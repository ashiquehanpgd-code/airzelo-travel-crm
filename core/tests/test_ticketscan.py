"""Offline ticket scanner: parsing rules, the reader on this computer (PDF text + OCR), and the scan / attach routes.
Fixtures are synthetic tickets with made-up passengers."""
import base64
import os
import unittest
from pathlib import Path

from helpers import Base
from server import ticketscan as ts
from server import services
from server.errors import ValidationError

HERE = Path(__file__).resolve().parent
READY = ts.reader_status()["ready"]
OCR = bool(ts.reader_status().get("ocr"))     # macOS (Apple Vision) and Windows (Windows.Media.Ocr)


def val(f):
    return f["value"]


class Parsing(unittest.TestCase):
    def test_labelled_ticket(self):
        text = """E-Ticket / Itinerary
Booking Reference: K7Q2LM   Date of Issue: 17 Oct 2026
Passenger: MRS FATHIMA RAHMAN (Adult)  Ticket No: 065-2417545001
MR AHMED RAHMAN (Adult) Ticket No: 0652417545002
MSTR ZAYAN RAHMAN (Child)
6E 1407  COK 04:10 02 Nov 2026  DXB 06:40 02 Nov 2026  Check-in 30 kg  Cabin 7 kg  Economy
EK 803   DXB 09:15 02 Nov 2026  JED 11:05 02 Nov 2026
SV 756   JED 23:30 20 Nov 2026  COK 07:45 21 Nov 2026
Base Fare INR 120,000.00   Taxes & Fees INR 15,500.00   Total Fare INR 135,500.00"""
        r = ts.parse(text)
        self.assertEqual(val(r["booking"]["pnr"]), "K7Q2LM")
        self.assertEqual(val(r["booking"]["issue_date"]), "2026-10-17")
        self.assertEqual([(val(p["title"]), val(p["name"]), val(p["pax_type"]), val(p["ticket_number"])) for p in r["passengers"]],
                         [("MRS", "FATHIMA RAHMAN", "ADT", "065-2417545001"), ("MR", "AHMED RAHMAN", "ADT", "065-2417545002"), ("MSTR", "ZAYAN RAHMAN", "CHD", "")])
        segs = [(val(s["flight_no"]), val(s["origin"]), val(s["destination"]), val(s["dep_at"]), val(s["arr_at"]), s["direction"]) for s in r["segments"]]
        self.assertEqual(segs, [("6E 1407", "COK", "DXB", "2026-11-02T04:10", "2026-11-02T06:40", "outbound"),
                                ("EK 803", "DXB", "JED", "2026-11-02T09:15", "2026-11-02T11:05", "outbound"),
                                ("SV 756", "JED", "COK", "2026-11-20T23:30", "2026-11-21T07:45", "return")])
        self.assertEqual(r["booking"]["trip_type"], "round_trip")
        self.assertEqual(val(r["segments"][0]["baggage"]), "30 kg + 7 kg cabin")
        self.assertEqual((r["fare"]["currency"], val(r["fare"]["base"]), val(r["fare"]["taxes"]), val(r["fare"]["total"])), ("INR", 120000.0, 15500.0, 135500.0))
        self.assertEqual(r["fare"]["total"]["confidence"], "high")      # base + taxes = total
        self.assertEqual(r["missing"], [])

    def test_never_invents_missing_details(self):
        r = ts.parse("Thank you for your enquiry. We will share the itinerary soon.")
        self.assertEqual(val(r["booking"]["pnr"]), "")
        self.assertEqual(r["passengers"], [])
        self.assertEqual(r["segments"], [])
        self.assertIn("PNR / booking reference", r["missing"])
        self.assertIn("Flight details", r["missing"])
        self.assertEqual(val(r["booking"]["status"]), "")    # status only when the ticket states it

    def test_ocr_codes_are_marked_for_checking(self):
        r = ts.parse("PNR: K7Q2LM\nMR TEST PERSON\n6E 1407 COK 04:10 02 Nov 2026 DXB 06:40 02 Nov 2026", "ocr", 0.98)
        self.assertEqual(r["booking"]["pnr"]["confidence"], "medium")
        self.assertIn("O / 0 / Q", r["booking"]["pnr"]["note"])

    def test_layouts(self):
        # times then airports, dates on the line below (table / OCR rows), an overnight arrival and a duration that is not a time
        r = ts.parse("PNR / BOOKING REFERENCE    AGENCY REF\nX9Y8Z7    BK-1\nIX 433\n23:30 TRV    05:10 RUH    Duration 1:35 hrs\n"
                     "Mon, 05 Jan 2026    Tue, 06 Jan 2026\nMS ANNA THOMAS (Adult)", "ocr", 0.99)
        self.assertEqual(val(r["booking"]["pnr"]), "X9Y8Z7")
        s = r["segments"][0]
        self.assertEqual((val(s["origin"]), val(s["destination"]), val(s["dep_at"]), val(s["arr_at"])), ("TRV", "RUH", "2026-01-05T23:30", "2026-01-06T05:10"))
        # check-in closing times are not flight times; a numeric PNR (e.g. Air Arabia)
        r = ts.parse("PNR 912345678\nCheck-in closes: 12:50 hrs\nG9 399 GIZ 16:55 21 Oct 2026 SHJ 20:45 21 Oct 2026\nMR SAMI KHAN")
        self.assertEqual(val(r["booking"]["pnr"]), "912345678")
        self.assertEqual(val(r["segments"][0]["dep_at"]), "2026-10-21T16:55")
        # English words in capitals are not airports
        self.assertEqual(ts._airport_tokens("PASSENGERS CAN CARRY ONE BAG (COK)", all_caps=True), [(29, "COK")])

    def test_baggage_formats(self):
        for txt, want in (("BAGGAGE\n30 KG CHECKED + 7 KG CABIN", "30 kg + 7 kg cabin"), ("CABIN: 7 KG  CHECK-IN: 20 KG", "20 kg + 7 kg cabin"),
                          ("BAGGAGE 20+7KG", "20 kg + 7 kg cabin"), ("CHECK-IN BAGGAGE 1PC 23KG CABIN 7KG", "1 pc x 23 kg + 7 kg cabin"), ("NO BAGS", "")):
            self.assertEqual(ts._baggage(txt), want, txt)

    def test_reports_are_not_tickets(self):
        lines = "\n".join("%s %d  RUH %02d:00 0%d Jan 2026  CCJ %02d:30 0%d Jan 2026" % ("XY", 100 + i, 1 + i, 1 + i % 9, 3 + i, 1 + i % 9) for i in range(12))
        r = ts.parse("MONTHLY SALES REPORT\n" + lines)
        self.assertEqual(r["segments"], [])
        self.assertTrue(any("report" in w for w in r["warnings"]))


