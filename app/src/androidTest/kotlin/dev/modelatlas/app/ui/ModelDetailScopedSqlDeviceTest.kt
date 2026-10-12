package dev.modelatlas.app.ui

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.CanonicalModelEntity
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.EvidenceEntity
import dev.modelatlas.app.data.ExternalObservationEntity
import dev.modelatlas.app.data.MyModelEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.PriceEventEntity
import dev.modelatlas.app.data.PriceSnapshotEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.ProviderModelAliasEntity
import dev.modelatlas.app.data.QuotaEventEntity
import dev.modelatlas.app.data.QuotaSnapshotEntity
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.SnapshotFactEntity
import dev.modelatlas.app.data.SnapshotHeadEntity
import dev.modelatlas.app.data.storageKey
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * CR-006 T1–T4 / MODEL-009 / X-001, X-004, X-005, X-006.
 * PRODUCT_SPEC §6/7/10/11; Intelligence Contract §13/23.3/23.6.
 * Real, isolated Room14 and the production three-argument loader. Fixtures are test-only,
 * safe references, never product data. No UI, network, sleeps, or legacy-helper substitutes.
 */
@RunWith(AndroidJUnit4::class)
class ModelDetailScopedSqlDeviceTest {
    private lateinit var db: AtlasDatabase
    private val queries = CopyOnWriteArrayList<SqlRead>()
    private var lastReads: List<SqlRead> = emptyList()
    @Volatile private var captureReads = false

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, args ->
                    if (captureReads) queries += SqlRead(sql, args.toList())
                },
                Executor { it.run() },
            ).build()
        db.withTransaction {
            for (provider in listOf(A, B)) {
                db.providerDao().insert(ProviderEntity(provider, "CR006 fixture $provider"))
                for ((suffix, protocol) in listOf("chat" to CHAT, "responses" to RESPONSES)) {
                    db.endpointDao().insert(
                        EndpointEntity(
                            endpointId = "$provider-$suffix", providerId = provider,
                            baseUrl = "https://cr006.fixture.invalid/v1",
                            endpointUrl = "https://cr006.fixture.invalid/v1/$suffix", protocol = protocol,
                        ),
                    )
                }
            }
            for ((account, provider) in listOf(AA to A, AA2 to A, BA to B)) {
                db.accountDao().insert(AccountEntity(account, provider))
            }
            for ((credential, account) in listOf(AK to AA, AK2 to AA, A2K to AA2, BK to BA)) {
                db.credentialDao().insert(CredentialEntity(credential, account, label = "Safe fixture ref"))
            }
            for (canonical in listOf(C1, C2, SHARED, EMPTY)) {
                db.canonicalModelDao().insert(CanonicalModelEntity(canonical, canonical))
            }
            // Durable sentinels: detail reads must neither add membership nor touch old history/heads.
            seedPair("preserved", preservedScope, 1L)
            db.myModelsDao().put(MyModelEntity(A, "preserved-model", 1L, "USER"))
            db.priceEventDao().insert(
                PriceEventEntity("preserved-price-event", preservedScope.storageKey(),
                    fromState = "UNKNOWN", toState = "PAID", observedAt = 1L, source = "USER"),
            )
            db.quotaEventDao().insert(
                QuotaEventEntity("preserved-quota-event", preservedScope.storageKey(),
                    fromState = "UNKNOWN", toState = "QUOTA_LOW", observedAt = 1L, source = "USER"),
            )
            seedSample("preserved-sample", A, "preserved-model", null, 1L)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rawIdCollisionAcrossDifferentCanonicalsNeverBorrowsOtherProvider() = runBlocking {
        alias(A, "collision", C1)
        alias(B, "collision", C2)
        val rowA = row(A, "collision", C1)
        val rowB = row(B, "collision", C2)
        val a = seedPair("raw-a", scope(A, "collision"), 10L, priceState = PriceState.PAID)
        val b = seedPair("raw-b", scope(B, "collision"), 20L, priceState = PriceState.FREE_NOW)
        seedSample("raw-a-final", A, "collision", C1, 100L)
        seedSample("raw-b-final", B, "collision", C2, 200L)
        // A canonical string is not a global raw model id / implicit fallback.
        seedPair("canonical-raw-decoy", scope(A, C1), 900L)
        seedSample("canonical-raw-final-decoy", A, C1, null, 901L)

        val detailA = readOnly { ModelDetailLoader.loadDetail(db, rowA, selected = null) }
        assertDetailSnapshots(detailA, listOf(a))
        assertTelemetry(detailA, listOf(101L))
        val detailB = readOnly { ModelDetailLoader.loadDetail(db, rowB, selected = null) }
        assertDetailSnapshots(detailB, listOf(b))
        assertTelemetry(detailB, listOf(201L))
        assertEquals(C1, detailA.canonicalId)
        assertEquals(C2, detailB.canonicalId)
    }

    @Test
    fun sameCanonicalUsesOwnedAliasesAndExplicitRowFallbackWithFullScopeAndProvenance() = runBlocking {
        alias(A, "owned-one", SHARED)
        alias(A, "owned-two", SHARED)
        alias(B, "owned-one", SHARED)
        alias(A, "unrelated", C2)
        val rowA = row(A, "explicit-a", SHARED) // No alias: the requesting pair must survive.
        val rowB = row(B, "explicit-b", SHARED)
        val a = listOf(
            seedPair("alias-a-one", scope(A, "owned-one", AA, AK, "vip"), 30L,
                priceSource = "USER", quotaSource = "USER"),
            seedPair("alias-a-two", scope(A, "owned-two", AA, AK, "研究🙂| :", RESPONSES), 40L,
                priceState = PriceState.FREE_NOW, quotaState = QuotaState.PUBLIC_POOL),
            seedPair("explicit-a", scope(A, "explicit-a", AA, AK, ""), 50L,
                priceSource = "unrecognised-fixture-source", quotaSource = "unrecognised-fixture-source"),
        )
        val b = listOf(
            seedPair("alias-b-one", scope(B, "owned-one", BA, BK, null), 60L),
            seedPair("explicit-b", scope(B, "explicit-b", BA, BK, "vip", RESPONSES), 70L),
        )
        seedPair("unrelated-a", scope(A, "unrelated", AA, AK), 9_000L)
        seedPair("canonical-string-a", scope(A, SHARED, AA, AK), 9_001L)
        seedSample("alias-a-final", A, "owned-one", SHARED, 300L)
        seedSample("explicit-a-final", A, "explicit-a", null, 400L)
        // Canonical-only history leg remains valid within A; its raw id need not be a current alias.
        seedSample("canonical-only-a-final", A, "historical-id", SHARED, 500L)
        seedSample("alias-b-final", B, "owned-one", SHARED, 600L)
        seedSample("explicit-b-final", B, "explicit-b", null, 700L)
        seedSample("unrelated-a-final", A, "unrelated", C2, 9_002L)
        db.externalObservationDao().upsert(
            ExternalObservationEntity(
                observationId = "shared-external", source = "fixture-external", externalModelId = "ref",
                canonicalModelId = SHARED, payloadJson = "{}", fetchedAt = 800L,
                ttlMillis = 1L, confidence = "LOW",
            ),
        )

        val detailA = readOnly { ModelDetailLoader.loadDetail(db, rowA, SelectedKey(AA, AK, "selected")) }
        assertDetailSnapshots(detailA, a)
        assertTelemetry(detailA, listOf(301L, 401L, 501L)) // Canonical + model legs deduplicate.
        val detailB = readOnly { ModelDetailLoader.loadDetail(db, rowB, SelectedKey(BA, BK, "selected")) }
        assertDetailSnapshots(detailB, b)
        assertTelemetry(detailB, listOf(601L, 701L))
        for (detail in listOf(detailA, detailB)) {
            assertEquals(
                listOf(DetailHistoryEvent(800L, "EXTERNAL", "LOW", sourceLabel = "external")),
                detail.history.filter { it.section == "EXTERNAL" },
            )
        }
        assertEquals(setOf("manual", "official", ""), detailA.priceHistory.map { it.sourceLabel }.toSet())
        assertEquals(setOf("vip", "研究🙂| :", ""), detailA.quotaPools.map { it.group }.toSet())
    }

    @Test
    fun quotaPoolsRespectGlobalAccountTokenNullEmptyAndAnonymousSelection() = runBlocking {
        val target = row(A, "quota-model", C1)
        data class PoolScope(val tag: String, val account: String?, val credential: String?)
        val passports = listOf(
            PoolScope("global-nn", null, null), PoolScope("global-ne", null, ""),
            PoolScope("global-en", "", null), PoolScope("global-ee", "", ""),
            PoolScope("account-null", AA, null), PoolScope("account-empty", AA, ""),
            PoolScope("token-own", AA, AK), PoolScope("token-sibling", AA, AK2),
            PoolScope("other-account-pool", AA2, null), PoolScope("other-account-token", AA2, A2K),
            PoolScope("absent-account-foreign-key", null, BK),
            PoolScope("empty-account-foreign-key", "", BK),
            PoolScope("wrong-account-same-key", AA2, AK),
            PoolScope("own-account-foreign-key", AA, BK),
        )
        val fixtures = linkedMapOf<String, MutableList<DetailQuotaPool>>()
        for ((index, passport) in passports.withIndex()) {
            for ((modelIndex, model) in listOf("", "quota-model").withIndex()) {
                val s = scope(A, model, passport.account, passport.credential,
                    if (modelIndex == 0) null else "other-group", if (modelIndex == 0) CHAT else RESPONSES)
                val kind = if (passport.credential.isNullOrEmpty()) "ACCOUNT_QUOTA" else "TOKEN_QUOTA"
                val expected = seedQuota(
                    "pool-${passport.tag}-$modelIndex", s, 1_000L + index * 2 + modelIndex,
                    state = QuotaState.entries[index % QuotaState.entries.size], kind = kind,
                    source = if (index % 2 == 0) "USER" else "newapi/api/user/self",
                    value = index.toLong(), unknownNumbers = index == 0,
                ).pool!!
                fixtures.getOrPut(passport.tag) { mutableListOf() } += expected
            }
        }
        // Exact raw model and global pools from B are both newer, but are never relevant to A.
        seedQuota("foreign-global", scope(B, ""), 90_000L)
        seedQuota("foreign-model", scope(B, "quota-model", BA, BK), 90_001L)
        val globals = listOf("global-nn", "global-ne", "global-en", "global-ee")
        val account = listOf("account-null", "account-empty")
        val vectors = listOf(
            SelectedKey(AA, AK, "selected-group") to (globals + account + "token-own"),
            SelectedKey(AA, AK2, null) to (globals + account + "token-sibling"),
            SelectedKey(AA2, A2K, "") to (globals + listOf("other-account-pool", "other-account-token")),
            SelectedKey(AA, "", "selected-group") to (globals + account), // Anonymous != wildcard key.
            null to globals,
            SelectedKey("", "", null) to globals,
        )
        for ((selected, tags) in vectors) {
            val detail = readOnly { ModelDetailLoader.loadDetail(db, target, selected) }
            val expected = tags.flatMap { fixtures.getValue(it) }
            assertEquals("pool count for $selected", expected.size, detail.quotaPools.size)
            assertEquals("exact passports/values for $selected", expected.toSet(), detail.quotaPools.toSet())
            assertTrue(detail.priceHistory.isEmpty())
        }
    }

    @Test
    fun sevenHundredSnapshotsReturnExactNewestFiveHundredWithStableCutoffAndFactStates() = runBlocking {
        val target = row(A, "bounded", C1)
        val s = scope(A, "bounded", AA, AK, "vip")
        val fixtures = db.withTransaction {
            (0 until 700).map { i ->
                seedPair(
                    "bounded-${padded(i)}", s, 10_000L + i / 3,
                    priceState = PriceState.entries[i % PriceState.entries.size],
                    quotaState = QuotaState.entries[i % QuotaState.entries.size],
                    priceSource = listOf("USER", "newapi/api/pricing", "unrecognised")[i % 3],
                    quotaSource = listOf("USER", "newapi/api/usage/token", "unrecognised")[i % 3],
                    value = i.toLong(),
                )
            }
        }
        // 499 rows at newer timestamps; id ASC must retain 198, rejecting 199/200 at the tie.
        val newestIndices = listOf(198) + (201..699).toList()
        val expected = newestIndices.map { fixtures[it] }
        val detail = readOnly { ModelDetailLoader.loadDetail(db, target, SelectedKey(AA, AK, "vip")) }
        assertDetailSnapshots(detail, expected)
        assertEquals(500, detail.priceHistory.size)
        assertEquals(500, detail.quotaPools.size)
        assertEquals(newestIndices.map { 10_000L + it / 3 }.sorted(), detail.priceHistory.map { it.observedAt })
        assertEquals(
            newestIndices.map { 10_000L + it / 3 }.sortedDescending(),
            detail.quotaPools.map { it.observedAt },
        )
        assertFactReads(expected)
        assertEquals(2, lastReads.count { it.isPriceFactRead() }) // 400 + 100 snapshot IDs.
        assertEquals(2, lastReads.count { it.isQuotaFactRead() })
    }

    @Test
    fun twelveHundredAliasesPerProviderChunkAtBoundariesAndKeepExactScopedNewestResults() = runBlocking {
        val rows = listOf(row(A, "alias-0000", SHARED), row(B, "alias-0000", SHARED))
        val fixtures = linkedMapOf<String, List<SnapshotFixture>>()
        db.withTransaction {
            for (provider in listOf(A, B)) {
                val account = if (provider == A) AA else BA
                val credential = if (provider == A) AK else BK
                fixtures[provider] = (0 until 1_200).map { i ->
                    val model = "alias-${padded(i)}"
                    alias(provider, model, SHARED)
                    // Permutation spreads the newest subset across every SQL chunk, not just its tail.
                    val rank = (i * 137) % 1_200
                    val base = if (provider == A) 1_000_000L else 10_000_000L
                    seedSample("$provider-final-${padded(i)}", provider, model, SHARED, base + 20_000 + rank)
                    seedPair("$provider-alias-${padded(i)}", scope(provider, model, account, credential, "g-$i"),
                        base + rank, priceState = if (i % 2 == 0) PriceState.PAID else PriceState.FREE_NOW,
                        quotaState = if (i % 2 == 0) QuotaState.QUOTA_LOW else QuotaState.QUOTA_AVAILABLE,
                        value = i.toLong())
                }
                seedSample("$provider-running-decoy", provider, "alias-0000", SHARED, 99_000_000L, final = false)
            }
        }
        assertEquals(2_400, db.providerModelAliasDao().byCanonical(SHARED).size)

        // Named scoped ports exercise the IN boundary vectors; full production calls follow below.
        for (size in listOf(400, 401, 800, 801)) {
            val selected = fixtures.getValue(A).take(size)
            val modelIds = selected.map { it.price!!.modelId }.toSet()
            val expected = selected.sortedByDescending { it.price!!.observedAt }.take(500)
            val result = readOnly {
                ModelDetailLoader.loadPricePointsScoped(db, A, modelIds) to
                    ModelDetailLoader.loadQuotaPoolsScoped(db, A, modelIds, SelectedKey(AA, AK, null))
            }
            assertEquals("price cap at $size aliases", minOf(size, 500), result.first.size)
            assertEquals("quota cap at $size aliases", minOf(size, 500), result.second.size)
            assertEquals(expected.map { it.price }.toSet(), result.first.toSet())
            assertEquals(expected.map { it.pool }.toSet(), result.second.toSet())
            assertFactReads(expected)
            assertTrue("price queries must split $size aliases", lastReads.count { it.from("price_snapshots") } >= 2)
            assertTrue("quota queries must split $size aliases", lastReads.count { it.from("quota_snapshots") } >= 2)
        }

        for (target in rows) {
            val owned = fixtures.getValue(target.providerId)
            val selected = if (target.providerId == A) SelectedKey(AA, AK) else SelectedKey(BA, BK)
            val expected = owned.sortedByDescending { it.price!!.observedAt }.take(500)
            val detail = readOnly { ModelDetailLoader.loadDetail(db, target, selected) }
            assertDetailSnapshots(detail, expected)
            assertFactReads(expected)
            val base = if (target.providerId == A) 1_000_000L else 10_000_000L
            assertTelemetry(detail, (1_000..1_199).map { base + 20_001 + it })
            assertEquals(200, detail.history.count { it.section == "TELEMETRY" })
            val sampleReads = lastReads.filter { it.from("samples") }
            assertTrue(sampleReads.size >= 5) // Independent canonical leg plus <=398-model chunks.
            assertTrue(sampleReads.all { it.sql.contains("completedAt", ignoreCase = true) &&
                it.sql.contains("LIMIT", ignoreCase = true) && it.args.firstOrNull() == target.providerId })
            assertTrue(lastReads.filter { it.from("price_snapshots") || it.from("quota_snapshots") }
                .all { it.args.firstOrNull() == target.providerId })
        }
    }

    @Test
    fun noRowsStayUnknownAndMissingFactDoesNotBecomeZeroOrFreeOrWriteMembership() = runBlocking {
        val target = row(A, "no-data", EMPTY)
        val empty = readOnly { ModelDetailLoader.loadDetail(db, target, selected = null) }
        assertEquals(EMPTY, empty.canonicalId)
        assertEquals(PriceState.UNKNOWN, empty.priceState)
        assertEquals(QuotaState.UNKNOWN, empty.quotaState)
        assertEquals(DetailPerformance(), empty.performance)
        assertTrue(empty.priceHistory.isEmpty())
        assertTrue(empty.quotaPools.isEmpty())
        assertTrue(empty.history.isEmpty())
        assertNull(empty.priceChangeCount)
        assertNull(empty.priceChangedAt)
        assertNull(empty.freeSinceAt)

        val s = scope(A, "no-data")
        seedPrice("price-no-fact", s, 20L, withFact = false, unknownNumbers = true)
        seedQuota("quota-no-fact", s, 21L, withFact = false, unknownNumbers = true)
        val missing = readOnly { ModelDetailLoader.loadDetail(db, target, selected = null) }
        val point = missing.priceHistory.single()
        assertEquals(PriceState.UNKNOWN, point.state)
        assertNull(point.inputPrice)
        assertNull(point.outputPrice)
        assertNull(point.fixedPrice)
        assertNull(point.cachePrice)
        assertTrue(missing.quotaPools.isEmpty()) // No fact/kind cannot fabricate a pool.
        assertEquals(PriceState.UNKNOWN, missing.priceState)
        assertEquals(QuotaState.UNKNOWN, missing.quotaState)
        assertNull(missing.freeSinceAt)
        assertEquals(0, db.myModelsDao().count(A, "no-data"))
    }

    @Test
    fun authoritativeAliasContradictionRejectsStaleRowAndFreshCanonicalReceivesSameFixtures() = runBlocking {
        val raw = "remapped-anchor"
        alias(A, raw, C2)
        val staleRow = row(A, raw, C1).copy(priceState = PriceState.UNKNOWN, quotaState = QuotaState.UNKNOWN)
        val fixture = seedPair(
            "remapped-anchor", scope(A, raw, AA, AK, "vip"), 100L,
            priceState = PriceState.FREE_NOW, quotaState = QuotaState.QUOTA_AVAILABLE,
            priceSource = "USER", quotaSource = "newapi/api/usage/token",
        )
        seedSample("remapped-anchor-final", A, raw, C2, 300L)
        val selected = SelectedKey(AA, AK, "vip")

        // An authoritative alias to C2 contradicts C1: explicit-row fallback cannot donate C2 facts.
        val stale = readOnly { ModelDetailLoader.loadDetail(db, staleRow, selected) }
        assertEquals(C1, stale.canonicalId)
        assertDetailSnapshots(stale, emptyList())
        assertTelemetry(stale, emptyList())
        assertTrue(stale.history.isEmpty())
        assertFactReads(emptyList())
        assertEquals(PriceState.UNKNOWN, stale.priceState)
        assertEquals(QuotaState.UNKNOWN, stale.quotaState)
        assertNull(stale.freeSinceAt)

        // Only the row's canonical anchor changes; the positive control reads the very same stored rows.
        val freshRow = staleRow.copy(canonicalId = C2)
        val fresh = readOnly { ModelDetailLoader.loadDetail(db, freshRow, selected) }
        assertEquals(C2, fresh.canonicalId)
        assertDetailSnapshots(fresh, listOf(fixture))
        assertTelemetry(fresh, listOf(301L))
        assertFactReads(listOf(fixture))
        // Primary states still come from the input row, not the newly matched history's FREE/AVAILABLE facts.
        assertEquals(PriceState.UNKNOWN, fresh.priceState)
        assertEquals(QuotaState.UNKNOWN, fresh.quotaState)
        assertNull(fresh.freeSinceAt)
    }

    @Test
    fun quotaRelevanceBeforeLimitRetainsOlderSelectedModelAndAccountPoolsAmongNewerForeignRows() = runBlocking {
        val raw = "crowded-model"
        alias(A, raw, C1)
        val target = row(A, raw, C1)
        val expected = listOf(
            seedQuota(
                "crowded-selected-model", scope(A, raw, AA, AK, "vip"), 10L,
                state = QuotaState.QUOTA_LOW, value = 700L,
            ),
            seedQuota(
                "crowded-selected-account", scope(A, "", AA, null, null), 20L,
                state = QuotaState.QUOTA_AVAILABLE, source = "USER", kind = "ACCOUNT_QUOTA", value = 900L,
            ),
        )
        db.withTransaction {
            // Each SQL leg has 501 newer irrelevant rows, enough to erase its older relevant pool
            // if relevance is applied after LIMIT500. Empty-model pools also require account/key relevance.
            for (i in 0..500) {
                val (account, credential) = when (i % 3) {
                    0 -> AA2 to A2K // Other account.
                    1 -> AA to AK2 // Same account, sibling key.
                    else -> null to BK // Missing account never legitimises a foreign credential.
                }
                for ((leg, model) in listOf(raw, "").withIndex()) {
                    seedQuota(
                        "crowded-decoy-$leg-${padded(i)}", scope(A, model, account, credential, "vip"),
                        10_000L + i * 2 + leg, state = QuotaState.QUOTA_EXHAUSTED, value = i.toLong(),
                    )
                }
            }
        }

        val detail = readOnly { ModelDetailLoader.loadDetail(db, target, SelectedKey(AA, AK, "vip")) }
        assertDetailSnapshots(detail, expected)
        assertEquals(2, detail.quotaPools.size)
        assertEquals(listOf(10L, 20L), detail.quotaPools.map { it.observedAt!! }.sorted())
        assertTelemetry(detail, emptyList())
        assertFactReads(expected) // The 1,002 newer decoy snapshot IDs must never enter fact joins.
        val quotaReads = lastReads.filter { it.from("quota_snapshots") }
        assertEquals(2, quotaReads.size) // One model leg plus one empty-model pool leg.
        for (query in quotaReads) {
            val predicate = query.sql.substringAfter("WHERE").substringBefore("ORDER BY")
            assertTrue("account relevance must be in SQL before LIMIT", predicate.contains("accountId"))
            assertTrue("key relevance must be in SQL before LIMIT", predicate.contains("credentialId"))
            assertTrue("actual selected scope must be bound", query.args.containsAll(listOf(A, AA, AK)))
        }
        assertEquals(PriceState.UNKNOWN, detail.priceState)
        assertEquals(QuotaState.UNKNOWN, detail.quotaState)
    }

    // Shared typed fixtures: constructors and FK ordering are real; expected structs are literal
    // fixture values, never recomputed with production loader/builder/source/state helpers.
    private data class SnapshotFixture(
        val priceId: String? = null, val quotaId: String? = null,
        val price: DetailPricePoint? = null, val pool: DetailQuotaPool? = null,
    )

    private suspend fun seedPair(
        id: String, s: PricingScope, at: Long,
        priceState: PriceState = PriceState.PAID, quotaState: QuotaState = QuotaState.QUOTA_LOW,
        priceSource: String = "newapi/api/pricing", quotaSource: String = "newapi/api/usage/token",
        value: Long = at,
    ): SnapshotFixture {
        val price = seedPrice("price-$id", s, at, priceState, priceSource, value)
        val quota = seedQuota("quota-$id", s, at, quotaState, quotaSource, value = value)
        return SnapshotFixture(price.priceId, quota.quotaId, price.price, quota.pool)
    }

    private suspend fun seedPrice(
        id: String, s: PricingScope, at: Long, state: PriceState = PriceState.PAID,
        source: String = "newapi/api/pricing", value: Long = at,
        withFact: Boolean = true, unknownNumbers: Boolean = false,
    ): SnapshotFixture {
        val input = if (unknownNumbers) null else value.toDouble() + 0.25
        val output = if (unknownNumbers) null else value.toDouble() + 1.5
        val fixed = if (unknownNumbers) null else 0.0
        val cache = if (unknownNumbers) null else 0.125
        db.priceDao().insert(
            PriceSnapshotEntity(
                id = id, providerId = s.providerId, accountId = s.accountId, credentialId = s.credentialId,
                modelId = s.modelId, group = s.group, endpointId = s.endpointId, operation = s.operation,
                observedAt = at, input = input?.toString(), output = output?.toString(),
                fixedRequest = fixed?.toString(), cacheRead = cache?.toString(), source = source,
                evidenceId = if (withFact) "evidence-$id" else null,
            ),
        )
        if (withFact) seedFact(id, s, at, "PRICE", state.name, source)
        return SnapshotFixture(
            priceId = id,
            price = DetailPricePoint(at, input, output, fixed, cache,
                if (withFact) state else PriceState.UNKNOWN, expectedSource(source),
                s.providerId, s.accountId, s.credentialId, s.modelId, s.group, s.endpointId, s.operation),
        )
    }

    private suspend fun seedQuota(
        id: String, s: PricingScope, at: Long, state: QuotaState = QuotaState.QUOTA_LOW,
        source: String = "newapi/api/usage/token", kind: String = "TOKEN_QUOTA", value: Long = at,
        withFact: Boolean = true, unknownNumbers: Boolean = false,
    ): SnapshotFixture {
        db.quotaDao().insert(
            QuotaSnapshotEntity(
                id = id, providerId = s.providerId, accountId = s.accountId, credentialId = s.credentialId,
                modelId = s.modelId, group = s.group, endpointId = s.endpointId, operation = s.operation,
                observedAt = at, state = state.name, source = source,
                evidenceId = if (withFact) "evidence-$id" else null,
            ),
        )
        if (withFact) seedFact(id, s, at, kind, state.name, source, value, unknownNumbers)
        return SnapshotFixture(
            quotaId = id,
            pool = if (!withFact) null else DetailQuotaPool(
                kind = if (kind == "ACCOUNT_QUOTA") DetailQuotaKind.ACCOUNT_QUOTA else DetailQuotaKind.TOKEN_QUOTA,
                providerId = s.providerId, accountId = s.accountId, credentialRef = s.credentialId, state = state,
                remainingRaw = if (unknownNumbers) null else value,
                usedRaw = if (unknownNumbers) null else value + 1,
                grantedRaw = if (unknownNumbers) null else value + 2,
                unlimited = if (unknownNumbers) null else value % 2 == 0L,
                expiresAtRaw = if (unknownNumbers) null else at + 60_000,
                observedAt = at, sourceLabel = expectedSource(source), modelId = s.modelId,
                group = s.group, endpointId = s.endpointId, operation = s.operation,
            ),
        )
    }

    private suspend fun seedFact(
        snapshotId: String, s: PricingScope, at: Long, kind: String, state: String, source: String,
        value: Long? = null, unknownNumbers: Boolean = false,
    ) {
        val evidenceId = "evidence-$snapshotId"
        db.evidenceDao().insert(
            EvidenceEntity(evidenceId, "request-$snapshotId", s.providerId, s.modelId, "OK",
                status = 200, timestamp = at, source = source),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId, scopeKey = s.storageKey(), kind = kind, observedAt = at,
                snapshotId = snapshotId, state = state, status = "OK", confidence = "LOW",
                providerVersion = null, pricingVersion = null, itemPricingVersion = null,
                quotaPerUnit = null, groupRatio = null, statusObservedAt = null,
                remainingRaw = if (unknownNumbers) null else value,
                usedRaw = if (unknownNumbers) null else value?.plus(1),
                grantedRaw = if (unknownNumbers) null else value?.plus(2),
                unlimited = if (unknownNumbers) null else value?.let { it % 2 == 0L },
                expiresAtRaw = if (unknownNumbers || value == null) null else at + 60_000,
                expiryMeaning = null, expressionHash = null, priceUnit = null,
            ),
        )
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity(s.storageKey(), kind, evidenceId, at, snapshotId, at))
    }

    private suspend fun alias(provider: String, model: String, canonical: String) {
        db.providerModelAliasDao().insert(
            ProviderModelAliasEntity("alias:$provider:$model", provider, model, canonical,
                source = "USER_OVERRIDE", confidence = "LOW", verified = false),
        )
    }

    private suspend fun row(provider: String, model: String, canonical: String): ModelRow {
        db.offeringDao().insert(OfferingEntity("offering:$provider:$model", provider, "$provider-chat", model))
        return ModelRow(provider, model, canonical, displayName = "CR006 fixture $model")
    }

    private suspend fun seedSample(
        id: String, provider: String, model: String, canonical: String?, started: Long, final: Boolean = true,
    ) {
        db.sampleDao().upsert(
            SampleEntity(
                id = id, providerId = provider, accountId = if (provider == A) AA else BA,
                safeCredentialId = if (provider == A) AK else BK, endpointId = "$provider-chat",
                providerModelId = model, canonicalModelId = canonical, source = "MANUAL_TEST",
                operation = CHAT, group = null, requestStartedAt = started,
                completedAt = if (final) started + 1 else null,
                outcome = if (final) "SUCCESS" else "RUNNING", httpStatus = if (final) 200 else null,
            ),
        )
    }

    private fun scope(
        provider: String, model: String, account: String? = null, credential: String? = null,
        group: String? = null, operation: String = CHAT,
    ) = PricingScope(provider, account, credential, model, group,
        "$provider-${if (operation == CHAT) "chat" else "responses"}", operation)

    private fun expectedSource(source: String): String = when (source) {
        "USER" -> "manual"
        "newapi/api/pricing", "newapi/api/user/self", "newapi/api/usage/token" -> "official"
        else -> ""
    }

    private fun assertDetailSnapshots(detail: ModelDetailState, fixtures: List<SnapshotFixture>) {
        val prices = fixtures.mapNotNull { it.price }
        val pools = fixtures.mapNotNull { it.pool }
        assertEquals(prices.size, detail.priceHistory.size)
        assertEquals(pools.size, detail.quotaPools.size)
        assertEquals(prices.toSet(), detail.priceHistory.toSet())
        assertEquals(pools.toSet(), detail.quotaPools.toSet())
        assertEquals(prices.map { it.observedAt }.sorted(), detail.priceHistory.map { it.observedAt })
        assertEquals(
            prices.map { DetailHistoryEvent(it.observedAt, "PRICE", it.state.name, sourceLabel = it.sourceLabel) }
                .groupingBy { it }.eachCount(),
            detail.history.filter { it.section == "PRICE" }.groupingBy { it }.eachCount(),
        )
        assertEquals(
            pools.map { DetailHistoryEvent(it.observedAt!!, "QUOTA:${it.kind.name}", it.state.name,
                sourceLabel = it.sourceLabel) }.groupingBy { it }.eachCount(),
            detail.history.filter { it.section.startsWith("QUOTA:") }.groupingBy { it }.eachCount(),
        )
    }

    private fun assertTelemetry(detail: ModelDetailState, timestamps: List<Long>) {
        assertEquals(
            timestamps.sortedDescending().map { DetailHistoryEvent(it, "TELEMETRY", "SUCCESS", "HTTP 200", "observed") },
            detail.history.filter { it.section == "TELEMETRY" },
        )
    }

    private data class SqlRead(val sql: String, val args: List<Any?>) {
        fun from(table: String): Boolean = Regex("\\bFROM\\s+[`\"]?$table[`\"]?\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(sql)
        fun isPriceFactRead(): Boolean = from("snapshot_facts") && sql.contains("'PRICE'")
        fun isQuotaFactRead(): Boolean = from("snapshot_facts") && sql.contains("'ACCOUNT_QUOTA'")
    }

    /** Counts for every real table plus exact durable sentinels and actual read SQL. */
    private suspend fun <T> readOnly(block: suspend () -> T): T {
        val before = tableCounts()
        val membership = db.myModelsDao().all()
        val head = db.snapshotFactDao().head(preservedScope.storageKey(), "PRICE")
        val priceEvent = db.priceEventDao().getById("preserved-price-event")
        val quotaEvent = db.quotaEventDao().getById("preserved-quota-event")
        val sample = db.sampleDao().getById("preserved-sample")
        queries.clear()
        captureReads = true
        val result = try { block() } finally { captureReads = false }
        lastReads = queries.toList() // Direct executor: no asynchronous logging barrier or sleep.
        assertTrue("real Room queries must have executed", lastReads.isNotEmpty())
        assertFalse("detail must execute no product DML", lastReads.any {
            Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.sql) &&
                !it.sql.contains("room_table_modification_log", ignoreCase = true)
        })
        for (query in lastReads.filter { it.from("price_snapshots") || it.from("quota_snapshots") ||
            it.from("samples") || it.from("snapshot_facts") }) {
            assertTrue("scoped query exceeds 400 total binds: ${query.args.size}", query.args.size <= 400)
            if (query.from("snapshot_facts")) {
                assertTrue("facts must join only bounded snapshot IDs", query.sql.contains("IN (", ignoreCase = true))
                assertTrue(query.args.isNotEmpty())
            } else {
                assertTrue("snapshot/sample reads must be bounded", query.sql.contains("LIMIT", ignoreCase = true))
            }
        }
        assertEquals("all table counts remain unchanged", before, tableCounts())
        assertEquals(membership, db.myModelsDao().all())
        assertEquals(head, db.snapshotFactDao().head(preservedScope.storageKey(), "PRICE"))
        assertEquals(priceEvent, db.priceEventDao().getById("preserved-price-event"))
        assertEquals(quotaEvent, db.quotaEventDao().getById("preserved-quota-event"))
        assertEquals(sample, db.sampleDao().getById("preserved-sample"))
        return result
    }

    private fun assertFactReads(fixtures: List<SnapshotFixture>) {
        val priceIds = fixtures.mapNotNull { it.priceId }.toSet()
        val quotaIds = fixtures.mapNotNull { it.quotaId }.toSet()
        assertEquals("only returned prices may enter fact joins", priceIds,
            lastReads.filter { it.isPriceFactRead() }.flatMap { it.args }.toSet())
        assertEquals("only returned quotas may enter fact joins", quotaIds,
            lastReads.filter { it.isQuotaFactRead() }.flatMap { it.args }.toSet())
    }

    private fun tableCounts(): Map<String, Long> {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            sql.query("SELECT COUNT(*) FROM `$table`").use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        }
    }

    private fun padded(value: Int): String = value.toString().padStart(4, '0')

    private val preservedScope get() = scope(A, "preserved-model", AA, AK, "preserved")

    companion object {
        private const val A = "cr006-provider-A"
        private const val B = "cr006-provider-B"
        private const val AA = "cr006-account-A"
        private const val AA2 = "cr006-account-A2"
        private const val BA = "cr006-account-B"
        private const val AK = "cr006-ref-A"
        private const val AK2 = "cr006-ref-A-sibling"
        private const val A2K = "cr006-ref-A2"
        private const val BK = "cr006-ref-B"
        private const val C1 = "cr006-canonical-one"
        private const val C2 = "cr006-canonical-two"
        private const val SHARED = "cr006-canonical-shared"
        private const val EMPTY = "cr006-canonical-empty"
        private const val CHAT = "CHAT_COMPLETIONS"
        private const val RESPONSES = "RESPONSES"
    }
}
