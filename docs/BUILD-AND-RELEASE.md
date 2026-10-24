# Build, test and release

## Pinned toolchain

Every version below has been out at least two weeks. Re-check licences before each commercial release.

| Component | Version | Licence |
|---|---|---|
| Python runtime (python-build-standalone) | release 20261003, CPython 3.12.15 | CPython: PSF; build scripts: MPL-2.0 |
| Electron | 44.7.0 | MIT (bundles Chromium, BSD-style + others: see `LICENSES.chromium.html` inside the app) |
| electron-builder | 26.17.0 | MIT (build tool only, not shipped) |
| Core | own code + Python standard library | — |

## macOS (this Mac or CI)

Fetch each runtime, then package:

```bash
python3 build/fetch_python.py mac-arm64
```

```bash
python3 build/fetch_python.py mac-x64
```

```bash
cd desktop && CSC_IDENTITY_AUTO_DISCOVERY=false npm run dist:mac
```

Then smoke-test the built app:

```bash
python3 build/smoke_test.py --app "dist/mac-arm64/AirZelo.app"
```

The ticket reader (`core/bin/ta-ticket-reader`) is a universal binary. Build it as the CI workflow does: `arm64-apple-macos12` + `x86_64-apple-macos13`, joined with `lipo`.

## Windows (CI only for now)

This Mac cannot build or test Windows. `.github/workflows/build.yml` runs on `windows-latest`. It:
1. fetches `win-x64`;
2. runs all core tests on the bundled Windows Python;
3. builds the NSIS installer;
4. smoke-tests `dist/win-unpacked` with its own runtime;
5. installs the setup `.exe` silently for the current user, checks the version, Add/Remove Programs entry and desktop shortcut, runs the smoke and GUI tests on the installed app, then uninstalls it.

On macOS, CI likewise copies `AirZelo.app` out of the DMG and the ZIP, checks the version and architecture, and runs the smoke and GUI tests on each copy.

On Windows the ticket **reader** is not available yet (Stage 3: `Windows.Data.Pdf` + `Windows.Media.Ocr`). The scanner reports "not ready", and everything else works.

**Before claiming Windows support**, the CI job must pass and a person must install the `.exe` on a real Windows 10/11 PC and check:
- install;
- create an agency;
- booking, payment and refund;
- ticket PDF;
- backup and restore;
- upgrade over the previous version;
- uninstall (the data must remain).

## Setting up CI

1. Create a **private** GitHub repository and push this folder (the `.gitignore` excludes runtimes, builds and any `.db` files).
2. Actions → *build* → run. Artifacts: the DMGs and ZIPs for each macOS architecture, and the Windows setup `.exe`.

## Signing (required before selling)

| Platform | Needs | Then |
|---|---|---|
| macOS | Apple Developer Program; a "Developer ID Application" certificate; an app-specific password or API key for notarisation | Set `mac.identity`, `hardenedRuntime: true`, entitlements (allow-jit is not needed; disable-library-validation may be needed for the bundled Python), and `notarize`. **Every** Mach-O file in `Resources/python` and `core/bin` must be signed. |
| Windows | Azure Trusted Signing, or an OV/EV code-signing certificate | Configure `win.azureSignOptions` or `win.signtoolOptions`. Sign the installer and `python.exe`/`.dll`s. |

Store all signing credentials as CI **secrets**, never in the repository.

## Release checklist (Stage 5)

- [ ] All CI jobs green; real-PC Windows check done; real Intel Mac or `macos-15-intel` CI smoke test green
- [ ] Upgrade test: install the previous release, create data, install the new release, and check that the data opens and the figures are identical
- [ ] Signed and notarised; Gatekeeper and SmartScreen checked on a clean machine
- [ ] `THIRD-PARTY-NOTICES` updated; EULA and privacy policy reviewed
- [ ] No Quick Trip identifiers (`tests/test_platform.py::NoQuickTripData`)
