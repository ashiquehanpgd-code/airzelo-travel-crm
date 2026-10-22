"""Agency branding: the company logo and image helpers.

Logos live in the agency's own folder (<company file>/branding/), named by content hash. A new upload never
overwrites an old file, so an issued invoice that recorded `logo-<hash>.png` keeps showing the logo it was issued with.
"""
import base64
import hashlib
import struct

from .errors import ValidationError

MAX_LOGO_BYTES = 1024 * 1024
MAX_LOGO_PX = 4000


def image_info(data):
    """(kind, width, height) for PNG or JPEG bytes, else ValidationError. Only the header is parsed."""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR":
        w, h = struct.unpack(">II", data[16:24])
        return "png", w, h
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            seg = struct.unpack(">H", data[i + 2:i + 4])[0]
            if marker in (0xC0, 0xC1, 0xC2):  # baseline / extended / progressive frame header
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return "jpeg", w, h
            i += 2 + seg
    raise ValidationError("The logo must be a PNG or JPEG image", "logo")


def save_logo(ctx, payload):
    """Validate an uploaded logo ({"data": base64}) and store it. Returns the stored file name."""
    try:
        data = base64.b64decode(str(payload.get("data") or "").split(",")[-1], validate=True)
    except (ValueError, TypeError):
        raise ValidationError("The logo upload is not valid", "logo")
    if not data:
        raise ValidationError("Choose a logo image", "logo")
    if len(data) > MAX_LOGO_BYTES:
        raise ValidationError("The logo must be 1 MB or smaller", "logo")
    kind, w, h = image_info(data)
    if not (16 <= w <= MAX_LOGO_PX and 16 <= h <= MAX_LOGO_PX):
        raise ValidationError("The logo must be between 16 and %d pixels wide and high" % MAX_LOGO_PX, "logo")
    name = "logo-%s.%s" % (hashlib.sha256(data).hexdigest()[:16], "png" if kind == "png" else "jpg")
    folder = ctx.data_dir / "branding"
    folder.mkdir(exist_ok=True)
    path = folder / name
    if not path.exists():
        tmp = path.with_suffix(".part")
        tmp.write_bytes(data)
        tmp.replace(path)
    return name, kind, w, h


def logo_path(ctx, name):
    """Path of a stored logo, or None. Only names this module creates are accepted."""
    if not name or "/" in name or "\\" in name or not name.startswith("logo-") or not name.endswith((".png", ".jpg")):
        return None
    p = ctx.data_dir / "branding" / name
    return p if p.is_file() else None
