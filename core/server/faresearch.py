"""Fare search: one search request, opened on each supplier's own portal by the user, with quotes typed in by hand.

Nothing here contacts a supplier. The app stores the portal's official address (never a password, OTP or cookie),
the search the user asked for, and the fares the user saw and entered. Amounts the user did not enter stay NULL and
are shown as "unavailable", never as 0. A quote is evidence of what was seen at one moment: it carries the time it was
seen and becomes stale after the configured number of minutes. Booking, ticketing and payment stay in the portal.
"""
import re
from datetime import datetime, timedelta
from urllib.parse import urlparse, parse_qsl

from . import validate as v
from .db import tx, now_iso, rows, one, get_setting, next_counter
from .errors import ApiError, ValidationError, NotFound
from .money import parse_amount
from .services import audit, BOOKING_STATUSES

TRIP_TYPES = ("one_way", "round_trip")
CABINS = ("Economy", "Premium Economy", "Business", "First")
SUPPLIER_STATUSES = ("pending", "opened", "searched", "quoted", "no_fare", "failed")
IATA_RE = re.compile(r"^[A-Z]{3}$")
# query keys that would put a secret into a saved address
SECRET_KEYS = re.compile(r"pass|pwd|otp|token|session|sid|auth|secret|key|cookie|jwt", re.I)
QUOTE_AMOUNTS = ("base_fare", "taxes", "fees", "payment_charges", "commission")
QUOTE_TEXT = (("airline", 60), ("flight_nos", 80), ("duration", 20), ("fare_family", 80), ("baggage", 120),
              ("refund_terms", 500), ("notes", 1000))


def enabled(conn):
    return get_setting(conn, "fare_search_enabled", "0") == "1"


def require_enabled(conn):
    if not enabled(conn):
        raise ApiError("Fare search is switched off. Turn it on in Settings -> Fare search.", status=403, code="feature_off")


def stale_minutes(conn):
    try:
        return max(1, int(get_setting(conn, "fare_stale_minutes", "15")))
    except ValueError:
        return 15


# ------------------------------------------------------------------ portals
def check_portal_url(value):
    url = (value or "").strip()
    if not url:
        raise ValidationError("Enter the portal's official sign-in address", "login_url")
    if len(url) > 500:
        raise ValidationError("The address is too long", "login_url")
    p = urlparse(url)
    if p.scheme != "https" or not p.hostname or "." not in p.hostname:
        raise ValidationError("Use the portal's https:// address, e.g. https://www.example.com/login", "login_url")
    if p.username or p.password or "@" in p.netloc:
        raise ValidationError("The address must not contain a username or password", "login_url")
    for k, _ in parse_qsl(p.query, keep_blank_values=True) + parse_qsl(p.fragment, keep_blank_values=True):
        if SECRET_KEYS.search(k):
            raise ValidationError("The address must not contain a password, OTP, token or session value. "
                                  "Copy the plain sign-in page address instead.", "login_url")
    return url


def list_portals(conn):
    return rows(conn.execute(
        "SELECT s.id AS supplier_id, s.name, s.kind, s.active AS supplier_active, p.login_url, p.search_hint, "
        "COALESCE(p.active, 0) AS active, p.sort_order, p.updated_at FROM suppliers s "
        "LEFT JOIN fare_portals p ON p.supplier_id = s.id ORDER BY p.login_url IS NULL, p.sort_order, s.name COLLATE NOCASE"))


def save_portal(conn, supplier_id, data):
    url = check_portal_url(data.get("login_url"))
    hint = v.s(data, "search_hint", 300, label="Search tip")
    active = 0 if data.get("active") in (False, 0, "0") else 1
    sort_order = v.i(data, "sort_order") or 0
    ts = now_iso()
    with tx(conn):
        if not conn.execute("SELECT 1 FROM suppliers WHERE id = ?", (supplier_id,)).fetchone():
            raise NotFound("Supplier not found")
        before = one(conn.execute("SELECT * FROM fare_portals WHERE supplier_id = ?", (supplier_id,)))
        conn.execute("INSERT INTO fare_portals(supplier_id, login_url, search_hint, active, sort_order, created_at, updated_at) "
                     "VALUES (?,?,?,?,?,?,?) ON CONFLICT(supplier_id) DO UPDATE SET login_url = excluded.login_url, "
                     "search_hint = excluded.search_hint, active = excluded.active, sort_order = excluded.sort_order, "
                     "updated_at = excluded.updated_at", (supplier_id, url, hint, active, sort_order, ts, ts))
        after = {"login_url": url, "search_hint": hint, "active": active, "sort_order": sort_order}
        audit(conn, "fare_portal", supplier_id, "update" if before else "create",
              {k: before[k] for k in after} if before else None, after)
    return supplier_id


