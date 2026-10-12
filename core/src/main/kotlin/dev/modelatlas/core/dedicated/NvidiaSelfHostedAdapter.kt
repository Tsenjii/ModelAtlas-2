package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.SystemClock
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * NVIDIA self-hosted adapter（NIM 容器；使用者自有 CUDA 主機）。
 *
 * TRACEABILITY: ADAPT-002（NVIDIA self-hosted metrics separation）。
 * Spec: `PRODUCT_SPEC.md` §12（self-hosted 如可用 Prometheus metrics，與 local telemetry 分開）。
 * 研究：`docs/NVIDIA_NIM_RESEARCH.md` §2（B/C；A=0）。
 *
 * 已證實 contract（只吃這些）：
 * - 管理端點（B）：`GET /v1/health/live`（nginx，不需模型載入）、`/v1/health/ready`
 *   （模型下載/初始化期間 503 屬正常）、`/v1/metadata`、`/v1/version`、`/v1/license`、
 *   `/v1/manifest`（available profiles）、`/v1/models`（僅已載入者，非可下載清單）。
 * - Metrics 路徑版本感知：1.x `/metrics`（NIM 自有命名）vs 2.x/3.x `/v1/metrics`
 *   （vLLM 原生 passthrough，不改寫；B/C）。先打 `/v1/version` 再選 schema。
 * - 推論端不驗證 `x-api-key`（B；`api_key="not-used"` 亦可）；`NGC_API_KEY`（download
 *   credential）與 inference credential 分開存放，不可互換、不可共用 auth 路徑。
 * - Quota：明確無 NVIDIA-imposed限流（B）→ [QuotaValueState.NO_PROVIDER_QUOTA]；
 *   實際上限為自有 GPU + batching（self-reported，非 provider limit）。
 * - 計價：AI Enterprise `$4500/GPU/年` 或 `~$1/GPU/小時`，per GPU（與 GPU 大小無關）（B）；
 *   Developer Program research/dev/test 限 16 GPUs（B）。不可用 0 / per-token 換算冒充免費。
 * - `X-Request-Id`：caller 未提供則 NIM 不加、回應亦不帶（C）→ client 必須自產生。
 * - `GET /v1/models` 空陣列 = 尚未載入，非 0、非 Unknown 缺失；三者必須區分。
 */
