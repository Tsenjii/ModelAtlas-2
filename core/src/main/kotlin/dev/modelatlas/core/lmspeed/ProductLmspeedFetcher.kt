package dev.modelatlas.core.lmspeed

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.SystemClock
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * P7 S3 公開端點 fetcher（TRACEABILITY LMS-001 import/discovery seed、LMS-005 free/provider seed workflow；
 * PRODUCT_SPEC §13 enrichment＋discovery seed、非 ground truth；RESEARCH_POLICY §7）。
 *
 * 來源依據（只讀，不含任何 key / PAT / 帳號資料）：
 * - `docs/LMSPEED_API_CONTRACT.md` §1（`x-api-key` 公開 key、不過期、**1000 requests/day**、
 *   公開展示須 backlink `https://lmspeed.net`、官方要求自行快取）／§2（只取
 *   `GET /api/v1/models`、`GET /api/v1/models/{slug}`；`benchmarks`／`pricing`／
 *   `provenance/{publicId}` 在 T1–T3 schema 補齊前不得寫入）／§2.3（未帶 key 一律 401
 *   `{"error":{"code","message","details"}}`）／§7-6（`lane=provisional`／
 *   `includeDisplayOnly=true` 降 confidence，由 S2 [LmspeedImporter] 處理，本檔不碰）。
 * - `docs/LMSPEED_DISCOVERY_RESEARCH.md` §3／§6（public key 限額內取、不得繞 robots 直爬 `/api/（萬用字元）`）。
 * - S2 `core/lmspeed/LmspeedImporter.kt`（[LmspeedFetcher] 注入點：本檔即其產品實作；
 *   `canonicalModelId` 恆 null 不猜、失敗保舊、TTL 由呼叫端傳入）。
 * - `app/data/ExternalStore.kt`（落庫語意：source 標記、fetchedAt、TTL、confidence；失敗不刪舊資料）。
 *
 * 鐵律（違者視為產品缺陷）：
 * - 只傳 safe ref：本檔唯一憑證欄位是 [safeCredentialId]（不透明佔位字串）。
 *   原始 public key 只在 [fetchModels] 內經 [CredentialAccess.bearerToken] 即時解析為
 *   區域變數，轉交 [LmspeedHttpTransport] 後即丟棄；**不存欄位、不進 log／exception／toString**。
 *   例外訊息只含分類名＋HTTP status，絕不含 key 本文、絕不回傳 vault 內部訊息。
 * - 只打公開端點：transport 實作必須只打 `https://lmspeed.net/api/v1/models`（list）與
 *   `https://lmspeed.net/api/v1/models/{slug}`（detail）；不得打 `benchmarks`／`pricing`／
 *   `provenance`（T1–T3 未補前）、不得用 `lmspeed.com`／`lmspeed.co`、不得為繞配額切換 PATH、
 *   不得直爬 `/api/（萬用字元）` 繞 `robots.txt`。本檔以 [LMSPEED_FETCH_ORIGIN] 綁定 vault origin。
 * - 單日上限：每次網路前經 [LmspeedDailyBudget.tryConsume] 扣配額（預設 1000/day，
 *   見 [LMSPEED_MAX_REQUESTS_PER_DAY]）；超限即丟 [LmspeedFetchFailure.DAILY_BUDGET_EXHAUSTED]，
 *   不碰 vault、不打網路。配額耗盡屬已花費：已扣即不退（429／5xx 亦耗配額，server 端同樣計數）。
 *   vault 拒絕／空白 key（[LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE]）不扣配額。
 * - 失敗分類：HTTP／傳輸例外一律轉 [LmspeedFetchException]（category＋httpStatus，
 *   無 body、無 header、無 key）；[LmspeedImporter] 接到即回 `Failure` 並保留舊資料。
 *   [CancellationException] 照常傳播（不吞取消、不扣？已扣不退，見上）。
 * - Unknown 不猜：本檔只搬運 [LmspeedRawModel]（slug 空白等非法筆由 importer 略過）；
 *   不合成 canonical、不填零值、不把 `stats`／`pricing_summary` 寫入 local telemetry。
 */
const val LMSPEED_FETCH_ORIGIN = "https://lmspeed.net"

