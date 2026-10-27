"""Ticket reading on Windows and any other computer that is not a Mac.

The Mac keeps its own reader (bin/ta-ticket-reader: Apple PDFKit + Vision). Everywhere else:
  * PDFs with embedded text  -> the bundled pypdf (vendor/pypdf, BSD-3-Clause), laid out by position
  * scanned pages and images -> Windows' own on-device text recognition (tools/ticket_ocr_windows.ps1),
                                only on Windows; other systems report that OCR is not available
read() returns the same JSON shape as the Mac reader, so ticketscan.parse() treats both alike.
"""
import json
import logging
import os
import re
import subprocess
import sys
from pathlib import Path

from .errors import ValidationError

ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / "vendor"
OCR_SCRIPT = ROOT / "tools" / "ticket_ocr_windows.ps1"
MIN_TEXT = 40          # fewer characters than this on a PDF page = a scanned page (same rule as the Mac reader)
WIDE_GAP = 0.04        # a gap wider than this share of the page width separates table columns


def _pypdf():
    if str(VENDOR) not in sys.path:
        sys.path.insert(0, str(VENDOR))
    import pypdf  # noqa: vendored, see THIRD-PARTY-NOTICES.md
    logging.getLogger("pypdf").setLevel(logging.ERROR)   # tolerated file damage is not the user's problem
    return pypdf


def powershell():
    """Windows PowerShell 5.1 (it has the WinRT projection that Windows.Media.Ocr needs; PowerShell 7 does not)."""
    if os.name != "nt":
        return None
    p = Path(os.environ.get("SystemRoot") or r"C:\Windows") / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    return p if p.exists() else None


def ocr_available():
    return powershell() is not None and OCR_SCRIPT.exists()


def status():
    try:
        _pypdf()
    except ImportError:
        return {"ready": False, "engine": "", "reason": "The PDF reader is missing from this installation (vendor/pypdf). Reinstall the app."}
    if ocr_available():
        return {"ready": True, "engine": "PDF text + Windows text recognition (on this computer)", "ocr": True}
    return {"ready": True, "engine": "PDF text (on this computer)", "ocr": False,
            "note": "Scanned PDFs and images need text recognition, which is available only on Windows and macOS."}


def normalise(text):
    """Layout text -> the Mac reader's shape: no indentation or blank lines, column gaps as four spaces."""
    lines = (re.sub(r" {3,}", "    ", line.strip()) for line in text.splitlines())
    return "\n".join(line for line in lines if line)


def reading_order(words, width):
    """Rebuild lines from OCR word boxes (pixels, y down): rows by vertical centre, words left to right,
    words far apart joined by a wide gap so table columns stay separable for the parser."""
    items = sorted(words, key=lambda w: w["y"] + w["h"] / 2)
    rows = []
    for w in items:
        mid = w["y"] + w["h"] / 2
        if rows:
            ref = rows[-1][0]
            if abs(ref["y"] + ref["h"] / 2 - mid) < max(ref["h"], w["h"]) * 0.55:
                rows[-1].append(w)
                continue
        rows.append([w])
    gap = width * WIDE_GAP
    lines = []
    for row in rows:
        line, prev = "", None
        for w in sorted(row, key=lambda w: w["x"]):
            if prev is not None:
                line += "    " if w["x"] - prev > gap else " "
            line += w["t"]
            prev = w["x"] + w["w"]
        lines.append(line)
    return "\n".join(lines)


def pdf_pages(path):
    """Embedded text of each page, in reading order. Raises ValidationError for unreadable or locked PDFs."""
    pypdf = _pypdf()
    try:
        reader = pypdf.PdfReader(str(path))
        if reader.is_encrypted:
            try:
                ok = reader.decrypt("")
            except Exception:
                ok = 0
            if not ok:
                raise ValidationError("The PDF is password protected")
        texts = []
        for page in reader.pages:
            try:
                texts.append(normalise(page.extract_text(extraction_mode="layout")))
            except Exception:
                texts.append("")          # one bad page: treat it as a scan rather than failing the file
        return texts
    except ValidationError:
        raise
    except Exception:
        raise ValidationError("Not a readable PDF")


def run_ocr(path, preview_path=None, pages=None, timeout=180):
    """Run the Windows helper. pages: 1-based PDF pages to recognise ([] = none, only the preview)."""
    ps = powershell()
    if ps is None or not OCR_SCRIPT.exists():
        raise ValidationError("Text recognition for scans and images is not available on this computer")
    args = [str(ps), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(OCR_SCRIPT), "-Path", str(path)]
    if preview_path:
        args += ["-Preview", str(preview_path)]
    if pages is not None:
        args += ["-Pages", ",".join(str(p) for p in pages) or "0"]
    try:
        r = subprocess.run(args, capture_output=True, timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except subprocess.TimeoutExpired:
        raise ValidationError("Reading the ticket took too long. Try a clearer image or a PDF.")
    except OSError as e:
        raise ValidationError("Windows text recognition could not be started (%s)" % e.strerror)
    lines = r.stdout.decode("utf-8", "replace").strip().splitlines()
    try:
        out = json.loads(lines[-1])
    except (ValueError, IndexError):
        detail = r.stderr.decode("utf-8", "replace").strip().splitlines()
        raise ValidationError("Windows text recognition gave no result" + (": " + detail[-1][:200] if detail else ""))
    if not out.get("ok"):
        raise ValidationError(out.get("error") or "The ticket could not be read")
    return out


def _ocr_pages(out):
    pages = out.get("pages") or []
    if isinstance(pages, dict):               # PowerShell 5.1 unwraps one-element arrays
        pages = [pages]
    result = {}
    for p in pages:
        words = p.get("words") or []
        if isinstance(words, dict):
            words = [words]
        result[int(p["page"])] = reading_order(words, p.get("width") or 1)
    return result


def read(path, preview_path=None):
    """Same output as the Mac reader: {ok, kind, pages: [{page, method, text, confidence}], page_count, preview}."""
    path = Path(path)
    if path.suffix.lower() == ".pdf":
        texts = pdf_pages(path)
        scanned = [i + 1 for i, t in enumerate(texts) if len(t) < MIN_TEXT]
        ocr, preview = {}, False
        if ocr_available() and (scanned or preview_path):
            try:
                out = run_ocr(path, preview_path, scanned)
                ocr, preview = _ocr_pages(out), bool(out.get("preview"))
            except ValidationError:
                if len(scanned) == len(texts):
                    raise                     # nothing readable without OCR: report why
        elif scanned and len(scanned) == len(texts):
            raise ValidationError("This PDF is a scan with no text inside. Text recognition for scans is not available "
                                  "on this computer; enter the details yourself or use a PDF e-ticket.")
        pages = []
        for i, t in enumerate(texts, 1):
            if i in ocr:
                pages.append({"page": i, "method": "ocr", "text": ocr[i], "confidence": None})
            elif t:
                pages.append({"page": i, "method": "pdf-text", "text": t, "confidence": None})
        return {"ok": True, "kind": "pdf", "pages": pages, "page_count": len(texts), "preview": preview}
    if not ocr_available():
        raise ValidationError("Reading a ticket image needs text recognition, which is not available on this computer. "
                              "Upload the PDF e-ticket instead.")
    out = run_ocr(path, preview_path)
    ocr = _ocr_pages(out)
    return {"ok": True, "kind": "image", "pages": [{"page": 1, "method": "ocr", "text": ocr.get(1, ""), "confidence": None}],
            "page_count": 1, "preview": bool(out.get("preview"))}