def delete_portal(conn, supplier_id):
    with tx(conn):
        before = one(conn.execute("SELECT * FROM fare_portals WHERE supplier_id = ?", (supplier_id,)))
        if not before:
            raise NotFound("No portal address is saved for this supplier")
        conn.execute("DELETE FROM fare_portals WHERE supplier_id = ?", (supplier_id,))
        audit(conn, "fare_portal", supplier_id, "delete", before, None)
    return supplier_id


# ------------------------------------------------------------------ searches
def _optional_amount(data, key, label):
    raw = data.get(key)
    if raw is None or (isinstance(raw, str) and raw.strip() == ""):
        return None
    return parse_amount(raw, label)


def _search_payload(data):
    origin = (v.s(data, "origin", 3, True, "From") or "").upper()
    dest = (v.s(data, "destination", 3, True, "To") or "").upper()
    if not IATA_RE.match(origin):
        raise ValidationError("From must be a 3-letter airport code, e.g. COK", "origin")
    if not IATA_RE.match(dest):
        raise ValidationError("To must be a 3-letter airport code, e.g. DXB", "destination")
    if origin == dest:
        raise ValidationError("From and To must be different airports", "destination")
    trip = v.choice(data, "trip_type", TRIP_TYPES, "one_way", "Trip type")
    depart = v.d(data, "depart_date", True, "Departure date")
    ret = v.d(data, "return_date", trip == "round_trip", "Return date") if trip == "round_trip" else None
    if ret and ret < depart:
        raise ValidationError("Return date cannot be before the departure date", "return_date")
    adults = v.i(data, "adults", label="Adults")
    adults = 1 if adults is None else adults
    children = v.i(data, "children", label="Children") or 0
    infants = v.i(data, "infants", label="Infants") or 0
    if not 1 <= adults <= 9:
        raise ValidationError("Adults must be 1 to 9", "adults")
    if not 0 <= children <= 8:
        raise ValidationError("Children must be 0 to 8", "children")
    if not 0 <= infants <= adults or infants > 4:
        raise ValidationError("Each infant needs an adult (at most one infant per adult, 4 in all)", "infants")
    if adults + children > 9:
        raise ValidationError("At most 9 seated passengers in one search", "children")
    return {
        "origin": origin, "destination": dest, "trip_type": trip, "depart_date": depart, "return_date": ret,
        "adults": adults, "children": children, "infants": infants,
        "cabin": v.choice(data, "cabin", CABINS, "Economy", "Cabin"),
        "direct_only": 1 if v.b(data, "direct_only") else 0,
        "baggage": v.s(data, "baggage", 120, label="Baggage"),
        "selling_price": _optional_amount(data, "selling_price", "Selling price"),
        "notes": v.s(data, "notes", 1000, label="Notes"),
    }


def create_search(conn, data):
    require_enabled(conn)
    p = _search_payload(data)
    idem = v.s(data, "idem_key", 64)
    supplier_ids = data.get("supplier_ids") or []
    if not isinstance(supplier_ids, list) or not supplier_ids:
        raise ValidationError("Choose at least one supplier portal", "supplier_ids")
    ts = now_iso()
    with tx(conn):
        if idem:
            hit = conn.execute("SELECT id FROM fare_searches WHERE idem_key = ?", (idem,)).fetchone()
            if hit:
                return hit["id"]
        sids = []
        for raw in supplier_ids:
            try:
                sid = int(raw)
            except (TypeError, ValueError):
                raise ValidationError("Unknown supplier", "supplier_ids")
            if not conn.execute("SELECT 1 FROM fare_portals p JOIN suppliers s ON s.id = p.supplier_id "
                                "WHERE p.supplier_id = ? AND p.active = 1 AND s.active = 1", (sid,)).fetchone():
                raise ValidationError("A chosen supplier has no active portal address. Add it in Settings -> Fare search.",
                                      "supplier_ids")
            if sid not in sids:
                sids.append(sid)
        no = "FS-%06d" % next_counter(conn, "fare_search")
        cols = list(p)
        cur = conn.execute("INSERT INTO fare_searches(search_no, %s, idem_key, created_at, updated_at) VALUES (?, %s, ?, ?, ?)" % (
            ", ".join(cols), ", ".join("?" * len(cols))), [no] + [p[c] for c in cols] + [idem, ts, ts])
        fid = cur.lastrowid
        for sid in sids:
            conn.execute("INSERT INTO fare_search_suppliers(search_id, supplier_id, status, updated_at) VALUES (?, ?, 'pending', ?)",
                         (fid, sid, ts))
        audit(conn, "fare_search", fid, "create", None, dict(p, search_no=no, supplier_ids=sids))
    return fid


