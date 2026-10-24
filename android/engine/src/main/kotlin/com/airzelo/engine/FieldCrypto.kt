package com.airzelo.engine

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the 256-bit AES key comes from: the Android Keystore in the app (the key never leaves secure
 * hardware), an in-memory key in tests.
 */
interface KeyProvider {
    fun key(): SecretKey

    companion object {
        fun ephemeral(): KeyProvider {
            val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return object : KeyProvider { override fun key() = k }
        }
    }
}

/**
 * Encrypts sensitive fields (passport numbers) before they are written to the database.
 * Format: "azenc1:" + base64(nonce(12) | ciphertext | tag(16)), AES-256-GCM with the column name as
 * associated data - the same layout as the iPhone app. Values without the prefix (desktop imports before
 * re-encryption) are read as plain text; values sealed with another device's key are kept unchanged.
 */
class FieldCrypto(private val keys: KeyProvider) {
    companion object {
        const val PREFIX = "azenc1:"
        private val random = SecureRandom()
        fun isSealed(s: String?) = s?.startsWith(PREFIX) == true
        /** Text for screens. */
        fun display(s: String?): String = when {
            s == null -> ""
            isSealed(s) -> "Encrypted on another device"
            else -> s
        }
        fun ephemeral() = FieldCrypto(KeyProvider.ephemeral())
    }

    fun seal(plain: String?, column: String): String? {
        if (plain.isNullOrEmpty() || isSealed(plain)) return plain
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keys.key(), GCMParameterSpec(128, nonce))
        c.updateAAD(column.toByteArray())
        val ct = c.doFinal(plain.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(nonce + ct)
    }

    fun open(stored: String?, column: String): String? {
        if (stored == null || !isSealed(stored)) return stored
        return try {
            val raw = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(128, raw, 0, 12))
            c.updateAAD(column.toByteArray())
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) {
            stored  // sealed with another device's key: kept as is, so saving or exporting never loses it
        }
    }
}
