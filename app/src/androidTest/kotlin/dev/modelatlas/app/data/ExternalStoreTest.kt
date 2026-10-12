package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.MappingConfidence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LMS-003／LMS-004 ExternalStore 邊界測試（Room v8）。
 *
 * 只用本地 Room＋假 id；不連線、不帶 secret、不寫任何來源 URL／endpoint、不當產品資料。
 * 釘住：觀測冪等、TTL 邊界、過期查詢／清理、映射冪等＋跨來源獨立、UNKNOWN 不猜、
 * external 絕不寫入 snapshot_facts／heads／samples（讀寫分離）。
 */
@RunWith(AndroidJUnit4::class)
class ExternalStoreTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: ExternalStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = ExternalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun obs(
        source: String = "fixture-source",
        model: String = "fixture-ext-model",
        fetchedAt: Long = 1000L,
        ttl: Long = 1000L,
        payload: String = """{"hint":"fixture"}""",
        canonical: String? = null,
        confidence: String = MappingConfidence.LOW.name,
    ) = ExternalObservationEntity(
        observationId = observationIdOf(source, model, fetchedAt),
        source = source,
        externalModelId = model,
        canonicalModelId = canonical,
        payloadJson = payload,
        fetchedAt = fetchedAt,
        ttlMillis = ttl,
        confidence = confidence,
    )

    @Test
    fun saveObservationsIsIdempotentSameId() = runBlocking {
        val row = obs()
        store.saveObservation(row)
        store.saveObservation(row.copy(payloadJson = """{"hint":"fixture-v2"}"""))
        assertEquals(1, db.externalObservationDao().count())
        val kept = db.externalObservationDao().getById(row.observationId)!!
        assertEquals("""{"hint":"fixture-v2"}""", kept.payloadJson)
    }

    @Test
    fun sameLogicalKeyRewriteDoesNotDuplicate() = runBlocking {
        store.saveObservations(listOf(obs(fetchedAt = 1000L), obs(fetchedAt = 1000L, payload = """{"hint":"retry"}""")))
        assertEquals(1, db.externalObservationDao().count())
        assertEquals("""{"hint":"retry"}""", db.externalObservationDao().getById(obs().observationId)!!.payloadJson)
    }

    @Test
    fun distinctFetchedAtCreatesDistinctRows() = runBlocking {
        store.saveObservations(listOf(obs(fetchedAt = 1000L), obs(fetchedAt = 2000L)))
        assertEquals(2, db.externalObservationDao().count())
        assertEquals(2, store.observationsOf("fixture-source", "fixture-ext-model").size)
    }

    @Test
    fun nullCanonicalStaysNullNeverGuessed() = runBlocking {
        val saved = store.saveObservation(obs(canonical = null))
        assertNull(saved.canonicalModelId)
        assertNull(db.externalObservationDao().getById(saved.observationId)!!.canonicalModelId)
    }

    @Test
    fun ttlNotExpiredBeforeDeadline() = runBlocking {
        store.saveObservation(obs(fetchedAt = 1000L, ttl = 1000L))
        assertEquals(0, store.expiredObservations(1999L).size)
        assertTrue(!store.isExpired(db.externalObservationDao().getById(obs().observationId)!!, 1999L))
    }

    @Test
    fun ttlExpiredAtBoundaryInclusive() = runBlocking {
        store.saveObservation(obs(fetchedAt = 1000L, ttl = 1000L))
        val expired = store.expiredObservations(2000L)
        assertEquals(1, expired.size)
        assertTrue(store.isExpired(expired.single(), 2000L))
    }

    @Test
    fun expiredQueryReturnsOnlyExpired() = runBlocking {
        store.saveObservations(
            listOf(
                obs(model = "old-model", fetchedAt = 1000L, ttl = 500L),
                obs(model = "fresh-model", fetchedAt = 5000L, ttl = 5000L),
            ),
        )
        val expired = store.expiredObservations(2000L)
        assertEquals(1, expired.size)
        assertEquals("old-model", expired.single().externalModelId)
    }

    @Test
    fun purgeExpiredNeverDeletesObservationsPerPermanentRetention() = runBlocking {
        // D-2026-09-30-006（永久保留）：TTL 只做重抓新鮮度觸發；purge 為 no-op，觀測歷史永不得刪。
        store.saveObservations(
            listOf(
                obs(model = "old-model", fetchedAt = 1000L, ttl = 500L),
                obs(model = "fresh-model", fetchedAt = 5000L, ttl = 5000L),
            ),
        )
        assertEquals(0, store.purgeExpired(2000L))
        assertEquals(2, db.externalObservationDao().count())
        assertEquals(1, db.externalObservationDao().byExternal("fixture-source", "old-model").size)
        assertEquals(1, db.externalObservationDao().byExternal("fixture-source", "fresh-model").size)
        assertEquals(0, store.purgeExpired(2000L))
        assertEquals(0, store.purgeExpired(1000L))
        assertEquals(2, db.externalObservationDao().count())
    }

    @Test
    fun purgeExpiredIsNoOpWhenAllExpired() = runBlocking {
        // D-2026-09-30-006：即便全部過期，purge 仍不刪，恆回 0。
        store.saveObservations(
            listOf(
                obs(model = "old-a", fetchedAt = 1000L, ttl = 500L),
                obs(model = "old-b", fetchedAt = 1100L, ttl = 100L),
            ),
        )
        assertEquals(2, store.expiredObservations(2000L).size)
        assertEquals(0, store.purgeExpired(2000L))
        assertEquals(2, db.externalObservationDao().count())
    }

    @Test
    fun expiredQueryNeverDeletesRows() = runBlocking {
        // D-2026-09-30-006：過期查詢是只讀新鮮度觸發，查完筆數不變。
        store.saveObservations(
            listOf(
                obs(model = "old-model", fetchedAt = 1000L, ttl = 500L),
                obs(model = "fresh-model", fetchedAt = 5000L, ttl = 5000L),
            ),
        )
        val expired = store.expiredObservations(2000L)
        assertEquals(1, expired.size)
        assertEquals("old-model", expired.single().externalModelId)
        assertEquals(2, db.externalObservationDao().count())
        assertEquals(0, store.purgeExpired(2000L))
        assertEquals(2, db.externalObservationDao().count())
    }

    @Test
    fun observationsHistorySurvivesRepeatedPurge() = runBlocking {
        // D-2026-09-30-006：重複 purge 觀測歷史仍完整保留；includeExpired 可讀回。
        store.saveObservation(obs(source = "fixture-source", model = "ext-old", fetchedAt = 1_000L, ttl = 500L))
        store.saveMapping("ext-old", "canon-y", "fixture-source", MappingConfidence.MEDIUM.name)
        assertEquals(0, store.purgeExpired(2_000L))
        assertEquals(0, store.purgeExpired(2_000L))
        assertEquals(1, db.externalObservationDao().count())
        assertEquals(1, store.observationsForCanonical("canon-y", nowMillis = 2_000L, includeExpired = true).size)
    }

    @Test
    fun saveMappingIsIdempotentSameKey() = runBlocking {
        val first = store.saveMapping(
            externalId = "ext-m1",
            canonicalModelId = "canon-a",
            source = "fixture-source",
            confidence = MappingConfidence.MEDIUM.name,
        )
        val second = store.saveMapping(
            externalId = "ext-m1",
            canonicalModelId = "canon-b",
            source = "fixture-source",
            confidence = MappingConfidence.HIGH.name,
            version = 2,
            observedAt = 3000L,
        )
        assertEquals(first.mappingId, second.mappingId)
        assertEquals(mappingIdOf("ext-m1", "fixture-source"), second.mappingId)
        assertEquals(1, db.externalMappingDao().count())
        assertEquals("canon-b", db.externalMappingDao().resolve("ext-m1", "fixture-source")!!.canonicalModelId)
        assertEquals(2, second.version)
    }

    @Test
    fun resolveReturnsMappingAndUnknownReturnsNull() = runBlocking {
        store.saveMapping(
            externalId = "ext-m2",
            canonicalModelId = "canon-a",
            source = "fixture-source",
            confidence = MappingConfidence.MEDIUM.name,
        )
        val hit = store.resolve("ext-m2", "fixture-source")!!
        assertEquals("canon-a", hit.canonicalModelId)
        assertNull(store.resolve("ext-missing", "fixture-source"))
        assertNull(store.resolve("ext-m2", "other-source"))
    }

    @Test
    fun mappingUniquePerSourceStaysIndependent() = runBlocking {
        store.saveMapping("ext-shared", "canon-a", "source-a", MappingConfidence.MEDIUM.name)
        store.saveMapping("ext-shared", "canon-b", "source-b", MappingConfidence.LOW.name)
        assertEquals(2, db.externalMappingDao().count())
        assertEquals("canon-a", store.resolve("ext-shared", "source-a")!!.canonicalModelId)
        assertEquals("canon-b", store.resolve("ext-shared", "source-b")!!.canonicalModelId)
    }

    @Test
    fun verifiedMappingRequiresHighOrMedium() = runBlocking {
        var rejected = false
        try {
            store.saveMapping("ext-v1", "canon-a", "fixture-source", MappingConfidence.LOW.name, verified = true)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertNull(store.resolve("ext-v1", "fixture-source"))
        val ok = store.saveMapping("ext-v1", "canon-a", "fixture-source", MappingConfidence.HIGH.name, verified = true)
        assertTrue(ok.verified)
    }

    @Test
    fun externalWritesNeverTouchLocalTables() = runBlocking {
        store.saveObservation(obs())
        store.saveMapping("ext-iso", "canon-iso", "fixture-source", MappingConfidence.MEDIUM.name)
        // P7-B2 MINOR：覆蓋全部 8 種 kind（MANUAL_*／DEDICATED_* 含在內），不只 PRICE／ACCOUNT_QUOTA。
        for (kind in SnapshotKind.entries) {
            assertEquals(kind.name, 0, db.snapshotFactDao().headsByKind(kind.name).size)
        }
        assertEquals(0, db.sampleDao().running().size)
        // snapshot_facts／price_snapshots／quota_snapshots／evidence／samples 全空（讀寫分離）。
        assertEquals(0, countRows("snapshot_facts"))
        assertEquals(0, countRows("price_snapshots"))
        assertEquals(0, countRows("quota_snapshots"))
        assertEquals(0, countRows("evidence"))
        assertEquals(0, countRows("samples"))
        assertEquals(1, db.externalObservationDao().count())
        assertEquals(1, db.externalMappingDao().count())
    }

    @Test
    fun observationsForCanonicalMergesMappingsAndDirectRows() = runBlocking {
        store.saveObservation(obs(source = "fixture-source", model = "ext-a", fetchedAt = 1_000L, ttl = 60_000L))
        store.saveObservation(obs(source = "fixture-source", model = "ext-a", fetchedAt = 2_000L, ttl = 60_000L))
        store.saveObservation(
            obs(source = "fixture-source", model = "ext-b", fetchedAt = 1_500L, ttl = 60_000L, canonical = "canon-x"),
        )
        store.saveMapping("ext-a", "canon-x", "fixture-source", MappingConfidence.MEDIUM.name)
        val rows = store.observationsForCanonical("canon-x", nowMillis = 3_000L)
        assertEquals(3, rows.size)
        // fetchedAt 倒序，未過期。
        assertEquals(2_000L, rows.first().fetchedAt)
        assertTrue(rows.all { !store.isExpired(it, 3_000L) })
    }

    @Test
    fun observationsForCanonicalFiltersExpiredByDefault() = runBlocking {
        store.saveObservation(obs(source = "fixture-source", model = "ext-old", fetchedAt = 1_000L, ttl = 500L))
        store.saveMapping("ext-old", "canon-y", "fixture-source", MappingConfidence.MEDIUM.name)
        assertTrue(store.observationsForCanonical("canon-y", nowMillis = 2_000L).isEmpty())
        assertEquals(1, store.observationsForCanonical("canon-y", nowMillis = 2_000L, includeExpired = true).size)
    }

    @Test
    fun latestObservationOfPicksNewest() = runBlocking {
        store.saveObservation(obs(source = "fixture-source", model = "ext-m", fetchedAt = 1_000L, ttl = 60_000L))
        store.saveObservation(obs(source = "fixture-source", model = "ext-m", fetchedAt = 2_000L, ttl = 60_000L))
        assertEquals(2_000L, store.latestObservationOf("fixture-source", "ext-m")!!.fetchedAt)
        assertNull(store.latestObservationOf("fixture-source", "ext-missing"))
    }

    @Test
    fun sharedPrefsBudgetSurvivesRecreationAndIsolatesRefs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(SharedPrefsLmspeedDailyBudget.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val day = 5_000L
        val first = SharedPrefsLmspeedDailyBudget(context, "fixture-ref-a", maxPerDay = 2)
        assertTrue(first.tryConsume(day))
        assertTrue(first.tryConsume(day))
        assertEquals(0, first.remaining(day))
        // 重建（模擬重啟）不丟已扣額度。
        val second = SharedPrefsLmspeedDailyBudget(context, "fixture-ref-a", maxPerDay = 2)
        assertEquals(0, second.remaining(day))
        // 跨 key 不共用（X-004）。
        val other = SharedPrefsLmspeedDailyBudget(context, "fixture-ref-b", maxPerDay = 2)
        assertEquals(2, other.remaining(day))
        // 換日重置（device-local）。
        val zone = java.time.ZoneId.systemDefault()
        val tomorrow = java.time.Instant.ofEpochMilli(day).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 1_000L
        assertEquals(2, second.remaining(tomorrow))
    }

    private fun countRows(table: String): Int {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }
}
