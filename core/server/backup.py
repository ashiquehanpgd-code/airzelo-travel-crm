"""Backup and restore.

A backup is a single .zip containing:
  agency.db    - consistent snapshot made with SQLite's online backup API
  uploads/...     - all customer/ticket documents
  manifest.json   - app version, schema version, created time and SHA-256 of every file

Backups are never overwritten (unique timestamped names). Restoring first
verifies the archive, then takes a safety backup of the current data, then
swaps the data in atomically.
"""
import hashlib
import json
import os
import shutil
import sqlite3
import tempfile
import threading
import time
import zipfile
from datetime import datetime
from pathlib import Path

from . import VERSION
from .db import connect, migrate, get_setting, now_iso, current_version
from .errors import ValidationError, NotFound

lock = threading.RLock()


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _unique_name(backup_dir, kind):
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    base = "backup-%s-%s" % (kind, stamp)
    name = base + ".zip"
    n = 1
    while (backup_dir / name).exists():
        n += 1
        name = "%s-%d.zip" % (base, n)
    return name


def create_backup(ctx, kind="manual", note=None):
    """kind: manual | auto | pre-restore"""
    with lock:
        ctx.backup_dir.mkdir(parents=True, exist_ok=True)
        name = _unique_name(ctx.backup_dir, kind)
        final = ctx.backup_dir / name
        tmpdir = Path(tempfile.mkdtemp(prefix="qtbk-", dir=str(ctx.backup_dir)))
        try:
            snap = tmpdir / "agency.db"
            src = connect(ctx.db_path)
            dst = sqlite3.connect(str(snap))
            try:
                src.backup(dst)
                dst.execute("PRAGMA journal_mode = DELETE")
            finally:
                dst.close()
                schema = current_version(src)
                src.close()
            files = {"agency.db": _sha256_file(snap)}
            uploads = []
            if ctx.upload_dir.exists():
                for p in sorted(ctx.upload_dir.iterdir()):
                    if p.is_file() and not p.name.endswith(".part"):
                        uploads.append(p)
                        files["uploads/" + p.name] = _sha256_file(p)
            manifest = {"app": "Travel Agency CRM", "app_version": VERSION, "schema_version": schema, "kind": kind,
                        "note": note or "", "created_at": now_iso(), "files": files}
            part = final.with_suffix(".zip.part")
            with zipfile.ZipFile(part, "w", compression=zipfile.ZIP_DEFLATED) as z:
                z.write(snap, "agency.db")
                for p in uploads:
                    z.write(p, "uploads/" + p.name)
                z.writestr("manifest.json", json.dumps(manifest, indent=2))
            with open(part, "rb") as fh:
                os.fsync(fh.fileno())
            if final.exists():
                raise RuntimeError("Backup name collision")
            os.replace(part, final)
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)
        result = verify_backup(final)
        if not result["ok"]:
            raise RuntimeError("Backup verification failed: %s" % "; ".join(result["problems"]))
        return {"name": name, "size": final.stat().st_size, "manifest": manifest, "verified": True}


def verify_backup(path):
    path = Path(path)
    problems = []
    manifest = None
    try:
        with zipfile.ZipFile(path) as z:
            bad = z.testzip()
            if bad:
                problems.append("Corrupt entry: %s" % bad)
            names = set(z.namelist())
            for n in names:
                if n.startswith(("/", "\\")) or "\\" in n or ":" in n or ".." in Path(n).parts:
                    problems.append("Unsafe path in archive: %s" % n)
            if "manifest.json" not in names:
                problems.append("manifest.json missing")
            else:
                manifest = json.loads(z.read("manifest.json"))
                if manifest.get("app") != "Travel Agency CRM":
                    problems.append("Not a backup made by this app")
                for fname, digest in (manifest.get("files") or {}).items():
                    if fname not in names:
                        problems.append("Missing file: %s" % fname)
                        continue
                    h = hashlib.sha256()
                    with z.open(fname) as fh:
                        for chunk in iter(lambda: fh.read(1 << 20), b""):
                            h.update(chunk)
                    if h.hexdigest() != digest:
                        problems.append("Checksum mismatch: %s" % fname)
            if "agency.db" in names and not problems:
                tmpdir = tempfile.mkdtemp(prefix="qtvf-")
                try:
                    z.extract("agency.db", tmpdir)
                    c = sqlite3.connect(os.path.join(tmpdir, "agency.db"))
                    try:
                        res = c.execute("PRAGMA integrity_check").fetchone()[0]
                        if res != "ok":
                            problems.append("Database integrity check failed: %s" % res)
                        fk = c.execute("PRAGMA foreign_key_check").fetchall()
                        if fk:
                            problems.append("Foreign key problems: %d" % len(fk))
                        c.execute("SELECT COUNT(*) FROM bookings").fetchone()
                    finally:
                        c.close()
                finally:
                    shutil.rmtree(tmpdir, ignore_errors=True)
    except zipfile.BadZipFile:
        problems.append("File is not a valid zip archive")
    except Exception as e:  # pragma: no cover - defensive
        problems.append("Could not read backup: %s" % e)
    return {"ok": not problems, "problems": problems, "manifest": manifest}


