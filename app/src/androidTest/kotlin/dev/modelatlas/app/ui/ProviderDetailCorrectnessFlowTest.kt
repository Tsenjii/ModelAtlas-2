package dev.modelatlas.app.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
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
 * CR-005 Provider Detail correctness device flow 測試（真 Room ＋ 真 [ProviderDetailLoader]）。
 *
 * TRACEABILITY：CORRECTNESS_REPAIR_AUDIT C1（typed quota parser）、C2（真 24h window ＋
 * 注入時鐘）、C4（真 AccountEntity identity，credentials 為子列）；spec-cr005 P1／P2／P5。
 *
 * 只經真 loader `loadDetail` 端到端驗證，不碰 production：
 * - C2：`nowMillis` 注入驅動 `windowStart = nowMillis - 24h`；半開下界 `startedAt >= windowStart`
 *   在此 DAO 契約下為「恰落邊界即計」。all-time 與 window 分開讀，`requestCount` 維持
 *   all-time；成功 24h 只取 window，空／失敗不得回退 all-time。
 * - C1：header `quotaState` 經 `quotaStateOf`，PriceState 值（FREE_NOW／PAID）不得冒充 QuotaState。
 * - C4：`accountsOf` 由 accounts 表 identity 分組，credentials 為子列；不得把 credentialId 冒充 accountId。
 *
 * fixture 只存在本 test sourceSet，不作產品資料；真 in-memory Room，無 sleep／timeout；
 * 每個測試獨立 DB，`tearDown` 先關 DB（in-memory 隨關閉銷毀，無跨測試殘留）。
 */
@RunWith(AndroidJUnit4::class)
class ProviderDetailCorrectnessFlowTest {

    private lateinit var db: AtlasDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- C2：注入時鐘的真 24h 窗口（半開下界；all-time 不冒充 24h） ----

    @Test
    fun healthAggregatesUsesInjectedNowMinus24hBoundary() = runBlocking {
        val now = 10_000_000_000L
        val day = 86_400_000L
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        // 恰在 24h 窗外（差 1ms）：window 排除，all-time 仍計。
        db.sampleDao().upsert(sample("just-outside", now - day - 1, "SUCCESS"))
        // 恰落 window 下界：DAO `startedAt >= sinceMillis`，必須計入。
        db.sampleDao().upsert(sample("at-boundary", now - day, "SUCCESS"))
        // 窗內的非成功樣本：進 decided 但不進 successes。
        db.sampleDao().upsert(sample("inside", now - 3_600_000L, "NETWORK"))

        val fresh = ProviderDetailLoader.loadDetail(db, "p1", nowMillis = now)
        val perf = fresh.performance!!
        // success24h 只取 window：A 被排除 → successes 1 / decided 2。若誤用 all-time 會是 2/3。
        assertEquals(0.5, perf.success24h!!, 1e-9)
        assertNull("success1h 無 per-hour 真值，不得由 24h／all-time 冒充", perf.success1h)
        // requestCount 仍是 locked all-time port（三筆皆 all-time），不隨 window 縮水。
        assertEquals(3L, perf.requestCount!!)

        // 注入時鐘可平移窗口：now 回推 24h 後 windowStart=now-48h，三筆全進窗。
        val shifted = ProviderDetailLoader.loadDetail(db, "p1", nowMillis = now - day)
        assertEquals(2.0 / 3.0, shifted.performance!!.success24h!!, 1e-9)
    }

    // ---- C1：配額 parser 端到端（header 經 loadDetail） ----

    @Test
    fun headerQuotaStateUsesTypedParserEndToEnd() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))

        val legal = ProviderDetailLoader.loadDetail(
            db,
            "p1",
            connections = listOf(connection(quota = "quota_low")),
        )
        assertEquals(QuotaState.QUOTA_LOW.name, legal.header.quotaState)

        // PriceState 值不得跨 coerce 成 QuotaState；價格欄位保留 PAID。
        val mixed = ProviderDetailLoader.loadDetail(
            db,
            "p1",
            connections = listOf(connection(price = PriceState.PAID.name, quota = PriceState.FREE_NOW.name)),
        )
        assertEquals(PriceState.PAID.name, mixed.header.priceState)
        assertEquals(QuotaState.UNKNOWN.name, mixed.header.quotaState)

        // 空白配額即未知，絕不合成 FREE。
        val blank = ProviderDetailLoader.loadDetail(
            db,
            "p1",
            connections = listOf(connection(quota = "  ")),
        )
        assertEquals(QuotaState.UNKNOWN.name, blank.header.quotaState)
    }

    // ---- C4：真 account identity → credentials 子列 ----

    @Test
    fun loadDetailProjectsTrueAccountChildrenFromRoom() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        db.accountDao().insert(AccountEntity(accountId = "acct-A", providerId = "p1"))
        db.credentialDao().insert(
            CredentialEntity(credentialId = "cred-A1", accountId = "acct-A", label = "主要憑證"),
        )
        db.credentialDao().insert(
            CredentialEntity(
                credentialId = "cred-A2",
                accountId = "acct-A",
                label = "備用憑證",
                enabled = false,
                group = "g1",
            ),
        )

        val detail = ProviderDetailLoader.loadDetail(db, "p1")
        val account = detail.accounts.single()
        assertEquals("acct-A", account.accountId)
        assertEquals(listOf("cred-A1", "cred-A2"), account.credentials.map { it.credentialId })
        assertEquals("主要憑證", account.credentials[0].label)
        assertTrue(account.credentials[0].enabled)
        assertFalse(account.credentials[1].enabled)
        assertEquals("g1", account.credentials[1].group)

        // 舊錯誤：每個 credential 被投影成 accountId==credentialId；不得再出現。
        assertTrue(
            "credentialId 不得冒充 accountId",
            detail.accounts.none { it.accountId == "cred-A1" || it.accountId == "cred-A2" },
        )
    }

    // ---- fixtures（只在本檔） ----

    private fun sample(id: String, startedAt: Long, outcome: String) = SampleEntity(
        id = id,
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "c1",
        endpointId = "e1",
        providerModelId = "m1",
        requestStartedAt = startedAt,
        completedAt = startedAt,
        outcome = outcome,
    )

    private fun connection(
        price: String = PriceState.UNKNOWN.name,
        quota: String = QuotaState.UNKNOWN.name,
    ) = ConnectionItem(
        id = "c-m1",
        providerId = "p1",
        name = "fixture",
        baseUrl = "https://fixture.invalid/v1",
        modelId = "m1",
        credentialLabel = "label",
        priceState = price,
        quotaState = quota,
    )
}
