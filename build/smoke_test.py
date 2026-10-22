#!/usr/bin/env python3
"""Smoke-test a packaged build (or a CI build tree) with its OWN bundled Python runtime and core:
create a synthetic agency, start the core on a free port, sign in, record a booking and a payment, check the
figures, make and verify a backup, then stop cleanly and confirm the database is consistent.

  python3 build/smoke_test.py --python <bundled python> --core <path to core/run.py>
  python3 build/smoke_test.py --app "dist/mac-arm64/Travel Agency CRM.app"     (macOS app bundle)
  python3 build/smoke_test.py --app "dist/win-unpacked"                         (Windows unpacked build)
"""
import argparse
import http.cookiejar
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path


def locate(app):
    app = Path(app)
    res = app / "Contents" / "Resources" if app.suffix == ".app" else app / "resources"
    py = res / "python" / ("python.exe" if os.name == "nt" else "bin/python3")
    return py, res / "core" / "run.py"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app")
    ap.add_argument("--python")
    ap.add_argument("--core")
    a = ap.parse_args()
    py, core = locate(a.app) if a.app else (Path(a.python), Path(a.core))
    assert py.exists(), "bundled python not found: %s" % py
    assert core.exists(), "core not found: %s" % core
    tmp = Path(tempfile.mkdtemp(prefix="ta-smoke-"))
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUNBUFFERED="1", PYTHONNOUSERSITE="1", TACRM_NO_OPEN="1",
               TACRM_STDIN_SHUTDOWN="1", TACRM_TICKET_DIR=str(tmp / "tickets"))
    data = tmp / "agency"
    ok = False
    try:
        v = subprocess.run([str(py), "-I", "-u", "-B", str(core), "--version"], capture_output=True, text=True, timeout=60, env=env)
        print("version:", v.stdout.strip() or v.stderr.strip())
        r = subprocess.run([str(py), "-I", "-u", "-B", str(core), "--data", str(data), "--create-agency"], capture_output=True, text=True, timeout=120, env=env,
                           input=json.dumps({"agency_name": "Smoke Test Travels", "username": "owner", "password": "Smoke-Test-Pass-123"}))
        assert r.returncode == 0, r.stdout + r.stderr
        p = subprocess.Popen([str(py), "-I", "-u", "-B", str(core), "--data", str(data), "--port", "0", "--no-browser"], stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
        port, deadline = None, time.time() + 60
        while time.time() < deadline and port is None:
            line = p.stdout.readline()
            if not line:
                break
            if line.startswith("TACRM_READY port="):
                port = int(line.strip().split("=")[1])
        assert port, "core did not report ready"
        jar = http.cookiejar.CookieJar()
        op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        base = "http://127.0.0.1:%d" % port

        def call(method, path, body=None):
            req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                         headers={"Content-Type": "application/json", "X-QT-CSRF": "1"})
            with op.open(req, timeout=30) as resp:
                return json.loads(resp.read() or b"null")
        with op.open(base + "/", timeout=30) as resp:
            assert resp.status == 200
        print("step:", 'call("POST", "/api/auth/login"', flush=True)
        call("POST", "/api/auth/login", {"username": "owner", "password": "Smoke-Test-Pass-123"})
        meta = call("GET", "/api/meta")
        assert meta["settings"]["business_name"] == "Smoke Test Travels"
        assert not meta["settings"].get("pay_account_number"), "no payment details may be built in"
        cash = [x for x in meta["accounts"] if x["account_type"] == "cash"][0]["id"]
        print("step:", 'cust = call(', flush=True)
        rid = lambda r, k: r.get("id") or r[k]["id"]  # created records come back as {"<kind>": {...}} or flat
        cust = rid(call("POST", "/api/customers", {"name": "Synthetic Customer", "phone": "+91 90000 00000"}), "customer")
        sup = rid(call("POST", "/api/suppliers", {"name": "Synthetic Portal", "kind": "b2b_portal"}), "supplier")
        bk = call("POST", "/api/bookings", {"customer_id": cust, "supplier_id": sup, "pnr": "SMK001", "booking_date": "2026-10-10",
                                            "status": "ticketed", "currency": "INR", "exchange_rate": "1", "purchase_cost": "10000",
                                            "selling_price": "12500", "passengers": [{"name": "SYNTHETIC PASSENGER", "pax_type": "ADT"}],
                                            "segments": [{"airline": "Demo Air", "flight_no": "DA 101", "origin": "COK", "destination": "DXB",
                                                          "dep_at": "2026-11-02T04:10", "arr_at": "2026-11-02T06:40"}]})
        bid = rid(bk, "booking")
        print("step:", 'call("POST", "/api/transactions"', flush=True)
        call("POST", "/api/transactions", {"kind": "customer_receipt", "txn_date": "2026-10-11", "account_id": cash, "method": "cash",
                                           "customer_id": cust, "amount": "12500", "allocations": [{"booking_id": bid, "amount": "12500"}]})
        d = call("GET", "/api/dashboard?from=2026-10-01&to=2026-10-31")["summary"]
        assert (d["sales"], d["gross_profit"]) == (1250000, 250000), d
        print("step:", 'b = call("POST", "/api/backups"', flush=True)
        b = call("POST", "/api/backups", {"note": "smoke"})
        assert b.get("name"), b
        print("step:", 'p.stdin.close()', flush=True)
        p.stdin.close()  # the desktop shell's clean shutdown
        p.wait(timeout=30)
        c = sqlite3.connect(str(data / "agency.db"))
        assert c.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert c.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 1
        c.close()
        ok = True
        print("SMOKE TEST PASSED: runtime %s, core %s" % (py, core))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
