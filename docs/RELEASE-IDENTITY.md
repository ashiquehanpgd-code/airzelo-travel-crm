# Release identity (permanent after the first public release)

| Setting | Value | Rule |
|---|---|---|
| App ID (`desktop/package.json` build.appId) | `com.airzelo.crm` | never change after release (macOS signing/permissions/keychain, Windows app ID) |
| Windows NSIS GUID (build.nsis.guid) | `edbf4f81-513b-471e-8899-465f7e581c3f` | never change: it is the Windows uninstall/upgrade key |
| Product name | `AirZelo` | keep stable: it is also the data-folder name |
| Publisher (author, copyright) | `[LEGAL NAME PLACEHOLDER]` | replace with the owner's exact legal name before release |

`python3 build/check_release_config.py` enforces these (CI runs it); `--release` also fails while the placeholder remains.
