package dev.modelatlas.core.newapi

import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.SystemClock
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.network.redactSecrets
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
 * New API 唯讀 adapter（Phase 4）。
 *
 * 研究依據：`docs/ADAPTER_NEWAPI_RESEARCH.md`（觀測日期 2026-09-27，
 * New API `v1.0.0-rc.25` tag 原始碼為準，seekai.cc 實機觀測為輔）。
 *
 * 設計原則：
 * - **version-aware**：只認證 `v1.0.0-rc.25`；其餘版本保留 UNKNOWN，不回報任何
 *   version-specific endpoint、不猜相容、不對未知端點發請求。
 * - **憑證分離**：`AccountPatRef` 只送 `/api/…`；`InferenceKeyRef` 只送
 *   `/api/usage/token/`。原始值只在 HTTP 邊界由 [CredentialAccess] 取值。
 * - **安全傳輸**：只接受 HTTPS（測試用的 loopback escape hatch 除外）、origin 綁定、
 *   不跟 redirect、不自動重試、只讀成功 body 且有大小上限；錯誤 body 完全不讀。
 * - **不記不洩**：不寫任何 log；不把 token、list key、PII、server `message`、
 *   錯誤 body 或原始 response 流出；notice/about 原文不帶出。
 * - **Unknown ≠ empty/0**：欄位缺失為 null；解析失敗為 MALFORMED；成功空陣列才是
 *   真正的 empty。401/429 不 fallback、不 retry。
 * - **pricing 只給事實**：精確 decimal 原值；永不 eval `billing_expr`、不做換算。
 */
