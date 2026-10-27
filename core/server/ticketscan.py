"""Offline ticket scanner: read an e-ticket (PDF or image) on this computer and turn it into booking-form fields.

Pipeline (all local, no network):
  1. read_file()  - macOS: bin/ta-ticket-reader (PDFKit text for digital PDFs, Apple Vision OCR for scans / screenshots)
                    Windows and others: server/pdftext.py (bundled pypdf for PDF text, Windows.Media.Ocr for scans)
  2. parse()      - deterministic patterns for PNRs, ticket numbers, flights, airports, dates, fares

Every field carries a confidence ("high", "medium", "low"). Anything not found is left empty and listed as
missing - nothing is guessed. The scanner never saves a booking; it only fills the form for review.
"""
import json
import os
import re
import secrets
import subprocess
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path

from . import pdftext
from .errors import ValidationError, NotFound
from .ticket import AIRPORTS

ROOT = Path(__file__).resolve().parent.parent
READER = ROOT / "bin" / "ta-ticket-reader"
READER_SRC = ROOT / "tools" / "ticket_reader.swift"
SCAN_TYPES = {".pdf": "application/pdf", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".heic": "image/heic"}
MAX_SCAN = 15 * 1024 * 1024

# --------------------------------------------------------------------------- reference data
AIRLINES = {
    "6E": "IndiGo", "AI": "Air India", "IX": "Air India Express", "QP": "Akasa Air", "SG": "SpiceJet", "UK": "Vistara", "9I": "Alliance Air",
    "G8": "Go First", "I5": "AIX Connect", "G9": "Air Arabia", "3L": "Air Arabia Abu Dhabi", "E5": "Air Arabia Egypt", "EK": "Emirates",
    "EY": "Etihad Airways", "QR": "Qatar Airways", "SV": "Saudia", "XY": "flynas", "F3": "flyadeal", "FZ": "flydubai", "GF": "Gulf Air",
    "WY": "Oman Air", "OV": "SalamAir", "KU": "Kuwait Airways", "J9": "Jazeera Airways", "RX": "Riyadh Air", "RJ": "Royal Jordanian",
    "MS": "EgyptAir", "TK": "Turkish Airlines", "PC": "Pegasus", "UL": "SriLankan Airlines", "SQ": "Singapore Airlines", "TR": "Scoot",
    "MH": "Malaysia Airlines", "AK": "AirAsia", "FD": "Thai AirAsia", "D7": "AirAsia X", "TG": "Thai Airways", "CX": "Cathay Pacific",
    "BA": "British Airways", "LH": "Lufthansa", "AF": "Air France", "KL": "KLM", "VS": "Virgin Atlantic", "UA": "United Airlines",
    "AA": "American Airlines", "DL": "Delta", "AC": "Air Canada", "ET": "Ethiopian Airlines", "KQ": "Kenya Airways", "BG": "Biman Bangladesh",
    "BS": "US-Bangla", "RA": "Nepal Airlines", "Q2": "Maldivian", "HM": "Air Seychelles", "QF": "Qantas", "NZ": "Air New Zealand",
    "JL": "Japan Airlines", "NH": "ANA", "PK": "PIA", "SU": "Aeroflot", "LX": "Swiss", "OS": "Austrian", "VJ": "VietJet", "VN": "Vietnam Airlines",
    "GA": "Garuda Indonesia", "PR": "Philippine Airlines", "5J": "Cebu Pacific", "KE": "Korean Air", "OZ": "Asiana", "CA": "Air China",
    "MU": "China Eastern", "CZ": "China Southern", "HU": "Hainan Airlines", "W6": "Wizz Air", "FR": "Ryanair", "U2": "easyJet", "A3": "Aegean",
    "ME": "Middle East Airlines", "R5": "Jordan Aviation", "SM": "Air Cairo", "NE": "Nesma Airlines", "XQ": "SunExpress", "HY": "Uzbekistan Airways",
    "KC": "Air Astana", "LO": "LOT", "IB": "Iberia", "AZ": "ITA Airways", "EI": "Aer Lingus", "SK": "SAS", "AY": "Finnair",
}
AIRLINE_NAMES = sorted({v: k for k, v in AIRLINES.items()}.items(), key=lambda x: -len(x[0]))  # longest names first
EXTRA_AIRPORTS = {
    "IXB": "Bagdogra", "IXC": "Chandigarh", "IXR": "Ranchi", "IXJ": "Jammu", "SXR": "Srinagar", "PAT": "Patna", "NAG": "Nagpur", "IDR": "Indore",
    "BDQ": "Vadodara", "STV": "Surat", "RPR": "Raipur", "VTZ": "Visakhapatnam", "VGA": "Vijayawada", "TIR": "Tirupati", "HBX": "Hubballi",
    "IXG": "Belagavi", "UDR": "Udaipur", "JDH": "Jodhpur", "DED": "Dehradun", "IXL": "Leh", "AGR": "Agra", "BHO": "Bhopal", "NMI": "Navi Mumbai",
    "HYD": "Hyderabad", "SHJ": "Sharjah", "AAN": "Al Ain", "FJR": "Fujairah", "AUH": "Abu Dhabi", "BAH": "Bahrain", "DWC": "Dubai World Central",
    "AJF": "Al Jouf", "ULH": "AlUla", "EAM": "Najran", "BHH": "Bisha", "RAE": "Arar", "WAE": "Wadi Al Dawasir", "DXB": "Dubai",
    "IKA": "Tehran", "BGW": "Baghdad", "NJF": "Najaf", "EBL": "Erbil", "BEY": "Beirut", "DOH": "Doha", "KHI": "Karachi", "LHE": "Lahore",
    "ISB": "Islamabad", "PEK": "Beijing", "PVG": "Shanghai", "CAN": "Guangzhou", "ICN": "Seoul", "NRT": "Tokyo Narita", "HND": "Tokyo Haneda",
    "MNL": "Manila", "CGK": "Jakarta", "DPS": "Bali", "SGN": "Ho Chi Minh City", "HAN": "Hanoi", "HKT": "Phuket", "DMK": "Bangkok Don Mueang",
    "IAD": "Washington", "ORD": "Chicago", "SFO": "San Francisco", "LAX": "Los Angeles", "EWR": "Newark", "YVR": "Vancouver", "BOS": "Boston",
    "FCO": "Rome", "MXP": "Milan", "MAD": "Madrid", "BCN": "Barcelona", "ZRH": "Zurich", "VIE": "Vienna", "MUC": "Munich", "BRU": "Brussels",
    "DUB": "Dublin", "CPH": "Copenhagen", "ARN": "Stockholm", "HEL": "Helsinki", "ATH": "Athens", "SVO": "Moscow", "TAS": "Tashkent",
    "ALA": "Almaty", "JNB": "Johannesburg", "MRU": "Mauritius", "SEZ": "Seychelles", "DAR": "Dar es Salaam", "EBB": "Entebbe",
}
AIRPORT_NAMES = dict(AIRPORTS, **EXTRA_AIRPORTS)
NOT_AIRPORTS = {"THE", "AND", "PNR", "INR", "SAR", "USD", "AED", "QAR", "OMR", "KWD", "BHD", "ADT", "CHD", "INF", "MRS", "MSTR", "FOR",
                "NON", "YES", "TAX", "FEE", "TOT", "REF", "NET", "GST", "VAT", "PAX", "APP", "WEB", "PDF", "ETK", "AIR", "OUT", "ARR", "DEP",
                "ONE", "WAY", "KGS", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG",
                "SEP", "OCT", "NOV", "DEC", "STD", "STA", "ETD", "ETA", "PER", "VIA", "BAG", "HRS", "MIN", "NOT", "ALL", "ANY", "NEW", "OLD",
                "ATR", "PDF", "URL", "WWW", "COM", "PVT", "LTD", "OFF"}
