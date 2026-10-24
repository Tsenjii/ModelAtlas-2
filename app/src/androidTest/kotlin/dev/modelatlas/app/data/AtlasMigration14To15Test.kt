// =============================================================================
// CR-011 — LANDED TEST (same-slice). Anchored at HEAD aabbce2 (code identical to
// green baseline 3d46ece; docs-only delta since). Migration version 15 LOCKED by
// Main/Principal for the CR-011 slice (ai11-cr011-spec.md §4 dispatch step 7).
// File: app/src/androidTest/kotlin/dev/modelatlas/app/data/AtlasMigration14To15Test.kt
// 8 @Test upgrade fixtures, transcribed from
// /tmp/opencode/ai9-cr011-upgrade-fixture-draft.kt (766 lines, 8 @Test) with
// every assertion kept verbatim except the production-form cross-checks added
// in fixture 1 (DAO exact/enumeration readers over the backfilled rows).
//
// SAME-SLICE PRODUCTION DEPENDENCY (prod owner landing concurrently, spec §4
// steps 1-5): MIGRATION_14_15 + version 15 (Hunk A3) is exercised through the
// production factory AtlasDatabase.create (no hand-written schema, no fake
// JSON — legacy DBs come from committed 14.json via MigrationTestHelper,
// 15.json is the real CI/KSP export only). sliceHead/sliceHeadsByKind DAO
// ports (Hunk A2) are called as production forms in fixture 1; all other
// assertions read via independent raw SQL (not a second implementation).
// No live probes, no secrets, no mock-as-product-data. CI-only verification.
// =============================================================================

