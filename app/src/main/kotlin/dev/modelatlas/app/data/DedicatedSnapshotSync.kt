package dev.modelatlas.app.data

import androidx.room.withTransaction
import dev.modelatlas.core.dedicated.AmdAdapter
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedProviderType
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.DedicatedStatus
import dev.modelatlas.core.dedicated.OpenRouterFreeAdapter
import dev.modelatlas.core.dedicated.PricingFact
import dev.modelatlas.core.dedicated.PricingMode
import dev.modelatlas.core.dedicated.QuotaPool
import dev.modelatlas.core.dedicated.QuotaPoolSlice
import dev.modelatlas.core.dedicated.QuotaScopeKind
import dev.modelatlas.core.dedicated.QuotaValueState
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.PricingPolicy
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.domain.QuotaState
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dedicated 觀測 → `snapshot_facts` 持久化（Phase 5 repository 層）。
 *
 * TRACEABILITY: ADAPT-001 ~ ADAPT-007（adapter 已 IMPLEMENTED，本層補 repository 映射＋last-good）。
 * Spec: `PRODUCT_SPEC.md` §9（capability 獨立缺失，不 giant switch）、§10（provenance／FREE／
 * Pricing／Quota：value＋source＋observedAt＋confidence＋scope，FREE 為 derived state，
 * Quota 為 Resource Pool 不假平均）、§12（Dedicated 各家：hosted／self-hosted 分離、
 * AMD structured metadata、OpenRouter pricing 驗證、Gemini Project×Model、Groq per-model）。
 * 決策：D-2026-09-28-001／002／003（ordinary gate／AA top-4／不可量化保存：Unknown 不等於 0／Free，
 * 不丟 DB）；D-2026-09-28-004（摘要漏字不縮水）。
 *
 * 契約（全部強制）：
 * - **dedicated 命名空間 kind，不與 NewApi 官方 head 互覆蓋（MAJOR8／X-004）**：
 *   本層只寫 [SnapshotKind.DEDICATED_PRICE]／[SnapshotKind.DEDICATED_ACCOUNT_QUOTA]／
 *   [SnapshotKind.DEDICATED_TOKEN_QUOTA] 三種 kind（`MANUAL_*` 只給手填，絕不混用；
 *   NewApi 官方入口的 `PRICE`／`ACCOUNT_QUOTA`／`TOKEN_QUOTA` 保持原樣不動）。
 *   同七維 scopeKey 下兩套 head／lastGood／evidence 互不覆蓋：dedicated 寫入絕不推進或
 *   抹掉同 scope 的 NewApi last-good，反之亦然（[dedicatedKind] 為唯一映射點）。
 *   scopeKey 仍即 [PricingScope.storageKey] 七維（Provider×Account／Credential×Model×
 *   Group×Endpoint×Operation，時間在 observedAt），不同 Provider／Account／Credential／
 *   Model／Group／Endpoint／Operation 即不同 head，絕不互相覆蓋。無新 Entity／Dao／migration。
 * - **來源字串區分各家＋綁 providerId（B2）**：[DedicatedEvidence.source] 原樣保存（例：
 *   `nvidia-hosted/quota`、`nvidia-selfhosted/quota`、`amd/v1/models`、
 *   `openrouter/api/v1/models`、`gemini/rate-limits`、`groq/rate-limits`），只接受
 *   dedicated 前綴（見 [DEDICATED_SOURCE_PREFIXES]），`newapi/…`／`USER`／空字串一律拒絕，
 *   official／observed／external 絕不互冒（X-005，confidence 見 [confidenceOf]）。
 *   另強制 source 家族綁定 `scope.providerId`（見 [validateSourceMatchesProvider]）：
 *   `nvidia-h`／`nvidia-hosted` 只收 `nvidia-hosted/…`，`nvidia-s`／`nvidia-selfhosted`
 *   只收 `nvidia-selfhosted/…`，其餘家族鍵只收同名前綴（如 `groq` 收 `groq/…`）。
 *   跨家 source 寫入直接 fail-closed 拒絕（X-004），避免 openrouter scope 誤收 nvidia 觀測。
 *   **本地指派 id 例外（P6 INT-1，BLOCKER1）**：onboarding 指派的裸 UUID（或其他無家族語意
 *   的自訂 id）本身不含家族資訊，無從與 source 前綴比對；此路徑改以 source 前綴唯一
 *   判定家族（家族路由已由呼叫端 `resolveDedicatedRoute` 解析），家族無法判定即明確
 *   拒絕（`Dedicated source family could not be determined`），不放行未知前綴、不猜家族。
 * - **Gemini project→group 維度＋quota 落庫（B3）**：`projectId` 只進 [PricingScope.group]，`model` 只進
 *   [PricingScope.modelId]，永不拼接 `project::model`（ADAPT-005 reviewer 要求）。
 *   任何 `modelId`／`group` 含 `::` 即 fail-closed 拒絕；[QuotaPool.modelId] 含 `::`
 *   亦拒絕（呼叫端必須先拆分，見 [geminiScope]）。[QuotaPool.scopeKind]／`projectId`／
 *   `quotaSlice` 一律落庫至 `snapshot_facts.quotaScopeKind／quotaProjectId／quotaSlice`
 *   （v6 只加欄），[validatePoolMatchesScope] 驗 `pool.projectId == scope.group`
 *  （兩皆 null 或完全相等，含 `::`／控制字元／超長即拒絕）。
 * - **UNKNOWN scope 禁寫具名 scope（B6）**：[QuotaPool.scopeKind] 為 `UNKNOWN`
 *  （例：NVIDIA hosted 官方未定義 scope）時，`pool.modelId／projectId／quotaSlice`
 *   必須全 null，且 `scope.modelId` 必須 `""`、`scope.group` 必須 null、
 *   `scope.credentialId` 必須 null（全域 account scope 才可寫）。具名 model／group／
 *   credential 配 UNKNOWN pool 一律 fail-closed 拒絕，避免上界值誤掛具名模型。
 * - **失敗／Unknown 不碰 last-good＋Unsupported 無 snapshot（M4）**：[DedicatedResult.Failure]／
 *   [DedicatedResult.Unsupported] 只 append fact＋evidence 並推進 `latest`（若更新），
 *   不建 `price_snapshots`／`quota_snapshots` 列（`fact.snapshotId=null`），`lastGood`
 *   僅在 `good && observedAt > lastGoodObservedAt` 才推進（見 [record]，與 P4 `newerGood` 同語義，
 *   X-006）。Success（即使 state UNKNOWN）仍建 snapshot 列（觀測事實）；Unknown 腿維持 null，
 *   絕不用 0 代替（X-001）。`latest` 永遠是本次狀態（含 Unsupported 的 UNKNOWN），
 *   `lastGood` 僅為歷史事實，不冒充本次成功。
 * - **secret 只傳 ref**：所有 API 只收 safe credential ref（DB 的 credentialId，不含值），
 *   絕不收 raw key／PAT／token／[dev.modelatlas.core.inference.CredentialAccess]。
 *   ref 須安全（非空、≤256、無控制字元、不以 `sk-` 開頭、不含空白，見 [requireSafeRef]），
 *   且提供時必須在 DB 存在、歸屬正確、enabled。DB／evidence／fact 只存 refs 與狀態，
 *   不存 secret 原始值（X-003）。
 * - **APIs 只收 typed 結果**：所有同步入口只收 [DedicatedResult] 包裝的 typed adapter 輸出
 *   （[PricingFact]／[QuotaPool]／[OpenRouterFreeAdapter.CatalogEntry]／
 *   [OpenRouterFreeAdapter.KeyQuotaFacts]／[AmdAdapter.AmdModelEntry]／[PricingMode]），
 *   絕不收 raw JSON／body／`Map<String,String>` headers／server message。數值唯一真相在
 *   adapter typed 輸出，本層只做 scope／ownership／source／provenance 驗證、[PricingPolicy]
 *   派生、evidence 鏈與 head 維護，不做 USD 換算、不 eval、不重算 quota 語義。
 *
 * 數值語義（fail-closed，不猜）：
 * - 價格 legs 缺失即 null（Unknown），負值／不安全精度即該腿 null。最終 [PriceState] 一律經
 *   [PricingPolicy.evaluate]（只支援 `text`／`CHAT_COMPLETIONS`，其餘 UNKNOWN；
 *   名稱永不參與判定）。`FreeAssessment` 不直接落庫（最終 FREE 仍需 legs＋telemetry）。
 * - [PricingMode] 非 `PER_TOKEN`／`FIXED_PER_REQUEST`（`NO_PER_TOKEN_BILLING`／`PER_GPU_*`／
 *   `CREDITS_SPEND`／`UNKNOWN`）一律 null legs→UNKNOWN，不以 0 冒充免費。
 * - [QuotaPool]→[QuotaState] 見 [quotaStateOf]：任一 `KNOWN`（含上界）→可用家族；
 *   `PLATFORM_GLOBAL`＋KNOWN→`PUBLIC_POOL`；全 `NO_PROVIDER_QUOTA`→
 *   `NO_KNOWN_CONSUMABLE_QUOTA`；其餘（UNKNOWN／REQUIRES_LOGIN／UNPUBLISHED／
 *   NOT_OFFERED／DIMENSION 不適用）→UNKNOWN。limits 數值本身不寫入 remaining（limits≠
 *   remaining，避免把文件上限當剩餘），故 [QuotaPool] 路徑 raws 全 null。
 *   上界／需登入／多數模型適用（`upperBound／requiresLogin／appliesToMostModels`）即
 *   `isEstimated=true`（H3）；精確 KNOWN 才 `false`（見 [estimatedOf]）。
 * - [OpenRouterFreeAdapter.KeyQuotaFacts]（`GET /api/v1/key` 觀察維度）才寫 raws：
 *   `remainingRaw=freeRemaining`／`usedRaw=freeUsed`／`grantedRaw=freeLimit`（Long 精確，
 *   不經 Double）。`remaining==null`→UNKNOWN；`<=0`→EXHAUSTED；否則 AVAILABLE。
 *   `remainingIsPolicyNotCeiling=true`（exempt／tier policy 非 enforced ceiling）即
 *   `isEstimated=true`，必須明示 Estimated（H3，SPEC §12 OpenRouter 條）。
 * - AMD shared／dedicated 分離（H2）：`syncAmdPricing` 必須帶 [DedicatedProviderType]
 *  （預設 `AMD_SHARED` 相容舊呼叫）；`AMD_DEDICATED`（自有 instance，credits／GPU-hour
 *   制，無 per-token 維度）一律 null legs→UNKNOWN、`priceUnit=null`，不寫 per-token 價，
 *   不以 0 冒充免費。`free` 旗標永不直判，僅經 [PricingPolicy] legs 派生。
 * - 跨呼叫去重（H5）：同 `scopeKey＋kind＋observedAt＋state＋status`，價格另比
 *   `priceUnit＋source＋input／output／fixedRequest／fixedRequestKnownAbsent`；無值觀測只匹配
 *   同 source 的無值列（見 [isDuplicatePrice]）。quota 另比
 *   `quotaScopeKind／quotaProjectId／quotaSlice＋isEstimated`，key quota 另比 raws＋estimated。
 *   完全相同才跳過寫入（重試／重放冪等，呼叫端在 mutex＋transaction 內檢查）。
 * - 鎖域（M6）：`syncMutex` 只護 `db.withTransaction { doPersist… }`（head 讀改寫競態），
 *   scope／ownership／source 驗證與最終 `price()／quota()` 回讀皆在鎖外，避免長持鎖
 *   拖慢 Room IO。`price()／quota()／history()` 本身不拿鎖。
 */