# real airport codes that are also everyday words - ignored when the whole ticket is in capitals
WORD_CODES = {"CAN", "MAN", "HAS", "ARE", "YOU", "BUS", "SEA", "BOS", "LOS", "PER", "VAN", "FAT", "FUN", "HOT", "BED", "TOP", "BAD",
              "RUN", "SIT", "BIG", "AGE", "USE", "END", "OUR", "DUE", "LET", "WAS", "MAY"}
MONTHS = {m: i + 1 for i, m in enumerate(["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"])}
CURRENCY_SIGNS = {"₹": "INR", "RS": "INR", "RS.": "INR", "INR": "INR", "SAR": "SAR", "SR": "SAR", "AED": "AED", "USD": "USD", "$": "USD",
                  "QAR": "QAR", "OMR": "OMR", "KWD": "KWD", "BHD": "BHD", "EUR": "EUR", "GBP": "GBP"}

# --------------------------------------------------------------------------- helpers
def field(value, conf, note=""):
    return {"value": value, "confidence": conf if value not in (None, "") else "missing", "note": note}


def _clean(text):
    text = text.replace(" ", " ").replace("–", "-").replace("—", "-").replace("→", " -> ")
    return re.sub(r"[ \t]+\n", "\n", text)


def _under_header(up, labels):
    """Value under a column header: a row of cells split by wide gaps followed by a row with the same number of cells.
    Only used when the label and value are not adjacent, and only when the columns line up exactly."""
    lines = up.split("\n")
    for i in range(len(lines) - 1):
        heads = [c.strip() for c in re.split(r"\s{3,}", lines[i].strip())]
        hit = next((h for h in heads if h in labels), None)
        if hit and len(heads) > 1:
            vals = [c.strip() for c in re.split(r"\s{3,}", lines[i + 1].strip())]
            if len(vals) == len(heads):
                return vals[heads.index(hit)]
    return None


def _parse_date(s, ref_year=None):
    """Return (date, explicit_year) for common ticket formats, else (None, False)."""
    s = s.strip().upper().replace(",", " ").replace(".", " ")
    m = re.match(r"^(\d{4})-(\d{1,2})-(\d{1,2})", s)
    if m:
        return _mk(int(m.group(1)), int(m.group(2)), int(m.group(3))), True
    m = re.match(r"^(\d{1,2})\s*[-/ ]?\s*([A-Z]{3})[A-Z]*\s*[-/ ]?\s*(\d{2,4})?", s)
    if m and m.group(2) in MONTHS:
        y = m.group(3)
        if y:
            return _mk(int(y) + 2000 if len(y) == 2 else int(y), MONTHS[m.group(2)], int(m.group(1))), True
        return _no_year(MONTHS[m.group(2)], int(m.group(1)), ref_year), False
    m = re.match(r"^([A-Z]{3})[A-Z]*\s+(\d{1,2})\s+(\d{4})", s)
    if m and m.group(1) in MONTHS:
        return _mk(int(m.group(3)), MONTHS[m.group(1)], int(m.group(2))), True
    m = re.match(r"^(\d{1,2})[/-](\d{1,2})[/-](\d{4})", s)
    if m:  # Indian / Gulf tickets: day first
        return _mk(int(m.group(3)), int(m.group(2)), int(m.group(1))), True
    return None, False


def _no_year(mo, d, ref):
    """Month/day without a year: the first such date on or after the ticket's reference date."""
    ref = ref if isinstance(ref, date) else (date(ref, 1, 1) if ref else date.today())
    for y in (ref.year, ref.year + 1):
        dt = _mk(y, mo, d)
        if dt and dt >= ref - timedelta(days=3):
            return dt
    return _mk(ref.year, mo, d)


def _mk(y, mo, d):
    try:
        return date(y, mo, d)
    except ValueError:
        return None


DATE_RX = re.compile(r"\b(\d{4}-\d{2}-\d{2}|\d{1,2}[ -]?(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*[ ,-]*(?:\d{4}|\d{2})?(?![\d:])"
                     r"|(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\s+\d{1,2},?\s+\d{4}|\d{1,2}/\d{1,2}/\d{4})", re.I)
TIME_RX = re.compile(r"(?<![\d:])([01]?\d|2[0-3])[:.]([0-5]\d)(?:\s*(AM|PM|HRS|HR|H)\b)?(?![\d:])|(?<![\d:])([01]\d|2[0-3])([0-5]\d)\s*HRS?\b", re.I)
FLIGHT_RX = re.compile(r"\b([A-Z][A-Z0-9]|[0-9][A-Z])\s?-?\s?(\d{2,4})\b(?![-:/.]\d)")
PNR_LABELS = r"(?:AIRLINE\s+REF(?:ERENCE)?\s*\(PNR\)|AIRLINE\s+PNR|GDS\s+PNR|PNR(?:\s*(?:NO|NUMBER|CODE))?\)?|AIRLINE\s+REF(?:ERENCE)?|BOOKING\s+(?:REFERENCE|REF|CODE)|RESERVATION\s+(?:CODE|NUMBER|NO)|CONFIRMATION\s+(?:CODE|NUMBER|NO)|RECORD\s+LOCATOR)"
CODE_WORDS = {"TRIP", "STATUS", "NUMBER", "DETAILS", "AIRLINE", "BOOKING", "REFERENCE", "TICKET", "FLIGHT", "PASSENGER",
              "ISSUED", "CONFIRMED", "TICKETED", "AGENCY", "TRAVEL", "TRAVELS", "ONWARD", "RETURN", "JOURNEY", "ECONOMY", "BUSINESS"}
