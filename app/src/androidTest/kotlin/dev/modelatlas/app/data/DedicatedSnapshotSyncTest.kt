package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.dedicated.AmdAdapter
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedProviderType
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.DedicatedStatus
import dev.modelatlas.core.dedicated.OpenRouterFreeAdapter
import dev.modelatlas.core.dedicated.PricingFact
import dev.modelatlas.core.dedicated.PricingMode
import dev.modelatlas.core.dedicated.QuotaDimension
import dev.modelatlas.core.dedicated.QuotaPool
import dev.modelatlas.core.dedicated.QuotaPoolSlice
import dev.modelatlas.core.dedicated.QuotaScopeKind
import dev.modelatlas.core.domain.PricingScope
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dedicated 觀測→`snapshot_facts` 邊界測試（ADAPT-001~007 repository 層）。
 *
 * 只用 typed adapter 輸出＋本地 Room；不連線、不帶 secret、不當產品資料。
 * 釘住：重用 PRICE／ACCOUNT_QUOTA／TOKEN_QUOTA＋provider 維度 key、不另加表、
 * 來源字串分家、Gemini project→group、失敗／Unknown 不碰 last-good、secret 只傳 ref。
 */
@RunWith(AndroidJUnit4::class)
class DedicatedSnapshotSyncTest {
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
    fun openRouterDoubleZeroPricingIsFreeNowWithSourcePreserved() = runBlocking {
        val scope = PricingScope("openrouter", "a", null, "m-free", null, "e-or", "CHAT_COMPLETIONS")
        val entry = OpenRouterFreeAdapter.CatalogEntry("m-free", "0", "0", "0", null, null)
        val stored = sync.syncOpenRouterPricing(
            scope,
            DedicatedResult.Success(
                DedicatedEvidence("openrouter/api/v1/models", 1000L, DedicatedProvenance.OBSERVED, 200),
                entry,
            ),
        )
        assertEquals("FREE_NOW", stored.latest!!.state)
        assertEquals("OK", stored.latest!!.status)
        assertEquals("OBSERVED", stored.latest!!.confidence)
        assertNotNull(stored.lastGood)
        decimal("0", stored.lastGood!!.input)
        decimal("0", stored.lastGood!!.output)
        val evidence = db.evidenceDao().getById(stored.latest!!.evidenceId)!!
        assertEquals("openrouter/api/v1/models", evidence.source)
        assertEquals("openrouter", evidence.providerId)
        assertEquals("m-free", evidence.modelId)
        assertEquals("USD_PER_1K_TOKENS", stored.latest!!.priceUnit)
    }