def list_backups(ctx):
    out = []
    if ctx.backup_dir.exists():
        for p in sorted(ctx.backup_dir.glob("backup-*.zip"), key=lambda p: p.stat().st_mtime, reverse=True):
            kind = p.name.split("-")[1] if p.name.count("-") >= 2 else "manual"
            out.append({"name": p.name, "size": p.stat().st_size, "kind": kind,
                        "created_at": datetime.fromtimestamp(p.stat().st_mtime).replace(microsecond=0).isoformat()})
    return out


def backup_path(ctx, name):
    if not name or "/" in name or "\\" in name or not name.startswith("backup-") or not name.endswith(".zip"):
        raise ValidationError("Invalid backup name")
    p = ctx.backup_dir / name
    if not p.exists():
        raise NotFound("Backup not found")
    return p


def prune_auto_backups(ctx, keep):
    """Only automatic backups are pruned, oldest first. Manual and pre-restore backups are kept."""
    autos = sorted(ctx.backup_dir.glob("backup-auto-*.zip"), key=lambda p: p.stat().st_mtime, reverse=True)
    removed = []
    for p in autos[keep:]:
        try:
            p.unlink()
            removed.append(p.name)
        except OSError:
            pass
    return removed


def restore_backup(ctx, path):
    """Verify, safety-backup the current data, then replace database + uploads."""
    path = Path(path)
    with lock:
        result = verify_backup(path)
        if not result["ok"]:
            raise ValidationError("Backup failed verification: " + "; ".join(result["problems"]))
        safety = create_backup(ctx, "pre-restore", "Automatic safety copy before restoring %s" % path.name)
        staging = Path(tempfile.mkdtemp(prefix="qtrs-", dir=str(ctx.data_dir)))
        try:
            with zipfile.ZipFile(path) as z:
                z.extract("agency.db", staging)
                for n in z.namelist():
                    if n.startswith("uploads/") and not n.endswith("/"):
                        z.extract(n, staging)
            # bring the restored database up to the current schema
            c = connect(staging / "agency.db")
            try:
                migrate(c)
                c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                c.execute("DELETE FROM sessions")
            finally:
                c.close()
            for suffix in ("-wal", "-shm"):
                p = Path(str(staging / "agency.db") + suffix)
                if p.exists():
                    p.unlink()
            # swap database (WAL checkpoint first so no stale -wal file wins)
            try:
                live = connect(ctx.db_path)
                live.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                live.close()
            except Exception:
                pass
            for suffix in ("-wal", "-shm"):
                p = Path(str(ctx.db_path) + suffix)
                if p.exists():
                    p.unlink()
            os.replace(staging / "agency.db", ctx.db_path)
            # swap uploads directory
            new_up = staging / "uploads"
            new_up.mkdir(exist_ok=True)
            old_up = ctx.data_dir / ("uploads-replaced-" + datetime.now().strftime("%Y%m%d%H%M%S"))
            if ctx.upload_dir.exists():
                os.replace(ctx.upload_dir, old_up)
            os.replace(new_up, ctx.upload_dir)
            shutil.rmtree(old_up, ignore_errors=True)  # its contents are inside the safety backup
        finally:
            shutil.rmtree(staging, ignore_errors=True)
        return {"restored": path.name, "safety_backup": safety["name"]}


class AutoBackup(threading.Thread):
    daemon = True

    def __init__(self, ctx, log=print):
        super().__init__(name="auto-backup")
        self.ctx = ctx
        self.log = log
        self.stop_event = threading.Event()

    def due(self):
        conn = connect(self.ctx.db_path)
        try:
            hours = int(get_setting(conn, "auto_backup_hours", "24") or 0)
            keep = int(get_setting(conn, "backup_keep", "30") or 30)
        finally:
            conn.close()
        if hours <= 0:
            return False, keep
        autos = sorted(self.ctx.backup_dir.glob("backup-*.zip"), key=lambda p: p.stat().st_mtime, reverse=True) \
            if self.ctx.backup_dir.exists() else []
        if not autos:
            return True, keep
        return (time.time() - autos[0].stat().st_mtime) >= hours * 3600, keep

    def run(self):
        while not self.stop_event.is_set():
            try:
                due, keep = self.due()
                if due:
                    r = create_backup(self.ctx, "auto")
                    prune_auto_backups(self.ctx, keep)
                    self.log("Automatic backup saved: %s" % r["name"])
            except Exception as e:
                self.log("Automatic backup failed: %s" % e)
            self.stop_event.wait(600)