NAME_STOP = re.compile(r"\s{2,}|\b(?:ADULT|CHILD|INFANT|ADT|CHD|INF|TICKET|E-?TICKET|ETKT|PASSPORT|SEAT|MEAL|BAGGAGE|CONFIRMED|STATUS|PNR|FREQUENT|DOB|AGE|MALE|FEMALE|NATIONALITY)\b|\d", re.I)


def _airport_tokens(text, all_caps=False):
    """Airport codes as printed (capitals in the original text, so words like 'can' are not read as CAN)."""
    out = []
    for m in re.finditer(r"\(([A-Z]{3})\)|\b([A-Z]{3})\b", text):
        code = m.group(1) or m.group(2)
        if code in NOT_AIRPORTS or (all_caps and not m.group(1) and code in WORD_CODES):
            continue
        if code in AIRPORT_NAMES or m.group(1):
            out.append((m.start(), code))
    return out


def _amount(s):
    s = s.replace(",", "")
    try:
        return round(float(s), 2)
    except ValueError:
        return None


# --------------------------------------------------------------------------- parsing
def parse(text, method="pdf-text", ocr_confidence=None):
    raw = _clean(text or "")
    up = raw.upper()
    ocr = method != "pdf-text"
    base_conf = "medium" if ocr and (ocr_confidence or 0) < 0.85 else "high"
    lower = lambda c: {"high": "medium", "medium": "low", "low": "low"}[c]  # noqa: E731
    warnings = []

    # ---- PNR / references
    pnr = None
    airline_pnr = None
    code_conf = "medium" if ocr else base_conf   # OCR can confuse O / Q / 0 in codes

    def ok_code(val):
        return bool(re.search(r"[A-Z]", val)) and val not in NOT_AIRPORTS and val not in CODE_WORDS and len(val) in (5, 6, 7, 8)
    for m in re.finditer(PNR_LABELS + r"(?:[ \t]{0,3}[:#.\-][ \t]*|[ \t]{1,3}|[ \t]*\n[ \t]*)([A-Z0-9]{5,8})\b", up):
        val = m.group(1)
        if not ok_code(val):
            continue
        if m.group(0).startswith("AIRLINE") and not airline_pnr:
            airline_pnr = val
        elif not pnr and not m.group(0).startswith("AIRLINE"):
            pnr = val
    if not pnr:  # table layout (OCR): labels in one row, values in the same columns on the next row
        lines = up.split("\n")
        for i, ln in enumerate(lines[:-1]):
            cells = re.split(r"\s{3,}", ln.strip())
            for ci, cell in enumerate(cells):
                if re.search(PNR_LABELS, cell) and not cell.startswith("AIRLINE"):
                    vals = re.split(r"\s{3,}", lines[i + 1].strip())
                    if ci < len(vals) and re.fullmatch(r"[A-Z0-9]{5,8}", vals[ci]) and ok_code(vals[ci]):
                        pnr = vals[ci]
                    break
            if pnr:
                break
    if not pnr and not airline_pnr:   # numeric PNRs (e.g. Air Arabia) printed right after "PNR"
        m = re.search(r"\bPNR\s*(?:NO\.?|NUMBER)?\s*[:#\-]?[ \t]*(\d{6,12})\b", up)
        if m and not re.search(r"(PHONE|MOBILE|CONTACT)", up[max(0, m.start() - 30):m.start()]):
            pnr = m.group(1)
    if not pnr and not airline_pnr:   # header row "Airline PNR | GDS PNR | ..." with the codes a few lines below
        m = re.search(r"AIRLINE\s+PNR[^\n]*\n((?:[^\n]*\n){1,5})", up)
        if m:
            c = re.search(r"\b(?=[A-Z0-9]{6}\b)(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\d)([A-Z0-9]{6})\b", m.group(1))
            if not c:   # letters-only PNR on a line of its own
                c = re.search(r"^[ \t]*([A-Z]{6})[ \t]*$", m.group(1), re.M)
            if c and ok_code(c.group(1)):
                airline_pnr = c.group(1)
    pnr = pnr or airline_pnr
    if pnr == airline_pnr:
        airline_pnr = None
    agency_ref = None
    m = re.search(r"(?:BOOKING\s+ID|TRIP\s+ID|ITINERARY\s+(?:NO|ID|NUMBER)|ORDER\s+(?:ID|NO)|REFERENCE\s+(?:NO|NUMBER))\s*[:#.\-]?\s*([A-Z0-9-]{7,24})", up)
    if m and m.group(1) != pnr and re.search(r"\d", m.group(1)):
        agency_ref = m.group(1)

    # ---- issue date / status
    issue = None
    m = re.search(r"(?:DATE\s+OF\s+ISSUE|ISSUE[D]?\s+(?:DATE|ON)|TICKET\s+ISSUED(?:\s+ON)?|BOOKING\s+DATE|BOOKED\s+ON)\s*[:\-]?\s*\n?\s*(?:[A-Z]{3},?\s+)?([^\n]{6,22})", up)
    if m:
        d, _ = _parse_date(m.group(1))
        issue = d
    if not issue:   # table layout: label in a header row, value in the same column of the next row
        v = _under_header(up, ("DATE OF ISSUE", "ISSUE DATE", "ISSUED ON", "TICKET ISSUED", "TICKET ISSUED ON", "BOOKING DATE", "BOOKED ON"))
        if v:
            issue, _ = _parse_date(re.sub(r"^[A-Z]{3},?\s+", "", v))
    status = None
    m = re.search(r"\b(?:BOOKING\s+)?STATUS\s*[:\-]?\s*\n?\s*(CONFIRMED|TICKETED|CANCELLED|CANCELED|ON\s*HOLD|HK|OK)\b", up)
    if not m:
        v = _under_header(up, ("STATUS", "BOOKING STATUS"))
        m = re.fullmatch(r"(CONFIRMED|TICKETED|CANCELLED|CANCELED|ON\s*HOLD|HK|OK)", v or "")
    if m:
        s = m.group(1)
        status = {"CONFIRMED": "confirmed", "HK": "confirmed", "OK": "confirmed", "TICKETED": "ticketed", "CANCELLED": "cancelled",
                  "CANCELED": "cancelled"}.get(s.replace(" ", ""), "on_hold" if "HOLD" in s else None)

    # ---- passengers
    passengers = []
    seen = set()
    taken = []
    for m in re.finditer(r"(?:^|[\s,(])(MR|MRS|MS|MSTR|MISS|MASTER)\.?\s+([A-Z][A-Z' \-]{1,40}?)\s*/\s*([A-Z][A-Z' \-]{1,40}?)\s*(?=\(|\n|$|\s{2})", up, re.M):
        name = (m.group(3).strip() + " " + m.group(2).strip()).strip()    # SURNAME / GIVEN -> GIVEN SURNAME
        if name not in seen and len(name) >= 3:
            seen.add(name)
            taken.append((m.start(), m.end()))
            passengers.append({"pos": m.start(), "title": {"MASTER": "MSTR"}.get(m.group(1), m.group(1)), "name": name})
    for m in re.finditer(r"(?:^|[\s,(])(MR|MRS|MS|MSTR|MISS|MASTER|DR)\.?\s+([A-Z][A-Z'\-]+(?:\s[A-Z][A-Z'\-]+){0,4})", up, re.M):
        if any(a <= m.start() < b for a, b in taken):
            continue
        name = NAME_STOP.split(m.group(2))[0].strip(" -'")
        if len(name) < 3 or name in seen or name in ("PASSENGER", "NAME") or len(name.split()) > 5:
            continue
        seen.add(name)
        passengers.append({"pos": m.start(), "title": {"MASTER": "MSTR", "DR": ""}.get(m.group(1), m.group(1)), "name": name})
    for m in re.finditer(r"\b([A-Z]{2,})/([A-Z]{2,}(?:\s[A-Z]{2,})?)\s*(MR|MRS|MS|MSTR|MISS)\b", up):  # GDS style SURNAME/GIVEN MR
        name = (m.group(2) + " " + m.group(1)).strip()
        if name not in seen and m.group(1) not in MONTHS:
            seen.add(name)
            passengers.append({"pos": m.start(), "title": m.group(3) or "", "name": name})
    if not passengers:   # names printed without a title: "FULL NAME  Adult |" / "FULL NAME (Adult)"
        for m in re.finditer(r"^[ \t]*(\d{1,2}[.)]?\s+)?([A-Z][A-Z'.\-]+(?:[ \t]+[A-Z][A-Z'.\-]+){1,4})[ \t]*[(|,-]?[ \t]*(ADULT|CHILD|INFANT)\b", up, re.M):
            name = m.group(2).strip()
            if name.split()[0] in ("PASSENGER", "TRAVELLER", "TRAVELER", "SECTOR", "TYPE", "NAME", "STATUS", "TOTAL", "BASE", "FARE") or name in seen:
                continue
            seen.add(name)
            passengers.append({"pos": m.start(), "title": "", "name": name})
    if not passengers:
        m = re.search(r"(?:PASSENGER|TRAVELL?ER|GUEST)\s*(?:NAME)?\s*(?:\(S\))?\s*[:\-]\s*([A-Z][A-Z .'\-]{3,50})", up)
        if m:
            passengers.append({"pos": m.start(), "title": "", "name": NAME_STOP.split(m.group(1))[0].strip()})
    passengers.sort(key=lambda p: p["pos"])

    tickets = []
    for m in re.finditer(r"(?<![\d-])(\d{3})[\s-]?(\d{10})(?:[/-]\d{1,2})?(?![\d])", up):
        t = m.group(1) + "-" + m.group(2)
        near = up[max(0, m.start() - 120):m.start()]
        if t not in [x[1] for x in tickets] and not re.search(r"(PHONE|MOBILE|CONTACT|TEL|GSTIN|ACCOUNT)\W*$", near[-40:]):
            tickets.append((m.start(), t, bool(re.search(r"TICKET|ETKT|E-TKT", near))))
    pax_out = []
    for i, p in enumerate(passengers):
        nxt = passengers[i + 1]["pos"] if i + 1 < len(passengers) else len(up)
        line_end = up.find("\n", p["pos"] + 1)
        tk = next((t for t in tickets if p["pos"] <= t[0] < nxt), None)
        kind = "ADT"
        window = up[p["pos"]:min(nxt, (line_end if line_end > 0 else nxt) + 40)]
        if re.search(r"\b(CHILD|CHD)\b", window) or p["title"] == "MSTR":
            kind = "CHD"
        if re.search(r"\b(INFANT|INF)\b", window):
            kind = "INF"
        pax_out.append({"title": field(p["title"], base_conf), "name": field(p["name"], base_conf if len(p["name"].split()) >= 2 else lower(base_conf),
                                                                              "" if len(p["name"].split()) >= 2 else "Only one name found - check spelling"),
                        "pax_type": field(kind, "medium" if kind == "ADT" else base_conf), "ticket_number": field(tk[1] if tk else "", base_conf if tk and tk[2] else "medium")})
    if passengers and tickets and not any(p["ticket_number"]["value"] for p in pax_out):
        for p, t in zip(pax_out, tickets):  # tickets listed in a separate table, same order as names
            p["ticket_number"] = field(t[1], "medium", "Matched to passenger by order")

    # ---- flight segments: time pairs first, flight-number windows as the fallback
    all_caps = len(re.findall(r"[a-z]", raw)) < len(re.findall(r"[A-Z]", raw)) * 0.15
    ref_year0 = issue
    if not ref_year0:
        for dm in DATE_RX.finditer(up):
            d, explicit = _parse_date(dm.group(0))
            if d and explicit:
                ref_year0 = d
                break
    time_segs = _time_segments(raw if len(raw) == len(up) else up, up, all_caps or len(raw) != len(up), ref_year0, base_conf)
    flights = []
    for m in FLIGHT_RX.finditer(up):
        code = m.group(1)
        if code not in AIRLINES:
            continue
        before = up[max(0, m.start() - 12):m.start()]
        if re.search(r"(TERMINAL|GATE|SEAT|ROW|PAGE|ZONE)\s*$", before):
            continue
        num = m.group(2)
        key = code + " " + num.lstrip("0") if len(num) > 2 else code + " " + num
        if flights and flights[-1]["key"] == key and m.start() - flights[-1]["pos"] < 400:
            continue  # same flight repeated nearby (header + detail)
        if any(f["key"] == key for f in flights):
            continue
        flights.append({"pos": m.start(), "end": m.end(), "code": code, "number": num, "key": key})
    ref_year = ref_year0
    segments = []
    for i, f in enumerate(flights):
        start = f["pos"]
        end = flights[i + 1]["pos"] if i + 1 < len(flights) else min(len(up), f["pos"] + 900)
        win = up[start:end]
        prev_end = flights[i - 1]["end"] if i else max(0, f["pos"] - 400)
        back = up[prev_end:start]
        ap = _airport_tokens(win)
        if len({c for _, c in ap}) < 2:
            ap = _airport_tokens(back + "\n" + win)
        codes = []
        for _, c in ap:
            if not codes or codes[-1] != c:
                codes.append(c)
        times = [(int(t.group(1) or t.group(4)) % 12 + (12 if (t.group(3) or "").upper() == "PM" else 0) if (t.group(3) or "").upper() in ("AM", "PM")
                  else int(t.group(1) or t.group(4)), int(t.group(2) or t.group(5))) for t in TIME_RX.finditer(win)]
        if len(times) < 2:
            times = [(int(t.group(1) or t.group(4)), int(t.group(2) or t.group(5))) for t in TIME_RX.finditer(back + "\n" + win)][-4:]
        dates = []
        for dm in DATE_RX.finditer(win):
            d, explicit = _parse_date(dm.group(0), ref_year)
            if d:
                dates.append((d, explicit))
        if not dates:
            for dm in DATE_RX.finditer(back):
                d, explicit = _parse_date(dm.group(0), ref_year)
                if d:
                    dates.append((d, explicit))
        notes = []
        origin = codes[0] if codes else ""
        dest = codes[1] if len(codes) > 1 else ""
        dep_at = arr_at = ""
        conf_dt = base_conf
        if dates and times:
            dep_d = dates[0][0]
            if not dates[0][1]:
                conf_dt = "low"
                notes.append("Year not printed - assumed %d" % dep_d.year)
            dh, dmin = times[0]
            dep_at = "%sT%02d:%02d" % (dep_d.isoformat(), dh, dmin)
            if len(times) > 1:
                ah, amin = times[1]
                arr_d = dates[1][0] if len(dates) > 1 and dates[1][0] >= dep_d and (dates[1][0] - dep_d).days <= 2 else dep_d
                if arr_d == dep_d and (ah, amin) < (dh, dmin):
                    arr_d = dep_d + timedelta(days=1)
                    notes.append("Arrives next day (+1)")
                arr_at = "%sT%02d:%02d" % (arr_d.isoformat(), ah, amin)
        term = re.findall(r"\bTERMINAL\s*[:\-]?\s*([0-9A-Z]{1,2})\b|\bT\s?([1-9])\b", win)
        terms = [a or b for a, b in term][:2]
        meal = re.search(r"\bMEAL\s*[:\-]?\s*([A-Z][A-Z ,\-]{2,30})", win)
        bag = _baggage(win) or ""
        cabin = _cabin(win)
        segments.append({
            "airline": field(AIRLINES[f["code"]], base_conf), "airline_code": f["code"],
            "flight_no": field(f["code"] + " " + f["number"], base_conf),
            "origin": field(origin, base_conf if origin else "missing"), "destination": field(dest, base_conf if dest else "missing"),
            "dep_at": field(dep_at, conf_dt if dep_at else "missing"), "arr_at": field(arr_at, conf_dt if arr_at else "missing"),
            "baggage": field(bag, "medium"), "cabin": field(cabin, "medium"), "airline_pnr": field("", "missing"),
            "terminals": terms, "meal": meal.group(1).strip().title() if meal else "", "notes": notes, "direction": "outbound",
        })
    # a short PDF with one route but flight numbers on separate pages: drop segments with no route at all
    segments = [s for s in segments if s["origin"]["value"] or s["destination"]["value"] or s["dep_at"]["value"]]
    if time_segs:
        segments = time_segs
    _directions(segments)
    trip_type = "one_way"
    if any(s["direction"] == "return" for s in segments):
        trip_type = "round_trip"
    if airline_pnr and len({s["airline_code"] for s in segments}) == 1:   # one airline: its PNR applies to every flight
        for s in segments:
            s["airline_pnr"] = field(airline_pnr, code_conf)

    bag_all = _baggage(up)
    for s in segments:
        if not s["baggage"]["value"] and bag_all:
            s["baggage"] = field(bag_all, "medium", "Taken from the ticket's baggage section")
        if not s["cabin"]["value"]:
            c = _cabin(up)
            if c:
                s["cabin"] = field(c, "medium")

    if len(segments) > 8 and not pnr:      # reports / statements list many unrelated flights
        warnings.append("This file lists %d flights and no PNR - it looks like a report or statement, not a single ticket. "
                        "No flights were filled in." % len(segments))
        segments = []
        trip_type = "one_way"
    fare = _fares(up)
    missing = []
    if not pnr:
        missing.append("PNR / booking reference")
    if not pax_out:
        missing.append("Passenger names")
    elif not any(p["ticket_number"]["value"] for p in pax_out):
        missing.append("Ticket numbers")
    if not segments:
        missing.append("Flight details")
    for i, s in enumerate(segments):
        for k, lbl in (("origin", "from"), ("destination", "to"), ("dep_at", "departure date/time"), ("arr_at", "arrival date/time")):
            if not s[k]["value"]:
                missing.append("Flight %d %s" % (i + 1, lbl))
    if ocr and (ocr_confidence or 1) < 0.6:
        warnings.append("The image is hard to read - check every field carefully.")
    extra = []
    if agency_ref:
        extra.append("Portal / agency ref: " + agency_ref)
    for i, s in enumerate(segments):
        bits = []
        if s["terminals"]:
            bits.append("terminal " + " -> ".join(s["terminals"]))
        if s["meal"]:
            bits.append("meal " + s["meal"])
        if bits:
            extra.append("%s: %s" % (s["flight_no"]["value"], ", ".join(bits)))
    return {
        "method": method, "ocr_confidence": ocr_confidence,
        "booking": {"pnr": field(pnr or "", code_conf if pnr else "missing", "Check letters O / 0 / Q" if pnr and ocr else ""), "agency_ref": field(agency_ref or "", "medium"),
                    "issue_date": field(issue.isoformat() if issue else "", "medium"),
                    "status": field(status or "", "medium", "Only set when the ticket states it"), "trip_type": trip_type},
        "passengers": pax_out, "segments": segments, "fare": fare,
        "extra_notes": "\n".join(extra), "missing": missing, "warnings": warnings,
    }


