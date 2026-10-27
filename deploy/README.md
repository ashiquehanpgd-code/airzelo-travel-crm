# Deploying an AirZelo cloud workspace (shared office)

A template for one agency on one small server. **Nothing here has been deployed or bought.** Follow it only after
the owner approves the provider and the cost.

Recommended: AWS Lightsail, Mumbai (`ap-south-1`), Ubuntu 24.04 LTS, 2 GB plan, with an S3 bucket in Mumbai for
off-site copies. Any Ubuntu server with Python 3.10+ works the same way.

```
staff computers (AirZelo desktop app → "Cloud workspace", or any browser)
        │ HTTPS (Let's Encrypt, HSTS)
        ▼
Caddy :443  ──►  AirZelo core 127.0.0.1:8765 (systemd, user airzelo, TACRM_MODE=cloud)
                     └─ /var/lib/airzelo/agency/{agency.db, uploads/, backups/}
                                   │ Litestream (≈1 s behind)      │ nightly verified .zip (built in)
                                   ▼                               ▼
                              S3 Mumbai (versioned, deletion lock)  + Lightsail daily snapshots
```

Why one server and SQLite: every computer talks to the one core process, which serialises writes
(`BEGIN IMMEDIATE`). The database file is never shared or synchronised between computers. Never put a live
`agency.db` in Google Drive, Dropbox, OneDrive or a network share.

## 1. Server

1. Create the Lightsail instance (Mumbai, Ubuntu 24.04, 2 GB). Attach a static IP. Turn on automatic snapshots.
2. Lightsail firewall: allow 80 and 443; allow 22 only from your office IP (or use the browser SSH).
3. On the server:
   ```bash
   sudo apt update && sudo apt -y upgrade && sudo apt -y install unattended-upgrades fail2ban python3 caddy
   sudo adduser --system --group --home /var/lib/airzelo airzelo
   sudo install -d -o airzelo -g airzelo -m 700 /var/lib/airzelo/agency
   sudo install -d -m 755 /opt/airzelo
   ```
4. Copy the release's `core/` folder (from a tagged release, not a working copy) to `/opt/airzelo/core`.
   `run.py`, `server/`, `static/`, `vendor/` and `tools/` are needed; `bin/` (the macOS reader) is not.

## 2. Settings and service

```bash
sudo install -d -m 700 /etc/airzelo
sudo cp deploy/airzelo.env.example /etc/airzelo/airzelo.env && sudo chmod 600 /etc/airzelo/airzelo.env
sudo nano /etc/airzelo/airzelo.env          # set TACRM_PUBLIC_ORIGIN=https://<your subdomain>
sudo cp deploy/airzelo.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now airzelo
sudo journalctl -u airzelo -n 30           # shows the FIRST-TIME SETUP CODE
```

| Variable | Meaning |
|---|---|
| `TACRM_MODE=cloud` | Secure cookies, public host accepted, files never opened on the server |
| `TACRM_PUBLIC_ORIGIN` | `https://crm.<your domain>`; the core refuses to start in cloud mode without https |
| `TACRM_TRUST_PROXY=1` | Read the visitor address from Caddy (only from 127.0.0.1) for the audit trail and sign-in limits |
| `TACRM_DATA_DIR` | The company file folder |
| `TACRM_NO_OPEN=1` | The server never tries to open PDFs |

## 3. Domain and HTTPS

1. At your DNS provider add `A  crm  <static IP>` (a subdomain of the agency's existing domain is enough).
2. Edit `deploy/Caddyfile` (replace `crm.example.in`), copy it to `/etc/caddy/Caddyfile`, then
   `sudo systemctl reload caddy`. Caddy obtains and renews the certificate automatically.
3. Open `https://crm.<domain>`: enter the setup code from step 2 and create the **owner** login.
4. Settings → Staff & roles: add each employee with their own username, role and a temporary password.
   They choose their own password at first sign-in.

## 4. Backups and restore

* **Built in:** the core makes a verified `.zip` every 24 hours in `/var/lib/airzelo/agency/backups`.
* **Continuous off-site (Litestream):**
  1. Create an S3 bucket in `ap-south-1`: block all public access, turn on versioning, add a 30-day Object Lock
     (or a lifecycle rule) so copies cannot be deleted early.
  2. Create an IAM user with access only to that bucket. Put its key in `/etc/litestream.env` (chmod 600).
  3. Install Litestream (pinned release from its GitHub page, checksum verified), copy `deploy/litestream.yml` to
     `/etc/litestream.yml`, set the bucket name, then `sudo systemctl enable --now litestream`.
* **Lightsail snapshots:** daily, 7 kept.
* **Restore test (do it before go-live, then monthly):** on a spare instance run
  `litestream restore -o /tmp/test.db s3://<bucket>/agency`, then `sqlite3 /tmp/test.db 'PRAGMA integrity_check'`,
  and compare booking and transaction counts with the live app.
* After restoring a backup **inside the app** (Settings → Backup & restore), restart Litestream:
  `sudo systemctl restart litestream`.

Disaster recovery: new instance (or snapshot) → install as above → `litestream restore` into
`/var/lib/airzelo/agency/agency.db` → start the service → point the DNS `A` record at the new IP. About 30–60 minutes.

## 5. Security checklist

- [ ] SSH keys only (`PasswordAuthentication no`), port 22 limited to the office IP
- [ ] `unattended-upgrades` on; `fail2ban` running
- [ ] core bound to 127.0.0.1 only (`ss -ltnp | grep 8765`)
- [ ] `/etc/airzelo/airzelo.env` and `/etc/litestream.env` are chmod 600; no keys in the repository
- [ ] AWS root account has two-factor login; a billing alarm is set
- [ ] each person has their own login; nobody shares the owner login
- [ ] restore test done and written down

## What changes for staff

* Open the AirZelo desktop app → **Connect to a cloud workspace** → `https://crm.<domain>`, or use any browser.
* Local company files stay as they are, offline, on that computer. They are **not** merged with the cloud workspace.
* Without internet the cloud workspace shows a red banner and nothing can be saved; forms stay open, and saving again
  after the connection returns cannot post twice.
* Ticket scanner in the cloud: PDF e-tickets are read on the server. Scanned images need the local reader, so for
  photos of tickets use a local company file or type the details (see the remaining risks in the report).
