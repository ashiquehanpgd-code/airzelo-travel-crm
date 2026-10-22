"""Decimal-safe money helpers.

All amounts are stored as INTEGER minor units (paise for INR, halalas for SAR).
Conversion and percentage maths use Decimal with ROUND_HALF_UP to 2 decimals,
which is the single rounding policy for the whole application.
"""
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation

from .errors import ValidationError

MAX_MINOR = 10 ** 13  # 100 billion in major units - generous sanity limit
HUNDRED = Decimal(100)


def _to_decimal(value, field):
    if isinstance(value, bool):
        raise ValidationError("%s must be a number" % field, field)
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, float):
        d = Decimal(repr(value))
        q = d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        # tolerate binary float noise (0.1 + 0.2) but not genuine extra precision
        return q if abs(d - q) < Decimal("0.0000001") else d
    text = str(value).replace(",", "").replace("₹", "").strip()
    if text == "":
        return None
    try:
        d = Decimal(text)
    except InvalidOperation:
        raise ValidationError("%s must be a valid amount" % field, field)
    if not d.is_finite():
        raise ValidationError("%s must be a valid amount" % field, field)
    return d


def parse_amount(value, field="amount", required=False, allow_zero=True, allow_negative=False):
    """Parse a major-unit amount ("12000.50") into integer minor units."""
    if value is None or (isinstance(value, str) and value.strip() == ""):
        if required:
            raise ValidationError("%s is required" % field, field)
        return 0
    d = _to_decimal(value, field)
    if d is None:
        if required:
            raise ValidationError("%s is required" % field, field)
        return 0
    if d.as_tuple().exponent < -2 and d != d.quantize(Decimal("0.01")):
        raise ValidationError("%s can have at most 2 decimal places" % field, field)
    minor = int((d * HUNDRED).to_integral_value(rounding=ROUND_HALF_UP))
    if minor < 0 and not allow_negative:
        raise ValidationError("%s cannot be negative" % field, field)
    if minor == 0 and not allow_zero:
        raise ValidationError("%s must be greater than zero" % field, field)
    if abs(minor) > MAX_MINOR:
        raise ValidationError("%s is too large" % field, field)
    return minor


def parse_rate(value, field="exchange_rate"):
    """Exchange rate: positive decimal, up to 8 decimal places. Returned as canonical string."""
    if value is None or str(value).strip() == "":
        raise ValidationError("%s is required" % field, field)
    d = _to_decimal(value, field)
    if d is None or d <= 0:
        raise ValidationError("%s must be greater than zero" % field, field)
    if d.as_tuple().exponent < -8:
        raise ValidationError("%s can have at most 8 decimal places" % field, field)
    if d > Decimal(1000000):
        raise ValidationError("%s is unrealistically large" % field, field)
    return format(d.normalize(), "f")


def parse_percent(value, field="rate"):
    if value is None or str(value).strip() == "":
        return "0"
    d = _to_decimal(value, field)
    if d is None:
        return "0"
    if d < 0 or d > 100:
        raise ValidationError("%s must be between 0 and 100" % field, field)
    if d.as_tuple().exponent < -4:
        raise ValidationError("%s can have at most 4 decimal places" % field, field)
    return format(d.normalize(), "f")


def convert(minor, rate):
    """Convert minor units at the given rate into reporting-currency minor units."""
    if not minor:
        return 0
    r = Decimal(str(rate))
    return int((Decimal(int(minor)) * r).to_integral_value(rounding=ROUND_HALF_UP))


def percent_of(minor, pct):
    """pct% of minor units, rounded half-up to the nearest minor unit."""
    if not minor:
        return 0
    p = Decimal(str(pct))
    return int((Decimal(int(minor)) * p / HUNDRED).to_integral_value(rounding=ROUND_HALF_UP))


def split_half(minor):
    """Split a tax amount into two halves (CGST/SGST) that always add back exactly."""
    first = int((Decimal(int(minor)) / 2).to_integral_value(rounding=ROUND_HALF_UP))
    return first, int(minor) - first


def fmt(minor):
    """Minor units -> '12345.67' string with exactly two decimals."""
    minor = int(minor or 0)
    sign = "-" if minor < 0 else ""
    m = abs(minor)
    return "%s%d.%02d" % (sign, m // 100, m % 100)
