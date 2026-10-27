#!/usr/bin/env python3
"""Verify a bundled Python runtime (a build/runtime/<target>/python folder, or the one inside a packaged app):
- every standard-library module the AirZelo core imports is present and importable,
- the compiled modules the app relies on (_sqlite3, _ssl, zlib, ...) load,
- pip, ensurepip, setuptools, IDLE, tkinter and the other trimmed parts are gone,
- no installer / launcher scripts remain.

  python3 build/check_runtime.py build/runtime/mac-arm64/python
  python3 build/check_runtime.py --app dist/mac-arm64/AirZelo.app
  python3 build/check_runtime.py --app dist/win-unpacked

Import checks run the runtime itself; when it cannot run on this machine (e.g. an Intel runtime on Apple Silicon without
Rosetta) they are reported as SKIPPED, never as passed.
"""
import ast
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRIMMED_STDLIB = ("test", "idlelib", "tkinter", "turtledemo", "ensurepip", "lib2to3", "pydoc_data")
TRIMMED_SITE = ("pip", "setuptools", "_distutils_hack")
LAUNCHERS = ("pip", "pip3", "idle3", "2to3", "pydoc3", "python3-config")
NATIVE = ("_sqlite3", "_ssl", "zlib", "_hashlib", "_json", "_socket", "select", "_decimal", "_struct", "binascii", "unicodedata")


def core_imports():
    """Top-level modules imported anywhere in the core (excluding the core's own package)."""
    names = set()
    for f in list((ROOT / "core" / "server").glob("*.py")) + [ROOT / "core" / "run.py"]:
        for node in ast.walk(ast.parse(f.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Import):
                names.update(a.name.split(".")[0] for a in node.names)
            elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
                names.add(node.module.split(".")[0])
    vendored = {d.name for d in (ROOT / "core" / "vendor").iterdir() if d.is_dir()} if (ROOT / "core" / "vendor").is_dir() else set()
    return sorted(names - {"server"} - vendored)


def vendor_dir(args):
    """The bundled third-party packages (core/vendor) that ship next to the core."""
    if args and args[0] == "--app":
        app = Path(args[1])
        res = app / "Contents" / "Resources" if app.suffix == ".app" else app / "resources"
        return res / "core" / "vendor"
    return ROOT / "core" / "vendor"


def locate(args):
    if args and args[0] == "--app":
        app = Path(args[1])
        res = app / "Contents" / "Resources" if app.suffix == ".app" else app / "resources"
        return res / "python"
    return Path(args[0])


def main(args):
    py = locate(args)
    win = (py / "python.exe").exists()
    exe = py / ("python.exe" if win else "bin/python3")
    lib = next((c for c in sorted((py / "lib").glob("python3.*")) + [py / "Lib"] if (c / "os.py").is_file()), None)
    fails, notes = [], []
    if not exe.exists() or lib is None:
        print("FAIL: no runtime at", py)
        return 1
    for name in TRIMMED_STDLIB:
        if (lib / name).exists():
            fails.append("trimmed stdlib package still present: " + name)
    for name in TRIMMED_SITE:
        if list((lib / "site-packages").glob(name)) or list((lib / "site-packages").glob(name + "-*.dist-info")):
            fails.append("site-packages still contains " + name)
    bindir = py / "bin"
    for name in LAUNCHERS:
        for d in (bindir, py / "Scripts"):
            if d.is_dir() and (list(d.glob(name)) + list(d.glob(name + ".*")) + list(d.glob(name + ".exe"))):
                fails.append("launcher still present: %s/%s" % (d.name, name))
    if (py / "Scripts").exists():
        fails.append("Scripts folder (pip launchers) still present")
    if not win and not (lib / "lib-dynload").is_dir():
        fails.append("lib-dynload (compiled modules) is missing")
    if not (lib / "unittest").is_dir():
        notes.append("unittest not present (CI runs the core tests on this runtime)")
    size = sum(f.lstat().st_size for f in py.rglob("*") if f.is_file() and not f.is_symlink())
    mods = core_imports()
    probe = ("import importlib, importlib.util, json, subprocess, sys\n"
             "mods = %r\nnative = %r\n"
             "missing = [m for m in mods + native if importlib.util.find_spec(m) is None]\n"
             "failed = []\n"
             "for m in mods + native:\n"
             "    try:\n        importlib.import_module(m)\n"
             "    except Exception as e:\n        failed.append('%%s: %%s' %% (m, e))\n"
             "sys.path.insert(0, %r)\n"
             "try:\n    import pypdf\n    vendored = 'pypdf ' + pypdf.__version__\n"
             "except Exception as e:\n    failed.append('pypdf (vendor): %%s' %% e)\n    vendored = ''\n"
             "import sqlite3, ssl\n"
             "pip = importlib.util.find_spec('pip') is not None\n"
             "ens = subprocess.run([sys.executable, '-I', '-m', 'ensurepip', '--version'], capture_output=True).returncode == 0\n"
             "print(json.dumps({'version': sys.version.split()[0], 'sqlite': sqlite3.sqlite_version, 'openssl': ssl.OPENSSL_VERSION,"
             " 'missing': missing, 'failed': failed, 'pip': pip, 'ensurepip': ens, 'vendored': vendored}))\n") % (mods, list(NATIVE), str(vendor_dir(args)))
    try:
        r = subprocess.run([str(exe), "-I", "-B", "-c", probe], capture_output=True, text=True, timeout=120)
        out = json.loads(r.stdout.strip().splitlines()[-1]) if r.returncode == 0 and r.stdout.strip() else None
        if out is None:
            fails.append("runtime probe failed: " + (r.stderr.strip()[-300:] or "no output"))
        else:
            if out["missing"]:
                fails.append("modules missing: " + ", ".join(out["missing"]))
            if out["failed"]:
                fails.append("modules failed to import: " + "; ".join(out["failed"]))
            if out["pip"]:
                fails.append("`import pip` still works")
            if out["ensurepip"]:
                fails.append("`python -m ensurepip` still works")
            notes.append("Python %s, SQLite %s, %s" % (out["version"], out["sqlite"], out["openssl"]))
            notes.append("%d core modules + %d native modules import; vendored: %s" % (len(mods), len(NATIVE), out.get("vendored") or "none"))
    except OSError as e:
        notes.append("import checks SKIPPED: this runtime cannot run on this machine (%s)" % e.strerror)
    print("runtime:", py)
    print("size: %.1f MB" % (size / 1048576))
    for n in notes:
        print("note:", n)
    for f in fails:
        print("FAIL:", f)
    print("RUNTIME CHECK", "FAILED" if fails else "PASSED" + (" (structure only; imports skipped)" if any("SKIPPED" in n for n in notes) else ""))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
