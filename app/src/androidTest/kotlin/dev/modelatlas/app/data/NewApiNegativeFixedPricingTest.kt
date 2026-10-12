package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiPricing
import dev.modelatlas.core.newapi.NewApiPricingItem
import dev.modelatlas.core.newapi.NewApiRatio
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiSupportedEndpoint
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
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

/**
 * CR-007 / E1 (PRODUCT_SPEC §10/18; Intelligence Contract §6.3/7).
 * Fixed-only negative pricing is UNKNOWN/non-good, while its exact raw decimal,
 * immutable facts and prior last-good survive. Zero and positive fixed controls
 * have null token/cache legs, not fabricated zero token prices.
 *
 * Typed fixtures enter the real store and in-memory Room. The consumer vector
 * uses public buildPricingScopes -> syncPricingSession -> enrichMyModelBundle
 * with an exact concrete group. It does not claim that a default-group head is
 * reachable through the connection reader's null-group policy.
 * No adapters, network, secrets or automatic inference; fixtures are test-only.
 */
@RunWith(AndroidJUnit4::class)
class NewApiNegativeFixedPricingTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: NewApiSnapshotStore

    private val scope = PricingScope("np", "na", null, "neg-model", "default", "ne", "CHAT_COMPLETIONS")

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = NewApiSnapshotStore(db)
        db.providerDao().insert(ProviderEntity("np", "Negative fixed fixture"))
        db.accountDao().insert(AccountEntity("na", "np"))
        db.credentialDao().insert(CredentialEntity("nc", "na", label = "Account PAT", authMode = "PAT_ACCOUNT"))
        db.credentialDao().insert(CredentialEntity("ni", "na", label = "Inference ref", authMode = "BEARER"))
        db.endpointDao().insert(EndpointEntity(
            "ne", "np", "https://neg.fixture.invalid/v1", "https://neg.fixture.invalid/v1/chat/completions",
        ))
        db.providerConfigDao().insert(ProviderManualConfig(
            providerId = "np",
            workflowId = "wf-np-neg",
            authMode = AuthMode.BEARER,
            credentials = listOf(
                SafeCredentialRef("nc", "Account PAT", CredentialRole.ACCOUNT),
                SafeCredentialRef("ni", "Inference ref", CredentialRole.INFERENCE),
            ),
        ).toEntity())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun negativeFixedIsUnknownRawPreservedAndPriorLastGoodUnchanged() = runBlocking {
        persistFixed(scope, 1000, POSITIVE_PRICE)
        val good = store.price(scope)
        val goodFact = good.latest!!
        val goodRaw = raw(good)
        val goodEvidence = db.evidenceDao().getById(goodFact.evidenceId)!!
        assertEquals("PAID", goodFact.state)
        decimal(POSITIVE_FIXED, good.lastGood!!.fixedRequest)

        persistFixed(scope, 2000, "-0.1")
        val negative = store.price(scope)
        val fact = negative.latest!!
        assertEquals("UNKNOWN", fact.state)
        assertEquals("OK", fact.status)
        assertEquals(2000L, fact.observedAt)
        assertEquals(good.lastGood, negative.lastGood)
        assertEquals(goodRaw, db.priceDao().getById(goodRaw.id))
        assertEquals(goodFact, db.snapshotFactDao().evidence(goodFact.evidenceId))
        assertEquals(goodEvidence, db.evidenceDao().getById(goodEvidence.id))
        assertNegativeRaw(scope, negative)
        assertEquals(listOf(fact, goodFact), store.history(scope, SnapshotKind.PRICE))
        assertEquals(2L, count("price_snapshots"))
        assertEquals(2L, count("snapshot_facts"))
        assertEquals(2L, count("evidence"))
        assertEquals(1L, count("snapshot_heads"))
        val head = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
        assertEquals(fact.evidenceId, head.latestEvidenceId)
        assertEquals(2000L, head.latestObservedAt)
        assertEquals(goodRaw.id, head.lastGoodSnapshotId)
        assertEquals(1000L, head.lastGoodObservedAt)
        assertEquals("USD_PER_REQUEST", fact.priceUnit)
        assertEquals("1.5", fact.groupRatio)
        assertEquals("OFFICIAL", fact.confidence)
        assertEquals(NewApiVersion.RC25, fact.providerVersion)
        assertEquals("catalog-v1", fact.pricingVersion)
        assertEquals("item-v1", fact.itemPricingVersion)
        assertEquals(1999L, fact.statusObservedAt)
        assertEquals(NewApiSnapshotStore.PRICE_SOURCE, raw(negative).source)
    }

    @Test
    fun negativeOnlyScopeKeepsUnknownNullLastGoodAndRawDecimal() = runBlocking {
        persistFixed(scope, 1000, "-0.1")
        val stored = store.price(scope)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertNull(stored.lastGood)
        assertNegativeRaw(scope, stored)
        assertEquals(listOf(stored.latest), store.history(scope, SnapshotKind.PRICE))
        val head = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
        assertNull(head.lastGoodSnapshotId)
        assertNull(head.lastGoodObservedAt)
        assertEquals(1L, count("price_snapshots"))
        assertEquals(1L, count("snapshot_heads"))
    }

    @Test
    fun zeroFixedControlStaysFreeNowAndBecomesLastGood() = runBlocking {
        assertZeroControl("0", "0.0")
    }

    @Test
    fun decimalZeroFixedControlStaysFreeNowAndBecomesLastGood() = runBlocking {
        assertZeroControl("0.00", "0.000")
    }

    @Test
    fun positiveFixedControlStaysPaidWithExactDecimal() = runBlocking {
        persistFixed(scope, 1000, POSITIVE_PRICE)
        val stored = store.price(scope)
        assertEquals("PAID", stored.latest!!.state)
        assertEquals(raw(stored).toDomain(), stored.lastGood)
        assertEquals(POSITIVE_FIXED, raw(stored).fixedRequest)
        decimal(POSITIVE_FIXED, stored.lastGood!!.fixedRequest)
        assertFalse(stored.lastGood!!.fixedRequestKnownAbsent)
        assertFixedOnly(raw(stored))
        assertEquals("USD_PER_REQUEST", stored.latest!!.priceUnit)
    }

    @Test
    fun laterPositiveRecoveryAdvancesLastGoodWithoutRewritingNegativeHistory() = runBlocking {
        persistFixed(scope, 1000, POSITIVE_PRICE)
        val first = store.price(scope)
        val firstRaw = raw(first)
        persistFixed(scope, 2000, "-0.1")
        val negative = store.price(scope)
        val negativeRaw = raw(negative)
        val negativeEvidence = db.evidenceDao().getById(negative.latest!!.evidenceId)!!
        assertEquals("UNKNOWN", negative.latest!!.state)
        assertEquals(first.lastGood, negative.lastGood)
        assertNegativeRaw(scope, negative)

        persistFixed(scope, 3000, "0.2")
        val recovered = store.price(scope)
        assertEquals("PAID", recovered.latest!!.state)
        assertEquals(3000L, recovered.latest!!.observedAt)
        assertEquals(raw(recovered).toDomain(), recovered.lastGood)
        assertEquals("0.30", raw(recovered).fixedRequest)
        decimal("0.30", recovered.lastGood!!.fixedRequest)
        assertFalse(recovered.lastGood!!.fixedRequestKnownAbsent)
        assertEquals(listOf(recovered.latest, negative.latest, first.latest), store.history(scope, SnapshotKind.PRICE))
        assertEquals(firstRaw, db.priceDao().getById(firstRaw.id))
        assertEquals(negativeRaw, db.priceDao().getById(negativeRaw.id))
        assertEquals(negative.latest, db.snapshotFactDao().evidence(negative.latest!!.evidenceId))
        assertEquals(negativeEvidence, db.evidenceDao().getById(negativeEvidence.id))
        val head = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
        assertEquals(recovered.latest!!.evidenceId, head.latestEvidenceId)
        assertEquals(recovered.lastGood!!.id, head.lastGoodSnapshotId)
        assertEquals(3000L, head.lastGoodObservedAt)
        assertEquals(3L, count("price_snapshots"))
        assertEquals(3L, count("snapshot_facts"))
        assertEquals(3L, count("evidence"))
        assertEquals(1L, count("snapshot_heads"))
    }

    // Each certification guard changes only the named input. UNKNOWN alone is
    // insufficient: null raw legs prove the negative shortcut was never reached.
    @Test
    fun uncertifiedPricingSupportNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        assertGuarded(result = pricingOk(2000).copy(
            evidence = evidence(2000).copy(versionSupport = NewApiVersionSupport.UNKNOWN),
        ))
    }

    @Test
    fun nonSuccessPricingHttpStatusNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        assertGuarded(result = pricingOk(2000).copy(evidence = evidence(2000).copy(httpStatus = 300)))
    }

    @Test
    fun uncertifiedPricingVersionNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        assertGuarded(result = pricingOk(2000).copy(
            evidence = evidence(2000).copy(providerVersion = "v1.0.0-rc.26"),
        ))
    }

    @Test
    fun nonSuccessStatusHttpStatusNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        val caps = capabilities(2000)
        assertGuarded(caps = caps.copy(evidence = caps.evidence.copy(httpStatus = 300)))
    }

    @Test
    fun uncertifiedStatusSupportNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        val caps = capabilities(2000)
        assertGuarded(caps = caps.copy(evidence = caps.evidence.copy(versionSupport = NewApiVersionSupport.UNKNOWN)))
    }

    @Test
    fun uncertifiedStatusVersionNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        val caps = capabilities(2000)
        assertGuarded(caps = caps.copy(evidence = caps.evidence.copy(providerVersion = "v1.0.0-rc.26")))
    }

    @Test
    fun capabilitiesValueVersionMismatchNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        val caps = capabilities(2000)
        val unknownVersion = NewApiVersion.of("v1.0.0-rc.26")
        assertGuarded(caps = caps.copy(value = caps.value.copy(
            version = unknownVersion, certifiedEndpoints = unknownVersion.certifiedEndpoints,
        )))
    }

    @Test
    fun wrongStatusSourceNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        val caps = capabilities(2000)
        assertGuarded(caps = caps.copy(evidence = caps.evidence.copy(source = NewApiSnapshotStore.PRICE_SOURCE)))
    }

    @Test
    fun laterStatusObservationNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        val caps = capabilities(2000)
        assertGuarded(caps = caps.copy(evidence = caps.evidence.copy(observedAt = 2001)))
    }

    @Test
    fun wrongEndpointPathNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        assertGuarded(result = pricingOk(2000, fixedCatalog(scope.modelId, "-0.1").copy(
            supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/responses", "POST")),
        )))
    }

    @Test
    fun wrongEndpointMethodNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        assertGuarded(result = pricingOk(2000, fixedCatalog(scope.modelId, "-0.1").copy(
            supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "GET")),
        )))
    }

    @Test
    fun wrongEndpointProtocolNeverCarriesLegsAndKeepsLastGood() = runBlocking {
        assertGuarded(endpointProtocol = "RESPONSES")
    }

    @Test
    fun wrongOperationNeverBorrowsChatFixedLegsOrOverwritesPriorScope() = runBlocking {
        persistFixed(scope, 1000, POSITIVE_PRICE)
        val prior = store.price(scope)
        val embeddings = scope.copy(operation = "EMBEDDINGS")
        persistFixed(embeddings, 2000, "-0.1")
        val stored = store.price(embeddings)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertNull(stored.lastGood)
        assertNull(raw(stored).fixedRequest)
        assertFalse(raw(stored).fixedRequestKnownAbsent)
        assertFixedOnly(raw(stored))
        assertEquals(listOf(stored.latest), store.history(embeddings, SnapshotKind.PRICE))
        assertEquals(prior, store.price(scope))
        assertEquals(listOf(prior.latest), store.history(scope, SnapshotKind.PRICE))
        assertEquals(2L, count("snapshot_heads"))
    }

    @Test
    fun textAliasRetainsNegativeFixedRawButNeverCreatesLastGood() = runBlocking {
        val alias = scope.copy(operation = "text")
        persistFixed(alias, 1000, "-0.1")
        val stored = store.price(alias)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertNull(stored.lastGood)
        assertNegativeRaw(alias, stored)
        assertEquals(listOf(stored.latest), store.history(alias, SnapshotKind.PRICE))
        assertNull(store.price(scope).latest)
    }

    @Test
    fun publicPricingSessionBundleConsumesExactGroupUnknownWithoutSideEffects() = runBlocking {
        val repo = AtlasRepository(db)
        val sessionStore = repo.newApiSnapshots
        val offering = OfferingEntity("off-np-neg", "np", "ne", scope.modelId, manual = false, source = "OFFICIAL")
        db.offeringDao().insert(offering)
        val canonicalStore = CanonicalStore(db)
        val canonical = canonicalStore.saveCanonical(scope.modelId, nowMillis = 100)
        val alias = canonicalStore.saveAlias(
            "np", scope.modelId, canonical.canonicalModelId, "NORMALIZATION", "LOW", observedAt = 100,
        )
        val sample = SampleEntity(
            "prior-sample", "np", "na", "ni", "ne", scope.modelId,
            group = "default", source = "MANUAL_TEST", requestStartedAt = 10,
            completedAt = 20, outcome = "SUCCESS",
        )
        db.sampleDao().upsert(sample)
        db.myModelsDao().put(MyModelEntity("np", "unrelated-member", addedAt = 50, source = "USER"))
        val membership = db.myModelsDao().all()
        val scopes = sessionStore.buildPricingScopes(
            "np", "na", AccountPatRef("nc"), fixedCatalog(scope.modelId, POSITIVE_PRICE),
            listOf(db.endpointDao().getById("ne")!!), listOf(offering),
        )
        assertEquals(setOf(scope, scope.copy(group = "other"), scope.copy(group = "")), scopes.toSet())
        assertEquals(3, scopes.size)
        assertTrue(scopes.all { it.credentialId == null })
        val target = scopes.single { it.group == "default" }
        val positive = sessionStore.syncPricingSession(
            AccountPatRef("nc"), capabilities(1000), pricingOk(1000, fixedCatalog(scope.modelId, POSITIVE_PRICE)), scopes,
        )
        assertEquals(listOf("PAID", "PAID", "PAID"), positive.map { it.latest!!.state })
        val prior = sessionStore.price(target)
        // Legitimate source/credential decoys cannot replace the official,
        // credential-null quote even though their FREE_NOW/zero prices differ.
        sessionStore.saveManualPrice(PriceSnapshot(
            scope = target, observedAt = 1500, fixedRequest = BigDecimal.ZERO,
        ))
        persistFixed(target.copy(credentialId = "ni"), 1500, "0")
        assertEquals("PAID", bundle(repo, "default").freePriceState)

        val observed = sessionStore.syncPricingSession(
            AccountPatRef("nc"), capabilities(2000), pricingOk(2000), listOf(target),
        ).single()
        assertEquals("UNKNOWN", observed.latest!!.state)
        assertEquals(prior.lastGood, observed.lastGood)
        assertNegativeRaw(target, observed)
        assertEquals(listOf(observed.latest, prior.latest), sessionStore.history(target, SnapshotKind.PRICE))
        assertEquals(membership, db.myModelsDao().all())
        assertEquals(sample, db.sampleDao().getById(sample.id))
        assertEquals(1L, count("samples"))

        val factsBefore = scopes.associateWith { sessionStore.history(it, SnapshotKind.PRICE) }
        val headsBefore = db.snapshotFactDao().headsByKind(SnapshotKind.PRICE.name).toSet()
        val countsBefore = consumerCounts()
        val result = bundle(repo, "default")
        assertEquals("UNKNOWN", result.freePriceState)
        assertTrue(result.aliasAttached)
        assertEquals(canonical.canonicalModelId, result.canonicalModelId)
        assertTrue(result.stepFailures.isEmpty())
        assertNull(result.newApiPricingSynced)
        assertFalse(result.providerPerformanceFetched)
        assertFalse(result.externalRefreshAttempted)
        assertNull(result.pricingSummary)
        assertNull(result.quotaSummary)
        // Sibling groups really stay PAID; missing/null groups are no-data.
        // This distinguishes exact-group matching from cross-group consensus.
        assertEquals("PAID", bundle(repo, "other").freePriceState)
        assertEquals("PAID", sessionStore.price(scope.copy(group = "")).latest!!.state)
        // Empty catalog groups are durable scopes, but the public bundle input
        // rejects blank groups. Do not fabricate a reachable blank-group call.
        val blankGroupCounts = consumerCounts()
        try {
            bundle(repo, "")
            fail("Blank bundle group must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Invalid group", expected.message)
        }
        assertEquals(blankGroupCounts, consumerCounts())
        assertEquals(membership, db.myModelsDao().all())
        assertEquals(sample, db.sampleDao().getById(sample.id))
        assertNull(bundle(repo, "missing-group").freePriceState)
        assertNull(bundle(repo, null).freePriceState)
        assertEquals(factsBefore, scopes.associateWith { sessionStore.history(it, SnapshotKind.PRICE) })
        assertEquals(headsBefore, db.snapshotFactDao().headsByKind(SnapshotKind.PRICE.name).toSet())
        assertEquals(countsBefore, consumerCounts())
        assertEquals(membership, db.myModelsDao().all())
        assertEquals(0, db.myModelsDao().count("np", scope.modelId))
        assertEquals(sample, db.sampleDao().getById(sample.id))
        assertEquals(offering, db.offeringDao().getById(offering.id))
        assertEquals(alias, db.providerModelAliasDao().get("np", scope.modelId))
        assertEquals(observed, sessionStore.price(target))

        // The same consumer also preserves an explicitly added member exactly.
        MyModelsStore(db).addToMyModels("np", scope.modelId, nowMillis = 2500)
        val explicitMembership = db.myModelsDao().all()
        val explicitCounts = consumerCounts()
        assertEquals("UNKNOWN", bundle(repo, "default").freePriceState)
        assertEquals(explicitMembership, db.myModelsDao().all())
        assertEquals(explicitCounts, consumerCounts())
        assertEquals(sample, db.sampleDao().getById(sample.id))
    }

    private suspend fun bundle(repo: AtlasRepository, group: String?) = repo.enrichMyModelBundle(
        providerId = "np", providerModelId = scope.modelId, accountId = "na", credentialId = "ni",
        group = group, endpointId = "ne", operation = "CHAT_COMPLETIONS",
    )

    private suspend fun assertZeroControl(modelPrice: String, expectedRaw: String) {
        persistFixed(scope, 1000, modelPrice)
        val stored = store.price(scope)
        assertEquals("FREE_NOW", stored.latest!!.state)
        assertEquals(1000L, stored.lastGood!!.observedAt)
        assertEquals(raw(stored).toDomain(), stored.lastGood)
        assertEquals(expectedRaw, raw(stored).fixedRequest)
        decimal("0", stored.lastGood!!.fixedRequest)
        assertFalse(stored.lastGood!!.fixedRequestKnownAbsent)
        assertFixedOnly(raw(stored))
    }

    private suspend fun assertGuarded(
        caps: NewApiResult.Success<NewApiCapabilities> = capabilities(2000),
        result: NewApiResult.Success<NewApiPricing> = pricingOk(2000),
        endpointProtocol: String? = null,
    ) {
        persistFixed(scope, 1000, POSITIVE_PRICE)
        val prior = store.price(scope)
        val priorRaw = raw(prior)
        assertEquals("PAID", prior.latest!!.state)
        assertEquals(POSITIVE_FIXED, priorRaw.fixedRequest)
        endpointProtocol?.let {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE endpoints SET protocol = ? WHERE endpointId = ?", arrayOf(it, scope.endpointId),
            )
        }
        store.persistPricing(scope, caps, result)
        val stored = store.price(scope)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertEquals("OK", stored.latest!!.status)
        assertEquals(2000L, stored.latest!!.observedAt)
        assertEquals(prior.lastGood, stored.lastGood)
        assertNull(raw(stored).fixedRequest)
        assertFalse(raw(stored).fixedRequestKnownAbsent)
        assertFixedOnly(raw(stored))
        assertEquals(priorRaw, db.priceDao().getById(priorRaw.id))
        assertEquals(listOf(stored.latest, prior.latest), store.history(scope, SnapshotKind.PRICE))
        val head = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
        assertEquals(prior.lastGood!!.id, head.lastGoodSnapshotId)
        assertEquals(1000L, head.lastGoodObservedAt)
        assertEquals(2L, count("price_snapshots"))
        assertEquals(1L, count("snapshot_heads"))
    }

    private suspend fun assertNegativeRaw(target: PricingScope, stored: StoredPrice) {
        val raw = raw(stored)
        assertEquals("-0.15", raw.fixedRequest)
        assertEquals(target, raw.toDomain().scope)
        assertEquals(stored.latest!!.observedAt, raw.observedAt)
        assertEquals(stored.latest!!.evidenceId, raw.evidenceId)
        assertFalse(raw.fixedRequestKnownAbsent)
        assertFixedOnly(raw)
    }

    private fun assertFixedOnly(raw: PriceSnapshotEntity) {
        assertNull(raw.input)
        assertNull(raw.output)
        assertNull(raw.cacheRead)
        assertNull(raw.cacheWrite)
        assertFalse(raw.fixedRequestKnownAbsent)
    }

    private suspend fun raw(stored: StoredPrice) = db.priceDao().getById(stored.latest!!.snapshotId!!)!!

    private suspend fun persistFixed(target: PricingScope, time: Long, modelPrice: String) {
        store.persistPricing(target, capabilities(time), pricingOk(time, fixedCatalog(target.modelId, modelPrice)))
    }

    private fun evidence(time: Long) = NewApiEvidence(
        NewApiSnapshotStore.PRICE_SOURCE, time, NewApiVersion.RC25,
        NewApiVersionSupport.CERTIFIED_RC25, 200, "catalog-v1",
    )

    private fun capabilities(time: Long): NewApiResult.Success<NewApiCapabilities> {
        val version = NewApiVersion.of(NewApiVersion.RC25)
        return NewApiResult.Success(
            NewApiEvidence("newapi/api/status", time - 1, NewApiVersion.RC25,
                NewApiVersionSupport.CERTIFIED_RC25, 200, null),
            NewApiCapabilities(version, BigDecimal("500000"), "USD", false,
                NewApiModuleAccess(true, true), version.certifiedEndpoints),
        )
    }

    private fun pricingOk(time: Long, pricing: NewApiPricing = fixedCatalog(scope.modelId, "-0.1")) =
        NewApiResult.Success(evidence(time), pricing)

    private fun fixedCatalog(model: String, modelPrice: String) = NewApiPricing(
        items = listOf(NewApiPricingItem(
            modelName = model, quotaType = 1, modelRatio = BigDecimal("2"), modelPrice = BigDecimal(modelPrice),
            completionRatio = BigDecimal("2.5"), cacheRatio = BigDecimal("0.25"), createCacheRatio = BigDecimal("1.25"),
            imageRatio = null, audioRatio = null, audioCompletionRatio = null, enableGroups = listOf("all"),
            supportedEndpointTypes = listOf("openai"), billingMode = null, billingExpression = null,
            vendorId = null, pricingVersion = "item-v1",
        )),
        skippedItems = 0,
        groupRatio = listOf("default", "other", "").associateWith { NewApiRatio(BigDecimal("1.5"), false) },
        usableGroups = listOf("default", "other", ""),
        supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
        autoGroups = emptyList(),
        pricingVersion = "catalog-v1",
    )

    private fun decimal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual!!))
    }

    private fun consumerCounts() = listOf(
        "my_models", "samples", "evidence", "price_snapshots", "snapshot_facts", "snapshot_heads",
        "offerings", "credential_availability", "canonical_models", "provider_model_aliases",
        "provider_perf_facts", "provider_perf_heads", "provider_account_usage_facts", "provider_account_usage_heads",
    ).associateWith { count(it) }

    private fun count(table: String): Long = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private companion object {
        const val POSITIVE_PRICE = "0.1234567890123456789"
        const val POSITIVE_FIXED = "0.18518518351851851835"
    }
}
