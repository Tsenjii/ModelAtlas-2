package dev.modelatlas.app.ui

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.EvidenceEntity
import dev.modelatlas.app.data.PriceQuotaHeadProjectionRow
import dev.modelatlas.app.data.PriceSnapshotEntity
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.SnapshotFactEntity
import dev.modelatlas.app.data.SnapshotHeadEntity
import dev.modelatlas.app.data.SnapshotKind
import dev.modelatlas.app.data.parseScopeKey
import dev.modelatlas.app.data.storageKey
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.PricingScope
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
 * CR-010 batched head projection equivalence fixture.
 *
 * Placement: app/src/androidTest/kotlin/dev/modelatlas/app/ui/
 * (class name landed per the CR-010 consolidation dispatch File 3). Nothing here is an
 * acceptance claim; evidence requires the authorized slice + independent review + same-SHA
 * full-green CI.
 *
 * PRODUCTION READ PORT (landed, C3 applied): the single annotated Room query
 * `priceQuotaHeadProjections(kinds, priceKinds)` on `SnapshotFactDao`
 * (`SnapshotFacts.kt`), joining snapshot_heads -> snapshot_facts (INNER, reproducing
 * `evidence(id) ?: continue`) -> evidence (LEFT, supplemental source) -> price_snapshots
 * (LEFT, gated `h.kind IN (:priceKinds)`, reproducing `getById(...)?let`). The exact
 * annotated port is:
 *
 *     @Query("""SELECT h.scopeKey AS headScopeKey, h.kind AS headKind,
 *         h.latestEvidenceId AS latestEvidenceId, f.state AS latestState,
 *         f.observedAt AS latestFactObservedAt, e.source AS latestEvidenceSource,
 *         h.lastGoodSnapshotId AS lastGoodSnapshotId, p.input AS lastGoodInput,
 *         p.output AS lastGoodOutput, p.cacheRead AS lastGoodCacheRead,
 *         p.source AS lastGoodPriceSource
 *         FROM snapshot_heads AS h
 *         INNER JOIN snapshot_facts AS f ON f.evidenceId = h.latestEvidenceId
 *         LEFT JOIN evidence AS e ON e.id = f.evidenceId
 *         LEFT JOIN price_snapshots AS p ON p.id = h.lastGoodSnapshotId
 *           AND h.kind IN (:priceKinds)
 *         WHERE h.kind IN (:kinds)
 *         ORDER BY h.scopeKey ASC, h.kind ASC""")
 *     abstract suspend fun priceQuotaHeadProjections(
 *         kinds: List<String>, priceKinds: List<String>,
 *     ): List<PriceQuotaHeadProjectionRow>
 *
 * Both read call sites below invoke
 * `db.snapshotFactDao().priceQuotaHeadProjections(kinds, priceKinds)` directly; no
 * test-local executor or POJO remains (C3 landing). The reader goes through the real
 * Room connection, and the `setQueryCallback` capture observes it via the `query(...)`
 * overloads (room-runtime 2.7.1 pins sqlite-android 2.5.0 where `SupportSQLiteDatabase`
 * has no `rawQuery`, and `QueryInterceptorDatabase` only overrides the `query(...)`
 * overloads). Note: `SnapshotFactDao` is an abstract class, not an interface; a future
 * delegating statement counter must subclass it and delegate its protected abstract
 * range method as well as this new projection method.
 *
 * Covers exactly: (a) latest UNKNOWN + older PAID last-good — current state stays UNKNOWN,
 * numeric legs stay the historical last-good numbers, with the latest fact's snapshotId
 * deliberately differing from the head pointer; (b) same-provider other-account decoy;
 * (O1) quota-kind head whose lastGoodSnapshotId collides with a real price_snapshots id —
 * never acquires price numbers or price provenance; (c) malformed 7D scope key dropped
 * exactly as today; (d) PRICE vs DEDICATED_PRICE on one raw scope — two tuples, consensus
 * left to Kotlin policy; (e) price head with null lastGoodSnapshotId; (f) production-
 * parseScopeKey adversary sweep — null-vs-empty per dimension, literal "UNKNOWN",
 * delimiter-embedded values, malformed/trailing bytes, supplementary-Unicode (T4/T5).
 * Asserts per-row
 * equality with today's per-head loop outputs, the guard-sensitive statement reduction
 * old `8 + Hparse + Plookup` (measured 18 on this defensive seed set) vs new 1, and — on a
 * separate normal-invariant seed — the simplified `8 + H + P` (13) vs new 1.
 */
@RunWith(AndroidJUnit4::class)
class Cr010BatchedHeadProjectionTest {
    private lateinit var db: AtlasDatabase
    private val queries = CopyOnWriteArrayList<SqlRead>()
    private var lastReads: List<SqlRead> = emptyList()
    @Volatile private var captureReads = false

