package dev.modelatlas.app.data

/**
 * X-014 事件偵測與投遞管線（TRACEABILITY X-014／PRODUCT_SPEC §10
 * `Raw Evidence → … → Snapshot → Event`／§16 rule 清單上游）。
 *
 * 管線三段：
 *  1. 觸發掃描：[scanPriceObservation]／[scanProviderHealth] 接收真實觀測
 *     （snapshot_facts 的 price/quota 轉換、samples 的健康樣本），以純函式判定
 *     是否構成事件。事件來源必須來自真實觀測，本類不做網路、不另抓資料。
 *  2. 寫事件：判定成立時經 [EventSink]（production 實作為 [EventStore]）append-only 寫入。
 *  3. 投遞回調＋重試上限：寫入成功後經 [DeliverySink] 投遞；失敗時以同一事件重試，
 *     至多 [MAX_DELIVERY_ATTEMPTS] 次，超過上限記為「未投遞」（不假裝已送、不 crash）。
 *
 * 鐵律（BACKLOG X-014＋全域 invariants）：
 * - X-001 Unknown≠0／Free：新舊任一側完全未知不產價格轉換事件；payload 欄位 null 保持 null。
 * - X-006 sync failure 保留 last-good：觀測失敗（[Observation.success]=false）一律不掃描、
 *   不寫事件、不投遞（否則會記錄不存在的狀態變化）。
 * - X-005 provenance 分離：[Observation.source] 原樣攜帶至事件，不升級不冒充。
 * - 單次失敗不產 OUTAGE：健康樣本需 [MIN_CONSECUTIVE_FAILURES] 次連續失敗才判 OUTAGE；
 *   RECOVERY／NOTICE 不做失敗門檻。管線以「連續失敗計數」表達，由呼叫端餵入。
 * - X-003 secret boundary：[Observation.detail]／[Observation.ref] 原樣為呼叫端已截斷的
 *   非敏感摘要／ref；本類不引入任何 key／PAT／raw body 欄位。
 * - FREE_NOW 等免費狀態轉換一樣走完整掃描→寫事件→投遞（不因免費跳過 telemetry/事件）。
 */
