package com.airzelo.engine

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted container for phone backups ("AZBK1"), byte-compatible with the iPhone app (Backup/BackupCrypto.swift).
 *
 *   magic "AZBK1\0" | header length (UInt32 LE) | header JSON | chunks
 *   header: {"v":1, "kdf":"device"|"pbkdf2-sha256", "salt":b64, "iterations":n, "chunk":1048576, "created_at":..., "app_version":...}
 *   chunk:  length (UInt32 LE) | AES-256-GCM sealed box (nonce | ciphertext | tag)
 *
 * Every chunk is authenticated together with the header, its index and whether it is the last one, so a truncated,
 * reordered or edited file is rejected rather than half restored. The plaintext is a desktop AirZelo backup (.zip
 * with manifest.json), so a decrypted export restores directly in the desktop app or on an iPhone.
 */
object BackupCrypto {
    private val MAGIC = "AZBK1\u0000".toByteArray()
    private const val CHUNK_SIZE = 1 shl 20
    const val PBKDF2_ITERATIONS = 600_000
    private val random = SecureRandom()

    sealed class KeySource {
        /** The device's 32-byte master key (the same key that seals passport numbers). */
        class Device(val key: ByteArray) : KeySource()
        class Passphrase(val passphrase: String) : KeySource()
    }

    class CryptoError(val kind: String, message: String) : Exception(message) {
        companion object {
            fun notABackup() = CryptoError("not_a_backup", "This is not an AirZelo phone backup.")
            fun wrongKey() = CryptoError("wrong_key", "The passphrase is wrong, or the backup was made on another device.")
            fun damaged() = CryptoError("damaged", "The backup file is damaged or incomplete.")
            fun weakPassphrase() = CryptoError("weak_passphrase", "Use a passphrase of at least 8 characters.")
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256")) }.doFinal(data)

    /** PBKDF2-HMAC-SHA256 over the UTF-8 passphrase (as CommonCrypto does on the iPhone). */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }, "HmacSHA256")) }
        // HMAC with an empty key equals HMAC with a zero byte key (keys are zero-padded), and SecretKeySpec refuses empty keys
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    /** Backups made with the device key use a key derived for this purpose only (HKDF-SHA256, no salt, never the field key itself). */
    fun backupKey(device: ByteArray): ByteArray {
        val prk = hmac(ByteArray(32), device)
        return hmac(prk, "AirZelo backup v1".toByteArray() + byteArrayOf(1)).copyOf(32)
    }

    private fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun readLE32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)

    private fun aad(header: ByteArray, index: Int, last: Boolean) = header + le32(index) + byteArrayOf(if (last) 1 else 0)

    fun seal(plain: ByteArray, source: KeySource, appVersion: String): ByteArray {
        val header = linkedMapOf<String, JValue>("v" to J(1), "chunk" to J(CHUNK_SIZE), "created_at" to J(Dates.nowISO()), "app_version" to J(appVersion))
        val key = when (source) {
            is KeySource.Device -> { header["kdf"] = J("device"); backupKey(source.key) }
            is KeySource.Passphrase -> {
                if (source.passphrase.codePointCount(0, source.passphrase.length) < 8) throw CryptoError.weakPassphrase()
                val salt = ByteArray(16).also { random.nextBytes(it) }
                header["kdf"] = J("pbkdf2-sha256")
                header["salt"] = J(Base64.getEncoder().encodeToString(salt))
                header["iterations"] = J(PBKDF2_ITERATIONS)
                deriveKey(source.passphrase, salt, PBKDF2_ITERATIONS)
            }
        }
        val headerData = JValue.serialize(JValue.Obj(header)).toByteArray()
        val out = java.io.ByteArrayOutputStream(plain.size + plain.size / 64 + 256)
        out.write(MAGIC); out.write(le32(headerData.size)); out.write(headerData)
        val n = maxOf(1, (plain.size + CHUNK_SIZE - 1) / CHUNK_SIZE)
        val k = SecretKeySpec(key, "AES")
        for (i in 0 until n) {
            val nonce = ByteArray(12).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
            c.updateAAD(aad(headerData, i, i == n - 1))
            val from = i * CHUNK_SIZE
            val box = nonce + c.doFinal(plain, from, minOf(plain.size, from + CHUNK_SIZE) - from)
            out.write(le32(box.size)); out.write(box)
        }
        return out.toByteArray()
    }

    fun isContainer(data: ByteArray) = data.size > MAGIC.size && data.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** Header fields without decrypting (to know whether a passphrase is needed). */
    fun header(data: ByteArray): JValue {
        if (!isContainer(data) || data.size < MAGIC.size + 4) throw CryptoError.notABackup()
        val len = readLE32(data, MAGIC.size)
        val start = MAGIC.size + 4
        if (start + len > data.size) throw CryptoError.damaged()
        return try { JValue.parse(String(data, start, len.toInt(), Charsets.UTF_8)) } catch (e: IllegalArgumentException) { throw CryptoError.damaged() }
    }

    fun open(data: ByteArray, deviceKey: ByteArray?, passphrase: String?): ByteArray {
        val h = header(data)
        val len = readLE32(data, MAGIC.size).toInt()
        val headerData = data.copyOfRange(MAGIC.size + 4, MAGIC.size + 4 + len)
        val key = if (h["kdf"].string == "device") {
            backupKey(deviceKey ?: throw CryptoError.wrongKey())
        } else {
            val salt = try { Base64.getDecoder().decode(h["salt"].string ?: "") } catch (e: IllegalArgumentException) { null }
            val it = h["iterations"].long
            if (passphrase == null || salt == null || it == null || it < 100_000 || it > 10_000_000) throw CryptoError.wrongKey()
            deriveKey(passphrase, salt, it.toInt())
        }
        var p = MAGIC.size + 4 + len
        val chunks = ArrayList<IntRange>()
        while (p < data.size) {
            if (p + 4 > data.size) throw CryptoError.damaged()
            val clen = readLE32(data, p)
            p += 4
            if (clen <= 28 || p + clen > data.size) throw CryptoError.damaged()
            chunks.add(p until p + clen.toInt())
            p += clen.toInt()
        }
        if (chunks.isEmpty()) throw CryptoError.damaged()
        val out = java.io.ByteArrayOutputStream(data.size)
        val k = SecretKeySpec(key, "AES")
        for ((i, r) in chunks.withIndex()) {
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, data, r.first, 12))
                c.updateAAD(aad(headerData, i, i == chunks.size - 1))
                out.write(c.doFinal(data, r.first + 12, r.last + 1 - r.first - 12))
            } catch (e: java.security.GeneralSecurityException) {
                throw if (i == 0) CryptoError.wrongKey() else CryptoError.damaged()
            }
        }
        return out.toByteArray()
    }
}

fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

/**
 * The agency's files on this device, and backup / restore (port of Backup/AgencyStore.swift, itself a port of
 * core/server/backup.py).
 *
 *   <root>/agency/agency.db (+ -wal, -shm)   live books (app-private storage)
 *   <root>/agency/uploads/, branding/          documents and logos imported from the desktop
 *   <root>/backups/backup-<kind>-<stamp>.azbackup   encrypted with the device key (see BackupCrypto)
 *
 * A backup's plaintext is exactly a desktop AirZelo backup (.zip: manifest.json, agency.db, uploads/, branding/),
 * so phone exports restore in the desktop app and desktop backups import on the phone.
 */
class AgencyStore(val root: File, private val deviceKey: ByteArray, private val factory: SqlDbFactory, val appVersion: String = APP_VERSION) {
    companion object {
        const val APP_VERSION = "0.3.0-android"
        private val SEALED_COLUMNS = listOf("customers" to "passport_no", "booking_passengers" to "passport_no")
    }

    val crypto = FieldCrypto(object : KeyProvider { private val k = SecretKeySpec(deviceKey, "AES"); override fun key() = k })
    var agency: Agency? = null
        private set

    val agencyDir get() = File(root, "agency")
    val dbFile get() = File(agencyDir, "agency.db")
    val backupDir get() = File(root, "backups")
    val exists get() = dbFile.exists()

    sealed class StoreError(message: String) : Exception(message) {
        class Corrupted(val why: String) : StoreError("The books on this device failed their integrity check ($why). Restore the latest backup.")
        class Verification(val problems: List<String>) : StoreError("Backup failed verification: " + problems.joinToString("; "))
    }

    /** Opens (or creates) the books and checks their integrity first. */
    fun open(): Agency {
        agency?.let { return it }
        agencyDir.mkdirs(); backupDir.mkdirs()
        if (exists) {
            val problem = quickCheck(dbFile)
            if (problem != "ok") throw StoreError.Corrupted(problem)
        }
        return Agency.open(factory, dbFile, crypto).also { agency = it }
    }

