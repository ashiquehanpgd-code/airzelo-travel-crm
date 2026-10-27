"""Local HTTP server for AirZelo (standard library only).

Security model:
  * binds to 127.0.0.1 by default (not reachable from other devices)
  * Host header must be localhost/127.0.0.1 (DNS-rebinding protection)
  * all /api routes except auth status/setup/login require a valid session cookie
  * mutating requests require the X-QT-CSRF header and a same-origin Origin
  * strict Content-Security-Policy, no third-party resources
"""
import json
import mimetypes
import os
import re
import secrets
import signal
import subprocess
import sys
import threading
import traceback
from datetime import date, timedelta
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs, quote

from . import VERSION, auth, backup, services, reports, finance, ticket, ticketscan, debts, upgrade, branding
from .db import connect, migrate, get_setting
from .errors import ApiError, NotFound, Unauthorized, Forbidden, ValidationError

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
MAX_BODY = 25 * 1024 * 1024
COOKIE = "qt_session"


class Context:
    def __init__(self, data_dir, host="127.0.0.1", port=8765):
        self.data_dir = Path(data_dir)
        self.db_path = self.data_dir / "agency.db"
        self.upload_dir = self.data_dir / "uploads"
        self.backup_dir = self.data_dir / "backups"
        self.host = host
        self.port = port
        self.setup_code = None
        self.gate = RWLock()

    def init(self):
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.upload_dir.mkdir(exist_ok=True)
        self.backup_dir.mkdir(exist_ok=True)
        try:
            self.data_dir.chmod(0o700)
        except OSError:
            pass
        upgrade.open_company_file(self.db_path, self.backup_dir)
        conn = connect(self.db_path)
        try:
            if not auth.owner_exists(conn):
                self.setup_code = "%06d" % secrets.randbelow(1000000)
        finally:
            conn.close()
        try:
            self.db_path.chmod(0o600)
        except OSError:
            pass


class RWLock:
    """Many requests may run together; restore needs exclusive access."""

    def __init__(self):
        self.cond = threading.Condition()
        self.readers = 0
        self.writer = False

    def acquire_read(self):
        with self.cond:
            while self.writer:
                self.cond.wait()
            self.readers += 1

    def release_read(self):
        with self.cond:
            self.readers -= 1
            self.cond.notify_all()

    def acquire_write(self):
        with self.cond:
            while self.writer or self.readers:
                self.cond.wait()
            self.writer = True

    def release_write(self):
        with self.cond:
            self.writer = False
            self.cond.notify_all()


ROUTES = []


def route(method, pattern, public=False, exclusive=False):
    rx = re.compile("^" + pattern + "$")

    def deco(fn):
        ROUTES.append((method, rx, fn, public, exclusive))
        return fn
    return deco


class Resp:
    def __init__(self, body=b"", status=200, ctype="application/json", headers=None):
        self.body = body
        self.status = status
        self.ctype = ctype
        self.headers = headers or {}


def J(obj, status=200, headers=None):
    return Resp(json.dumps(obj, default=str).encode("utf-8"), status, "application/json; charset=utf-8", headers)


def money_params(q):
    return {k: (v[0] if isinstance(v, list) else v) for k, v in q.items()}


# ---------------------------------------------------------------- auth
@route("GET", r"/api/auth/status", public=True)
def r_auth_status(req):
    conn = req.conn
    return J({"setup_needed": not auth.owner_exists(conn), "logged_in": req.authed, "version": VERSION,
              "business_name": get_setting(conn, "business_name", "")})


@route("POST", r"/api/auth/setup", public=True)
def r_setup(req):
    auth.setup_owner(req.conn, req.json(), req.ctx.setup_code)
    req.ctx.setup_code = None
    token = auth.login(req.conn, req.json())
    return J({"ok": True}, headers={"Set-Cookie": session_cookie(token)})


@route("POST", r"/api/auth/login", public=True)
def r_login(req):
    token = auth.login(req.conn, req.json())
    return J({"ok": True}, headers={"Set-Cookie": session_cookie(token)})


@route("POST", r"/api/auth/logout", public=True)
def r_logout(req):
    auth.logout(req.conn, req.token)
    return J({"ok": True}, headers={"Set-Cookie": "%s=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" % COOKIE})