class DedicatedSnapshotSync(private val db: AtlasDatabase) {
    private val syncMutex = Mutex()

    /** 通用價格：typed [PricingFact]（各家 numeric 價格經 caller 轉為此型，不含名稱）。 */
    suspend fun syncPricing(
        scope: PricingScope,
        result: DedicatedResult<PricingFact>,
    ): StoredPrice {
        // M6：驗證在鎖外（DB 讀＋純檢查），鎖只護 persist＋head。
        validatePricingScope(scope)
        when (result) {
            is DedicatedResult.Success -> {
                require(result.value.modelId == scope.modelId) { "Pricing model mismatch" }
                require(!result.value.modelId.contains("::")) { "Pricing model must not contain concatenation" }
                require(result.value.provenance != DedicatedProvenance.MANUAL) {
                    "Manual must not impersonate dedicated facts"
                }
            }
            is DedicatedResult.Unsupported,
            is DedicatedResult.Failure,
            -> Unit
        }
        validateDedicatedEvidence(scope, resultEvidence(result), allowManual = false)
        syncMutex.withLock {
            db.withTransaction {
                doPersistPricing(scope, result)
            }
        }
        return price(scope)
    }

    /** OpenRouter catalog 觀測：`pricing.prompt/completion` 精確字串，双零才 FREE（名稱不參與）。 */
    suspend fun syncOpenRouterPricing(
        scope: PricingScope,
        result: DedicatedResult<OpenRouterFreeAdapter.CatalogEntry>,
    ): StoredPrice {
        validatePricingScope(scope)
        if (result is DedicatedResult.Success) {
            require(result.value.id == scope.modelId) { "Pricing model mismatch" }
            require(!result.value.id.contains("::")) { "Pricing model must not contain concatenation" }
        }
        validateDedicatedEvidence(scope, resultEvidence(result), allowManual = false)
        syncMutex.withLock {
            db.withTransaction {
                doPersistOpenRouterPricing(scope, result)
            }
        }
        return price(scope)
    }

    /**
     * AMD structured metadata：`pricing{prompt,completion}` USD／token 精確值，×1000 為 USD／1K。
     * H2：`providerType` 必須明確（預設 SHARED 相容舊呼叫）；DEDICATED（自有 instance，
     * credits／GPU-hour 制）一律 UNKNOWN，不寫 per-token 價。
     */
    suspend fun syncAmdPricing(
        scope: PricingScope,
        result: DedicatedResult<AmdAdapter.AmdModelEntry>,
        providerType: DedicatedProviderType = DedicatedProviderType.AMD_SHARED,
    ): StoredPrice {
        validatePricingScope(scope)
        if (result is DedicatedResult.Success) {
            require(result.value.id == scope.modelId) { "Pricing model mismatch" }
            require(!result.value.id.contains("::")) { "Pricing model must not contain concatenation" }
        }
        validateDedicatedEvidence(scope, resultEvidence(result), allowManual = false)
        syncMutex.withLock {
            db.withTransaction {
                doPersistAmdPricing(scope, result, providerType)
            }
        }
        return price(scope)
    }

    /** 計價模式觀測（例：NVIDIA hosted `NO_PER_TOKEN_BILLING`）：只記錄模式，不產 legs，一律 UNKNOWN。 */
    suspend fun syncPricingMode(
        scope: PricingScope,
        result: DedicatedResult<PricingMode>,
    ): StoredPrice {
        validatePricingScope(scope)
        validateDedicatedEvidence(scope, resultEvidence(result), allowManual = false)
        syncMutex.withLock {
            db.withTransaction {
                doPersistPricingMode(scope, result)
            }
        }
        return price(scope)
    }

    /** 帳號／全域層級 quota：`credentialId` 必 null（per-key 必須走 token API，避免 mis-scope）。 */
    suspend fun syncAccountQuota(
        scope: PricingScope,
        result: DedicatedResult<QuotaPool>,
    ): StoredQuota {
        validateQuotaScope(scope, requireCredential = false)
        if (result is DedicatedResult.Success) {
            require(result.value.scopeKind != QuotaScopeKind.PER_KEY) { "Per-key pool must use token API" }
            require(result.value.scopeKind != QuotaScopeKind.PER_KEY_MODEL) { "Per-key pool must use token API" }
            require(result.value.provenance != DedicatedProvenance.MANUAL) {
                "Manual must not impersonate dedicated facts"
            }
            validatePoolMatchesScope(result.value, scope)
        }
        validateDedicatedEvidence(scope, resultEvidence(result), allowManual = false)
        syncMutex.withLock {
            db.withTransaction {
                doPersistQuotaPool(scope, SnapshotKind.DEDICATED_ACCOUNT_QUOTA, result)
            }
        }
        return quota(scope, SnapshotKind.ACCOUNT_QUOTA)
    }

