// =============================================================================
// CR-011 — LANDED TEST (same-slice). Anchored at HEAD aabbce2 (code identical to
// green baseline 3d46ece; docs-only delta since). Migration version 15 LOCKED by
// Main/Principal for the CR-011 slice (ai11-cr011-spec.md §4 dispatch step 6).
// File: app/src/androidTest/kotlin/dev/modelatlas/app/data/SnapshotSliceNamespaceIdentityTest.kt
// 6 @Test runtime namespace-identity cases, transcribed from
// /tmp/opencode/ai9-cr011-unsliced-draft.kt (1017 lines, 6 @Test) with every
// assertion kept verbatim except the production-form swaps noted below.
//
// SAME-SLICE PRODUCTION DEPENDENCY (prod owner landing concurrently, spec §4
// steps 1-5): SnapshotSliceHeadEntity + sliceHead/upsertSliceHead/
// sliceHeadsByKind DAO ports (Hunk A1+A2), MIGRATION_14_15 + version 15 (Hunk
// A3), writer slice branches (Hunks B1+B3). This file CALLS those production
// forms: recordSlicedMirror writes via sliceHead/upsertSliceHead,
// assertSliceHead cross-checks via sliceHead, case 4d via sliceHeadsByKind,
// case 6 drives the REAL syncQuotaPools caller. Raw-SQL readers are RETAINED
// as the independent verification leg (not a second implementation).
// By-design pending: case 6d unsliced-head-absent passes only after the
// writer's slice branch (Hunk B1+B3) lands — kept as specified.
// No live probes, no secrets, no mock-as-product-data. CI-only verification.
// =============================================================================

