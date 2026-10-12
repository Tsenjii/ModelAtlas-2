package dev.modelatlas.app.data

import androidx.room.withTransaction
import java.util.UUID

/**
 * X-018 事件層寫入／查詢層（Room v10；TRACEABILITY X-018／PRODUCT_SPEC §10末端
 * `Raw Evidence → … → Snapshot → Event`／§11 History／§14 Notices-Events可讀／
 * IMPLEMENTATION_PLAN「隨階段增加 PriceEvent／ProviderNotice／Event」）。
 *
 * 鐵律（BACKLOG X-018 acceptance＋全域 invariants）：
 * - 只從既有 `snapshot_facts`／`samples` **衍生**事件語意，不另抓資料、不做網路、
 *   不寫任何來源 URL／endpoint（source 為不透明邏輯來源名）。
 * - X-001 Unknown≠0／Free：事件欄位 null 保持 null，永不填 0／"0"／FREE 冒充已知。
 * - X-004 七維 scope 隔離：[scopeKey] 為 [PricingScope.storageKey] 全維，
 *   查詢一律精確 `=`，不同 account／credential／group／operation 永不互相覆蓋。
 * - X-005 provenance 分離：[source] 原樣攜帶，MANUAL 永不升為 OFFICIAL，
 *   official／observed／external 不互相冒充。
 * - X-006 sync failure 保留 last-good：[success=false] 一律回 null 且不寫任何事件列
 *  （否則會記錄不存在的狀態變化）。
 * - X-003 secret boundary：notice 只存截斷摘要＋ref，不存 raw response body、
 *   不存任何 secret／prompt／回覆全文。
 * - append-only：全部事件表以 ABORT 插入，歷史永不覆寫；重試以新 eventId 追加，
 *   不以 REPLACE 抹掉舊列。
 * - 單次失敗不產 OUTAGE：[recordProviderHealth] 的 OUTAGE 需 [MIN_DECISIVE_FOR_OUTAGE]
 *   個已判定樣本＋OFFLINE 級成功率，否則回 null。
 */
class EventStore(private val db: AtlasDatabase) {

    // ── Notices ──

    /**
     * 保存一則 provider notice（冪等由呼叫端以決定性 [noticeId] 保證；同 ID 重送即 ABORT 拋出，
     * 呼叫端不得以 REPLACE 覆寫歷史）。
     * [summary] 為已截斷的人讀摘要（本函式再截斷一次防呆）；raw response body 永不落地，
     * 僅以 [ref] 保存關聯（evidenceId／ETag 等非敏感 ref）。
     */
    suspend fun saveNotice(
        noticeId: String = newEventId("notice"),
        providerId: String,
        summary: String,
        ref: String? = null,
        source: String,
        evidenceId: String? = null,
        observedAt: Long = 0L,
        expiresAt: Long? = null,
    ): ProviderNoticeEntity = db.withTransaction {
        require(noticeId.isNotBlank()) { "noticeId must not be blank" }
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(summary.isNotBlank()) { "summary must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        val row = ProviderNoticeEntity(
            noticeId = noticeId,
            providerId = providerId,
            observedAt = observedAt,
            expiresAt = expiresAt,
            source = source,
            evidenceId = evidenceId?.takeIf { it.isNotBlank() },
            summary = truncateSummary(summary),
            ref = ref?.takeIf { it.isNotBlank() },
        )
        db.providerNoticeDao().insert(row)
        db.providerNoticeDao().getById(noticeId)
            ?: throw IllegalStateException("Provider notice write did not persist")
    }

    suspend fun noticesOf(providerId: String): List<ProviderNoticeEntity> {
        if (providerId.isBlank()) return emptyList()
        return db.providerNoticeDao().byProvider(providerId)
    }

    // ── Price transitions ──

