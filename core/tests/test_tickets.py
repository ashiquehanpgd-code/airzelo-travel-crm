"""Ticket data, the ticket / ticket + payment PDFs, payment details settings and the ticket routes."""
import os
import re
import tempfile
import zlib
from pathlib import Path

from helpers import Base
from server import services, ticket, pdfkit
from server.errors import ValidationError


def pdf_text(data):
    """Text drawn in a PDF made by pdfkit (decompresses each content stream)."""
    out = []
    for m in re.finditer(rb"stream\n(.*?)\nendstream", data, re.S):
        try:
            body = zlib.decompress(m.group(1))
        except zlib.error:
            continue
        out += [s.decode("cp1252").replace("\\(", "(").replace("\\)", ")") for s in re.findall(rb"\(((?:\\.|[^\\)])*)\) Tj", body)]
    return "\n".join(out)


class TicketTests(Base):
    def setUp(self):
        super().setUp()
        # synthetic agency payment details (a new agency starts with none)
        services.update_settings(self.conn, {"business_name": "Demo Travels", "pay_account_name": "DEMO TRAVELS", "pay_account_number": "50100012345678",
                                             "pay_ifsc": "ABCD0123456", "pay_branch": "MAIN ROAD", "pay_upi_number": "9000000001"})
        self.ret = self.booking(pnr="K7Q2LM", status="ticketed", trip_type="round_trip", selling_price="135500", purchase_cost="120000",
                                cabin="Economy", baggage="30 kg + 7 kg",
                                passengers=[{"title": "Mrs", "name": "FATHIMA RAHMAN", "pax_type": "ADT", "ticket_number": "065-2417545001"},
                                            {"title": "Mr", "name": "MR. AHMED RAHMAN", "pax_type": "ADT", "ticket_number": ""},
                                            {"title": "", "name": "ZAYAN RAHMAN", "pax_type": "CHD"}],
                                segments=[{"direction": "outbound", "airline": "IndiGo", "flight_no": "6E 1407", "origin": "COK", "destination": "DXB",
                                           "dep_at": "2026-11-02T04:10", "arr_at": "2026-11-02T06:40"},
                                          {"direction": "outbound", "airline": "Emirates", "flight_no": "EK 803", "origin": "DXB", "destination": "JED",
                                           "dep_at": "2026-11-02T09:15", "arr_at": "2026-11-02T11:05", "airline_pnr": "EKX9QZ"},
                                          {"direction": "return", "airline": "Saudia", "flight_no": "SV 756", "origin": "JED", "destination": "COK",
                                           "dep_at": "2026-11-20T23:30"}])
        self.tmp_tickets = Path(tempfile.mkdtemp(prefix="qt-tickets-"))
        os.environ["TACRM_TICKET_DIR"] = str(self.tmp_tickets)

    def tearDown(self):
        os.environ.pop("TACRM_TICKET_DIR", None)
        super().tearDown()

    def test_ticket_data_uses_saved_details_only(self):
        td = ticket.ticket_data(self.conn, self.ret)
        b = td["booking"]
        self.assertEqual((b["pnr"], b["ref"], b["status"], b["doc_title"], b["is_ticketed"]), ("K7Q2LM", b["ref"], "ticketed", "E-TICKET", True))
        self.assertEqual([j["direction"] for j in td["journeys"]], ["outbound", "return"])
        out = td["journeys"][0]
        self.assertEqual((out["origin"], out["destination"], out["stops"], out["origin_city"]), ("COK", "JED", 1, "Kochi"))
        self.assertEqual(out["legs"][0]["layover"], {"airport": "DXB", "city": "Dubai", "duration": "2h 35m"})
        ret = td["journeys"][1]["legs"][0]
        self.assertEqual((ret["arr_at"], ret["arr_time"]), ("", ""))  # arrival not saved -> not invented
        self.assertEqual(out["legs"][1]["airline_pnr"], "EKX9QZ")
        names = [p["display_name"] for p in td["passengers"]]
        self.assertEqual(names, ["Mrs FATHIMA RAHMAN", "MR. AHMED RAHMAN", "ZAYAN RAHMAN"])
        self.assertEqual([p["ticket_number"] for p in td["passengers"]], ["065-2417545001", "", ""])
        self.assertNotIn("passport", repr(td).lower())

    def test_status_never_upgraded(self):
        bid = self.booking(pnr="CNF111", status="confirmed")
        td = ticket.ticket_data(self.conn, bid)
        self.assertEqual((td["booking"]["doc_title"], td["booking"]["is_ticketed"]), ("BOOKING CONFIRMATION", False))
        enq = self.booking(pnr="ENQ111", status="enquiry")
        td = ticket.ticket_data(self.conn, enq)
        self.assertFalse(td["booking"]["is_confirmed"])
        self.assertIn("NOT CONFIRMED", td["booking"]["doc_title"])
        self.assertEqual(td["payment"]["status"], "not_billed")
        text = pdf_text(ticket.render_pdf(td))
        self.assertNotIn("E-TICKET\n", text)
        self.assertIn("not valid for travel", text)

    def test_payment_figures_match_ledger(self):
        unpaid = ticket.ticket_data(self.conn, self.ret)["payment"]
        self.assertEqual((unpaid["total"], unpaid["received"], unpaid["pending"], unpaid["status"]), (13550000, 0, 13550000, "unpaid"))
        self.txn(amount="50000", customer_id=self.cust, allocations=[{"booking_id": self.ret, "amount": "50000"}])
        part = ticket.ticket_data(self.conn, self.ret)["payment"]
        self.assertEqual((part["received"], part["pending"], part["status"]), (5000000, 8550000, "partial"))
        self.assertEqual(part["pending"], self.calc(self.ret)["customer_outstanding"])
        self.txn(amount="85500", customer_id=self.cust, allocations=[{"booking_id": self.ret, "amount": "85500"}], reference="r2")
        paid = ticket.ticket_data(self.conn, self.ret)["payment"]
        self.assertEqual((paid["pending"], paid["status"]), (0, "paid"))
        text = pdf_text(ticket.render_pdf(ticket.ticket_data(self.conn, self.ret), "payment"))
        self.assertIn("Paid in full", text)
        self.assertNotIn("50100012345678", text)  # no bank details pushed on a settled booking

    def test_pdfs_are_valid_and_contain_the_booking(self):
        before = self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
        td = ticket.ticket_data(self.conn, self.ret)
        for variant in ("ticket", "payment"):
            data = ticket.render_pdf(td, variant)
            self.assertTrue(data.startswith(b"%PDF-1.4") and data.rstrip().endswith(b"%%EOF"))
            self.assertEqual(data.count(b"/Type /Page "), 2 if variant == "ticket" else 3)
            text = pdf_text(data)
            for s in ("K7Q2LM", "COK", "JED", "IndiGo 6E 1407", "Emirates EK 803", "Saudia SV 756", "Layover at DXB (Dubai)  |  2h 35m",
                      "065-2417545001", "Not issued yet", "Mrs FATHIMA RAHMAN", "30 kg + 7 kg", "Time not recorded", "Ticketed"):
                self.assertIn(s, text, (variant, s))
            if variant == "payment":
                for s in ("INR 1,35,500.00", "INR 0.00", "DEMO TRAVELS", "50100012345678", "ABCD0123456", "MAIN ROAD", "9000000001",
                          "Google Pay", "PhonePe", "BHIM UPI", "Payment pending"):
                    self.assertIn(s, text, s)
            else:
                self.assertNotIn("50100012345678", text)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], before)  # read-only

    def test_save_pdf_file_name(self):
        td = ticket.ticket_data(self.conn, self.ret)
        path, size = ticket.save_pdf(td, "payment")
        self.assertEqual(path.parent, self.tmp_tickets)
        self.assertRegex(path.name, r"^Booking_BK-\d\d-\d{5}_K7Q2LM_FATHIMA-RAHMAN_Ticket\+Payment\.pdf$")
        self.assertEqual(path.stat().st_size, size)
        other = ticket.save_pdf(ticket.ticket_data(self.conn, self.booking(pnr="ZZZ999")), "ticket")[0]
        self.assertNotEqual(other.name, path.name)

    def test_payment_settings_defaults_and_validation(self):
        # a new agency starts with no payment details: nothing from any other business is built in
        services.update_settings(self.conn, {"pay_account_name": "", "pay_account_number": "", "pay_ifsc": "", "pay_branch": "", "pay_upi_number": ""})
        s = services.get_settings(self.conn)
        self.assertEqual((s["pay_account_name"], s["pay_account_number"], s["pay_ifsc"], s["pay_branch"], s["pay_upi_number"]),
                         ("", "", "", "", ""))
        from server.db import DEFAULT_SETTINGS
        self.assertFalse(any(DEFAULT_SETTINGS[k] for k in ("business_name", "pay_account_name", "pay_account_number", "pay_ifsc", "pay_branch", "pay_upi_number")))
        self.assertEqual(s["pay_upi_apps"], "Google Pay, PhonePe, BHIM UPI")
        for k, bad in (("pay_ifsc", "ABCD012345"), ("pay_account_number", "12AB"), ("pay_upi_number", "95449547")):
            with self.assertRaises(ValidationError):
                services.update_settings(self.conn, {k: bad})
        s = services.update_settings(self.conn, {"pay_ifsc": "abcd0123456", "pay_account_number": "5010 0012 3456 78"})
        self.assertEqual((s["pay_ifsc"], s["pay_account_number"]), ("ABCD0123456", "50100012345678"))

    def test_title_not_repeated(self):
        n = ticket.pax_name
        self.assertEqual(n({"title": "MRS", "name": "Ms Leena Sample Traveller"}), "Ms Leena Sample Traveller")
        self.assertEqual(n({"title": "Mr", "name": "MR. AHMED"}), "MR. AHMED")
        self.assertEqual(n({"title": "Mr", "name": "MRITHUN DAS"}), "Mr MRITHUN DAS")  # a name starting with "Mr..." is not a title
        self.assertEqual(n({"title": "", "name": "ZAYAN"}), "ZAYAN")

    def test_long_names_are_never_cut(self):
        long = "MUHAMMAD RAIHAN KARUPPAMVEETTIL RIYAS ABDULLA KUTTY"
        bid = self.booking(pnr="LONG01", passengers=[{"title": "Mr", "name": long, "pax_type": "ADT"}] * 1 +
                           [{"name": "PAX %d" % i, "pax_type": "ADT"} for i in range(30)])
        text = pdf_text(ticket.render_pdf(ticket.ticket_data(self.conn, bid)))
        self.assertIn("RIYAS ABDULLA KUTTY", text.replace("\n", " "))
        self.assertIn("PAX 29", text)  # 31 passengers flow onto further pages

    def test_pdfkit_text_helpers(self):
        self.assertEqual(pdfkit.clean("₹100 → Dubai ✈"), "INR 100 -> Dubai ?")
        lines = pdfkit.wrap("A very long baggage allowance description that must wrap", 9, 100)
        self.assertTrue(len(lines) > 1 and all(pdfkit.text_width(l, 9) <= 100 for l in lines))
        self.assertEqual(ticket.money(13550000), "INR 1,35,500.00")
        self.assertEqual(ticket.money(-123456789), "INR -12,34,567.89")