NOT_FLIGHT_TIME = re.compile(r"(CHECK[\s-]?IN|CLOS\w*|BAG\s*DROP|BOARDING|GATE|REPORT\w*|BOOK\w*|PRINT\w*|GENERATED|ISSUE\w*|DATE\s+OF|"
                             r"PAYMENT|TRANSACTION|CREATED|DOWNLOAD\w*)[^\n]{0,40}$")


def _times(text):
    out = []
    for t in TIME_RX.finditer(text):
        line_start = text.rfind("\n", 0, t.start()) + 1
        if NOT_FLIGHT_TIME.search(text[line_start:t.start()]):
            continue              # check-in closing, boarding, booking or print times are not flight times
        if re.search(r"(DURATION|TRAVEL\s*TIME|FLYING\s*TIME|LAYOVER|JOURNEY\s*TIME)[^\n]{0,20}$", text[line_start:t.start()]):
            continue              # "Duration 2:35"
        if t.group(1) and len(t.group(1)) == 1 and (t.group(3) or "").upper() in ("HRS", "HR", "H"):
            continue              # "1:35 hrs" is a duration; flight times are printed as 01:35 / 13:35
        h, m = int(t.group(1) or t.group(4)), int(t.group(2) or t.group(5))
        ap = (t.group(3) or "").upper()
        if ap == "PM" and h < 12:
            h += 12
        elif ap == "AM" and h == 12:
            h = 0
        out.append((t.start(), t.end(), h, m))
    return out


