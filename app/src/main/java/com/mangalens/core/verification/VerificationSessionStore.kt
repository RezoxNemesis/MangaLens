package com.mangalens.core.verification

import android.content.Context
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class VerificationSession(
    val domain: String,
    val cookie: String,
    val userAgent: String
)

/**
 * Stores verification cookies and the matching user-agent encrypted at rest.
 *
 * Android Keystore protects the AES key; SharedPreferences stores only encrypted payloads.
 */
class VerificationSessionStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        "mangalens_verification_sessions",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "mangalens_verification_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val PREFIX = "session_"
    }

    @Synchronized
    fun save(domain: String, cookie: String, userAgent: String) {
        val normalizedDomain = normalizeDomain(domain)
        require(normalizedDomain.isNotEmpty()) { "A valid verification domain is required" }
        require(cookie.isNotBlank()) { "A verification cookie is required" }

        val payload = encodePayload(cookie, userAgent)
        preferences.edit()
            .putString(PREFIX + keyFor(normalizedDomain), encrypt(payload))
            .apply()
    }

    @Synchronized
    fun get(domain: String): VerificationSession? {
        val normalizedDomain = normalizeDomain(domain)
        if (normalizedDomain.isEmpty()) return null

        val encrypted = preferences.getString(PREFIX + keyFor(normalizedDomain), null)
            ?: return null

        return runCatching {
            val payload = decrypt(encrypted)
            val parts = payload.split('|', limit = 2)
            require(parts.size == 2)
            VerificationSession(
                domain = normalizedDomain,
                cookie = decode(parts[0]),
                userAgent = decode(parts[1])
            )
        }.getOrNull()
    }

    @Synchronized
    fun clear(domain: String) {
        val normalizedDomain = normalizeDomain(domain)
        if (normalizedDomain.isEmpty()) return
        preferences.edit()
            .remove(PREFIX + keyFor(normalizedDomain))
            .apply()
    }

    @Synchronized
    fun clearAll() {
        preferences.edit().clear().apply()
    }

    private fun normalizeDomain(value: String): String {
        val candidate = value.trim()
        if (candidate.isEmpty()) return ""

        return runCatching {
            val host = URI(candidate).host ?: candidate.substringBefore('/').substringBefore(':')
            host.lowercase().removePrefix("www.")
        }.getOrDefault(candidate.lowercase().removePrefix("www.").substringBefore('/'))
    }

    private fun keyFor(domain: String): String =
        Base64.encodeToString(
            domain.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )

    private fun encodePayload(cookie: String, userAgent: String): String =
        encode(cookie) + "|" + encode(userAgent)

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun encodeBytes(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance("AES", KEYSTORE_PROVIDER)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return encodeBytes(iv) + "." + encodeBytes(ciphertext)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
