package dev.modelatlas.app.data

import androidx.room.withTransaction
import dev.modelatlas.core.dedicated.AmdAdapter
import dev.modelatlas.core.dedicated.DedicatedCapability
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.GeminiFreeAdapter
import dev.modelatlas.core.dedicated.GroqFreeAdapter
import dev.modelatlas.core.dedicated.NvidiaHostedAdapter
import dev.modelatlas.core.dedicated.NvidiaSelfHostedAdapter
import dev.modelatlas.core.dedicated.OpenRouterFreeAdapter
import dev.modelatlas.core.dedicated.QuotaScopeKind
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySink
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.lmspeed.LMSPEED_DEFAULT_TTL_MILLIS
import dev.modelatlas.core.lmspeed.LmspeedFetcher
import dev.modelatlas.core.lmspeed.LmspeedImportResult
import dev.modelatlas.core.lmspeed.LmspeedImporter
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiDomainMapper
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** 手動同步結果：`tokenQuota=null` 表示該 provider 無可用 `INFERENCE` key，已跳過而不偽造。 */
data class NewApiSyncResult(
    val prices: List<StoredPrice>,
    val accountQuota: StoredQuota,
    val tokenQuota: StoredQuota?,
    val accountUsage: StoredProviderAccountUsage? = null,
    /**
     * CR-009 DRAFT: wave session passport created from this call's outer observed
     * caps Success (null when caps uncertified/failed or legs unevaluated).
     * Read-only carrier; never evidence, never persisted.
     */
    val sessionPassport: NewApiSessionPassport? = null,
)