@route("POST", r"/api/auth/password")
def r_password(req):
    auth.change_password(req.conn, req.json())
    return J({"ok": True}, headers={"Set-Cookie": "%s=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" % COOKIE})


def session_cookie(token):
    return "%s=%s; Path=/; HttpOnly; SameSite=Strict" % (COOKIE, token)


# ----------------------------------------------------------- meta / lookup
@route("GET", r"/api/meta")
def r_meta(req):
    c = req.conn
    return J({
        "settings": services.get_settings(c), "currencies": services.list_currencies(c),
        "accounts": [dict(a) for a in c.execute("SELECT id, name, kind, account_type, bank_name, account_last4, currency, active "
                                                 "FROM accounts ORDER BY active DESC, kind, name")],
        "suppliers": [dict(s) for s in c.execute("SELECT id, name, kind, active FROM suppliers ORDER BY name")],
        "tax_profiles": services.list_tax_profiles(c), "expense_categories": services.list_expense_categories(c),
        "methods": list(services.METHODS), "statuses": list(services.BOOKING_STATUSES), "service_types": list(services.SERVICE_TYPES),
        "kinds": finance.KIND_LABELS, "version": VERSION, "today": date.today().isoformat(),
    })


@route("GET", r"/api/search")
def r_search(req):
    return J(services.global_search(req.conn, req.q("q")))


@route("GET", r"/api/dashboard")
def r_dashboard(req):
    frm, to = req.q("from"), req.q("to")
    if not frm and not to:
        t = date.today()
        frm, to = t.replace(day=1).isoformat(), t.isoformat()
    return J(finance.dashboard(req.conn, frm, to))


# --------------------------------------------------------------- customers
@route("GET", r"/api/customers")
def r_customers(req):
    return J(services.list_customers(req.conn, req.q("q"), req.q("inactive") == "1"))


@route("POST", r"/api/customers")
def r_customer_create(req):
    cid = services.create_customer(req.conn, req.json())
    return J(services.get_customer(req.conn, cid), 201)


@route("GET", r"/api/customers/(\d+)")
def r_customer(req, cid):
    return J(services.get_customer(req.conn, int(cid)))


@route("PUT", r"/api/customers/(\d+)")
def r_customer_update(req, cid):
    return J(services.update_customer(req.conn, int(cid), req.json()))


@route("DELETE", r"/api/customers/(\d+)")
def r_customer_delete(req, cid):
    return J(services.delete_customer(req.conn, int(cid)))


@route("GET", r"/api/customers/(\d+)/statement")
def r_customer_statement(req, cid):
    L = finance.Ledger(req.conn)
    st = reports.customer_statement(L, int(cid), req.q("from"), req.q("to"))
    if req.q("format") == "csv":
        return csv_resp(reports.statement_csv(st), "customer-statement-%s.csv" % cid)
    return J(st)


# --------------------------------------------------------------- suppliers
@route("GET", r"/api/suppliers")
def r_suppliers(req):
    return J(services.list_suppliers(req.conn, req.q("inactive") == "1"))


@route("POST", r"/api/suppliers")
def r_supplier_create(req):
    sid = services.create_supplier(req.conn, req.json())
    return J(services.get_supplier(req.conn, sid), 201)


@route("GET", r"/api/suppliers/(\d+)")
def r_supplier(req, sid):
    return J(services.get_supplier(req.conn, int(sid)))


@route("PUT", r"/api/suppliers/(\d+)")
def r_supplier_update(req, sid):
    return J(services.update_supplier(req.conn, int(sid), req.json()))


@route("DELETE", r"/api/suppliers/(\d+)")
def r_supplier_delete(req, sid):
    return J(services.delete_supplier(req.conn, int(sid)))


@route("GET", r"/api/suppliers/(\d+)/statement")
def r_supplier_statement(req, sid):
    L = finance.Ledger(req.conn)
    st = reports.supplier_statement(L, int(sid), req.q("from"), req.q("to"))
    if req.q("format") == "csv":
        return csv_resp(reports.statement_csv(st), "supplier-statement-%s.csv" % sid)
    return J(st)


# ---------------------------------------------------------------- bookings
@route("GET", r"/api/bookings")
def r_bookings(req):
    return J(services.search_bookings(req.conn, req.params()))


@route("POST", r"/api/bookings")
def r_booking_create(req):
    bid = services.save_booking(req.conn, req.json())
    return J(services.get_booking(req.conn, bid), 201)


