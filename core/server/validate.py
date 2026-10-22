import re
from datetime import datetime

from .errors import ValidationError

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def s(data, key, max_len=500, required=False, label=None):
    v = data.get(key)
    label = label or key.replace("_", " ").capitalize()
    if v is None:
        v = ""
    if not isinstance(v, (str, int, float)) or isinstance(v, bool):
        raise ValidationError("%s is invalid" % label, key)
    v = str(v).strip()
    if required and not v:
        raise ValidationError("%s is required" % label, key)
    if len(v) > max_len:
        raise ValidationError("%s is too long (max %d characters)" % (label, max_len), key)
    return v or None


def d(data, key, required=False, label=None):
    v = s(data, key, 10, required, label)
    if v is None:
        return None
    label = label or key.replace("_", " ").capitalize()
    if not DATE_RE.match(v):
        raise ValidationError("%s must be a date (YYYY-MM-DD)" % label, key)
    try:
        datetime.strptime(v, "%Y-%m-%d")
    except ValueError:
        raise ValidationError("%s is not a valid date" % label, key)
    return v


def dt(data, key, label=None):
    v = s(data, key, 19, False, label)
    if v is None:
        return None
    label = label or key.replace("_", " ").capitalize()
    v = v.replace(" ", "T")
    for fmt in ("%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d"):
        try:
            datetime.strptime(v, fmt)
            return v[:16] if "T" in v else v
        except ValueError:
            continue
    raise ValidationError("%s must be a date/time (YYYY-MM-DDTHH:MM)" % label, key)


def choice(data, key, options, default=None, label=None):
    v = data.get(key)
    if v is None or v == "":
        if default is not None:
            return default
        raise ValidationError("%s is required" % (label or key), key)
    if v not in options:
        raise ValidationError("%s must be one of: %s" % (label or key, ", ".join(options)), key)
    return v


def i(data, key, required=False, label=None):
    v = data.get(key)
    label = label or key.replace("_", " ").capitalize()
    if v is None or v == "":
        if required:
            raise ValidationError("%s is required" % label, key)
        return None
    if isinstance(v, bool):
        raise ValidationError("%s is invalid" % label, key)
    try:
        return int(v)
    except (TypeError, ValueError):
        raise ValidationError("%s is invalid" % label, key)


def b(data, key):
    v = data.get(key)
    return v in (True, 1, "1", "true", "on", "yes")


def email(data, key):
    v = s(data, key, 200)
    if v and not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", v):
        raise ValidationError("Email address looks invalid", key)
    return v


def phone(data, key):
    v = s(data, key, 40)
    if v and not re.match(r"^[0-9+()\-\s.]{5,40}$", v):
        raise ValidationError("Phone number looks invalid", key)
    return v


def norm_code(v, max_len=12):
    if v is None:
        return None
    v = str(v).strip().upper()
    return v[:max_len] or None