    /**
     * Key 層級 quota：只收 safe ref（[credentialRef] 必須等於 `scope.credentialId`），
     * 原始 secret 永不進本層（只在 adapter HTTP 邊界經 vault 取值）。
     */
    suspend fun syncTokenQuota(
        scope: PricingScope,
        credentialRef: String,
        result: DedicatedResult<QuotaPool>,
    ): StoredQuota {
        requireSafeRef(credentialRef)
        require(scope.credentialId == credentialRef) { "Credential ref mismatch" }
        validateQuotaScope(scope, requireCredential = true)
        if (result is DedicatedResult.Success) {
            require(result.value.provenance != DedicatedProvenance.MANUAL) {
                "Manual must not impersonate dedicated facts"
            }
            validatePoolMatchesScope(result.value, scope)
        }
        validateDedicatedEvidence(scope, resultEvidence(result), allowManual = false)
        syncMutex.withLock {
            db.withTransaction {
                doPersistQuotaPool(scope, SnapshotKind.DEDICATED_TOKEN_QUOTA, result)
            }
        }
        return quota(scope, SnapshotKind.TOKEN_QUOTA)
    }

    /** OpenRouter `GET /api/v1/key` 觀察維度（非執行 gate）：整數原值精確保存，exempt 僅為 policy。 */
    suspend fun syncOpenRouterKeyQuota(
        scope: PricingScope,
        credentialRef: String,
        result: DedicatedResult<OpenRouterFreeAdapter.KeyQuotaFacts>,
    ): StoredQuota {
        requireSafeRef(credentialRef)
        require(scope.credentialId == credentialRef) { "Credential ref mismatch" }
        require(scope.modelId == "") { "Key quota scope must be global" }
        require(scope.group == null) { "Key quota scope must be global" }
        validateQuotaScope(scope, requireCredential = true)
        validateDedicatedEvidence(scope, resultEvidence(result), allowManual = false)
        syncMutex.withLock {
            db.withTransaction {
                doPersistKeyQuota(scope, result)
            }
        }
        return quota(scope, SnapshotKind.TOKEN_QUOTA)
    }

    /**
     * 多 pool 批次（per-model 不合併）：每筆自帶完整 [PricingScope]，同批次 scopeKey＋kind
     * 重複即拒絕（避免平均／覆蓋）。Capability 獨立缺失：單筆 Failure／Unsupported 只影響自己。
     * M6：全批驗證在鎖外，鎖只護 persist；回讀在鎖外逐筆讀。
     */
    suspend fun syncQuotaPools(entries: List<DedicatedQuotaEntry>): List<StoredQuota> {
        require(entries.isNotEmpty() && entries.size <= 1000) { "Invalid entry count" }
        val seen = HashSet<Triple<String, String, String>>()
        for (entry in entries) {
            entry.credentialRef?.let {
                requireSafeRef(it)
                require(entry.scope.credentialId == it) { "Credential ref mismatch" }
            }
            val kind = if (entry.scope.credentialId != null) SnapshotKind.DEDICATED_TOKEN_QUOTA else SnapshotKind.DEDICATED_ACCOUNT_QUOTA
            validateQuotaScope(entry.scope, requireCredential = entry.scope.credentialId != null)
            if (entry.result is DedicatedResult.Success) {
                if (kind == SnapshotKind.DEDICATED_ACCOUNT_QUOTA) {
                    require(entry.result.value.scopeKind != QuotaScopeKind.PER_KEY) { "Per-key pool must use token scope" }
                    require(entry.result.value.scopeKind != QuotaScopeKind.PER_KEY_MODEL) { "Per-key pool must use token scope" }
                }
                require(entry.result.value.provenance != DedicatedProvenance.MANUAL) {
                    "Manual must not impersonate dedicated facts"
                }
                validatePoolMatchesScope(entry.result.value, entry.scope)
            }
            validateDedicatedEvidence(entry.scope, resultEvidence(entry.result), allowManual = false)
            // B3：同 scope＋kind＋pool 維度重複即拒絕（含 quotaSlice，避免同 scope 多切片互蓋）。
            val poolKey = if (entry.result is DedicatedResult.Success) {
                "${entry.result.value.scopeKind}/${entry.result.value.projectId}/${entry.result.value.quotaSlice}"
            } else {
                resultStatus(entry.result)
            }
            require(seen.add(Triple(entry.scope.storageKey(), kind.name, poolKey))) {
                "Quota entries must not share scope"
            }
        }
        syncMutex.withLock {
            db.withTransaction {
                for (entry in entries) {
                    val kind = if (entry.scope.credentialId != null) SnapshotKind.DEDICATED_TOKEN_QUOTA else SnapshotKind.DEDICATED_ACCOUNT_QUOTA
                    doPersistQuotaPool(entry.scope, kind, entry.result)
                }
            }
        }
        return entries.map {
            val kind = if (it.scope.credentialId != null) SnapshotKind.TOKEN_QUOTA else SnapshotKind.ACCOUNT_QUOTA
            quota(it.scope, kind, (it.result as? DedicatedResult.Success<QuotaPool>)?.value?.quotaSlice?.name)
        }
    }