def update_search(conn, fid, data):
    require_enabled(conn)
    with tx(conn):
        before = one(conn.execute("SELECT * FROM fare_searches WHERE id = ?", (fid,)))
        if not before:
            raise NotFound("Fare search not found")
        changes = {}
        if "selling_price" in data:
            changes["selling_price"] = _optional_amount(data, "selling_price", "Selling price")
        if "notes" in data:
            changes["notes"] = v.s(data, "notes", 1000, label="Notes")
        if "status" in data:
            changes["status"] = v.choice(data, "status", ("open", "closed"), "open", "Status")
        if changes:
            conn.execute("UPDATE fare_searches SET %s, updated_at = ? WHERE id = ?" % ", ".join("%s = ?" % k for k in changes),
                         list(changes.values()) + [now_iso(), fid])
            audit(conn, "fare_search", fid, "update", {k: before[k] for k in changes}, changes)
    return get_search(conn, fid)


def delete_search(conn, fid):
    require_enabled(conn)
    with tx(conn):
        before = one(conn.execute("SELECT * FROM fare_searches WHERE id = ?", (fid,)))
        if not before:
            raise NotFound("Fare search not found")
        n = conn.execute("SELECT COUNT(*) FROM fare_quotes WHERE search_id = ?", (fid,)).fetchone()[0]
        conn.execute("DELETE FROM fare_searches WHERE id = ?", (fid,))
        audit(conn, "fare_search", fid, "delete", dict(before, quotes=n), None)
    return fid


def set_supplier_status(conn, fid, sid, data):
    require_enabled(conn)
    status = v.choice(data, "status", SUPPLIER_STATUSES, None, "Status")
    note = v.s(data, "note", 300, label="Note")
    with tx(conn):
        before = one(conn.execute("SELECT * FROM fare_search_suppliers WHERE search_id = ? AND supplier_id = ?", (fid, sid)))
        if not before:
            raise NotFound("This supplier is not part of the search")
        conn.execute("UPDATE fare_search_suppliers SET status = ?, note = ?, updated_at = ? WHERE search_id = ? AND supplier_id = ?",
                     (status, note, now_iso(), fid, sid))
    return {"status": status, "note": note}


def list_searches(conn, limit=50):
    return rows(conn.execute(
        "SELECT f.id, f.search_no, f.origin, f.destination, f.trip_type, f.depart_date, f.return_date, f.adults, f.children, "
        "f.infants, f.cabin, f.status, f.selling_price, f.created_at, "
        "(SELECT COUNT(*) FROM fare_quotes q WHERE q.search_id = f.id) AS quotes, "
        "(SELECT MIN(final_payable) FROM fare_quotes q WHERE q.search_id = f.id) AS lowest_payable "
        "FROM fare_searches f ORDER BY f.id DESC LIMIT ?", (min(int(limit or 50), 500),)))


def _age_seconds(seen_at, now):
    try:
        return max(0, int((now - datetime.fromisoformat(seen_at)).total_seconds()))
    except ValueError:
        return None


def quote_view(q, selling_price, stale_after, now):
    """Derived figures for one quote. Profit is only shown when both prices are known; missing optional
    amounts are listed so the screen can say what the estimate leaves out."""
    q = dict(q)
    age = _age_seconds(q["seen_at"], now)
    q["age_seconds"] = age
    q["stale"] = age is None or age >= stale_after * 60
    missing = [k for k in ("base_fare", "taxes", "fees", "payment_charges", "commission") if q.get(k) is None]
    q["missing"] = missing
    if selling_price is None:
        q["est_profit"] = None
        q["profit_note"] = "Enter your selling price to see profit"
    else:
        q["est_profit"] = selling_price - q["final_payable"] - (q["payment_charges"] or 0) + (q["commission"] or 0)
        parts = []
        if q["payment_charges"] is None:
            parts.append("payment charges not entered (counted as 0)")
        if q["commission"] is None:
            parts.append("no confirmed commission entered")
        q["profit_note"] = "; ".join(parts)
    return q