def _nearest_date(up, pos, lo, hi, ref_year):
    """Closest date to `pos` between lo..hi; a date just after the time wins only when it is closer."""
    best = None
    for dm in DATE_RX.finditer(up, lo, hi):
        d, explicit = _parse_date(dm.group(0), ref_year)
        if not d:
            continue
        dist = (pos - dm.end()) if dm.end() <= pos else (dm.start() - pos) + 0.5   # ties go to the date before
        if best is None or dist < best[0]:
            best = (dist, d, explicit)
    return (best[1], best[2]) if best else (None, False)


def _is_airport(raw, start, code, paren, all_caps):
    return code not in NOT_AIRPORTS and (paren or code in AIRPORT_NAMES) and not (all_caps and not paren and code in WORD_CODES)


def _near_before(raw, t, all_caps):
    """Airport printed right before a time on the same line: 'COK 22:50'."""
    ls = raw.rfind("\n", 0, t[0]) + 1
    m = re.search(r"(\()?\b([A-Z]{3})\b(\))?[ \t,:\-]{0,4}$", raw[max(ls, t[0] - 12):t[0]])
    return m.group(2) if m and _is_airport(raw, 0, m.group(2), bool(m.group(1)), all_caps) else ""


def _near_after(raw, t, all_caps):
    """Airport printed right after a time: '04:10 COK', '12:45 PM <newline> GIZ', '18:10 hrs (DXB)'."""
    m = re.match(r"(?:[ \t]*(?:AM|PM|am|pm|HRS?|hrs?|H)\b)?[ \t,]*\n?[ \t]*(\()?([A-Z]{3})\b(\))?", raw[t[1]:t[1] + 18])
    return m.group(2) if m and _is_airport(raw, 0, m.group(2), bool(m.group(1)), all_caps) else ""


