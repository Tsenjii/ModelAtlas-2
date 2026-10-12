package dev.modelatlas.core.models

import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * P6 session J：MODEL-008 Compare Live 執行層（純 Kotlin，無 Android 依賴）。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §8 Compare Live、§3 統一 telemetry、§10 FREE 判定）。
 *
 * - 同一 canonicalId 的多個 offering 共用同一份小 prompt（[CompareLiveRequest.messages]）
 *   與同一個 [CompareLiveRequest.maxOutputTokens]，逐一經 [InferenceEngine]（唯一入口）
 *   執行，全部走正常 telemetry（source 恆為 COMPARE_LIVE，免費／付費皆記錄）。
 * - 上限併發 2（[MAX_CONCURRENCY]）；結果保持 [CompareLiveRequest.offerings] 輸入順序。
 * - 任一 offering 失敗（401/429/timeout/其他）不中斷其他列：失敗收斂為該列的
 *   outcome＋httpStatus，telemetry final 由 InferenceEngine 照常保存（不遺失）。
 * - 取消（CancellationException）不收斂為列失敗，直接傳播（同 scope 其餘列連帶取消）。
 * - PriceState／QuotaState 只吃呼叫端傳入的字串並 fail-closed 正規化；UNKNOWN 維持
 *   UNKNOWN，絕不依 model id／名稱含 "free" 推導（§10）。
 * - 指標（TTFT/TPS/latency/usage/rate-limit headers）只從 [CompareLiveSampleFeed]
 *   以本次 engine-owned [StreamEvent.RequestSent.sampleId] 精確讀回
 *   InferenceEngine 寫下的 final sample，並核對 source＝COMPARE_LIVE＋
 *   provider／account／actual credential／endpoint／model／canonical／group／
 *   operation 全 scope＋執行 outcome 一致才信任；feed 缺失、id 缺失或任一欄
 *   不符一律 null＝未知，永不補 0／不捏造／不用 latest 猜（§3：non-streaming
 *   無首 token 即 TTFT unavailable＝null）。
 * - [CompareLiveOffering.comparisonId] 為本批比測列唯一鍵：同一實際 offeringId
 *   可在不同 scoped comparisonId 下出現兩次（跨 key／group 比測）；校驗只認
 *   comparisonId 唯一，不認 offeringId 唯一。credentialId 維持實際 Bearer
 *   safe ref（nullable，anonymous／custom-only 允許 null）；selectedCredentialId
 *   為 UI／selection anchor；authFields 原樣透傳 target（custom auth 在
 *   transport 邊界解析，secret 不進此層）。
 */
object CompareLiveRunner {

    /** Compare Live 同時在途上限：避免一次打爆 quota／rate limit。 */
    const val MAX_CONCURRENCY = 2

    private val KNOWN_PRICE_STATES = setOf("FREE_NOW", "PAID", "PARTIALLY_FREE", "UNKNOWN")
    private val KNOWN_QUOTA_STATES = setOf(
        "QUOTA_AVAILABLE",
        "QUOTA_LOW",
        "QUOTA_EXHAUSTED",
        "RATE_LIMIT_ONLY",
        "PUBLIC_POOL",
        "UNLIMITED_CLAIMED",
        "NO_KNOWN_CONSUMABLE_QUOTA",
        "UNKNOWN",
    )

    /** 價格字串 fail-closed 正規化：未知字串回 UNKNOWN，永不回 FREE。 */
    fun normalizePriceState(raw: String): String {
        val normalized = raw.trim().uppercase()
        return if (normalized in KNOWN_PRICE_STATES) normalized else "UNKNOWN"
    }

    /** Quota 字串 fail-closed 正規化：未知字串回 UNKNOWN，永不當可用。 */
    fun normalizeQuotaState(raw: String): String {
        val normalized = raw.trim().uppercase()
        return if (normalized in KNOWN_QUOTA_STATES) normalized else "UNKNOWN"
    }

