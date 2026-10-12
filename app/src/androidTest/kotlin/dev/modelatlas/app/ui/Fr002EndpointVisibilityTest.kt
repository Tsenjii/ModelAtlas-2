// FR-002 endpoint-aware pending My Models rows (PRODUCT_SPEC §4/§6/§18; Contract §23.1/§23.6).
// Landed repo file; CI pending. Implements Main's corrected-B port plan
// (/tmp/opencode/ai9-fr002-plan-review-result.md): mapper `includeHiddenFor` param +
// VM two-list partition; NO synthetic forced UNKNOWN; NO 2D dedupe.
// Baseline a3f581b/37229173570 (device683/core576/appJVM292 zero failures/errors/skips).
//
// PROPOSED PORTS (every call site is marked inline; everything else is current committed API at a3f581b):
//   P1  ModelRowMapper.buildPartitioned(..., includeHiddenFor: Set<MyModelKey>) splits
//       offering-level into ModelRowsPartition(visible, pendingMember) with the hidden predicate
//       `.filter { !it.hidden || MyModelKey(it.providerId, it.providerModelId) in includeHiddenFor }`
//       (plan review F6; build() keeps its 7-param signature delegating with empty includeHiddenFor).
//   P2  AtlasViewModel.pendingMemberRows: StateFlow<List<ModelRow>> — new partition output filled inside the
//       existing single loadModelRows IO job (plan review F5): one row per hidden member offering, real joins
//       only, nothing forced on the row.
//   P3  myModelRows = combine(_allModelRows, _pendingMemberRows, myModelKeys) { all, pending, keys ->
//       (all + pending).filter { MyModelKey(it.providerId, it.providerModelId) in keys } }. The draft reads
//       only the public myModelRows flow and asserts observable semantics, not internals.
// Draft assumptions (asserted as behavior, not new ports):
//   A1  _allModelRows keeps exactly current catalog semantics: hidden rows never enter it, member or not (M1/V3).
//   A2  Membership is key-level (providerId, providerModelId); N same-key hidden offerings surface as N pending
//       rows (anti-collapse, plan review F1 — no dedupe; my_models PK already guarantees ONE membership row).
//   A3  ADD writes offering + USER evidence only (AtlasRepository.addProviderModelCandidate:1576-1614): pending
//       rows are verified=false / selectedKeyUsable=false NATURALLY via UsableProjection; the draft asserts this,
//       it does not force any field (plan review F2).
//   A4  Toggle/remove stays key-level via public toggleMyModel (AtlasViewModel:2227); membership removal never
//       touches offerings/evidence/samples/facts/heads (D-006 / Contract §23.1 / MyModelsStore KDoc).
// Boundaries: real AtlasApplication + real AtlasViewModel + real Room/Keystore, mirroring the accepted
// CR-008-F2 MyModelsClockFlowTest harness. Public VM paths only — never calls private addMyModelExplicit /
// recordFor / loadModelRows. No inference (ADD branch stops before any engine call), no availability invention,
// no VERIFIED badge, no schema/DAO/config edits. Bundle enrichment on the fixture provider fails open against
// .fixture.invalid exactly as in the accepted clock test; no assertion depends on enrichment success.
// Deterministic: injected fixed timestamps; barrier helper pumps main while a compound flow predicate gates;
// no sleeps, no timeouts beyond the harness constant.

