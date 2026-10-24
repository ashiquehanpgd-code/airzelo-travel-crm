package com.airzelo.engine

import org.junit.Test
import java.io.File
import java.util.Base64

/** Port of AirZeloCheckSuite/SecurityChecks.swift (the backup-container part is in BackupTest). */
class SecurityTest {
    @Test fun passportNumbersEncryptedAtRest() = TempAgency().use { t ->
        val a = t.agency
        val cid = a.createCustomer(f("name" to "Secret Holder", "passport_no" to "k9876543"))
        a.saveBooking(f("customer_id" to cid, "status" to "confirmed", "passengers" to listOf(mapOf("name" to "Pax One", "passport_no" to "q1112223"))))
        a.updateCustomer(cid, f("city" to "Kochi"))  // re-saves the sealed value
        expectEq(a.getCustomer(cid).customer["passport_no"], J("K9876543"), "readable in the app")
        val stored = a.db.one("SELECT passport_no FROM customers WHERE id = ?", listOf(cid))!!.str("passport_no")
        expectTrue(stored.startsWith(FieldCrypto.PREFIX), "stored sealed, got $stored")
        a.db.query("PRAGMA wal_checkpoint(FULL)")
        for (suffix in listOf("", "-wal")) {
            val file = File(a.db.path + suffix)
            if (!file.exists()) continue
            val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
            for (secret in listOf("K9876543", "Q1112223")) expectTrue(!bytes.contains(secret), "$secret found in plain text in agency.db$suffix")
        }
        val audit = a.listAudit().joinToString("") { it.str("after_json") + it.str("before_json") }
        expectTrue(!audit.contains("K9876543") && !audit.contains("Q1112223"), "audit log must not hold passport numbers")
    }

    @Test fun tamperedOrForeignFieldRejected() {
        val c = FieldCrypto.ephemeral()
        val sealed = c.seal("P1234567", "passport_no")!!
        expectEq(c.open(sealed, "passport_no"), "P1234567")
        expectTrue(c.open(sealed, "notes") != "P1234567", "bound to its column")
        expectTrue(FieldCrypto.ephemeral().open(sealed, "passport_no") != "P1234567", "another device key cannot read it")
        val raw = Base64.getDecoder().decode(sealed.removePrefix(FieldCrypto.PREFIX))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        expectTrue(c.open(FieldCrypto.PREFIX + Base64.getEncoder().encodeToString(raw), "passport_no") != "P1234567", "edited value rejected")
    }

    @Test fun liveFeaturesRefuseNeverSimulate() {
        for (feature in OnlineFeature.values()) {
            try {
                feature.perform()
            } catch (e: AZError) {
                expectEq(e.code, "unavailable", feature.title)
                expectTrue(e.message.contains("not available"), "clear message for ${feature.title}")
            }
        }
    }

    /** Static scan of the engine's own sources for networking APIs. */
    @Test fun engineSourceHasNoNetworkCode() {
        val src = File("src/main/kotlin")
        val files = src.walkTopDown().filter { it.extension == "kt" }.toList()
        expectTrue(files.isNotEmpty(), "engine sources not found from ${File(".").absolutePath}")
        val forbidden = listOf("java.net", "HttpURLConnection", "okhttp", "Socket(", "URL(", "android.net", "WebView")
        val hits = files.flatMap { f -> val text = f.readText(); forbidden.filter { text.contains(it) }.map { "${f.name}: $it" } }
        expectTrue(hits.isEmpty(), "network API in the offline engine: $hits")
        note("${files.size} source files scanned")
    }
}
