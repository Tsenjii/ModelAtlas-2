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
import androidx.sqlite.db.SupportSQLiteDatabase

data class WorkspaceVaultCiphertext(
    val ref: String, val origin: String, val nonce: String,
    val ciphertext: String, val deleted: Boolean = false,
) {
    override fun toString(): String = "WorkspaceVaultCiphertext([protected])"
}

/** Raw credentials exist only in the secure input and this local HTTP credential layer. */
class AndroidCredentialVault(context: Context) : CredentialAccess {
    private val preferences = context.getSharedPreferences("credential_ciphertexts", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var syncDatabase: (() -> SupportSQLiteDatabase)? = null

    /** The closure is attached without opening Room on the UI thread. Ciphertext
     * can then commit in the same SQLite transaction as imported metadata. */
    @Synchronized
    fun attachSyncDatabase(database: () -> SupportSQLiteDatabase) { syncDatabase = database }

    @Synchronized
    fun sealForSync(id: String, origin: String, secret: String): WorkspaceVaultCiphertext {
        validateId(id)
        val bound = validatedOrigin(origin)
        require(secret.isNotBlank() && secret.length <= 8192 && '\r' !in secret && '\n' !in secret)
        val bytes = secret.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD("$id|$bound".toByteArray())
            }
            return WorkspaceVaultCiphertext(id, bound, Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP))
        } finally { bytes.fill(0) }
    }

    /** Fragment ciphertext has a separate authenticated domain from credentials.
     * Call outside Room transactions to preserve the vault -> database lock order. */
    @Synchronized
    fun sealWorkspaceChunk(id: String,origin: String,binding: String,bytes: ByteArray): WorkspaceVaultCiphertext {
        validateId(id);val bound=validatedOrigin(origin)
        require(binding.matches(Regex("[a-f0-9]{64}")) && bytes.isNotEmpty() && bytes.size<=256*1024)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE,key())
            updateAAD("workspace-fragment-v1|$id|$bound|$binding".toByteArray(Charsets.UTF_8))
        }
        return WorkspaceVaultCiphertext(id,bound,Base64.encodeToString(cipher.iv,Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(bytes),Base64.NO_WRAP))
    }
    @Synchronized
    fun openWorkspaceChunk(value: WorkspaceVaultCiphertext,binding: String): ByteArray {
        validateId(value.ref);val bound=validatedOrigin(value.origin)
        require(!value.deleted && binding.matches(Regex("[a-f0-9]{64}")))
        val nonce=Base64.decode(value.nonce,Base64.NO_WRAP);val encrypted=Base64.decode(value.ciphertext,Base64.NO_WRAP)
        require(nonce.size==12 && encrypted.size in 17..(256*1024+16))
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,nonce))
            updateAAD("workspace-fragment-v1|${value.ref}|$bound|$binding".toByteArray(Charsets.UTF_8))
            doFinal(encrypted)
        }
    }

    fun putSyncedCiphertext(db: SupportSQLiteDatabase, sealed: WorkspaceVaultCiphertext) {
        validateId(sealed.ref); validatedOrigin(sealed.origin)
        val previous = databaseCiphertext(db, sealed.ref)
        check(previous == null || previous.origin == sealed.origin) { "Credential scope mismatch" }
        db.execSQL("INSERT OR REPLACE INTO workspace_sync_vault VALUES(?,?,?,?,?)",
            arrayOf(sealed.ref, sealed.origin, sealed.nonce, sealed.ciphertext, if (sealed.deleted) 1 else 0))
    }

    private fun databaseCiphertext(db: SupportSQLiteDatabase, id: String): WorkspaceVaultCiphertext? =
        db.query("SELECT origin,nonce,ciphertext,deleted FROM workspace_sync_vault WHERE ref=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null else WorkspaceVaultCiphertext(id, c.getString(0), c.getString(1),
                c.getString(2), c.getInt(3) != 0)
        }

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
        check(!has(id)) { "Credential already exists" }
        syncDatabase?.let { provider ->
            putSyncedCiphertext(provider(), sealForSync(id, boundOrigin, secret))
            return
        }
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
        syncDatabase?.let { provider ->
            databaseCiphertext(provider(), credentialId)?.let { sealed ->
                check(!sealed.deleted && sealed.origin == boundOrigin) { "Credential scope mismatch" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(sealed.nonce, Base64.NO_WRAP)))
                    updateAAD("$credentialId|$boundOrigin".toByteArray())
                }
                val bytes = cipher.doFinal(Base64.decode(sealed.ciphertext, Base64.NO_WRAP))
                return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
            }
        }
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
        syncDatabase?.let { provider -> databaseCiphertext(provider(), id)?.let { return !it.deleted } }
        return preferences.contains("$id.ciphertext") && preferences.contains("$id.iv") &&
            preferences.contains("$id.origin")
    }

    /** Scope metadata only; the secret remains in this secure layer. */
    @Synchronized
    fun originOf(id: String): String? {
        validateId(id)
        syncDatabase?.let { provider -> databaseCiphertext(provider(), id)?.let {
            return if (it.deleted) null else it.origin
        } }
        return preferences.getString("$id.origin", null)
    }

    /** 同步、冪等移除；失敗時保留可重試的明確錯誤。 */
    @Synchronized
    fun remove(id: String): Boolean {
        validateId(id)
        val existed = has(id)
        syncDatabase?.let { provider ->
            val db = provider()
            val previous = databaseCiphertext(db, id)
            val origin = previous?.origin ?: preferences.getString("$id.origin", null)
            if (origin != null) putSyncedCiphertext(db, WorkspaceVaultCiphertext(id, origin, "", "", true))
        }
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