    /**
     * 執行一次 Compare Live。
     *
     * @param engine 唯一 inference 入口（呼叫端持有；內含正常 telemetry sink）。
     * @param request 同一 canonical＋offering 列表＋共用小 prompt。
     * @param feed 可選：能讀回 engine sink 內 final samples 的視圖（測試用 recording
     *   sink、產品用 Room telemetry 實作；見下方接線建議）。null 時指標欄全未知，
     *   但 inference 照常執行＋telemetry 照常保存（FREE_NOW 不略過）。
     * @param beforeOffering 可選：在 permit 內、engine 執行前呼叫，供主 AI 做
     *   live scope／credential 重驗；其 CancellationException 直接傳播並取消整批，
     *   不收斂為列成功、不產生合成 telemetry；其他例外收斂為該列 OTHER 失敗隔離。
     * @return 與 [CompareLiveRequest.offerings] 同順序的逐列結果；空 offerings 回空表。
     * @throws CancellationException 外部取消（含 beforeOffering 取消）時傳播，不收斂為列結果。
     */
    suspend fun run(
        engine: InferenceEngine,
        request: CompareLiveRequest,
        feed: CompareLiveSampleFeed? = null,
        beforeOffering: suspend (CompareLiveOffering) -> Unit = {},
    ): List<CompareLiveRowResult> {
        require(request.canonicalId.isNotBlank()) { "canonicalId must not be blank" }
        require(request.messages.isNotEmpty()) { "compare prompt must not be empty" }
        require(request.maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        if (request.offerings.isEmpty()) return emptyList()
        val comparisonIds = request.offerings.map { it.comparisonId }
        require(comparisonIds.all { it.isNotBlank() }) { "comparisonId must not be blank" }
        require(comparisonIds.size == comparisonIds.toSet().size) {
            "comparisonIds must be distinct for correlation"
        }

        val semaphore = Semaphore(MAX_CONCURRENCY)
        return coroutineScope {
            request.offerings.map { offering ->
                async {
                    semaphore.withPermit { runOne(engine, request, offering, feed, beforeOffering) }
                }
            }.awaitAll()
        }
    }

    private suspend fun runOne(
        engine: InferenceEngine,
        request: CompareLiveRequest,
        offering: CompareLiveOffering,
        feed: CompareLiveSampleFeed?,
        beforeOffering: suspend (CompareLiveOffering) -> Unit,
    ): CompareLiveRowResult {
        val priceState = normalizePriceState(offering.priceState)
        val quotaState = normalizeQuotaState(offering.quotaState)
        // beforeOffering 在 permit 內、engine 執行前；取消直接傳播整批。
        try {
            beforeOffering(offering)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 非取消例外：該列隔離失敗，不呼叫 engine、不產生 telemetry。
            return CompareLiveRowResult(
                offeringId = offering.offeringId,
                comparisonId = offering.comparisonId,
                providerId = offering.providerId,
                accountId = offering.accountId,
                credentialId = offering.selectedCredentialId,
                endpointId = offering.endpointId,
                providerModelId = offering.providerModelId,
                canonicalId = request.canonicalId,
                group = offering.group,
                success = false,
                outcome = Outcome.OTHER,
                httpStatus = null,
                priceState = priceState,
                quotaState = quotaState,
                pricingSnapshotId = offering.pricingSnapshotId,
                quotaSnapshotBeforeId = offering.quotaSnapshotBeforeId,
                sampleId = null,
            )
        }
        val target = InferenceTarget(
            providerId = offering.providerId,
            accountId = offering.accountId,
            credentialId = offering.credentialId,
            endpointId = offering.endpointId,
            endpointUrl = offering.endpointUrl,
            modelId = offering.providerModelId,
            canonicalModelId = request.canonicalId,
            group = offering.group,
            operation = offering.operation,
            maxOutputTokens = request.maxOutputTokens,
            authFields = offering.authFields,
            telemetryCredentialId = offering.selectedCredentialId,
        )
        // 同一列只跑一次：成功走完 flow 即 SUCCESS；InferenceFailure 帶 outcome＋status；
        // CancellationException 直接傳播（取消語意，不可收斂為列失敗）。
        // 同時捕捉本次 engine-owned RequestSent.sampleId（transport 偽造會被 engine 覆寫）。
        var outcome = Outcome.SUCCESS
        var status: Int? = null
        var capturedSampleId: String? = null
        try {
            engine.execute(
                target = target,
                messages = request.messages,
                source = TelemetrySource.COMPARE_LIVE,
                priceState = priceState,
                quotaState = quotaState,
                pricingSnapshotId = offering.pricingSnapshotId,
                quotaSnapshotBeforeId = offering.quotaSnapshotBeforeId,
            ).collect { event ->
                if (event is StreamEvent.RequestSent && event.sampleId != null) {
                    capturedSampleId = event.sampleId
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InferenceFailure) {
            outcome = e.outcome
            status = e.status
        } catch (_: Exception) {
            // Engine 已把 transport 例外分類為 InferenceFailure；此處只收斂非預期例外，
            // 不攜帶遠端訊息（不污染結果／log）。
            outcome = Outcome.OTHER
            status = null
        }
        // 精確關聯：只認本次 capturedSampleId 的 final；任一 scope／source／outcome
        // 不符即視為未知，不 fallback、不猜 latest。
        val rawSample: PerformanceSample? =
            if (feed != null && capturedSampleId != null) feed.finalFor(capturedSampleId!!) else null
        val sample = rawSample?.takeIf {
            isExactMatch(
                sample = it,
                expectedId = capturedSampleId,
                offering = offering,
                canonicalId = request.canonicalId,
                localOutcome = outcome,
            )
        }
        return CompareLiveRowResult(
            offeringId = offering.offeringId,
            comparisonId = offering.comparisonId,
            providerId = offering.providerId,
            accountId = offering.accountId,
            credentialId = offering.selectedCredentialId,
            endpointId = offering.endpointId,
            providerModelId = offering.providerModelId,
            canonicalId = request.canonicalId,
            group = offering.group,
            success = outcome == Outcome.SUCCESS,
            outcome = sample?.outcome ?: outcome,
            httpStatus = sample?.httpStatus ?: status,
            ttftMs = sample?.ttftMs,
            ttfbMs = sample?.ttfbMs,
            totalLatencyMs = sample?.totalLatencyMs,
            tps = sample?.tps,
            inputTokens = sample?.usage?.inputTokens,
            outputTokens = sample?.usage?.outputTokens,
            priceState = priceState,
            quotaState = quotaState,
            rateLimitHeaders = sample?.rateLimitHeaders ?: emptyMap(),
            pricingSnapshotId = offering.pricingSnapshotId,
            quotaSnapshotBeforeId = offering.quotaSnapshotBeforeId,
            quotaSnapshotAfterId = sample?.quotaSnapshotAfterId,
            // 無 feed 時維持 legacy sampleId=null；有 feed 時回本次 engine-owned id。
            sampleId = if (feed == null) null else capturedSampleId,
        )
    }

    /**
     * 精確匹配：id 必須等於本次 capturedSampleId、completedAt 非 null（final）、
     * source 恆 COMPARE_LIVE、provider／account／actual credential／endpoint／
     * model／canonical／group／operation 全 scope 相等、且 outcome 與本次執行
     * outcome 一致；任一不符即不可信任（回 null＝未知，不補 0／不猜）。
     */
    private fun isExactMatch(
        sample: PerformanceSample,
        expectedId: String?,
        offering: CompareLiveOffering,
        canonicalId: String,
        localOutcome: Outcome,
    ): Boolean {
        if (expectedId == null) return false
        if (sample.id != expectedId) return false
        if (sample.completedAt == null) return false
        if (sample.source != TelemetrySource.COMPARE_LIVE) return false
        if (sample.providerId != offering.providerId) return false
        if (sample.accountId != offering.accountId) return false
        if (sample.safeCredentialId != (offering.selectedCredentialId ?: offering.credentialId).orEmpty()) return false
        if (sample.endpointId != offering.endpointId) return false
        if (sample.providerModelId != offering.providerModelId) return false
        if (sample.canonicalModelId != canonicalId) return false
        if (sample.group != offering.group) return false
        if (sample.operation != offering.operation) return false
        if (sample.outcome != localOutcome) return false
        return true
    }
}

/**
 * 單一待比 offering。credential 只收 safe id 字串（禁 raw key／PAT）；
 * priceState／quotaState 由呼叫端（UsableProjection／availability）傳入，不在此推導。
 *
 * - [credentialId] 維持實際 Bearer safe ref（nullable：anonymous／custom-only 允許 null）。
 * - [selectedCredentialId] 為 UI／selection anchor（預設等於 [credentialId]）。
 * - [comparisonId] 為本批唯一列鍵（預設等於 [offeringId]，向後相容）；同一實際
 *   offeringId 可在不同 scoped comparisonId 下出現。
 * - [authFields] 原樣透傳 [InferenceTarget.authFields]（custom auth 在 transport
 *   邊界解析）。
 */
data class CompareLiveOffering(
    val offeringId: String,
    val providerId: String,
    val accountId: String,
    val credentialId: String? = null,
    val endpointId: String,
    val endpointUrl: String,
    val providerModelId: String,
    val group: String? = null,
    val operation: String = "CHAT_COMPLETIONS",
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val pricingSnapshotId: String? = null,
    val quotaSnapshotBeforeId: String? = null,
    val comparisonId: String = offeringId,
    val selectedCredentialId: String? = credentialId,
    val authFields: List<InferenceAuthField> = emptyList(),
)

/** Compare Live 請求：同一 canonical＋共用小 prompt＋統一 maxOutputTokens。 */
data class CompareLiveRequest(
    val canonicalId: String,
    val offerings: List<CompareLiveOffering>,
    val messages: List<PromptMessage>,
    val maxOutputTokens: Int = 64,
)

/**
 * 逐列比對結果。指標 null＝未知（telemetry 無該值，如無首 token 時 TTFT unavailable），
 * UI 必須明示「未知」，禁 0／禁免費字樣；[errorKind] 僅供顯示（outcome 名），不含遠端訊息。
 *
 * - [comparisonId] 為本批唯一列鍵（預設等於 [offeringId]，向後相容）。
 * - [accountId]／[credentialId] 為 selected anchor（後者即 offering.selectedCredentialId）。
 * - [group] 回顯 offering.group；[quotaSnapshotAfterId] 來自已校驗 final sample。
 */
data class CompareLiveRowResult(
    val offeringId: String,
    val providerId: String,
    val endpointId: String,
    val providerModelId: String,
    val canonicalId: String,
    val success: Boolean,
    val outcome: Outcome,
    val httpStatus: Int? = null,
    val ttftMs: Long? = null,
    val ttfbMs: Long? = null,
    val totalLatencyMs: Long? = null,
    val tps: Double? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val rateLimitHeaders: Map<String, String> = emptyMap(),
    val pricingSnapshotId: String? = null,
    val quotaSnapshotBeforeId: String? = null,
    val sampleId: String? = null,
    val comparisonId: String = offeringId,
    val accountId: String? = null,
    val credentialId: String? = null,
    val group: String? = null,
    val quotaSnapshotAfterId: String? = null,
) {
    /** 非成功列的錯誤種類（outcome 名）；成功列為 null。 */
    val errorKind: String? = if (success) null else outcome.name
}

/**
 * Engine sink 內 final samples 的唯讀視圖。產品接線建議（主 AI）：
 * Room telemetry DAO 實作此介面（以 sample id 精確查詢 completedAt 非 null 列，
 * 見 CompareLiveFeed 覆寫 finalFor SELECT by id；或以 Tee sink 同步轉發）；
 * 實作必須只讀、不干擾正常保存路徑。
 */
interface CompareLiveSampleFeed {
    fun finalSamples(): List<PerformanceSample>

    /**
     * 本次 engine-owned sampleId 的精確 final（completedAt 非 null）；對不上回
     * null＝未知。預設在 [finalSamples] 中按 id 精確查找，不做 latest 猜測。
     */
    fun finalFor(sampleId: String): PerformanceSample? =
        finalSamples().firstOrNull { it.id == sampleId && it.completedAt != null }

    /**
     * Legacy：該 offering 三元組的最新 final（completedAt 非 null）；對不上回 null。
     * 僅為舊獨立 API／tests 保留，runner 不再呼叫（必須用 [finalFor] 精確關聯）。
     */
    fun latestFinalFor(
        providerId: String,
        endpointId: String,
        providerModelId: String,
    ): PerformanceSample? = finalSamples()
        .filter {
            it.providerId == providerId &&
                it.endpointId == endpointId &&
                it.providerModelId == providerModelId &&
                it.completedAt != null
        }
        .maxByOrNull { it.requestStartedAt }
}
