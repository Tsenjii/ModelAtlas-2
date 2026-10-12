package dev.modelatlas.app.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ui.ModelRowMapper
import dev.modelatlas.app.ui.PriceQuotaHead
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
 * S8 batched slice-head projection device tests (real in-memory Room), per
 * `/tmp/opencode/ai11-spec-s8-slicebatch.md` §5.
 *
 * Production under test (lands concurrently via Astra, spec §3 exact port):
 * `slicePriceQuotaHeadProjections(kinds)` on `SnapshotFactDao` — one statement
 * `FROM snapshot_slice_heads INNER JOIN snapshot_facts (latestEvidenceId) LEFT JOIN
 * evidence`, `WHERE kind IN (:kinds)`, `ORDER BY scopeKey, kind, quotaSlice`; no
 * price_snapshots join (quota-only by construction); seven-dim parsing stays in
 * Kotlin (`parseScopeKey ?: continue`); dangling heads drop via INNER JOIN.
 *
 * Vector->test map (spec §5 table):
 * - V0 empty namespace, 1 statement -> [emptySliceNamespaceCostsOneStatement]
 * - V1 single head, 1 statement + head-observedAt pin -> [singleSliceHeadMatchesLegacyOracle]
 * - V2 5 heads across 2 kinds, 1 (was 4+5=9) + order pin -> [fiveHeadsAcrossTwoKindsCostOneStatement]
 * - V3 3 heads / 2 malformed, 1 + Kotlin drop, no extra lookup -> [malformedScopesDroppedInKotlinWithoutExtraLookup]
 * - V4 3 heads / 1 dangling, 1 + INNER JOIN drop -> [danglingHeadDroppedByInnerJoin]
 * - V5 12 heads across 4 kinds (1 malformed + 1 dangling), 1 (was 4+12=16) -> [twelveHeadsAcrossFourKindsCostOneStatement]
 * - full loader ordinary 1 + slice 1 = 2 -> [fullLoaderCostsTwoStatementsRegardlessOfN]
 *
 * Query counting mirrors `Cr010BatchedHeadProjectionTest`: `setQueryCallback`
 * capture observes the production DAO path. The legacy oracle below is a
 * test-local byte-faithful replica of the pre-batch slice loop
 * (`AtlasViewModel.kt:2425-2446`: 4 unconditional `sliceHeadsByKind` scans +
 * per-head `evidence()` incurred even for malformed scopes, parse follows the
 * lookup); it is never merged and only measures the old 4+N fan-out shape.
 */
@RunWith(AndroidJUnit4::class)
class SliceProjectionBatchingTest {
    private lateinit var db: AtlasDatabase
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
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    // =====================================================================
    // V0: empty slice namespace — still exactly 1 statement (4-kind IN bind).
    // =====================================================================

    @Test
    fun emptySliceNamespaceCostsOneStatement() = runBlocking {
        val rows = readOnly { db.snapshotFactDao().slicePriceQuotaHeadProjections(QUOTA_KINDS) }
        assertSliceStatements(1)
        assertEquals(QUOTA_KINDS, lastReads.single { it.from(SLICE_TABLE) }.args)
        assertTrue(rows.isEmpty())
        assertTrue(sliceRowToHeads(rows).isEmpty())

        val legacy = readOnly { legacySliceLoad(db) }
        assertTrue(legacy.isEmpty())
        assertEquals("legacy slice path costs 4 kind scans + 0 head lookups", 4, lastReads.size)
    }

    // =====================================================================
    // V1: single head — oracle equivalence + head-observedAt + null legs.
    // =====================================================================

    @Test
    fun singleSliceHeadMatchesLegacyOracle() = runBlocking {
        val scope = qScope("v1", cred = "s8-c-v1")
        // Head time (5_000) deliberately differs from fact time (4_900): the caller
        // keeps today's exact `observedAt = head.latestObservedAt` semantics.
        seedSlice(scope.storageKey(), SnapshotKind.ACCOUNT_QUOTA.name, STANDARD, "s8-v1-ev", "OK", headAt = 5_000L, factAt = 4_900L)

        val rows = readOnly { db.snapshotFactDao().slicePriceQuotaHeadProjections(QUOTA_KINDS) }
        assertSliceStatements(1)
        assertSqlOrderPinned(rows)
        val raw = rows.single()
        assertEquals("s8-v1-ev", raw.latestEvidenceId)
        assertEquals("OK", raw.latestState)
        assertEquals(5_000L, raw.headLatestObservedAt)
        assertEquals(4_900L, raw.latestFactObservedAt)
        assertEquals("newapi/api/pricing", raw.latestEvidenceSource)

        val batched = sliceRowToHeads(rows)
        val head = batched.single()
        assertEquals(5_000L, head.observedAt)
        assertEquals(STANDARD, head.quotaSlice)
        assertNull(head.inputPrice)
        assertNull(head.outputPrice)
        assertNull(head.cachePrice)

        val legacy = readOnly { legacySliceLoad(db) }
        assertEquals("legacy slice path costs 4 kind scans + 1 head lookup", 5, lastReads.size)
        assertEquals(legacy, batched)
    }