def _time_segments(raw, up, all_caps, ref_year, base_conf):
    """Pair departure / arrival times that each have an airport printed near them."""
    times = [t for t in _times(up) if _airport_tokens(raw[max(0, t[0] - 70):t[1] + 70], all_caps)]
    flights = [(m.start(), m.group(1), m.group(2)) for m in FLIGHT_RX.finditer(up) if m.group(1) in AIRLINES
               and not re.search(r"(TERMINAL|GATE|SEAT|ROW|PAGE|ZONE)\s*$", up[max(0, m.start() - 12):m.start()])]
    segs, i, prev_end = [], 0, 0
    while i + 1 < len(times):
        t1, t2 = times[i], times[i + 1]
        nxt = times[i + 2][0] if i + 2 < len(times) else len(up)
        stop = t2[1]
        for _ in range(4):                       # the arrival airport can sit up to 3 lines under the time
            k = up.find("\n", stop + 1)
            stop = k if k >= 0 else len(up)
        tail_end = min(nxt, stop, t2[1] + 160)
        main, tail = [], []
        for _, c in _airport_tokens(raw[prev_end:t2[1]], all_caps):
            if not main or main[-1] != c:
                main.append(c)
        for _, c in _airport_tokens(raw[t2[1]:tail_end], all_caps):
            if not tail or tail[-1] != c:
                tail.append(c)
        nb1, nb2, na1, na2 = _near_before(raw, t1, all_caps), _near_before(raw, t2, all_caps), _near_after(raw, t1, all_caps), _near_after(raw, t2, all_caps)
        if nb1 and nb2 and nb1 != nb2:
            origin, dest = nb1, nb2                 # "COK 22:50  SIN 06:10"
        elif na1 and na2 and na1 != na2:
            origin, dest = na1, na2                 # "04:10 COK ... 06:40 DXB"
        elif (nb1 or na1) and (na2 or nb2 or tail):
            origin = nb1 or na1
            dest = na2 or nb2 or next((c for c in tail if c != origin), "")
        elif len(main) >= 2:
            origin, dest = main[-2], main[-1]       # airports in their own columns before the times
        else:
            origin, dest = (main[-1] if main else ""), (tail[0] if tail else "")
        if not origin or not dest or origin == dest:
            i += 1          # not a departure / arrival pair - try the next time
            continue
        same_row = "\n" not in up[t1[1]:t2[0]]
        row_start = up.rfind("\n", 0, t1[0]) + 1
        row_end = up.find("\n", t2[1])
        row_has_dates = bool(DATE_RX.search(up, row_start, row_end if row_end >= 0 else len(up)))
        below = []
        if same_row and not row_has_dates:   # table rows (OCR): "04:10 COK    06:40 DXB" with both dates on the line underneath
            nl = up.find("\n", t2[1])
            nl2 = nl
            for _ in range(2):                    # dates can sit one or two lines under the times
                k = up.find("\n", nl2 + 1) if nl2 >= 0 else -1
                nl2 = k if k >= 0 else len(up)
            for dm in DATE_RX.finditer(up, nl if nl >= 0 else t2[1], nl2):
                d, ex = _parse_date(dm.group(0), ref_year)
                if d:
                    below.append((d, ex))
        if len(below) >= 2:
            (d1, e1), (d2, e2) = below[0], below[1]
            tail_end = max(tail_end, (nl2 if nl2 >= 0 else tail_end))
        else:
            d1, e1 = _nearest_date(up, t1[0], max(prev_end, t1[0] - 160), t2[0], ref_year)
            d2, e2 = _nearest_date(up, t2[0], t1[1], tail_end, ref_year)
            if not d1 and below:
                d1, e1 = below[0]
        if not d1:
            i += 1
            continue
        notes = [] if e1 else ["Year not printed - assumed %d" % d1.year]
        dep = datetime(d1.year, d1.month, d1.day, t1[2], t1[3])
        if not d2 or d2 < d1 or (d2 - d1).days > 2:
            d2 = d1
        arr = datetime(d2.year, d2.month, d2.day, t2[2], t2[3])
        if arr < dep:
            arr += timedelta(days=1)
            notes.append("Arrives next day (+1)")
        hours = (arr - dep).total_seconds() / 3600
        if hours > 30:
            i += 1
            continue
        fl = [f for f in flights if prev_end <= f[0] < t2[0]] or [f for f in flights if t2[1] <= f[0] < tail_end]
        f = fl[-1] if fl and fl[-1][0] < t2[0] else (fl[0] if fl else None)
        block = up[prev_end:tail_end]
        airline = AIRLINES[f[1]] if f else next((n for n, c in AIRLINE_NAMES if n.upper() in block), "")
        conf = base_conf if e1 else "low"
        segs.append({
            "airline": field(airline, base_conf if f else "medium"), "airline_code": f[1] if f else "",
            "flight_no": field(f[1] + " " + f[2] if f else "", base_conf if f else "missing"),
            "origin": field(origin, base_conf), "destination": field(dest, base_conf),
            "dep_at": field(dep.strftime("%Y-%m-%dT%H:%M"), conf), "arr_at": field(arr.strftime("%Y-%m-%dT%H:%M"), conf),
            "baggage": field(_baggage(block), "medium"), "cabin": field(_cabin(block), "medium"), "airline_pnr": field("", "missing"),
            "terminals": [a or b for a, b in re.findall(r"\bTERMINAL\s*[:\-]?\s*([0-9A-Z]{1,2})\b|\bT\s?([1-9])\b", block)][:2],
            "meal": "", "notes": notes, "direction": "outbound",
        })
        line_end = up.find("\n", t2[1])
        prev_end = min(tail_end, line_end if line_end >= 0 else tail_end)   # the next flight starts after this arrival's line
        i += 2
    # itineraries repeated per passenger / page: keep each flight once
    out, seen = [], set()
    for s in segs:
        key = (s["origin"]["value"], s["destination"]["value"], s["dep_at"]["value"])
        if key not in seen:
            seen.add(key)
            out.append(s)
    return out


