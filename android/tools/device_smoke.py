#!/usr/bin/env python3
"""Smoke test of a built APK on a real Android system (an emulator in CI). The app lock is checked with Android's own
prompt and a real device PIN: without a screen lock the app only shows setup instructions; after a PIN is set, a wrong
PIN is refused and the right one opens the books; a short trip to the home screen keeps it open, a longer one (over
the 30 s privacy timeout) and a restart lock it again. Then every tab, a backup (Android Keystore key + SQLite +
backup encryption) and a report. Fails on any crash of the app.

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
            out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                        "cls": n.get("class", ""), "pkg": n.get("package", ""), "rid": n.get("resource-id", "")})
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
        hits = [n for n in ns if label in (n["text"], n["desc"], n["rid"])]
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


def screen_size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size", check=False))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def tap_tag(tag, swipes=5):
    """Taps the element with this test tag, scrolling down a little at a time until it is on screen."""
    for _ in range(swipes + 1):
        hits = [n for n in nodes() if n["rid"] == tag]
        if hits:
            adb("shell", "input", "tap", str(hits[0]["x"]), str(hits[0]["y"]))
            time.sleep(1.5)
            print("tapped", tag)
            return
        w, h = screen_size()
        adb("shell", "input", "swipe", str(w // 2), str(h * 7 // 10), str(w // 2), str(h * 3 // 10), "300")
        time.sleep(1)
    raise SystemExit(f"'{tag}' not found. On screen: " + " | ".join(n["text"] or n["rid"] for n in nodes() if n["text"] or n["rid"]))


def launch():
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")


PIN = "1234"           # a test PIN on the throwaway emulator only
LOCK_TIMEOUT_S = 30    # AppLock.DEFAULT_TIMEOUT_MS


def credential_field(timeout=45):
    """Waits for Android's own PIN entry (BiometricPrompt's device-credential screen, outside the app)."""
    end = time.time() + timeout
    while time.time() < end:
        if crashed():
            raise SystemExit("the app crashed:\n" + adb("logcat", "-d", "-b", "crash", check=False)[-4000:])
        fields = [n for n in nodes() if n["cls"].endswith("EditText") and n["pkg"] != PKG]
        if fields:
            return fields[0]
        time.sleep(1.5)
    raise SystemExit("Android's PIN screen did not appear. On screen: " + " | ".join(n["text"] or n["desc"] for n in nodes() if n["text"] or n["desc"]))


def enter_pin(pin):
    f = credential_field()
    adb("shell", "input", "tap", str(f["x"]), str(f["y"]))
    adb("shell", "input", "text", pin)
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(3)


def books_visible():
    return any(n["rid"] in ("dashboard-hero", "dashboard") for n in nodes())


def main(apk):
    adb("install", "-r", apk)
    adb("shell", "locksettings", "clear", "--old", PIN, check=False)
    adb("shell", "svc", "power", "stayon", "true", check=False)
    adb("logcat", "-c", "-b", "all", check=False)

    # 1. no screen lock: setup instructions only, the books stay closed
    launch()
    wait_for("Set up a screen lock")
    assert not books_visible(), "books shown without a screen lock"
    print("no screen lock: setup instructions shown, books closed")

    # 2. the owner sets a PIN; Android's prompt refuses a wrong PIN and accepts the right one
    adb("shell", "locksettings", "set-pin", PIN)
    tap("Check again")
    enter_pin("0000")
    if books_visible():
        raise SystemExit("a wrong PIN opened the books")
    credential_field()  # still asking
    print("wrong PIN refused")
    enter_pin(PIN)
    wait_for("dashboard-hero")
    print("device PIN accepted: dashboard shown")

    # 3. every tab, a backup and a report
    for tab in ["tab-bookings", "tab-customers", "tab-money", "tab-more"]:
        tap_tag(tab)
    tap_tag("more-backup")
    tap_tag("backup-now")
    print("backup:", wait_for_prefix("Backup saved and verified"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    tap_tag("more-reports")
    tap_tag("report-profit")
    wait_for("Profit report")
    print("profit report shown")

    # 4. a short trip to the home screen keeps the app open
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(5)
    launch()
    wait_for("Profit report")
    if [n for n in nodes() if n["cls"].endswith("EditText") and n["pkg"] != PKG]:
        raise SystemExit("asked for the PIN again after 5 s away")
    print("back within the timeout: still unlocked")

    # 5. longer than the privacy timeout: locked again, the PIN is asked for
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(LOCK_TIMEOUT_S + 8)
    launch()
    enter_pin(PIN)
    wait_for("Profit report")
    print("away longer than the timeout: locked, PIN asked, unlocked")

    # 6. restart: the books are reopened, after the PIN again
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    launch()
    enter_pin(PIN)
    wait_for("dashboard-hero")
    print("restarted: PIN asked, existing books reopened")
    if crashed():
        raise SystemExit("the app crashed")
    adb("shell", "locksettings", "clear", "--old", PIN, check=False)
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
