#!/usr/bin/env python3
"""Download the pinned standalone Python runtime for one target, verify its SHA-256 and unpack it to
build/runtime/<target>/python. Trims parts the app never uses (tests, IDLE, Tk, headers) to keep installers small.

  python3 build/fetch_python.py mac-arm64|mac-x64|win-x64
"""
import hashlib
import json
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
TRIM = ("test", "idlelib", "tkinter", "turtledemo", "ensurepip", "lib2to3", "pydoc_data", "__phello__")


def main(target):
    pins = json.loads((HERE / "python-runtime.json").read_text())["targets"]
    if target not in pins:
        sys.exit("Unknown target %s (choose: %s)" % (target, ", ".join(pins)))
    pin = pins[target]
    cache = HERE / "cache"
    cache.mkdir(exist_ok=True)
    archive = cache / pin["url"].rsplit("/", 1)[1].replace("%2B", "+")
    if not archive.exists():
        print("Downloading", pin["url"])
        tmp = archive.with_suffix(".part")
        with urllib.request.urlopen(pin["url"], timeout=300) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        tmp.rename(archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != pin["sha256"]:
        archive.unlink()
        sys.exit("Checksum mismatch for %s: got %s, expected %s. The download was deleted." % (archive.name, digest, pin["sha256"]))
    out = HERE / "runtime" / target
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    with tarfile.open(archive) as t:
        t.extractall(out, filter="data") if sys.version_info >= (3, 12) else t.extractall(out)  # noqa: S202 - verified archive
    py = out / "python"
    lib = next((p for p in [py / "Lib", *sorted((py / "lib").glob("python3.*"))] if p.is_dir()), None)
    for name in TRIM:
        for d in [lib / name] if lib else []:
            shutil.rmtree(d, ignore_errors=True)
    for p in [py / "include", py / "share", py / "tcl"]:
        shutil.rmtree(p, ignore_errors=True)
    for p in list(py.rglob("__pycache__")):
        shutil.rmtree(p, ignore_errors=True)
    for p in list(py.rglob("libtcl*")) + list(py.rglob("libtk*")) + list(py.rglob("_tkinter*")):
        p.unlink() if p.is_file() else shutil.rmtree(p, ignore_errors=True)
    print("Runtime ready:", py, "(sha256 verified)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "mac-arm64")
