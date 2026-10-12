package dev.modelatlas.app.ui

import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.HealthAggregateRow
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROV-001 repair PD：ProviderDetailLoader 純函式測試（純 Kotlin，無 Android／Room 依賴）。
 *
 * TRACEABILITY：PROV-001（PRODUCT_SPEC §4 Detail 四分頁；Models FREE NOW 優先）；
 * CR-005 C1–C3 純函式向量（typed quota parser／24h window 未知語意／ratio percent formatter）。
 *
 * - fixture 只存在於本 test sourceSet，不作產品資料。
 * - 缺資料一律 UNKNOWN／null／空集合，不捏造、不填零值、不拿 0／Free 冒充未知。
 * - verified 僅由驗證 evidence 判定；manual／official／observed／external 不互冒。
 */
class ProviderDetailTest {

    private fun connection(
        modelId: String,
        price: String = PriceState.UNKNOWN.name,
        quota: String = PriceState.UNKNOWN.name,
    ) = ConnectionItem(
        id = "c-$modelId",
        providerId = "p1",
        name = "n",
        baseUrl = "https://fixture.invalid/v1",
        modelId = modelId,
        credentialLabel = "label",
        priceState = price,
        quotaState = quota,
    )

    // ---- 共識 ----

    @Test
    fun uniformConsensusTakesValue() {
        val rows = listOf(
            connection("m1", PriceState.PAID.name),
            connection("m2", PriceState.PAID.name),
        )
        assertEquals(PriceState.PAID.name, ProviderDetailLoader.priceConsensusOf(rows))
    }

    @Test
    fun divergentOrEmptyConsensusIsUnknown() {
        val mixed = listOf(
            connection("m1", PriceState.PAID.name),
            connection("m2", PriceState.FREE_NOW.name),
        )
        assertEquals(ProviderDetailLoader.UNKNOWN, ProviderDetailLoader.priceConsensusOf(mixed))
        assertEquals(ProviderDetailLoader.UNKNOWN, ProviderDetailLoader.priceConsensusOf(emptyList()))
        assertEquals(ProviderDetailLoader.UNKNOWN, ProviderDetailLoader.consensusOf(listOf("", "  ")))
    }

    @Test
    fun priceStateOfNeverSynthesizesFree() {
        assertEquals(PriceState.UNKNOWN.name, ProviderDetailLoader.priceStateOf(null))
        assertEquals(PriceState.UNKNOWN.name, ProviderDetailLoader.priceStateOf(""))
        assertEquals(PriceState.UNKNOWN.name, ProviderDetailLoader.priceStateOf("FREE"))
        assertEquals(PriceState.UNKNOWN.name, ProviderDetailLoader.priceStateOf("免費"))
        assertEquals(PriceState.FREE_NOW.name, ProviderDetailLoader.priceStateOf("free_now"))
        assertEquals(PriceState.PAID.name, ProviderDetailLoader.priceStateOf("paid"))
    }

    // ---- header ----

    @Test
    fun headerDefaultsAreUnknownAndEmpty() {
        val header = ProviderDetailLoader.headerOf(providerId = "p", name = "", baseUrl = "")
        assertEquals(HealthState.UNKNOWN.name, header.officialHealth)
        assertEquals(HealthState.UNKNOWN.name, header.observedHealth)
        assertEquals(ProviderDetailLoader.UNKNOWN, header.priceState)
        assertEquals(ProviderDetailLoader.UNKNOWN, header.quotaState)
        assertEquals(ProviderDetailLoader.UNKNOWN, header.baseUrl)
        assertEquals(ProviderDetailLoader.UNKNOWN, header.adapterType)
        assertNull(header.websiteUrl)
        assertNull(header.freeNowCount)
        assertNull(header.checkinPending)
        assertNull(header.lastSyncedLabel)
        assertFalse(header.syncing)
    }