/** 公開 key 配額：1000 requests/day（contract §1，B 級官方文件觀察）。 */
const val LMSPEED_MAX_REQUESTS_PER_DAY = 1000

const val LMSPEED_MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

/**
 * LMSpeed 公開 key 的 safe ref（非 secret，僅佔位名）。
 * 真值只存 vault；本常數絕不攜帶 key 本文。未配置（vault 無此 ref）即不可同步。
 */
const val LMSPEED_SAFE_CREDENTIAL_ID = "lmspeed-public-key"

/** Fetch 失敗分類（只記分類＋HTTP status；無 body／header／key，Unknown 不冒充已知）。 */
enum class LmspeedFetchFailure {
    DAILY_BUDGET_EXHAUSTED,
    CREDENTIAL_UNAVAILABLE,
    AUTH,
    RATE_LIMIT,
    NOT_FOUND,
    CLIENT,
    SERVER,
    NETWORK,
    TIMEOUT,
    TLS,
    UNKNOWN,
}

/**
 * 已分類的 fetch 失敗（給 [LmspeedImporter] 轉 `Failure` 用）。
 * [message] 只含分類名，絕不含 key／vault 內文／server body。
 */
class LmspeedFetchException(
    val category: LmspeedFetchFailure,
    val httpStatus: Int? = null,
) : Exception("lmspeed fetch failed: $category") {
    override fun toString(): String =
        "LmspeedFetchException(category=$category, httpStatus=$httpStatus)"
}

/**
 * 實際 HTTP 呼叫注入點（產品以 OkHttp 實作；本檔不綁 HTTP client 以利測試）。
 *
 * 產品實作必須：只打 `GET /api/v1/models`（`q/page/page_size/sort/locale/include`）與
 * `GET /api/v1/models/{slug}`；`x-api-key: <apiKey>`（[apiKey] 為 transient，**不得保存、
 * 不得記錄、不得放入例外訊息**）；單 request、禁 redirect、禁重試、有界讀取；
 * HTTP 非 2xx 即丟 [LmspeedFetchException]（見 [ProductLmspeedFetcher.classifyHttpStatus]）；
 * 憑證回聲（response 含 key 變體）必須剔除該筆，絕不回傳。
 */
fun interface LmspeedHttpTransport {
    suspend fun fetch(apiKey: String): List<LmspeedRawModel>
}

/** 單日配額帳本（每 safe ref 一本；跨 key 不共用，X-004 scope 隔離）。 */
interface LmspeedDailyBudget {
    /** 當日剩餘（device-local day 由呼叫端 [nowMillis] 決定，不猜時間）。 */
    fun remaining(nowMillis: Long): Int

    /**
     * 扣一次配額；成功回 true，無額度回 false（不丟例外、不透支）。
     * 同一天內第 1001 次回 false。
     */
    fun tryConsume(nowMillis: Long): Boolean
}

/** 記憶體單日帳本（預設 1000/day；按 device-local 日期換日重置）。
 *
 * 修復：舊實作以 `floorDiv(now, DAY)`（epoch UTC 日）切分，與裝置本地日不一致
 * （時區非 UTC 時換日點漂移）。現改以 device-local [LocalDate] 切分，與
 * `fetchedAt`／UI 顯示同源。reset 語義僅為本地節流閘門：server 端 1000/day
 * 重置點未證實（時區／滾動窗口未知），本地只做保守限流，不宣稱與 server 對齊。
 */
class InMemoryLmspeedDailyBudget(
    private val maxPerDay: Int = LMSPEED_MAX_REQUESTS_PER_DAY,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) : LmspeedDailyBudget {
    private var day: java.time.LocalDate? = null
    private var used: Int = 0

    @Synchronized
    override fun remaining(nowMillis: Long): Int {
        require(nowMillis >= 0L) { "nowMillis must be >= 0" }
        maybeRoll(nowMillis)
        return (maxPerDay - used).coerceAtLeast(0)
    }

    @Synchronized
    override fun tryConsume(nowMillis: Long): Boolean {
        require(nowMillis >= 0L) { "nowMillis must be >= 0" }
        maybeRoll(nowMillis)
        if (used >= maxPerDay) return false
        used++
        return true
    }

    private fun maybeRoll(nowMillis: Long) {
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (today != day) {
            day = today
            used = 0
        }
    }
}

