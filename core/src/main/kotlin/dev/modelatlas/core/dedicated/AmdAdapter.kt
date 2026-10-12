package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.SystemClock
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
 * AMD adapter（shared 公共免費池 + dedicated 自有 instance；同一 key、不同 host/語意）。
 *
 * TRACEABILITY: ADAPT-003（AMD）。
 * Spec: `PRODUCT_SPEC.md` §12 AMD（structured metadata；若有 quota/usage/header 正規化
 * RPM/RPD/TPM/TPD/reset/by-model；性能缺口用 local telemetry）。
 * 研究：`docs/AMD_CLOUD_RESEARCH.md`（觀測日期 2026-09-28，docs-only，A=0）。
 *
 * 已證實 contract（只吃這些）：
 * - Shared base `https://developer.amd.com.cn/radeon/api/v1`（路徑亦可 `/api/v1/...`）（B）；
 *   `POST /v1/chat/completions`、`GET /v1/models`（Bearer 或 session）、`POST /v1/messages`
 *   (+ `/count_tokens`)；legacy completions/embeddings/image/audio/rerank/`/v1/responses` → 404（B）。
 * - `GET /v1/models` envelope `{data:[...]}` 非 OpenAI 標準；欄位 `id/pricing{prompt,completion,
 *   input_cache_read}(USD/token decimal string)/context_length/supported_parameters/free:bool/
 *   stability/architecture/providers[]`（B）。`free:true` 不可直標 FREE_NOW。
 * - Gateway 重建語意：未列參數靜默丟棄（B）；`thinking/reasoning.enabled` 回 400；
 *   `reasoning_effort` 頂層名互斥（`high` 在 Qwen3.8-27B 為 400，`xhigh` 在 GLM-5.3-Flash 為 422；
 *   跨模型統一只用 low/medium）。
 * - Shared quota（B）：platform per-key RPM 30 / per-IP 120 / per-key concurrency 8 /
 *   per-model concurrency 存在無數字 / gateway per-account RPM 20（60s sliding）/ rolling
 *   spend cap；成功 headers `X-RateLimit-Limit-User-RPM/Remaining/Reset` +
 *   `X-RateLimit-Limit-User-Daily-USD/Used/Remaining/Reset`；429 + `Retry-After`
 *  （per-minute 60、concurrency 1、spend 剩餘秒數）。RPD / TPM / TPD 官方未公開 → Unknown。
 * - Reset 衝突保留：`usage` 稱 `daily_reset_at`（Asia/Shanghai）vs `rate-limits` 稱 rolling
 *   自首次 billed 起算 → 以當次回傳 `daily_reset_at` + header `Reset` unix 為準，不寫死午夜。
 * - Usage `GET /api/profile/model-usage`（B）：`{status,rpm_limit,daily_cost_limit/used/remaining_usd,
 *   daily_reset_timezone,daily_reset_at,today,last_30_days,all_time,by_model[],recent[]}`；
 *   `status=ok/not_configured/not_available`；502 聚合歸零 → 必須 UNKNOWN 不當 0。
 * - Auth（B）：`rc-`+48hex（共 51 chars）；`Authorization: Bearer`（`/v1/messages` 另可用
 *   `x-api-key` + `anthropic-version`，並存時 Authorization 勝）；rotate
 *   `POST /api/profile/api-token`（發新即廢舊）；未驗證帳號無 key、`403 account_not_verified`。
 * - 錯誤雙 envelope（B）：platform FastAPI 形 `{detail:...}` vs gateway OpenAI 形
 *   （`/v1/messages` 用 Anthropic 形）；SDK 讀 `body.error ?? body.detail.error ?? body.detail`。
 * - Dedicated（B/C）：base `https://<host>/spaces/<instance-id>/<port>/v1`（launch 顯示，
 *   不手拼）；vLLM/SGLang 直透；request path 無 per-minute/concurrency/spend 上限；
 *   計費 instance 存續即耗 credits（另表）；每帳號一 active instance。
 * - Catalog 出現 ≠ 這把 key 可用；`400 not supported` → 重抓 catalog，不刪 last-good。
 */
