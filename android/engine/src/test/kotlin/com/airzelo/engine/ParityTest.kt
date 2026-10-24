package com.airzelo.engine

import org.junit.Test
import java.io.File

/** Port of AirZeloCheckSuite/ParityChecks.swift: cross-checks against the desktop app (fixture from ios/tools/parity.py). */
class ParityTest {
    /** 1. The desktop's own database, read by the Android engine, must give exactly the desktop's figures. */
    @Test fun engineReadsDesktopData() {
        val fx = Parity.load()
        val dir = tempDir("az-parity-read-")
        try {
            val file = File(dir, "agency.db")
            fx.dbFile.copyTo(file)
            withClock(fx.today) {
                val a = Agency.open(JdbcDb.factory, file, FieldCrypto.ephemeral())
                try {
                    val diffs = Parity.diff(Parity.snapshot(a, fx.today), fx.snapshot)
                    note("compared ${Parity.count(fx.snapshot)} values: ${fx.snapshot["bookings"].obj?.size ?: 0} bookings, " +
                        "${fx.snapshot["reports"].obj?.size ?: 0} report variants, ${fx.snapshot["statements"].obj?.size ?: 0} statements")
                    expectTrue(diffs.isEmpty(), "${diffs.size} differences from the desktop figures:\n" + diffs.take(25).joinToString("\n"))
                } finally { a.close() }
            }
        } finally { dir.deleteRecursively() }
    }

    /** 2. The same operations through the Android services: same results, same refusals, same books. */
    @Test fun replayedOperationsMatchDesktop() {
        val fx = Parity.load()
        withClock(fx.today) {
            TempAgency().use { t ->
                val a = t.agency
                val mismatches = ArrayList<String>()
                for ((i, op) in fx.ops.withIndex()) {
                    val name = op["op"].string!!
                    val args = op["args"].array.orEmpty()
                    val got = Parity.outcome { Parity.apply(a, name, args) }
                    val want = op["outcome"]
                    if (got != want) {
                        mismatches.add("op #$i $name${JValue.serialize(JValue.Arr(args)).take(160)}:\n    desktop ${JValue.serialize(want)}\n    Android ${JValue.serialize(got)}")
                        if (mismatches.size >= 10) break
                    }
                }
                note("${fx.ops.size} operations replayed, ${fx.ops.count { !it["outcome"]["error"].isNull }} of them refused on both")
                expectTrue(mismatches.isEmpty(), "${mismatches.size} operation outcomes differ:\n" + mismatches.joinToString("\n"))
                val diffs = Parity.diff(Parity.snapshot(a, fx.today), fx.snapshot)
                expectTrue(diffs.isEmpty(), "${diffs.size} differences after replay:\n" + diffs.take(25).joinToString("\n"))
            }
        }
    }
}
