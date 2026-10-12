package dev.modelatlas.app.data

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.core.lmspeed.LMSPEED_SOURCE
import dev.modelatlas.core.lmspeed.LmspeedFetcher
import dev.modelatlas.core.lmspeed.LmspeedRawModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P7-B2 LMSpeed enrichment 觸發點 device test（TRACEABILITY LMS-001／LMS-002／LMS-005）。
 *
 * 只用記憶體 db（repository 直測）＋真 app DB（ViewModel 觸發鏈）＋假 fetcher
 * （記憶體列，不打網路、不碰 vault、無 key、無 URL）；fixture 僅存本檔，不當產品資料。
 * 釘住：fetcher→importer→sink→saveMapping 全鏈落庫、失敗保舊、single-flight、
 * ViewModel 狀態轉換（syncing／error／lastSynced）。
 */
@RunWith(AndroidJUnit4::class)
class LmspeedEnrichmentFlowTest {
    private lateinit var memDb: AtlasDatabase
    private lateinit var repo: AtlasRepository

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    private class FakeFetcher(
        val rows: List<LmspeedRawModel> = emptyList(),
        var error: Throwable? = null,
        var calls: Int = 0,
    ) : LmspeedFetcher {
        override suspend fun fetchModels(): List<LmspeedRawModel> {
            calls++
            error?.let { throw it }
            return rows
        }
    }

