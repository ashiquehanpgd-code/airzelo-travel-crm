package com.airzelo.engine

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Port of core/server/money.py.
 *
 * All amounts are INTEGER minor units (paise for INR). Conversion and percentage maths round HALF_UP
 * (ties away from zero) to the nearest minor unit - the single rounding policy of the whole application.
 * java.math.BigDecimal with RoundingMode.HALF_UP is exactly Python's Decimal with ROUND_HALF_UP, and its
 * scale is Python's exponent as typed, so the same inputs are accepted and refused as on the desktop.
 */

const val MAX_MINOR = 10_000_000_000_000L  // 100 billion in major units
private val HUNDRED = BigDecimal(100)

/** Python Decimal(text): strict literal syntax (underscores allowed), null for invalid, NON_FINITE for NaN/Infinity. */
private val NON_FINITE = BigDecimal(-424242)

private fun decimalOf(raw: String): BigDecimal? {
    val s = raw.trim().replace("_", "")
    val body = s.lowercase().removePrefix("+").removePrefix("-")
    if (body in setOf("inf", "infinity", "nan", "snan") || body.startsWith("nan") || body.startsWith("snan")) return NON_FINITE
    if (!Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""").matches(s)) return null
    return BigDecimal(s)
}

/** Decimal value of a JSON input (money._to_decimal). Returns null for "" (blank). */
internal fun toDecimal(value: JValue, field: String): BigDecimal? {
    return when (value) {
        is JValue.Bool -> throw AZError.validation("$field must be a number", field)
        is JValue.Int -> BigDecimal(value.v)
        is JValue.Dbl -> {
            val d = decimalOf(JValue.pyFloat(value.v))
            if (d == null || d === NON_FINITE) throw AZError.validation("$field must be a valid amount", field)
            val q = d.setScale(2, RoundingMode.HALF_UP)
            // tolerate binary float noise (0.1 + 0.2) but not genuine extra precision
            if ((d - q).abs() < BigDecimal("0.0000001")) q else d
        }
        else -> {
            val text = value.py.replace(",", "").replace("₹", "").trim()
            if (text.isEmpty()) return null
            val d = decimalOf(text)
            if (d == null || d === NON_FINITE) throw AZError.validation("$field must be a valid amount", field)
            d
        }
    }
}

private fun isBlank(v: JValue) = v.isNull || (v is JValue.Str && v.v.isBlank())

/** Parse a major-unit amount ("12000.50") into integer minor units. */
fun parseAmount(value: JValue, field: String = "amount", required: Boolean = false, allowZero: Boolean = true,
                allowNegative: Boolean = false): Long {
    if (isBlank(value)) {
        if (required) throw AZError.validation("$field is required", field)
        return 0
    }
    val d = toDecimal(value, field) ?: run {
        if (required) throw AZError.validation("$field is required", field)
        return 0
    }
    if (d.scale() > 2 && d.compareTo(d.setScale(2, RoundingMode.HALF_UP)) != 0) {
        throw AZError.validation("$field can have at most 2 decimal places", field)
    }
    val hundredths = (d * HUNDRED).setScale(0, RoundingMode.HALF_UP)
    if (hundredths.signum() < 0 && !allowNegative) throw AZError.validation("$field cannot be negative", field)
    if (hundredths.signum() == 0 && !allowZero) throw AZError.validation("$field must be greater than zero", field)
    if (hundredths.abs() > BigDecimal(MAX_MINOR)) throw AZError.validation("$field is too large", field)
    return hundredths.toLong()
}

/** Python format(d.normalize(), "f"). */
internal fun normalized(d: BigDecimal): String {
    if (d.signum() == 0) return if (d.toString().startsWith("-")) "-0" else "0"
    return d.stripTrailingZeros().toPlainString()
}

/** Exchange rate: positive decimal, up to 8 decimal places. Returned as canonical string. */
fun parseRate(value: JValue, field: String = "exchange_rate"): String {
    if (isBlank(value)) throw AZError.validation("$field is required", field)
    val d = toDecimal(value, field)
    if (d == null || d.signum() <= 0) throw AZError.validation("$field must be greater than zero", field)
    if (d.scale() > 8) throw AZError.validation("$field can have at most 8 decimal places", field)
    if (d > BigDecimal(1_000_000)) throw AZError.validation("$field is unrealistically large", field)
    return normalized(d)
}

fun parsePercent(value: JValue, field: String = "rate"): String {
    if (isBlank(value)) return "0"
    val d = toDecimal(value, field) ?: return "0"
    if (d.signum() < 0 || d > HUNDRED) throw AZError.validation("$field must be between 0 and 100", field)
    if (d.scale() > 4) throw AZError.validation("$field can have at most 4 decimal places", field)
    // BigDecimal has no negative zero; Python's Decimal keeps it ("-0")
    val text = if (value is JValue.Dbl) JValue.pyFloat(value.v) else value.py
    if (d.signum() == 0 && text.trim().startsWith("-")) return "-0"
    return normalized(d)
}

private fun decimalText(s: String): BigDecimal = decimalOf(s)?.takeIf { it !== NON_FINITE } ?: BigDecimal.ZERO

/** Convert minor units at the given rate into reporting-currency minor units. */
fun convert(minor: Long, rate: String): Long =
    if (minor == 0L) 0 else (BigDecimal(minor) * decimalText(rate)).setScale(0, RoundingMode.HALF_UP).toLong()

/** pct% of minor units, rounded half-up to the nearest minor unit. */
fun percentOf(minor: Long, pct: String): Long =
    if (minor == 0L) 0 else (BigDecimal(minor) * decimalText(pct)).divide(HUNDRED).setScale(0, RoundingMode.HALF_UP).toLong()

/** Split a tax amount into two halves (CGST/SGST) that always add back exactly. */
fun splitHalf(minor: Long): Pair<Long, Long> {
    val first = BigDecimal(minor).divide(BigDecimal(2)).setScale(0, RoundingMode.HALF_UP).toLong()
    return first to (minor - first)
}

/** Minor units -> "12345.67" with exactly two decimals. */
fun fmt(minor: Long): String {
    val sign = if (minor < 0) "-" else ""
    val m = Math.abs(minor)
    return sign + (m / 100) + "." + String.format("%02d", m % 100)
}

/** Normalised decimal text ("9", "4.5"). */
fun normalizedDecimal(s: String): String = decimalOf(s)?.takeIf { it !== NON_FINITE }?.let { normalized(it) } ?: s

/** Half a percentage rate as text ("18" -> "9"), used for CGST / SGST labels. */
fun halfRate(rate: String): String = decimalOf(rate)?.takeIf { it !== NON_FINITE }?.let { normalized(it.divide(BigDecimal(2))) } ?: rate

/** Money text as on desktop documents: "INR 1,23,456.00" (Indian grouping for INR), "SAR 1234.50" otherwise. */
fun money(minor: Long, currency: String = "INR"): String = currency + " " + (if (currency == "INR") indianGrouping(fmt(minor)) else fmt(minor))

fun indianGrouping(s: String): String {
    val neg = s.startsWith("-")
    val body = s.removePrefix("-")
    val parts = body.split(".", limit = 2)
    var whole = parts[0].replace(",", "")
    val frac = if (parts.size > 1) parts[1] else ""
    if (whole.length > 3) {
        var head = whole.dropLast(3)
        val tail = whole.takeLast(3)
        val groups = ArrayList<String>()
        while (head.length > 2) { groups.add(0, head.takeLast(2)); head = head.dropLast(2) }
        if (head.isNotEmpty()) groups.add(0, head)
        whole = (groups + tail).joinToString(",")
    }
    return (if (neg) "-" else "") + whole + (if (frac.isEmpty()) "" else ".$frac")
}