@unittest.skipUnless(READY, "ticket reader not available (see reader_status())")
class OfflineReader(unittest.TestCase):
    def check(self, path, method):
        out = ts.read_file(path)
        self.assertTrue(out["ok"])
        self.assertTrue(all(p["method"] == method for p in out["pages"]))
        text = "\n".join(p["text"] for p in out["pages"])
        r = ts.parse(text, method)
        self.assertEqual(val(r["booking"]["pnr"]), "K7Q2LM")
        self.assertEqual([val(s["flight_no"]) for s in r["segments"]], ["6E 1407", "EK 803", "SV 756"])
        self.assertEqual([val(p["name"]) for p in r["passengers"]], ["FATHIMA RAHMAN", "AHMED RAHMAN", "ZAYAN RAHMAN"])
        self.assertEqual(val(r["segments"][2]["arr_at"]), "2026-11-21T07:45")

    def test_digital_pdf(self):
        self.check(HERE / "fixture_ticket.pdf", "pdf-text")

    @unittest.skipUnless(OCR, "no text recognition on this system (macOS and Windows only)")
    def test_image_ocr(self):
        self.check(HERE / "fixture_ticket.png", "ocr")

    def test_not_a_ticket_file(self):
        bad = HERE / "helpers.py"
        with self.assertRaises(ValidationError):
            ts.read_file(bad)


@unittest.skipUnless(READY, "ticket reader not available (see reader_status())")
class ScanAndAttach(Base):
    def upload(self, name):
        return ts.scan(self.conn, self.ctx, {"name": name, "data": base64.b64encode((HERE / name).read_bytes()).decode()})

    def test_scan_fills_but_never_saves(self):
        before = self.conn.execute("SELECT COUNT(*) FROM bookings").fetchone()[0]
        r = self.upload("fixture_ticket.pdf")
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM bookings").fetchone()[0], before)   # no booking created
        self.assertEqual(r["kind"], "pdf")
        if OCR:   # the page preview is drawn by the same system component that does OCR
            self.assertTrue(r["preview"])
            self.assertTrue(ts.preview_path(self.ctx, r["scan_id"]).exists())
        self.assertEqual(r["duplicate_pnr"], [])
        # the same PNR saved later is reported as a possible duplicate
        self.booking(pnr="K7Q2LM")
        self.assertEqual(len(self.upload("fixture_ticket.pdf")["duplicate_pnr"]), 1)

    def test_attach_to_booking(self):
        r = self.upload("fixture_ticket.pdf")
        bid = self.booking(pnr="K7Q2LM")
        did = ts.attach_scan(self.conn, self.ctx, bid, r["scan_id"])
        doc = self.conn.execute("SELECT * FROM documents WHERE id = ?", (did,)).fetchone()
        self.assertEqual((doc["booking_id"], doc["original_name"], doc["label"]), (bid, "fixture_ticket.pdf", "E-ticket (scanned)"))
        self.assertTrue((self.ctx.upload_dir / doc["stored_name"]).exists())
        self.assertEqual(list(ts.scan_dir(self.ctx).glob(r["scan_id"] + ".*")), [])   # temporary copy removed
        with self.assertRaises(Exception):
            ts.attach_scan(self.conn, self.ctx, bid, r["scan_id"])                    # cannot attach twice

    def test_rejects_wrong_files(self):
        for name, data in (("ticket.docx", b"x"), ("ticket.pdf", b"")):
            with self.assertRaises(ValidationError):
                ts.scan(self.conn, self.ctx, {"name": name, "data": base64.b64encode(data).decode()})
        with self.assertRaises(Exception):
            ts.preview_path(self.ctx, "../../etc/passwd")

    def test_discard(self):
        r = self.upload("fixture_ticket.png" if OCR else "fixture_ticket.pdf")
        self.assertEqual(r["method"], "ocr" if OCR else "pdf-text")
        ts.discard_scan(self.ctx, r["scan_id"])
        self.assertEqual(list(ts.scan_dir(self.ctx).glob(r["scan_id"] + ".*")), [])
