package dev.modelatlas.app.data

/**
 * D-028 F1 bounded-retry policy machine (D-2026-10-04-028 strategy A +
 * D-2026-10-06-030 rulings D2-YES / D3-YES / SLICE-UNION).
 *
 * Per-VM-session x per-exact-[RefreshWorkScope] state machine. Pure Kotlin:
 * no Android/Room/clock — [classify] takes NO time argument (structural
 * no-TTL; J5 asserts the parameter does not exist).
 */
class MyModelMetadataRetryPolicy {

    /** Exact work scope. First five dims = VM BundleEnrichmentKey (VM:2851-2857). */
    data class RefreshWorkScope(
        val providerId: String,
        val providerModelId: String,
        val accountId: String?,
        val credentialId: String?,
        val group: String?,
        val endpointId: String?,
        val operation: String?,
    )

    /** Passport state per (kind-set, scope); slice-union applied by caller for quota kinds. */
    enum class PassportClass { ABSENT, FAILED_NO_GOOD, HAS_LAST_GOOD, UNKNOWN_READ }

    enum class AutoState {
        IDLE_UNTRIED, RESERVED_AUTO, CHARGED_RUNNING,
        AUTO_SPENT_SUCCEEDED, AUTO_SPENT_NO_GOOD, AUTO_SPENT_CANCELLED,
        AUTO_SKIPPED,
    }

    private val autoStates = mutableMapOf<RefreshWorkScope, AutoState>()

    /** T2: pure classification. [hasLastGood], [hasAnyHeadOrFact], [readOk] derive from passport reads; [legCapable] = a network leg would run. No clock param — EVER. */
    fun classify(
        hasLastGood: Boolean,
        hasAnyHeadOrFact: Boolean,
        readOk: Boolean,
        legCapable: Boolean,
    ): PassportClass = when {
        !readOk -> PassportClass.UNKNOWN_READ
        hasLastGood -> PassportClass.HAS_LAST_GOOD
        hasAnyHeadOrFact -> PassportClass.FAILED_NO_GOOD
        !legCapable -> PassportClass.ABSENT // caller maps to SKIPPED_UNCAPABLE; kept ABSENT here
        else -> PassportClass.ABSENT
    }

    /** T5: atomic claim. Returns true iff reservation taken (IDLE_UNTRIED -> RESERVED_AUTO). */
    @Synchronized
    fun reserve(w: RefreshWorkScope): Boolean {
        if ((autoStates[w] ?: AutoState.IDLE_UNTRIED) != AutoState.IDLE_UNTRIED) return false
        autoStates[w] = AutoState.RESERVED_AUTO
        return true
    }

    /** T7: start. D2-YES — charge committed at provider-lock entry, before first network-capable call. Only legal from RESERVED_AUTO. */
    @Synchronized
    fun onStart(w: RefreshWorkScope): Boolean {
        if (autoStates[w] != AutoState.RESERVED_AUTO) return false
        autoStates[w] = AutoState.CHARGED_RUNNING
        return true
    }

    /** T11: queued-unstarted cancel. Only legal pre-start; releases to IDLE_UNTRIED, budget intact. */
    @Synchronized
    fun onQueuedCancel(w: RefreshWorkScope): Boolean {
        if (autoStates[w] != AutoState.RESERVED_AUTO) return false
        autoStates.remove(w) // == IDLE_UNTRIED
        return true
    }

    /**
     * T8/T9/T10: terminal. D2-YES — [zeroHttp] is IGNORED for budget purposes:
     * a started attempt always lands AUTO_SPENT (SUCCEEDED iff [anyGoodRow],
     * else CANCELLED iff [cancelledAfterStart], else NO_GOOD). No transport
     * signal consumed (P-PORT2 purity).
     */
    @Synchronized
    fun onTerminal(w: RefreshWorkScope, anyGoodRow: Boolean, cancelledAfterStart: Boolean, zeroHttp: Boolean = false) {
        if (autoStates[w] != AutoState.CHARGED_RUNNING) return
        autoStates[w] = when {
            anyGoodRow -> AutoState.AUTO_SPENT_SUCCEEDED
            cancelledAfterStart -> AutoState.AUTO_SPENT_CANCELLED
            else -> AutoState.AUTO_SPENT_NO_GOOD
        }
    }

