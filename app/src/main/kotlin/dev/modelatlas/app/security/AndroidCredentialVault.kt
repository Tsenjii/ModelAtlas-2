package dev.modelatlas.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.network.EndpointPolicy
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Raw credentials exist only in the secure input and this local HTTP credential layer. */
class AndroidCredentialVault(context: Context) : CredentialAccess {
    private val preferences = context.getSharedPreferences("credential_ciphertexts", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    /** origin is a normalized HTTPS origin; references cannot be silently rebound. */
    @Synchronized
    fun store(id: String, origin: String, secret: String) {
        validateId(id)
        val boundOrigin = validatedOrigin(origin)
        require(secret.isNotBlank() && secret.length <= 8192 && '\r' !in secret && '\n' !in secret)
        check(!preferences.contains("$id.ciphertext")) { "Credential already exists" }
        val bytes = secret.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD("$id|$boundOrigin".toByteArray())
            }
            val encrypted = cipher.doFinal(bytes)
            check(preferences.edit()
                .putString("$id.origin", boundOrigin)
                .putString("$id.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("$id.ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()) { "Credential persistence failed" }
        } finally { bytes.fill(0) }
    }

    @Synchronized
    override fun bearerToken(credentialId: String, origin: String): String {
        validateId(credentialId)
        val boundOrigin = validatedOrigin(origin)
        check(preferences.getString("$credentialId.origin", null) == boundOrigin) { "Credential scope mismatch" }
        val iv = preferences.getString("$credentialId.iv", null) ?: error("Credential unavailable")
        val encoded = preferences.getString("$credentialId.ciphertext", null) ?: error("Credential unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            updateAAD("$credentialId|$boundOrigin".toByteArray())
        }
        val bytes = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    @Synchronized
    fun has(id: String): Boolean {
        validateId(id)
        return preferences.contains("$id.ciphertext") && preferences.contains("$id.iv") &&
            preferences.contains("$id.origin")
    }

    /** 同步、冪等移除；失敗時保留可重試的明確錯誤。 */
    @Synchronized
    fun remove(id: String): Boolean {
        validateId(id)
        val existed = preferences.contains("$id.ciphertext")
        check(preferences.edit().remove("$id.origin").remove("$id.iv").remove("$id.ciphertext").commit()) {
            "Credential removal failed"
        }
        return existed
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Invalid credential reference" }
    }

    private fun validatedOrigin(origin: String): String {
        val url = EndpointPolicy.validate(origin)
        require(url.encodedPath == "/") { "Origin must not contain a path" }
        return EndpointPolicy.origin(url)
    }

    private companion object { const val ALIAS = "modelatlas.credentials.v1" }
}
