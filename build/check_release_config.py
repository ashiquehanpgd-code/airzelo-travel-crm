#!/usr/bin/env python3
"""Check the permanent release identity in desktop/package.json.

  python3 build/check_release_config.py            development / CI build: identity must be intact; placeholders only warn
  python3 build/check_release_config.py --release  release build: also fails while any placeholder remains

The app ID and the Windows NSIS GUID must NEVER change after the first public release:
- the app ID identifies the app to macOS (signing, Gatekeeper, privacy permissions, keychain) and Windows (taskbar, notifications);
- the NSIS GUID is the Windows uninstall/upgrade key; a new GUID would install a second copy instead of upgrading.
The product name is also the data-folder name (~/Library/Application Support/AirZelo, %APPDATA%\\AirZelo): keep it stable.
"""
import json
import re
import sys
from pathlib import Path

APP_ID = "com.airzelo.crm"
NSIS_GUID = "edbf4f81-513b-471e-8899-465f7e581c3f"  # generated once on 22 Oct 2026; never change after release
PRODUCT = "AirZelo"
PLACEHOLDER = re.compile(r"placeholder", re.I)


def main(release):
    pkg = json.loads((Path(__file__).resolve().parent.parent / "desktop" / "package.json").read_text(encoding="utf-8"))
    b = pkg["build"]
    errors, warnings = [], []
    if b.get("appId") != APP_ID:
        errors.append("build.appId is %r; it must stay %r" % (b.get("appId"), APP_ID))
    if b.get("nsis", {}).get("guid") != NSIS_GUID:
        errors.append("build.nsis.guid changed; it must stay %s" % NSIS_GUID)
    if pkg.get("productName") != PRODUCT or b.get("productName") != PRODUCT:
        errors.append("productName must stay %r (it is also the data-folder name)" % PRODUCT)
    for field, value in (("author", pkg.get("author", "")), ("build.copyright", b.get("copyright", ""))):
        if not value or PLACEHOLDER.search(value):
            (errors if release else warnings).append("%s still holds a placeholder (%r): set the publisher's exact legal name" % (field, value))
    for w in warnings:
        print("WARNING:", w)
    for e in errors:
        print("ERROR:", e)
    print("release identity: appId=%s nsis.guid=%s productName=%s -> %s" % (b.get("appId"), b.get("nsis", {}).get("guid"), b.get("productName"),
                                                                           "FAILED" if errors else "ok" + (" (with warnings)" if warnings else "")))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main("--release" in sys.argv[1:]))
