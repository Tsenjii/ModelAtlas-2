package dev.modelatlas.core.lmspeed

import dev.modelatlas.core.domain.MappingConfidence
import kotlinx.coroutines.CancellationException

/**
 * P7 S2 實作 import（TRACEABILITY LMS-001 import/discovery seed、LMS-005 free/provider seed workflow；
 * PRODUCT_SPEC §13 enrichment＋discovery seed、非 ground truth；RESEARCH_POLICY §7）。
 *
 * 來源依據（只讀，不含任何 key / PAT / 帳號資料）：
 * - `docs/LMSPEED_DISCOVERY_RESEARCH.md` §3／§6（公開 API＋候選來源 S-6／S-7 第一批 structured seed）
 * - `docs/LMSPEED_SOURCES_PART2.md` §1-3（STRUCTURED_ENDPOINT：public key 限額內取、存 urlOrEndpoint＋fetchedAt＋attribution）
 * - `docs/LMSPEED_API_CONTRACT.md` §2–§4（只取公開端點 models list／detail；benchmarks／pricing／provenance schema 未補前不得寫入）
 * - `app/data/ExternalStore.kt` 落庫語意（source 標記、fetchedAt、TTL、confidence；失敗不刪舊資料）
 * - `app/data/ExternalCanonicalMapping.kt`（canonical 解析是 LMS-002 的事，本檔一律 UNKNOWN 不猜）
 *
 * 鐵律（違者視為產品缺陷）：
 * - 只取公開端點：`GET /api/v1/models`、`GET /api/v1/models/{slug}`（contract §2 表 1–2）。
 *   `benchmarks`／`pricing`／`provenance/{publicId}` 在 T1–T3 schema 補齊前不得寫入（S-8 為 U），
 *   本檔以 [ALLOWED_ENDPOINTS] 白名單拒收非 models 端點。
 * - 正主唯一 `https://lmspeed.net`：`lmspeed.com`（停泊待售）／`lmspeed.co`（VPN）及任何非
 *   `https://lmspeed.net/` 開頭的 `lmspeedUrl` 一律略過該筆（不失敗整個 import）。
 * - external 絕不寫入 snapshot_facts／snapshot_heads／samples／telemetry：本檔只呼叫
 *   [LmspeedObservationSink]（產品接線以 `ExternalStore.saveObservations` 轉接，欄位一一對應），
 *   不做任何 purge／delete；fetch 或 sink 失敗即回 [LmspeedImportResult.Failure]，舊資料原地保留。
 * - 二手 hint 上限：預設 [MappingConfidence.LOW]（E 級第三方彙整，`≤MEDIUM`）；`lane=provisional`
 *   或 `includeDisplayOnly=true` 降為 [MappingConfidence.UNKNOWN] 並在 payload 標記（contract §7-6）。
 * - 本檔不碰 key：[LmspeedFetcher] 由產品層另行實作（public key 走 safe ref＋快取＋1000/day，
 *   不進 prompt／repo／log；`robots.txt Disallow: /api/（萬用字元）`，不得繞 robots 直爬）。
 * - Unknown 不猜：`canonicalModelId` 恆 null（映射由 LMS-002 `mapExternalToCanonical` 處理）；
 *   空白 slug 略過，不合成 canonical、不填零值。
 */
const val LMSPEED_SOURCE = "LMSPEED"

/** 公開展示 backlink（contract §1 使用條款層級要求，payload 必帶）。 */
const val LMSPEED_ATTRIBUTION_URL = "https://lmspeed.net"

/** 唯一允許的公開端點標記（contract §2 表 1–2；S-6／S-7）。 */
const val LMSPEED_MODELS_ENDPOINT = "GET /api/v1/models"
const val LMSPEED_MODEL_DETAIL_ENDPOINT = "GET /api/v1/models/{slug}"

/** 允許寫入的 endpoint 白名單；其餘（benchmarks／pricing／provenance 等）一律略過該筆。 */
val LMSPEED_ALLOWED_ENDPOINTS: Set<String> = setOf(
    LMSPEED_MODELS_ENDPOINT,
    LMSPEED_MODEL_DETAIL_ENDPOINT,
)

