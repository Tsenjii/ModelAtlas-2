package dev.modelatlas.app.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.data.CredentialAvailabilityEntity
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.EvidenceEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.ProviderManualConfig
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.app.data.ScopedVerifiedModel
import dev.modelatlas.app.data.SnapshotFactEntity
import dev.modelatlas.app.data.SnapshotHeadEntity
import dev.modelatlas.app.data.SnapshotKind
import dev.modelatlas.app.data.storageKey
import dev.modelatlas.app.data.toEntity
import dev.modelatlas.app.data.toSample
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialLocation
import dev.modelatlas.core.onboarding.CustomAuthFieldRef
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-006 T5–T9: PROV-001 / MODEL-009, PRODUCT_SPEC §4/6/10/11/18,
 * Intelligence Contract §13/23.3/23.4/23.5. Real Room and production loadDetail;
 * custom-anchor binding is created only by the existing public repository verifier.
 * Fixtures are local test data with safe refs, no vault/network/Application harness.
 * Display caps never authorize verification or delete stored history.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDetailFactsHistoryDeviceTest {
    private lateinit var db: AtlasDatabase
    private lateinit var repo: AtlasRepository
    private lateinit var queryGate: FactQueryGate

    @Before
    fun setUp() {
        queryGate = FactQueryGate()
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries()
            // Direct callback runs on the real Room query executor, before SQLite execution.
            .setQueryCallback(RoomDatabaseQueryCallback(queryGate), Executor { it.run() })
            .build()
        repo = AtlasRepository(db)
    }

    @After
    fun tearDown() {
        queryGate.release.countDown()
        db.close()
    }

    @Test
    fun intermediateFactsAndHeadAllReachHistoryWithExactProvenanceAndSevenScopeFields() = runBlocking {
        seedConnection()
        seedConnection(provider = "p2", account = "a2", credential = "c2", endpoint = "e2")
        val scope = PricingScope("p1", "a1", "c1", "m-base", " vip ", "e1", OP)
        val facts = listOf(
            fact("intermediate-1", scope, 1_000L, "PAID", "OK", "newapi/api/pricing"),
            fact("intermediate-2", scope, 2_000L, "UNKNOWN", "HTTP_ERROR", "USER"),
            fact("intermediate-3", scope, 3_000L, "FREE_NOW", "OK", "amd/pricing"),
            fact("head-4", scope, 4_000L, "UNKNOWN", "MALFORMED", "unrecognized/provider"),
        )
        val head = SnapshotHeadEntity(scope.storageKey(), "PRICE", "head-4", 4_000L, null, null)
        db.snapshotFactDao().upsertHead(head)
        fact("foreign", scope.copy(providerId = "p2"), 9_000L, "FOREIGN", "OK", "USER")
        fact("malformed", scope, 8_000L, "MALFORMED_SCOPE", "OK", "USER",
            rawKey = scope.storageKey() + "trailing")
        val before = rowCounts()

        val detail = load()

        assertEquals(listOf("UNKNOWN", "FREE_NOW", "UNKNOWN", "PAID"), detail.history.map { it.stateLabel })
        assertEquals(listOf("MALFORMED", "OK", "HTTP_ERROR", "OK"), detail.history.map { it.statusLabel })
        assertEquals(listOf("", "official", "manual", "official"), detail.history.map { it.sourceLabel })
        assertEquals(List(4) { "PRICE" }, detail.history.map { it.section })
        assertEquals(listOf(4_000L, 3_000L, 2_000L, 1_000L).map { ProviderDetailLoader.formatTimestamp(it) },
            detail.history.map { it.observedAtLabel })
        val label = "provider='p1' account='a1' key='c1' model='m-base' group=' vip ' endpoint='e1' operation='$OP'"
        assertEquals(List(4) { label }, detail.history.map { it.scopeLabel })
        assertFalse(detail.history.any { it.scopeLabel == scope.storageKey() })
        assertEquals(listOf(setOf("intermediate-1", "intermediate-2", "intermediate-3", "head-4", "malformed")),
            queryGate.evidenceBatches.map { it.toSet() })
        assertEquals(head, db.snapshotFactDao().head(scope.storageKey(), "PRICE"))
        assertEquals(facts.reversed(), db.snapshotFactDao().history(scope.storageKey(), "PRICE"))
        assertEquals(before, rowCounts())
    }

    @Test
    fun allEightKindsRetainNullEmptyAndPaddedRawScopeLabelsAndWhitelistSources() = runBlocking {
        seedConnection()
        val kinds = SnapshotKind.values().toList()
        assertEquals(8, kinds.size)
        val sources = listOf("newapi/api/pricing", "newapi/api/user/self", "newapi/api/usage/token",
            "USER", "USER", "nvidia-hosted/pricing", "groq/quota", "external/untrusted")
        val labels = listOf("official", "official", "official", "manual", "manual", "official", "official", "")
        val scopes = kinds.indices.map { i ->
            when (i % 3) {
                0 -> PricingScope("p1", null, null, "", null, "", "")
                1 -> PricingScope("p1", "", "", "", "", "", "")
                else -> PricingScope("p1", "a1", "c1", "m-base", " vip ", "e1", OP)
            }
        }
        kinds.forEachIndexed { i, kind ->
            fact("kind-$i", scopes[i], 1_000L + i, "UNKNOWN", " NOT_SUPPORTED ", sources[i], kind.name)
        }
        val before = rowCounts()

        val history = load().history

        assertEquals(kinds.reversed().map { it.name }, history.map { it.section })
        assertEquals(List(8) { "UNKNOWN" }, history.map { it.stateLabel })
        assertEquals(List(8) { "NOT_SUPPORTED" }, history.map { it.statusLabel })
        assertEquals(labels.reversed(), history.map { it.sourceLabel })
        assertEquals(kinds.indices.map { listOf("kind-$it") }, queryGate.evidenceBatches.toList())
        kinds.indices.forEach { i ->
            val expected = when (i % 3) {
                0 -> "provider='p1' account=null key=null model='' group=null endpoint='' operation=''"
                1 -> "provider='p1' account='' key='' model='' group='' endpoint='' operation=''"
                else -> "provider='p1' account='a1' key='c1' model='m-base' group=' vip ' endpoint='e1' operation='$OP'"
            }
            assertEquals(expected, history.single { it.section == kinds[i].name }.scopeLabel)
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun boundedFactSqlUsesNewestThenEvidenceIdTieOrderWithoutDeletingOlderFacts() = runBlocking {
        seedConnection()
        val scope = PricingScope("p1", "a1", "c1", "m-base", null, "e1", OP)
        // Insert backwards to distinguish SQL's stable evidenceId tie order from insertion order.
        for (i in 204 downTo 0) {
            fact("tie-${i.toString().padStart(3, '0')}", scope, 10_000L, "state-$i", "OK", "USER")
        }
        fact("older", scope, 9_999L, "OLDER", "OK", "USER")
        fact("newer-quota", scope, 15_000L, "QUOTA_LOW", "OK", "newapi/api/usage/token", "TOKEN_QUOTA")
        db.sampleDao().upsert(sample("newer-telemetry", at = 20_000L))
        val before = rowCounts()

        val bounded = db.snapshotFactDao().recentFactsForProviderKind("p1", "PRICE", 200)
        assertEquals((0..199).map { "tie-${it.toString().padStart(3, '0')}" }, bounded.map { it.evidenceId })
        val detail = load()
        assertEquals(200, detail.history.size)
        assertEquals(listOf("NETWORK", "QUOTA_LOW") + (0..197).map { "state-$it" }, detail.history.map { it.stateLabel })
        assertEquals(listOf("TELEMETRY", "TOKEN_QUOTA") + List(198) { "PRICE" }, detail.history.map { it.section })
        assertEquals(listOf("observed", "official") + List(198) { "manual" }, detail.history.map { it.sourceLabel })
        assertEquals(listOf(bounded.map { it.evidenceId }, listOf("newer-quota")), queryGate.evidenceBatches.toList())
        assertEquals(206, db.snapshotFactDao().history(scope.storageKey(), "PRICE").size)
        assertNotNull(db.snapshotFactDao().evidence("older"))
        assertEquals(before, rowCounts())
    }

    @Test
    fun oldExactManualSuccessVerifiesWhileHistoryContainsOnlyNewestTwoHundredFinals() = runBlocking {
        val selected = seedConnection()
        val old = sample("old-manual", model = "m-base", at = 1_000L, source = "MANUAL_TEST", outcome = "SUCCESS", group = " vip ")
        repo.save(old.toSample())
        val newer = seedNewerFinals(group = " vip ")
        // Very new nonfinal and foreign success would displace valid rows without SQL filters.
        db.sampleDao().upsert(sample("nonfinal", at = 999_000L).copy(completedAt = null))
        seedConnection(provider = "p2", account = "a2", credential = "c2", endpoint = "e2")
        db.sampleDao().upsert(sample("foreign-final", at = 999_001L).copy(
            providerId = "p2", accountId = "a2", safeCredentialId = "c2", endpointId = "e2",
        ))
        val before = rowCounts()

        val recent = db.sampleDao().recentFinalsByProvider("p1", 200)
        assertEquals(newer.takeLast(200).reversed().map { it.id }, recent.map { it.id })
        assertEquals(listOf(ScopedVerifiedModel("e1", "m-base")),
            db.sampleDao().verifiedManualKeys("p1", "a1", "c1", "e1", OP, " vip "))
        val detail = load(selected.id, " vip ")
        assertTrue(detail.models.single { it.modelId == "m-base" }.verified)
        assertEquals(200, detail.history.size)
        assertEquals(newer.takeLast(200).reversed().map { it.outcome }, detail.history.map { it.stateLabel })
        assertEquals(newer.takeLast(200).reversed().map { ProviderDetailLoader.formatTimestamp(it.completedAt) },
            detail.history.map { it.observedAtLabel })
        assertEquals(List(200) { "TELEMETRY" }, detail.history.map { it.section })
        assertEquals(List(200) { "observed" }, detail.history.map { it.sourceLabel })
        assertFalse(detail.history.any { it.observedAtLabel == ProviderDetailLoader.formatTimestamp(old.completedAt) })
        assertFalse(load(selected.id, "vip").models.single { it.modelId == "m-base" }.verified)
        assertFalse(load().models.single { it.modelId == "m-base" }.verified)
        assertEquals(old, db.sampleDao().getById(old.id))
        assertNotNull(db.evidenceDao().getById("sample:${old.id}"))
        assertEquals(before, rowCounts())
    }

    @Test
    fun customAnchorPublicVerificationSurvivesHistoryCapAndCannotDonateToOtherAnchor() = runBlocking {
        val first = seedConnection(config = false)
        db.credentialDao().insert(CredentialEntity("c-other-anchor", "a1"))
        db.availabilityDao().insert(CredentialAvailabilityEntity("av-other-anchor", first.offeringId, "a1", "c-other-anchor"))
        db.providerConfigDao().insert(ProviderManualConfig(
            providerId = "p1", workflowId = "custom-anchors", authMode = AuthMode.CUSTOM_HEADER,
            customAuthFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-One", "c1"),
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Two", "c-other-anchor"),
            ),
        ).toEntity())
        val second = repo.connections.first().single { it.id == "av-other-anchor" }
        val candidate = repo.addProviderModelCandidate(first, "m-custom", null)
        val target = InferenceTarget(
            providerId = "p1", accountId = "a1", credentialId = null, endpointId = "e1",
            endpointUrl = URL, modelId = "m-custom", operation = OP,
            authFields = listOf(InferenceAuthField("X-One", "c1"), InferenceAuthField("X-Two", "c-other-anchor")),
        )
        val firstSample = sample("custom-first", "m-custom", 1_000L, "MANUAL_TEST", "SUCCESS").copy(safeCredentialId = "")
        // Repository's real telemetry persistence supplies sample:<id>; verifier writes the binding.
        repo.save(firstSample.toSample())
        assertTrue(repo.verifyProviderModelCandidate(candidate, first, target, firstSample.id))
        val firstBinding = requireNotNull(db.evidenceDao().byRequest("manual-scope:${firstSample.id}"))
        val firstAvailability = db.availabilityDao().byExactScope(candidate, "a1", "c1", null).single()
        db.availabilityDao().insert(CredentialAvailabilityEntity("copied-availability", candidate, "a1", "c-other-anchor"))
        seedNewerFinals()
        val before = rowCounts()

        assertTrue(load(first.id).models.single { it.modelId == "m-custom" }.verified)
        assertFalse(load(second.id).models.single { it.modelId == "m-custom" }.verified)
        assertEquals(200, load(first.id).history.size)
        assertEquals(listOf(ScopedVerifiedModel("e1", "m-custom")), anchored("c1"))
        assertTrue(anchored("c-other-anchor").isEmpty())
        var rejected = false
        try {
            repo.verifyProviderModelCandidate(candidate, second, target, firstSample.id)
        } catch (e: IllegalArgumentException) {
            assertEquals("Verification sample belongs to another selected anchor", e.message)
            rejected = true
        }
        assertTrue("The already claimed sample must not be rebound", rejected)
        assertEquals(firstBinding, db.evidenceDao().byRequest("manual-scope:${firstSample.id}"))
        assertEquals(before, rowCounts())

        val secondSample = firstSample.copy(id = "custom-second", requestStartedAt = 1_001L, completedAt = 1_001L)
        repo.save(secondSample.toSample())
        assertTrue(repo.verifyProviderModelCandidate(candidate, second, target, secondSample.id))
        val afterOwnVerification = rowCounts()
        assertTrue(load(second.id).models.single { it.modelId == "m-custom" }.verified)
        assertTrue(load(first.id).models.single { it.modelId == "m-custom" }.verified)
        assertEquals(200, load(second.id).history.size)
        assertEquals(listOf(ScopedVerifiedModel("e1", "m-custom")), anchored("c-other-anchor"))
        assertEquals(firstBinding, db.evidenceDao().byRequest("manual-scope:${firstSample.id}"))
        assertEquals(firstAvailability, db.availabilityDao().byExactScope(candidate, "a1", "c1", null).single())
        assertEquals(firstSample, db.sampleDao().getById(firstSample.id))
        assertEquals(afterOwnVerification, rowCounts())
    }

    @Test
    fun eachWrongScopeSourceOutcomeCompletionAndPaddedModelRejectsWithPositiveControl() = runBlocking {
        val selected = seedConnection()
        seedConnection(provider = "p2", account = "a2", credential = "c2", endpoint = "e2")
        db.accountDao().insert(AccountEntity("a-sibling", "p1"))
        db.credentialDao().insert(CredentialEntity("c-sibling", "a1"))
        db.endpointDao().insert(EndpointEntity("e-sibling", "p1", BASE, URL))
        val base = sample("unused", source = "MANUAL_TEST", outcome = "SUCCESS", group = "default")
        val negatives = linkedMapOf(
            "provider" to base.copy(providerId = "p2"),
            "account" to base.copy(accountId = "a-sibling"),
            "credential" to base.copy(safeCredentialId = "c-sibling"),
            "endpoint" to base.copy(endpointId = "e-sibling"),
            "operation" to base.copy(operation = "RESPONSES"),
            "group-null" to base.copy(group = null),
            "group-empty" to base.copy(group = ""),
            "group-padded" to base.copy(group = " default "),
            "source" to base.copy(source = "PLAYGROUND"),
            "network" to base.copy(outcome = "NETWORK"),
            "cancelled" to base.copy(outcome = "CANCELLED"),
            "running" to base.copy(outcome = "RUNNING"),
            "nonfinal-success" to base.copy(completedAt = null),
            "padded-model" to base,
            "tabbed-model" to base,
        )
        negatives.forEach { (label, row) ->
            val model = when (label) {
                "padded-model" -> " m-$label "
                "tabbed-model" -> "\tm-$label\t"
                else -> "m-$label"
            }
            // Distinct offering for every negative: removing a predicate exposes that model.
            addOffering(model)
            db.sampleDao().upsert(row.copy(id = "negative-$label", providerModelId = model))
        }
        db.sampleDao().upsert(base.copy(id = "positive", providerModelId = "m-base"))
        val before = rowCounts()

        val keys = db.sampleDao().verifiedManualKeys("p1", "a1", "c1", "e1", OP, "default")
        // SQLite TRIM rejects space padding; Kotlin loader must also reject tabs that SQL retains.
        assertEquals(setOf("m-base", "\tm-tabbed-model\t"), keys.map { it.providerModelId }.toSet())
        val detail = load(selected.id, "default")
        assertEquals(setOf("m-base"), detail.models.filter { it.verified }.map { it.modelId }.toSet())
        assertEquals(negatives.size + 1, detail.models.size)
        negatives.keys.forEach { label ->
            assertFalse("$label cannot authorize its offering", detail.models.single { it.modelId == "m-$label" }.verified)
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun anonymousExactVerificationKeepsNullAndEmptyGroupsDistinctAndNeverBorrowsCredential() = runBlocking {
        val selected = seedConnection(anonymous = true)
        addOffering("m-empty-group")
        addOffering("m-bearer-decoy")
        db.credentialDao().insert(CredentialEntity("c-bearer", "a1"))
        db.sampleDao().upsert(sample("anon-null", "m-base", source = "MANUAL_TEST", outcome = "SUCCESS").copy(safeCredentialId = ""))
        db.sampleDao().upsert(sample("anon-empty", "m-empty-group", source = "MANUAL_TEST", outcome = "SUCCESS", group = "").copy(safeCredentialId = ""))
        db.sampleDao().upsert(sample("bearer", "m-bearer-decoy", source = "MANUAL_TEST", outcome = "SUCCESS").copy(safeCredentialId = "c-bearer"))
        val before = rowCounts()

        assertTrue(selected.anonymous)
        assertEquals("", selected.credentialId)
        val nullScope = load(selected.id)
        val emptyScope = load(selected.id, "")
        assertEquals(setOf("m-base"), nullScope.models.filter { it.verified }.map { it.modelId }.toSet())
        assertEquals(setOf("m-empty-group"), emptyScope.models.filter { it.verified }.map { it.modelId }.toSet())
        assertTrue(nullScope.actionScope!!.anonymous)
        assertTrue(load().models.none { it.verified })
        assertEquals(0L, before.getValue("credential_availability"))
        assertEquals(before, rowCounts())
    }

    @Test
    fun realSqlRangeSeparatesProviderPrefixesColonsAndSupplementaryCharsAndLoaderRejectsMalformed() = runBlocking {
        val providers = listOf("p", "p1", "p:🧪", "p:🧪x", "p:🧫")
        val expected = linkedMapOf<String, List<String>>()
        providers.forEachIndexed { i, provider ->
            db.providerDao().insert(ProviderEntity(provider, "Unicode fixture $i"))
            val scope = PricingScope(provider, "a:😀", "c:𐀀", "m:🧪", " g:😀 ", "e:𐀀", "op:🧪")
            val ids = listOf("sql-$i-new", "sql-$i-old")
            expected[provider] = ids
            fact(ids[1], scope, 1_000L + i, ids[1], "OK", "USER")
            fact(ids[0], scope, 2_000L + i, ids[0], "OK", "USER")
            // In the real provider range but unparseable: a range hit is not identity proof.
            fact("sql-$i-malformed", scope, 3_000L + i, "BAD-$i", "OK", "USER",
                rawKey = "${provider.length}:$provider" + "broken")
        }
        val before = rowCounts()

        providers.forEachIndexed { i, provider ->
            val sqlRows = db.snapshotFactDao().recentFactsForProviderKind(provider, "PRICE", 200)
            assertEquals(listOf("sql-$i-malformed") + expected.getValue(provider), sqlRows.map { it.evidenceId })
            val history = load(provider = provider).history
            assertEquals(expected.getValue(provider), history.map { it.stateLabel })
            assertEquals(List(2) { "manual" }, history.map { it.sourceLabel })
            assertEquals(List(2) {
                "provider='$provider' account='a:😀' key='c:𐀀' model='m:🧪' group=' g:😀 ' endpoint='e:𐀀' operation='op:🧪'"
            }, history.map { it.scopeLabel })
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun cancellationDuringRealRoomFactQueryPropagatesWithoutReturningPartialOrFailedState() = runBlocking {
        seedConnection()
        val scope = PricingScope("p1", "a1", "c1", "m-base", null, "e1", OP)
        val stored = fact("cancel-fact", scope, 1_000L, "PAID", "OK", "USER")
        val before = rowCounts()
        val returned = AtomicBoolean(false)
        val propagated = AtomicBoolean(false)
        queryGate.armed.set(true)
        val loading = launch(Dispatchers.Default) {
            try {
                ProviderDetailLoader.loadDetail(db, "p1")
                returned.set(true)
            } catch (e: CancellationException) {
                propagated.set(true)
                throw e
            }
        }
        try {
            withTimeout(5_000L) { queryGate.entered.await() }
            assertTrue("Cancellation occurs after load entered its real facts SQL query", loading.isActive)
            loading.cancel(CancellationException("cancel in-flight facts query"))
        } finally {
            queryGate.release.countDown()
            withTimeout(5_000L) { loading.join() }
        }
        assertTrue(queryGate.intercepted.get())
        assertFalse("The executor barrier must be released explicitly, not time out", queryGate.timedOut.get())
        assertTrue("Caller must receive CancellationException", propagated.get())
        assertFalse("Neither partial success nor loadFailed shell may be returned", returned.get())
        assertEquals(stored, db.snapshotFactDao().evidence("cancel-fact"))
        assertEquals(before, rowCounts())
        // A fresh uncancelled real load remains usable; cancellation did not erase last-good.
        val retry = load()
        assertEquals(listOf("PAID"), retry.history.map { it.stateLabel })
        assertEquals(listOf("manual"), retry.history.map { it.sourceLabel })
        assertEquals(before, rowCounts())
    }

    // Shared true-Room fixture, deliberately limited to this loader's actual dependencies.
    private suspend fun seedConnection(
        provider: String = "p1", account: String = "a1", credential: String = "c1",
        endpoint: String = "e1", config: Boolean = true, anonymous: Boolean = false,
    ): ConnectionRecord {
        db.providerDao().insert(ProviderEntity(provider, "Provider $provider"))
        db.accountDao().insert(AccountEntity(account, provider))
        if (!anonymous) db.credentialDao().insert(CredentialEntity(credential, account))
        db.endpointDao().insert(EndpointEntity(endpoint, provider, BASE, URL, OP))
        val offering = "offering:$provider"
        db.offeringDao().insert(OfferingEntity(offering, provider, endpoint, "m-base", manual = false, source = "OFFICIAL"))
        if (!anonymous) {
            db.availabilityDao().insert(CredentialAvailabilityEntity("av:$provider", offering, account, credential))
        }
        if (config) {
            db.providerConfigDao().insert(ProviderManualConfig(
                providerId = provider, workflowId = "workflow:$provider",
                authMode = if (anonymous) AuthMode.NONE else AuthMode.BEARER,
                credentials = if (anonymous) emptyList() else listOf(SafeCredentialRef(credential, "Fixture inference ref")),
                anonymousConfirmed = anonymous,
            ).toEntity())
        }
        return repo.connections.first().single { it.providerId == provider && it.modelId == "m-base" }
    }

    private suspend fun addOffering(model: String) {
        db.offeringDao().insert(OfferingEntity("offering:$model", "p1", "e1", model, manual = false, source = "OFFICIAL"))
    }

    private fun sample(
        id: String, model: String = "m-base", at: Long = 1_000L,
        source: String = "PLAYGROUND", outcome: String = "NETWORK", group: String? = null,
    ) = SampleEntity(
        id = id, providerId = "p1", accountId = "a1", safeCredentialId = "c1", endpointId = "e1",
        providerModelId = model, operation = OP, group = group, source = source,
        requestStartedAt = at, completedAt = at, outcome = outcome,
    )

    private suspend fun seedNewerFinals(group: String? = null): List<SampleEntity> = (0..204).map { i ->
        sample(
            "new-${i.toString().padStart(3, '0')}", at = 100_000L + i * 60_000L,
            source = if (i % 2 == 0) "PLAYGROUND" else "MANUAL_TEST",
            outcome = if (i % 2 == 0) "NETWORK" else "CANCELLED", group = group,
        ).also { db.sampleDao().upsert(it) }
    }

    private suspend fun fact(
        id: String, scope: PricingScope, at: Long, state: String, status: String, source: String,
        kind: String = "PRICE", rawKey: String = scope.storageKey(),
    ): SnapshotFactEntity {
        db.evidenceDao().insert(EvidenceEntity(
            id, "request:$id", scope.providerId, scope.modelId, "OTHER", timestamp = at, source = source,
        ))
        return SnapshotFactEntity(
            evidenceId = id, scopeKey = rawKey, kind = kind, observedAt = at,
            snapshotId = null, state = state, status = status, confidence = "LOW",
            providerVersion = null, pricingVersion = null, itemPricingVersion = null,
            quotaPerUnit = null, groupRatio = null, statusObservedAt = null,
            remainingRaw = null, usedRaw = null, grantedRaw = null, unlimited = null,
            expiresAtRaw = null, expiryMeaning = null, expressionHash = null, priceUnit = null,
        ).also { db.snapshotFactDao().insert(it) }
    }

    private suspend fun load(
        selected: String? = null, group: String? = null, provider: String = "p1",
    ): ProviderDetailState = ProviderDetailLoader.loadDetail(
        db, provider, selectedConnectionId = selected, selectedGroup = group, nowMillis = 50_000_000L,
    ).also {
        assertFalse("Real load must not silently fail closed", it.loadFailed)
        assertTrue("Each provenance read must use at most 400 binds", queryGate.evidenceBatches.all { ids -> ids.size <= 400 })
    }

    private suspend fun anchored(credential: String) =
        db.sampleDao().customAnchorVerifiedModels("p1", "a1", credential, "e1", OP, null)

    private fun rowCounts(): Map<String, Long> {
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
            assertFalse("Every real fixture must satisfy Room foreign keys", it.moveToFirst())
        }
        return listOf("providers", "accounts", "credentials", "endpoints", "offerings",
            "credential_availability", "provider_configurations", "samples", "evidence",
            "snapshot_facts", "snapshot_heads", "price_snapshots", "quota_snapshots", "my_models").associateWith { table ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
                assertTrue(it.moveToFirst())
                it.getLong(0)
            }
        }
    }

    /** Deterministic in-flight query barrier, not a launch with an already-cancelled Job. */
    private class FactQueryGate {
        val armed = AtomicBoolean(false)
        val intercepted = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val evidenceBatches = ConcurrentLinkedQueue<List<String>>()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)

        fun onQuery(sql: String, bindArgs: List<Any?>) {
            if (sql.contains("FROM evidence WHERE id IN")) {
                val ids = bindArgs.map { it as String }
                evidenceBatches.add(ids)
            }
            if (sql.contains("FROM snapshot_facts") && sql.contains("scopeKey >=") &&
                armed.compareAndSet(true, false)) {
                intercepted.set(true)
                entered.complete(Unit)
                if (!release.await(5L, TimeUnit.SECONDS)) timedOut.set(true)
            }
        }
    }

    private class RoomDatabaseQueryCallback(private val gate: FactQueryGate) : androidx.room.RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            gate.onQuery(sqlQuery, bindArgs)
        }
    }

    private companion object {
        const val OP = "CHAT_COMPLETIONS"
        const val BASE = "https://provider-detail-history.fixture.invalid/v1"
        const val URL = "$BASE/chat/completions"
    }
}
