# Installing, updating and recovering (customer guide, draft)

## Install
- **Mac:** open the `.dmg` and drag *AirZelo* to Applications. Use the Apple Silicon build on M-series Macs and the Intel build on Intel Macs. macOS 12 or later.
- **Windows 10/11 (64-bit):** run the setup `.exe` and choose where to install. No administrator rights are needed (it installs for the current user).
- No Terminal, Python or other software is needed. Everything is included.

## First start
1. Click **New agency** and enter your agency name and an owner username and password (at least 10 characters).
2. Click **Open** and sign in. The **setup wizard** opens, with these steps:
   1. Company details
   2. Logo and brand colour
   3. Bank accounts and the payment details shown to customers
   4. Invoice prefixes, date format and currency display
   5. A sample invoice to check
   6. Finish
   Optional fields can be left empty. **Skip for now** takes you straight to the dashboard.
3. Change any of this later in **Settings → Company & branding** and **Settings → Documents & formats**. Changes apply to new documents. Invoices you have already issued keep the details they were issued with.

## Where your data is
- **Mac:** `~/Library/Application Support/AirZelo/agencies/`
- **Windows:** `%APPDATA%\AirZelo\agencies\`
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
