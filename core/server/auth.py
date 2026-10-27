"""Staff sign-in: PBKDF2-SHA256 password hashing, hashed session tokens with idle expiry, login throttling per
user and per address, and the staff accounts themselves (owner, admin, booking staff, accountant - see access.py).

Rules kept here, on the server:
  * there is always exactly one active owner; only the owner can create, change or reset an admin
  * an admin manages booking staff and accountants, never the owner
  * deactivating a user, changing their role or resetting their password signs them out everywhere
"""
import base64
import hashlib
import hmac
import secrets
import threading
import time
from datetime import datetime, timedelta

from .db import tx, now_iso, get_setting
from .errors import ValidationError, Unauthorized, Forbidden, ApiError, NotFound, Conflict
from . import access, services

ITERATIONS = 390000
MIN_PASSWORD = 10
USER_FIELDS = "id, username, display_name, role, active, must_change_password, created_at, updated_at, created_by, last_login_at"


def hash_password(password, iterations=ITERATIONS):
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return "pbkdf2_sha256$%d$%s$%s" % (iterations, base64.b64encode(salt).decode(), base64.b64encode(dk).decode())


def verify_password(password, stored):
    try:
        algo, it, salt, dk = stored.split("$")
        if algo != "pbkdf2_sha256":
            return False
        calc = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), base64.b64decode(salt), int(it))
        return hmac.compare_digest(calc, base64.b64decode(dk))
    except Exception:
        return False


# checked against when the username does not exist, so a wrong username takes as long as a wrong password
_DUMMY_HASH = hash_password(secrets.token_urlsafe(16))


def _check_password_strength(pw, field="password"):
    if not isinstance(pw, str) or len(pw) < MIN_PASSWORD:
        raise ValidationError("Password must be at least %d characters" % MIN_PASSWORD, field)
    if len(pw) > 200:
        raise ValidationError("Password is too long", field)
    classes = sum([any(c.islower() for c in pw), any(c.isupper() for c in pw), any(c.isdigit() for c in pw),
                   any(not c.isalnum() for c in pw)])
    if classes < 2:
        raise ValidationError("Use a mix of letters, numbers or symbols", field)


def _check_username(username):
    if not 3 <= len(username) <= 60:
        raise ValidationError("Username must be 3-60 characters", "username")
    if any(c.isspace() for c in username):
        raise ValidationError("Usernames cannot contain spaces", "username")


def _token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def owner_exists(conn):
    return conn.execute("SELECT 1 FROM users WHERE role = 'owner'").fetchone() is not None


def setup_owner(conn, data, setup_code):
    if owner_exists(conn):
        raise Forbidden("The owner account already exists")
    if not setup_code or not hmac.compare_digest(str(data.get("setup_code") or "").strip(), setup_code):
        raise ValidationError("The setup code is incorrect. It is shown in the window where AirZelo is running.", "setup_code")
    username = str(data.get("username") or "").strip()
    _check_username(username)
    pw = data.get("password") or ""
    _check_password_strength(pw)
    ts = now_iso()
    with tx(conn):
        if owner_exists(conn):
            raise Forbidden("The owner account already exists")
        h = hash_password(pw)
        # the pre-v5 single-owner table is kept in step so an older app version (after a rollback) still signs in
        conn.execute("INSERT OR REPLACE INTO owner(id, username, pw_hash, created_at, updated_at) VALUES (1, ?, ?, ?, ?)", (username, h, ts, ts))
        conn.execute("INSERT INTO users(id, username, display_name, role, pw_hash, created_at, updated_at) VALUES (1, ?, ?, 'owner', ?, ?, ?)",
                     (username, str(data.get("display_name") or username).strip()[:80], h, ts, ts))
        services.audit(conn, "user", 1, "setup", None, {"username": username, "role": "owner"})


class Throttle:
    """Failed sign-ins are counted per username and per address, so one person typing a wrong password (or someone
    guessing) cannot lock every other member of staff out."""
    LIMITS = {"u": 5, "ip": 20}

    def __init__(self):
        self.lock = threading.Lock()
        self.state = {}       # key -> [failures, locked_until]

    def check(self, *keys):
        with self.lock:
            now = time.time()
            for k in keys:
                wait = self.state.get(k, [0, 0.0])[1] - now
                if wait > 0:
                    raise ApiError("Too many failed attempts. Try again in %d seconds." % int(wait + 1), status=429, code="throttled")

    def fail(self, *keys):
        with self.lock:
            for k in keys:
                s = self.state.setdefault(k, [0, 0.0])
                s[0] += 1
                limit = self.LIMITS[k.split(":", 1)[0]]
                if s[0] >= limit:
                    s[1] = time.time() + min(30 * (2 ** (s[0] - limit)), 900)

    def ok(self, *keys):
        """Clear the given keys (all keys when called without any)."""
        with self.lock:
            if not keys:
                self.state.clear()
            for k in keys:
                self.state.pop(k, None)


