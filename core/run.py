#!/usr/bin/env python3
"""Start the Travel Agency CRM core for one company file (agency data folder).

  python3 run.py --data DIR                 start on http://127.0.0.1:8765 for the agency in DIR
  python3 run.py --data DIR --port 0        pick a free port (prints "TACRM_READY port=N")
  python3 run.py --data DIR --create-agency read {"agency_name", "username", "password"} as JSON on stdin,
                                            create the company file and its owner login, print JSON
  python3 run.py --data DIR --backup        make a verified backup now and exit
  python3 run.py --data DIR --reset-password   reset the owner login (needs access to this computer)
  python3 run.py --data DIR --rollback-to N    undo database upgrades after version N (backs up first)
  python3 run.py --version                  print app and data versions as JSON
"""
import argparse
import getpass
import json
import os
import socket
import sys
from pathlib import Path

if sys.version_info < (3, 8):
    sys.exit("Travel Agency CRM needs Python 3.8 or newer.")

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

from server import VERSION, web, auth, backup, services  # noqa: E402
from server.db import connect, rollback, current_version, MIGRATIONS  # noqa: E402
from server.errors import ApiError  # noqa: E402
from server.upgrade import UpgradeError  # noqa: E402


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def create_agency(data_dir):
    """Create a new company file with its owner login. Called by the desktop app; no setup code is typed."""
    req = json.loads(sys.stdin.read() or "{}")
    name = str(req.get("agency_name") or "").strip()
    if not 2 <= len(name) <= 120:
        raise ApiError("Agency name must be 2-120 characters", 400, "validation", "agency_name")
    ctx = web.Context(data_dir)
    if ctx.db_path.exists():
        raise ApiError("A company file already exists in this folder", 409, "exists")
    ctx.init()
    conn = connect(ctx.db_path)
    try:
        auth.setup_owner(conn, dict(req, setup_code=ctx.setup_code), ctx.setup_code)
        services.update_settings(conn, {"business_name": name})
        version = current_version(conn)
    finally:
        conn.close()
    return {"ok": True, "agency_name": name, "data_version": version}


def main():
    ap = argparse.ArgumentParser(description="Travel Agency CRM core")
    ap.add_argument("--host", default=os.environ.get("TACRM_HOST", "127.0.0.1"),
                    help="Interface to bind (default 127.0.0.1 - this computer only)")
    ap.add_argument("--port", type=int, default=int(os.environ.get("TACRM_PORT", "8765")))
    ap.add_argument("--data", default=os.environ.get("TACRM_DATA_DIR", str(ROOT / "data")), help="Agency data folder (company file)")
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--create-agency", action="store_true")
    ap.add_argument("--reset-password", action="store_true")
    ap.add_argument("--backup", action="store_true")
    ap.add_argument("--version", action="store_true")
    ap.add_argument("--rollback-to", type=int, metavar="VERSION",
                    help="Undo database upgrades newer than VERSION (stop the app first)")
    a = ap.parse_args()

    if a.version:
        print(json.dumps({"app_version": VERSION, "data_version": MIGRATIONS[-1][0], "python": sys.version.split()[0]}))
        return

    if a.create_agency:
        try:
            print(json.dumps(create_agency(a.data)))
        except (ApiError, UpgradeError) as e:
            print(json.dumps({"ok": False, "error": getattr(e, "message", str(e)), "field": getattr(e, "field", None)}))
            sys.exit(2)
        return

    if a.rollback_to is not None:
        ctx = web.Context(a.data)
        ctx.init()
        r = backup.create_backup(ctx, "manual", "Before rollback to schema %d" % a.rollback_to)
        print("Verified backup saved: %s" % (ctx.backup_dir / r["name"]))
        conn = connect(ctx.db_path)
        try:
            before = current_version(conn)
            after = rollback(conn, a.rollback_to)
            ok = conn.execute("PRAGMA integrity_check").fetchone()[0]
        finally:
            conn.close()
        print("Database schema %d -> %d (integrity: %s)." % (before, after, ok))
        return

    if a.reset_password:
        ctx = web.Context(a.data)
        ctx.init()
        conn = connect(ctx.db_path)
        user = input("Owner username: ").strip()
        pw = getpass.getpass("New password (min 10 characters): ")
        if pw != getpass.getpass("Repeat new password: "):
            sys.exit("Passwords do not match.")
        auth.reset_password_offline(conn, user, pw)
        conn.close()
        print("Password reset. All sessions were signed out.")
        return
    if a.backup:
        ctx = web.Context(a.data)
        ctx.init()
        r = backup.create_backup(ctx, "manual", "Command-line backup")
        print("Verified backup saved: %s" % (ctx.backup_dir / r["name"]))
        return
    port = free_port() if a.port == 0 else a.port
    try:
        web.serve(a.data, a.host, port, open_browser=not a.no_browser)
    except UpgradeError as e:
        print("TACRM_ERROR %s" % e, flush=True)
        sys.exit(3)


if __name__ == "__main__":
    main()
