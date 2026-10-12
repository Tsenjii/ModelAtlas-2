package dev.modelatlas.app.data

import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiPerfModelSummary
import dev.modelatlas.core.newapi.NewApiPerfWindow
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport

/**
 * CR-009 DRAFT (planning only): typed per-safe-session capability passport.
 *
 * One passport per `syncNewApi` session (per bundle wave when driven by the VM):
 * carries the session's observed caps + identity dimensions exactly once, consumed
 * by the account/token legs (G1) and holding the G2 summary memo. The passport is
 * NOT evidence: it never becomes a `NewApiEvidence`, never writes a DB fact, never
 * claims certification, carries no clock/TTL, and never reads persisted `lastGood`.
 * Raw secrets never enter (safe `AccountPatRef` only).
 */
enum class NewApiSessionAuthRole { ACCOUNT_PAT, ANON }

/**
 * Certified-good remembered summary observation (ai8 `WaveSummary`): evidence plus
 * the model rows of that one observed success. Membership is recomputed per
 * requested model at consumption; metrics fields only, no secret.
 * Pending lock P-empty: certified+surface-valid INCLUDING empty `models: []`
 * is remembered (empty is an observation, `usable=false`, advances no lastGood;
 * a hit performs zero writes so no duplicate empty marker). If Main instead locks
 * "never remember empty", the oracle count for an N-member empty wave becomes
 * N markers, not 1.
 */
data class WaveSummary(
    val evidence: NewApiEvidence,
    val models: List<NewApiPerfModelSummary>,
) {
    fun memberGoodCount(providerModelId: String): Int =
        if (models.any { it.modelName == providerModelId }) models.size else 0
}

/**
 * Session passport: exact-match identity + one observed caps + optional summary memo.
 * `hours` is the NORMALIZED perf window (`NewApiPerfWindow.normalize`) of the wave
 * (bundle path seeds 168 via `ProviderPerfStore.DEFAULT_ENRICHMENT_HOURS`); any
 * consumption with a different normalized window is a memo miss (IV9).
 */
data class NewApiSessionPassport(
    val providerId: String,
    val baseUrl: String,
    val origin: String,
    val authRole: NewApiSessionAuthRole,
    val patRef: AccountPatRef?,
    val accountId: String?,
    val versionRaw: String?,
    val hours: Int,
    val caps: NewApiResult<NewApiCapabilities>,
    val summary: WaveSummary? = null,
) {
    companion object {
        /**
         * Creation gate: ONLY from a real observed caps Success at the session origin.
         * Returns null for Failure / uncertified / unversioned caps: legs then keep
         * their current fresh-caps + source-relabel paths (failure waves stay 3 status).
         * Cancel inside creation leaves nothing remembered (rethrow paths unchanged).
         */
        fun create(
            providerId: String,
            baseUrl: String,
            origin: String,
            accountId: String?,
            pat: AccountPatRef?,
            caps: NewApiResult<NewApiCapabilities>,
            hours: Int = NewApiPerfWindow.normalize(
                ProviderPerfStore.DEFAULT_ENRICHMENT_HOURS
            ),
        ): NewApiSessionPassport? {
            val success = caps as? NewApiResult.Success ?: return null
            // Literal mirrors NewApiSnapshotStore.persistPricing:228
            // (`SOURCE_STATUS` is a private companion const; no cert-map touch).
            if (success.evidence.source != "newapi/api/status") return null
            if (success.evidence.httpStatus == null) return null
            val version: NewApiVersion = success.value.version
            if (version.support != NewApiVersionSupport.CERTIFIED_RC25) return null
            val raw: String = version.raw?.takeIf { it.isNotBlank() } ?: return null
            if (raw != NewApiVersion.RC25) return null
            return NewApiSessionPassport(
                providerId = providerId,
                baseUrl = baseUrl,
                origin = origin,
                authRole = if (pat != null) NewApiSessionAuthRole.ACCOUNT_PAT
                    else NewApiSessionAuthRole.ANON,
                patRef = pat,
                accountId = accountId,
                versionRaw = raw,
                hours = hours,
                caps = caps,
            )
        }
    }

    /**
     * Exact-match gate (IV1/IV2/IV5/IV6/IV8/IV9): provider, exact base string,
     * origin, auth role, safe PAT ref, named-vs-null account, certified version raw,
     * normalized window. Any mismatch = miss (caller fetches fresh; cross-account
     * sharing stays impossible). Revocation/role mutation stays fail-closed via the
     * per-leg `validatePat`/`validateRole` re-checks (IV7), which run BEFORE network.
     */
    fun matchesLeg(
        providerId: String,
        baseUrl: String,
        origin: String,
        authRole: NewApiSessionAuthRole,
        pat: AccountPatRef?,
        accountId: String?,
        hours: Int,
    ): Boolean =
        this.providerId == providerId &&
            this.baseUrl == baseUrl &&
            this.origin == origin &&
            this.authRole == authRole &&
            this.patRef?.value == pat?.value &&
            this.accountId == accountId &&
            this.hours == hours

    /** Memo-hit gate: leg match plus passport caps version still the certified raw. */
    fun matchesSummary(
        providerId: String,
        baseUrl: String,
        origin: String,
        authRole: NewApiSessionAuthRole,
        pat: AccountPatRef?,
        accountId: String?,
        hours: Int,
        versionRaw: String,
    ): Boolean = summary != null &&
        matchesLeg(providerId, baseUrl, origin, authRole, pat, accountId, hours) &&
        this.versionRaw == versionRaw
}
