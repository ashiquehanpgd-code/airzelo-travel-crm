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
# standard-library packages the app never uses (unittest and lib-dynload are kept: CI runs the tests on this runtime,
# and lib-dynload holds the compiled modules such as _sqlite3 and _ssl)
TRIM = ("test", "idlelib", "tkinter", "turtledemo", "ensurepip", "lib2to3", "pydoc_data", "__phello__")
# installer tooling the app never uses: pip (and its vendored packages) and the command-line launchers
TRIM_SITE = ("pip", "pip-*.dist-info", "setuptools", "setuptools-*.dist-info", "_distutils_hack", "distutils-precedence.pth")
TRIM_BIN = ("pip", "pip3", "pip3.*", "idle3", "idle3.*", "2to3", "2to3-*", "pydoc3", "pydoc3.*", "python3-config", "python3.*-config")


def stdlib_dir(py):
    """The standard-library folder: python/lib/python3.X on macOS, python/Lib on Windows. Checked by content, not by name,
    because macOS disks are case-insensitive ("Lib" also matches the "lib" parent folder)."""
    for cand in sorted((py / "lib").glob("python3.*")) + [py / "Lib"]:
        if (cand / "os.py").is_file():
            return cand
    raise SystemExit("Standard library not found under %s" % py)


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
    lib = stdlib_dir(py)
    for name in TRIM:
        shutil.rmtree(lib / name, ignore_errors=True)
    site = lib / "site-packages"
    for pattern in TRIM_SITE:
        for p in site.glob(pattern):
            p.unlink() if p.is_file() else shutil.rmtree(p, ignore_errors=True)
    bindir = py / ("Scripts" if (py / "Scripts").is_dir() else "bin")
    for pattern in TRIM_BIN:
        for p in bindir.glob(pattern):
            if p.is_file() or p.is_symlink():
                p.unlink()
    for p in list(py.glob("Scripts")):  # Windows pip launchers live here
        shutil.rmtree(p, ignore_errors=True)
    for p in [py / "include", py / "share", py / "tcl"]:
        shutil.rmtree(p, ignore_errors=True)
    for p in list(py.rglob("__pycache__")):
        shutil.rmtree(p, ignore_errors=True)
    for p in list(py.rglob("libtcl*")) + list(py.rglob("libtk*")) + list(py.rglob("_tkinter*")):
        p.unlink() if p.is_file() else shutil.rmtree(p, ignore_errors=True)
    print("Runtime ready:", py, "(sha256 verified)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "mac-arm64")
