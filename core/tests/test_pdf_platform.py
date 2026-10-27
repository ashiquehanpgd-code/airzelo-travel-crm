"""Cross-platform PDF handling: the portable ticket reader (bundled pypdf + Windows text recognition), ticket PDF
file names and folders, opening files with errors reported, and who may download PDFs.
Runs on macOS and Windows; Windows-only checks skip elsewhere. Fixtures are synthetic."""
import base64
import http.client
import json
import os
import re
import shutil
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from helpers import Base, ROOT
from server import pdfkit, pdftext, ticket, ticketscan as ts, web
from server.errors import ValidationError

HERE = Path(__file__).resolve().parent
WINDOWS = os.name == "nt"


def val(f):
    return f["value"]


def check_fixture(test, text, method):
    r = ts.parse(text, method)
    test.assertEqual(val(r["booking"]["pnr"]), "K7Q2LM")
    test.assertEqual([val(s["flight_no"]) for s in r["segments"]], ["6E 1407", "EK 803", "SV 756"])
    test.assertEqual([val(p["name"]) for p in r["passengers"]], ["FATHIMA RAHMAN", "AHMED RAHMAN", "ZAYAN RAHMAN"])
    test.assertEqual(val(r["segments"][2]["arr_at"]), "2026-11-21T07:45")


def scanned_pdf(folder):
    """An image-only PDF (no text inside), like a scanned ticket."""
    doc = pdfkit.Document(title="scan")
    name, w, h = doc.add_image((HERE / "fixture_ticket.png").read_bytes())
    page = doc.new_page()
    pw, ph = pdfkit.A4
    s = min(pw / w, ph / h)
    page.image(name, 0, ph - h * s, w * s, h * s)
    path = Path(folder) / "scan.pdf"
    path.write_bytes(doc.bytes())
    return path