    /**
     * dedicated 價格回讀：head 一律為 `DEDICATED_PRICE`（[dedicatedKind] 為唯一映射點），
     * 故同 scope 的 NewApi `PRICE` head 不會被本層讀取或推進。
     */
    suspend fun price(scope: PricingScope): StoredPrice = db.withTransaction {
        val head = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.DEDICATED_PRICE.name)
        StoredPrice(
            head?.let { db.snapshotFactDao().evidence(it.latestEvidenceId) },
            head?.lastGoodSnapshotId?.let { db.priceDao().getById(it)?.toDomain() },
        )
    }

    /**
     * dedicated quota 回讀：接受既有 kind 名（`ACCOUNT_QUOTA`／`TOKEN_QUOTA`）或已
     * namespaced 的 `DEDICATED_*`（映射後同義）；`MANUAL_*` 一律拒絕。
     */
    suspend fun quota(scope: PricingScope, kind: SnapshotKind): StoredQuota = db.withTransaction {
        require(kind in setOf(
            SnapshotKind.ACCOUNT_QUOTA,
            SnapshotKind.TOKEN_QUOTA,
            SnapshotKind.DEDICATED_ACCOUNT_QUOTA,
            SnapshotKind.DEDICATED_TOKEN_QUOTA,
        )) {
            "Dedicated quota only uses ACCOUNT_QUOTA/TOKEN_QUOTA"
        }
        val head = db.snapshotFactDao().head(scope.storageKey(), kind.dedicatedKind().name)
        StoredQuota(
            head?.let { db.snapshotFactDao().evidence(it.latestEvidenceId) },
            head?.lastGoodSnapshotId?.let { db.quotaDao().getById(it)?.toDomain() },
        )
    }

    suspend fun quota(scope: PricingScope, kind: SnapshotKind, quotaSlice: String?): StoredQuota = db.withTransaction {
        require(kind in setOf(
            SnapshotKind.ACCOUNT_QUOTA,
            SnapshotKind.TOKEN_QUOTA,
            SnapshotKind.DEDICATED_ACCOUNT_QUOTA,
            SnapshotKind.DEDICATED_TOKEN_QUOTA,
        )) {
            "Dedicated quota only uses ACCOUNT_QUOTA/TOKEN_QUOTA"
        }
        if (quotaSlice.isNullOrEmpty()) {
            // existing :356 body verbatim (head + evidence + quotaDao read)
            val head = db.snapshotFactDao().head(scope.storageKey(), kind.dedicatedKind().name)
            StoredQuota(
                head?.let { db.snapshotFactDao().evidence(it.latestEvidenceId) },
                head?.lastGoodSnapshotId?.let { db.quotaDao().getById(it)?.toDomain() },
            )
        } else {
            val head = db.snapshotFactDao().sliceHead(scope.storageKey(), kind.dedicatedKind().name, quotaSlice)
            StoredQuota(
                head?.let { db.snapshotFactDao().evidence(it.latestEvidenceId) },
                head?.lastGoodSnapshotId?.let { db.quotaDao().getById(it)?.toDomain() },
            )
        }
    }

    suspend fun history(scope: PricingScope, kind: SnapshotKind): List<SnapshotFactEntity> =
        db.snapshotFactDao().history(scope.storageKey(), kind.dedicatedKind().name)

    // ---- persist (呼叫端已在 transaction 內；只做映射＋evidence／head 維護，不做 USD 換算) ----

    private suspend fun doPersistPricing(scope: PricingScope, result: DedicatedResult<PricingFact>) {
        val evidence = resultEvidence(result)
        val status = resultStatus(result)
        when (result) {
            is DedicatedResult.Success -> {
                val fact = result.value
                val (input, output, fixed, fixedAbsent) = pricingLegsOf(fact)
                val probe = PriceSnapshot(
                    id = "probe", scope = scope, observedAt = evidence.observedAt,
                    input = input, output = output, fixedRequest = fixed,
                    fixedRequestKnownAbsent = fixedAbsent, cacheRead = null, cacheWrite = null,
                    source = evidence.source, evidenceId = "probe",
                )
                val state = PricingPolicy.evaluate(probe).state
                val priceUnit = when (fact.mode) {
                    PricingMode.PER_TOKEN -> "USD_PER_1K_TOKENS"
                    PricingMode.FIXED_PER_REQUEST -> "USD_PER_REQUEST"
                    else -> null
                }
                // H5：同 scope＋kind＋observedAt＋state＋status＋priceUnit＋source＋legs 才冪等跳過。
                if (isDuplicatePrice(scope, evidence, state.name, status, probe, priceUnit)) return
                val id = newId()
                val snapshot = probe.copy(id = id, evidenceId = id)
                val state2 = PricingPolicy.evaluate(snapshot).state
                db.priceDao().insert(snapshot.toEntity())
                record(
                    scope, evidence,
                    baseFact(scope, SnapshotKind.DEDICATED_PRICE, evidence, status, state2.name, id, snapshotId = id).copy(
                        providerVersion = safeVersion(evidence.providerVersion),
                        priceUnit = priceUnit,
                    ),
                    state2 != PriceState.UNKNOWN,
                )
            }
            is DedicatedResult.Unsupported,
            is DedicatedResult.Failure,
            -> {
                // M4：Unsupported／Failure 不建 snapshot 列（snapshotId=null），只 append fact＋evidence。
                if (isDuplicatePrice(scope, evidence, PriceState.UNKNOWN.name, status, null, null)) return
                val id = newId()
                record(
                    scope, evidence,
                    baseFact(scope, SnapshotKind.DEDICATED_PRICE, evidence, status, PriceState.UNKNOWN.name, id, snapshotId = null).copy(
                        providerVersion = safeVersion(evidence.providerVersion),
                    ),
                    false,
                )
            }
        }
    }

    private suspend fun doPersistOpenRouterPricing(
        scope: PricingScope,
        result: DedicatedResult<OpenRouterFreeAdapter.CatalogEntry>,
    ) {
        val evidence = resultEvidence(result)
        val status = resultStatus(result)
        if (result is DedicatedResult.Success) {
            val entry = result.value
            // 精確字串→BigDecimal（不可解析即 null，不猜付費）；負值／不安全精度即 null。
            val prompt = entry.prompt?.trim()?.let { runCatching { BigDecimal(it) }.getOrNull() }?.let(::safeLeg)
            val completion = entry.completion?.trim()?.let { runCatching { BigDecimal(it) }.getOrNull() }?.let(::safeLeg)
            // 附加 request 費用：null＝不存在／未知（fixed 未知，不宣稱 absent）；"0"＝免費零值；其餘精確值。
            val fixed: BigDecimal?
            val fixedAbsent: Boolean
            when (entry.request?.trim()) {
                null -> {
                    fixed = null
                    fixedAbsent = false
                }
                "0", "0.0", "0.00" -> {
                    fixed = BigDecimal.ZERO
                    fixedAbsent = false
                }
                else -> {
                    fixed = runCatching { BigDecimal(entry.request!!.trim()) }.getOrNull()?.let(::safeLeg)
                    fixedAbsent = false
                }
            }
            // prompt／completion 缺欄即 UNKNOWN（不猜）；image／web_search 附加費本版不進 legs，
            // 故 request 未知時整體 UNKNOWN（fail-closed，不以雙零冒充 FREE）。
            val probe = PriceSnapshot(
                id = "probe", scope = scope, observedAt = evidence.observedAt,
                input = prompt, output = completion, fixedRequest = fixed,
                fixedRequestKnownAbsent = fixedAbsent, cacheRead = null, cacheWrite = null,
                source = evidence.source, evidenceId = "probe",
            )
            val state = PricingPolicy.evaluate(probe).state
            if (isDuplicatePrice(scope, evidence, state.name, status, probe, "USD_PER_1K_TOKENS")) return
            val id = newId()
            val snapshot = probe.copy(id = id, evidenceId = id)
            db.priceDao().insert(snapshot.toEntity())
            record(
                scope, evidence,
                baseFact(scope, SnapshotKind.DEDICATED_PRICE, evidence, status, state.name, id, snapshotId = id).copy(
                    providerVersion = safeVersion(evidence.providerVersion),
                    priceUnit = "USD_PER_1K_TOKENS",
                ),
                state != PriceState.UNKNOWN,
            )
        } else {
            // M4：無 snapshot 列。
            if (isDuplicatePrice(scope, evidence, PriceState.UNKNOWN.name, status, null, null)) return
            val id = newId()
            record(
                scope, evidence,
                baseFact(scope, SnapshotKind.DEDICATED_PRICE, evidence, status, PriceState.UNKNOWN.name, id, snapshotId = null).copy(
                    providerVersion = safeVersion(evidence.providerVersion),
                ),
                false,
            )
        }
    }

    private suspend fun doPersistAmdPricing(
        scope: PricingScope,
        result: DedicatedResult<AmdAdapter.AmdModelEntry>,
        providerType: DedicatedProviderType,
    ) {
        val evidence = resultEvidence(result)
        val status = resultStatus(result)
        if (result is DedicatedResult.Success) {
            val entry = result.value
            // H2：dedicated 自有 instance（credits／GPU-hour 制）不寫 per-token 價，一律 UNKNOWN。
            val (input, output, fixedAbsent, priceUnit) = if (providerType == DedicatedProviderType.AMD_DEDICATED) {
                Quad(null, null, false, null)
            } else {
                // USD／token→USD／1K（精確 BigDecimal，不經 Double）；缺失即 null。
                val i = entry.promptPerTokenUsd?.let { safeLeg(it)?.multiply(THOUSAND)?.let(::safeLeg) }
                val o = entry.completionPerTokenUsd?.let { safeLeg(it)?.multiply(THOUSAND)?.let(::safeLeg) }
                // Shared 無固定費維度：有雙腿即 absent=true（允許 FREE 派生）；缺腿即 UNKNOWN 由 policy 判定。
                Quad(i, o, true, "USD_PER_1K_TOKENS")
            }
            val probe = PriceSnapshot(
                id = "probe", scope = scope, observedAt = evidence.observedAt,
                input = input, output = output, fixedRequest = null,
                fixedRequestKnownAbsent = fixedAbsent, cacheRead = null, cacheWrite = null,
                source = evidence.source, evidenceId = "probe",
            )
            val state = PricingPolicy.evaluate(probe).state
            if (isDuplicatePrice(scope, evidence, state.name, status, probe, priceUnit)) return
            val id = newId()
            val snapshot = probe.copy(id = id, evidenceId = id)
            db.priceDao().insert(snapshot.toEntity())
            record(
                scope, evidence,
                baseFact(scope, SnapshotKind.DEDICATED_PRICE, evidence, status, state.name, id, snapshotId = id).copy(
                    providerVersion = safeVersion(evidence.providerVersion),
                    priceUnit = priceUnit,
                ),
                state != PriceState.UNKNOWN,
            )
        } else {
            // M4：無 snapshot 列。
            if (isDuplicatePrice(scope, evidence, PriceState.UNKNOWN.name, status, null, null)) return
            val id = newId()
            record(
                scope, evidence,
                baseFact(scope, SnapshotKind.DEDICATED_PRICE, evidence, status, PriceState.UNKNOWN.name, id, snapshotId = null).copy(
                    providerVersion = safeVersion(evidence.providerVersion),
                ),
                false,
            )
        }
    }

    private suspend fun doPersistPricingMode(scope: PricingScope, result: DedicatedResult<PricingMode>) {
        val evidence = resultEvidence(result)
        val status = resultStatus(result)
        // 模式本身不帶 legs（NO_PER_TOKEN／PER_GPU／CREDITS／UNKNOWN 皆非價格 0），一律 UNKNOWN。
        // M4：模式觀測不建 snapshot 列（無 legs 可查），只留 fact＋evidence。
        if (isDuplicatePrice(scope, evidence, PriceState.UNKNOWN.name, status, null, null)) return
        val id = newId()
        val good = false
        record(
            scope, evidence,
            baseFact(scope, SnapshotKind.DEDICATED_PRICE, evidence, status, PriceState.UNKNOWN.name, id, snapshotId = null).copy(
                providerVersion = safeVersion(evidence.providerVersion),
            ),
            good,
        )
    }

    private suspend fun doPersistQuotaPool(
        scope: PricingScope,
        kind: SnapshotKind,
        result: DedicatedResult<QuotaPool>,
    ) {
        val evidence = resultEvidence(result)
        val status = resultStatus(result)
        when (result) {
            is DedicatedResult.Success -> {
                val pool = result.value
                val state = quotaStateOf(pool)
                val estimated = estimatedOf(pool)
                // H5：同 scope＋kind＋observedAt＋state＋status＋quota 維度＋isEstimated 才跳過。
                if (isDuplicateQuota(scope, kind, evidence.observedAt, state.name, status, pool, estimated)) return
                val id = newId()
                val snapshot = QuotaSnapshot(id, scope, evidence.observedAt, state, evidence.source, id)
                db.quotaDao().insert(snapshot.toEntity())
                record(
                    scope, evidence,
                    baseFact(scope, kind, evidence, status, state.name, id, snapshotId = id).copy(
                        providerVersion = safeVersion(evidence.providerVersion),
                        quotaScopeKind = pool.scopeKind.name,
                        quotaProjectId = pool.projectId,
                        quotaSlice = pool.quotaSlice?.name,
                        isEstimated = estimated,
                    ),
                    state != QuotaState.UNKNOWN,
                )
            }
            is DedicatedResult.Unsupported,
            is DedicatedResult.Failure,
            -> {
                // M4：無 snapshot 列，snapshotId=null。
                if (isDuplicateQuota(scope, kind, evidence.observedAt, QuotaState.UNKNOWN.name, status, null, false)) return
                val id = newId()
                record(
                    scope, evidence,
                    baseFact(scope, kind, evidence, status, QuotaState.UNKNOWN.name, id, snapshotId = null).copy(
                        providerVersion = safeVersion(evidence.providerVersion),
                        isEstimated = false,
                    ),
                    false,
                )
            }
        }
    }

    private suspend fun doPersistKeyQuota(
        scope: PricingScope,
        result: DedicatedResult<OpenRouterFreeAdapter.KeyQuotaFacts>,
    ) {
        val evidence = resultEvidence(result)
        val status = resultStatus(result)
        if (result is DedicatedResult.Success) {
            val facts = result.value
            // H3 remaining 語意：null→UNKNOWN；<=0→EXHAUSTED；否則 AVAILABLE（Long 精確，不經 Double）。
            // remainingIsPolicyNotCeiling=true（exempt／tier policy）即 Estimated，必須明示。
            val remaining = facts.freeRemaining
            val state = when {
                remaining == null -> QuotaState.UNKNOWN
                remaining <= 0L -> QuotaState.QUOTA_EXHAUSTED
                else -> QuotaState.QUOTA_AVAILABLE
            }
            val estimated = facts.remainingIsPolicyNotCeiling
            // H5：key quota 以 raws 全量＋estimated 去重（同 observedAt 不同 remaining 不得誤判去重）。
            if (isDuplicateKeyQuota(scope, evidence.observedAt, state.name, status, facts, estimated)) return
            val id = newId()
            val snapshot = QuotaSnapshot(id, scope, evidence.observedAt, state, evidence.source, id)
            db.quotaDao().insert(snapshot.toEntity())
            record(
                scope, evidence,
                baseFact(scope, SnapshotKind.DEDICATED_TOKEN_QUOTA, evidence, status, state.name, id, snapshotId = id).copy(
                    providerVersion = safeVersion(evidence.providerVersion),
                    remainingRaw = facts.freeRemaining,
                    usedRaw = facts.freeUsed,
                    grantedRaw = facts.freeLimit,
                    quotaScopeKind = QuotaScopeKind.PLATFORM_GLOBAL.name,
                    quotaProjectId = null,
                    quotaSlice = null,
                    isEstimated = estimated,
                ),
                state != QuotaState.UNKNOWN,
            )
        } else {
            // M4：無 snapshot 列。
            val failProbe = QuotaPool(
                scopeKind = QuotaScopeKind.PLATFORM_GLOBAL, modelId = null,
                provenance = DedicatedProvenance.OBSERVED,
            )
            if (isDuplicateQuota(scope, SnapshotKind.DEDICATED_TOKEN_QUOTA, evidence.observedAt, QuotaState.UNKNOWN.name, status, failProbe, false)) return
            val id = newId()
            record(
                scope, evidence,
                baseFact(scope, SnapshotKind.DEDICATED_TOKEN_QUOTA, evidence, status, QuotaState.UNKNOWN.name, id, snapshotId = null).copy(
                    providerVersion = safeVersion(evidence.providerVersion),
                    quotaScopeKind = QuotaScopeKind.PLATFORM_GLOBAL.name,
                    isEstimated = false,
                ),
                false,
            )
        }
    }

    private suspend fun record(scope: PricingScope, evidence: DedicatedEvidence, fact: SnapshotFactEntity, good: Boolean) {
        require(evidence.observedAt >= 0) { "Invalid observation time" }
        db.evidenceDao().insert(
            EvidenceEntity(
                fact.evidenceId, fact.evidenceId, scope.providerId, scope.modelId,
                fact.status, evidence.httpStatus, evidence.observedAt, evidence.source,
            ),
        )
        db.snapshotFactDao().insert(fact)
        if (fact.quotaSlice.isNullOrEmpty()) {
            // existing :662-674 body verbatim (head / upsertHead)
            val old = db.snapshotFactDao().head(fact.scopeKey, fact.kind)
            val latest = old == null || fact.observedAt > old.latestObservedAt
            val newerGood = good && (old?.lastGoodObservedAt == null || fact.observedAt > old.lastGoodObservedAt)
            db.snapshotFactDao().upsertHead(
                SnapshotHeadEntity(
                    fact.scopeKey, fact.kind,
                    if (latest) fact.evidenceId else old!!.latestEvidenceId,
                    if (latest) fact.observedAt else old!!.latestObservedAt,
                    if (newerGood) fact.snapshotId else old?.lastGoodSnapshotId,
                    if (newerGood) fact.observedAt else old?.lastGoodObservedAt,
                ),
            )
        } else {
            require(fact.kind in setOf(
                SnapshotKind.ACCOUNT_QUOTA.name,
                SnapshotKind.TOKEN_QUOTA.name,
                SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name,
                SnapshotKind.DEDICATED_TOKEN_QUOTA.name,
            )) { "Slice only on quota kinds" }
            require(fact.quotaSlice in setOf("STANDARD_INTERACTIVE", "PRIORITY", "BATCH")) {
                "Unknown quota slice"
            }
            val old = db.snapshotFactDao().sliceHead(fact.scopeKey, fact.kind, fact.quotaSlice!!)
            val latest = old == null || fact.observedAt > old.latestObservedAt
            val newerGood = good && (old?.lastGoodObservedAt == null || fact.observedAt > old.lastGoodObservedAt)
            db.snapshotFactDao().upsertSliceHead(
                SnapshotSliceHeadEntity(
                    fact.scopeKey, fact.kind, fact.quotaSlice!!,
                    if (latest) fact.evidenceId else old!!.latestEvidenceId,
                    if (latest) fact.observedAt else old!!.latestObservedAt,
                    if (newerGood) fact.snapshotId else old?.lastGoodSnapshotId,
                    if (newerGood) fact.observedAt else old?.lastGoodObservedAt,
                ),
            )
        }
    }

    private fun baseFact(
        scope: PricingScope,
        kind: SnapshotKind,
        evidence: DedicatedEvidence,
        status: String,
        state: String,
        id: String,
        snapshotId: String? = id,
    ) = SnapshotFactEntity(
        evidenceId = id,
        scopeKey = scope.storageKey(),
        kind = kind.name,
        observedAt = evidence.observedAt,
        snapshotId = snapshotId,
        state = state,
        status = status,
        confidence = confidenceOf(evidence.provenance),
        providerVersion = safeVersion(evidence.providerVersion),
        pricingVersion = null,
        itemPricingVersion = null,
        quotaPerUnit = null,
        groupRatio = null,
        statusObservedAt = null,
        remainingRaw = null,
        usedRaw = null,
        grantedRaw = null,
        unlimited = null,
        expiresAtRaw = null,
        expiryMeaning = null,
        expressionHash = null,
        priceUnit = null,
        quotaScopeKind = null,
        quotaProjectId = null,
        quotaSlice = null,
        isEstimated = null,
    )

    // ---- H5 去重 helpers（呼叫端已在 mutex＋transaction 內） ----

    private suspend fun isDuplicatePrice(
        scope: PricingScope,
        evidence: DedicatedEvidence,
        state: String,
        status: String,
        probe: PriceSnapshot?,   // null = value-less observation (Failure/Unsupported/pricing-mode)
        priceUnit: String?,
    ): Boolean {
        val rows = db.snapshotFactDao().byScopeKindObserved(
            scope.storageKey(), SnapshotKind.DEDICATED_PRICE.name, evidence.observedAt,
        )
        return rows.any { row ->
            row.state == state && row.status == status && row.priceUnit == priceUnit &&
                matchesStoredPrice(row, probe, evidence.source)
        }
    }

    /** true only when the stored row is a replay of the same observation (persisted values + source). */
    private suspend fun matchesStoredPrice(
        row: SnapshotFactEntity,
        probe: PriceSnapshot?,
        source: String,
    ): Boolean {
        if (probe == null) {
            // Value-less row: comparable persisted facets are source + status (+state/priceUnit above).
            return row.snapshotId == null && db.evidenceDao().getById(row.evidenceId)?.source == source
        }
        val stored = row.snapshotId?.let { db.priceDao().getById(it) } ?: return false
        return stored.source == source &&
            decimalEquals(stored.input, probe.input) &&
            decimalEquals(stored.output, probe.output) &&
            decimalEquals(stored.fixedRequest, probe.fixedRequest) &&
            stored.fixedRequestKnownAbsent == probe.fixedRequestKnownAbsent
    }

    /** Numeric equality (BigDecimal value, not string/scale); unparseable stored value fails open. */
    private fun decimalEquals(stored: String?, incoming: BigDecimal?): Boolean = when {
        stored == null -> incoming == null
        incoming == null -> false
        else -> stored.toBigDecimalOrNull()?.compareTo(incoming) == 0
    }

    private suspend fun isDuplicateQuota(
        scope: PricingScope,
        kind: SnapshotKind,
        observedAt: Long,
        state: String,
        status: String,
        pool: QuotaPool?,
        estimated: Boolean,
    ): Boolean {
        val rows = db.snapshotFactDao().byScopeKindObserved(scope.storageKey(), kind.dedicatedKind().name, observedAt)
        return rows.any {
            it.state == state && it.status == status && it.isEstimated == estimated &&
                it.quotaScopeKind == pool?.scopeKind?.name &&
                it.quotaProjectId == pool?.projectId &&
                it.quotaSlice == pool?.quotaSlice?.name
        }
    }

    private suspend fun isDuplicateKeyQuota(
        scope: PricingScope,
        observedAt: Long,
        state: String,
        status: String,
        facts: OpenRouterFreeAdapter.KeyQuotaFacts,
        estimated: Boolean,
    ): Boolean {
        val rows = db.snapshotFactDao().byScopeKindObserved(scope.storageKey(), SnapshotKind.DEDICATED_TOKEN_QUOTA.name, observedAt)
        return rows.any {
            it.state == state && it.status == status &&
                it.remainingRaw == facts.freeRemaining &&
                it.usedRaw == facts.freeUsed &&
                it.grantedRaw == facts.freeLimit &&
                it.quotaScopeKind == QuotaScopeKind.PLATFORM_GLOBAL.name &&
                it.isEstimated == estimated
        }
    }

    private suspend fun validatePricingScope(scope: PricingScope) {
        require(scope.providerId.isNotBlank() && scope.operation.isNotBlank()) { "Invalid scope" }
        require(!scope.providerId.contains("::")) { "Scope must not contain concatenation" }
        require(!scope.modelId.contains("::")) { "Scope model must not contain concatenation" }
        (scope.group)?.let { require(!it.contains("::")) { "Scope group must not contain concatenation" } }
        require(db.providerDao().getById(scope.providerId) != null) { "Provider not found" }
        scope.accountId?.let {
            require(it.isNotBlank()) { "Invalid account" }
            require(db.accountDao().getById(it)?.providerId == scope.providerId) { "Account ownership mismatch" }
        }
        require(scope.credentialId == null) { "Pricing scope must not carry credential" }
        require(scope.modelId.isNotBlank()) { "Missing model" }
        require(scope.modelId.length <= 256) { "Model too long" }
        require(scope.modelId.none { c -> c.code < 0x20 || c.code == 0x7F }) { "Model must not contain control characters" }
        scope.group?.let {
            require(it.length <= 256) { "Group too long" }
            require(it.none { c -> c.code < 0x20 || c.code == 0x7F }) { "Group must not contain control characters" }
        }
        require(db.endpointDao().getById(scope.endpointId)?.providerId == scope.providerId) {
            "Endpoint ownership mismatch"
        }
    }

    private suspend fun validateQuotaScope(scope: PricingScope, requireCredential: Boolean) {
        require(scope.providerId.isNotBlank() && scope.operation.isNotBlank()) { "Invalid scope" }
        require(!scope.providerId.contains("::")) { "Scope must not contain concatenation" }
        require(!scope.modelId.contains("::")) { "Scope model must not contain concatenation" }
        (scope.group)?.let { require(!it.contains("::")) { "Scope group must not contain concatenation" } }
        require(db.providerDao().getById(scope.providerId) != null) { "Provider not found" }
        scope.accountId?.let {
            require(it.isNotBlank()) { "Invalid account" }
            require(db.accountDao().getById(it)?.providerId == scope.providerId) { "Account ownership mismatch" }
        }
        if (requireCredential) {
            val ref = requireNotNull(scope.credentialId) { "Credential required" }
            requireSafeRef(ref)
            val credential = db.credentialDao().getById(ref) ?: throw IllegalArgumentException("Credential ownership mismatch")
            require(credential.enabled) { "Credential ownership mismatch" }
            val account = requireNotNull(scope.accountId) { "Account required" }
            require(credential.accountId == account) { "Credential ownership mismatch" }
        } else {
            require(scope.credentialId == null) { "Account quota must not carry credential" }
        }
        require(scope.modelId.length <= 256) { "Model too long" }
        require(scope.modelId.none { c -> c.code < 0x20 || c.code == 0x7F }) { "Model must not contain control characters" }
        scope.group?.let {
            require(it.length <= 256) { "Group too long" }
            require(it.none { c -> c.code < 0x20 || c.code == 0x7F }) { "Group must not contain control characters" }
        }
        if (scope.endpointId.isNotEmpty()) {
            require(db.endpointDao().getById(scope.endpointId)?.providerId == scope.providerId) {
                "Endpoint ownership mismatch"
            }
        }
    }

    private fun validatePoolMatchesScope(pool: QuotaPool, scope: PricingScope) {
        // B6：UNKNOWN scope（官方未定義）禁寫具名 scope。
        if (pool.scopeKind == QuotaScopeKind.UNKNOWN) {
            require(pool.modelId == null) { "UNKNOWN pool must not carry model" }
            require(pool.projectId == null) { "UNKNOWN pool must not carry project" }
            require(pool.quotaSlice == null) { "UNKNOWN pool must not carry slice" }
            require(scope.modelId == "") { "UNKNOWN pool requires global scope" }
            require(scope.group == null) { "UNKNOWN pool requires global scope" }
            require(scope.credentialId == null) { "UNKNOWN pool must not use credential scope" }
            return
        }
        val poolModel = pool.modelId
        if (poolModel != null) {
            require(!poolModel.contains("::")) {
                "Quota pool carries concatenated project::model; caller must split project to group"
            }
            require(poolModel.length <= 256) { "Pool model too long" }
            require(poolModel.none { c -> c.code < 0x20 || c.code == 0x7F }) { "Pool model must not contain control characters" }
            require(scope.modelId == poolModel) { "Quota model mismatch" }
        } else {
            require(scope.modelId == "") { "Global pool requires global scope" }
        }
        // B3：project 驗證（pool.projectId == scope.group，兩皆 null 或完全相等）。
        val poolProject = pool.projectId
        if (poolProject != null) {
            require(!poolProject.contains("::")) { "Quota pool project must not contain concatenation" }
            require(poolProject.isNotBlank()) { "Quota pool project must not be blank" }
            require(poolProject.length <= 256) { "Pool project too long" }
            require(poolProject.none { c -> c.code < 0x20 || c.code == 0x7F }) { "Pool project must not contain control characters" }
            require(scope.group == poolProject) { "Quota project mismatch" }
        } else {
            // pool 無 project 時，scope 亦須無 project（具名 project 必須來自 pool，不可自造）。
            // 全域／org／key 等無 project 語意的 pool 配具名 group 即 mis-scope。
            if (pool.scopeKind == QuotaScopeKind.PER_PROJECT || pool.scopeKind == QuotaScopeKind.PER_PROJECT_MODEL) {
                throw IllegalArgumentException("Project pool requires projectId")
            }
            require(scope.group == null) { "Quota project mismatch" }
        }
        // B3：同 model 全域池不得帶切片；具名池切片可 null（舊相容）或明確三態。
        if (poolModel == null) {
            require(pool.quotaSlice == null) { "Global pool must not carry slice" }
        }
        // B3：PER_PROJECT／PER_PROJECT_MODEL 必須帶 project（上已驗）；PER_KEY／PER_KEY_MODEL
        // 與 credential 綁定由呼叫端 API（account vs token）保證，此處不再重驗。
    }

    private fun validateDedicatedEvidence(scope: PricingScope, evidence: DedicatedEvidence, allowManual: Boolean) {
        require(evidence.observedAt >= 0) { "Invalid observation time" }
        require(evidence.source.isNotBlank()) { "Invalid source" }
        require(evidence.source.length <= 256) { "Source too long" }
        require(evidence.source.none { it.code < 0x20 || it.code == 0x7F }) { "Source must not contain control characters" }
        require(DEDICATED_SOURCE_PREFIXES.any { evidence.source.startsWith(it) }) {
            "Unexpected dedicated source"
        }
        // B2：source 前綴必須綁定 scope.providerId（跨家寫入 fail-closed）。
        validateSourceMatchesProvider(scope.providerId, evidence.source)
        if (!allowManual) {
            require(evidence.provenance != DedicatedProvenance.MANUAL) { "Manual must not impersonate dedicated facts" }
        }
    }

    /**
     * B2／P6 INT-1：source 家族必須與 scope 綁定，跨家寫入 fail-closed（X-004）。
     *
     * 兩條判定路徑（皆不猜）：
     * 1. `scope.providerId` 本身即家族鍵（含 `nvidia-h`／`nvidia-s` 別名）：source 家族
     *    必須與之完全相同，否則拒絕（例：`nvidia-h` scope 收 `groq/…` 直接拒）。
     * 2. `scope.providerId` 無家族語意（onboarding 指派的裸 UUID 或其他自訂 id）：
     *    無法從 id 反推家族，改以 source 前綴唯一判定（家族路由已由呼叫端
     *    `resolveDedicatedRoute` 解析）；前綴不在 [DEDICATED_SOURCE_FAMILY] 即家族
     *    無法判定，明確拒絕（不放行、不猜家族、不退回 `<providerId>/` 比對）。
     */
    private fun validateSourceMatchesProvider(providerId: String, source: String) {
        val providerFamily = familyOfProviderId(providerId)
        val sourceFamily = familyOfSource(source)
        if (providerFamily != null) {
            require(providerFamily == sourceFamily) { "Dedicated source does not match provider" }
            return
        }
        require(sourceFamily != null) { "Dedicated source family could not be determined" }
    }

    companion object {
        /** Dedicated 來源前綴（各家區分，不含 `newapi/`／`USER`；obs／official 皆保留原字串）。 */
        val DEDICATED_SOURCE_PREFIXES: List<String> = listOf(
            "nvidia-hosted/",
            "nvidia-selfhosted/",
            "amd/",
            "openrouter/",
            "gemini/",
            "groq/",
        )

        /**
         * source 前綴 → 家族鍵（[DEDICATED_SOURCE_PREFIXES] 的唯一真相對應）；
         * 家族無法由前綴判定回 null（fail-closed，不猜）。nvidia hosted／self-hosted 分列。
         */
        private val DEDICATED_SOURCE_FAMILY: List<Pair<String, String>> = listOf(
            "nvidia-hosted/" to "nvidia-hosted",
            "nvidia-selfhosted/" to "nvidia-selfhosted",
            "amd/" to "amd",
            "openrouter/" to "openrouter",
            "gemini/" to "gemini",
            "groq/" to "groq",
        )

        /** providerId → 家族鍵；`nvidia-h`／`nvidia-s` 為 hosted／self-hosted 的別名。 */
        private fun familyOfProviderId(providerId: String): String? = when (providerId) {
            "nvidia-h", "nvidia-hosted" -> "nvidia-hosted"
            "nvidia-s", "nvidia-selfhosted" -> "nvidia-selfhosted"
            else -> DEDICATED_SOURCE_FAMILY.firstOrNull { it.second == providerId }?.second
        }

        /** source 前綴 → 家族鍵；前綴不屬任何已知家族回 null（家族無法判定）。 */
        private fun familyOfSource(source: String): String? =
            DEDICATED_SOURCE_FAMILY.firstOrNull { source.startsWith(it.first) }?.second

        /** Quota 全域 operation（與 `NewApiDomainMapper.OPERATION_GLOBAL="UNKNOWN"` 同值，讀寫一致）。 */
        const val OPERATION_GLOBAL = "UNKNOWN"

        /**
         * Gemini Project×Model scope：`projectId` 只進 `group`，`model` 只進 `modelId`，
         * 永不拼接（ADAPT-005 reviewer 要求）。兩者含 `::` 即拒絕。
         */
        fun geminiScope(
            providerId: String,
            accountId: String?,
            credentialRef: String?,
            projectId: String,
            modelId: String,
            endpointId: String,
            operation: String,
        ): PricingScope {
            require(providerId.isNotBlank() && projectId.isNotBlank() && modelId.isNotBlank()) {
                "Gemini scope ids must not be blank"
            }
            require(!projectId.contains("::") && !modelId.contains("::")) {
                "Gemini project/model must not contain concatenation"
            }
            credentialRef?.let { requireSafeRef(it) }
            return PricingScope(providerId, accountId, credentialRef, modelId, projectId, endpointId, operation)
        }

        fun pricingScope(
            providerId: String,
            accountId: String?,
            modelId: String,
            group: String?,
            endpointId: String,
            operation: String,
        ): PricingScope = PricingScope(providerId, accountId, null, modelId, group, endpointId, operation)

        fun accountQuotaScope(
            providerId: String,
            accountId: String?,
            group: String? = null,
            modelId: String = "",
            endpointId: String = "",
        ): PricingScope = PricingScope(providerId, accountId, null, modelId, group, endpointId, OPERATION_GLOBAL)

        fun tokenQuotaScope(
            providerId: String,
            accountId: String,
            credentialRef: String,
            group: String? = null,
            modelId: String = "",
            endpointId: String = "",
        ): PricingScope {
            requireSafeRef(credentialRef)
            return PricingScope(providerId, accountId, credentialRef, modelId, group, endpointId, OPERATION_GLOBAL)
        }

        /**
         * [QuotaPool]→[QuotaState]（limits≠remaining，故 raws 全 null，只派生 state）：
         * 任一 `KNOWN`（含上界）→可用家族（`PLATFORM_GLOBAL`＋KNOWN→`PUBLIC_POOL`，
         * 全 `NO_PROVIDER_QUOTA`→`NO_KNOWN_CONSUMABLE_QUOTA`）；其餘全 UNKNOWN 系
         * （UNKNOWN／REQUIRES_LOGIN／UNPUBLISHED／NOT_OFFERED／DIMENSION 不適用）→UNKNOWN。
         */
        fun quotaStateOf(pool: QuotaPool): QuotaState {
            val dims = listOf(pool.rpm, pool.rpd, pool.tpm, pool.tpd)
            if (dims.all { it.state == QuotaValueState.NO_PROVIDER_QUOTA }) {
                return QuotaState.NO_KNOWN_CONSUMABLE_QUOTA
            }
            val known = dims.any { it.state == QuotaValueState.KNOWN && it.value != null }
            if (known) {
                return if (pool.scopeKind == QuotaScopeKind.PLATFORM_GLOBAL) {
                    QuotaState.PUBLIC_POOL
                } else {
                    QuotaState.QUOTA_AVAILABLE
                }
            }
            return QuotaState.UNKNOWN
        }

        /** provenance→confidence（official 家族才 `OFFICIAL`；OBSERVED／EXTERNAL 等原樣保留，MANUAL 另處）。 */
        fun confidenceOf(provenance: DedicatedProvenance): String = when (provenance) {
            DedicatedProvenance.OFFICIAL_DOC,
            DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX,
            DedicatedProvenance.OFFICIAL_STAFF_FORUM,
            -> "OFFICIAL"
            else -> provenance.name
        }

        /**
         * H3 估算標記：任一 `KNOWN` 維度帶 `upperBound／requiresLogin／appliesToMostModels`
         * 即 Estimated（例：NVIDIA hosted RPM 40 上界＋多數模型＋需登入）；精確 KNOWN 才 false；
         * 全 UNKNOWN 系（無 KNOWN）回 false（無估算，只有未知）。
         */
        fun estimatedOf(pool: QuotaPool): Boolean {
            val dims = listOf(pool.rpm, pool.rpd, pool.tpm, pool.tpd)
            return dims.any {
                it.state == QuotaValueState.KNOWN && it.value != null &&
                    (it.upperBound || it.requiresLogin || it.appliesToMostModels)
            }
        }
    }
}