    /**
     * 記錄一次價格轉換（從既有 snapshot 衍生，呼叫端傳入新舊已知值）。
     * 回 null（不寫列）條件：[success=false]（X-006）／新舊任一側完全未知
     * （input＋output＋fixed 全 null，X-001）／新舊完全相等（無轉換）。
     * null 欄位原樣保存，永不填 0。
     *
     * fixed 腿（X-014 slice 2 D-FIXED-1/F1 修復）：僅參與已知側／相等閘，
     * 不持久化（price_events 無 fixed 欄）——確保「有通知必有列」的
     * append-only 不變量：pipeline 通過的 fixed-only 轉換在 store 層同樣
     * 通過 gates 並寫入列（列上腿欄為 null，事實原樣）。
     */
    suspend fun recordPriceTransition(
        scopeKey: String,
        fromInput: String?,
        fromOutput: String?,
        toInput: String?,
        toOutput: String?,
        fromState: String? = null,
        toState: String? = null,
        observedAt: Long,
        source: String,
        evidenceId: String? = null,
        success: Boolean = true,
        eventId: String = newEventId("price"),
        fromFixed: String? = null,
        toFixed: String? = null,
    ): PriceEventEntity? {
        if (!success) return null
        require(scopeKey.isNotBlank()) { "scopeKey must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        if (!isPriceKnown(fromInput, fromOutput, fromState) && fromFixed == null) return null
        if (!isPriceKnown(toInput, toOutput, toState) && toFixed == null) return null
        if (fromInput == toInput && fromOutput == toOutput && fromState == toState &&
            fromFixed == toFixed
        ) return null
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        val row = PriceEventEntity(
            eventId = eventId,
            scopeKey = scopeKey,
            fromState = fromState,
            toState = toState,
            fromInput = fromInput,
            toInput = toInput,
            fromOutput = fromOutput,
            toOutput = toOutput,
            observedAt = observedAt,
            source = source,
            evidenceId = evidenceId?.takeIf { it.isNotBlank() },
        )
        return db.withTransaction {
            db.priceEventDao().insert(row)
            db.priceEventDao().getById(eventId)
                ?: throw IllegalStateException("Price event write did not persist")
        }
    }

    suspend fun priceHistory(scopeKey: String): List<PriceEventEntity> {
        if (scopeKey.isBlank()) return emptyList()
        return db.priceEventDao().history(scopeKey)
    }

    // ── Quota transitions ──

    /**
     * 記錄一次配額轉換。回 null（不寫列）條件：[success=false]／新舊任一側 state
     * 為 null（UNKNOWN，X-001）／新舊相等（無轉換）。
     */
    suspend fun recordQuotaTransition(
        scopeKey: String,
        fromState: String?,
        toState: String?,
        observedAt: Long,
        source: String,
        evidenceId: String? = null,
        success: Boolean = true,
        eventId: String = newEventId("quota"),
    ): QuotaEventEntity? {
        if (!success) return null
        require(scopeKey.isNotBlank()) { "scopeKey must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        // X-001 防護深化（slice-3 review F-02）：pipeline 對字面 "UNKNOWN" 已設閘，
        // store 端重複設防——繞過 scanQuota 的直接呼叫者也無法寫入 UNKNOWN 邊。
        if (fromState == null || toState == null || fromState == "UNKNOWN" || toState == "UNKNOWN") return null
        if (fromState == toState) return null
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        val row = QuotaEventEntity(
            eventId = eventId,
            scopeKey = scopeKey,
            fromState = fromState,
            toState = toState,
            observedAt = observedAt,
            source = source,
            evidenceId = evidenceId?.takeIf { it.isNotBlank() },
        )
        return db.withTransaction {
            db.quotaEventDao().insert(row)
            db.quotaEventDao().getById(eventId)
                ?: throw IllegalStateException("Quota event write did not persist")
        }
    }

    suspend fun quotaHistory(scopeKey: String): List<QuotaEventEntity> {
        if (scopeKey.isBlank()) return emptyList()
        return db.quotaEventDao().history(scopeKey)
    }

    internal suspend fun quotaEventById(eventId: String): QuotaEventEntity? {
        return db.quotaEventDao().getById(eventId)
    }

    // ── Provider health events ──

    /**
     * 記錄一次 provider 健康事件（OUTAGE／RECOVERY／NOTICE）。
     * - [kind] 僅接受 [PROVIDER_EVENT_KINDS]；其他一律 require 拒絕。
     * - OUTAGE 需健康證據：[decided]≥[MIN_DECISIVE_FOR_OUTAGE] 且成功率達 OFFLINE 級
     *   （[isOutageEvidence]）；單次失敗（decided=1）永不產 OUTAGE（X-018 驗收第 6 項）。
     * - RECOVERY／NOTICE 不做樣本門檻（呼叫端已持有恢復／公告證據），但仍需非空 kind＋source。
     * - [success=false] 一律回 null（X-006）。
     */
    suspend fun recordProviderHealth(
        providerId: String,
        kind: String,
        observedAt: Long,
        source: String,
        evidenceId: String? = null,
        detail: String? = null,
        decided: Int = 0,
        successes: Int = 0,
        success: Boolean = true,
        eventId: String = newEventId("provider"),
    ): ProviderEventEntity? {
        if (!success) return null
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(kind in PROVIDER_EVENT_KINDS) { "Unknown provider event kind" }
        require(source.isNotBlank()) { "source must not be blank" }
        if (kind == PROVIDER_EVENT_OUTAGE && !isOutageEvidence(decided, successes)) return null
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        val row = ProviderEventEntity(
            eventId = eventId,
            providerId = providerId,
            kind = kind,
            observedAt = observedAt,
            source = source,
            evidenceId = evidenceId?.takeIf { it.isNotBlank() },
            detail = detail?.takeIf { it.isNotBlank() }?.let(::truncateSummary),
        )
        return db.withTransaction {
            db.providerEventDao().insert(row)
            db.providerEventDao().getById(eventId)
                ?: throw IllegalStateException("Provider event write did not persist")
        }
    }

    suspend fun eventsOf(providerId: String): List<ProviderEventEntity> {
        if (providerId.isBlank()) return emptyList()
        return db.providerEventDao().byProvider(providerId)
    }

    // ── 純函式（不碰 DB，可單測；OUTAGE 門檻集中一處） ──

    /** 價格已知：input／output／state 任一非 null 即已知；全 null＝UNKNOWN。 */
    fun isPriceKnown(input: String?, output: String?, state: String? = null): Boolean =
        input != null || output != null || state != null

    /**
     * OUTAGE 證據判定：已判定樣本數達 [MIN_DECISIVE_FOR_OUTAGE] 且成功率 < 0.5
     * （對標 [HealthRollup.UNSTABLE_THRESHOLD] 的 OFFLINE 級；門檻為產品定義，
     * 未知（decided<=0）永不判 OUTAGE）。
     */
    fun isOutageEvidence(decided: Int, successes: Int): Boolean {
        if (decided < MIN_DECISIVE_FOR_OUTAGE) return false
        val safe = successes.coerceIn(0, decided)
        return safe.toDouble() / decided.toDouble() < OUTAGE_SUCCESS_RATE_BELOW
    }

    private fun truncateSummary(raw: String): String =
        if (raw.length <= MAX_SUMMARY_CHARS) raw else raw.take(MAX_SUMMARY_CHARS)

    companion object {
        /** notice 摘要上限（單行 UI＋DB 有界；截斷不丟 ref，raw body 永不落地）。 */
        const val MAX_SUMMARY_CHARS = 280

        /** provider 事件種類：僅此三者（BACKLOG X-018：OUTAGE／RECOVERY／NOTICE）。 */
        const val PROVIDER_EVENT_OUTAGE = "OUTAGE"
        const val PROVIDER_EVENT_RECOVERY = "RECOVERY"
        const val PROVIDER_EVENT_NOTICE = "NOTICE"
        val PROVIDER_EVENT_KINDS: Set<String> = setOf(
            PROVIDER_EVENT_OUTAGE,
            PROVIDER_EVENT_RECOVERY,
            PROVIDER_EVENT_NOTICE,
        )

        /**
         * OUTAGE 最小已判定樣本數（named const＋說明：單次失敗不產 OUTAGE；
         * 2 為「連續失敗」最小證據，1 永遠不足）。
         */
        const val MIN_DECISIVE_FOR_OUTAGE = 2

        /** OUTAGE 成功率上限（嚴格小於 0.5＝OFFLINE 級，對標 HealthRollup）。 */
        const val OUTAGE_SUCCESS_RATE_BELOW = 0.5

        /** provenance 來源白名單（manual／official／observed／external 分離，X-005）。 */
        val KNOWN_SOURCES: Set<String> = setOf(
            "MANUAL",
            "OFFICIAL",
            "OBSERVED",
            "EXTERNAL",
            "USER",
            "SYSTEM",
        )

        internal fun newEventId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    }
}