throttle = Throttle()


def login(conn, data, ip=None):
    username = str(data.get("username") or "").strip()
    pw = data.get("password") or ""
    keys = ["u:" + username.lower()[:60]] + (["ip:" + ip] if ip else [])
    throttle.check(*keys)
    if not owner_exists(conn):
        raise Unauthorized("Set up the owner account first")
    row = conn.execute("SELECT * FROM users WHERE username = ? COLLATE NOCASE", (username,)).fetchone()
    good = verify_password(pw, row["pw_hash"] if row else _DUMMY_HASH) and row is not None and row["active"] == 1
    if not good:
        throttle.fail(*keys)
        with tx(conn):
            services.audit(conn, "user", row["id"] if row else None, "login_failed", None, {"username": username[:60]})
        raise Unauthorized("Incorrect username or password")
    throttle.ok(keys[0])
    token = secrets.token_urlsafe(32)
    hours = int(get_setting(conn, "session_hours", "8") or 8)
    now = datetime.now().replace(microsecond=0)
    with tx(conn):
        conn.execute("DELETE FROM sessions WHERE expires_at < ?", (now.isoformat(),))
        conn.execute("INSERT INTO sessions(token_hash, created_at, last_seen, expires_at, user_id, ip) VALUES (?,?,?,?,?,?)",
                     (_token_hash(token), now.isoformat(), now.isoformat(), (now + timedelta(hours=hours)).isoformat(), row["id"], ip))
        conn.execute("UPDATE users SET last_login_at = ? WHERE id = ?", (now.isoformat(), row["id"]))
        with access.acting_as({"id": row["id"], "username": row["username"], "role": row["role"], "ip": ip}):
            services.audit(conn, "user", row["id"], "login", None, None)
    return token


def validate_session(conn, token):
    """The signed-in user for this session token (a dict without the password hash), or None."""
    if not token:
        return None
    th = _token_hash(token)
    row = conn.execute("SELECT s.expires_at, s.last_seen, u.* FROM sessions s JOIN users u ON u.id = s.user_id "
                       "WHERE s.token_hash = ? AND u.active = 1", (th,)).fetchone()
    now = datetime.now().replace(microsecond=0)
    if not row or row["expires_at"] < now.isoformat():
        return None
    # sliding idle expiry, refreshed at most once a minute
    if row["last_seen"] < (now - timedelta(seconds=60)).isoformat():
        hours = int(get_setting(conn, "session_hours", "8") or 8)
        try:
            conn.execute("UPDATE sessions SET last_seen = ?, expires_at = ? WHERE token_hash = ?",
                         (now.isoformat(), (now + timedelta(hours=hours)).isoformat(), th))
        except Exception:
            pass
    user = {k: row[k] for k in ("id", "username", "display_name", "role", "must_change_password")}
    user["permissions"] = sorted(access.PERMISSIONS.get(user["role"], ()))
    return user


def logout(conn, token):
    if token:
        with tx(conn):
            conn.execute("DELETE FROM sessions WHERE token_hash = ?", (_token_hash(token),))


def _sync_owner_table(conn, user_id):
    row = conn.execute("SELECT username, pw_hash, role FROM users WHERE id = ?", (user_id,)).fetchone()
    if row and row["role"] == "owner":
        conn.execute("UPDATE owner SET username = ?, pw_hash = ?, updated_at = ? WHERE id = 1", (row["username"], row["pw_hash"], now_iso()))


def change_password(conn, user, data):
    """A signed-in user changes their own password; all their sessions end (they sign in again)."""
    row = conn.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
    if not row or not verify_password(data.get("current_password") or "", row["pw_hash"]):
        raise ValidationError("Current password is incorrect", "current_password")
    pw = data.get("new_password") or ""
    _check_password_strength(pw, "new_password")
    if verify_password(pw, row["pw_hash"]):
        raise ValidationError("Choose a password different from the current one", "new_password")
    with tx(conn):
        conn.execute("UPDATE users SET pw_hash = ?, must_change_password = 0, updated_at = ? WHERE id = ?", (hash_password(pw), now_iso(), row["id"]))
        _sync_owner_table(conn, row["id"])
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (row["id"],))
        services.audit(conn, "user", row["id"], "password_changed", None, None)


