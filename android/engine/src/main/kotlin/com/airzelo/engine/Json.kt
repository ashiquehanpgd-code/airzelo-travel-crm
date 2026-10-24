package com.airzelo.engine

import java.math.BigDecimal

/**
 * A JSON value. Service inputs ("payloads") use the same shape as the desktop app's JSON API, so the
 * Kotlin port applies the desktop validation rules unchanged and can replay the same inputs in parity tests.
 * Port of ios/AirZeloKit JSONValue.
 */
sealed class JValue {
    object Null : JValue()
    data class Bool(val v: Boolean) : JValue()
    data class Int(val v: Long) : JValue()
    data class Dbl(val v: Double) : JValue()
    data class Str(val v: String) : JValue()
    data class Arr(val v: List<JValue>) : JValue()
    data class Obj(val v: Map<String, JValue>) : JValue()

    val isNull get() = this is Null
    val string: String? get() = (this as? Str)?.v
    val array: List<JValue>? get() = (this as? Arr)?.v
    val obj: Map<String, JValue>? get() = (this as? Obj)?.v
    val long: Long?
        get() = when (this) {
            is Int -> v
            is Dbl -> if (v == Math.rint(v) && !v.isInfinite()) v.toLong() else null
            is Str -> v.trim().toLongOrNull()
            else -> null
        }
    val intValue: kotlin.Int? get() = long?.toInt()

    operator fun get(key: String): JValue = (this as? Obj)?.v?.get(key) ?: Null

    /** Python str(value) for scalars. */
    val py: String
        get() = when (this) {
            Null -> "None"
            is Bool -> if (v) "True" else "False"
            is Int -> v.toString()
            is Dbl -> pyFloat(v)
            is Str -> v
            else -> serialize(this)
        }

    /** Python truthiness. */
    val truthy: Boolean
        get() = when (this) {
            Null -> false
            is Bool -> v
            is Int -> v != 0L
            is Dbl -> v != 0.0
            is Str -> v.isNotEmpty()
            is Arr -> v.isNotEmpty()
            is Obj -> v.isNotEmpty()
        }

    override fun toString() = serialize(this)

    companion object {
        fun of(s: String?): JValue = if (s == null) Null else Str(s)
        fun of(i: Long?): JValue = if (i == null) Null else Int(i)
        fun of(i: kotlin.Int?): JValue = if (i == null) Null else Int(i.toLong())
        fun of(b: Boolean): JValue = Bool(b)
        fun obj(vararg pairs: Pair<String, Any?>): JValue = Obj(linkedMapOf(*pairs.map { it.first to wrap(it.second) }.toTypedArray()))
        fun arr(items: List<Any?>): JValue = Arr(items.map { wrap(it) })

        /** Kotlin value -> JValue (used by tests and services to build payloads). */
        fun wrap(x: Any?): JValue = when (x) {
            null -> Null
            is JValue -> x
            is String -> Str(x)
            is Boolean -> Bool(x)
            is kotlin.Int -> Int(x.toLong())
            is Long -> Int(x)
            is Double -> Dbl(x)
            is Map<*, *> -> Obj(x.entries.associate { it.key.toString() to wrap(it.value) })
            is List<*> -> Arr(x.map { wrap(it) })
            is SqlValue -> fromSql(x)
            else -> Str(x.toString())
        }

        fun fromSql(v: SqlValue): JValue = when (v) {
            SqlValue.Null -> Null
            is SqlValue.I -> Int(v.v)
            is SqlValue.R -> Dbl(v.v)
            is SqlValue.T -> Str(v.v)
            is SqlValue.B -> Str(java.util.Base64.getEncoder().encodeToString(v.v))
        }

        /** Python repr(float). */
        fun pyFloat(d: Double): String {
            if (d.isNaN()) return "nan"
            if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
            val (digits, exp10) = shortestDigits(d)  // value = 0.digits * 10^exp10
            val neg = d < 0 || (d == 0.0 && 1.0 / d < 0)
            val sign = if (neg) "-" else ""
            if (digits == "0") return sign + "0.0"
            val point = exp10  // position of the decimal point relative to digits
            val sciExp = point - 1
            return if (sciExp < -4 || sciExp >= 16) {
                val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
                sign + mant + "e" + (if (sciExp < 0) "-" else "+") + String.format("%02d", Math.abs(sciExp))
            } else if (point <= 0) {
                sign + "0." + "0".repeat(-point) + digits
            } else if (point >= digits.length) {
                sign + digits + "0".repeat(point - digits.length) + ".0"
            } else {
                sign + digits.substring(0, point) + "." + digits.substring(point)
            }
        }

        /** Shortest decimal digits that round-trip to d: (digits, exponent) with value = 0.d1d2.. * 10^exponent. */
        private fun shortestDigits(d: Double): Pair<String, kotlin.Int> {
            val a = Math.abs(d)
            if (a == 0.0) return "0" to 1
            val exact = BigDecimal(a)
            for (p in 1..17) {
                val r = exact.round(java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN))
                if (r.toDouble() == a) {
                    val s = r.stripTrailingZeros()
                    val unscaled = s.unscaledValue().toString()
                    return unscaled to (unscaled.length - s.scale())
                }
            }
            val s = exact.stripTrailingZeros()
            return s.unscaledValue().toString() to (s.unscaledValue().toString().length - s.scale())
        }

