package dev.modelatlas.app.ui

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.CanonicalStore
import dev.modelatlas.app.data.DedicatedQuotaEntry
import dev.modelatlas.app.data.DedicatedSnapshotSync
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.ExternalObservationEntity
import dev.modelatlas.app.data.ExternalStore
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.MyModelsStore
import dev.modelatlas.app.data.NewApiSnapshotStore
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.app.data.SnapshotKind
import dev.modelatlas.app.data.observationIdOf
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.PricingFact
import dev.modelatlas.core.dedicated.PricingMode
import dev.modelatlas.core.dedicated.QuotaDimension
import dev.modelatlas.core.dedicated.QuotaPool
import dev.modelatlas.core.dedicated.QuotaScopeKind
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.PricingPolicy
import dev.modelatlas.core.domain.PricingScope
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MYM-004 §23.3 bundle evidence 面（D-008／D-010；三分離 Contract §23.4；PRODUCT_SPEC §10）。
 *
 * 真 Room（in-memory）＋真 store／真 repository（CanonicalStore／MyModelsStore／
 * ExternalStore／NewApiSnapshotStore／DedicatedSnapshotSync／AtlasRepository）；不連線、
 * 不帶 secret；external 觀測走既有 ExternalStore 寫入＋讀取。前 7 測試鎖 evidence
 * 語意不變式；後 5 測試鎖 bundle API（`AtlasRepository.enrichMyModelBundle`）evidence 面：
 * - 缺來源即 Unknown：無 pricing／無 perf／無 external 時各面 No data（空／null），
 *   不補 0、不補 Free、不冒充 VERIFIED；UNKNOWN confidence 保存原樣。
 * - bundle freePriceState：只讀既有 snapshot heads（PRICE／DEDICATED_PRICE，official
 *   維度）已派生 state；無候選 null、分歧 UNKNOWN、group 提供時精確比對；不補 Free。
 * - providerPerformanceFetched 恆 false（Unknown 不冒充；本地 samples／external
 *   觀測不冒充 provider-wide perf，X-005）。
 * - externalObservationCount 只讀挂接：無 fetcher 不觸網（externalRefreshAttempted
 *   false／externalRefreshOk null）；數為既有外部槽 TTL 內新鮮真值；bundle 零寫入
 *   外部槽。
 * - stepFailures 靜態標籤（QUOTA 等，不含 URL／server body／secret）；支援家族
 *   UNSUPPORTED 為靜默降級不記失敗；membership／samples／telemetry 零寫入；
 *   輸入驗證 fail-closed。
 * - 三分離（§23.4）：My Uptime 樣本（samples）／Provider 端結構化觀測
 *  （snapshot_facts，official provenance）／External Availability（external_observations）
 *   各自獨立槽，寫入任一槽零污染其他兩槽；evidenceId 各自串回自己來源，不互相冒充、
 *   不互相平均。User Cancel 樣本 outcome 逐列保存、可辨識、不被吸收進任何槽；
 *   My Uptime 分子／分母聚合本身是未來 My Uptime slice（D-009），本檔只鎖 evidence 基礎。
 * - nominal≠effective：標價非 0 但免費機制（fixed request）未知 → production 派生
 *   UNKNOWN（不硬判 PAID）；FREE 僅在完整零價 evidence 下成立；manual 槽不冒充
 *   official 槽；nominal 值不被 zero 化。
 * - 多 pool 不平均：quota 多 pool 各自 scope head 呈現；total 未知即 UNKNOWN，
 *   不推進 last-good、不產生假百分比材料（pool raws 全 null）。
 *
 * Provider perf（New API perf-metrics）fetch path 尚不存在（survey 無路徑）；
 * perf 面證據由 provider 端結構化觀測槽承載，缺來源一律 Unknown。store 層 membership
 * 寫入不觸發任何同步／external 寫入；VM 自動 enrichment 觸發語意由既有
 * MyModelsEnrichmentTest 鎖定。樣本 seed 只代表已持久化的真實 request telemetry
 * （fixture 僅存於 androidTest，不當產品資料）。dedicated pricing／quota 同步只走
 * UNSUPPORTED／local 官方表路徑（gemini 兩維 UNSUPPORTED、groq 官方 per-model 表），
 * 不發任何網路請求；bundle 測試不注入 fetcher（無 fetcher 不觸網）。
 */
@RunWith(AndroidJUnit4::class)
class MyModelsBundleEvidenceTest {

    private lateinit var context: Context
    private val open = mutableListOf<AtlasDatabase>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    @Test
    fun missingExternalSourceYieldsNoDataForAddedMyModel() = runBlocking {
        val db = memoryDb()
        val external = ExternalStore(db)
        val myModels = MyModelsStore(db)
        val canonical = CanonicalStore(db).saveCanonical("GLM-5.3 Flash", nowMillis = 1_000L)

        // 缺 external 來源：No data（空），UNKNOWN 不猜、不補假觀測。
        assertTrue(external.observationsForCanonical(canonical.canonicalModelId, nowMillis = 5_000L).isEmpty())
        assertNull(external.latestObservationOf("lmspeed", "glm-5-3-flash"))
        assertTrue(external.mappingsForCanonical(canonical.canonicalModelId).isEmpty())

        // 明確加入 My Models：缺 external 來源不阻擋加入（D-008）；membership 成立
        // 且不補任何假 external 可用性（仍 No data，不補 0／Free）。
        assertTrue(myModels.addToMyModels("p1", "glm-5-3-flash", source = "USER", nowMillis = 2_000L))
        assertTrue(myModels.isMyModel("p1", "glm-5-3-flash"))
        assertTrue(external.observationsForCanonical(canonical.canonicalModelId, nowMillis = 5_000L).isEmpty())
        assertEquals(0, db.externalObservationDao().count())
        assertEquals(0, db.externalMappingDao().count())
    }

