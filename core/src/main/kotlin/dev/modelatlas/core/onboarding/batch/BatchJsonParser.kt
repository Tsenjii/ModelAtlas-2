package dev.modelatlas.core.onboarding.batch

import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 嚴格 JSON 非 secret 匯入 parser。
 *
 * 接受的形狀只有兩種（其他一律拒絕）：
 * - `[ { ... }, { ... } ]`
 * - `{ "providers": [ { ... }, { ... } ] }`（`providers` 必須是唯一 key）
 *
 * 接受的欄位只有四個（其他一律拒絕，含 `apiKey` / `pat` / `token` 之類 secret 欄位）：
 * `baseUrl`（必填）、`websiteUrl`、`suggestedModelId`、`providerName`。
 *
 * 邊界：
 * - 不接受、不產生任何憑證：輸出 draft 的 `credentials` / `safeCredentialIds` 恆為空，
 *   原始 key / PAT 沒有匯入路徑；safe refs 由 app 端另行綁定。
 * - 錯誤是固定分類（[BatchParseError]）+ 固定訊息（[BatchParseMessages]），
 *   絕不把 raw 輸入、欄位值、URL 或疑似 secret 回進例外。
 * - 大小上限集中在 [BatchLimits]，解析前先擋字元數與筆數，解析中擋單筆字元數與欄位長度。
 */
fun interface BatchImportParser {
    /** @throws BatchParseException 任何結構、欄位、大小或 secret 問題。 */
    fun parse(rawInput: String): BatchDraft
}

object StrictBatchJsonParser : BatchImportParser {

    /** 預設 `Json` 即為嚴格模式（不容許單引號、註解、尾逗號）。 */
    private val strictJson = Json

    private const val FIELD_BASE_URL = "baseUrl"
    private const val FIELD_WEBSITE_URL = "websiteUrl"
    private const val FIELD_SUGGESTED_MODEL_ID = "suggestedModelId"
    private const val FIELD_PROVIDER_NAME = "providerName"
    private const val FIELD_PROVIDERS = "providers"
    private const val ITEM_ID_PREFIX = "batch-item-"

    private val ALLOWED_FIELDS = setOf(
        FIELD_BASE_URL,
        FIELD_WEBSITE_URL,
        FIELD_SUGGESTED_MODEL_ID,
        FIELD_PROVIDER_NAME,
    )

    override fun parse(rawInput: String): BatchDraft = parse(rawInput, BatchLimits.DEFAULT)

    fun parse(rawInput: String, limits: BatchLimits): BatchDraft {
        if (rawInput.isBlank()) throw BatchParseException(BatchParseError.EMPTY_INPUT)
        if (rawInput.length > limits.maxRawInputChars) {
            throw BatchParseException(BatchParseError.INPUT_TOO_LARGE)
        }
        val entries = rootEntries(parseElement(rawInput))
        if (entries.isEmpty()) throw BatchParseException(BatchParseError.EMPTY_ENTRY_LIST)
        if (entries.size > limits.maxItems) {
            throw BatchParseException(BatchParseError.TOO_MANY_ENTRIES)
        }
        val items = ArrayList<BatchItemDraft>(entries.size)
        val seenBaseUrls = HashSet<String>(entries.size)
        entries.forEachIndexed { index, element ->
            if (element.toString().length > limits.maxEntryChars) {
                throw BatchParseException(BatchParseError.ENTRY_TOO_LARGE, index)
            }
            val fields = readFields(asObject(element, index), index)
            val baseUrl = readBaseUrl(fields[FIELD_BASE_URL], index, limits)
            // 契約 §1.1：尾端 `/` 正規化後比對，`…/v1` 與 `…/v1/` 視為重複。
            val duplicateKey = baseUrl.trimEnd('/')
            if (!seenBaseUrls.add(duplicateKey)) {
                throw BatchParseException(BatchParseError.DUPLICATE_BASE_URL, index)
            }
            items += BatchItemDraft(
                itemId = itemIdFor(index),
                draft = OnboardingSnapshot(
                    workflowId = "",
                    flowType = OnboardingFlowType.QUICK,
                    providerName = fields[FIELD_PROVIDER_NAME],
                    baseUrl = baseUrl,
                    websiteUrl = readWebsiteUrl(fields[FIELD_WEBSITE_URL], index, limits),
                    suggestedModelIds = fields[FIELD_SUGGESTED_MODEL_ID]?.let { listOf(it) } ?: emptyList(),
                    credentials = emptyList(),
                    safeCredentialIds = emptyList(),
                ),
            )
        }
        return BatchDraft(items)
    }

