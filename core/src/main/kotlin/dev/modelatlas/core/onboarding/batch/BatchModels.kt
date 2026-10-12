package dev.modelatlas.core.onboarding.batch

import dev.modelatlas.core.onboarding.OnboardingFailureReason
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Batch import 核心 model（Phase 3 Providers；`docs/PROVIDER_ONBOARDING_CONTRACT.md` §0.2 / §3）。
 *
 * 不可簡化的邊界：
 * - 輸入只有非 secret 的 [OnboardingSnapshot] draft 與 safe credential refs；原始 key / PAT 不進本 model。
 * - 每筆都保存自己的 `workflowId`，重啟後可單筆恢復（契約 §3.2）。
 * - 狀態可序列化（[BatchSnapshot]），持久化由注入的 [BatchStateStore] 負責；core 不提供 in-memory 實作。
 * - 錯誤分類固定（[BatchFailure] / [BatchParseError]），訊息不帶 raw 輸入。
 */

/** 批次整體狀態：只表示「排程層」進度，不代表任何單筆的 workflow 結果。 */
enum class BatchStatus {
    /** 執行中（逐筆排程）。 */
    RUNNING,

    /** 因 [BatchSnapshot.stopReason] 停手；可 `resume` 從停手處續跑。 */
    PAUSED,

    /** 所有筆都已到終結狀態（`Review` 待確認 / 逐筆失敗 / 逐筆待輸入）。 */
    COMPLETED,
}

/** 單筆狀態。`RUNNING` 殘留在持久化狀態代表進程被殺，重啟後視為 [INTERRUPTED] 可續跑。 */
enum class BatchItemStatus {
    /** 尚未排程。 */
    PENDING,

    /** 執行中。 */
    RUNNING,

    /** 上次執行被進程中斷，可恢復。 */
    INTERRUPTED,

    /** 已跑到 `Review`，等人類確認；本批次永不自動 approve / commit。 */
    AWAITING_REVIEW,

    /** workflow 停在需要使用者補輸入（缺 credential / 無模型），已發生的步驟保留。 */
    NEEDS_INPUT,

    /** 匯入輸入在本地驗證就被拒，未發任何 request。 */
    VALIDATION_FAILED,

    /** 遇 429：本筆停手且批次不再排後續筆。 */
    RATE_LIMITED,

    /** `inspect` 失敗或無替代路徑，整筆被阻擋。 */
    BLOCKED,

    /** 單筆執行失敗（分類見 [BatchFailure]），不影響其他筆。 */
    FAILED,

    /** 執行被取消；恢復後續跑，不算完成。 */
    CANCELLED,
    ;

    /** 排程層終結：不再重跑，除非呼叫端明確 [BatchOnboardingImporter.retryItem]。 */
    val terminal: Boolean
        get() = when (this) {
            PENDING, RUNNING, INTERRUPTED, CANCELLED -> false
            else -> true
        }

    /** 可由 `resume` 直接續跑（不當成已完成單）。 */
    val resumable: Boolean
        get() = this == RUNNING || this == INTERRUPTED || this == CANCELLED
}

/**
 * 固定的失敗／停止分類。`wireName` 供 UI 字串映射用。
 * 每一個值都對應一則固定安全訊息（[BatchMessages]），不得帶入 raw 輸入、URL、key 或 server body。
 */
enum class BatchFailure(val wireName: String) {
    EMPTY_BATCH("empty_batch"),
    ITEM_LIMIT_EXCEEDED("item_limit_exceeded"),
    BATCH_NOT_FOUND("batch_not_found"),
    BATCH_ALREADY_EXISTS("batch_already_exists"),
    ITEM_NOT_FOUND("item_not_found"),

    ITEM_ID_DUPLICATE("item_id_duplicate"),
    BASE_URL_MISSING("base_url_missing"),
    BASE_URL_INVALID("base_url_invalid"),
    WEBSITE_URL_INVALID("website_url_invalid"),
    MODEL_ID_INVALID("model_id_invalid"),
    NAME_INVALID("name_invalid"),
    FIELD_TOO_LONG("field_too_long"),
    SAFE_CREDENTIAL_REF_INVALID("safe_credential_ref_invalid"),
    DUPLICATE_BASE_URL("duplicate_base_url"),
    FORBIDDEN_INPUT_REFUSED("forbidden_input_refused"),
    REVIEW_APPROVAL_REFUSED("review_approval_refused"),
    WORKFLOW_STATE_REFUSED("workflow_state_refused"),

