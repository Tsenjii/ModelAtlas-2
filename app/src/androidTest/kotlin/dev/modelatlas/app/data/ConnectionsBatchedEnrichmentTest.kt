package dev.modelatlas.app.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.newapi.NewApiDomainMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * S7 batched connections enrichment device tests (real in-memory Room + real
 * [AtlasRepository.connections] flow), per `/tmp/opencode/ai11-spec-connections-n1.md` §6.
 *
 * Production under test (landed uncommitted): `AtlasRepository.withBatchedSnapshotStates` +
 * `resolvePriceStateBatched` — one `priceQuotaHeadProjections(kinds x3, priceKinds x1)` statement
 * per emission, exact-key quota index, B1 price policy kept in Kotlin, per-row `runCatching`
 * fail-closed, `lastGood` never read.
 *
 * T->test map:
 * - T1 statement count -> [batchedEnrichmentCostsOneProjectionStatementWithFourBinds]
 * - T2 quota legs -> [quotaExactKeyTokenPrecedenceAndAnonymousAccountOnly]
 * - T3 lastGood isolation -> [lastGoodNeverSurfacesAndPriceTableUnread]
 * - T4 dangling latest pointer -> [danglingLatestPointerFallsThroughLikeLegacy]
 * - T5 price consensus (B1) -> [priceConsensusAndB1Gates]
 * - T6 equivalence fuzz -> [randomizedEquivalenceFuzzMatchesLegacyOracle]
 * - history immutability (read-only, CR-010 T1 style) -> T1 test's read-only guard
 * - empty-rows fast path -> [emptyConnectionsEmitWithoutProjection]
 * - runCatching fail-closed -> [malformedScopeKeyFailsClosedPerRow]
 *
 * Query counting mirrors `Cr010BatchedHeadProjectionTest`: `setQueryCallback` capture observes the
 * production DAO path (room-runtime pins `query(...)` overloads). The legacy oracle below is a
 * test-local replica of the pre-batch per-row path (spec §1: per-row `headsByKind(PRICE)` scan +
 * per-head `evidence()` + `head(key)` x<=2 + `evidence()` quota legs); it is never merged and only
 * measures the old fan-out shape for the T1 comparison.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionsBatchedEnrichmentTest {
    private lateinit var db: AtlasDatabase
    private lateinit var repo: AtlasRepository
    private val queries = CopyOnWriteArrayList<SqlRead>()
    @Volatile private var captureReads = false
    private var lastReads: List<SqlRead> = emptyList()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, args ->
                    if (captureReads) queries += SqlRead(sql, args.toList())
                },
                Executor { it.run() },
            ).build()
        repo = AtlasRepository(db)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    // =====================================================================
    // T1: statement count — R named + 1 anonymous rows, PRICE/TOKEN/ACCOUNT heads.
    // =====================================================================

    @Test
    fun batchedEnrichmentCostsOneProjectionStatementWithFourBinds() = runBlocking {
        val r = 4
        repeat(r) { i ->
            seedNamedConnection(p = "s7t1-p$i", a = "s7t1-a$i", c = "s7t1-c$i", e = "s7t1-e$i", model = "s7t1-m$i", tag = "t1n$i")
            seedHeadTriple(priceScope("s7t1-p$i", "s7t1-a$i", "s7t1-m$i", "s7t1-e$i"), SnapshotKind.PRICE, "PAID", "s7t1-ev-p$i")
            seedHeadTriple(tokenScope("s7t1-p$i", "s7t1-a$i", "s7t1-c$i"), SnapshotKind.TOKEN_QUOTA, "TOK-OK-$i", "s7t1-ev-t$i")
            seedHeadTriple(accountScope("s7t1-p$i", "s7t1-a$i"), SnapshotKind.ACCOUNT_QUOTA, "ACC-OK-$i", "s7t1-ev-a$i")
        }
        seedAnonymousProvider(p = "s7t1-pa", a = "s7t1-aa", e = "s7t1-ea", o = "s7t1-oa", model = "s7t1-ma")
        seedHeadTriple(priceScope("s7t1-pa", "s7t1-aa", "s7t1-ma", "s7t1-ea"), SnapshotKind.PRICE, "PAID", "s7t1-ev-pa")
        seedHeadTriple(accountScope("s7t1-pa", "s7t1-aa"), SnapshotKind.ACCOUNT_QUOTA, "ACC-ANON", "s7t1-ev-aa")

        val batched = readOnlyBatched()
        assertEquals(r + 1, batched.size)
        val projections = lastReads.filter { it.from("snapshot_heads") && it.sql.contains("snapshot_facts", ignoreCase = true) }
        assertEquals("enrichment must cost exactly 1 projection statement per emission", 1, projections.size)
        assertEquals(
            listOf(SnapshotKind.PRICE.name, SnapshotKind.PRICE.name, SnapshotKind.TOKEN_QUOTA.name, SnapshotKind.ACCOUNT_QUOTA.name),
            projections.single().args,
        )
        assertTrue("connections observe query must have run", lastReads.any { it.sql.contains("credential_availability", ignoreCase = true) })

        // Both public flows share the helper: identical enriched output.
        val mgmt = repo.managementConnections.first()
        assertEquals(batched.associateBy { it.id }, mgmt.associateBy { it.id })

        // Named rows take TOKEN precedence; anonymous row takes ACCOUNT.
        repeat(r) { i ->
            val row = batched.single { it.id == "av-t1n$i" }
            assertEquals("PAID", row.priceState)
            assertEquals("TOK-OK-$i", row.quotaState)
        }
        assertEquals("ACC-ANON", batched.single { it.anonymous }.quotaState)

        // Legacy fan-out shape on the same seed: per-row PRICE scan + <=2 head + evidence reads.
        val raw = db.connectionDao().observe().first()
        val legacy = capture { legacyEnrich(raw) }
        assertEquals(legacy.associateBy { it.id }, batched.associateBy { it.id })
        assertTrue(
            "legacy path must fan out with row count (got ${lastReads.size} for ${raw.size} rows)",
            lastReads.size >= 4 * raw.size,
        )
    }

    // =====================================================================
    // T2: quota legs — TOKEN hit wins; TOKEN miss falls to ACCOUNT; both miss
    // keeps the DAO default; anonymous consults ACCOUNT only.
    // =====================================================================

    @Test
    fun quotaExactKeyTokenPrecedenceAndAnonymousAccountOnly() = runBlocking {
        seedNamedConnection(p = "s7t2-p", a = "s7t2-a", c = "s7t2-c", e = "s7t2-e", model = "s7t2-m", tag = "t2both", quotaState = "DAO-Q-BOTH")
        seedHeadTriple(tokenScope("s7t2-p", "s7t2-a", "s7t2-c"), SnapshotKind.TOKEN_QUOTA, "TOK-WINS", "s7t2-ev-t")
        seedHeadTriple(accountScope("s7t2-p", "s7t2-a"), SnapshotKind.ACCOUNT_QUOTA, "ACC-LOSES", "s7t2-ev-a")

        seedNamedConnection(p = "s7t2-p2", a = "s7t2-a2", c = "s7t2-c2", e = "s7t2-e2", model = "s7t2-m2", tag = "t2acc", quotaState = "DAO-Q-ACC")
        seedHeadTriple(accountScope("s7t2-p2", "s7t2-a2"), SnapshotKind.ACCOUNT_QUOTA, "ACC-FALLBACK", "s7t2-ev-a2")

        seedNamedConnection(p = "s7t2-p3", a = "s7t2-a3", c = "s7t2-c3", e = "s7t2-e3", model = "s7t2-m3", tag = "t2none", quotaState = "DAO-Q-NONE")

        seedAnonymousProvider(p = "s7t2-pa", a = "s7t2-aa", e = "s7t2-ea", o = "s7t2-oa", model = "s7t2-ma")
        // Decoy TOKEN head under a credential the anonymous row does not own: never consulted.
        seedHeadTriple(tokenScope("s7t2-pa", "s7t2-aa", "s7t2-ghost"), SnapshotKind.TOKEN_QUOTA, "TOK-GHOST", "s7t2-ev-ghost")
        seedHeadTriple(accountScope("s7t2-pa", "s7t2-aa"), SnapshotKind.ACCOUNT_QUOTA, "ACC-ANON2", "s7t2-ev-aa2")

        val batched = repo.connections.first()
        assertEquals("TOK-WINS", batched.single { it.id == "av-t2both" }.quotaState)
        assertEquals("ACC-FALLBACK", batched.single { it.id == "av-t2acc" }.quotaState)
        assertEquals("DAO-Q-NONE", batched.single { it.id == "av-t2none" }.quotaState)
        assertEquals("ACC-ANON2", batched.single { it.anonymous }.quotaState)

        val legacy = legacyEnrich(db.connectionDao().observe().first())
        assertEquals(legacy.associateBy { it.id }, batched.associateBy { it.id })
    }

    // =====================================================================
    // T3: lastGood isolation — dangling/divergent lastGood pointers never surface;
    // price_snapshots is never read (FROM) on the enrichment path.
    // =====================================================================

    @Test
    fun lastGoodNeverSurfacesAndPriceTableUnread() = runBlocking {
        seedNamedConnection(p = "s7t3-p", a = "s7t3-a", c = "s7t3-c", e = "s7t3-e", model = "s7t3-m", tag = "t3lg")
        // Dangling lastGood pointer: output must still be the latest fact state.
        seedHeadTriple(
            priceScope("s7t3-p", "s7t3-a", "s7t3-m", "s7t3-e"), SnapshotKind.PRICE, "PAID", "s7t3-ev-p",
            lastGoodId = "s7t3-no-such-price-row",
        )
        seedHeadTriple(
            tokenScope("s7t3-p", "s7t3-a", "s7t3-c"), SnapshotKind.TOKEN_QUOTA, "TOK-LG", "s7t3-ev-t",
            lastGoodId = "s7t3-no-such-price-row-either",
        )

        val batched = capture { repo.connections.first() }
        val row = batched.single { it.id == "av-t3lg" }
        assertEquals("PAID", row.priceState)
        assertEquals("TOK-LG", row.quotaState)
        assertTrue(
            "lastGood lives in price_snapshots, which the batched path must never FROM-read",
            lastReads.none { it.from("price_snapshots") },
        )

        val legacy = legacyEnrich(db.connectionDao().observe().first())
        assertEquals(legacy.associateBy { it.id }, batched.associateBy { it.id })
    }

    // =====================================================================
    // T4: dangling latest pointer — upsertHead with a nonexistent latestEvidenceId
    // is skipped exactly like legacy `evidence(...) ?: continue` / null.
    // =====================================================================

    @Test
    fun danglingLatestPointerFallsThroughLikeLegacy() = runBlocking {
        seedNamedConnection(p = "s7t4-p", a = "s7t4-a", c = "s7t4-c", e = "s7t4-e", model = "s7t4-m", tag = "t4d", priceState = "DAO-KEEP")
        // Only PRICE head dangles (no fact row): zero live candidates -> DAO value kept.
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(
                priceScope("s7t4-p", "s7t4-a", "s7t4-m", "s7t4-e").storageKey(),
                SnapshotKind.PRICE.name, "s7t4-missing-ev", 1_000L, null, null,
            ),
        )
        // TOKEN head dangles, ACCOUNT head is live -> falls through to ACCOUNT.
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(
                tokenScope("s7t4-p", "s7t4-a", "s7t4-c").storageKey(),
                SnapshotKind.TOKEN_QUOTA.name, "s7t4-missing-ev", 1_000L, null, null,
            ),
        )
        seedHeadTriple(accountScope("s7t4-p", "s7t4-a"), SnapshotKind.ACCOUNT_QUOTA, "ACC-VIA-FALLBACK", "s7t4-ev-a")

        val batched = repo.connections.first()
        val row = batched.single { it.id == "av-t4d" }
        assertEquals("DAO-KEEP", row.priceState)
        assertEquals("ACC-VIA-FALLBACK", row.quotaState)

        val legacy = legacyEnrich(db.connectionDao().observe().first())
        assertEquals(legacy.associateBy { it.id }, batched.associateBy { it.id })
    }

    // =====================================================================
    // T5: price consensus (B1 locked policy).
    // =====================================================================

    @Test
    fun priceConsensusAndB1Gates() = runBlocking {
        seedProviderStack(p = "s7t5-p", a = "s7t5-a", c = "s7t5-c", e = "s7t5-e")
        // Agreement -> take (a second agreeing head on identical dims is impossible:
        // PK is (scopeKey, kind); multi-candidate agreement is covered by the fuzz test).
        seedOfferingRow(p = "s7t5-p", a = "s7t5-a", c = "s7t5-c", e = "s7t5-e", model = "s7t5-agree", tag = "t5agree")
        seedPriceHead(priceScope("s7t5-p", "s7t5-a", "s7t5-agree", "s7t5-e"), "PAID", "s7t5-ev-ag1", at = 1_000L)
        // Second agreeing candidate on the same dims via a second head is impossible
        // (PK is (scopeKey, kind)); agreement is covered by the single-candidate take
        // plus the fuzz test. Divergence needs distinct scopeKeys that both match:
        // unknown-scope operations "" vs "UNKNOWN" both match an unknown protocol.
        seedUnknownProtocolConnection(p = "s7t5-pu", a = "s7t5-au", c = "s7t5-cu", e = "s7t5-eu", model = "s7t5-div", tag = "t5div", priceState = "DAO-DIV")
        seedPriceHead(PricingScope("s7t5-pu", "s7t5-au", null, "s7t5-div", null, "s7t5-eu", ""), "FREE_NOW", "s7t5-ev-d1", at = 1_000L)
        seedPriceHead(PricingScope("s7t5-pu", "s7t5-au", null, "s7t5-div", null, "s7t5-eu", "UNKNOWN"), "PAID", "s7t5-ev-d2", at = 2_000L)

        // Non-null group excluded: vip FREE_NOW + null-group PAID -> PAID.
        seedEndpoint("s7t5-e2", "s7t5-p")
        seedOfferingRow(p = "s7t5-p", a = "s7t5-a", c = "s7t5-c", e = "s7t5-e2", model = "s7t5-grp", tag = "t5grp")
        seedPriceHead(PricingScope("s7t5-p", "s7t5-a", null, "s7t5-grp", "vip", "s7t5-e2", "CHAT_COMPLETIONS"), "FREE_NOW", "s7t5-ev-g1", at = 1_000L)
        seedPriceHead(PricingScope("s7t5-p", "s7t5-a", null, "s7t5-grp", null, "s7t5-e2", "CHAT_COMPLETIONS"), "PAID", "s7t5-ev-g2", at = 2_000L)

        // Operation mismatch excluded: "text" FREE_NOW + CHAT PAID -> PAID.
        seedEndpoint("s7t5-e3", "s7t5-p")
        seedOfferingRow(p = "s7t5-p", a = "s7t5-a", c = "s7t5-c", e = "s7t5-e3", model = "s7t5-op", tag = "t5op")
        seedPriceHead(PricingScope("s7t5-p", "s7t5-a", null, "s7t5-op", null, "s7t5-e3", "text"), "FREE_NOW", "s7t5-ev-o1", at = 1_000L)
        seedPriceHead(PricingScope("s7t5-p", "s7t5-a", null, "s7t5-op", null, "s7t5-e3", "CHAT_COMPLETIONS"), "PAID", "s7t5-ev-o2", at = 2_000L)

        // Endpoint mismatch excluded -> DAO value kept.
        seedEndpoint("s7t5-e4", "s7t5-p")
        seedOfferingRow(p = "s7t5-p", a = "s7t5-a", c = "s7t5-c", e = "s7t5-e4", model = "s7t5-ep", tag = "t5ep", priceState = "DAO-EP")
        seedPriceHead(PricingScope("s7t5-p", "s7t5-a", null, "s7t5-ep", null, "s7t5-other-ep", "CHAT_COMPLETIONS"), "FREE_NOW", "s7t5-ev-e1", at = 1_000L)

        // Zero candidates -> DAO value kept (model mismatch only).
        seedEndpoint("s7t5-e5", "s7t5-p")
        seedOfferingRow(p = "s7t5-p", a = "s7t5-a", c = "s7t5-c", e = "s7t5-e5", model = "s7t5-quiet", tag = "t5quiet", priceState = "PARTIALLY_FREE")
        seedPriceHead(PricingScope("s7t5-p", "s7t5-a", null, "s7t5-other-model", null, "s7t5-e5", "CHAT_COMPLETIONS"), "FREE_NOW", "s7t5-ev-q1", at = 1_000L)

        // Malformed scopeKey head with a live fact: ignored, sibling valid head applies.
        seedEndpoint("s7t5-e6", "s7t5-p")
        seedOfferingRow(p = "s7t5-p", a = "s7t5-a", c = "s7t5-c", e = "s7t5-e6", model = "s7t5-mal", tag = "t5mal")
        seedFactOnly(evidenceId = "s7t5-ev-mal", scopeKey = "malformed-not-a-scope-key", kind = SnapshotKind.PRICE.name, state = "FREE_NOW", at = 1_000L)
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity("malformed-not-a-scope-key", SnapshotKind.PRICE.name, "s7t5-ev-mal", 1_000L, null, null),
        )
        seedPriceHead(PricingScope("s7t5-p", "s7t5-a", null, "s7t5-mal", null, "s7t5-e6", "CHAT_COMPLETIONS"), "PAID", "s7t5-ev-m2", at = 2_000L)

        val batched = repo.connections.first()
        assertEquals("PAID", batched.single { it.id == "av-t5agree" }.priceState)
        assertEquals("UNKNOWN", batched.single { it.id == "av-t5div" }.priceState)
        assertEquals("PAID", batched.single { it.id == "av-t5grp" }.priceState)
        assertEquals("PAID", batched.single { it.id == "av-t5op" }.priceState)
        assertEquals("DAO-EP", batched.single { it.id == "av-t5ep" }.priceState)
        assertEquals("PARTIALLY_FREE", batched.single { it.id == "av-t5quiet" }.priceState)
        assertEquals("PAID", batched.single { it.id == "av-t5mal" }.priceState)

        val legacy = legacyEnrich(db.connectionDao().observe().first())
        assertEquals(legacy.associateBy { it.id }, batched.associateBy { it.id })
    }

    // =====================================================================
    // runCatching fail-closed: a malformed head never breaks sibling rows.
    // =====================================================================

    @Test
    fun malformedScopeKeyFailsClosedPerRow() = runBlocking {
        seedProviderStack(p = "s7t5b-p", a = "s7t5b-a", c = "s7t5b-c", e = "s7t5b-e")
        seedOfferingRow(p = "s7t5b-p", a = "s7t5b-a", c = "s7t5b-c", e = "s7t5b-e", model = "s7t5b-only", tag = "t5bonly", priceState = "DAO-ONLY")
        seedFactOnly(evidenceId = "s7t5b-ev-mal", scopeKey = "!!!", kind = SnapshotKind.PRICE.name, state = "FREE_NOW", at = 1_000L)
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity("!!!", SnapshotKind.PRICE.name, "s7t5b-ev-mal", 1_000L, null, null),
        )
        seedEndpoint("s7t5b-e2", "s7t5b-p")
        seedOfferingRow(p = "s7t5b-p", a = "s7t5b-a", c = "s7t5b-c", e = "s7t5b-e2", model = "s7t5b-sib", tag = "t5bsib")
        seedPriceHead(PricingScope("s7t5b-p", "s7t5b-a", null, "s7t5b-sib", null, "s7t5b-e2", "CHAT_COMPLETIONS"), "PAID", "s7t5b-ev-sib", at = 2_000L)

        val batched = repo.connections.first()
        assertEquals("DAO-ONLY", batched.single { it.id == "av-t5bonly" }.priceState)
        assertEquals("PAID", batched.single { it.id == "av-t5bsib" }.priceState)

        val legacy = legacyEnrich(db.connectionDao().observe().first())
        assertEquals(legacy.associateBy { it.id }, batched.associateBy { it.id })
    }

    // =====================================================================
    // Empty-rows fast path: no connections -> no projection statement.
    // =====================================================================

    @Test
    fun emptyConnectionsEmitWithoutProjection() = runBlocking {
        val rows = capture { repo.connections.first() }
        assertTrue(rows.isEmpty())
        assertTrue(
            "empty emission must not touch snapshot_heads",
            lastReads.none { it.from("snapshot_heads") },
        )
    }

    // =====================================================================
    // T6: equivalence fuzz — randomized small universe, batched == legacy oracle.
    // =====================================================================

    @Test
    fun randomizedEquivalenceFuzzMatchesLegacyOracle() = runBlocking {
        val rnd = Random(2026_10_07)
        val states = listOf("PAID", "FREE_NOW", "UNKNOWN")
        val quotaStates = listOf("OK", "LOW", "UNKNOWN")
        var ev = 0
        fun nextEv(): String = "s7fz-ev-${ev++}"
        repeat(3) { pi ->
            val p = "s7fz-p$pi"
            val a = "s7fz-a$pi"
            val c = "s7fz-c$pi"
            val e = "s7fz-e$pi"
            seedProviderStack(p, a, c, e)
            repeat(2) { mi ->
                val model = "s7fz-m$mi"
                seedOfferingRow(p, a, c, e, model, "fz$pi$mi")
                repeat(rnd.nextInt(0, 3)) {
                    val group = if (rnd.nextBoolean()) null else "vip"
                    val op = listOf("CHAT_COMPLETIONS", "text", "").random(rnd)
                    val ep = if (rnd.nextInt(10) < 8) e else "s7fz-other-ep"
                    seedPriceHead(
                        PricingScope(p, a, null, model, group, ep, op),
                        states.random(rnd), nextEv(), at = 1_000L + ev,
                    )
                }
                if (rnd.nextInt(10) < 7) {
                    seedHeadTriple(tokenScope(p, a, c), SnapshotKind.TOKEN_QUOTA, quotaStates.random(rnd), nextEv())
                }
                if (rnd.nextInt(10) < 7) {
                    seedHeadTriple(accountScope(p, a), SnapshotKind.ACCOUNT_QUOTA, quotaStates.random(rnd), nextEv())
                }
            }
        }
        // Adversaries: malformed keys (live facts) and dangling valid-key pointers.
        repeat(2) { i ->
            val key = "fuzz-malformed-$i-{not-a-key}"
            seedFactOnly(nextEv(), key, SnapshotKind.PRICE.name, "FREE_NOW", 9_000L)
            db.snapshotFactDao().upsertHead(SnapshotHeadEntity(key, SnapshotKind.PRICE.name, "s7fz-ev-${ev - 1}", 9_000L, null, null))
        }
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(
                tokenScope("s7fz-p0", "s7fz-a0", "s7fz-c0").storageKey(),
                SnapshotKind.TOKEN_QUOTA.name, "s7fz-ev-dangling", 9_001L, null, null,
            ),
        )

        val batched = repo.connections.first()
        assertEquals(6, batched.size)
        val legacy = legacyEnrich(db.connectionDao().observe().first())
        assertEquals(legacy.associateBy { it.id }, batched.associateBy { it.id })
    }

    // =====================================================================
    // Legacy oracle: test-local replica of the pre-batch per-row path (spec §1).
    // headsByKind(PRICE) per row + evidence() per candidate; quota head()+evidence()
    // per leg; lastGood ignored; per-row runCatching fail-closed. Never merged.
    // =====================================================================

    private suspend fun legacyEnrich(rows: List<ConnectionRecord>): List<ConnectionRecord> {
        val factDao = db.snapshotFactDao()
        return rows.map { record ->
            runCatching {
                var seen: String? = null
                var count = 0
                var diverged = false
                // Old resolvePriceState: full PRICE kind scan issued PER ROW.
                for (head in factDao.headsByKind(SnapshotKind.PRICE.name)) {
                    val parts = parseScopeKey(head.scopeKey) ?: continue
                    if (parts[0] != record.providerId) continue
                    if (parts[1] != record.accountId) continue
                    if (parts[2] != null) continue
                    if (parts[3] != record.modelId) continue
                    if (parts[4] != null) continue
                    val headOperation = parts[6]
                    val headUnknown = unknownScopeValue(headOperation)
                    val callerOperation = operationFromProtocol(record.protocol)
                    val callerUnknown = unknownScopeValue(callerOperation)
                    if (headUnknown != callerUnknown) continue
                    if (!headUnknown && headOperation != callerOperation) continue
                    if (parts[5] != record.endpointId) continue
                    val state = factDao.evidence(head.latestEvidenceId)?.state ?: continue
                    count++
                    if (seen == null) seen = state
                    else if (seen != state) {
                        diverged = true
                        break
                    }
                }
                val priceState = if (count == 0) record.priceState else if (diverged) "UNKNOWN" else seen!!
                val tokenState = record.credentialId.takeIf { it.isNotEmpty() }?.let { cid ->
                    val key = tokenScope(record.providerId, record.accountId, cid).storageKey()
                    factDao.head(key, SnapshotKind.TOKEN_QUOTA.name)?.let { h ->
                        factDao.evidence(h.latestEvidenceId)?.state
                    }
                }
                val accountKey = accountScope(record.providerId, record.accountId).storageKey()
                val accountState = factDao.head(accountKey, SnapshotKind.ACCOUNT_QUOTA.name)?.let { h ->
                    factDao.evidence(h.latestEvidenceId)?.state
                }
                record.copy(priceState = priceState, quotaState = tokenState ?: accountState ?: record.quotaState)
            }.getOrDefault(record)
        }
    }

    /** Verbatim replica of `AtlasRepository.operationFromProtocol` (private there). */
    private fun operationFromProtocol(protocol: String?): String {
        val clean = protocol?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 64 && it.matches(Regex("[A-Za-z0-9_]+"))
        } ?: return "UNKNOWN"
        return clean
    }

    /** Verbatim replica of the production `unknownScopeValue` (private there). */
    private fun unknownScopeValue(raw: String?): Boolean {
        val normalized = raw?.trim()?.uppercase() ?: return true
        return normalized.isEmpty() || normalized == "UNKNOWN"
    }

    // =====================================================================
    // Harness (fixtures live only in this file, never product data).
    // =====================================================================

    private data class SqlRead(val sql: String, val args: List<Any?>) {
        fun from(table: String): Boolean = Regex("\\bFROM\\s+[`\"]?$table[`\"]?\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(sql)
    }

    private suspend fun <T> capture(block: suspend () -> T): T {
        queries.clear()
        captureReads = true
        try {
            return block()
        } finally {
            captureReads = false
            lastReads = queries.toList()
        }
    }

    /** Batched emission under capture + CR-010 T1 style read-only guards (no DML, no drift). */
    private suspend fun readOnlyBatched(): List<ConnectionRecord> {
        val before = tableCounts()
        val out = capture { repo.connections.first() }
        assertTrue("real Room queries must have executed", lastReads.isNotEmpty())
        assertFalse(
            "enrichment must not write",
            lastReads.any {
                Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.sql) &&
                    !it.sql.contains("room_table_modification_log", ignoreCase = true)
            },
        )
        for (query in lastReads) {
            assertTrue("read exceeds 400 binds: ${query.args.size}", query.args.size <= 400)
        }
        assertEquals("all table counts remain unchanged", before, tableCounts())
        return out
    }

    private fun tableCounts(): Map<String, Long> {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table ->
            sql.query("SELECT COUNT(*) FROM `$table`").use { c ->
                assertTrue(c.moveToFirst())
                c.getLong(0)
            }
        }
    }

    private fun priceScope(provider: String, account: String, model: String, endpoint: String) =
        PricingScope(provider, account, null, model, null, endpoint, "CHAT_COMPLETIONS")

    private fun tokenScope(provider: String, account: String, credential: String) =
        PricingScope(provider, account, credential, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL)

    private fun accountScope(provider: String, account: String) =
        PricingScope(provider, account, null, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL)

    private suspend fun seedNamedConnection(
        p: String,
        a: String,
        c: String,
        e: String,
        model: String,
        tag: String,
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
    ) {
        // Direct inserts (not repo.addConnection): addConnection ABORTs on duplicate
        // provider/account/credential, while several tests share one stack across rows.
        seedProviderStack(p, a, c, e)
        seedOfferingRow(p, a, c, e, model, tag, priceState, quotaState)
    }

    private suspend fun seedProviderStack(
        p: String,
        a: String,
        c: String,
        e: String,
        protocol: String = "CHAT_COMPLETIONS",
    ) {
        db.providerDao().insert(ProviderEntity(providerId = p, name = "S7 $p"))
        db.accountDao().insert(AccountEntity(accountId = a, providerId = p))
        db.credentialDao().insert(CredentialEntity(credentialId = c, accountId = a))
        db.endpointDao().insert(
            EndpointEntity(endpointId = e, providerId = p, baseUrl = BASE_URL, endpointUrl = "$BASE_URL/chat", protocol = protocol),
        )
    }

    private suspend fun seedEndpoint(e: String, p: String, protocol: String = "CHAT_COMPLETIONS") {
        db.endpointDao().insert(
            EndpointEntity(endpointId = e, providerId = p, baseUrl = BASE_URL, endpointUrl = "$BASE_URL/chat", protocol = protocol),
        )
    }

    private suspend fun seedOfferingRow(
        p: String,
        a: String,
        c: String,
        e: String,
        model: String,
        tag: String,
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
    ) {
        db.offeringDao().insert(OfferingEntity(id = "off-$tag", providerId = p, endpointId = e, providerModelId = model))
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-$tag", offeringId = "off-$tag", accountId = a, credentialId = c,
                priceState = priceState, quotaState = quotaState,
            ),
        )
    }

    /** Anonymous offering-direct row: NONE-auth provider gives exactly one anon row per offering x account. */
    private suspend fun seedAnonymousProvider(p: String, a: String, e: String, o: String, model: String) {
        db.providerDao().insert(ProviderEntity(providerId = p, name = "S7 anon $p"))
        db.accountDao().insert(AccountEntity(accountId = a, providerId = p))
        db.endpointDao().insert(
            EndpointEntity(endpointId = e, providerId = p, baseUrl = BASE_URL, endpointUrl = "$BASE_URL/chat", protocol = "CHAT_COMPLETIONS"),
        )
        db.offeringDao().insert(OfferingEntity(id = o, providerId = p, endpointId = e, providerModelId = model))
        db.providerConfigDao().insert(
            ProviderConfigurationEntity(providerId = p, workflowId = "wf-$p", authMode = "NONE", snapshotJson = "{}"),
        )
    }

    /** Connection whose persisted endpoint protocol is unknown-scope ("UNKNOWN"). */
    private suspend fun seedUnknownProtocolConnection(
        p: String,
        a: String,
        c: String,
        e: String,
        model: String,
        tag: String,
        priceState: String,
    ) {
        db.providerDao().insert(ProviderEntity(providerId = p, name = "S7 unknown $p"))
        db.accountDao().insert(AccountEntity(accountId = a, providerId = p))
        db.credentialDao().insert(CredentialEntity(credentialId = c, accountId = a))
        db.endpointDao().insert(
            EndpointEntity(endpointId = e, providerId = p, baseUrl = BASE_URL, endpointUrl = "$BASE_URL/chat", protocol = "UNKNOWN"),
        )
        db.offeringDao().insert(OfferingEntity(id = "off-$tag", providerId = p, endpointId = e, providerModelId = model))
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-$tag", offeringId = "off-$tag", accountId = a, credentialId = c,
                priceState = priceState, quotaState = "UNKNOWN",
            ),
        )
    }

    private suspend fun seedPriceHead(scope: PricingScope, state: String, evidenceId: String, at: Long) {
        seedHeadTriple(scope, SnapshotKind.PRICE, state, evidenceId, at = at)
    }

    private suspend fun seedHeadTriple(
        scope: PricingScope,
        kind: SnapshotKind,
        state: String,
        evidenceId: String,
        at: Long = 1_000L,
        lastGoodId: String? = null,
    ) {
        seedFactOnly(evidenceId, scope.storageKey(), kind.name, state, at)
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(scope.storageKey(), kind.name, evidenceId, at, lastGoodId, lastGoodId?.let { at }),
        )
    }

    private suspend fun seedFactOnly(evidenceId: String, scopeKey: String, kind: String, state: String, at: Long) {
        db.evidenceDao().insert(
            EvidenceEntity(
                id = evidenceId, requestId = "req-$evidenceId", providerId = "s7-seed",
                modelId = "s7-seed-model", outcome = "OK", status = 200, timestamp = at,
                source = "newapi/api/pricing",
            ),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId, scopeKey = scopeKey, kind = kind, observedAt = at,
                snapshotId = evidenceId, state = state, status = "OK", confidence = "OFFICIAL",
                providerVersion = null, pricingVersion = null, itemPricingVersion = null,
                quotaPerUnit = null, groupRatio = null, statusObservedAt = null,
                remainingRaw = null, usedRaw = null, grantedRaw = null, unlimited = null,
                expiresAtRaw = null, expiryMeaning = null, expressionHash = null, priceUnit = null,
            ),
        )
    }

    companion object {
        private const val BASE_URL = "https://s7.fixture.invalid/v1"
    }
}
