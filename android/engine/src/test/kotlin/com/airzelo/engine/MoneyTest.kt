package com.airzelo.engine

import org.junit.Test

/** Port of AirZeloCheckSuite/UnitChecks.swift (money). */
class MoneyTest {
    @Test fun roundingAndFormatting() {
        expectEq(parseAmount(J("12,000.50")), 1_200_050L)
        expectEq(parseAmount(JValue.Dbl(0.1)), 10L)
        expectEq(parseAmount(J("₹ 1,500")), 150_000L)
        expectError("validation", "Amount can have at most 2 decimal places") { parseAmount(J("1.005"), "Amount") }
        expectError("validation", "Amount must be a valid amount") { parseAmount(J("12abc"), "Amount") }
        expectEq(convert(12_345, "22.15"), 273_442L)       // 12345 * 22.15 = 273441.75 -> half up
        expectEq(convert(-1_050, "0.5"), -525L)
        expectEq(convert(5, "0.5"), 3L)                     // 2.5 -> 3 (ties away from zero)
        expectEq(convert(-5, "0.5"), -3L)
        expectEq(percentOf(12_345, "18"), 2_222L)           // 2222.1
        expectEq(splitHalf(2_223).first + splitHalf(2_223).second, 2_223L)
        expectEq(splitHalf(2_223).first, 1_112L)
        expectEq(fmt(-1_050), "-10.50")
        expectEq(money(1_23_45_678_90, "INR"), "INR 1,23,45,678.90")
        expectEq(money(123_456, "SAR"), "SAR 1234.56")
        expectEq(halfRate("18"), "9")
        expectEq(halfRate("5"), "2.5")
    }

    /** Every input the desktop's money.py was given, with its exact result or refusal (from ios/tools/parity.py). */
    @Test fun parsingMatchesDesktopVectors() {
        val vectors = Parity.load().money
        val problems = ArrayList<String>()
        var n = 0
        for (row in vectors["parse"].array.orEmpty()) {
            val input = row["input"]
            val fns: List<Pair<String, (JValue) -> JValue>> = listOf(
                "amount" to { v -> J(parseAmount(v, "Amount")) },
                "amount_neg" to { v -> J(parseAmount(v, "Amount", allowZero = false, allowNegative = true)) },
                "rate" to { v -> J(parseRate(v, "Rate")) },
                "percent" to { v -> J(parsePercent(v, "Pct")) })
            for ((name, fn) in fns) {
                n++
                val got = try { JValue.obj("ok" to fn(input)) } catch (e: AZError) { JValue.obj("error" to e.message) }
                if (got != row[name]) problems.add("$name(${JValue.serialize(input)}): desktop ${JValue.serialize(row[name])} Android ${JValue.serialize(got)}")
            }
        }
        for (c in vectors["calc"].array.orEmpty()) {
            val m = c["minor"].long!!; val r = c["rate"].string!!
            n += 4
            if (convert(m, r) != c["convert"].long) problems.add("convert($m, $r)")
            if (percentOf(m, r) != c["percent_of"].long) problems.add("percent_of($m, $r)")
            val sh = splitHalf(m)
            if (listOf(J(sh.first), J(sh.second)) != c["split_half"].array) problems.add("split_half($m)")
            if (fmt(m) != c["fmt"].string) problems.add("fmt($m)")
        }
        note("$n money results compared with the desktop")
        expectTrue(n > 0, "no money vectors in the fixture")
        expectTrue(problems.isEmpty(), problems.take(20).joinToString("\n"))
    }
}