    NEEDS_INPUT("needs_input"),
    BLOCKED("blocked"),
    RATE_LIMITED("rate_limited"),
    CANCELLED("cancelled"),
    UNEXPECTED_COMMIT("unexpected_commit"),
    WORKFLOW_STATE_INVALID("workflow_state_invalid"),
    WORKFLOW_ERROR("workflow_error"),
}

/**
 * 固定安全訊息表。與原始輸入完全無關，改分類不改文案。
 * 一律不含 URL / key / PAT / server body / raw JSON。
 */
internal object BatchMessages {
    fun of(failure: BatchFailure): String = when (failure) {
        BatchFailure.EMPTY_BATCH -> "批次匯入沒有任何項目"
        BatchFailure.ITEM_LIMIT_EXCEEDED -> "批次項目數超過上限"
        BatchFailure.BATCH_NOT_FOUND -> "批次不存在"
        BatchFailure.BATCH_ALREADY_EXISTS -> "批次已存在，不可重複建立"
        BatchFailure.ITEM_NOT_FOUND -> "批次內找不到該項目"
        BatchFailure.ITEM_ID_DUPLICATE -> "批次內有重複的項目識別字"
        BatchFailure.BASE_URL_MISSING -> "缺少 baseUrl"
        BatchFailure.BASE_URL_INVALID -> "baseUrl 必須為合法 HTTPS 位址"
        BatchFailure.WEBSITE_URL_INVALID -> "websiteUrl 必須為合法 HTTPS 位址"
        BatchFailure.MODEL_ID_INVALID -> "model id 無效"
        BatchFailure.NAME_INVALID -> "名稱無效"
        BatchFailure.FIELD_TOO_LONG -> "欄位長度超過上限"
        BatchFailure.SAFE_CREDENTIAL_REF_INVALID -> "safe credential ref 無效"
        BatchFailure.DUPLICATE_BASE_URL -> "同一批次內 baseUrl 重複"
        BatchFailure.FORBIDDEN_INPUT_REFUSED -> "輸入疑似含有 secret，已拒絕"
        BatchFailure.REVIEW_APPROVAL_REFUSED -> "批次匯入不接受預先核准的項目"
        BatchFailure.WORKFLOW_STATE_REFUSED -> "項目狀態不允許匯入執行"
        BatchFailure.NEEDS_INPUT -> "等待使用者補輸入"
        BatchFailure.BLOCKED -> "該筆被阻擋，需修正後重跑"
        BatchFailure.RATE_LIMITED -> "已遇速率限制，已停止排程後續項目"
        BatchFailure.CANCELLED -> "已取消，未完成"
        BatchFailure.UNEXPECTED_COMMIT -> "批次匯入不允許自動 commit"
        BatchFailure.WORKFLOW_STATE_INVALID -> "workflow 狀態不允許繼續"
        BatchFailure.WORKFLOW_ERROR -> "該筆執行失敗"
    }
}

/**
 * 集中技術限制。產品常數未定（契約 §10），只在此調；解析器與執行器共用同一組上限，
 * 呼叫端不得自行放寬。
 */
@Serializable
data class BatchLimits(
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    val maxRawInputChars: Int = DEFAULT_MAX_RAW_INPUT_CHARS,
    val maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS,
    val maxUrlChars: Int = DEFAULT_MAX_URL_CHARS,
    val maxModelIdChars: Int = DEFAULT_MAX_MODEL_ID_CHARS,
    val maxProviderNameChars: Int = DEFAULT_MAX_PROVIDER_NAME_CHARS,
) {
    init {
        require(maxItems > 0) { "maxItems must be positive" }
        require(maxRawInputChars > 0) { "maxRawInputChars must be positive" }
        require(maxEntryChars > 0) { "maxEntryChars must be positive" }
        require(maxUrlChars > 0) { "maxUrlChars must be positive" }
        require(maxModelIdChars > 0) { "maxModelIdChars must be positive" }
        require(maxProviderNameChars > 0) { "maxProviderNameChars must be positive" }
    }

    companion object {
        val DEFAULT: BatchLimits = BatchLimits()

        const val DEFAULT_MAX_ITEMS = 50
        const val DEFAULT_MAX_RAW_INPUT_CHARS = 262_144
        const val DEFAULT_MAX_ENTRY_CHARS = 4_096
        const val DEFAULT_MAX_URL_CHARS = 2_048
        const val DEFAULT_MAX_MODEL_ID_CHARS = 256
        const val DEFAULT_MAX_PROVIDER_NAME_CHARS = 128
    }
}