class TicketRoutes(Base):
    def test_routes(self):
        import http.client, json, threading, socket
        from http.server import ThreadingHTTPServer
        from server import web, auth
        tmp = Path(tempfile.mkdtemp(prefix="qt-tickets-"))
        os.environ["TACRM_TICKET_DIR"] = str(tmp)
        os.environ["TACRM_NO_OPEN"] = "1"
        bid = self.booking(pnr="ROUTE1", status="ticketed")
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        self.ctx.port = port
        auth.setup_owner(self.conn, {"username": "owner", "password": "Str0ng-Passw0rd!", "setup_code": "123456"}, "123456")
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
            c.close()
            return r.status, data, r
        try:
            self.assertEqual(call("GET", "/api/bookings/%d/ticket.pdf" % bid)[0], 401)
            self.assertEqual(call("POST", "/api/auth/login", {"username": "owner", "password": "Str0ng-Passw0rd!"})[0], 200)
            st, data, _ = call("GET", "/api/bookings/%d/ticket" % bid)
            self.assertEqual(st, 200)
            self.assertEqual(json.loads(data)["booking"]["pnr"], "ROUTE1")
            st, data, r = call("GET", "/api/bookings/%d/ticket.pdf?variant=payment" % bid)
            self.assertEqual((st, r.getheader("Content-Type")), (200, "application/pdf"))
            self.assertIn("Ticket%2BPayment.pdf", r.getheader("Content-Disposition"))
            self.assertTrue(data.startswith(b"%PDF"))
            st, data, _ = call("POST", "/api/bookings/%d/ticket-file" % bid, {"variant": "ticket", "action": "reveal", "path": "/etc/passwd"})
            res = json.loads(data)
            self.assertEqual(st, 200)
            self.assertEqual(Path(res["path"]).parent, tmp)  # path is built by the server, never from the request
            self.assertFalse(res["opened"])
            self.assertTrue((tmp / res["name"]).exists())
            self.assertEqual(call("GET", "/api/bookings/99999/ticket")[0], 404)
        finally:
            httpd.shutdown()
            httpd.server_close()
            os.environ.pop("TACRM_TICKET_DIR", None)
            os.environ.pop("TACRM_NO_OPEN", None)