    /**
     * T13: explicit user press. D3-YES — budgets independent: NEVER touches
     * auto state (J6 asserts). Per-press user budget is tracked by the caller
     * (VM seam), not here; this hook exists so the separation is explicit and
     * reviewable. No-op by design.
     */
    fun onUserExplicit(@Suppress("UNUSED_PARAMETER") w: RefreshWorkScope) {
        // Intentional no-op: user presses must not mutate auto state.
    }

    @Synchronized
    fun autoStateOf(w: RefreshWorkScope): AutoState = autoStates[w] ?: AutoState.IDLE_UNTRIED
}

/** D-028 F1 refresh origin. SESSION_AUTO = bounded session pass; USER_EXPLICIT = per-press Retry. */
enum class RefreshOrigin { SESSION_AUTO, USER_EXPLICIT }

/** Terminal-ish outcome per (pass, W). Returned by requestMyModelRefresh AND carried by Evaluated/Terminal. */
enum class RefreshOutcome {
    SKIPPED_NOT_MEMBER,
    SKIPPED_HAS_LAST_GOOD,
    SKIPPED_UNKNOWN_READ,
    SKIPPED_UNCAPABLE,
    JOINED_EXISTING_FLIGHT,
    DISPATCHED,
    SUCCEEDED,
    FAILED_NO_GOOD,
    CANCELLED_AFTER_START,
    SUPPRESSED_BUDGET,
    SUPPRESSED_ALREADY_RUNNING,
    FAILED_INPUT,
}

fun interface RefreshAttemptObserver {
    fun onEvent(e: RefreshAttemptEvent)
}

sealed interface RefreshAttemptEvent {
    val w: MyModelMetadataRetryPolicy.RefreshWorkScope
    val origin: RefreshOrigin

    /** Exactly one per (pass, W), emitted AFTER that W's decision is final (incl. Terminal). O1. */
    data class Evaluated(
        override val w: MyModelMetadataRetryPolicy.RefreshWorkScope,
        override val origin: RefreshOrigin,
        val outcome: RefreshOutcome,
    ) : RefreshAttemptEvent

    /** Claim taken, before provider lock. O2-first. */
    data class Reserved(
        override val w: MyModelMetadataRetryPolicy.RefreshWorkScope,
        override val origin: RefreshOrigin,
    ) : RefreshAttemptEvent

    /** Provider lock entered, BEFORE first network-capable call. Charge point (D2-YES, T7). O2. */
    data class ChargedAtStart(
        override val w: MyModelMetadataRetryPolicy.RefreshWorkScope,
        override val origin: RefreshOrigin,
    ) : RefreshAttemptEvent

    /** Follower only — never Reserved/ChargedAtStart/ReleasedNoCharge/Terminal. O3. */
    data class Joined(
        override val w: MyModelMetadataRetryPolicy.RefreshWorkScope,
        override val origin: RefreshOrigin,
    ) : RefreshAttemptEvent

    /** Queued-unstarted cancel (T11). Budget intact. */
    data class ReleasedNoCharge(
        override val w: MyModelMetadataRetryPolicy.RefreshWorkScope,
        override val origin: RefreshOrigin,
    ) : RefreshAttemptEvent

    data class Terminal(
        override val w: MyModelMetadataRetryPolicy.RefreshWorkScope,
        override val origin: RefreshOrigin,
        val outcome: RefreshOutcome,
    ) : RefreshAttemptEvent
}

/**
 * D-028 F1 V1 passport point-read (same `data` package; plain data holder).
 * Production default reader builds it from DAO — `facts:128/136/155` heads +
 * `facts:149` slice heads (slice-union) + `perffacts:130/133/136` — and tests
 * substitute via the `passportReader` port (P-UNK).
 */
data class PassportSnapshot(
    val hasLastGood: Boolean,      // any kind+scope head with lastGood id (any age)
    val hasAnyHeadOrFact: Boolean, // any head or fact row for kind+scope (incl. slice union)
    val readOk: Boolean,           // false iff DAO read threw (UNKNOWN_READ)
    val legCapable: Boolean,       // a network leg would run (evidence gate + injected ports)
)
