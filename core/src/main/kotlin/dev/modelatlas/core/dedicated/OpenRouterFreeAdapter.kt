package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.SystemClock
import java.math.BigDecimal
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * OpenRouter Free adapter。
 *
 * TRACEABILITY: ADAPT-004（OpenRouter Free）。
 * Spec: `PRODUCT_SPEC.md` §12 OpenRouter Free（主要產品池只收經 pricing 驗證的 Free Models；
 * 不要把 paid catalog 塞滿 Models；free request quota / credit-spend / RPM / RPD 分開；
 * 估算 remaining 必須明示 Estimated）。
 * 研究：`docs/PROVIDER_QUOTA_PRICING_RESEARCH.md` §1.2 / §2.1 / §3.2 / §5.1（B 為主 + A 1 筆
 * 匿名 `GET /api/v1/models`：460 entries / 17 `:free`，pricing 皆 `"0"`）。
 *
 * 已證實 contract（只吃這些）：
 * - 免費判定唯一充分證據：該 `:free` entry 的 `pricing.prompt == "0"` 且
 *   `pricing.completion == "0"`（B：`A value of "0" indicates the feature is free`）。
 *   `:free` 後綴存在只證明有 variant，不充分；名稱含 `free` 非證據；router 回應的 `model`
 *   欄位非證據；`Free model availability changes frequently` → 每次重讀 pricing，不快取名單。
 * - 附加費用 `request` / `image` / `web_search` 為 `null` 與 `"0"` 語意不同，不可合併。
 * - Quota scope = platform-global（B：多帳號/多 key 不改變限額）；per-model 差異存在但數值
 *   未公開 → Unknown；`free_model_daily_requests` 為觀察維度非執行 gate；exempt（BYOK/
 *   部分帳號/端點）的 `remaining` 反映 tier policy 非 enforced ceiling，不當 ground truth。
 * - RPD：50（all-time <10 credits）/ 1000（≥10；9 credits 起已給高檔，`is_free_tier` 不可預測）；
 *   RPM 20（兩檔皆同；`is not reported` 於 `/api/v1/key`）；TPD 官方無條款 → Unknown。
 * - `GET /api/v1/key` schema（B）：`limit/limit_reset/limit_remaining/usage*-/byok_usage*-/is_free_tier/
 *   free_model_daily_requests{used,limit,remaining}/rate_limit(deprecated)/include_byok_in_limit`。
 * - 429 兩支：自身（`X-RateLimit-*`）vs 上游（`provider_code` + fallback 先重試他家）；
 *   成功回應不含 `X-RateLimit-*`；不可只看 HTTP status（200 帶 `error` / mid-stream
 *   `error.code:429 + finish_reason:error`）；4 個 error_type 轉成功 + `finish_reason:length`
 *   （`context_length_exceeded/max_tokens_exceeded/token_limit_exceeded/string_too_long`）。
 * - 402 依 `limit_source` 分流（branch on `limit_source`, not hint text）：
 *   `in_flight_budget_exhausted` 可重試 / `weight_exceeds_budget` 不可重試 /
 *   `openrouter_credits` 不足 / `openrouter_key_limit` 視 `limit_reset` / 無則不可重試。
 *   In-flight budget 僅 prepaid 低餘額子集（free/BYOK/enterprise/invoice 不適用）。
 * - 410 官方未列（`S410_Gone` 僅常數表）→ 保留/不預期。
 */