/** 批次 quota 單筆：scope 自帶完整七維（Gemini project 已在 group），ref null 即帳號層級。 */
data class DedicatedQuotaEntry(
    val scope: PricingScope,
    val credentialRef: String?,
    val result: DedicatedResult<QuotaPool>,
)

/**
 * MAJOR8：dedicated 落庫 kind 命名空間（唯一映射點）。
 *
 * 對外 API 沿用既有 kind 名（`PRICE`／`ACCOUNT_QUOTA`／`TOKEN_QUOTA`），對內一律
 * 寫／讀 `DEDICATED_*`；`MANUAL_*` 與已 namespaced 的 kind 為恆等（不誤改手填槽）。
 * 效果：同七維 scopeKey 下 dedicated 與 NewApi 官方 head／lastGood／evidence 互不覆蓋。
 */
internal fun SnapshotKind.dedicatedKind(): SnapshotKind = when (this) {
    SnapshotKind.PRICE -> SnapshotKind.DEDICATED_PRICE
    SnapshotKind.ACCOUNT_QUOTA -> SnapshotKind.DEDICATED_ACCOUNT_QUOTA
    SnapshotKind.TOKEN_QUOTA -> SnapshotKind.DEDICATED_TOKEN_QUOTA
    else -> this
}

private fun <T> resultEvidence(result: DedicatedResult<T>): DedicatedEvidence = when (result) {
    is DedicatedResult.Success -> result.evidence
    is DedicatedResult.Unsupported -> result.evidence
    is DedicatedResult.Failure -> result.evidence
}