class EventPipeline(
    private val sink: EventSink,
    private val delivery: DeliverySink,
) {

    /** 一次真實觀測（來源：snapshot_facts／samples／CheckinDao 等，由呼叫端組裝）。 */
    data class Observation(
        val scopeKey: String,
        val source: String,
        val observedAt: Long,
        val success: Boolean = true,
        val evidenceId: String? = null,
        val detail: String? = null,
        val ref: String? = null,
    )

    /** 價格觀測：新舊任一側欄位可為 null（UNKNOWN），null 原樣傳遞。
     * fixed 腿（D-FIXED-1）僅參與管線已知側／相等閘，不持久化（price_events 無 fixed 欄）。 */
    data class PriceObservation(
        val base: Observation,
        val fromInput: String?,
        val fromOutput: String?,
        val toInput: String?,
        val toOutput: String?,
        val fromFixed: String? = null,
        val toFixed: String? = null,
    )

    /** 健康觀測：連續失敗計數由呼叫端依 samples 維護，1＝單次失敗。 */
    data class HealthObservation(
        val base: Observation,
        val consecutiveFailures: Int,
    )

    /** 配額觀測：新舊任一側 state 可為 null（UNKNOWN），null 原樣傳遞。 */
    data class QuotaObservation(
        val base: Observation,
        val fromState: String?,
        val toState: String?,
    )

    /** 掃描＋寫入＋投遞的結果（供呼叫端記 telemetry／UI 呈現，不夾帶 raw 資料）。 */
    data class PipelineResult(
        val written: Boolean,
        val delivered: Boolean,
        val deliveryAttempts: Int,
        val reason: String? = null,
    )

    /**
     * 掃描一次價格觀測：新舊兩側皆有已知值且不相等才寫事件並投遞；
     * 觀測失敗／任一側未知／無轉換皆回 [PipelineResult.written]=false。
     * fixed 腿（D-FIXED-1）僅參與已知側／相等閘，不寫入持久列。
     */
    suspend fun scanPrice(observation: PriceObservation): PipelineResult {
        val base = observation.base
        if (!base.success) {
            return PipelineResult(false, false, 0, REASON_SYNC_FAILURE)
        }
        val fromKnown = isPriceSideKnown(observation.fromInput, observation.fromOutput, observation.fromFixed)
        val toKnown = isPriceSideKnown(observation.toInput, observation.toOutput, observation.toFixed)
        if (!fromKnown || !toKnown) {
            return PipelineResult(false, false, 0, REASON_UNKNOWN_SIDE)
        }
        if (observation.fromInput == observation.toInput &&
            observation.fromOutput == observation.toOutput &&
            observation.fromFixed == observation.toFixed
        ) {
            return PipelineResult(false, false, 0, REASON_NO_TRANSITION)
        }
        val eventId = EventStore.newEventId(EVENT_PREFIX_PRICE)
        sink.writePriceEvent(
            eventId = eventId,
            scopeKey = base.scopeKey,
            fromInput = observation.fromInput,
            fromOutput = observation.fromOutput,
            toInput = observation.toInput,
            toOutput = observation.toOutput,
            fromFixed = observation.fromFixed,
            toFixed = observation.toFixed,
            observedAt = base.observedAt,
            source = base.source,
            evidenceId = base.evidenceId,
        )
        return deliver(eventId, base)
    }

    /**
     * 掃描一次配額觀測：新舊兩側皆為已知 state 且不相等才寫事件並投遞；
     * 觀測失敗／任一側未知（null 或字串 "UNKNOWN"）／無轉換皆回 [PipelineResult.written]=false。
     * X-001：quota ladders 以 enum name 字串持久化，UNKNOWN 側以字串形式出現亦視為未知。
     */
    suspend fun scanQuota(observation: QuotaObservation): PipelineResult {
        val base = observation.base
        if (!base.success) {
            return PipelineResult(false, false, 0, REASON_SYNC_FAILURE)
        }
        if (!isQuotaSideKnown(observation.fromState) || !isQuotaSideKnown(observation.toState)) {
            return PipelineResult(false, false, 0, REASON_UNKNOWN_SIDE)
        }
        if (observation.fromState == observation.toState) {
            return PipelineResult(false, false, 0, REASON_NO_TRANSITION)
        }
        val eventId = EventStore.newEventId(EVENT_PREFIX_QUOTA)
        sink.writeQuotaEvent(
            eventId = eventId,
            scopeKey = base.scopeKey,
            fromState = observation.fromState,
            toState = observation.toState,
            observedAt = base.observedAt,
            source = base.source,
            evidenceId = base.evidenceId,
        )
        return deliver(eventId, base)
    }

    /**
     * 掃描一次健康觀測：連續失敗達 [MIN_CONSECUTIVE_FAILURES] 判 OUTAGE；
     * 成功樣本在先前有 OUTAGE 待恢復時判 RECOVERY（[wasOutaged]）；
     * 單次失敗（X-006 同源語意）與無變化的成功皆不產事件。
     * NOTICE 不經本掃描（公告由呼叫端直接經 [EventSink]／notice 通道寫入）。
     */
    suspend fun scanHealth(observation: HealthObservation, wasOutaged: Boolean): PipelineResult {
        val base = observation.base
        if (!base.success) {
            return PipelineResult(false, false, 0, REASON_SYNC_FAILURE)
        }
        val kind = when {
            observation.consecutiveFailures >= MIN_CONSECUTIVE_FAILURES ->
                EventStore.PROVIDER_EVENT_OUTAGE
            observation.consecutiveFailures == 0 && wasOutaged ->
                EventStore.PROVIDER_EVENT_RECOVERY
            else -> null
        } ?: return PipelineResult(false, false, 0, REASON_NO_TRANSITION)
        val eventId = EventStore.newEventId(EVENT_PREFIX_PROVIDER)
        sink.writeProviderEvent(
            eventId = eventId,
            scopeKey = base.scopeKey,
            kind = kind,
            observedAt = base.observedAt,
            source = base.source,
            evidenceId = base.evidenceId,
            detail = base.detail,
        )
        return deliver(eventId, base)
    }

    /**
     * 投遞回調＋重試上限：至多 [MAX_DELIVERY_ATTEMPTS] 次嘗試；每次失敗即重試同一事件，
     * 超過上限停止並標記未投遞（永不拋出、永不假裝已送）。成功即停，不重複投遞。
     */
    private suspend fun deliver(eventId: String, base: Observation): PipelineResult {
        var attempts = 0
        var delivered = false
        while (attempts < MAX_DELIVERY_ATTEMPTS) {
            attempts++
            if (delivery.deliver(eventId, base.scopeKey, base.source, base.observedAt)) {
                delivered = true
                break
            }
        }
        if (!delivered) delivery.markUndelivered(eventId)
        return PipelineResult(
            written = true,
            delivered = delivered,
            deliveryAttempts = attempts,
            reason = if (delivered) null else REASON_DELIVERY_EXHAUSTED,
        )
    }

    /** 價格單側已知判定：input／output／fixed 任一非 null 即已知（X-001：全 null＝UNKNOWN；D-FIXED-1 加 fixed 腿）。 */
    fun isPriceSideKnown(input: String?, output: String?, fixed: String? = null): Boolean =
        input != null || output != null || fixed != null

    /** 配額單側已知判定：null 或字串 "UNKNOWN" 皆為未知（X-001；quota ladders 以 enum name 字串持久化）。 */
    fun isQuotaSideKnown(state: String?): Boolean =
        state != null && state != QUOTA_STATE_UNKNOWN

    companion object {
        /**
         * 投遞重試上限（named const）：超過即放棄並標記未投遞，避免無界重試。
         */
        const val MAX_DELIVERY_ATTEMPTS = 3

        /**
         * OUTAGE 最小連續失敗次數（對標 [EventStore.MIN_DECISIVE_FOR_OUTAGE]：
         * 單次失敗不產 OUTAGE，2 為「連續失敗」最小證據）。
         */
        const val MIN_CONSECUTIVE_FAILURES = 2

        const val EVENT_PREFIX_PRICE = "price"
        const val EVENT_PREFIX_PROVIDER = "provider"
        const val EVENT_PREFIX_QUOTA = "quota"

        /** 配額未知側的字串形式（quota ladders 以 enum name 字串持久化，見 spec §1.1／§2.2）。 */
        const val QUOTA_STATE_UNKNOWN = "UNKNOWN"

        const val REASON_SYNC_FAILURE = "SYNC_FAILURE"
        const val REASON_UNKNOWN_SIDE = "UNKNOWN_SIDE"
        const val REASON_NO_TRANSITION = "NO_TRANSITION"
        const val REASON_DELIVERY_EXHAUSTED = "DELIVERY_EXHAUSTED"
    }
}

/** 寫事件抽象（production 實作轉發 [EventStore]；append-only，由實作保證）。
 * fixed 腿（D-FIXED-1）僅供管線閘用，不持久化（price_events 無 fixed 欄）。 */
interface EventSink {
    suspend fun writePriceEvent(
        eventId: String,
        scopeKey: String,
        fromInput: String?,
        fromOutput: String?,
        toInput: String?,
        toOutput: String?,
        observedAt: Long,
        source: String,
        evidenceId: String?,
        fromFixed: String? = null,
        toFixed: String? = null,
    )

    suspend fun writeProviderEvent(
        eventId: String,
        scopeKey: String,
        kind: String,
        observedAt: Long,
        source: String,
        evidenceId: String?,
        detail: String?,
    )

    suspend fun writeQuotaEvent(
        eventId: String,
        scopeKey: String,
        fromState: String?,
        toState: String?,
        observedAt: Long,
        source: String,
        evidenceId: String?,
    )
}

/** 投遞抽象（回 true＝已投遞；production 實作接通知通道，無權限時回 false 並記錄）。 */
interface DeliverySink {
    fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean
    fun markUndelivered(eventId: String)
}