@route("GET", r"/api/bookings/(\d+)")
def r_booking(req, bid):
    return J(services.get_booking(req.conn, int(bid)))


# ----------------------------------------------------------- offline ticket scanner
@route("GET", r"/api/ticket-scan/status")
def r_scan_status(req):
    st = ticketscan.reader_status()
    where = "on this Mac with Apple PDFKit and Vision" if ticketscan.mac_reader_ready() else "on this computer"
    st.update({"offline": True, "cloud_ai": False, "local_ai": False,
               "note": " ".join(x for x in (st.get("note"), "Tickets are read %s. Nothing is sent to the internet." % where) if x)})
    return J(st)


@route("POST", r"/api/ticket-scan")
def r_scan(req):
    return J(ticketscan.scan(req.conn, req.ctx, req.json()))


@route("GET", r"/api/ticket-scan/([0-9a-f]{24})/preview")
def r_scan_preview(req, sid):
    return Resp(ticketscan.preview_path(req.ctx, sid).read_bytes(), 200, "image/png", {"Cache-Control": "no-store"})


@route("DELETE", r"/api/ticket-scan/([0-9a-f]{24})")
def r_scan_discard(req, sid):
    ticketscan.discard_scan(req.ctx, sid)
    return J({"ok": True})


@route("POST", r"/api/bookings/(\d+)/attach-scan")
def r_scan_attach(req, bid):
    did = ticketscan.attach_scan(req.conn, req.ctx, int(bid), req.json().get("scan_id"))
    return J({"document_id": did}, 201)


# ----------------------------------------------------------- tickets (PDF + WhatsApp data)
@route("GET", r"/api/bookings/(\d+)/ticket")
def r_ticket_data(req, bid):
    return J(ticket.ticket_data(req.conn, int(bid)))


@route("GET", r"/api/bookings/(\d+)/ticket\.pdf")
def r_ticket_pdf(req, bid):
    variant = "payment" if req.q("variant") == "payment" else "ticket"
    td = ticket.ticket_data(req.conn, int(bid))
    disp = "inline" if req.q("inline") == "1" else "attachment"
    return Resp(ticket.render_pdf(td, variant), 200, "application/pdf",
                {"Content-Disposition": "%s; filename*=UTF-8''%s" % (disp, quote(ticket.file_name(td, variant)))})


