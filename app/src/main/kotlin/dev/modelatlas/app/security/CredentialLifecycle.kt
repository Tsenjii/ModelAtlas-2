package dev.modelatlas.app.security

import dev.modelatlas.app.data.AtlasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room 與 Keystore 無法共用交易：先停用，再移除 secret，最後移除 metadata。
 * 中途失敗留下停用的資料，可重試；不讓已刪 secret 的憑證重新啟用。
 * 呼叫端先取消並等待使用此 credential 的 inference，避免在途請求競態。
 */
class CredentialLifecycle(
    private val repository: AtlasRepository,
    private val vault: AndroidCredentialVault,
) {
    private val mutex = Mutex()

    suspend fun setEnabled(safeCredentialId: String, enabled: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            check(!enabled || vault.has(safeCredentialId)) { "Credential unavailable" }
            repository.setCredentialEnabled(safeCredentialId, enabled)
        }
    }

    suspend fun delete(safeCredentialId: String) = mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            if (repository.hasCredential(safeCredentialId)) {
                repository.setCredentialEnabled(safeCredentialId, false)
            }
            vault.remove(safeCredentialId)
            repository.deleteCredentialMetadata(safeCredentialId)
        }
    }
}
