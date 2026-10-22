"""Flight ticket / itinerary documents.

ticket_data() is the single source for everything shown on a ticket: the PDF below and the
WhatsApp messages in static/js/whatsapp.js both use it, so they always agree. Only saved
booking data is used - missing details are left out, never guessed.
"""
import os
import re
from datetime import datetime
from pathlib import Path

from . import pdfkit
from .db import all_settings
from .errors import NotFound
from .finance import Ledger
from .money import fmt

STATUS_LABELS = {"enquiry": "Enquiry - not confirmed", "on_hold": "On hold - not confirmed", "confirmed": "Confirmed",
                 "ticketed": "Ticketed", "cancelled": "Cancelled", "refunded": "Refunded"}
DOC_TITLES = {"ticketed": "E-TICKET", "confirmed": "BOOKING CONFIRMATION", "enquiry": "ITINERARY (NOT CONFIRMED)",
              "on_hold": "ITINERARY (ON HOLD)", "cancelled": "CANCELLED BOOKING", "refunded": "CANCELLED BOOKING"}
PAX_TYPES = {"ADT": "Adult", "CHD": "Child", "INF": "Infant"}
TRIP_TYPES = {"one_way": "One way", "round_trip": "Return", "multi_city": "Multi-city"}
DIRECTIONS = {"outbound": "Onward journey", "return": "Return journey", "other": "Journey"}

# City names for common airport codes. Unknown codes are shown as the code only.
AIRPORTS = {
    "COK": "Kochi", "TRV": "Thiruvananthapuram", "CCJ": "Kozhikode", "CNN": "Kannur", "BLR": "Bengaluru", "MAA": "Chennai",
    "HYD": "Hyderabad", "BOM": "Mumbai", "DEL": "Delhi", "CCU": "Kolkata", "AMD": "Ahmedabad", "GOI": "Goa", "GOX": "Goa (Mopa)",
    "PNQ": "Pune", "IXE": "Mangaluru", "CJB": "Coimbatore", "TRZ": "Tiruchirappalli", "IXM": "Madurai", "LKO": "Lucknow",
    "JAI": "Jaipur", "ATQ": "Amritsar", "VNS": "Varanasi", "GAU": "Guwahati", "BBI": "Bhubaneswar", "IXZ": "Port Blair",
    "JED": "Jeddah", "RUH": "Riyadh", "DMM": "Dammam", "MED": "Madinah", "TIF": "Taif", "AHB": "Abha", "TUU": "Tabuk",
    "GIZ": "Jizan", "ELQ": "Qassim", "HAS": "Hail", "YNB": "Yanbu", "DXB": "Dubai", "DWC": "Dubai World Central",
    "AUH": "Abu Dhabi", "SHJ": "Sharjah", "RKT": "Ras Al Khaimah", "DOH": "Doha", "MCT": "Muscat", "SLL": "Salalah",
    "KWI": "Kuwait", "BAH": "Bahrain", "CMB": "Colombo", "MLE": "Male", "KTM": "Kathmandu", "DAC": "Dhaka",
    "SIN": "Singapore", "KUL": "Kuala Lumpur", "BKK": "Bangkok", "HKG": "Hong Kong", "IST": "Istanbul", "SAW": "Istanbul (Sabiha)",
    "CAI": "Cairo", "AMM": "Amman", "LHR": "London Heathrow", "LGW": "London Gatwick", "MAN": "Manchester",
    "CDG": "Paris", "FRA": "Frankfurt", "AMS": "Amsterdam", "JFK": "New York", "YYZ": "Toronto", "SYD": "Sydney",
    "MEL": "Melbourne", "CHC": "Christchurch", "AKL": "Auckland", "NBO": "Nairobi", "ADD": "Addis Ababa",
}

PAYMENT_KEYS = ("pay_account_name", "pay_account_number", "pay_ifsc", "pay_branch", "pay_bank_name", "pay_upi_number", "pay_upi_apps")


def city(code):
    return AIRPORTS.get((code or "").upper().strip(), "")


def _dt(s):
    if not s:
        return None
    for f in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s[:19], f)
        except ValueError:
            continue
    return None


def _date_label(s):
    d = _dt(s)
    return d.strftime("%a, %d %b %Y") if d else ""


def _time_label(s):
    d = _dt(s)
    return d.strftime("%H:%M") if d and len(s) > 10 else ""


