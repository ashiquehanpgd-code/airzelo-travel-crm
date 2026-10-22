"""Runs the WhatsApp message tests (tests/whatsapp.test.js) when Node.js is installed."""
import shutil
import subprocess
import unittest
from pathlib import Path


class WhatsAppMessages(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "Node.js not installed - WhatsApp JS tests skipped")
    def test_whatsapp_js(self):
        r = subprocess.run(["node", str(Path(__file__).with_name("whatsapp.test.js"))], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