def _baggage(s):
    m = re.search(r"\b(\d{1,2})\s?(?:KGS?)?\s?\+\s?(\d{1,2})\s?KGS?\b", s)     # "20+7KG" = 20 kg checked + 7 kg cabin
    if m and int(m.group(2)) <= 10:
        return "%s kg + %s kg cabin" % (m.group(1), m.group(2))
    found = []
    for m in re.finditer(r"\b(\d{1,2})\s?(KGS?|K|PCS?|PIECES?)\b", s):
        unit = "kg" if m.group(2).startswith("K") else "pc"
        after = re.match(r"[ \t]*(CABIN|HAND|CARRY|CHECK)[\w-]*[ \t]*([:\-]?)[ \t]*(\d?)", s[m.end():m.end() + 16])
        if after and not after.group(3) and not after.group(2):   # label right after the amount: "7 KG CABIN"
            kind = "checked" if after.group(1) == "CHECK" else "cabin"
        else:                                                      # otherwise the nearest label before it
            before = s[max(0, m.start() - 24):m.start()]
            c = max(before.rfind("CABIN"), before.rfind("HAND"), before.rfind("CARRY"))
            kind = "cabin" if c > before.rfind("CHECK") else "checked"
        found.append((m.group(1) + " " + unit, kind))
        if len(found) == 4:
            break
    checked_all = [v for v, k in found if k == "checked"]
    checked = checked_all[0] if checked_all else ""
    if len(checked_all) > 1 and checked_all[0].endswith("pc") and checked_all[1].endswith("kg"):
        checked = checked_all[0] + " x " + checked_all[1]   # 1 pc x 23 kg
    cabin = next((v for v, k in found if k == "cabin"), "")
    if not checked and not cabin:
        return ""
    return (checked or "") + (" + " + cabin + " cabin" if cabin else "")


def _cabin(s):
    m = re.search(r"\b(PREMIUM\s+ECONOMY|ECONOMY|BUSINESS|FIRST\s+CLASS|ECO\b)", s)
    if not m:
        return ""
    v = m.group(1).replace("ECO", "ECONOMY") if m.group(1) == "ECO" else m.group(1)
    return v.title().replace("Class", "").strip()


def _fares(up):
    cur = None
    for k in ("INR", "SAR", "AED", "USD", "QAR", "OMR", "KWD", "BHD", "EUR", "GBP"):
        if re.search(r"\b" + k + r"\b", up):
            cur = k
            break
    if not cur and ("₹" in up or re.search(r"\bRS\.?\s*\d", up)):
        cur = "INR"
    amt = r"(?:₹|RS\.?|INR|SAR|AED|USD|QAR|OMR|KWD|BHD|\$)?\s*([\d,]+(?:\.\d{1,2})?)"
    out = {"currency": cur or ""}
    for key, rx in (("base", r"BASE\s+FARE|BASIC\s+FARE|^FARE\b"), ("taxes", r"TAXES?\s*(?:&|AND)?\s*(?:FEES?|SURCHARGES?|CHARGES)?|AIRPORT\s+(?:FEES|CHARGES)"),
                    ("total", r"GRAND\s+TOTAL|TOTAL\s+(?:FARE|AMOUNT|PRICE|PAID)|AMOUNT\s+PAID|TOTAL\b")):
        m = re.search(r"(?:" + rx + r")[^\n\d₹$]{0,25}" + amt + r"(?!\s*(?:KGS?|PCS?|%|H\b|HRS?\b|M\b|MIN))", up, re.M)
        val = _amount(m.group(1)) if m else None
        if val is not None and val < 50:      # "Total 2 passengers", "20 kg" etc. are not fares
            val = None
        out[key] = field(val if val is not None else "", "medium" if val is not None else "missing")
    if out["total"]["value"] and out["base"]["value"] and out["taxes"]["value"]:
        if abs(out["base"]["value"] + out["taxes"]["value"] - out["total"]["value"]) < 1:
            for k in ("base", "taxes", "total"):
                out[k]["confidence"] = "high"
    return out