private fun <T> resultStatus(result: DedicatedResult<T>): String = when (result) {
    is DedicatedResult.Success -> "OK"
    is DedicatedResult.Unsupported -> DedicatedStatus.CAPABILITY_UNSUPPORTED.name
    is DedicatedResult.Failure -> result.status.name
}

private fun pricingLegsOf(fact: PricingFact): PricingLegs {
    return when (fact.mode) {
        PricingMode.PER_TOKEN -> PricingLegs(
            safeLeg(fact.inputPer1kUsd), safeLeg(fact.outputPer1kUsd), null, true,
        )
        PricingMode.FIXED_PER_REQUEST -> PricingLegs(
            null, null, safeLeg(fact.fixedPerRequestUsd), false,
        )
        else -> PricingLegs(null, null, null, false)
    }
}

private data class PricingLegs(
    val input: BigDecimal?,
    val output: BigDecimal?,
    val fixed: BigDecimal?,
    val fixedAbsent: Boolean,
)

private data class Quad(
    val input: BigDecimal?,
    val output: BigDecimal?,
    val fixedAbsent: Boolean,
    val priceUnit: String?,
)

private fun estimatedOf(pool: QuotaPool): Boolean = DedicatedSnapshotSync.estimatedOf(pool)

private val THOUSAND: BigDecimal = BigDecimal("1000")

private fun safeLeg(value: BigDecimal?): BigDecimal? = value?.takeIf {
    it.signum() >= 0 && it.precision() <= 128 && it.scale().toLong() in -128L..128L
}

private fun safeVersion(value: String?): String? = value?.takeIf {
    it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._-]+")) && !it.startsWith("sk-")
}

private fun requireSafeRef(value: String) {
    require(value.isNotBlank()) { "Credential ref must not be blank" }
    require(value.length <= 256) { "Credential ref too long" }
    require(value.none { it.code < 0x20 || it.code == 0x7F }) { "Credential ref must not contain control characters" }
    require(!value.startsWith("sk-")) { "Credential ref must not carry secret" }
    require(value.none { it.isWhitespace() }) { "Credential ref must not contain whitespace" }
}

private fun newId(): String = "fact:" + UUID.randomUUID()