package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.QuotaDimension
import dev.modelatlas.core.dedicated.QuotaPool
import dev.modelatlas.core.dedicated.QuotaPoolSlice
import dev.modelatlas.core.dedicated.QuotaScopeKind
import dev.modelatlas.core.domain.PricingScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Room runtime identity regression for CR-011 slice-head design
 * (landed same-slice at HEAD aabbce2; version 15 LOCKED by Main).
 *
 * What this landed test proves (one assertion family per @Test):
 *   1. The unsliced legacy namespace (snapshot_heads, identity (scopeKey, kind))
 *      stays BYTE-IDENTICAL under the slice identity design: same table schema
 *      (six legacy columns, PK positions 1/2, NO slice column, no new identity
 *      dimension), same strict-advance write semantics, same exact reader
 *      queries, parseScopeKey round-trip unchanged.
 *   2. Sliced observations (quotaSlice non-null) can never push or erase an
 *      unsliced head, and unsliced writes can never advance a slice head.
 *   3. STANDARD_INTERACTIVE / PRIORITY / BATCH each get a DISTINCT
 *      latest/lastGood head under one exact 7-dim scope + one quota kind:
 *      under today's (scopeKey,kind)-only upsert the same trio collapses into
 *      ONE head row overwritten three times (the I1 collision being fixed).
 *   4. Exact reader queries: head(scopeKey,kind) sees ONLY the unsliced row;
 *      sliceHead(scopeKey,kind,quotaSlice) sees exactly one per-slice row
 *      (wrong slice name fail-closed null, no LIKE); headsByKind/sliceHeadsByKind
 *      enumerate without LIMIT-1 picking, caller-side parseScopeKey filter;
 *      history() keeps all slices' facts visible (slice rides in the fact row).
 *   5. Duplicate-slice ABORT preserved: facts @Insert(ABORT); slice-head PK
 *      (scopeKey,kind,quotaSlice) rejects duplicate direct INSERT; H5 dedup
 *      dims include quotaSlice; facts->evidence FK RESTRICT intact; fact rows
 *      byte-identical before/after every head operation (append-only).
 *   6. Gemini route stays UNSUPPORTED; syncQuotaPools B3 batch dedup (poolKey
 *      includes quotaSlice) preserved; no phantom slice groups from weird values.
 *
 * Harness: real Room in-memory DB (verified DedicatedSnapshotSyncTest pattern),
 * fresh build at v15. This is NOT a migration test: the true 14->15 upgrade
 * fixture is the sibling AtlasMigration14To15Test.kt. The writer helpers
 * reproduce production record() semantics EXACTLY (cited line refs at HEAD
 * aabbce2) with the slice leg calling the production DAO ports; case 6 drives
 * the real production caller (syncQuotaPools with sliced pools, memo §3.3).
 * All DB-level assertions kept verbatim from the draft.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotSliceNamespaceIdentityTest {
    private lateinit var db: AtlasDatabase
    private lateinit var sync: DedicatedSnapshotSync

    @Before
    fun setUp() = runBlocking {
        // Real harness pattern (DedicatedSnapshotSyncTest.kt:40-45). This slice
        // builds at v15 WITH snapshot_slice_heads (same-slice Hunk A3).
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        sync = DedicatedSnapshotSync(db)
        seedParents(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Case 1 — unsliced legacy namespace stays BYTE-IDENTICAL under the slice
    // identity design. Writes use ONLY real production symbols in the exact
    // record() sequence (evidence -> fact -> head strict-advance,
    // DedicatedSnapshotSync.kt:654-675); asserts table schema byte-identity
    // (six legacy columns, PK (scopeKey,kind), no slice column), strict-advance
    // semantics, exact reader queries, parseScopeKey parity.
    // -------------------------------------------------------------------------
    @Test
    fun unslicedLegacyNamespaceStaysByteIdentical_underSliceIdentityDesign() = runBlocking {
        val scope = TRIO_SCOPE
        val key = scope.storageKey()

        // Version sanity: this slice runs at version 15 WITH snapshot_slice_heads
        // (LOCKED by Main/Principal for CR-011; identity memo §0/§4.1; oracle §6).
        assertEquals(16, db.openHelper.writableDatabase.version)

        // Two unsliced writes (older good @1000, newer good @2000) — the exact
        // legacy record() sequence, byte-identical semantics asserted below.
        recordUnslicedMirror(scope, evidence(1000L), unslicedFact(scope, "uns-1", 1000L, "QUOTA_AVAILABLE", "snap-uns-1"), good = true)
        recordUnslicedMirror(scope, evidence(2000L), unslicedFact(scope, "uns-2", 2000L, "QUOTA_AVAILABLE", "snap-uns-2"), good = true)

        // (a) Table schema byte-identity: snapshot_heads has EXACTLY the six
        //     legacy columns with PK positions (scopeKey=1, kind=2), NO slice
        //     column / no new identity dimension, and no FK — additive-only
        //     design (memo §3.1: the discriminator lives in the NEW table
        //     only; full per-object fresh-vs-upgraded DDL parity is the
        //     SIBLING draft's migration fixture, not duplicated here).
        assertEquals(LEGACY_HEAD_COLUMNS, headColumnNames(db.openHelper.writableDatabase))
        assertEquals(LEGACY_HEAD_PK_POSITIONS, headPkPositions(db.openHelper.writableDatabase))
        assertFalse(hasForeignKey(db.openHelper.writableDatabase, "snapshot_heads"))

        // (b) Head identity is (scopeKey, kind) ONLY: the exact reader query
        //     returns ONE row and it carries the strict-advanced values from the
        //     last unsliced write (latest=uns-2@2000; lastGood=uns-2@2000).
        val head = db.snapshotFactDao().head(key, DEDICATED_ACCOUNT_QUOTA)!!
        assertEquals("uns-2", head.latestEvidenceId)
        assertEquals(2000L, head.latestObservedAt)
        assertEquals("snap-uns-2", head.lastGoodSnapshotId)
        assertEquals(2000L, head.lastGoodObservedAt)

        // (c) Exact reader queries unchanged: head() by (scopeKey, kind);
        //     headsByKind() enumerates without LIKE/LIMIT-1 (KDoc contract,
        //     SnapshotFacts.kt:102-108); every enumerated row parses to 7 dims.
        val heads = db.snapshotFactDao().headsByKind(DEDICATED_ACCOUNT_QUOTA)
        assertEquals(1, heads.size)
        assertEquals("uns-2", heads.single().latestEvidenceId)
        assertTrue(parseScopeKey(heads.single().scopeKey)?.size == 7)

        // (d) History unchanged: all facts visible, ordered observedAt DESC,
        //     evidenceId ASC (SnapshotFacts.kt:113-114), slice rides in the row.
        val history = db.snapshotFactDao().history(key, DEDICATED_ACCOUNT_QUOTA)
        assertEquals(listOf("uns-2", "uns-1"), history.map { it.evidenceId })
        assertNull(history.single { it.evidenceId == "uns-1" }.quotaSlice)

        // (e) parseScopeKey round-trip parity on every row (oracle §5).
        assertScopeKeyParses(db.openHelper.writableDatabase, "snapshot_facts")
        assertScopeKeyParses(db.openHelper.writableDatabase, "snapshot_heads")

        // (f) syncQuotaPools B3 batch dedup still keyed WITH quotaSlice in the
        //     pool key (DedicatedSnapshotSync.kt:307-315) — unchanged by the
        //     slice identity (asserted via the caller-level guard in Case 6).
    }

    // -------------------------------------------------------------------------
    // Case 2 — cross-namespace isolation, both directions: sliced observations
    // can never push or erase an unsliced head; unsliced writes can never
    // advance a slice head (memo §3.3 "No cross-namespace reads or writes in
    // either direction"). The sliced writes go through the production slice branch
    // (Hunk B1 shape, DAO ports below); the unsliced writes go through the real record()
    // sequence. Head rows captured and re-compared byte-for-byte (six columns).
    // -------------------------------------------------------------------------
    @Test
    fun slicedWritesNeverTouchUnslicedNamespace_andViceVersa() = runBlocking {
        val scope = TRIO_SCOPE
        val key = scope.storageKey()
        val kind = DEDICATED_ACCOUNT_QUOTA

        // Unsliced head exists (older good @1000; newer FAILURE @1500 — failure
        // legs write quotaSlice null + good=false exactly like
        // DedicatedSnapshotSync.kt:580-593, and must not erase last-good).
        recordUnslicedMirror(scope, evidence(1000L), unslicedFact(scope, "iso-uns-1", 1000L, "QUOTA_AVAILABLE", "snap-iso-uns-1"), good = true)
        recordUnslicedMirror(scope, evidence(1500L), unslicedFact(scope, "iso-uns-2", 1500L, "UNKNOWN", snapshotId = null), good = false)
        val beforeUnsliced = rawHeadRow(db.openHelper.writableDatabase, key, kind)!!
        assertEquals("iso-uns-2", beforeUnsliced.latestEvidenceId)
        assertEquals("snap-iso-uns-1", beforeUnsliced.lastGoodSnapshotId)

        // Three sliced observations after the unsliced head exists.
        val beforeFacts = factRows(db.openHelper.writableDatabase, "snapshot_facts")
        recordSlicedMirror(scope, evidence(2100L), slicedFact(scope, "iso-std", 2100L, "QUOTA_AVAILABLE", "snap-iso-std", QuotaPoolSlice.STANDARD_INTERACTIVE), good = true)
        recordSlicedMirror(scope, evidence(2200L), slicedFact(scope, "iso-pri", 2200L, "QUOTA_AVAILABLE", "snap-iso-pri", QuotaPoolSlice.PRIORITY), good = true)
        recordSlicedMirror(scope, evidence(2300L), slicedFact(scope, "iso-bat", 2300L, "UNKNOWN", null, QuotaPoolSlice.BATCH), good = false)

        // (a) The unsliced head row is BYTE-IDENTICAL after three sliced writes:
        //     not advanced, not erased, all six columns exact (old rows never
        //     rewritten — the unsliced head is the recorded pointer).
        assertEquals(
            beforeUnsliced,
            rawHeadRow(db.openHelper.writableDatabase, key, kind)!!,
        )

        // (b) The three sliced writes only APPENDED fact rows (append-only; no
        //     fact rewritten — old facts/FKs never rewritten).
        assertEquals(beforeFacts, factRows(db.openHelper.writableDatabase, "snapshot_facts").take(beforeFacts.size))
        assertEquals(beforeFacts.size + 3, factRows(db.openHelper.writableDatabase, "snapshot_facts").size)

        // (c) Vice versa: a new unsliced write must not advance any slice head.
        val beforeSliceHeads = listOf(
            rawSliceHeadRow(db.openHelper.writableDatabase, key, kind, "STANDARD_INTERACTIVE")!!,
            rawSliceHeadRow(db.openHelper.writableDatabase, key, kind, "PRIORITY")!!,
            rawSliceHeadRow(db.openHelper.writableDatabase, key, kind, "BATCH")!!,
        )
        recordUnslicedMirror(scope, evidence(3000L), unslicedFact(scope, "iso-uns-3", 3000L, "QUOTA_AVAILABLE", "snap-iso-uns-3"), good = true)
        assertEquals(
            beforeSliceHeads,
            listOf(
                rawSliceHeadRow(db.openHelper.writableDatabase, key, kind, "STANDARD_INTERACTIVE")!!,
                rawSliceHeadRow(db.openHelper.writableDatabase, key, kind, "PRIORITY")!!,
                rawSliceHeadRow(db.openHelper.writableDatabase, key, kind, "BATCH")!!,
            ),
        )
        // ...while the unsliced head DID strict-advance (legacy semantics kept).
        assertEquals("iso-uns-3", db.snapshotFactDao().head(key, kind)!!.latestEvidenceId)

        // (d) Exact readback: today's quota() readback reads ONLY the unsliced
        //     namespace (DedicatedSnapshotSync.kt:347-364, head(scopeKey,
        //     kind.dedicatedKind().name)) — under the design it must NOT see
        //     slice heads; the additive quota(scope, kind, slice) overload
        //     (memo §3.3, same-slice Hunk B3) is the per-slice readback.
        assertEquals("iso-uns-3", sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA).latest!!.evidenceId)
    }

    // -------------------------------------------------------------------------
    // Case 3 — the audit's core trio: same exact 7-dim scope + ONE quota kind
    // (DEDICATED_ACCOUNT_QUOTA), three slices at DISTINCT observedAt => THREE
    // independent slice heads, each with its own latest/lastGood; zero
    // cross-slice interference; then each slice re-written newer and ONLY that
    // slice's head advances. Under today's (scopeKey,kind)-only upsert this
    // trio collapses into ONE head overwritten three times (I1 collision) —
    // the distinct trio IS the regression proof.
    // -------------------------------------------------------------------------
    @Test
    fun sliceTrioEachSliceGetsDistinctLatestAndLastGood_zeroCrossSliceInterference() = runBlocking {
        val scope = TRIO_SCOPE
        val key = scope.storageKey()
        val kind = DEDICATED_ACCOUNT_QUOTA

        // Distinct observedAt => every group's latest unique (all derivations
        // unambiguous — memo §4.2). STANDARD good @2100; PRIORITY good @2200;
        // BATCH latest is a Failure (snapshotId null, state UNKNOWN, good=false
        // exactly like DedicatedSnapshotSync.kt:580-593) => lastGood honestly
        // absent (NULL), not invented.
        recordSlicedMirror(scope, evidence(2100L), slicedFact(scope, "trio-std", 2100L, "QUOTA_AVAILABLE", "snap-trio-std", QuotaPoolSlice.STANDARD_INTERACTIVE), good = true)
        recordSlicedMirror(scope, evidence(2200L), slicedFact(scope, "trio-pri", 2200L, "QUOTA_AVAILABLE", "snap-trio-pri", QuotaPoolSlice.PRIORITY), good = true)
        recordSlicedMirror(scope, evidence(2300L), slicedFact(scope, "trio-bat", 2300L, "UNKNOWN", null, QuotaPoolSlice.BATCH), good = false)

        // (a) Three INDEPENDENT slice heads, distinct latest/lastGood each.
        assertSliceHead(key, kind, "STANDARD_INTERACTIVE", "trio-std", 2100L, "snap-trio-std", 2100L)
        assertSliceHead(key, kind, "PRIORITY", "trio-pri", 2200L, "snap-trio-pri", 2200L)
        // BATCH: latest present, lastGood NULL (only failure observed — honest).
        assertSliceHead(key, kind, "BATCH", "trio-bat", 2300L, null, null)

        // (b) Exactly 3 slice rows for this scope+kind; no unsliced head row was
        //     created (no unsliced fact was ever written in this test).
        assertEquals(3, sliceHeadCount(key, kind))
        assertNull(rawHeadRow(db.openHelper.writableDatabase, key, kind))
        assertTrue(db.snapshotFactDao().headsByKind(kind).isEmpty())

        // (c) Cross-slice interference zero: each slice re-written newer moves
        //     ONLY its own head (strict-advance per (scopeKey,kind,quotaSlice)).
        //     STANDARD newer FAILURE @2400 -> latest advances, lastGood STAYS.
        recordSlicedMirror(scope, evidence(2400L), slicedFact(scope, "trio-std-2", 2400L, "UNKNOWN", null, QuotaPoolSlice.STANDARD_INTERACTIVE), good = false)
        val std = readSliceHeadRow(key, kind, "STANDARD_INTERACTIVE")!!
        assertEquals("trio-std-2", std.latestEvidenceId)
        assertEquals(2400L, std.latestObservedAt)
        assertEquals("snap-trio-std", std.lastGoodSnapshotId)
        assertEquals(2100L, std.lastGoodObservedAt)
        // PRIORITY untouched by the STANDARD write.
        assertEquals("trio-pri", readSliceHeadRow(key, kind, "PRIORITY")!!.latestEvidenceId)
        assertEquals(2200L, readSliceHeadRow(key, kind, "PRIORITY")!!.latestObservedAt)
        assertEquals("trio-bat", readSliceHeadRow(key, kind, "BATCH")!!.latestEvidenceId)
        // PRIORITY newer GOOD @2500 -> both advance.
        recordSlicedMirror(scope, evidence(2500L), slicedFact(scope, "trio-pri-2", 2500L, "QUOTA_LOW", "snap-trio-pri-2", QuotaPoolSlice.PRIORITY), good = true)
        val pri = readSliceHeadRow(key, kind, "PRIORITY")!!
        assertEquals("trio-pri-2", pri.latestEvidenceId)
        assertEquals("snap-trio-pri-2", pri.lastGoodSnapshotId)
        assertEquals(2500L, pri.lastGoodObservedAt)
        // BATCH newer GOOD @2600 -> lastGood fills the previously-NULL slot.
        recordSlicedMirror(scope, evidence(2600L), slicedFact(scope, "trio-bat-2", 2600L, "QUOTA_AVAILABLE", "snap-trio-bat-2", QuotaPoolSlice.BATCH), good = true)
        val bat = readSliceHeadRow(key, kind, "BATCH")!!
        assertEquals("trio-bat-2", bat.latestEvidenceId)
        assertEquals("snap-trio-bat-2", bat.lastGoodSnapshotId)
        // STANDARD lastGood never moved by PRIORITY/BATCH writes.
        assertEquals("snap-trio-std", readSliceHeadRow(key, kind, "STANDARD_INTERACTIVE")!!.lastGoodSnapshotId)
        assertEquals(2100L, readSliceHeadRow(key, kind, "STANDARD_INTERACTIVE")!!.lastGoodObservedAt)

        // (d) Still exactly 3 slice rows; facts count = 6 (3+3), append-only.
        assertEquals(3, sliceHeadCount(key, kind))
        assertEquals(6, db.snapshotFactDao().history(key, kind).size)
    }

    // -------------------------------------------------------------------------
    // Case 4 — exact reader queries, fail-closed both namespaces (memo §3.3):
    // head() sees ONLY the unsliced row; sliceHead() sees exactly one per-slice
    // row, wrong slice name => null (no LIKE, no LIMIT-1 picking);
    // headsByKind/sliceHeadsByKind enumerate all rows with caller-side
    // parseScopeKey filtering; history() keeps all slices visible.
    // -------------------------------------------------------------------------
    @Test
    fun exactReaderQueries_sliceAndUnslicedFailClosedDistinct() = runBlocking {
        val scope = TRIO_SCOPE
        val key = scope.storageKey()
        val kind = DEDICATED_ACCOUNT_QUOTA

        recordUnslicedMirror(scope, evidence(1000L), unslicedFact(scope, "rd-uns", 1000L, "QUOTA_AVAILABLE", "snap-rd-uns"), good = true)
        recordSlicedMirror(scope, evidence(2100L), slicedFact(scope, "rd-std", 2100L, "QUOTA_AVAILABLE", "snap-rd-std", QuotaPoolSlice.STANDARD_INTERACTIVE), good = true)
        recordSlicedMirror(scope, evidence(2200L), slicedFact(scope, "rd-pri", 2200L, "QUOTA_AVAILABLE", "snap-rd-pri", QuotaPoolSlice.PRIORITY), good = true)

        // (a) head(scopeKey, kind): exactly the unsliced row; slice heads
        //     invisible to it (namespace separation).
        val head = db.snapshotFactDao().head(key, kind)!!
        assertEquals("rd-uns", head.latestEvidenceId)
        assertEquals(1000L, head.latestObservedAt)

        // (b) sliceHead(scopeKey, kind, quotaSlice): exactly one per-slice row
        //     each; WRONG slice name => null (fail-closed, no LIKE prefix).
        assertSliceHead(key, kind, "STANDARD_INTERACTIVE", "rd-std", 2100L, "snap-rd-std", 2100L)
        assertSliceHead(key, kind, "PRIORITY", "rd-pri", 2200L, "snap-rd-pri", 2200L)
        assertNull(readSliceHeadRow(key, kind, "BATCH"))
        // Wrong/illegal slice name => null (fail-closed, no LIKE prefix match).
        assertNull(readSliceHeadRow(key, kind, "STANDARD_INTERACTIVE_X"))

        // (c) No phantom slice group from an illegal non-empty string (memo
        //     §3.2: value space is {null} ∪ QuotaPoolSlice.name; a corrupt
        //     value must never create a slice group).
        assertEquals(2, sliceHeadCount(key, kind))

        // (d) headsByKind (unsliced namespace) does NOT include slice heads;
        //     sliceHeadsByKind (production port, same-slice Hunk A2) enumerates
        //     both slices without LIMIT-1 picking; caller-side parseScopeKey
        //     filter parity.
        assertEquals(1, db.snapshotFactDao().headsByKind(kind).size)
        assertEquals(
            setOf("STANDARD_INTERACTIVE", "PRIORITY"),
            sliceHeadsByKindRaw(kind).map { it.quotaSlice }.toSet(),
        )
        // Production-port cross-check (same-slice Hunk A2): DAO enumeration
        // without LIMIT-1 picking sees the same two slices.
        assertEquals(
            setOf("STANDARD_INTERACTIVE", "PRIORITY"),
            db.snapshotFactDao().sliceHeadsByKind(kind).map { it.quotaSlice }.toSet(),
        )
        assertTrue(sliceHeadsByKindRaw(kind).all { parseScopeKey(it.scopeKey ?: return@all false)?.size == 7 })

        // (e) history(): ALL slices' facts visible, ordered observedAt DESC,
        //     evidenceId ASC; slice rides in the fact row (quotaSlice column).
        val history = db.snapshotFactDao().history(key, kind)
        assertEquals(listOf("rd-pri", "rd-std", "rd-uns"), history.map { it.evidenceId })
        assertEquals("PRIORITY", history[0].quotaSlice)
        assertEquals("STANDARD_INTERACTIVE", history[1].quotaSlice)
        assertNull(history[2].quotaSlice)

        // (f) byScopeKindObserved (H5 cross-call dedup readback): both slice
        //     facts AND the unsliced fact at the same observedAt are returned
        //     distinctly — the dedup dims include quotaSlice
        //     (DedicatedSnapshotSync.kt:729-734), so same observedAt with
        //     different slices are NOT one row.
        recordSlicedMirror(scope, evidence(2100L), slicedFact(scope, "rd-std-dup", 2100L, "QUOTA_AVAILABLE", "snap-rd-std-dup", QuotaPoolSlice.BATCH), good = true)
        assertEquals(2, db.snapshotFactDao().byScopeKindObserved(key, kind, 2100L).size)
    }

    // -------------------------------------------------------------------------
    // Case 5 — duplicate-slice ABORT preserved + old facts/FKs never rewritten
    // (oracle §5 fail-closed): facts @Insert(ABORT) duplicate evidenceId;
    // slice-head PK (scopeKey,kind,quotaSlice) rejects duplicate direct INSERT
    // (upsert REPLACE only via the strict-advance port matching upsertHead);
    // FK RESTRICT (facts->evidence) intact after slice writes; fact rows
    // byte-identical before/after every head operation (append-only, no
    // UPDATE/DELETE in the DAO — SnapshotFacts.kt:93-149 verified).
    // -------------------------------------------------------------------------
    @Test
    fun duplicateSliceIdentityAborts_factsAndFksNeverRewritten() = runBlocking {
        val scope = TRIO_SCOPE
        val key = scope.storageKey()
        val kind = DEDICATED_ACCOUNT_QUOTA

        recordSlicedMirror(scope, evidence(2100L), slicedFact(scope, "ab-std", 2100L, "QUOTA_AVAILABLE", "snap-ab-std", QuotaPoolSlice.STANDARD_INTERACTIVE), good = true)
        val beforeFacts = factRows(db.openHelper.writableDatabase, "snapshot_facts")
        val beforeSliceHeads = allSliceHeadRows()

        // (a) facts @Insert(ABORT): duplicate evidenceId ABORTS (preserved).
        expectAbort {
            db.snapshotFactDao().insert(
                slicedFact(scope, "ab-std", 2100L, "QUOTA_LOW", "snap-ab-std-dup", QuotaPoolSlice.PRIORITY),
            )
        }
        // The ABORT left no row and rewrote nothing (immutable facts).
        assertEquals(beforeFacts, factRows(db.openHelper.writableDatabase, "snapshot_facts"))

        // (b) Slice-head identity constraint enforced: duplicate
        //     (scopeKey, kind, quotaSlice) direct INSERT ABORTS. The
        //     strict-advance path uses upsert(REPLACE) — matching upsertHead
        //     (SnapshotFacts.kt:96-97) — but a RAW duplicate must never be
        //     silently replaced by a non-writer path.
        expectAbort {
            db.openHelper.writableDatabase.execSQL(SLICE_HEAD_INSERT.format(
                key, kind, "STANDARD_INTERACTIVE", "ab-std-rogue", 9999L, "NULL", "NULL",
            ))
        }
        // The rogue INSERT left the recorded head untouched (byte-identical).
        assertEquals(beforeSliceHeads, allSliceHeadRows())

        // (c) FK RESTRICT preserved after slice writes: snapshot_facts.evidenceId
        //     -> evidence.id (RESTRICT, SnapshotFacts.kt:38-41) still blocks
        //     parent deletion; no evidence row was rewritten or dropped.
        expectAbort {
            db.openHelper.writableDatabase.execSQL("DELETE FROM evidence WHERE id = 'ab-std'")
        }

        // (d) Append-only: no UPDATE/DELETE exists in SnapshotFactDao
        //     (SnapshotFacts.kt:93-149) — asserted behaviorally: after every
        //     head/slice-head operation above, ALL fact rows are byte-identical
        //     plus only appended rows; all head rows never rewritten.
        recordSlicedMirror(scope, evidence(2200L), slicedFact(scope, "ab-pri", 2200L, "QUOTA_AVAILABLE", "snap-ab-pri", QuotaPoolSlice.PRIORITY), good = true)
        assertEquals(beforeFacts, factRows(db.openHelper.writableDatabase, "snapshot_facts").take(beforeFacts.size))
        assertEquals(beforeFacts.size + 1, factRows(db.openHelper.writableDatabase, "snapshot_facts").size)
        assertEquals(
            beforeSliceHeads,
            allSliceHeadRows().filter { it.quotaSlice != "PRIORITY" },
        )

        // (e) Whole-DB FK check clean (oracle §5).
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    // -------------------------------------------------------------------------
    // Case 6 — Gemini route stays DISABLED + caller-level duplicate-slice ABORT
    // preserved (B3). Gemini enable is NOT part of this repair (audit I1
    // precondition: separate slice-head identity + backward compat + real trio
    // regression BEFORE Gemini enable). buildDedicatedRoutes() is private
    // (AtlasRepository.kt:531), so the real committed test asserts the
    // disabled route via the documented static evidence below plus the
    // Room-level consequence: nothing ever reaches a slice head except through
    // the writer's explicit slice branch (no phantom groups).
    // -------------------------------------------------------------------------
    @Test
    fun geminiRouteStaysDisabled_batchDuplicateSliceRejected() = runBlocking {
        // Per-project scope: pool.projectId must equal scope.group
        // (validatePoolMatchesScope, DedicatedSnapshotSync.kt:813); credentialId
        // null => DEDICATED_ACCOUNT_QUOTA (syncQuotaPools :294, :798).
        val scope = PROJ_SCOPE
        val kind = DEDICATED_ACCOUNT_QUOTA

        // (a) STATIC EVIDENCE (verified at HEAD aabbce2, grep over
        //     app/src/main/kotlin): the gemini family route stays
        //     `quotaKind = DedicatedQuotaKind.UNSUPPORTED`
        //     (AtlasRepository.kt:560); `syncQuotaPools`
        //     (DedicatedSnapshotSync.kt:286) and `geminiScope` (:937) have NO
        //     production callers — the collision is LATENT, real production DBs
        //     hold zero sliced facts, and this draft's fixtures are test-only
        //     (never product data). The real committed test MUST keep this
        //     invariant documented and re-verify the line at its own HEAD
        //     before running; if Main wants a runtime assertion, the
        //     implementation slice may add an additive route-kind seam — that
        //     decision is NOT silently omitted here, it is out of this draft's
        //     scope. No Gemini enable is proposed anywhere in this draft.

        // (b) Caller-level duplicate-slice ABORT preserved: syncQuotaPools B3
        //     batch dedup keys on (scopeKey, kind, poolKey) where poolKey
        //     includes quotaSlice (DedicatedSnapshotSync.kt:307-315) — two
        //     entries sharing scope+kind+pool dims INCLUDING slice are
        //     rejected ("Quota entries must not share scope"); two entries
        //     differing ONLY in slice are NOT duplicates (distinct slices are
        //     independent pools, never averaged/overwritten).
        val pool = { slice: QuotaPoolSlice? ->
            DedicatedQuotaEntry(
                scope, null,
                DedicatedResult.Success(
                    evidence(4000L),
                    QuotaPool(
                        scopeKind = QuotaScopeKind.PER_PROJECT_MODEL,
                        modelId = "fixture-model",
                        rpm = QuotaDimension.known(30L),
                        provenance = DedicatedProvenance.OFFICIAL_DOC,
                        projectId = "proj-15",
                        quotaSlice = slice,
                    ),
                ),
            )
        }
        expectIllegalArgument {
            sync.syncQuotaPools(listOf(pool(QuotaPoolSlice.PRIORITY), pool(QuotaPoolSlice.PRIORITY)))
        }
        // Distinct slices at the same scope+kind are NOT duplicates (legal batch).
        val results = sync.syncQuotaPools(listOf(pool(QuotaPoolSlice.STANDARD_INTERACTIVE), pool(QuotaPoolSlice.PRIORITY)))
        assertEquals(2, results.size)

        // (c) The legal batch wrote TWO slice facts carrying their own slices;
        //     the fact rows carry quotaSlice exactly (Success leg
        //     DedicatedSnapshotSync.kt:574 quotaSlice = pool.quotaSlice?.name).
        val facts = db.snapshotFactDao().byScopeKindObserved(scope.storageKey(), "DEDICATED_ACCOUNT_QUOTA", 4000L)
        assertEquals(2, facts.size)
        assertEquals(
            setOf("STANDARD_INTERACTIVE", "PRIORITY"),
            facts.map { it.quotaSlice }.toSet(),
        )
        // (d) No phantom slice group for illegal values (memo §3.2); and the
        //     writer's slice branch must never write the unsliced head for this
        //     scope+kind (no unsliced fact was written here; under the design
        //     the slice namespace is fully separate).
        assertEquals(2, sliceHeadCount(scope.storageKey(), kind))
        assertNull(rawHeadRow(db.openHelper.writableDatabase, scope.storageKey(), kind))

        // (e) Whole-DB FK check clean.
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    // ---- seeds (all test-only fixture; no secret-like names) ----------------

    /** Minimal FK parents, real DAO inserts (DedicatedSnapshotSyncTest pattern). */
    private suspend fun seedParents(db: AtlasDatabase) {
        db.providerDao().insert(ProviderEntity("p15", "Fixture Provider"))
        db.accountDao().insert(AccountEntity("a15", "p15"))
        db.credentialDao().insert(CredentialEntity("c15", "a15"))
        db.endpointDao().insert(
            EndpointEntity("e15", "p15", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
    }

    /** DedicatedEvidence mirror (DedicatedContracts.kt:177-184, top-level in
     *  dev.modelatlas.core.dedicated). */
    private fun evidence(observedAt: Long) = DedicatedEvidence(
        source = "gemini/rate-limits",
        observedAt = observedAt,
        provenance = DedicatedProvenance.OFFICIAL_DOC,
        httpStatus = 200,
        providerVersion = null,
    )

    /** Unsliced fact: every quota column null — the legacy/NewApi shape
     *  (NewApiSnapshotStore.kt:429-433 verified: all quota columns null). */
    private fun unslicedFact(
        scope: PricingScope,
        id: String,
        observedAt: Long,
        state: String,
        snapshotId: String?,
    ) = SnapshotFactEntity(
        evidenceId = id,
        scopeKey = scope.storageKey(),
        kind = DEDICATED_ACCOUNT_QUOTA,
        observedAt = observedAt,
        snapshotId = snapshotId,
        state = state,
        status = "OK",
        confidence = "OFFICIAL",
        providerVersion = null,
        pricingVersion = null,
        itemPricingVersion = null,
        quotaPerUnit = null,
        groupRatio = null,
        statusObservedAt = null,
        remainingRaw = null,
        usedRaw = null,
        grantedRaw = null,
        unlimited = null,
        expiresAtRaw = null,
        expiryMeaning = null,
        expressionHash = null,
        priceUnit = null,
    )

    /** Sliced fact: quotaScopeKind/quotaProjectId/quotaSlice carried exactly like
     *  the quota Success leg (DedicatedSnapshotSync.kt:568-578). */
    private fun slicedFact(
        scope: PricingScope,
        id: String,
        observedAt: Long,
        state: String,
        snapshotId: String?,
        slice: QuotaPoolSlice,
    ) = SnapshotFactEntity(
        evidenceId = id,
        scopeKey = scope.storageKey(),
        kind = DEDICATED_ACCOUNT_QUOTA,
        observedAt = observedAt,
        snapshotId = snapshotId,
        state = state,
        status = "OK",
        confidence = "OFFICIAL",
        providerVersion = null,
        pricingVersion = null,
        itemPricingVersion = null,
        quotaPerUnit = null,
        groupRatio = null,
        statusObservedAt = null,
        remainingRaw = null,
        usedRaw = null,
        grantedRaw = null,
        unlimited = null,
        expiresAtRaw = null,
        expiryMeaning = null,
        expressionHash = null,
        priceUnit = null,
        quotaScopeKind = "PER_PROJECT_MODEL",
        quotaProjectId = "proj-15",
        quotaSlice = slice.name,
        isEstimated = false,
    )

    // ---- writer mirrors (production semantics, cited) ------------------------

    /**
     * Exact mirror of production record() (DedicatedSnapshotSync.kt:654-675;
     * NewApiSnapshotStore.kt:414-427 identical strict-advance): evidence ->
     * fact -> head(scopeKey, kind) -> strict-advance -> upsertHead(REPLACE).
     * Uses ONLY real existing production symbols. The unsliced leg is
     * byte-identical by design (writer branches on isNullOrEmpty); case 6
     * additionally drives the real production caller (syncQuotaPools) — the
     * assertions above are kept verbatim.
     */
    private suspend fun recordUnslicedMirror(
        scope: PricingScope,
        evidence: DedicatedEvidence,
        fact: SnapshotFactEntity,
        good: Boolean,
    ) {
        require(evidence.observedAt >= 0) { "Invalid observation time" }
        db.evidenceDao().insert(
            EvidenceEntity(
                fact.evidenceId, fact.evidenceId, scope.providerId, scope.modelId,
                fact.status, evidence.httpStatus, evidence.observedAt, evidence.source,
            ),
        )
        db.snapshotFactDao().insert(fact)
        val old = db.snapshotFactDao().head(fact.scopeKey, fact.kind)
        val latest = old == null || fact.observedAt > old.latestObservedAt
        val newerGood = good && (old?.lastGoodObservedAt == null || fact.observedAt > old.lastGoodObservedAt)
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(
                fact.scopeKey, fact.kind,
                if (latest) fact.evidenceId else old!!.latestEvidenceId,
                if (latest) fact.observedAt else old!!.latestObservedAt,
                if (newerGood) fact.snapshotId else old?.lastGoodSnapshotId,
                if (newerGood) fact.observedAt else old?.lastGoodObservedAt,
            ),
        )
    }

    /**
     * Slice-branch writer (identity memo §3.3 production shape, Hunk B1): same
     * evidence -> fact (quotaSlice carried) insert, then the PRODUCTION DAO
     * ports sliceHead(scopeKey, kind, quotaSlice) read + IDENTICAL
     * strict-advance latest/newerGood logic + upsertSliceHead(REPLACE) per
     * (scopeKey, kind, quotaSlice). No cross-namespace reads or writes.
     * Same-slice dependency: ports land with Hunk A2.
     */
    private suspend fun recordSlicedMirror(
        scope: PricingScope,
        evidence: DedicatedEvidence,
        fact: SnapshotFactEntity,
        good: Boolean,
    ) {
        require(evidence.observedAt >= 0) { "Invalid observation time" }
        require(fact.quotaSlice != null && fact.quotaSlice!!.isNotEmpty()) {
            "Slice branch requires a legal slice name"
        }
        require(fact.kind in QUOTA_KINDS) {
            "Slice dimension is legal only on the four quota kinds"
        }
        db.evidenceDao().insert(
            EvidenceEntity(
                fact.evidenceId, fact.evidenceId, scope.providerId, scope.modelId,
                fact.status, evidence.httpStatus, evidence.observedAt, evidence.source,
            ),
        )
        db.snapshotFactDao().insert(fact)
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

    // ---- independent raw-SQL readers (verification leg; writers use DAO) ----

    private data class HeadRow(
        val scopeKey: String?,
        val latestEvidenceId: String?,
        val latestObservedAt: Long?,
        val lastGoodSnapshotId: String?,
        val lastGoodObservedAt: Long?,
        val quotaSlice: String? = null,
    )

    private fun rawSliceHeadRow(
        db: SupportSQLiteDatabase,
        scopeKey: String,
        kind: String,
        slice: String,
    ): HeadRow? =
        db.query(
            "SELECT latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt " +
                "FROM `$SLICE_HEAD_TABLE` WHERE scopeKey = ? AND kind = ? AND quotaSlice = ?",
            arrayOf(scopeKey, kind, slice),
        ).use { c ->
            if (!c.moveToFirst()) return null
            HeadRow(
                null,
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getLong(1),
                if (c.isNull(2)) null else c.getString(2),
                if (c.isNull(3)) null else c.getLong(3),
            )
        }

    private fun readSliceHeadRow(scopeKey: String, kind: String, slice: String): HeadRow? =
        db.openHelper.writableDatabase.query(
            "SELECT latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt " +
                "FROM `$SLICE_HEAD_TABLE` WHERE scopeKey = ? AND kind = ? AND quotaSlice = ?",
            arrayOf(scopeKey, kind, slice),
        ).use { c ->
            if (!c.moveToFirst()) return null
            HeadRow(
                null,
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getLong(1),
                if (c.isNull(2)) null else c.getString(2),
                if (c.isNull(3)) null else c.getLong(3),
            )
        }

    private fun rawHeadRow(db: SupportSQLiteDatabase, scopeKey: String, kind: String): HeadRow? =
        db.query(
            "SELECT latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt " +
                "FROM snapshot_heads WHERE scopeKey = ? AND kind = ?",
            arrayOf(scopeKey, kind),
        ).use { c ->
            if (!c.moveToFirst()) return null
            HeadRow(
                null,
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getLong(1),
                if (c.isNull(2)) null else c.getString(2),
                if (c.isNull(3)) null else c.getLong(3),
            )
        }

    private fun sliceHeadCount(scopeKey: String, kind: String): Int =
        db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM `$SLICE_HEAD_TABLE` WHERE scopeKey = ? AND kind = ?",
            arrayOf(scopeKey, kind),
        ).use { it.moveToFirst(); it.getInt(0) }

    private fun allSliceHeadRows(): List<HeadRow> = buildList {
        db.openHelper.writableDatabase.query(
            "SELECT scopeKey, latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt, quotaSlice " +
                "FROM `$SLICE_HEAD_TABLE` ORDER BY scopeKey, kind, quotaSlice",
        ).use { c ->
            while (c.moveToNext()) add(
                HeadRow(
                    if (c.isNull(0)) null else c.getString(0),
                    if (c.isNull(1)) null else c.getString(1),
                    if (c.isNull(2)) null else c.getLong(2),
                    if (c.isNull(3)) null else c.getString(3),
                    if (c.isNull(4)) null else c.getLong(4),
                    if (c.isNull(5)) null else c.getString(5),
                ),
            )
        }
    }

    private fun sliceHeadsByKindRaw(kind: String): List<HeadRow> = buildList {
        db.openHelper.writableDatabase.query(
            "SELECT scopeKey, latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt, quotaSlice " +
                "FROM `$SLICE_HEAD_TABLE` WHERE kind = ?",
            arrayOf(kind),
        ).use { c ->
            while (c.moveToNext()) add(
                HeadRow(
                    if (c.isNull(0)) null else c.getString(0),
                    if (c.isNull(1)) null else c.getString(1),
                    if (c.isNull(2)) null else c.getLong(2),
                    if (c.isNull(3)) null else c.getString(3),
                    if (c.isNull(4)) null else c.getLong(4),
                    if (c.isNull(5)) null else c.getString(5),
                ),
            )
        }
    }

    // ---- assertions / helpers -----------------------------------------------

    private suspend fun assertSliceHead(
        scopeKey: String,
        kind: String,
        slice: String,
        latestEvidenceId: String,
        latestObservedAt: Long,
        lastGoodSnapshotId: String?,
        lastGoodObservedAt: Long?,
    ) {
        val row = readSliceHeadRow(scopeKey, kind, slice)
        assertTrue("expected slice head $kind/$slice", row != null)
        assertEquals(latestEvidenceId, row!!.latestEvidenceId)
        assertEquals(latestObservedAt, row.latestObservedAt)
        assertEquals(lastGoodSnapshotId, row.lastGoodSnapshotId)
        assertEquals(lastGoodObservedAt, row.lastGoodObservedAt)
        // Production-port cross-check (same-slice Hunk A2): the DAO exact
        // reader sees the identical row.
        val viaDao = db.snapshotFactDao().sliceHead(scopeKey, kind, slice)
        assertTrue("DAO sliceHead sees $kind/$slice", viaDao != null)
        assertEquals(latestEvidenceId, viaDao!!.latestEvidenceId)
        assertEquals(latestObservedAt, viaDao.latestObservedAt)
        assertEquals(lastGoodSnapshotId, viaDao.lastGoodSnapshotId)
        assertEquals(lastGoodObservedAt, viaDao.lastGoodObservedAt)
    }

    /** Every scopeKey in the table must parse to exactly 7 dims (pos == key.length). */
    private fun assertScopeKeyParses(db: SupportSQLiteDatabase, table: String) {
        db.query("SELECT scopeKey FROM `$table`").use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                assertTrue(
                    "scopeKey does not parse to 7 dims in $table: $key",
                    parseScopeKey(key)?.size == 7,
                )
            }
        }
    }

    private fun factRows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = buildList {
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                if (cursor.isNull(it)) null else cursor.getString(it)
            })
        }
    }

    private fun headColumnNames(db: SupportSQLiteDatabase): List<String> = buildList {
        db.query("PRAGMA table_info(`snapshot_heads`)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun headPkPositions(db: SupportSQLiteDatabase): List<Int> = buildList {
        db.query("PRAGMA table_info(`snapshot_heads`)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getInt(cursor.getColumnIndexOrThrow("pk")))
        }
    }

    private fun hasForeignKey(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("PRAGMA foreign_key_list(`$table`)").use { it.moveToFirst() }

    private suspend fun expectAbort(block: suspend () -> Unit) {
        try {
            block()
            fail("expected SQLiteConstraintException (ABORT/RESTRICT)")
        } catch (_: SQLiteConstraintException) {
        }
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException (duplicate slice batch entry)")
        } catch (_: IllegalArgumentException) {
        }
    }

    private companion object {
        // Migration version 15 LOCKED by Main/Principal for the CR-011 slice.
        const val NEXT_VERSION = 15
        const val SLICE_HEAD_TABLE = "snapshot_slice_heads"
        const val DEDICATED_ACCOUNT_QUOTA = "DEDICATED_ACCOUNT_QUOTA"

        val QUOTA_KINDS = setOf(
            "ACCOUNT_QUOTA", "TOKEN_QUOTA", "DEDICATED_ACCOUNT_QUOTA", "DEDICATED_TOKEN_QUOTA",
        )

        // Legacy snapshot_heads byte-identity (SnapshotFacts.kt:81-89, CI-exported
        // 14.json PK ["scopeKey","kind"] verified): six columns, PK positions
        // scopeKey=1 / kind=2, NO slice column, no new identity dimension.
        val LEGACY_HEAD_COLUMNS = listOf(
            "scopeKey", "kind", "latestEvidenceId", "latestObservedAt",
            "lastGoodSnapshotId", "lastGoodObservedAt",
        )
        val LEGACY_HEAD_PK_POSITIONS = listOf(1, 2, 0, 0, 0, 0)

        // One exact 7-dim scope for the trio/isolation/reader/abort cases (each
        // @Test uses a fresh in-memory DB, so sharing the value is not a
        // collision): account-level quota scope (credentialId null =>
        // DEDICATED_ACCOUNT_QUOTA), group null => "-1:" inside storageKey().
        val TRIO_SCOPE = PricingScope("p15", "a15", null, "fixture-model", null, "e15", "CHAT_COMPLETIONS")

        // Per-project scope for the caller-level case: pool.projectId must equal
        // scope.group (validatePoolMatchesScope, DedicatedSnapshotSync.kt:813).
        val PROJ_SCOPE = PricingScope("p15", "a15", null, "fixture-model", "proj-15", "e15", "CHAT_COMPLETIONS")

        // Raw insert matching the identity memo §3.1 DDL, used only for the
        // fail-closed duplicate-PK test. Seven %s in order: scopeKey, kind,
        // quotaSlice, latestEvidenceId, latestObservedAt, lastGoodSnapshotId,
        // lastGoodObservedAt (lastGood columns are pre-rendered literals).
        const val SLICE_HEAD_INSERT =
            "INSERT INTO `$SLICE_HEAD_TABLE` " +
                "(scopeKey,kind,quotaSlice,latestEvidenceId,latestObservedAt,lastGoodSnapshotId,lastGoodObservedAt) " +
                "VALUES ('%s','%s','%s','%s',%s,%s,%s)"
    }
}