def get_search(conn, fid):
    f = one(conn.execute("SELECT * FROM fare_searches WHERE id = ?", (fid,)))
    if not f:
        raise NotFound("Fare search not found")
    stale_after = stale_minutes(conn)
    now = datetime.now()
    sups = rows(conn.execute(
        "SELECT fs.supplier_id, fs.status, fs.note, fs.updated_at, s.name, p.login_url, p.search_hint, "
        "COALESCE(p.active, 0) AS portal_active FROM fare_search_suppliers fs JOIN suppliers s ON s.id = fs.supplier_id "
        "LEFT JOIN fare_portals p ON p.supplier_id = fs.supplier_id WHERE fs.search_id = ? "
        "ORDER BY COALESCE(p.sort_order, 0), s.name COLLATE NOCASE", (fid,)))
    quotes = [quote_view(q, f["selling_price"], stale_after, now) for q in conn.execute(
        "SELECT q.*, s.name AS supplier_name FROM fare_quotes q JOIN suppliers s ON s.id = q.supplier_id "
        "WHERE q.search_id = ? ORDER BY q.final_payable, q.id", (fid,))]
    rechecked = {q["recheck_of"] for q in quotes if q["recheck_of"]}
    for q in quotes:
        q["superseded"] = q["id"] in rechecked
    return {"search": f, "suppliers": sups, "quotes": quotes, "stale_minutes": stale_after, "server_time": now_iso()}


# ------------------------------------------------------------------ quotes
def _quote_payload(conn, data, search):
    sid = v.i(data, "supplier_id", True, "Supplier")
    if not conn.execute("SELECT 1 FROM fare_search_suppliers WHERE search_id = ? AND supplier_id = ?", (search["id"], sid)).fetchone():
        raise ValidationError("This supplier is not part of the search", "supplier_id")
    p = {"supplier_id": sid}
    for key, n in QUOTE_TEXT:
        p[key] = v.s(data, key, n)
    p["flight_nos"] = v.norm_code(p["flight_nos"], 80) if p["flight_nos"] else None
    p["dep_at"] = v.dt(data, "dep_at", "Departure time")
    p["arr_at"] = v.dt(data, "arr_at", "Arrival time")
    if p["dep_at"] and p["arr_at"] and p["arr_at"] < p["dep_at"]:
        raise ValidationError("Arrival cannot be before departure", "arr_at")
    p["stops"] = v.i(data, "stops", label="Stops")
    if p["stops"] is not None and not 0 <= p["stops"] <= 5:
        raise ValidationError("Stops must be 0 to 5", "stops")
    for key in QUOTE_AMOUNTS:
        p[key] = _optional_amount(data, key, key.replace("_", " ").capitalize())
    p["final_payable"] = parse_amount(data.get("final_payable"), "Final payable", required=True, allow_zero=False)
    seen = v.dt(data, "seen_at", "Time seen in portal")
    now = datetime.now().replace(second=0, microsecond=0)
    if seen:
        when = datetime.fromisoformat(seen if "T" in seen else seen + "T00:00")
        if when > now + timedelta(minutes=2):
            raise ValidationError("The time seen cannot be in the future", "seen_at")
        if when < now - timedelta(days=7):
            raise ValidationError("That quote is more than 7 days old. Search the portal again.", "seen_at")
        p["seen_at"] = when.isoformat()
    else:
        p["seen_at"] = now_iso()
    return p