def open_path(path, reveal=False):
    """Open a saved file in the system viewer, or show it in Finder / File Explorer.
    Returns (opened, reason) - the reason is shown to the user instead of failing silently."""
    try:
        if sys.platform == "darwin":
            subprocess.run(["/usr/bin/open"] + (["-R"] if reveal else []) + [str(path)], timeout=15, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        elif sys.platform == "win32":
            if reveal:
                # explorer.exe exits with 1 even when it worked, so its exit code is not checked
                subprocess.run(["explorer.exe", "/select,", str(path)], timeout=15)
            else:
                os.startfile(str(path))  # noqa: only exists on Windows
        else:
            subprocess.run(["xdg-open", str(path.parent if reveal else path)], timeout=15, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True, ""
    except OSError as e:
        if getattr(e, "winerror", None) == 1155:   # ERROR_NO_ASSOCIATION
            return False, "no app is set to open PDF files on this computer"
        return False, e.strerror or str(e)
    except subprocess.SubprocessError as e:
        return False, str(e) or "the viewer did not start"


@route("POST", r"/api/bookings/(\d+)/ticket-file")
def r_ticket_file(req, bid):
    """Save the ticket PDF to the tickets folder and optionally open it in Preview or show it in Finder.
    The path is built here from the booking - never taken from the request."""
    data = req.json()
    variant = "payment" if data.get("variant") == "payment" else "ticket"
    td = ticket.ticket_data(req.conn, int(bid))
    path, size = ticket.save_pdf(td, variant)
    action = data.get("action")
    opened, reason = False, ""
    if action in ("open", "reveal"):
        if os.environ.get("TACRM_NO_OPEN"):
            reason = "opening files is switched off here"
        else:
            opened, reason = open_path(path, reveal=action == "reveal")
    return J({"name": path.name, "folder": str(path.parent), "path": str(path), "size": size, "variant": variant,
              "action": action, "opened": opened, "open_error": reason})


@route("PUT", r"/api/bookings/(\d+)")
def r_booking_update(req, bid):
    services.save_booking(req.conn, req.json(), int(bid))
    return J(services.get_booking(req.conn, int(bid)))


@route("POST", r"/api/bookings/(\d+)/status")
def r_booking_status(req, bid):
    services.set_booking_status(req.conn, int(bid), req.json().get("status"))
    return J(services.get_booking(req.conn, int(bid)))


@route("POST", r"/api/bookings/(\d+)/adjustments")
def r_adjust(req, bid):
    services.create_adjustment(req.conn, int(bid), req.json())
    return J(services.get_booking(req.conn, int(bid)), 201)


@route("POST", r"/api/bookings/(\d+)/refund")
def r_booking_refund(req, bid):
    return J(services.refund_booking(req.conn, int(bid), req.json()), 201)


@route("DELETE", r"/api/bookings/(\d+)")
def r_booking_delete(req, bid):
    return J(services.delete_booking(req.conn, int(bid)))


@route("POST", r"/api/adjustments/(\d+)/correct")
def r_adjust_correct(req, aid):
    nid = services.correct_adjustment(req.conn, int(aid), req.json())
    return J({"id": nid}, 201)


@route("POST", r"/api/adjustments/(\d+)/reverse")
def r_adjust_reverse(req, aid):
    services.reverse_adjustment(req.conn, int(aid), req.json())
    return J({"ok": True})


@route("POST", r"/api/adjustments/(\d+)/credit-note")
def r_credit_note(req, aid):
    iid = services.create_credit_note(req.conn, int(aid), req.json())
    return J(services.get_invoice(req.conn, iid), 201)


@route("POST", r"/api/bookings/(\d+)/invoice")
def r_invoice_create(req, bid):
    iid = services.create_invoice(req.conn, int(bid), req.json())
    return J(services.get_invoice(req.conn, iid), 201)


@route("POST", r"/api/bookings/(\d+)/documents")
def r_doc_upload(req, bid):
    did = services.save_document(req.conn, req.ctx, req.json(), booking_id=int(bid))
    return J({"id": did}, 201)


@route("GET", r"/api/bookings/check-pnr")
def r_check_pnr(req):
    return J(services.find_duplicate_pnr(req.conn, (req.q("pnr") or "").strip().upper(), req.q("exclude")))


# ------------------------------------------------------------- transactions
@route("GET", r"/api/transactions")
def r_txns(req):
    return J(services.list_transactions(req.conn, req.params()))


@route("POST", r"/api/transactions")
def r_txn_create(req):
    tid, dup = services.create_transaction(req.conn, req.json())
    r = services.get_transaction(req.conn, tid)
    r["idempotent_replay"] = dup
    return J(r, 200 if dup else 201)


@route("POST", r"/api/transactions/(\d+)/correct")
def r_txn_correct(req, tid):
    nid, how = services.correct_transaction(req.conn, int(tid), req.json())
    r = services.get_transaction(req.conn, nid)
    r["edit_result"] = how
    return J(r)


@route("GET", r"/api/transactions/(\d+)")
def r_txn(req, tid):
    return J(services.get_transaction(req.conn, int(tid)))


@route("PATCH", r"/api/transactions/(\d+)")
def r_txn_meta(req, tid):
    services.update_transaction_meta(req.conn, int(tid), req.json())
    return J(services.get_transaction(req.conn, int(tid)))


@route("POST", r"/api/transactions/(\d+)/reverse")
def r_txn_reverse(req, tid):
    rid = services.reverse_transaction(req.conn, int(tid), req.json())
    return J(services.get_transaction(req.conn, rid), 201)


@route("PUT", r"/api/transactions/(\d+)/allocations")
def r_txn_alloc(req, tid):
    services.set_allocations(req.conn, int(tid), req.json())
    return J(services.get_transaction(req.conn, int(tid)))


@route("POST", r"/api/transactions/(\d+)/documents")
def r_txn_doc(req, tid):
    did = services.save_document(req.conn, req.ctx, req.json(), transaction_id=int(tid))
    return J({"id": did}, 201)


# ---------------------------------------------------------------- documents
@route("GET", r"/api/documents/(\d+)")
def r_doc(req, did):
    d, path = services.get_document(req.conn, req.ctx, int(did))
    data = path.read_bytes()
    disp = "inline" if req.q("inline") == "1" and d["mime"] in ("application/pdf", "image/png", "image/jpeg", "image/webp", "image/gif") else "attachment"
    return Resp(data, 200, d["mime"], {"Content-Disposition": "%s; filename*=UTF-8''%s" % (disp, quote(d["original_name"]))})


@route("DELETE", r"/api/documents/(\d+)")
def r_doc_delete(req, did):
    services.delete_document(req.conn, req.ctx, int(did))
    return J({"ok": True})


# ---------------------------------------------------------------- invoices
@route("GET", r"/api/invoices")
def r_invoices(req):
    return J(services.list_invoices(req.conn, req.params()))


@route("GET", r"/api/invoices/(\d+)")
def r_invoice(req, iid):
    return J(services.get_invoice(req.conn, int(iid)))


@route("POST", r"/api/invoices/(\d+)/cancel")
def r_invoice_cancel(req, iid):
    services.cancel_invoice(req.conn, int(iid), req.json())
    return J(services.get_invoice(req.conn, int(iid)))


# ---------------------------------------------------------------- accounts
@route("GET", r"/api/accounts")
def r_accounts(req):
    return J(services.list_accounts(req.conn))


@route("POST", r"/api/accounts")
def r_account_create(req):
    return J({"id": services.save_account(req.conn, req.json())}, 201)


@route("PUT", r"/api/accounts/(\d+)")
def r_account_update(req, aid):
    return J({"id": services.save_account(req.conn, req.json(), int(aid))})


@route("POST", r"/api/accounts/(\d+)/active")
def r_account_active(req, aid):
    return J({"id": services.set_account_active(req.conn, int(aid), bool(req.json().get("active")))})


@route("GET", r"/api/accounts/(\d+)/reconcile")
def r_recon(req, aid):
    return J(services.reconciliation_view(req.conn, int(aid), req.q("as_of")))


@route("POST", r"/api/accounts/(\d+)/reconcile")
def r_recon_save(req, aid):
    return J(services.save_reconciliation(req.conn, int(aid), req.json()))


# ------------------------------------------------------------------- debts
@route("GET", r"/api/debts")
def r_debts(req):
    return J(debts.list_debts(req.conn, req.q("status")))


@route("GET", r"/api/debts/summary")
def r_debt_summary(req):
    frm, to = req.q("from"), req.q("to")
    if not frm or not to:
        t = date.today()
        frm, to = t.replace(day=1).isoformat(), t.isoformat()
    return J(debts.summary(req.conn, frm, to))


@route("POST", r"/api/debts")
def r_debt_create(req):
    return J({"id": debts.save_debt(req.conn, req.json())}, 201)


@route("GET", r"/api/debts/(\d+)")
def r_debt(req, did):
    return J(debts.get_debt(req.conn, int(did)))


@route("PUT", r"/api/debts/(\d+)")
def r_debt_update(req, did):
    return J({"id": debts.save_debt(req.conn, req.json(), int(did))})


@route("DELETE", r"/api/debts/(\d+)")
def r_debt_delete(req, did):
    return J({"id": debts.delete_debt(req.conn, int(did))})


@route("POST", r"/api/debts/(\d+)/status")
def r_debt_status(req, did):
    return J({"id": debts.set_status(req.conn, int(did), req.json().get("status"))})


@route("POST", r"/api/debts/receive")
def r_debt_receive(req):
    did, tid = debts.receive_loan(req.conn, req.json())
    return J({"debt_id": did, "transaction_id": tid}, 201)


@route("POST", r"/api/debts/(\d+)/repay")
def r_debt_repay(req, did):
    return J(debts.repay_loan(req.conn, int(did), req.json()), 201)


@route("GET", r"/api/debts/repayments/(\d+)")
def r_debt_repayment(req, tid):
    return J(debts.repayment(req.conn, int(tid)))


@route("POST", r"/api/debts/repayments/(\d+)/reverse")
def r_debt_repayment_reverse(req, tid):
    return J({"reversal_ids": debts.reverse_repayment(req.conn, int(tid), req.json())})


@route("POST", r"/api/debts/(\d+)/interest")
def r_debt_interest(req, did):
    return J({"id": debts.add_interest_charge(req.conn, int(did), req.json())}, 201)


@route("POST", r"/api/debt-entries/(\d+)/reverse")
def r_debt_entry_reverse(req, eid):
    return J({"id": debts.reverse_entry(req.conn, int(eid), req.json())})


# ----------------------------------------------------------------- reports
@route("GET", r"/api/reports/([a-z_]+)")
def r_report(req, name):
    rep = reports.run_report(req.conn, name, req.params())
    if req.q("format") == "csv":
        return csv_resp(reports.to_csv(rep), "%s-%s.csv" % (name, date.today().isoformat()))
    return J(rep)


def csv_resp(text, filename):
    return Resp(text.encode("utf-8"), 200, "text/csv; charset=utf-8",
                {"Content-Disposition": "attachment; filename=\"%s\"" % filename})


# ---------------------------------------------------------------- settings
@route("PUT", r"/api/settings")
def r_settings(req):
    return J(services.update_settings(req.conn, req.json()))


@route("POST", r"/api/settings/logo")
def r_settings_logo(req):
    return J({"logo": services.set_logo(req.conn, req.ctx, req.json()), "settings": services.get_settings(req.conn)})


@route("GET", r"/api/branding/logo")
def r_branding_logo(req):
    """The current logo, or a specific stored one (?f=logo-<hash>.png) for documents issued with an older logo."""
    name = req.q("f") or get_setting(req.conn, "logo_file", "")
    path = branding.logo_path(req.ctx, name)
    if not path:
        raise NotFound("No logo")
    return Resp(path.read_bytes(), 200, "image/png" if path.suffix == ".png" else "image/jpeg", {"Cache-Control": "private, max-age=86400"})


@route("POST", r"/api/currencies")
def r_currency(req):
    return J(services.save_currency(req.conn, req.json()))


@route("POST", r"/api/tax-profiles")
def r_tax_create(req):
    return J({"id": services.save_tax_profile(req.conn, req.json())}, 201)


@route("PUT", r"/api/tax-profiles/(\d+)")
def r_tax_update(req, pid):
    return J({"id": services.save_tax_profile(req.conn, req.json(), int(pid))})


@route("POST", r"/api/expense-categories")
def r_expcat(req):
    return J({"name": services.save_expense_category(req.conn, req.json())})


@route("GET", r"/api/audit")
def r_audit(req):
    return J(services.list_audit(req.conn, req.q("entity"), req.q("entity_id"), req.q("limit") or 300))


# ----------------------------------------------------------------- backups
@route("GET", r"/api/backups")
def r_backups(req):
    return J({"backups": backup.list_backups(req.ctx), "folder": str(req.ctx.backup_dir)})


@route("POST", r"/api/backups")
def r_backup_create(req):
    r = backup.create_backup(req.ctx, "manual", (req.json() or {}).get("note"))
    with_conn_audit(req, "backup", r["name"], "create", {"size": r["size"]})
    return J(r, 201)


@route("POST", r"/api/backups/verify")
def r_backup_verify(req):
    p = backup.backup_path(req.ctx, req.json().get("name"))
    return J(backup.verify_backup(p))


@route("GET", r"/api/backups/download")
def r_backup_download(req):
    p = backup.backup_path(req.ctx, req.q("name"))
    return Resp(p.read_bytes(), 200, "application/zip", {"Content-Disposition": "attachment; filename=\"%s\"" % p.name})


@route("POST", r"/api/backups/restore", exclusive=True)
def r_backup_restore(req):
    data = req.json()
    if data.get("confirm") != "RESTORE":
        raise ValidationError("Type RESTORE to confirm. Your current data will be replaced (a safety backup is taken first).")
    if data.get("upload"):
        import base64
        import tempfile
        raw = data["upload"]
        if raw.startswith("data:"):
            raw = raw.split(",", 1)[1]
        content = base64.b64decode(raw)
        tmp = Path(tempfile.mkstemp(prefix="upload-", suffix=".zip", dir=str(req.ctx.backup_dir))[1])
        tmp.write_bytes(content)
        try:
            v = backup.verify_backup(tmp)
            if not v["ok"]:
                raise ValidationError("Uploaded file failed verification: " + "; ".join(v["problems"]))
            final = req.ctx.backup_dir / backup._unique_name(req.ctx.backup_dir, "imported")
            tmp.rename(final)
        finally:
            if tmp.exists():
                tmp.unlink()
        path = final
    else:
        path = backup.backup_path(req.ctx, data.get("name"))
    req.close_conn()
    result = backup.restore_backup(req.ctx, path)
    conn = connect(req.ctx.db_path)
    try:
        services.audit(conn, "backup", path.name, "restore", None, result)
    finally:
        conn.close()
    return J(result, headers={"Set-Cookie": "%s=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" % COOKIE})


def with_conn_audit(req, entity, eid, action, after):
    from .db import tx
    with tx(req.conn):
        services.audit(req.conn, entity, eid, action, None, after)


# ------------------------------------------------------------------ server
class Request:
    def __init__(self, handler, ctx, method, path, query, body):
        self.h = handler
        self.ctx = ctx
        self.method = method
        self.path = path
        self.query = query
        self.body = body
        self._json = None
        self.conn = None
        self.token = None
        self.authed = False

    def q(self, k):
        v = self.query.get(k)
        return v[0] if v else None

    def params(self):
        return {k: v[0] for k, v in self.query.items() if v and v[0] != ""}

    def json(self):
        if self._json is None:
            if not self.body:
                self._json = {}
            else:
                try:
                    self._json = json.loads(self.body.decode("utf-8"))
                except Exception:
                    raise ValidationError("Request body must be valid JSON")
                if not isinstance(self._json, dict):
                    raise ValidationError("Request body must be a JSON object")
        return self._json

    def close_conn(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except Exception:
                pass
            self.conn = None


SECURITY_HEADERS = {
    "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
                               "font-src 'self' data:; connect-src 'self'; frame-src 'self' blob:; object-src 'self' blob:; "
                               "frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Resource-Policy": "same-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
}


def make_handler(ctx, log=print):
    allowed_hosts = {"127.0.0.1:%d" % ctx.port, "localhost:%d" % ctx.port, "[::1]:%d" % ctx.port}
    if ctx.host not in ("127.0.0.1", "localhost", "::1"):
        allowed_hosts.add("%s:%d" % (ctx.host, ctx.port))

    class Handler(BaseHTTPRequestHandler):
        server_version = "AirZelo/" + VERSION
        sys_version = ""
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def _send(self, resp):
            self.send_response(resp.status)
            for k, v in SECURITY_HEADERS.items():
                self.send_header(k, v)
            self.send_header("Content-Type", resp.ctype)
            self.send_header("Content-Length", str(len(resp.body)))
            if not resp.headers.get("Cache-Control"):
                self.send_header("Cache-Control", "no-store")
            for k, v in resp.headers.items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(resp.body)

        def _err(self, status, message, code="error"):
            self._send(J({"error": message, "code": code}, status))

        def do_GET(self):
            self.dispatch("GET")

        def do_HEAD(self):
            self.dispatch("GET")

        def do_POST(self):
            self.dispatch("POST")

        def do_PUT(self):
            self.dispatch("PUT")

        def do_PATCH(self):
            self.dispatch("PATCH")

        def do_DELETE(self):
            self.dispatch("DELETE")

        def dispatch(self, method):
            host = (self.headers.get("Host") or "").lower()
            if host not in allowed_hosts:
                return self._err(421, "Unrecognised host. Open AirZelo at http://127.0.0.1:%d" % ctx.port, "bad_host")
            u = urlparse(self.path)
            path = u.path
            if not path.startswith("/api/"):
                if method != "GET":
                    return self._err(405, "Method not allowed")
                return self.static(path)
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                return self._err(413, "Request too large (max 25 MB)")
            body = self.rfile.read(length) if length else b""
            if method != "GET":
                if self.headers.get("X-QT-CSRF") != "1":
                    return self._err(403, "Missing request header", "csrf")
                origin = self.headers.get("Origin")
                if origin and urlparse(origin).netloc.lower() not in allowed_hosts:
                    return self._err(403, "Cross-site request blocked", "csrf")
            for m, rx, fn, public, exclusive in ROUTES:
                if m != method:
                    continue
                mt = rx.match(path)
                if not mt:
                    continue
                return self.run(fn, mt.groups(), public, exclusive, method, path, parse_qs(u.query), body)
            return self._err(404, "Not found", "not_found")

        def run(self, fn, groups, public, exclusive, method, path, query, body):
            req = Request(self, ctx, method, path, query, body)
            if exclusive:
                ctx.gate.acquire_write()
            else:
                ctx.gate.acquire_read()
            try:
                req.conn = connect(ctx.db_path)
                c = cookies.SimpleCookie()
                try:
                    c.load(self.headers.get("Cookie") or "")
                except cookies.CookieError:
                    pass
                req.token = c[COOKIE].value if COOKIE in c else None
                req.authed = auth.validate_session(req.conn, req.token)
                if not public and not req.authed:
                    raise Unauthorized()
                resp = fn(req, *groups)
            except ApiError as e:
                resp = J(e.to_dict(), e.status)
            except Exception as e:
                msg = str(e)
                if "Posted" in msg or "cannot be" in msg:
                    resp = J({"error": msg, "code": "immutable"}, 409)
                elif "UNIQUE constraint failed" in msg:
                    resp = J({"error": "This record already exists (duplicate value): %s" % msg.split(":")[-1].strip(), "code": "duplicate"}, 409)
                elif "FOREIGN KEY" in msg:
                    resp = J({"error": "A linked record is missing or still in use.", "code": "integrity"}, 409)
                elif "CHECK constraint failed" in msg:
                    resp = J({"error": "A value is outside the allowed range.", "code": "validation"}, 400)
                else:
                    log("Error on %s %s: %s" % (method, path, msg))
                    traceback.print_exc()
                    resp = J({"error": "Something went wrong: %s" % msg, "code": "server"}, 500)
            finally:
                req.close_conn()
                if exclusive:
                    ctx.gate.release_write()
                else:
                    ctx.gate.release_read()
            self._send(resp)

        def static(self, path):
            if path in ("/", "/index.html"):
                p = STATIC_DIR / "index.html"
            else:
                rel = path.lstrip("/")
                if rel.startswith("static/"):
                    rel = rel[7:]
                p = (STATIC_DIR / rel).resolve()
                if STATIC_DIR.resolve() not in p.parents or not p.is_file():
                    p = STATIC_DIR / "index.html"
            data = p.read_bytes()
            ctype = mimetypes.guess_type(str(p))[0] or "application/octet-stream"
            if ctype.startswith("text/") or ctype in ("application/javascript",):
                ctype += "; charset=utf-8"
            self._send(Resp(data, 200, ctype, {"Cache-Control": "no-cache"}))

    return Handler


def serve(data_dir, host="127.0.0.1", port=8765, open_browser=False, log=print):
    ctx = Context(data_dir, host, port)
    ctx.init()
    handler = make_handler(ctx, log)
    httpd = ThreadingHTTPServer((host, port), handler)
    httpd.daemon_threads = True
    auto = backup.AutoBackup(ctx, log)
    auto.start()
    url = "http://127.0.0.1:%d" % port if host in ("127.0.0.1", "0.0.0.0") else "http://%s:%d" % (host, port)
    log("")
    log("  AirZelo %s is running at  %s" % (VERSION, url))
    log("  Data folder: %s" % ctx.data_dir)
    if host not in ("127.0.0.1", "localhost", "::1"):
        log("  WARNING: bound to %s - other devices on your network may reach this app." % host)
    if ctx.setup_code:
        log("")
        log("  FIRST-TIME SETUP CODE:  %s" % ctx.setup_code)
        log("  Enter this code in the browser to create your owner login.")
    log("")
    log("  Keep this window open while you use the app. Press Ctrl+C to stop safely.")
    log("TACRM_READY port=%d" % port)  # machine-readable line for the desktop shell

    def stop(*_):
        threading.Thread(target=httpd.shutdown, daemon=True).start()
    try:
        signal.signal(signal.SIGTERM, stop)
    except Exception:
        pass
    if os.environ.get("TACRM_STDIN_SHUTDOWN") == "1":
        # desktop shell: closing our stdin (or the shell exiting) stops the server cleanly on every OS
        def watch_stdin():
            try:
                while sys.stdin.readline():
                    pass
            except Exception:
                pass
            stop()
        threading.Thread(target=watch_stdin, daemon=True).start()
    if open_browser:
        import webbrowser
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        auto.stop_event.set()
        ctx.gate.acquire_write()  # wait for in-flight requests to finish
        try:
            httpd.server_close()
            c = connect(ctx.db_path)
            c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            c.close()
        finally:
            ctx.gate.release_write()
        log("  AirZelo stopped. All data is saved.")
    return ctx