    private fun parseElement(rawInput: String): JsonElement = try {
        strictJson.parseToJsonElement(rawInput)
    } catch (e: SerializationException) {
        throw BatchParseException(BatchParseError.MALFORMED_JSON)
    } catch (e: IllegalArgumentException) {
        throw BatchParseException(BatchParseError.MALFORMED_JSON)
    }

    private fun rootEntries(root: JsonElement): List<JsonElement> = when (root) {
        is JsonArray -> root
        is JsonObject -> {
            // 物件根節點先看是否夾帶 secret 欄位，再看形狀；分類固定、不回顯欄位名。
            if (root.keys.any { BatchSecretGuard.keyLooksLikeSecret(it) }) {
                throw BatchParseException(BatchParseError.SECRET_FIELD_REFUSED)
            }
            if (root.size != 1) throw BatchParseException(BatchParseError.UNKNOWN_FIELD)
            val providers = root[FIELD_PROVIDERS] ?: throw BatchParseException(
                BatchParseError.UNSUPPORTED_ROOT,
            )
            providers as? JsonArray ?: throw BatchParseException(BatchParseError.UNSUPPORTED_ROOT)
        }
        else -> throw BatchParseException(BatchParseError.UNSUPPORTED_ROOT)
    }

    private fun asObject(element: JsonElement, index: Int): JsonObject =
        element as? JsonObject ?: throw BatchParseException(
            BatchParseError.ENTRY_NOT_OBJECT,
            index,
        )

    /** 只收四個允許欄位；未知欄位與疑似 secret 欄位各有固定分類，值也做 fail-closed 掃描。 */
    private fun readFields(entry: JsonObject, index: Int): Map<String, String?> {
        val out = LinkedHashMap<String, String?>(ALLOWED_FIELDS.size)
        for ((key, value) in entry) {
            if (key !in ALLOWED_FIELDS) {
                val error = if (BatchSecretGuard.keyLooksLikeSecret(key)) {
                    BatchParseError.SECRET_FIELD_REFUSED
                } else {
                    BatchParseError.UNKNOWN_FIELD
                }
                throw BatchParseException(error, index)
            }
            if (value is JsonNull) {
                out[key] = null
                continue
            }
            val primitive = value as? JsonPrimitive
                ?: throw BatchParseException(BatchParseError.FIELD_TYPE_INVALID, index)
            if (!primitive.isString) {
                throw BatchParseException(BatchParseError.FIELD_TYPE_INVALID, index)
            }
            val text = primitive.content
            if (BatchSecretGuard.valueLooksLikeSecret(text)) {
                throw BatchParseException(BatchParseError.SECRET_VALUE_REFUSED, index)
            }
            out[key] = text.trim().takeIf { it.isNotEmpty() }
        }
        return out
    }

    private fun readBaseUrl(raw: String?, index: Int, limits: BatchLimits): String {
        val value = raw ?: throw BatchParseException(BatchParseError.BASE_URL_MISSING, index)
        if (value.length > limits.maxUrlChars) {
            throw BatchParseException(BatchParseError.FIELD_TOO_LONG, index)
        }
        return try {
            EndpointPolicy.validate(value).toString()
        } catch (e: IllegalArgumentException) {
            throw BatchParseException(BatchParseError.BASE_URL_INVALID, index)
        }
    }

