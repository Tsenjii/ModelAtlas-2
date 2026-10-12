package dev.modelatlas.app.data

import androidx.room.withTransaction
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.PricingPolicy
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.newapi.*
import dev.modelatlas.core.onboarding.CredentialRole
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * P4 官方入口：由同次 sync 取得 status + pricing，不能注入跨 session 匯率。
 * 僅接受本地 scope / safe refs；adapter 負責網路與 secret 邊界。
 * 各觀測 append，head 同 scope upsert；失敗／空清單／欄位缺失不抹 last-good。
 *
 * 價格／配額數值單一真相在 [NewApiDomainMapper]：本 store 不做任何 USD／ratio
 * 換算、不重算 quota state、不 eval `billing_expr`；只做 scope／role 驗證、
 * endpoint 認證、certified 閘門、evidence 鏈與 head／last-good 維護。不足欄位
 *（vendor／billing 原文＋hash／雙 pricingVersion／groupRatio／quota 整數／
 * 到期語義）一律取自 mapper 的 safe DTO，不碰 raw body／secret。
 */
class NewApiSnapshotStore(
    private val db: AtlasDatabase,
    private val accountQuotaObserver: AccountQuotaObserver? = null,
) {
    private val syncMutex = Mutex()

    suspend fun syncPricing(
        adapter: NewApiAdapter,
        pat: AccountPatRef,
        scopes: List<PricingScope>,
    ): List<StoredPrice> = syncMutex.withLock {
        require(scopes.isNotEmpty() && scopes.size <= 1000) { "Invalid scope count" }
        val first = scopes.first()
        require(scopes.all { it.providerId == first.providerId && it.accountId == first.accountId }) {
            "Pricing session ownership mismatch"
        }
        validatePat(first.providerId, first.accountId, pat)
        val base = baseUrl(first.providerId)
        scopes.forEach { validateScope(it, pricing = true) }
        val caps = adapter.fetchCapabilities(base)
        val result = if (caps is NewApiResult.Success) {
            adapter.fetchPricing(base, pat, caps.value.version)
        } else {
            val failure = caps as NewApiResult.Failure
            failure.copy(evidence = failure.evidence.copy(source = PRICE_SOURCE))
        }
        db.withTransaction {
            for (scope in scopes.distinct()) persistPricing(scope, caps, result)
        }
        scopes.map { price(it) }
    }

    /**
     * 同 session 定價落庫（不取網路）：呼叫端已一次取好 `caps`＋`pricing`，
     * 經 [buildPricingScopes] 建出官方 scopes，本函數只驗證＋落庫＋回讀。
     * `config.group` 永不進本路徑（scopes 全來自官方 catalog）。
     * Unknown 維持 null、失敗不碰 last-good（見 [persistPricing]／[record]）。
     */
    suspend fun syncPricingSession(
        pat: AccountPatRef,
        caps: NewApiResult<NewApiCapabilities>,
        result: NewApiResult<NewApiPricing>,
        scopes: List<PricingScope>,
    ): List<StoredPrice> = syncMutex.withLock {
        require(scopes.isNotEmpty() && scopes.size <= 1000) { "Invalid scope count" }
        val first = scopes.first()
        require(scopes.all { it.providerId == first.providerId && it.accountId == first.accountId }) {
            "Pricing session ownership mismatch"
        }
        validatePat(first.providerId, first.accountId, pat)
        scopes.forEach { validateScope(it, pricing = true) }
        db.withTransaction {
            for (scope in scopes.distinct()) persistPricing(scope, caps, result)
        }
        scopes.map { price(it) }
    }

    suspend fun syncAccountQuota(
        adapter: NewApiAdapter, providerId: String, accountId: String, pat: AccountPatRef,
        /**
         * CR-009 DRAFT: wave passport from the enclosing `syncNewApi` outer caps
         * observation. Null = legacy path. Consumed ONLY on exact match
         * (provider/base/origin/ACCOUNT_PAT/same safe PAT ref/same account/
         * certified version raw); mismatch = miss = fresh fetch. `validatePat`
         * re-check stays BEFORE any network (IV7 fails closed as today).
         * `accountEndpointObserved` still derives solely from the account-leg
         * `fetchAccountQuota` result; observer args and cancel rethrow unchanged.
         */
        passport: NewApiSessionPassport? = null,
    ): StoredQuota = syncMutex.withLock {
        validatePat(providerId, accountId, pat)
        if (accountQuotaObserver != null) requireSafeAccountUsageRef(pat.value)
        val base = baseUrl(providerId)
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(base))
        val caps = if (passport != null && passport.matchesLeg(
                providerId = providerId,
                baseUrl = base,
                origin = origin,
                authRole = NewApiSessionAuthRole.ACCOUNT_PAT,
                pat = pat,
                accountId = accountId,
                hours = passport.hours,
            )) passport.caps else adapter.fetchCapabilities(base)
        val result = if (caps is NewApiResult.Success) adapter.fetchAccountQuota(base, pat, caps.value.version)
        else (caps as NewApiResult.Failure).let { it.copy(evidence = it.evidence.copy(source = ACCOUNT_SOURCE)) }
        val scope = accountQuotaScope(providerId, accountId)
        persistAccountQuota(scope, result)
        val stored = quota(scope, SnapshotKind.ACCOUNT_QUOTA)
        // The existing quota transaction and return value are independent of this leg.
        accountQuotaObserver?.let { observer ->
            try {
                observer.onObserved(AccountQuotaObservation(providerId, accountId, pat, base, caps, result))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Supplementary usage persistence must not fail quota/token enrichment.
            }
        }
        stored
    }

    suspend fun syncTokenQuota(
        adapter: NewApiAdapter, providerId: String, accountId: String, key: InferenceKeyRef,
        /**
         * CR-009 DRAFT: same consumption contract as the account leg. The
         * inference key never enters the passport (`InferenceKeyRef` stays in
         * this leg's resolution only); `validateRole` re-check unchanged.
         */
        passport: NewApiSessionPassport? = null,
    ): StoredQuota = syncMutex.withLock {
        validateRole(providerId, accountId, key.value, CredentialRole.INFERENCE)
        val base = baseUrl(providerId)
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(base))
        val caps = if (passport != null && passport.matchesLeg(
                providerId = providerId,
                baseUrl = base,
                origin = origin,
                authRole = NewApiSessionAuthRole.ACCOUNT_PAT,
                pat = passport.patRef,
                accountId = accountId,
                hours = passport.hours,
            )) passport.caps else adapter.fetchCapabilities(base)
        val result = if (caps is NewApiResult.Success) adapter.fetchTokenQuota(base, key, caps.value.version)
        else (caps as NewApiResult.Failure).let { it.copy(evidence = it.evidence.copy(source = TOKEN_SOURCE)) }
        val scope = tokenQuotaScope(providerId, accountId, key)
        persistTokenQuota(scope, result)
        quota(scope, SnapshotKind.TOKEN_QUOTA)
    }

    suspend fun price(scope: PricingScope, manual: Boolean = false): StoredPrice = db.withTransaction {
        val head = db.snapshotFactDao().head(scope.storageKey(),
            if (manual) SnapshotKind.MANUAL_PRICE.name else SnapshotKind.PRICE.name)
        StoredPrice(head?.let { db.snapshotFactDao().evidence(it.latestEvidenceId) },
            head?.lastGoodSnapshotId?.let { db.priceDao().getById(it)?.toDomain() })
    }

    suspend fun quota(scope: PricingScope, kind: SnapshotKind): StoredQuota = db.withTransaction {
        require(kind in setOf(SnapshotKind.ACCOUNT_QUOTA, SnapshotKind.TOKEN_QUOTA, SnapshotKind.MANUAL_QUOTA))
        val head = db.snapshotFactDao().head(scope.storageKey(), kind.name)
        StoredQuota(head?.let { db.snapshotFactDao().evidence(it.latestEvidenceId) },
            head?.lastGoodSnapshotId?.let { db.quotaDao().getById(it)?.toDomain() })
    }

    suspend fun history(scope: PricingScope, kind: SnapshotKind): List<SnapshotFactEntity> =
        db.snapshotFactDao().history(scope.storageKey(), kind.name)

    /**
     * 真實七維 scope 建構器：從本地 endpoints／offerings 組 model／group／
     * endpoint／operation，附 account／PAT role 校验。
     *
     * - provider／account／PAT 由 [validatePat] 校验（ACCOUNT role，account 歸屬）。
     * - endpoints／offerings 必須同 provider，且 offering.endpointId 必須落在
     *   endpoints 內；offerings 必須是 DB 已有列（[offeringDao.getById]），不捏造。
     * - model 來自 offerings.providerModelId；group／operation 來自官方 pricing
     *   catalog，經 [NewApiDomainMapper.mapPricing] 展開（`all`→usable、
     *   空 types→UNKNOWN），本層不重寫展開規則。
     * - 無官方數據的 offering 不產 scope（不捏造群組／operation）；官方空清單
     *  （authoritativeEmpty）回空 list（非 UNKNOWN 假 scope）。
     * - 產出一律 PAT 角色：credentialId 恆 null；operation 取自 mapper
     *  （EndpointType.name 或 UNKNOWN），不猜 `CHAT_COMPLETIONS`。
     */
    suspend fun buildPricingScopes(
        providerId: String,
        accountId: String,
        pat: AccountPatRef,
        pricing: NewApiPricing,
        endpoints: List<EndpointEntity>,
        offerings: List<OfferingEntity>,
    ): List<PricingScope> {
        validatePat(providerId, accountId, pat)
        require(endpoints.isNotEmpty()) { "Endpoints required" }
        require(offerings.isNotEmpty()) { "Offerings required" }
        require(endpoints.all { it.providerId == providerId }) { "Endpoint ownership mismatch" }
        require(offerings.all { it.providerId == providerId }) { "Offering ownership mismatch" }
        val endpointIds = endpoints.map { it.endpointId }.toSet()
        require(offerings.all { it.endpointId in endpointIds }) { "Offering endpoint mismatch" }
        for (offering in offerings) {
            val stored = db.offeringDao().getById(offering.id)
            require(stored != null && stored.providerId == providerId &&
                stored.endpointId == offering.endpointId &&
                stored.providerModelId == offering.providerModelId) {
                "Offering ownership mismatch"
            }
        }
        val dummyEndpoint = endpoints.first().endpointId
        val dummyScope = NewApiAccountScope(providerId, accountId, dummyEndpoint)
        val dummyEvidence = NewApiEvidence(
            PRICE_SOURCE, 0, NewApiVersion.RC25,
            NewApiVersionSupport.CERTIFIED_RC25, 200, pricing.pricingVersion,
        )
        val mapping = NewApiDomainMapper.mapPricing(
            NewApiResult.Success(dummyEvidence, pricing), null, dummyScope,
        )
        val mapped = mapping as? NewApiPricingMapping.Mapped ?: return emptyList()
        if (mapped.authoritativeEmpty) return emptyList()
        val out = LinkedHashSet<PricingScope>()
        for (offering in offerings) {
            val model = offering.providerModelId
            if (model.isBlank()) continue
            val candidates = mapped.snapshots.filter { it.scope.modelId == model }
            if (candidates.isEmpty()) continue
            for (candidate in candidates) {
                out += PricingScope(
                    providerId = providerId,
                    accountId = accountId,
                    credentialId = null,
                    modelId = model,
                    group = candidate.scope.group,
                    endpointId = offering.endpointId,
                    operation = candidate.scope.operation,
                )
            }
        }
        for (scope in out) validateScope(scope, pricing = true)
        return out.toList()
    }

    // internal 接縫供裝置測試注入 typed fixture；產品只能走上方同 session sync。
    // 數值唯一真相在 mapper：本函數只做 scope／endpoint／certified 驗證、
    // evidence 鏈與 head 維護，不做任何 USD 換算。
    internal suspend fun persistPricing(
        scope: PricingScope,
        capabilities: NewApiResult<NewApiCapabilities>,
        result: NewApiResult<NewApiPricing>,
    ) = db.withTransaction {
        validateScope(scope, pricing = true)
        val evidence = result.evidence()
        require(evidence.source == PRICE_SOURCE) { "Unexpected evidence source" }
        val catalog = (result as? NewApiResult.Success)?.value
        val caps = (capabilities as? NewApiResult.Success)?.value
        val statusEvidence = capabilities.evidence()
        val certified = evidence.certified() && statusEvidence.certified() &&
            statusEvidence.source == "newapi/api/status" && statusEvidence.observedAt <= evidence.observedAt &&
            caps?.version?.raw == evidence.providerVersion
        val endpoint = db.endpointDao().getById(scope.endpointId)
        require(endpoint != null) { "Endpoint not found" }
        // Operation-aware 認證：scope.operation 決定期望的官方 endpoint key 與
        // 本地 protocol；UNKNOWN／CUSTOM 永不認證，不拿他 operation 的 spec 冒充。
        val endpointOk = isEndpointCertified(
            scopeOperation = scope.operation,
            endpointProtocol = endpoint.protocol,
            endpointUrl = endpoint.endpointUrl,
            supported = catalog?.supportedEndpoint,
        )
        // 同 session Q，絕不跨 session、絕不預設 500000。
        val quotaPerUnit = NewApiDomainMapper.quotaPerUnitOf(capabilities)
        // null account／空 endpoint 僅為隔離測試的維度佔位：mapper 仍需合法 refs
        // 才可求 legs，legs 與 refs 無關（只與 catalog 數字有關），最終仍以原 scope 落庫隔離。
        val mapperAccountId = scope.accountId?.takeIf { it.isNotBlank() } ?: scope.providerId
        val mapperEndpointId = scope.endpointId.takeIf { it.isNotBlank() } ?: scope.providerId
        val mapperScope = NewApiAccountScope(scope.providerId, mapperAccountId, mapperEndpointId)
        val mapping = NewApiDomainMapper.mapPricing(result, quotaPerUnit, mapperScope)
        val mapped = mapping as? NewApiPricingMapping.Mapped
        var exactSnapshot: PriceSnapshot? = null
        var exactDetail: NewApiPriceDetail? = null
        var fallbackDetail: NewApiPriceDetail? = null
        if (mapped != null) {
            val exactIdx = mapped.snapshots.indexOfFirst {
                it.scope.modelId == scope.modelId &&
                    it.scope.group == scope.group &&
                    it.scope.operation == scope.operation
            }
            if (exactIdx >= 0) {
                exactSnapshot = mapped.snapshots[exactIdx]
                exactDetail = mapped.details.getOrNull(exactIdx)
                fallbackDetail = exactDetail
            } else {
                // `text` 為 CHAT_COMPLETIONS 舊別名（PricingPolicy 兩者皆支援），
                // mapper 只產 CHAT_COMPLETIONS：別名命中同組 legs，不另算。
                if (scope.operation == "text") {
                    val aliasIdx = mapped.snapshots.indexOfFirst {
                        it.scope.modelId == scope.modelId &&
                            it.scope.group == scope.group &&
                            it.scope.operation == "CHAT_COMPLETIONS"
                    }
                    if (aliasIdx >= 0) {
                        exactSnapshot = mapped.snapshots[aliasIdx]
                        exactDetail = mapped.details.getOrNull(aliasIdx)
                    }
                }
                // operation 不合仍保留同 model／group 的 groupRatio／Q 於 fact，
                // legs 仍 UNKNOWN（不以他 operation 腿冒充本次）。
                if (fallbackDetail == null) {
                    fallbackDetail = mapped.details.firstOrNull {
                        it.scope.modelId == scope.modelId && it.scope.group == scope.group
                    }
                }
            }
        }
        // tiered 永不求值：mapper 已回 null legs＋hash，本層直接沿用，不解析 expr。
        val canUseLegs = certified && endpointOk && exactSnapshot != null && exactDetail != null
        val id = newId()
        val snapshot = if (canUseLegs) {
            exactSnapshot!!.copy(
                id = id, scope = scope, observedAt = evidence.observedAt,
                source = PRICE_SOURCE, evidenceId = id,
            )
        } else {
            PriceSnapshot(
                id = id, scope = scope, observedAt = evidence.observedAt,
                source = PRICE_SOURCE, evidenceId = id,
            )
        }
        // 跨 module public property 不可 smart cast：先取本地 val。
        // fixed 快捷分類與 PricingPolicy 負價規則對齊（負價不當免費、不當付費）：
        // fixed-only 快照 token 腿恆 null 不能直接 evaluate，負 fixed 一律
        // UNKNOWN 且非 good（raw 快照照寫、prior last-good 不覆寫）。
        val fixed = snapshot.fixedRequest
        val state = if (fixed != null) {
            when {
                fixed.signum() < 0 -> PriceState.UNKNOWN
                fixed.signum() == 0 -> PriceState.FREE_NOW
                else -> PriceState.PAID
            }
        } else PricingPolicy.evaluate(snapshot).state
        val topVersion = mapped?.topPricingVersion ?: catalog?.pricingVersion ?: evidence.pricingVersion
        val factDetail = exactDetail ?: fallbackDetail
        val itemForUnit = catalog?.items?.singleOrNull { it.modelName == scope.modelId }
        val fact = fact(scope, SnapshotKind.PRICE, evidence, result.status(), state.name, id).copy(
            pricingVersion = safeVersion(topVersion),
            itemPricingVersion = safeVersion(factDetail?.itemPricingVersion),
            quotaPerUnit = (factDetail?.quotaPerUnitUsed ?: if (factDetail == null) quotaPerUnit else null)?.toPlainString(),
            groupRatio = factDetail?.groupRatioExact?.toPlainString(),
            statusObservedAt = statusEvidence.observedAt,
            expressionHash = factDetail?.billingExpressionHash,
            priceUnit = when {
                factDetail?.billingMode == "tiered_expr" -> "USD_PER_1M_EXPRESSION_UNKNOWN"
                factDetail?.quotaType == 1 -> "USD_PER_REQUEST"
                itemForUnit?.billingMode == "tiered_expr" -> "USD_PER_1M_EXPRESSION_UNKNOWN"
                itemForUnit?.quotaType == 1 -> "USD_PER_REQUEST"
                else -> "USD_PER_1K_TOKENS"
            },
        )
        db.priceDao().insert(snapshot.toEntity())
        record(scope, evidence, fact, state != PriceState.UNKNOWN)
    }

    // 帳號層級：state 與整數原值一律取自 mapper safe DTO，本層只做
    // scope／PAT role／certified 閘門與 evidence／head 維護。Unknown 維持 null，
    // 401／失敗不碰 last-good。
    internal suspend fun persistAccountQuota(scope: PricingScope, result: NewApiResult<NewApiAccountQuota>) = db.withTransaction {
        require(scope == accountQuotaScope(scope.providerId, requireNotNull(scope.accountId))) { "Invalid account quota scope" }
        validateScope(scope, pricing = false)
        val evidence = result.evidence()
        require(evidence.source == ACCOUNT_SOURCE) { "Unexpected evidence source" }
        val mapperEndpoint = scope.endpointId.takeIf { it.isNotBlank() } ?: scope.providerId
        val mapperScope = NewApiAccountScope(scope.providerId, requireNotNull(scope.accountId), mapperEndpoint)
        val mapping = NewApiDomainMapper.mapAccountQuota(result, mapperScope)
        val mapped = mapping as? NewApiAccountQuotaMapping.Mapped
        val detailForFact = mapped?.detail
        var state = mapped?.snapshot?.state ?: QuotaState.UNKNOWN
        if (!evidence.certified()) state = QuotaState.UNKNOWN
        val id = newId()
        val snapshot = QuotaSnapshot(id, scope, evidence.observedAt, state, evidence.source, id)
        db.quotaDao().insert(snapshot.toEntity())
        record(scope, evidence, fact(scope, SnapshotKind.ACCOUNT_QUOTA, evidence, result.status(), state.name, id).copy(
            remainingRaw = detailForFact?.quota,
            usedRaw = detailForFact?.usedQuota,
        ), state != QuotaState.UNKNOWN)
    }

    // Key 層級：直接沿用 adapter→mapper 的 state（過期／unlimited 語義已在
    // adapter 依研究 §3.10 推導，本層不重算；`expires_at==0` 保持 ambiguous）。
    // Uncertified／失敗一律 UNKNOWN 且不碰 last-good，整數原值仍取自 safe DTO。
    internal suspend fun persistTokenQuota(scope: PricingScope, result: NewApiResult<NewApiTokenQuota>) = db.withTransaction {
        require(scope == tokenQuotaScope(scope.providerId, requireNotNull(scope.accountId),
            InferenceKeyRef(requireNotNull(scope.credentialId)))) { "Invalid token quota scope" }
        validateScope(scope, pricing = false)
        val evidence = result.evidence()
        require(evidence.source == TOKEN_SOURCE) { "Unexpected evidence source" }
        val credentialId = requireNotNull(scope.credentialId)
        val mapperEndpoint = scope.endpointId.takeIf { it.isNotBlank() } ?: scope.providerId
        val mapperScope = NewApiKeyScope(scope.providerId, credentialId, mapperEndpoint)
        val mapping = NewApiDomainMapper.mapTokenQuota(result, mapperScope)
        val mapped = mapping as? NewApiTokenQuotaMapping.Mapped
        val detailForFact = mapped?.detail
        var state = mapped?.snapshot?.state ?: QuotaState.UNKNOWN
        if (!evidence.certified()) state = QuotaState.UNKNOWN
        val id = newId()
        val snapshot = QuotaSnapshot(id, scope, evidence.observedAt, state, evidence.source, id)
        db.quotaDao().insert(snapshot.toEntity())
        record(scope, evidence, fact(scope, SnapshotKind.TOKEN_QUOTA, evidence, result.status(), state.name, id).copy(
            remainingRaw = detailForFact?.totalAvailable,
            usedRaw = detailForFact?.totalUsed,
            grantedRaw = detailForFact?.totalGranted,
            unlimited = detailForFact?.unlimitedQuota,
            expiresAtRaw = detailForFact?.expiresAtRaw,
            expiryMeaning = detailForFact?.expiryMeaning?.name,
        ), state != QuotaState.UNKNOWN)
    }

    /** 手填只進 LOW 分槽，呼叫端 source/evidenceId 不得冒充官方。回傳新的不可變 snapshot id。 */
    suspend fun saveManualPrice(snapshot: PriceSnapshot): String = db.withTransaction {
        // F16：呼叫端 id（含預設 ""）一律忽略，統一 newId() 後覆寫；不設殘留前置。
        validateScope(snapshot.scope, pricing = true)
        val id = newId()
        val evidence = NewApiEvidence("USER", snapshot.observedAt)
        val safe = snapshot.copy(id = id, source = "USER", evidenceId = id)
        require(listOf(safe.input, safe.output, safe.fixedRequest, safe.cacheRead, safe.cacheWrite)
            .all { it == null || it.safeDecimal() != null }) { "Invalid manual decimal" }
        db.priceDao().insert(safe.toEntity())
        record(safe.scope, evidence, fact(safe.scope, SnapshotKind.MANUAL_PRICE, evidence, "OK", "UNKNOWN", id),
            safe.input != null || safe.output != null || safe.fixedRequest != null)
        id
    }

    suspend fun saveManualQuota(snapshot: QuotaSnapshot): String = db.withTransaction {
        // F16：同上，呼叫端 id 忽略，統一新 id。
        validateScope(snapshot.scope, pricing = false)
        val id = newId()
        val evidence = NewApiEvidence("USER", snapshot.observedAt)
        val safe = snapshot.copy(id = id, source = "USER", evidenceId = id)
        db.quotaDao().insert(safe.toEntity())
        record(safe.scope, evidence, fact(safe.scope, SnapshotKind.MANUAL_QUOTA, evidence, "OK", safe.state.name, id),
            safe.state != QuotaState.UNKNOWN)
        id
    }

    private suspend fun record(scope: PricingScope, evidence: NewApiEvidence, fact: SnapshotFactEntity, good: Boolean) {
        require(evidence.observedAt >= 0) { "Invalid observation time" }
        db.evidenceDao().insert(EvidenceEntity(fact.evidenceId, fact.evidenceId, scope.providerId, scope.modelId,
            fact.status, evidence.httpStatus, evidence.observedAt, evidence.source))
        db.snapshotFactDao().insert(fact)
        if (fact.quotaSlice.isNullOrEmpty()) {
            // existing body verbatim (head / upsertHead)
            val old = db.snapshotFactDao().head(fact.scopeKey, fact.kind)
            val latest = old == null || fact.observedAt > old.latestObservedAt
            val newerGood = good && (old?.lastGoodObservedAt == null || fact.observedAt > old.lastGoodObservedAt)
            db.snapshotFactDao().upsertHead(SnapshotHeadEntity(fact.scopeKey, fact.kind,
                if (latest) fact.evidenceId else old!!.latestEvidenceId,
                if (latest) fact.observedAt else old!!.latestObservedAt,
                if (newerGood) fact.snapshotId else old?.lastGoodSnapshotId,
                if (newerGood) fact.observedAt else old?.lastGoodObservedAt))
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

    private fun fact(scope: PricingScope, kind: SnapshotKind, e: NewApiEvidence, status: String, state: String, id: String) =
        SnapshotFactEntity(id, scope.storageKey(), kind.name, e.observedAt, id, state, status,
            if (kind == SnapshotKind.MANUAL_PRICE || kind == SnapshotKind.MANUAL_QUOTA) "LOW" else "OFFICIAL",
            safeVersion(e.providerVersion), safeVersion(e.pricingVersion), null, null, null, null,
            null, null, null, null, null, null, null, null)

    /**
     * Operation-aware endpoint 認證：`scope.operation` 決定期望的官方
     * `supported_endpoint` key（`text` 為 `CHAT_COMPLETIONS` 舊別名，走 `openai`）
     * 與本地 `protocol`；任一不合即 false（`UNKNOWN`／`CUSTOM` 永 false）。
     */
    private fun isEndpointCertified(
        scopeOperation: String,
        endpointProtocol: String,
        endpointUrl: String,
        supported: Map<String, NewApiSupportedEndpoint>?,
    ): Boolean {
        if (supported == null) return false
        val expectedKeys: List<String> = when (scopeOperation) {
            "CHAT_COMPLETIONS", "text" -> listOf("openai")
            "RESPONSES" -> listOf("openai-response", "openai-response-compact")
            "ANTHROPIC_MESSAGES" -> listOf("anthropic")
            "GEMINI_NATIVE" -> listOf("gemini")
            "IMAGES_GENERATION" -> listOf("image-generation")
            "EMBEDDINGS" -> listOf("embeddings")
            "RERANK" -> listOf("jina-rerank")
            else -> return false
        }
        val expectedProtocol = if (scopeOperation == "text") "CHAT_COMPLETIONS" else scopeOperation
        if (endpointProtocol != expectedProtocol) return false
        val path = try {
            EndpointPolicy.validate(endpointUrl).encodedPath
        } catch (_: Exception) {
            return false
        }
        return expectedKeys.any { key ->
            val spec = supported[key] ?: return@any false
            spec.method == "POST" && spec.path == path
        }
    }

    private suspend fun validateScope(scope: PricingScope, pricing: Boolean) {
        require(scope.providerId.isNotBlank() && scope.operation.isNotBlank()) { "Invalid scope" }
        require(db.providerDao().getById(scope.providerId) != null) { "Provider not found" }
        scope.accountId?.let {
            require(db.accountDao().getById(it)?.providerId == scope.providerId) { "Account ownership mismatch" }
        }
        scope.credentialId?.let {
            require(scope.accountId != null && db.credentialDao().getById(it)?.accountId == scope.accountId) {
                "Credential ownership mismatch"
            }
        }
        if (pricing || scope.endpointId.isNotEmpty()) {
            require(db.endpointDao().getById(scope.endpointId)?.providerId == scope.providerId) { "Endpoint ownership mismatch" }
        }
        if (pricing) require(scope.modelId.isNotBlank()) { "Missing model" }
    }

    private suspend fun validatePat(provider: String, account: String?, pat: AccountPatRef) =
        validateRole(provider, requireNotNull(account) { "Account required" }, pat.value, CredentialRole.ACCOUNT)

    private suspend fun validateRole(provider: String, account: String, ref: String, role: CredentialRole) {
        require(db.accountDao().getById(account)?.providerId == provider &&
            db.credentialDao().getById(ref)?.let { it.accountId == account && it.enabled } == true) { "Credential ownership mismatch" }
        val config = db.providerConfigDao().getByProvider(provider)?.toManualConfig()
        require(config?.credentials?.filter { it.safeCredentialId == ref }?.map { it.role }?.toSet() == setOf(role)) {
            "Credential role unavailable"
        }
    }

    private suspend fun baseUrl(provider: String): String {
        val endpoints = db.endpointDao().byProvider(provider)
        require(endpoints.isNotEmpty()) { "Endpoint required" }
        require(endpoints.map { EndpointPolicy.origin(EndpointPolicy.validate(it.baseUrl)) }.distinct().size == 1) {
            "Provider origin mismatch"
        }
        return endpoints.first().baseUrl
    }

    companion object {
        const val PRICE_SOURCE = "newapi/api/pricing"
        const val ACCOUNT_SOURCE = "newapi/api/user/self"
        const val TOKEN_SOURCE = "newapi/api/usage/token"

        fun accountQuotaScope(providerId: String, accountId: String) =
            PricingScope(providerId, accountId, null, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL)

        fun tokenQuotaScope(providerId: String, accountId: String, key: InferenceKeyRef) =
            PricingScope(providerId, accountId, key.value, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL)
    }
}

private fun NewApiResult<*>.evidence(): NewApiEvidence = when (this) {
    is NewApiResult.Success -> evidence
    is NewApiResult.Failure -> evidence
}
private fun NewApiResult<*>.status(): String = when (this) {
    is NewApiResult.Success -> "OK"
    is NewApiResult.Failure -> status.name
}
private fun NewApiEvidence.certified(): Boolean = providerVersion == NewApiVersion.RC25 &&
    versionSupport == NewApiVersionSupport.CERTIFIED_RC25 && httpStatus in 200..299

private fun BigDecimal?.safeDecimal(): BigDecimal? = this?.takeIf {
    it.signum() >= 0 && it.precision() <= 128 && it.scale().toLong() in -128L..128L
}
private fun safeVersion(value: String?): String? = value?.takeIf {
    it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._-]+")) && !it.startsWith("sk-")
}
private fun newId(): String = "fact:" + UUID.randomUUID()