// =============================================================================
// PRODUCTION SHAPE THIS TEST COVERS (identity memo §3.1/§3.3; landed
// same-slice per spec §4 steps 1-5; reference only — NOTHING below is active
// code; version 15 LOCKED by Main; 15.json is the real CI/KSP export only).
//
// DDL (v15, additive-only; produced by Room from a real entity, never hand-written):
//
//   CREATE TABLE IF NOT EXISTS `snapshot_slice_heads` (
//     `scopeKey` TEXT NOT NULL,
//     `kind` TEXT NOT NULL,
//     `quotaSlice` TEXT NOT NULL,
//     `latestEvidenceId` TEXT NOT NULL,
//     `latestObservedAt` INTEGER NOT NULL,
//     `lastGoodSnapshotId` TEXT,
//     `lastGoodObservedAt` INTEGER,
//     PRIMARY KEY(`scopeKey`, `kind`, `quotaSlice`)
//   )
//   CREATE INDEX IF NOT EXISTS `index_snapshot_slice_heads_kind`
//     ON `snapshot_slice_heads` (`kind`)
//
// DAO ports expected (co-located in SnapshotFacts.kt per single-owner convention):
//   sliceHead(scopeKey, kind, quotaSlice): SnapshotSliceHeadEntity?
//   upsertSliceHead(head): Unit            // @Insert(REPLACE)
//   sliceHeadsByKind(kind): List<SnapshotSliceHeadEntity>
//
// Writer branch (both record() implementations, DedicatedSnapshotSync.kt:654-675 /
// NewApiSnapshotStore.kt:414-427) branches on fact.quotaSlice.isNullOrEmpty():
//   true  -> existing head()/upsertHead() path, BYTE-IDENTICAL behavior
//            (NewApi paths always hit this; zero behavior change).
//   false -> sliceHead()/upsertSliceHead(...) with identical strict-advance
//            latest/newerGood logic per (scopeKey, kind, quotaSlice); no
//            cross-namespace reads or writes in either direction.
//   require quota-kind when slice non-null-and-non-empty (memo §3.2 writer-side
//   guard); readbacks: quota(scope, kind, slice: QuotaPoolSlice?) additive
//   overload — null -> existing path unchanged; Success legs read back their own
//   slice; Failure/Unsupported legs fail-closed to the unsliced namespace.
//
// Migration 14->15 backfill (identity memo §4.2, adjudicated ambiguity ->
// absence; full fixture coverage is the SIBLING draft
// /tmp/opencode/ai9-cr011-upgrade-fixture-draft.kt -> AtlasMigration14To15Test.kt):
//   Per group (scopeKey, kind, quotaSlice) over snapshot_facts
//   WHERE quotaSlice IS NOT NULL AND quotaSlice != ''
//     AND quotaSlice IN ('STANDARD_INTERACTIVE','PRIORITY','BATCH')
//     AND kind IN ('ACCOUNT_QUOTA','TOKEN_QUOTA','DEDICATED_ACCOUNT_QUOTA','DEDICATED_TOKEN_QUOTA'):
//     latest_rows := facts at MAX(observedAt)             -- require exactly 1
//     good        := snapshotId IS NOT NULL AND state != 'UNKNOWN'
//     lastGood_row:= facts at MAX(observedAt) among good  -- require <= 1
//     IF |latest_rows| == 1 AND |lastGood_row| <= 1: INSERT slice head
//     ELSE: no row -- Unknown preserved, no tie winner picked
//   Old snapshot_heads rows: byte-identical, NEVER recomputed (including mixed
//   collision artifacts); convergence via new unsliced writes only.
//
// Invariants preserved (hard requirements, identity memo §3.4 / oracle §5):
//   storageKey()/parseScopeKey/providerScopeRange untouched; snapshot_facts
//   append-only (no UPDATE/DELETE in the DAO); snapshot_heads PK/rows/queries
//   untouched; NewApi official/manual quota/price semantics untouched (never
//   sliced); no history deletion; duplicate-slice ABORT preserved; facts->evidence
//   FK RESTRICT intact; Gemini route stays DedicatedQuotaKind.UNSUPPORTED
//   (AtlasRepository.kt:560 verified at HEAD aabbce2).
//
// LANDED STATUS: version 15 LOCKED by Main; SnapshotSliceHeadEntity +
// sliceHead/upsertSliceHead/sliceHeadsByKind called as production forms
// (same-slice Hunks A1+A2); case 6 drives real syncQuotaPools; 15.json is the
// real CI/KSP export only (never hand-written); device execution, independent
// review, same-SHA full-green CI and TRACEABILITY update remain per spec §4
// steps 8-10. No queued next task.
// =============================================================================