    private fun readWebsiteUrl(raw: String?, index: Int, limits: BatchLimits): String? {
        val value = raw ?: return null
        if (value.length > limits.maxUrlChars) {
            throw BatchParseException(BatchParseError.FIELD_TOO_LONG, index)
        }
        return try {
            EndpointPolicy.validate(value).toString()
        } catch (e: IllegalArgumentException) {
            throw BatchParseException(BatchParseError.WEBSITE_URL_INVALID, index)
        }
    }

    private fun itemIdFor(index: Int): String = "$ITEM_ID_PREFIX${index + 1}"
}

/** 固定解析分類。`wireName` 供 UI 字串映射；每一值對應一則固定安全訊息。 */
enum class BatchParseError(val wireName: String) {
    EMPTY_INPUT("empty_input"),
    INPUT_TOO_LARGE("input_too_large"),
    MALFORMED_JSON("malformed_json"),
    UNSUPPORTED_ROOT("unsupported_root"),
    EMPTY_ENTRY_LIST("empty_entry_list"),
    TOO_MANY_ENTRIES("too_many_entries"),
    ENTRY_TOO_LARGE("entry_too_large"),
    ENTRY_NOT_OBJECT("entry_not_object"),
    FIELD_TYPE_INVALID("field_type_invalid"),
    FIELD_TOO_LONG("field_too_long"),
    UNKNOWN_FIELD("unknown_field"),
    SECRET_FIELD_REFUSED("secret_field_refused"),
    SECRET_VALUE_REFUSED("secret_value_refused"),
    BASE_URL_MISSING("base_url_missing"),
    BASE_URL_INVALID("base_url_invalid"),
    WEBSITE_URL_INVALID("website_url_invalid"),
    DUPLICATE_BASE_URL("duplicate_base_url"),
}

/** 固定安全訊息表；不含 raw 輸入、欄位值、URL 或疑似 secret。 */
internal object BatchParseMessages {
    fun of(error: BatchParseError): String = when (error) {
        BatchParseError.EMPTY_INPUT -> "匯入內容為空"
        BatchParseError.INPUT_TOO_LARGE -> "匯入內容超過大小上限"
        BatchParseError.MALFORMED_JSON -> "匯入內容不是合法 JSON"
        BatchParseError.UNSUPPORTED_ROOT -> "匯入格式只支援物件陣列或 providers 陣列"
        BatchParseError.EMPTY_ENTRY_LIST -> "匯入內容沒有任何項目"
        BatchParseError.TOO_MANY_ENTRIES -> "匯入項目數超過上限"
        BatchParseError.ENTRY_TOO_LARGE -> "單一項目超過大小上限"
        BatchParseError.ENTRY_NOT_OBJECT -> "單一項目必須為物件"
        BatchParseError.FIELD_TYPE_INVALID -> "欄位型別無效，只接受字串"
        BatchParseError.FIELD_TOO_LONG -> "欄位長度超過上限"
        BatchParseError.UNKNOWN_FIELD ->
            "匯入不支援此欄位，只接受 baseUrl / websiteUrl / suggestedModelId / providerName"
        BatchParseError.SECRET_FIELD_REFUSED ->
            "匯入不接受 secret 欄位，原始金鑰請在安全輸入後另行綁定"
        BatchParseError.SECRET_VALUE_REFUSED -> "欄位值疑似含有 secret，已拒絕"
        BatchParseError.BASE_URL_MISSING -> "項目缺少 baseUrl"
        BatchParseError.BASE_URL_INVALID -> "baseUrl 必須為合法 HTTPS 位址"
        BatchParseError.WEBSITE_URL_INVALID -> "websiteUrl 必須為合法 HTTPS 位址"
        BatchParseError.DUPLICATE_BASE_URL -> "匯入內容內 baseUrl 重複"
    }
}

/**
 * 解析失敗例外。[entryIndex] 是位置（0 起算），不是內容；[error] 是可辨識分類。
 * 訊息固定，不含任何使用者輸入。
 */
class BatchParseException(
    val error: BatchParseError,
    val entryIndex: Int? = null,
) : IllegalArgumentException(BatchParseMessages.of(error))
