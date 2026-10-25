#!/usr/bin/env python3
"""Screenshots of the redesigned app on a real Android system (an emulator in CI), for design review.

Installs the DEBUG apk, starts it with synthetic demo books (MainActivity.EXTRA_DEMO; debug builds only, which also
allows screenshots - the release app always blocks them), unlocks it with a test PIN through Android's own prompt,
then walks the main screens and saves PNGs.

    python3 android/tools/device_screenshots.py path/to/app-debug.apk out-dir
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import device_smoke as d  # noqa: E402

DEMO = "com.airzelo.app.DEMO_SCREENSHOTS"


def shot(out, name):
    time.sleep(1.5)
    with open(os.path.join(out, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    print("screenshot", name)


def scroll():
    w, h = d.screen_size()
    d.adb("shell", "input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "400")


def back():
    d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.2)


def main(apk, out):
    os.makedirs(out, exist_ok=True)
    d.adb("install", "-r", apk)
    d.adb("shell", "locksettings", "set-pin", d.PIN, check=False)
    d.adb("shell", "svc", "power", "stayon", "true", check=False)
    d.adb("shell", "am", "start", "-W", "-n", f"{d.PKG}/.MainActivity", "--ez", DEMO, "true")
    time.sleep(2)
    shot(out, "00-lock")
    d.enter_pin(d.PIN)
    d.wait_for("dashboard-hero")
    shot(out, "01-dashboard")
    scroll(); shot(out, "02-dashboard-kpis")
    scroll(); shot(out, "03-dashboard-chart")
    scroll(); scroll(); shot(out, "04-dashboard-lists")
    d.tap_tag("tab-bookings"); shot(out, "05-bookings")
    hits = [n for n in d.nodes() if n["rid"].startswith("booking-BK")]
    if hits:
        d.adb("shell", "input", "tap", str(hits[0]["x"]), str(hits[0]["y"])); shot(out, "06-booking-detail")
        scroll(); shot(out, "07-booking-detail-lower")
        d.tap_tag("edit-booking"); shot(out, "08-booking-form")
        scroll(); scroll(); shot(out, "09-booking-form-passengers")
        back(); back()
    d.tap_tag("tab-customers"); shot(out, "10-customers")
    d.tap_tag("tab-money"); shot(out, "11-money")
    d.tap_tag("new-transaction"); shot(out, "12-new-entry")
    back()
    d.tap_tag("tab-more"); shot(out, "13-more")
    d.tap_tag("more-reports"); shot(out, "14-reports")
    d.tap_tag("report-profit"); shot(out, "15-report-profit")
    back(); back()
    d.tap_tag("more-backup"); shot(out, "16-backup")
    back()
    d.tap_tag("more-settings"); shot(out, "17-settings")
    if d.crashed():
        raise SystemExit("the app crashed")
    d.adb("shell", "locksettings", "clear", "--old", d.PIN, check=False)
    print("screenshots done")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
