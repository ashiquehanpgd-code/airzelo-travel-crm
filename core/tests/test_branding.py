"""White-label company settings: profile validation, logos, branded documents, numbering, history safety and
agency isolation. Synthetic data only."""
import base64
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

from helpers import Base
from server import services, finance, ticket, backup, branding, db
from server.db import connect
from server.errors import ValidationError

ROOT = Path(__file__).resolve().parent.parent
JPEG = (Path(__file__).parent / "fixture_logo.jpg").read_bytes()


def png(w=24, h=24, rgba=True, shade=0):
    """A small synthetic PNG (RGBA or RGB, 8-bit) with varied filter-free rows."""
    n = 4 if rgba else 3
    rows = b"".join(b"\x00" + b"".join(bytes((x * 9 % 256, (y * 7 + shade) % 256, 120, 200)[:n]) for x in range(w)) for y in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6 if rgba else 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def b64(data):
    return {"data": base64.b64encode(data).decode()}


class CompanyProfile(Base):
    def test_new_agency_has_no_built_in_identity(self):
        s = services.get_settings(self.conn)
        for k in ("legal_name", "business_website", "business_registration", "logo_file", "pay_account_number", "pay_upi_number", "pay_ifsc"):
            self.assertEqual(s[k], "", k)
        self.assertEqual(s["setup_completed"], "0")

    def test_profile_saves_and_validates(self):
        s = services.update_settings(self.conn, {
            "business_name": "Sunrise Travels", "legal_name": "Sunrise Travels Private Limited", "business_country": "India",
            "business_state": "Kerala", "business_phone": "+91 90000 00001", "business_email": "accounts@sunrise.example",
            "business_website": "www.sunrise.example", "business_gstin": "32abcde1234f1z5", "business_registration": "IATA 00-0 0000 0",
            "receipt_footer": "Thanks!", "brand_color": "#0E7C66", "date_format": "DD/MM/YYYY", "currency_display": "code"})
        self.assertEqual((s["business_gstin"], s["brand_color"], s["date_format"]), ("32ABCDE1234F1Z5", "#0E7C66", "DD/MM/YYYY"))
        bad = [("business_email", "not-an-email"), ("business_website", "no spaces allowed.com x"), ("business_gstin", "12345"),
               ("brand_color", "green"), ("brand_color", "#12345G"), ("business_phone", "call me"), ("date_format", "DD.MM.YY"),
               ("currency_display", "words"), ("invoice_prefix", ""), ("invoice_prefix", "A VERY LONG PREFIX"), ("receipt_prefix", "R<1"),
               ("business_name", ""), ("setup_completed", "maybe")]
        for k, v in bad:
            with self.assertRaises(ValidationError, msg=(k, v)):
                services.update_settings(self.conn, {k: v})
        self.assertEqual(services.get_settings(self.conn)["business_email"], "accounts@sunrise.example")  # nothing half-saved

    def test_profile_changes_are_audited(self):
        services.update_settings(self.conn, {"business_name": "Audit Travels", "brand_color": "#B91C1C"})
        rows = services.list_audit(self.conn, "settings")
        self.assertEqual(rows[0]["action"], "update")
        self.assertIn("Audit Travels", rows[0]["after_json"])


class Logos(Base):
    def test_png_and_jpeg_logos_are_stored_and_old_ones_kept(self):
        a = services.set_logo(self.conn, self.ctx, b64(png()))
        self.assertEqual((a["type"], a["width"], a["height"]), ("png", 24, 24))
        first = services.get_settings(self.conn)["logo_file"]
        b = services.set_logo(self.conn, self.ctx, b64(JPEG))
        self.assertEqual(b["type"], "jpeg")
        second = services.get_settings(self.conn)["logo_file"]
        self.assertNotEqual(first, second)
        self.assertTrue(branding.logo_path(self.ctx, first))   # previous logo kept for issued documents
        self.assertTrue(branding.logo_path(self.ctx, second))
        services.set_logo(self.conn, self.ctx, {"remove": True})
        self.assertEqual(services.get_settings(self.conn)["logo_file"], "")
        self.assertEqual([r["action"] for r in services.list_audit(self.conn, "settings")][:3], ["logo", "logo", "logo"])

    def test_bad_logos_are_refused(self):
        for bad in (b"GIF89a....", b"%PDF-1.4", png(w=8, h=8), b"\x89PNG\r\n\x1a\n" + b"x" * (1024 * 1024 + 10)):
            with self.assertRaises(ValidationError):
                services.set_logo(self.conn, self.ctx, b64(bad))
        with self.assertRaises(ValidationError):
            services.set_logo(self.conn, self.ctx, {"data": "***not base64***"})
        for name in ("../agency.db", "logo-x/../../a.png", "agency.db", ""):
            self.assertIsNone(branding.logo_path(self.ctx, name))


class BrandedDocuments(Base):
    def setUp(self):
        super().setUp()
        services.update_settings(self.conn, {"business_name": "Sunrise Travels", "brand_color": "#0E7C66", "business_gstin": "32ABCDE1234F1Z5"})
        self.bid = self.booking(pnr="ZX9Q7K")

    def test_ticket_pdf_embeds_png_and_jpeg_logos(self):
        for logo in (png(), png(rgba=False), JPEG):
            services.set_logo(self.conn, self.ctx, b64(logo))
            td = ticket.ticket_data(self.conn, self.bid)
            self.assertEqual(td["business"]["logo"], logo)
            pdf = ticket.render_pdf(td, "ticket")
            self.assertTrue(pdf.startswith(b"%PDF-1.4") and pdf.rstrip().endswith(b"%%EOF"))
            self.assertIn(b"/Subtype /Image", pdf)
            self.assertIn(b"/XObject << /Im1", pdf)
            self.assertIn(b"/DCTDecode" if logo is JPEG else b"/FlateDecode", pdf)
            if logo is not JPEG and logo[25] == 6:
                self.assertIn(b"/SMask", pdf)  # transparency kept

    def test_ticket_pdf_without_logo_and_no_built_in_slogan(self):
        pdf = ticket.render_pdf(ticket.ticket_data(self.conn, self.bid), "ticket")
        self.assertNotIn(b"/Subtype /Image", pdf)
        self.assertNotIn(b"Journey, Our Care", zlib.decompress(pdf.split(b"stream\n", 1)[1].split(b"\nendstream", 1)[0]))

    def test_issued_invoice_keeps_its_snapshot_after_profile_edits(self):
        services.set_logo(self.conn, self.ctx, b64(png()))
        old_logo = services.get_settings(self.conn)["logo_file"]
        iid = services.create_invoice(self.conn, self.bid, {})
        inv = services.get_invoice(self.conn, iid)
        number, biz = inv["number"], dict(inv["doc"]["business"])
        self.assertTrue(number.startswith("INV/"))
        self.assertEqual((biz["business_name"], biz["logo_file"], biz["brand_color"]), ("Sunrise Travels", old_logo, "#0E7C66"))
        dash = finance.dashboard(self.conn, "2000-01-01", "2099-12-31")["summary"]
        services.update_settings(self.conn, {"business_name": "Renamed Travels", "invoice_prefix": "SR/", "invoice_footer": "New footer",
                                             "brand_color": "#B91C1C", "business_gstin": "29ABCDE1234F1Z5", "date_format": "YYYY-MM-DD"})
        services.set_logo(self.conn, self.ctx, b64(JPEG))
        again = services.get_invoice(self.conn, iid)
        self.assertEqual((again["number"], again["doc"]["business"]), (number, biz))
        self.assertTrue(branding.logo_path(self.ctx, old_logo))  # the issued invoice can still show its logo
        self.assertEqual(finance.dashboard(self.conn, "2000-01-01", "2099-12-31")["summary"], dash)  # cosmetic edits never move money
        services.cancel_invoice(self.conn, iid, {"reason": "re-issue"})
        new = services.get_invoice(self.conn, services.create_invoice(self.conn, self.bid, {}))
        self.assertTrue(new["number"].startswith("SR/"))
        self.assertEqual(new["doc"]["business"]["business_name"], "Renamed Travels")

    def test_receipt_and_booking_prefixes_apply_to_new_records_only(self):
        r1 = self.txn(kind="customer_receipt", customer_id=self.cust, amount="100", allocations=[{"booking_id": self.bid, "amount": "100"}])
        before = services.get_transaction(self.conn, r1)["txn_no"]
        self.assertTrue(before.startswith("RCT-"))
        bal = {a["id"]: a["balance"] for a in services.list_accounts(self.conn)}
        services.update_settings(self.conn, {"receipt_prefix": "SR-RC", "booking_prefix": "SR-"})
        r2 = self.txn(kind="customer_receipt", customer_id=self.cust, amount="50", allocations=[{"booking_id": self.bid, "amount": "50"}])
        self.assertEqual(services.get_transaction(self.conn, r1)["txn_no"], before)
        self.assertEqual(services.get_transaction(self.conn, r2)["txn_no"], "SR-RC-000002")  # counter continues: never reuses a number
        new_bid = self.booking(pnr="ZX9Q7L")
        ref = self.conn.execute("SELECT ref FROM bookings WHERE id = ?", (new_bid,)).fetchone()["ref"]
        old_ref = self.conn.execute("SELECT ref FROM bookings WHERE id = ?", (self.bid,)).fetchone()["ref"]
        self.assertTrue(ref.startswith("SR-") and old_ref.startswith("BK-"), (ref, old_ref))
        after = {a["id"]: a["balance"] for a in services.list_accounts(self.conn)}
        self.assertEqual(after[self.cash] - bal[self.cash], 5000)

    def test_backup_and_restore_keep_logos(self):
        services.set_logo(self.conn, self.ctx, b64(png()))
        name = services.get_settings(self.conn)["logo_file"]
        b = backup.create_backup(self.ctx, "manual", "logo test")
        self.assertIn("branding/" + name, json.loads(__import__("zipfile").ZipFile(self.ctx.backup_dir / b["name"]).read("manifest.json"))["files"])
        shutil.rmtree(self.ctx.data_dir / "branding")
        self.conn.close()
        backup.restore_backup(self.ctx, self.ctx.backup_dir / b["name"])
        self.conn = connect(self.ctx.db_path)
        self.assertTrue(branding.logo_path(self.ctx, name))


class AgencyIsolation(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ta-iso-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_settings_and_logo_of_one_agency_never_reach_another(self):
        from server.web import Context
        ctxs = {}
        for name in ("Agency One", "Agency Two"):
            r = subprocess.run([sys.executable, "-B", str(ROOT / "run.py"), "--data", str(self.tmp / name), "--create-agency"], capture_output=True, text=True,
                               input=json.dumps({"agency_name": name, "username": "owner", "password": "Synthetic-Pass-123"}), timeout=60,
                               env=dict(os.environ, TACRM_NO_OPEN="1"))
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            ctxs[name] = Context(self.tmp / name)
        one, two = connect(ctxs["Agency One"].db_path), connect(ctxs["Agency Two"].db_path)
        try:
            services.update_settings(one, {"legal_name": "One Pvt Ltd", "brand_color": "#B91C1C", "invoice_prefix": "ONE/",
                                           "pay_account_number": "123456789012", "setup_completed": "1"})
            services.set_logo(one, ctxs["Agency One"], b64(png(shade=50)))
            logo_one = services.get_settings(one)["logo_file"]
            s2 = services.get_settings(two)
            self.assertEqual((s2["business_name"], s2["legal_name"], s2["brand_color"], s2["invoice_prefix"], s2["pay_account_number"], s2["logo_file"], s2["setup_completed"]),
                             ("Agency Two", "", "#1B3FCC", "INV/", "", "", "0"))
            self.assertIsNone(branding.logo_path(ctxs["Agency Two"], logo_one))  # the other agency's folder does not have it
            self.assertFalse((self.tmp / "Agency Two" / "branding").exists())
        finally:
            one.close()
            two.close()


class LogoRoutes(Base):
    def test_logo_upload_and_download_need_login_and_refuse_paths(self):
        import http.client
        import socket
        import threading
        from http.server import ThreadingHTTPServer
        from server import web, auth
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        self.ctx.port = port
        auth.setup_owner(self.conn, {"username": "owner", "password": "Synthetic-Pass-123", "setup_code": "123456"}, "123456")
        httpd = ThreadingHTTPServer(("127.0.0.1", port), web.make_handler(self.ctx, log=lambda *a: None))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        cookie = {}

        def call(method, path, body=None):
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
            h = {"Host": "127.0.0.1:%d" % port, "Content-Type": "application/json", "X-QT-CSRF": "1"}
            if cookie:
                h["Cookie"] = cookie["v"]
            c.request(method, path, json.dumps(body) if body is not None else None, h)
            r = c.getresponse()
            data = r.read()
            sc = r.getheader("Set-Cookie")
            if sc and "qt_session=" in sc:
                cookie["v"] = sc.split(";")[0]
            ctype = r.getheader("Content-Type")
            c.close()
            return r.status, data, ctype
        try:
            self.assertEqual(call("POST", "/api/settings/logo", b64(png()))[0], 401)
            self.assertEqual(call("GET", "/api/branding/logo")[0], 401)
            self.assertEqual(call("POST", "/api/auth/login", {"username": "owner", "password": "Synthetic-Pass-123"})[0], 200)
            self.assertEqual(call("GET", "/api/branding/logo")[0], 404)
            st, body, _ = call("POST", "/api/settings/logo", b64(png()))
            self.assertEqual(st, 200)
            name = json.loads(body)["settings"]["logo_file"]
            st, data, ctype = call("GET", "/api/branding/logo")
            self.assertEqual((st, ctype, data), (200, "image/png", png()))
            self.assertEqual(call("GET", "/api/branding/logo?f=" + name)[0], 200)
            for evil in ("../agency.db", "..%2Fagency.db", "logo-..%2F..%2Fagency.db", "agency.db"):
                self.assertEqual(call("GET", "/api/branding/logo?f=" + evil)[0], 404, evil)
            self.assertEqual(call("POST", "/api/settings/logo", {"data": base64.b64encode(b"GIF89a").decode()})[0], 400)
        finally:
            httpd.shutdown()
            httpd.server_close()