    // =====================================================================
    // V2: 5 heads across 2 kinds — 1 statement (was 4 + 5 = 9) + order pin.
    // =====================================================================

    @Test
    fun fiveHeadsAcrossTwoKindsCostOneStatement() = runBlocking {
        seedSlice(qScope("v2a").storageKey(), SnapshotKind.ACCOUNT_QUOTA.name, STANDARD, "s8-v2-ev0", "OK", 1_000L, 1_000L)
        seedSlice(qScope("v2a").storageKey(), SnapshotKind.ACCOUNT_QUOTA.name, PRIORITY, "s8-v2-ev1", "LOW", 1_100L, 1_100L)
        seedSlice(qScope("v2b", cred = "s8-c-v2").storageKey(), SnapshotKind.ACCOUNT_QUOTA.name, BATCH, "s8-v2-ev2", "OK", 1_200L, 1_200L)
        seedSlice(qScope("v2a").storageKey(), SnapshotKind.TOKEN_QUOTA.name, STANDARD, "s8-v2-ev3", "OK", 1_300L, 1_300L)
        seedSlice(qScope("v2c", group = "vip").storageKey(), SnapshotKind.TOKEN_QUOTA.name, PRIORITY, "s8-v2-ev4", "OK", 1_400L, 1_400L)

        val rows = readOnly { db.snapshotFactDao().slicePriceQuotaHeadProjections(QUOTA_KINDS) }
        assertSliceStatements(1)
        assertEquals(5, rows.size)
        assertSqlOrderPinned(rows)
        val batched = sliceRowToHeads(rows)
        // Emission order already pinned at the SQL level above; oracle equality
        // below additionally pins row-for-row order (both sides same comparator).
        assertTrue(batched.all { it.inputPrice == null && it.outputPrice == null && it.cachePrice == null })

        val legacy = readOnly { legacySliceLoad(db) }
        assertEquals("legacy slice path fans out 4 + N = 9", 9, lastReads.size)
        assertEquals(legacy, batched)
    }

    // =====================================================================
    // V3: 3 heads / 2 malformed — 1 statement, Kotlin drop, no extra lookup.
    // =====================================================================

    @Test
    fun malformedScopesDroppedInKotlinWithoutExtraLookup() = runBlocking {
        seedSlice(qScope("v3ok").storageKey(), SnapshotKind.TOKEN_QUOTA.name, STANDARD, "s8-v3-ev-ok", "OK", 2_000L, 2_000L)
        // Malformed keys carry live facts: SQL still returns their rows (fact join
        // matched); Kotlin `parseScopeKey ?: continue` drops them — same as today.
        seedSlice(MALFORMED_A, SnapshotKind.ACCOUNT_QUOTA.name, STANDARD, "s8-v3-ev-m1", "OK", 2_100L, 2_100L)
        seedSlice(MALFORMED_B, SnapshotKind.TOKEN_QUOTA.name, PRIORITY, "s8-v3-ev-m2", "LOW", 2_200L, 2_200L)

        val rows = readOnly { db.snapshotFactDao().slicePriceQuotaHeadProjections(QUOTA_KINDS) }
        assertSliceStatements(1)
        assertEquals(3, rows.size) // Malformed rows returned by SQL ...
        val batched = sliceRowToHeads(rows)
        assertEquals(1, batched.size) // ... and dropped in Kotlin, exactly as today.
        assertEquals(qScope("v3ok").storageKey(), scopeKeyOf(batched.single()))
        assertTrue(
            "malformed lookup costs no extra statement beyond the single projection",
            lastReads.none { it.from(FACT_TABLE) },
        )

        val legacy = readOnly { legacySliceLoad(db) }
        // Legacy pays per-head evidence() even for malformed scopes (parse follows
        // the lookup): 4 kind scans + 3 head lookups = 7.
        assertEquals(7, lastReads.size)
        assertEquals(legacy, batched)
    }

    // =====================================================================
    // V4: 3 heads / 1 dangling — 1 statement, INNER JOIN drop == ?: continue.
    // =====================================================================

