package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ui.CompareLiveWiring
import dev.modelatlas.app.ui.ModelRow
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MODEL-008 cross-provider multi-key compare wiring tests（DB-TEST worker，僅本檔）。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §3／§6／§8／§10、contract §7／§23 scope／source 分離）。
 *
 * - 真 in-memory Room metadata（providers／accounts／credentials／endpoints／offerings／
 *   aliases／availability／provider_configurations）＋真 [CompareLiveWiring] builder／
 *   真 [RoomCompareLiveSampleFeed]；fixture 只存於本測試檔，不當產品資料。
 * - 無真 HTTP、無 raw key／PAT（全部 safe id）；不讀 vault、不寫 DB、不碰既有檔。
 * - 鎖定：跨 provider 各自 account／credential／group 歸屬、wrong owner／disabled／
 *   PAT／NONE fail-closed、confirmed NONE 匿名不捏 key、collision-safe scoped
 *   comparisonId（actual offeringId 不變）、price／quota 從 matching availability
 *   取值不被 foreign ModelRow FREE／PAID 污染、單次 6 列上限與輸入順序、
 *   feed finalFor 精確 id（不吃 latest decoy）。
 */
@RunWith(AndroidJUnit4::class)
class CompareMultiProviderWiringTest {
    private lateinit var context: Context
    private var db: AtlasDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        runCatching { db?.close() }
        db = null
    }

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    // ------------------------------------------------------------------
    // Fixtures / helpers（僅本檔使用；只收 safe id）
    // ------------------------------------------------------------------

    private suspend fun seedSharedCanonical(target: AtlasDatabase) {
        target.canonicalModelDao().insert(
            CanonicalModelEntity(canonicalModelId = CANONICAL, normalizedName = CANONICAL),
        )
    }

    private fun inferenceConfig(providerId: String, keyIds: List<String>): ProviderManualConfig =
        ProviderManualConfig(
            providerId = providerId,
            workflowId = "wf-$providerId",
            authMode = AuthMode.API_KEY,
            credentials = keyIds.map {
                SafeCredentialRef(safeCredentialId = it, label = it, role = CredentialRole.INFERENCE)
            },
        )

    private fun patOnlyConfig(): ProviderManualConfig = ProviderManualConfig(
        providerId = "prov-a",
        workflowId = "wf-pat-only",
        authMode = AuthMode.PAT_ACCOUNT,
        credentials = listOf(
            SafeCredentialRef(safeCredentialId = "pat-a1", label = "PAT A1", role = CredentialRole.ACCOUNT),
        ),
    )

    /** Provider A：兩把 enabled INFERENCE key、endpoint、offering、alias、per-key availability、config。 */
    private suspend fun seedProviderA(
        target: AtlasDatabase,
        config: ProviderManualConfig = inferenceConfig("prov-a", listOf("key-a1", "key-a2")),
    ) {
        target.providerDao().insert(ProviderEntity(providerId = "prov-a", name = "Provider A"))
        target.accountDao().insert(AccountEntity(accountId = "acct-a1", providerId = "prov-a"))
        target.credentialDao().insert(
            CredentialEntity(credentialId = "key-a1", accountId = "acct-a1", label = "Key A1", authMode = AuthMode.API_KEY.name),
        )
        target.credentialDao().insert(
            CredentialEntity(credentialId = "key-a2", accountId = "acct-a1", label = "Key A2", authMode = AuthMode.API_KEY.name),
        )
        target.endpointDao().insert(
            EndpointEntity(endpointId = "ep-a", providerId = "prov-a", baseUrl = "https://prov-a.example", endpointUrl = URL_A),
        )
        target.offeringDao().insert(
            OfferingEntity(id = "off-a", providerId = "prov-a", endpointId = "ep-a", providerModelId = "model-a", manual = false, source = "OFFICIAL"),
        )
        target.providerModelAliasDao().insert(
            ProviderModelAliasEntity(
                aliasId = "alias-a", providerId = "prov-a", providerModelId = "model-a",
                canonicalModelId = CANONICAL, source = "OFFICIAL", confidence = "HIGH",
            ),
        )
        target.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-a1", offeringId = "off-a", accountId = "acct-a1", credentialId = "key-a1",
                priceState = "PAID", quotaState = "QUOTA_LOW",
            ),
        )
        target.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-a2", offeringId = "off-a", accountId = "acct-a1", credentialId = "key-a2",
                priceState = "FREE_NOW", quotaState = "QUOTA_AVAILABLE",
            ),
        )
        target.providerConfigDao().insert(
            ProviderConfigurationEntity(
                providerId = config.providerId,
                workflowId = config.workflowId,
                authMode = config.authMode.name,
                snapshotJson = config.encodeToSnapshotJson(),
            ),
        )
    }

    /** Provider B：generic legacy（無 provider config），無 availability（缺席 → UNKNOWN）。 */
    private suspend fun seedProviderB(target: AtlasDatabase) {
        target.providerDao().insert(ProviderEntity(providerId = "prov-b", name = "Provider B"))
        target.accountDao().insert(AccountEntity(accountId = "acct-b1", providerId = "prov-b"))
        target.credentialDao().insert(
            CredentialEntity(credentialId = "key-b1", accountId = "acct-b1", label = "Key B1", authMode = AuthMode.API_KEY.name),
        )
        target.endpointDao().insert(
            EndpointEntity(endpointId = "ep-b", providerId = "prov-b", baseUrl = "https://prov-b.example", endpointUrl = URL_B),
        )
        target.offeringDao().insert(
            OfferingEntity(id = "off-b", providerId = "prov-b", endpointId = "ep-b", providerModelId = "model-b", manual = false, source = "OFFICIAL"),
        )
        target.providerModelAliasDao().insert(
            ProviderModelAliasEntity(
                aliasId = "alias-b", providerId = "prov-b", providerModelId = "model-b",
                canonicalModelId = CANONICAL, source = "OFFICIAL", confidence = "HIGH",
            ),
        )
    }

    /** 匿名 NONE provider：anonymousConfirmed 控制 confirmed / unconfirmed；withKey 加一把具名 key。 */
    private suspend fun seedNoneProvider(
        target: AtlasDatabase,
        providerId: String,
        anonymousConfirmed: Boolean,
        withKey: Boolean,
    ) {
        target.providerDao().insert(ProviderEntity(providerId = providerId, name = "Provider ${providerId.takeLast(1)}"))
        target.accountDao().insert(AccountEntity(accountId = "acct-$providerId", providerId = providerId))
        if (withKey) {
            target.credentialDao().insert(
                CredentialEntity(credentialId = "key-$providerId", accountId = "acct-$providerId", label = "Key $providerId"),
            )
        }
        target.endpointDao().insert(
            EndpointEntity(
                endpointId = "ep-$providerId", providerId = providerId,
                baseUrl = "https://$providerId.example", endpointUrl = "https://$providerId.example/v1/chat",
            ),
        )
        target.offeringDao().insert(
            OfferingEntity(
                id = "off-$providerId", providerId = providerId, endpointId = "ep-$providerId",
                providerModelId = "model-$providerId", manual = false, source = "OFFICIAL",
            ),
        )
        target.providerModelAliasDao().insert(
            ProviderModelAliasEntity(
                aliasId = "alias-$providerId", providerId = providerId, providerModelId = "model-$providerId",
                canonicalModelId = CANONICAL, source = "OFFICIAL", confidence = "HIGH",
            ),
        )
        val config = ProviderManualConfig(
            providerId = providerId,
            workflowId = "wf-$providerId",
            authMode = AuthMode.NONE,
            anonymousConfirmed = anonymousConfirmed,
        )
        target.providerConfigDao().insert(
            ProviderConfigurationEntity(
                providerId = providerId,
                workflowId = config.workflowId,
                authMode = AuthMode.NONE.name,
                snapshotJson = config.encodeToSnapshotJson(),
            ),
        )
    }

    /** Cap provider：7 個同 canonical offering（各自 endpoint），quota 保護上限測試用。 */
    private suspend fun seedCapProvider(target: AtlasDatabase) {
        target.providerDao().insert(ProviderEntity(providerId = "prov-cap", name = "Provider Cap"))
        target.accountDao().insert(AccountEntity(accountId = "acct-cap", providerId = "prov-cap"))
        target.credentialDao().insert(
            CredentialEntity(credentialId = "key-cap", accountId = "acct-cap", label = "Key Cap"),
        )
        for (i in 1..7) {
            target.endpointDao().insert(
                EndpointEntity(
                    endpointId = "ep-cap$i", providerId = "prov-cap",
                    baseUrl = "https://prov-cap.example", endpointUrl = "https://prov-cap.example/v$i/chat",
                ),
            )
            target.offeringDao().insert(
                OfferingEntity(
                    id = "off-cap$i", providerId = "prov-cap", endpointId = "ep-cap$i",
                    providerModelId = "model-a", manual = false, source = "OFFICIAL",
                ),
            )
        }
        target.providerModelAliasDao().insert(
            ProviderModelAliasEntity(
                aliasId = "alias-cap", providerId = "prov-cap", providerModelId = "model-a",
                canonicalModelId = CANONICAL, source = "OFFICIAL", confidence = "HIGH",
            ),
        )
    }

    private fun row(
        providerId: String,
        modelId: String,
        priceState: PriceState = PriceState.UNKNOWN,
        quotaState: QuotaState = QuotaState.UNKNOWN,
    ): ModelRow = ModelRow(
        providerId = providerId,
        providerModelId = modelId,
        canonicalId = CANONICAL,
        priceState = priceState,
        quotaState = quotaState,
    )

    private fun selA(credentialId: String, group: String? = null) = CompareKeySelection(
        providerId = "prov-a",
        accountId = "acct-a1",
        credentialId = credentialId,
        group = group,
    )

    private fun selB() = CompareKeySelection(
        providerId = "prov-b",
        accountId = "acct-b1",
        credentialId = "key-b1",
    )

    private fun sample(
        id: String,
        accountId: String = "acct-feed",
        credentialId: String = "key-feed",
        startedAt: Long = 5_000L,
        completedAt: Long? = 6_000L,
        outcome: Outcome = Outcome.SUCCESS,
        ttftMs: Long? = 400L,
    ) = PerformanceSample(
        id = id,
        providerId = "p-feed",
        accountId = accountId,
        safeCredentialId = credentialId,
        endpointId = "ep-feed",
        providerModelId = "model-feed",
        canonicalModelId = CANONICAL,
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.COMPARE_LIVE,
        requestStartedAt = startedAt,
        firstTokenAt = if (ttftMs == null) null else startedAt + ttftMs,
        completedAt = completedAt,
        streaming = true,
        ttftMs = ttftMs,
        ttfbMs = 100L,
        totalLatencyMs = 1_000L,
        tps = 110.0,
        usage = Usage(inputTokens = 8L, outputTokens = 12L, totalTokens = 20L),
        httpStatus = 200,
        outcome = outcome,
        rateLimitHeaders = emptyMap(),
        priceState = "UNKNOWN",
        quotaState = "UNKNOWN",
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun crossProviderSelectionsUseEachOwnCredentialAndAvailabilityPrices() = runBlocking {
        val target = memoryDb()
        seedSharedCanonical(target)
        seedProviderA(target)
        seedProviderB(target)
        // Provider 級 ModelRow 旗標與 availability 真值相反（decoy），不得污染 price／quota。
        val allRows = listOf(
            row("prov-a", "model-a", priceState = PriceState.FREE_NOW, quotaState = QuotaState.QUOTA_AVAILABLE),
            row("prov-b", "model-b", priceState = PriceState.PAID, quotaState = QuotaState.QUOTA_EXHAUSTED),
        )
        val request = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(selA("key-a1"), selB()),
        )
        assertNotNull(request)
        val offerings = request!!.offerings
        assertEquals(2, offerings.size)
        val a = offerings.single { it.providerId == "prov-a" }
        val b = offerings.single { it.providerId == "prov-b" }
        // 各自真實帳號／key／endpoint／model：絕不跨 provider 沿用同一把 key。
        assertEquals("off-a", a.offeringId)
        assertEquals("acct-a1", a.accountId)
        assertEquals("key-a1", a.credentialId)
        assertEquals("key-a1", a.selectedCredentialId)
        assertEquals("ep-a", a.endpointId)
        assertEquals(URL_A, a.endpointUrl)
        assertEquals("model-a", a.providerModelId)
        assertEquals("off-b", b.offeringId)
        assertEquals("acct-b1", b.accountId)
        assertEquals("key-b1", b.credentialId)
        assertEquals("key-b1", b.selectedCredentialId)
        // Price／quota 從 matching availability 取值：availability 真值壓過 ModelRow decoy。
        assertEquals("PAID", a.priceState)
        assertEquals("QUOTA_LOW", a.quotaState)
        // 缺 availability → UNKNOWN；不沿用另一把 key 的 ModelRow PAID decoy、不依 id／0 猜 Free。
        assertEquals("UNKNOWN", b.priceState)
        assertEquals("UNKNOWN", b.quotaState)
        // Generic legacy inference key：無 custom auth fields、comparisonId 非空白。
        assertTrue(a.authFields.isEmpty())
        assertTrue(a.comparisonId.isNotBlank())
        assertTrue(b.comparisonId.isNotBlank())
    }

    @Test
    fun foreignKeyUnderWrongAccountFailsClosedWithoutFallback() = runBlocking {
        val target = memoryDb()
        seedSharedCanonical(target)
        seedProviderA(target)
        seedProviderB(target)
        val allRows = listOf(row("prov-a", "model-a"), row("prov-b", "model-b"))
        // B 的 key 配 A 的帳號：credential.accountId != selection.accountId → 該 selection 拒絕，
        // 不 fallback 猜 A 的其他 key；有效 selection 照常出列。
        val mixed = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(CompareKeySelection("prov-a", "acct-a1", "key-b1"), selB()),
        )
        assertNotNull(mixed)
        assertTrue(mixed!!.offerings.all { it.providerId == "prov-b" })
        assertTrue(mixed.offerings.none { it.accountId == "acct-a1" })
        assertTrue(mixed.offerings.none { it.credentialId == "key-b1" && it.accountId == "acct-a1" })
        // selection 的 account 屬於另一 provider（account.providerId != providerId）→ 整體 fail-closed。
        val wrongOwner = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(CompareKeySelection("prov-a", "acct-b1", "key-a1")),
        )
        assertNull(wrongOwner)
    }

    @Test
    fun disabledCredentialFailsClosedAndNotListedInChoices() = runBlocking {
        val target = memoryDb()
        seedSharedCanonical(target)
        seedProviderA(target)
        seedProviderB(target)
        target.credentialDao().setEnabled("key-a2", false)
        val allRows = listOf(row("prov-a", "model-a"), row("prov-b", "model-b"))
        val request = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(selA("key-a2")),
        )
        assertNull(request)
        val choices = CompareLiveWiring.loadChoicesFromDb(target, row("prov-a", "model-a"), allRows)
        assertTrue(choices.any { it.selection == CompareKeySelection("prov-a", "acct-a1", "key-a1") })
        assertTrue(choices.none { it.selection.credentialId == "key-a2" })
        // choices 只含 safe metadata（providerName 來自 DB 真值；label 非 raw key）。
        val a1 = choices.first { it.selection.credentialId == "key-a1" }
        assertEquals("Provider A", a1.providerName)
        assertEquals("Key A1", a1.credentialLabel)
    }

    @Test
    fun patAccountCredentialNeverInferenceAndNotListed() = runBlocking {
        val target = memoryDb()
        seedSharedCanonical(target)
        seedProviderA(target, patOnlyConfig())
        target.credentialDao().insert(
            CredentialEntity(credentialId = "pat-a1", accountId = "acct-a1", label = "PAT A1", authMode = AuthMode.PAT_ACCOUNT.name),
        )
        val allRows = listOf(row("prov-a", "model-a"), row("prov-b", "model-b"))
        // PAT 是 account／management credential，不是 inference key：selection 拒絕。
        val request = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(selA("pat-a1")),
        )
        assertNull(request)
        val choices = CompareLiveWiring.loadChoicesFromDb(target, row("prov-a", "model-a"), allRows)
        assertTrue(choices.none { it.selection.providerId == "prov-a" && it.selection.credentialId == "pat-a1" })
    }

    @Test
    fun confirmedNoneAnonymousAllowedWithoutFabricatedKeyAndUnconfirmedRejected() = runBlocking {
        val target = memoryDb()
        seedSharedCanonical(target)
        seedNoneProvider(target, "prov-c", anonymousConfirmed = true, withKey = false)
        seedNoneProvider(target, "prov-d", anonymousConfirmed = false, withKey = true)
        val allRows = listOf(row("prov-c", "model-prov-c"), row("prov-d", "model-prov-d"))
        // 已確認 NONE（authMode NONE + anonymousConfirmed）＋真 account：匿名合法。
        val request = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-c", "model-prov-c"), allRows,
            listOf(CompareKeySelection("prov-c", "acct-prov-c", null)),
        )
        assertNotNull(request)
        val anonymous = request!!.offerings.single()
        assertEquals("prov-c", anonymous.providerId)
        assertEquals("acct-prov-c", anonymous.accountId)
        // 不捏造 key：credentialId／selectedCredentialId 皆 null、無 authFields。
        assertNull(anonymous.credentialId)
        assertNull(anonymous.selectedCredentialId)
        assertTrue(anonymous.authFields.isEmpty())
        // 匿名無 availability：UNKNOWN，不補 0／Free。
        assertEquals("UNKNOWN", anonymous.priceState)
        assertEquals("UNKNOWN", anonymous.quotaState)
        // 未確認 NONE：匿名拒絕；NONE config 帶具名 key 也拒絕（fail-closed）。
        assertNull(
            CompareLiveWiring.buildRequestFromDb(
                target, row("prov-d", "model-prov-d"), allRows,
                listOf(CompareKeySelection("prov-d", "acct-prov-d", null)),
            ),
        )
        assertNull(
            CompareLiveWiring.buildRequestFromDb(
                target, row("prov-d", "model-prov-d"), allRows,
                listOf(CompareKeySelection("prov-d", "acct-prov-d", "key-prov-d")),
            ),
        )
        // choices：已確認 NONE 才列匿名（group null、label 匿名）；未確認 provider 不列匿名。
        val choices = CompareLiveWiring.loadChoicesFromDb(target, row("prov-c", "model-prov-c"), allRows)
        assertTrue(choices.any { it.selection == CompareKeySelection("prov-c", "acct-prov-c", null) })
        assertTrue(choices.none { it.selection.providerId == "prov-d" && it.selection.credentialId == null })
    }

    @Test
    fun sameOfferingDifferentKeyOrGroupScopesCollisionSafeComparisonIds() = runBlocking {
        val target = memoryDb()
        seedSharedCanonical(target)
        seedProviderA(target)
        val allRows = listOf(row("prov-a", "model-a"))
        // 同一 actual offering 兩把 key：offeringId 不變，comparisonId collision-safe distinct；
        // per-key availability 各自取值。
        val twoKeys = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(selA("key-a1"), selA("key-a2")),
        )
        assertNotNull(twoKeys)
        val keyOfferings = twoKeys!!.offerings
        assertEquals(2, keyOfferings.size)
        assertTrue(keyOfferings.all { it.offeringId == "off-a" })
        val comparisonIds = keyOfferings.map { it.comparisonId }
        assertEquals(2, comparisonIds.toSet().size)
        assertTrue(comparisonIds.all { it.isNotBlank() })
        assertEquals("PAID", keyOfferings.first { it.credentialId == "key-a1" }.priceState)
        assertEquals("FREE_NOW", keyOfferings.first { it.credentialId == "key-a2" }.priceState)
        // 同 key 不同 group scope：comparisonId 仍 distinct；group scope 無 matching availability → UNKNOWN。
        val twoGroups = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(selA("key-a1"), selA("key-a1", group = "free")),
        )
        assertNotNull(twoGroups)
        val groupOfferings = twoGroups!!.offerings
        assertEquals(2, groupOfferings.size)
        assertTrue(groupOfferings.all { it.offeringId == "off-a" })
        assertEquals(2, groupOfferings.map { it.comparisonId }.toSet().size)
        val freeScope = groupOfferings.first { it.group == "free" }
        assertEquals("UNKNOWN", freeScope.priceState)
        assertEquals("UNKNOWN", freeScope.quotaState)
        // 同 selection 重複（identical scope）：去重，不重複發同一列。
        val deduped = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-a", "model-a"), allRows,
            listOf(selA("key-a1"), selA("key-a1")),
        )
        assertNotNull(deduped)
        assertEquals(1, deduped!!.offerings.size)
        // length-prefix 編碼：null 與空字串不碰撞（collision-safe scope key）。
        assertNotEquals(
            CompareLiveWiring.scopeKey(selA("key-a1")),
            CompareLiveWiring.scopeKey(CompareKeySelection("prov-a", "acct-a1", "")),
        )
    }

    @Test
    fun capAtSixOfferingsPreservingInputOrder() = runBlocking {
        val target = memoryDb()
        seedSharedCanonical(target)
        seedCapProvider(target)
        seedProviderB(target)
        val allRows = listOf(row("prov-cap", "model-a"), row("prov-b", "model-b"))
        val request = CompareLiveWiring.buildRequestFromDb(
            target, row("prov-cap", "model-a"), allRows,
            listOf(CompareKeySelection("prov-cap", "acct-cap", "key-cap"), selB()),
        )
        assertNotNull(request)
        val offerings = request!!.offerings
        // 單次比測上限 6（quota 保護先於第二個 selection；第二 provider 不出列）。
        assertEquals(CompareLiveWiring.COMPARE_MAX_OFFERINGS, offerings.size)
        assertTrue(offerings.all { it.providerId == "prov-cap" })
        assertTrue(offerings.none { it.providerId == "prov-b" })
        // 輸入順序保留：byProvider id ASC 的前 6 列，不重排。
        assertEquals((1..6).map { "off-cap$it" }, offerings.map { it.offeringId })
    }

    @Test
    fun finalForExactIdQueryReturnsExactFinalNotLatestDecoy() = runBlocking {
        val target = memoryDb()
        val repo = AtlasRepository(target)
        val feed = RoomCompareLiveSampleFeed(target)
        // 同一 triple 兩個 final sample（不同 key）＋一個 RUNNING：較新的 decoy 不得蓋掉 exact id。
        repo.save(sample("samp-old", accountId = "acct-old", credentialId = "key-old", startedAt = 1_000L, completedAt = 2_000L))
        repo.save(sample("samp-run", startedAt = 3_000L, completedAt = null, outcome = Outcome.RUNNING))
        repo.save(sample("samp-new", accountId = "acct-new", credentialId = "key-new", startedAt = 5_000L, completedAt = 6_000L))

        // 精確 id：回該 id 的 final（completedAt 非 null），不吃 latest decoy。
        val exact = feed.finalFor("samp-old")
        assertNotNull(exact)
        assertEquals("samp-old", exact!!.id)
        assertEquals(2_000L, exact.completedAt)
        assertEquals(400L, exact.ttftMs)
        assertEquals(110.0, exact.tps!!, 1e-9)
        assertEquals(8L, exact.usage.inputTokens)
        assertEquals(12L, exact.usage.outputTokens)
        assertEquals(TelemetrySource.COMPARE_LIVE, exact.source)
        // decoy 確實存在且 legacy latestFinalFor 會取最新；exact-id 與 latest 語意分離。
        assertEquals("samp-new", feed.latestFinalFor("p-feed", "ep-feed", "model-feed")!!.id)
        // RUNNING（completedAt null）不是 final；缺席／空 id 一律 null（未知，不補 0）。
        assertNull(feed.finalFor("samp-run"))
        assertNull(feed.finalFor("samp-missing"))
        assertNull(feed.finalFor(""))
        // 唯讀：查詢不改動 rows。
        assertEquals(1, target.sampleDao().countById("samp-old"))
    }

    private companion object {
        const val CANONICAL = "canonical-shared"
        const val URL_A = "https://prov-a.example/v1/chat"
        const val URL_B = "https://prov-b.example/v1/chat"
    }
}
