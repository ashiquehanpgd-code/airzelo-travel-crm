package com.airzelo.engine

import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/** Port of AirZeloCheckSuite/BackupChecks.swift, the zip check of UnitChecks.swift and the container part of SecurityChecks.swift. */
class BackupTest {
    class TempStore(key: ByteArray = randomKey()) : AutoCloseable {
        val root = tempDir("az-store-")
        val store = AgencyStore(root, key, JdbcDb.factory)
        override fun close() { store.close(); root.deleteRecursively() }
    }

    companion object {
        fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

        fun sample(a: Agency) {
            val x = ServiceTest.basics(a)
            val b = a.saveBooking(f("customer_id" to x.cust, "supplier_id" to x.sup, "status" to "ticketed", "purchase_cost" to "20000",
                "selling_price" to "22500", "passengers" to listOf(mapOf("name" to "Backup Pax", "passport_no" to "m5554443", "ticket_number" to "0651112223334"))))
            a.createTransaction(f("kind" to "customer_receipt", "account_id" to x.cash, "amount" to "10000", "booking_id" to b))
            a.createInvoice(b)
        }

        fun dump(a: Agency) = Parity.tableDump(a)
    }

    @Test fun zipAndCrc32RoundTrip() {
        expectEq(Zip.crc32("123456789".toByteArray()), 0xCBF43926L, "crc32")
        val w = Zip.Writer()
        val big = ByteArray(300_000) { (it % 251).toByte() }
        w.add("manifest.json", "{\"app\": \"AirZelo\"}".toByteArray())
        w.add("uploads/a.bin", big)
        w.add("empty.txt", ByteArray(0))
        val data = w.finish()
        val r = Zip.Reader(data)
        expectEq(r.names, listOf("manifest.json", "uploads/a.bin", "empty.txt"))
        expectTrue(r.read("uploads/a.bin").contentEquals(big), "big entry")
        expectEq(r.read("empty.txt").size, 0)
        // the archive is a standard zip: java.util.zip reads it too
        java.util.zip.ZipInputStream(data.inputStream()).use { z ->
            val names = generateSequence { z.nextEntry }.map { e -> e.name.also { z.readBytes() } }.toList()
            expectEq(names, listOf("manifest.json", "uploads/a.bin", "empty.txt"))
        }
        val bad = data.copyOf()
        bad[60] = (bad[60].toInt() xor 0xFF).toByte()  // damage the compressed payload of the first entry
        expectTrue(runCatching { Zip.Reader(bad).read("manifest.json") }.isFailure, "damaged entry must be rejected")
    }

