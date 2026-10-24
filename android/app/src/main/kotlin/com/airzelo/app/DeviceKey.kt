package com.airzelo.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device key for passport-number encryption and device backups.
 *
 * A 32-byte master key is generated once and stored only wrapped (AES-256-GCM) by a key that lives in the Android
 * Keystore and never leaves it (hardware-backed where the phone supports it). The engine needs the raw master key
 * because device backups derive their own key from it with HKDF, exactly as the iPhone app does. The app itself is
 * gated by BiometricPrompt / the device credential (see MainActivity), so the Keystore key does not require
 * per-use authentication.
 */
object DeviceKey {
    private const val ALIAS = "airzelo-master-wrap-v1"
    private const val FILE = "master-key.bin"

    fun loadOrCreate(dir: File): ByteArray {
        dir.mkdirs()
        val file = File(dir, FILE)
        val wrap = wrappingKey()
        if (file.exists()) {
            val blob = file.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
        val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrap)
        val blob = c.iv + c.doFinal(master)
        val part = File(dir, "$FILE.part")
        part.outputStream().use { it.write(blob); it.fd.sync() }
        if (!part.renameTo(file)) throw IllegalStateException("Could not save the device key")
        return master
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return gen.generateKey()
    }
}