    @Test
    fun danglingHeadDroppedByInnerJoin() = runBlocking {
        seedSlice(qScope("v4a").storageKey(), SnapshotKind.ACCOUNT_QUOTA.name, STANDARD, "s8-v4-ev0", "OK", 3_000L, 3_000L)
        seedSlice(qScope("v4b").storageKey(), SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name, BATCH, "s8-v4-ev1", "LOW", 3_100L, 3_100L)
        // Dangling pointer: no fact/evidence row. INNER JOIN drops the row — no
        // extra query, no crash — matching legacy `evidence(id) ?: continue`.
        seedDanglingSlice(qScope("v4dang").storageKey(), SnapshotKind.TOKEN_QUOTA.name, STANDARD, "s8-v4-ev-missing", 3_200L)

        val rows = readOnly { db.snapshotFactDao().slicePriceQuotaHeadProjections(QUOTA_KINDS) }
        assertSliceStatements(1)
        assertEquals(2, rows.size)
        val batched = sliceRowToHeads(rows)
        assertEquals(2, batched.size)
        assertTrue(batched.none { it.observedAt == 3_200L })

        val legacy = readOnly { legacySliceLoad(db) }
        assertEquals(7, lastReads.size) // 4 kind scans + 3 head lookups (dangling pays one).
        assertEquals(legacy, batched)
    }

    // =====================================================================
    // V5: 12 heads across 4 kinds (1 malformed + 1 dangling) — 1 (was 16).
    // =====================================================================

    @Test
    fun twelveHeadsAcrossFourKindsCostOneStatement() = runBlocking {
        val kinds = QUOTA_KINDS
        val slices = listOf(STANDARD, PRIORITY, BATCH)
        var ev = 0
        var at = 4_000L
        // 10 valid heads spread across all 4 kinds (3/3/2/2).
        val perKind = listOf(3, 3, 2, 2)
        kinds.forEachIndexed { ki, kind ->
            repeat(perKind[ki]) { hi ->
                val scope = qScope("v5-$ki-$hi", cred = if (hi % 2 == 0) "s8-c-v5" else null)
                seedSlice(scope.storageKey(), kind, slices[(ki + hi) % 3], "s8-v5-ev-${ev++}", if (hi % 2 == 0) "OK" else "LOW", at, at)
                at += 100L
            }
        }
        seedSlice(MALFORMED_A, SnapshotKind.DEDICATED_TOKEN_QUOTA.name, STANDARD, "s8-v5-ev-mal", "OK", at, at)
        at += 100L
        seedDanglingSlice(qScope("v5dang").storageKey(), SnapshotKind.TOKEN_QUOTA.name, PRIORITY, "s8-v5-ev-missing", at)

        val rows = readOnly { db.snapshotFactDao().slicePriceQuotaHeadProjections(QUOTA_KINDS) }
        assertSliceStatements(1)
        assertEquals(11, rows.size) // 12 heads minus the INNER-JOIN-dropped dangling row.
        assertSqlOrderPinned(rows)
        val batched = sliceRowToHeads(rows)
        assertEquals(10, batched.size) // Minus malformed (Kotlin drop) as well.
        assertEquals(4, batched.map { it.kind }.toSet().size) // All 4 kinds represented.
        assertTrue(batched.all { it.inputPrice == null && it.outputPrice == null && it.cachePrice == null })
        assertTrue(batched.all { it.quotaSlice in slices })

        val legacy = readOnly { legacySliceLoad(db) }
        assertEquals("legacy slice path fans out 4 + 12 = 16", 16, lastReads.size)
        assertEquals(legacy, batched)
    }

    // =====================================================================
    // Full loader: ordinary projection 1 + slice projection 1 = 2 statements.
    // =====================================================================