/** 安全寫入：只存 refs 與狀態，不存 secret 原始值、聊天內容、raw headers。 */
class AtlasRepository(
    private val db: AtlasDatabase,
    accountUsageObserver: AccountQuotaObserver? = null,
    private val pipeline: EventPipeline? = null,
    private val pricePipeline: EventPipeline? = null,
    private val quotaPipeline: EventPipeline? = null,
) : TelemetrySink {
    val providerAccountUsage = ProviderAccountUsageStore(db)
    val newApiSnapshots = NewApiSnapshotStore(db, accountUsageObserver ?: providerAccountUsage)
    val providerPerf = ProviderPerfStore(db)
    private val healthRollup = HealthRollupStore(db)

    /**
     * 快照覆蓋的 app 層 enrichment：每次 emission 共用一次 head/fact JOIN，
     * 在 Kotlin 按列精確比對 scope。
     * Price 列缺 group／operation 維度：候選集合（同 provider／account／model／
     * endpoint 的官方 `PRICE` heads）state 一致才取值、不一致 `UNKNOWN`；
     * 絕不挑一列冒充。無候選沿用 DAO 舊值（匿名沿用 `UNKNOWN`）；
     * 不拿 `lastGood` 冒充本次成功；逐列投影失敗 fail-closed 回原列。
     */
    val connections: Flow<List<ConnectionRecord>> =
        db.connectionDao().observe().map { rows -> withBatchedSnapshotStates(rows) }
    val managementConnections: Flow<List<ConnectionRecord>> =
        db.connectionDao().observeManagement().map { rows -> withBatchedSnapshotStates(rows) }

    /** Single-statement enrichment for the whole emission (cf. CR-010). */
    private suspend fun withBatchedSnapshotStates(rows: List<ConnectionRecord>): List<ConnectionRecord> {
        if (rows.isEmpty()) return rows
        val projections = db.snapshotFactDao().priceQuotaHeadProjections(
            kinds = listOf(SnapshotKind.PRICE.name, SnapshotKind.TOKEN_QUOTA.name, SnapshotKind.ACCOUNT_QUOTA.name),
            priceKinds = listOf(SnapshotKind.PRICE.name),
        )
        // Exact-key quota index: (scopeKey, kind) -> latestState.
        val quotaStates = HashMap<Pair<String, String>, String>(projections.size)
        val priceRows = ArrayList<PriceQuotaHeadProjectionRow>()
        for (row in projections) {
            if (row.headKind == SnapshotKind.PRICE.name) priceRows += row
            else quotaStates[row.headScopeKey to row.headKind] = row.latestState
        }
        return rows.map { record ->
            runCatching {
                val priceState = resolvePriceStateBatched(record, priceRows)
                // Quota scope 的 operation 必須與寫入端一致（`OPERATION_GLOBAL="UNKNOWN"`，
                // 見 accountQuotaScope／tokenQuotaScope）；寫死字串即永遠 miss。
                val credentialId = record.credentialId.takeIf { it.isNotEmpty() }
                val tokenState = credentialId?.let { cid ->
                    val key = PricingScope(record.providerId, record.accountId, cid, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL).storageKey()
                    quotaStates[key to SnapshotKind.TOKEN_QUOTA.name]
                }
                // 匿名列 credentialId=""：只查 ACCOUNT head；具名列先 TOKEN 再 ACCOUNT。
                val accountKey = PricingScope(record.providerId, record.accountId, null, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL).storageKey()
                val accountState = quotaStates[accountKey to SnapshotKind.ACCOUNT_QUOTA.name]
                record.copy(priceState = priceState, quotaState = tokenState ?: accountState ?: record.quotaState)
            }.getOrDefault(record)
        }
    }

    /**
     * 精確 scope 價格投影（CR-004 B1 repo parity）：同 provider／account／model 的官方
     * （`credentialId=null`）`PRICE` heads 為候選；逐列解析 storageKey 精確比對，
     * 格式不合 fail-closed 跳過。Policy 與 `ModelRowMapper.matchesPriceHead` 同一
     * locked policy（兩處 KDoc 互相參照；語意同 `deriveFreePriceState` exact-null 慣例）：
     * - **Group exact**：`ConnectionRecord` 無 group 維度，呼叫端視為未知（null）；
     *   只有 null-group heads 合格（raw exact；null ≠ "" ≠ 具體值），具體 group 的
     *   head 不得污染 Connections 投影（同 `deriveFreePriceState` HIGH-2）。
     * - **Operation exact**：head.operation 對 `operationFromProtocol(record.protocol)`
     *   精確比對（persisted protocol 的 writer 真值推導；與 mapper
     *   `headDimensionMatches` 同語意）——head operation 未知 scope
     *   （null／blank／`"UNKNOWN"`）只配未知 scope 的推導值；具體值須完全相等。
     * - **Endpoint exact**：`parts[5] != record.endpointId` 維持原精確守門。
     * 無候選沿用 DAO 舊值；候選 state 全一致才取值，任一分歧即 `UNKNOWN`（絕不挑一列
     * 冒充；K3：不發明 manual/official precedence、來源衝突維持 `UNKNOWN`、provenance 不變）。
     */
    private fun resolvePriceStateBatched(record: ConnectionRecord, priceRows: List<PriceQuotaHeadProjectionRow>): String {
        // 未知 scope＝trim／uppercase 後 blank 或 "UNKNOWN"（與 mapper isUnknownScope 同定義）。
        fun unknownScopeValue(raw: String?): Boolean {
            val normalized = raw?.trim()?.uppercase() ?: return true
            return normalized.isEmpty() || normalized == "UNKNOWN"
        }
        val callerOperation = operationFromProtocol(record.protocol)
        val callerOperationUnknown = unknownScopeValue(callerOperation)
        var seen: String? = null
        var count = 0
        for (row in priceRows) {
            val parts = parseScopeKey(row.headScopeKey) ?: continue
            if (parts[0] != record.providerId) continue
            if (parts[1] != record.accountId) continue
            if (parts[2] != null) continue
            if (parts[3] != record.modelId) continue
            // B1 group（caller 未知＝null）：只命中 null-group head。
            if (parts[4] != null) continue
            // B1 operation：未知 scope 只配未知 scope；具體值對 protocol 推導值 exact。
            val headOperation = parts[6]
            val headOperationUnknown = unknownScopeValue(headOperation)
            if (headOperationUnknown != callerOperationUnknown) continue
            if (!headOperationUnknown && headOperation != callerOperation) continue
            if (parts[5] != record.endpointId) continue
            val state = row.latestState
            count++
            if (seen == null) seen = state
            else if (seen != state) return "UNKNOWN"
        }
        return if (count == 0) record.priceState else seen!!
    }

    /** 只取最新 100 筆作 UI 展示，不刪歷史。 */
    val samples: Flow<List<PerformanceSample>> =
        db.sampleDao().observeLatest().map { rows -> rows.map { it.toSample() } }

    val checkins: Flow<List<CheckinEntity>> = db.checkinDao().observe()

    /**
     * Transaction 原子插入各表；重複鍵直接 ABORT 失敗，不靜默部分完成。
     * record.id 為 availabilityId；record.offeringId 預設等於 id（v1 相容），為 offeringId；對外仍是單一 ConnectionRecord。
     */
    suspend fun addConnection(record: ConnectionRecord) {
        db.withTransaction {
            db.providerDao().insert(
                ProviderEntity(
                    providerId = record.providerId,
                    name = record.name,
                    officialHealth = record.officialHealth,
                    observedHealth = record.observedHealth,
                    checkinUrl = record.checkinUrl,
                    requiresDailyCheckin = record.requiresDailyCheckin,
                ),
            )
            db.accountDao().insert(
                AccountEntity(accountId = record.accountId, providerId = record.providerId),
            )
            db.credentialDao().insert(
                CredentialEntity(
                    credentialId = record.credentialId,
                    accountId = record.accountId,
                    label = record.credentialLabel,
                ),
            )
            db.endpointDao().insert(
                EndpointEntity(
                    endpointId = record.endpointId,
                    providerId = record.providerId,
                    baseUrl = record.baseUrl,
                    endpointUrl = record.endpointUrl,
                    protocol = "CHAT_COMPLETIONS",
                ),
            )
            db.offeringDao().insert(
                OfferingEntity(
                    id = record.offeringId,
                    providerId = record.providerId,
                    endpointId = record.endpointId,
                    providerModelId = record.modelId,
                    manual = true,
                    source = "MANUAL",
                ),
            )
            db.availabilityDao().insert(
                CredentialAvailabilityEntity(
                    id = record.id,
                    offeringId = record.offeringId,
                    accountId = record.accountId,
                    credentialId = record.credentialId,
                    priceState = record.priceState,
                    quotaState = record.quotaState,
                ),
            )
        }
    }

    suspend fun addAccount(providerId: String, accountId: String) {
        require(providerId.isNotBlank() && accountId.isNotBlank()) { "providerId/accountId must not be blank" }
        val provider = db.providerDao().getById(providerId)
            ?: throw IllegalArgumentException("Provider not found")
        check(provider.providerId == providerId) { "Provider mismatch" }
        db.accountDao().insert(AccountEntity(accountId = accountId, providerId = providerId))
    }
    suspend fun attachCredential(offeringId: String, accountId: String, credentialId: String, credentialLabel: String, availabilityId: String) {
        require(offeringId.isNotBlank() && accountId.isNotBlank() && credentialId.isNotBlank() && availabilityId.isNotBlank()) { "ids must not be blank" }
        require(credentialLabel.isNotBlank()) { "credentialLabel must not be blank" }
        db.withTransaction {
            val offering = db.offeringDao().getById(offeringId)
                ?: throw IllegalArgumentException("Offering not found")
            val account = db.accountDao().getById(accountId)
                ?: throw IllegalArgumentException("Account not found")
            require(account.providerId == offering.providerId) { "Cross-provider refs rejected" }
            val existing = db.credentialDao().getById(credentialId)
            if (existing != null) {
                require(existing.accountId == accountId) { "Cross-account credential rejected" }
            } else {
                db.credentialDao().insert(CredentialEntity(credentialId = credentialId, accountId = accountId, label = credentialLabel, enabled = true))
            }
            db.availabilityDao().insert(CredentialAvailabilityEntity(id = availabilityId, offeringId = offeringId, accountId = accountId, credentialId = credentialId, priceState = "UNKNOWN", quotaState = "UNKNOWN"))
        }
    }
    suspend fun setCredentialEnabled(credentialId: String, enabled: Boolean) {
        require(credentialId.isNotBlank()) { "credentialId must not be blank" }
        val rows = db.credentialDao().setEnabled(credentialId, enabled)
        if (rows == 0) throw IllegalArgumentException("Credential not found")
    }
    suspend fun hasCredential(credentialId: String): Boolean =
        db.credentialDao().getById(credentialId) != null

    suspend fun deleteCredentialMetadata(credentialId: String) {
        require(credentialId.isNotBlank()) { "credentialId must not be blank" }
        db.withTransaction {
            db.availabilityDao().deleteByCredential(credentialId)
            db.credentialDao().deleteById(credentialId)
        }
    }
    /** 同 id upsert：開始與完成只有兩次 write，全欄位＋簡短 Evidence 同 transaction。 */
    override suspend fun save(sample: PerformanceSample) {
        db.withTransaction {
            db.sampleDao().upsert(sample.toEntity())
            db.evidenceDao().upsert(sample.toEvidence())
        }
        // PK-11: terminal-sample rollup hook (best-effort, never masks save).
        // X-014: 同一 hook 內比對「刷新前／刷新後」observedHealth，只在真實轉換時
        // 掃描健康事件（中斷持續中不重複產 OUTAGE、不重複通知）。
        // M2 修復：CancellationException 重拋（不吞取消）；completedAt==null 時不寫
        // 裝置 wall-clock 冒充觀測時間——跳過該列（比捏造時間戳誠實）。
        if (sample.outcome != Outcome.RUNNING && sample.outcome != Outcome.CANCELLED) {
            val observedAt = sample.completedAt
            if (observedAt != null) {
                try {
                    // X-014: 轉換判定需先讀「刷新前」的 last-good observedHealth，
                    // 再以刷新後的新狀態比對；兩者皆為 UNKNOWN 時不產事件（Unknown≠0／≠中斷）。
                    val previous = db.providerDao().getById(sample.providerId)
                        ?.observedHealth
                        ?.let { name -> runCatching { HealthState.valueOf(name) }.getOrDefault(HealthState.UNKNOWN) }
                        ?: HealthState.UNKNOWN
                    val state = healthRollup.refreshObserved(sample.providerId, nowMillis = observedAt)
                    // 只在「真實轉換」時掃描：中斷持續中不重複產 OUTAGE／不重複通知。
                    if (pipeline != null && state != HealthState.UNKNOWN &&
                        previous != HealthState.UNKNOWN && previous != state
                    ) {
                        // OFFLINE 對應管線的「連續失敗達門檻」語意（單次失敗永不產 OUTAGE）；
                        // HEALTHY／DEGRADED 為恢復候選（wasOutaged 由 previous==OFFLINE 決定）。
                        // UNSTABLE 無明確中斷／恢復語意，不掃描。
                        val consecutiveFailures = when (state) {
                            HealthState.OFFLINE -> EventPipeline.MIN_CONSECUTIVE_FAILURES
                            HealthState.HEALTHY, HealthState.DEGRADED -> 0
                            else -> null
                        }
                        if (consecutiveFailures != null) {
                            pipeline.scanHealth(
                                EventPipeline.HealthObservation(
                                    base = EventPipeline.Observation(
                                        scopeKey = sample.providerId,
                                        // X-005 provenance：本地觀測，不冒充 OFFICIAL／MANUAL。
                                        source = ClassificationSources.OBSERVED,
                                        observedAt = observedAt,
                                    ),
                                    consecutiveFailures = consecutiveFailures,
                                ),
                                wasOutaged = previous == HealthState.OFFLINE,
                            )
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /** App 啟動呼叫一次：RUNNING 轉 INTERRUPTED，completedAt 與 latency 不猜。 */
    suspend fun recoverInterrupted() {
        db.withTransaction {
            val running = db.sampleDao().running()
            for (row in running) {
                save(row.toSample().copy(outcome = Outcome.INTERRUPTED))
            }
        }
    }

    /** date 為裝置本地 ISO yyyy-MM-dd，source 固定 USER。accountId="" 為 legacy，不當 wildcard；有 accountId 時驗證 provider ownership。 */
    suspend fun completeCheckin(providerId: String, date: String, completedAt: Long, accountId: String = "") {
        require(providerId.isNotBlank() && date.isNotBlank()) { "providerId/date must not be blank" }
        if (accountId.isNotEmpty()) {
            val account = db.accountDao().getById(accountId)
                ?: throw IllegalArgumentException("Account not found")
            require(account.providerId == providerId) { "Cross-provider checkin rejected" }
        }
        db.checkinDao().upsert(
            CheckinEntity(providerId = providerId, date = date, completedAt = completedAt, source = "USER", accountId = accountId),
        )
    }
    suspend fun getCheckin(providerId: String, date: String, accountId: String = ""): CheckinEntity? {
        if (accountId.isNotEmpty()) {
            val account = db.accountDao().getById(accountId)
                ?: throw IllegalArgumentException("Account not found")
            require(account.providerId == providerId) { "Cross-provider checkin rejected" }
        }
        return db.checkinDao().get(providerId, date, accountId)
    }
    suspend fun setProviderCheckinConfig(providerId: String, url: String?, requiresDailyCheckin: Boolean) {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        val provider = db.providerDao().getById(providerId)
            ?: throw IllegalArgumentException("Provider not found")
        check(provider.providerId == providerId) { "Provider mismatch" }
        val clean = url?.trim()?.takeIf { it.isNotEmpty() }
        if (requiresDailyCheckin) {
            require(clean != null) { "checkinUrl required when requiresDailyCheckin" }
        }
        val normalized = if (clean != null) {
            try {
                EndpointPolicy.validate(clean).toString()
            } catch (e: IllegalArgumentException) {
                throw e
            }
        } else {
            null
        }
        val rows = db.providerDao().updateCheckinConfig(providerId, normalized, requiresDailyCheckin)
        if (rows == 0) throw IllegalArgumentException("Provider not found")
    }

    suspend fun savePrice(snapshot: PriceSnapshot) {
        newApiSnapshots.saveManualPrice(snapshot)
    }

    suspend fun saveQuota(snapshot: QuotaSnapshot) {
        newApiSnapshots.saveManualQuota(snapshot)
    }

    /**
     * P4 手動同步：單 session wiring——`fetchCapabilities` → `fetchPricing` →
     * `buildPricingScopes` → 單 session 落庫；再 `syncAccountQuota` →
     * `syncTokenQuota`（後兩者沿用 store 單 session 方法）。
     * CR-001 exact account/key pass-through（CR001／X003·004·006／NAPI002·007／
     * NINT002·005A；PRODUCT_SPEC §6／§10／§12／§18；Contract §2／§3／§23.3）：
     * 請求 scope 經共用 [NewApiCredentialSelector.resolveSync] 解析（同包新檔，
     * 本片不自建），只用其回傳 [NewApiResolvedSyncScope] 的
     * `accountId／pat／key`——pricing scopes、account quota、usage observation
     * 一律該 resolved account；token quota 同 resolved account＋resolved key；
     * evidence／account／token／request_count 全歸 exact account，不借他帳號。
     * Helper 保證純 role＋嚴格 safe-ref＋enabled＋provider／account ownership、
     * 同帳號 valid PAT fallback、explicit key exact、缺 account 只允單帳號或
     * exact key owner、缺 key 只允同帳號唯一 valid inference fallback，歧義一律
     * fail-closed。Legacy provider-only（兩參皆 null）僅單帳號相容，多帳號在首
     * 次網路請求前拋 `IllegalArgumentException`；explicit 錯 ref 靜態 fail-closed。
     * 官方 pricing scopes 全來自官方 catalog（經 [NewApiSnapshotStore.buildPricingScopes]
     * 展開 `all`→usable、空 types→UNKNOWN），`provider_config.group`（手填分類）
     * 永不進官方 scope；官方空清單或 pricing 失敗回空 `prices`（不捏造 scope、
     * 不碰 last-good）。無 PAT / 缺 scope 基礎（無 config、無 offering、無 endpoint、
     * origin 分歧）一律在首次網路請求前拋 `IllegalArgumentException`，不燒額度。
     * 只傳 safe refs（`AccountPatRef`／`InferenceKeyRef`），原始值只在 adapter
     * HTTP 邊界經 `CredentialAccess` 取值。無 `INFERENCE` key 時跳過 token 同步
     * （`tokenQuota=null`），不偽造。Unknown 維持 null、失敗不碰 last-good。
     * 6-call HTTP 順序／failure／source／last-good 與 NINT-005A post-commit
     * observer 本片不動（共享優化留 CR-009）；`CancellationException` 照常傳播。
     *
     * @param providerId provider 主鍵，不可空白。
     * @param adapter 已注入的 New API adapter（secret 邊界在內）。
     * @param accountId 請求帳號（null 表 legacy／selector fallback；多帳號歧義即拋錯）。
     * @param credentialId 請求 inference key 的 safe ref（null 表 selector fallback；explicit 錯配即拋錯）。
     */
    suspend fun syncNewApi(
        providerId: String,
        adapter: NewApiAdapter,
        accountId: String? = null,
        credentialId: String? = null,
        /**
         * CR-009 DRAFT: existing wave passport for the legs to consume (validated
         * exact-match; mismatch = miss = fresh fetch). Null = legacy path; the
         * call still creates+returns a passport from its own outer caps Success.
         */
        passport: NewApiSessionPassport? = null,
    ): NewApiSyncResult {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        val provider = db.providerDao().getById(providerId)
            ?: throw IllegalArgumentException("Provider not found")
        check(provider.providerId == providerId) { "Provider mismatch" }
        val configEntity = db.providerConfigDao().getByProvider(providerId)
            ?: throw IllegalArgumentException("Provider config not found")
        try {
            configEntity.toManualConfig()
        } catch (_: Exception) {
            throw IllegalArgumentException("Provider config unavailable")
        }
        // CR-001 exact scope：first-PAT／provider-wide first-key 已由共用 selector 取代；
        // legacy provider-only（兩參皆 null）僅單帳號相容，多帳號／錯 ref 在首次網路前 fail-closed。
        // 變數不遮蔽參數 accountId，一律 resolvedScope／resolvedAccountId。
        val resolvedScope = NewApiCredentialSelector(db).resolveSync(providerId, accountId, credentialId)
        val resolvedAccountId = resolvedScope.accountId
        val pat = resolvedScope.pat
        val resolvedKey = resolvedScope.key
        val endpoints = db.endpointDao().byProvider(providerId)
        if (endpoints.isEmpty()) throw IllegalArgumentException("Endpoint required")
        val offerings = db.offeringDao().byProvider(providerId)
        if (offerings.isEmpty()) throw IllegalArgumentException("Offering required")
        // 先驗本地歸屬（燒網路前 fail-fast）；完整校验由 buildPricingScopes 覆核。
        require(endpoints.all { it.providerId == providerId }) { "Endpoint ownership mismatch" }
        require(offerings.all { it.providerId == providerId }) { "Offering ownership mismatch" }
        val endpointIds = endpoints.map { it.endpointId }.toSet()
        require(offerings.all { it.endpointId in endpointIds }) { "Offering endpoint mismatch" }
        val base = run {
            require(endpoints.map { EndpointPolicy.origin(EndpointPolicy.validate(it.baseUrl)) }.distinct().size == 1) {
                "Provider origin mismatch"
            }
            endpoints.first().baseUrl
        }
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(base))
        // 單 session：一次 capabilities＋一次 pricing，建 scope 與落庫共用同批結果。
        val caps = adapter.fetchCapabilities(base)
        val pricingResult = if (caps is NewApiResult.Success) {
            adapter.fetchPricing(base, pat, caps.value.version)
        } else {
            val failure = caps as NewApiResult.Failure
            failure.copy(evidence = failure.evidence.copy(source = NewApiSnapshotStore.PRICE_SOURCE))
        }
        val pricingScopes = if (pricingResult is NewApiResult.Success) {
            newApiSnapshots.buildPricingScopes(providerId, resolvedAccountId, pat, pricingResult.value, endpoints, offerings)
        } else {
            emptyList()
        }
        if (pricingScopes.size > 1000) throw IllegalArgumentException("Invalid scope count")
        // X-014 slice 2 價格 hook（best-effort，永不遮蔽同步結果）：
        // 寫入前先讀各 scope 的最新快照腿（latest 的 price 行腿，永不拿 lastGood
        // 冒充；首見 latest==null 即 fail-closed 跳過，不捏造 from），寫入後以
        // 回讀的最新腿比對，腿字串有差才掃描價格事件（含 fixed 腿）。Failure
        // 不掃描（X-006）。CancellationException 重拋（不吞取消）。
        val previousLegs = HashMap<PricingScope, Triple<String?, String?, String?>>()
        for (scope in pricingScopes.distinct()) {
            try {
                val prevLatest = newApiSnapshots.price(scope).latest ?: continue
                val prevSnapshotId = prevLatest.snapshotId ?: continue
                val prevPrice = db.priceDao().getById(prevSnapshotId)?.toDomain() ?: continue
                previousLegs[scope] = Triple(
                    prevPrice.input?.toPlainString(),
                    prevPrice.output?.toPlainString(),
                    prevPrice.fixedRequest?.toPlainString(),
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        val prices = if (pricingScopes.isEmpty()) {
            emptyList()
        } else {
            newApiSnapshots.syncPricingSession(pat, caps, pricingResult, pricingScopes)
        }
        if (pricingResult is NewApiResult.Success && pricePipeline != null && pricingScopes.isNotEmpty()) {
            val newByScope = HashMap<PricingScope, StoredPrice>()
            // syncPricingSession 回傳與 scopes 同尺寸同順序的 list（store 尾端
            // scopes.map { price(it) } 契約）；putIfAbsent 對重複 scope 保留第一個。
            for (i in pricingScopes.indices) {
                if (i < prices.size) newByScope.putIfAbsent(pricingScopes[i], prices[i])
            }
            for (scope in pricingScopes.distinct()) {
                try {
                    val prev = previousLegs[scope] ?: continue
                    val newStored = newByScope[scope] ?: continue
                    val newLatest = newStored.latest ?: continue
                    val newSnapshotId = newLatest.snapshotId ?: continue
                    val newPrice = db.priceDao().getById(newSnapshotId)?.toDomain() ?: continue
                    val toInput = newPrice.input?.toPlainString()
                    val toOutput = newPrice.output?.toPlainString()
                    val toFixed = newPrice.fixedRequest?.toPlainString()
                    if (prev.first != toInput || prev.second != toOutput || prev.third != toFixed) {
                        pricePipeline.scanPrice(
                            EventPipeline.PriceObservation(
                                base = EventPipeline.Observation(
                                    scopeKey = scope.storageKey(),
                                    source = NewApiSnapshotStore.PRICE_SOURCE,
                                    observedAt = newLatest.observedAt,
                                    evidenceId = newLatest.evidenceId,
                                ),
                                fromInput = prev.first,
                                fromOutput = prev.second,
                                toInput = toInput,
                                toOutput = toOutput,
                                fromFixed = prev.third,
                                toFixed = toFixed,
                            ),
                        )
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        // CR-009 DRAFT: wave passport = passed-in (validated at consumption) or
        // created from THIS call's outer observed caps; pricing leg still uses the
        // outer fetch (its in-leg certification check unchanged).
        val wavePassport = passport ?: NewApiSessionPassport.create(
            providerId = providerId,
            baseUrl = base,
            origin = origin,
            accountId = resolvedAccountId,
            pat = pat,
            caps = caps,
        )
        // X-014 slice 3 QUOTA_LOW part C（repo trigger hook，evaluation-layer only；
        // best-effort，永不遮蔽同步結果）：
        // 寫入前先讀兩條 quota 腿的 pre-image（lastGood 的已知 state 字串；首見
        // lastGood==null 即 fail-closed 跳過，不捏造 from），寫入後以回傳的
        // StoredQuota.lastGood 比對，字串有差且兩側皆已知（非 null／非 "UNKNOWN"）
        // 才掃描。只透傳 ladders 真實發出的 state 字串（QUOTA_AVAILABLE／
        // QUOTA_EXHAUSTED／UNLIMITED_CLAIMED／PUBLIC_POOL／
        // NO_KNOWN_CONSUMABLE_QUOTA；生產 ladder 永不發 QUOTA_LOW，見 spec §1.2；
        // 不猜任何門檻，BACKLOG X-014 test 3）。source 按腿取自 persist 腿的
        // evidence 來源常數（ACCOUNT_SOURCE／TOKEN_SOURCE，X-005 原樣攜帶）；
        // scopeKey 取 scope.storageKey()；observedAt 取新 fact 的 observedAt
        // （永不用裝置 wall-clock）；evidenceId 取新 fact。形狀與價格 hook
        // （上）＋健康 hook（save:273-321）一致：CancellationException 重拋，
        // 其餘一律吞掉。quotaPipeline 為 null（預設）即 no-op；AtlasApplication
        // 接線在後續 slice（需 QUOTA_LOW body 字串＋sink 決策 D4），本片不改任何呼叫端。
        val accountQuotaScope = NewApiSnapshotStore.accountQuotaScope(providerId, resolvedAccountId)
        val tokenQuotaScope = resolvedKey?.let {
            NewApiSnapshotStore.tokenQuotaScope(providerId, resolvedAccountId, it)
        }
        // F-01（review）：pre-reads 只在 hook 啟用時執行——quotaPipeline==null 的
        // 既有呼叫端不付兩次額外 quota() 交易，也不新增取消面。
        var prevAccountQuotaState: String? = null
        var prevTokenQuotaState: String? = null
        if (quotaPipeline != null) {
            prevAccountQuotaState = try {
                newApiSnapshots.quota(accountQuotaScope, SnapshotKind.ACCOUNT_QUOTA).lastGood?.state?.name
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (tokenQuotaScope != null) {
                prevTokenQuotaState = try {
                    newApiSnapshots.quota(tokenQuotaScope, SnapshotKind.TOKEN_QUOTA).lastGood?.state?.name
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
        val accountQuota = newApiSnapshots.syncAccountQuota(adapter, providerId, resolvedAccountId, pat,
            passport = wavePassport)
        val tokenQuota = if (resolvedKey != null) {
            newApiSnapshots.syncTokenQuota(adapter, providerId, resolvedAccountId, resolvedKey,
                passport = wavePassport)
        } else {
            null
        }
        if (quotaPipeline != null) {
            try {
                val fromState = prevAccountQuotaState
                val toState = accountQuota.lastGood?.state?.name
                val latest = accountQuota.latest
                if (fromState != null && toState != null &&
                    fromState != "UNKNOWN" && toState != "UNKNOWN" &&
                    fromState != toState && latest != null
                ) {
                    quotaPipeline.scanQuota(
                        EventPipeline.QuotaObservation(
                            base = EventPipeline.Observation(
                                scopeKey = accountQuotaScope.storageKey(),
                                source = NewApiSnapshotStore.ACCOUNT_SOURCE,
                                observedAt = latest.observedAt,
                                evidenceId = latest.evidenceId,
                            ),
                            fromState = fromState,
                            toState = toState,
                        ),
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (tokenQuotaScope != null && tokenQuota != null) {
                try {
                    val fromState = prevTokenQuotaState
                    val toState = tokenQuota.lastGood?.state?.name
                    val latest = tokenQuota.latest
                    if (fromState != null && toState != null &&
                        fromState != "UNKNOWN" && toState != "UNKNOWN" &&
                        fromState != toState && latest != null
                    ) {
                        quotaPipeline.scanQuota(
                            EventPipeline.QuotaObservation(
                                base = EventPipeline.Observation(
                                    scopeKey = tokenQuotaScope.storageKey(),
                                    source = NewApiSnapshotStore.TOKEN_SOURCE,
                                    observedAt = latest.observedAt,
                                    evidenceId = latest.evidenceId,
                                ),
                                fromState = fromState,
                                toState = toState,
                            ),
                        )
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        val accountUsage = try {
            providerAccountUsage.observation(providerId, resolvedAccountId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return NewApiSyncResult(prices = prices, accountQuota = accountQuota, tokenQuota = tokenQuota,
            accountUsage = accountUsage, sessionPassport = wavePassport)
    }

    // =====================================================================
    // P6 worker C（B1 解除條件）：Dedicated 產品流（MODEL-009 出口；追加區）。
    //
    // TRACEABILITY：P5 blocker B1（本層為解除條件）＋ MODEL-009。
    // Spec：PRODUCT_SPEC §9（禁 giant switch；capability 可獨立缺失，不因某
    // capability 不支援讓整個 Provider 失效）、§12（各家官方免費源；主資料限
    // 價格驗證；FREE_NOW 走完整 pipeline；hosted／self-hosted 分離；
    // official vs local 分離）、§4（Providers／Models 卡片同步入口）、§10
    // （七維 PricingScope 不互蓋；newerGood last-good；Unknown 明示）、§11
    // （一次 sync failure 不抹 last-good）；DECISIONS D-001／D-003。
    //
    // 契約（全部強制，呼叫 DedicatedSnapshotSync 前先行）：
    // - 本層只做 scope／ownership／ref 驗證＋adapter 分發＋結果摘要；數值真相在
    //   adapter typed 輸出，持久化語義（newerGood、七維隔離、失敗保留 last-good、
    //   Unsupported 無 snapshot 列）全委託 DedicatedSnapshotSync，不重做。
    // - 分發走 capability registry（能力介面＋來源前綴匹配），禁
    //   `when(providerId == …)` giant switch；provider 名一律由各 adapter 的
    //   dedicated 來源常數（`…/…` 前綴）推導，本檔不寫 provider 名字串。
    // - 參數校验在首次網路請求前：缺 credential／scope 即拋
    //   IllegalArgumentException，不燒額度（與 syncNewApi 同語意）。
    // - 免費判定只吃價格驗證路徑（CatalogEntry 經 syncOpenRouterPricing→
    //   PricingPolicy legs 派生）；名稱／`free` 旗標永不直判；paid catalog 永
    //   不建 offering（本層不寫 offerings 表，只寫 snapshot）。
    // - 七維透傳：credential／account／group／endpoint／operation 由參數與 DB
    //   offering 攜帶，不互蓋、不捏造（project 未知不猜 group；缺 project 的
    //   project 粒度家族走 Unsupported 降級）。
    // - secret 只經手 credentialId 字串（safe ref 檢查）；raw key／PAT 永不進
    //   本層；網路只用 adapter 既有 transport（本層不自建 http、不轉送憑證、
    //   不跟 redirect）。
    // - 錯誤訊息為靜態字串（禁 URL／server body／secret）；分類由 ViewModel 轉
    //   代碼顯示。
    // =====================================================================

    /** Dedicated 同步結果摘要（成功／失敗皆保留 last-good 語意由 store 層保證）。 */
    private val dedicatedSyncStore: DedicatedSnapshotSync by lazy { DedicatedSnapshotSync(db) }

    /** Adapter 需要的 CredentialAccess 佔位：本層只傳 ref，永不取值；誤取值即拋錯。 */
    private val neverCredentialAccess: CredentialAccess by lazy {
        CredentialAccess { _, _ -> throw IllegalStateException("Credential unavailable") }
    }

    /** 價格維度種類（capability 路由鍵，非 provider 名）。 */
    private enum class DedicatedPricingKind {
        /** 匿名 catalog 抓取＋逐 offering 價格驗證（既有 transport，不帶憑證）。 */
        OPENROUTER_CATALOG,
        /** 無 per-token 計價維度，只記模式（local，不燒網路）。 */
        HOSTED_MODE,
        /** 該家族無本層可用的價格能力（catalog 需 key 邊界／本輪未證實），降級跳過。 */
        UNSUPPORTED,
    }

    /** 配額維度種類（capability 路由鍵，非 provider 名）。 */
    private enum class DedicatedQuotaKind {
        /** 全域單池（官方表 local，不燒網路；per-key 維度需 key 邊界則降級跳過）。 */
        GLOBAL,
        /** 多欄並存（key／IP／account 不互蓋；無 key 時 key 欄降級跳過）。 */
        SPLIT,
        /** 官方 per-model 表：只持久化 DB 已有 offering 的模型，不捏造 scope。 */
        PER_MODEL,
        /** scope 官方未定義的單一全域池（local）。 */
        UNDEFINED_SCOPE_GLOBAL,
        /** 明確無 provider 限額的自有容量池（local）。 */
        INSTANCE_OWNED,
        /** project 粒度需 projectId（DB 無該欄，不捏造 group），降級跳過。 */
        UNSUPPORTED,
    }

    /** Capability 路由條目：歸屬只由家族鍵推導（adapter 來源常數 canonical，不寫 provider 名）。 */
    private data class DedicatedRoute(
        val familyKey: String,
        val capabilities: Set<DedicatedCapability>,
        val pricingKind: DedicatedPricingKind,
        val quotaKind: DedicatedQuotaKind,
    )

    private val dedicatedRoutes: List<DedicatedRoute> by lazy { buildDedicatedRoutes() }

    /** 來源字串 `prefix/rest` 的家族 canonical id（不寫 provider 名字串）。 */
    private fun canonicalDedicatedId(source: String): String = source.substringBefore("/")

    /**
     * Capability registry：每條路由的家族鍵＋能力集一律取自 adapter
     * 自述（來源常數＋supportedCapabilities），本層不 hard-code provider 名、
     * 不做 provider 名 switch。未知家族即無路由（呼叫端 fail-closed）。
     * 正規家族鍵僅 canonical（openrouter／amd／groq／gemini／nvidia-hosted／
     * nvidia-selfhosted）；nvidia-h／nvidia-s 機械短別名已刪除（碰撞風險）。
     */
    private fun buildDedicatedRoutes(): List<DedicatedRoute> {
        val openRouterAdapter = OpenRouterFreeAdapter(neverCredentialAccess)
        val amdAdapter = AmdAdapter(neverCredentialAccess)
        val groqAdapter = GroqFreeAdapter()
        val geminiAdapter = GeminiFreeAdapter()
        val hostedAdapter = NvidiaHostedAdapter(neverCredentialAccess)
        val selfHostedAdapter = NvidiaSelfHostedAdapter()
        val openRouterId = canonicalDedicatedId(OpenRouterFreeAdapter.SOURCE_MODELS)
        val amdId = canonicalDedicatedId(AmdAdapter.SOURCE_MODELS)
        val groqId = canonicalDedicatedId(GroqFreeAdapter.SOURCE_LIMITS)
        val geminiId = canonicalDedicatedId(GeminiFreeAdapter.SOURCE_QUOTA)
        val hostedCanonical = canonicalDedicatedId(NvidiaHostedAdapter.SOURCE_QUOTA)
        val selfCanonical = canonicalDedicatedId(NvidiaSelfHostedAdapter.SOURCE_QUOTA)
        return listOf(
            DedicatedRoute(
                familyKey = openRouterId,
                capabilities = openRouterAdapter.supportedCapabilities,
                pricingKind = DedicatedPricingKind.OPENROUTER_CATALOG,
                quotaKind = DedicatedQuotaKind.GLOBAL,
            ),
            DedicatedRoute(
                familyKey = amdId,
                capabilities = amdAdapter.supportedCapabilities,
                pricingKind = DedicatedPricingKind.UNSUPPORTED,
                quotaKind = DedicatedQuotaKind.SPLIT,
            ),
            DedicatedRoute(
                familyKey = groqId,
                capabilities = groqAdapter.supportedCapabilities,
                pricingKind = DedicatedPricingKind.UNSUPPORTED,
                quotaKind = DedicatedQuotaKind.PER_MODEL,
            ),
            DedicatedRoute(
                familyKey = geminiId,
                capabilities = geminiAdapter.supportedCapabilities,
                pricingKind = DedicatedPricingKind.UNSUPPORTED,
                quotaKind = DedicatedQuotaKind.UNSUPPORTED,
            ),
            DedicatedRoute(
                familyKey = hostedCanonical,
                capabilities = hostedAdapter.supportedCapabilities,
                pricingKind = DedicatedPricingKind.HOSTED_MODE,
                quotaKind = DedicatedQuotaKind.UNDEFINED_SCOPE_GLOBAL,
            ),
            DedicatedRoute(
                familyKey = selfCanonical,
                capabilities = selfHostedAdapter.supportedCapabilities,
                pricingKind = DedicatedPricingKind.UNSUPPORTED,
                quotaKind = DedicatedQuotaKind.INSTANCE_OWNED,
            ),
        )
    }

    /** 正規家族鍵集合（由 adapter 來源常數推導，不寫字面 provider 名 switch）。 */
    private fun dedicatedFamilyKeys(): Set<String> = dedicatedRoutes.map { it.familyKey }.toSet()

    /** 直接家族鍵命中（canonical ID 同步鈕快路；UUID 永不直接命中）。 */
    private fun findDedicatedRouteByFamilyKey(familyKey: String): DedicatedRoute? =
        dedicatedRoutes.firstOrNull { it.familyKey == familyKey }

    /**
     * BLOCKER1 家族路由（UUID 可真）：先直接家族鍵；再 endpoint baseUrl origin 維度
     * （官方 base origin 由各 adapter OFFICIAL 常數推導，不寫 provider 名）；
     * 再 onboarding 家族標籤（provider_config group／tags／resolvedAdapter，只讀）。
     * 禁 when(providerId)==字串分發；一律集合／映射查表。空 id 回 null（不污染）。
     */
    private suspend fun resolveDedicatedRoute(providerId: String): DedicatedRoute? {
        if (providerId.isBlank()) return null
        findDedicatedRouteByFamilyKey(providerId)?.let { return it }
        // baseUrl 維度：官方 origin→家族鍵映射（由 adapter 常數推導）。
        runCatching {
            val officialOrigins = buildOfficialOriginMap()
            val endpoints = db.endpointDao().byProvider(providerId)
            for (ep in endpoints) {
                val origin = runCatching {
                    EndpointPolicy.origin(EndpointPolicy.validate(ep.baseUrl))
                }.getOrNull() ?: continue
                officialOrigins[origin]?.let { family ->
                    findDedicatedRouteByFamilyKey(family)?.let { return it }
                }
            }
        }
        // onboarding 家族標籤（只讀 provider_config，不改其檔）。
        runCatching {
            val cfg = db.providerConfigDao().getByProvider(providerId)?.let {
                runCatching { it.toManualConfig() }.getOrNull()
            } ?: return@runCatching
            val keys = dedicatedFamilyKeys()
            cfg.group?.trim()?.takeIf { it.isNotEmpty() }?.let { g ->
                if (g in keys) {
                    findDedicatedRouteByFamilyKey(g)?.let { return it }
                }
            }
            for (tag in cfg.tags) {
                val clean = tag.trim()
                if (clean in keys) {
                    findDedicatedRouteByFamilyKey(clean)?.let { return it }
                }
            }
            // adapter 類型維度：GEMINI_NATIVE 即 gemini 家族（OPENAI_COMPATIBLE 歧義不映射）。
            if (cfg.resolvedAdapter == dev.modelatlas.core.onboarding.AdapterType.GEMINI_NATIVE) {
                val geminiKey = canonicalDedicatedId(GeminiFreeAdapter.SOURCE_QUOTA)
                findDedicatedRouteByFamilyKey(geminiKey)?.let { return it }
            }
            // websiteUrl origin 維度（手填官方站）。
            cfg.websiteUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { site ->
                val siteOrigin = runCatching {
                    EndpointPolicy.origin(EndpointPolicy.validate(site))
                }.getOrNull()
                if (siteOrigin != null) {
                    val officialOrigins = buildOfficialOriginMap()
                    officialOrigins[siteOrigin]?.let { family ->
                        findDedicatedRouteByFamilyKey(family)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** 官方 base origin→家族鍵（全由 adapter OFFICIAL 常數推導，不寫字面 host switch）。 */
    private fun buildOfficialOriginMap(): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            val origin = EndpointPolicy.origin(
                EndpointPolicy.validate(OpenRouterFreeAdapter.OFFICIAL_BASE_URL),
            )
            out[origin] = canonicalDedicatedId(OpenRouterFreeAdapter.SOURCE_MODELS)
        }
        runCatching {
            val origin = EndpointPolicy.origin(
                EndpointPolicy.validate(NvidiaHostedAdapter.OFFICIAL_BASE_URL),
            )
            out[origin] = canonicalDedicatedId(NvidiaHostedAdapter.SOURCE_QUOTA)
        }
        runCatching {
            val origin = EndpointPolicy.origin(
                EndpointPolicy.validate(AmdAdapter.OFFICIAL_SHARED_BASE_URL),
            )
            out[origin] = canonicalDedicatedId(AmdAdapter.SOURCE_MODELS)
        }
        return out
    }

    /**
     * 是否支援 Dedicated 同步：直接家族鍵快路（純本地，不碰網路／DB）。
     * 未知／空白／裸 UUID 回 false（fail-closed，UI 隱藏按鈕）。
     * UUID 家族判定走 [dedicatedSupportsSyncResolved]（DB 維度，需協程）。
     */
    fun dedicatedSupportsSync(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        val route = findDedicatedRouteByFamilyKey(providerId) ?: return false
        return route.capabilities.isNotEmpty()
    }

    /**
     * BLOCKER1 UUID 家族 gating（suspend，讀 endpoint／provider_config，只讀不改）。
     * 直接鍵命中即 true；否則走 [resolveDedicatedRoute]；例外 fail-closed false。
     * 空 id 直接 false，不寫鍵（MINOR9）。
     */
    suspend fun dedicatedSupportsSyncResolved(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        return try {
            resolveDedicatedRoute(providerId) != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun newApiSyncSetupPresent(providerId: String): Boolean {
        // PK-12（H3 修復）：只 gate endpoints（連線可達性）；offerings 由 sync 本身補齊
        //（舊 gate 令首次使用的 PAT provider CTA 永遠不出現——把缺列誤當能力判定）。
        if (providerId.isBlank()) return false
        return try {
            db.endpointDao().byProvider(providerId).isNotEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Safe credential ref 檢查（與 store 層同規則；靜態訊息，不洩漏 ref 內容）。 */
    private fun requireSafeDedicatedRef(ref: String) {
        require(ref.isNotBlank()) { "Credential ref must not be blank" }
        require(ref.length <= 256) { "Credential ref too long" }
        require(ref.none { it.code < 0x20 || it.code == 0x7F }) { "Credential ref must not contain control characters" }
        require(!ref.startsWith("sk-")) { "Credential ref must not carry secret" }
        require(ref.none { it.isWhitespace() }) { "Credential ref must not contain whitespace" }
    }

    /** 解析 account（BLOCKER7：參數透傳優先；單帳戶才允許 fallback，多帳戶缺參即拋錯不猜）。 */
    private suspend fun resolveDedicatedAccount(providerId: String, accountId: String?): Pair<AccountEntity, Boolean> {
        if (accountId != null) {
            require(accountId.isNotBlank()) { "Account required" }
            val account = db.accountDao().getById(accountId)
                ?: throw IllegalArgumentException("Account not found")
            require(account.providerId == providerId) { "Cross-provider refs rejected" }
            return account to false
        }
        val all = db.accountDao().byProvider(providerId).sortedBy { it.accountId }
        if (all.isEmpty()) throw IllegalArgumentException("Account required")
        // 多帳戶不猜首帳號（BLOCKER7 寫錯 scope 根因）；單一才 fallback 且呼叫端明示。
        require(all.size == 1) { "Account required" }
        return all.first() to true
    }

    private suspend fun resolveDedicatedEndpoints(providerId: String) =
        db.endpointDao().byProvider(providerId).also {
            if (it.isEmpty()) throw IllegalArgumentException("Endpoint required")
            require(it.all { row -> row.providerId == providerId }) { "Endpoint ownership mismatch" }
        }

    private suspend fun resolveDedicatedOfferings(providerId: String) =
        db.offeringDao().byProvider(providerId).also {
            if (it.isEmpty()) throw IllegalArgumentException("Offering required")
            require(it.all { row -> row.providerId == providerId }) { "Offering ownership mismatch" }
            val endpointIds = db.endpointDao().byProvider(providerId).map { row -> row.endpointId }.toSet()
            require(it.all { row -> row.endpointId in endpointIds }) { "Offering endpoint mismatch" }
        }

    /**
     * BLOCKER2 角色校验（與 syncNewApi:294-321 同語意）：quota 的 credential 必須在
     * provider_config 具單一 INFERENCE 角色；ACCOUNT（PAT）／重疊 ref／缺 config 一律拒絕，
     * PAT 永不進 inference quota 路徑（守 NAPI-002）。靜態訊息，不洩漏 ref。
     */
    private suspend fun requireDedicatedInferenceRole(providerId: String, ref: String) {
        requireSafeDedicatedRef(ref)
        val configEntity = db.providerConfigDao().getByProvider(providerId)
            ?: throw IllegalArgumentException("Credential role unavailable")
        val config = try {
            configEntity.toManualConfig()
        } catch (_: Exception) {
            throw IllegalArgumentException("Credential role unavailable")
        }
        val roles = config.credentials
            .filter { it.safeCredentialId.trim() == ref }
            .map { it.role }
            .toSet()
        require(roles == setOf(CredentialRole.INFERENCE)) {
            "Credential role unavailable"
        }
    }

    /** MAJOR2 operation 推導：由 endpoint protocol 攜帶（DB 真值），禁硬編字串分發。 */
    private fun operationFromProtocol(protocol: String?): String {
        val clean = protocol?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 64 && it.matches(Regex("[A-Za-z0-9_]+"))
        } ?: return "UNKNOWN"
        return clean
    }

    /** group 透傳校验（BLOCKER7；未知不猜，格式錯即拒絕）。 */
    private fun requireValidDedicatedGroup(group: String?): String? {
        if (group == null) return null
        require(group.isNotBlank()) { "Invalid group" }
        require(group.length <= 256) { "Group too long" }
        require(!group.contains("::")) { "Scope group must not contain concatenation" }
        require(group.none { it.code < 0x20 || it.code == 0x7F }) { "Group must not contain control characters" }
        return group
    }

    /**
     * Dedicated 價格同步：參數校验在首次網路請求前（缺 credential／scope 即拋錯
     * 不燒額度）；依 capability 路由走 adapter；單 offering 失敗只影響自己
     * （不同 capability 缺失不讓整個 Provider 失效）；持久化語義委託 store 層
     * （失敗保留 last-good）。回傳摘要（含 resolvedAccountId／fallback 註記／
     * latestObservedAt；lastSyncedAt 僅為裝置觸發時間，不冒充觀測時間）。
     */
    suspend fun syncDedicatedPricing(
        providerId: String,
        accountId: String? = null,
        credentialId: String? = null,
        group: String? = null,
    ): DedicatedSyncSummary {
        require(providerId.isNotBlank()) { "Provider required" }
        // 價格 scope 永不帶 credential（store 層同約束）：先行拒絕，不燒網路。
        require(credentialId == null) { "Pricing scope must not carry credential" }
        val validGroup = requireValidDedicatedGroup(group)
        val provider = db.providerDao().getById(providerId)
            ?: throw IllegalArgumentException("Provider not found")
        check(provider.providerId == providerId) { "Provider mismatch" }
        val route = resolveDedicatedRoute(providerId)
            ?: throw IllegalArgumentException("Unsupported provider")
        val (account, fallbackUsed) = resolveDedicatedAccount(providerId, accountId)
        val detail = when (route.pricingKind) {
            DedicatedPricingKind.OPENROUTER_CATALOG ->
                syncOpenRouterCatalogPricing(providerId, account.accountId, validGroup)
            DedicatedPricingKind.HOSTED_MODE ->
                syncHostedModePricing(providerId, account.accountId, validGroup)
            DedicatedPricingKind.UNSUPPORTED -> DedicatedSyncDetail(0, true, null)
        }
        return DedicatedSyncSummary(
            providerId = providerId,
            pricingSynced = detail.count,
            quotaSynced = 0,
            pricingUnsupported = detail.unsupported,
            quotaUnsupported = false,
            lastSyncedAtMillis = System.currentTimeMillis(),
            resolvedAccountId = account.accountId,
            accountFallbackUsed = fallbackUsed,
            latestObservedAtMillis = detail.latestObservedAt,
        )
    }

    /**
     * Dedicated 配額池同步：同上（首次網路請求前校验；capability 獨立降級；
     * last-good 委託 store 層）。credentialId 只收單一 INFERENCE role safe ref
     * （BLOCKER2：PAT／重疊／缺 config 一律拒絕；DB 存在、歸屬正確、enabled）；
     * per-key 維度無 key 時降級跳過，不偽造。
     */
    suspend fun syncDedicatedQuotaPools(
        providerId: String,
        accountId: String? = null,
        credentialId: String? = null,
        group: String? = null,
    ): DedicatedSyncSummary {
        require(providerId.isNotBlank()) { "Provider required" }
        credentialId?.let { requireSafeDedicatedRef(it) }
        val validGroup = requireValidDedicatedGroup(group)
        val provider = db.providerDao().getById(providerId)
            ?: throw IllegalArgumentException("Provider not found")
        check(provider.providerId == providerId) { "Provider mismatch" }
        val route = resolveDedicatedRoute(providerId)
            ?: throw IllegalArgumentException("Unsupported provider")
        var resolvedAccountId = accountId
        var resolvedRef: String? = null
        if (credentialId != null) {
            // BLOCKER2：先驗角色（PAT 不進 inference quota 路徑），再驗歸屬。
            requireDedicatedInferenceRole(providerId, credentialId)
            val credential = db.credentialDao().getById(credentialId)
                ?: throw IllegalArgumentException("Credential ownership mismatch")
            require(credential.enabled) { "Credential ownership mismatch" }
            val owner = db.accountDao().getById(credential.accountId)
                ?: throw IllegalArgumentException("Account not found")
            require(owner.providerId == providerId) { "Cross-provider refs rejected" }
            if (resolvedAccountId != null) {
                require(resolvedAccountId == credential.accountId) { "Cross-account credential rejected" }
            }
            resolvedAccountId = credential.accountId
            resolvedRef = credentialId
        }
        val (account, fallbackUsed) = resolveDedicatedAccount(providerId, resolvedAccountId)
        if (resolvedRef != null) {
            val credential = db.credentialDao().getById(resolvedRef)
                ?: throw IllegalArgumentException("Credential ownership mismatch")
            require(credential.accountId == account.accountId) { "Cross-account credential rejected" }
        }
        val detail = when (route.quotaKind) {
            DedicatedQuotaKind.GLOBAL -> syncGlobalQuota(providerId, account.accountId, resolvedRef, validGroup)
            DedicatedQuotaKind.SPLIT -> syncSplitQuota(providerId, account.accountId, resolvedRef, validGroup)
            DedicatedQuotaKind.PER_MODEL -> syncPerModelQuota(providerId, account.accountId, validGroup)
            DedicatedQuotaKind.UNDEFINED_SCOPE_GLOBAL -> syncUndefinedScopeQuota(providerId, account.accountId, validGroup)
            DedicatedQuotaKind.INSTANCE_OWNED -> syncInstanceOwnedQuota(providerId, account.accountId, validGroup)
            DedicatedQuotaKind.UNSUPPORTED -> DedicatedSyncDetail(0, true, null)
        }
        return DedicatedSyncSummary(
            providerId = providerId,
            pricingSynced = 0,
            quotaSynced = detail.count,
            pricingUnsupported = false,
            quotaUnsupported = detail.unsupported,
            lastSyncedAtMillis = System.currentTimeMillis(),
            resolvedAccountId = account.accountId,
            accountFallbackUsed = fallbackUsed,
            latestObservedAtMillis = detail.latestObservedAt,
        )
    }

    /** 內部同步明細（count 僅計成功觀測；Failure 永不計入，由呼叫端拋錯）。 */
    private data class DedicatedSyncDetail(
        val count: Int,
        val unsupported: Boolean,
        val latestObservedAt: Long?,
    )

    /**
     * 匿名 catalog 抓取＋逐 offering 價格驗證（唯一網路路徑；呼叫前已驗 scope，
     * 故此次為首次網路請求）。MAJOR1：首次網路前 EndpointPolicy.validate＋單 origin
     * 斷言（與 syncNewApi:331 同語意）。免費判定只吃 CatalogEntry 雙零 legs（經 store→
     * PricingPolicy 派生）；名稱／旗標不參與；catalog 缺該模型即跳過不捏造；
     * MAJOR5：抓取失敗逐 scope 落 Failure 保 last-good 後拋錯（不計 done）。
     * MAJOR2：operation 由 endpoint protocol 推導，group 由參數透傳。
     */
    private suspend fun syncOpenRouterCatalogPricing(
        providerId: String,
        accountId: String,
        group: String?,
    ): DedicatedSyncDetail {
        // 先驗 scope 基礎（缺 offering／endpoint 即拋錯，不燒網路）；網路抓取只做一次。
        val offerings = resolveDedicatedOfferings(providerId)
        val endpoints = resolveDedicatedEndpoints(providerId)
        // MAJOR1：HTTPS＋單 origin 綁定（首次網路前，靜態訊息不洩漏 URL）。
        val validatedBase: String = run {
            val validated = endpoints.map { EndpointPolicy.validate(it.baseUrl) }
            require(validated.map { EndpointPolicy.origin(it) }.distinct().size == 1) {
                "Provider origin mismatch"
            }
            endpoints.first().baseUrl
        }
        val byEndpoint = endpoints.associateBy { it.endpointId }
        val adapter = OpenRouterFreeAdapter(neverCredentialAccess)
        val catalog = adapter.discoverModels(validatedBase)
        when (catalog) {
            is DedicatedResult.Unsupported -> return DedicatedSyncDetail(0, true, null)
            is DedicatedResult.Failure -> {
                for (offering in offerings) {
                    val endpoint = byEndpoint[offering.endpointId]
                    val operation = operationFromProtocol(endpoint?.protocol)
                    val scope = PricingScope(providerId, accountId, null, offering.providerModelId, group, offering.endpointId, operation)
                    try {
                        dedicatedSyncStore.syncOpenRouterPricing(
                            scope,
                            DedicatedResult.Failure(catalog.status, catalog.evidence),
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                // MAJOR5：Failure 只保 last-good，不計 done，直接拋錯回 UI。
                throw IllegalStateException("Dedicated sync failed")
            }
            is DedicatedResult.Success -> {
                val byId = catalog.value.associateBy { it.id }
                val evidence = catalog.evidence
                var count = 0
                var firstError: Throwable? = null
                for (offering in offerings) {
                    // 免費判定輸入只吃價格驗證：無 catalog 條目即跳過（不以名稱／旗標補位）。
                    val entry = byId[offering.providerModelId] ?: continue
                    val endpoint = byEndpoint[offering.endpointId]
                    val operation = operationFromProtocol(endpoint?.protocol)
                    val scope = PricingScope(providerId, accountId, null, offering.providerModelId, group, offering.endpointId, operation)
                    try {
                        dedicatedSyncStore.syncOpenRouterPricing(scope, DedicatedResult.Success(evidence, entry))
                        count++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (firstError == null) firstError = e
                    }
                }
                if (count == 0 && firstError != null) throw firstError
                return DedicatedSyncDetail(count, false, evidence.observedAt.takeIf { count > 0 })
            }
        }
    }

    /** Hosted 計價模式（local：無 per-token 維度，一律 UNKNOWN，不以 0 冒充）。 */
    private suspend fun syncHostedModePricing(
        providerId: String,
        accountId: String,
        group: String?,
    ): DedicatedSyncDetail {
        val offerings = resolveDedicatedOfferings(providerId)
        val endpoints = resolveDedicatedEndpoints(providerId)
        val byEndpoint = endpoints.associateBy { it.endpointId }
        val adapter = NvidiaHostedAdapter(neverCredentialAccess)
        val mode = adapter.fetchPricing()
        if (mode is DedicatedResult.Unsupported) return DedicatedSyncDetail(0, true, null)
        if (mode is DedicatedResult.Failure) {
            for (offering in offerings) {
                val operation = operationFromProtocol(byEndpoint[offering.endpointId]?.protocol)
                val scope = PricingScope(providerId, accountId, null, offering.providerModelId, group, offering.endpointId, operation)
                try {
                    dedicatedSyncStore.syncPricingMode(scope, DedicatedResult.Failure(mode.status, mode.evidence))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            throw IllegalStateException("Dedicated sync failed")
        }
        val success = mode as DedicatedResult.Success
        var count = 0
        var firstError: Throwable? = null
        for (offering in offerings) {
            val operation = operationFromProtocol(byEndpoint[offering.endpointId]?.protocol)
            val scope = PricingScope(providerId, accountId, null, offering.providerModelId, group, offering.endpointId, operation)
            try {
                dedicatedSyncStore.syncPricingMode(scope, mode)
                count++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (count == 0 && firstError != null) throw firstError
        return DedicatedSyncDetail(count, false, success.evidence.observedAt.takeIf { count > 0 })
    }

    /**
     * 全域單池（官方表 local）。MAJOR7：傳入 credential 即 per-key 維度需求，
     * 本層無 key 網路邊界則明示 unsupported（不謊稱 synced）；呼叫端如需 key 維度
     * 走 syncOpenRouterKeyQuota（需 CredentialAccess 網路邊界，本版未接線故降級）。
     */
    private suspend fun syncGlobalQuota(
        providerId: String,
        accountId: String,
        credentialRef: String?,
        group: String?,
    ): DedicatedSyncDetail {
        if (credentialRef != null) {
            // per-key quota 永不同步為 account 冒充：明示不支援。
            return DedicatedSyncDetail(0, true, null)
        }
        // MAJOR8 互斥註記（只讀 NewApiSnapshotStore 常數，不改其本體／舊 kind 語意）：
        // dedicated 與 NewApi 共用 PRICE／ACCOUNT_QUOTA／TOKEN_QUOTA 三 kind
        //（NewApiSnapshotStore.PRICE_SOURCE="newapi/api/pricing"、
        // ACCOUNT_SOURCE="newapi/api/user/self"、TOKEN_SOURCE="newapi/api/usage/token"；
        // DedicatedSnapshotSync.DEDICATED_SOURCE_PREFIXES 為 openrouter/ 等六前綴）。
        // 本版禁改 store 本體與 SnapshotKind，故 kind 不加前綴；隔離由七維 scopeKey＋
        // evidence.source 前綴＋provenance（OFFICIAL／OBSERVED 等，不互冒）承載；
        // 同 scopeKey＋kind 覆寫風險列為未決（需 worker A 在 store 層加命名空間，見回報）。
        val adapter = OpenRouterFreeAdapter(neverCredentialAccess)
        val pools = adapter.quotaPools(null)
        val observedAt = System.currentTimeMillis()
        val evidence = DedicatedEvidence(
            OpenRouterFreeAdapter.SOURCE_KEY,
            observedAt,
            DedicatedProvenance.OFFICIAL_DOC,
        )
        val scope = DedicatedSnapshotSync.accountQuotaScope(providerId, accountId, group = group)
        dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Success(evidence, pools.single()))
        return DedicatedSyncDetail(1, false, observedAt)
    }

    /** 多欄並存（key／IP／account 不互蓋；無 key 時 key 欄跳過；單欄失敗不拖垮他欄）。 */
    private suspend fun syncSplitQuota(
        providerId: String,
        accountId: String,
        credentialRef: String?,
        group: String?,
    ): DedicatedSyncDetail {
        val adapter = AmdAdapter(neverCredentialAccess)
        val pools = adapter.quotaPools()
        val observedAt = System.currentTimeMillis()
        val evidence = DedicatedEvidence(
            AmdAdapter.SOURCE_USAGE,
            observedAt,
            DedicatedProvenance.OFFICIAL_DOC,
        )
        var count = 0
        var firstError: Throwable? = null
        for (pool in pools) {
            try {
                if (pool.scopeKind == QuotaScopeKind.PER_KEY) {
                    // per-key 池無 key 即降級跳過（不偽造、不記失敗）。
                    val ref = credentialRef ?: continue
                    val scope = DedicatedSnapshotSync.tokenQuotaScope(providerId, accountId, ref, group = group)
                    dedicatedSyncStore.syncTokenQuota(scope, ref, DedicatedResult.Success(evidence, pool))
                } else {
                    val scope = DedicatedSnapshotSync.accountQuotaScope(providerId, accountId, group = group)
                    dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Success(evidence, pool))
                }
                count++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (count == 0 && firstError != null) throw firstError
        return DedicatedSyncDetail(count, false, observedAt.takeIf { count > 0 })
    }

    /** per-model 表：只持久化 DB 已有 offering 的模型（表外跳過，不捏造 scope）。 */
    private suspend fun syncPerModelQuota(
        providerId: String,
        accountId: String,
        group: String?,
    ): DedicatedSyncDetail {
        val offerings = resolveDedicatedOfferings(providerId)
        val adapter = GroqFreeAdapter()
        val result = adapter.fetchQuotaPools()
        if (result is DedicatedResult.Unsupported) return DedicatedSyncDetail(0, true, null)
        if (result is DedicatedResult.Failure) {
            for (offering in offerings) {
                val scope = DedicatedSnapshotSync.accountQuotaScope(
                    providerId, accountId, group = group, modelId = offering.providerModelId, endpointId = offering.endpointId,
                )
                try {
                    dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Failure(result.status, result.evidence))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            throw IllegalStateException("Dedicated sync failed")
        }
        val success = result as DedicatedResult.Success
        val byModel = offerings.associateBy { it.providerModelId }
        var count = 0
        var firstError: Throwable? = null
        for (pool in success.value) {
            val model = pool.modelId ?: continue
            // 官方表模型不在本地 offerings 即跳過（不建 offering、不把 paid 塞入 Models）。
            val offering = byModel[model] ?: continue
            val scope = DedicatedSnapshotSync.accountQuotaScope(
                providerId, accountId, group = group, modelId = model, endpointId = offering.endpointId,
            )
            try {
                dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Success(success.evidence, pool))
                count++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (count == 0 && firstError != null) throw firstError
        return DedicatedSyncDetail(count, false, success.evidence.observedAt.takeIf { count > 0 })
    }

    /** scope 官方未定義的單一全域池（local；具名 scope 由 store 層拒絕，本層只送全域）。 */
    private suspend fun syncUndefinedScopeQuota(
        providerId: String,
        accountId: String,
        group: String?,
    ): DedicatedSyncDetail {
        // 全域 UNKNOWN pool 禁帶具名 group（store B6）：具名 group 即 mis-scope， fail-closed。
        if (group != null) throw IllegalArgumentException("UNKNOWN pool requires global scope")
        val adapter = NvidiaHostedAdapter(neverCredentialAccess)
        val result = adapter.fetchQuotaPools()
        if (result is DedicatedResult.Unsupported) return DedicatedSyncDetail(0, true, null)
        if (result is DedicatedResult.Failure) {
            val scope = DedicatedSnapshotSync.accountQuotaScope(providerId, accountId)
            try {
                dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Failure(result.status, result.evidence))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            throw IllegalStateException("Dedicated sync failed")
        }
        val success = result as DedicatedResult.Success
        val scope = DedicatedSnapshotSync.accountQuotaScope(providerId, accountId)
        dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Success(success.evidence, success.value.single()))
        return DedicatedSyncDetail(1, false, success.evidence.observedAt)
    }

    /** 自有容量池（明確無 provider 限額；local；不以 0／unlimited 冒充）。 */
    private suspend fun syncInstanceOwnedQuota(
        providerId: String,
        accountId: String,
        group: String?,
    ): DedicatedSyncDetail {
        if (group != null) throw IllegalArgumentException("Global pool requires global scope")
        val adapter = NvidiaSelfHostedAdapter()
        val result = adapter.fetchQuotaPools()
        if (result is DedicatedResult.Unsupported) return DedicatedSyncDetail(0, true, null)
        if (result is DedicatedResult.Failure) {
            val scope = DedicatedSnapshotSync.accountQuotaScope(providerId, accountId)
            try {
                dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Failure(result.status, result.evidence))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            throw IllegalStateException("Dedicated sync failed")
        }
        val success = result as DedicatedResult.Success
        val scope = DedicatedSnapshotSync.accountQuotaScope(providerId, accountId)
        dedicatedSyncStore.syncAccountQuota(scope, DedicatedResult.Success(success.evidence, success.value.single()))
        return DedicatedSyncDetail(1, false, success.evidence.observedAt)
    }

/**
 * P6 session N（MODEL-006/007 接線；追加區，不改既有函式）。
 *
 * TRACEABILITY: MODEL-006（manual hidden offering）/ MODEL-007（selected-key usable projection）。
 * Spec: PRODUCT_SPEC §8（manual offering 需實測 evidence 才 promote，保存 Evidence 與來源）、
 * §6（selected-key 語意：同 Provider 不同 Credential/Group 互不污染）、
 * §10（MANUAL/USER 分槽：manual 永不寫 official 槽；LOW 不升 official last-good）。
 *
 * 契約：
 * - 本區只追加，不改既有函式/DAO/Entity/ViewModel/Screen/Domain。
 * - [addManualOffering] 只寫 offerings 表（availability/price/quota 由各自 store 分槽處理，
 *   不在此捏造）；失敗不刪列、不標 REMOVED。
 * - 空 verification evidence 調 promote 永不拋崩：[promoteManualOffering] catch
 *   IllegalArgumentException 轉 [ManualPromoteResult.Rejected]（照 ManualOfferingStore
 *   promoteOnVerified D-fix KDoc 指示）。
 * - [usableRowsFor] 純讀 DB + 調 UsableProjection 純函式，不寫 DB。
 * - 只經手 credentialId 字串（safe ref）；raw key/PAT 永不進本區。
 */

/**
 * MODEL-006 接線：manual hidden offering 落庫（冪等）。
 *
 * 冪等鍵為 (providerId, endpointId, providerModelId) 三元組：先查既有 manual 列即回傳既有 id，
 * 不新建、不覆寫（同 provider 下不同 endpoint 的同名 model 是不同 offering）。
 * 冪等僅對 manual 家族生效（existing.manual 且 source MANUAL/USER）：同 id/三元組若被
 * official 列佔住，不回傳 official id（同 id 拋 IllegalStateException，不互相冒充；
 * 三元組被 official 佔住則落到 insert 另建 manual 列，同 id 則 PK 失敗上浮）。
 * 已驗證重送會真寫回：既有 manual 列命中三元組/id 且草稿帶非空白 verificationEvidenceId、
 * hidden 或 verification 與既有不同時，經 SQLite UPDATE 寫回 hidden/verificationEvidenceId，
 * 不再被冪等早退吞掉（§8 驗證後可見）。
 * 未驗證（verificationEvidenceId 空白）強制 hidden=true 落庫（§8 未驗證不可見，
 * fail-closed）；已帶非空白驗證 evidence 才保留呼叫端可見性；未驗證重送不清除既有驗證。
 * source 僅接受 MANUAL/USER（manual 與 official 分槽，不互相冒充）。
 *
 * @return 落庫（或既有）offering id。
 * @throws IllegalArgumentException 三元組空白、source 非 manual 家族、evidenceId 空白、
 * observedAt < 0，或 provider/endpoint 父列不存在（FK RESTRICT 由 Room 拋錯上浮）。
 * @throws IllegalStateException 同 id 既有列為非 manual 家族（official 冒充防護）。
 */
suspend fun addManualOffering(draft: OfferingDraft): String {
    require(draft.providerId.isNotBlank()) { "providerId must not be blank" }
    require(draft.endpointId.isNotBlank()) { "endpointId must not be blank" }
    require(draft.providerModelId.isNotBlank()) { "providerModelId must not be blank" }
    require(draft.id.isNotBlank()) { "OfferingDraft.id must not be blank" }
    val normalizedSource = draft.source.trim().uppercase()
    require(
        normalizedSource == MANUAL_OFFERING_SOURCE_MANUAL ||
            normalizedSource == MANUAL_OFFERING_SOURCE_USER,
    ) { "Manual offering source must be MANUAL or USER" }
    require(draft.evidenceId.isNotBlank()) { "Manual offering requires evidenceId" }
    require(draft.observedAt >= 0) { "Invalid observation time" }
    val offeringDao = db.offeringDao()
    offeringDao.byProvider(draft.providerId).firstOrNull {
        it.providerId == draft.providerId &&
            it.endpointId == draft.endpointId &&
            it.providerModelId == draft.providerModelId
    }?.let { existing ->
        val existingSource = existing.source.trim().uppercase()
        val isManualFamily = existing.manual &&
            (existingSource == MANUAL_OFFERING_SOURCE_MANUAL ||
                existingSource == MANUAL_OFFERING_SOURCE_USER)
        if (isManualFamily) {
            val desiredVerification = draft.verificationEvidenceId?.takeIf { it.isNotBlank() }
            if (desiredVerification != null &&
                (existing.hidden != draft.hidden || existing.verificationEvidenceId != desiredVerification)
            ) {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE offerings SET hidden = ?, verificationEvidenceId = ? WHERE id = ?",
                    arrayOf<Any?>(if (draft.hidden) 1 else 0, desiredVerification, existing.id),
                )
            }
            return existing.id
        }
        if (existing.id == draft.id) {
            throw IllegalStateException("offering id ${draft.id} occupied by non-manual source")
        }
    }
    offeringDao.getById(draft.id)?.let { existing ->
        val existingSource = existing.source.trim().uppercase()
        val isManualFamily = existing.manual &&
            (existingSource == MANUAL_OFFERING_SOURCE_MANUAL ||
                existingSource == MANUAL_OFFERING_SOURCE_USER)
        if (!isManualFamily) {
            throw IllegalStateException("offering id ${draft.id} occupied by non-manual source")
        }
        val desiredVerification = draft.verificationEvidenceId?.takeIf { it.isNotBlank() }
        if (desiredVerification != null &&
            (existing.hidden != draft.hidden || existing.verificationEvidenceId != desiredVerification)
        ) {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE offerings SET hidden = ?, verificationEvidenceId = ? WHERE id = ?",
                arrayOf<Any?>(if (draft.hidden) 1 else 0, desiredVerification, existing.id),
            )
        }
        return existing.id
    }
    val hidden = if (draft.verificationEvidenceId.isNullOrBlank()) true else draft.hidden
    val entity = OfferingEntity(
        id = draft.id,
        providerId = draft.providerId,
        endpointId = draft.endpointId,
        providerModelId = draft.providerModelId,
        manual = true,
        source = normalizedSource,
        hidden = hidden,
        verificationEvidenceId = draft.verificationEvidenceId?.takeIf { it.isNotBlank() },
    )
    try {
        offeringDao.insert(entity)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        offeringDao.byProvider(draft.providerId).firstOrNull {
            it.providerId == draft.providerId &&
                it.endpointId == draft.endpointId &&
                it.providerModelId == draft.providerModelId
        }?.let { existing ->
            val existingSource = existing.source.trim().uppercase()
            val isManualFamily = existing.manual &&
                (existingSource == MANUAL_OFFERING_SOURCE_MANUAL ||
                    existingSource == MANUAL_OFFERING_SOURCE_USER)
            if (isManualFamily) {
                val desiredVerification = draft.verificationEvidenceId?.takeIf { it.isNotBlank() }
                if (desiredVerification != null &&
                    (existing.hidden != draft.hidden || existing.verificationEvidenceId != desiredVerification)
                ) {
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE offerings SET hidden = ?, verificationEvidenceId = ? WHERE id = ?",
                        arrayOf<Any?>(if (draft.hidden) 1 else 0, desiredVerification, existing.id),
                    )
                }
                return existing.id
            }
        }
        offeringDao.getById(draft.id)?.let { existing ->
            val existingSource = existing.source.trim().uppercase()
            if (existing.manual &&
                (existingSource == MANUAL_OFFERING_SOURCE_MANUAL ||
                    existingSource == MANUAL_OFFERING_SOURCE_USER)
            ) {
                return existing.id
            }
        }
        throw e
    }
    return entity.id
}

/**
 * MODEL-007 接線：selected-key 可用投影（唯讀，不寫 DB）。
 *
 * 未驗證 hidden offering 永不進入投影（§8 未驗證不可見）；無 availability 的可見
 * offering 照 UsableProjection 語意標 UNKNOWN，不隱藏。
 * availability 的 endpoint/operation 維度由其 FK 父 offering（及父 endpoint protocol）
 * 繼承（DB 真值，非猜測）；group 維度取 availability.group 原值精確比對。
 * account/credential 缺列 fail-closed 回空表（不污染、不拋崩）；id 空白才拋錯。
 */
suspend fun usableRowsFor(accountId: String, credentialId: String, group: String?): List<UsableRow> {
    require(accountId.isNotBlank()) { "accountId must not be blank" }
    require(credentialId.isNotBlank()) { "credentialId must not be blank" }
    val account = db.accountDao().getById(accountId) ?: return emptyList()
    db.credentialDao().getById(credentialId) ?: return emptyList()
    val offerings = db.offeringDao().byProvider(account.providerId).filter { !it.hidden }
    if (offerings.isEmpty()) return emptyList()
    val endpointsById = db.endpointDao().byProvider(account.providerId).associateBy { it.endpointId }
    val availabilities = db.availabilityDao().byCredential(credentialId)
        .filter { it.accountId == accountId }
    val availabilityByOffering = availabilities.groupBy { it.offeringId }
    val offeringInputs = offerings.map { offering ->
        val operation = endpointsById[offering.endpointId]?.protocol?.trim()?.takeIf { it.isNotEmpty() }
            ?: "CHAT_COMPLETIONS"
        offering.toUsableInput(operation = operation)
    }
    val availabilityInputs = offeringInputs.flatMap { offeringInput ->
        (availabilityByOffering[offeringInput.id] ?: emptyList()).map { availability ->
            availability.toUsableInput(
                group = availability.group,
                endpointId = offeringInput.endpointId,
                operation = offeringInput.operation,
            )
        }
    }
    val selected = UsableProjection.selectedKey(accountId, credentialId, group)
    return UsableProjection.usableOfferings(
        offerings = offeringInputs,
        availabilities = availabilityInputs,
        selected = selected,
    )
}

/**
 * P7-B2 LMSpeed enrichment 產品接線（追加；既有 dedicated／NewApi 語意不動）。
 *
 * TRACEABILITY LMS-001／LMS-002／LMS-003／LMS-005；PRODUCT_SPEC §13；RESEARCH_POLICY §7。
 * 全鏈：`fetcher.fetchModels()` → [LmspeedImporter]（正規化＋fail-closed略過非法筆）
 * → [LmspeedSinkAdapter.forStore]（只寫 `external_observations`，冪等 upsert）
 * → 真 indices（[CanonicalStore.lmspeedMappingIndices] DB 真值；呼叫端顯式傳入
 *   非空即尊重——測試顯式斷言用，產品 UI 傳空即建真 indices，不傳空集合判定）
 * → [mapExternalToCanonicalDecision]＋[ExternalStore.saveDecision]
 * （寫 `external_mappings`，UNKNOWN null 亦落槽，不猜）。
 * - 只寫外部槽：絕不碰 snapshot_facts／snapshot_heads／samples／telemetry／
 *   price_snapshots／quota_snapshots／evidence（讀寫分離，違者 CI 擋）。
 * - 失敗保舊：fetch 或 sink 失敗即拋 `IllegalStateException`（不刪舊列，
 *   舊觀測／舊映射原地保留）；單筆映射寫回失敗不拖垮整批（觀測已落庫為主結果，
 *   該筆跳過繼續）；`CancellationException` 照常傳播。
 * - 無 key：只收 [LmspeedFetcher] 介面（呼叫端注入；本層不解析 vault、不存 secret、
 *   錯誤訊息為靜態字串，不含 key／URL／body）。
 * - 冪等：同 source＋externalModelId＋fetchedAt 重送即覆寫，不增列。
 */
suspend fun syncLmspeedEnrichment(
    fetcher: LmspeedFetcher,
    nowMillis: Long = System.currentTimeMillis(),
    ttlMillis: Long = LMSPEED_DEFAULT_TTL_MILLIS,
    exactAliasIndex: Map<String, String> = emptyMap(),
    normalizedAliasIndex: Map<String, String> = emptyMap(),
    knownCanonicalIds: Set<String> = emptySet(),
): LmspeedEnrichmentSummary {
    require(nowMillis >= 0L) { "nowMillis must be >= 0" }
    require(ttlMillis >= 0L) { "ttlMillis must be >= 0" }
    val store = ExternalStore(db)
    val sink = LmspeedSinkAdapter.forStore(store)
    val importer = LmspeedImporter(fetcher, sink)
    val result = importer.importOnce(nowMillis, ttlMillis)
    when (result) {
        is LmspeedImportResult.Failure -> throw IllegalStateException(result.error, result.cause)
        is LmspeedImportResult.Success -> {
            // 真 indices：三者皆空（產品 UI 路徑）即從 DB 建真值；任一非空（測試顯式路徑）即尊重傳入。
            val indices = if (exactAliasIndex.isEmpty() && normalizedAliasIndex.isEmpty() && knownCanonicalIds.isEmpty()) {
                runCatching { CanonicalStore(db).lmspeedMappingIndices() }.getOrDefault(
                    LmspeedMappingIndices(),
                )
            } else {
                LmspeedMappingIndices(exactAliasIndex, normalizedAliasIndex, knownCanonicalIds)
            }
            var mapped = 0
            for (row in result.rows) {
                val decision = mapExternalToCanonicalDecision(
                    externalModelId = row.externalModelId,
                    exactAliasIndex = indices.exactAliasIndex,
                    normalizedAliasIndex = indices.normalizedAliasIndex,
                    knownCanonicalIds = indices.knownCanonicalIds,
                    source = row.source,
                )
                try {
                    store.saveDecision(
                        externalId = row.externalModelId,
                        decision = decision,
                        verified = false,
                        version = 1,
                        observedAt = row.fetchedAt,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 單筆映射寫回失敗不拖垮整批（觀測已落庫；失敗保舊，該筆下次重建）。
                    continue
                }
                if (decision.canonicalId != null) mapped++
            }
            return LmspeedEnrichmentSummary(
                saved = result.saved,
                mapped = mapped,
                lastSyncedAtMillis = System.currentTimeMillis(),
                latestObservedAtMillis = result.rows.maxOfOrNull { it.fetchedAt },
            )
        }
    }
}

    /**
     * P7 repair read consumer（LMS-003；追加只讀，供 Models／Detail 外部分欄，UI 由 B 接線）。
     * 以 canonical 反查外部 enrichment（TTL 預設過濾；local／external 分欄由呼叫端呈現，
     * 本層只回外部列，不摻本地 snapshot／telemetry）。
     */
    suspend fun externalEnrichmentFor(
        canonicalModelId: String,
        nowMillis: Long = System.currentTimeMillis(),
        includeExpired: Boolean = false,
    ): List<ExternalObservationEntity> =
        ExternalStore(db).observationsForCanonical(canonicalModelId, nowMillis, includeExpired)

    /** P7 repair：同 source＋external 最新觀測（無即 null，不猜；只讀外部槽）。 */
    suspend fun latestExternalObservationOf(source: String, externalModelId: String): ExternalObservationEntity? =
        ExternalStore(db).latestObservationOf(source, externalModelId)

    // =====================================================================
    // DATA-PROV worker（PROV-001 / MODEL-006 consumer 資料層 ports；追加區，
    // 不改既有函式）。
    //
    // TRACEABILITY: PROV-001（Provider Detail 新增模型／立即測試）、MODEL-006
    // （manual hidden offering）；PRODUCT_SPEC §4／§6／§8／§10。
    //
    // 契約：
    // - 所選 record 即唯一真相：所有父列必須存在、歸屬一致、enabled；不挑另一把 key。
    // - 匿名僅限已 commit 的 auth NONE provider（不捏造 credential）。
    // - add：只寫 USER evidence ＋ hidden manual offering（冪等，manual 家族）；
    //   不寫 availability（未驗證不可用，除非 preexisting）；既有 official 列零寫入。
    // - verify：精確 sampleId ＋ "sample:<id>" evidence 查找（絕不用 latest 類查詢）；
    //   final completedAt ＋ SUCCESS ＋ MANUAL_TEST ＋ 七維 scope 精確比對 target；
    //   custom-only auth 可 target.credentialId=null，但 auth fields 必須是該
    //   provider 的可信 refs；promote ＋ availability 同 transaction 原子；
    //   price/quota 恒 UNKNOWN（不複製其他模型的值）；既有 availability last-good
    //   不動、v14 exact group scope 共存互不覆寫（wrong-sample-group guard 不變）；
    //   official 列成功零寫入。RUNNING／取消／失敗一律不 promote。
    // - 只經手 safe credential refs；raw key/PAT 永不進本層；錯誤訊息一律靜態
    //   字串（不含 URL／server body／secret）。
    // =====================================================================

    /** 匿名有效前提：provider 已有 committed 的 auth NONE 配置列（§6）。 */
    private suspend fun requireCommittedAnonymousAuthNone(providerId: String) {
        val config = db.providerConfigDao().getByProvider(providerId)
            ?: throw IllegalArgumentException("Provider config not found")
        require(config.toManualConfig().authMode == AuthMode.NONE) { "Anonymous requires committed auth NONE" }
    }

    /** Reuse legacy Bearer and committed custom-only anchor contracts, never the quota-only role gate. */
    private suspend fun requireProviderModelCredential(record: ConnectionRecord): ProviderManualConfig? {
        val entity = db.providerConfigDao().getByProvider(record.providerId)
        if (entity == null) {
            // P1 legacy saveConnection has no config; a real persisted availability proves its selected ref.
            require(db.availabilityDao().byCredential(record.credentialId).any {
                it.id == record.id && it.accountId == record.accountId && it.offeringId == record.offeringId
            }) { "Selected connection unavailable" }
            return null
        }
        val config = entity.toManualConfig()
        val roles = config.credentials.filter { it.safeCredentialId == record.credentialId }
            .map { it.role }.toSet()
        require(CredentialRole.ACCOUNT !in roles) { "Account credential cannot be used for inference" }
        val customOnly = (config.authMode == AuthMode.API_KEY || config.authMode == AuthMode.CUSTOM_HEADER) &&
            config.credentials.none { it.role == CredentialRole.INFERENCE }
        val trustedAnchor = customOnly && config.customAuthFields.any { it.safeCredentialId == record.credentialId }
        require(roles == setOf(CredentialRole.INFERENCE) || trustedAnchor) { "Credential role unavailable" }
        require(config.authMode != AuthMode.NONE) { "Named credential cannot be used for anonymous auth" }
        return config
    }

    /**
     * custom auth fields 只信 committed config：完整欄位必須出現在 provider config 的
     * customAuthFields 內，且絕非 ACCOUNT（PAT）角色；不是任意
     * 同 provider enabled key。
     */
    private suspend fun requireCommittedCustomAuthRefs(providerId: String, fields: List<InferenceAuthField>) {
        if (fields.isEmpty()) return
        val configEntity = db.providerConfigDao().getByProvider(providerId)
            ?: throw IllegalArgumentException("Credential role unavailable")
        val config = try {
            configEntity.toManualConfig()
        } catch (_: Exception) {
            throw IllegalArgumentException("Credential role unavailable")
        }
        val accountRefs = config.credentials
            .filter { it.role == CredentialRole.ACCOUNT }
            .map { it.safeCredentialId.trim() }
            .toSet()
        for (field in fields) {
            require(field.credentialId !in accountRefs) { "Credential role unavailable" }
            require(config.customAuthFields.any {
                it.safeCredentialId == field.credentialId && it.name.trim() == field.name &&
                    (it.location == dev.modelatlas.core.onboarding.CredentialLocation.QUERY) == field.query &&
                    it.overrideConfirmed == field.overrideConfirmed
            }) { "Custom auth field unavailable" }
            val credential = db.credentialDao().getById(field.credentialId)
                ?: throw IllegalArgumentException("Credential not found")
            val owner = db.accountDao().getById(credential.accountId)
                ?: throw IllegalArgumentException("Account not found")
            require(credential.enabled && owner.providerId == providerId) { "Custom auth ownership mismatch" }
        }
    }

    /**
     * PROV-001 新增模型候選：持久化 USER evidence ＋ hidden manual offering，
     * 回傳實際 persisted offering id。三元組 (provider, endpoint, model) 既有列
     * （manual 或 official）一律重用並零寫入：official 不動、manual 冪等；只有
     * 三元組缺席才插入 hidden manual candidate（經 [addManualOffering]）。
     * 未驗證候選強制 hidden，不出現在任何連線投影。
     *
     * @throws IllegalArgumentException record 空白／group 格式非法／父列不存在或
     *   歸屬不一致／endpoint URL／protocol 與持久化列不符／憑證不存在、跨帳戶、
     *   disabled、PAT／ACCOUNT 或不可信角色／匿名但未 commit auth NONE。
     *   既有 P1 legacy Bearer 與已 commit custom-only anchor 依原 production 契約處理。
     */
    suspend fun addProviderModelCandidate(record: ConnectionRecord, modelId: String, group: String?): String {
        require(record.providerId.isNotBlank()) { "providerId must not be blank" }
        require(record.accountId.isNotBlank()) { "accountId must not be blank" }
        require(record.endpointId.isNotBlank()) { "endpointId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        requireValidDedicatedGroup(group)
        if (record.anonymous) {
            require(record.credentialId.isEmpty()) { "Anonymous record must not carry credential ref" }
            requireCommittedAnonymousAuthNone(record.providerId)
        } else {
            require(record.credentialId.isNotBlank()) { "Credential ref required" }
            requireProviderModelCredential(record)
        }
        val provider = db.providerDao().getById(record.providerId)
            ?: throw IllegalArgumentException("Provider not found")
        check(provider.providerId == record.providerId) { "Provider mismatch" }
        val account = db.accountDao().getById(record.accountId)
            ?: throw IllegalArgumentException("Account not found")
        require(account.providerId == record.providerId) { "Cross-provider refs rejected" }
        val endpoint = db.endpointDao().getById(record.endpointId)
            ?: throw IllegalArgumentException("Endpoint not found")
        require(endpoint.providerId == record.providerId) { "Endpoint ownership mismatch" }
        // 持久化 endpoint 一致性：偽造 record baseUrl／endpointUrl／protocol 即拒絕。
        require(endpoint.baseUrl == record.baseUrl) { "Endpoint mismatch" }
        require(endpoint.endpointUrl == record.endpointUrl) { "Endpoint mismatch" }
        require(endpoint.protocol == record.protocol) { "Endpoint mismatch" }
        if (!record.anonymous) {
            val credential = db.credentialDao().getById(record.credentialId)
                ?: throw IllegalArgumentException("Credential not found")
            require(credential.accountId == record.accountId) { "Cross-account credential rejected" }
            require(credential.enabled) { "Credential disabled" }
        }
        return db.withTransaction {
            // 三元組已存在（manual 或 official）即重用既有列、零寫入：official 不動、
            // manual 冪等；只有三元組缺席才插入 hidden manual candidate。
            val existing = db.offeringDao().byProvider(record.providerId).firstOrNull {
                it.providerId == record.providerId &&
                    it.endpointId == record.endpointId &&
                    it.providerModelId == modelId
            }
            if (existing != null) {
                return@withTransaction existing.id
            }
            val now = System.currentTimeMillis()
            val evidenceId = "user-offering:" + UUID.randomUUID()
            val draft = ManualOfferingStore.createHiddenOffering(
                providerId = record.providerId,
                endpointId = record.endpointId,
                providerModelId = modelId,
                source = MANUAL_OFFERING_SOURCE_USER,
                evidenceId = evidenceId,
                observedAt = now,
            )
            val offeringId = addManualOffering(draft)
            // USER evidence：requestId 綁實際 persisted offeringId（可回溯）；outcome=OTHER
            //（無 request，不冒充成功）；evidence 寫入失敗即整個 transaction rollback。
            db.evidenceDao().insert(
                EvidenceEntity(
                    id = evidenceId,
                    requestId = offeringId,
                    providerId = record.providerId,
                    modelId = modelId,
                    outcome = Outcome.OTHER.name,
                    status = null,
                    timestamp = now,
                    source = MANUAL_OFFERING_SOURCE_USER,
                    version = 1,
                ),
            )
            offeringId
        }
    }

    /**
     * PROV-001 立即測試驗證：僅在 VM 完成 engine execute collect 後呼叫。
     * 以精確 [sampleId]（RequestSent.sampleId）與其 evidence（`"sample:" + sampleId`，
     * 見 [toEvidence] id 約定）查找本次 sample，絕不用 latest 類查詢猜測。
     * 全部比對 fail-closed：final completedAt、outcome SUCCESS、source MANUAL_TEST、
     * provider/account/endpoint/model/operation/group/canonical/safeCredentialId
     * 逐維精確等於 target；target 必須屬於所選 record ownership；匿名只允許
     * 真 auth NONE 無 credential；custom-only auth 可 target.credentialId=null，
     * 但 auth fields 必須是該 provider 已存在、enabled 的可信 refs。
     * 手動家族 offering：promote（hidden→visible，verificationEvidenceId 指向本次
     * evidence）＋ availability 寫入同 transaction 原子；新 availability
     * price/quota 恒 UNKNOWN；v14（CR-003 B3）promotion 的 availability 查找為
     * exact scope（offering/account/credential/GROUP identity，lossless：null 與
     * 空字串各自獨立、不當 wildcard）：exact 列存在即 last-good 原樣保留、絕不
     * 覆寫，不存在才新建；同 credential 的其他 group 列與本 group 共存、互不
     * 覆蓋。sample.group == target.group 的 wrong-sample-group guard 不變：實際
     * sample group 與 requested group 不符即在一切寫入前 fail-closed。匿名不寫
     * availability（不捏造 credential；匿名分支直接投影 offerings）。
     * 既有 official 列：驗證成功回 true，official verification／source 零寫入。
     *
     * @throws IllegalArgumentException sample/evidence 缺失或不符、scope/ownership
     *   不符（含實際 sample group 與 requested group 不符的 wrong-sample-group
     *   guard）、endpoint URL／protocol／operation 與 persisted record 不符、
     *   RUNNING／取消／失敗、group 格式非法、憑證非
     *   INFERENCE 角色（PAT／ACCOUNT／未知拒絕）或 custom auth fields 非
     *   committed config 可信 refs。
     */
    suspend fun verifyProviderModelCandidate(
        offeringId: String,
        record: ConnectionRecord,
        target: InferenceTarget,
        sampleId: String,
    ): Boolean {
        require(offeringId.isNotBlank()) { "offeringId must not be blank" }
        require(sampleId.isNotBlank()) { "sampleId must not be blank" }
        require(record.providerId.isNotBlank()) { "providerId must not be blank" }
        require(record.accountId.isNotBlank()) { "accountId must not be blank" }
        require(record.endpointId.isNotBlank()) { "endpointId must not be blank" }
        val validGroup = requireValidDedicatedGroup(target.group)
        val evidenceId = "sample:" + sampleId
        val manualTestSource = TelemetrySource.MANUAL_TEST.name
        db.withTransaction {
            // 精確關聯：本次 sample ＋ 對應 evidence（toEvidence id 約定）。
            val sample = db.sampleDao().getById(sampleId)
                ?: throw IllegalArgumentException("Verification sample not found")
            val evidence = db.evidenceDao().getById(evidenceId)
                ?: throw IllegalArgumentException("Verification evidence not found")
            require(evidence.requestId == sampleId) { "Verification evidence mismatch" }
            require(evidence.outcome == Outcome.SUCCESS.name) { "Verification evidence mismatch" }
            require(evidence.source == manualTestSource) { "Verification evidence mismatch" }
            require(evidence.providerId == sample.providerId) { "Verification evidence mismatch" }
            require(evidence.modelId == sample.providerModelId) { "Verification evidence mismatch" }
            // final ＋ 成功 ＋ MANUAL_TEST：RUNNING／取消／失敗一律不 promote。
            require(sample.completedAt != null) { "Verification sample not final" }
            require(sample.outcome == Outcome.SUCCESS.name) { "Verification sample not successful" }
            require(sample.source == manualTestSource) { "Verification source mismatch" }
            // 七維 scope 精確比對 target（多餘／缺失／他 key 皆不符）。
            require(sample.providerId == target.providerId) { "Verification scope mismatch" }
            require(sample.accountId == target.accountId) { "Verification scope mismatch" }
            require(sample.endpointId == target.endpointId) { "Verification scope mismatch" }
            require(sample.providerModelId == target.modelId) { "Verification scope mismatch" }
            require(sample.operation == target.operation) { "Verification scope mismatch" }
            require(sample.group == target.group) { "Verification scope mismatch" }
            require(sample.canonicalModelId == target.canonicalModelId) { "Verification scope mismatch" }
            require(sample.safeCredentialId == target.credentialId.orEmpty()) { "Verification scope mismatch" }
            // target 必須屬於所選 record ownership；不猜另一把 key。
            require(target.providerId == record.providerId) { "Target ownership mismatch" }
            require(target.accountId == record.accountId) { "Target ownership mismatch" }
            require(target.endpointId == record.endpointId) { "Target ownership mismatch" }
            require(target.modelId.isNotBlank()) { "Target model required" }
            // account／endpoint 持久化歸屬＋URL／protocol 一致：偽造 operation／endpointUrl
            // 即使 sample 全等也拒絕（不得繞過原選定 persisted endpoint）。
            val account = db.accountDao().getById(record.accountId)
                ?: throw IllegalArgumentException("Account not found")
            require(account.providerId == record.providerId) { "Target ownership mismatch" }
            val endpoint = db.endpointDao().getById(record.endpointId)
                ?: throw IllegalArgumentException("Endpoint not found")
            require(endpoint.providerId == record.providerId) { "Target ownership mismatch" }
            require(endpoint.baseUrl == record.baseUrl) { "Endpoint mismatch" }
            require(endpoint.endpointUrl == record.endpointUrl) { "Endpoint mismatch" }
            require(endpoint.protocol == record.protocol) { "Endpoint mismatch" }
            require(target.endpointUrl == record.endpointUrl) { "Target endpoint mismatch" }
            require(target.operation == record.protocol) { "Target operation mismatch" }
            if (record.anonymous) {
                require(record.credentialId.isEmpty()) { "Anonymous record must not carry credential ref" }
                require(target.credentialId == null) { "Anonymous target must not carry credential ref" }
                require(target.authFields.isEmpty()) { "Anonymous target must not carry auth fields" }
                require(sample.safeCredentialId.isEmpty()) { "Anonymous verification must not carry credential ref" }
                requireCommittedAnonymousAuthNone(record.providerId)
            } else {
                require(record.credentialId.isNotBlank()) { "Credential ref required" }
                val credential = db.credentialDao().getById(record.credentialId)
                    ?: throw IllegalArgumentException("Credential not found")
                require(credential.accountId == record.accountId) { "Cross-account credential rejected" }
                require(credential.enabled) { "Credential disabled" }
                val config = requireProviderModelCredential(record)
                requireCommittedCustomAuthRefs(record.providerId, target.authFields)
                if (target.credentialId != null) {
                    require(target.credentialId == record.credentialId) { "Target credential mismatch" }
                } else {
                    // custom-only auth：record 保留安全選定 ref；auth fields 必須是
                    // committed config 欄位／非 ACCOUNT 角色的可信 refs，且 DB 存在、
                    // enabled、歸屬該 provider——不是任意同 provider enabled key。
                    require(target.authFields.isNotEmpty()) { "Credential required" }
                    require(config != null &&
                        (config.authMode == AuthMode.API_KEY || config.authMode == AuthMode.CUSTOM_HEADER) &&
                        config.credentials.none { it.role == CredentialRole.INFERENCE } &&
                        target.authFields.any { it.credentialId == record.credentialId }
                    ) { "Custom-only credential anchor required" }
                    for (field in target.authFields) {
                        val refCredential = db.credentialDao().getById(field.credentialId)
                            ?: throw IllegalArgumentException("Credential not found")
                        val owner = db.accountDao().getById(refCredential.accountId)
                            ?: throw IllegalArgumentException("Account not found")
                        require(owner.providerId == record.providerId) { "Cross-provider credential rejected" }
                        require(refCredential.enabled) { "Credential disabled" }
                    }
                }
            }
            val offering = db.offeringDao().getById(offeringId)
                ?: throw IllegalArgumentException("Offering not found")
            require(offering.providerId == record.providerId) { "Offering ownership mismatch" }
            require(offering.endpointId == record.endpointId) { "Offering endpoint mismatch" }
            require(offering.providerModelId == target.modelId) { "Offering model mismatch" }
            if (!record.anonymous && target.credentialId == null) {
                // The transport has no Bearer ref, so preserve the selected anchor explicitly
                // rather than allowing another anchor's identical telemetry scope to donate proof.
                val scopeId = manualVerificationScopeId(sampleId, record.credentialId)
                val scopeRequest = manualVerificationScopeRequest(sampleId)
                val existingBinding = db.evidenceDao().byRequest(scopeRequest)
                require(existingBinding == null || existingBinding.id == scopeId) {
                    "Verification sample belongs to another selected anchor"
                }
                db.evidenceDao().upsert(EvidenceEntity(
                    id = scopeId,
                    requestId = scopeRequest,
                    providerId = record.providerId, modelId = target.modelId,
                    outcome = Outcome.SUCCESS.name, status = sample.httpStatus,
                    timestamp = sample.completedAt ?: evidence.timestamp,
                    source = TelemetrySource.MANUAL_TEST.name,
                ))
            }
            val existingSource = offering.source.trim().uppercase()
            val isManualFamily = offering.manual &&
                (existingSource == MANUAL_OFFERING_SOURCE_MANUAL ||
                    existingSource == MANUAL_OFFERING_SOURCE_USER)
            if (!isManualFamily) {
                // 既有 official 列：成功回 true，official verification／source 零寫入。
                return@withTransaction
            }
            val completedAtFinal = sample.completedAt
            // v14（CR-003 B3）既有 availability 即 last-good：promotion 的 availability
            // 查找改 exact scope（offering/account/credential/GROUP identity，
            // availabilityGroupIdentity lossless，null 與空字串各自獨立、不當 wildcard）。
            // exact 列存在即 last-good 原樣保留、絕不覆寫；不存在才新建（下方，
            // price/quota 恒 UNKNOWN）。同 credential 的其他 group 列與本 group
            // 共存、互不覆蓋；sample.group == target.group 的 wrong-sample-group
            // guard（上方）在任何寫入／成功回傳前 fail-closed，不符即拋出、
            // transaction rollback 保 last-good。
            val existingAvailability = if (!record.anonymous) {
                db.availabilityDao().byExactScope(offering.id, record.accountId, record.credentialId, validGroup)
            } else {
                emptyList()
            }
            val promoted = promoteManualOffering(
                OfferingDraft(
                    id = offering.id,
                    providerId = offering.providerId,
                    endpointId = offering.endpointId,
                    providerModelId = offering.providerModelId,
                    manual = true,
                    source = existingSource,
                    evidenceId = evidenceId,
                    observedAt = completedAtFinal ?: evidence.timestamp,
                    hidden = false,
                    verificationEvidenceId = evidenceId,
                ),
                evidenceId,
            )
            when (promoted) {
                is ManualPromoteResult.Rejected ->
                    throw IllegalArgumentException(promoted.reason)
                is ManualPromoteResult.Promoted ->
                    addManualOffering(promoted.draft)
            }
            if (!record.anonymous && existingAvailability.isEmpty()) {
                // 僅在無 exact-scope availability 時新建：price/quota 恒 UNKNOWN（不複製
                // 其他模型／其他 group 的值）；promote ＋ availability 同 transaction 原子。
                db.availabilityDao().insert(
                    CredentialAvailabilityEntity(
                        id = "avail:" + UUID.randomUUID(),
                        offeringId = offering.id,
                        accountId = record.accountId,
                        credentialId = record.credentialId,
                        priceState = "UNKNOWN",
                        quotaState = "UNKNOWN",
                        group = validGroup,
                    ),
                )
            }
        }
        return true
    }

    // =====================================================================
    // DATA-MYM004 worker（MYM-004 §23.3 bundle 餘項；追加區，不改既有函式）。
    //
    // TRACEABILITY: MYM-004；Contract §23.3（模型加入即自動接 Intelligence）；
    // PRODUCT_SPEC §4／§6／§8／§10；DECISIONS D-2026-10-01-008／010。
    //
    // 契約：
    // - membership／offerings／samples／telemetry 零寫入：membership 由呼叫端先行
    //   落庫（MyModelsStore.addToMyModels），本函式只做 enrichment；任一來源失敗
    //   不刪 membership／offering／telemetry（D-008；Catalog != My Models 不變）。
    // - 全鏈 fail-open：步驟失敗只記靜態標籤（不含 URL／server body／secret）並
    //   回傳部分結果；CancellationException 照常傳播。輸入驗證（空白 id／group
    //   格式）fail-closed require，與既有 sync 路徑同規則。
    // - Exact scope：只用傳入的 account／credential／group／endpoint／operation；
    //   未知即 null（只命中 null 維 head，七維不互借）；禁 fallback 猜測、禁跨
    //   scope 讀寫。account 未知時只採既有 sync 摘要自帶的 resolvedAccountId
    //  （同步實際寫入 scope 的真值，非本層猜測）。
    // - New API 腿（M2 第三腿）：controlled port `newApiAdapter` 注入才走既有
    //   syncNewApi（dedicated 家族已由 dedicated 腿覆蓋，不重疊）；adapter=null
    //   靜默跳過無標籤。前置（PAT／endpoint／offering／origin）在首次網路請求前
    //   fail-fast（不燒額度），經 failOpen 記靜態標籤 PRICING_NEWAPI，其餘腿照跑；
    //   結果寫 MyModelBundleResult 獨立 nullable 欄，不塞 DedicatedSyncSummary
    //  （那是 dedicated 語意）。只傳 safe ref（AccountPatRef 由 syncNewApi 建構），
    //   raw secret 留 adapter HTTP 邊界，不進本層。
    // - Provider perf（NINT-004 第四腿，candidate NEWAPI-001）：獨立 controlled
    //   port [newApiPerfAdapter] 注入才走 [ProviderPerfStore.syncPerf]（summary＋
    //   per-model query，產品預設 168h；pricing 第三腿的 `newApiAdapter` 不共用，
    //   不得多燒 pricing-only 既有路徑）。port=null 靜默跳過（false、無標籤）。
    //   version gate 讀 repo 已存官方 certified evidence（stored 未認證即零請求）；
    //   PAT 選用該 provider 管理 safe ref、無 PAT 匿名；失敗／空／無效只記標記、
    //   全無 good 資料才 fetched=false；typed failure／invalid／例外只記靜態標籤，
    //   另一腿的 good 不被抹掉。本地 samples／external
    //   觀測不冒充 provider-wide perf（X-005）；站方指標不反推 key 可用性。
    // - FREE evidence：只讀既有 snapshot heads（PRICE／DEDICATED_PRICE，official
    //   維度）已派生 state，不重算 PricingPolicy；group／endpoint／operation 提供
    //   時精確比對（含 null==null；M5 維度），分歧即 UNKNOWN、無候選 null；
    //   不讀 MANUAL_*（manual 不冒充 official）、不補 0／不補 Free。
    // =====================================================================

    /** 步驟 fail-open 執行：例外只記靜態標籤並回 null；CancellationException 照常傳播。 */
    private suspend fun <T> failOpen(
        label: String,
        failures: MutableList<String>,
        step: suspend () -> T,
    ): T? =
        try {
            step()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failures += label
            null
        }

    /**
     * MYM-004 §23.3 bundle（repository 能做的部分；VM 接線由主 AI 另行）。
     *
     * M5／M2 埠（NAPI-001）：[endpointId]／[operation] 為 FREE 證據精確維度
     * （缺值只命中 null 維 head，七維不互借）；[newApiAdapter] 為 New API 第三腿
     * controlled port（null 即靜默跳過無標籤；secret 留 adapter HTTP 邊界）。
     * [newApiPerfAdapter] 為 provider perf 第四腿獨立 controlled port（NINT-004；
     * null 即靜默跳過無標籤；pricing 腿的 [newApiAdapter] 不共用）。
     * CR-001 exact pass-through（CR001／NINT002·005A；Contract §23.3）：
     * New API 腿原樣透傳 [accountId]／[credentialId] 至 [syncNewApi]（命名參數；
     * 歧義／錯 ref 由 selector 在首次網路前 fail-closed，經 failOpen 記
     * PRICING_NEWAPI，其餘腿照跑）；perf 腿原樣透傳 [accountId] 至
     * [ProviderPerfStore.syncPerf]（命名參數，尾部 additive default）。
     *
     * 流程（步驟失敗只記 [MyModelBundleResult.stepFailures] 靜態標籤，部分結果回傳）：
     * 1. resolve CanonicalModel → attach ProviderModelAlias：既有 alias 直接沿用
     *    （零寫入）；缺席時 saveCanonical(providerModelId) → saveAlias
     *    （source=NORMALIZATION／confidence=LOW、verified=false：mapping 由
     *    normalization 派生，本函式不取 metadata，不冒充 METADATA／OFFICIAL；
     *    CanonicalStore 既有守衛照常生效：official 不降級、跨 source 不覆寫）。
     * 2. pricing／quota：dedicated 家族走既有 syncDedicatedPricing／
     *    syncDedicatedQuotaPools（single-flight、失敗分類、last-good 語意不變）；
     *    New API 腿（2b）由 controlled port 注入 [newApiAdapter] 才走既有
     *    syncNewApi（dedicated 家族不重疊；adapter=null 靜默跳過無標籤；結果寫
     *    newApi* 欄，不塞 DedicatedSyncSummary）。
     * 3. provider perf（NINT-004 第四腿）：獨立 [newApiPerfAdapter] 才走
     *    [ProviderPerfStore.syncPerf]（stored version gate＋168h summary／query＋
     *    落庫；port=null／gate 未過即 false；失敗 fail-open，不阻其他腿）。
     * 4. external：caller 注入 [LmspeedFetcher] 才走既有 syncLmspeedEnrichment
     *    （本函式不造網路層；null 即靜默跳過）；attach 只讀 externalEnrichmentFor
     *    （resolved canonical 的 TTL 內新鮮觀測數，不寫外部槽以外的任何槽）。
     * 5. FREE evidence：deriveFreePriceState（snapshot heads 已派生 state；
     *    group／endpoint／operation 提供時精確比對，缺值只命中 null 維）。
     *
     * 輸入驗證 fail-closed：providerId／providerModelId 空白、group 格式非法即
     * require 拒絕（既有 sync 路徑同規則）；membership 由呼叫端先行落庫，本函式
     * 不檢查也不寫 membership。
     */
    suspend fun enrichMyModelBundle(
        providerId: String,
        providerModelId: String,
        accountId: String? = null,
        credentialId: String? = null,
        group: String? = null,
        lmspeedFetcher: LmspeedFetcher? = null,
        endpointId: String? = null,
        operation: String? = null,
        newApiAdapter: NewApiAdapter? = null,
        newApiPerfAdapter: NewApiAdapter? = null,
        /**
         * CR-009 DRAFT: wave passport from the VM registry (earlier leader's
         * observed caps + summary memo). Null = legacy path. Consumed read-only
         * by the syncNewApi legs and the perf leg; memo update returned via
         * [MyModelBundleResult.sessionPassport].
         */
        newApiPassport: NewApiSessionPassport? = null,
    ): MyModelBundleResult {
        require(providerId.isNotBlank()) { "Provider required" }
        require(providerModelId.isNotBlank()) { "Model required" }
        val validGroup = requireValidDedicatedGroup(group)
        credentialId?.let { require(it.isNotBlank()) { "Credential ref required" } }
        // LOW-7：入口即做完整 safe-ref 檢查；raw secret 違規 fail-closed 拋出
        //（VM 接住，不靜默吞成 QUOTA 失敗）。
        credentialId?.let { requireSafeDedicatedRef(it) }
        val failures = mutableListOf<String>()
        // 1. canonical／alias attach（冪等；既有 alias 沿用零寫入）。
        var canonicalId: String? = null
        var aliasRow: ProviderModelAliasEntity? = null
        try {
            val existing = db.providerModelAliasDao().get(providerId, providerModelId)
            if (existing != null) {
                canonicalId = existing.canonicalModelId
                aliasRow = existing
            } else {
                val canonical = CanonicalStore(db).saveCanonical(
                    providerModelId,
                    nowMillis = System.currentTimeMillis(),
                )
                canonicalId = canonical.canonicalModelId
                // MEDIUM-5：完整性守衛拒絕（official 降級／跨 source 覆寫／FK）與暫時性
                // 失敗分標籤；前者永久、本質不同，不冒充「未解析」。
                try {
                    aliasRow = CanonicalStore(db).saveAlias(
                        providerId = providerId,
                        providerModelId = providerModelId,
                        canonicalModelId = canonical.canonicalModelId,
                        source = MappingSource.NORMALIZATION.name,
                        confidence = MappingConfidence.LOW.name,
                        verified = false,
                        observedAt = System.currentTimeMillis(),
                        version = 1,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: IllegalArgumentException) {
                    failures += "CANONICAL_ALIAS_GUARD"
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failures += "CANONICAL_ALIAS"
        }
        // 2. pricing／quota（既有 dedicated sync 路徑；一維失敗不阻另一維）。
        // pricing scope 永不帶 credential（既有 repository 規則先行拒絕；與
        // REVIEW-MYM004 finding 同根因）：此處剝掉，quota 維度才用 credential，
        // 否則具名 credential 的自動 enrichment pricing 維度永遠抓不到。
        var pricingSummary: DedicatedSyncSummary? = null
        var quotaSummary: DedicatedSyncSummary? = null
        if (dedicatedSupportsSyncResolved(providerId)) {
            pricingSummary = failOpen("PRICING", failures) {
                syncDedicatedPricing(providerId, accountId, null, validGroup)
            }
            quotaSummary = failOpen("QUOTA", failures) {
                syncDedicatedQuotaPools(providerId, accountId, credentialId, validGroup)
            }
        }
        // 2b. New API 腿（M2 第三腿）：controlled port 注入 adapter 才走既有
        // syncNewApi（dedicated 家族已由上腿覆蓋，不重疊）；adapter=null 靜默跳過
        // 無標籤。前置（PAT／endpoint／offering／origin）在首次網路請求前 fail-fast
        //（不燒額度），經 failOpen 記靜態標籤 PRICING_NEWAPI，其餘腿照跑；結果寫
        // 獨立 nullable 欄，不塞 DedicatedSyncSummary（dedicated 語意不互冒充）。
        // 只傳 safe ref（AccountPatRef 由 syncNewApi 建構），raw secret 留 adapter
        // HTTP 邊界，不進本層。
        var newApiPricingSynced: Int? = null
        var newApiAccountQuota: StoredQuota? = null
        var newApiTokenQuota: StoredQuota? = null
        var wavePassport: NewApiSessionPassport? = newApiPassport
        if (newApiAdapter != null && !dedicatedSupportsSyncResolved(providerId)) {
            val adapter = newApiAdapter
            val newApi = failOpen("PRICING_NEWAPI", failures) {
                syncNewApi(providerId, adapter, accountId = accountId, credentialId = credentialId,
                    passport = newApiPassport)
            }
            newApiPricingSynced = newApi?.prices?.size
            newApiAccountQuota = newApi?.accountQuota
            newApiTokenQuota = newApi?.tokenQuota
            // First leader creates; later leaders (pricing adapter nulled by
            // generation) keep the VM-supplied passport for the perf leg.
            wavePassport = newApi?.sessionPassport ?: wavePassport
        }
        // 3. provider perf（NINT-004 第四腿）：獨立 newApiPerfAdapter 才走
        // ProviderPerfStore.syncPerf（summary＋per-model query，產品預設 168h；
        // pricing 腿的 newApiAdapter 不共用——VM generation 對同 provider 下一模型
        // 會把 pricing adapter 降為 null，不能因此永久漏 per-model perf）。
        // port=null 靜默跳過（false、無標籤）；stored version gate 未過零請求；
        // PAT 選用／匿名、typed failure／invalid／例外皆只記靜態 PROVIDER_PERF；
        // 其餘腿照跑；CancellationException 照常傳播。
        var providerPerformanceFetched = false
        if (newApiPerfAdapter != null) {
            providerPerformanceFetched = failOpen("PROVIDER_PERF", failures) {
                val result = providerPerf.syncPerf(newApiPerfAdapter, providerId, providerModelId,
                    accountId = accountId, passport = wavePassport)
                if (result.failed) failures += "PROVIDER_PERF"
                // Memo update: only a certified+surface-valid summary from THIS call
                // is remembered (exclusion set in store); a hit itself writes zero.
                result.summaryObservation?.let { wavePassport = wavePassport?.copy(summary = it) }
                result.fetched
            } == true
        }
        // 4. external：注入 fetcher 才走既有 syncLmspeedEnrichment；attach 只讀。
        var externalRefreshAttempted = false
        var externalRefreshOk: Boolean? = null
        if (lmspeedFetcher != null) {
            externalRefreshAttempted = true
            // MEDIUM-4：false 可達＝嘗試但零寫入；失敗走 null＋標籤。
            externalRefreshOk = failOpen("EXTERNAL_REFRESH", failures) {
                syncLmspeedEnrichment(lmspeedFetcher).saved > 0
            }
        }
        val attachedCanonical = canonicalId
        // HIGH-1／MEDIUM-3：Int?——null＝未查（canonical 未解析）或讀取失敗
        //（Unknown，不補 0）；真的是 0 才 0。
        var externalObservationCount: Int? = null
        if (attachedCanonical != null) {
            externalObservationCount = failOpen("EXTERNAL_ATTACH", failures) {
                externalEnrichmentFor(attachedCanonical).size
            }
        }
        // 5. FREE evidence：只用已拿到的 snapshot heads 推導，推不出即 Unknown。
        var freePriceState: String? = null
        val scopedAccount = accountId?.takeIf { it.isNotBlank() }
            ?: pricingSummary?.resolvedAccountId
            ?: quotaSummary?.resolvedAccountId
        if (scopedAccount != null) {
            freePriceState = failOpen("FREE_EVIDENCE", failures) {
                deriveFreePriceState(providerId, scopedAccount, providerModelId, validGroup, endpointId, operation)
            }
        }
        return MyModelBundleResult(
            providerId = providerId,
            providerModelId = providerModelId,
            canonicalModelId = canonicalId,
            aliasAttached = aliasRow != null,
            aliasSource = aliasRow?.source,
            aliasConfidence = aliasRow?.confidence,
            pricingSummary = pricingSummary,
            quotaSummary = quotaSummary,
            providerPerformanceFetched = providerPerformanceFetched,
            externalRefreshAttempted = externalRefreshAttempted,
            externalRefreshOk = externalRefreshOk,
            externalObservationCount = externalObservationCount,
            freePriceState = freePriceState,
            newApiPricingSynced = newApiPricingSynced,
            newApiAccountQuota = newApiAccountQuota,
            newApiTokenQuota = newApiTokenQuota,
            stepFailures = failures.toList(),
            sessionPassport = wavePassport,
        )
    }

    /**
     * FREE evidence 推導（MYM-004）：只讀既有 snapshot heads（PRICE／
     * DEDICATED_PRICE，official 維度）已派生的 PriceState state，不重算
     * PricingPolicy。同 provider／account／model（group／endpoint／operation
     * 提供時精確比對，含 null==null；呼叫端缺值時只命中 null 維 head，絕不借
     * 別 endpoint／operation 的 state 冒充單一維結果）候選 state 全一致才取值、
     * 任一分歧即 UNKNOWN、無候選 null（Unknown，不補 0／不補 Free）。不讀
     * MANUAL_*（manual 不冒充 official）；格式不合的列 fail-closed 跳過，絕不猜。
     */
    private suspend fun deriveFreePriceState(
        providerId: String,
        accountId: String,
        providerModelId: String,
        group: String?,
        endpointId: String?,
        operation: String?,
    ): String? {
        val factDao = db.snapshotFactDao()
        var seen: String? = null
        var count = 0
        for (kind in listOf(SnapshotKind.PRICE.name, SnapshotKind.DEDICATED_PRICE.name)) {
            for (head in factDao.headsByKind(kind)) {
                val parts = parseScopeKey(head.scopeKey) ?: continue
                if (parts[0] != providerId) continue
                if (parts[1] != accountId) continue
                if (parts[2] != null) continue
                if (parts[3] != providerModelId) continue
                // HIGH-2：group 精確比對（含 null==null；呼叫端 group 未知時只取
                // group-agnostic heads，不拿別 group 的 head 冒充）。
                if (parts[4] != group) continue
                // HIGH-2 延伸（M5）：endpoint／operation 精確比對（含 null==null；
                // 呼叫端缺值時只命中 null 維 head，絕不借別 endpoint／operation 的
                // head 冒充單一維 state 為模型整體 FREE／誤判分歧）。
                if (parts[5] != endpointId) continue
                if (parts[6] != operation) continue
                val state = factDao.evidence(head.latestEvidenceId)?.state ?: continue
                count++
                if (seen == null) seen = state
                else if (seen != state) return "UNKNOWN"
            }
        }
        return if (count == 0) null else seen
    }
}

/** promote 接線結果（top-level，P6-V 編譯破口修復）：成功帶已驗證草稿；空 evidence 等拒絕轉錯誤值，不拋崩。 */
sealed interface ManualPromoteResult {
    data class Promoted(val draft: OfferingDraft) : ManualPromoteResult
    data class Rejected(val reason: String) : ManualPromoteResult
}

/**
 * D-fix 接線（top-level 純函式，不寫 DB；持久化由呼叫端經 [AtlasRepository.addManualOffering]
 * 重送已驗證草稿，該路徑命中三元組/id 時會 UPDATE 寫回 hidden/verificationEvidenceId）。
 * 空 evidence 調 promote 必須 catch 轉錯誤回傳值，不拋崩。
 */
fun promoteManualOffering(draft: OfferingDraft, verificationEvidenceId: String): ManualPromoteResult =
    try {
        ManualPromoteResult.Promoted(ManualOfferingStore.promoteOnVerified(draft, verificationEvidenceId))
    } catch (e: IllegalArgumentException) {
        ManualPromoteResult.Rejected(e.message ?: "Verification evidence required to promote")
    }

/**
 * P6 worker C（B1）：Dedicated 同步結果摘要。
 *
 * `pricingSynced／quotaSynced` 為實際成功觀測的 scope 數（Failure／Unsupported 永不計入；
 * 表外模型跳過不計）；`…Unsupported` 表該維度 capability 缺失走降級（未寫庫、未失敗、
 * last-good 不動）；`resolvedAccountId` 為實際寫入 scope 的帳號，
 * `accountFallbackUsed=true` 表單帳戶 fallback（多帳戶缺參一律拋錯不猜）；
 * `latestObservedAtMillis` 為本次成功觀測的最大 observedAt（資料時間；null 表無成功觀測）；
 * `lastSyncedAtMillis` 為裝置本地觸發時鐘（僅供「同步觸發時間」顯示，不冒充觀測時間）。
 */
data class DedicatedSyncSummary(
    val providerId: String,
    val pricingSynced: Int,
    val quotaSynced: Int,
    val pricingUnsupported: Boolean,
    val quotaUnsupported: Boolean,
    val lastSyncedAtMillis: Long,
    val resolvedAccountId: String,
    val accountFallbackUsed: Boolean,
    val latestObservedAtMillis: Long?,
)

/**
 * P7-B2 LMSpeed enrichment 同步摘要（top-level 追加）。
 * `saved` 為寫入 `external_observations` 列數；`mapped` 為解析出非 null
 * canonical 並寫回 `external_mappings` 數（UNKNOWN null 亦寫槽，不計入）；
 * `lastSyncedAtMillis` 為裝置本地觸發時鐘（僅供顯示，不冒充觀測時間）；
 * `latestObservedAtMillis` 為本次成功觀測的最大 fetchedAt（資料時間）。
 */
data class LmspeedEnrichmentSummary(
    val saved: Int,
    val mapped: Int,
    val lastSyncedAtMillis: Long,
    val latestObservedAtMillis: Long?,
)

/**
 * MYM-004 §23.3 bundle 結果（top-level 追加）。
 *
 * 各來源分離；缺來源一律 Unknown（不補 0／不補 Free）：
 * - canonicalModelId=null 表 canonical 未解析（Unknown）；aliasAttached 表 alias 列
 *   已持久化（既有沿用或新寫）；aliasSource／aliasConfidence 為實際列的 provenance
 *   （新寫一律 NORMALIZATION／LOW，既有列照其原值回報）。
 * - pricingSummary／quotaSummary=null 表該步驟未嘗試（非 dedicated 家族）或失敗
 *   （stepFailures 帶靜態標籤）；非 null 為既有 DedicatedSyncSummary（last-good
 *   語意由 store 層保證）。New API 腿結果見 newApi* 欄，不塞本欄
 *  （DedicatedSyncSummary 是 dedicated 語意，不互相冒充）。
 * - providerPerformanceFetched（NINT-004）：獨立 `newApiPerfAdapter` port 未注入
 *   即 false（靜默跳過）；注入後由 [ProviderPerfStore.syncPerf] 決定——僅本次有
 *   verified-good 可用資料列持久化才 true；全無 good（空／全失敗／invalid／gate 未過）
 *   才 false，partial good 可為 true 且 stepFailures 保留來源失敗。本地 samples／external
 *   觀測不冒充 provider-wide perf；站方指標不反推
 *   key 可用性。
 * - externalRefreshAttempted=false 表未注入 fetcher（靜默跳過，本函式不造網路層）；
 *   externalRefreshOk=true（有寫入）／false（嘗試但零寫入）為既有
 *   syncLmspeedEnrichment 結果（null=未嘗試或失敗，見 stepFailures）。
 *   externalObservationCount 為 resolved canonical 的 TTL 內新鮮外部觀測數（唯讀挂接；
 *   null=未查或讀取失敗，Unknown 不補 0；真的是 0 才 0）。
 * - freePriceState 為 snapshot heads（PRICE／DEDICATED_PRICE，official 維度）已派生
 *   的 PriceState 名：同 model 候選全一致才取值；分歧即 UNKNOWN、無候選或讀取失敗
 *   為 null；nominal price 不當 effective charge。
 * - newApiPricingSynced／newApiAccountQuota／newApiTokenQuota 為 New API 第三腿
 *   （M2 controlled port `newApiAdapter`）結果：null 表該腿未嘗試（adapter 未
 *   注入，或 dedicated 家族已由 dedicated 腿覆蓋）或失敗（stepFailures 帶
 *   PRICING_NEWAPI）；非 null 為實際觀測——newApiPricingSynced 為成功落庫的
 *   官方 price scope 數（真 0 才 0），quota 為 store 層 StoredQuota（last-good
 *   語意由 store 層保證）。secret 不入本層：syncNewApi 只傳 AccountPatRef
 *   safe ref，原始值只在 adapter HTTP 邊界取值。
 * - stepFailures 為靜態標籤（CANONICAL_ALIAS／CANONICAL_ALIAS_GUARD／PRICING／
 *   QUOTA／PRICING_NEWAPI／PROVIDER_PERF／EXTERNAL_REFRESH／EXTERNAL_ATTACH／FREE_EVIDENCE），
 *   不含 URL／server body／secret。GUARD 表永久完整性拒絕（official 降級／跨
 *   source 覆寫／FK），與暫時性失敗區分。
 */
data class MyModelBundleResult(
    val providerId: String,
    val providerModelId: String,
    val canonicalModelId: String?,
    val aliasAttached: Boolean,
    val aliasSource: String?,
    val aliasConfidence: String?,
    val pricingSummary: DedicatedSyncSummary?,
    val quotaSummary: DedicatedSyncSummary?,
    val providerPerformanceFetched: Boolean,
    val externalRefreshAttempted: Boolean,
    val externalRefreshOk: Boolean?,
    val externalObservationCount: Int?,
    val freePriceState: String?,
    val newApiPricingSynced: Int? = null,
    val newApiAccountQuota: StoredQuota? = null,
    val newApiTokenQuota: StoredQuota? = null,
    val stepFailures: List<String>,
    /**
     * CR-009 DRAFT: wave passport (created or memo-updated in this call) for the
     * VM wave registry. Null = no certified caps observed in this call and none
     * supplied. Trailing defaulted field; existing call sites unaffected.
     */
    val sessionPassport: NewApiSessionPassport? = null,
)
