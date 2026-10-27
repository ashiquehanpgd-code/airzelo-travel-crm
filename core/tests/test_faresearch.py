"""Fare search: off by default; portal addresses never hold secrets; quotes keep unavailable amounts as NULL;
profit, staleness, re-check and duplicate warnings; nothing touches bookings or money; HTTP routes need a session."""
import http.client
import json
import shutil
import sqlite3
import tempfile
import threading
import unittest
from datetime import datetime, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path

from helpers import Base
from test_api_security_backup import free_port
from server import faresearch as fs, finance, services, web
from server.db import connect, current_version, migrate, remove_fare_search, MIGRATIONS
from server.errors import ApiError, ValidationError

LATEST = MIGRATIONS[-1][0]


class FareSearch(Base):
    def setUp(self):
        super().setUp()
        services.update_settings(self.conn, {"fare_search_enabled": "1"})
        fs.save_portal(self.conn, self.sup, {"login_url": "https://portal-a.example.com/login"})
        fs.save_portal(self.conn, self.sup2, {"login_url": "https://b.example.in/agent/", "search_hint": "dates as DD-MMM-YYYY"})

    def search(self, **kw):
        data = {"origin": "cok", "destination": "DXB", "trip_type": "one_way", "depart_date": "2026-11-12", "adults": 2,
                "children": 1, "infants": 0, "cabin": "Economy", "supplier_ids": [self.sup, self.sup2]}
        data.update(kw)
        return fs.create_search(self.conn, data)

    def quote(self, fid, **kw):
        data = {"supplier_id": self.sup, "airline": "Air Arabia", "flight_nos": "g9 452", "final_payable": "41000"}
        data.update(kw)
        return fs.add_quote(self.conn, fid, data)

    # ------------------------------------------------------------ switched off
    def test_off_by_default_and_refuses_searches_but_allows_portal_setup(self):
        services.update_settings(self.conn, {"fare_search_enabled": "0"})
        self.assertEqual(services.get_settings(self.conn)["fare_stale_minutes"], "15")
        with self.assertRaises(ApiError) as e:
            self.search()
        self.assertEqual((e.exception.status, e.exception.code), (403, "feature_off"))
        fs.save_portal(self.conn, self.sup, {"login_url": "https://other.example.com/"})
        self.assertEqual({p["supplier_id"]: p["login_url"] for p in fs.list_portals(self.conn)}[self.sup], "https://other.example.com/")

    def test_new_company_file_has_feature_off(self):
        tmp = Path(tempfile.mkdtemp(prefix="qt-fs-"))
        try:
            ctx = web.Context(tmp)
            ctx.init()
            c = connect(ctx.db_path)
            self.assertEqual(c.execute("SELECT value FROM settings WHERE key = 'fare_search_enabled'").fetchone()[0], "0")
            c.close()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    # ------------------------------------------------------------ portal addresses
    def test_portal_address_must_be_plain_https_without_secrets(self):
        bad = ["http://portal.example.com/", "https://user:pw@portal.example.com/", "https://portal.example.com/?password=x",
               "https://portal.example.com/login?sessionid=abc", "https://portal.example.com/#token=abc", "javascript:alert(1)",
               "https://localhost/", "", "https://portal.example.com/?otp=123"]
        for url in bad:
            with self.assertRaises(ValidationError, msg=url):
                fs.save_portal(self.conn, self.sup, {"login_url": url})
        fs.save_portal(self.conn, self.sup, {"login_url": "https://agents.example.com/b2bplus/login?lang=en"})
        audit = self.conn.execute("SELECT COUNT(*) FROM audit_log WHERE entity = 'fare_portal'").fetchone()[0]
        self.assertGreaterEqual(audit, 3)

    def test_no_password_columns_exist(self):
        for t in ("fare_portals", "fare_searches", "fare_search_suppliers", "fare_quotes"):
            cols = [r[1] for r in self.conn.execute("PRAGMA table_info(%s)" % t)]
            self.assertFalse([c for c in cols if any(x in c for x in ("pass", "otp", "cookie", "token", "secret"))], t)

    # ------------------------------------------------------------ searches
    def test_search_validation(self):
        cases = [({"origin": "COCHIN"}, "origin"), ({"destination": "COK"}, "destination"), ({"depart_date": ""}, "depart_date"),
                 ({"trip_type": "round_trip"}, "return_date"), ({"trip_type": "round_trip", "return_date": "2026-11-01"}, "return_date"),
                 ({"infants": 3, "adults": 2}, "infants"), ({"adults": 0}, "adults"), ({"adults": 8, "children": 2}, "children"),
                 ({"supplier_ids": []}, "supplier_ids"), ({"supplier_ids": [9999]}, "supplier_ids"), ({"cabin": "Luxury"}, "cabin")]
        for kw, field in cases:
            with self.assertRaises(ValidationError, msg=kw) as e:
                self.search(**kw)
            self.assertEqual(e.exception.field, field, kw)

    def test_search_saved_with_suppliers_and_is_idempotent(self):
        a = self.search(idem_key="k1")
        b = self.search(idem_key="k1")
        self.assertEqual(a, b)
        d = fs.get_search(self.conn, a)
        self.assertEqual((d["search"]["origin"], d["search"]["search_no"]), ("COK", "FS-000001"))
        self.assertEqual(sorted(s["supplier_id"] for s in d["suppliers"]), sorted([self.sup, self.sup2]))
        self.assertTrue(all(s["status"] == "pending" for s in d["suppliers"]))

    def test_inactive_portal_cannot_be_searched(self):
        fs.save_portal(self.conn, self.sup2, {"login_url": "https://b.example.in/agent/", "active": False})
        with self.assertRaises(ValidationError):
            self.search()

    def test_one_supplier_failing_does_not_touch_the_others(self):
        fid = self.search()
        fs.set_supplier_status(self.conn, fid, self.sup, {"status": "failed", "note": "session expired"})
        self.quote(fid, supplier_id=self.sup2)
        st = {s["supplier_id"]: (s["status"], s["note"]) for s in fs.get_search(self.conn, fid)["suppliers"]}
        self.assertEqual(st[self.sup], ("failed", "session expired"))
        self.assertEqual(st[self.sup2][0], "quoted")

    # ------------------------------------------------------------ quotes, profit, staleness
    def test_missing_amounts_are_unavailable_not_zero(self):
        fid = self.search()
        qid = self.quote(fid)
        row = self.conn.execute("SELECT base_fare, taxes, fees, payment_charges, commission, final_payable FROM fare_quotes WHERE id = ?", (qid,)).fetchone()
        self.assertEqual(tuple(row), (None, None, None, None, None, 4100000))
        q = fs.get_quote(self.conn, qid)
        self.assertIsNone(q["est_profit"])
        self.assertEqual(set(q["missing"]), {"base_fare", "taxes", "fees", "payment_charges", "commission"})
        with self.assertRaises(ValidationError):
            self.quote(fid, final_payable="")
        with self.assertRaises(ValidationError):
            self.quote(fid, final_payable="0")
        with self.assertRaises(ValidationError):
            self.quote(fid, supplier_id=self.sup, final_payable="-5")

    def test_profit_uses_selling_price_payable_charges_and_confirmed_commission(self):
        fid = self.search(selling_price="45000")
        q = fs.get_quote(self.conn, self.quote(fid, final_payable="41000", payment_charges="250", commission="300"))
        self.assertEqual(q["est_profit"], 405000)  # 45,000 − 41,000 − 250 + 300
        self.assertEqual(q["profit_note"], "")
        q2 = fs.get_quote(self.conn, self.quote(fid, final_payable="42000"))
        self.assertEqual(q2["est_profit"], 300000)
        self.assertIn("payment charges not entered", q2["profit_note"])
        fs.update_search(self.conn, fid, {"selling_price": ""})
        self.assertIsNone(fs.get_quote(self.conn, q2["id"])["est_profit"])

    def test_quote_supplier_must_belong_to_search(self):
        fid = self.search(supplier_ids=[self.sup])
        with self.assertRaises(ValidationError):
            self.quote(fid, supplier_id=self.sup2)

    def test_stale_after_configured_minutes_and_time_seen_rules(self):
        fid = self.search()
        old = (datetime.now() - timedelta(minutes=20)).strftime("%Y-%m-%dT%H:%M")
        fresh = fs.get_quote(self.conn, self.quote(fid))
        stale = fs.get_quote(self.conn, self.quote(fid, seen_at=old))
        self.assertFalse(fresh["stale"])
        self.assertTrue(stale["stale"])
        services.update_settings(self.conn, {"fare_stale_minutes": "30"})
        self.assertFalse(fs.get_quote(self.conn, stale["id"])["stale"])
        future = (datetime.now() + timedelta(minutes=30)).strftime("%Y-%m-%dT%H:%M")
        with self.assertRaises(ValidationError):
            self.quote(fid, seen_at=future)
        with self.assertRaises(ValidationError):
            self.quote(fid, seen_at=(datetime.now() - timedelta(days=8)).strftime("%Y-%m-%dT%H:%M"))
        with self.assertRaises(ValidationError):
            services.update_settings(self.conn, {"fare_stale_minutes": "0"})

    def test_recheck_needs_confirmation_and_keeps_the_old_quote(self):
        fid = self.search(selling_price="45000")
        qid = self.quote(fid, seen_at=(datetime.now() - timedelta(minutes=40)).strftime("%Y-%m-%dT%H:%M"), fare_family="Value")
        with self.assertRaises(ValidationError):
            fs.recheck_quote(self.conn, qid, {"final_payable": "41500"})
        new = fs.recheck_quote(self.conn, qid, {"final_payable": "41500", "confirmed": True})
        self.assertEqual((new["source"], new["recheck_of"], new["final_payable"], new["changed_from"]), ("recheck", qid, 4150000, 4100000))
        self.assertFalse(new["stale"])
        self.assertEqual(new["fare_family"], "Value")
        d = fs.get_search(self.conn, fid)
        self.assertTrue({q["id"]: q for q in d["quotes"]}[qid]["superseded"])
        with self.assertRaises(ValidationError):
            fs.delete_quote(self.conn, qid)
        same = fs.recheck_quote(self.conn, new["id"], {"final_payable": "41500", "confirmed": "1"})
        self.assertIsNone(same["changed_from"])

    def test_duplicate_warning_finds_live_bookings_on_same_route_and_day(self):
        fid = self.search()
        qid = self.quote(fid)
        self.assertEqual(fs.possible_duplicates(self.conn, qid), [])
        seg = [{"airline": "Air Arabia", "flight_no": "G9 452", "origin": "COK", "destination": "DXB", "dep_at": "2026-11-12T04:10"}]
        bid = self.booking(segments=seg, pnr="DUP111")
        self.booking(segments=seg, pnr="CAN222", status="cancelled")
        self.assertEqual([b["id"] for b in fs.possible_duplicates(self.conn, qid)], [bid])

    # ------------------------------------------------------------ no effect on money
    def test_fare_search_never_changes_bookings_or_money(self):
        bid = self.booking()
        before = (finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"],
                  self.conn.execute("SELECT COUNT(*) FROM bookings").fetchone()[0],
                  self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0])
        fid = self.search(selling_price="45000")
        qid = self.quote(fid, commission="500")
        fs.recheck_quote(self.conn, qid, {"final_payable": "40000", "confirmed": True})
        fs.delete_search(self.conn, fid)
        after = (finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"],
                 self.conn.execute("SELECT COUNT(*) FROM bookings").fetchone()[0],
                 self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0])
        self.assertEqual(before, after)
        self.assertEqual(services.get_booking(self.conn, bid)["booking"]["id"], bid)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM fare_quotes").fetchone()[0], 0)

    # ------------------------------------------------------------ schema
    def test_schema_version_unchanged_and_tables_removable(self):
        self.assertEqual(current_version(self.conn), LATEST)
        self.search()
        remove_fare_search(self.conn)
        names = {r[0] for r in self.conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        self.assertFalse(names & {"fare_portals", "fare_searches", "fare_search_suppliers", "fare_quotes"})
        self.assertIsNone(self.conn.execute("SELECT value FROM settings WHERE key = 'fare_search_enabled'").fetchone())
        self.assertEqual(self.conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        self.assertEqual(migrate(self.conn), LATEST)  # an upgraded app recreates empty tables, switched off
        self.assertEqual(services.get_settings(self.conn)["fare_search_enabled"], "0")
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM fare_searches").fetchone()[0], 0)

    def test_deleting_a_supplier_with_a_portal_only_drops_the_address(self):
        sid = services.create_supplier(self.conn, {"name": "Portal C", "kind": "b2b_portal"})
        fs.save_portal(self.conn, sid, {"login_url": "https://c.example.com/"})
        services.delete_supplier(self.conn, sid)
        self.assertIsNone(self.conn.execute("SELECT 1 FROM fare_portals WHERE supplier_id = ?", (sid,)).fetchone())


class FareSearchHttp(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="qt-fs-api-"))
        cls.port = free_port()
        cls.ctx = web.Context(cls.tmp, "127.0.0.1", cls.port)
        cls.ctx.init()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", cls.port), web.make_handler(cls.ctx, log=lambda *a: None))
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.cookie = None

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def call(self, method, path, body=None, csrf=True):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=20)
        h = {"Host": "127.0.0.1:%d" % self.port}
        if body is not None:
            h["Content-Type"] = "application/json"
        if csrf and method != "GET":
            h["X-QT-CSRF"] = "1"
        if FareSearchHttp.cookie:
            h["Cookie"] = FareSearchHttp.cookie
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        data = r.read()
        sc = r.getheader("Set-Cookie")
        if sc and "qt_session=" in sc:
            FareSearchHttp.cookie = sc.split(";")[0]
        c.close()
        return r.status, (json.loads(data) if data and "json" in (r.getheader("Content-Type") or "") else data)

    def test_flow(self):
        st, _ = self.call("GET", "/api/fare/portals")
        self.assertEqual(st, 401)
        st, b = self.call("POST", "/api/auth/setup", {"username": "owner", "password": "Str0ng-Passw0rd!", "setup_code": self.ctx.setup_code})
        self.assertEqual(st, 200, b)
        conn = connect(self.ctx.db_path)
        sid = services.create_supplier(conn, {"name": "Riya Connect", "kind": "b2b_portal"})
        conn.close()
        st, b = self.call("PUT", "/api/fare/portals/%d" % sid, {"login_url": "https://riyaconnect.com/"}, csrf=False)
        self.assertEqual(st, 403)  # no CSRF header
        st, b = self.call("PUT", "/api/fare/portals/%d" % sid, {"login_url": "https://riyaconnect.com/"})
        self.assertEqual(st, 200, b)
        search = {"origin": "CCJ", "destination": "SHJ", "depart_date": "2026-12-01", "supplier_ids": [sid], "selling_price": "20000"}
        st, b = self.call("POST", "/api/fare/searches", search)
        self.assertEqual((st, b["code"]), (403, "feature_off"))
        st, b = self.call("GET", "/api/fare/searches")
        self.assertEqual(st, 403)
        st, b = self.call("PUT", "/api/settings", {"fare_search_enabled": "1"})
        self.assertEqual(st, 200)
        st, b = self.call("POST", "/api/fare/searches", search)
        self.assertEqual(st, 201, b)
        fid = b["search"]["id"]
        st, b = self.call("POST", "/api/fare/searches/%d/suppliers/%d" % (fid, sid), {"status": "opened"})
        self.assertEqual(st, 200, b)
        st, q = self.call("POST", "/api/fare/searches/%d/quotes" % fid, {"supplier_id": sid, "final_payable": "18500.50", "taxes": "2100"})
        self.assertEqual(st, 201, q)
        self.assertEqual((q["final_payable"], q["base_fare"], q["est_profit"]), (1850050, None, 149950))
        st, d = self.call("GET", "/api/fare/searches/%d" % fid)
        self.assertEqual((st, len(d["quotes"]), d["suppliers"][0]["status"]), (200, 1, "quoted"))
        st, r = self.call("POST", "/api/fare/quotes/%d/recheck" % q["id"], {"final_payable": "18600", "confirmed": True})
        self.assertEqual((st, r["changed_from"]), (201, 1850050))
        st, dups = self.call("GET", "/api/fare/quotes/%d/duplicates" % r["id"])
        self.assertEqual((st, dups), (200, []))
        st, lst = self.call("GET", "/api/fare/searches")
        self.assertEqual((st, lst[0]["quotes"], lst[0]["lowest_payable"]), (200, 2, 1850050))


if __name__ == "__main__":
    unittest.main()