        // ---- parse / serialise

        fun parse(text: String): JValue = Parser(text).parseTop()

        /** Deterministic JSON (keys sorted when asked; Python json.dumps separators). */
        fun serialize(v: JValue, sortKeys: Boolean = true): String = StringBuilder().also { write(v, it, sortKeys) }.toString()

        private fun write(v: JValue, out: StringBuilder, sortKeys: Boolean) {
            when (v) {
                Null -> out.append("null")
                is Bool -> out.append(if (v.v) "true" else "false")
                is Int -> out.append(v.v)
                is Dbl -> out.append(pyFloat(v.v))
                is Str -> writeString(v.v, out)
                is Arr -> {
                    out.append('[')
                    v.v.forEachIndexed { i, x -> if (i > 0) out.append(", "); write(x, out, sortKeys) }
                    out.append(']')
                }
                is Obj -> {
                    out.append('{')
                    val keys = if (sortKeys) v.v.keys.sorted() else v.v.keys.toList()
                    keys.forEachIndexed { i, k ->
                        if (i > 0) out.append(", ")
                        writeString(k, out); out.append(": "); write(v.v[k]!!, out, sortKeys)
                    }
                    out.append('}')
                }
            }
        }

        private fun writeString(s: String, out: StringBuilder) {
            out.append('"')
            for (ch in s) {
                when {
                    ch == '"' -> out.append("\\\"")
                    ch == '\\' -> out.append("\\\\")
                    ch == '\n' -> out.append("\\n")
                    ch == '\r' -> out.append("\\r")
                    ch == '\t' -> out.append("\\t")
                    ch.code < 0x20 || ch.code > 0x7E -> out.append(String.format("\\u%04x", ch.code))
                    else -> out.append(ch)
                }
            }
            out.append('"')
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun parseTop(): JValue {
            val v = value(); ws()
            if (i != s.length) error("trailing data")
            return v
        }
        fun error(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg at $i")
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): JValue {
            ws()
            if (i >= s.length) error("end of input")
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, JValue>(); ws()
                    if (s[i] == '}') { i++; return Obj(m) }
                    while (true) { ws(); val k = str(); ws(); if (s[i] != ':') error("':'"); i++; m[k] = value(); ws()
                        if (s[i] == ',') { i++; continue }; if (s[i] == '}') { i++; break }; error("',' or '}'") }
                    Obj(m) }
                '[' -> { i++; val l = ArrayList<JValue>(); ws()
                    if (s[i] == ']') { i++; return Arr(l) }
                    while (true) { l.add(value()); ws()
                        if (s[i] == ',') { i++; continue }; if (s[i] == ']') { i++; break }; error("',' or ']'") }
                    Arr(l) }
                '"' -> Str(str())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c'")
            }
        }
        fun expect(w: String) { if (!s.startsWith(w, i)) error(w); i += w.length }
        fun num(): JValue {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isFloat = false
            if (i < s.length && s[i] == '.') { isFloat = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { isFloat = true; i++
                if (s[i] == '+' || s[i] == '-') i++; while (i < s.length && s[i].isDigit()) i++ }
            val t = s.substring(start, i)
            return if (isFloat) Dbl(t.toDouble()) else (t.toLongOrNull()?.let { Int(it) } ?: Dbl(t.toDouble()))
        }
        fun str(): String {
            if (s[i] != '"') error("string")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("escape $e")
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}

/** Service input: the same keys the desktop API accepts. */
typealias Fields = Map<String, JValue>

fun Fields.arg(key: String): JValue = this[key] ?: JValue.Null
fun fieldsOf(vararg pairs: Pair<String, Any?>): Fields = linkedMapOf(*pairs.map { it.first to JValue.wrap(it.second) }.toTypedArray())
fun J(s: String?): JValue = JValue.of(s)
fun J(i: Long?): JValue = JValue.of(i)
fun J(i: Int?): JValue = JValue.of(i)
