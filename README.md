# Travel Agency CRM (working title)

A desktop CRM and accounting app for travel agencies, for **Windows 10/11** and **macOS** (Apple Silicon and Intel).
Each agency is a separate **company file**, so agencies never share data.
It is built from the business workflows of the Quick Trip CRM, with all Quick Trip identity and data removed.

> **Status: Stage 2 (foundation).** The product name, publisher and app ID are placeholders.
> macOS packages are **unsigned** test builds. Windows is configured in CI but **not yet built or tested**: no Windows environment has run it yet.

## Layout

| Path | What |
|---|---|
| `core/` | Python core (standard library only): local HTTP API, business logic, SQLite company file, migrations, backups, PDFs, ticket parser. `core/static/` is the web UI. |
| `core/tests/` | 143 Python tests + 17 WhatsApp template tests (synthetic data only) |
| `desktop/` | Electron shell: agency chooser, starts the bundled core per agency, window, menus, installer config |
| `build/` | Pinned Python runtime (`python-runtime.json`), `fetch_python.py` (sha256-verified download), `smoke_test.py` (tests a packaged app with its own runtime) |
| `.github/workflows/build.yml` | CI: tests + packaging + smoke tests on macOS arm64, macOS x64 and Windows x64 |
| `docs/` | Architecture assessment, build/release guide, customer install & recovery guide |

## How it runs

1. The desktop app shows the agency chooser (`desktop/launcher`).
2. **New agency** runs `core/run.py --create-agency`. This creates `<data>/agencies/<uuid>/agency.db` and the owner login (no Terminal, no setup code).
3. **Open** starts `core/run.py --data <agency folder> --port 0` with the **bundled** Python. The core:
   - opens the company file safely (`server/upgrade.py`): it refuses files from a newer version, takes a verified safety copy before any schema upgrade, and restores it automatically if the upgrade fails;
   - prints `TACRM_READY port=N`;
   - the window then loads `http://127.0.0.1:N/`.
4. **Quitting or switching agency** closes the core's stdin, so the core shuts down cleanly (WAL checkpoint) on every OS.

**Data folders** (never inside the app, so upgrades and uninstall don't touch them):
- macOS: `~/Library/Application Support/Travel Agency CRM/agencies/`
- Windows: `%APPDATA%\Travel Agency CRM\agencies\`
- Ticket PDFs: `Documents/Travel Agency CRM/<Agency>/Tickets`

## Develop

```bash
python3 build/fetch_python.py mac-arm64
```

```bash
cd core/tests && ../../build/runtime/mac-arm64/python/bin/python3 -B -m unittest discover
```

```bash
cd desktop && npm install && npm start
```

See `docs/BUILD-AND-RELEASE.md` for packaging, CI and signing.
