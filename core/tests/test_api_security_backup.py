"""HTTP API, authentication, security and backup/restore tests."""
import base64
import http.client
import json
import shutil
import socket
import tempfile
import threading
import unittest
import zipfile
from http.server import ThreadingHTTPServer
from pathlib import Path

import helpers  # noqa: F401  (sets sys.path)
from server import web, auth, backup, services
from server.db import connect


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class ApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="qt-api-"))
        cls.port = free_port()
        cls.ctx = web.Context(cls.tmp, "127.0.0.1", cls.port)
        cls.ctx.init()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", cls.port), web.make_handler(cls.ctx, log=lambda *a: None))
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.cookie = None

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def call(self, method, path, body=None, headers=None, csrf=True, cookie=True, host=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=20)
        h = {"Host": host or "127.0.0.1:%d" % self.port}
        if body is not None:
            h["Content-Type"] = "application/json"
        if csrf and method != "GET":
            h["X-QT-CSRF"] = "1"
        if cookie and ApiTests.cookie:
            h["Cookie"] = ApiTests.cookie
        h.update(headers or {})
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        data = r.read()
        sc = r.getheader("Set-Cookie")
        if sc and "qt_session=" in sc:
            val = sc.split(";")[0]
            ApiTests.cookie = val if val != "qt_session=" else None
        try:
            parsed = json.loads(data) if data and "json" in (r.getheader("Content-Type") or "") else data
        except ValueError:
            parsed = data
        c.close()
        return r.status, parsed, r

    def login(self):
        st, _, _ = self.call("POST", "/api/auth/login", {"username": "owner", "password": "Str0ng-Passw0rd!"})
        self.assertEqual(st, 200)

    # tests run in alphabetical order; test_a_* set up the owner
    def test_a1_setup_requires_code_and_strong_password(self):
        st, b, _ = self.call("GET", "/api/auth/status")
        self.assertTrue(b["setup_needed"])
        st, b, _ = self.call("POST", "/api/auth/setup", {"username": "owner", "password": "Str0ng-Passw0rd!", "setup_code": "000000x"})
        self.assertEqual(st, 400)
        st, b, _ = self.call("POST", "/api/auth/setup", {"username": "owner", "password": "short", "setup_code": self.ctx.setup_code})
        self.assertEqual(st, 400)
        st, b, _ = self.call("POST", "/api/auth/setup", {"username": "owner", "password": "Str0ng-Passw0rd!", "setup_code": self.ctx.setup_code})
        self.assertEqual(st, 200, b)
        st, b, _ = self.call("POST", "/api/auth/setup", {"username": "x", "password": "Another-Passw0rd", "setup_code": "1"})
        self.assertIn(st, (400, 403))

    def test_a2_password_not_stored_in_plain_text(self):
        conn = connect(self.ctx.db_path)
        row = conn.execute("SELECT pw_hash FROM owner").fetchone()
        conn.close()
        self.assertNotIn("Str0ng-Passw0rd!", row["pw_hash"])
        self.assertTrue(row["pw_hash"].startswith("pbkdf2_sha256$"))
        raw = self.ctx.db_path.read_bytes()
        self.assertNotIn(b"Str0ng-Passw0rd!", raw)

    def test_b_requires_auth(self):
        saved = ApiTests.cookie
        ApiTests.cookie = None
        st, b, _ = self.call("GET", "/api/dashboard")
        self.assertEqual(st, 401)
        st, b, _ = self.call("GET", "/api/customers", headers={"Cookie": "qt_session=forged"}, cookie=False)
        self.assertEqual(st, 401)
        ApiTests.cookie = saved

    def test_c_bad_login_throttled(self):
        saved = ApiTests.cookie
        for _ in range(5):
            st, _, _ = self.call("POST", "/api/auth/login", {"username": "owner", "password": "wrong-password"})
            self.assertEqual(st, 401)
        st, b, _ = self.call("POST", "/api/auth/login", {"username": "owner", "password": "Str0ng-Passw0rd!"})
        self.assertEqual(st, 429)
        auth.throttle.ok()
        ApiTests.cookie = saved

    def test_d_csrf_and_host_checks(self):
        self.login()
        st, b, _ = self.call("POST", "/api/customers", {"name": "X"}, csrf=False)
        self.assertEqual(st, 403)
        st, b, _ = self.call("POST", "/api/customers", {"name": "X"}, headers={"Origin": "http://evil.example"})
        self.assertEqual(st, 403)
        st, b, _ = self.call("GET", "/api/auth/status", host="evil.example:%d" % self.port)
        self.assertEqual(st, 421)

    def test_e_security_headers(self):
        st, b, r = self.call("GET", "/")
        self.assertEqual(st, 200)
        self.assertIn("default-src 'self'", r.getheader("Content-Security-Policy"))
        self.assertEqual(r.getheader("X-Frame-Options"), "DENY")
        st, b, r = self.call("GET", "/../server/db.py")
        self.assertNotIn(b"SCHEMA_V1", b if isinstance(b, bytes) else b"")

    def test_f_full_flow(self):
        self.login()
        st, cust, _ = self.call("POST", "/api/customers", {"name": "Api Customer", "phone": "9999988888"})
        self.assertEqual(st, 201, cust)
        cid = cust["customer"]["id"]
        st, sup, _ = self.call("POST", "/api/suppliers", {"name": "Api Portal"})
        sid = sup["supplier"]["id"]
        st, meta, _ = self.call("GET", "/api/meta")
        cash = meta["accounts"][0]["id"]
        st, bk, _ = self.call("POST", "/api/bookings", {
            "customer_id": cid, "supplier_id": sid, "pnr": "API001", "status": "ticketed", "booking_date": "2026-10-15",
            "purchase_cost": "20000", "selling_price": "23500.50", "commission": "120",
            "passengers": [{"name": "API PAX", "ticket_number": "065-1234567890"}],
            "segments": [{"airline": "Saudia", "flight_no": "SV 773", "origin": "COK", "destination": "JED", "dep_at": "2026-11-05T13:20"}]})
        self.assertEqual(st, 201, bk)
        bid = bk["booking"]["id"]
        self.assertEqual(bk["calc"]["gross_profit"], 350050 + 12000)
        st, dup, _ = self.call("POST", "/api/bookings", {"customer_id": cid, "pnr": "api001", "passengers": [{"name": "Z"}]})
        self.assertEqual(st, 409)
        self.assertEqual(dup["code"], "duplicate_pnr")
        st, t, _ = self.call("POST", "/api/transactions", {"kind": "customer_receipt", "amount": "10000", "account_id": cash,
                                                            "booking_id": bid, "idem_key": "k1", "reference": "UPI-77", "method": "upi",
                                                            "txn_date": "2026-10-15"})
        self.assertEqual(st, 201, t)
        st, t2, _ = self.call("POST", "/api/transactions", {"kind": "customer_receipt", "amount": "10000", "account_id": cash,
                                                             "booking_id": bid, "idem_key": "k1", "reference": "UPI-77", "method": "upi",
                                                             "txn_date": "2026-10-15"})
        self.assertEqual(st, 200)
        self.assertTrue(t2["idempotent_replay"])
        st, b, _ = self.call("GET", "/api/bookings/%d" % bid)
        self.assertEqual(b["calc"]["customer_outstanding"], 1350050)
        st, res, _ = self.call("GET", "/api/bookings?q=API001")
        self.assertEqual(res[0]["id"], bid)
        st, res, _ = self.call("GET", "/api/bookings?q=065-1234567890")
        self.assertEqual(len(res), 1)
        st, res, _ = self.call("GET", "/api/bookings?q=99999")  # phone digits
        self.assertEqual(len(res), 1)
        st, res, _ = self.call("GET", "/api/bookings?airline=saudia&origin=COK")
        self.assertEqual(len(res), 1)
        st, res, _ = self.call("GET", "/api/bookings?q=NOPE99")
        self.assertEqual(res, [])
        st, d, _ = self.call("GET", "/api/dashboard?from=2026-10-01&to=2026-10-31")
        self.assertEqual(st, 200)
        self.assertEqual(d["summary"]["cash"]["customer_receipts"], 1000000)
        st, csvdata, r = self.call("GET", "/api/reports/sales?format=csv")
        self.assertEqual(st, 200)
        self.assertIn(b"23500.50", csvdata)
        # upload: allowed and blocked types
        st, up, _ = self.call("POST", "/api/bookings/%d/documents" % bid, {"name": "ticket.pdf", "data": base64.b64encode(b"%PDF-1.4 test").decode()})
        self.assertEqual(st, 201)
        st, up2, _ = self.call("POST", "/api/bookings/%d/documents" % bid, {"name": "evil.html", "data": base64.b64encode(b"<script>").decode()})
        self.assertEqual(st, 400)
        st, file_, r = self.call("GET", "/api/documents/%d" % up["id"])
        self.assertEqual(file_, b"%PDF-1.4 test")
        self.assertIn("attachment", r.getheader("Content-Disposition"))
        saved = ApiTests.cookie
        ApiTests.cookie = None
        st, _, _ = self.call("GET", "/api/documents/%d" % up["id"])
        self.assertEqual(st, 401)  # customer documents are never served without login
        ApiTests.cookie = saved
        # posted transaction cannot be edited
        st, e, _ = self.call("PATCH", "/api/transactions/%d" % t["id"], {"amount": "1"})
        self.assertEqual(st, 400)
        st, e, _ = self.call("PATCH", "/api/transactions/%d" % t["id"], {"notes": "Paid by brother"})
        self.assertEqual(st, 200)
        self.assertEqual(e["notes"], "Paid by brother")

    def test_g_backup_and_restore(self):
        self.login()
        st, b, _ = self.call("POST", "/api/backups", {"note": "test"})
        self.assertEqual(st, 201, b)
        name = b["name"]
        st, v, _ = self.call("POST", "/api/backups/verify", {"name": name})
        self.assertTrue(v["ok"], v)
        # add data after the backup
        st, cust, _ = self.call("POST", "/api/customers", {"name": "Added After Backup"})
        st, lst, _ = self.call("GET", "/api/customers?q=Added")
        self.assertEqual(len(lst), 1)
        st, r, _ = self.call("POST", "/api/backups/restore", {"name": name})
        self.assertEqual(st, 400)  # needs explicit confirmation
        st, r, _ = self.call("POST", "/api/backups/restore", {"name": name, "confirm": "RESTORE"})
        self.assertEqual(st, 200, r)
        self.assertTrue(r["safety_backup"].startswith("backup-pre-restore"))
        self.login()
        st, lst, _ = self.call("GET", "/api/customers?q=Added")
        self.assertEqual(lst, [])
        st, lst, _ = self.call("GET", "/api/customers?q=Api")
        self.assertEqual(len(lst), 1)
        st, docs, _ = self.call("GET", "/api/bookings?q=API001")
        bk = self.call("GET", "/api/bookings/%d" % docs[0]["id"])[1]
        self.assertEqual(len(bk["documents"]), 1)  # uploads restored too
        # the safety backup still contains the data added after the backup
        st, all_b, _ = self.call("GET", "/api/backups")
        names = [x["name"] for x in all_b["backups"]]
        self.assertIn(name, names)
        self.assertIn(r["safety_backup"], names)


