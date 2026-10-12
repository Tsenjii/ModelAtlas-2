package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.SystemClock
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * NVIDIA hosted adapter（build.nvidia.com / integrate.api.nvidia.com）。
 *
 * TRACEABILITY: ADAPT-001（NVIDIA hosted）。
 * Spec: `PRODUCT_SPEC.md` §12 NVIDIA NIM（hosted 與 self-hosted 分開；catalog 出現 ≠
 * 這把 key 可用；狀態至少 CATALOG / VERIFIED_USABLE / TEMPORARILY_UNAVAILABLE /
 * NOT_AVAILABLE_FOR_ACCOUNT；避免同步時全部 inference probe）。
 * 研究：`docs/NVIDIA_NIM_RESEARCH.md` §1（觀測日期 2026-09-28，docs-only，A=0）。
 *
 * 已證實 contract（只吃這些，未證實一律 Unknown）：
 * - Base `https://integrate.api.nvidia.com` + `POST /v1/chat/completions`（B）。
 * - Auth `Authorization: Bearer $NVIDIA_API_KEY` + org「Public API Endpoints」權限（B/S）。
 * - 免費性質 `free to prototype` + `no per-token billing`（C）；RPM `up to 40 for most models`（C）。
 * - RPD / TPD / TPM 官方未公開 → [QuotaValueState.UNKNOWN]（D-003：不得以 0 代替、不得換算）。
 * - Quota/usage structured endpoint 官方未提供 → [DedicatedResult.Unsupported]，維持 UNKNOWN。
 * - 官方 error contract 不存在 → 全部分類標 `provenance = OBSERVED`，不得冒充 Official；
 *   同根因（缺 Public API Endpoints 權限）可回 403 或 410 → 兩者皆映射
 *   [ModelAvailability.NOT_AVAILABLE_FOR_ACCOUNT]；402 語意 U，不可套用「402=付費不足」。
 * - Catalog 兩份官方頁衝突（`reference/llm-apis` 2026-09-21 主來源 vs `nim/docs/models` 舊版
 *   以 conflict 保留，不覆蓋）。
 * - 免費判定證據鏈 = hosted + no per-token billing + Developer Program；名稱（含 `free`）
 *   永不作為證據（NIM catalog 無 variant 概念）。
 */
