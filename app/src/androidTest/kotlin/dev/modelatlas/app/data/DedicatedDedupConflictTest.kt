package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.DedicatedStatus
import dev.modelatlas.core.dedicated.PricingFact
import dev.modelatlas.core.dedicated.PricingMode
import dev.modelatlas.core.dedicated.QuotaDimension
import dev.modelatlas.core.dedicated.QuotaPool
import dev.modelatlas.core.dedicated.QuotaScopeKind
import dev.modelatlas.core.dedicated.QuotaValueState
import dev.modelatlas.core.domain.PricingScope
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PK-09 / S9 價值感知去重衝突向量（AI11 spec `ai11-spec-pk9-dedup.md` §4）。
 *
 * 與 [DedicatedSnapshotSyncTest] 同 harness（in-memory Room＋本地 fixture，不連線、
 * 不帶 secret、不當產品資料）。斷言對象是修復後語意：同 ms 不同值必須並存，
 * 完全相同重放仍去重；HEAD 未修復時 V1／V3／V4 為 RED（第二筆被吞），V2／V5 雙綠。
 */
@RunWith(AndroidJUnit4::class)
class DedicatedDedupConflictTest {
    private lateinit var db: AtlasDatabase
    private lateinit var sync: DedicatedSnapshotSync

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        sync = DedicatedSnapshotSync(db)
        for (provider in listOf("openrouter", "gemini", "groq", "nvidia-h", "nvidia-s", "amd")) {
            db.providerDao().insert(ProviderEntity(provider, "測試供應者"))
        }
        db.accountDao().insert(AccountEntity("a", "openrouter"))
        db.accountDao().insert(AccountEntity("ag", "gemini"))
        db.accountDao().insert(AccountEntity("aq", "groq"))
        db.accountDao().insert(AccountEntity("an", "nvidia-h"))
        db.accountDao().insert(AccountEntity("am", "amd"))
        db.credentialDao().insert(CredentialEntity("c", "a"))
        db.credentialDao().insert(CredentialEntity("c2", "a"))
        db.credentialDao().insert(CredentialEntity("cg", "ag"))
        db.credentialDao().insert(CredentialEntity("cn", "an"))
        db.credentialDao().insert(CredentialEntity("cm", "am"))
        db.endpointDao().insert(
            EndpointEntity("e-or", "openrouter", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        db.endpointDao().insert(
            EndpointEntity("e-gm", "gemini", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        db.endpointDao().insert(
            EndpointEntity("e-gq", "groq", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        db.endpointDao().insert(
            EndpointEntity("e-nv", "nvidia-h", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        db.endpointDao().insert(
            EndpointEntity("e-amd", "amd", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun v1sameMsDistinctPricesBothPersisted() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-v1", null, "e-gq", "CHAT_COMPLETIONS")
        val priceBefore = count("price_snapshots")
        val factA = PricingFact("m-v1", BigDecimal("0.001"), BigDecimal("0.002"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        val factB = PricingFact("m-v1", BigDecimal("0.003"), BigDecimal("0.004"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), factA))
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), factB))
        val history = sync.history(scope, SnapshotKind.PRICE)
        assertEquals(2, history.size)
        assertEquals(listOf(1000L, 1000L), history.map { it.observedAt })
        assertEquals(priceBefore + 2, count("price_snapshots"))
        val inputs = history.map { fact ->
            assertNotNull(fact.snapshotId)
            val row = db.priceDao().getById(fact.snapshotId!!)
            assertNotNull(row)
            assertEquals("groq/models", row!!.source)
            row.input
        }
        assertEquals(2, inputs.toSet().size)
        assertTrue(inputs.any { it?.let { v -> BigDecimal(v).compareTo(BigDecimal("0.001")) == 0 } == true })
        assertTrue(inputs.any { it?.let { v -> BigDecimal(v).compareTo(BigDecimal("0.003")) == 0 } == true })
        assertEquals("PAID", sync.price(scope).latest!!.state)
        assertNotNull(sync.price(scope).lastGood)
    }

    @Test
    fun v2identicalPriceReplayStillDeduped() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-v2", null, "e-gq", "CHAT_COMPLETIONS")
        val fact = PricingFact("m-v2", BigDecimal("0.001"), BigDecimal("0.002"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        val priceBefore = count("price_snapshots")
        val evidenceBefore = count("evidence")
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), fact))
        assertEquals(1, sync.history(scope, SnapshotKind.PRICE).size)
        assertEquals(priceBefore + 1, count("price_snapshots"))
        assertEquals(evidenceBefore + 1, count("evidence"))
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), fact))
        assertEquals(1, sync.history(scope, SnapshotKind.PRICE).size)
        assertEquals(priceBefore + 1, count("price_snapshots"))
        assertEquals(evidenceBefore + 1, count("evidence"))
    }

    @Test
    fun v3sameValuesDifferentSourceBothPersisted() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-v3", null, "e-gq", "CHAT_COMPLETIONS")
        val fact = PricingFact("m-v3", BigDecimal("0.001"), BigDecimal("0.002"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), fact))
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/rate-limits", 1000L), fact))
        val history = sync.history(scope, SnapshotKind.PRICE)
        assertEquals(2, history.size)
        val sources = history.map { db.evidenceDao().getById(it.evidenceId)!!.source }.toSet()
        assertEquals(setOf("groq/models", "groq/rate-limits"), sources)
    }