class OpenRouterFreeAdapter(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock,
    private val allowLoopbackHttpForTests: Boolean = false,
) {
    val providerType: DedicatedProviderType = DedicatedProviderType.OPENROUTER_FREE

    val supportedCapabilities: Set<DedicatedCapability> = setOf(
        DedicatedCapability.DISCOVER_MODELS,
        DedicatedCapability.FETCH_PRICING,
        DedicatedCapability.FETCH_QUOTA_POOLS,
        DedicatedCapability.FETCH_USAGE,
        DedicatedCapability.TEST_MODEL,
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

    // ---- Catalog entry（pricing 全精確字串；缺失 null，Unknown 維持 null） ----

    /**
     * Catalog 條目（ADAPT-004；`PROVIDER_QUOTA_PRICING_RESEARCH.md` §1.2）。
     *
     * 附加費用三欄採「值＋存在旗標」雙欄保存：值為 null 恆表 Unknown（維持 null，
     * 絕不用 0 代替；明確零值以 `"0"` 等數值字串承載），存在旗標區分「鍵不存在」
     * （舊 schema／無該欄）vs「明確 JSON null」（本輪 A 觀測真實形狀：
     * `request`／`image`／`web_search` 皆為明確 null，非缺鍵）。
     * 呼叫端判免費只看值（經 [surchargeKind]）；存在旗標僅供 evidence 誠實記錄，
     * 不得據此推導免費或填 0。
     */
    data class CatalogEntry(
        val id: String,
        val prompt: String?,
        val completion: String?,
        val request: String?,
        val image: String?,
        val webSearch: String?,
        /** `pricing` 內含 `request` 鍵（無論值為 null 或字串）即 true；無 pricing 物件即 false。 */
        val requestPresent: Boolean = false,
        /** 同上（`image` 鍵是否存在）。 */
        val imagePresent: Boolean = false,
        /** 同上（`web_search` 鍵是否存在）。 */
        val webSearchPresent: Boolean = false,
    )

    fun parseModels(body: String): DedicatedResult<List<CatalogEntry>> {
        val evidence = DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.OBSERVED)
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return DedicatedResult.Failure(DedicatedStatus.MALFORMED, evidence)
        val data = runCatching { root["data"]!!.jsonArray }.getOrNull()
            ?: return DedicatedResult.Failure(DedicatedStatus.MALFORMED, evidence)
        val out = ArrayList<CatalogEntry>(data.size)
        for (element in data) {
            val obj = element as? JsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: continue
            val pricing = obj["pricing"] as? JsonObject
            out += CatalogEntry(
                id = id,
                prompt = pricing?.feeOrNull("prompt"),
                completion = pricing?.feeOrNull("completion"),
                request = pricing?.feeOrNull("request"),
                image = pricing?.feeOrNull("image"),
                webSearch = pricing?.feeOrNull("web_search"),
                requestPresent = pricing?.containsKey("request") == true,
                imagePresent = pricing?.containsKey("image") == true,
                webSearchPresent = pricing?.containsKey("web_search") == true,
            )
        }
        return DedicatedResult.Success(evidence, out)
    }

    // ---- 免費判定（只吃 pricing 數值；簽名不收 model name） ----

    /**
     * 充分證據：`prompt == "0"` 且 `completion == "0"`。後綴/名稱/router 回應皆不輸入，
     * 從簽名層面禁止名稱判定。附加費用另行保存，不參與此判定。
     */
    /** 數值比較（`"0.0"` 與 `"0"` 同為零）；不可解析即 Unknown，不猜付費。 */
    fun isFreeByPricing(prompt: String?, completion: String?): FreeAssessment {
        if (prompt == null || completion == null) return FreeAssessment.Unknown("pricing 缺欄則 Unknown，不猜")
        val promptValue = runCatching { BigDecimal(prompt.trim()) }.getOrNull()
            ?: return FreeAssessment.Unknown("pricing 非數值則 Unknown，不猜")
        val completionValue = runCatching { BigDecimal(completion.trim()) }.getOrNull()
            ?: return FreeAssessment.Unknown("pricing 非數值則 Unknown，不猜")
        return if (promptValue.signum() == 0 && completionValue.signum() == 0) {
            FreeAssessment.Free("pricing 雙零 (official)")
        } else {
            FreeAssessment.NotFree("pricing 非雙零（official）")
        }
    }

    /**
     * 附加費用 `null`/空（不存在）vs 零值（免費）語意不同；回傳 [SurchargeKind.ABSENT_UNKNOWN]
     * 表「不存在/未知」。零值以數值判定（`"0"`／`"0.0"`／`"0.00"`／`"0.000"` 皆為零）；
     * 非數值字串同樣回 Unknown（不猜 CHARGED）。
     */
    fun surchargeKind(raw: String?): SurchargeKind {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return SurchargeKind.ABSENT_UNKNOWN
        val parsed = runCatching { BigDecimal(v) }.getOrNull() ?: return SurchargeKind.ABSENT_UNKNOWN
        return if (parsed.signum() == 0) SurchargeKind.FREE_ZERO else SurchargeKind.CHARGED
    }

    // ---- Quota（global；RPM/RPD 已知；TPD Unknown；per-model 差異 Unknown） ----

    /** 依 all-time 購買 credits 選檔（9 credits 起已給高檔；`is_free_tier` 不可預測）。 */
    fun quotaPools(allTimeCreditsPurchased: Long?): List<QuotaPool> {
        val rpd = if (allTimeCreditsPurchased != null && allTimeCreditsPurchased >= RPD_HIGH_TIER_FROM_CREDITS) {
            QuotaDimension.known(RPD_WITH_CREDITS)
        } else {
            QuotaDimension.known(RPD_FREE_TIER)
        }
        val pools = listOf(
            QuotaPool(
                scopeKind = QuotaScopeKind.PLATFORM_GLOBAL,
                modelId = null,
                rpm = QuotaDimension.known(FREE_MODEL_RPM),
                rpd = rpd,
                tpm = QuotaDimension.unknown(),
                tpd = QuotaDimension.unknown(),
                resetSemantics = "UTC_DAY",
                provenance = DedicatedProvenance.OFFICIAL_DOC,
                isGroundTruth = false,
            ),
        )
        DedicatedSync.assertNoMerge(pools)
        return pools
    }

    data class KeyQuotaFacts(
        val freeUsed: Long?,
        val freeLimit: Long?,
        val freeRemaining: Long?,
        /** exempt（BYOK/部分帳號/端點）時 remaining 僅反映 tier policy，非 enforced ceiling。 */
        val remainingIsPolicyNotCeiling: Boolean,
        val isFreeTier: Boolean?,
    )

    /** `GET /api/v1/key` 的 `free_model_daily_requests`（觀察維度，非執行 gate）。 */
    fun parseKeyQuota(body: String): DedicatedResult<KeyQuotaFacts> {
        val evidence = DedicatedEvidence(SOURCE_KEY, clock.epochMillis(), DedicatedProvenance.OFFICIAL_DOC)
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return DedicatedResult.Failure(DedicatedStatus.MALFORMED, evidence)
        val data = root["data"] as? JsonObject ?: root
        val free = data["free_model_daily_requests"] as? JsonObject
        return DedicatedResult.Success(
            evidence,
            KeyQuotaFacts(
                freeUsed = (free?.get("used") as? JsonPrimitive)?.longOrNull,
                freeLimit = (free?.get("limit") as? JsonPrimitive)?.longOrNull,
                freeRemaining = (free?.get("remaining") as? JsonPrimitive)?.longOrNull,
                remainingIsPolicyNotCeiling = true,
                isFreeTier = (data["is_free_tier"] as? JsonPrimitive)?.booleanOrNull,
            ),
        )
    }

    // ---- 錯誤語意（429 兩支 / 402 limit_source 分流 / 410 保留；200 仍須查 body） ----

    data class ErrorInput(
        val httpStatus: Int,
        val bodyErrorCode: Int?,
        val errorType: String?,
        val providerCode: String?,
        val limitSource: String?,
        val limitReason: String?,
        val hasRateLimitHeaders: Boolean,
        val finishReason: String?,
    )

    fun classifyError(input: ErrorInput): DedicatedError {
        // Mid-stream / 200-with-error：body error.code 優先於 HTTP status。
        val effective = input.bodyErrorCode ?: input.httpStatus
        // 4 個 error_type 轉成功 + finish_reason:length，不得計失敗。
        if (input.errorType in LENGTH_AS_SUCCESS_TYPES) {
            return DedicatedError(
                httpStatus = input.httpStatus,
                providerCode = input.providerCode,
                category = DedicatedErrorCategory.SUCCESS_AS_LENGTH,
                retryable = false,
                provenance = DedicatedProvenance.OFFICIAL_DOC,
            )
        }
        return when (effective) {
            429 -> {
                if (input.providerCode != null || !input.hasRateLimitHeaders) {
                    DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.UPSTREAM_RATE_LIMITED, true, DedicatedProvenance.OFFICIAL_DOC)
                } else {
                    DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.RATE_LIMITED_MINUTE, true, DedicatedProvenance.OFFICIAL_DOC)
                }
            }
            402 -> when (input.limitSource) {
                "openrouter_in_flight_budget" -> when (input.limitReason) {
                    "in_flight_budget_exhausted" -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.IN_FLIGHT_BUDGET_TEMPORARY, true, DedicatedProvenance.OFFICIAL_DOC)
                    "weight_exceeds_budget" -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.SINGLE_REQUEST_EXCEEDS_BUDGET, false, DedicatedProvenance.OFFICIAL_DOC)
                    else -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.INSUFFICIENT_CREDITS, false, DedicatedProvenance.OFFICIAL_DOC)
                }
                "openrouter_credits" -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.INSUFFICIENT_CREDITS, false, DedicatedProvenance.OFFICIAL_DOC)
                "openrouter_key_limit" -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.KEY_CREDIT_EXHAUSTED, false, DedicatedProvenance.OFFICIAL_DOC)
                else -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.DO_NOT_RETRY, false, DedicatedProvenance.OFFICIAL_DOC)
            }
            410 -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
            401 -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.AUTH_INVALID, false, DedicatedProvenance.OFFICIAL_DOC)
            403 -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OFFICIAL_DOC)
            404 -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OFFICIAL_DOC)
            else -> DedicatedError(input.httpStatus, input.providerCode, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
        }
    }

    /** 從回應 body 抽 `error.{code,type,metadata{provider_code,limit_source,reason}}`。 */
    fun extractErrorInput(httpStatus: Int, body: String, hasRateLimitHeaders: Boolean): ErrorInput {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        // Mid-stream：choices[0].finish_reason。
        val finish = runCatching {
            root?.get("choices")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val metadata = error?.get("metadata") as? JsonObject
        fun str(obj: JsonObject?, name: String): String? =
            (obj?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 256 }
        val code = (error?.get("code") as? JsonPrimitive)?.let {
            if (it.isString) it.content.toIntOrNull() else it.longOrNull?.toInt()
        }
        return ErrorInput(
            httpStatus = httpStatus,
            bodyErrorCode = code,
            errorType = str(metadata, "error_type") ?: str(error, "type"),
            providerCode = str(metadata, "provider_code"),
            limitSource = str(metadata, "limit_source"),
            limitReason = str(metadata, "reason"),
            hasRateLimitHeaders = hasRateLimitHeaders,
            finishReason = finish,
        )
    }

    // ---- HTTP boundary（GET /api/v1/models 匿名可取；GET /api/v1/key 需 key） ----

    suspend fun discoverModels(baseUrl: String): DedicatedResult<List<CatalogEntry>> {
        val base = validatedBase(baseUrl)
            ?: return DedicatedResult.Failure(
                DedicatedStatus.INPUT_INVALID,
                DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.OBSERVED),
            )
        val url = base.newBuilder().encodedPath(MODELS_PATH).build()
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        return when (val exchange = exchange(request, MAX_MODELS_BYTES)) {
            is Exchange.Failed -> DedicatedResult.Failure(
                exchange.status,
                DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.OBSERVED, exchange.httpStatus),
            )
            is Exchange.Body -> parseModels(exchange.content)
        }
    }

    private fun JsonObject.feeOrNull(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        if (primitive.content == JsonNull.content) return null
        return primitive.content.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
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
        val source = response.body?.source() ?: return Exchange.Body("")
        val buffer = Buffer()
        while (buffer.size <= limitBytes) {
            if (source.read(buffer, limitBytes + 1 - buffer.size) < 0L) break
        }
        if (buffer.size > limitBytes) return Exchange.Failed(DedicatedStatus.RESPONSE_TOO_LARGE, response.code)
        return Exchange.Body(buffer.readUtf8())
    }

    private fun transportStatus(e: IOException): DedicatedStatus = when (e) {
        is SSLException -> DedicatedStatus.TLS_FAILED
        is UnknownHostException -> DedicatedStatus.NETWORK_UNREACHABLE
        is SocketTimeoutException -> DedicatedStatus.TIMEOUT
        is InterruptedIOException -> DedicatedStatus.TIMEOUT
        else -> DedicatedStatus.NETWORK_UNREACHABLE
    }

    private sealed interface Exchange {
        data class Failed(val status: DedicatedStatus, val httpStatus: Int? = null) : Exchange
        data class Body(val content: String) : Exchange
    }

    companion object {
        const val OFFICIAL_BASE_URL = "https://openrouter.ai"
        const val MODELS_PATH = "/api/v1/models"
        const val KEY_PATH = "/api/v1/key"

        const val FREE_MODEL_RPM = 20L
        const val RPD_FREE_TIER = 50L
        const val RPD_WITH_CREDITS = 1000L

        /** 高檔起始為 threshold 下一 credit（目前 9 credits；`is_free_tier` 不可預測）。 */
        const val RPD_HIGH_TIER_FROM_CREDITS = 9L

        const val SOURCE_MODELS = "openrouter/api/v1/models"
        const val SOURCE_KEY = "openrouter/api/v1/key"

        const val MAX_MODELS_BYTES = 4_194_304L

        /** 轉成功 + `finish_reason:length` 的 4 個 error_type（官方明文，不得計失敗）。 */
        val LENGTH_AS_SUCCESS_TYPES: Set<String> = setOf(
            "context_length_exceeded",
            "max_tokens_exceeded",
            "token_limit_exceeded",
            "string_too_long",
        )
    }
}

enum class SurchargeKind { FREE_ZERO, CHARGED, ABSENT_UNKNOWN }