def reset_password_offline(conn, username, password):
    """Used by the command-line reset tool (requires access to the computer itself): resets the owner login."""
    _check_password_strength(password)
    _check_username(username)
    with tx(conn):
        ts = now_iso()
        h = hash_password(password)
        if owner_exists(conn):
            conn.execute("UPDATE users SET username = ?, pw_hash = ?, active = 1, must_change_password = 0, updated_at = ? WHERE role = 'owner'",
                         (username, h, ts))
        else:
            conn.execute("INSERT INTO users(username, display_name, role, pw_hash, created_at, updated_at) VALUES (?, ?, 'owner', ?, ?, ?)",
                         (username, username, h, ts, ts))
        conn.execute("INSERT OR REPLACE INTO owner(id, username, pw_hash, created_at, updated_at) VALUES (1, ?, ?, ?, ?)", (username, h, ts, ts))
        owner_id = conn.execute("SELECT id FROM users WHERE role = 'owner'").fetchone()["id"]
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (owner_id,))
        services.audit(conn, "user", owner_id, "password_reset_offline", None, {"username": username})


# ------------------------------------------------------------------ staff accounts
def list_users(conn):
    return [dict(r) for r in conn.execute("SELECT %s FROM users ORDER BY role = 'owner' DESC, active DESC, username COLLATE NOCASE" % USER_FIELDS)]


def get_user(conn, uid):
    row = conn.execute("SELECT %s FROM users WHERE id = ?" % USER_FIELDS, (uid,)).fetchone()
    if not row:
        raise NotFound("User not found")
    return dict(row)


def _may_manage(actor, target_role):
    """The owner manages everyone else; an admin manages booking staff and accountants only."""
    if not access.can(actor, access.USERS):
        raise Forbidden("Only the owner or an admin can manage staff accounts")
    if target_role == "owner":
        raise Forbidden("The owner account can only be changed by the owner, from My account")
    if target_role == "admin" and actor["role"] != "owner":
        raise Forbidden("Only the owner can create or change an admin")


def _role(value):
    if value not in access.ROLES or value == "owner":
        raise ValidationError("Choose a role: admin, booking staff or accountant", "role")
    return value


def create_user(conn, actor, data):
    username = str(data.get("username") or "").strip()
    _check_username(username)
    role = _role(data.get("role"))
    _may_manage(actor, role)
    pw = data.get("password") or ""
    _check_password_strength(pw)
    display = str(data.get("display_name") or username).strip()[:80]
    ts = now_iso()
    with tx(conn):
        if conn.execute("SELECT 1 FROM users WHERE username = ? COLLATE NOCASE", (username,)).fetchone():
            raise Conflict("The username %s is already taken" % username, code="duplicate")
        cur = conn.execute("INSERT INTO users(username, display_name, role, pw_hash, must_change_password, created_at, updated_at, created_by) "
                           "VALUES (?, ?, ?, ?, 1, ?, ?, ?)", (username, display, role, hash_password(pw), ts, ts, actor["id"]))
        uid = cur.lastrowid
        services.audit(conn, "user", uid, "create", None, {"username": username, "display_name": display, "role": role})
    return get_user(conn, uid)


def update_user(conn, actor, uid, data):
    """Change display name, role or active state. A role change or deactivation signs the user out everywhere."""
    with tx(conn):
        before = conn.execute("SELECT %s FROM users WHERE id = ?" % USER_FIELDS, (uid,)).fetchone()
        if not before:
            raise NotFound("User not found")
        before = dict(before)
        if uid == actor["id"]:
            raise Forbidden("You cannot change your own role or switch off your own account")
        _may_manage(actor, before["role"])
        role = _role(data.get("role", before["role"]))
        _may_manage(actor, role)
        active = 1 if data.get("active", before["active"]) in (1, True, "1", "true") else 0
        display = str(data.get("display_name", before["display_name"]) or before["username"]).strip()[:80]
        conn.execute("UPDATE users SET display_name = ?, role = ?, active = ?, updated_at = ? WHERE id = ?", (display, role, active, now_iso(), uid))
        if role != before["role"] or not active:
            conn.execute("DELETE FROM sessions WHERE user_id = ?", (uid,))
        keys = ("display_name", "role", "active")
        services.audit(conn, "user", uid, "update", {k: before[k] for k in keys}, dict(zip(keys, (display, role, active))))
    return get_user(conn, uid)


def reset_user_password(conn, actor, uid, data):
    """Set a temporary password for another user; they must change it at their next sign-in."""
    row = conn.execute("SELECT * FROM users WHERE id = ?", (uid,)).fetchone()
    if not row:
        raise NotFound("User not found")
    if uid == actor["id"]:
        raise Forbidden("Use My account to change your own password")
    _may_manage(actor, row["role"])
    pw = data.get("password") or ""
    _check_password_strength(pw)
    with tx(conn):
        conn.execute("UPDATE users SET pw_hash = ?, must_change_password = 1, updated_at = ? WHERE id = ?", (hash_password(pw), now_iso(), uid))
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (uid,))
        services.audit(conn, "user", uid, "password_reset", None, None)
    return get_user(conn, uid)
