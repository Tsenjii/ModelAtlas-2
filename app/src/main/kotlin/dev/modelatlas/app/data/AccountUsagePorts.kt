package dev.modelatlas.app.data

import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiResult

/** Same-fetch safe metadata; never carries a response body, raw credential, or station user id. */
data class AccountQuotaObservation(
    val providerId: String,
    val accountId: String,
    val patRef: AccountPatRef,
    val validatedBaseUrl: String,
    val capabilities: NewApiResult<NewApiCapabilities>,
    val result: NewApiResult<NewApiAccountQuota>,
)

/**
 * NINT-005A: called once after quota commit/readback, under the quota sync mutex.
 * Implementations do no network and never re-enter NewApiSnapshotStore.sync methods.
 * Ordinary failure is best-effort; CancellationException always propagates.
 */
fun interface AccountQuotaObserver {
    suspend fun onObserved(observation: AccountQuotaObservation)
}

/** Additional persistence-boundary defense beyond AccountPatRef's existing length/control guards. */
internal fun requireSafeAccountUsageRef(ref: String) {
    require(ref.isNotBlank()) { "Credential ref must not be blank" }
    require(ref.length <= 256) { "Credential ref too long" }
    require(ref.none { it.code < 0x20 || it.code == 0x7f }) { "Credential ref must not contain control characters" }
    require(!ref.startsWith("sk-")) { "Credential ref must not carry secret" }
    require(ref.none { it.isWhitespace() }) { "Credential ref must not contain whitespace" }
}