    @Test
    fun pricingUnknownAndFailuresPreserveLastGood() = runBlocking {
        val scope = PricingScope("openrouter", "a", null, "m1", null, "e-or", "CHAT_COMPLETIONS")
        val goodFact = PricingFact("m1", BigDecimal("0.002"), BigDecimal("0.004"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("openrouter/api/v1/models", 1000L), goodFact))
        val good = sync.price(scope).lastGood!!
        // Unknown 模式（無 per-token 計價維度）不碰 last-good（B2：同家 source 才可寫）。
        sync.syncPricingMode(
            scope,
            DedicatedResult.Success(evidence("openrouter/api/v1/models", 2000L), PricingMode.NO_PER_TOKEN_BILLING),
        )
        // Unknown legs（null）不碰 last-good。
        val unknownFact = PricingFact("m1", null, null, null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("openrouter/api/v1/models", 3000L), unknownFact))
        // Failure／Unsupported 不碰 last-good，但推進 latest＋history；M4：不建 snapshot 列。
        sync.syncPricing(
            scope,
            DedicatedResult.Failure(DedicatedStatus.HTTP_UNAUTHORIZED_401, evidence("openrouter/api/v1/models", 4000L, 401)),
        )
        sync.syncPricing(
            scope,
            DedicatedResult.Unsupported(evidence("openrouter/api/v1/models", 5000L), "no structured endpoint"),
        )
        val stored = sync.price(scope)
        assertEquals(good, stored.lastGood)
        assertEquals(5000L, stored.latest!!.observedAt)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertEquals("CAPABILITY_UNSUPPORTED", stored.latest!!.status)
        // M4：Unsupported／Failure latest 無 snapshot 列。
        assertNull(stored.latest!!.snapshotId)
        assertEquals(5, sync.history(scope, SnapshotKind.PRICE).size)
        assertEquals(listOf(5000L, 4000L, 3000L, 2000L, 1000L), sync.history(scope, SnapshotKind.PRICE).map { it.observedAt })
    }

    @Test
    fun amdPricingConvertsPerTokenToPer1kAndNullStaysUnknown() = runBlocking {
        val scope = PricingScope("amd", "am", null, "amd-m", null, "e-amd", "CHAT_COMPLETIONS")
        val entry = AmdAdapter.AmdModelEntry("amd-m", BigDecimal("0.000002"), BigDecimal("0.000006"), null, null, true, null)
        val stored = sync.syncAmdPricing(
            scope,
            DedicatedResult.Success(DedicatedEvidence("amd/v1/models", 1000L, DedicatedProvenance.OFFICIAL_DOC, 200), entry),
        )
        assertEquals("PAID", stored.latest!!.state)
        decimal("0.002", stored.lastGood!!.input)
        decimal("0.006", stored.lastGood!!.output)
        val missing = entry.copy(promptPerTokenUsd = null, completionPerTokenUsd = null)
        sync.syncAmdPricing(
            scope,
            DedicatedResult.Success(DedicatedEvidence("amd/v1/models", 2000L, DedicatedProvenance.OFFICIAL_DOC, 200), missing),
        )
        assertEquals("UNKNOWN", sync.price(scope).latest!!.state)
        assertEquals(1000L, sync.price(scope).lastGood!!.observedAt)
    }

    @Test
    fun accountQuotaKnownUpdatesLastGood_unknownPreserves() = runBlocking {
        val scope = DedicatedSnapshotSync.accountQuotaScope("openrouter", "a")
        val known = QuotaPool(
            QuotaScopeKind.PLATFORM_GLOBAL, null,
            rpm = QuotaDimension.known(20L), rpd = QuotaDimension.known(50L),
            tpm = QuotaDimension.unknown(), tpd = QuotaDimension.unknown(),
            resetSemantics = "UTC_DAY", provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false,
        )
        val stored = sync.syncAccountQuota(
            scope,
            DedicatedResult.Success(DedicatedEvidence("openrouter/api/v1/key", 1000L, DedicatedProvenance.OFFICIAL_DOC, 200), known),
        )
        assertEquals("PUBLIC_POOL", stored.latest!!.state)
        assertEquals(1000L, stored.lastGood!!.observedAt)
        assertEquals("OFFICIAL", stored.latest!!.confidence)
        assertNull(stored.latest!!.remainingRaw)
        val unknown = QuotaPool(
            QuotaScopeKind.PLATFORM_GLOBAL, null,
            provenance = DedicatedProvenance.OFFICIAL_DOC,
        )
        sync.syncAccountQuota(
            scope,
            DedicatedResult.Success(DedicatedEvidence("openrouter/api/v1/key", 2000L, DedicatedProvenance.OFFICIAL_DOC, 200), unknown),
        )
        val after = sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals("UNKNOWN", after.latest!!.state)
        assertEquals(1000L, after.lastGood!!.observedAt)
        sync.syncAccountQuota(
            scope,
            DedicatedResult.Failure(DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429, evidence("openrouter/api/v1/key", 3000L, 429)),
        )
        assertEquals(1000L, sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA).lastGood!!.observedAt)
        assertEquals(3000L, sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA).latest!!.observedAt)
    }

    @Test
    fun tokenQuotasStayIsolatedByCredentialAndProvider() = runBlocking {
        val s1 = DedicatedSnapshotSync.tokenQuotaScope("openrouter", "a", "c")
        val s2 = DedicatedSnapshotSync.tokenQuotaScope("openrouter", "a", "c2")
        val pool = QuotaPool(
            QuotaScopeKind.PER_KEY, null,
            rpm = QuotaDimension.known(40L), provenance = DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX, isGroundTruth = false,
        )
        // B2：同家 source 才可寫（openrouter scope 配 openrouter source）。
        sync.syncTokenQuota(s1, "c", DedicatedResult.Success(evidence("openrouter/api/v1/key", 1000L), pool))
        sync.syncTokenQuota(s2, "c2", DedicatedResult.Success(evidence("openrouter/api/v1/key", 1000L), pool))
        assertEquals(1000L, sync.quota(s1, SnapshotKind.TOKEN_QUOTA).lastGood!!.observedAt)
        assertEquals(1000L, sync.quota(s2, SnapshotKind.TOKEN_QUOTA).lastGood!!.observedAt)
        assertNotEquals(
            sync.quota(s1, SnapshotKind.TOKEN_QUOTA).latest!!.evidenceId,
            sync.quota(s2, SnapshotKind.TOKEN_QUOTA).latest!!.evidenceId,
        )
        // 同 credential 不同 provider 不覆蓋。
        db.providerDao().insert(ProviderEntity("p2", "測試供應者2"))
        db.accountDao().insert(AccountEntity("a2", "p2"))
        db.credentialDao().insert(CredentialEntity("c-p2", "a2"))
        val cross = DedicatedSnapshotSync.tokenQuotaScope("p2", "a2", "c-p2")
        assertNull(sync.quota(cross, SnapshotKind.TOKEN_QUOTA).latest)
        assertEquals(2L, count("snapshot_heads"))
    }

    @Test
    fun geminiProjectGoesToGroupAndLegacyConcatIsRejected() = runBlocking {
        val p1 = DedicatedSnapshotSync.geminiScope("gemini", "ag", null, "proj-1", "gemini-3-flash", "e-gm", "CHAT_COMPLETIONS")
        val p2 = DedicatedSnapshotSync.geminiScope("gemini", "ag", null, "proj-2", "gemini-3-flash", "e-gm", "CHAT_COMPLETIONS")
        assertEquals("proj-1", p1.group)
        assertEquals("gemini-3-flash", p1.modelId)
        val quotaScope1 = DedicatedSnapshotSync.accountQuotaScope("gemini", "ag", group = "proj-1", modelId = "gemini-3-flash", endpointId = "e-gm")
        val quotaScope2 = DedicatedSnapshotSync.accountQuotaScope("gemini", "ag", group = "proj-2", modelId = "gemini-3-flash", endpointId = "e-gm")
        val pool1 = QuotaPool(
            QuotaScopeKind.PER_PROJECT_MODEL, "gemini-3-flash",
            rpm = QuotaDimension.requiresLogin(), rpd = QuotaDimension.requiresLogin(),
            tpm = QuotaDimension.requiresLogin(), tpd = QuotaDimension.requiresLogin(),
            resetSemantics = "PACIFIC_MIDNIGHT", provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false,
            projectId = "proj-1", quotaSlice = QuotaPoolSlice.STANDARD_INTERACTIVE,
        )
        sync.syncAccountQuota(quotaScope1, DedicatedResult.Success(evidence("gemini/rate-limits", 1000L), pool1))
        // RequiresLogin 即 UNKNOWN，不碰 last-good（lastGood null）。
        // CR-011：quotaSlice 非 null 的 pool 寫入 slice namespace，
        // 讀回必須走 3-arg slice overload（unsliced reader 看不到 slice heads）。
        assertEquals("UNKNOWN", sync.quota(quotaScope1, SnapshotKind.ACCOUNT_QUOTA, "STANDARD_INTERACTIVE").latest!!.state)
        assertNull(sync.quota(quotaScope1, SnapshotKind.ACCOUNT_QUOTA, "STANDARD_INTERACTIVE").lastGood)
        assertNull(sync.quota(quotaScope2, SnapshotKind.ACCOUNT_QUOTA, "STANDARD_INTERACTIVE").latest)
        // 舊拼接 `proj::model` 必須拒絕（reviewer：project 移出 modelId）。
        val legacy = pool1.copy(modelId = "proj-1::gemini-3-flash")
        try {
            sync.syncAccountQuota(quotaScope1, DedicatedResult.Success(evidence("gemini/rate-limits", 2000L), legacy))
            fail("Concatenated pool model must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            PricingScope("gemini", "ag", null, "proj-1::gemini-3-flash", null, "e-gm", "CHAT_COMPLETIONS")
                .let { sync.syncPricing(it, DedicatedResult.Success(evidence("gemini/pricing", 2000L), PricingFact("proj-1::gemini-3-flash", null, null, null, PricingMode.UNKNOWN, DedicatedProvenance.OFFICIAL_DOC))) }
            fail("Concatenated pricing model must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun quotaPoolsBatchKeepsPerModelSeparateAndFailureIsolated() = runBlocking {
        val a = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-a", endpointId = "e-gq")
        val b = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-b", endpointId = "e-gq")
        val poolA = QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-a", rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false)
        val poolB = QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-b", rpm = QuotaDimension.known(10L), provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false)
        val results = sync.syncQuotaPools(
            listOf(
                DedicatedQuotaEntry(a, null, DedicatedResult.Success(evidence("groq/rate-limits", 1000L), poolA)),
                DedicatedQuotaEntry(b, null, DedicatedResult.Failure(DedicatedStatus.HTTP_UNAUTHORIZED_401, evidence("groq/rate-limits", 1000L, 401))),
            ),
        )
        assertEquals(2, results.size)
        assertEquals("QUOTA_AVAILABLE", sync.quota(a, SnapshotKind.ACCOUNT_QUOTA).latest!!.state)
        assertNotNull(sync.quota(a, SnapshotKind.ACCOUNT_QUOTA).lastGood)
        assertEquals("UNKNOWN", sync.quota(b, SnapshotKind.ACCOUNT_QUOTA).latest!!.state)
        assertNull(sync.quota(b, SnapshotKind.ACCOUNT_QUOTA).lastGood)
        assertEquals("groq/rate-limits", db.evidenceDao().getById(sync.quota(a, SnapshotKind.ACCOUNT_QUOTA).latest!!.evidenceId)!!.source)
    }

    @Test
    fun openRouterKeyQuotaPreservesLongPrecisionAndExhaustion() = runBlocking {
        val scope = DedicatedSnapshotSync.tokenQuotaScope("openrouter", "a", "c")
        val exact = 9_007_199_254_740_993L
        sync.syncOpenRouterKeyQuota(
            scope, "c",
            DedicatedResult.Success(
                evidence("openrouter/api/v1/key", 1000L),
                OpenRouterFreeAdapter.KeyQuotaFacts(exact, exact + 1, 1L, true, true),
            ),
        )
        val stored = sync.quota(scope, SnapshotKind.TOKEN_QUOTA)
        assertEquals("QUOTA_AVAILABLE", stored.latest!!.state)
        assertEquals(1L, stored.latest!!.remainingRaw)
        assertEquals(exact, stored.latest!!.usedRaw)
        sync.syncOpenRouterKeyQuota(
            scope, "c",
            DedicatedResult.Success(
                evidence("openrouter/api/v1/key", 2000L),
                OpenRouterFreeAdapter.KeyQuotaFacts(exact, exact, 0L, true, true),
            ),
        )
        assertEquals("QUOTA_EXHAUSTED", sync.quota(scope, SnapshotKind.TOKEN_QUOTA).latest!!.state)
        assertEquals(2000L, sync.quota(scope, SnapshotKind.TOKEN_QUOTA).lastGood!!.observedAt)
    }

    @Test
    fun secretsOnlyRefsAndSourcesAreValidated() = runBlocking {
        val scope = PricingScope("openrouter", "a", null, "m1", null, "e-or", "CHAT_COMPLETIONS")
        val fact = PricingFact("m1", BigDecimal.ZERO, BigDecimal.ZERO, null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        // newapi 來源冒充 dedicated 拒絕。
        try {
            sync.syncPricing(scope, DedicatedResult.Success(evidence("newapi/api/pricing", 1000L), fact))
            fail("newapi source must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        // MANUAL 冒充官方拒絕。
        try {
            sync.syncPricing(
                scope,
                DedicatedResult.Success(
                    DedicatedEvidence("openrouter/api/v1/models", 1000L, DedicatedProvenance.MANUAL, 200),
                    fact,
                ),
            )
            fail("MANUAL must not impersonate dedicated facts")
        } catch (_: IllegalArgumentException) {
        }
        // secret 原值（sk-）當 ref 拒絕；pricing 帶 credential 拒絕；per-key 走 account API 拒絕。
        val tokenScope = DedicatedSnapshotSync.tokenQuotaScope("openrouter", "a", "c")
        try {
            sync.syncTokenQuota(tokenScope, "sk-secret-value-1234567890", DedicatedResult.Success(evidence("openrouter/api/v1/key", 1000L), QuotaPool(QuotaScopeKind.PER_KEY, null, provenance = DedicatedProvenance.OFFICIAL_DOC)))
            fail("secret must not pass as ref")
        } catch (_: IllegalArgumentException) {
        }
        try {
            sync.syncAccountQuota(
                tokenScope,
                DedicatedResult.Success(evidence("openrouter/api/v1/key", 1000L), QuotaPool(QuotaScopeKind.PLATFORM_GLOBAL, null, provenance = DedicatedProvenance.OFFICIAL_DOC)),
            )
            fail("credentialed scope must not use account API")
        } catch (_: IllegalArgumentException) {
        }
        // DB 無 secret 殘留：全部 source／fact 皆為 dedicated 前綴，無 sk-。
        sync.syncPricing(scope, DedicatedResult.Success(evidence("openrouter/api/v1/models", 2000L), fact))
        for (row in sync.history(scope, SnapshotKind.PRICE)) {
            assertTrue(row.evidenceId.startsWith("fact:"))
            assertFalse(db.evidenceDao().getById(row.evidenceId)!!.source.contains("sk-"))
        }
    }

    @Test
    fun noNewTablesAndConfidenceSeparation() = runBlocking {
        val scope = PricingScope("nvidia-h", "an", null, "nv-m", null, "e-nv", "CHAT_COMPLETIONS")
        sync.syncPricingMode(scope, DedicatedResult.Success(evidence("nvidia-hosted/pricing", 1000L), PricingMode.NO_PER_TOKEN_BILLING))
        assertEquals("UNKNOWN", sync.price(scope).latest!!.state)
        assertNull(sync.price(scope).lastGood)
        assertEquals("OFFICIAL", sync.price(scope).latest!!.confidence)
        val observed = PricingScope("amd", "am", null, "amd-m2", null, "e-amd", "CHAT_COMPLETIONS")
        sync.syncAmdPricing(
            observed,
            DedicatedResult.Success(
                DedicatedEvidence("amd/v1/models", 1000L, DedicatedProvenance.OBSERVED, 200),
                AmdAdapter.AmdModelEntry("amd-m2", BigDecimal.ZERO, BigDecimal.ZERO, null, null, true, null),
            ),
        )
        // AMD 無料未知時 fixed absent=true，雙零可 FREE；provenance OBSERVED 保留不冒充 OFFICIAL。
        assertEquals("OBSERVED", sync.price(observed).latest!!.confidence)
        val tables = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use {
            generateSequence { if (it.moveToNext()) it.getString(0) else null }.toList()
        }
        assertTrue(tables.none { it.contains("dedicated", ignoreCase = true) })
        assertTrue(tables.contains("snapshot_facts"))
        assertTrue(tables.contains("snapshot_heads"))
    }

    @Test
    fun olderObservationsDoNotRollBackLatestOrLastGood() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-a", null, "e-gq", "CHAT_COMPLETIONS")
        val fact = PricingFact("m-a", BigDecimal("0.001"), BigDecimal("0.002"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 3000L), fact))
        val newest = sync.price(scope)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), fact))
        assertEquals(newest, sync.price(scope))
        assertEquals(listOf(3000L, 1000L), sync.history(scope, SnapshotKind.PRICE).map { it.observedAt })
    }

    // ---- reviewer 8 項回歸（B2／B3／B6／H2／H3／H5／M4／M6） ----

    @Test
    fun b2crossProviderSourceIsRejected() = runBlocking {
        val scope = PricingScope("openrouter", "a", null, "m1", null, "e-or", "CHAT_COMPLETIONS")
        val fact = PricingFact("m1", BigDecimal("0.001"), BigDecimal("0.002"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        // openrouter scope 配 nvidia source 必須拒絕。
        try {
            sync.syncPricing(scope, DedicatedResult.Success(evidence("nvidia-hosted/pricing", 1000L), fact))
            fail("Cross-provider source must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        // nvidia-h provider 只收 nvidia-hosted／，不收 openrouter／。
        val nvScope = PricingScope("nvidia-h", "an", null, "nv-m", null, "e-nv", "CHAT_COMPLETIONS")
        try {
            sync.syncPricingMode(nvScope, DedicatedResult.Success(evidence("openrouter/api/v1/models", 1000L), PricingMode.NO_PER_TOKEN_BILLING))
            fail("Cross-provider source must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        // 同家通過。
        sync.syncPricingMode(nvScope, DedicatedResult.Success(evidence("nvidia-hosted/pricing", 1000L), PricingMode.NO_PER_TOKEN_BILLING))
        assertEquals("UNKNOWN", sync.price(nvScope).latest!!.state)
    }

    @Test
    fun b3quotaScopeKindProjectSlicePersistedAndProjectValidated() = runBlocking {
        // Gemini PER_PROJECT_MODEL：project 必須等於 scope.group。
        val okScope = DedicatedSnapshotSync.accountQuotaScope("gemini", "ag", group = "proj-1", modelId = "gemini-3-flash", endpointId = "e-gm")
        val pool = QuotaPool(
            QuotaScopeKind.PER_PROJECT_MODEL, "gemini-3-flash",
            rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false,
            projectId = "proj-1", quotaSlice = QuotaPoolSlice.STANDARD_INTERACTIVE,
        )
        sync.syncAccountQuota(okScope, DedicatedResult.Success(evidence("gemini/rate-limits", 1000L), pool))
        // CR-011：sliced pool 讀回走 slice overload。
        val latest = sync.quota(okScope, SnapshotKind.ACCOUNT_QUOTA, "STANDARD_INTERACTIVE").latest!!
        assertEquals("PER_PROJECT_MODEL", latest.quotaScopeKind)
        assertEquals("proj-1", latest.quotaProjectId)
        assertEquals("STANDARD_INTERACTIVE", latest.quotaSlice)
        assertEquals(false, latest.isEstimated)
        // project 不符必須拒絕。
        val badScope = DedicatedSnapshotSync.accountQuotaScope("gemini", "ag", group = "proj-2", modelId = "gemini-3-flash", endpointId = "e-gm")
        try {
            sync.syncAccountQuota(badScope, DedicatedResult.Success(evidence("gemini/rate-limits", 2000L), pool))
            fail("Project mismatch must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        // pool 無 project 卻配具名 group 必須拒絕。
        val noProject = pool.copy(projectId = null, quotaSlice = null)
        try {
            sync.syncAccountQuota(okScope, DedicatedResult.Success(evidence("gemini/rate-limits", 3000L), noProject))
            fail("Missing project must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun b6unknownScopeKindMustNotWriteNamedScope() = runBlocking {
        // NVIDIA hosted UNKNOWN 池：全域可寫。
        val global = DedicatedSnapshotSync.accountQuotaScope("openrouter", "a")
        val unknownPool = QuotaPool(
            QuotaScopeKind.UNKNOWN, null, provenance = DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX, isGroundTruth = false,
        )
        // openrouter 全域＋UNKNOWN pool：需用 openrouter source（B2 相容）。
        sync.syncAccountQuota(global, DedicatedResult.Success(evidence("openrouter/api/v1/key", 1000L), unknownPool))
        // UNKNOWN 池配具名 model／group 必須拒絕。
        val namedModel = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-a", endpointId = "e-gq")
        val namedPool = unknownPool.copy(modelId = "m-a")
        try {
            sync.syncAccountQuota(namedModel, DedicatedResult.Success(evidence("groq/rate-limits", 2000L), namedPool))
            fail("UNKNOWN with model must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        val namedGroup = DedicatedSnapshotSync.accountQuotaScope("gemini", "ag", group = "proj-1", modelId = "", endpointId = "e-gm")
        try {
            sync.syncAccountQuota(namedGroup, DedicatedResult.Success(evidence("gemini/rate-limits", 2000L), unknownPool))
            fail("UNKNOWN with group must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun h2amdDedicatedDoesNotWritePerTokenPrice() = runBlocking {
        val scope = PricingScope("amd", "am", null, "amd-m", null, "e-amd", "CHAT_COMPLETIONS")
        val entry = AmdAdapter.AmdModelEntry("amd-m", BigDecimal("0.000002"), BigDecimal("0.000006"), null, null, true, null)
        // Shared：per-token 正常落庫。
        val shared = sync.syncAmdPricing(
            scope, DedicatedResult.Success(DedicatedEvidence("amd/v1/models", 1000L, DedicatedProvenance.OFFICIAL_DOC, 200), entry),
            DedicatedProviderType.AMD_SHARED,
        )
        assertEquals("PAID", shared.latest!!.state)
        assertEquals("USD_PER_1K_TOKENS", shared.latest!!.priceUnit)
        // Dedicated：同樣 entry 不寫 per-token，一律 UNKNOWN、priceUnit null、lastGood 不動。
        val scope2 = PricingScope("amd", "am", null, "amd-d", null, "e-amd", "CHAT_COMPLETIONS")
        val entry2 = entry.copy(id = "amd-d")
        val before = sync.price(scope2).lastGood
        val dedicated = sync.syncAmdPricing(
            scope2, DedicatedResult.Success(DedicatedEvidence("amd/v1/models", 2000L, DedicatedProvenance.OFFICIAL_DOC, 200), entry2),
            DedicatedProviderType.AMD_DEDICATED,
        )
        assertEquals("UNKNOWN", dedicated.latest!!.state)
        assertNull(dedicated.latest!!.priceUnit)
        assertEquals(before, dedicated.lastGood)
    }

    @Test
    fun h3estimatedFlagAndRemainingSemantics() = runBlocking {
        // OpenRouter key quota：exempt 即 Estimated。
        val scope = DedicatedSnapshotSync.tokenQuotaScope("openrouter", "a", "c")
        sync.syncOpenRouterKeyQuota(
            scope, "c",
            DedicatedResult.Success(
                evidence("openrouter/api/v1/key", 1000L),
                OpenRouterFreeAdapter.KeyQuotaFacts(10L, 100L, 90L, true, true),
            ),
        )
        val keyLatest = sync.quota(scope, SnapshotKind.TOKEN_QUOTA).latest!!
        assertEquals(true, keyLatest.isEstimated)
        assertEquals(90L, keyLatest.remainingRaw)
        // 上界 pool 即 Estimated；精確 pool 為 false。
        val upperScope = DedicatedSnapshotSync.accountQuotaScope("openrouter", "a")
        val upper = QuotaPool(
            QuotaScopeKind.PLATFORM_GLOBAL, null,
            rpm = QuotaDimension(value = 40L, state = dev.modelatlas.core.dedicated.QuotaValueState.KNOWN, upperBound = true, appliesToMostModels = true, requiresLogin = true),
            provenance = DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX, isGroundTruth = false,
        )
        sync.syncAccountQuota(upperScope, DedicatedResult.Success(evidence("openrouter/api/v1/key", 2000L), upper))
        assertEquals(true, sync.quota(upperScope, SnapshotKind.ACCOUNT_QUOTA).latest!!.isEstimated)
        val preciseScope = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-a", endpointId = "e-gq")
        val precise = QuotaPool(
            QuotaScopeKind.PER_ORG_MODEL, "m-a",
            rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false,
        )
        sync.syncAccountQuota(preciseScope, DedicatedResult.Success(evidence("groq/rate-limits", 3000L), precise))
        assertEquals(false, sync.quota(preciseScope, SnapshotKind.ACCOUNT_QUOTA).latest!!.isEstimated)
        // remaining null→UNKNOWN（H3 語意）。
        val scope2 = DedicatedSnapshotSync.tokenQuotaScope("openrouter", "a", "c2")
        sync.syncOpenRouterKeyQuota(
            scope2, "c2",
            DedicatedResult.Success(
                evidence("openrouter/api/v1/key", 4000L),
                OpenRouterFreeAdapter.KeyQuotaFacts(null, 100L, null, true, null),
            ),
        )
        assertEquals("UNKNOWN", sync.quota(scope2, SnapshotKind.TOKEN_QUOTA).latest!!.state)
    }

    @Test
    fun h5duplicateAcrossCallsIsDeduped() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-dedup", null, "e-gq", "CHAT_COMPLETIONS")
        val fact = PricingFact("m-dedup", BigDecimal("0.001"), BigDecimal("0.002"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC)
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), fact))
        assertEquals(1, sync.history(scope, SnapshotKind.PRICE).size)
        // 同 observedAt＋同 state＋同 status 重放即去重，不增 history。
        sync.syncPricing(scope, DedicatedResult.Success(evidence("groq/models", 1000L), fact))
        assertEquals(1, sync.history(scope, SnapshotKind.PRICE).size)
        // quota 同理。
        val qScope = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-qd", endpointId = "e-gq")
        val pool = QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-qd", rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false)
        sync.syncAccountQuota(qScope, DedicatedResult.Success(evidence("groq/rate-limits", 2000L), pool))
        assertEquals(1, sync.history(qScope, SnapshotKind.ACCOUNT_QUOTA).size)
        sync.syncAccountQuota(qScope, DedicatedResult.Success(evidence("groq/rate-limits", 2000L), pool))
        assertEquals(1, sync.history(qScope, SnapshotKind.ACCOUNT_QUOTA).size)
    }

    @Test
    fun m4unsupportedAndFailureHaveNoSnapshotRow() = runBlocking {
        val scope = PricingScope("groq", "aq", null, "m-unsup", null, "e-gq", "CHAT_COMPLETIONS")
        val priceCountBefore = count("price_snapshots")
        sync.syncPricing(scope, DedicatedResult.Unsupported(evidence("groq/models", 1000L), "no cap"))
        sync.syncPricing(scope, DedicatedResult.Failure(DedicatedStatus.HTTP_UNAUTHORIZED_401, evidence("groq/models", 2000L, 401)))
        // M4：兩次皆無 snapshot 列，fact 有 snapshotId=null，latest 推進但 lastGood 為 null。
        assertEquals(priceCountBefore, count("price_snapshots"))
        val stored = sync.price(scope)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertNull(stored.latest!!.snapshotId)
        assertNull(stored.lastGood)
        assertEquals(2, sync.history(scope, SnapshotKind.PRICE).size)
        // quota 同理。
        val qScope = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-qu", endpointId = "e-gq")
        val quotaCountBefore = count("quota_snapshots")
        sync.syncAccountQuota(qScope, DedicatedResult.Unsupported(evidence("groq/rate-limits", 3000L), "no cap"))
        assertEquals(quotaCountBefore, count("quota_snapshots"))
        assertNull(sync.quota(qScope, SnapshotKind.ACCOUNT_QUOTA).latest!!.snapshotId)
    }

    @Test
    fun m6batchPoolsPersistWithoutSharingScope() = runBlocking {
        // M6 鎖域縮小後批次仍正確：per-model 不合併、Failure 隔離。
        val a = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-m6a", endpointId = "e-gq")
        val b = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-m6b", endpointId = "e-gq")
        val poolA = QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-m6a", rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false)
        sync.syncQuotaPools(
            listOf(
                DedicatedQuotaEntry(a, null, DedicatedResult.Success(evidence("groq/rate-limits", 1000L), poolA)),
                DedicatedQuotaEntry(b, null, DedicatedResult.Failure(DedicatedStatus.HTTP_UNAUTHORIZED_401, evidence("groq/rate-limits", 1000L, 401))),
            ),
        )
        assertEquals("QUOTA_AVAILABLE", sync.quota(a, SnapshotKind.ACCOUNT_QUOTA).latest!!.state)
        assertEquals("PER_ORG_MODEL", sync.quota(a, SnapshotKind.ACCOUNT_QUOTA).latest!!.quotaScopeKind)
        assertEquals("UNKNOWN", sync.quota(b, SnapshotKind.ACCOUNT_QUOTA).latest!!.state)
        // 同 scope＋同維度重複批次必須拒絕（B3 去重鍵含 slice）。
        try {
            sync.syncQuotaPools(
                listOf(
                    DedicatedQuotaEntry(a, null, DedicatedResult.Success(evidence("groq/rate-limits", 2000L), poolA)),
                    DedicatedQuotaEntry(a, null, DedicatedResult.Success(evidence("groq/rate-limits", 2000L), poolA)),
                ),
            )
            fail("Duplicate batch entries must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun evidence(source: String, time: Long, httpStatus: Int = 200) = DedicatedEvidence(
        source, time, DedicatedProvenance.OFFICIAL_DOC, httpStatus,
    )

    private fun decimal(expected: String, actual: java.math.BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual!!))
    }

    private fun count(table: String): Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }
}
