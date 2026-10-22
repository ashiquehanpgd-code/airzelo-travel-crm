"""Safe opening of a company file: refuse files from a newer app version, and before any schema upgrade take a
verified copy of the database; if the upgrade fails, put the copy back so the company file is exactly as before."""
import os
import shutil
import sqlite3
from datetime import datetime
from pathlib import Path

from .db import connect, migrate, current_version, MIGRATIONS

LATEST = MIGRATIONS[-1][0]


class UpgradeError(RuntimeError):
    pass


def _copy(src, dst):
    """Consistent copy with SQLite's online backup API, then an integrity check of the copy."""
    s = sqlite3.connect("file:%s?mode=ro" % Path(src).as_posix(), uri=True)
    d = sqlite3.connect(str(dst))
    try:
        s.backup(d)
        d.execute("PRAGMA journal_mode = DELETE")  # a single self-contained file
        ok = d.execute("PRAGMA integrity_check").fetchone()[0]
    finally:
        d.close()
        s.close()
    if ok != "ok":
        raise UpgradeError("The safety copy failed its integrity check (%s); nothing was changed." % ok)


def _restore(copy, db_path):
    for suffix in ("-wal", "-shm"):
        p = Path(str(db_path) + suffix)
        if p.exists():
            p.unlink()
    tmp = Path(str(db_path) + ".restoring")
    shutil.copyfile(copy, tmp)
    os.replace(tmp, db_path)


def open_company_file(db_path, backup_dir, log=print):
    """Bring the company file up to the current schema. Returns (old_version, new_version, safety_copy or None)."""
    db_path, backup_dir = Path(db_path), Path(backup_dir)
    if not db_path.exists():
        conn = connect(db_path)
        try:
            return 0, migrate(conn), None
        finally:
            conn.close()
    conn = connect(db_path)
    try:
        version = current_version(conn)
    finally:
        conn.close()
    if version > LATEST:
        raise UpgradeError("This company file was saved by a newer version of the app (data version %d; this app reads up to %d). "
                           "Install the latest version to open it. The file was not changed." % (version, LATEST))
    if version == LATEST:
        conn = connect(db_path)
        try:
            return version, migrate(conn), None  # no schema change; seeds any new default settings
        finally:
            conn.close()
    backup_dir.mkdir(parents=True, exist_ok=True)
    copy = backup_dir / ("pre-upgrade-v%d-to-v%d-%s.db" % (version, LATEST, datetime.now().strftime("%Y%m%d-%H%M%S")))
    _copy(db_path, copy)
    log("  Safety copy before upgrade: %s" % copy)
    try:
        conn = connect(db_path)
        try:
            new = migrate(conn)
            if conn.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise UpgradeError("Integrity check failed after the upgrade")
        finally:
            conn.close()
    except Exception as e:
        _restore(copy, db_path)
        raise UpgradeError("The data upgrade from version %d to %d failed (%s). Your company file was restored exactly as it was "
                           "from the safety copy %s. Please contact support." % (version, LATEST, e, copy.name))
    return version, new, copy
