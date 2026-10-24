package com.airzelo.engine

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The desktop app's error classes (server/errors.py): same codes and messages. */
class AZError(val kind: Kind, override val message: String, val code: String, val field: String? = null,
              val details: JValue = JValue.Null) : RuntimeException(message) {
    enum class Kind { VALIDATION, NOT_FOUND, CONFLICT, UNAVAILABLE }

    companion object {
        fun validation(message: String, field: String? = null) = AZError(Kind.VALIDATION, message, "validation", field)
        fun notFound(message: String = "Not found") = AZError(Kind.NOT_FOUND, message, "not_found")
        fun conflict(message: String, code: String = "conflict", details: JValue = JValue.Null) = AZError(Kind.CONFLICT, message, code, null, details)
        /** A feature that needs the internet (or the desktop app); never simulated offline. */
        fun unavailable(message: String) = AZError(Kind.UNAVAILABLE, message, "unavailable")
    }
}

/** Port of core/server/validate.py: same rules and messages. */
internal object V {
    /** Python str.capitalize(). */
    fun capitalize(s: String): String = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1).lowercase()
    private fun label(key: String, label: String?) = label ?: capitalize(key.replace("_", " "))
    private fun cpLen(s: String) = s.codePointCount(0, s.length)

    fun s(data: Fields, key: String, maxLen: Int = 500, required: Boolean = false, label: String? = null): String? {
        val lab = label(key, label)
        val raw = data.arg(key)
        val text = when (raw) {
            JValue.Null -> ""
            is JValue.Str -> raw.v
            is JValue.Int, is JValue.Dbl -> raw.py
            else -> throw AZError.validation("$lab is invalid", key)
        }
        val v = text.trim()
        if (required && v.isEmpty()) throw AZError.validation("$lab is required", key)
        if (cpLen(v) > maxLen) throw AZError.validation("$lab is too long (max $maxLen characters)", key)
        return v.ifEmpty { null }
    }

    fun d(data: Fields, key: String, required: Boolean = false, label: String? = null): String? {
        val v = s(data, key, 10, required, label) ?: return null
        val lab = label(key, label)
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(v)) throw AZError.validation("$lab must be a date (YYYY-MM-DD)", key)
        if (!Dates.isValidDate(v)) throw AZError.validation("$lab is not a valid date", key)
        return v
    }

    fun dt(data: Fields, key: String, label: String? = null): String? {
        var v = s(data, key, 19, label = label) ?: return null
        val lab = label(key, label)
        v = v.replace(" ", "T")
        val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(T(\d{1,2}):(\d{1,2})(:(\d{1,2}))?)?""").matchEntire(v)
        if (m != null) {
            val g = m.groupValues
            var ok = Dates.isValid(g[1].toInt(), g[2].toInt(), g[3].toInt())
            if (g[5].isNotEmpty()) ok = ok && g[5].toInt() < 24 && g[6].toInt() < 60
            if (g[8].isNotEmpty()) ok = ok && g[8].toInt() < 62
            if (ok) return if (v.contains("T")) v.take(16) else v
        }
        throw AZError.validation("$lab must be a date/time (YYYY-MM-DDTHH:MM)", key)
    }

    fun choice(data: Fields, key: String, options: List<String>, dflt: String? = null, label: String? = null): String {
        val v = data.arg(key)
        if (v.isNull || v == JValue.Str("")) {
            if (dflt != null) return dflt
            throw AZError.validation("${label ?: key} is required", key)
        }
        if (v !is JValue.Str || v.v !in options) throw AZError.validation("${label ?: key} must be one of: ${options.joinToString(", ")}", key)
        return v.v
    }

    fun i(data: Fields, key: String, required: Boolean = false, label: String? = null): Long? {
        val v = data.arg(key)
        val lab = label(key, label)
        if (v.isNull || v == JValue.Str("")) {
            if (required) throw AZError.validation("$lab is required", key)
            return null
        }
        return when (v) {
            is JValue.Int -> v.v
            is JValue.Dbl -> if (v.v.isFinite()) v.v.toLong() else throw AZError.validation("$lab is invalid", key)
            is JValue.Str -> v.v.trim().toLongOrNull() ?: throw AZError.validation("$lab is invalid", key)
            else -> throw AZError.validation("$lab is invalid", key)
        }
    }

    fun b(data: Fields, key: String): Boolean = when (val v = data.arg(key)) {
        is JValue.Bool -> v.v
        is JValue.Int -> v.v == 1L
        is JValue.Dbl -> v.v == 1.0
        is JValue.Str -> v.v in setOf("1", "true", "on", "yes")
        else -> false
    }

    /** data.arg(key) in (False, 0, "0"). */
    fun isOff(v: JValue): Boolean = when (v) {
        is JValue.Bool -> !v.v
        is JValue.Int -> v.v == 0L
        is JValue.Dbl -> v.v == 0.0
        is JValue.Str -> v.v == "0"
        else -> false
    }

    fun email(data: Fields, key: String): String? {
        val v = s(data, key, 200)
        if (v != null && !Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""").matches(v)) throw AZError.validation("Email address looks invalid", key)
        return v
    }

    fun phone(data: Fields, key: String): String? {
        val v = s(data, key, 40)
        if (v != null && !Regex("""[0-9+()\-\s.]{5,40}""").matches(v)) throw AZError.validation("Phone number looks invalid", key)
        return v
    }

    fun normCode(v: JValue, maxLen: Int = 12): String? {
        if (v.isNull) return null
        val t = v.py.trim().uppercase()
        val cut = if (cpLen(t) > maxLen) t.substring(0, t.offsetByCodePoints(0, maxLen)) else t
        return cut.ifEmpty { null }
    }

    fun normCode(v: String?, maxLen: Int = 12) = normCode(J(v), maxLen)
}

/** Calendar helpers. Dates are ISO text (YYYY-MM-DD), exactly as the desktop stores them. */
object Dates {
    fun isValid(y: Int, m: Int, d: Int): Boolean = y >= 1 && m in 1..12 && d >= 1 && d <= daysInMonth(y, m)
    fun isValidDate(s: String): Boolean {
        val p = s.split("-").mapNotNull { it.toIntOrNull() }
        return p.size == 3 && isValid(p[0], p[1], p[2])
    }
    fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Clock used by the services; tests pin it so results do not depend on the day they run. */
    @Volatile var now: () -> LocalDateTime = { LocalDateTime.now(ZoneId.systemDefault()) }

    fun today(): String = now().toLocalDate().toString()
    fun nowISO(): String = now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    fun parse(s: String?): LocalDate? = try { if (s == null) null else LocalDate.parse(s.take(10)) } catch (e: Exception) { null }
    fun addDays(s: String, n: Long): String = parse(s)?.plusDays(n)?.toString() ?: s
    fun daysBetween(a: String, b: String): Long? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        return ChronoUnit.DAYS.between(x, y)
    }
    val monthAbbr = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