class BackupUnitTests(helpers.Base):
    def test_backup_never_overwrites_and_detects_tampering(self):
        b1 = backup.create_backup(self.ctx)
        b2 = backup.create_backup(self.ctx)
        self.assertNotEqual(b1["name"], b2["name"])
        p = self.ctx.backup_dir / b1["name"]
        self.assertTrue(backup.verify_backup(p)["ok"])
        bad = self.ctx.backup_dir / "backup-manual-tampered.zip"
        with zipfile.ZipFile(p) as zin, zipfile.ZipFile(bad, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "agency.db":
                    data = data[:-100] + bytes(x ^ 0xFF for x in data[-100:])  # really change the bytes
                zout.writestr(item, data)
        res = backup.verify_backup(bad)
        self.assertFalse(res["ok"])
        with self.assertRaises(Exception):
            backup.restore_backup(self.ctx, bad)
        not_zip = self.ctx.backup_dir / "backup-manual-junk.zip"
        not_zip.write_bytes(b"junk")
        self.assertFalse(backup.verify_backup(not_zip)["ok"])

    def test_prune_only_auto(self):
        for _ in range(4):
            backup.create_backup(self.ctx, "auto")
        backup.create_backup(self.ctx, "manual")
        backup.prune_auto_backups(self.ctx, 2)
        names = [b["name"] for b in backup.list_backups(self.ctx)]
        self.assertEqual(sum(1 for n in names if "-auto-" in n), 2)
        self.assertEqual(sum(1 for n in names if "-manual-" in n), 1)

    def test_data_persists_across_connections(self):
        bid = self.booking()
        self.conn.close()
        c2 = connect(self.ctx.db_path)
        self.assertEqual(c2.execute("SELECT ref FROM bookings WHERE id = ?", (bid,)).fetchone()["ref"], "BK-26-00001")
        self.conn = c2

    def test_atomic_rollback_on_error(self):
        before = self.conn.execute("SELECT COUNT(*) FROM bookings").fetchone()[0]
        with self.assertRaises(Exception):
            self.booking(passengers=[{"name": "OK"}, {"name": "", "ticket_number": "123"}])
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM bookings").fetchone()[0], before)


if __name__ == "__main__":
    unittest.main()