/** `lmspeedUrl` 唯一合法前綴（正主；`lmspeed.com`／`lmspeed.co` 皆拒）。 */
const val LMSPEED_URL_PREFIX = "https://lmspeed.net/"

/** 預設 TTL（呼叫端可覆寫；高變動頁由產品排程決定重掃頻率）。24 小時。 */
const val LMSPEED_DEFAULT_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L

/** Enrichment payload 有界（避免 hostile body OOM；超過即截斷，不失敗整批）。 */
private const val MAX_DESCRIPTION_CHARS = 4_096
private const val MAX_CONTEXT_TOKENS = 16_777_216
private const val MAX_CAPABILITIES = 64
private const val MAX_CAPABILITY_CHARS = 64
private const val MAX_REF_CHARS = 4_096

/**
 * 正規化前的原始模型承載（fetcher 回傳；不透明 hint，非 ground truth）。
 *
 * @param slug LMSpeed canonical slug（detail 路徑鍵；空白即略過）。
 * @param name 顯示名（僅比對線索，不當真偽證據）。
 * @param developer 開發者（僅線索）。
 * @param lmspeedUrl 回指頁面（須為 `https://lmspeed.net/…`，否則該筆略過）。
 * @param sourceEndpoint 產出本筆的公開端點標記（必須在 [LMSPEED_ALLOWED_ENDPOINTS] 內）。
 * @param lane verified／provisional；`provisional` 降 confidence。
 * @param includeDisplayOnly true 表示含 display-only manual override，降 confidence。
 * @param schemaVersion／publicationRevision pipeline 版本錨（有即透傳 payload，無不猜）。
 * @param etag／lastModified 快取錨（有即透傳，無不猜）。
 * @param description 站方彙整描述（locale 相依外部文字；null＝Unknown，不猜；不進 prompt）。
 * @param contextWindowTokens catalog metadata（null＝Unknown；<=0 視為 Unknown 不寫）。
 * @param maxOutputTokens catalog metadata（null＝Unknown；<=0 視為 Unknown 不寫）。
 * @param capabilities `include=capabilities` 回傳的不透明能力鍵（schema 未證實前只搬運；
 * null／空＝Unknown，不猜、不展開、不寫 local）。
 * @param externalTps 外部量測平均 TPS（`stats`／`performance_summary` 原始值；
 * 單位未證實，只搬運不換算；null＝Unknown，絕不寫 local telemetry）。
 * @param externalFirstTokenLatency 外部量測首 token latency 原始值（單位未證實；
 * null＝Unknown，絕不寫 local telemetry）。
 * @param benchmarkRef headline benchmark 指標的不透明引用（JSON 片段或 metric key；
 * null＝Unknown，不當 authenticity 證據）。
 * @param pricingRef benchmark-derived pricing 摘要的不透明引用（JSON 片段；
 * null＝Unknown，絕不當 official 價格）。
 */
data class LmspeedRawModel(
    val slug: String,
    val name: String? = null,
    val developer: String? = null,
    val lmspeedUrl: String? = null,
    val sourceEndpoint: String = LMSPEED_MODELS_ENDPOINT,
    val lane: String? = null,
    val includeDisplayOnly: Boolean = false,
    val schemaVersion: String? = null,
    val publicationRevision: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val description: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val capabilities: List<String>? = null,
    val externalTps: Double? = null,
    val externalFirstTokenLatency: Double? = null,
    val benchmarkRef: String? = null,
    val pricingRef: String? = null,
)

/**
 * Fetcher 注入點（產品實作另行；本檔不做網路、不碰 key）。
 *
 * 產品實作必須：只打 `GET /api/v1/models`（`q/page/page_size/sort/locale/include`）與
 * `GET /api/v1/models/{slug}`；走 public key（safe ref）＋自行快取＋1000/day 預算；
 * 不得為繞配額切換 PATH，不得直爬 `/api/（萬用字元）` 繞 `robots.txt`，不得使用 `lmspeed.com`／`.co`。
 */