package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.EvidenceEntity
import dev.modelatlas.app.data.MANUAL_OFFERING_SOURCE_USER
import dev.modelatlas.app.data.ManualOfferingStore
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.PriceSnapshotEntity
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.SnapshotFactEntity
import dev.modelatlas.app.data.SnapshotHeadEntity
import dev.modelatlas.app.data.storageKey
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.PricingScope
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Fr002EndpointVisibilityTest {

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        viewModelStore = ViewModelStore()
        onMain {
            viewModel = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ===== M1 (RESOLVED PORT P1 — buildPartitioned) — mapper-level anti-collapse + default no-leak =====

    @Test
    fun mapperDefaultExcludesHiddenAndIncludeHiddenForAddsExactlyMemberKey() {
        val memberKey = MyModelKey("p1", MODEL_ADDED)
        val offerings = listOf(
            offering(id = "o-visible", endpointId = "e1", model = MODEL_ID, hidden = false, verified = true),
            offering(id = "manual:p1:e1:cand", endpointId = "e1", model = MODEL_ADDED, hidden = true),
            offering(id = "manual:p1:e2:cand", endpointId = "e2", model = MODEL_ADDED, hidden = true),
        )
        // Current default behavior (no port): hidden rows never surface.
        val defaultRows = ModelRowMapper.build(
            offerings = offerings, availabilities = emptyList(), selected = SelectedKey("", "", null),
        )
        assertEquals(listOf(MODEL_ID), defaultRows.map { it.providerModelId })
        // RESOLVED PORT P1: buildPartitioned splits visible vs pendingMember at offering level.
        val memberPartition = ModelRowMapper.buildPartitioned(
            offerings = offerings, availabilities = emptyList(), selected = SelectedKey("", "", null),
            includeHiddenFor = setOf(memberKey),
        )
        // Visible partition equals the default (hidden never leaks into catalog truth).
        assertEquals(defaultRows, memberPartition.visible)
        // Anti-collapse: both hidden endpoint offerings stay distinct pending rows (F1, no dedupe).
        assertEquals(2, memberPartition.pendingMember.size)
        assertTrue(memberPartition.pendingMember.all { it.providerModelId == MODEL_ADDED && it.providerId == "p1" })
        // A non-member key must never unhide anything.
        val otherPartition = ModelRowMapper.buildPartitioned(
            offerings = offerings, availabilities = emptyList(), selected = SelectedKey("", "", null),
            includeHiddenFor = setOf(MyModelKey("p1", "m-not-a-member")),
        )
        assertEquals(listOf(MODEL_ID), otherPartition.visible.map { it.providerModelId })
        assertTrue(otherPartition.pendingMember.isEmpty())
    }

    // ===== M2 (RESOLVED PORT P1 — buildPartitioned) — hidden member row keeps real scope-exact head pricing =====

    @Test
    fun mapperHiddenMemberRowKeepsRealScopeExactHeadPricing() {
        val memberKey = MyModelKey("p1", MODEL_ADDED)
        val candidate = offering(id = "manual:p1:e1:cand", endpointId = "e1", model = MODEL_ADDED, hidden = true)
        val head = PriceQuotaHead(
            kind = "PRICE", providerId = "p1", accountId = "a1", credentialId = null,
            modelId = MODEL_ADDED, group = null, endpointId = "e1", operation = "CHAT_COMPLETIONS",
            state = "PAID", inputPrice = 1.5, outputPrice = 4.5, cachePrice = null, observedAt = 1_000L,
        )
        val partition = ModelRowMapper.buildPartitioned(
            offerings = listOf(candidate),
            availabilities = emptyList(),
            selected = SelectedKey("a1", "", null),
            priceQuotaHeads = listOf(head),
            includeHiddenFor = setOf(memberKey),
        )
        assertTrue(partition.visible.isEmpty())
        val rows = partition.pendingMember
        assertEquals(1, rows.size)
        // Real head-derived pricing flows through the same join — nothing forced to UNKNOWN.
        assertEquals(PriceState.PAID, rows.single().priceState)
        assertEquals(1.5, rows.single().inputPrice!!, 1e-9)
        assertEquals(4.5, rows.single().outputPrice!!, 1e-9)
        // No availability rows exist (ADD writes none), so usable is false as a REAL fact.
        assertFalse(rows.single().selectedKeyUsable)
        assertFalse(rows.single().verified)
    }

    // ===== V1 — anti-collapse: two hidden endpoints → two pending rows, one membership =====

    @Test
    fun antiCollapseTwoHiddenEndpointsYieldTwoPendingRows() {
        val scenario = seedTwoEndpointPendingScenario("anti-collapse")
        val key = MyModelKey(scenario.providerId, MODEL_ADDED)
        // The seeded E2 offering landed after ADD's finally-refresh; one explicit public
        // reload makes the partition see both endpoint offerings before the barrier.
        onMain { viewModel.refreshModelRows() }
        val (all, pending, keys) = awaitConsistentSnapshot(key)
        // Two hidden offerings of the same (provider, model) on different endpoints each
        // surface as their own pending row — the list (not a set) has cardinality 2.
        assertEquals(2, pending.count { it.providerModelId == MODEL_ADDED })
        assertTrue(pending.filter { it.providerModelId == MODEL_ADDED }.all { it.providerId == scenario.providerId })
        assertTrue(keys.contains(key))
        assertTrue(all.none { it.providerModelId == MODEL_ADDED })
        // Membership is key-level: exactly ONE my_models row covers both endpoint offerings.
        runBlocking {
            assertEquals(
                1,
                app.database.myModelsDao().all().count {
                    it.providerId == scenario.providerId && it.providerModelId == MODEL_ADDED
                },
            )
        }
        // The corrected P3 combine carries visible member + both pending rows for the key.
        await {
            viewModel.myModelRows.first { rows ->
                rows.count { it.providerModelId == MODEL_ADDED } == 2 &&
                    rows.any { it.providerModelId == MODEL_ID }
            }
        }
    }

    // ===== V2 — exact-scope price heads retained on pending rows, no forced UNKNOWN, no cross borrow =====

    @Test
    fun pendingRowsRetainExactScopePriceHeadsWithoutCrossEndpointBorrow() {
        val scenario = seedTwoEndpointPendingScenario("heads")
        onMain { viewModel.refreshModelRows() }
        val pending = await {
            // PROPOSED PORT P2
            viewModel.pendingMemberRows.first { rows -> rows.count { it.providerModelId == MODEL_ADDED } == 2 }
        }
        val byInput = pending.filter { it.providerModelId == MODEL_ADDED }.associateBy { it.inputPrice }
        assertTrue("both endpoint heads must price their own row", byInput.containsKey(1.5) && byInput.containsKey(2.5))
        val e1 = byInput.getValue(1.5)
        val e2 = byInput.getValue(2.5)
        // Real heads retained — not forced UNKNOWN (plan review F2).
        assertEquals(PriceState.PAID, e1.priceState)
        assertEquals(PriceState.PAID, e2.priceState)
        assertEquals(1.5, e1.inputPrice!!, 1e-9)
        assertEquals(4.5, e1.outputPrice!!, 1e-9)
        assertEquals(2.5, e2.inputPrice!!, 1e-9)
        assertEquals(7.5, e2.outputPrice!!, 1e-9)
        // No cross-endpoint borrow: each endpoint's head prices stay on its own row.
        assertTrue(e1.outputPrice != 7.5 && e2.outputPrice != 4.5)
        // selectedKeyUsable stays a real fact: ADD writes no availability.
        assertFalse(e1.selectedKeyUsable)
        assertFalse(e2.selectedKeyUsable)
    }

    // ===== V3 — catalog not polluted; non-member hidden stray never surfaces =====

    @Test
    fun allModelRowsStaysCatalogCleanIncludingNonMemberStray() {
        val scenario = seedTwoEndpointPendingScenario("catalog")
        val before = await { viewModel.allModelRows.first { rows -> rows.any { it.providerModelId == MODEL_ID } } }
        // Third hidden offering (different model, NO membership) must never surface anywhere.
        seedStrayHiddenOffering(scenario)
        onMain { viewModel.refreshModelRows() }
        // Barrier note: a refresh whose visible content is unchanged never re-emits
        // allModelRows (StateFlow equality conflation), so a `first { !loading }` on
        // that flow can miss the completion edge. Gate on the loading flow itself
        // (true→false always emits), then read the settled value — same idiom as the
        // locked I-HIGH-1 tail. No assertion change.
        await { viewModel.modelRowsLoading.first { !it } }
        val all = viewModel.allModelRows.value
        // Catalog content-identity: same visible model ids before and after all seeding.
        assertEquals(before.map { it.providerModelId }.sorted(), all.map { it.providerModelId }.sorted())
        assertFalse(all.any { it.providerModelId == MODEL_ADDED })
        assertFalse(all.any { it.providerModelId == MODEL_STRAY })
        // Pending rows exist only for the member key.
        await {
            // PROPOSED PORT P2
            viewModel.pendingMemberRows.first { rows ->
                rows.count { it.providerModelId == MODEL_ADDED } == 2 &&
                    rows.none { it.providerModelId == MODEL_STRAY }
            }
        }
    }

    // ===== V4 — ADD path: no VERIFIED, no inference, no availability invention =====

    @Test
    fun addNeverVerifiesInfersOrInventsAvailability() {
        val scenario = seedTwoEndpointPendingScenario("no-verify")
        val evidenceBefore = countWhere("evidence", "providerId = ?", scenario.providerId)
        val samplesBefore = countWhere("samples", "providerId = ?", scenario.providerId)
        val availabilityBefore = countWhere(
            "credential_availability", "accountId = ?", scenario.accountId,
        )
        onMain { viewModel.refreshModelRows() }
        val pending = await {
            // PROPOSED PORT P2
            viewModel.pendingMemberRows.first { rows -> rows.count { it.providerModelId == MODEL_ADDED } == 2 }
        }
        // ADD branch completes with SUCCESS (unverified), never starts the engine.
        assertEquals("SUCCESS", viewModel.providerModelAction.value.status)
        pending.forEach { row ->
            assertFalse("pending rows are never VERIFIED", row.verified)
            assertFalse("pending rows carry no invented availability", row.selectedKeyUsable)
        }
        runBlocking {
            val candidates = app.database.offeringDao().byProvider(scenario.providerId)
                .filter { it.providerModelId == MODEL_ADDED }
            assertEquals(2, candidates.size)
            assertTrue(candidates.all { it.hidden })
            assertNull(
                "no verification evidence may exist before any Test-now",
                candidates.first().verificationEvidenceId,
            )
            assertTrue(candidates.none { it.verificationEvidenceId != null })
        }
        // No inference telemetry, no evidence growth beyond the seeded USER/price rows,
        // no availability rows invented for the hidden candidates.
        assertEquals(evidenceBefore, countWhere("evidence", "providerId = ?", scenario.providerId))
        assertEquals(samplesBefore, countWhere("samples", "providerId = ?", scenario.providerId))
        assertEquals(availabilityBefore, countWhere("credential_availability", "accountId = ?", scenario.accountId))
    }

    // ===== V5 — exact-key toggle/remove deletes membership rows only =====

    @Test
    fun toggleRemoveDeletesMembershipOnlyAndRetainsOfferingsEvidenceTelemetryHistory() {
        val scenario = seedTwoEndpointPendingScenario("toggle-remove")
        val key = MyModelKey(scenario.providerId, MODEL_ADDED)
        val offeringsBefore = runBlocking { app.database.offeringDao().byProvider(scenario.providerId) }
        val evidenceBefore = countWhere("evidence", "providerId = ?", scenario.providerId)
        val samplesBefore = countWhere("samples", "providerId = ?", scenario.providerId)
        val priceScopes = listOf(scenario.scope1, scenario.scope2)

        onMain { viewModel.toggleMyModel(scenario.providerId, MODEL_ADDED) }
        await { viewModel.myModelKeys.first { !it.contains(key) } }
        // Membership removed; the user-visible My Models view drops both pending rows
        // reactively (keys gate the P3 combine) — without any extra reload call here.
        await {
            viewModel.myModelRows.first { rows -> rows.none { it.providerModelId == MODEL_ADDED } }
        }

        // Offerings (both hidden candidates + visible base) survive untouched.
        assertEquals(offeringsBefore, runBlocking { app.database.offeringDao().byProvider(scenario.providerId) })
        // Evidence, telemetry (samples) and exact-scope history (heads/facts + price
        // snapshots) are all retained — D-006 permanent retention, membership-only delete.
        assertEquals(evidenceBefore, countWhere("evidence", "providerId = ?", scenario.providerId))
        assertEquals(samplesBefore, countWhere("samples", "providerId = ?", scenario.providerId))
        runBlocking {
            for (scope in priceScopes) {
                val head = app.database.snapshotFactDao().head(scope.storageKey(), "PRICE")
                assertEquals("PRICE head survives removal", scenario.headEvidenceIds[scope.endpointId], head?.latestEvidenceId)
                assertEquals("PAID", app.database.snapshotFactDao().evidence(head!!.latestEvidenceId)?.state)
            }
        }

        // Key-level re-add restores membership and pending rows without recreating
        // offerings or evidence (membership-only write; enrichment fails open).
        onMain { viewModel.toggleMyModel(scenario.providerId, MODEL_ADDED, nowMillis = 1_700_000_001_000L) }
        await { viewModel.myModelKeys.first { it.contains(key) } }
        onMain { viewModel.refreshModelRows() }
        await {
            // PROPOSED PORT P2
            viewModel.pendingMemberRows.first { rows -> rows.count { it.providerModelId == MODEL_ADDED } == 2 }
        }
        assertEquals(offeringsBefore, runBlocking { app.database.offeringDao().byProvider(scenario.providerId) })
        assertEquals(evidenceBefore, countWhere("evidence", "providerId = ?", scenario.providerId))
    }

    // ===== V6 — deterministic first-emission barrier (plan review F5 ordering concern) =====

    @Test
    fun deterministicBarrierJoinsPendingKeysAndCatalogInOneConsistentSnapshot() {
        val scenario = seedTwoEndpointPendingScenario("barrier")
        val key = MyModelKey(scenario.providerId, MODEL_ADDED)
        onMain { viewModel.refreshModelRows() }
        // One combine().first{} over all three sources is the deterministic barrier: it
        // resumes only when keys, pending rows and catalog-clean allModelRows are mutually
        // consistent in a SINGLE emission. No sleep, and no ordering assumption about
        // whether the myModelKeys Room emission or the load job lands first (F5): any
        // intermediate inconsistent emission simply fails the predicate and keeps waiting.
        val (all, pending, keys) = await {
            combine(viewModel.allModelRows, viewModel.pendingMemberRows, viewModel.myModelKeys) { a, p, k ->
                Triple(a, p, k)
            }.first { (a, p, k) ->
                k.contains(key) &&
                    p.count { it.providerModelId == MODEL_ADDED } == 2 &&
                    a.none { it.providerModelId == MODEL_ADDED } &&
                    a.any { it.providerModelId == MODEL_ID }
            }
        }
        assertEquals(2, pending.count { it.providerModelId == MODEL_ADDED })
        assertTrue(keys.contains(key))
        assertTrue(all.any { it.providerModelId == MODEL_ID })
        assertFalse("partition load must not fail closed", viewModel.modelRowsLoadFailed.value)
    }

    // ===== scenario builder (fixtures only; every real user action goes through production paths) =====

    private data class Scenario(
        val providerId: String,
        val accountId: String,
        val credentialId: String,
        val endpointId1: String,
        val endpointId2: String,
        val scope1: PricingScope,
        val scope2: PricingScope,
        val headEvidenceIds: Map<String, String>,
    )

    /**
     * Real VM flow up to the hidden E1 candidate (`saveConnection` auto-join + public
     * `addProviderModel`), then direct Room seeding for the one state public API cannot
     * produce here — a SECOND endpoint under the SAME provider with an identical-shape
     * hidden USER candidate (`ManualOfferingStore.createHiddenOffering` + USER evidence,
     * exactly what addProviderModelCandidate writes) plus one scope-exact PRICE head per
     * endpoint (evidence → price snapshot → fact → head, the real loadPriceQuotaHeads chain).
     */
    private fun seedTwoEndpointPendingScenario(label: String): Scenario = runBlocking {
        val fixture = saveFixtureAndAwaitJoin(label)
        val t = 1_700_000_000_000L
        onMain { viewModel.addProviderModel(fixture.providerId, MODEL_ADDED) }
        await { viewModel.providerModelAction.first { it.status == "SUCCESS" } }
        await { viewModel.myModelKeys.first { it.contains(MyModelKey(fixture.providerId, MODEL_ADDED)) } }

        val db = app.database
        val endpoint2 = "fr002-e2-${fixture.providerId}"
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = endpoint2, providerId = fixture.providerId,
                baseUrl = BASE_URL_2, endpointUrl = "$BASE_URL_2/chat/completions",
            ),
        )
        val evidence2 = "user-offering:fr002-e2-${UUID.randomUUID()}"
        val draft = ManualOfferingStore.createHiddenOffering(
            providerId = fixture.providerId, endpointId = endpoint2,
            providerModelId = MODEL_ADDED, source = MANUAL_OFFERING_SOURCE_USER,
            evidenceId = evidence2, observedAt = t,
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = draft.id, providerId = draft.providerId, endpointId = draft.endpointId,
                providerModelId = draft.providerModelId, manual = draft.manual,
                source = draft.source, hidden = draft.hidden,
                verificationEvidenceId = draft.verificationEvidenceId,
            ),
        )
        db.evidenceDao().insert(
            EvidenceEntity(
                id = evidence2, requestId = draft.id, providerId = fixture.providerId,
                modelId = MODEL_ADDED, outcome = "OTHER", status = null,
                timestamp = t, source = MANUAL_OFFERING_SOURCE_USER, version = 1,
            ),
        )
        // Exact-scope PRICE heads: one per endpoint, fixture account, official credential-null
        // (matches matchesPriceHead: group null==null, endpoint exact, account exact).
        val scope1 = PricingScope(fixture.providerId, fixture.accountId, null, MODEL_ADDED, null, fixture.endpointId, "CHAT_COMPLETIONS")
        val scope2 = PricingScope(fixture.providerId, fixture.accountId, null, MODEL_ADDED, null, endpoint2, "CHAT_COMPLETIONS")
        seedPriceHead(scope1, evidenceId = "ev-price-e1-$t", input = "1.5", output = "4.5", at = t)
        seedPriceHead(scope2, evidenceId = "ev-price-e2-$t", input = "2.5", output = "7.5", at = t)
        // One CANCELLED telemetry row for the candidate: V5 retention assertions must see it
        // survive membership removal, and a CANCELLED sample can never verify anything.
        db.sampleDao().upsert(
            SampleEntity(
                id = "sample-fr002-$t", providerId = fixture.providerId,
                accountId = fixture.accountId, safeCredentialId = fixture.credentialId,
                endpointId = fixture.endpointId, providerModelId = MODEL_ADDED,
                requestStartedAt = t, completedAt = t, outcome = "CANCELLED",
            ),
        )
        Scenario(
            providerId = fixture.providerId,
            accountId = fixture.accountId,
            credentialId = fixture.credentialId,
            endpointId1 = fixture.endpointId,
            endpointId2 = endpoint2,
            scope1 = scope1,
            scope2 = scope2,
            headEvidenceIds = mapOf(fixture.endpointId to "ev-price-e1-$t", endpoint2 to "ev-price-e2-$t"),
        )
    }

    private fun seedStrayHiddenOffering(scenario: Scenario) = runBlocking {
        val db = app.database
        val evidence = "user-offering:fr002-stray-${UUID.randomUUID()}"
        val draft = ManualOfferingStore.createHiddenOffering(
            providerId = scenario.providerId, endpointId = scenario.endpointId2,
            providerModelId = MODEL_STRAY, source = MANUAL_OFFERING_SOURCE_USER,
            evidenceId = evidence, observedAt = 1_700_000_000_500L,
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = draft.id, providerId = draft.providerId, endpointId = draft.endpointId,
                providerModelId = draft.providerModelId, manual = draft.manual,
                source = draft.source, hidden = draft.hidden,
                verificationEvidenceId = draft.verificationEvidenceId,
            ),
        )
        db.evidenceDao().insert(
            EvidenceEntity(
                id = evidence, requestId = draft.id, providerId = scenario.providerId,
                modelId = MODEL_STRAY, outcome = "OTHER", status = null,
                timestamp = 1_700_000_000_500L, source = MANUAL_OFFERING_SOURCE_USER, version = 1,
            ),
        )
    }

    /** evidence → price_snapshots → snapshot_facts → snapshot_heads, the exact chain loadPriceQuotaHeads reads. */
    private fun seedPriceHead(scope: PricingScope, evidenceId: String, input: String, output: String, at: Long) =
        runBlocking {
            val db = app.database
        val snapshotId = "ps-${scope.endpointId}-$at"
        db.evidenceDao().insert(
            EvidenceEntity(
                id = evidenceId, requestId = "request:$evidenceId", providerId = scope.providerId,
                modelId = scope.modelId, outcome = "OTHER", status = null,
                timestamp = at, source = "OFFICIAL", version = 1,
            ),
        )
        db.priceDao().insert(
            PriceSnapshotEntity(
                id = snapshotId, providerId = scope.providerId, accountId = scope.accountId,
                credentialId = scope.credentialId, modelId = scope.modelId, group = scope.group,
                endpointId = scope.endpointId, operation = scope.operation, observedAt = at,
                input = input, output = output, source = "OFFICIAL", evidenceId = evidenceId,
            ),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId, scopeKey = scope.storageKey(), kind = "PRICE",
                observedAt = at, snapshotId = snapshotId, state = "PAID", status = "OK",
                confidence = "LOW", providerVersion = null, pricingVersion = null,
                itemPricingVersion = null, quotaPerUnit = null, groupRatio = null,
                statusObservedAt = null, remainingRaw = null, usedRaw = null, grantedRaw = null,
                unlimited = null, expiresAtRaw = null, expiryMeaning = null,
                expressionHash = null, priceUnit = null,
            ),
        )
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(
                scopeKey = scope.storageKey(), kind = "PRICE", latestEvidenceId = evidenceId,
                latestObservedAt = at, lastGoodSnapshotId = snapshotId, lastGoodObservedAt = at,
            ),
        )
        }

    /** Deterministic compound barrier: keys + pending rows + catalog-clean allModelRows in one emission. */
    private fun awaitConsistentSnapshot(key: MyModelKey): Triple<List<ModelRow>, List<ModelRow>, Set<MyModelKey>> =
        await {
            // PROPOSED PORT P2 (viewModel.pendingMemberRows)
            combine(viewModel.allModelRows, viewModel.pendingMemberRows, viewModel.myModelKeys) { a, p, k ->
                Triple(a, p, k)
            }.first { (a, p, k) ->
                k.contains(key) &&
                    p.count { it.providerModelId == MODEL_ADDED } == 2 &&
                    a.none { it.providerModelId == MODEL_ADDED } &&
                    a.any { it.providerModelId == MODEL_ID }
            }
        }

    // ===== harness (mirrors accepted MyModelsClockFlowTest) =====

    private data class Fixture(
        val providerId: String,
        val accountId: String,
        val credentialId: String,
        val endpointId: String,
    )

    private fun saveFixtureAndAwaitJoin(label: String): Fixture {
        val name = "My Models Endpoint Draft $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        await { viewModel.myModelKeys.first { it.contains(MyModelKey(item.providerId, MODEL_ID)) } }
        return Fixture(
            providerId = item.providerId,
            accountId = singleString("SELECT accountId FROM accounts WHERE providerId = ?", item.providerId),
            credentialId = item.credentialId,
            endpointId = singleString("SELECT endpointId FROM endpoints WHERE providerId = ?", item.providerId),
        )
    }

    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val ids = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            // Each statement binds its own single providerId IN (...) list once;
            // outer IN clauses are plain subqueries with no placeholders.
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM snapshot_heads WHERE scopeKey IN (SELECT scopeKey FROM snapshot_facts WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId IN ($list)))", ids)
            db.execSQL("DELETE FROM snapshot_facts WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId IN ($list))", ids)
            db.execSQL("DELETE FROM price_snapshots WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM evidence WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_notices WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_events WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", ids)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", ids)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", ids)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun countWhere(table: String, where: String, arg: String): Long =
        app.database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM `$table` WHERE $where", arrayOf(arg))
            .use { cursor ->
                check(cursor.moveToFirst()) { "count query returned no rows for $table" }
                cursor.getLong(0)
            }

    private fun singleString(sql: String, arg: String): String =
        app.database.openHelper.readableDatabase.query(sql, arrayOf(arg)).use { cursor ->
            check(cursor.moveToFirst()) { "fixture query returned no rows: $sql" }
            cursor.getString(0)
        }

    private fun offering(
        id: String,
        endpointId: String,
        model: String,
        hidden: Boolean,
        providerId: String = "p1",
        verified: Boolean = false,
    ) = OfferingRef(
        id = id, providerId = providerId, endpointId = endpointId, providerModelId = model,
        operation = "CHAT_COMPLETIONS", manual = true, source = if (hidden) "USER" else "OFFICIAL",
        hidden = hidden, verified = verified,
    )

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private fun <T> await(timeoutMillis: Long = TIMEOUT_MS, block: suspend () -> T): T {
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { block() } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    onMain { }
                }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { viewModel.state.first(predicate) }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://fr002-draft.fixture.invalid/v1"
        const val BASE_URL_2 = "https://fr002-draft-e2.fixture.invalid/v1"
        const val MODEL_ID = "fr002-draft-base-model"
        const val MODEL_ADDED = "fr002-draft-candidate-model"
        const val MODEL_STRAY = "fr002-draft-stray-model"
        const val FIXTURE_SECRET = "fr002-draft-fixture-secret-never-persisted"
    }
}