/**
 * 公開端點產品 fetcher（[LmspeedFetcher] 的產品實作；S2 importer 的注入對象）。
 *
 * @param safeCredentialId 公開 key 的 safe ref（佔位，非 key 本文；blank 即程式錯誤）。
 * @param credentials 特權 vault 存取（只在呼叫當下解析，不保存結果）。
 * @param transport 實際 HTTP（只收 transient key；見 [LmspeedHttpTransport] 契約）。
 * @param budget 單日帳本（每 safe ref 一本，不共用）。
 * @param clock device-local 時間（day 切分與 `fetchedAt` 同源）。
 */
class ProductLmspeedFetcher(
    private val safeCredentialId: String,
    private val credentials: CredentialAccess,
    private val transport: LmspeedHttpTransport,
    private val budget: LmspeedDailyBudget = InMemoryLmspeedDailyBudget(),
    private val clock: Clock = SystemClock,
) : LmspeedFetcher {

    init {
        require(safeCredentialId.isNotBlank()) { "safeCredentialId must not be blank" }
    }

    override suspend fun fetchModels(): List<LmspeedRawModel> {
        val now = clock.epochMillis()
        // 先看額度：無額度即停，不碰 vault、不打網路。
        if (budget.remaining(now) <= 0) {
            throw LmspeedFetchException(LmspeedFetchFailure.DAILY_BUDGET_EXHAUSTED)
        }
        // 特權邊界：只在當下解析；vault 拒絕即 CREDENTIAL_UNAVAILABLE（不扣配額）。
        val apiKey: String = try {
            credentials.bearerToken(safeCredentialId, LMSPEED_FETCH_ORIGIN)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LmspeedFetchException(LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE)
        }
        if (apiKey.isBlank() || '\r' in apiKey || '\n' in apiKey) {
            throw LmspeedFetchException(LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE)
        }
        // 解析成功才扣配額（避免 vault 失敗浪費額度）；扣不到表示併發耗盡。
        if (!budget.tryConsume(now)) {
            throw LmspeedFetchException(LmspeedFetchFailure.DAILY_BUDGET_EXHAUSTED)
        }
        // 網路 exactly-once：分類後丟出，訊息不含 key；取消照常傳播。
        try {
            return transport.fetch(apiKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LmspeedFetchException) {
            throw e
        } catch (e: IOException) {
            throw LmspeedFetchException(classifyTransportError(e))
        } catch (_: Exception) {
            throw LmspeedFetchException(LmspeedFetchFailure.UNKNOWN)
        }
    }

    /** 當日剩餘配額（供排程／UI 顯示；不扣額）。 */
    fun remainingToday(): Int = budget.remaining(clock.epochMillis())

    // 絕不洩漏 key：本檔無 key 欄位；toString 只露 safe ref 存在性狀，不露值。
    override fun toString(): String = "ProductLmspeedFetcher(origin=$LMSPEED_FETCH_ORIGIN)"

    companion object {
        /**
         * HTTP status → 失敗分類（contract §2.3＋文件 400/404/429 列舉；body 未取得前只看 status）。
         * 401／403→AUTH；404→NOT_FOUND；429→RATE_LIMIT；其餘 4xx→CLIENT；5xx→SERVER。
         */
        fun classifyHttpStatus(httpStatus: Int): LmspeedFetchFailure = when (httpStatus) {
            401, 403 -> LmspeedFetchFailure.AUTH
            404 -> LmspeedFetchFailure.NOT_FOUND
            429 -> LmspeedFetchFailure.RATE_LIMIT
            in 400..499 -> LmspeedFetchFailure.CLIENT
            in 500..599 -> LmspeedFetchFailure.SERVER
            else -> LmspeedFetchFailure.UNKNOWN
        }

        /** 傳輸例外 → 分類（只看型別，不看訊息；訊息可能含環境資訊）。 */
        fun classifyTransportError(e: IOException): LmspeedFetchFailure = when (e) {
            is SSLException -> LmspeedFetchFailure.TLS
            is UnknownHostException -> LmspeedFetchFailure.NETWORK
            is SocketTimeoutException -> LmspeedFetchFailure.TIMEOUT
            is InterruptedIOException -> LmspeedFetchFailure.TIMEOUT
            else -> LmspeedFetchFailure.NETWORK
        }
    }
}