    @Test
    fun fullLoaderCostsTwoStatementsRegardlessOfN() = runBlocking {
        val priceKinds = SnapshotKind.entries.filter { it.name in ModelRowMapper.PRICE_KINDS }.map { it.name }
        val allKinds = SnapshotKind.entries.map { it.name }
        val scope = qScope("vfull")
        db.evidenceDao().insert(
            EvidenceEntity("s8-vf-ev", "req-s8-vf-ev", P, "s8-model", "OK", status = 200, timestamp = 6_000L, source = "newapi/api/pricing"),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = "s8-vf-ev", scopeKey = scope.storageKey(), kind = SnapshotKind.PRICE.name, observedAt = 6_000L,
                snapshotId = null, state = "PAID", status = "OK", confidence = "OFFICIAL",
                providerVersion = null, pricingVersion = null, itemPricingVersion = null,
                quotaPerUnit = null, groupRatio = null, statusObservedAt = null,
                remainingRaw = null, usedRaw = null, grantedRaw = null, unlimited = null,
                expiresAtRaw = null, expiryMeaning = null, expressionHash = null, priceUnit = null,
            ),
        )
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(scope.storageKey(), SnapshotKind.PRICE.name, "s8-vf-ev", 6_000L, null, null),
        )
        seedSlice(qScope("vfull-s0").storageKey(), SnapshotKind.ACCOUNT_QUOTA.name, STANDARD, "s8-vf-sev0", "OK", 6_100L, 6_100L)
        seedSlice(qScope("vfull-s1").storageKey(), SnapshotKind.TOKEN_QUOTA.name, PRIORITY, "s8-vf-sev1", "LOW", 6_200L, 6_200L)

        readOnly {
            db.snapshotFactDao().priceQuotaHeadProjections(allKinds, priceKinds)
            db.snapshotFactDao().slicePriceQuotaHeadProjections(QUOTA_KINDS)
        }
        assertEquals(
            "ordinary projection must cost exactly 1 statement",
            1, lastReads.count { it.from("snapshot_heads") },
        )
        assertEquals(
            "slice projection must cost exactly 1 statement",
            1, lastReads.count { it.from(SLICE_TABLE) },
        )
        assertEquals(
            "full loadPriceQuotaHeads emission costs 2 projection statements regardless of N",
            2, lastReads.count { it.from("snapshot_heads") || it.from(SLICE_TABLE) },
        )
    }

    // =====================================================================
    // Oracle + mapping: test-local only, never merged into production.
    // =====================================================================

    /**
     * Byte-faithful replica of the pre-batch slice loop
     * (`AtlasViewModel.kt:2425-2446`): 4 unconditional kind scans, Kotlin
     * `(scopeKey, kind, quotaSlice)` sort, per-head `evidence(id)` incurred
     * BEFORE `parseScopeKey` (malformed scopes still cost a lookup),
     * quota-only `PriceQuotaHead` with null price legs and head observedAt.
     */
    private suspend fun legacySliceLoad(target: AtlasDatabase): List<PriceQuotaHead> {
        val dao = target.snapshotFactDao()
        val heads = QUOTA_KINDS.flatMap { dao.sliceHeadsByKind(it) }
            .sortedWith(compareBy({ it.scopeKey }, { it.kind }, { it.quotaSlice }))
        val out = ArrayList<PriceQuotaHead>()
        for (head in heads) {
            val fact = dao.evidence(head.latestEvidenceId) ?: continue
            val parts = parseScopeKey(head.scopeKey) ?: continue
            out += PriceQuotaHead(
                kind = head.kind,
                providerId = parts[0] ?: "",
                accountId = parts[1],
                credentialId = parts[2],
                modelId = parts[3] ?: "",
                group = parts[4],
                endpointId = parts[5],
                operation = parts[6],
                state = fact.state,
                inputPrice = null,
                outputPrice = null,
                cachePrice = null,
                observedAt = head.latestObservedAt,
                quotaSlice = head.quotaSlice,
            )
        }
        return out
    }

    /**
     * Kotlin-side mapping, byte-exact with the spec §4 caller rewrite:
     * production [parseScopeKey] unchanged (malformed -> null, dropped exactly
     * as today); price legs stay null (quota price gate); `observedAt` keeps
     * `head.latestObservedAt`; no SQL extraction or normalization.
     */
    private fun sliceRowToHeads(rows: List<SlicePriceQuotaHeadProjectionRow>): List<PriceQuotaHead> {
        val out = ArrayList<PriceQuotaHead>()
        for (row in rows) {
            val parts = parseScopeKey(row.headScopeKey) ?: continue
            out += PriceQuotaHead(
                kind = row.headKind,
                providerId = parts[0] ?: "",
                accountId = parts[1],
                credentialId = parts[2],
                modelId = parts[3] ?: "",
                group = parts[4],
                endpointId = parts[5],
                operation = parts[6],
                state = row.latestState,
                inputPrice = null,
                outputPrice = null,
                cachePrice = null,
                observedAt = row.headLatestObservedAt,
                quotaSlice = row.headQuotaSlice,
            )
        }
        // Idempotent guard reproducing the SQL ORDER BY (spec §3 note).
        return out.sortedWith(
            compareBy({ scopeKeyOf(it) }, { it.kind }, { it.quotaSlice ?: "" }),
        )
    }

    /** Rebuild the raw scopeKey for sort-key parity (test-local helper). */
    private fun scopeKeyOf(head: PriceQuotaHead): String = PricingScope(
        head.providerId, head.accountId, head.credentialId, head.modelId,
        head.group, head.endpointId ?: "", head.operation ?: "",
    ).storageKey()

    /** The batched slice path is exactly 1 FROM-slice-heads statement per emission. */
    private fun assertSliceStatements(expected: Int) {
        assertEquals(
            "slice path must cost exactly $expected projection statement(s) per emission",
            expected, lastReads.count { it.from(SLICE_TABLE) },
        )
        assertTrue(
            "slice path must not issue standalone snapshot_facts reads (facts only via INNER JOIN)",
            lastReads.none { it.from(FACT_TABLE) },
        )
        assertTrue(
            "slice path is quota-only: price_snapshots must never be read",
            lastReads.none { it.from(PRICE_TABLE) },
        )
    }

    /** ORDER BY (scopeKey ASC, kind ASC, quotaSlice ASC) as written in the SQL. */
    private fun assertSqlOrderPinned(rows: List<SlicePriceQuotaHeadProjectionRow>) {
        val keys = rows.map { Triple(it.headScopeKey, it.headKind, it.headQuotaSlice) }
        assertEquals(
            keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })),
            keys,
        )
    }

    // =====================================================================
    // Harness (fixtures live only in this file, never product data).
    // =====================================================================

    private data class SqlRead(val sql: String, val args: List<Any?>) {
        fun from(table: String): Boolean = Regex("\\bFROM\\s+[`\"]?$table[`\"]?\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(sql)
    }

    /** Real-query capture, no-DML guard, 400-bind budget, unchanged table counts. */
    private suspend fun <T> readOnly(block: suspend () -> T): T {
        val before = tableCounts()
        queries.clear()
        captureReads = true
        val result = try {
            block()
        } finally {
            captureReads = false
            lastReads = queries.toList()
        }
        assertTrue("real Room queries must have executed", lastReads.isNotEmpty())
        assertFalse(
            "projection reads must execute no DML",
            lastReads.any {
                Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.sql) &&
                    !it.sql.contains("room_table_modification_log", ignoreCase = true)
            },
        )
        for (query in lastReads) {
            assertTrue("read exceeds 400 binds: ${query.args.size}", query.args.size <= 400)
        }
        assertEquals("all table counts remain unchanged", before, tableCounts())
        return result
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

    private fun qScope(tag: String, cred: String? = null, group: String? = null) =
        PricingScope(P, "s8-a-$tag", cred, "", group, "", "UNKNOWN")

    private suspend fun seedSlice(
        scopeKey: String,
        kind: String,
        slice: String,
        evidenceId: String,
        state: String,
        headAt: Long,
        factAt: Long,
    ) {
        db.evidenceDao().insert(
            EvidenceEntity(
                evidenceId, "req-$evidenceId", P, "s8-model", "OK",
                status = 200, timestamp = factAt, source = "newapi/api/pricing",
            ),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId, scopeKey = scopeKey, kind = kind, observedAt = factAt,
                snapshotId = null, state = state, status = "OK", confidence = "OFFICIAL",
                providerVersion = null, pricingVersion = null, itemPricingVersion = null,
                quotaPerUnit = null, groupRatio = null, statusObservedAt = null,
                remainingRaw = null, usedRaw = null, grantedRaw = null, unlimited = null,
                expiresAtRaw = null, expiryMeaning = null, expressionHash = null, priceUnit = null,
            ),
        )
        db.snapshotFactDao().upsertSliceHead(
            SnapshotSliceHeadEntity(scopeKey, kind, slice, evidenceId, headAt, null, null),
        )
    }

    private suspend fun seedDanglingSlice(
        scopeKey: String,
        kind: String,
        slice: String,
        missingEvidenceId: String,
        headAt: Long,
    ) {
        db.snapshotFactDao().upsertSliceHead(
            SnapshotSliceHeadEntity(scopeKey, kind, slice, missingEvidenceId, headAt, null, null),
        )
    }

    companion object {
        private const val P = "s8-provider"
        private const val SLICE_TABLE = "snapshot_slice_heads"
        private const val FACT_TABLE = "snapshot_facts"
        private const val PRICE_TABLE = "price_snapshots"
        private const val STANDARD = "STANDARD_INTERACTIVE"
        private const val PRIORITY = "PRIORITY"
        private const val BATCH = "BATCH"
        private const val MALFORMED_A = "malformed-not-a-scope-key"
        private const val MALFORMED_B = "s8-bad-{not-a-key}"

        private val QUOTA_KINDS = listOf(
            SnapshotKind.ACCOUNT_QUOTA.name,
            SnapshotKind.TOKEN_QUOTA.name,
            SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name,
            SnapshotKind.DEDICATED_TOKEN_QUOTA.name,
        )
    }
}