class AmdAdapter(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock,
    private val allowLoopbackHttpForTests: Boolean = false,
) {
    val sharedType: DedicatedProviderType = DedicatedProviderType.AMD_SHARED
    val dedicatedType: DedicatedProviderType = DedicatedProviderType.AMD_DEDICATED

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

    // ---- Structured metadata（GET /v1/models） ----

    data class AmdModelEntry(
        val id: String,
        val promptPerTokenUsd: BigDecimal?,
        val completionPerTokenUsd: BigDecimal?,
        val cacheReadPerTokenUsd: BigDecimal?,
        val contextLength: Long?,
        val free: Boolean?,
        val stability: String?,
    )

    /**
     * 解析 `{data:[...]}`（無 object/owned_by/created）。pricing 為 USD/token 精確 decimal
     * 字串；缺失為 null。`free` 只保存，不直標 FREE_NOW。
     */
    fun parseModels(body: String): DedicatedResult<List<AmdModelEntry>> {
        val evidence = DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.OFFICIAL_DOC)
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return DedicatedResult.Failure(DedicatedStatus.MALFORMED, evidence)
        val data = runCatching { root["data"]!!.jsonArray }.getOrNull()
            ?: return DedicatedResult.Failure(DedicatedStatus.MALFORMED, evidence)
        val out = ArrayList<AmdModelEntry>(data.size)
        for (element in data) {
            val obj = element as? JsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: continue
            val pricing = obj["pricing"] as? JsonObject
            out += AmdModelEntry(
                id = id,
                promptPerTokenUsd = pricing?.decimalOrNull("prompt"),
                completionPerTokenUsd = pricing?.decimalOrNull("completion"),
                cacheReadPerTokenUsd = pricing?.decimalOrNull("input_cache_read"),
                contextLength = (obj["context_length"] as? JsonPrimitive)?.longOrNull,
                free = (obj["free"] as? JsonPrimitive)?.booleanOrNull,
                stability = (obj["stability"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 },
            )
        }
        return DedicatedResult.Success(evidence, out)
    }

    // ---- 免費判定（pricing 精確值 + free 旗標 + spend 行為；不用名稱） ----

    /**
     * 簽名刻意不收 model name。`free` 旗標 + pricing 零值合成證據；任一未知 → Unknown。
     * 最終 FREE_NOW 仍需 PricingPolicy（必要 effective 零價）+ telemetry，本函式只給輸入。
     */
    fun assessFree(
        pricingPromptUsd: BigDecimal?,
        pricingCompletionUsd: BigDecimal?,
        freeFlag: Boolean?,
    ): FreeAssessment = when {
        pricingPromptUsd == null || pricingCompletionUsd == null || freeFlag == null ->
            FreeAssessment.Unknown("pricing 或 free 旗標未知則 Unknown，不猜")
        freeFlag && pricingPromptUsd.signum() == 0 && pricingCompletionUsd.signum() == 0 ->
            FreeAssessment.Free("free:true + prompt/completion 精確零價（official）")
        else -> FreeAssessment.Unknown("證據不足或非零價，不判定免費（Unknown≠Paid 斷言）")
    }

    // ---- Quota（RPM 三欄 + spend；RPD/TPM/TPD Unknown；by-model 寫 Model+Credential scope） ----

    data class SpendFacts(
        val limitUsd: Double?,
        val usedUsd: Double?,
        val remainingUsd: Double?,
        /** 當次回傳的 reset（ISO8601 或 header unix），不寫死午夜。 */
        val resetAt: String?,
        val resetUnix: Long?,
    )

    /**
     * Shared quota 池：per-key / per-IP / per-account 三欄 RPM 並存，不可互蓋；
     * RPD / TPM / TPD 一律 Unknown（官方未公開）。
     */
    fun quotaPools(): List<QuotaPool> {
        val pools = listOf(
            QuotaPool(
                scopeKind = QuotaScopeKind.PER_KEY,
                modelId = null,
                rpm = QuotaDimension.known(SHARED_PLATFORM_RPM_PER_KEY),
                rpd = QuotaDimension.unknown(),
                tpm = QuotaDimension.unknown(),
                tpd = QuotaDimension.unknown(),
                resetSemantics = "SLIDING_60S",
                provenance = DedicatedProvenance.OFFICIAL_DOC,
                isGroundTruth = false,
            ),
            QuotaPool(
                scopeKind = QuotaScopeKind.PER_IP,
                modelId = null,
                rpm = QuotaDimension.known(SHARED_PLATFORM_RPM_PER_IP),
                rpd = QuotaDimension.unknown(),
                tpm = QuotaDimension.unknown(),
                tpd = QuotaDimension.unknown(),
                resetSemantics = "SLIDING_60S",
                provenance = DedicatedProvenance.OFFICIAL_DOC,
                isGroundTruth = false,
            ),
            QuotaPool(
                scopeKind = QuotaScopeKind.PER_ACCOUNT,
                modelId = null,
                rpm = QuotaDimension.known(SHARED_GATEWAY_RPM_PER_ACCOUNT),
                rpd = QuotaDimension.unknown(),
                tpm = QuotaDimension.unknown(),
                tpd = QuotaDimension.unknown(),
                resetSemantics = "ROLLING_FROM_FIRST_BILLED",
                provenance = DedicatedProvenance.OFFICIAL_DOC,
                isGroundTruth = false,
            ),
        )
        DedicatedSync.assertNoMerge(pools)
        return pools
    }

    /** Dedicated：明確無 provider 限流（瓶頸為 instance queue/GPU，超載排隊非 429）。 */
    fun dedicatedQuotaPools(): List<QuotaPool> {
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

    // ---- Usage（model-usage；502 → UNKNOWN；by-model 不合併） ----

    data class ModelUsage(val model: String, val requests: Long?, val totalTokens: Long?, val costUsd: Double?)

    data class UsageFacts(
        val status: String?,
        val rpmLimit: Long?,
        val spend: SpendFacts,
        val byModel: List<ModelUsage>,
    )

    fun parseUsage(body: String, httpStatus: Int): DedicatedResult<UsageFacts> {
        val evidence = DedicatedEvidence(
            SOURCE_USAGE, clock.epochMillis(), DedicatedProvenance.OFFICIAL_DOC, httpStatus,
        )
        if (httpStatus == 502) {
            return DedicatedResult.Failure(DedicatedStatus.HTTP_SERVER_ERROR, evidence)
        }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return DedicatedResult.Failure(DedicatedStatus.MALFORMED, evidence)
        val status = (root["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val spend = SpendFacts(
            limitUsd = (root["daily_cost_limit_usd"] as? JsonPrimitive)?.doubleOrNull,
            usedUsd = (root["daily_cost_used_usd"] as? JsonPrimitive)?.doubleOrNull,
            remainingUsd = (root["daily_cost_remaining_usd"] as? JsonPrimitive)?.doubleOrNull,
            resetAt = (root["daily_reset_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            resetUnix = null,
        )
        val byModel = ArrayList<ModelUsage>()
        val seen = HashSet<String>()
        val array = root["by_model"] as? JsonArray
        if (array != null) {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val name = (obj["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: continue
                if (!seen.add(name)) continue
                byModel += ModelUsage(
                    model = name,
                    requests = (obj["requests"] as? JsonPrimitive)?.longOrNull,
                    totalTokens = (obj["total_tokens"] as? JsonPrimitive)?.longOrNull,
                    costUsd = (obj["cost"] as? JsonPrimitive)?.doubleOrNull,
                )
            }
        }
        return DedicatedResult.Success(
            evidence,
            UsageFacts(
                status = status,
                rpmLimit = (root["rpm_limit"] as? JsonPrimitive)?.longOrNull,
                spend = spend,
                byModel = byModel,
            ),
        )
    }

    // ---- 成功 headers（即時 quota truth；減少 model-usage 輪詢） ----

    data class RateLimitHeaders(
        val userRpmLimit: Long?,
        val userRpmRemaining: Long?,
        val userRpmResetUnix: Long?,
        val dailyUsdLimit: Double?,
        val dailyUsdUsed: Double?,
        val dailyUsdRemaining: Double?,
        val dailyUsdResetUnix: Long?,
    )

    /** Header 缺失即 null（Unknown），不猜、不填 0。 */
    fun parseRateLimitHeaders(headers: Map<String, String>): RateLimitHeaders {
        fun longOf(name: String): Long? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.toLongOrNull()
        fun doubleOf(name: String): Double? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.toDoubleOrNull()
        return RateLimitHeaders(
            userRpmLimit = longOf("X-RateLimit-Limit-User-RPM"),
            userRpmRemaining = longOf("X-RateLimit-Remaining-User-RPM"),
            userRpmResetUnix = longOf("X-RateLimit-Reset-User-RPM"),
            dailyUsdLimit = doubleOf("X-RateLimit-Limit-User-Daily-USD"),
            dailyUsdUsed = doubleOf("X-RateLimit-Used-User-Daily-USD"),
            dailyUsdRemaining = doubleOf("X-RateLimit-Remaining-User-Daily-USD"),
            dailyUsdResetUnix = longOf("X-RateLimit-Reset-User-Daily-USD"),
        )
    }

    // ---- Key 形狀（rc- + 48hex，共 51 chars；B） ----

    fun isKeyShapeValid(raw: String?): Boolean {
        if (raw == null || raw.length != 51 || !raw.startsWith("rc-")) return false
        return raw.drop(3).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    // ---- 錯誤語意（雙 envelope；status + Retry-After 為準；429/402/410 分開） ----

    /**
     * 讀 `body.error ?? body.detail.error ?? body.detail`；410 在 AMD 無定義 → UNKNOWN 保留；
     * 429 一律 honour Retry-After（per-minute 60 / concurrency 1 / spend 剩餘秒數由呼叫端讀 header）。
     */
    fun classifyHttpStatus(httpStatus: Int, retryAfterSeconds: Long? = null): DedicatedError {
        val retryable = httpStatus == 429 || httpStatus == 502 || httpStatus == 503
        return when (httpStatus) {
            429 -> DedicatedError(httpStatus, "rate_limit_exceeded", DedicatedErrorCategory.RATE_LIMITED_MINUTE, true, DedicatedProvenance.OFFICIAL_DOC)
            402 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
            410 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
            401 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.AUTH_INVALID, false, DedicatedProvenance.OFFICIAL_DOC)
            403 -> DedicatedError(httpStatus, "account_not_verified", DedicatedErrorCategory.AUTH_OK_BUT_INFERENCE_DENIED, false, DedicatedProvenance.OFFICIAL_DOC)
            404 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OFFICIAL_DOC)
            400 -> DedicatedError(httpStatus, null, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OFFICIAL_DOC)
            else -> DedicatedError(httpStatus, null, if (retryable) DedicatedErrorCategory.RETRYABLE else DedicatedErrorCategory.UNKNOWN, retryable, DedicatedProvenance.OBSERVED)
        }
    }

    /** 從 body 抽 provider code（gateway OpenAI 形 / platform FastAPI 形 / Anthropic 形兼容）。 */
    fun extractProviderCode(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun codeOf(obj: JsonObject?): String? {
            val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull?.trim()
            return code?.takeIf { it.isNotEmpty() && it.length <= 128 }
        }
        (root["error"] as? JsonObject)?.let { codeOf(it)?.let { return it } }
        (root["detail"] as? JsonObject)?.let { detail ->
            (detail["error"] as? JsonObject)?.let { codeOf(it)?.let { return it } }
            codeOf(detail)?.let { return it }
        }
        (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 128 }?.let { return it }
        return null
    }

    /** `400 Requested model <name> not supported` → 重抓 catalog，不刪 last-good。 */
    fun isModelNotSupported400(httpStatus: Int, body: String): Boolean {
        if (httpStatus != 400) return false
        return body.contains("not supported", ignoreCase = true)
    }

    fun availabilityOf(httpStatus: Int, body: String): ModelAvailability = when {
        httpStatus == 200 -> ModelAvailability.VERIFIED_USABLE
        httpStatus == 429 || httpStatus == 502 || httpStatus == 503 -> ModelAvailability.TEMPORARILY_UNAVAILABLE
        httpStatus == 401 || httpStatus == 403 -> ModelAvailability.NOT_AVAILABLE_FOR_ACCOUNT
        isModelNotSupported400(httpStatus, body) -> ModelAvailability.CATALOG_LISTED
        else -> ModelAvailability.UNKNOWN
    }

    // ---- HTTP boundary（GET /v1/models；Bearer；不跟 redirect；錯誤 body 不讀） ----

    suspend fun discoverModels(baseUrl: String, apiKeyRef: String): DedicatedResult<List<AmdModelEntry>> {
        val base = validatedBase(baseUrl)
            ?: return DedicatedResult.Failure(
                DedicatedStatus.INPUT_INVALID,
                DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.OFFICIAL_DOC),
            )
        val url = base.newBuilder().encodedPath(MODELS_PATH).build()
        val token = resolveCredential(apiKeyRef, originOf(url.toString()))
            ?: return DedicatedResult.Failure(
                DedicatedStatus.CREDENTIAL_UNAVAILABLE,
                DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.OFFICIAL_DOC),
            )
        val request = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token").build()
        return when (val exchange = exchange(request, MAX_BODY_BYTES)) {
            is Exchange.Failed -> DedicatedResult.Failure(
                exchange.status,
                DedicatedEvidence(SOURCE_MODELS, clock.epochMillis(), DedicatedProvenance.OFFICIAL_DOC, exchange.httpStatus),
            )
            is Exchange.Body -> parseModels(exchange.content)
        }
    }

    private fun JsonObject.decimalOrNull(name: String): BigDecimal? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) {
            val raw = primitive.content.trim()
            if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return null
            return runCatching { BigDecimal(raw) }.getOrNull()
        }
        return primitive.contentOrNull?.let { runCatching { BigDecimal(it) }.getOrNull() }
    }

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
        val source = response.body?.source() ?: return Exchange.Body("")
        val buffer = Buffer()
        while (buffer.size <= limitBytes) {
            if (source.read(buffer, limitBytes + 1 - buffer.size) < 0L) break
        }
        if (buffer.size > limitBytes) return Exchange.Failed(DedicatedStatus.RESPONSE_TOO_LARGE, response.code)
        return Exchange.Body(buffer.readUtf8())
    }

    private fun transportStatus(e: IOException): DedicatedStatus = when (e) {
        is javax.net.ssl.SSLException -> DedicatedStatus.TLS_FAILED
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
        const val OFFICIAL_SHARED_BASE_URL = "https://developer.amd.com.cn/radeon/api/v1"
        const val MODELS_PATH = "/v1/models"
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val MESSAGES_PATH = "/v1/messages"

        const val SHARED_PLATFORM_RPM_PER_KEY = 30L
        const val SHARED_PLATFORM_RPM_PER_IP = 120L
        const val SHARED_GATEWAY_RPM_PER_ACCOUNT = 20L
        const val SHARED_PLATFORM_CONCURRENCY_PER_KEY = 8L

        const val SOURCE_MODELS = "amd/v1/models"
        const val SOURCE_USAGE = "amd/api/profile/model-usage"

        const val MAX_BODY_BYTES = 1_048_576L
    }
}