    @Before
    fun setUp() = runBlocking {
        db = buildCapturingDb()
        seedAll()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun projectionMatchesLegacyPerHeadLoopAndCutsHeadStatementsFromEighteenToOne() = runBlocking {
        val kinds = SnapshotKind.entries.map { it.name }
        val priceKinds = SnapshotKind.entries
            .filter { it.name in ModelRowMapper.PRICE_KINDS }.map { it.name }
        assertEquals(
            setOf(
                SnapshotKind.PRICE.name, SnapshotKind.MANUAL_PRICE.name,
                SnapshotKind.DEDICATED_PRICE.name,
            ),
            priceKinds.toSet(),
        )

        // Legacy oracle: today's exact per-head loop (AtlasViewModel.kt caller :2328, body
        // :2334-2372 at HEAD ccf69f9; byte-identical to preland-verified 0191c7d :2333-2371
        // modulo the +1 shift from the added NewApiSessionPassport import).
        val legacy = readOnly { legacyLoadPriceQuotaHeads(db) }
        val legacyReads = lastReads
        val headStmts = legacyReads.count { it.from("snapshot_heads") }
        val factStmts = legacyReads.count { it.from("snapshot_facts") }
        val priceStmts = legacyReads.count { it.from("price_snapshots") }
        assertEquals(SnapshotKind.entries.size, headStmts) // 8 unconditional kind scans.
        assertEquals(6, factStmts) // Hparse: 6 parseable heads; the malformed head costs no lookup.
        assertEquals(4, priceStmts) // Plookup: null last-good pointer + quota kind cost no price lookup.
        assertEquals(8 + 6 + 4, legacyReads.size) // Exact guard-sensitive 8 + Hparse + Plookup.
        assertEquals(
            SnapshotKind.entries.map { it.name }.toSet(),
            legacyReads.filter { it.from("snapshot_heads") }.map { it.args.single() }.toSet(),
        )
        val legacyByKey = legacy.associate { it.head.kind to it.scopeKey to it.head }
        // The malformed head is scanned (part of the 8) but dropped before any evidence/price cost.
        assertTrue(legacy.none { it.scopeKey == malformedKey })

        // New reader: the production single joined projection via the DAO.
        val rawRows = readOnly { db.snapshotFactDao().priceQuotaHeadProjections(kinds, priceKinds) }
        val join = lastReads.single()
        assertEquals(1, lastReads.size) // The whole projection is one statement.
        assertTrue(join.from("snapshot_heads"))
        // G2 fix: snapshot_facts is reached via INNER JOIN, not FROM — SqlRead.from matches
        // only literal FROM <table>, so assert textual presence (mirrors :price_snapshots below).
        assertTrue(join.sql.contains("snapshot_facts", ignoreCase = true))
        assertTrue(join.sql.contains("LEFT JOIN", ignoreCase = true))
        assertTrue(join.sql.contains("price_snapshots", ignoreCase = true))
        assertEquals(11, join.args.size) // 3 priceKinds (ON clause) + 8 kinds (WHERE clause).
        assertEquals(priceKinds + kinds, join.args) // ON-clause binds precede WHERE-clause binds.

        // ORDER BY pin: (scopeKey ASC, kind ASC) as written in the production SQL. Fixture keys are
        // ASCII, where JVM lexicographic order coincides with device SQLite BINARY order.
        assertEquals(
            rawRows.map { it.headScopeKey to it.headKind }
                .sortedWith(compareBy({ it.first }, { it.second })),
            rawRows.map { it.headScopeKey to it.headKind },
        )

        // Malformed head: still returned by SQL (the fact join matched), dropped exactly as
        // today by the Kotlin parseScopeKey skip — no new drop condition, none lost.
        assertEquals(7, rawRows.size)
        assertTrue(rawRows.any { it.headScopeKey == malformedKey })
        val tuples = rawRows.mapNotNull { projectionToHead(it) }
        assertEquals(6, tuples.size)
        assertTrue(tuples.none { it.scopeKey == malformedKey })

        // Per-row equality with today's per-head loop outputs, keyed by (kind, raw scopeKey).
        assertEquals(legacyByKey, tuples.associate { it.head.kind to it.scopeKey to it.head })

        // (a): current state is the LATEST fact's UNKNOWN; numeric legs are the HISTORICAL
        // last-good numbers. No success may be inferred from last-good.
        val l1 = tuples.single { it.scopeKey == l1Scope.storageKey() }
        assertEquals(SnapshotKind.PRICE.name, l1.head.kind)
        assertEquals("UNKNOWN", l1.head.state)
        assertEquals(1.5, l1.head.inputPrice)
        assertEquals(6.0, l1.head.outputPrice)
        assertEquals(0.25, l1.head.cachePrice)
        assertEquals(5_000L, l1.head.observedAt) // Latest fact's time, not last-good 1000.
        // Wrong-pointer leg: the latest fact's snapshotId differs from the head pointer;
        // numbers and price source must follow h.lastGoodSnapshotId, not f.snapshotId.
        assertEquals("price-l1-failed", db.snapshotFactDao().evidence("evidence-l1")?.snapshotId)
        assertEquals(
            "price-l1-old",
            db.snapshotFactDao().head(l1Scope.storageKey(), SnapshotKind.PRICE.name)?.lastGoodSnapshotId,
        )
        // Provenance: latestEvidenceSource (evidence row of the LATEST fact) and
        // lastGoodPriceSource (price row of the LAST-GOOD pointer) are distinct fields from
        // distinct rows; here they differ in value and origin.
        assertEquals("newapi/api/pricing", l1.row.latestEvidenceSource)
        assertEquals("USER", l1.row.lastGoodPriceSource)

        // (b): the same-provider other-account head keeps its own state/numbers; the decoy
        // price row no head points to never becomes a head, a tuple, or a number.
        val other = tuples.single { it.scopeKey == otherScope.storageKey() }
        assertEquals("PAID", other.head.state)
        assertEquals(9.99, other.head.inputPrice)
        assertEquals(19.99, other.head.outputPrice)
        assertEquals(0.99, other.head.cachePrice)
        assertEquals(6_000L, other.head.observedAt)
        assertEquals("3.00", db.priceDao().getById("price-decoy-other-account")?.input)
        assertTrue(
            tuples.none { it.head.inputPrice == 3.0 || it.head.outputPrice == 9.0 || it.head.cachePrice == 0.3 },
        )

        // (O1): a quota-kind head whose lastGoodSnapshotId collides with a real
        // price_snapshots id never acquires price numbers or price provenance — the
        // price-kind gate discriminates; the pointer itself is preserved in the projection row.
        val dedQuota = tuples.single { it.scopeKey == dedicatedQuotaScope.storageKey() }
        assertEquals(SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name, dedQuota.head.kind)
        assertEquals("PAID", dedQuota.head.state)
        assertNull(dedQuota.head.inputPrice)
        assertNull(dedQuota.head.outputPrice)
        assertNull(dedQuota.head.cachePrice)
        assertEquals(10_000L, dedQuota.head.observedAt)
        assertEquals("price-conflict-official", dedQuota.row.lastGoodSnapshotId)
        assertNull(dedQuota.row.lastGoodInput)
        assertNull(dedQuota.row.lastGoodOutput)
        assertNull(dedQuota.row.lastGoodCacheRead)
        assertNull(dedQuota.row.lastGoodPriceSource)

        // (e): a null lastGoodSnapshotId keeps the head, its latest fact state/time/source
        // intact, and null numeric/source legs — rejecting an accidental inner price join.
        val noLastGood = tuples.single { it.scopeKey == nolastgoodScope.storageKey() }
        assertEquals("PAID", noLastGood.head.state)
        assertNull(noLastGood.head.inputPrice)
        assertNull(noLastGood.head.outputPrice)
        assertNull(noLastGood.head.cachePrice)
        assertNull(noLastGood.row.lastGoodSnapshotId)
        assertNull(noLastGood.row.lastGoodPriceSource)
        assertEquals(9_000L, noLastGood.head.observedAt)

        // Per-row provenance across every returned row, including the malformed head's row
        // and the quota-collision head's row.
        val provenance = rawRows.associate {
            it.headKind to it.headScopeKey to (it.latestEvidenceSource to it.lastGoodPriceSource)
        }
        assertEquals(
            "newapi/api/pricing" to "USER",
            provenance[SnapshotKind.PRICE.name to l1Scope.storageKey()],
        )
        assertEquals(
            "newapi/api/pricing" to "USER",
            provenance[SnapshotKind.PRICE.name to otherScope.storageKey()],
        )
        assertEquals(
            "newapi/api/pricing" to "newapi/api/pricing",
            provenance[SnapshotKind.PRICE.name to conflictScope.storageKey()],
        )
        assertEquals(
            "USER" to "USER",
            provenance[SnapshotKind.DEDICATED_PRICE.name to conflictScope.storageKey()],
        )
        assertEquals(
            "newapi/api/pricing" to null,
            provenance[SnapshotKind.PRICE.name to nolastgoodScope.storageKey()],
        )
        assertEquals("USER" to "USER", provenance[SnapshotKind.PRICE.name to malformedKey])
        assertEquals(
            "newapi/api/pricing" to null,
            provenance[SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name to dedicatedQuotaScope.storageKey()],
        )
    }

    @Test
    fun sourceConflictStaysTwoTuplesWithKotlinConsensusUnknownAndNoProvenanceLeakage() = runBlocking {
        val kinds = SnapshotKind.entries.map { it.name }
        val priceKinds = SnapshotKind.entries
            .filter { it.name in ModelRowMapper.PRICE_KINDS }.map { it.name }
        val rawRows = readOnly { db.snapshotFactDao().priceQuotaHeadProjections(kinds, priceKinds) }
        val tuples = rawRows.mapNotNull { projectionToHead(it) }

        // (d): one raw scope carries two heads (PK (scopeKey, kind)); both survive the
        // projection with their own states/numbers. No grouping, no kind winner.
        val conflict = tuples.filter { it.scopeKey == conflictScope.storageKey() }
        assertEquals(
            setOf(SnapshotKind.PRICE.name, SnapshotKind.DEDICATED_PRICE.name),
            conflict.map { it.head.kind }.toSet(),
        )

        // Matching policy stays in Kotlin (ModelRowMapper); the projection supplies rows only.
        val rows = ModelRowMapper.build(
            offerings = listOf(
                OfferingRef(
                    id = "offering:probe", providerId = A, endpointId = "$A-chat",
                    providerModelId = "probe-model",
                ),
                OfferingRef(
                    id = "offering:conflict", providerId = A, endpointId = "$A-chat",
                    providerModelId = "conflict-model",
                ),
            ),
            availabilities = emptyList(),
            selected = SelectedKey(AA, AK, "vip"),
            priceQuotaHeads = tuples.map { it.head },
        )
        val probe = rows.single { it.providerModelId == "probe-model" }
        val conflicted = rows.single { it.providerModelId == "conflict-model" }
        // (a) through the consumer: latest UNKNOWN consensus with the historical last-good
        // numbers filled from the single matching candidate.
        assertEquals(PriceState.UNKNOWN, probe.priceState)
        assertEquals(1.5, probe.inputPrice)
        assertEquals(6.0, probe.outputPrice)
        assertEquals(0.25, probe.cachePrice)
        // (d): PAID vs UNKNOWN disagreement -> consensus UNKNOWN; two candidates -> no numbers.
        assertEquals(PriceState.UNKNOWN, conflicted.priceState)
        assertNull(conflicted.inputPrice)
        assertNull(conflicted.outputPrice)
        assertNull(conflicted.cachePrice)
        // (b): the other-account head is excluded by exact account matching (numbers stay
        // 1.5, not 9.99). (e): the empty-group head is excluded by raw group matching.
        // Sources stay offering-level; no head provenance leaks into consumer semantics.
        assertEquals("manual", probe.sourceLabel)
        assertEquals("manual", conflicted.sourceLabel)
    }

    @Test
    fun normalSeedLegacyCountIsExactlyEightPlusHPlusPAndProjectionIsOne() = runBlocking {
        // Simplified-count proof on a NORMAL seed (all head keys parse, every head's latest
        // fact exists): the guard-sensitive form `8 + Hparse + Plookup` collapses to
        // `8 + H + P`. This form must never be asserted on the defensive seed above.
        val kinds = SnapshotKind.entries.map { it.name }
        val priceKinds = SnapshotKind.entries
            .filter { it.name in ModelRowMapper.PRICE_KINDS }.map { it.name }
        val db2 = buildCapturingDb()
        try {
            db2.withTransaction {
                // H head 1: PRICE, parses, fact exists, last-good pointer -> existing price row.
                val normalScope = scope(AA, AK, "normal-price-model", "vip")
                seedFact(db2, "evidence-normal", normalScope.storageKey(), SnapshotKind.PRICE.name,
                    20_000L, snapshotId = "np-price-good", state = "PAID",
                    source = "newapi/api/pricing")
                seedPriceRow(db2, "np-price-good", normalScope, at = 20_100L, input = "2.50",
                    output = "7.00", cacheRead = "0.30", source = "USER")
                seedHead(db2, normalScope.storageKey(), SnapshotKind.PRICE.name,
                    "evidence-normal", 20_000L, lastGoodSnapshotId = "np-price-good",
                    lastGoodObservedAt = 20_100L)

                // H head 2: PRICE, parses, fact exists, last-good pointer -> ABSENT price row.
                // The lookup is still issued (P counts head lookups, not price rows); the
                // numeric legs come back null in both outputs.
                val missingScope = scope(AA, AK, "missing-pointer-model", "vip")
                seedFact(db2, "evidence-missing", missingScope.storageKey(), SnapshotKind.PRICE.name,
                    21_000L, snapshotId = "np-price-absent", state = "PAID",
                    source = "newapi/api/pricing")
                seedHead(db2, missingScope.storageKey(), SnapshotKind.PRICE.name,
                    "evidence-missing", 21_000L, lastGoodSnapshotId = "np-price-absent",
                    lastGoodObservedAt = 21_100L)

                // H head 3: ACCOUNT_QUOTA, parses, fact exists, non-null last-good pointer that
                // collides with the existing price row — the kind gate still costs no price
                // lookup (P excludes quota kinds) and no price numbers/source are acquired.
                val quotaScope = scope(AA, AK, "quota-pointer-model", "vip")
                seedFact(db2, "evidence-quota", quotaScope.storageKey(),
                    SnapshotKind.ACCOUNT_QUOTA.name, 22_000L, snapshotId = null, state = "OK",
                    source = "newapi/api/pricing")
                seedHead(db2, quotaScope.storageKey(), SnapshotKind.ACCOUNT_QUOTA.name,
                    "evidence-quota", 22_000L, lastGoodSnapshotId = "np-price-good",
                    lastGoodObservedAt = 22_100L)
            }

            // H = 3 (all keys parse, all facts exist); P = 2 (only the two PRICE heads are
            // price-kind with non-null last-good pointers).
            val h = 3
            val p = 2
            val legacy = readOnlyOn(db2) { target -> legacyLoadPriceQuotaHeads(target) }
            val legacyReads = lastReads
            assertEquals(SnapshotKind.entries.size, legacyReads.count { it.from("snapshot_heads") })
            assertEquals(h, legacyReads.count { it.from("snapshot_facts") })
            assertEquals(p, legacyReads.count { it.from("price_snapshots") })
            assertEquals(8 + h + p, legacyReads.size) // Simplified 8 + H + P exactly.

            // New reader: one statement, constant in H/P/E/G.
            val rawRows = readOnlyOn(db2) { target ->
                target.snapshotFactDao().priceQuotaHeadProjections(kinds, priceKinds)
            }
            assertEquals(1, lastReads.size)
            assertEquals(11, lastReads.single().args.size)

            // Per-row equality on the normal seed as well.
            val tuples = rawRows.mapNotNull { projectionToHead(it) }
            assertEquals(3, rawRows.size)
            assertEquals(3, tuples.size)
            assertEquals(
                legacy.associate { it.head.kind to it.scopeKey to it.head },
                tuples.associate { it.head.kind to it.scopeKey to it.head },
            )

            // Existing-pointer PRICE head: numbers follow the last-good price row.
            val normal = tuples.single { it.scopeKey == scope(AA, AK, "normal-price-model", "vip").storageKey() }
            assertEquals("PAID", normal.head.state)
            assertEquals(2.5, normal.head.inputPrice)
            assertEquals(7.0, normal.head.outputPrice)
            assertEquals(0.3, normal.head.cachePrice)
            assertEquals(20_000L, normal.head.observedAt)
            // Absent-pointer PRICE head: lookup issued, numeric legs null.
            val missing = tuples.single { it.scopeKey == scope(AA, AK, "missing-pointer-model", "vip").storageKey() }
            assertEquals("PAID", missing.head.state)
            assertNull(missing.head.inputPrice)
            assertNull(missing.head.outputPrice)
            assertNull(missing.head.cachePrice)
            assertNull(missing.row.lastGoodPriceSource)
            // ACCOUNT_QUOTA head with a colliding non-null pointer: kind gate keeps numeric
            // legs and price provenance null; the pointer is preserved in the projection row.
            val quota = tuples.single { it.scopeKey == scope(AA, AK, "quota-pointer-model", "vip").storageKey() }
            assertEquals("OK", quota.head.state)
            assertNull(quota.head.inputPrice)
            assertNull(quota.head.outputPrice)
            assertNull(quota.head.cachePrice)
            assertEquals("np-price-good", quota.row.lastGoodSnapshotId)
            assertNull(quota.row.lastGoodPriceSource)
        } finally {
            db2.close()
        }
    }

    @Test
    fun parseScopeKeyRoundTripsNullEmptyUnknownDelimitersAndSupplementary() {
        // (f1) null-vs-empty per dimension through PRODUCTION parseScopeKey
        // (SnapshotFacts.kt:180-198): "-1" -> null; "0" -> ""; else an exact-length slice.
        // enc() mirrors the storageKey scheme (SnapshotFacts.kt:157-159) for building raw
        // inputs only — it never parses — and is pinned to production storageKey() below.
        fun enc(s: String?): String = if (s == null) "-1:" else "${s.length}:$s"
        val pinned = PricingScope(A, "", AK, "", "", "", "")
        assertEquals(
            pinned.storageKey(),
            enc(A) + enc("") + enc(AK) + enc("") + enc("") + enc("") + enc(""),
        )

        // Null-vs-empty per dimension (account[1]/credential[2]/group[4]/endpoint[5]/
        // operation[6]): "-1:" and "0:" never collide; decode is exactly null vs "".
        // (endpoint/operation nulls are parser-level — the PricingScope type keeps them
        // non-null — so they are built raw here and cross-checked via storageKey below.)
        val dims = listOf(A, AA, AK, "m", "g", "e", "op")
        for (i in listOf(1, 2, 4, 5, 6)) {
            val withNull = dims.mapIndexed { j, v -> if (j == i) null else v }
            val rawNull = withNull.joinToString("") { enc(it) }
            assertEquals(withNull, parseScopeKey(rawNull))
            val withEmpty = dims.mapIndexed { j, v -> if (j == i) "" else v }
            val rawEmpty = withEmpty.joinToString("") { enc(it) }
            assertEquals(withEmpty, parseScopeKey(rawEmpty))
            assertTrue(rawNull != rawEmpty)
        }
        // Production-encoder cross-checks: null account/group and empty endpoint/operation.
        assertEquals(
            listOf(A, null, AK, "m", null, "e", "op"),
            parseScopeKey(PricingScope(A, null, AK, "m", null, "e", "op").storageKey()),
        )
        assertEquals(
            listOf(A, "", AK, "m", "", "", ""),
            parseScopeKey(PricingScope(A, "", AK, "m", "", "", "").storageKey()),
        )

        // (f2) literal "UNKNOWN" is ordinary payload: kept as a value, never a sentinel.
        assertEquals(
            listOf(A, AA, AK, "UNKNOWN", "g", "e", "op"),
            parseScopeKey(PricingScope(A, AA, AK, "UNKNOWN", "g", "e", "op").storageKey()),
        )
        assertEquals(
            listOf(A, AA, AK, "m", "UNKNOWN", "e", "op"),
            parseScopeKey(PricingScope(A, AA, AK, "m", "UNKNOWN", "e", "op").storageKey()),
        )
        assertNull(parseScopeKey("UNKNOWN")) // No length prefix at all -> malformed.

        // (f3) delimiter-embedded values: length-prefix framing means ':' (even digit-colon
        // runs that look like framing) inside a value round-trips exactly.
        for (v in listOf("a:b", "12:x", ":leading", "trail:", ":", "3:3:3")) {
            assertEquals(
                listOf(A, AA, AK, v, "g", "e", "op"),
                parseScopeKey(PricingScope(A, AA, AK, v, "g", "e", "op").storageKey()),
            )
        }

        // (f4) supplementary-Unicode: String.length counts UTF-16 units and substring is
        // char-indexed, so encode/decode stay symmetric across surrogate pairs.
        val smileyModel = "m\uD83D\uDE00del" // 'm' + U+1F600 pair + "del" = 6 units.
        assertEquals(6, smileyModel.length)
        assertEquals(
            listOf(A, AA, AK, smileyModel, "g", "e", "op"),
            parseScopeKey(PricingScope(A, AA, AK, smileyModel, "g", "e", "op").storageKey()),
        )
        val clefGroup = "\uD834\uDD1E" // U+1D11E, one surrogate pair = 2 units.
        assertEquals(2, clefGroup.length)
        assertEquals(
            listOf(A, AA, AK, "m", clefGroup, "e", "op"),
            parseScopeKey(PricingScope(A, AA, AK, "m", clefGroup, "e", "op").storageKey()),
        )

        // All-null raw key parses (7 x "-1:") and is KEPT by the reader with the same
        // provider/model "" coercions the production loop applies (parts[0]/parts[3] ?: "").
        val kept = projectionToHead(
            PriceQuotaHeadProjectionRow(
                "-1:-1:-1:-1:-1:-1:-1:", SnapshotKind.PRICE.name, "e", "PAID", 1L,
                null, null, null, null, null, null,
            ),
        )
        assertTrue(kept != null)
        assertEquals("", kept!!.head.providerId)
        assertEquals("", kept.head.modelId)
        assertNull(kept.head.accountId)
        assertNull(kept.head.endpointId)
        assertNull(kept.head.operation)
    }

    @Test
    fun parseScopeKeyRejectsMalformedTruncatedAndTrailingBytes() {
        val valid = PricingScope(A, AA, AK, "m", "g", "e", "op").storageKey()
        assertTrue(parseScopeKey(valid) != null) // Control: the base key parses.
        // Each vector traced against SnapshotFacts.kt:180-198 (fail-closed -> null):
        // no colon at dim 0; single/six dims only (dim loop hits no colon); non-numeric
        // length (toIntOrNull null); negative length other than the -1 sentinel; declared
        // length overrunning the buffer; Int-range overflow (toIntOrNull null); trailing
        // bytes/segments after 7 dims (pos != length); truncated final dim (overrun);
        // off-by-one account length corrupting the next dim's length prefix ("A11" parses
        // as no Int -> null).
        val accountSeg = "${AA.length}:$AA"
        assertTrue(valid.contains(accountSeg)) // Guard: the corruption edit below applies.
        for (key in listOf(
            "",
            "malformed-not-a-scope-key", // T1 defensive-seed vector.
            "UNKNOWN",
            "0:",
            "-1:-1:-1:-1:-1:-1:",
            "x:abcdef",
            "-2:ab",
            "999:ab",
            "2147483648:x",
            valid + "X",
            valid + ":0:",
            valid.dropLast(1),
            valid.replaceFirst(accountSeg, "${AA.length - 1}:$AA"),
        )) {
            assertNull("must fail closed: $key", parseScopeKey(key))
        }

        // Only the literal -1 is null: "-0" parses as length 0, i.e. seven empty strings.
        assertEquals(
            listOf("", "", "", "", "", "", ""),
            parseScopeKey("-0:-0:-0:-0:-0:-0:-0:"),
        )

        // Reader drop parity: malformed keys never become tuples (same skip as the
        // production loop's `parseScopeKey ?: continue`).
        for (key in listOf("malformed-not-a-scope-key", valid + "X", "")) {
            assertNull(
                projectionToHead(
                    PriceQuotaHeadProjectionRow(
                        key, SnapshotKind.PRICE.name, "e", "PAID", 1L,
                        null, null, null, null, null, null,
                    ),
                ),
            )
        }
    }

    /** Tuple identity for per-row equality: (kind, raw scopeKey) -> 13-field consumer head. */
    private data class HeadWithKey(
        val scopeKey: String,
        val head: PriceQuotaHead,
        val row: PriceQuotaHeadProjectionRow,
    )

    /**
     * G1 fix: legacy-oracle tuple. The legacy per-head loop never produces a projection row,
     * so it uses this row-less type instead of [HeadWithKey]. This keeps [HeadWithKey.row]
     * non-null (every new-path provenance assertion derefs `.row` directly) and limits the
     * fix to this decl + the oracle signature + one construction site.
     */
    private data class LegacyHeadWithKey(
        val scopeKey: String,
        val head: PriceQuotaHead,
    )

    /**
     * Kotlin-side mapping, byte-exact with the production loop's conversions:
     * production [parseScopeKey] unchanged (malformed -> null, dropped exactly as today) and
     * the exact `?.toBigDecimalOrNull()?.toDouble()` numeric conversions; providerId/modelId
     * null -> "" coercions preserved; no SQL extraction, normalization, or wildcard.
     */
    private fun projectionToHead(row: PriceQuotaHeadProjectionRow): HeadWithKey? {
        val parts = parseScopeKey(row.headScopeKey) ?: return null
        return HeadWithKey(
            scopeKey = row.headScopeKey,
            row = row,
            head = PriceQuotaHead(
                kind = row.headKind,
                providerId = parts[0] ?: "",
                accountId = parts[1],
                credentialId = parts[2],
                modelId = parts[3] ?: "",
                group = parts[4],
                endpointId = parts[5],
                operation = parts[6],
                state = row.latestState,
                inputPrice = row.lastGoodInput?.toBigDecimalOrNull()?.toDouble(),
                outputPrice = row.lastGoodOutput?.toBigDecimalOrNull()?.toDouble(),
                cachePrice = row.lastGoodCacheRead?.toBigDecimalOrNull()?.toDouble(),
                observedAt = row.latestFactObservedAt,
            ),
        )
    }

    /**
     * Byte-exact test replica of `AtlasViewModel.loadPriceQuotaHeads`
     * (app/src/main/kotlin/dev/modelatlas/app/AtlasViewModel.kt caller :2328, body :2334-2372
     * at HEAD ccf69f9, re-verified this session; byte-identical to 0191c7d :2333-2371 modulo
     * the +1 import shift): 8 unconditional headsByKind scans, parseScopeKey-skip,
     * `evidence(id) ?: continue`, and the per-head priceDao().getById(lastGoodId) lookup with
     * `?.toBigDecimalOrNull()?.toDouble()`. This is the legacy oracle only; it is not a
     * production caller and is never merged.
     */
    private suspend fun legacyLoadPriceQuotaHeads(target: AtlasDatabase): List<LegacyHeadWithKey> {
        val factDao = target.snapshotFactDao()
        val out = ArrayList<LegacyHeadWithKey>()
        for (kind in SnapshotKind.entries) {
            val isPrice = kind == SnapshotKind.PRICE || kind == SnapshotKind.MANUAL_PRICE ||
                kind == SnapshotKind.DEDICATED_PRICE
            for (head in factDao.headsByKind(kind.name)) {
                val parts = parseScopeKey(head.scopeKey) ?: continue
                val fact = factDao.evidence(head.latestEvidenceId) ?: continue
                var input: Double? = null
                var output: Double? = null
                var cache: Double? = null
                val lastGoodId = head.lastGoodSnapshotId
                if (isPrice && lastGoodId != null) {
                    target.priceDao().getById(lastGoodId)?.let { snapshot ->
                        input = snapshot.input?.toBigDecimalOrNull()?.toDouble()
                        output = snapshot.output?.toBigDecimalOrNull()?.toDouble()
                        cache = snapshot.cacheRead?.toBigDecimalOrNull()?.toDouble()
                    }
                }
                out += LegacyHeadWithKey(
                    scopeKey = head.scopeKey,
                    head = PriceQuotaHead(
                        kind = kind.name,
                        providerId = parts[0] ?: "",
                        accountId = parts[1],
                        credentialId = parts[2],
                        modelId = parts[3] ?: "",
                        group = parts[4],
                        endpointId = parts[5],
                        operation = parts[6],
                        state = fact.state,
                        inputPrice = input,
                        outputPrice = output,
                        cachePrice = cache,
                        observedAt = fact.observedAt,
                    ),
                )
            }
        }
        return out
    }

    // Shared typed fixtures: constructors and FK ordering are real; expected structs are literal
    // fixture values, never recomputed with production loader/builder/source helpers.
    // Seed helpers take the target database explicitly so the defensive seed (db) and the
    // normal-invariant seed (db2) share identical construction logic.

    private suspend fun seedAll() {
        db.withTransaction {
            // (a) latest UNKNOWN + older PAID last-good, plus the wrong latest-fact pointer:
            // the fact's snapshotId ("price-l1-failed") differs from the head pointer
            // ("price-l1-old"); numbers must follow the head pointer.
            seedFact(db, "evidence-l1", l1Scope.storageKey(), SnapshotKind.PRICE.name, 5_000L,
                snapshotId = "price-l1-failed", state = "UNKNOWN", source = "newapi/api/pricing")
            seedPriceRow(db, "price-l1-failed", l1Scope, at = 5_000L, input = null, output = null,
                cacheRead = null, source = "newapi/api/pricing")
            seedPriceRow(db, "price-l1-old", l1Scope, at = 1_000L, input = "1.50", output = "6.00",
                cacheRead = "0.25", source = "USER")
            seedHead(db, l1Scope.storageKey(), SnapshotKind.PRICE.name, "evidence-l1", 5_000L,
                lastGoodSnapshotId = "price-l1-old", lastGoodObservedAt = 1_000L)

            // (b) same-provider other-account head, plus a decoy price row no head points to.
            seedFact(db, "evidence-other", otherScope.storageKey(), SnapshotKind.PRICE.name, 6_000L,
                snapshotId = "price-other-old", state = "PAID", source = "newapi/api/pricing")
            seedPriceRow(db, "price-other-old", otherScope, at = 1_100L, input = "9.99",
                output = "19.99", cacheRead = "0.99", source = "USER")
            seedPriceRow(db, "price-decoy-other-account", otherScope, at = 6_100L, input = "3.00",
                output = "9.00", cacheRead = "0.30", source = "newapi/api/pricing")
            seedHead(db, otherScope.storageKey(), SnapshotKind.PRICE.name, "evidence-other", 6_000L,
                lastGoodSnapshotId = "price-other-old", lastGoodObservedAt = 1_100L)

            // (c) malformed 7D scope key: known kind, existing fact and last-good, but the
            // key has no length prefixes. Dropped exactly as today, before any evidence or
            // price lookup; the projection still returns its row and Kotlin drops it.
            // "price-malformed-old" is joined by exact PK id only; its l1Scope provenance
            // columns are unread by this oracle (the raw malformed key is not a PricingScope).
            seedFact(db, "evidence-malformed", malformedKey, SnapshotKind.PRICE.name, 7_000L,
                snapshotId = "price-malformed-old", state = "PAID", source = "USER")
            seedPriceRow(db, "price-malformed-old", l1Scope, at = 1_200L, input = "4.00",
                output = "8.00", cacheRead = "0.40", source = "USER")
            seedHead(db, malformedKey, SnapshotKind.PRICE.name, "evidence-malformed", 7_000L,
                lastGoodSnapshotId = "price-malformed-old", lastGoodObservedAt = 1_200L)

            // (d) PRICE vs DEDICATED_PRICE on one raw scope (same storageKey, two heads):
            // official numbers/state from the New API leg, manual numbers/state from the
            // dedicated leg; consensus and any source treatment stay Kotlin policy.
            seedFact(db, "evidence-conflict-price", conflictScope.storageKey(),
                SnapshotKind.PRICE.name, 8_000L, snapshotId = "price-conflict-official",
                state = "PAID", source = "newapi/api/pricing")
            seedPriceRow(db, "price-conflict-official", conflictScope, at = 1_300L, input = "1.00",
                output = "2.00", cacheRead = "0.10", source = "newapi/api/pricing")
            seedHead(db, conflictScope.storageKey(), SnapshotKind.PRICE.name,
                "evidence-conflict-price", 8_000L, lastGoodSnapshotId = "price-conflict-official",
                lastGoodObservedAt = 1_300L)
            seedFact(db, "evidence-conflict-dedicated", conflictScope.storageKey(),
                SnapshotKind.DEDICATED_PRICE.name, 8_100L, snapshotId = "price-conflict-dedicated",
                state = "UNKNOWN", source = "USER")
            seedPriceRow(db, "price-conflict-dedicated", conflictScope, at = 1_400L, input = "2.00",
                output = "4.00", cacheRead = "0.20", source = "USER")
            seedHead(db, conflictScope.storageKey(), SnapshotKind.DEDICATED_PRICE.name,
                "evidence-conflict-dedicated", 8_100L,
                lastGoodSnapshotId = "price-conflict-dedicated", lastGoodObservedAt = 1_400L)

            // (O1) quota-kind head on a fresh scope whose lastGoodSnapshotId deliberately
            // collides with the real price row "price-conflict-official": the price-kind gate
            // must keep its numeric legs and price provenance null; the pointer is preserved.
            seedFact(db, "evidence-dedquota", dedicatedQuotaScope.storageKey(),
                SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name, 10_000L, snapshotId = null,
                state = "PAID", source = "newapi/api/pricing")
            seedHead(db, dedicatedQuotaScope.storageKey(), SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name,
                "evidence-dedquota", 10_000L, lastGoodSnapshotId = "price-conflict-official",
                lastGoodObservedAt = 1_300L)

            // (e) price head with a null lastGoodSnapshotId: head + fact survive with null
            // numeric/source legs (rejects an accidental inner price join).
            seedFact(db, "evidence-nolastgood", nolastgoodScope.storageKey(),
                SnapshotKind.PRICE.name, 9_000L, snapshotId = null, state = "PAID",
                source = "newapi/api/pricing")
            seedHead(db, nolastgoodScope.storageKey(), SnapshotKind.PRICE.name, "evidence-nolastgood",
                9_000L, lastGoodSnapshotId = null, lastGoodObservedAt = null)
        }
    }

    private suspend fun seedFact(
        target: AtlasDatabase,
        evidenceId: String,
        scopeKey: String,
        kind: String,
        observedAt: Long,
        snapshotId: String?,
        state: String,
        source: String,
    ) {
        target.evidenceDao().insert(
            EvidenceEntity(
                evidenceId, "request-$evidenceId", A, "fixture-model", "OK",
                status = 200, timestamp = observedAt, source = source,
            ),
        )
        target.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId, scopeKey = scopeKey, kind = kind, observedAt = observedAt,
                snapshotId = snapshotId, state = state, status = "OK", confidence = "LOW",
                providerVersion = null, pricingVersion = null, itemPricingVersion = null,
                quotaPerUnit = null, groupRatio = null, statusObservedAt = null,
                remainingRaw = null, usedRaw = null, grantedRaw = null, unlimited = null,
                expiresAtRaw = null, expiryMeaning = null, expressionHash = null, priceUnit = null,
            ),
        )
    }

    private suspend fun seedPriceRow(
        target: AtlasDatabase,
        id: String,
        s: PricingScope,
        at: Long,
        input: String?,
        output: String?,
        cacheRead: String?,
        source: String,
    ) {
        target.priceDao().insert(
            PriceSnapshotEntity(
                id = id, providerId = s.providerId, accountId = s.accountId,
                credentialId = s.credentialId, modelId = s.modelId, group = s.group,
                endpointId = s.endpointId, operation = s.operation, observedAt = at,
                input = input, output = output, cacheRead = cacheRead, source = source,
            ),
        )
    }

    private suspend fun seedHead(
        target: AtlasDatabase,
        scopeKey: String,
        kind: String,
        latestEvidenceId: String,
        latestObservedAt: Long,
        lastGoodSnapshotId: String?,
        lastGoodObservedAt: Long?,
    ) {
        target.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(scopeKey, kind, latestEvidenceId, latestObservedAt,
                lastGoodSnapshotId, lastGoodObservedAt),
        )
    }

    private data class SqlRead(val sql: String, val args: List<Any?>) {
        fun from(table: String): Boolean = Regex("\\bFROM\\s+[`\"]?$table[`\"]?\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(sql)
    }

    private fun buildCapturingDb(): AtlasDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
    ).allowMainThreadQueries()
        .setQueryCallback(
            RoomDatabase.QueryCallback { sql, args ->
                if (captureReads) queries += SqlRead(sql, args.toList())
            },
            Executor { it.run() },
        ).build()

    /** Real-query capture, no-DML guard, 400-bind budget, and unchanged table counts. */
    private suspend fun <T> readOnly(block: suspend () -> T): T = readOnlyOn(db) { block() }

    private suspend fun <T> readOnlyOn(
        target: AtlasDatabase,
        block: suspend (AtlasDatabase) -> T,
    ): T {
        val before = tableCounts(target)
        queries.clear()
        captureReads = true
        val result = try { block(target) } finally { captureReads = false }
        lastReads = queries.toList() // Direct executor: synchronous callback, no barrier or sleep.
        assertTrue("real Room queries must have executed", lastReads.isNotEmpty())
        assertFalse("projection reads must execute no DML", lastReads.any {
            Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.sql) &&
                !it.sql.contains("room_table_modification_log", ignoreCase = true)
        })
        for (query in lastReads) {
            assertTrue("read exceeds 400 binds: ${query.args.size}", query.args.size <= 400)
        }
        assertEquals("all table counts remain unchanged", before, tableCounts(target))
        return result
    }

    private fun tableCounts(target: AtlasDatabase): Map<String, Long> {
        val sql = target.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            sql.query("SELECT COUNT(*) FROM `$table`").use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        }
    }

    private fun scope(account: String, credential: String, model: String, group: String?) =
        PricingScope(A, account, credential, model, group, "$A-chat", CHAT)

    private val l1Scope get() = scope(AA, AK, "probe-model", "vip")
    private val otherScope get() = scope(AA2, A2K, "probe-model", "vip")
    private val conflictScope get() = scope(AA, AK, "conflict-model", "vip")
    private val nolastgoodScope get() = scope(AA, AK, "nolastgood-model", "")
    private val dedicatedQuotaScope get() = scope(AA, AK, "dedquota-model", "vip")
    private val malformedKey = "malformed-not-a-scope-key"

    companion object {
        private const val A = "cr010-provider-A"
        private const val AA = "cr010-account-A"
        private const val AA2 = "cr010-account-A2"
        private const val AK = "cr010-ref-A"
        private const val A2K = "cr010-ref-A2"
        private const val CHAT = "CHAT_COMPLETIONS"
    }
}
