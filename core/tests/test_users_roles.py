"""Shared office use: staff accounts and roles, permission checks on every route, who created or changed records,
edit conflicts between two staff, duplicate protection under simultaneous saves, and cloud-mode security
(Secure cookies, the public host, the visitor address behind the proxy). Synthetic data only."""
import http.client
import json
import os
import socket
import threading
import unittest
from unittest import mock

from helpers import Base
from server import access, auth, db, services, web
from server.errors import Conflict, Forbidden, ValidationError

PASSWORDS = {"owner": "Owner-Pass-123", "admin1": "Admin-Pass-123", "staff1": "Staff-Pass-123", "acct1": "Acct-Pass-123"}


class Server(Base):
    """A real HTTP server on a free port with an owner, an admin, a booking staff member and an accountant."""
    env = {}

    def setUp(self):
        super().setUp()
        auth.throttle.ok()
        self.envp = mock.patch.dict(os.environ, dict(self.env, TACRM_NO_OPEN="1"))
        self.envp.start()
        s = socket.socket(); s.bind(("127.0.0.1", 0)); self.port = s.getsockname()[1]; s.close()
        self.ctx = web.Context(self.tmp)   # re-read the mode from the environment
        self.ctx.port = self.port
        auth.setup_owner(self.conn, {"username": "owner", "password": PASSWORDS["owner"], "setup_code": "1"}, "1")
        self.owner = {"id": 1, "username": "owner", "role": "owner"}
        self.ids = {"owner": 1}
        for name, role in (("admin1", "admin"), ("staff1", "booking_staff"), ("acct1", "accountant")):
            u = auth.create_user(self.conn, self.owner, {"username": name, "role": role, "password": "Temp-Pass-123"})
            self.conn.execute("UPDATE users SET pw_hash = ?, must_change_password = 0 WHERE id = ?", (auth.hash_password(PASSWORDS[name]), u["id"]))
            self.ids[name] = u["id"]
        self.httpd = web.Server(("127.0.0.1", self.port), web.make_handler(self.ctx, log=lambda *a: None))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.cookies = {}

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.envp.stop()
        auth.throttle.ok()
        super().tearDown()

    def call(self, who, method, path, body=None, host=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        h = {"Host": host or "127.0.0.1:%d" % self.port, "Content-Type": "application/json", "X-QT-CSRF": "1"}
        if who and who in self.cookies:
            h["Cookie"] = self.cookies[who]
        h.update(headers or {})
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        data = r.read()
        c.close()
        try:
            data = json.loads(data)
        except ValueError:
            pass
        return r.status, data, r

    def login(self, who, **kw):
        st, data, r = self.call(None, "POST", "/api/auth/login", {"username": who, "password": PASSWORDS[who]}, **kw)
        self.assertEqual(st, 200, data)
        self.cookies[who] = r.getheader("Set-Cookie").split(";")[0]
        return r


class RolesAndPermissions(Server):
    def test_every_route_declares_a_permission(self):
        for method, rx, fn, public, exclusive, perm in web.ROUTES:
            if public:
                self.assertTrue(rx.pattern.startswith("^/api/auth/"), rx.pattern)
            else:
                self.assertIn(perm, access.ALL, "%s %s" % (method, rx.pattern))
        with self.assertRaises(RuntimeError):
            web.route("GET", r"/api/oops")          # a new route without a permission does not load

    def test_role_matrix(self):
        m = access.PERMISSIONS
        self.assertEqual(m["owner"], access.ALL)
        self.assertEqual(m["admin"], access.ALL - {access.RESTORE})
        self.assertNotIn(access.MONEY, m["booking_staff"])
        self.assertNotIn(access.REPORTS, m["booking_staff"])
        self.assertIn(access.RECEIPTS, m["booking_staff"])
        self.assertNotIn(access.BOOKINGS, m["accountant"])
        self.assertIn(access.MONEY, m["accountant"])
        self.assertNotIn(access.SETTINGS, m["accountant"])
        self.assertNotIn(access.USERS, m["accountant"])

    def test_server_enforces_roles(self):
        for who in PASSWORDS:
            self.login(who)
        bid = self.booking(pnr="ROLE01")
        expect = [   # (method, path, body, {who: allowed?})
            ("GET", "/api/dashboard", None, {"owner": 1, "admin1": 1, "staff1": 0, "acct1": 1}),
            ("GET", "/api/bookings", None, {"owner": 1, "admin1": 1, "staff1": 1, "acct1": 1}),
            ("POST", "/api/customers", {"name": "New Customer"}, {"owner": 1, "admin1": 1, "staff1": 1, "acct1": 0}),
            ("GET", "/api/accounts", None, {"owner": 1, "admin1": 1, "staff1": 0, "acct1": 1}),
            ("GET", "/api/reports/profit_loss", None, {"owner": 1, "admin1": 1, "staff1": 0, "acct1": 1}),
            ("PUT", "/api/settings", {"business_phone": "+91 90000 00000"}, {"owner": 1, "admin1": 1, "staff1": 0, "acct1": 0}),
            ("GET", "/api/users", None, {"owner": 1, "admin1": 1, "staff1": 0, "acct1": 0}),
            ("GET", "/api/backups", None, {"owner": 1, "admin1": 1, "staff1": 0, "acct1": 0}),
            ("POST", "/api/backups/restore", {"confirm": "no"}, {"owner": 1, "admin1": 0, "staff1": 0, "acct1": 0}),
            ("POST", "/api/bookings/%d/invoice" % bid, {}, {"staff1": 0}),
            ("GET", "/api/bookings/%d/ticket.pdf" % bid, None, {"owner": 1, "admin1": 1, "staff1": 1, "acct1": 1}),
            ("POST", "/api/tax-profiles", {"name": "T", "rate": "18", "basis": "service_charge"}, {"acct1": 1, "staff1": 0}),
        ]
        for method, path, body, rule in expect:
            for who, allowed in rule.items():
                st, data, _ = self.call(who, method, path, body)
                if allowed:
                    self.assertNotIn(st, (401, 403), "%s %s %s -> %s %s" % (who, method, path, st, data))
                else:
                    self.assertEqual(st, 403, "%s %s %s -> %s %s" % (who, method, path, st, data))

    def test_booking_staff_record_receipts_only(self):
        self.login("staff1")
        bid = self.booking(pnr="RCPT01")
        receipt = {"kind": "customer_receipt", "txn_date": "2026-10-11", "account_id": self.cash, "method": "cash",
                   "customer_id": self.cust, "amount": "1000", "allocations": [{"booking_id": bid, "amount": "1000"}]}
        st, data, _ = self.call("staff1", "POST", "/api/transactions", receipt)
        self.assertEqual(st, 201, data)
        self.assertEqual(data["created_by"], self.ids["staff1"])
        st, _, _ = self.call("staff1", "POST", "/api/transactions", {"kind": "expense", "txn_date": "2026-10-11", "account_id": self.cash,
                                                                     "category": "Rent", "amount": "50"})
        self.assertEqual(st, 403)
        st, _, _ = self.call("staff1", "POST", "/api/transactions/%d/reverse" % data["id"], {"reason": "x"})
        self.assertEqual(st, 403)

    def test_signed_out_and_unknown_session(self):
        self.assertEqual(self.call(None, "GET", "/api/bookings")[0], 401)
        self.assertEqual(self.call(None, "GET", "/api/bookings", headers={"Cookie": "qt_session=forged"})[0], 401)


class StaffAccounts(Server):
    def test_owner_and_admin_rules(self):
        admin = dict(self.owner, id=self.ids["admin1"], role="admin", username="admin1")
        with self.assertRaises(Forbidden):      # only the owner creates admins
            auth.create_user(self.conn, admin, {"username": "admin2", "role": "admin", "password": "Temp-Pass-123"})
        with self.assertRaises(ValidationError):  # nobody creates a second owner
            auth.create_user(self.conn, self.owner, {"username": "owner2", "role": "owner", "password": "Temp-Pass-123"})
        with self.assertRaises(Forbidden):      # an admin cannot touch the owner
            auth.update_user(self.conn, admin, 1, {"active": 0})
        with self.assertRaises(Forbidden):      # nor reset another admin's password
            auth.reset_user_password(self.conn, admin, self.ids["admin1"], {"password": "Other-Pass-123"})
        u = auth.create_user(self.conn, admin, {"username": "staff2", "role": "booking_staff", "password": "Temp-Pass-123"})
        self.assertEqual((u["role"], u["must_change_password"], u["created_by"]), ("booking_staff", 1, self.ids["admin1"]))
        with self.assertRaises(Conflict):
            auth.create_user(self.conn, admin, {"username": "STAFF2", "role": "accountant", "password": "Temp-Pass-123"})
        with self.assertRaises(Forbidden):      # no promoting staff to admin without the owner
            auth.update_user(self.conn, admin, u["id"], {"role": "admin"})
        with self.assertRaises(Forbidden):
            auth.update_user(self.conn, self.owner, 1, {"role": "admin"})   # the owner cannot demote themself

    def test_deactivate_and_role_change_sign_out(self):
        self.login("staff1")
        self.assertEqual(self.call("staff1", "GET", "/api/bookings")[0], 200)
        auth.update_user(self.conn, self.owner, self.ids["staff1"], {"role": "accountant"})
        self.assertEqual(self.call("staff1", "GET", "/api/bookings")[0], 401)
        self.login("staff1")
        auth.update_user(self.conn, self.owner, self.ids["staff1"], {"active": 0})
        self.assertEqual(self.call("staff1", "GET", "/api/bookings")[0], 401)
        st, _, _ = self.call(None, "POST", "/api/auth/login", {"username": "staff1", "password": PASSWORDS["staff1"]})
        self.assertEqual(st, 401)

    def test_temporary_password_must_be_changed(self):
        auth.reset_user_password(self.conn, self.owner, self.ids["staff1"], {"password": "Temp-Pass-999"})
        PASSWORDS["staff_tmp"] = "Temp-Pass-999"
        st, _, r = self.call(None, "POST", "/api/auth/login", {"username": "staff1", "password": "Temp-Pass-999"})
        self.assertEqual(st, 200)
        self.cookies["staff1"] = r.getheader("Set-Cookie").split(";")[0]
        self.assertEqual(self.call("staff1", "GET", "/api/bookings")[0], 403)
        st, me, _ = self.call("staff1", "GET", "/api/auth/status")
        self.assertEqual(me["user"]["must_change_password"], 1)
        st, data, _ = self.call("staff1", "POST", "/api/auth/password", {"current_password": "Temp-Pass-999", "new_password": "Brand-New-Pass-1"})
        self.assertEqual(st, 200, data)
        st, _, r = self.call(None, "POST", "/api/auth/login", {"username": "staff1", "password": "Brand-New-Pass-1"})
        self.cookies["staff1"] = r.getheader("Set-Cookie").split(";")[0]
        self.assertEqual(self.call("staff1", "GET", "/api/bookings")[0], 200)

    def test_one_users_wrong_passwords_do_not_lock_out_others(self):
        for _ in range(5):
            self.call(None, "POST", "/api/auth/login", {"username": "staff1", "password": "wrong-password"})
        st, _, _ = self.call(None, "POST", "/api/auth/login", {"username": "staff1", "password": PASSWORDS["staff1"]})
        self.assertEqual(st, 429)
        self.login("acct1")                      # a colleague still signs in
        self.login("owner")

    def test_owner_password_kept_in_step_for_rollback(self):
        self.login("owner")
        st, _, _ = self.call("owner", "POST", "/api/auth/password", {"current_password": PASSWORDS["owner"], "new_password": "Owner-New-Pass-1"})
        self.assertEqual(st, 200)
        row = self.conn.execute("SELECT pw_hash FROM owner WHERE id = 1").fetchone()
        self.assertTrue(auth.verify_password("Owner-New-Pass-1", row["pw_hash"]))


class Attribution(Server):
    def test_records_and_audit_name_the_user(self):
        self.login("staff1")
        self.login("acct1")
        st, b, _ = self.call("staff1", "POST", "/api/bookings", {
            "customer_id": self.cust, "supplier_id": self.sup, "pnr": "WHO001", "booking_date": "2026-10-10", "status": "ticketed",
            "currency": "INR", "exchange_rate": "1", "purchase_cost": "100", "selling_price": "120",
            "passengers": [{"name": "TEST PAX", "pax_type": "ADT"}], "segments": []})
        self.assertEqual(st, 201, b)
        bid = b["booking"]["id"]
        st, t, _ = self.call("acct1", "POST", "/api/transactions", {"kind": "supplier_payment", "txn_date": "2026-10-11",
                                                                   "account_id": self.cash, "supplier_id": self.sup, "amount": "100"})
        self.assertEqual(st, 201, t)
        st, rv, _ = self.call("acct1", "POST", "/api/transactions/%d/reverse" % t["id"], {"reason": "test"})
        self.assertIn(st, (200, 201), rv)
        row = self.conn.execute("SELECT created_by, updated_by FROM bookings WHERE id = ?", (bid,)).fetchone()
        self.assertEqual(tuple(row), (self.ids["staff1"], self.ids["staff1"]))
        rows = self.conn.execute("SELECT created_by FROM transactions ORDER BY id").fetchall()
        self.assertEqual([r[0] for r in rows], [self.ids["acct1"]] * 2)
        audit = self.conn.execute("SELECT username, ip FROM audit_log WHERE entity = 'transaction' AND action = 'reverse'").fetchone()
        self.assertEqual(tuple(audit), ("acct1", "127.0.0.1"))


class EditConflicts(Server):
    def test_second_save_of_a_stale_copy_is_refused(self):
        self.login("staff1")
        self.login("admin1")
        st, c, _ = self.call("staff1", "GET", "/api/customers/%d" % self.cust)
        version = c["customer"]["row_version"]
        st, _, _ = self.call("admin1", "PUT", "/api/customers/%d" % self.cust, {"name": "Ahmed Khan", "phone": "+91 98470 11112", "row_version": version})
        self.assertEqual(st, 200)
        st, err, _ = self.call("staff1", "PUT", "/api/customers/%d" % self.cust, {"name": "Ahmed K", "row_version": version})
        self.assertEqual(st, 409)
        self.assertEqual(err["code"], "edit_conflict")
        self.assertIn("admin1", err["error"])
        self.assertEqual(self.conn.execute("SELECT name FROM customers WHERE id = ?", (self.cust,)).fetchone()[0], "Ahmed Khan")

    def test_booking_versions(self):
        bid = self.booking(pnr="VER001")
        b = services.get_booking(self.conn, bid)["booking"]
        self.assertEqual(b["row_version"], 1)
        data = {"customer_id": self.cust, "supplier_id": self.sup, "pnr": "VER001", "booking_date": "2026-10-10", "status": "ticketed",
                "currency": "INR", "exchange_rate": "1", "purchase_cost": "10000", "selling_price": "12500",
                "passengers": [{"name": "AHMED KHAN", "pax_type": "ADT"}], "segments": []}
        services.save_booking(self.conn, dict(data, row_version=1), bid)
        self.assertEqual(services.get_booking(self.conn, bid)["booking"]["row_version"], 2)
        with self.assertRaises(Conflict):
            services.save_booking(self.conn, dict(data, selling_price="13000", row_version=1), bid)


class SimultaneousSaves(Server):
    def test_same_submission_from_two_computers_posts_once(self):
        for who in ("staff1", "admin1"):
            self.login(who)
        receipt = {"kind": "customer_receipt", "txn_date": "2026-10-11", "account_id": self.cash, "method": "cash",
                   "customer_id": self.cust, "amount": "700", "idem_key": "form-abc-123"}
        results = []

        def post(who):
            results.append(self.call(who, "POST", "/api/transactions", receipt)[0])
        threads = [threading.Thread(target=post, args=(w,)) for w in ("staff1", "admin1", "staff1", "admin1")]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(sorted(results).count(201), 1, results)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions WHERE idem_key = 'form-abc-123'").fetchone()[0], 1)

    def test_numbers_stay_unique_under_parallel_saves(self):
        self.login("staff1")
        self.login("acct1")
        out = []

        def post(who, i):
            st, d, _ = self.call(who, "POST", "/api/transactions", {"kind": "customer_receipt", "txn_date": "2026-10-11", "account_id": self.cash,
                                                                   "method": "cash", "customer_id": self.cust, "amount": str(100 + i)})
            out.append((st, d.get("txn_no")))
        threads = [threading.Thread(target=post, args=("staff1" if i % 2 else "acct1", i)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertTrue(all(st == 201 for st, _ in out), out)
        self.assertEqual(len({n for _, n in out}), 10, out)


class CloudMode(Server):
    env = {"TACRM_MODE": "cloud", "TACRM_PUBLIC_ORIGIN": "https://crm.example.test", "TACRM_TRUST_PROXY": "1"}

    def test_secure_cookie_public_host_and_real_address(self):
        r = self.login("staff1", host="crm.example.test", headers={"X-Forwarded-For": "203.0.113.9"})
        cookie = r.getheader("Set-Cookie")
        for flag in ("HttpOnly", "SameSite=Strict", "Secure"):
            self.assertIn(flag, cookie)
        st, _, _ = self.call("staff1", "GET", "/api/bookings", host="crm.example.test")
        self.assertEqual(st, 200)
        self.assertEqual(self.call("staff1", "GET", "/api/bookings", host="evil.example.test")[0], 421)
        st, _, _ = self.call("staff1", "POST", "/api/customers", {"name": "X"}, host="crm.example.test",
                             headers={"Origin": "https://evil.example.test"})
        self.assertEqual(st, 403)
        ip = self.conn.execute("SELECT ip FROM sessions ORDER BY rowid DESC LIMIT 1").fetchone()[0]
        self.assertEqual(ip, "203.0.113.9")

    def test_pdfs_go_to_the_users_computer_not_the_server(self):
        self.login("staff1")
        bid = self.booking(pnr="CLOUD1")
        st, data, _ = self.call("staff1", "POST", "/api/bookings/%d/ticket-file" % bid, {"variant": "ticket", "action": "open"})
        self.assertEqual(st, 200)
        self.assertTrue(data["download"])
        self.assertIsNone(data["path"])
        st, pdf, r = self.call("staff1", "GET", "/api/bookings/%d/ticket.pdf" % bid)
        self.assertEqual((st, r.getheader("Content-Type")), (200, "application/pdf"))

    def test_cloud_mode_requires_https_origin(self):
        with mock.patch.dict(os.environ, {"TACRM_PUBLIC_ORIGIN": "http://crm.example.test"}):
            with self.assertRaises(SystemExit):
                web.Context(self.tmp)


class Migration(unittest.TestCase):
    def test_owner_becomes_user_one_and_rollback_keeps_login(self):
        import tempfile, shutil
        tmp = tempfile.mkdtemp(prefix="ta-v5-")
        try:
            conn = db.connect(os.path.join(tmp, "a.db"))
            db.migrate(conn)
            db.rollback(conn, 4)
            h = auth.hash_password("Old-Owner-Pass-1")
            conn.execute("INSERT INTO owner(id, username, pw_hash, created_at, updated_at) VALUES (1, 'boss', ?, 'x', 'x')", (h,))
            self.assertEqual(db.migrate(conn), 5)
            u = conn.execute("SELECT username, role, pw_hash FROM users WHERE id = 1").fetchone()
            self.assertEqual((u["username"], u["role"]), ("boss", "owner"))
            self.assertTrue(auth.login(conn, {"username": "boss", "password": "Old-Owner-Pass-1"}))
            auth.reset_password_offline(conn, "boss", "New-Owner-Pass-1")
            self.assertEqual(db.rollback(conn, 4), 4)
            row = conn.execute("SELECT pw_hash FROM owner").fetchone()
            self.assertTrue(auth.verify_password("New-Owner-Pass-1", row["pw_hash"]))
            self.assertEqual(conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")
            conn.close()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
