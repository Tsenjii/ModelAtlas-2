package dev.modelatlas.app.data

/**
 * X-014 production [EventSink]：把管線判出的事件 append-only 轉發到 [EventStore]。
 *
 * 本類不做判定、不抓資料、不碰網路（判定在 [EventPipeline]）；只負責把「已成立的事件」
 * 落到既有事件表，因此不新增 schema、不新增表。
 *
 * 鐵律（與 [EventStore]／[EventPipeline] 同源，不得放寬）：
 * - X-006 sync failure 保留 last-good：本類不吞掉任何例外，也不在失敗時補寫事件；
 *   [EventStore] 自行對 `success=false` 回 null（append-only 語意下即「本次不寫」）。
 * - 單次失敗不產 OUTAGE：OUTAGE 的 [EventStore.recordProviderHealth] 需
 *   `decided ≥ [EventStore.MIN_DECISIVE_FOR_OUTAGE]` 且成功率 < [EventStore.OUTAGE_SUCCESS_RATE_BELOW]。
 *   [EventStore.isOutageEvidence] 為 EventStore 實例方法（非 companion），故在此以
 *   [outageDecidedFor]／[outageSuccessesFor] 這組純函式提供同一組證據值
 *   （decided=2、successes=0 → 0/2 = 0.0 < 0.5），可 JVM 單測。
 * - X-003 secret boundary：事件欄位僅帶 scopeKey／source／evidenceId／detail，
 *   本類不引入任何 key／PAT／raw body 欄位。
 * - X-005 provenance 分離：source 原樣轉發，不升級不冒充。
 */
class StoreEventSink(private val eventStore: EventStore) : EventSink {

    override suspend fun writePriceEvent(
        eventId: String,
        scopeKey: String,
        fromInput: String?,
        fromOutput: String?,
        toInput: String?,
        toOutput: String?,
        observedAt: Long,
        source: String,
        evidenceId: String?,
        fromFixed: String?,
        toFixed: String?,
    ) {
        // X-001 Unknown≠0：null 欄位原樣傳遞，不補 0、不補 FREE 冒充已知。
        // D-FIXED-1／F1 修復：fixed 腿不持久化（price_events 無 fixed 欄），
        // 但轉發給 store 的 gates——確保 pipeline 通過的 fixed-only 轉換在
        // store 層同樣寫列（「有通知必有列」append-only 不變量）。
        eventStore.recordPriceTransition(
            scopeKey = scopeKey,
            fromInput = fromInput,
            fromOutput = fromOutput,
            toInput = toInput,
            toOutput = toOutput,
            observedAt = observedAt,
            source = source,
            evidenceId = evidenceId,
            eventId = eventId,
            fromFixed = fromFixed,
            toFixed = toFixed,
        )
    }

    override suspend fun writeProviderEvent(
        eventId: String,
        scopeKey: String,
        kind: String,
        observedAt: Long,
        source: String,
        evidenceId: String?,
        detail: String?,
    ) {
        // scopeKey 於健康事件即 providerId（provider 維度健康，不是 pricing scope）。
        // OUTAGE 才帶失敗證據；RECOVERY／NOTICE 不做樣本門檻（決定性證據由呼叫端持有）。
        eventStore.recordProviderHealth(
            providerId = scopeKey,
            kind = kind,
            observedAt = observedAt,
            source = source,
            evidenceId = evidenceId,
            detail = detail,
            decided = outageDecidedFor(kind),
            successes = outageSuccessesFor(kind),
            eventId = eventId,
        )
    }

    override suspend fun writeQuotaEvent(
        eventId: String,
        scopeKey: String,
        fromState: String?,
        toState: String?,
        observedAt: Long,
        source: String,
        evidenceId: String?,
    ) {
        // X-001 Unknown≠0：from/to state 原樣傳遞，不合成、不補值；
        // X-005 provenance 分離：source 原樣轉發，不升級不冒充。
        eventStore.recordQuotaTransition(
            scopeKey = scopeKey,
            fromState = fromState,
            toState = toState,
            observedAt = observedAt,
            source = source,
            evidenceId = evidenceId,
            eventId = eventId,
        )
    }

    companion object {
        /**
         * OUTAGE 的已判定樣本數證據：只有 OUTAGE 需要，且必為
         * [EventStore.MIN_DECISIVE_FOR_OUTAGE]（單次失敗永不產 OUTAGE）；
         * 其餘 kind 一律 0（不帶樣本門檻）。
         */
        internal fun outageDecidedFor(kind: String): Int =
            if (kind == EventStore.PROVIDER_EVENT_OUTAGE) EventStore.MIN_DECISIVE_FOR_OUTAGE else 0

        /**
         * OUTAGE 的成功樣本數證據：OUTAGE 恆為 0（0/decided = 0.0 低於 OFFLINE 級門檻）；
         * 其餘 kind 一律 0（不帶樣本門檻）。
         */
        internal fun outageSuccessesFor(kind: String): Int = OUTAGE_SUCCESSES

        /** OUTAGE 成功樣本數證據值：0（0/decided 遠低於 OFFLINE 級門檻）。 */
        private const val OUTAGE_SUCCESSES = 0
    }
}