def _minutes(a, b):
    da, db = _dt(a), _dt(b)
    if not da or not db or len(a) <= 10 or len(b) <= 10:
        return None
    m = int((db - da).total_seconds() // 60)
    return m if 0 < m < 7 * 24 * 60 else None


def _dur(m):
    return "%dh %02dm" % (m // 60, m % 60) if m >= 60 else "%dm" % m


def payment_details(settings):
    return {k[4:]: (settings.get(k) or "").strip() for k in PAYMENT_KEYS}


def _logo_bytes(conn, name):
    """The agency's logo from its own company folder (next to its database), or None."""
    if not name or "/" in name or "\\" in name or not name.startswith("logo-"):
        return None
    try:
        db_file = conn.execute("PRAGMA database_list").fetchone()[2]
        path = Path(db_file).parent / "branding" / name
        return path.read_bytes() if path.is_file() else None
    except (OSError, TypeError, IndexError):
        return None


def _shade(hex_color, factor):
    """Darken (factor < 1) a #RRGGBB colour."""
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02X%02X%02X" % tuple(max(0, min(255, int(v * factor))) for v in (r, g, b))


def ticket_data(conn, bid):
    L = Ledger(conn)
    b = L.bookings.get(int(bid))
    if not b:
        raise NotFound("Booking not found")
    s = all_settings(conn)
    calc = b["calc"]
    cust = L.customers.get(b["customer_id"]) or {}
    segs = L.segments.get(b["id"], [])
    pax = L.passengers.get(b["id"], [])

    # group segments into journeys (onward / return / other), with layovers between connecting flights
    journeys = []
    for direction in ("outbound", "return", "other"):
        group = [x for x in segs if x["direction"] == direction]
        if not group:
            continue
        legs = []
        for i, x in enumerate(group):
            leg = {
                "airline": x["airline"] or "", "flight_no": x["flight_no"] or "", "airline_pnr": x["airline_pnr"] or "",
                "origin": (x["origin"] or "").upper(), "origin_city": city(x["origin"]),
                "destination": (x["destination"] or "").upper(), "destination_city": city(x["destination"]),
                "dep_at": x["dep_at"] or "", "dep_date": _date_label(x["dep_at"]), "dep_time": _time_label(x["dep_at"]),
                "arr_at": x["arr_at"] or "", "arr_date": _date_label(x["arr_at"]), "arr_time": _time_label(x["arr_at"]),
                "cabin": x["cabin"] or b["cabin"] or "", "baggage": x["baggage"] or b["baggage"] or "", "layover": None,
            }
            if i + 1 < len(group):
                nxt = group[i + 1]
                if (x["destination"] or "").upper() == (nxt["origin"] or "").upper() and x["destination"]:
                    m = _minutes(x["arr_at"], nxt["dep_at"])
                    leg["layover"] = {"airport": leg["destination"], "city": leg["destination_city"], "duration": _dur(m) if m else ""}
            legs.append(leg)
        journeys.append({"direction": direction, "label": DIRECTIONS[direction], "legs": legs,
                         "origin": legs[0]["origin"], "origin_city": legs[0]["origin_city"],
                         "destination": legs[-1]["destination"], "destination_city": legs[-1]["destination_city"],
                         "stops": len(legs) - 1})

    ticketed_on = ""
    for h in conn.execute("SELECT at, detail FROM booking_history WHERE booking_id = ? AND action IN ('status','created') ORDER BY id", (b["id"],)):
        if re.search(r"(-> ticketed|status ticketed)$", h["detail"] or ""):
            ticketed_on = h["at"][:10]
    total = calc["customer_payable"] - calc["customer_credits"]
    received = calc["received"] - calc["refunded"]
    pending = calc["customer_outstanding"]
    committed = bool(b["committed"])
    if not committed:
        pay_status, pay_label = "not_billed", "Not billed yet"
    elif pending <= 0:
        pay_status, pay_label = "paid", "Fully paid"
    elif received > 0:
        pay_status, pay_label = "partial", "Partly paid"
    else:
        pay_status, pay_label = "unpaid", "Payment pending"
    return {
        "business": {"name": s.get("business_name") or "Travel Agency", "tagline": s.get("business_tagline") or "",
                     "phone": s.get("business_phone") or "", "email": s.get("business_email") or "",
                     "address": s.get("business_address") or "", "website": s.get("business_website") or "",
                     "gstin": s.get("business_gstin") or "", "brand_color": s.get("brand_color") or "",
                     "logo": _logo_bytes(conn, s.get("logo_file") or "")},
        "booking": {"id": b["id"], "ref": b["ref"], "pnr": b["pnr"] or "", "status": b["status"], "status_label": STATUS_LABELS[b["status"]],
                    "doc_title": DOC_TITLES[b["status"]], "is_ticketed": b["status"] == "ticketed",
                    "is_confirmed": b["status"] in ("confirmed", "ticketed"), "is_cancelled": b["status"] in ("cancelled", "refunded"),
                    "service_type": b["service_type"], "trip_type": b["trip_type"], "trip_label": TRIP_TYPES.get(b["trip_type"], ""),
                    "booking_date": b["booking_date"], "booking_date_label": _date_label(b["booking_date"]),
                    "ticketed_on": ticketed_on, "ticketed_on_label": _date_label(ticketed_on),
                    "travel_date": b["travel_date"] or "", "return_date": b["return_date"] or "",
                    "cabin": b["cabin"] or "", "baggage": b["baggage"] or ""},
        "customer": {"id": cust.get("id"), "name": cust.get("name") or "", "phone": cust.get("phone") or "", "alt_phone": cust.get("alt_phone") or ""},
        "passengers": [{"title": p["title"] or "", "name": p["name"], "display_name": pax_name(p),
                        "type": PAX_TYPES.get(p["pax_type"], p["pax_type"]), "ticket_number": p["ticket_number"] or ""} for p in pax],
        "journeys": journeys,
        "payment": {"currency": L.base, "total": total, "received": received, "pending": max(pending, 0), "credit": max(-pending, 0),
                    "status": pay_status, "status_label": pay_label, "committed": committed},
        "payment_details": payment_details(s),
    }


def pax_name(p):
    """Title + name, without repeating a title already typed into the name (e.g. "MR. ...")."""
    title = (p.get("title") or "").strip()
    if title and not re.match(r"^(MR|MRS|MS|MISS|MSTR|MASTER|DR|%s)\b\.?\s" % re.escape(title.rstrip(".")), p["name"].strip() + " ", re.I):
        return title + " " + p["name"]
    return p["name"]


def file_name(td, variant):
    b = td["booking"]
    lead = td["passengers"][0]["name"] if td["passengers"] else td["customer"]["name"]
    parts = ["Booking", b["ref"], b["pnr"], lead, "Ticket+Payment" if variant == "payment" else "Ticket"]
    safe = [re.sub(r"[^A-Za-z0-9+-]+", "-", p).strip("-")[:40] for p in parts if p]
    return "_".join(x for x in safe if x) + ".pdf"


def money(minor, cur="INR"):
    return "%s %s" % (cur, _indian(fmt(minor)) if cur == "INR" else fmt(minor))


def _indian(s):
    neg = s.startswith("-")
    s = s.lstrip("-")
    whole, _, frac = s.partition(".")
    whole = whole.replace(",", "")
    if len(whole) > 3:
        head, tail = whole[:-3], whole[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        whole = ",".join(groups + [tail])
    return ("-" if neg else "") + whole + ("." + frac if frac else "")


# ======================================================================== PDF
NAVY, NAVY2, ROYAL, SKY, SKY_LIGHT, INK, MUTED, LINE, BG = ("#0A1A3F", "#0F2457", "#2350E6", "#35C9EA", "#E6F7FD",
                                                          "#0D1833", "#64738F", "#E2E8F2", "#F5F8FD")
STATUS_COLORS = {"ticketed": ("#0E9F6B", "#E3F6EE"), "confirmed": ("#2350E6", "#EAF0FF"), "enquiry": ("#C27A06", "#FDF3DF"),
                 "on_hold": ("#C27A06", "#FDF3DF"), "cancelled": ("#D33447", "#FDE9EC"), "refunded": ("#D33447", "#FDE9EC")}
PAY_COLORS = {"paid": ("#0E9F6B", "#E3F6EE"), "partial": ("#C27A06", "#FDF3DF"), "unpaid": ("#D33447", "#FDE9EC"),
              "not_billed": ("#64738F", "#EEF2F9")}
W, H = pdfkit.A4
M = 34  # page margin


class _Layout:
    def __init__(self, td):
        self.td = td
        self.doc = pdfkit.Document(title="%s %s" % (td["booking"]["doc_title"].title(), td["booking"]["pnr"] or td["booking"]["ref"]),
                                   author=td["business"]["name"])
        self.page = None
        self.y = 0

    def logo(self, p, x, y, size=40, on_dark=True):
        """The agency's own logo on a white tile when one is set; otherwise the neutral plane mark."""
        logo = self.td["business"].get("logo")
        img = self.doc.add_image(logo) if logo else None
        if img:
            name, iw, ih = img
            p.rect(x, y, size, size, fill="#FFFFFF", r=size * 0.18)
            pad = size * 0.1
            scale = min((size - 2 * pad) / iw, (size - 2 * pad) / ih)
            w, h = iw * scale, ih * scale
            p.image(name, x + (size - w) / 2, y + (size - h) / 2, w, h)
            return
        p.gradient_rect(x, y, size, size, SKY if on_dark else ROYAL, ROYAL if on_dark else NAVY, r=size * 0.25)
        p.plane(x + size * 0.5, y + size * 0.5, size * 0.62, "#FFFFFF", angle=0)
        p.circle(x + size * 0.76, y + size * 0.27, size * 0.07, fill=SKY if not on_dark else "#FFFFFF")

    def header(self, title, sub, compact=False):
        td = self.td
        p = self.page = self.doc.new_page()
        hh = 70 if compact else 104
        top = H - hh
        brand = td["business"].get("brand_color") or ""
        if re.match(r"^#[0-9A-Fa-f]{6}$", brand):
            p.gradient_rect(0, top, W, hh, _shade(brand, 0.55), _shade(brand, 0.8))
        else:
            p.gradient_rect(0, top, W, hh, NAVY, NAVY2)
        p.rect(0, top - 4, W, 4, fill=SKY)
        # sky "route" arc in the band - subtle aviation motif
        p.line(W - 250, top + 18, W - 30, top + hh - 14, color="#1D3A7A", lw=1.2, dash=(2, 5))
        p.plane(W - 40, top + hh - 18, 14, "#2A4D9A", angle=28)
        size = 34 if compact else 46
        self.logo(p, M, top + (hh - size) / 2, size)
        x = M + size + 12
        name = td["business"]["name"].upper()
        p.text(x, top + hh / 2 + (2 if compact else 6), name, 17 if compact else 23, "B", "#FFFFFF", spacing=1.2)
        tag = td["business"]["tagline"] or td["business"]["website"] or ""
        p.text(x, top + hh / 2 - (11 if compact else 12), tag, 8 if compact else 9, "R", SKY)
        p.text(W - M, top + hh / 2 + (2 if compact else 8), title, 12 if compact else 15, "B", "#FFFFFF", align="right", spacing=0.8)
        if sub:
            p.text(W - M, top + hh / 2 - (11 if compact else 9), sub, 8.5, "R", "#B9C7E6", align="right")
        self.y = top - 22

    def need(self, h, cont_title):
        if self.y - h < 60:
            self.footer()
            self.header(cont_title, "%s  |  PNR %s" % (self.td["booking"]["ref"], self.td["booking"]["pnr"] or "-"), compact=True)

    def footer(self):
        p, td = self.page, self.td
        biz = td["business"]
        p.line(M, 46, W - M, 46, LINE)
        contact = "  |  ".join(x for x in (biz["phone"], biz["email"]) if x)
        p.text(M, 32, biz["name"].upper() + (("  |  GSTIN " + biz["gstin"]) if biz.get("gstin") else ""), 8, "B", NAVY)
        if contact:
            p.text(M, 21, contact, 7.5, "R", MUTED)
        p.text(W - M, 32, "Generated %s with AirZelo" % datetime.now().strftime("%d %b %Y, %H:%M"), 7, "R", MUTED, align="right")
        p.text(W - M, 21, "Page %d" % len(self.doc.pages), 7, "R", MUTED, align="right")

    def section(self, label):
        p = self.page
        p.rect(M, self.y - 2, 3, 12, fill=SKY)
        p.text(M + 9, self.y, label.upper(), 9, "B", NAVY, spacing=0.8)
        self.y -= 14

    # ------------------------------------------------------------------ blocks
    def summary_cards(self):
        p, b = self.page, self.td["booking"]
        cards = [("PNR / BOOKING REFERENCE", b["pnr"] or "Not issued yet", 17 if b["pnr"] else 11),
                 ("BOOKING REF", b["ref"], 12),
                 ("STATUS", b["status_label"], 10.5),
                 ("TICKET ISSUED" if b["ticketed_on"] else "BOOKED ON", b["ticketed_on_label"] or b["booking_date_label"], 10)]
        gap, cw, ch = 8, (W - 2 * M - 24) / 4, 50
        x = M
        for i, (lbl, val, size) in enumerate(cards):
            p.rect(x, self.y - ch, cw, ch, fill=BG if i else NAVY, stroke=None if i == 0 else LINE, r=8)
            p.text(x + 10, self.y - 15, lbl, 6.8, "B", SKY if i == 0 else MUTED, spacing=0.4)
            if i == 2:
                fg, bg = STATUS_COLORS[b["status"]]
                tw = pdfkit.text_width(val, size, "B") + 16
                p.rect(x + 9, self.y - 41, min(tw, cw - 18), 18, fill=bg, r=9)
                p.text(x + 17, self.y - 35.5, val, size if tw <= cw - 18 else 8, "B", fg)
            else:
                p.text(x + 10, self.y - 36, val, size, "B", "#FFFFFF" if i == 0 else INK, spacing=1.5 if i == 0 else 0)
            x += cw + gap
        self.y -= ch + 18

    def notice(self, text, fg, bg):
        p = self.page
        lines = pdfkit.wrap(text, 8.8, W - 2 * M - 30, "B")
        h = 12 + 12 * len(lines)
        p.rect(M, self.y - h, W - 2 * M, h, fill=bg, r=7)
        p.rect(M, self.y - h, 4, h, fill=fg, r=2)
        yy = self.y - 15
        for ln in lines:
            p.text(M + 14, yy, ln, 8.8, "B", fg)
            yy -= 12
        self.y -= h + 14

    def journey(self, j):
        legs = j["legs"]
        rows = sum(1 for _ in legs)
        h = 98 + 70 * rows + sum(16 for l in legs if l["layover"])
        self.need(h + 20, "E-TICKET (CONTINUED)")
        p = self.page
        top = self.y
        p.rect(M, top - h, W - 2 * M, h, fill="#FFFFFF", stroke=LINE, r=10)
        # title strip
        p.rect(M, top - 24, W - 2 * M, 24, fill=SKY_LIGHT, r=10)
        p.rect(M, top - 24, W - 2 * M, 10, fill=SKY_LIGHT)
        p.text(M + 12, top - 16, j["label"].upper(), 8.5, "B", NAVY, spacing=0.6)
        stops = "Non-stop" if j["stops"] == 0 else "%d stop%s" % (j["stops"], "s" if j["stops"] > 1 else "")
        p.text(W - M - 12, top - 16, stops + ("  |  " + legs[0]["dep_date"] if legs[0]["dep_date"] else ""), 8.5, "B", ROYAL, align="right")
        # big route graphic
        yy = top - 58
        p.text(M + 18, yy, j["origin"] or "---", 26, "B", NAVY, spacing=1)
        p.text(M + 18, yy - 14, j["origin_city"], 8.5, "R", MUTED)
        p.text(W - M - 18, yy, j["destination"] or "---", 26, "B", NAVY, align="right", spacing=1)
        p.text(W - M - 18, yy - 14, j["destination_city"], 8.5, "R", MUTED, align="right")
        lx1, lx2 = M + 110, W - M - 110
        p.circle(lx1, yy + 9, 3, fill=SKY)
        p.circle(lx2, yy + 9, 3, fill=NAVY)
        p.line(lx1 + 6, yy + 9, lx2 - 6, yy + 9, SKY, 1.4, dash=(4, 4))
        mid = (lx1 + lx2) / 2
        p.circle(mid, yy + 9, 14, fill="#FFFFFF", stroke=SKY, lw=1.2)
        p.plane(mid + 1, yy + 8, 21, ROYAL, angle=-18)
        self.y = top - 92
        for leg in legs:
            self.leg(leg)
        self.y = top - h - 18

    def leg(self, leg):
        p = self.page
        x0, y = M + 14, self.y
        p.line(M + 10, y + 6, W - M - 10, y + 6, LINE, 0.6)
        flight = " ".join(x for x in (leg["airline"], leg["flight_no"]) if x) or "Flight details not recorded"
        p.text(x0, y - 8, flight, 10, "B", INK)
        extra = "  |  ".join(x for x in (leg["cabin"], ("Airline PNR " + leg["airline_pnr"]) if leg["airline_pnr"] else "") if x)
        if extra:
            p.text(W - M - 14, y - 8, extra, 8, "R", MUTED, align="right")
        cols = (W - 2 * M - 28) / 3
        # departure
        p.text(x0, y - 24, "DEPARTURE", 6.8, "B", MUTED, spacing=0.4)
        p.text(x0, y - 38, (leg["dep_time"] + "  " if leg["dep_time"] else "") + leg["origin"], 12, "B", NAVY)
        p.text(x0, y - 50, leg["dep_date"] or "Date not recorded", 8, "R", INK)
        if leg["origin_city"]:
            p.text(x0, y - 60, leg["origin_city"], 7.5, "R", MUTED)
        # arrival
        x1 = x0 + cols
        p.text(x1, y - 24, "ARRIVAL", 6.8, "B", MUTED, spacing=0.4)
        if leg["arr_at"]:
            p.text(x1, y - 38, (leg["arr_time"] + "  " if leg["arr_time"] else "") + leg["destination"], 12, "B", NAVY)
            p.text(x1, y - 50, leg["arr_date"], 8, "R", INK)
        else:
            p.text(x1, y - 38, leg["destination"], 12, "B", NAVY)
            p.text(x1, y - 50, "Time not recorded", 8, "R", MUTED)
        if leg["destination_city"]:
            p.text(x1, y - 60, leg["destination_city"], 7.5, "R", MUTED)
        # baggage
        x2 = x0 + 2 * cols
        p.text(x2, y - 24, "BAGGAGE", 6.8, "B", MUTED, spacing=0.4)
        bag = pdfkit.wrap(leg["baggage"] or "As per airline rules", 8.5, cols - 4, "B" if leg["baggage"] else "R")
        yy = y - 37
        for ln in bag[:3]:
            p.text(x2, yy, ln, 8.5, "B" if leg["baggage"] else "R", INK if leg["baggage"] else MUTED)
            yy -= 11
        self.y = y - 70
        if leg["layover"]:
            lo = leg["layover"]
            txt = "Layover at %s%s%s" % (lo["airport"], " (" + lo["city"] + ")" if lo["city"] else "", "  |  " + lo["duration"] if lo["duration"] else "")
            tw = pdfkit.text_width(txt, 8, "B") + 20
            p.rect(M + 14, self.y - 6, tw, 15, fill="#FDF3DF", r=7)
            p.text(M + 24, self.y - 1.5, txt, 8, "B", "#9A5F00")
            self.y -= 16

    def passengers(self):
        pax = self.td["passengers"]
        self.need(46 + 20 * min(max(len(pax), 1), 3), "E-TICKET (CONTINUED)")
        self.section("Passengers (%d)" % len(pax))
        p = self.page
        cols = [(M, "#", 22), (M + 22, "PASSENGER NAME", 250), (M + 272, "TYPE", 70), (M + 342, "E-TICKET NUMBER", W - 2 * M - 342)]
        p.rect(M, self.y - 18, W - 2 * M, 18, fill=NAVY, r=5)
        for x, lbl, _ in cols:
            p.text(x + 8, self.y - 12, lbl, 7, "B", "#FFFFFF", spacing=0.4)
        self.y -= 18
        if not pax:
            p.text(M + 8, self.y - 14, "No passengers recorded", 9, "R", MUTED)
            self.y -= 22
        for i, x in enumerate(pax):
            lines = pdfkit.wrap(pax_name(x), 9.5, 236, "B")  # full name, wrapped - never cut off
            rh = 20 + 12 * (len(lines) - 1)
            if self.y - rh < 60:
                self.need(rh + 20, "E-TICKET (CONTINUED)")
                p = self.page
            if i % 2:
                p.rect(M, self.y - rh, W - 2 * M, rh, fill=BG)
            p.text(M + 8, self.y - 13.5, str(i + 1), 9, "R", MUTED)
            for k, ln in enumerate(lines):
                p.text(M + 30, self.y - 13.5 - 12 * k, ln, 9.5, "B", INK)
            p.text(M + 280, self.y - 13.5, x["type"], 9, "R", INK)
            p.text(M + 350, self.y - 13.5, x["ticket_number"] or "Not issued yet", 9.5, "B" if x["ticket_number"] else "R",
                   ROYAL if x["ticket_number"] else MUTED, spacing=0.5 if x["ticket_number"] else 0)
            p.line(M, self.y - rh, W - M, self.y - rh, LINE, 0.5)
            self.y -= rh
        self.y -= 16

    def good_to_know(self):
        b = self.td["booking"]
        tips = ["Carry a valid passport or government photo ID. Names must match the ticket exactly.",
                "Reach the airport at least 3 hours before international and 2 hours before domestic departures.",
                "All times are local airport times. Please reconfirm timings with the airline before travel.",
                "Baggage, cancellation and change rules are as per the airline fare conditions."]
        if not b["is_ticketed"] and not b["is_cancelled"] and not any(x["ticket_number"] for x in self.td["passengers"]):
            tips.insert(0, "This is not an e-ticket yet. Travel is possible only after the ticket is issued.")
        h = 26 + 13 * sum(len(pdfkit.wrap(t, 8.3, W - 2 * M - 40)) for t in tips)
        self.need(h + 20, "E-TICKET (CONTINUED)")
        p = self.page
        p.rect(M, self.y - h, W - 2 * M, h, fill=SKY_LIGHT, r=10)
        p.text(M + 14, self.y - 16, "GOOD TO KNOW", 8.5, "B", NAVY, spacing=0.6)
        yy = self.y - 31
        for t in tips:
            p.circle(M + 18, yy + 3, 1.8, fill=ROYAL)
            yy = p.para(M + 26, yy, t, 8.3, W - 2 * M - 40, "R", INK, leading=13)
        self.y -= h + 16
        p.text(W / 2, self.y - 4, "We wish you a safe, comfortable and wonderful journey!", 10, "B", ROYAL, align="center")
        self.y -= 22

    # ------------------------------------------------------------------ payment page
    def payment_page(self):
        td = self.td
        b, pay, pd = td["booking"], td["payment"], td["payment_details"]
        self.footer()
        self.header("PAYMENT DETAILS", "%s  |  PNR %s" % (b["ref"], b["pnr"] or "-"), compact=True)
        p = self.page
        cur = pay["currency"]
        # summary
        fg, bg = PAY_COLORS[pay["status"]]
        h = 118
        p.rect(M, self.y - h, W - 2 * M, h, fill="#FFFFFF", stroke=LINE, r=10)
        p.text(M + 16, self.y - 20, "PAYMENT SUMMARY", 9, "B", NAVY, spacing=0.6)
        tw = pdfkit.text_width(pay["status_label"], 9, "B") + 18
        p.rect(W - M - 16 - tw, self.y - 26, tw, 17, fill=bg, r=8.5)
        p.text(W - M - 16 - tw + 9, self.y - 20.5, pay["status_label"], 9, "B", fg)
        rows = [("Booking reference", "%s  |  PNR %s" % (b["ref"], b["pnr"] or "-")),
                ("Total amount", money(pay["total"], cur) if pay["committed"] else "Not billed yet"),
                ("Amount received", money(pay["received"], cur))]
        yy = self.y - 44
        for k, v in rows:
            p.text(M + 16, yy, k, 9, "R", MUTED)
            p.text(W - M - 16, yy, v, 9.5, "B", INK, align="right")
            p.line(M + 16, yy - 6, W - M - 16, yy - 6, LINE, 0.5)
            yy -= 19
        p.text(M + 16, yy - 4, "Balance pending", 11, "B", NAVY)
        p.text(W - M - 16, yy - 5, money(pay["pending"], cur), 15, "B", "#D33447" if pay["pending"] > 0 else "#0E9F6B", align="right")
        self.y -= h + 16
        if pay["credit"] > 0:
            self.notice("Customer credit of %s is held for this booking (paid more than billed)." % money(pay["credit"], cur), "#6B4CE6", "#EFEBFE")
        if pay["status"] == "paid":
            self.notice("Paid in full - thank you! No payment is due for this booking.", "#0E9F6B", "#E3F6EE")
            return
        if not pay["committed"]:
            self.notice("The amount is not billed yet. We will share the payable amount once the booking is confirmed.", MUTED, "#EEF2F9")
            return
        # bank + UPI cards
        bank = [("Account name", pd["account_name"]), ("Account number", pd["account_number"]), ("IFSC code", pd["ifsc"]),
                ("Bank", pd["bank_name"]), ("Branch", pd["branch"])]
        bank = [r for r in bank if r[1]]
        cw = (W - 2 * M - 14) / 2
        ch = 52 + 28 * max(len(bank), 3)
        if bank:
            x = M
            p.rect(x, self.y - ch, cw, ch, fill=BG, stroke=LINE, r=10)
            p.gradient_rect(x, self.y - 30, cw, 30, NAVY, ROYAL, r=10)
            p.rect(x, self.y - 30, cw, 12, fill=NAVY)
            p.gradient_rect(x, self.y - 30, cw, 12, NAVY, ROYAL)
            p.text(x + 14, self.y - 19.5, "BANK TRANSFER", 9.5, "B", "#FFFFFF", spacing=0.8)
            yy = self.y - 48
            for k, v in bank:
                p.text(x + 14, yy, k, 7.5, "R", MUTED)
                p.text(x + 14, yy - 12.5, v, 10.5, "B", INK, spacing=0.4)
                yy -= 28
                if yy < self.y - ch + 10:
                    break
        if pd["upi_number"]:
            x = M + cw + 14 if bank else M
            p.rect(x, self.y - ch, cw, ch, fill=BG, stroke=LINE, r=10)
            p.gradient_rect(x, self.y - 30, cw, 30, ROYAL, SKY, r=10)
            p.gradient_rect(x, self.y - 30, cw, 12, ROYAL, SKY)
            p.text(x + 14, self.y - 19.5, "UPI PAYMENT", 9.5, "B", "#FFFFFF", spacing=0.8)
            p.text(x + 14, self.y - 48, "UPI payment number", 7.5, "R", MUTED)
            p.text(x + 14, self.y - 66, pd["upi_number"], 17, "B", NAVY, spacing=1.2)
            apps = [a.strip() for a in (pd["upi_apps"] or "").split(",") if a.strip()]
            if apps:
                p.text(x + 14, self.y - 86, "Pay with", 7.5, "R", MUTED)
                ax, ay = x + 14, self.y - 106
                for a in apps:
                    tw = pdfkit.text_width(a, 8.5, "B") + 18
                    if ax + tw > x + cw - 10:
                        ax, ay = x + 14, ay - 22
                    p.rect(ax, ay, tw, 16, fill="#FFFFFF", stroke="#C9D5EE", r=8)
                    p.text(ax + 9, ay + 5, a, 8.5, "B", NAVY2)
                    ax += tw + 6
        if bank or pd["upi_number"]:
            self.y -= ch + 16
            self.notice("Please verify the beneficiary details before confirming payment. After paying, kindly share your payment "
                        "confirmation (screenshot or UTR number) with us.", ROYAL, "#EAF0FF")
        else:
            self.notice("Payment details are not set up yet. Please contact us for payment instructions.", "#C27A06", "#FDF3DF")
        if pay["pending"] > 0:
            p = self.page
            p.text(W / 2, self.y - 4, "Thank you for trusting %s!" % td["business"]["name"].upper(), 10, "B", ROYAL, align="center")
            self.y -= 22

    # ------------------------------------------------------------------ build
    def build(self, variant):
        td, b = self.td, self.td["booking"]
        sub = "%s  |  %s" % (b["ref"], b["trip_label"] or "Flight") if b["trip_label"] else b["ref"]
        self.header(b["doc_title"], sub)
        self.summary_cards()
        if b["is_cancelled"]:
            self.notice("This booking has been %s. It is not valid for travel." % b["status"], "#D33447", "#FDE9EC")
        elif not b["is_confirmed"]:
            self.notice("Not confirmed yet - this itinerary is for reference only and is not valid for travel.", "#C27A06", "#FDF3DF")
        elif not b["is_ticketed"]:
            if any(x["ticket_number"] for x in td["passengers"]):
                self.notice("Booking confirmed. E-ticket number(s) recorded for this booking are listed below.", ROYAL, "#EAF0FF")
            else:
                self.notice("Booking confirmed. E-ticket numbers will be shared once the ticket is issued.", ROYAL, "#EAF0FF")
        if td["journeys"]:
            self.section("Flight itinerary")
            for j in td["journeys"]:
                self.journey(j)
        else:
            self.notice("No flight segments are recorded for this booking.", MUTED, "#EEF2F9")
        self.passengers()
        self.good_to_know()
        if variant == "payment":
            self.payment_page()
        self.footer()
        return self.doc.bytes()


def render_pdf(td, variant="ticket"):
    return _Layout(td).build("payment" if variant == "payment" else "ticket")


def tickets_dir():
    return Path(os.environ.get("TACRM_TICKET_DIR") or (Path.home() / "Documents" / "AirZelo" / "Tickets"))


def save_pdf(td, variant):
    """Write the PDF to the tickets folder (overwriting an older copy for the same booking)."""
    folder = tickets_dir()
    folder.mkdir(parents=True, exist_ok=True)
    name = file_name(td, variant)
    data = render_pdf(td, variant)
    path = folder / name
    tmp = folder / (name + ".part")
    tmp.write_bytes(data)
    os.replace(tmp, path)
    return path, len(data)