interface LmspeedFetcher {
    suspend fun fetchModels(): List<LmspeedRawModel>
}

/**
 * 外部觀測列（與 `ExternalObservationEntity` 欄位一一對應；core 不依賴 app，故自立型別）。
 *
 * 產品接線範例（app 層另行，不在本檔）：
 * `externalStore.saveObservations(rows.map { ExternalObservationEntity(it.observationId, it.source, …) })`。
 * 只寫 `external_observations`；絕不碰 snapshot／samples／telemetry。
 */
data class LmspeedObservation(
    val observationId: String,
    val source: String,
    val externalModelId: String,
    val canonicalModelId: String? = null,
    val payloadJson: String,
    val fetchedAt: Long,
    val ttlMillis: Long,
    val confidence: String,
)

/** 落庫注入點（產品以 `ExternalStore.saveObservations` 轉接；本檔不做 delete／purge）。 */
interface LmspeedObservationSink {
    suspend fun saveObservations(rows: List<LmspeedObservation>)
}

/** 單次 import 結果：成功帶列；失敗帶原因且 `saved = 0`、sink 未刪任何舊列。 */
sealed interface LmspeedImportResult {
    data class Success(
        val saved: Int,
        val rows: List<LmspeedObservation>,
    ) : LmspeedImportResult

    data class Failure(
        val error: String,
        val cause: Throwable? = null,
        val saved: Int = 0,
    ) : LmspeedImportResult
}

/**
 * 決定性觀測 ID（必須與 `app.data.observationIdOf` 同公式；長度前綴故分隔符不碰撞）。
 * 同 source＋externalModelId＋fetchedAt 重送即覆寫（冪等），不增列。
 */
fun lmspeedObservationIdOf(source: String, externalModelId: String, fetchedAt: Long): String =
    "ext-obs:${source.length}:$source:${externalModelId.length}:$externalModelId:$fetchedAt"

/**
 * fetch → 正規化 → 外部槽落庫（LMS-001／LMS-005）。
 *
 * 流程：`fetcher.fetchModels()` → 逐筆 [normalizeRow]（略過非法筆，不 fail 全批）
 * → 同 observationId 去重（last wins）→ 非空才呼叫 `sink.saveObservations` 一次。
 * 失敗（fetch 丟例外或 sink 丟例外）即回 [LmspeedImportResult.Failure]，不呼叫任何刪除，
 * 舊資料原地保留；`CancellationException` 照常傳播（不吞取消）。
 */
