package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.newapi.*
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 僅 typed fixture / 本地 Room；不連線、不承載 secret、不當產品資料。 */
@RunWith(AndroidJUnit4::class)
class NewApiSnapshotStoreTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: NewApiSnapshotStore
    private val scope = PricingScope("p", "a", "c", "model", "default", "e", "CHAT_COMPLETIONS")

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = NewApiSnapshotStore(db)
        for (provider in listOf("p", "p2")) {
            db.providerDao().insert(ProviderEntity(provider, "測試供應者"))
        }
        for ((account, provider) in listOf("a" to "p", "a2" to "p", "a3" to "p2")) {
            db.accountDao().insert(AccountEntity(account, provider))
        }
        for ((credential, account) in listOf("c" to "a", "c2" to "a", "c3" to "a2", "c4" to "a3")) {
            db.credentialDao().insert(CredentialEntity(credential, account))
        }
        for ((endpoint, provider) in listOf("e" to "p", "e2" to "p", "e3" to "p2")) {
            db.endpointDao().insert(EndpointEntity(endpoint, provider,
                "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"))
        }
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun sevenDimensionsRemainIsolated_includingUnsupportedOperation() = runBlocking {
        // provider/account 的所有權關係須合法；null account/credential 可獨立驗證維度。
        val scopes = listOf(
            scope,
            scope.copy(credentialId = "c2"),
            scope.copy(credentialId = null),
            scope.copy(accountId = "a2", credentialId = null),
            scope.copy(accountId = null, credentialId = null),
            scope.copy(providerId = "p2", accountId = null, credentialId = null, endpointId = "e3"),
            scope.copy(modelId = "model2"),
            scope.copy(group = "other"),
            scope.copy(endpointId = "e2"),
            scope.copy(operation = "text"),
            scope.copy(operation = "EMBEDDINGS"),
        )
        for ((index, candidate) in scopes.withIndex()) {
            assertNull(store.price(candidate).latest)
            persist(candidate, 1000L + index, (index + 1).toString())
            if (index == 0) {
                // 單獨改 provider/account 的唯讀 key 探針：不能靠一併改 endpoint/key 才通過隔離。
                assertNull(store.price(scope.copy(providerId = "p2")).latest)
                assertNull(store.price(scope.copy(accountId = "a2")).latest)
            }
        }
        val before = scopes.associateWith { store.price(it) }
        assertEquals(scopes.size, before.values.map { it.latest!!.evidenceId }.toSet().size)
        for ((index, candidate) in scopes.withIndex()) {
            val stored = before.getValue(candidate)
            assertEquals(1000L + index, stored.latest!!.observedAt)
            assertEquals(1, store.history(candidate, SnapshotKind.PRICE).size)
            if (candidate.operation == "EMBEDDINGS") {
                assertEquals("UNKNOWN", stored.latest!!.state)
                assertNull(stored.lastGood)
                val raw = db.priceDao().getById(stored.latest!!.snapshotId!!)!!
                assertEquals("EMBEDDINGS", raw.operation)
                assertNull(raw.input)
                assertNull(raw.output)
            } else {
                assertEquals(candidate, stored.lastGood!!.scope)
                decimal(BigDecimal(index + 1).multiply(BigDecimal("0.003")).toPlainString(), stored.lastGood!!.input)
            }
        }
        persist(scope, 2000, "99")
        for (candidate in scopes.drop(1)) assertEquals(before[candidate], store.price(candidate))
        assertEquals(scopes.size.toLong(), count("snapshot_heads"))
    }

    @Test
    fun sameScopeUpsertsHead_keepsImmutableHistoryAndEvidence() = runBlocking {
        persist(scope, 1000, "2")
        val first = store.price(scope)
        persist(scope, 2000, "3")
        val second = store.price(scope)
        assertNotEquals(first.lastGood!!.id, second.lastGood!!.id)
        decimal("0.006", db.priceDao().getById(first.lastGood!!.id)!!.toDomain().input)
        decimal("0.009", second.lastGood!!.input)
        assertEquals(1L, count("snapshot_heads"))
        assertEquals(2L, count("price_snapshots"))
        val history = store.history(scope, SnapshotKind.PRICE)
        assertEquals(listOf(2000L, 1000L), history.map { it.observedAt })
        assertEquals(setOf(first.latest!!.evidenceId, second.latest!!.evidenceId), history.map { it.evidenceId }.toSet())
        for (fact in history) {
            val evidence = db.evidenceDao().getById(fact.evidenceId)!!
            assertEquals(NewApiSnapshotStore.PRICE_SOURCE, evidence.source)
            assertEquals(fact.observedAt, evidence.timestamp)
            assertEquals(200, evidence.status)
            assertEquals(scope.providerId, evidence.providerId)
            assertEquals(scope.modelId, evidence.modelId)
            assertEquals(NewApiVersion.RC25, fact.providerVersion)
            assertEquals("catalog-v1", fact.pricingVersion)
            assertEquals("item-v1", fact.itemPricingVersion)
            assertEquals(fact.observedAt - 1, fact.statusObservedAt)
            assertEquals("500000", fact.quotaPerUnit)
            assertEquals("1.5", fact.groupRatio)
            assertEquals("OFFICIAL", fact.confidence)
        }
    }

    @Test
    fun nullAndEmptyGroupHaveIndependentHeadsAndHistory() = runBlocking {
        val missing = scope.copy(group = null)
        val empty = scope.copy(group = "")
        persist(missing, 1000)
        persist(empty, 2000)
        assertEquals("UNKNOWN", store.price(missing).latest!!.state)
        assertNull(store.price(missing).lastGood)
        assertEquals("PAID", store.price(empty).latest!!.state)
        assertEquals("", store.price(empty).lastGood!!.scope.group)
        persist(missing, 3000)
        assertEquals(listOf(3000L, 1000L), store.history(missing, SnapshotKind.PRICE).map { it.observedAt })
        assertEquals(listOf(2000L), store.history(empty, SnapshotKind.PRICE).map { it.observedAt })
        assertEquals(2L, count("snapshot_heads"))
    }

    @Test
    fun unauthorizedUnknownAndEmptyPricingRetainLastGoodWithoutClaimingCurrentSuccess() = runBlocking {
        persist(scope, 1000)
        val good = store.price(scope).lastGood!!
        val results = listOf(
            NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED, evidence(2000).copy(httpStatus = 401)),
            NewApiResult.Success(evidence(3000).copy(providerVersion = "v9.0.0", versionSupport = NewApiVersionSupport.UNKNOWN), catalog()),
            NewApiResult.Success(evidence(4000), catalog().copy(items = emptyList())),
        )
        for ((index, result) in results.withIndex()) {
            val time = 2000L + index * 1000
            store.persistPricing(scope, capabilities(time), result)
            val stored = store.price(scope)
            assertEquals(good, stored.lastGood)
            assertEquals(time, stored.latest!!.observedAt)
            assertEquals("UNKNOWN", stored.latest!!.state)
            val raw = db.priceDao().getById(stored.latest!!.snapshotId!!)!!
            assertNull(raw.input)
            assertNull(raw.output)
            assertEquals(if (index == 0) "HTTP_UNAUTHORIZED" else "OK", stored.latest!!.status)
        }
        assertEquals(4, store.history(scope, SnapshotKind.PRICE).size)
        assertEquals(4L, count("evidence"))
        val failed = store.history(scope, SnapshotKind.PRICE).single { it.observedAt == 2000L }
        assertEquals(401, db.evidenceDao().getById(failed.evidenceId)!!.status)
    }

    @Test
    fun exactTokenAndFixedRequestPricesUseDifferentUnits() = runBlocking {
        persist(scope, 1000, "0.1234567890123456789")
        val token = store.price(scope)
        decimal("0.0003703703670370370367", token.lastGood!!.input)
        decimal("0.00092592591759259259175", token.lastGood!!.output)
        decimal("0.000092592591759259259175", token.lastGood!!.cacheRead)
        assertTrue(token.lastGood!!.fixedRequestKnownAbsent)
        assertNull(token.lastGood!!.fixedRequest)
        assertEquals("USD_PER_1K_TOKENS", token.latest!!.priceUnit)
        val fixedScope = scope.copy(modelId = "fixed")
        val fixed = item("fixed").copy(quotaType = 1, modelPrice = BigDecimal("0.1234567890123456789"))
        store.persistPricing(fixedScope, capabilities(2000), NewApiResult.Success(evidence(2000), catalog().copy(items = listOf(fixed))))
        val stored = store.price(fixedScope)
        decimal("0.18518518351851851835", stored.lastGood!!.fixedRequest)
        assertNull(stored.lastGood!!.input)
        assertNull(stored.lastGood!!.output)
        assertFalse(stored.lastGood!!.fixedRequestKnownAbsent)
        assertEquals("PAID", stored.latest!!.state)
        assertEquals("USD_PER_REQUEST", stored.latest!!.priceUnit)
    }

    @Test
    fun endpointCertificationRequiresProtocolExactPathAndPost() = runBlocking {
        db.endpointDao().insert(EndpointEntity("responses", "p", "https://fixture.invalid/v1",
            "https://fixture.invalid/v1/chat/completions", "RESPONSES"))
        val cases = listOf(
            scope.copy(endpointId = "responses") to catalog(),
            scope.copy(modelId = "wrong-path") to catalog("wrong-path").copy(
                supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions/", "POST"))),
            scope.copy(modelId = "wrong-method") to catalog("wrong-method").copy(
                supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "GET"))),
        )
        for ((candidate, catalog) in cases) {
            store.persistPricing(candidate, capabilities(1000), NewApiResult.Success(evidence(1000), catalog))
            assertEquals("UNKNOWN", store.price(candidate).latest!!.state)
            assertNull(store.price(candidate).lastGood)
        }
        persist(scope, 2000)
        assertEquals("PAID", store.price(scope).latest!!.state)
    }

    @Test
    fun manualLowPriceAndQuotaCannotReplaceOfficialFactsOrForgeSource() = runBlocking {
        persist(scope, 1000)
        val official = store.price(scope)
        val manualId = store.saveManualPrice(PriceSnapshot(
            id = "caller-price", scope = scope, observedAt = 2000, input = BigDecimal.ZERO,
            output = BigDecimal.ZERO, fixedRequestKnownAbsent = true,
            source = NewApiSnapshotStore.PRICE_SOURCE, evidenceId = official.latest!!.evidenceId,
        ))
        assertEquals(official, store.price(scope))
        val manual = store.price(scope, manual = true)
        assertEquals(manualId, manual.lastGood!!.id)
        assertNotEquals("caller-price", manualId)
        assertEquals("LOW", manual.latest!!.confidence)
        assertEquals("USER", manual.lastGood!!.source)
        assertEquals("USER", db.evidenceDao().getById(manual.latest!!.evidenceId)!!.source)
        val account = NewApiSnapshotStore.accountQuotaScope("p", "a")
        store.persistAccountQuota(account, accountResult(1000, 123))
        val officialQuota = store.quota(account, SnapshotKind.ACCOUNT_QUOTA)
        store.saveManualQuota(QuotaSnapshot("caller-quota", account, 2000, QuotaState.UNLIMITED_CLAIMED,
            NewApiSnapshotStore.ACCOUNT_SOURCE, officialQuota.latest!!.evidenceId))
        assertEquals(officialQuota, store.quota(account, SnapshotKind.ACCOUNT_QUOTA))
        val manualQuota = store.quota(account, SnapshotKind.MANUAL_QUOTA)
        assertEquals("LOW", manualQuota.latest!!.confidence)
        assertEquals("USER", manualQuota.lastGood!!.source)
        assertEquals(1, store.history(scope, SnapshotKind.MANUAL_PRICE).size)
        assertEquals(1, store.history(account, SnapshotKind.MANUAL_QUOTA).size)
    }

    @Test
    fun accountQuotaUsesNullCredential_tokenQuotasStaySeparateAndPreserveLongPrecision() = runBlocking {
        val account = NewApiSnapshotStore.accountQuotaScope("p", "a")
        val token1 = NewApiSnapshotStore.tokenQuotaScope("p", "a", InferenceKeyRef("c"))
        val token2 = NewApiSnapshotStore.tokenQuotaScope("p", "a", InferenceKeyRef("c2"))
        val exact = 9_007_199_254_740_993L // 大於 Double 可精確保存的連續整數範圍。
        store.persistAccountQuota(account, accountResult(1000, exact))
        store.persistTokenQuota(token1, tokenResult(2000, Long.MAX_VALUE))
        store.persistTokenQuota(token2, tokenResult(3000, exact + 2))
        val accountStored = store.quota(account, SnapshotKind.ACCOUNT_QUOTA)
        assertNull(accountStored.lastGood!!.scope.credentialId)
        assertEquals(exact, accountStored.latest!!.remainingRaw)
        assertEquals(exact - 1, accountStored.latest!!.usedRaw)
        for ((candidate, amount) in listOf(token1 to Long.MAX_VALUE, token2 to exact + 2)) {
            val stored = store.quota(candidate, SnapshotKind.TOKEN_QUOTA)
            assertEquals(candidate, stored.lastGood!!.scope)
            assertEquals(amount, stored.latest!!.remainingRaw)
            assertEquals(amount - 1, stored.latest!!.usedRaw)
            assertEquals(Long.MAX_VALUE, stored.latest!!.grantedRaw)
            assertEquals(false, stored.latest!!.unlimited)
            assertEquals(0L, stored.latest!!.expiresAtRaw)
            assertEquals("ZERO_AMBIGUOUS", stored.latest!!.expiryMeaning)
            assertEquals(NewApiSnapshotStore.TOKEN_SOURCE, db.evidenceDao().getById(stored.latest!!.evidenceId)!!.source)
        }
        assertNull(store.quota(account, SnapshotKind.TOKEN_QUOTA).latest)
        assertNull(store.quota(token1, SnapshotKind.ACCOUNT_QUOTA).latest)
        store.persistAccountQuota(account, accountResult(4000, null))
        store.persistTokenQuota(token1, tokenResult(4000, null))
        assertEquals("UNKNOWN", store.quota(account, SnapshotKind.ACCOUNT_QUOTA).latest!!.state)
        assertEquals(accountStored.lastGood, store.quota(account, SnapshotKind.ACCOUNT_QUOTA).lastGood)
        assertEquals("UNKNOWN", store.quota(token1, SnapshotKind.TOKEN_QUOTA).latest!!.state)
        assertEquals(2000L, store.quota(token1, SnapshotKind.TOKEN_QUOTA).lastGood!!.observedAt)
        assertEquals(3000L, store.quota(token2, SnapshotKind.TOKEN_QUOTA).latest!!.observedAt)
        assertEquals(3L, count("snapshot_heads"))
    }

    @Test
    fun olderObservationsCannotRollBackLatestOrLastGood_butRemainInHistory() = runBlocking {
        persist(scope, 3000, "3")
        val newest = store.price(scope)
        persist(scope, 1000, "1")
        store.persistPricing(scope, capabilities(2000), NewApiResult.Failure(
            NewApiStatus.HTTP_UNAUTHORIZED, evidence(2000).copy(httpStatus = 401)))
        assertEquals(newest, store.price(scope))
        assertEquals(listOf(3000L, 2000L, 1000L), store.history(scope, SnapshotKind.PRICE).map { it.observedAt })
        // 最新是失敗時，稍晚抵達的歷史成功只能補 last-good，不能改最新失敗。
        store.persistPricing(scope, capabilities(5000), NewApiResult.Failure(
            NewApiStatus.UNKNOWN, evidence(5000)))
        persist(scope, 4000, "4")
        assertEquals(5000L, store.price(scope).latest!!.observedAt)
        assertEquals("UNKNOWN", store.price(scope).latest!!.state)
        assertEquals(4000L, store.price(scope).lastGood!!.observedAt)
        val account = NewApiSnapshotStore.accountQuotaScope("p", "a")
        val token = NewApiSnapshotStore.tokenQuotaScope("p", "a", InferenceKeyRef("c"))
        store.persistAccountQuota(account, accountResult(3000, 30))
        store.persistTokenQuota(token, tokenResult(3000, 40))
        val accountNewest = store.quota(account, SnapshotKind.ACCOUNT_QUOTA)
        val tokenNewest = store.quota(token, SnapshotKind.TOKEN_QUOTA)
        store.persistAccountQuota(account, accountResult(1000, 0))
        store.persistTokenQuota(token, tokenResult(1000, 0))
        assertEquals(accountNewest, store.quota(account, SnapshotKind.ACCOUNT_QUOTA))
        assertEquals(tokenNewest, store.quota(token, SnapshotKind.TOKEN_QUOTA))
        assertEquals(listOf(3000L, 1000L), store.history(account, SnapshotKind.ACCOUNT_QUOTA).map { it.observedAt })
        assertEquals(listOf(3000L, 1000L), store.history(token, SnapshotKind.TOKEN_QUOTA).map { it.observedAt })
    }

    @Test
    fun quotaUnauthorizedKeepsLastGoodAndFailureEvidenceForEachKind() = runBlocking {
        val account = NewApiSnapshotStore.accountQuotaScope("p", "a")
        val token = NewApiSnapshotStore.tokenQuotaScope("p", "a", InferenceKeyRef("c"))
        store.persistAccountQuota(account, accountResult(1000, 10))
        store.persistTokenQuota(token, tokenResult(1000, 20))
        val beforeAccount = store.quota(account, SnapshotKind.ACCOUNT_QUOTA).lastGood
        val beforeToken = store.quota(token, SnapshotKind.TOKEN_QUOTA).lastGood
        store.persistAccountQuota(account, NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED,
            evidence(2000, NewApiSnapshotStore.ACCOUNT_SOURCE).copy(httpStatus = 401)))
        store.persistTokenQuota(token, NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED,
            evidence(2000, NewApiSnapshotStore.TOKEN_SOURCE).copy(httpStatus = 401)))
        assertEquals(beforeAccount, store.quota(account, SnapshotKind.ACCOUNT_QUOTA).lastGood)
        assertEquals(beforeToken, store.quota(token, SnapshotKind.TOKEN_QUOTA).lastGood)
        for ((candidate, kind) in listOf(account to SnapshotKind.ACCOUNT_QUOTA, token to SnapshotKind.TOKEN_QUOTA)) {
            val latest = store.quota(candidate, kind).latest!!
            assertEquals("UNKNOWN", latest.state)
            assertEquals("HTTP_UNAUTHORIZED", latest.status)
            assertNull(latest.remainingRaw)
            val evidence = db.evidenceDao().getById(latest.evidenceId)!!
            assertEquals(401, evidence.status)
            assertEquals(2000L, evidence.timestamp)
            assertEquals(listOf(2000L, 1000L), store.history(candidate, kind).map { it.observedAt })
        }
    }

    @Test
    fun tieredExprIsNeverEvaluatedAndKeepsLastGood() = runBlocking {
        persist(scope, 1000, "2")
        val good = store.price(scope).lastGood!!
        decimal("0.006", good.input)
        val tiered = item(scope.modelId).copy(
            quotaType = 0,
            billingMode = "tiered_expr",
            billingExpression = "1+1",
        )
        val tieredCatalog = catalog().copy(items = listOf(tiered))
        store.persistPricing(scope, capabilities(2000), NewApiResult.Success(evidence(2000), tieredCatalog))
        val stored = store.price(scope)
        // 401／失敗語義：不碰 last-good，最新為 UNKNOWN 且腿全 null。
        assertEquals(good, stored.lastGood)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertEquals(2000L, stored.latest!!.observedAt)
        val raw = db.priceDao().getById(stored.latest!!.snapshotId!!)!!.toDomain()
        assertNull(raw.input)
        assertNull(raw.output)
        assertNull(raw.fixedRequest)
        assertFalse(raw.fixedRequestKnownAbsent)
        // safe DTO：priceUnit 標記 expression 未知，hash 保留供變更偵測，絕不求值。
        assertEquals("USD_PER_1M_EXPRESSION_UNKNOWN", stored.latest!!.priceUnit)
        val hash = stored.latest!!.expressionHash
        assertNotNull(hash)
        assertEquals(64, hash!!.length)
        assertTrue(hash.matches(Regex("[0-9a-f]+")))
        assertEquals(2, store.history(scope, SnapshotKind.PRICE).size)
    }

    @Test
    fun persistPricingUsesMapperSafeDtoForFactFields() = runBlocking {
        persist(scope, 1000, "2")
        val stored = store.price(scope)
        // 不足欄位取自 mapper safe DTO：雙 pricingVersion、Q、groupRatio、hash、unit。
        assertEquals("catalog-v1", stored.latest!!.pricingVersion)
        assertEquals("item-v1", stored.latest!!.itemPricingVersion)
        assertEquals("500000", stored.latest!!.quotaPerUnit)
        assertEquals("1.5", stored.latest!!.groupRatio)
        assertEquals(999L, stored.latest!!.statusObservedAt)
        assertEquals("USD_PER_1K_TOKENS", stored.latest!!.priceUnit)
        // Unknown 維持 null，不以 0 冒充。
        val raw = db.priceDao().getById(stored.latest!!.snapshotId!!)!!.toDomain()
        assertNotNull(raw.input)
        assertNotNull(raw.output)
    }

    @Test
    fun buildPricingScopesFromEndpointsOfferingsWithRoleChecks() = runBlocking {
        val patRef = "pat-a"
        if (db.credentialDao().getById(patRef) == null) {
            db.credentialDao().insert(CredentialEntity(patRef, "a"))
        }
        val manualConfig = ProviderManualConfig(
            providerId = "p",
            workflowId = "wf-p-builder",
            authMode = AuthMode.BEARER,
            credentials = listOf(
                SafeCredentialRef(patRef, "帳號 PAT", CredentialRole.ACCOUNT),
                SafeCredentialRef("c", "推理憑證", CredentialRole.INFERENCE),
            ),
        )
        db.providerConfigDao().insert(manualConfig.toEntity())
        db.offeringDao().insert(OfferingEntity("off-1", "p", "e", "model"))
        db.offeringDao().insert(OfferingEntity("off-2", "p", "e2", "model2"))
        val pricing = NewApiPricing(
            items = listOf(item("model"), item("model2")),
            skippedItems = 0,
            groupRatio = mapOf("default" to NewApiRatio(BigDecimal("1"), false)),
            usableGroups = listOf("default"),
            supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
            autoGroups = emptyList(),
            pricingVersion = "catalog-v1",
        )
        val endpoints = listOf(
            db.endpointDao().getById("e")!!,
            db.endpointDao().getById("e2")!!,
        )
        val offerings = listOf(
            db.offeringDao().getById("off-1")!!,
            db.offeringDao().getById("off-2")!!,
        )
        val scopes = store.buildPricingScopes("p", "a", AccountPatRef(patRef), pricing, endpoints, offerings)
        assertEquals(2, scopes.size)
        val byModel = scopes.associateBy { it.modelId }
        assertEquals("e", byModel.getValue("model").endpointId)
        assertEquals("e2", byModel.getValue("model2").endpointId)
        for (s in scopes) {
            assertEquals("p", s.providerId)
            assertEquals("a", s.accountId)
            assertNull(s.credentialId)
            assertEquals("default", s.group)
            assertEquals("CHAT_COMPLETIONS", s.operation)
        }
        assertTrue(scopes.none { it.group == "all" })
        // role 校验：INFERENCE key 不可當 PAT。
        try {
            store.buildPricingScopes("p", "a", AccountPatRef("c"), pricing, endpoints, offerings)
            fail("INFERENCE key must not pass PAT role check")
        } catch (_: IllegalArgumentException) {
        }
        // account 歸屬 mismatch。
        try {
            store.buildPricingScopes("p", "a2", AccountPatRef(patRef), pricing, endpoints, offerings)
            fail("Account ownership mismatch must fail")
        } catch (_: IllegalArgumentException) {
        }
        // offering endpoint 不在 endpoints 內。
        try {
            store.buildPricingScopes(
                "p", "a", AccountPatRef(patRef), pricing, endpoints,
                listOf(OfferingEntity("off-x", "p", "missing-e", "model")),
            )
            fail("Offering endpoint mismatch must fail")
        } catch (_: IllegalArgumentException) {
        }
        // 官方空清單不捏造 scope。
        val empty = store.buildPricingScopes(
            "p", "a", AccountPatRef(patRef), pricing.copy(items = emptyList()),
            endpoints, offerings,
        )
        assertTrue(empty.isEmpty())
        // 無官方數據的 offering 不產 scope。
        db.offeringDao().insert(OfferingEntity("off-3", "p", "e", "ghost-model"))
        val withGhost = store.buildPricingScopes(
            "p", "a", AccountPatRef(patRef), pricing, endpoints,
            offerings + db.offeringDao().getById("off-3")!!,
        )
        assertEquals(2, withGhost.size)
        assertTrue(withGhost.none { it.modelId == "ghost-model" })
    }

    private suspend fun persist(candidate: PricingScope, time: Long, ratio: String = "2") {
        store.persistPricing(candidate, capabilities(time), NewApiResult.Success(evidence(time), catalog(candidate.modelId, ratio)))
    }

    private fun evidence(time: Long, source: String = NewApiSnapshotStore.PRICE_SOURCE) = NewApiEvidence(
        source, time, NewApiVersion.RC25, NewApiVersionSupport.CERTIFIED_RC25, 200, "catalog-v1",
    )

    private fun capabilities(time: Long): NewApiResult<NewApiCapabilities> {
        val version = NewApiVersion.of(NewApiVersion.RC25)
        return NewApiResult.Success(evidence(time - 1, "newapi/api/status"), NewApiCapabilities(
            version, BigDecimal("500000"), "USD", false, NewApiModuleAccess(true, true), version.certifiedEndpoints,
        ))
    }

    private fun catalog(model: String = "model", ratio: String = "2") = NewApiPricing(
        items = listOf(item(model).copy(modelRatio = BigDecimal(ratio))), skippedItems = 0,
        groupRatio = listOf("default", "other", "").associateWith { NewApiRatio(BigDecimal("1.5"), false) },
        usableGroups = listOf("default", "other", ""),
        supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
        autoGroups = emptyList(), pricingVersion = "catalog-v1",
    )

    private fun item(model: String) = NewApiPricingItem(
        modelName = model, quotaType = 0, modelRatio = BigDecimal("2"), modelPrice = null,
        completionRatio = BigDecimal("2.5"), cacheRatio = BigDecimal("0.25"), createCacheRatio = BigDecimal("1.25"),
        imageRatio = null, audioRatio = null, audioCompletionRatio = null, enableGroups = listOf("all"),
        supportedEndpointTypes = listOf("openai"), billingMode = null, billingExpression = null,
        vendorId = null, pricingVersion = "item-v1",
    )

    private fun accountResult(time: Long, amount: Long?) = NewApiResult.Success(
        evidence(time, NewApiSnapshotStore.ACCOUNT_SOURCE),
        NewApiAccountQuota(amount, amount?.minus(1), null, null, null, null),
    )

    private fun tokenResult(time: Long, amount: Long?): NewApiResult.Success<NewApiTokenQuota> {
        // 與 adapter §3.10 一致的便利 state：缺 available 即 UNKNOWN，不以 AVAILABLE 冒充。
        val state = when {
            amount == null -> QuotaState.UNKNOWN
            amount <= 0L -> QuotaState.QUOTA_EXHAUSTED
            else -> QuotaState.QUOTA_AVAILABLE
        }
        return NewApiResult.Success(
            evidence(time, NewApiSnapshotStore.TOKEN_SOURCE),
            NewApiTokenQuota(Long.MAX_VALUE, amount?.minus(1), amount, false, null, 0,
                NewApiExpiryMeaning.ZERO_AMBIGUOUS, null, state),
        )
    }

    private fun decimal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual!!))
    }

    private fun count(table: String): Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }
}