    @Test
    fun headerQuotaStateUsesTypedQuotaParser() {
        fun quotaOf(quota: String?): String = ProviderDetailLoader.headerOf(
            providerId = "p",
            name = "",
            baseUrl = "",
            quotaState = quota,
        ).quotaState

        // 缺值／空白一律 UNKNOWN，不合成 FREE。
        assertEquals(QuotaState.UNKNOWN.name, quotaOf(null))
        assertEquals(QuotaState.UNKNOWN.name, quotaOf(""))
        // 合法 QuotaState 全值皆可解析。
        assertEquals(QuotaState.QUOTA_AVAILABLE.name, quotaOf("quota_available"))
        assertEquals(QuotaState.QUOTA_LOW.name, quotaOf("QUOTA_LOW"))
        assertEquals(QuotaState.QUOTA_EXHAUSTED.name, quotaOf("QUOTA_EXHAUSTED"))
        assertEquals(QuotaState.RATE_LIMIT_ONLY.name, quotaOf("RATE_LIMIT_ONLY"))
        assertEquals(QuotaState.PUBLIC_POOL.name, quotaOf("PUBLIC_POOL"))
        assertEquals(QuotaState.UNLIMITED_CLAIMED.name, quotaOf("UNLIMITED_CLAIMED"))
        assertEquals(
            QuotaState.NO_KNOWN_CONSUMABLE_QUOTA.name,
            quotaOf("NO_KNOWN_CONSUMABLE_QUOTA"),
        )
        // PriceState 值不是合法 QuotaState：不得跨 coerce，一律 UNKNOWN。
        assertEquals(QuotaState.UNKNOWN.name, quotaOf(PriceState.FREE_NOW.name))
        assertEquals(QuotaState.UNKNOWN.name, quotaOf(PriceState.PAID.name))
        // price／quota 各自獨立解析（priceState 保持 priceStateOf，不互相覆蓋）。
        val mixed = ProviderDetailLoader.headerOf(
            providerId = "p",
            name = "",
            baseUrl = "",
            priceState = PriceState.PAID.name,
            quotaState = PriceState.FREE_NOW.name,
        )
        assertEquals(PriceState.PAID.name, mixed.priceState)
        assertEquals(QuotaState.UNKNOWN.name, mixed.quotaState)
    }

    @Test
    fun quotaStateOfTrimsCaseAndNeverCrossCoercesPriceValues() {
        assertEquals(QuotaState.QUOTA_LOW.name, ProviderDetailLoader.quotaStateOf("  quota_low  "))
        assertEquals(QuotaState.QUOTA_AVAILABLE.name, ProviderDetailLoader.quotaStateOf("Quota_Available"))
        // PriceState 字串（含小寫／近義字）絕不合成 Quota 或 FREE。
        assertEquals(QuotaState.UNKNOWN.name, ProviderDetailLoader.quotaStateOf("free_now"))
        assertEquals(QuotaState.UNKNOWN.name, ProviderDetailLoader.quotaStateOf("FREE"))
        assertEquals(QuotaState.UNKNOWN.name, ProviderDetailLoader.quotaStateOf("partially_free"))
    }

    // ---- endpoints ----