    @Test
    fun v4sameMsQuotaEstimateDifferenceBothPersisted() = runBlocking {
        val scope = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-v4", endpointId = "e-gq")
        val precise = QuotaPool(
            QuotaScopeKind.PER_ORG_MODEL, "m-v4",
            rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false,
        )
        val upper = QuotaPool(
            QuotaScopeKind.PER_ORG_MODEL, "m-v4",
            rpm = QuotaDimension(value = 30L, state = QuotaValueState.KNOWN, upperBound = true),
            provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false,
        )
        assertEquals("QUOTA_AVAILABLE", DedicatedSnapshotSync.quotaStateOf(precise).name)
        assertEquals("QUOTA_AVAILABLE", DedicatedSnapshotSync.quotaStateOf(upper).name)
        sync.syncAccountQuota(scope, DedicatedResult.Success(evidence("groq/rate-limits", 1000L), precise))
        sync.syncAccountQuota(scope, DedicatedResult.Success(evidence("groq/rate-limits", 1000L), upper))
        val history = sync.history(scope, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals(2, history.size)
        assertEquals(setOf(false, true), history.map { it.isEstimated }.toSet())
        assertEquals("QUOTA_AVAILABLE", sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA).latest!!.state)
        assertNotNull(sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA).lastGood)
        // 完全相同重放仍去重。
        sync.syncAccountQuota(scope, DedicatedResult.Success(evidence("groq/rate-limits", 1000L), upper))
        assertEquals(2, sync.history(scope, SnapshotKind.ACCOUNT_QUOTA).size)
    }

    @Test
    fun v5valuelessReplayDedupedStatusDiffersPersisted() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-v5", null, "e-gq", "CHAT_COMPLETIONS")
        val priceBefore = count("price_snapshots")
        sync.syncPricing(scope, DedicatedResult.Failure(DedicatedStatus.HTTP_UNAUTHORIZED_401, evidence("groq/models", 1000L, 401)))
        assertEquals(1, sync.history(scope, SnapshotKind.PRICE).size)
        sync.syncPricing(scope, DedicatedResult.Failure(DedicatedStatus.HTTP_UNAUTHORIZED_401, evidence("groq/models", 1000L, 401)))
        assertEquals(1, sync.history(scope, SnapshotKind.PRICE).size)
        sync.syncPricing(scope, DedicatedResult.Failure(DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429, evidence("groq/models", 1000L, 429)))
        val history = sync.history(scope, SnapshotKind.PRICE)
        assertEquals(2, history.size)
        assertEquals(
            setOf("HTTP_UNAUTHORIZED_401", "HTTP_TOO_MANY_REQUESTS_429"),
            history.map { it.status }.toSet(),
        )
        assertTrue(history.all { it.snapshotId == null })
        assertEquals(priceBefore, count("price_snapshots"))
    }

    @Test
    fun v6appendOnlyHistoryAndLastGoodPreserved() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-v6", null, "e-gq", "CHAT_COMPLETIONS")
        val fact = PricingFact("m-v6", BigDecimal("0.001"), BigDecimal("0.002"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 3000L), fact))
        val firstEvidenceId = sync.history(scope, SnapshotKind.PRICE).single().evidenceId
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), fact))
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 2000L), fact))
        val history = sync.history(scope, SnapshotKind.PRICE)
        // history 排序契約：observedAt DESC，次鍵 evidenceId ASC。
        assertEquals(listOf(3000L, 2000L, 1000L), history.map { it.observedAt })
        val sorted = history.sortedWith(compareByDescending<SnapshotFactEntity> { it.observedAt }.thenBy { it.evidenceId })
        assertEquals(history.map { it.evidenceId }, sorted.map { it.evidenceId })
        // append-only：舊列仍在，無覆寫；evidence 與 fact 一對一增長。
        assertTrue(history.any { it.evidenceId == firstEvidenceId })
        assertEquals(3, count("evidence"))
        // 舊觀測不回滾 latest／lastGood（strict-> 指標語意）。
        assertEquals(3000L, sync.price(scope).latest!!.observedAt)
        assertEquals(3000L, sync.price(scope).lastGood!!.observedAt)
        assertEquals(1L, count("snapshot_heads"))
    }

    private fun evidence(source: String, time: Long, httpStatus: Int = 200) = DedicatedEvidence(
        source, time, DedicatedProvenance.OFFICIAL_DOC, httpStatus,
    )

    private fun count(table: String): Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }
}