def add_quote(conn, fid, data, source="manual", recheck_of=None):
    require_enabled(conn)
    idem = v.s(data, "idem_key", 64)
    with tx(conn):
        search = one(conn.execute("SELECT * FROM fare_searches WHERE id = ?", (fid,)))
        if not search:
            raise NotFound("Fare search not found")
        if idem:
            hit = conn.execute("SELECT id FROM fare_quotes WHERE idem_key = ?", (idem,)).fetchone()
            if hit:
                return hit["id"]
        p = _quote_payload(conn, data, search)
        no = "FQ-%06d" % next_counter(conn, "fare_quote")
        cols = list(p)
        cur = conn.execute("INSERT INTO fare_quotes(quote_no, search_id, %s, source, recheck_of, idem_key, created_at) "
                           "VALUES (?, ?, %s, ?, ?, ?, ?)" % (", ".join(cols), ", ".join("?" * len(cols))),
                           [no, fid] + [p[c] for c in cols] + [source, recheck_of, idem, now_iso()])
        conn.execute("UPDATE fare_search_suppliers SET status = 'quoted', updated_at = ? WHERE search_id = ? AND supplier_id = ?",
                     (now_iso(), fid, p["supplier_id"]))
        audit(conn, "fare_quote", cur.lastrowid, "create", None, dict(p, quote_no=no, search_id=fid, source=source))
    return cur.lastrowid


def get_quote(conn, qid):
    q = one(conn.execute("SELECT q.*, s.name AS supplier_name FROM fare_quotes q JOIN suppliers s ON s.id = q.supplier_id "
                         "WHERE q.id = ?", (qid,)))
    if not q:
        raise NotFound("Quote not found")
    f = one(conn.execute("SELECT * FROM fare_searches WHERE id = ?", (q["search_id"],)))
    return quote_view(q, f["selling_price"], stale_minutes(conn), datetime.now())


def delete_quote(conn, qid):
    require_enabled(conn)
    with tx(conn):
        before = one(conn.execute("SELECT * FROM fare_quotes WHERE id = ?", (qid,)))
        if not before:
            raise NotFound("Quote not found")
        if conn.execute("SELECT 1 FROM fare_quotes WHERE recheck_of = ?", (qid,)).fetchone():
            raise ValidationError("A re-check was recorded from this quote, so it is kept as evidence", "id")
        conn.execute("DELETE FROM fare_quotes WHERE id = ?", (qid,))
        audit(conn, "fare_quote", qid, "delete", before, None)
    return qid


def recheck_quote(conn, qid, data):
    """Record the fare the user sees again in the portal right before booking. The old quote is kept; the new one
    carries the current time, so the booking draft never uses an old fare as if it were live."""
    require_enabled(conn)
    if not v.b(data, "confirmed"):
        raise ValidationError("Tick the box to confirm you re-checked this fare in the supplier's portal just now", "confirmed")
    old = one(conn.execute("SELECT * FROM fare_quotes WHERE id = ?", (qid,)))
    if not old:
        raise NotFound("Quote not found")
    fresh = {k: old[k] for k in ("supplier_id", "airline", "flight_nos", "dep_at", "arr_at", "stops", "duration",
                                  "fare_family", "baggage", "refund_terms", "notes")}
    for k in QUOTE_AMOUNTS:
        fresh[k] = "" if old[k] is None else "%d.%02d" % divmod(old[k], 100)
    fresh.update({k: data[k] for k in ("final_payable", "payment_charges", "commission", "fees", "taxes", "base_fare",
                                       "fare_family", "baggage", "refund_terms") if k in data})
    fresh["idem_key"] = data.get("idem_key")
    fresh["seen_at"] = None
    nid = add_quote(conn, old["search_id"], fresh, source="recheck", recheck_of=qid)
    q = get_quote(conn, nid)
    q["changed_from"] = old["final_payable"] if old["final_payable"] != q["final_payable"] else None
    return q


def possible_duplicates(conn, qid):
    """Existing live bookings on the same route and departure day, so a fare is not booked twice by mistake."""
    q = one(conn.execute("SELECT search_id FROM fare_quotes WHERE id = ?", (qid,)))
    if not q:
        raise NotFound("Quote not found")
    f = one(conn.execute("SELECT * FROM fare_searches WHERE id = ?", (q["search_id"],)))
    live = [s for s in BOOKING_STATUSES if s not in ("cancelled", "refunded")]
    return rows(conn.execute(
        "SELECT DISTINCT b.id, b.ref, b.pnr, b.status, b.travel_date, c.name AS customer_name FROM bookings b "
        "JOIN customers c ON c.id = b.customer_id JOIN booking_segments g ON g.booking_id = b.id "
        "WHERE g.origin = ? COLLATE NOCASE AND g.destination = ? COLLATE NOCASE AND substr(g.dep_at, 1, 10) = ? "
        "AND b.status IN (%s) ORDER BY b.id DESC LIMIT 20" % ",".join("?" * len(live)),
        [f["origin"], f["destination"], f["depart_date"]] + live))