class NewApiAdapter(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock,
    // 只供 loopback 單測使用；產品路徑永不開啟。
    private val allowLoopbackHttpForTests: Boolean = false,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** `GET /api/status`：無需憑證；版本／能力偵測的唯一來源。 */
    suspend fun fetchCapabilities(baseUrl: String): NewApiResult<NewApiCapabilities> {
        val base = validatedBase(baseUrl)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_STATUS, null)
        val request = getRequest(endpoint(base, NewApiEndpoint.STATUS), null)
        return when (val exchange = exchange(request, MAX_STATUS_BYTES)) {
            is Exchange.Failed -> failure(exchange.status, SOURCE_STATUS, null, exchange.httpStatus)
            is Exchange.Body -> parseCapabilities(exchange)
        }
    }

    /** `GET /api/notice`：無需憑證；只回公告是否存在，不帶出原文。 */
    suspend fun fetchNotice(
        baseUrl: String,
        version: NewApiVersion,
    ): NewApiResult<NewApiNotice> {
        val base = validatedBase(baseUrl)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_NOTICE, version)
        versionGate(version, SOURCE_NOTICE)?.let { return it }
        val request = getRequest(endpoint(base, NewApiEndpoint.NOTICE), null)
        return when (val exchange = exchange(request, MAX_NOTICE_BYTES)) {
            is Exchange.Failed -> failure(exchange.status, SOURCE_NOTICE, version, exchange.httpStatus)
            is Exchange.Body -> parseNotice(exchange, version)
        }
    }

    /** `GET /api/pricing`：需 `AccountPatRef`（PAT）；唯一價格 ground truth。 */
    suspend fun fetchPricing(
        baseUrl: String,
        pat: AccountPatRef,
        version: NewApiVersion,
    ): NewApiResult<NewApiPricing> {
        val base = validatedBase(baseUrl)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_PRICING, version)
        versionGate(version, SOURCE_PRICING)?.let { return it }
        val url = endpoint(base, NewApiEndpoint.PRICING)
        val token = resolveCredential(pat.value, EndpointPolicy.origin(url))
            ?: return failure(NewApiStatus.CREDENTIAL_UNAVAILABLE, SOURCE_PRICING, version)
        val request = getRequest(url, token)
        return when (val exchange = exchange(request, MAX_PRICING_BYTES)) {
            is Exchange.Failed -> failure(exchange.status, SOURCE_PRICING, version, exchange.httpStatus)
            is Exchange.Body -> parsePricing(exchange, token, version)
        }
    }

    /** `GET /api/user/self`：需 `AccountPatRef`（PAT）；帳號層級 quota。 */
    suspend fun fetchAccountQuota(
        baseUrl: String,
        pat: AccountPatRef,
        version: NewApiVersion,
    ): NewApiResult<NewApiAccountQuota> {
        val base = validatedBase(baseUrl)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_ACCOUNT, version)
        versionGate(version, SOURCE_ACCOUNT)?.let { return it }
        val url = endpoint(base, NewApiEndpoint.ACCOUNT_SELF)
        val token = resolveCredential(pat.value, EndpointPolicy.origin(url))
            ?: return failure(NewApiStatus.CREDENTIAL_UNAVAILABLE, SOURCE_ACCOUNT, version)
        val request = getRequest(url, token)
        return when (val exchange = exchange(request, MAX_QUOTA_BYTES)) {
            is Exchange.Failed -> failure(exchange.status, SOURCE_ACCOUNT, version, exchange.httpStatus)
            is Exchange.Body -> parseAccountQuota(exchange, token, version)
        }
    }

    /** `GET /api/usage/token/`：需 `InferenceKeyRef`；Key 層級 quota。 */
    suspend fun fetchTokenQuota(
        baseUrl: String,
        key: InferenceKeyRef,
        version: NewApiVersion,
    ): NewApiResult<NewApiTokenQuota> {
        val base = validatedBase(baseUrl)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_TOKEN, version)
        versionGate(version, SOURCE_TOKEN)?.let { return it }
        val url = endpoint(base, NewApiEndpoint.TOKEN_USAGE)
        val token = resolveCredential(key.value, EndpointPolicy.origin(url))
            ?: return failure(NewApiStatus.CREDENTIAL_UNAVAILABLE, SOURCE_TOKEN, version)
        val request = getRequest(url, token)
        return when (val exchange = exchange(request, MAX_QUOTA_BYTES)) {
            is Exchange.Failed -> failure(exchange.status, SOURCE_TOKEN, version, exchange.httpStatus)
            is Exchange.Body -> parseTokenQuota(exchange, token, version)
        }
    }

    /**
     * `GET /api/perf-metrics/summary?hours={H}`：站方全站 model overview。
     *
     * 這是 **PROVIDER_WIDE** 資料，不是 MY_KEY_LOCAL；`success_rate` 為 rc.25
     * provider-relay 語意（含把 client-cancel 計為 success），不得併入 My Uptime。
     *
     * [pat] 為選用 dashboard 憑證：提供時帶 PAT，否則匿名（站方設定決定是否要求）；
     * 缺少 credential 本身不是錯誤。Inference key 永不進 `/api/…`。
     * [hours] 預設 24、client 端正規化到 1..720（見 [NewApiPerfWindow]）。
     */
    suspend fun fetchPerfSummary(
        baseUrl: String,
        version: NewApiVersion,
        pat: AccountPatRef? = null,
        hours: Int = NewApiPerfWindow.DEFAULT_HOURS,
    ): NewApiResult<NewApiPerfSummary> {
        val base = validatedBase(baseUrl)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_PERF_SUMMARY, version)
        versionGate(version, SOURCE_PERF_SUMMARY)?.let { return it }
        val url = perfEndpoint(
            base = base,
            endpoint = NewApiPerfEndpoint.SUMMARY,
            model = null,
            hours = NewApiPerfWindow.normalize(hours),
            group = null,
        )
        val token = pat?.let {
            resolveCredential(it.value, EndpointPolicy.origin(url))
                ?: return failure(NewApiStatus.CREDENTIAL_UNAVAILABLE, SOURCE_PERF_SUMMARY, version)
        }
        val request = getRequest(url, token)
        return when (val exchange = exchange(request, MAX_PERF_BYTES)) {
            is Exchange.Failed -> failure(exchange.status, SOURCE_PERF_SUMMARY, version, exchange.httpStatus)
            is Exchange.Body -> parsePerfSummary(exchange, version, token)
        }
    }

    /**
     * `GET /api/perf-metrics?model={M}&hours={H}&group={G?}`：單一 model、可依 group
     * 分開的站方時間序列（PROVIDER_WIDE，與 My Uptime 分離）。
     *
     * `model` 空白／不安全時在送出前即 [NewApiStatus.INPUT_INVALID]（rc.25 server 會
     * 回 400）；未知 model 是 200 + `groups: []`（typed empty），不是錯誤。
     * 空白 [group] 視同未指定（server 語意為 all groups）。
     * [pat] 與 [hours] 語意同 [fetchPerfSummary]。
     */
    suspend fun fetchPerfQuery(
        baseUrl: String,
        model: String,
        version: NewApiVersion,
        pat: AccountPatRef? = null,
        hours: Int = NewApiPerfWindow.DEFAULT_HOURS,
        group: String? = null,
    ): NewApiResult<NewApiPerfQuery> {
        val base = validatedBase(baseUrl)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_PERF_QUERY, version)
        versionGate(version, SOURCE_PERF_QUERY)?.let { return it }
        val safeModel = safeText(model, null, MAX_MODEL_NAME_CHARS)
            ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_PERF_QUERY, version)
        val safeGroup = group?.trim()?.takeIf { it.isNotEmpty() }?.let {
            safeText(it, null, MAX_GROUP_NAME_CHARS)
                ?: return failure(NewApiStatus.INPUT_INVALID, SOURCE_PERF_QUERY, version)
        }
        val url = perfEndpoint(
            base = base,
            endpoint = NewApiPerfEndpoint.QUERY,
            model = safeModel,
            hours = NewApiPerfWindow.normalize(hours),
            group = safeGroup,
        )
        val token = pat?.let {
            resolveCredential(it.value, EndpointPolicy.origin(url))
                ?: return failure(NewApiStatus.CREDENTIAL_UNAVAILABLE, SOURCE_PERF_QUERY, version)
        }
        val request = getRequest(url, token)
        return when (val exchange = exchange(request, MAX_PERF_BYTES)) {
            is Exchange.Failed -> failure(exchange.status, SOURCE_PERF_QUERY, version, exchange.httpStatus)
            is Exchange.Body -> parsePerfQuery(exchange, version, token)
        }
    }

    private fun versionGate(version: NewApiVersion, source: String): NewApiResult.Failure? =
        if (version.support == NewApiVersionSupport.CERTIFIED_RC25) {
            null
        } else {
            failure(NewApiStatus.UNKNOWN_VERSION, source, version)
        }

    private fun parseCapabilities(body: Exchange.Body): NewApiResult<NewApiCapabilities> {
        val data = successObject(body.content) ?: return malformed(SOURCE_STATUS, null, body.httpCode)
        val version = NewApiVersion.of(data.str("version"))
        val capabilities = NewApiCapabilities(
            version = version,
            quotaPerUnit = data.exactDecimal("quota_per_unit"),
            quotaDisplayType = safeText(data.str("quota_display_type"), null, MAX_TOKEN_CHARS, asciiOnly = true),
            checkinEnabled = data.boolOrNull("checkin_enabled"),
            pricingModule = parseModuleAccess(data.str("HeaderNavModules")),
            certifiedEndpoints = version.certifiedEndpoints,
        )
        return NewApiResult.Success(evidence(SOURCE_STATUS, version, body.httpCode), capabilities)
    }

    private fun parseNotice(body: Exchange.Body, version: NewApiVersion): NewApiResult<NewApiNotice> {
        val root = parseObjectOrNull(body.content) ?: return malformed(SOURCE_NOTICE, version, body.httpCode)
        if (root.boolOrNull("success") != true) return malformed(SOURCE_NOTICE, version, body.httpCode)
        val data = root["data"]
        // 只判斷存在性，不讀內容。
        val present = data != null && data !is JsonNull
        return NewApiResult.Success(evidence(SOURCE_NOTICE, version, body.httpCode), NewApiNotice(present))
    }

    private fun parsePricing(
        body: Exchange.Body,
        token: String,
        version: NewApiVersion,
    ): NewApiResult<NewApiPricing> {
        val root = parseObjectOrNull(body.content) ?: return malformed(SOURCE_PRICING, version, body.httpCode)
        if (root.boolOrNull("success") != true) return malformed(SOURCE_PRICING, version, body.httpCode)
        val data = root["data"] as? JsonArray ?: return malformed(SOURCE_PRICING, version, body.httpCode)
        var skipped = 0
        val items = ArrayList<NewApiPricingItem>(data.size)
        for (element in data) {
            val item = (element as? JsonObject)?.let { parsePricingItem(it, token) }
            if (item == null) skipped++ else items += item
        }
        val pricingVersion = safeText(root.str("pricing_version"), token, MAX_VERSION_HASH_CHARS, asciiOnly = true)
        val pricing = NewApiPricing(
            items = items,
            skippedItems = skipped,
            groupRatio = parseRatioMap(root["group_ratio"], token),
            usableGroups = parseGroupNames(root["usable_group"], token),
            supportedEndpoint = parseSupportedEndpoints(root["supported_endpoint"], token),
            autoGroups = parseStringArray(root["auto_groups"], token, MAX_GROUP_NAME_CHARS),
            pricingVersion = pricingVersion,
        )
        return NewApiResult.Success(
            evidence(SOURCE_PRICING, version, body.httpCode, pricingVersion),
            pricing,
        )
    }

    private fun parsePricingItem(obj: JsonObject, token: String?): NewApiPricingItem? {
        val modelName = safeText(obj.str("model_name"), token, MAX_MODEL_NAME_CHARS) ?: return null
        return NewApiPricingItem(
            modelName = modelName,
            quotaType = obj.intOrNull("quota_type"),
            modelRatio = obj.exactDecimal("model_ratio"),
            modelPrice = obj.exactDecimal("model_price"),
            completionRatio = obj.exactDecimal("completion_ratio"),
            cacheRatio = obj.exactDecimal("cache_ratio"),
            createCacheRatio = obj.exactDecimal("create_cache_ratio"),
            imageRatio = obj.exactDecimal("image_ratio"),
            audioRatio = obj.exactDecimal("audio_ratio"),
            audioCompletionRatio = obj.exactDecimal("audio_completion_ratio"),
            enableGroups = parseStringArray(obj["enable_groups"], token, MAX_GROUP_NAME_CHARS),
            supportedEndpointTypes = parseStringArray(obj["supported_endpoint_types"], token, MAX_TOKEN_CHARS),
            billingMode = safeText(obj.str("billing_mode"), token, MAX_TOKEN_CHARS, asciiOnly = true),
            // 僅傳遞原文事實；永不求值（換算由 PricingExpressionParser session 處理）。
            billingExpression = safeExpression(obj.str("billing_expr"), token),
            vendorId = obj.intOrNull("vendor_id"),
            pricingVersion = safeText(obj.str("pricing_version"), token, MAX_VERSION_HASH_CHARS, asciiOnly = true),
        )
    }

    private fun parseAccountQuota(
        body: Exchange.Body,
        token: String,
        version: NewApiVersion,
    ): NewApiResult<NewApiAccountQuota> {
        val data = successObject(body.content) ?: return malformed(SOURCE_ACCOUNT, version, body.httpCode)
        val quota = NewApiAccountQuota(
            quota = data.longOrNull("quota"),
            usedQuota = data.longOrNull("used_quota"),
            requestCount = data.longOrNull("request_count"),
            group = safeText(data.str("group"), token, MAX_GROUP_NAME_CHARS),
            role = data.intOrNull("role"),
            status = data.intOrNull("status"),
        )
        return NewApiResult.Success(evidence(SOURCE_ACCOUNT, version, body.httpCode), quota)
    }

    private fun parseTokenQuota(
        body: Exchange.Body,
        token: String,
        version: NewApiVersion,
    ): NewApiResult<NewApiTokenQuota> {
        val root = parseObjectOrNull(body.content) ?: return malformed(SOURCE_TOKEN, version, body.httpCode)
        // 此端點封套用 `code` 而非 `success`（研究 §2）。
        if (root.boolOrNull("code") != true) return malformed(SOURCE_TOKEN, version, body.httpCode)
        val data = root["data"] as? JsonObject ?: return malformed(SOURCE_TOKEN, version, body.httpCode)
        val unlimited = data.boolOrNull("unlimited_quota")
        val available = data.longOrNull("total_available")
        val expiresAtRaw = data.longOrNull("expires_at")
        val expired = expiresAtRaw?.takeIf { it > 0 }?.let { it < clock.epochMillis() / 1000L }
        val expiryMeaning = when {
            expiresAtRaw == null -> NewApiExpiryMeaning.UNKNOWN
            expiresAtRaw == 0L -> NewApiExpiryMeaning.ZERO_AMBIGUOUS
            else -> NewApiExpiryMeaning.ABSOLUTE_EPOCH_SECONDS
        }
        val state = when {
            unlimited == true -> QuotaState.UNLIMITED_CLAIMED
            unlimited == false && available != null && available <= 0 -> QuotaState.QUOTA_EXHAUSTED
            expired == true -> QuotaState.QUOTA_EXHAUSTED
            available != null -> QuotaState.QUOTA_AVAILABLE
            else -> QuotaState.UNKNOWN
        }
        val quota = NewApiTokenQuota(
            totalGranted = data.longOrNull("total_granted"),
            totalUsed = data.longOrNull("total_used"),
            totalAvailable = available,
            unlimitedQuota = unlimited,
            modelLimitsEnabled = data.boolOrNull("model_limits_enabled"),
            expiresAtRaw = expiresAtRaw,
            expiryMeaning = expiryMeaning,
            expired = expired,
            state = state,
        )
        return NewApiResult.Success(evidence(SOURCE_TOKEN, version, body.httpCode), quota)
    }

    /**
     * 嚴格解析 rc.25 perf envelope：200 `{success:true,data:{…}}`；`success` 非 true、
     * `data` 非物件、必要欄位缺失或型別不符 ⇒ [NewApiStatus.MALFORMED]。未知 key 一律
     * 忽略（forward-tolerant），但 main-only 的 `summary`/`series`/`window_*` 永不當成
     * rc.25 必要欄位。
     *
     * 400/500 的 `{success:false,message}` 在 HTTP 邊界即已映射為失敗且錯誤 body 完全
     * 不讀，因此 raw driver message 不會進入本 parser、不會被保留或外洩。
     */
    private fun parsePerfSummary(
        body: Exchange.Body,
        version: NewApiVersion,
        token: String?,
    ): NewApiResult<NewApiPerfSummary> {
        val data = successObject(body.content)
            ?: return malformed(SOURCE_PERF_SUMMARY, version, body.httpCode)
        val modelsJson = data["models"] as? JsonArray
            ?: return malformed(SOURCE_PERF_SUMMARY, version, body.httpCode)
        val models = ArrayList<NewApiPerfModelSummary>(modelsJson.size)
        for (element in modelsJson) {
            val obj = element as? JsonObject
                ?: return malformed(SOURCE_PERF_SUMMARY, version, body.httpCode)
            models += parsePerfModelSummary(obj, token)
                ?: return malformed(SOURCE_PERF_SUMMARY, version, body.httpCode)
        }
        return NewApiResult.Success(
            evidence(SOURCE_PERF_SUMMARY, version, body.httpCode),
            NewApiPerfSummary(models = models),
        )
    }

    private fun parsePerfModelSummary(obj: JsonObject, token: String?): NewApiPerfModelSummary? {
        val modelName = safeText(obj.str("model_name"), token, MAX_MODEL_NAME_CHARS) ?: return null
        val recent = when (val element = obj["recent_success_rates"]) {
            null, is JsonNull -> null
            is JsonArray -> ArrayList<Double>(element.size).also { rates ->
                for (item in element) rates += numericDouble(item) ?: return null
            }
            else -> return null
        }
        return NewApiPerfModelSummary(
            modelName = modelName,
            avgLatencyMs = obj.longOrNull("avg_latency_ms") ?: return null,
            successRate = obj.doubleOrNull("success_rate") ?: return null,
            avgTps = obj.doubleOrNull("avg_tps") ?: return null,
            recentSuccessRates = recent,
            // rc.25 標記 json:"-"，永不序列化；缺失為 null（Unknown），絕不補 0。
            requestCount = obj.longOrNull("request_count"),
        )
    }

    private fun parsePerfQuery(
        body: Exchange.Body,
        version: NewApiVersion,
        token: String?,
    ): NewApiResult<NewApiPerfQuery> {
        val data = successObject(body.content)
            ?: return malformed(SOURCE_PERF_QUERY, version, body.httpCode)
        val modelName = safeText(data.str("model_name"), token, MAX_MODEL_NAME_CHARS)
            ?: return malformed(SOURCE_PERF_QUERY, version, body.httpCode)
        // opaque cache/schema marker；原值 passthrough，不驗證、不寫死。
        val seriesSchema = safeText(data.str("series_schema"), token, MAX_TOKEN_CHARS)
            ?: return malformed(SOURCE_PERF_QUERY, version, body.httpCode)
        val groupsJson = data["groups"] as? JsonArray
            ?: return malformed(SOURCE_PERF_QUERY, version, body.httpCode)
        val groups = ArrayList<NewApiPerfGroup>(groupsJson.size)
        for (element in groupsJson) {
            val obj = element as? JsonObject
                ?: return malformed(SOURCE_PERF_QUERY, version, body.httpCode)
            groups += parsePerfGroup(obj, token)
                ?: return malformed(SOURCE_PERF_QUERY, version, body.httpCode)
        }
        return NewApiResult.Success(
            evidence(SOURCE_PERF_QUERY, version, body.httpCode),
            NewApiPerfQuery(modelName = modelName, seriesSchema = seriesSchema, groups = groups),
        )
    }

    private fun parsePerfGroup(obj: JsonObject, token: String?): NewApiPerfGroup? {
        val group = safeText(obj.str("group"), token, MAX_GROUP_NAME_CHARS) ?: return null
        val seriesJson = obj["series"] as? JsonArray ?: return null
        val series = ArrayList<NewApiPerfPoint>(seriesJson.size)
        for (element in seriesJson) {
            val point = (element as? JsonObject)?.let { parsePerfPoint(it) } ?: return null
            series += point
        }
        return NewApiPerfGroup(
            group = group,
            avgTtftMs = obj.longOrNull("avg_ttft_ms") ?: return null,
            avgLatencyMs = obj.longOrNull("avg_latency_ms") ?: return null,
            successRate = obj.doubleOrNull("success_rate") ?: return null,
            avgTps = obj.doubleOrNull("avg_tps") ?: return null,
            series = series,
        )
    }

    private fun parsePerfPoint(obj: JsonObject): NewApiPerfPoint? {
        val ts = obj.longOrNull("ts") ?: return null
        val ttft = obj.longOrNull("avg_ttft_ms") ?: return null
        val latency = obj.longOrNull("avg_latency_ms") ?: return null
        val rate = obj.doubleOrNull("success_rate") ?: return null
        val tps = obj.doubleOrNull("avg_tps") ?: return null
        return NewApiPerfPoint(
            tsEpochSeconds = ts,
            avgTtftMs = ttft,
            avgLatencyMs = latency,
            successRate = rate,
            avgTps = tps,
        )
    }

    private fun numericDouble(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull
    }

    private fun parseModuleAccess(rawModules: String?): NewApiModuleAccess {
        val safe = safeText(rawModules, null, MAX_MODULE_JSON_CHARS) ?: return NewApiModuleAccess(null, null)
        val root = runCatching { json.parseToJsonElement(safe) as? JsonObject }.getOrNull()
            ?: return NewApiModuleAccess(null, null)
        val module = root[PRICING_MODULE] as? JsonObject ?: return NewApiModuleAccess(null, null)
        return NewApiModuleAccess(module.boolOrNull("enabled"), module.boolOrNull("requireAuth"))
    }

    private fun parseRatioMap(element: JsonElement?, token: String?): Map<String, NewApiRatio> {
        val obj = element as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, NewApiRatio>()
        for ((rawName, value) in obj) {
            val name = safeText(rawName, token, MAX_GROUP_NAME_CHARS) ?: continue
            val primitive = value as? JsonPrimitive ?: continue
            val exact = if (primitive.isString) null else primitive.contentOrNull?.toBigDecimalOrNull()
            out[name] = NewApiRatio(exact = exact, nonNumeric = primitive.isString || exact == null)
        }
        return out
    }

    private fun parseGroupNames(element: JsonElement?, token: String?): List<String> {
        val obj = element as? JsonObject ?: return emptyList()
        return obj.keys.mapNotNull { safeText(it, token, MAX_GROUP_NAME_CHARS) }.distinct()
    }

    private fun parseSupportedEndpoints(
        element: JsonElement?,
        token: String?,
    ): Map<String, NewApiSupportedEndpoint> {
        val obj = element as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, NewApiSupportedEndpoint>()
        for ((rawType, value) in obj) {
            val type = safeText(rawType, token, MAX_TOKEN_CHARS, asciiOnly = true) ?: continue
            val spec = value as? JsonObject
            out[type] = NewApiSupportedEndpoint(
                path = safePath(spec?.str("path"), token),
                method = safeText(spec?.str("method"), token, 16, asciiOnly = true)?.uppercase(),
            )
        }
        return out
    }

    private fun parseStringArray(element: JsonElement?, token: String?, maxChars: Int): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val primitive = item as? JsonPrimitive
            if (primitive == null || !primitive.isString) return@mapNotNull null
            safeText(primitive.content, token, maxChars)
        }.distinct()
    }

    // ---- HTTP boundary ----

    private fun resolveCredential(ref: String, origin: String): String? = try {
        val token = credentials.bearerToken(ref, origin)
        if (token.isNotBlank() && token.none { it.code < 0x20 || it.code == 0x7F }) token else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // vault 拒絕不得把 vault 內部訊息帶到呼叫端。
        null
    }

    private fun getRequest(url: HttpUrl, token: String?): Request =
        Request.Builder().url(url).get().header("Accept", "application/json").apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()

    private fun endpoint(base: HttpUrl, endpoint: NewApiEndpoint): HttpUrl =
        base.newBuilder().encodedPath(endpoint.path).query(null).fragment(null).build()

    /**
     * 由已驗證的 base origin 建 perf URL（origin 綁定；只帶 GET query 參數）。
     * `model` / `hours` / `group` 皆已 client 端正規化或驗證。
     */
    private fun perfEndpoint(
        base: HttpUrl,
        endpoint: NewApiPerfEndpoint,
        model: String?,
        hours: Int,
        group: String?,
    ): HttpUrl {
        val builder = base.newBuilder()
            .encodedPath(endpoint.path)
            .query(null)
            .fragment(null)
        if (model != null) builder.addQueryParameter("model", model)
        builder.addQueryParameter("hours", hours.toString())
        if (group != null) builder.addQueryParameter("group", group)
        return builder.build()
    }

    private fun validatedBase(baseUrl: String): HttpUrl? {
        val trimmed = baseUrl.trim()
        return if (allowLoopbackHttpForTests) {
            loopbackOnly(trimmed)
        } else {
            runCatching { EndpointPolicy.validate(trimmed) }.getOrNull()
        }
    }

    private fun loopbackOnly(url: String): HttpUrl? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host !in LOOPBACK_HOSTS) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.query != null || parsed.fragment != null) return null
        return parsed
    }

    /**
     * 單次請求、單次嘗試；跑在 caller 執行緒之外，coroutine 取消時立刻取消 socket。
     */
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
                        val outcome = response.use { read(it, limitBytes) }
                        deliver(outcome)
                    }
                })
            }
        }

    /** 只讀成功 body，且上限 [limitBytes] + 1；錯誤 body 完全不讀。 */
    private fun read(response: Response, limitBytes: Long): Exchange {
        if (!response.isSuccessful) return Exchange.Failed(httpStatus(response.code), response.code)
        val source = response.body?.source() ?: return Exchange.Body(response.code, "")
        val buffer = Buffer()
        while (buffer.size <= limitBytes) {
            if (source.read(buffer, limitBytes + 1 - buffer.size) < 0L) break
        }
        if (buffer.size > limitBytes) return Exchange.Failed(NewApiStatus.RESPONSE_TOO_LARGE, response.code)
        return Exchange.Body(response.code, buffer.readUtf8())
    }

    private fun transportStatus(e: IOException): NewApiStatus = when (e) {
        is SSLException -> NewApiStatus.TLS_FAILED
        is UnknownHostException -> NewApiStatus.NETWORK_UNREACHABLE
        is SocketTimeoutException -> NewApiStatus.TIMEOUT
        is InterruptedIOException -> NewApiStatus.TIMEOUT
        else -> NewApiStatus.NETWORK_UNREACHABLE
    }

    private fun httpStatus(code: Int): NewApiStatus = when (code) {
        in 200..299 -> NewApiStatus.OK
        401 -> NewApiStatus.HTTP_UNAUTHORIZED
        403 -> NewApiStatus.HTTP_FORBIDDEN
        404 -> NewApiStatus.HTTP_NOT_FOUND
        429 -> NewApiStatus.HTTP_TOO_MANY_REQUESTS
        in 300..399 -> NewApiStatus.HTTP_REDIRECT
        in 400..499 -> NewApiStatus.HTTP_CLIENT_ERROR
        in 500..599 -> NewApiStatus.HTTP_SERVER_ERROR
        else -> NewApiStatus.HTTP_UNSUPPORTED
    }

    // ---- parsing helpers ----

    private fun successObject(content: String): JsonObject? {
        val root = parseObjectOrNull(content) ?: return null
        if (root.boolOrNull("success") != true) return null
        return root["data"] as? JsonObject
    }

    private fun parseObjectOrNull(content: String): JsonObject? =
        runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull()

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.exactDecimal(name: String): BigDecimal? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.contentOrNull?.let { runCatching { BigDecimal(it) }.getOrNull() }
    }

    private fun JsonObject.longOrNull(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.intOrNull(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.boolOrNull(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.doubleOrNull(name: String): Double? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun safePath(raw: String?, token: String?): String? {
        val value = safeText(raw, token, MAX_PATH_CHARS, asciiOnly = true) ?: return null
        return value.takeIf { it.startsWith('/') }
    }

    /**
     * 只保留安全的 server 字串：拒絕空白、超長、控制字元、非 ASCII（選用），
     * 且若與憑證或其常見編碼 echo 相符即丟棄（不洩漏、不落地）。
     */
    private fun safeText(
        raw: String?,
        token: String?,
        maxChars: Int,
        asciiOnly: Boolean = false,
    ): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.length > maxChars) return null
        if (value.any { it.code < 0x20 || it.code == 0x7F }) return null
        if (asciiOnly && value.any { it.code > 0x7E }) return null
        if (token != null && redactSecrets(value, token) != value) return null
        return value
    }

    /** `billing_expr` 原文字串；允許 parser 接受的空白，其他控制字元拒絕。 */
    private fun safeExpression(raw: String?, token: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.length > MAX_BILLING_EXPR_CHARS) return null
        if (value.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) return null
        if (token != null && redactSecrets(value, token) != value) return null
        return value
    }

    // ---- evidence ----

    private fun malformed(source: String, version: NewApiVersion?, httpStatus: Int?): NewApiResult.Failure =
        failure(NewApiStatus.MALFORMED, source, version, httpStatus)

    private fun failure(
        status: NewApiStatus,
        source: String,
        version: NewApiVersion?,
        httpStatus: Int? = null,
    ): NewApiResult.Failure = NewApiResult.Failure(status, evidence(source, version, httpStatus))

    private fun evidence(
        source: String,
        version: NewApiVersion?,
        httpStatus: Int? = null,
        pricingVersion: String? = null,
    ): NewApiEvidence = NewApiEvidence(
        source = source,
        observedAt = clock.epochMillis(),
        providerVersion = version?.raw,
        versionSupport = version?.support ?: NewApiVersionSupport.UNKNOWN,
        httpStatus = httpStatus,
        pricingVersion = pricingVersion,
    )

    private sealed interface Exchange {
        data class Failed(val status: NewApiStatus, val httpStatus: Int? = null) : Exchange
        data class Body(val httpCode: Int, val content: String) : Exchange
    }

    private companion object {
        const val SOURCE_STATUS = "newapi/api/status"
        const val SOURCE_NOTICE = "newapi/api/notice"
        const val SOURCE_PRICING = "newapi/api/pricing"
        const val SOURCE_ACCOUNT = "newapi/api/user/self"
        const val SOURCE_TOKEN = "newapi/api/usage/token"
        const val SOURCE_PERF_SUMMARY = "newapi/api/perf-metrics/summary"
        const val SOURCE_PERF_QUERY = "newapi/api/perf-metrics"
        const val PRICING_MODULE = "pricing"

        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L

        const val MAX_STATUS_BYTES = 262_144L
        const val MAX_NOTICE_BYTES = 262_144L
        const val MAX_QUOTA_BYTES = 262_144L
        const val MAX_PRICING_BYTES = 4_194_304L
        // perf summary（多模型）與 per-model series 皆可能較大；比照 pricing 上限。
        const val MAX_PERF_BYTES = 4_194_304L

        const val MAX_TOKEN_CHARS = 64
        const val MAX_MODEL_NAME_CHARS = 256
        const val MAX_GROUP_NAME_CHARS = 128
        const val MAX_PATH_CHARS = 256
        const val MAX_VERSION_HASH_CHARS = 128
        const val MAX_MODULE_JSON_CHARS = 65_536
        const val MAX_BILLING_EXPR_CHARS = 8_192

        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