class NvidiaHostedAdapter(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock,
    private val allowLoopbackHttpForTests: Boolean = false,
) {
    val providerType: DedicatedProviderType = DedicatedProviderType.NVIDIA_NIM_HOSTED

    val supportedCapabilities: Set<DedicatedCapability> = setOf(
        DedicatedCapability.DISCOVER_MODELS,
        DedicatedCapability.TEST_MODEL,
        DedicatedCapability.FETCH_QUOTA_POOLS,
        DedicatedCapability.FETCH_PRICING,
        DedicatedCapability.FETCH_NOTICE,
        DedicatedCapability.FETCH_CAPABILITIES,
    )

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(20L, TimeUnit.SECONDS)
        .readTimeout(20L, TimeUnit.SECONDS)
        .callTimeout(30L, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Quota（RPM 上界 + RPD/TPD/TPM Unknown；per-model 不合併） ----

    /**
     * Hosted quota 池（scope 官方未定義 → [QuotaScopeKind.UNKNOWN]；
     * per-key／per-account 說法皆為 E 級第三方彙整，禁猜，不用）。
     * RPM 40 為 `officialUpperBound` + `appliesToMostModels`，個人值需登入。
     */
    fun quotaPools(): List<QuotaPool> {
        val pools = listOf(
            QuotaPool(
                scopeKind = QuotaScopeKind.UNKNOWN,
                modelId = null,
                rpm = QuotaDimension(
                    value = HOSTED_RPM_UPPER_BOUND,
                    state = QuotaValueState.KNOWN,
                    upperBound = true,
                    appliesToMostModels = true,
                    requiresLogin = true,
                ),
                rpd = QuotaDimension.unknown(),
                tpm = QuotaDimension.unknown(),
                tpd = QuotaDimension.unknown(),
                resetSemantics = null,
                provenance = DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX,
                isGroundTruth = false,
            ),
        )
        DedicatedSync.assertNoMerge(pools)
        return pools
    }

    fun fetchQuotaPools(): DedicatedResult<List<QuotaPool>> = DedicatedResult.Success(
        DedicatedEvidence(
            source = SOURCE_QUOTA,
            observedAt = clock.epochMillis(),
            provenance = DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX,
            note = "RPM up to 40 for most models (upper bound, C via index); " +
                "quota scope officially undefined (E per-key/per-account claims not used) → UNKNOWN; " +
                "RPD/TPD/TPM Unknown; no structured endpoint",
        ),
        quotaPools(),
    )

    // ---- Pricing（無 per-token 計價維度；絕不用 0 冒充免費） ----

    /** Hosted 無 per-token 價格端點 → mode 固定為 [PricingMode.NO_PER_TOKEN_BILLING]。 */
    fun pricingMode(): PricingMode = PricingMode.NO_PER_TOKEN_BILLING

    fun fetchPricing(): DedicatedResult<PricingMode> = DedicatedResult.Success(
        DedicatedEvidence(
            source = SOURCE_PRICING,
            observedAt = clock.epochMillis(),
            provenance = DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX,
            note = "no per-token billing; pricingMode carries no zero price",
        ),
        pricingMode(),
    )

    // ---- 免費判定（不收 model name；名稱永不作為證據） ----

    /**
     * @param hostedOnBuildNvidia 是否為 build.nvidia.com hosted endpoint。
     * @param noPerTokenBilling 是否有 `no per-token billing` 官方敘述。
     * @param developerProgramMember 是否為 Developer Program 會員。
     */
    fun assessFree(
        hostedOnBuildNvidia: Boolean?,
        noPerTokenBilling: Boolean?,
        developerProgramMember: Boolean?,
    ): FreeAssessment = when {
        hostedOnBuildNvidia == null || noPerTokenBilling == null || developerProgramMember == null ->
            FreeAssessment.Unknown("hosted/noPerTokenBilling/membership 任一未知則 Unknown，不猜")
        hostedOnBuildNvidia && noPerTokenBilling && developerProgramMember ->
            FreeAssessment.Free("hosted on build.nvidia.com + no per-token billing + Developer Program (C/S)")
        else -> FreeAssessment.Unknown("證據鏈不完整，不判定免費")
    }

    // ---- HTTP 錯誤語意（無官方 contract → provenance 一律 OBSERVED；429/402/410 分開） ----

    /**
     * 429 / 402 / 410 三分支（絕不合併）：
     * - 429 → TEMPORARILY_UNAVAILABLE（observed；官方無定義）。
     * - 402 → Unknown 語意（credits 耗盡行為 U；不可套用 402=付費不足）。
     * - 410 → NOT_AVAILABLE_FOR_ACCOUNT（觀察到：org 權限缺失；官方未定義）。
     * - 403 → NOT_AVAILABLE_FOR_ACCOUNT（同根因另一 status；≥2 種 body）。
     */
    fun classifyHttpStatus(httpStatus: Int, providerCode: String? = null): DedicatedError {
        val safeCode = providerCode?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }
        return when (httpStatus) {
            429 -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.TEMPORARILY_UNAVAILABLE,
                retryable = true,
                provenance = DedicatedProvenance.OBSERVED,
            )
            402 -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.UNKNOWN,
                retryable = false,
                provenance = DedicatedProvenance.OBSERVED,
            )
            410 -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.NOT_AVAILABLE_FOR_ACCOUNT,
                retryable = false,
                provenance = DedicatedProvenance.OBSERVED,
            )
            403 -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.NOT_AVAILABLE_FOR_ACCOUNT,
                retryable = false,
                provenance = DedicatedProvenance.OBSERVED,
            )
            401 -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.AUTH_INVALID,
                retryable = false,
                provenance = DedicatedProvenance.OBSERVED,
            )
            404 -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.UNKNOWN,
                retryable = false,
                provenance = DedicatedProvenance.OBSERVED,
            )
            in 500..599 -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.TEMPORARILY_UNAVAILABLE,
                retryable = true,
                provenance = DedicatedProvenance.OBSERVED,
            )
            else -> DedicatedError(
                httpStatus = httpStatus,
                providerCode = safeCode,
                category = DedicatedErrorCategory.UNKNOWN,
                retryable = false,
                provenance = DedicatedProvenance.OBSERVED,
            )
        }
    }

    /** HTTP status → [ModelAvailability]（403 與 410 同映射；catalog listed 永不投影為可用）。 */
    fun availabilityOf(httpStatus: Int): ModelAvailability = when (httpStatus) {
        200 -> ModelAvailability.VERIFIED_USABLE
        403, 410 -> ModelAvailability.NOT_AVAILABLE_FOR_ACCOUNT
        429 -> ModelAvailability.TEMPORARILY_UNAVAILABLE
        else -> ModelAvailability.UNKNOWN
    }

    // ---- 最小 high-value probe（同步時禁止全量推論；單模型單次、失敗不重試） ----

    /**
     * 單模型最小可用性 probe（`POST /v1/chat/completions`，stream=false，最小 max_tokens）。
     * 成功（200）→ VERIFIED_USABLE；403/410 → NOT_AVAILABLE_FOR_ACCOUNT；429/5xx →
     * TEMPORARILY_UNAVAILABLE；其餘 → UNKNOWN。401 區分 AUTH_INVALID 與 inference 被拒
     * （key 有效但 inference 被擋 → NOT_AVAILABLE_FOR_ACCOUNT，由呼叫端以 catalog+probe 合成判定）。
     */
    suspend fun probeModel(
        baseUrl: String,
        apiKeyRef: String,
        model: String,
    ): DedicatedResult<ModelAvailability> {
        val base = validatedBase(baseUrl)
            ?: return DedicatedResult.Failure(
                DedicatedStatus.INPUT_INVALID,
                evidence(SOURCE_PROBE, null),
            )
        val trimmedModel = model.trim().takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return DedicatedResult.Failure(DedicatedStatus.INPUT_INVALID, evidence(SOURCE_PROBE, null))
        val url = base.newBuilder().encodedPath(INFERENCE_PATH).build()
        val token = resolveCredential(apiKeyRef, originOf(url.toString()))
            ?: return DedicatedResult.Failure(DedicatedStatus.CREDENTIAL_UNAVAILABLE, evidence(SOURCE_PROBE, null))
        val body = """{"model":"${escapeJson(trimmedModel)}","messages":[{"role":"user","content":"ok"}],"max_tokens":1,"stream":false}"""
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token").build()
        return when (val exchange = exchange(request, MAX_BODY_BYTES)) {
            is Exchange.Failed -> DedicatedResult.Failure(exchange.status, evidence(SOURCE_PROBE, exchange.httpStatus))
            is Exchange.Body -> {
                val availability = availabilityOf(exchange.httpCode)
                DedicatedResult.Success(evidence(SOURCE_PROBE, exchange.httpCode), availability)
            }
        }
    }

    /** Notice：無結構化端點 → 獨立缺失（Unsupported），不拖垮其他能力。 */
    fun fetchNotice(): DedicatedResult<Boolean> = DedicatedResult.Unsupported(
        DedicatedEvidence(
            source = SOURCE_NOTICE,
            observedAt = clock.epochMillis(),
            provenance = DedicatedProvenance.UNKNOWN,
            note = "no structured notice endpoint (U)",
        ),
        "NVIDIA hosted has no structured notice endpoint",
    )

    // ---- HTTP boundary（不跟 redirect、不重試、只讀成功 body、錯誤 body 不讀、不記 secret） ----

    private fun resolveCredential(ref: String, origin: String): String? = try {
        val token = credentials.bearerToken(ref, origin)
        if (token.isNotBlank() && token.none { it.code < 0x20 || it.code == 0x7F }) token else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun originOf(url: String): String = try {
        val parsed = url.toHttpUrlOrNull() ?: return ""
        "${parsed.scheme}://${parsed.host}${if (parsed.port != 80 && parsed.port != 443) ":${parsed.port}" else ""}"
    } catch (_: Exception) {
        ""
    }

    private fun validatedBase(baseUrl: String): HttpUrl? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        return if (allowLoopbackHttpForTests) {
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            if (parsed.host !in setOf("localhost", "127.0.0.1", "::1")) return null
            parsed
        } else {
            if (!trimmed.startsWith("https://")) return null
            trimmed.toHttpUrlOrNull()
        }
    }

    private suspend fun exchange(request: Request, limitBytes: Long): Exchange =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                val delivered = AtomicBoolean(false)
                fun deliver(value: Exchange) {
                    if (delivered.compareAndSet(false, true)) continuation.resume(value, null)
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        deliver(Exchange.Failed(transportStatus(e)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        deliver(read(response, limitBytes))
                    }
                })
            }
        }

    private fun read(response: Response, limitBytes: Long): Exchange {
        if (!response.isSuccessful) return Exchange.Failed(DedicatedSync.statusOf(response.code), response.code)
        val source = response.body?.source() ?: return Exchange.Body(response.code, "")
        val buffer = Buffer()
        while (buffer.size <= limitBytes) {
            if (source.read(buffer, limitBytes + 1 - buffer.size) < 0L) break
        }
        if (buffer.size > limitBytes) return Exchange.Failed(DedicatedStatus.RESPONSE_TOO_LARGE, response.code)
        return Exchange.Body(response.code, buffer.readUtf8())
    }

    private fun transportStatus(e: IOException): DedicatedStatus = when (e) {
        is SSLException -> DedicatedStatus.TLS_FAILED
        is UnknownHostException -> DedicatedStatus.NETWORK_UNREACHABLE
        is SocketTimeoutException -> DedicatedStatus.TIMEOUT
        is InterruptedIOException -> DedicatedStatus.TIMEOUT
        else -> DedicatedStatus.NETWORK_UNREACHABLE
    }

    private fun evidence(source: String, httpStatus: Int?): DedicatedEvidence = DedicatedEvidence(
        source = source,
        observedAt = clock.epochMillis(),
        provenance = DedicatedProvenance.OBSERVED,
        httpStatus = httpStatus,
    )

    private fun escapeJson(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")

    @Suppress("unused")
    private fun parseObjectOrNull(content: String): JsonObject? =
        runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull()

    private sealed interface Exchange {
        data class Failed(val status: DedicatedStatus, val httpStatus: Int? = null) : Exchange
        data class Body(val httpCode: Int, val content: String) : Exchange
    }

    companion object {
        const val OFFICIAL_BASE_URL = "https://integrate.api.nvidia.com"
        const val INFERENCE_PATH = "/v1/chat/completions"
        const val HOSTED_RPM_UPPER_BOUND = 40L

        const val SOURCE_QUOTA = "nvidia-hosted/quota"
        const val SOURCE_PRICING = "nvidia-hosted/pricing"
        const val SOURCE_PROBE = "nvidia-hosted/v1/chat/completions"
        const val SOURCE_NOTICE = "nvidia-hosted/notice"

        const val MAX_BODY_BYTES = 262_144L
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** 三者必須分開；呼叫端不得把任一折成 CLIENT_ERROR。 */
        fun distinctStatuses(): Triple<DedicatedStatus, DedicatedStatus, DedicatedErrorCategory> =
            Triple(
                DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429,
                DedicatedStatus.HTTP_PAYMENT_REQUIRED_402,
                DedicatedErrorCategory.NOT_AVAILABLE_FOR_ACCOUNT,
            )
    }
}