package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v14 -> v15 true upgrade fixture for CR-011 slice-head identity
 * (landed same-slice at HEAD aabbce2; version 15 LOCKED by Main).
 *
 * This test is modeled 1:1 on the verified `AtlasMigration13To14Test` pattern:
 *   - legacy DB from committed CI-exported `14.json` via MigrationTestHelper
 *   - reopen through `AtlasDatabase.create` (registers MIGRATION_14_15, dev.modelatlas.app.data.MIGRATION_15_16)
 *   - tables/schema/rows/FK captured before and after
 *   - fresh-generated DDL compared per object
 *   - PRAGMA foreign_key_check clean
 *   - fail-closed duplicate ABORT + FK RESTRICT after upgrade
 * It adds the slice-head namespace fixtures (trio, tie->absence, mixed old head,
 * unsliced-only, coexistence, defensive exclusion, no-deletion, parseScopeKey parity).
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration14To15Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    // -------------------------------------------------------------------------
    // Fixture 1 (audit core): real STANDARD/PRIORITY/BATCH trio, distinct
    // observedAt, one exact 7-dim scope + DEDICATED_ACCOUNT_QUOTA.
    // Asserts 3 independent slice heads (latest + lastGood each), zero cross-slice
    // interference, and that the unsliced snapshot_heads row is absent when no
    // unsliced fact was written.
    // -------------------------------------------------------------------------
    @Test
    fun migrate14ToNext_realSliceTrio_createsThreeIndependentSliceHeads_andNeverTouchesUnslicedHead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-slice-trio.db"
        context.deleteDatabase(name)

        val legacyTables: Set<String>
        val legacyRows: Map<String, List<List<String?>>>
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedMinimalV14(old)
            // Slice facts live in snapshot_facts.quotaSlice, NOT in scopeKey/kind.
            // scopeKey is built EXACTLY like PricingScope.storageKey() (7 dims).
            val scope = TRIO_SCOPE_KEY
            // distinct observedAt => every group's latest is unique; all Success
            // (snapshotId != null, state != UNKNOWN) => unambiguous lastGood.
            old.execSQL(
                "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                    "VALUES ('ev-trio-std','ev-trio-std','p14','fixture-model','SUCCESS',200,2100,'PROBE',1)",
            )
            old.execSQL(
                "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                    "quotaScopeKind,quotaSlice) VALUES " +
                    "('ev-trio-std','$scope','DEDICATED_ACCOUNT_QUOTA',2100,'snap-std','QUOTA_AVAILABLE','OK','OFFICIAL','PER_PROJECT_MODEL','STANDARD_INTERACTIVE')",
            )
            old.execSQL(
                "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                    "VALUES ('ev-trio-pri','ev-trio-pri','p14','fixture-model','SUCCESS',200,2200,'PROBE',1)",
            )
            old.execSQL(
                "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                    "quotaScopeKind,quotaSlice) VALUES " +
                    "('ev-trio-pri','$scope','DEDICATED_ACCOUNT_QUOTA',2200,'snap-pri','QUOTA_LOW','OK','OFFICIAL','PER_PROJECT_MODEL','PRIORITY')",
            )
            old.execSQL(
                "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                    "VALUES ('ev-trio-bat','ev-trio-bat','p14','fixture-model','FAILURE',503,2300,'PROBE',1)",
            )
            // BATCH latest is a Failure (snapshotId null, state UNKNOWN) => latest head
            // exists, lastGood is absent (NULL) for that slice; honest, not invented.
            old.execSQL(
                "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                    "quotaScopeKind,quotaSlice) VALUES " +
                    "('ev-trio-bat','$scope','DEDICATED_ACCOUNT_QUOTA',2300,NULL,'UNKNOWN','HTTP_503','OFFICIAL','PER_PROJECT_MODEL','BATCH')",
            )
            legacyTables = tables(old)
            legacyRows = legacyTables.associateWith { rows(old, it) }
        }

        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(16, sql.version)

            // (a) Slice heads derived from facts where unambiguous.
            assertSliceHead(sql, TRIO_SCOPE_KEY, "DEDICATED_ACCOUNT_QUOTA", "STANDARD_INTERACTIVE", "ev-trio-std", 2100, "snap-std", 2100)
            assertSliceHead(sql, TRIO_SCOPE_KEY, "DEDICATED_ACCOUNT_QUOTA", "PRIORITY", "ev-trio-pri", 2200, "snap-pri", 2200)
            // BATCH: latest present, lastGood NULL (only failure observed).
            assertSliceHead(sql, TRIO_SCOPE_KEY, "DEDICATED_ACCOUNT_QUOTA", "BATCH", "ev-trio-bat", 2300, null, null)

            // (b) Cross-slice isolation: exactly 3 slice-head rows for this scope+kind.
            assertEquals(3, sliceHeadCount(sql, TRIO_SCOPE_KEY, "DEDICATED_ACCOUNT_QUOTA"))

            // (c) Unsliced namespace untouched & empty for this scope+kind: no
            //     snapshot_heads row was ever written because no unsliced fact exists.
            assertNull(rawHead(sql, TRIO_SCOPE_KEY, "DEDICATED_ACCOUNT_QUOTA"))

            // (d) No history deletion: facts/evidence row-for-row identical.
            assertEquals(legacyRows["snapshot_facts"], rows(sql, "snapshot_facts"))
            assertEquals(legacyRows["evidence"], rows(sql, "evidence"))

            // (e) parseScopeKey parity: scopeKey was never mutated; still parses 7 dims.
            assertScopeKeyParses(sql, "snapshot_facts")
            assertScopeKeyParses(sql, "snapshot_heads")
            assertScopeKeyParses(sql, "snapshot_slice_heads")

            // (f) Production-port cross-check (same-slice Hunk A2): the DAO exact
            //     reader and enumeration see the identical backfilled rows.
            assertEquals(
                "ev-trio-std",
                opened.snapshotFactDao().sliceHead(
                    TRIO_SCOPE_KEY, "DEDICATED_ACCOUNT_QUOTA", "STANDARD_INTERACTIVE",
                )!!.latestEvidenceId,
            )
            assertEquals(
                setOf("STANDARD_INTERACTIVE", "PRIORITY", "BATCH"),
                opened.snapshotFactDao().sliceHeadsByKind("DEDICATED_ACCOUNT_QUOTA")
                    .map { it.quotaSlice }.toSet(),
            )

            // (g) Fresh-generated DDL == upgraded DDL per object.
            assertFreshMatchesUpgraded(context, sql)

            // (g) FK check clean.
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // -------------------------------------------------------------------------
    // Fixture 2 (adjudication): same observedAt tie => NO slice head row.
    // No evidenceId ASC winner is picked. Stable across repeated migration runs.
    // -------------------------------------------------------------------------
    @Test
    fun migrate14ToNext_tiedSliceGroup_yieldsNoSliceHead_notAPinnedWinner() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-slice-tie.db"
        context.deleteDatabase(name)
        val scope = TIE_SCOPE_KEY
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedMinimalV14(old)
            // Two facts, same group, same observedAt, DIFFERENT state/evidenceId.
            old.execSQL(
                "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                    "VALUES ('ev-tie-a','ev-tie-a','p14','fixture-model','SUCCESS',200,2400,'PROBE',1)",
            )
            old.execSQL(
                "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                    "VALUES ('ev-tie-b','ev-tie-b','p14','fixture-model','FAILURE',500,2400,'PROBE',1)",
            )
            for ((id, snapshotId, state, status) in listOf<List<String?>>(
                listOf("ev-tie-a", "snap-tie-a", "QUOTA_AVAILABLE", "OK"),
                listOf("ev-tie-b", null, "UNKNOWN", "HTTP_500"),
            )) {
                old.execSQL(
                    "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                        "quotaScopeKind,quotaSlice) VALUES " +
                        "('$id','$scope','DEDICATED_ACCOUNT_QUOTA',2400,${sqlNullable(snapshotId)},'$state','$status','OFFICIAL','PER_PROJECT_MODEL','PRIORITY')",
                )
            }
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            // Tie => absence (Unknown preserved), NOT a lexicographically-first winner.
            assertEquals(0, sliceHeadCount(sql, scope, "DEDICATED_ACCOUNT_QUOTA"))
            // The facts themselves are fully retained (nothing lost, only unprojected).
            assertEquals(2, factCount(sql, scope, "DEDICATED_ACCOUNT_QUOTA"))
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // -------------------------------------------------------------------------
    // Fixture 3 (oracle): mixed old head artifact. v14 snapshot_heads row whose
    // latestEvidenceId -> PRIORITY fact and lastGoodSnapshotId -> STANDARD fact.
    // After 14->NEXT: snapshot_heads row BYTE-IDENTICAL (never recomputed);
    // slice heads independently derived from facts.
    // -------------------------------------------------------------------------
    @Test
    fun migrate14ToNext_mixedOldHead_staysByteIdentical_sliceHeadsDerivedFromFacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-mixed-head.db"
        context.deleteDatabase(name)
        val scope = MIXED_SCOPE_KEY
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedMinimalV14(old)
            for ((id, at, snapshotId, state, slice) in listOf<List<Any?>>(
                // STANDARD older, good.
                listOf("ev-mix-std", 2500L, "snap-mix-std", "QUOTA_AVAILABLE", "STANDARD_INTERACTIVE"),
                // PRIORITY newer, good.
                listOf("ev-mix-pri", 2600L, "snap-mix-pri", "QUOTA_LOW", "PRIORITY"),
            )) {
                old.execSQL(
                    "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                        "VALUES ('$id','$id','p14','fixture-model','SUCCESS',200,$at,'PROBE',1)",
                )
                old.execSQL(
                    "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                        "quotaScopeKind,quotaSlice) VALUES " +
                        "('$id','$scope','DEDICATED_ACCOUNT_QUOTA',$at,'$snapshotId','$state','OK','OFFICIAL','PER_PROJECT_MODEL','$slice')",
                )
            }
            // The collision artifact: ONE unsliced head row pointing at two different
            // slices (latest = PRIORITY, lastGood = STANDARD). This is exactly the
            // direct artifact of today's (scopeKey,kind)-only upsert.
            old.execSQL(
                "INSERT INTO snapshot_heads (scopeKey,kind,latestEvidenceId,latestObservedAt,lastGoodSnapshotId,lastGoodObservedAt) " +
                    "VALUES ('$scope','DEDICATED_ACCOUNT_QUOTA','ev-mix-pri',2600,'snap-mix-std',2500)",
            )
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            // Old unsliced head row is carried verbatim (no recompute, no invention).
            val head = rawHead(sql, scope, "DEDICATED_ACCOUNT_QUOTA")!!
            assertEquals("ev-mix-pri", head.latestEvidenceId)
            assertEquals(2600L, head.latestObservedAt)
            assertEquals("snap-mix-std", head.lastGoodSnapshotId)
            assertEquals(2500L, head.lastGoodObservedAt)
            // Slice heads independently derived from facts (both unambiguous).
            assertSliceHead(sql, scope, "DEDICATED_ACCOUNT_QUOTA", "STANDARD_INTERACTIVE", "ev-mix-std", 2500, "snap-mix-std", 2500)
            assertSliceHead(sql, scope, "DEDICATED_ACCOUNT_QUOTA", "PRIORITY", "ev-mix-pri", 2600, "snap-mix-pri", 2600)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // -------------------------------------------------------------------------
    // Fixture 4: unsliced-only scope => zero slice-head rows, snapshot_heads untouched.
    // -------------------------------------------------------------------------
    @Test
    fun migrate14ToNext_unslicedOnlyScope_createsNoSliceHead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-unsliced-only.db"
        context.deleteDatabase(name)
        val scope = UNSLICED_SCOPE_KEY
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedMinimalV14(old)
            old.execSQL(
                "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                    "VALUES ('ev-uns','ev-uns','p14','fixture-model','SUCCESS',200,2700,'PROBE',1)",
            )
            // quotaSlice NULL => unsliced namespace, exactly like pre-v6 legacy rows and
            // the PLATFORM_GLOBAL key-quota path (DedicatedSnapshotSync.kt:627-629).
            old.execSQL(
                "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                    "quotaScopeKind,quotaSlice) VALUES " +
                    "('ev-uns','$scope','DEDICATED_TOKEN_QUOTA',2700,'snap-uns','QUOTA_AVAILABLE','OK','OFFICIAL','PLATFORM_GLOBAL',NULL)",
            )
            old.execSQL(
                "INSERT INTO snapshot_heads (scopeKey,kind,latestEvidenceId,latestObservedAt,lastGoodSnapshotId,lastGoodObservedAt) " +
                    "VALUES ('$scope','DEDICATED_TOKEN_QUOTA','ev-uns',2700,'snap-uns',2700)",
            )
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(0, sliceHeadCount(sql, scope, "DEDICATED_TOKEN_QUOTA"))
            val head = rawHead(sql, scope, "DEDICATED_TOKEN_QUOTA")!!
            assertEquals("ev-uns", head.latestEvidenceId)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // -------------------------------------------------------------------------
    // Fixture 5: coexistence — same scope+kind, unsliced AND sliced facts.
    // Both namespaces readable independently; neither overwrites the other.
    // -------------------------------------------------------------------------
    @Test
    fun migrate14ToNext_unslicedAndSlicedCoexist_neitherOverwrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-coexist.db"
        context.deleteDatabase(name)
        val scope = COEXIST_SCOPE_KEY
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedMinimalV14(old)
            for ((id, at, snapshotId, state, slice) in listOf<List<Any?>>(
                listOf("ev-co-null", 2800L, "snap-co-null", "QUOTA_AVAILABLE", null),
                listOf("ev-co-std", 2900L, "snap-co-std", "QUOTA_LOW", "STANDARD_INTERACTIVE"),
            )) {
                old.execSQL(
                    "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                        "VALUES ('$id','$id','p14','fixture-model','SUCCESS',200,$at,'PROBE',1)",
                )
                old.execSQL(
                    "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                        "quotaScopeKind,quotaSlice) VALUES " +
                        "('$id','$scope','DEDICATED_ACCOUNT_QUOTA',$at,'$snapshotId','$state','OK','OFFICIAL','PER_PROJECT_MODEL',${sqlNullable(slice)})",
                )
            }
            old.execSQL(
                "INSERT INTO snapshot_heads (scopeKey,kind,latestEvidenceId,latestObservedAt,lastGoodSnapshotId,lastGoodObservedAt) " +
                    "VALUES ('$scope','DEDICATED_ACCOUNT_QUOTA','ev-co-null',2800,'snap-co-null',2800)",
            )
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            // Unsliced head unchanged and still points only at the unsliced fact; the
            // later sliced observation must NOT have advanced it.
            val head = rawHead(sql, scope, "DEDICATED_ACCOUNT_QUOTA")!!
            assertEquals("ev-co-null", head.latestEvidenceId)
            assertEquals(2800L, head.latestObservedAt)
            // Slice head exists separately, did not overwrite / was not overwritten.
            assertSliceHead(sql, scope, "DEDICATED_ACCOUNT_QUOTA", "STANDARD_INTERACTIVE", "ev-co-std", 2900, "snap-co-std", 2900)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // -------------------------------------------------------------------------
    // Fixture 6: defensive exclusion — a fact with a slice but a NON-quota kind,
    // and a fact with quotaSlice = '' (impossible in production) => no slice head;
    // fact rows preserved untouched.
    // -------------------------------------------------------------------------
    @Test
    fun migrate14ToNext_sliceOnNonQuotaKindOrEmpty_producesNoSliceHead_factsPreserved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-defensive.db"
        context.deleteDatabase(name)
        val scope = DEFENSIVE_SCOPE_KEY
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedMinimalV14(old)
            for ((id, kind, slice) in listOf(
                listOf("ev-def-price", "DEDICATED_PRICE", "PRIORITY"),
                listOf("ev-def-empty", "DEDICATED_ACCOUNT_QUOTA", ""),
            )) {
                old.execSQL(
                    "INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) " +
                        "VALUES ('$id','$id','p14','fixture-model','SUCCESS',200,3000,'PROBE',1)",
                )
                old.execSQL(
                    "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence," +
                        "quotaScopeKind,quotaSlice) VALUES " +
                        "('$id','$scope','$kind',3000,'snap-$id','QUOTA_AVAILABLE','OK','OFFICIAL','PER_PROJECT_MODEL','$slice')",
                )
            }
            val beforeFacts = rows(old, "snapshot_facts")
            assertTrue(beforeFacts.isNotEmpty())
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            // No phantom slice group for either impossible value.
            assertEquals(0, sliceHeadCount(sql, scope, "DEDICATED_PRICE"))
            assertEquals(0, sliceHeadCount(sql, scope, "DEDICATED_ACCOUNT_QUOTA"))
            // Both fact rows preserved.
            assertEquals(2, factCount(sql, scope, null))
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // -------------------------------------------------------------------------
    // Fixture 7 (oracle §5 fail-closed + FK): after upgrade, the new slice-head
    // PK ABORTs a duplicate, and existing FK RESTRICT still holds.
    // -------------------------------------------------------------------------
    @Test
    fun afterUpgrade_duplicateSliceHeadAborts_andRestrictStillHolds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-failclosed.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedMinimalV14(old)
            // FK RESTRICT leg needs a referencing facts row (PK 14 v14 schema:
            // snapshot_facts.evidenceId -> evidence.id ON DELETE RESTRICT).
            old.execSQL(
                "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence) " +
                    "VALUES ('legacy-evidence-14','fk-facts-scope','DEDICATED_ACCOUNT_QUOTA',1000,NULL,'UNKNOWN','OK','OFFICIAL')",
            )
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            // Direct SQL exercises the new PK independent of the DAO ports (raw check
            // retained alongside the DAO-port coverage in fixture 1).
            sql.execSQL(SLICE_HEAD_INSERT.format(
                "fk-scope", "DEDICATED_ACCOUNT_QUOTA", "PRIORITY", "ev-fk", 1L, "NULL", "NULL",
            ))
            expectAbort {
                sql.execSQL(SLICE_HEAD_INSERT.format(
                    "fk-scope", "DEDICATED_ACCOUNT_QUOTA", "PRIORITY", "ev-fk2", 2L, "NULL", "NULL",
                ))
            }
            // FK RESTRICT still enforced for facts -> evidence.
            expectAbort { sql.execSQL("DELETE FROM evidence WHERE id = 'legacy-evidence-14'") }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // -------------------------------------------------------------------------
    // Fixture 8: old-rows-verbatim + no-deletion across ALL tables (the
    // AtlasMigration13To14Test whole-table convention, kept for the new slice).
    // -------------------------------------------------------------------------
    @Test
    fun migrate14ToNext_allOldRowsColumnForColumnIdentical_exceptDocumentedAdditiveTable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-14-next-rows.db"
        context.deleteDatabase(name)
        val legacyTables: Set<String>
        val legacyRows: Map<String, List<List<String?>>>
        helper.createDatabase(name, LEGACY_VERSION).use { old ->
            seedFullV14(old)
            legacyTables = tables(old)
            legacyRows = legacyTables.associateWith { rows(old, it) }
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(LEGACY_VERSION + 1, NEXT_VERSION) // placeholder sanity
            // Documented additive table only.
            assertEquals(legacyTables + SLICE_HEAD_TABLE + workspaceSyncTables, tables(sql))
            for ((table, before) in legacyRows) {
                assertEquals(table, before, rows(sql, table))
            }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // ---- seeds (all test-only fixture; no secret-like names) ----------------

    /**
     * Minimal parent rows for the slice fixtures: one provider, account, credential,
     * endpoint, offering, a canonical model alias, and the fact FK parent evidence
     * for NEW slice facts. Existing unsliced heads are seeded per test as needed.
     */
    private fun seedMinimalV14(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO providers (providerId,name,officialHealth,observedHealth,checkinUrl,requiresDailyCheckin) VALUES ('p14','Fixture','UNKNOWN','HEALTHY','https://fixture.invalid/checkin',1)")
        db.execSQL("INSERT INTO accounts (accountId,providerId) VALUES ('a14','p14')")
        db.execSQL("INSERT INTO credentials (credentialId,accountId,label,enabled,note,\"group\",priority,authMode,isSecret) VALUES ('c14','a14','safe reference',1,'metadata note','legacy-group',1,'ACCOUNT',1)")
        db.execSQL("INSERT INTO endpoints (endpointId,providerId,baseUrl,endpointUrl,protocol) VALUES ('e14','p14','https://fixture.invalid/v1','https://fixture.invalid/v1/chat/completions','CHAT_COMPLETIONS')")
        db.execSQL("INSERT INTO offerings (id,providerId,endpointId,modelId,manual,source) VALUES ('off14-a','p14','e14','fixture-model',1,'MANUAL')")
        db.execSQL("INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) VALUES ('legacy-evidence-14','legacy-sample-14','p14','fixture-model','SUCCESS',200,1200,'PLAYGROUND',1)")
    }

    /** Full 37-table seed (mirrors AtlasMigration13To14Test.seedFullLegacyV13 on v14). */
    private fun seedFullV14(db: SupportSQLiteDatabase) {
        seedMinimalV14(db)
        db.execSQL("INSERT INTO onboarding_snapshots (workflowId,updatedAt,snapshotJson) VALUES ('workflow-14',1500,'{}')")
        db.execSQL("INSERT INTO onboarding_commits (workflowId,providerId,committedAt) VALUES ('workflow-14','p14',1600)")
        db.execSQL("INSERT INTO provider_configurations (providerId,workflowId,authMode,snapshotJson) VALUES ('p14','workflow-14','BEARER','{}')")
        db.execSQL("INSERT INTO onboarding_batches (batchId,updatedAt,snapshotJson) VALUES ('batch-14',1700,'{}')")
        db.execSQL("INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,state,status,confidence) VALUES ('legacy-evidence-14','scope-14','PRICE',2000,'UNKNOWN','OK','OFFICIAL')")
        db.execSQL("INSERT INTO snapshot_heads (scopeKey,kind,latestEvidenceId,latestObservedAt) VALUES ('scope-14','PRICE','legacy-evidence-14',2000)")
        db.execSQL("INSERT INTO canonical_models (canonicalModelId, normalizedName, displayName, createdAt, updatedAt, version) VALUES ('cm14', 'fixture-model', 'Fixture', 1, 1, 1)")
        db.execSQL("INSERT INTO provider_model_aliases (aliasId, providerId, providerModelId, canonicalModelId, source, confidence, verified, observedAt, version, evidenceId) VALUES ('alias:1:p14:fixture-model', 'p14', 'fixture-model', 'cm14', 'METADATA', 'MEDIUM', 0, 2, 1, NULL)")
        db.execSQL("INSERT INTO model_classifications (id, canonicalModelId, source, confidence, primaryCategory, observedAt, version) VALUES ('classification:cm14:MANUAL', 'cm14', 'MANUAL', 'LOW', 'CODING', 3, 1)")
        db.execSQL("INSERT INTO classification_capabilities (classificationId, capability) VALUES ('classification:cm14:MANUAL', 'CODING')")
        db.execSQL("INSERT INTO classification_modalities (classificationId, modality) VALUES ('classification:cm14:MANUAL', 'TEXT_INPUT')")
        db.execSQL("INSERT INTO classification_endpoint_types (classificationId, endpointType) VALUES ('classification:cm14:MANUAL', 'CHAT_COMPLETIONS')")
        db.execSQL("INSERT INTO external_observations (observationId, source, externalModelId, canonicalModelId, payloadJson, fetchedAt, ttlMillis, confidence) VALUES ('obs-14', 'fixture-source', 'ext-m', NULL, '{\"hint\":\"fixture\"}', 1000, 1000, 'LOW')")
        db.execSQL("INSERT INTO external_mappings (mappingId, externalId, canonicalModelId, source, confidence, verified, version, observedAt) VALUES ('map-14', 'ext-m1', NULL, 'fixture-source', 'LOW', 0, 1, 2000)")
        db.execSQL("INSERT INTO favorite_models (canonicalModelId, addedAt, source) VALUES ('cm14', 1000, 'USER')")
        db.execSQL("INSERT INTO watchlist_models (canonicalModelId, addedAt, note) VALUES ('cm14', 1000, NULL)")
        db.execSQL("INSERT INTO recent_models (canonicalModelId, lastUsedAt, useCount) VALUES ('cm14', 2000, 1)")
        db.execSQL("INSERT INTO provider_notices (noticeId, providerId, observedAt, expiresAt, source, evidenceId, summary, ref) VALUES ('notice-14', 'p14', 2000, NULL, 'OFFICIAL', NULL, 'fixture summary', NULL)")
        db.execSQL("INSERT INTO provider_events (eventId, providerId, kind, observedAt, source, evidenceId, detail) VALUES ('event-14', 'p14', 'RECOVERY', 2100, 'OBSERVED', NULL, NULL)")
        db.execSQL("INSERT INTO price_events (eventId, scopeKey, fromState, toState, fromInput, toInput, fromOutput, toOutput, observedAt, source, evidenceId) VALUES ('price-ev-14', 'scope-14', NULL, NULL, NULL, '0.2', NULL, NULL, 2200, 'OFFICIAL', NULL)")
        db.execSQL("INSERT INTO quota_events (eventId, scopeKey, fromState, toState, observedAt, source, evidenceId) VALUES ('quota-ev-14', 'scope-14', 'QUOTA_AVAILABLE', 'QUOTA_LOW', 2300, 'OBSERVED', NULL)")
        db.execSQL("INSERT INTO provider_perf_facts (evidenceId, scopeKey, kind, observedAt, empty, status, providerVersion, httpStatus, modelId, groupName, hours, accountId, credentialId, avgTtftMs, avgLatencyMs, successRate, avgTps, recentSuccessRates, seriesSchema, seriesJson, seriesPointCount) VALUES ('legacy-evidence-14', 'perf-scope-14', 'SUMMARY', 2000, 0, 'OK', 'v1.0.0-rc.25', 200, 'fixture-model', NULL, 168, NULL, NULL, NULL, 120, 99.5, 10.0, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT INTO provider_perf_heads (scopeKey, kind, latestEvidenceId, latestObservedAt, lastGoodEvidenceId, lastGoodObservedAt) VALUES ('perf-scope-14', 'SUMMARY', 'legacy-evidence-14', 2000, NULL, NULL)")
        db.execSQL("INSERT INTO provider_account_usage_facts (evidenceId, scopeKey, kind, providerId, accountId, credentialId, observedAt, status, providerVersion, httpStatus, source, requestCountRaw, counterSemantics, accountEndpointObserved) VALUES ('legacy-evidence-14', 'usage-scope-14', 'PROVIDER_ACCOUNT_USAGE', 'p14', 'a14', 'safe-ref-only', 2000, 'OK', 'v1.0.0-rc.25', 200, 'newapi/api/user/self', 9007199254740993, 'UNSPECIFIED', 1)")
        db.execSQL("INSERT INTO provider_account_usage_heads (scopeKey, kind, latestEvidenceId, latestObservedAt, lastGoodEvidenceId, lastGoodObservedAt) VALUES ('usage-scope-14', 'PROVIDER_ACCOUNT_USAGE', 'legacy-evidence-14', 2000, NULL, NULL)")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\",groupIdentity) VALUES ('availability-14-x','off14-a','a14','c14','PAID','QUOTA_AVAILABLE','vip','vvip')")
        db.execSQL("INSERT INTO checkins (providerId,date,completedAt,source,accountId) VALUES ('p14','2026-10-04',17,'USER','a14')")
        db.execSQL("INSERT INTO price_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,input,output,fixedRequest,fixedRequestKnownAbsent,cacheRead,cacheWrite,source,evidenceId) VALUES ('legacy-price-14','p14','a14','c14','fixture-model',NULL,'e14','CHAT_COMPLETIONS',1300,'0.000000000000000000123456789',NULL,NULL,0,NULL,NULL,'USER','legacy-evidence-14')")
        db.execSQL("INSERT INTO quota_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,state,source,evidenceId) VALUES ('legacy-quota-14','p14','a14',NULL,'fixture-model','','e14','CHAT_COMPLETIONS',1400,NULL,'PROBE','legacy-evidence-14')")
        db.execSQL("INSERT INTO my_models (providerId,providerModelId,addedAt,source) VALUES ('p14','fixture-model',1500,'USER')")
        db.execSQL("INSERT INTO samples (id,providerId,accountId,safeCredentialId,endpointId,modelId,operation,source,startedAt,streaming,usageEstimated,outcome,retryCount,rateLimitHeaders,priceState,quotaState,inputTokens,outputTokens) VALUES ('legacy-sample-14','p14','a14','c14','e14','fixture-model','CHAT_COMPLETIONS','PLAYGROUND',1000,1,0,'SUCCESS',0,'','FREE_NOW','UNKNOWN',9007199254740993,7)")
    }

    // ---- assertions / helpers -----------------------------------------------

    private data class SliceHeadRow(
        val latestEvidenceId: String?,
        val latestObservedAt: Long?,
        val lastGoodSnapshotId: String?,
        val lastGoodObservedAt: Long?,
    )

    private fun assertSliceHead(
        db: SupportSQLiteDatabase,
        scopeKey: String,
        kind: String,
        slice: String,
        latestEvidenceId: String,
        latestObservedAt: Long,
        lastGoodSnapshotId: String?,
        lastGoodObservedAt: Long?,
    ) {
        val row = sliceHead(db, scopeKey, kind, slice)
        assertTrue("expected slice head $kind/$slice", row != null)
        assertEquals(latestEvidenceId, row!!.latestEvidenceId)
        assertEquals(latestObservedAt, row.latestObservedAt)
        assertEquals(lastGoodSnapshotId, row.lastGoodSnapshotId)
        assertEquals(lastGoodObservedAt, row.lastGoodObservedAt)
    }

    private fun sliceHead(db: SupportSQLiteDatabase, scopeKey: String, kind: String, slice: String): SliceHeadRow? =
        db.query(
            "SELECT latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt " +
                "FROM `$SLICE_HEAD_TABLE` WHERE scopeKey = ? AND kind = ? AND quotaSlice = ?",
            arrayOf(scopeKey, kind, slice),
        ).use { c ->
            if (!c.moveToFirst()) return null
            SliceHeadRow(
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getLong(1),
                if (c.isNull(2)) null else c.getString(2),
                if (c.isNull(3)) null else c.getLong(3),
            )
        }

    private fun sliceHeadCount(db: SupportSQLiteDatabase, scopeKey: String, kind: String): Int =
        db.query("SELECT COUNT(*) FROM `$SLICE_HEAD_TABLE` WHERE scopeKey = ? AND kind = ?", arrayOf(scopeKey, kind)).use {
            it.moveToFirst(); it.getInt(0)
        }

    private fun factCount(db: SupportSQLiteDatabase, scopeKey: String, kind: String?): Int =
        if (kind == null) {
            db.query("SELECT COUNT(*) FROM snapshot_facts WHERE scopeKey = ?", arrayOf(scopeKey)).use {
                it.moveToFirst(); it.getInt(0)
            }
        } else {
            db.query("SELECT COUNT(*) FROM snapshot_facts WHERE scopeKey = ? AND kind = ?", arrayOf(scopeKey, kind)).use {
                it.moveToFirst(); it.getInt(0)
            }
        }

    private fun rawHead(db: SupportSQLiteDatabase, scopeKey: String, kind: String): SliceHeadRow? =
        db.query(
            "SELECT latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt " +
                "FROM snapshot_heads WHERE scopeKey = ? AND kind = ?",
            arrayOf(scopeKey, kind),
        ).use { c ->
            if (!c.moveToFirst()) return null
            SliceHeadRow(
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getLong(1),
                if (c.isNull(2)) null else c.getString(2),
                if (c.isNull(3)) null else c.getLong(3),
            )
        }

    /** Every scopeKey in the table must parse to exactly 7 dims (pos == key.length). */
    private fun assertScopeKeyParses(db: SupportSQLiteDatabase, table: String) {
        db.query("SELECT scopeKey FROM `$table`").use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                val parsed = parseScopeKey(key)
                assertTrue("scopeKey does not parse to 7 dims in $table: $key", parsed?.size == 7)
            }
        }
    }

    private fun assertFreshMatchesUpgraded(context: android.content.Context, upgraded: SupportSQLiteDatabase) {
        val fresh = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(
                normalizedSchemaObjects(fresh.openHelper.writableDatabase),
                normalizedSchemaObjects(upgraded),
            )
        } finally {
            fresh.close()
        }
    }

    private suspend fun expectAbort(block: suspend () -> Unit) {
        try {
            block()
            fail("expected SQLiteConstraintException (ABORT/RESTRICT)")
        } catch (_: SQLiteConstraintException) {
        }
    }

    private fun tables(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use {
            while (it.moveToNext()) add(it.getString(0))
        }
    }

    private fun schemaObjects(db: SupportSQLiteDatabase): Map<String, String?> = buildMap {
        db.query("SELECT name, sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use {
            while (it.moveToNext()) put(it.getString(0), if (it.isNull(1)) null else it.getString(1))
        }
    }

    private fun normalizedSchemaObjects(db: SupportSQLiteDatabase): Map<String, String?> =
        schemaObjects(db).mapValues { (objectName, ddl) ->
            ddl?.replace("\${TABLE_NAME}", objectName)?.replace("IF NOT EXISTS ", "")
        }

    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = buildList {
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                if (cursor.isNull(it)) null else cursor.getString(it)
            })
        }
    }

    private fun sqlNullable(value: Any?): String = if (value == null) "NULL" else "'$value'"

    private companion object {
        // Migration version 15 LOCKED by Main/Principal for the CR-011 slice.
        const val LEGACY_VERSION = 14
        const val NEXT_VERSION = 15 // 14 -> 15
        const val SLICE_HEAD_TABLE = "snapshot_slice_heads"

        // Exact 7-dim scope keys built like PricingScope.storageKey():
        // [providerId, accountId, credentialId, modelId, group, endpointId, operation]
        // "p14" => "3:p14"; "a14" => "3:a14"; "c14" => "3:c14";
        // "fixture-model" => "13:fixture-model"; group null => "-1:";
        // "e14" => "3:e14"; "CHAT_COMPLETIONS" => "16:CHAT_COMPLETIONS".
        private const val SCOPE_SUFFIX = "3:a14" + "3:c14" + "13:fixture-model" + "-1:" + "3:e14" + "16:CHAT_COMPLETIONS"
        const val TRIO_SCOPE_KEY = "3:p14" + SCOPE_SUFFIX
        const val TIE_SCOPE_KEY = "3:p14" + SCOPE_SUFFIX // reuse; tests use separate DBs
        const val MIXED_SCOPE_KEY = "3:p14" + SCOPE_SUFFIX
        const val UNSLICED_SCOPE_KEY = "3:p14" + SCOPE_SUFFIX
        const val COEXIST_SCOPE_KEY = "3:p14" + SCOPE_SUFFIX
        const val DEFENSIVE_SCOPE_KEY = "3:p14" + SCOPE_SUFFIX

        // Raw insert matching the identity memo §3.1 DDL, used only for the
        // fail-closed PK test. Seven %s in order: scopeKey, kind, quotaSlice,
        // latestEvidenceId, latestObservedAt, lastGoodSnapshotId, lastGoodObservedAt
        // (lastGood columns are pre-rendered literals: 'text' or NULL).
        const val SLICE_HEAD_INSERT =
            "INSERT INTO `$SLICE_HEAD_TABLE` " +
                "(scopeKey,kind,quotaSlice,latestEvidenceId,latestObservedAt,lastGoodSnapshotId,lastGoodObservedAt) " +
                "VALUES ('%s','%s','%s','%s',%s,%s,%s)"
    }
}

