# Installing, updating and recovering (customer guide, draft)

## Install
- **Mac:** open the `.dmg` and drag *Travel Agency CRM* to Applications. Use the Apple Silicon build on M-series Macs and the Intel build on Intel Macs. macOS 12 or later.
- **Windows 10/11 (64-bit):** run the setup `.exe` and choose where to install. No administrator rights are needed (it installs for the current user).
- No Terminal, Python or other software is needed. Everything is included.

## First start
1. Click **New agency** and enter your agency name and an owner username and password (at least 10 characters).
2. Click **Open** and sign in.
3. Under Settings, add your company details, logo, invoice details and the bank / UPI details shown to customers. A new agency starts with none.

## Where your data is
- **Mac:** `~/Library/Application Support/Travel Agency CRM/agencies/`
- **Windows:** `%APPDATA%\Travel Agency CRM\agencies\`
- File → **Show data folder** opens it.

Each agency has its own folder: database, attachments and backups. Uninstalling the app does **not** delete your data.

## Backups
- **Automatic** every 24 hours. Use Settings → Backup & restore → **Back up now** before big changes.
- Copy backup `.zip` files to an external drive or cloud folder regularly.
- **Restore:** Settings → Backup & restore. Each backup is checked before anything is replaced, and a safety backup of the current data is taken first.

## Updates
- Before a new version opens your company file, it makes a verified safety copy (`backups/pre-upgrade-…db`).
- If the update cannot convert your data, it puts the copy back automatically, so your data is exactly as before, and tells you.
- A company file saved by a newer version will not open in an older version. Install the latest version instead.

## If something goes wrong
- **"The CRM engine stopped":** your data is saved. Open the agency again. Help → **Open log folder** shows logs to send to support (logs contain no customer data).
- **Forgot the owner password:** contact support (an admin reset tool is planned for Stage 4).