/** 匯入輸入（每筆一列）；`draft` 只放非 secret 欄位與 safe credential refs。 */
@Serializable
data class BatchDraft(
    val items: List<BatchItemDraft>,
)

/** 單筆輸入：[itemId] 為批次內穩定識別字；[draft] 是 workflow 的非 secret draft。 */
@Serializable
data class BatchItemDraft(
    val itemId: String,
    val draft: OnboardingSnapshot,
)

/** 單筆可序列化狀態。`workflowId` 一旦指派即固定，重啟後用它恢復。 */
@Serializable
data class BatchItemState(
    val itemId: String,
    val index: Int,
    val draft: OnboardingSnapshot,
    val workflowId: String? = null,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val failure: BatchFailure? = null,
    /** 來自單筆 workflow 的固定分類（契約 §4），只存列舉值，不存 server body。 */
    val workflowReason: OnboardingFailureReason? = null,
    val workflowStatus: OnboardingStatus? = null,
    val rateLimited: Boolean = false,
    /** 是否已跑到 `Review`（`ReviewItems` 本體存在 workflow 快照，這裡只留旗標）。 */
    val reviewReached: Boolean = false,
    val attempts: Int = 0,
    val requestsSpent: Int = 0,
    val startedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val finishedAtMillis: Long? = null,
)

/**
 * 批次可序列化狀態。整份覆寫保存（Room adapter 由 app 實作）。
 * 只含非 secret 欄位、safe refs、分類與計數；沒有任意 payload。
 */
@Serializable
data class BatchSnapshot(
    val batchId: String,
    val status: BatchStatus,
    val items: List<BatchItemState>,
    val stopReason: BatchFailure? = null,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    fun itemOf(itemId: String): BatchItemState? = items.firstOrNull { it.itemId == itemId }

    fun countOf(status: BatchItemStatus): Int = items.count { it.status == status }

    fun itemIdsWith(status: BatchItemStatus): List<String> =
        items.filter { it.status == status }.map { it.itemId }

    fun totalRequestsSpent(): Int = items.sumOf { it.requestsSpent }
}

/**
 * 狀態編解碼用 JSON：全部欄位具現、未知欄位忽略（前向相容）。
 * app 端持久化時仍須沿用既有的 secret-guard（見 `app/data/OnboardingStore.kt`）。
 */
val BatchJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/**
 * Fail-closed 的疑似 secret 偵測。兩種樣式：
 *
 * 1. 值樣式：常見供應商 key 前綴（`sk-`、`gsk_`、`AIza`、`nvapi-`、`hf_`、`xai-`）與
 *    `Bearer <token>` 回音。只在**自由文字**欄位上掃描（名稱、備註、標籤、model id、label），
 *    URL 另走 [dev.modelatlas.core.network.EndpointPolicy] 的 HTTPS / 無 userinfo / 無 query 規則。
 * 2. 鍵名樣式：key 名去掉 `_` / `-` / 空白並轉小寫後，命中已知 secret 詞彙即視為 secret 欄位。
 *
 * 誤判方向一律偏向拒絕：命中即回固定分類，不回顯命中內容。
 */
internal object BatchSecretGuard {
    private val SECRET_VALUE_PATTERNS: List<Regex> = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{8,}"""),
        Regex("""sk_[A-Za-z0-9_\-]{8,}"""),
        Regex("""gsk_[A-Za-z0-9_\-]{8,}"""),
        Regex("""AIza[A-Za-z0-9_\-]{8,}"""),
        Regex("""nvapi-[A-Za-z0-9_\-]{8,}"""),
        Regex("""hf_[A-Za-z0-9]{8,}"""),
        Regex("""xai-[A-Za-z0-9]{16,}"""),
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{8,}"""),
    )

    private val SECRET_KEY_TOKENS: List<String> = listOf(
        "apikey",
        "key",
        "secret",
        "token",
        "pat",
        "password",
        "passwd",
        "passphrase",
        "authorization",
        "auth",
        "bearer",
        "credential",
        "privatekey",
        "jwt",
        "cookie",
        "session",
    )

    fun valueLooksLikeSecret(value: String): Boolean =
        SECRET_VALUE_PATTERNS.any { it.containsMatchIn(value) }

    fun keyLooksLikeSecret(key: String): Boolean {
        val normalized = buildString(key.length) {
            for (ch in key) {
                if (ch == '_' || ch == '-' || ch == ' ' || ch == '.') continue
                append(ch.lowercaseChar())
            }
        }
        return SECRET_KEY_TOKENS.any { normalized.contains(it) }
    }
}
