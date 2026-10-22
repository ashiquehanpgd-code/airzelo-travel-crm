"""Single-owner authentication: PBKDF2-SHA256 password hashing, hashed session
tokens, idle expiry and login throttling."""
import base64
import hashlib
import hmac
import secrets
import threading
import time
from datetime import datetime, timedelta

from .db import tx, now_iso, get_setting
from .errors import ValidationError, Unauthorized, Forbidden, ApiError
from . import services

ITERATIONS = 390000
MIN_PASSWORD = 10


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


def _check_password_strength(pw):
    if not isinstance(pw, str) or len(pw) < MIN_PASSWORD:
        raise ValidationError("Password must be at least %d characters" % MIN_PASSWORD, "password")
    if len(pw) > 200:
        raise ValidationError("Password is too long", "password")
    classes = sum([any(c.islower() for c in pw), any(c.isupper() for c in pw), any(c.isdigit() for c in pw),
                   any(not c.isalnum() for c in pw)])
    if classes < 2:
        raise ValidationError("Use a mix of letters, numbers or symbols", "password")


def _token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def owner_exists(conn):
    return conn.execute("SELECT 1 FROM owner WHERE id = 1").fetchone() is not None


def setup_owner(conn, data, setup_code):
    if owner_exists(conn):
        raise Forbidden("The owner account already exists")
    if not setup_code or not hmac.compare_digest(str(data.get("setup_code") or "").strip(), setup_code):
        raise ValidationError("The setup code is incorrect. It is shown in the window where AirZelo is running.", "setup_code")
    username = str(data.get("username") or "").strip()
    if not 3 <= len(username) <= 60:
        raise ValidationError("Username must be 3-60 characters", "username")
    pw = data.get("password") or ""
    _check_password_strength(pw)
    ts = now_iso()
    with tx(conn):
        if owner_exists(conn):
            raise Forbidden("The owner account already exists")
        conn.execute("INSERT INTO owner(id, username, pw_hash, created_at, updated_at) VALUES (1, ?, ?, ?, ?)",
                     (username, hash_password(pw), ts, ts))
        services.audit(conn, "owner", 1, "setup", None, {"username": username})


class Throttle:
    def __init__(self):
        self.lock = threading.Lock()
        self.failures = 0
        self.locked_until = 0.0

    def check(self):
        with self.lock:
            wait = self.locked_until - time.time()
            if wait > 0:
                raise ApiError("Too many failed attempts. Try again in %d seconds." % int(wait + 1), status=429, code="throttled")

    def fail(self):
        with self.lock:
            self.failures += 1
            if self.failures >= 5:
                self.locked_until = time.time() + min(30 * (2 ** (self.failures - 5)), 900)

    def ok(self):
        with self.lock:
            self.failures = 0
            self.locked_until = 0.0


throttle = Throttle()


def login(conn, data):
    throttle.check()
    row = conn.execute("SELECT * FROM owner WHERE id = 1").fetchone()
    if not row:
        raise Unauthorized("Set up the owner account first")
    username = str(data.get("username") or "").strip()
    pw = data.get("password") or ""
    good = hmac.compare_digest(username.lower(), row["username"].lower()) and verify_password(pw, row["pw_hash"])
    if not good:
        throttle.fail()
        with tx(conn):
            services.audit(conn, "owner", 1, "login_failed", None, {"username": username[:60]})
        raise Unauthorized("Incorrect username or password")
    throttle.ok()
    token = secrets.token_urlsafe(32)
    hours = int(get_setting(conn, "session_hours", "8") or 8)
    now = datetime.now().replace(microsecond=0)
    with tx(conn):
        conn.execute("DELETE FROM sessions WHERE expires_at < ?", (now.isoformat(),))
        conn.execute("INSERT INTO sessions(token_hash, created_at, last_seen, expires_at) VALUES (?,?,?,?)",
                     (_token_hash(token), now.isoformat(), now.isoformat(), (now + timedelta(hours=hours)).isoformat()))
        services.audit(conn, "owner", 1, "login", None, None)
    return token


def validate_session(conn, token):
    if not token:
        return False
    th = _token_hash(token)
    row = conn.execute("SELECT * FROM sessions WHERE token_hash = ?", (th,)).fetchone()
    now = datetime.now().replace(microsecond=0)
    if not row or row["expires_at"] < now.isoformat():
        return False
    # sliding idle expiry, refreshed at most once a minute
    if row["last_seen"] < (now - timedelta(seconds=60)).isoformat():
        hours = int(get_setting(conn, "session_hours", "8") or 8)
        try:
            conn.execute("UPDATE sessions SET last_seen = ?, expires_at = ? WHERE token_hash = ?",
                         (now.isoformat(), (now + timedelta(hours=hours)).isoformat(), th))
        except Exception:
            pass
    return True


def logout(conn, token):
    if token:
        with tx(conn):
            conn.execute("DELETE FROM sessions WHERE token_hash = ?", (_token_hash(token),))


def change_password(conn, data):
    row = conn.execute("SELECT * FROM owner WHERE id = 1").fetchone()
    if not verify_password(data.get("current_password") or "", row["pw_hash"]):
        raise ValidationError("Current password is incorrect", "current_password")
    pw = data.get("new_password") or ""
    _check_password_strength(pw)
    with tx(conn):
        conn.execute("UPDATE owner SET pw_hash = ?, updated_at = ? WHERE id = 1", (hash_password(pw), now_iso()))
        conn.execute("DELETE FROM sessions")
        services.audit(conn, "owner", 1, "password_changed", None, None)


def reset_password_offline(conn, username, password):
    """Used by the command-line reset tool (requires access to the Mac itself)."""
    _check_password_strength(password)
    with tx(conn):
        if owner_exists(conn):
            conn.execute("UPDATE owner SET username = ?, pw_hash = ?, updated_at = ? WHERE id = 1",
                         (username, hash_password(password), now_iso()))
        else:
            ts = now_iso()
            conn.execute("INSERT INTO owner(id, username, pw_hash, created_at, updated_at) VALUES (1,?,?,?,?)",
                         (username, hash_password(password), ts, ts))
        conn.execute("DELETE FROM sessions")
        services.audit(conn, "owner", 1, "password_reset_offline", None, {"username": username})