    @Test
    fun missingPricingAndPerfEvidenceLeavesReadsUnknown() = runBlocking {
        val db = memoryDb()
        db.providerDao().insert(ProviderEntity("p1", "P p1"))
        db.accountDao().insert(AccountEntity("aq", "p1"))
        val newApi = NewApiSnapshotStore(db)
        val dedicated = DedicatedSnapshotSync(db)
        val priceScope = PricingScope("p1", null, null, "m1", null, "e-p1", "CHAT_COMPLETIONS")
        val quotaScope = DedicatedSnapshotSync.accountQuotaScope("p1", "aq")

        // 無 pricing 來源：official／manual 槽皆 No data，不推導 PAID／FREE。
        assertNull(newApi.price(priceScope).latest)
        assertNull(newApi.price(priceScope).lastGood)
        assertNull(newApi.price(priceScope, manual = true).latest)
        assertNull(newApi.price(priceScope, manual = true).lastGood)

        // 無 provider perf／quota 來源（dedicated 槽）：讀取皆 No data，不補假值。
        assertNull(dedicated.price(priceScope).latest)
        assertNull(dedicated.price(priceScope).lastGood)
        assertNull(newApi.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA).latest)
        assertNull(newApi.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA).lastGood)
        assertNull(dedicated.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA).latest)
        assertNull(dedicated.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA).lastGood)

        // Unknown 不補 0／Free：無任何 state／head 寫入。
        assertEquals(0L, countRows(db, "snapshot_heads"))
        assertEquals(0L, countRows(db, "snapshot_facts"))
        assertEquals(0L, countRows(db, "price_snapshots"))
        assertEquals(0L, countRows(db, "quota_snapshots"))
    }

    @Test
    fun canonicalAliasAndExternalMappingStaySeparateSlots() = runBlocking {
        val db = memoryDb()
        val canonicalStore = CanonicalStore(db)
        val external = ExternalStore(db)
        val canonical = canonicalStore.saveCanonical("GLM-5.3 Flash", nowMillis = 1_000L)

        // alias attach：未驗證映射不得標 VERIFIED（verified=false 保存原樣）。
        val alias = canonicalStore.saveAlias(
            providerId = "p1",
            providerModelId = "glm-5-3-flash",
            canonicalModelId = canonical.canonicalModelId,
            source = MappingSource.METADATA.name,
            confidence = MappingConfidence.MEDIUM.name,
            verified = false,
            observedAt = 2_000L,
        )
        assertFalse(alias.verified)
        assertEquals(MappingSource.METADATA.name, alias.source)
        assertEquals(0, db.externalMappingDao().count())

        // external 映射走 ExternalStore：UNKNOWN confidence 保留原樣、不冒充 VERIFIED、
        // 不冒充 alias 來源（槽分離）。
        external.saveMapping("ext-glm", canonical.canonicalModelId, "lmspeed", MappingConfidence.UNKNOWN.name)
        val resolved = external.resolve("ext-glm", "lmspeed")!!
        assertEquals(MappingConfidence.UNKNOWN.name, resolved.confidence)
        assertFalse(resolved.verified)
        val mappings = external.mappingsForCanonical(canonical.canonicalModelId)
        assertEquals(1, mappings.size)
        assertEquals("lmspeed", mappings.single().source)

        // external 寫入零污染 alias 槽：alias 列原樣、同 canonical 恰一列 alias。
        val aliasAfter = db.providerModelAliasDao().get("p1", "glm-5-3-flash")!!
        assertFalse(aliasAfter.verified)
        assertEquals(MappingSource.METADATA.name, aliasAfter.source)
        assertEquals(1, db.providerModelAliasDao().byCanonical(canonical.canonicalModelId).size)

        // 弱 evidence 不得宣稱已驗證：manual＋LOW＋verified=true fail-closed。
        try {
            canonicalStore.saveAlias(
                providerId = "p1",
                providerModelId = "glm-5-3-flash-other",
                canonicalModelId = canonical.canonicalModelId,
                source = MappingSource.USER_OVERRIDE.name,
                confidence = MappingConfidence.LOW.name,
                verified = true,
            )
            fail("Weak evidence must not be marked verified")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun myUptimeTelemetryProviderFactsAndExternalObservationsStaySeparate() = runBlocking {
        val db = memoryDb()
        val myModels = MyModelsStore(db)
        val external = ExternalStore(db)
        val sync = DedicatedSnapshotSync(db)
        db.providerDao().insert(ProviderEntity("groq", "Groq fixture"))
        db.accountDao().insert(AccountEntity("aq", "groq"))
        assertTrue(myModels.addToMyModels("groq", "m-a", source = "USER", nowMillis = 1_000L))

        // My Uptime 樣本：同 scope 真實 request telemetry（fixture seed）。
        db.sampleDao().upsert(sample("s-success", "groq", "m-a", "SUCCESS", completedAt = 1_500L))

        // Provider 端結構化觀測（official provenance）：quota pool 落 snapshot 槽。
        val scope = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-a")
        sync.syncQuotaPools(
            listOf(
                DedicatedQuotaEntry(
                    scope, null,
                    DedicatedResult.Success(
                        DedicatedEvidence("groq/rate-limits", 1_000L, DedicatedProvenance.OFFICIAL_DOC),
                        QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-a", rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC),
                    ),
                ),
            ),
        )

        // External Availability：external 觀測走既有 ExternalStore 寫入（真槽）。
        val canonical = CanonicalStore(db).saveCanonical("m-a", nowMillis = 1_200L)
        val observation = externalObservation("lmspeed", "lm-a", fetchedAt = 1_800L, canonicalModelId = canonical.canonicalModelId)
        external.saveObservation(observation)

        // 1) My Uptime 樣本原樣：不因 provider／external 寫入被覆蓋或合併。
        val success = db.sampleDao().getById("s-success")!!
        assertEquals("SUCCESS", success.outcome)
        assertEquals("MANUAL_TEST", success.source)
        assertEquals("m-a", success.providerModelId)
        assertEquals(1L, countRows(db, "samples"))

        // 2) Provider 端結構化 fact：state 由官方 pool evidence 派生；evidenceId
        //    串回 provider 官方來源，非 sample id、非 external observation id。
        val stored = sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals("QUOTA_AVAILABLE", stored.latest!!.state)
        val evidence = db.evidenceDao().getById(stored.latest!!.evidenceId)!!
        assertEquals("groq/rate-limits", evidence.source)
        assertEquals("groq", evidence.providerId)
        assertTrue(stored.latest!!.evidenceId != "s-success")
        assertTrue(stored.latest!!.evidenceId != observation.observationId)
        assertNotNull(stored.lastGood)

        // 3) External observation：UNKNOWN confidence 讀回原樣（external 槽）。
        val rows = external.observationsForCanonical(canonical.canonicalModelId, nowMillis = 60_000L)
        assertEquals(1, rows.size)
        assertEquals(MappingConfidence.UNKNOWN.name, rows.single().confidence)

        // 4) 零跨槽污染：external 槽只有 1 列、snapshot 槽只有 provider fact、
        //    evidence 只有 provider evidence；任何槽都沒把其他兩槽平均或覆蓋。
        assertEquals(1, db.externalObservationDao().count())
        assertEquals(0, db.externalMappingDao().count())
        assertEquals(1L, countRows(db, "snapshot_facts"))
        assertEquals(1, db.snapshotFactDao().headsByKind(SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name).size)
        assertEquals(1L, countRows(db, "evidence"))
        assertEquals(0L, countRows(db, "provider_events"))
        assertEquals(0L, countRows(db, "price_events"))
        assertEquals(0L, countRows(db, "quota_events"))

        // evidence 寫入不移除 membership（D-008）。
        assertTrue(myModels.isMyModel("groq", "m-a"))
    }

    @Test
    fun userCancelSampleStaysSeparateFromProviderAndExternalEvidence() = runBlocking {
        val db = memoryDb()
        val external = ExternalStore(db)
        val sync = DedicatedSnapshotSync(db)
        db.providerDao().insert(ProviderEntity("groq", "Groq fixture"))
        db.accountDao().insert(AccountEntity("aq", "groq"))

        // My Uptime 樣本（同 scope）：SUCCESS 與 User Cancel 各一列，outcome 逐列保存。
        db.sampleDao().upsert(sample("s-success", "groq", "m-a", "SUCCESS", completedAt = 1_500L))
        db.sampleDao().upsert(sample("s-cancel", "groq", "m-a", "CANCELLED", completedAt = 1_600L))

        // Provider 端官方 quota pool 落 snapshot 槽；external 觀測落 external 槽。
        val scope = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-a")
        sync.syncQuotaPools(
            listOf(
                DedicatedQuotaEntry(
                    scope, null,
                    DedicatedResult.Success(
                        DedicatedEvidence("groq/rate-limits", 1_000L, DedicatedProvenance.OFFICIAL_DOC),
                        QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-a", rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC),
                    ),
                ),
            ),
        )
        external.saveObservation(externalObservation("lmspeed", "lm-a", fetchedAt = 1_800L))

        // User Cancel 樣本不被改寫／刪除／合併：outcome 逐列保存、兩列獨立可辨識。
        val cancelled = db.sampleDao().getById("s-cancel")!!
        assertEquals("CANCELLED", cancelled.outcome)
        assertEquals("MANUAL_TEST", cancelled.source)
        assertEquals("s-cancel", cancelled.id)
        val success = db.sampleDao().getById("s-success")!!
        assertEquals("SUCCESS", success.outcome)
        assertEquals("MANUAL_TEST", success.source)
        assertEquals(2L, countRows(db, "samples"))

        // Provider 端 state 只由官方 pool evidence 派生（QUOTA_AVAILABLE）：不因
        // User Cancel 樣本被拖低／平均；evidenceId 串回 provider 官方來源，不吸收樣本。
        val stored = sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals("QUOTA_AVAILABLE", stored.latest!!.state)
        assertEquals("groq/rate-limits", db.evidenceDao().getById(stored.latest!!.evidenceId)!!.source)
        assertTrue(stored.latest!!.evidenceId != "s-cancel" && stored.latest!!.evidenceId != "s-success")

        // External 觀測 UNKNOWN 原樣；無任何槽把 CANCELLED 樣本吸收成 VERIFIED／可用。
        assertEquals(1, db.externalObservationDao().count())
        assertEquals(MappingConfidence.UNKNOWN.name, external.latestObservationOf("lmspeed", "lm-a")!!.confidence)
        assertEquals(1L, countRows(db, "snapshot_facts"))
        assertEquals(1L, countRows(db, "evidence"))
        assertEquals(0, db.externalMappingDao().count())

        // My Uptime 分子／分母聚合本身是未來 My Uptime slice（D-009；§23.4）；
        // 本檔只鎖 evidence 基礎：outcome 逐列保存、CANCELLED 可辨識、三槽互不合併。
    }

    @Test
    fun storedNominalPriceKeepsUnknownStateUntilFreeEvidenceComplete() = runBlocking {
        val db = memoryDb()
        db.providerDao().insert(ProviderEntity("p1", "P p1"))
        db.endpointDao().insert(
            EndpointEntity("e-p1", "p1", "https://p1.fixture.invalid/v1", "https://p1.fixture.invalid/v1/chat/completions"),
        )
        val newApi = NewApiSnapshotStore(db)
        val unknownMechanism = PricingScope("p1", null, null, "m1", null, "e-p1", "CHAT_COMPLETIONS")

        // 標價非 0 但免費機制（fixed request）未知：manual nominal price 落庫；
        // fact state／confidence 保持 UNKNOWN／LOW（store 層不硬判 PAID），
        // nominal 值不被 zero 化；provenance USER＋evidenceId 保存。
        val id = newApi.saveManualPrice(
            PriceSnapshot(
                scope = unknownMechanism,
                observedAt = 1_000L,
                input = BigDecimal("0.002"),
                output = BigDecimal("0.004"),
                fixedRequest = null,
                fixedRequestKnownAbsent = false,
            ),
        )
        val stored = newApi.price(unknownMechanism, manual = true)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertEquals("LOW", stored.latest!!.confidence)
        assertEquals(id, stored.latest!!.snapshotId)
        val lastGood = stored.lastGood!!
        assertEquals(0, BigDecimal("0.002").compareTo(lastGood.input!!))
        assertEquals(0, BigDecimal("0.004").compareTo(lastGood.output!!))
        assertNull(lastGood.fixedRequest)
        assertFalse(lastGood.fixedRequestKnownAbsent)
        assertEquals("USER", lastGood.source)
        assertEquals(id, lastGood.evidenceId)

        // 真 stored evidence 經 production 派生（PricingPolicy）：免費機制未知 →
        // UNKNOWN，不硬判 PAID／FREE（nominal 非零不冒充已判定）。
        assertEquals(PriceState.UNKNOWN, PricingPolicy.evaluate(lastGood).state)

        // 完整零價 evidence（input/output/fixed 皆 0）：FREE 才成立，且 cache 另計。
        val freeScope = PricingScope("p1", null, null, "m2", null, "e-p1", "CHAT_COMPLETIONS")
        newApi.saveManualPrice(
            PriceSnapshot(
                scope = freeScope,
                observedAt = 2_000L,
                input = BigDecimal.ZERO,
                output = BigDecimal.ZERO,
                fixedRequest = BigDecimal.ZERO,
            ),
        )
        val freeGood = newApi.price(freeScope, manual = true).lastGood!!
        assertEquals(PriceState.FREE_NOW, PricingPolicy.evaluate(freeGood).state)
        assertFalse(PricingPolicy.evaluate(freeGood).cacheCostsExtra)

        // manual 槽不冒充 official 槽：official PRICE head 仍 No data。
        val official = newApi.price(unknownMechanism)
        assertNull(official.latest)
        assertNull(official.lastGood)
    }

    @Test
    fun quotaPoolsStaySeparateAndUnknownTotalStaysUnknown() = runBlocking {
        val db = memoryDb()
        db.providerDao().insert(ProviderEntity("groq", "Groq fixture"))
        db.accountDao().insert(AccountEntity("aq", "groq"))
        val sync = DedicatedSnapshotSync(db)
        val scopeA = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-a")
        val scopeB = DedicatedSnapshotSync.accountQuotaScope("groq", "aq", modelId = "m-b")

        // 兩 pool：m-a 已知 RPM；m-b 全維度未知（total 未知）。
        sync.syncQuotaPools(
            listOf(
                DedicatedQuotaEntry(
                    scopeA, null,
                    DedicatedResult.Success(
                        DedicatedEvidence("groq/rate-limits", 1_000L, DedicatedProvenance.OFFICIAL_DOC),
                        QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-a", rpm = QuotaDimension.known(30L), provenance = DedicatedProvenance.OFFICIAL_DOC),
                    ),
                ),
                DedicatedQuotaEntry(
                    scopeB, null,
                    DedicatedResult.Success(
                        DedicatedEvidence("groq/rate-limits", 1_000L, DedicatedProvenance.OFFICIAL_DOC),
                        QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-b", provenance = DedicatedProvenance.OFFICIAL_DOC),
                    ),
                ),
            ),
        )

        // 各 pool 各自 state 呈現：m-a KNOWN→QUOTA_AVAILABLE；m-b 全未知→UNKNOWN
        // （total 未知不補假值、不畫假百分比）；unknown pool 不推進 last-good。
        val a = sync.quota(scopeA, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals("QUOTA_AVAILABLE", a.latest!!.state)
        assertNotNull(a.lastGood)
        val b = sync.quota(scopeB, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals("UNKNOWN", b.latest!!.state)
        assertNull(b.lastGood)

        // 兩 pool 各自 head／evidenceId：不互相平均、不互相覆蓋（各自 scope 讀取
        // 只回自己的 state）。
        assertTrue(a.latest!!.evidenceId != b.latest!!.evidenceId)
        assertEquals(2, db.snapshotFactDao().headsByKind(SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name).size)

        // 無假百分比材料：pool sync 只派生 state，used／granted raws 全 null。
        assertNull(a.latest!!.remainingRaw)
        assertNull(a.latest!!.usedRaw)
        assertNull(a.latest!!.grantedRaw)
        assertNull(a.latest!!.unlimited)
        assertNull(b.latest!!.remainingRaw)
        assertNull(b.latest!!.usedRaw)
        assertNull(b.latest!!.grantedRaw)
    }

    @Test
    fun bundleFreePriceStateReflectsStoredEvidenceWithoutFabricatedFree() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val sync = DedicatedSnapshotSync(db)
        db.providerDao().insert(ProviderEntity("gemini", "Gemini fixture"))
        db.accountDao().insert(AccountEntity("ag", "gemini"))
        db.endpointDao().insert(
            EndpointEntity("e-gm", "gemini", "https://gemini.fixture.invalid/v1", "https://gemini.fixture.invalid/v1/chat/completions"),
        )

        // 既有 dedicated pricing 路徑（真 store）落一筆 PAID evidence（官方 provenance）；
        // bundle 只讀已派生 state，不重算、不補 Free。
        sync.syncPricing(
            PricingScope("gemini", "ag", null, "m-a", null, "e-gm", "CHAT_COMPLETIONS"),
            DedicatedResult.Success(
                DedicatedEvidence("gemini/pricing", 1_000L, DedicatedProvenance.OFFICIAL_DOC),
                PricingFact("m-a", BigDecimal("0.002"), BigDecimal("0.004"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC),
            ),
        )
        val paid = repo.enrichMyModelBundle(
            "gemini", "m-a", accountId = "ag", endpointId = "e-gm", operation = "CHAT_COMPLETIONS",
        )

        // freePriceState 反映真實 stored evidence（PAID），不補 Free、不補 0；
        // provider perf 恆 false；pricing／quota 對 UNSUPPORTED 家族靜默降級（非失敗）。
        assertEquals("PAID", paid.freePriceState)
        assertFalse(paid.providerPerformanceFetched)
        assertNotNull(paid.pricingSummary)
        assertTrue(paid.pricingSummary!!.pricingUnsupported)
        assertNotNull(paid.quotaSummary)
        assertTrue(paid.quotaSummary!!.quotaUnsupported)
        assertTrue(paid.stepFailures.isEmpty())
        assertTrue(paid.aliasAttached)
        assertEquals(MappingSource.NORMALIZATION.name, paid.aliasSource)
        assertEquals(MappingConfidence.LOW.name, paid.aliasConfidence)
        assertFalse(db.providerModelAliasDao().get("gemini", "m-a")!!.verified)

        // 無候選（無 pricing evidence）：freePriceState null（Unknown，不補 0／Free）。
        val none = repo.enrichMyModelBundle("gemini", "m-none", accountId = "ag")
        assertNull(none.freePriceState)
        assertTrue(none.stepFailures.isEmpty())

        // Unknown state evidence：保持 UNKNOWN，不冒充 Free／PAID。
        sync.syncPricing(
            PricingScope("gemini", "ag", null, "m-unk", null, "e-gm", "CHAT_COMPLETIONS"),
            DedicatedResult.Success(
                DedicatedEvidence("gemini/pricing", 2_000L, DedicatedProvenance.OFFICIAL_DOC),
                PricingFact("m-unk", null, null, null, PricingMode.UNKNOWN, DedicatedProvenance.OFFICIAL_DOC),
            ),
        )
        val unk = repo.enrichMyModelBundle(
            "gemini", "m-unk", accountId = "ag", endpointId = "e-gm", operation = "CHAT_COMPLETIONS",
        )
        assertEquals("UNKNOWN", unk.freePriceState)
        assertTrue(unk.stepFailures.isEmpty())
    }

    @Test
    fun bundleFreePriceStateUnknownOnDisagreementAndExactGroupFilter() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val sync = DedicatedSnapshotSync(db)
        db.providerDao().insert(ProviderEntity("gemini", "Gemini fixture"))
        db.accountDao().insert(AccountEntity("ag", "gemini"))
        db.endpointDao().insert(
            EndpointEntity("e-gm", "gemini", "https://gemini.fixture.invalid/v1", "https://gemini.fixture.invalid/v1/chat/completions"),
        )

        // 同 provider／account／model、不同 group 兩筆真實 evidence：PAID 與 FREE_NOW。
        sync.syncPricing(
            PricingScope("gemini", "ag", null, "m-a", "free-pool", "e-gm", "CHAT_COMPLETIONS"),
            DedicatedResult.Success(
                DedicatedEvidence("gemini/pricing", 1_000L, DedicatedProvenance.OFFICIAL_DOC),
                PricingFact("m-a", BigDecimal("0.002"), BigDecimal("0.004"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC),
            ),
        )
        sync.syncPricing(
            PricingScope("gemini", "ag", null, "m-a", "vip", "e-gm", "CHAT_COMPLETIONS"),
            DedicatedResult.Success(
                DedicatedEvidence("gemini/pricing", 2_000L, DedicatedProvenance.OFFICIAL_DOC),
                PricingFact("m-a", BigDecimal.ZERO, BigDecimal.ZERO, null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC),
            ),
        )
        db.endpointDao().insert(
            EndpointEntity("e-gm2", "gemini", "https://gemini2.fixture.invalid/v1", "https://gemini2.fixture.invalid/v1/chat/completions"),
        )
        // 同 group 的另一 endpoint 價格不同；不能拿來製造單一 endpoint 的分歧。
        sync.syncPricing(
            PricingScope("gemini", "ag", null, "m-a", "vip", "e-gm2", "CHAT_COMPLETIONS"),
            DedicatedResult.Success(
                DedicatedEvidence("gemini/pricing", 3_000L, DedicatedProvenance.OFFICIAL_DOC),
                PricingFact("m-a", BigDecimal("0.002"), BigDecimal("0.004"), null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC),
            ),
        )

        // group 未知（null）：跨 group 候選不是 exact-scope evidence，一律 null
        //（Unknown，不拿別 group 的 state 冒充；HIGH-2 修正）。
        assertNull(repo.enrichMyModelBundle("gemini", "m-a", accountId = "ag").freePriceState)
        // endpoint 未知也不能選其中一個。具名 endpoint/group 取各自的真 state；
        // 同七維 scope 的兩種 kind 分歧由 NewApiEndpointOperationFreeTest 驗證。
        assertNull(repo.enrichMyModelBundle("gemini", "m-a", accountId = "ag", group = "vip").freePriceState)
        assertEquals("FREE_NOW", repo.enrichMyModelBundle(
            "gemini", "m-a", accountId = "ag", group = "vip", endpointId = "e-gm", operation = "CHAT_COMPLETIONS",
        ).freePriceState)
        assertEquals("PAID", repo.enrichMyModelBundle(
            "gemini", "m-a", accountId = "ag", group = "vip", endpointId = "e-gm2", operation = "CHAT_COMPLETIONS",
        ).freePriceState)
        assertEquals("PAID", repo.enrichMyModelBundle(
            "gemini", "m-a", accountId = "ag", group = "free-pool", endpointId = "e-gm", operation = "CHAT_COMPLETIONS",
        ).freePriceState)
    }

    @Test
    fun bundleProviderPerformanceFetchedStaysFalseEverywhere() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        db.providerDao().insert(ProviderEntity("gemini", "Gemini fixture"))
        db.accountDao().insert(AccountEntity("ag", "gemini"))
        db.providerDao().insert(ProviderEntity("groq", "Groq fixture"))
        db.accountDao().insert(AccountEntity("aq", "groq"))

        // 全 repo 無 provider-wide performance fetch 路徑：三種 provider（支援路由／
        // 無路由／支援但 quota 失敗）皆恆 false（Unknown 不冒充；本地 samples／
        // external 觀測不冒充 provider-wide perf）。
        val supported = repo.enrichMyModelBundle("gemini", "m-a", accountId = "ag")
        assertFalse(supported.providerPerformanceFetched)
        val unrouted = repo.enrichMyModelBundle("p1", "m-a")
        assertFalse(unrouted.providerPerformanceFetched)
        // 無路由：pricing／quota 未嘗試（null），無 PRICING／QUOTA 失敗標籤。
        assertNull(unrouted.pricingSummary)
        assertNull(unrouted.quotaSummary)
        assertTrue(unrouted.stepFailures.isEmpty())
        val quotaFailed = repo.enrichMyModelBundle("groq", "m-a", accountId = "aq")
        assertFalse(quotaFailed.providerPerformanceFetched)
        assertTrue(quotaFailed.stepFailures.contains("QUOTA"))
    }

    @Test
    fun bundleExternalObservationCountIsReadOnlyAttachWithoutFetcher() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val external = ExternalStore(db)
        db.providerDao().insert(ProviderEntity("gemini", "Gemini fixture"))
        db.accountDao().insert(AccountEntity("ag", "gemini"))
        val canonical = CanonicalStore(db).saveCanonical("m-a", nowMillis = 1_000L)
        val now = System.currentTimeMillis()

        // 既有外部槽真值：一筆 TTL 內新鮮＋一筆已過期（external 觀測走既有
        // ExternalStore 寫入；未偽造產品資料）。
        val fresh = externalObservation("lmspeed", "lm-a", fetchedAt = now, canonicalModelId = canonical.canonicalModelId, ttlMillis = 86_400_000L)
        val expired = externalObservation("lmspeed", "lm-a", fetchedAt = now - 10_000L, canonicalModelId = canonical.canonicalModelId, ttlMillis = 5_000L)
        external.saveObservation(fresh)
        external.saveObservation(expired)

        val result = repo.enrichMyModelBundle("gemini", "m-a", accountId = "ag")

        // 無 fetcher 不觸網：externalRefreshAttempted=false、externalRefreshOk=null
        // （靜默跳過，本函式不造網路層）。
        assertFalse(result.externalRefreshAttempted)
        assertNull(result.externalRefreshOk)
        // attach 只讀挂接：resolved canonical 的 TTL 內新鮮觀測數（過期排除）。
        assertEquals(1, result.externalObservationCount)
        assertEquals(canonical.canonicalModelId, result.canonicalModelId)
        // 零寫入外部槽：bundle 未新增／改寫任何 external 列（唯讀挂接）。
        assertEquals(2, db.externalObservationDao().count())
        assertEquals(fresh, db.externalObservationDao().getById(fresh.observationId))
        assertEquals(expired, db.externalObservationDao().getById(expired.observationId))
        // membership／samples／telemetry 零寫入。
        assertEquals(0, db.myModelsDao().all().size)
        assertEquals(0L, countRows(db, "samples"))
        assertEquals(0L, countRows(db, "snapshot_facts"))
    }

    @Test
    fun bundleStepFailuresAreStaticLabelsAndMembershipUntouched() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val myModels = MyModelsStore(db)
        db.providerDao().insert(ProviderEntity("groq", "Groq fixture"))
        db.accountDao().insert(AccountEntity("aq", "groq"))
        assertTrue(myModels.addToMyModels("groq", "m-a", source = "USER", nowMillis = 1_000L))

        // groq（PER_MODEL quota 路由）無 offerings：quota 步驟自然失敗 → 靜態標籤
        // "QUOTA"、quotaSummary null；pricing（UNSUPPORTED）靜默降級不記失敗；
        // fail-open 不拋給呼叫端、不刪 membership。
        val result = repo.enrichMyModelBundle("groq", "m-a", accountId = "aq")
        assertEquals(listOf("QUOTA"), result.stepFailures)
        assertNotNull(result.pricingSummary)
        assertTrue(result.pricingSummary!!.pricingUnsupported)
        assertNull(result.quotaSummary)
        assertFalse(result.providerPerformanceFetched)
        assertTrue(result.aliasAttached)
        assertEquals(MappingSource.NORMALIZATION.name, result.aliasSource)
        assertEquals(MappingConfidence.LOW.name, result.aliasConfidence)
        // membership 零寫入：bundle 前後 my_models 恰一列、membership 仍成立。
        assertEquals(1, db.myModelsDao().all().size)
        assertTrue(myModels.isMyModel("groq", "m-a"))
        // 無候選 FREE：推不出即 null（Unknown，不補 0／Free）。
        assertNull(result.freePriceState)

        // 輸入驗證 fail-closed（與既有 sync 路徑同規則）：空白 id／非法 group 格式
        // 即 require 拒絕，membership 不受影響。
        try {
            repo.enrichMyModelBundle("", "m-a")
            fail("Blank provider must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            repo.enrichMyModelBundle("groq", "   ")
            fail("Blank model must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            repo.enrichMyModelBundle("groq", "m-a", group = "a::b")
            fail("Concatenated group must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1, db.myModelsDao().all().size)
        assertTrue(myModels.isMyModel("groq", "m-a"))
    }

    // ===== VM 自動觸發（§23.3「加入即自動」）：真 App＋真 VM，不手動調任何 repository 方法

    @Test
    fun vmSaveConnectionAndAddAutoEnrichBundleAlias() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        val vmStore = ViewModelStore()
        val vm = onMainGet {
            ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
        val ownedProviders = mutableListOf<String>()
        val ownedRefs = mutableListOf<String>()
        try {
            val name = "MYM-004 closeout VM ${UUID.randomUUID()}"
            onMain { vm.saveConnection(name, BASE_URL, MODEL_SAVE, FIXTURE_SECRET) }
            val item = awaitUi(vm) { state ->
                !state.saving && state.connections.any { it.name == name }
            }.connections.first { it.name == name }
            ownedProviders += item.providerId
            ownedRefs += item.credentialId

            // saveConnection 成功即自動入會，且 bundle 已自動跑完：alias 落庫。
            await { vm.myModelKeys.first { it.contains(MyModelKey(item.providerId, MODEL_SAVE)) } }
            val saved = awaitAlias(app, item.providerId, MODEL_SAVE)
            assertEquals(MappingSource.NORMALIZATION.name, saved.source)
            assertEquals(MappingConfidence.LOW.name, saved.confidence)
            assertFalse(saved.verified)

            // ADD 成功同樣自動走 maybeEnrich→bundle：第二個模型 alias 亦自動落地。
            onMain { vm.addProviderModel(item.providerId, MODEL_ADD) }
            await { vm.providerModelAction.first { it.status == "SUCCESS" } }
            await { vm.myModelKeys.first { it.contains(MyModelKey(item.providerId, MODEL_ADD)) } }
            val added = awaitAlias(app, item.providerId, MODEL_ADD)
            assertEquals(MappingSource.NORMALIZATION.name, added.source)
            assertEquals(MappingConfidence.LOW.name, added.confidence)
            assertFalse(added.verified)

            // 舊 provider-only 路徑語意鎖定：blank／未知 provider 皆靜默 false，不寫 membership。
            assertFalse(runBlocking { vm.maybeEnrichMyModel("   ") })
            assertFalse(runBlocking { vm.maybeEnrichMyModel("mym004-closeout-ghost-provider") })
        } finally {
            onMain { vmStore.clear() }
            for (ref in ownedRefs) runCatching { app.vault.remove(ref) }
            deleteOwnRows(app, ownedProviders)
        }
    }

    // ===== VM 層 credential fail-closed→false：非法 ref 不進 bundle，membership 不動

    @Test
    fun vmMaybeEnrichReturnsFalseOnUnsafeCredentialRef() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        val vmStore = ViewModelStore()
        val vm = onMainGet {
            ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
        val pid = "mym004-closeout-cred-scope"
        val model = "mym004-closeout-cred-model"
        val myModels = MyModelsStore(app.database)
        runBlocking { assertTrue(myModels.addToMyModels(pid, model, source = "USER", nowMillis = 1_000L)) }
        try {
            // bundle 入口 fail-closed 拋錯；VM 層接住轉 false（不靜默吞成 QUOTA 失敗）。
            assertFalse(runBlocking { vm.maybeEnrichMyModel(pid, null, "   ", null, model) })
            assertFalse(runBlocking { vm.maybeEnrichMyModel(pid, null, "sk-fixture-raw-secret", null, model) })
            runBlocking {
                assertTrue(myModels.isMyModel(pid, model))
                assertNull(app.database.providerModelAliasDao().get(pid, model))
            }
        } finally {
            onMain { vmStore.clear() }
            deleteOwnRows(app, listOf(pid))
        }
    }

    // ===== VM harness（與 MyModelsFoundationTest 同形；自包含，不碰既有 setUp／tearDown） =====

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private fun <T> onMainGet(action: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = action()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun <T> await(timeoutMillis: Long = TIMEOUT_MS, block: suspend () -> T): T {
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { block() } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    onMain { }
                }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    private fun awaitUi(vm: AtlasViewModel, predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { vm.state.first(predicate) }

    private fun awaitAlias(app: AtlasApplication, providerId: String, modelId: String) =
        await {
            var row = app.database.providerModelAliasDao().get(providerId, modelId)
            while (row == null) {
                kotlinx.coroutines.yield()
                row = app.database.providerModelAliasDao().get(providerId, modelId)
            }
            row
        }

    private fun deleteOwnRows(app: AtlasApplication, ids: List<String>) {
        if (ids.isEmpty()) return
        val list = ids.joinToString(",") { "?" }
        val args = ids.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM evidence WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_notices WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_events WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", args)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", args)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://mym004-closeout.fixture.invalid/v1"
        const val MODEL_SAVE = "mym004-closeout-vm-model"
        const val MODEL_ADD = "mym004-closeout-vm-added-model"
        const val FIXTURE_SECRET = "mym004-closeout-fixture-secret-never-persisted"
    }

    // ===== harness =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    /** 已持久化真實 request telemetry 的 seed（fixture 僅存於 androidTest，不當產品資料）。 */
    private fun sample(id: String, providerId: String, modelId: String, outcome: String, completedAt: Long) =
        SampleEntity(
            id = id,
            providerId = providerId,
            accountId = "aq",
            safeCredentialId = "cred-fixture",
            endpointId = "e-gq",
            providerModelId = modelId,
            source = "MANUAL_TEST",
            requestStartedAt = 1_000L,
            completedAt = completedAt,
            ttftMs = if (outcome == "SUCCESS") 120L else null,
            tps = if (outcome == "SUCCESS") 25.0 else null,
            outcome = outcome,
        )

    /** external 觀測 seed：UNKNOWN confidence（未驗證），payload 明示 fixture。 */
    private fun externalObservation(
        source: String,
        externalModelId: String,
        fetchedAt: Long,
        canonicalModelId: String? = null,
        ttlMillis: Long = 60_000L,
    ) =
        ExternalObservationEntity(
            observationId = observationIdOf(source, externalModelId, fetchedAt),
            source = source,
            externalModelId = externalModelId,
            canonicalModelId = canonicalModelId,
            payloadJson = """{"fixture":"external-observation","model":"$externalModelId"}""",
            fetchedAt = fetchedAt,
            ttlMillis = ttlMillis,
            confidence = MappingConfidence.UNKNOWN.name,
        )

    private fun countRows(db: AtlasDatabase, table: String): Long =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getLong(0)
        }
}