class PortableReader(unittest.TestCase):
    """The reader used on Windows (and on a Mac without the Swift reader) - tested on every system."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ta-pdf-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_status_ready_with_bundled_pypdf(self):
        st = pdftext.status()
        self.assertTrue(st["ready"], st)
        self.assertEqual(st["ocr"], WINDOWS)

    def test_digital_pdf_reads_like_the_mac_reader(self):
        out = pdftext.read(HERE / "fixture_ticket.pdf")
        self.assertTrue(out["ok"])
        self.assertEqual(out["kind"], "pdf")
        self.assertEqual(out["page_count"], 2)
        self.assertTrue(all(p["method"] == "pdf-text" for p in out["pages"]))
        check_fixture(self, "\n\n".join(p["text"] for p in out["pages"]), "pdf-text")

    def test_layout_text_has_no_indentation_or_blank_lines(self):
        text = pdftext.normalise("   A    B\n\n      C          D   \n")
        self.assertEqual(text, "A    B\nC    D")

    def test_reading_order_rebuilds_rows_and_column_gaps(self):
        words = [  # pixels, y grows downwards; given out of order
            {"x": 600, "y": 102, "w": 80, "h": 20, "t": "K7Q2LM"},
            {"x": 10, "y": 100, "w": 40, "h": 20, "t": "PNR"},
            {"x": 55, "y": 101, "w": 20, "h": 20, "t": "/"},
            {"x": 10, "y": 160, "w": 60, "h": 20, "t": "6E"},
            {"x": 75, "y": 161, "w": 60, "h": 20, "t": "1407"},
        ]
        self.assertEqual(pdftext.reading_order(words, 1000), "PNR /    K7Q2LM\n6E 1407")

    def test_damaged_pdf_is_reported(self):
        bad = self.tmp / "bad.pdf"
        bad.write_bytes(b"%PDF-1.4\nnot really a pdf")
        with self.assertRaises(ValidationError):
            pdftext.read(bad)

    def test_password_protected_pdf_is_reported(self):
        pypdf = pdftext._pypdf()
        w = pypdf.PdfWriter(clone_from=str(HERE / "fixture_ticket.pdf"))
        w.encrypt(user_password="secret", owner_password="owner", algorithm="RC4-128")
        locked = self.tmp / "locked.pdf"
        with open(locked, "wb") as f:
            w.write(f)
        with self.assertRaises(ValidationError) as e:
            pdftext.read(locked)
        self.assertIn("password", str(e.exception))

    @unittest.skipIf(WINDOWS, "Windows reads scans with its own text recognition")
    def test_scanned_pdf_without_ocr_says_why(self):
        with mock.patch.object(pdftext, "ocr_available", return_value=False):
            with self.assertRaises(ValidationError) as e:
                pdftext.read(scanned_pdf(self.tmp))
        self.assertIn("scan", str(e.exception))

    @unittest.skipIf(WINDOWS, "Windows reads images with its own text recognition")
    def test_image_without_ocr_says_why(self):
        with mock.patch.object(pdftext, "ocr_available", return_value=False):
            with self.assertRaises(ValidationError):
                pdftext.read(HERE / "fixture_ticket.png")

    def test_helper_script_ships_with_the_core(self):
        self.assertTrue(pdftext.OCR_SCRIPT.exists())
        self.assertTrue((ROOT / "vendor" / "pypdf" / "LICENSE").exists())
        pkg = json.loads((ROOT.parent / "desktop" / "package.json").read_text(encoding="utf-8"))
        core = next(r for r in pkg["build"]["extraResources"] if r["to"] == "core")
        self.assertIn("vendor/**", core["filter"])
        self.assertIn("tools/*.ps1", core["filter"])


@unittest.skipUnless(WINDOWS and pdftext.ocr_available(), "Windows text recognition (Windows 10/11 only)")
class WindowsOcr(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ta-ocr-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_image_ticket(self):
        out = pdftext.read(HERE / "fixture_ticket.png", self.tmp / "p.png")
        self.assertEqual(out["kind"], "image")
        self.assertEqual(out["pages"][0]["method"], "ocr")
        check_fixture(self, out["pages"][0]["text"], "ocr")
        self.assertTrue(out["preview"])
        self.assertTrue((self.tmp / "p.png").read_bytes().startswith(b"\x89PNG"))

    def test_scanned_pdf(self):
        out = pdftext.read(scanned_pdf(self.tmp))
        self.assertEqual([p["method"] for p in out["pages"]], ["ocr"])
        r = ts.parse(out["pages"][0]["text"], "ocr")
        self.assertEqual(val(r["booking"]["pnr"]), "K7Q2LM")

    def test_digital_pdf_preview(self):
        out = pdftext.read(HERE / "fixture_ticket.pdf", self.tmp / "p.png")
        self.assertTrue(out["preview"])
        self.assertTrue((self.tmp / "p.png").read_bytes().startswith(b"\x89PNG"))
        self.assertTrue(all(p["method"] == "pdf-text" for p in out["pages"]))   # text pages are not OCR'd

    def test_not_an_image(self):
        bad = self.tmp / "ticket.png"
        bad.write_bytes(b"not an image")
        with self.assertRaises(ValidationError):
            pdftext.read(bad)


class TicketFiles(Base):
    def test_file_name_is_safe_and_short_on_every_system(self):
        bid = self.booking(pnr="AB1234")
        td = ticket.ticket_data(self.conn, bid)
        # names and PNRs as they might arrive from older data or imports: reserved characters and device names
        td["booking"]["pnr"] = 'AB:C/1?*'
        td["passengers"][0]["name"] = 'CON <MR> "VERY" ' + "LONGNAME " * 20 + "|\\"
        for variant in ("ticket", "payment"):
            name = ticket.file_name(td, variant)
            self.assertRegex(name, r"^[A-Za-z0-9+_-]+\.pdf$")
            self.assertLessEqual(len(name), 114)
            self.assertFalse(re.match(r"(?i)^(CON|PRN|AUX|NUL|COM\d|LPT\d)(\.|$)", name))

    def test_tickets_folder(self):
        with mock.patch.dict(os.environ, {"TACRM_TICKET_DIR": str(self.tmp / "t")}):
            self.assertEqual(ticket.tickets_dir(), self.tmp / "t")
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("TACRM_TICKET_DIR", None)
            docs = ticket.documents_dir()
            self.assertTrue(docs.is_absolute())
            self.assertEqual(ticket.tickets_dir(), docs / "AirZelo" / "Tickets")
            if not WINDOWS:
                self.assertEqual(docs, Path.home() / "Documents")

    @unittest.skipUnless(WINDOWS, "Windows known folders")
    def test_windows_documents_is_the_known_folder(self):
        # %USERPROFILE%\Documents is wrong when Documents is redirected (e.g. OneDrive); the known folder never is
        import ctypes
        buf = ctypes.create_unicode_buffer(260)
        ctypes.windll.shell32.SHGetFolderPathW(None, 5, None, 0, buf)   # CSIDL_PERSONAL
        self.assertEqual(os.path.normcase(str(ticket.documents_dir())), os.path.normcase(buf.value))

    def test_save_into_folder_with_spaces_and_non_ascii(self):
        bid = self.booking(pnr="SAVE01")
        td = ticket.ticket_data(self.conn, bid)
        folder = self.tmp / "My Documents ടിക്കറ്റ്" / "AirZelo" / "Tickets"
        with mock.patch.dict(os.environ, {"TACRM_TICKET_DIR": str(folder)}):
            path, size = ticket.save_pdf(td, "ticket")
            path2, _ = ticket.save_pdf(td, "ticket")       # saving again replaces the file
        self.assertEqual(path, path2)
        self.assertTrue(path.read_bytes().startswith(b"%PDF"))
        self.assertEqual(path.stat().st_size, size)
        self.assertEqual([p.name for p in folder.iterdir()], [path.name])   # no .part file left behind


class OpenFiles(unittest.TestCase):
    def test_windows_without_pdf_app_reports_it(self):
        err = OSError("No application is associated")
        err.winerror = 1155
        with mock.patch.object(web.sys, "platform", "win32"), \
                mock.patch.object(web.os, "startfile", side_effect=err, create=True):
            self.assertEqual(web.open_path(Path("x.pdf")), (False, "no app is set to open PDF files on this computer"))

    def test_windows_reveal_ignores_explorers_exit_code(self):
        with mock.patch.object(web.sys, "platform", "win32"), mock.patch.object(web.subprocess, "run") as run:
            run.return_value.returncode = 1
            self.assertEqual(web.open_path(Path(r"C:\Docs\a b.pdf"), reveal=True), (True, ""))
            self.assertEqual(run.call_args[0][0], ["explorer.exe", "/select,", r"C:\Docs\a b.pdf"])

    def test_mac_failure_reports_reason(self):
        with mock.patch.object(web.sys, "platform", "darwin"), \
                mock.patch.object(web.subprocess, "run", side_effect=web.subprocess.CalledProcessError(1, "open")):
            ok, reason = web.open_path(Path("/tmp/x.pdf"))
        self.assertFalse(ok)
        self.assertTrue(reason)

    def test_no_mac_only_wording_left_in_the_ui(self):
        """'this Mac', Finder, Preview and the Command key must come from QT.os so Windows users see their own terms."""
        for f in (ROOT / "static" / "js").glob("*.js"):
            if f.name == "core.js":
                continue
            src = f.read_text(encoding="utf-8")
            for phrase in ("on this Mac", "in Finder", "in Preview", "⌘"):
                self.assertNotIn(phrase, src, "%s still says %r" % (f.name, phrase))


class PdfRoutes(Base):
    def setUp(self):
        super().setUp()
        from server import auth
        self.tickets = self.tmp / "tickets"
        self.env = mock.patch.dict(os.environ, {"TACRM_TICKET_DIR": str(self.tickets), "TACRM_NO_OPEN": "1"})
        self.env.start()
        s = socket.socket(); s.bind(("127.0.0.1", 0)); self.port = s.getsockname()[1]; s.close()
        self.ctx.port = self.port
        auth.setup_owner(self.conn, {"username": "owner", "password": "Str0ng-Passw0rd!", "setup_code": "123456"}, "123456")
        from http.server import ThreadingHTTPServer
        self.httpd = ThreadingHTTPServer(("127.0.0.1", self.port), web.make_handler(self.ctx, log=lambda *a: None))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.cookie = None

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.env.stop()
        super().tearDown()

    def call(self, method, path, body=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        h = {"Host": "127.0.0.1:%d" % self.port, "Content-Type": "application/json", "X-QT-CSRF": "1"}
        if self.cookie:
            h["Cookie"] = self.cookie
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        data = r.read()
        sc = r.getheader("Set-Cookie")
        if sc and "qt_session=" in sc:
            self.cookie = sc.split(";")[0]
        c.close()
        return r.status, data, r

    def login(self):
        self.assertEqual(self.call("POST", "/api/auth/login", {"username": "owner", "password": "Str0ng-Passw0rd!"})[0], 200)

    def test_pdfs_need_a_signed_in_user(self):
        bid = self.booking(pnr="PERM01")
        for method, path, body in (("GET", "/api/bookings/%d/ticket.pdf" % bid, None),
                                   ("GET", "/api/bookings/%d/ticket.pdf?inline=1" % bid, None),
                                   ("POST", "/api/bookings/%d/ticket-file" % bid, {"variant": "ticket"}),
                                   ("GET", "/api/ticket-scan/status", None)):
            self.assertEqual(self.call(method, path, body)[0], 401, path)
        self.assertFalse(self.tickets.exists())          # nothing was written for the anonymous request

    def test_download_and_inline_preview(self):
        bid = self.booking(pnr="DL0001")
        self.login()
        st, data, r = self.call("GET", "/api/bookings/%d/ticket.pdf" % bid)
        self.assertEqual((st, r.getheader("Content-Type")), (200, "application/pdf"))
        self.assertTrue(r.getheader("Content-Disposition").startswith("attachment; filename*=UTF-8''Booking_"))
        self.assertTrue(data.startswith(b"%PDF"))
        st, data, r = self.call("GET", "/api/bookings/%d/ticket.pdf?inline=1" % bid)
        self.assertTrue(r.getheader("Content-Disposition").startswith("inline;"))
        self.assertEqual(self.call("GET", "/api/bookings/999999/ticket.pdf")[0], 404)

    def test_save_reports_why_it_was_not_opened(self):
        bid = self.booking(pnr="OPEN01")
        self.login()
        st, data, _ = self.call("POST", "/api/bookings/%d/ticket-file" % bid, {"variant": "payment", "action": "open"})
        res = json.loads(data)
        self.assertEqual(st, 200)
        self.assertFalse(res["opened"])
        self.assertTrue(res["open_error"])              # the screen explains it and downloads instead
        self.assertEqual(Path(res["folder"]), self.tickets)
        self.assertTrue((self.tickets / res["name"]).exists())
        st, data, _ = self.call("POST", "/api/bookings/%d/ticket-file" % bid, {"variant": "ticket"})
        self.assertEqual(json.loads(data)["open_error"], "")   # nothing was asked to open, so no error

    def test_attached_pdf_document_download(self):
        bid = self.booking(pnr="DOC001")
        self.login()
        pdf = (HERE / "fixture_ticket.pdf").read_bytes()
        st, data, _ = self.call("POST", "/api/bookings/%d/documents" % bid, {"name": "e ticket.pdf", "data": base64.b64encode(pdf).decode()})
        self.assertIn(st, (200, 201))
        did = json.loads(data)["id"] if "id" in json.loads(data) else json.loads(data)["document_id"]
        st, data, r = self.call("GET", "/api/documents/%d?inline=1" % did)
        self.assertEqual((st, r.getheader("Content-Type"), data), (200, "application/pdf", pdf))
        self.assertTrue(r.getheader("Content-Disposition").startswith("inline;"))
        self.cookie = None
        self.assertEqual(self.call("GET", "/api/documents/%d" % did)[0], 401)

    def test_scanner_status_describes_this_computer(self):
        self.login()
        st, data, _ = self.call("GET", "/api/ticket-scan/status")
        res = json.loads(data)
        self.assertTrue(res["ready"])
        self.assertIn("Nothing is sent to the internet", res["note"])
        if sys.platform != "darwin":
            self.assertNotIn("Mac", res["note"] + res["engine"])


if __name__ == "__main__":
    unittest.main()