def _directions(segments):
    """Onward / return split. A stay of 24h+ between flights starts a new journey; a trip that ends where it began is a return
    trip (the return starts at the first flight leaving the furthest point)."""
    if len(segments) < 2:
        return
    start = segments[0]["origin"]["value"]

    def t(s, k):
        try:
            return datetime.strptime(s[k]["value"], "%Y-%m-%dT%H:%M")
        except (TypeError, ValueError):
            return None
    turn = None
    for i in range(1, len(segments)):
        a, d = t(segments[i - 1], "arr_at") or t(segments[i - 1], "dep_at"), t(segments[i], "dep_at")
        if a and d and (d - a) >= timedelta(hours=24):
            turn = i
            break
    round_trip = segments[-1]["destination"]["value"] == start and start
    if turn is None and round_trip:
        # no measurable stay: the return starts with the first flight whose route heads back (its destination was visited before)
        visited = {start}
        for i, sg in enumerate(segments):
            if i and sg["destination"]["value"] in visited:
                turn = i
                break
            visited.add(sg["destination"]["value"])
    if turn is None:
        return
    for sg in segments[turn:]:
        sg["direction"] = "return" if round_trip else "other"


# --------------------------------------------------------------------------- file handling
def scan_dir(ctx):
    d = ctx.data_dir / "scans"
    d.mkdir(parents=True, exist_ok=True)
    try:
        d.chmod(0o700)
    except OSError:
        pass
    cutoff = time.time() - 2 * 86400   # unsaved scans are removed after two days
    for p in d.iterdir():
        try:
            if p.stat().st_mtime < cutoff:
                p.unlink()
        except OSError:
            pass
    return d


def mac_reader_ready():
    return sys.platform == "darwin" and READER.exists() and os.access(READER, os.X_OK)


def reader_status():
    """Is the offline reader ready on this computer?"""
    if mac_reader_ready():
        return {"ready": True, "engine": "Apple PDFKit + Vision (on this Mac)", "ocr": True}
    return pdftext.status()


def read_file(path, preview_path=None, timeout=180):
    st = reader_status()
    if not st["ready"]:
        raise ValidationError(st["reason"])
    if not mac_reader_ready():
        return pdftext.read(path, preview_path)
    args = [str(READER), str(path)] + ([str(preview_path)] if preview_path else [])
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise ValidationError("Reading the ticket took too long. Try a clearer image or a PDF.")
    try:
        out = json.loads(r.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        raise ValidationError("The ticket could not be read on this Mac")
    if not out.get("ok"):
        raise ValidationError(out.get("error") or "The ticket could not be read")
    return out


def scan(conn, ctx, data):
    import base64
    name = os.path.basename(str(data.get("name") or "ticket"))[:150]
    ext = os.path.splitext(name)[1].lower()
    if ext not in SCAN_TYPES:
        raise ValidationError("Upload a PDF, JPG or PNG ticket (got %s)" % (ext or "no extension"))
    raw = data.get("data") or ""
    if raw.startswith("data:") and "," in raw[:100]:
        raw = raw.split(",", 1)[1]
    try:
        content = base64.b64decode(raw, validate=True)
    except Exception:
        raise ValidationError("The file could not be read")
    if not content:
        raise ValidationError("The file is empty")
    if len(content) > MAX_SCAN:
        raise ValidationError("Ticket files must be 15 MB or smaller")
    folder = scan_dir(ctx)
    sid = secrets.token_hex(12)
    path = folder / (sid + ext)
    path.write_bytes(content)
    (folder / (sid + ".name")).write_text(name, encoding="utf-8")
    preview = folder / (sid + ".preview.png")   # never the uploaded file itself (a PNG upload has the same stem)
    started = time.time()
    out = read_file(path, preview)
    text = "\n\n".join(p["text"] for p in out["pages"])
    methods = {p["method"] for p in out["pages"]}
    method = "ocr" if "ocr" in methods and "pdf-text" not in methods else ("mixed" if len(methods) > 1 else (methods.pop() if methods else "pdf-text"))
    confs = [p["confidence"] for p in out["pages"] if p.get("confidence") is not None]
    result = parse(text, method, sum(confs) / len(confs) if confs else None)
    if len(text.strip()) < 30:
        result["warnings"].append("No readable text was found in this file.")
    dup = []
    if result["booking"]["pnr"]["value"]:
        dup = [dict(r) for r in conn.execute("SELECT id, ref, status FROM bookings WHERE pnr = ? COLLATE NOCASE", (result["booking"]["pnr"]["value"],))]
    tk = [p["ticket_number"]["value"] for p in result["passengers"] if p["ticket_number"]["value"]]
    tk_dup = [dict(r) for r in conn.execute("SELECT DISTINCT b.id, b.ref, p.ticket_number FROM booking_passengers p JOIN bookings b ON b.id = p.booking_id "
                                            "WHERE p.ticket_number IN (%s)" % ",".join("?" * len(tk)), tk)] if tk else []
    result.update({"scan_id": sid, "file_name": name, "kind": out["kind"], "page_count": out["page_count"], "size": len(content),
                   "preview": bool(out.get("preview") and preview.exists()), "seconds": round(time.time() - started, 1),
                   "duplicate_pnr": dup, "duplicate_tickets": tk_dup, "engine": reader_status()["engine"]})
    return result


def scan_file(ctx, sid):
    if not re.fullmatch(r"[0-9a-f]{24}", sid or ""):
        raise NotFound("Scan not found")
    folder = scan_dir(ctx)
    for ext in SCAN_TYPES:
        p = folder / (sid + ext)
        if p.exists():
            name_file = folder / (sid + ".name")
            return p, (name_file.read_text(encoding="utf-8") if name_file.exists() else p.name), SCAN_TYPES[ext]
    raise NotFound("The scanned file is no longer available - upload it again")


def preview_path(ctx, sid):
    if not re.fullmatch(r"[0-9a-f]{24}", sid or ""):
        raise NotFound("Scan not found")
    p = scan_dir(ctx) / (sid + ".preview.png")
    if not p.exists():
        raise NotFound("No preview")
    return p


def attach_scan(conn, ctx, bid, sid):
    """Store the scanned ticket with the booking (as a normal booking document) and remove the temporary copy."""
    import base64
    from . import services
    path, name, _ = scan_file(ctx, sid)
    did = services.save_document(conn, ctx, {"name": name, "data": base64.b64encode(path.read_bytes()).decode(), "label": "E-ticket (scanned)"},
                                 booking_id=bid)
    for p in scan_dir(ctx).glob(sid + ".*"):
        try:
            p.unlink()
        except OSError:
            pass
    return did


def discard_scan(ctx, sid):
    if not re.fullmatch(r"[0-9a-f]{24}", sid or ""):
        return
    for p in scan_dir(ctx).glob(sid + ".*"):
        try:
            p.unlink()
        except OSError:
            pass
