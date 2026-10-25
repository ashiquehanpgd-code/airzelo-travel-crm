#!/usr/bin/env python3
"""Smoke test of a built APK on a real Android system (an emulator in CI): install, launch, open every tab, make a
backup (Android Keystore key + SQLite + backup encryption), open a report, then force-stop and relaunch so the
existing books are reopened. Fails on any crash of the app.

    python3 android/tools/device_smoke.py path/to/app.apk

Uses only adb and uiautomator (FLAG_SECURE blocks screenshots, not the accessibility tree). No data leaves the device.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.airzelo.app"


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=180)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {r.stdout}{r.stderr}")
    return r.stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/az-ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/az-ui.xml", check=False)
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except (ET.ParseError, ValueError):
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "x": (x1 + x2) // 2, "y": (y1 + y2) // 2})
    return out


def crashed():
    log = adb("logcat", "-d", "-b", "crash", check=False)
    return PKG in log and ("FATAL EXCEPTION" in log or "Process: " + PKG in log)


def wait_for(label, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        if crashed():
            raise SystemExit("the app crashed:\n" + adb("logcat", "-d", "-b", "crash", check=False)[-4000:])
        ns = nodes()
        hits = [n for n in ns if label in (n["text"], n["desc"])]
        if hits:
            return hits
        problem = [n["text"] for n in ns if "could not be opened" in n["text"] or n["text"].startswith("Something went wrong")]
        if problem:
            raise SystemExit("the app reported a startup problem: " + " | ".join(n["text"] for n in ns if n["text"]))
        time.sleep(1.5)
    raise SystemExit(f"'{label}' did not appear. On screen: " + " | ".join(n["text"] or n["desc"] for n in nodes() if n["text"] or n["desc"]))


def tap(label, bottom=False):
    hits = wait_for(label)
    n = max(hits, key=lambda h: h["y"]) if bottom else hits[0]
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
    time.sleep(1.5)
    print("tapped", label)


def launch():
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")


def main(apk):
    adb("install", "-r", apk)
    adb("logcat", "-c", "-b", "all", check=False)
    launch()
    wait_for("Net sales (ex tax)")
    print("dashboard shown")
    for tab in ["Bookings", "Customers", "Money", "More"]:
        tap(tab, bottom=True)
    tap("Backup & restore")
    tap("Back up now")
    hits = wait_for_prefix("Backup saved and verified")
    print("backup:", hits)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    tap("Reports")
    tap("Profit")
    wait_for("Profit report")
    print("profit report shown")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    launch()
    wait_for("Net sales (ex tax)")
    print("relaunched: existing books reopened")
    if crashed():
        raise SystemExit("the app crashed")
    print("device smoke test passed")


def wait_for_prefix(prefix, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        hits = [n["text"] for n in nodes() if n["text"].startswith(prefix)]
        if hits:
            return hits
        time.sleep(1.5)
    raise SystemExit(f"no text starting with '{prefix}'. On screen: " + " | ".join(n["text"] for n in nodes() if n["text"]))


if __name__ == "__main__":
    main(sys.argv[1])