class NvidiaSelfHostedAdapter(
    private val clock: Clock = SystemClock,
) {
    val providerType: DedicatedProviderType = DedicatedProviderType.NVIDIA_NIM_SELF_HOSTED

    val supportedCapabilities: Set<DedicatedCapability> = setOf(
        DedicatedCapability.DISCOVER_MODELS,
        DedicatedCapability.FETCH_CAPABILITIES,
        DedicatedCapability.FETCH_PROVIDER_PERFORMANCE,
        DedicatedCapability.FETCH_ACCOUNT,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** 推論端是否需要 auth：官方明文不驗證 → false（誤標需認證會產生無意義 401）。 */
    fun inferenceAuthRequired(): Boolean = false

    // ---- Version-aware endpoint discovery（全部無推論成本） ----

    /** 依 `/v1/version` 選擇 metrics 路徑；未知版本回 null（不猜、不硬編碼單一路徑）。 */
    fun metricsPathFor(nimVersion: String?): String? {
        val v = nimVersion?.trim() ?: return null
        return when {
            v.startsWith("1.") -> METRICS_PATH_V1
            v.startsWith("2.") || v.startsWith("3.") -> METRICS_PATH_V2
            v.equals("latest", ignoreCase = true) -> METRICS_PATH_V2
            else -> null
        }
    }

    /** `/v1/health/ready` 503 在下載/初始化期間屬正常 → STARTING，不可誤判 UNSTABLE。 */
    fun readinessOf(httpStatus: Int): SelfHostedReadiness = when (httpStatus) {
        200 -> SelfHostedReadiness.READY
        503 -> SelfHostedReadiness.STARTING
        else -> SelfHostedReadiness.UNKNOWN
    }

    // ---- Quota：明確無 provider 限額（非 unlimited，非 0） ----

    fun quotaPools(): List<QuotaPool> {
        val pools = listOf(
            QuotaPool(
                scopeKind = QuotaScopeKind.INSTANCE_OWNED,
                modelId = null,
                rpm = QuotaDimension.noProviderQuota(),
                rpd = QuotaDimension.noProviderQuota(),
                tpm = QuotaDimension.noProviderQuota(),
                tpd = QuotaDimension.noProviderQuota(),
                resetSemantics = null,
                provenance = DedicatedProvenance.OFFICIAL_DOC,
                isGroundTruth = true,
            ),
        )
        DedicatedSync.assertNoMerge(pools)
        return pools
    }

    fun fetchQuotaPools(): DedicatedResult<List<QuotaPool>> = DedicatedResult.Success(
        DedicatedEvidence(
            source = SOURCE_QUOTA,
            observedAt = clock.epochMillis(),
            provenance = DedicatedProvenance.OFFICIAL_DOC,
            note = "explicitly no NVIDIA-imposed rate limit; capacity is self-owned",
        ),
        quotaPools(),
    )

    // ---- License 投影（不自行推論授權狀態） ----

    data class LicenseProjection(
        val maxGpusDeveloperProgram: Long?,
        val requiresAiEnterpriseForProduction: Boolean?,
        val provenance: DedicatedProvenance,
    )

    /** 已證實：Developer 16 GPUs；production 需 AI Enterprise（B）。其餘 Unknown。 */
    fun licenseProjection(): LicenseProjection = LicenseProjection(
        maxGpusDeveloperProgram = 16L,
        requiresAiEnterpriseForProduction = true,
        provenance = DedicatedProvenance.OFFICIAL_DOC,
    )

    // ---- Billing unit（per GPU；不可 per-token 換算，不可用 0） ----

    fun billingModes(): Set<PricingMode> = setOf(PricingMode.PER_GPU_YEAR, PricingMode.PER_GPU_HOUR)

    // ---- Metrics 隔離（officialSelfHosted vs localTelemetry；版本感知 schema） ----

    enum class MetricsSchema { NIM_V1_NAMES, VLLM_NATIVE_PASSTHROUGH, UNKNOWN }

    fun metricsSchemaFor(nimVersion: String?): MetricsSchema {
        val v = nimVersion?.trim() ?: return MetricsSchema.UNKNOWN
        return when {
            v.startsWith("1.") -> MetricsSchema.NIM_V1_NAMES
            v.startsWith("2.") || v.startsWith("3.") -> MetricsSchema.VLLM_NATIVE_PASSTHROUGH
            v.equals("latest", ignoreCase = true) -> MetricsSchema.VLLM_NATIVE_PASSTHROUGH
            else -> MetricsSchema.UNKNOWN
        }
    }

    data class SelfHostedMetrics(
        val schema: MetricsSchema,
        /** 1.x NIM 自有指標（僅該 schema 下解析；2.x/3.x 不硬套，否則全數取不到）。 */
        val nimV1: Map<String, Double>,
        /** vLLM 原生 passthrough 行數（不改寫、不重命名；dashboard 規則可直接沿用）。 */
        val vllmPassthroughLines: Int,
        val provenance: DedicatedProvenance,
    )

    /**
     * 解析 Prometheus exposition。1.x 提取已知 NIM 指標名；latest/2.x/3.x 只計 passthrough
     * 行數（官方明文不改寫，故不硬編碼 vLLM 指標名）。輸出寫入 `officialSelfHosted` 通道，
     * 絕不混入 `localTelemetry`（X-005）。
     */
    fun parseMetrics(text: String, nimVersion: String?): SelfHostedMetrics {
        val schema = metricsSchemaFor(nimVersion)
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val v1 = LinkedHashMap<String, Double>()
        if (schema == MetricsSchema.NIM_V1_NAMES) {
            for (line in lines) {
                val name = line.substringBefore("{").substringBefore(" ").trim()
                if (name in NIM_V1_KNOWN_METRICS) {
                    line.substringAfterLast(" ").trim().toDoubleOrNull()?.let { v1[name] = it }
                }
            }
        }
        return SelfHostedMetrics(
            schema = schema,
            nimV1 = v1,
            vllmPassthroughLines = if (schema == MetricsSchema.VLLM_NATIVE_PASSTHROUGH) lines.size else 0,
            provenance = DedicatedProvenance.SELF_HOSTED_OFFICIAL,
        )
    }

    // ---- `/v1/models` = 僅已載入（空 ≠ 不存在 ≠ Unknown） ----

    sealed interface LoadedModels {
        data class Loaded(val ids: List<String>) : LoadedModels
        data object EmptyNotLoaded : LoadedModels
    }

    /** 空陣列 = 尚未載入（EmptyNotLoaded），非「不存在」、非 Unknown 缺失。 */
    fun parseLoadedModels(body: String): DedicatedResult<LoadedModels> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return DedicatedResult.Failure(
                DedicatedStatus.MALFORMED,
                DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.SELF_HOSTED_OFFICIAL),
            )
        val data = runCatching { root["data"]!!.jsonArray }.getOrNull()
            ?: return DedicatedResult.Failure(
                DedicatedStatus.MALFORMED,
                DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.SELF_HOSTED_OFFICIAL),
            )
        val ids = data.mapNotNull {
            runCatching { it.jsonObject["id"]!!.jsonPrimitive.content }.getOrNull()
                ?.trim()?.takeIf { id -> id.isNotEmpty() && id.length <= 256 }
        }.distinct()
        val value = if (data.isEmpty()) LoadedModels.EmptyNotLoaded else LoadedModels.Loaded(ids)
        return DedicatedResult.Success(
            DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.SELF_HOSTED_OFFICIAL),
            value,
        )
    }

    // ---- Request correlation（官方不保證回傳；client 自產生） ----

    /** 產生呼叫端 `X-Request-Id`（官方：未提供則不加、回應亦不帶）。 */
    fun newRequestId(): String = UUID.randomUUID().toString()

    // ---- HTTP 錯誤語意（沿用 vLLM；無官方表 → OBSERVED；429/402/410 分開） ----

    fun classifyHttpStatus(httpStatus: Int): DedicatedError = when (httpStatus) {
        429 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.TEMPORARILY_UNAVAILABLE, true, DedicatedProvenance.OBSERVED)
        402 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
        410 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
        503 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.TEMPORARILY_UNAVAILABLE, true, DedicatedProvenance.OBSERVED)
        else -> DedicatedError(httpStatus, null, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
    }

    companion object {
        const val METRICS_PATH_V2 = "/v1/metrics"
        const val METRICS_PATH_V1 = "/metrics"
        const val HEALTH_LIVE = "/v1/health/live"
        const val HEALTH_READY = "/v1/health/ready"
        const val METADATA = "/v1/metadata"
        const val VERSION = "/v1/version"
        const val LICENSE = "/v1/license"
        const val MANIFEST = "/v1/manifest"
        const val MODELS = "/v1/models"

        const val SOURCE_QUOTA = "nvidia-selfhosted/quota"
        const val SOURCE_MODELS = "nvidia-selfhosted/v1/models"

        /** 1.x NIM 自有指標名（C；2.x/3.x 不適用）。 */
        val NIM_V1_KNOWN_METRICS: Set<String> = setOf(
            "gpu_cache_usage_perc",
            "num_requests_running",
            "generation_tokens_total",
            "time_to_first_token_seconds",
            "time_per_output_token_seconds",
            "e2e_request_latency_seconds",
            "request_prompt_tokens",
            "request_generation_tokens",
            "request_success_total",
        )
    }
}

enum class SelfHostedReadiness { READY, STARTING, UNKNOWN }
