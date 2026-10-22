# Release identity (permanent after the first public release)

| Setting | Value | Rule |
|---|---|---|
| App ID (`desktop/package.json` build.appId) | `com.airzelo.crm` | never change after release (macOS signing/permissions/keychain, Windows app ID) |
| Windows NSIS GUID (build.nsis.guid) | `edbf4f81-513b-471e-8899-465f7e581c3f` | never change: it is the Windows uninstall/upgrade key |
| Product name | `AirZelo` | keep stable: it is also the data-folder name |
| Publisher (author, copyright) | `ASHIQUE KALIPPADATH` (`Copyright © 2026 ASHIQUE KALIPPADATH`) | the owner's exact legal name (individual developer); must match the Apple Developer account and code-signing certificates |

`python3 build/check_release_config.py` enforces these (CI runs it); `--release` also fails if a placeholder is ever reintroduced.
