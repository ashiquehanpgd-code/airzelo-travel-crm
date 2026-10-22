"""Product foundation: company files, safe upgrades with automatic restore, agency creation and desktop start-up."""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from server import db, upgrade, services  # noqa: E402
from server.db import connect, rollback, current_version  # noqa: E402

LATEST = db.MIGRATIONS[-1][0]


def run_core(*args, stdin=None, data=None):
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", TACRM_NO_OPEN="1")
    return subprocess.run([sys.executable, str(ROOT / "run.py"), "--data", str(data), *args], input=stdin,
                          capture_output=True, text=True, timeout=60, env=env)


class CompanyFile(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ta-platform-"))
        self.db = self.tmp / "agency.db"
        self.backups = self.tmp / "backups"

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def counts(self):
        c = sqlite3.connect(str(self.db))
        try:
            return {t: c.execute("SELECT COUNT(*) FROM %s" % t).fetchone()[0] for t in ("customers", "accounts", "settings")}
        finally:
            c.close()

    def test_new_company_file_is_created_at_the_latest_version(self):
        old, new, copy = upgrade.open_company_file(self.db, self.backups)
        self.assertEqual((old, new, copy), (0, LATEST, None))

    def test_upgrade_takes_a_verified_safety_copy(self):
        upgrade.open_company_file(self.db, self.backups)
        c = connect(self.db)
        services.create_customer(c, {"name": "Synthetic Customer"})
        rollback(c, 3)
        c.close()
        old, new, copy = upgrade.open_company_file(self.db, self.backups)
        self.assertEqual((old, new), (3, LATEST))
        self.assertTrue(copy.exists() and copy.parent == self.backups)
        s = sqlite3.connect(str(copy))
        self.assertEqual(s.execute("SELECT MAX(version) FROM schema_migrations").fetchone()[0], 3)
        self.assertEqual(s.execute("SELECT COUNT(*) FROM customers").fetchone()[0], 1)
        s.close()

    def test_failed_upgrade_restores_the_company_file(self):
        upgrade.open_company_file(self.db, self.backups)
        c = connect(self.db)
        services.create_customer(c, {"name": "Synthetic Customer"})
        rollback(c, 3)
        c.close()
        before = self.counts()
        orig_migrations, orig_latest = list(db.MIGRATIONS), upgrade.LATEST
        db.MIGRATIONS.append((LATEST + 1, "broken test migration", "CREATE TABLE broken(;"))
        upgrade.LATEST = LATEST + 1
        try:
            with self.assertRaises(upgrade.UpgradeError) as cm:
                upgrade.open_company_file(self.db, self.backups)
            self.assertIn("restored exactly as it was", str(cm.exception))
        finally:
            db.MIGRATIONS[:] = orig_migrations
            upgrade.LATEST = orig_latest
        c = connect(self.db)
        self.assertEqual(current_version(c), 3)  # v4 had been applied, then everything was put back
        self.assertEqual(c.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        c.close()
        self.assertEqual(self.counts(), before)

    def test_file_from_a_newer_app_is_refused_unchanged(self):
        upgrade.open_company_file(self.db, self.backups)
        c = connect(self.db)
        c.execute("INSERT INTO schema_migrations VALUES (?, 'future', 'x')", (LATEST + 5,))
        c.close()
        data = self.db.read_bytes()
        with self.assertRaises(upgrade.UpgradeError):
            upgrade.open_company_file(self.db, self.backups)
        self.assertEqual(self.db.read_bytes(), data)


class DesktopEntryPoints(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ta-entry-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_create_agency_then_refuse_a_second_one(self):
        data = self.tmp / "agency-a"
        req = json.dumps({"agency_name": "Synthetic Travels", "username": "owner", "password": "Synthetic-Pass-123"})
        r = run_core("--create-agency", stdin=req, data=data)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertTrue(json.loads(r.stdout.strip().splitlines()[-1])["ok"])
        c = connect(data / "agency.db")
        self.assertEqual(c.execute("SELECT username FROM owner").fetchone()[0], "owner")
        self.assertEqual(services.get_settings(c)["business_name"], "Synthetic Travels")
        self.assertEqual(services.get_settings(c)["pay_account_number"], "")
        c.close()
        again = run_core("--create-agency", stdin=req, data=data)
        self.assertEqual(again.returncode, 2)
        weak = run_core("--create-agency", stdin=json.dumps({"agency_name": "B", "username": "x", "password": "y"}), data=self.tmp / "b")
        self.assertEqual(weak.returncode, 2)

    def test_two_agencies_are_separate_files(self):
        for name in ("Agency One", "Agency Two"):
            run_core("--create-agency", stdin=json.dumps({"agency_name": name, "username": "owner", "password": "Synthetic-Pass-123"}),
                     data=self.tmp / name.replace(" ", "-"))
        a, b = connect(self.tmp / "Agency-One" / "agency.db"), connect(self.tmp / "Agency-Two" / "agency.db")
        services.create_customer(a, {"name": "Only In One"})
        self.assertEqual(b.execute("SELECT COUNT(*) FROM customers WHERE name = 'Only In One'").fetchone()[0], 0)
        a.close()
        b.close()

    def test_free_port_start_and_ready_line(self):
        data = self.tmp / "agency"
        run_core("--create-agency", stdin=json.dumps({"agency_name": "Synthetic Travels", "username": "owner", "password": "Synthetic-Pass-123"}), data=data)
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUNBUFFERED="1", TACRM_NO_OPEN="1")
        p = subprocess.Popen([sys.executable, str(ROOT / "run.py"), "--data", str(data), "--port", "0", "--no-browser"],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
        try:
            port, deadline = None, time.time() + 30
            while time.time() < deadline and port is None:
                line = p.stdout.readline()
                if line.startswith("TACRM_READY port="):
                    port = int(line.strip().split("=")[1])
            self.assertIsNotNone(port)
            with urllib.request.urlopen("http://127.0.0.1:%d/" % port, timeout=10) as r:
                self.assertEqual(r.status, 200)
                self.assertIn(b"AirZelo", r.read())
        finally:
            p.terminate()
            p.wait(timeout=20)


class NoQuickTripData(unittest.TestCase):
    """The product must not contain Quick Trip's name, slogan, document prefixes or bank / UPI identifiers.
    Only SHA-256 hashes of the forbidden phrases are stored here, so this test does not itself carry them."""
    FORBIDDEN = {
        "09ff163a416367f767af6f5ba3f7245db72dd92ed73be547832149fce816efbc",
        "0e07d1fd0ca5e4cfe1b2008256a1db6b614886da9ca57fccacc7eb296e7778a8",
        "132b887597e3269ea98a7ae55bd9070ee59c242f88274b30dbad1f7672f52e25",
        "1c2d1c5b8aa5b2fef55573a3b9d938a94be0ae3292422ede5a6b85fbbac2d833",
        "40bb28f34340456d31b6f65aa5a6443897e0bf80e0995f5839aa4ce03442ba16",
        "7b02ae173aa989be9e032dd019629f3b71bbf3ef805d4ca5f5310d6d0587f36e",
        "858ab098d6cbb00487c8cc1e35c2585d27ef8bde9750547e94a64a6fce59e562",
        "99def35fe9573984ca1b128d30b41c49ca3b4639f788300387cd4047650b8a0e",
        "b038d4633fb62aae9388893cd23ed48d7388e64a7058e765cef625c3e80db629",
        "f5ea3a816f5a989a2c147dc5ad24da4f21ad206f840b4889ac770b9b79741637",
    }

    def test_product_contains_no_quick_trip_identifiers(self):
        import hashlib
        import re
        hits = []
        for p in list((ROOT / "server").rglob("*.py")) + list((ROOT / "static").rglob("*")) + [ROOT / "run.py"]:
            if not (p.is_file() and p.suffix in (".py", ".js", ".html", ".css", ".svg")):
                continue
            words = re.findall(r"[a-z0-9]+", p.read_text(encoding="utf-8").lower())
            for n in (1, 2, 3, 4):  # every word and 2-4 word phrase
                for i in range(len(words) - n + 1):
                    if hashlib.sha256(" ".join(words[i:i + n]).encode()).hexdigest() in self.FORBIDDEN:
                        hits.append("%s (phrase of %d words at word %d)" % (p.name, n, i))
        self.assertEqual(hits, [])

    def test_guard_detects_a_forbidden_phrase(self):
        import hashlib
        self.assertIn(hashlib.sha256(b"quick trip").hexdigest(), self.FORBIDDEN)