    private fun row(slug: String) = LmspeedRawModel(slug = slug)

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        memDb = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AtlasRepository(memDb)
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(viewModelStore, LmspeedViewModelFactory(app)).get(AtlasViewModel::class.java)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        runCatching { memDb.close() }
        deleteVmFixture()
    }

    @Test
    fun repo_successWritesObservationsAndMappings() = runBlocking {
        val fetcher = FakeFetcher(rows = listOf(row("fixture-lmspeed-a"), row("gpt-4o")))
        val summary = repo.syncLmspeedEnrichment(
            fetcher = fetcher,
            nowMillis = 5_000L,
            ttlMillis = 1_000L,
            knownCanonicalIds = setOf("gpt-4o"),
        )
        assertEquals(1, fetcher.calls)
        assertEquals(2, summary.saved)
        // 僅 gpt-4o 命中 known（LOW），另一筆 UNKNOWN 仍寫槽但不計 mapped。
        assertEquals(1, summary.mapped)
        assertEquals(2, memDb.externalObservationDao().count())
        assertEquals(2, memDb.externalMappingDao().count())
        val hit = memDb.externalMappingDao().resolve("gpt-4o", LMSPEED_SOURCE)!!
        assertEquals("gpt-4o", hit.canonicalModelId)
        val unknown = memDb.externalMappingDao().resolve("fixture-lmspeed-a", LMSPEED_SOURCE)!!
        assertNull(unknown.canonicalModelId)
        // 讀寫分離：本地 telemetry 表全空。
        for (kind in SnapshotKind.entries) {
            assertEquals(kind.name, 0, memDb.snapshotFactDao().headsByKind(kind.name).size)
        }
        assertEquals(0, memDb.sampleDao().running().size)
        // P7-V4 §(4) 弱項補強：snapshot_facts／price／quota／evidence 全空（讀寫分離）。
        assertEquals(0, countRows("snapshot_facts"))
        assertEquals(0, countRows("price_snapshots"))
        assertEquals(0, countRows("quota_snapshots"))
        assertEquals(0, countRows("evidence"))
    }

    @Test
    fun repo_failurePreservesOld() = runBlocking {
        val ok = FakeFetcher(rows = listOf(row("fixture-lmspeed-keep")))
        repo.syncLmspeedEnrichment(fetcher = ok, nowMillis = 5_000L, ttlMillis = 1_000L)
        assertEquals(1, memDb.externalObservationDao().count())
        val failing = FakeFetcher(error = IllegalStateException("fixture fetch boom"))
        var failed = false
        try {
            repo.syncLmspeedEnrichment(fetcher = failing, nowMillis = 6_000L, ttlMillis = 1_000L)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        // 舊列原地保留，未被刪除或覆蓋。
        assertEquals(1, memDb.externalObservationDao().count())
        assertEquals(
            "fixture-lmspeed-keep",
            memDb.externalObservationDao().byExternal(LMSPEED_SOURCE, "fixture-lmspeed-keep").single().externalModelId,
        )
    }

    @Test
    fun repo_emptyIsNoop() = runBlocking {
        val empty = FakeFetcher(rows = emptyList())
        val summary = repo.syncLmspeedEnrichment(fetcher = empty, nowMillis = 5_000L, ttlMillis = 1_000L)
        assertEquals(0, summary.saved)
        assertEquals(0, summary.mapped)
        assertEquals(0, memDb.externalObservationDao().count())
        assertEquals(0, memDb.externalMappingDao().count())
    }

    @Test
    fun repo_emptyIndicesBuildTrueIndicesFromDb() = runBlocking {
        // P7 repair：產品 UI 傳空集合時，Repository 從 DB 建真 indices 再判定（不傳空集合判定）。
        val canonical = CanonicalStore(memDb)
        val saved = canonical.saveCanonical("gpt-4o", nowMillis = 1_000L)
        canonical.saveAlias(
            providerId = "fixture-provider",
            providerModelId = "GPT-4o",
            canonicalModelId = saved.canonicalModelId,
            source = dev.modelatlas.core.domain.MappingSource.LMSPEED.name,
            confidence = dev.modelatlas.core.domain.MappingConfidence.MEDIUM.name,
        )
        val summary = repo.syncLmspeedEnrichment(
            fetcher = FakeFetcher(rows = listOf(row("GPT-4o"), row("fixture-unknown-xyz"))),
            nowMillis = 5_000L,
            ttlMillis = 60_000L,
        )
        assertEquals(2, summary.saved)
        // GPT-4o 經真 alias 命中（exact→HIGH），另一筆 UNKNOWN 仍寫槽但不計 mapped。
        assertEquals(1, summary.mapped)
        assertEquals(saved.canonicalModelId, memDb.externalMappingDao().resolve("GPT-4o", LMSPEED_SOURCE)!!.canonicalModelId)
        assertNull(memDb.externalMappingDao().resolve("fixture-unknown-xyz", LMSPEED_SOURCE)!!.canonicalModelId)
    }

    @Test
    fun viewModel_trigger_syncingTransitions_lastSynced() {
        deleteVmFixture()
        assertFalse(viewModel.lmspeedSyncing.value)
        viewModel.syncLmspeed(
            fetcher = FakeFetcher(rows = listOf(row("fixture-lmspeed-vm"))),
            nowMillis = 7_000L,
            ttlMillis = 1_000L,
        )
        val done = awaitLmspeed { viewModel.lmspeedLastSynced.value != null && !viewModel.lmspeedSyncing.value }
        assertTrue("syncLmspeed must complete with lastSynced", done)
        assertNull(viewModel.lmspeedSyncError.value)
        val kept = runBlocking {
            app.database.externalObservationDao().byExternal(LMSPEED_SOURCE, "fixture-lmspeed-vm")
        }
        assertEquals(1, kept.size)
    }

    @Test
    fun viewModel_failureSetsErrorPreservesLastSynced() {
        deleteVmFixture()
        // 先成功一次，記下 lastSynced 舊值（失敗保舊的比較基準）。
        viewModel.syncLmspeed(
            fetcher = FakeFetcher(rows = listOf(row("fixture-lmspeed-vm"))),
            nowMillis = 7_000L,
            ttlMillis = 1_000L,
        )
        val firstDone = awaitLmspeed { viewModel.lmspeedLastSynced.value != null && !viewModel.lmspeedSyncing.value }
        assertTrue("first success must complete with lastSynced", firstDone)
        assertNull(viewModel.lmspeedSyncError.value)
        val oldSynced = viewModel.lmspeedLastSynced.value
        assertTrue("old lastSynced must exist before failure", oldSynced != null)
        // 再失敗：錯誤浮現，且 lastSynced 保留舊值（非 null、等於舊值）。
        viewModel.syncLmspeed(
            fetcher = FakeFetcher(error = IllegalStateException("fixture vm boom")),
            nowMillis = 8_000L,
            ttlMillis = 1_000L,
        )
        val done = awaitLmspeed { !viewModel.lmspeedSyncing.value && viewModel.lmspeedSyncError.value != null }
        assertTrue("failure must surface error", done)
        assertEquals("UNKNOWN", viewModel.lmspeedSyncError.value)
        assertEquals(oldSynced, viewModel.lmspeedLastSynced.value)
    }

    @Test
    fun viewModel_emptySuccessMarksEmptyWithoutTouchingLastSynced() {
        deleteVmFixture()
        // 先成功一次建立 lastSynced；真空成功不得更新時間（未證實不冒充同步過）。
        viewModel.syncLmspeed(
            fetcher = FakeFetcher(rows = listOf(row("fixture-lmspeed-vm"))),
            nowMillis = 7_000L,
            ttlMillis = 1_000L,
        )
        assertTrue(awaitLmspeed { viewModel.lmspeedLastSynced.value != null && !viewModel.lmspeedSyncing.value })
        val oldSynced = viewModel.lmspeedLastSynced.value
        viewModel.syncLmspeed(
            fetcher = FakeFetcher(rows = emptyList()),
            nowMillis = 8_000L,
            ttlMillis = 1_000L,
        )
        assertTrue(awaitLmspeed { !viewModel.lmspeedSyncing.value && viewModel.lmspeedSyncError.value != null })
        assertEquals("EMPTY", viewModel.lmspeedSyncError.value)
        assertEquals(oldSynced, viewModel.lmspeedLastSynced.value)
    }

    @Test
    fun viewModel_unconfiguredReportsUnsupportedWithoutNetwork() {
        deleteVmFixture()
        // 不傳 fetcher 即走真產品鏈；測試環境 vault 無 LMSpeed key → UNSUPPORTED 不可同步。
        // （不斷言 vault 內部；只斷言不碰網路、不更新時間、不記假成功。）
        runCatching {
            app.vault.remove(dev.modelatlas.core.lmspeed.LMSPEED_SAFE_CREDENTIAL_ID)
        }
        viewModel.syncLmspeed()
        assertTrue(awaitLmspeed { !viewModel.lmspeedSyncing.value && viewModel.lmspeedSyncError.value != null })
        assertEquals("UNSUPPORTED", viewModel.lmspeedSyncError.value)
        assertNull(viewModel.lmspeedLastSynced.value)
    }

    @Test
    fun viewModel_singleFlight_secondCallReturnsImmediately() {
        deleteVmFixture()
        val fetcher = FakeFetcher(rows = listOf(row("fixture-lmspeed-vm")))
        viewModel.syncLmspeed(fetcher = fetcher, nowMillis = 7_000L, ttlMillis = 1_000L)
        viewModel.syncLmspeed(fetcher = fetcher, nowMillis = 7_000L, ttlMillis = 1_000L)
        val done = awaitLmspeed { viewModel.lmspeedLastSynced.value != null && !viewModel.lmspeedSyncing.value }
        assertTrue("single-flight sync must still complete once", done)
        assertFalse(viewModel.lmspeedSyncing.value)
        // 可觀測計數：double-execution 必紅（若兩次都跑完，calls==2 即失敗）。
        // 給第二個（有 bug 才會跑的）執行留出完成窗口，避免早斷放水。
        Thread.sleep(500)
        assertEquals(1, fetcher.calls)
    }

    private fun deleteVmFixture() {
        runCatching {
            app.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM external_observations WHERE source = ? AND externalModelId = ?",
                arrayOf<Any?>(LMSPEED_SOURCE, "fixture-lmspeed-vm"),
            )
        }
        runCatching {
            app.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM external_mappings WHERE source = ? AND externalId = ?",
                arrayOf<Any?>(LMSPEED_SOURCE, "fixture-lmspeed-vm"),
            )
        }
    }

    private fun awaitLmspeed(timeoutMillis: Long = 15_000L, predicate: () -> Boolean): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            if (Looper.myLooper() != Looper.getMainLooper()) runCatching { instrumentation.waitForIdleSync() }
            Thread.sleep(50)
        }
        return predicate()
    }

    private fun countRows(table: String): Int {
        memDb.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private class LmspeedViewModelFactory(private val app: AtlasApplication) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AtlasViewModel(app) as T
        }
    }
}