// =============================================================================
// PRODUCTION SHAPE THIS FIXTURE COVERS (identity memo §3.1/§3.3; landed
// same-slice per spec §4 steps 1-5; reference only — NOTHING below is active
// code; 15.json is the real CI/KSP export only).
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
// DAO ports (co-located in SnapshotFacts.kt, called as production forms):
//   sliceHead(scopeKey, kind, quotaSlice): SnapshotSliceHeadEntity?
//   upsertSliceHead(head): Unit            // @Insert(REPLACE)
//   sliceHeadsByKind(kind): List<SnapshotSliceHeadEntity>
//
// Migration backfill 14->15 (identity memo §4.2, ambiguity -> absence):
//   Per group (scopeKey, kind, quotaSlice) over snapshot_facts
//   WHERE quotaSlice IS NOT NULL AND quotaSlice != ''
//     AND quotaSlice IN ('STANDARD_INTERACTIVE','PRIORITY','BATCH')
//     AND kind IN ('ACCOUNT_QUOTA','TOKEN_QUOTA','DEDICATED_ACCOUNT_QUOTA','DEDICATED_TOKEN_QUOTA'):
//     latest_rows := facts at MAX(observedAt)             -- require exactly 1
//     good        := snapshotId IS NOT NULL AND state != 'UNKNOWN'
//     lastGood_row:= facts at MAX(observedAt) among good  -- require <= 1
//     IF |latest_rows| == 1 AND |lastGood_row| <= 1:
//         INSERT slice head (lastGoodSnapshotId/At observedAt NULL if no good fact)
//     ELSE:
//         no row  -- Unknown preserved, no tie winner picked