    fun close() {
        agency?.let { a -> runCatching { a.db.query("PRAGMA wal_checkpoint(TRUNCATE)") }; a.close() }
        agency = null
    }

    /** PRAGMA quick_check on a file without changing it ("ok" or the problem). */
    private fun quickCheck(file: File): String = try {
        val db = factory.open(file.path, readOnly = true)
        try {
            val r = db.one("PRAGMA quick_check")?.optStr("quick_check") ?: "unknown"
            if (r != "ok") r else { db.scalarLong("SELECT COUNT(*) FROM bookings"); "ok" }
        } finally { db.close() }
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    // ---- backups

    data class BackupInfo(val name: String, val kind: String, val size: Long, val created: Long)

    fun listBackups(): List<BackupInfo> {
        val names = backupDir.list()?.toList() ?: return emptyList()
        // backup-<kind>-<yyyyMMdd>-<HHmmss>[-n].azbackup; kinds can contain "-" (pre-restore)
        val pattern = Regex("""^backup-(.+?)-\d{8}-\d{6}(-\d+)?\.azbackup$""")
        return names.filter { it.startsWith("backup-") && it.endsWith(".azbackup") }.map { n ->
            val f = File(backupDir, n)
            BackupInfo(n, pattern.find(n)?.groupValues?.get(1) ?: "manual", f.length(), f.lastModified())
        }.sortedWith(compareByDescending<BackupInfo> { it.created }.thenByDescending { it.name })
    }

    private fun uniqueName(kind: String): String {
        val base = "backup-$kind-" + Dates.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var name = "$base.azbackup"; var n = 1
        while (File(backupDir, name).exists()) { n++; name = "$base-$n.azbackup" }
        return name
    }

    private fun tempDir(prefix: String): File = File(root, prefix + UUID.randomUUID()).also { it.mkdirs() }

    /** The desktop-format archive of the current books. [portable]: passport numbers decrypted (the archive is then
     *  encrypted with a passphrase, or handed to the desktop app). */
    fun archive(portable: Boolean, note: String = ""): ByteArray {
        val a = open()
        val tmp = tempDir("snap-")
        try {
            val snap = File(tmp, "agency.db")
            a.db.snapshotTo(snap)
            val s = factory.open(snap.path, readOnly = false)
            try {
                if (portable) {
                    s.exec("BEGIN")
                    for ((table, col) in SEALED_COLUMNS) {
                        for (r in s.query("SELECT id, $col FROM $table WHERE $col LIKE 'azenc1:%'")) {
                            s.run("UPDATE $table SET $col = ? WHERE id = ?", listOf(crypto.open(r.optStr(col), col), r.long("id")))
                        }
                    }
                    s.exec("COMMIT")
                }
                s.query("PRAGMA journal_mode = DELETE")
                if (portable) s.exec("VACUUM")  // the sealed values do not linger in free pages
            } finally { s.close() }
            val dbData = snap.readBytes()
            val files = linkedMapOf<String, JValue>("agency.db" to J(sha256Hex(dbData)))
            val zip = Zip.Writer()
            zip.add("agency.db", dbData)
            for (sub in listOf("uploads", "branding")) {
                val dir = File(agencyDir, sub)
                for (n in (dir.list()?.sorted() ?: emptyList())) {
                    val f = File(dir, n)
                    if (n.endsWith(".part") || !f.isFile) continue
                    val d = f.readBytes()
                    zip.add("$sub/$n", d)
                    files["$sub/$n"] = J(sha256Hex(d))
                }
            }
            val manifest = JValue.obj("app" to "AirZelo", "app_version" to appVersion, "schema_version" to Agency.schemaVersion,
                "kind" to (if (portable) "export" else "device"), "note" to note, "created_at" to Dates.nowISO(),
                "platform" to "android", "files" to JValue.Obj(files))
            zip.add("manifest.json", JValue.serialize(manifest).toByteArray())
            return zip.finish()
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** A backup kept on this device, encrypted with the device key. kind: manual | auto | pre-restore | pre-import | pre-recovery */
    fun createBackup(kind: String = "manual", note: String = ""): BackupInfo {
        backupDir.mkdirs()
        val sealed = BackupCrypto.seal(archive(portable = false, note = note), BackupCrypto.KeySource.Device(deviceKey), appVersion)
        val name = uniqueName(kind)
        val file = File(backupDir, name)
        val part = File(backupDir, "$name.part")
        part.outputStream().use { it.write(sealed); it.fd.sync() }
        if (!part.renameTo(file)) throw java.io.IOException("Could not save the backup")
        val check = verify(file.readBytes(), null)
        if (!check.ok) { file.delete(); throw StoreError.Verification(check.problems) }
        return listBackups().first { it.name == name }
    }

    /** A portable backup to share or keep elsewhere (another phone, the desktop app), protected by a passphrase. */
    fun exportBackup(passphrase: String, note: String = ""): ByteArray =
        BackupCrypto.seal(archive(portable = true, note = note), BackupCrypto.KeySource.Passphrase(passphrase), appVersion)

    /** Only automatic backups are pruned, oldest first. */
    fun pruneAutoBackups(keep: Int): List<String> =
        listBackups().filter { it.kind == "auto" }.drop(maxOf(keep, 1)).filter { File(backupDir, it.name).delete() }.map { it.name }

    /** Makes an automatic backup when the last one is older than the auto_backup_hours setting (0 = off). */
    fun autoBackupIfDue(nowMillis: Long = System.currentTimeMillis()): BackupInfo? {
        val a = open()
        // a backup interrupted earlier (app killed while writing) leaves only a .part file; it is never a usable backup
        backupDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val hours = a.setting("auto_backup_hours", "24")?.toLongOrNull() ?: 24
        if (hours <= 0) return null
        val last = listBackups().firstOrNull { it.kind == "auto" }
        if (last != null && nowMillis - last.created < hours * 3_600_000) return null
        val b = createBackup("auto")
        pruneAutoBackups(a.setting("backup_keep", "30")?.toIntOrNull() ?: 30)
        return b
    }

    // ---- verify / restore

    class Verification {
        val problems = ArrayList<String>()
        val ok get() = problems.isEmpty()
        var manifest: JValue = JValue.Null
        var zip: ByteArray? = null
        var needsPassphrase = false
    }

    /** Opens a phone backup (.azbackup) or a desktop backup (.zip) and checks it completely: archive structure,
     *  manifest, SHA-256 of every file, database integrity and schema version. */
    fun verify(data: ByteArray, passphrase: String?): Verification {
        val v = Verification()
        var zipData = data
        if (BackupCrypto.isContainer(data)) {
            try {
                val h = BackupCrypto.header(data)
                if (h["kdf"].string != "device" && passphrase == null) {
                    v.needsPassphrase = true; v.problems.add("Enter the backup passphrase"); return v
                }
                zipData = BackupCrypto.open(data, deviceKey, passphrase)
            } catch (e: BackupCrypto.CryptoError) {
                v.problems.add(e.message!!); return v
            }
        }
        val zip = try { Zip.Reader(zipData) } catch (e: Zip.ZipError) { v.problems.add(e.message!!); return v }
        for (n in zip.names) {
            if (n.startsWith("/") || n.startsWith("\\") || n.contains("\\") || n.contains(":") || n.split("/").contains("..")) v.problems.add("Unsafe path in archive: $n")
        }
        if ("manifest.json" !in zip.names) { v.problems.add("manifest.json missing"); return v }
        try { v.manifest = JValue.parse(String(zip.read("manifest.json"), Charsets.UTF_8)) } catch (e: Exception) { v.problems.add("manifest.json unreadable"); return v }
        if (v.manifest["app"].string !in listOf("AirZelo", "Travel Agency CRM")) v.problems.add("Not a backup made by this app")
        for ((name, digest) in v.manifest["files"].obj.orEmpty()) {
            if (name !in zip.names) { v.problems.add("Missing file: $name"); continue }
            try {
                if (sha256Hex(zip.read(name)) != digest.string) v.problems.add("Checksum mismatch: $name")
            } catch (e: Zip.ZipError) { v.problems.add("Corrupt entry: $name") }
        }
        if ("agency.db" in zip.names && v.problems.isEmpty()) {
            val tmp = File(root.also { it.mkdirs() }, "verify-${UUID.randomUUID()}.db")
            try {
                tmp.writeBytes(zip.read("agency.db"))
                val db = factory.open(tmp.path, readOnly = true)
                try {
                    val ic = db.one("PRAGMA integrity_check")?.optStr("integrity_check") ?: "?"
                    if (ic != "ok") v.problems.add("Database integrity check failed: $ic")
                    val fk = db.query("PRAGMA foreign_key_check")
                    if (fk.isNotEmpty()) v.problems.add("Foreign key problems: ${fk.size}")
                    db.scalarLong("SELECT COUNT(*) FROM bookings")
                    val ver = db.scalarLong("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")
                    if (ver > Agency.schemaVersion) v.problems.add("Made by a newer AirZelo (data version $ver); update this app first")
                    if (ver < Agency.schemaVersion) v.problems.add("Data version $ver is older than ${Agency.schemaVersion}: open and back it up in the current desktop app first")
                } finally { db.close() }
            } catch (e: Exception) {
                v.problems.add("Database unreadable: ${e.message}")
            } finally {
                for (s in listOf("", "-wal", "-shm", "-journal")) File(tmp.path + s).delete()
            }
        } else if ("agency.db" !in zip.names) {
            v.problems.add("agency.db missing")
        }
        v.zip = zipData
        return v
    }

    /**
     * Restores a backup (phone .azbackup or desktop .zip). Verifies it fully, saves a safety backup of the current
     * books, swaps the files in, re-encrypts passport numbers and checks the result; on any failure the previous
     * books are put back. Returns the safety backup (null when there were no books yet).
     */
    fun restore(data: ByteArray, passphrase: String? = null, kind: String = "pre-restore"): BackupInfo? {
        val v = verify(data, passphrase)
        val zipData = v.zip
        if (!v.ok || zipData == null) throw StoreError.Verification(v.problems)
        val zip = Zip.Reader(zipData)
        var safety: BackupInfo? = null
        if (exists) {
            if (agency == null && quickCheck(dbFile) != "ok") {
                // damaged books cannot be backed up; keep the files aside instead
                agencyDir.copyRecursively(File(root, "damaged-" + UUID.randomUUID().toString().take(8)))
            } else {
                safety = createBackup(kind, "Automatic safety copy before restoring")
            }
        }
        val staging = File(root, "restore-staging-" + UUID.randomUUID())
        staging.mkdirs()
        try {
            File(staging, "agency.db").writeBytes(zip.read("agency.db"))
            for (n in zip.names) {
                if (!(n.startsWith("uploads/") || n.startsWith("branding/logo-")) || n.endsWith("/")) continue
                val dest = File(staging, n)
                dest.parentFile?.mkdirs()
                dest.writeBytes(zip.read(n))
            }
            // passport numbers in desktop data are plain text: seal them with this device's key; sessions are not used on phones
            val s = factory.open(File(staging, "agency.db").path, readOnly = false)
            try {
                s.exec("BEGIN")
                for ((table, col) in SEALED_COLUMNS) {
                    for (r in s.query("SELECT id, $col FROM $table WHERE $col IS NOT NULL AND $col <> '' AND $col NOT LIKE 'azenc1:%'")) {
                        s.run("UPDATE $table SET $col = ? WHERE id = ?", listOf(crypto.seal(r.optStr(col), col), r.long("id")))
                    }
                }
                s.exec("DELETE FROM sessions")
                s.exec("COMMIT")
                s.exec("VACUUM")
            } finally { s.close() }
            close()
            val replaced = File(root, "agency-replaced-" + UUID.randomUUID())
            if (agencyDir.exists() && !agencyDir.renameTo(replaced)) throw java.io.IOException("Could not move the current books aside")
            try {
                if (!staging.renameTo(agencyDir)) throw java.io.IOException("Could not move the restored books into place")
                val check = quickCheck(dbFile)
                if (check != "ok") throw StoreError.Corrupted(check)
                open()
            } catch (e: Exception) {
                close()
                agencyDir.deleteRecursively()
                if (replaced.exists()) replaced.renameTo(agencyDir)
                throw e
            }
            replaced.deleteRecursively()  // its contents are inside the safety backup
            return safety
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Restores the newest backup on this device that passes verification (used after the books fail their check). */
    fun recoverFromLatestBackup(): BackupInfo {
        close()
        for (b in listBackups()) {
            val d = runCatching { File(backupDir, b.name).readBytes() }.getOrNull() ?: continue
            if (!verify(d, null).ok) continue
            restore(d, kind = "pre-recovery")
            return b
        }
        throw StoreError.Verification(listOf("No usable backup was found on this device"))
    }

    fun backupData(info: BackupInfo): ByteArray = File(backupDir, info.name).readBytes()
}