class LmspeedImporter(
    private val fetcher: LmspeedFetcher,
    private val sink: LmspeedObservationSink,
) {
    suspend fun importOnce(
        nowMillis: Long,
        ttlMillis: Long = LMSPEED_DEFAULT_TTL_MILLIS,
    ): LmspeedImportResult {
        require(nowMillis >= 0L) { "nowMillis must be >= 0" }
        require(ttlMillis >= 0L) { "ttlMillis must be >= 0" }
        val raw: List<LmspeedRawModel> = try {
            fetcher.fetchModels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LmspeedImportResult.Failure(
                error = "lmspeed fetch failed: ${e.javaClass.simpleName}",
                cause = e,
            )
        }
        val deduped = LinkedHashMap<String, LmspeedObservation>()
        for (item in raw) {
            val row = normalizeRow(item, nowMillis, ttlMillis) ?: continue
            deduped[row.observationId] = row
        }
        val rows = deduped.values.toList()
        if (rows.isEmpty()) return LmspeedImportResult.Success(saved = 0, rows = emptyList())
        try {
            sink.saveObservations(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LmspeedImportResult.Failure(
                error = "lmspeed sink failed: ${e.javaClass.simpleName}",
                cause = e,
            )
        }
        return LmspeedImportResult.Success(saved = rows.size, rows = rows)
    }

    private fun normalizeRow(
        raw: LmspeedRawModel,
        nowMillis: Long,
        ttlMillis: Long,
    ): LmspeedObservation? {
        val externalId = raw.slug.trim()
        if (externalId.isEmpty()) return null
        if (raw.sourceEndpoint !in LMSPEED_ALLOWED_ENDPOINTS) return null
        val url = raw.lmspeedUrl?.trim().orEmpty()
        if (url.isNotEmpty() && !url.startsWith(LMSPEED_URL_PREFIX)) return null
        val provisional = raw.lane?.trim()?.equals("provisional", ignoreCase = true) == true
        val confidence = if (provisional || raw.includeDisplayOnly) {
            MappingConfidence.UNKNOWN.name
        } else {
            MappingConfidence.LOW.name
        }
        return LmspeedObservation(
            observationId = lmspeedObservationIdOf(LMSPEED_SOURCE, externalId, nowMillis),
            source = LMSPEED_SOURCE,
            externalModelId = externalId,
            canonicalModelId = null,
            payloadJson = buildPayload(raw, externalId, nowMillis),
            fetchedAt = nowMillis,
            ttlMillis = ttlMillis,
            confidence = confidence,
        )
    }

    private fun buildPayload(raw: LmspeedRawModel, externalId: String, nowMillis: Long): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"slug\":").append("\"").append(jsonEscape(externalId)).append("\"")
        raw.name?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"name\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        raw.developer?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"developer\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        raw.lmspeedUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"lmspeed_url\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        sb.append(",\"endpoint\":").append("\"").append(jsonEscape(raw.sourceEndpoint)).append("\"")
        sb.append(",\"attribution\":")
        sb.append("{\"required\":true,\"name\":\"LMSpeed\",\"url\":\"").append(LMSPEED_ATTRIBUTION_URL).append("\"}")
        raw.lane?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"lane\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        if (raw.includeDisplayOnly) sb.append(",\"display_only\":true")
        raw.schemaVersion?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"schema_version\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        raw.publicationRevision?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"publication_revision\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        raw.etag?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"etag\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        raw.lastModified?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"last_modified\":").append("\"").append(jsonEscape(it)).append("\"")
        }
        // Enrichment 欄位（LMS-005）：有即透傳外部槽，無即 Unknown 省略；數值未證實不換算、不填零。
        raw.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"description\":").append("\"").append(jsonEscape(it.take(MAX_DESCRIPTION_CHARS))).append("\"")
        }
        raw.contextWindowTokens?.takeIf { it > 0 }?.let {
            sb.append(",\"context_window_tokens\":").append(it.coerceAtMost(MAX_CONTEXT_TOKENS))
        }
        raw.maxOutputTokens?.takeIf { it > 0 }?.let {
            sb.append(",\"max_output_tokens\":").append(it.coerceAtMost(MAX_CONTEXT_TOKENS))
        }
        raw.capabilities?.map { c -> c.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(MAX_CAPABILITIES)?.takeIf { it.isNotEmpty() }?.let { caps ->
            sb.append(",\"capabilities\":[")
            caps.forEachIndexed { i, c ->
                if (i > 0) sb.append(",")
                sb.append("\"").append(jsonEscape(c.take(MAX_CAPABILITY_CHARS))).append("\"")
            }
            sb.append("]")
        }
        raw.externalTps?.takeIf { it.isFinite() && it > 0.0 }?.let {
            sb.append(",\"external_tps\":").append(it)
        }
        raw.externalFirstTokenLatency?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            sb.append(",\"external_first_token_latency\":").append(it)
        }
        raw.benchmarkRef?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"benchmark_ref\":").append("\"").append(jsonEscape(it.take(MAX_REF_CHARS))).append("\"")
        }
        raw.pricingRef?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append(",\"pricing_ref\":").append("\"").append(jsonEscape(it.take(MAX_REF_CHARS))).append("\"")
        }
        sb.append(",\"fetched_at\":").append(nowMillis)
        sb.append("}")
        return sb.toString()
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (Character.isISOControl(c)) {
                    sb.append(String.format("\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