    @Test
    fun endpointsProjectWithoutFabrication() {
        val endpoints = listOf(
            EndpointEntity("e2", "p1", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat", "CHAT_COMPLETIONS"),
            EndpointEntity("e1", "p1", "https://fixture.invalid/v1", "https://fixture.invalid/v1/models", "MODELS"),
        )
        val projected = ProviderDetailLoader.endpointsOf(endpoints)
        assertEquals(listOf("e1", "e2"), projected.map { it.endpointId })
        assertEquals("MODELS", projected.first().endpointType)
        assertEquals("https://fixture.invalid/v1/models", projected.first().url)
        assertTrue(projected.all { it.enabled })
        assertTrue(ProviderDetailLoader.endpointsOf(emptyList()).isEmpty())
    }

    // ---- accounts（safe metadata only） ----

    @Test
    fun accountsExposeSafeMetadataOnly() {
        val credentials = listOf(
            CredentialEntity("k2", "a1", label = "第二把", enabled = false, group = "g1"),
            CredentialEntity("k1", "a1", label = "主要憑證", group = "  "),
        )
        val accounts = ProviderDetailLoader.accountsOf(
            listOf(AccountEntity(accountId = "a1", providerId = "p1")),
            credentials,
            usableCounts = mapOf("k1" to 3),
        )
        // credentialId 不再冒充 accountId：兩把憑證同屬真帳號 a1。
        assertEquals(listOf("a1"), accounts.map { it.accountId })
        val children = accounts.first().credentials
        assertEquals(listOf("k1", "k2"), children.map { it.credentialId })
        assertEquals("主要憑證", children.first().label)
        // 空白 group 視為未知（null），不自造群組名。
        assertNull(children.first().group)
        assertEquals("g1", children[1].group)
        assertFalse(children[1].enabled)
        assertEquals(3, children.first().usableModels)
        // 缺席計數即未知（null），不填 0。
        assertNull(children[1].usableModels)
        assertNull(children.first().lastSuccessLabel)
        assertNull(children.first().lastFailureLabel)
    }

    @Test
    fun accountsNestCredentialsUnderTrueAccountIdentity() {
        val accounts = listOf(
            AccountEntity("a2", "p1"),
            AccountEntity("a1", "p1"),
            AccountEntity("a3", "p1"),
        )
        val credentials = listOf(
            CredentialEntity("c2", "a1", label = "第二把", enabled = false, group = "g1"),
            CredentialEntity("c1", "a1", label = "主要憑證", group = "  "),
            CredentialEntity("c4", "a9", label = "孤兒憑證"),
        )
        val projected = ProviderDetailLoader.accountsOf(
            accounts,
            credentials,
            usableCounts = mapOf("c1" to 3),
        )
        // 真 accountId 升冪；零憑證 account 保留；orphan 仍歸其 persisted accountId。
        assertEquals(listOf("a1", "a2", "a3", "a9"), projected.map { it.accountId })
        val a1 = projected.first { it.accountId == "a1" }
        assertEquals(listOf("c1", "c2"), a1.credentials.map { it.credentialId })
        assertTrue(projected.first { it.accountId == "a2" }.credentials.isEmpty())
        assertTrue(projected.first { it.accountId == "a3" }.credentials.isEmpty())
        assertEquals(
            listOf("c4"),
            projected.first { it.accountId == "a9" }.credentials.map { it.credentialId },
        )
        // 沒有任何 account row 把 credentialId 當成 accountId。
        assertTrue(projected.none { account -> account.credentials.any { it.credentialId == account.accountId } })
        // 子列安全 metadata：label／group／enabled／usable（缺值 null，不填 0）。
        assertEquals("主要憑證", a1.credentials.first().label)
        assertNull(a1.credentials.first().group)
        assertTrue(a1.credentials.first().enabled)
        assertEquals(3, a1.credentials.first().usableModels)
        assertEquals("g1", a1.credentials[1].group)
        assertFalse(a1.credentials[1].enabled)
        assertNull(a1.credentials[1].usableModels)
    }

    // ---- models ----

    @Test
    fun modelsVerifiedOnlyByEvidenceAndDedupe() {
        val offerings = listOf(
            OfferingEntity("o1", "p1", "e1", "m-free", manual = false, verificationEvidenceId = "ev1"),
            OfferingEntity("o2", "p1", "e1", "m-manual", manual = true),
            OfferingEntity("o3", "p1", "e1", "m-free", manual = false),
            OfferingEntity("o4", "p1", "e1", "  "),
        )
        val models = ProviderDetailLoader.modelsOf(
            offerings,
            mapOf("m-free" to PriceState.FREE_NOW.name, "m-manual" to "bogus"),
        )
        assertEquals(listOf("m-free", "m-manual"), models.map { it.modelId })
        assertTrue(models.first().verified)
        assertFalse(models[1].verified)
        assertTrue(models[1].manual)
        assertEquals(PriceState.FREE_NOW.name, models.first().priceState)
        // 非法價格字串回 UNKNOWN，不臆測。
        assertEquals(PriceState.UNKNOWN.name, models[1].priceState)
    }

    @Test
    fun priceByModelDivergenceIsUnknown() {
        val rows = listOf(
            connection("m1", PriceState.PAID.name),
            connection("m1", PriceState.FREE_NOW.name),
        )
        assertEquals(
            ProviderDetailLoader.UNKNOWN,
            ProviderDetailLoader.priceByModel(rows)["m1"],
        )
        assertTrue(ProviderDetailLoader.priceByModel(emptyList()).isEmpty())
    }

    // ---- performance（未知不得當成 0） ----

    @Test
    fun performanceNullWhenNoDecidedSamples() {
        assertNull(ProviderDetailLoader.performanceOf(emptyList()))
        val runningOnly = listOf(
            HealthAggregateRow("p1", "m1", total = 5, decided = 0, successes = 0, rateLimited = 0, null, null, null, null),
        )
        assertNull(ProviderDetailLoader.performanceOf(runningOnly))
    }

    @Test
    fun performanceAggregatesWithoutZeroFill() {
        val rows = listOf(
            HealthAggregateRow("p1", "m1", total = 10, decided = 8, successes = 6, rateLimited = 0, avgTtftMs = 100.0, avgTps = 20.0, avgLatencyMs = 500.0, lastObservedAt = 1L),
            HealthAggregateRow("p1", "m2", total = 10, decided = 2, successes = 2, rateLimited = 0, avgTtftMs = null, avgTps = null, avgLatencyMs = null, lastObservedAt = 2L),
        )
        val perf = ProviderDetailLoader.performanceOf(rows)!!
        assertEquals(0.8, perf.success24h!!, 1e-9)
        // success1h 無 per-hour 真值，恆未知（不拿全期冒充）。
        assertNull(perf.success1h)
        assertEquals(20.0, perf.tps!!, 1e-9)
        assertEquals(100L, perf.ttftMs)
        assertEquals(500L, perf.latencyMs)
        assertEquals(20L, perf.requestCount)
    }

    @Test
    fun success24hUnknownWhenWindowEmptyDespiteAllTimeDecided() {
        val allTime = listOf(
            HealthAggregateRow("p1", "m1", total = 10, decided = 8, successes = 6, rateLimited = 0, avgTtftMs = 100.0, avgTps = 20.0, avgLatencyMs = 500.0, lastObservedAt = 1L),
        )
        // all-time 有已判定樣本，但 24h window 空（讀取失敗）→ success24h 未知，
        // 絕不拿全期成功率冒充 24h。
        val perf = ProviderDetailLoader.performanceOf(allTime, emptyList())!!
        assertNull(perf.success24h)
        assertNull(perf.success1h)
        // 其餘 all-time 指標照常保留（未知不得當 0）。
        assertEquals(20.0, perf.tps!!, 1e-9)
        assertEquals(100L, perf.ttftMs)
        assertEquals(500L, perf.latencyMs)
        assertEquals(10L, perf.requestCount)
    }

    @Test
    fun success24hComesFromWindowReadNotAllTime() {
        val allTime = listOf(
            HealthAggregateRow("p1", "m1", total = 100, decided = 10, successes = 8, rateLimited = 0, avgTtftMs = null, avgTps = null, avgLatencyMs = null, lastObservedAt = 1L),
        )
        val window = listOf(
            HealthAggregateRow("p1", "m1", total = 4, decided = 4, successes = 2, rateLimited = 0, avgTtftMs = null, avgTps = null, avgLatencyMs = null, lastObservedAt = 2L),
        )
        val perf = ProviderDetailLoader.performanceOf(allTime, window)!!
        // 24h 只信 window（0.5），all-time 的 0.8 不得冒充。
        assertEquals(0.5, perf.success24h!!, 1e-9)
        assertEquals(100L, perf.requestCount)
    }

    // ---- success ratio formatter（CR-005 C3） ----

    @Test
    fun successRatePercentLabelTruncatesWithoutOverstating() {
        assertNull(ProviderDetailLoader.successRatePercentLabel(null))
        assertEquals("0%", ProviderDetailLoader.successRatePercentLabel(0.0))
        assertEquals("29%", ProviderDetailLoader.successRatePercentLabel(0.29))
        assertEquals("80%", ProviderDetailLoader.successRatePercentLabel(0.8))
        assertEquals("84%", ProviderDetailLoader.successRatePercentLabel(0.845))
        assertEquals("99%", ProviderDetailLoader.successRatePercentLabel(0.999))
        assertEquals("100%", ProviderDetailLoader.successRatePercentLabel(1.0))
        // 超出比例域或非有限值一律 fail-closed null，不渲染數字。
        assertNull(ProviderDetailLoader.successRatePercentLabel(-0.1))
        assertNull(ProviderDetailLoader.successRatePercentLabel(1.5))
        assertNull(ProviderDetailLoader.successRatePercentLabel(Double.NaN))
    }

    @Test
    fun successRatePercentLabelNeverRendersHundredBelowOne() {
        // 最大 <1.0 double 不得因 epsilon 進位渲染成 100%（spec P4「<1.0 永不 100%」）。
        assertEquals("99%", ProviderDetailLoader.successRatePercentLabel(0.9999999999999999))
        assertEquals("99%", ProviderDetailLoader.successRatePercentLabel(0.999999))
        assertNull(ProviderDetailLoader.successRatePercentLabel(Double.POSITIVE_INFINITY))
        assertNull(ProviderDetailLoader.successRatePercentLabel(Double.NEGATIVE_INFINITY))
    }

    // ---- history ----

    @Test
    fun historyMergesAndSortsDescWithUnknownFallback() {
        val events = ProviderDetailLoader.historyOf(
            telemetry = listOf(
                ProviderDetailLoader.TelemetryPoint(observedAt = 10L, outcome = "SUCCESS"),
                ProviderDetailLoader.TelemetryPoint(observedAt = 30L, outcome = ""),
            ),
            snapshots = listOf(
                ProviderDetailLoader.SnapshotPoint(kind = "PRICE", observedAt = 20L, state = "PAID"),
                ProviderDetailLoader.SnapshotPoint(kind = "  ", observedAt = 40L, state = "PAID"),
            ),
        )
        // 空 kind 列略過；空白 outcome 回 UNKNOWN。
        assertEquals(3, events.size)
        assertEquals("TELEMETRY", events[0].section)
        assertEquals(ProviderDetailLoader.UNKNOWN, events[0].stateLabel)
        assertEquals("PRICE", events[1].section)
        assertEquals("TELEMETRY", events[2].section)
        assertEquals("observed", events[0].sourceLabel)
        assertTrue(ProviderDetailLoader.historyOf(emptyList(), emptyList()).isEmpty())
    }

    // ---- overview／source ----

    @Test
    fun overviewUnknownByDefaultAndKeepsUserNotes() {
        val empty = ProviderDetailLoader.overviewOf(null)
        assertNull(empty.description)
        assertNull(empty.userNotes)
        assertTrue(empty.notices.isEmpty())
        val noted = ProviderDetailLoader.overviewOf("  自訂備註  ")
        assertEquals("  自訂備註  ", noted.userNotes)
        assertTrue(ProviderDetailLoader.overviewOf("   ").userNotes == null)
    }

    @Test
    fun snapshotSourceLabelNeverImpersonates() {
        assertEquals("manual", ProviderDetailLoader.snapshotSourceLabel("USER"))
        assertEquals("official", ProviderDetailLoader.snapshotSourceLabel("newapi/api/pricing"))
        assertEquals("official", ProviderDetailLoader.snapshotSourceLabel("openrouter/catalog"))
        assertEquals("", ProviderDetailLoader.snapshotSourceLabel(""))
        assertEquals("", ProviderDetailLoader.snapshotSourceLabel("self-claimed-official"))
    }
}