    @Test fun backupContainerRejectsTampering() {
        val plain = ByteArray(2_500_000) { (it * 31).toByte() }  // 3 chunks
        val box = BackupCrypto.seal(plain, BackupCrypto.KeySource.Passphrase("correct horse"), "test")
        expectTrue(BackupCrypto.open(box, null, "correct horse").contentEquals(plain), "round trip")
        fun fails(d: ByteArray, pass: String, what: String) = expectTrue(runCatching { BackupCrypto.open(d, null, pass) }.isFailure, what)
        fails(box, "wrong horse", "wrong passphrase")
        fails(box.copyOf().also { it[it.size - 600_000] = (it[it.size - 600_000].toInt() xor 0x40).toByte() }, "correct horse", "flipped byte in a later chunk")
        fails(box.copyOf(box.size - 1_100_000), "correct horse", "truncated file (last chunk missing)")
        fails(box.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }, "correct horse", "edited header")
        expectTrue(runCatching { BackupCrypto.seal(plain, BackupCrypto.KeySource.Passphrase("short"), "test") }.exceptionOrNull() is BackupCrypto.CryptoError,
            "weak passphrase refused")
        val dev = BackupCrypto.seal("x".toByteArray(), BackupCrypto.KeySource.Device(randomKey()), "t")
        expectTrue(runCatching { BackupCrypto.open(dev, randomKey(), null) }.isFailure, "other device key")
    }

    /** Known-answer vectors, so the key derivations are exactly the iPhone's (CommonCrypto PBKDF2, CryptoKit HKDF). */
    @Test fun keyDerivationKnownAnswers() {
        // RFC 7914 section 11: PBKDF2-HMAC-SHA256("passwd", "salt", 1)
        expectEq(BackupCrypto.deriveKey("passwd", "salt".toByteArray(), 1).joinToString("") { "%02x".format(it) },
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc")
        // RFC 6070-style SHA-256 vector: PBKDF2-HMAC-SHA256("password", "salt", 4096), first 32 bytes
        expectEq(BackupCrypto.deriveKey("password", "salt".toByteArray(), 4096).joinToString("") { "%02x".format(it) },
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a")
        // HKDF-SHA256 with no salt (RFC 5869 test case 3 uses an empty salt and empty info; here the info is ours)
        val ikm = ByteArray(22) { 0x0b }
        val prk = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256")) }.doFinal(ikm)
        expectEq(prk.joinToString("") { "%02x".format(it) }, "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04")
    }

    @Test fun deviceBackupAndRestore() = TempStore().use { t ->
        val a = t.store.open()
        sample(a)
        val before = dump(a)
        val b = t.store.createBackup()
        a.createCustomer(f("name" to "Added After Backup"))
        val safety = t.store.restore(t.store.backupData(b))
        expectTrue(safety != null, "a safety backup of the replaced books is made")
        expectEq(safety!!.kind, "pre-restore", "kind of the safety backup")
        expectEq(t.store.listBackups().map { it.kind }.sorted(), listOf("manual", "pre-restore"))
        val restored = t.store.open()
        val diffs = Parity.diff(dump(restored), before)
        expectTrue(diffs.isEmpty(), "restored books equal the backed-up books: $diffs")
        expectEq(restored.scalarInt("SELECT COUNT(*) FROM customers WHERE name = 'Added After Backup'"), 0L)
        expectEq(restored.integrityCheck(), "ok")
        note("backup ${b.size} bytes; ${t.store.listBackups().size} backups on device")
    }

    @Test fun passphraseExportToAnotherDevice() {
        TempStore().use { phone1 -> TempStore().use { phone2 ->
            val a = phone1.store.open()
            sample(a)
            val before = dump(a)
            val file = phone1.store.exportBackup("kerala-travel-2026")
            expectTrue(phone2.store.verify(file, null).needsPassphrase, "export asks for its passphrase")
            expectTrue(!phone2.store.verify(file, "wrong passphrase").ok, "wrong passphrase refused")
            phone2.store.restore(file, "kerala-travel-2026")
            val b = phone2.store.open()
            val diffs = Parity.diff(dump(b), before)
            expectTrue(diffs.isEmpty(), "the other phone has the same books, passports readable: $diffs")
            val sealed = b.db.one("SELECT passport_no FROM booking_passengers")!!.str("passport_no")
            expectTrue(sealed.startsWith(FieldCrypto.PREFIX), "re-encrypted with the new phone's key")
        } }
    }

    @Test fun damagedBackupRefusedBooksUntouched() = TempStore().use { t ->
        val a = t.store.open()
        sample(a)
        val before = dump(a)
        val data = t.store.backupData(t.store.createBackup())
        data[data.size / 2] = (data[data.size / 2].toInt() xor 0xFF).toByte()
        expectTrue(runCatching { t.store.restore(data) }.exceptionOrNull() is AgencyStore.StoreError, "a damaged backup must be refused")
        expectTrue(Parity.diff(dump(t.store.open()), before).isEmpty(), "current books unchanged")
        expectTrue(!t.store.verify("hello".toByteArray(), null).ok, "random file refused")
    }

    @Test fun corruptedBooksRestoredFromBackup() = TempStore().use { t ->
        val a = t.store.open()
        sample(a)
        val before = dump(a)
        t.store.createBackup("auto")
        t.store.close()
        // overwrite the middle of the database file, as a failing disk or an interrupted copy would
        RandomAccessFile(t.store.dbFile, "rw").use { h -> h.seek(h.length() / 3); h.write(ByteArray(8192) { 0xA5.toByte() }) }
        val err = runCatching { t.store.open() }.exceptionOrNull()
        expectTrue(err is AgencyStore.StoreError.Corrupted, "corrupted books must not open without warning (got $err)")
        note("detected: ${(err as AgencyStore.StoreError.Corrupted).why.take(80)}")
        val used = t.store.recoverFromLatestBackup()
        val b = t.store.open()
        expectEq(b.integrityCheck(), "ok")
        expectTrue(Parity.diff(dump(b), before).isEmpty(), "books equal the last good backup (${used.name})")
        expectTrue(t.root.list()!!.any { it.startsWith("damaged-") }, "the damaged files are kept aside")
    }

    /** A real process is killed in the middle of a save (and right after another) with WAL + synchronous=FULL. */
    @Test fun crashMidSaveLosesNothingCommitted() {
        val dir = tempDir("az-crash-")
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            for (mode in listOf("committed", "uncommitted")) {
                val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.airzelo.engine.CrashWriter", dir.path, mode)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                expectEq(code, 77, "child must have been killed mid-way ($mode): $out")
            }
            val a = Agency.open(JdbcDb.factory, File(dir, "agency.db"), FieldCrypto.ephemeral())
            try {
                expectEq(a.integrityCheck(), "ok")
                expectEq(a.scalarInt("SELECT COUNT(*) FROM customers WHERE name LIKE 'Committed %'"), 1L, "committed save survived")
                expectEq(a.scalarInt("SELECT COUNT(*) FROM customers WHERE name LIKE 'Lost %'"), 0L, "unfinished save rolled back")
            } finally { a.close() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun desktopBackupImportsOnAndroid() {
        val fx = Parity.load()
        withClock(fx.today) {
            TempStore().use { t ->
                val zip = File(fx.dir, "desktop-backup.zip").readBytes()
                val v = t.store.verify(zip, null)
                expectTrue(v.ok, "desktop backup verifies: ${v.problems}")
                t.store.restore(zip)
                val a = t.store.open()
                val diffs = Parity.diff(Parity.snapshot(a, fx.today), fx.snapshot)
                expectTrue(diffs.isEmpty(), "${diffs.size} differences after import:\n" + diffs.take(20).joinToString("\n"))
                expectEq(a.scalarInt("SELECT COUNT(*) FROM customers WHERE passport_no IS NOT NULL AND passport_no NOT LIKE 'azenc1:%'"), 0L,
                    "imported passport numbers are encrypted on the phone")
                note("desktop ${v.manifest["app_version"].py} backup: ${zip.size} bytes, ${fx.snapshot["bookings"].obj?.size ?: 0} bookings imported")
            }
        }
    }

    /** Replays the desktop's operations through the Android services and writes a portable export; the desktop then
     *  restores it (ios/tools/parity.py --check-export) and must reproduce every figure. */
    @Test fun androidExportForDesktop() {
        val fx = Parity.load()
        withClock(fx.today) {
            TempStore().use { t ->
                val a = t.store.open()
                for (op in fx.ops) Parity.outcome { Parity.apply(a, op["op"].string!!, op["args"].array.orEmpty()) }
                val zip = t.store.archive(portable = true, note = "parity export")
                val out = File(fx.dir, "android-export.zip")
                out.writeBytes(zip)
                val r = Zip.Reader(zip)
                expectTrue("agency.db" in r.names && "manifest.json" in r.names, "desktop archive layout")
                note("wrote ${out.name} (${zip.size} bytes); check it with: python3 ios/tools/parity.py --check-export ${fx.dir} --export-name ${out.name}")
            }
        }
    }
}

/** Child-process half of crashMidSaveLosesNothingCommitted: CrashWriter <dir> committed|uncommitted. */
object CrashWriter {
    @JvmStatic fun main(args: Array<String>) {
        val a = Agency.open(JdbcDb.factory, File(args[0], "agency.db"), FieldCrypto.ephemeral())
        if (args[1] == "committed") {
            a.createCustomer(f("name" to "Committed One"))
            Runtime.getRuntime().halt(77)  // straight after COMMIT returned
        }
        a.tx {
            for (i in 0 until 300) a.createCustomer(f("name" to "Lost $i"))
            Runtime.getRuntime().halt(77)  // in the middle of an open transaction
        }
        Runtime.getRuntime().halt(1)
    }
}
