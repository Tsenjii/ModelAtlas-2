package dev.modelatlas.app.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiAccountQuotaMapping
import dev.modelatlas.core.newapi.NewApiAccountScope
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiDomainMapper
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiExpiryMeaning
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiTokenQuota
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * X-014 slice-3 QUOTA_LOW device 驗證（TRACEABILITY X-014／spec §6.3 item F，
 * vectors D1–D7）。
 *
 * 真實 in-memory Room＋真實 [EventStore]＋真實 [NewApiSnapshotStore]＋真實
 * [EventPipeline]／[StoreEventSink]；只有投遞端是 fake [DeliverySink]（記錄呼叫，
 * 不碰 Android 通知 framework）。形狀 mirror [PriceChangeDeliveryFlowTest]（slice-2
 * sibling）。
 *
 * 向量分工（每個測試只負責一件事，失敗時可直接定位）：
 * - **D1** reachability proof：以**真實 production 配額階梯**跑完整輸入矩陣——
 *   account 階梯 [NewApiDomainMapper.mapAccountQuota]（純函式，餵真 raw API 值）與
 *   token 階梯 [NewApiAdapter.fetchTokenQuota] → `parseTokenQuota`（真 HTTP ＋
 *   loopback MockWebServer，餵真 raw JSON）——再把每個結果經真
 *   [NewApiSnapshotStore] 落庫，斷言 derived／persisted state set **永不含**
 *   `QUOTA_LOW`。任何未來階梯改動若開始推導 QUOTA_LOW，本測試即紅。
 * - **D2** end-to-end：真 pipeline 把 `QUOTA_AVAILABLE → QUOTA_LOW` 掃描成**恰好
 *   一列** `quota_events`，fromState／toState／scopeKey／source／evidenceId 原樣；
 *   `quotaHistory(scopeKey).size == 1`。
 * - **D3** 401／UNKNOWN 觀測夾在兩個已知狀態之間 → **零列**事件（X-006＋X-001），
 *   且 UNKNOWN 不得成為下一段轉換的 pre-image（last-good 不被 401 推進）。
 * - **D4** 較舊觀測晚到 → head 不回捲、last-good 不動，因此它不得成為事件
 *   pre-image（pre-image 由 head 決定，非插入順序）。
 * - **D5** 七維 scope 隔離：同帳號兩把 credential → 兩個 scopeKey → 零 cross-write。
 * - **D6** append-only：預先存在的 `quota_events` 列在新事件寫入後逐欄 byte-identical。
 * - **D7** no migration：`quota_events` 真實 DDL／欄位／索引／無 FK 仍與 committed
 *   `app/schemas/dev.modelatlas.app.data.AtlasDatabase/15.json` 一致（an accidental
 *   schema change 紅 CI）。
 *
 * 誠實範圍說明（review 必讀）：
 * - 本檔**不含** `AtlasRepository.syncNewApi`→`scanQuota` 的整合級 wiring 斷言：
 *   trigger hook（spec §6.2 item C）需真 adapter＋憑證鏈路且屬另一個檔案 owner；
 *   此處以「真 store 事實 → 真管線 → 真事件列」的裝置級邊界覆蓋之（與 slice-2
 *   sibling 同一取捨，見其 KDoc）。
 * - D2 的 post-image 事實由 [NewApiSnapshotStore.saveManualQuota] 產生：它是目前
 *   **唯一** production 能持久化 `QUOTA_LOW` 的 writer（手填槽 MANUAL_QUOTA，與
 *   官方 ACCOUNT_QUOTA 不同 kind、不同槽）。本測試不假裝它是自動階梯的產物。
 * - D1 未納入 Dedicated `quotaStateOf` 與 OpenRouter key quota 兩階梯：兩者為
 *   private fun 且需真 dedicated transport／adapter fixture；其「無 QUOTA_LOW」
 *   並沒有 set-equality 守衛 pin（review F-05）——dedicated 階梯
 *   （`DedicatedSnapshotSync.kt:1081-1095`／`:636-640`）仍需一個 D1 孪生向
 *   量；此處不外推。
 * - 所有 JSON／數值皆為 synthetic fixture，只在 loopback 傳輸層出現；DB 只存 safe
 *   ref，無任何 key／PAT／raw body。
 */
@RunWith(AndroidJUnit4::class)
class QuotaLowEventFlowDeviceTest {

    private lateinit var db: AtlasDatabase
    private lateinit var store: EventStore
    private lateinit var snapshots: NewApiSnapshotStore
    private lateinit var server: MockWebServer

    private val tokenBodies = ArrayDeque<String>()

    /** 規則閘仿真：與 slice-2 sibling 同形——OFF 時如 production sink 回 true（已處理）。 */
    private class FakeDelivery(
        var ruleEnabled: Boolean = true,
        var succeed: Boolean = true,
    ) : DeliverySink {
        val delivered = mutableListOf<String>()
        val posts = mutableListOf<String>()
        val undelivered = mutableListOf<String>()
        var attempts = 0

        override fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean {
            attempts++
            if (!ruleEnabled) return true
            if (!succeed) return false
            delivered.add(eventId)
            posts.add(scopeKey)
            return true
        }

        override fun markUndelivered(eventId: String) {
            undelivered.add(eventId)
        }
    }

    /** 固定時鐘：過期判定與 evidence 時間戳必須可重現（loopback fixture）。 */
    private class FixedClock(private val millis: Long) : Clock {
        override fun epochMillis(): Long = millis
        override fun monotonicMillis(): Long = millis
    }

    /** 只供 loopback 傳輸改寫：https→真 loopback socket，path／Authorization 原樣。 */
    private class Loopback(private val server: MockWebServer) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val loop = server.url("/")
            val rewritten = chain.request().newBuilder()
                .url(url.newBuilder().scheme(loop.scheme).host(loop.host).port(loop.port).build())
                .build()
            return chain.proceed(rewritten)
        }
    }

    @Before
    fun setUp() {
        runBlocking {
            db = Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
            ).allowMainThreadQueries().build()
            store = EventStore(db)
            snapshots = NewApiSnapshotStore(db)
            db.providerDao().insert(ProviderEntity(PROVIDER, "X-014 QUOTA_LOW fixture"))
            db.accountDao().insert(AccountEntity(ACCOUNT, PROVIDER))
            db.credentialDao().insert(CredentialEntity(CREDENTIAL_A, ACCOUNT))
            db.credentialDao().insert(CredentialEntity(CREDENTIAL_B, ACCOUNT))
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?')
                if (path != TOKEN_PATH) {
                    return MockResponse().setResponseCode(404).setBody("""{"unexpected":"$path"}""")
                }
                val body = tokenBodies.removeFirstOrNull()
                    ?: return MockResponse().setResponseCode(500).setBody("""{"code":false}""")
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    // ── harness ──

    private fun pipeline(delivery: FakeDelivery): EventPipeline =
        EventPipeline(StoreEventSink(store), delivery)

    private fun accountQuotaScope() = NewApiSnapshotStore.accountQuotaScope(PROVIDER, ACCOUNT)

    private fun tokenQuotaScope(credential: String) =
        NewApiSnapshotStore.tokenQuotaScope(PROVIDER, ACCOUNT, InferenceKeyRef(credential))

    private fun accountEvidence(time: Long, httpStatus: Int = 200) = NewApiEvidence(
        source = NewApiSnapshotStore.ACCOUNT_SOURCE,
        observedAt = time,
        providerVersion = NewApiVersion.RC25,
        versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
        httpStatus = httpStatus,
    )

    private fun tokenEvidence(time: Long, httpStatus: Int = 200) = NewApiEvidence(
        source = NewApiSnapshotStore.TOKEN_SOURCE,
        observedAt = time,
        providerVersion = NewApiVersion.RC25,
        versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
        httpStatus = httpStatus,
    )

    private fun accountSuccess(time: Long, amount: Long?) =
        NewApiResult.Success(accountEvidence(time), accountQuota(amount))

    private fun accountUnauthorized(time: Long) =
        NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED, accountEvidence(time, httpStatus = 401))

    private fun accountQuota(amount: Long?) =
        NewApiAccountQuota(amount, amount?.minus(1), null, null, null, null)

    private fun tokenQuotaObserved(time: Long, granted: Long, available: Long?, state: QuotaState) =
        NewApiResult.Success(
            tokenEvidence(time),
            NewApiTokenQuota(
                totalGranted = granted,
                totalUsed = granted - (available ?: 0L),
                totalAvailable = available,
                unlimitedQuota = false,
                modelLimitsEnabled = false,
                expiresAtRaw = 0L,
                expiryMeaning = NewApiExpiryMeaning.ZERO_AMBIGUOUS,
                expired = null,
                state = state,
            ),
        )

    /** 單次解封裝（測試用）：失敗時以 AssertionError 指出實際結果，不靜默 null。 */
    private fun NewApiResult<NewApiTokenQuota>.requireValue(): NewApiTokenQuota =
        (this as? NewApiResult.Success)?.value
            ?: throw AssertionError("expected NewApiResult.Success, got $this")

    private fun quotaObservation(
        scopeKey: String,
        fromState: String?,
        toState: String?,
        source: String = NewApiSnapshotStore.ACCOUNT_SOURCE,
        observedAt: Long = 3_000L,
        success: Boolean = true,
        evidenceId: String? = null,
    ) = EventPipeline.QuotaObservation(
        base = EventPipeline.Observation(
            scopeKey = scopeKey,
            source = source,
            observedAt = observedAt,
            success = success,
            evidenceId = evidenceId,
        ),
        fromState = fromState,
        toState = toState,
    )

    private suspend fun factStates(scope: PricingScope, kind: SnapshotKind): List<String> =
        db.snapshotFactDao().history(scope.storageKey(), kind.name).map { it.state }

    // ================================================================= D1

    /**
     * D1 reachability proof：真實 account 階梯＋真實 token 階梯的完整輸入矩陣，
     * derived 與 persisted state set 都不得出現 `QUOTA_LOW`。
     */
    @Test
    fun d1_productionQuotaLaddersNeverDeriveOrPersistQuotaLow() = runBlocking {
        val accountQuota = accountQuotaScope()
        val mapperScope = NewApiAccountScope(PROVIDER, ACCOUNT, "e-x014")
        val derived = mutableSetOf<QuotaState>()
        var observedAt = 1_000L

        // ---- account 階梯（純函式；quota=null／<=0／>0 三分支全餵） ----
        val accountCases = listOf<Long?>(null, 0L, -1L, 1L, Long.MAX_VALUE)
        val accountStates = accountCases.map { amount ->
            val result = accountSuccess(observedAt, amount)
            val mapped = NewApiDomainMapper.mapAccountQuota(result, mapperScope)
                as NewApiAccountQuotaMapping.Mapped
            val expected = when (amount) {
                null -> QuotaState.UNKNOWN
                in Long.MIN_VALUE..0L -> QuotaState.QUOTA_EXHAUSTED
                else -> QuotaState.QUOTA_AVAILABLE
            }
            assertEquals("account ladder amount=$amount", expected, mapped.snapshot.state)
            derived += mapped.snapshot.state
            snapshots.persistAccountQuota(accountQuota, result)
            observedAt += 1L
            mapped.snapshot.state.name
        }
        // Failure 不映射狀態（Unmappable）：推導層不得憑空生出 state。
        assertTrue(
            NewApiDomainMapper.mapAccountQuota(accountUnauthorized(observedAt), mapperScope)
                is NewApiAccountQuotaMapping.Unmappable,
        )

        // ---- token 階梯（真 HTTP＋loopback；unlimited／<=0／過期／>0／缺欄位／壞封套） ----
        val adapter = NewApiAdapter(
            credentials = CredentialAccess { _, _ -> KEY_SECRET },
            client = OkHttpClient.Builder().addInterceptor(Loopback(server)).build(),
            clock = FixedClock(NOW_MILLIS),
        )
        val version = NewApiVersion.of(NewApiVersion.RC25)
        val tokenCases = listOf(
            TOKEN_UNLIMITED to QuotaState.UNLIMITED_CLAIMED,
            TOKEN_ZERO_AVAILABLE to QuotaState.QUOTA_EXHAUSTED,
            TOKEN_NEGATIVE_AVAILABLE to QuotaState.QUOTA_EXHAUSTED,
            TOKEN_EXPIRED to QuotaState.QUOTA_EXHAUSTED,
            TOKEN_POSITIVE_AVAILABLE to QuotaState.QUOTA_AVAILABLE,
            TOKEN_MISSING_FIELDS to QuotaState.UNKNOWN,
        )
        val tokenStates = tokenCases.map { (body, expected) ->
            tokenBodies.addLast(body)
            val result = adapter.fetchTokenQuota(BASE_URL, InferenceKeyRef(CREDENTIAL_A), version)
            assertEquals("token ladder body=$body", expected, result.requireValue().state)
            derived += result.requireValue().state
            snapshots.persistTokenQuota(tokenQuotaScope(CREDENTIAL_A), result)
            observedAt += 1L
            result.requireValue().state.name
        }
        // 壞封套（code=false）不映射狀態。
        tokenBodies.addLast("""{"code":false,"message":"fixture"}""")
        assertTrue(
            adapter.fetchTokenQuota(BASE_URL, InferenceKeyRef(CREDENTIAL_A), version)
                is NewApiResult.Failure,
        )

        // ---- derived 集合：不含 QUOTA_LOW，且每個成員都被真實生產過 ----
        assertEquals(
            setOf(
                QuotaState.UNKNOWN,
                QuotaState.QUOTA_AVAILABLE,
                QuotaState.QUOTA_EXHAUSTED,
                QuotaState.UNLIMITED_CLAIMED,
            ),
            derived,
        )
        assertFalse("no production ladder may derive QUOTA_LOW", derived.contains(QuotaState.QUOTA_LOW))

        // ---- persisted 事實：snapshot_facts.state 同樣不得出現 QUOTA_LOW ----
        // 逐分支核對（不只是集合比對），確保每個輸入矩陣分支都真的走過真 writer。
        // 以排序後比較：token 階梯的 evidence 時間戳來自 adapter 的固定時鐘（同值），
        // DAO history 的第二排序鍵是隨機 evidenceId，順序不可作 oracle。
        val tokenScopeA = tokenQuotaScope(CREDENTIAL_A)
        assertEquals(accountStates.sorted(), factStates(accountQuota, SnapshotKind.ACCOUNT_QUOTA).sorted())
        assertEquals(tokenStates.sorted(), factStates(tokenScopeA, SnapshotKind.TOKEN_QUOTA).sorted())
        val persistedStates = (accountStates + tokenStates).toSet()
        assertFalse(
            "persisted quota state must never be QUOTA_LOW",
            persistedStates.contains(QuotaState.QUOTA_LOW.name),
        )
        assertEquals(derived.map { it.name }.toSet(), persistedStates)
        assertEquals(0, db.quotaEventDao().count())
    }

    // ================================================================= D2

    /** D2：`QUOTA_AVAILABLE → QUOTA_LOW` 經真 pipeline 落**恰好一列** `quota_events`。 */
    @Test
    fun d2_availableToQuotaLowPersistsExactlyOneEventRow() = runBlocking {
        val accountQuota = accountQuotaScope()
        val scopeKey = accountQuota.storageKey()

        // 真 pre-image：官方 account 階梯寫出 QUOTA_AVAILABLE 事實。
        snapshots.persistAccountQuota(accountQuota, accountSuccess(1_000L, 500L))
        val preImage = snapshots.quota(accountQuota, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals(QuotaState.QUOTA_AVAILABLE.name, preImage.latest!!.state)

        // 真 post-image 事實：目前唯一能持久化 QUOTA_LOW 的 production writer。
        val manualId = snapshots.saveManualQuota(
            QuotaSnapshot(
                id = "caller-quota-x014",
                scope = accountQuota,
                observedAt = 2_000L,
                state = QuotaState.QUOTA_LOW,
                source = NewApiSnapshotStore.ACCOUNT_SOURCE,
                evidenceId = preImage.latest!!.evidenceId,
            ),
        )
        assertEquals(
            QuotaState.QUOTA_LOW.name,
            snapshots.quota(accountQuota, SnapshotKind.MANUAL_QUOTA).latest!!.state,
        )

        val delivery = FakeDelivery()
        val result = pipeline(delivery).scanQuota(
            quotaObservation(
                scopeKey = scopeKey,
                fromState = preImage.latest!!.state,
                toState = QuotaState.QUOTA_LOW.name,
                source = MANUAL_SOURCE,
                observedAt = 2_000L,
                evidenceId = manualId,
            ),
        )

        assertTrue("a real transition must be written", result.written)
        assertTrue(result.delivered)
        assertEquals(1, result.deliveryAttempts)

        val rows = store.quotaHistory(scopeKey)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(scopeKey, row.scopeKey)
        assertEquals(QuotaState.QUOTA_AVAILABLE.name, row.fromState)
        assertEquals(QuotaState.QUOTA_LOW.name, row.toState)
        assertEquals(MANUAL_SOURCE, row.source)
        assertEquals(manualId, row.evidenceId)
        assertEquals(2_000L, row.observedAt)
        assertEquals(1, db.quotaEventDao().count())
        assertEquals(listOf(row.eventId), delivery.delivered)
        assertEquals(listOf(scopeKey), delivery.posts)
        assertEquals(0, delivery.undelivered.size)
    }

    // ================================================================= D3

    /**
     * D3：兩個已知狀態之間夾一個 401／UNKNOWN 觀測 → 零事件列；UNKNOWN 也不得
     * 成為下一段真轉換的 pre-image（last-good 不被 401 推進，X-006＋X-001）。
     */
    @Test
    fun d3_unknownObservationBetweenKnownStatesEmitsNoEvent() = runBlocking {
        val accountQuota = accountQuotaScope()
        val scopeKey = accountQuota.storageKey()
        val delivery = FakeDelivery()

        snapshots.persistAccountQuota(accountQuota, accountSuccess(1_000L, 500L))
        val lastGoodBefore = snapshots.quota(accountQuota, SnapshotKind.ACCOUNT_QUOTA).lastGood
        assertEquals(1_000L, lastGoodBefore!!.observedAt)
        assertEquals(QuotaState.QUOTA_AVAILABLE, lastGoodBefore.state)

        // 401 觀測：最新事實降為 UNKNOWN，last-good 原樣不動。
        snapshots.persistAccountQuota(accountQuota, accountUnauthorized(2_000L))
        val after401 = snapshots.quota(accountQuota, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals(QuotaState.UNKNOWN.name, after401.latest!!.state)
        assertEquals(lastGoodBefore, after401.lastGood)

        // 401 本身（success=false）不掃描、不寫、不投遞。
        val failed = pipeline(delivery).scanQuota(
            quotaObservation(
                scopeKey = scopeKey,
                fromState = QuotaState.QUOTA_AVAILABLE.name,
                toState = QuotaState.QUOTA_EXHAUSTED.name,
                observedAt = 2_000L,
                success = false,
            ),
        )
        assertFalse(failed.written)
        assertEquals(EventPipeline.REASON_SYNC_FAILURE, failed.reason)

        // UNKNOWN 側（字串層級亦為未知）不構成轉換。
        val unknownSide = pipeline(delivery).scanQuota(
            quotaObservation(
                scopeKey = scopeKey,
                fromState = QuotaState.QUOTA_AVAILABLE.name,
                toState = QuotaState.UNKNOWN.name,
                observedAt = 2_000L,
            ),
        )
        assertFalse(unknownSide.written)
        assertEquals(EventPipeline.REASON_UNKNOWN_SIDE, unknownSide.reason)
        assertEquals(0, store.quotaHistory(scopeKey).size)
        assertEquals(0, db.quotaEventDao().count())
        assertEquals(0, delivery.attempts)

        // 真正的 AVAILABLE → EXHAUSTED 只落一列，且 pre-image 是 last-known 的
        // QUOTA_AVAILABLE（不是中途的 UNKNOWN）。
        snapshots.persistAccountQuota(accountQuota, accountSuccess(3_000L, 0L))
        val exhausted = snapshots.quota(accountQuota, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals(QuotaState.QUOTA_EXHAUSTED.name, exhausted.latest!!.state)
        val real = pipeline(delivery).scanQuota(
            quotaObservation(
                scopeKey = scopeKey,
                fromState = after401.lastGood!!.state.name,
                toState = exhausted.latest!!.state,
                observedAt = 3_000L,
                evidenceId = exhausted.latest!!.evidenceId,
            ),
        )
        assertTrue(real.written)
        val rows = store.quotaHistory(scopeKey)
        assertEquals(1, rows.size)
        assertEquals(QuotaState.QUOTA_AVAILABLE.name, rows.single().fromState)
        assertEquals(QuotaState.QUOTA_EXHAUSTED.name, rows.single().toState)
        assertEquals(1, db.quotaEventDao().count())
    }

    // ================================================================= D4

    /**
     * D4：較舊觀測晚到 → head 不回捲、last-good 不動，因此它不得成為事件 pre-image。
     * 完整驅動真 [NewApiSnapshotStore.persistAccountQuota]（非 store-level 近似）。
     */
    @Test
    fun d4_olderObservationCannotBecomeEventPreImage() = runBlocking {
        val accountQuota = accountQuotaScope()
        val scopeKey = accountQuota.storageKey()

        snapshots.persistAccountQuota(accountQuota, accountSuccess(3_000L, 500L))
        val newest = snapshots.quota(accountQuota, SnapshotKind.ACCOUNT_QUOTA)

        // 晚到的舊觀測（t=1000, quota=0 → QUOTA_EXHAUSTED）。
        snapshots.persistAccountQuota(accountQuota, accountSuccess(1_000L, 0L))
        val afterOlder = snapshots.quota(accountQuota, SnapshotKind.ACCOUNT_QUOTA)

        // head 由 observedAt 決定：舊觀測不得成為 latest，也不得推進 last-good。
        assertEquals(3_000L, afterOlder.latest!!.observedAt)
        assertEquals(QuotaState.QUOTA_AVAILABLE.name, afterOlder.latest!!.state)
        assertEquals(newest.latest!!.evidenceId, afterOlder.latest!!.evidenceId)
        assertEquals(newest.lastGood, afterOlder.lastGood)
        assertEquals(
            listOf(3_000L, 1_000L),
            snapshots.history(accountQuota, SnapshotKind.ACCOUNT_QUOTA).map { it.observedAt },
        )

        // 以 head 讀出的 pre-image（仍是 QUOTA_AVAILABLE）驅動管線 → 無轉換、零列。
        val delivery = FakeDelivery()
        val headFact = db.snapshotFactDao().head(scopeKey, SnapshotKind.ACCOUNT_QUOTA.name)!!
        val headState = db.snapshotFactDao().evidence(headFact.latestEvidenceId)!!.state
        assertEquals(QuotaState.QUOTA_AVAILABLE.name, headState)
        val result = pipeline(delivery).scanQuota(
            quotaObservation(
                scopeKey = scopeKey,
                fromState = headState,
                toState = headState,
                observedAt = 1_000L,
                evidenceId = headFact.latestEvidenceId,
            ),
        )
        assertFalse("a stale arrival must not create a transition", result.written)
        assertEquals(EventPipeline.REASON_NO_TRANSITION, result.reason)
        assertEquals(0, store.quotaHistory(scopeKey).size)
        assertEquals(0, db.quotaEventDao().count())
        assertEquals(0, delivery.attempts)
    }

    // ================================================================= D5

    /** D5：同帳號兩把 credential → 兩個 scopeKey → 零 cross-write。 */
    @Test
    fun d5_twoCredentialsStayIsolatedAcrossSevenDimensions() = runBlocking {
        val scopeA = tokenQuotaScope(CREDENTIAL_A)
        val scopeB = tokenQuotaScope(CREDENTIAL_B)
        val keyA = scopeA.storageKey()
        val keyB = scopeB.storageKey()
        assertNotEquals(keyA, keyB)
        assertEquals(CREDENTIAL_A, parseScopeKey(keyA)!![2])
        assertEquals(CREDENTIAL_B, parseScopeKey(keyB)!![2])

        snapshots.persistTokenQuota(scopeA, tokenQuotaObserved(1_000L, 200L, 100L, QuotaState.QUOTA_AVAILABLE))
        snapshots.persistTokenQuota(scopeB, tokenQuotaObserved(1_000L, 1_800L, 900L, QuotaState.QUOTA_AVAILABLE))
        assertEquals(
            QuotaState.QUOTA_AVAILABLE.name,
            snapshots.quota(scopeA, SnapshotKind.TOKEN_QUOTA).latest!!.state,
        )
        assertEquals(
            900L,
            snapshots.quota(scopeB, SnapshotKind.TOKEN_QUOTA).latest!!.remainingRaw,
        )

        val delivery = FakeDelivery()
        val result = pipeline(delivery).scanQuota(
            quotaObservation(
                scopeKey = keyA,
                fromState = QuotaState.QUOTA_AVAILABLE.name,
                toState = QuotaState.QUOTA_LOW.name,
                source = NewApiSnapshotStore.TOKEN_SOURCE,
                observedAt = 2_000L,
                evidenceId = snapshots.quota(scopeA, SnapshotKind.TOKEN_QUOTA).latest!!.evidenceId,
            ),
        )
        assertTrue(result.written)
        assertEquals(1, store.quotaHistory(keyA).size)
        assertEquals(keyA, store.quotaHistory(keyA).single().scopeKey)
        assertEquals(0, store.quotaHistory(keyB).size)
        assertEquals(1, db.quotaEventDao().count())
        assertEquals(listOf(keyA), delivery.posts)
        // 另一把 credential 的官方事實完全未被事件寫入影響。
        assertEquals(
            900L,
            snapshots.quota(scopeB, SnapshotKind.TOKEN_QUOTA).latest!!.remainingRaw,
        )
    }

    // ================================================================= D6

    /** D6：append-only——預先存在的列在新事件寫入後逐欄 byte-identical。 */
    @Test
    fun d6_preExistingEventRowIsByteIdenticalAfterNewWrite() = runBlocking {
        val accountQuota = accountQuotaScope()
        val scopeKey = accountQuota.storageKey()

        store.recordQuotaTransition(
            scopeKey = scopeKey,
            fromState = QuotaState.QUOTA_AVAILABLE.name,
            toState = QuotaState.QUOTA_LOW.name,
            observedAt = 1_000L,
            source = NewApiSnapshotStore.ACCOUNT_SOURCE,
            evidenceId = "ev-x014-seed",
            success = true,
            eventId = SEED_EVENT_ID,
        )
        val before = db.quotaEventDao().getById(SEED_EVENT_ID)!!

        val result = pipeline(FakeDelivery()).scanQuota(
            quotaObservation(
                scopeKey = scopeKey,
                fromState = QuotaState.QUOTA_EXHAUSTED.name,
                toState = QuotaState.QUOTA_LOW.name,
                observedAt = 2_000L,
                evidenceId = "ev-x014-new",
            ),
        )
        assertTrue(result.written)

        assertEquals(
            "append-only: the pre-seeded row must not change",
            before,
            db.quotaEventDao().getById(SEED_EVENT_ID),
        )
        assertEquals(2, db.quotaEventDao().count())
        val history = store.quotaHistory(scopeKey)
        assertEquals(listOf(2_000L, 1_000L), history.map { it.observedAt })
        val seeded = history.single { it.eventId == SEED_EVENT_ID }
        assertEquals(before, seeded)
    }

    // ================================================================= D7

    /**
     * D7 no migration：`quota_events` 的真實 DDL／欄位／索引／無 FK 仍與 committed
     * `app/schemas/dev.modelatlas.app.data.AtlasDatabase/15.json` 一致。
     * normalization 與 [AtlasMigration14To15Test] 同形（`${TABLE_NAME}` 代入、去掉
     * `IF NOT EXISTS `），另收斂空白。
     */
    @Test
    fun d7_quotaEventsSchemaMatchesCommittedV15Ddl() {
        val sql = db.openHelper.writableDatabase
        assertEquals(
            mapOf(
                "eventId" to Triple("TEXT", 1, 1),
                "scopeKey" to Triple("TEXT", 1, 0),
                "fromState" to Triple("TEXT", 0, 0),
                "toState" to Triple("TEXT", 0, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
                "source" to Triple("TEXT", 1, 0),
                "evidenceId" to Triple("TEXT", 0, 0),
            ),
            tableColumns(sql, "quota_events"),
        )
        assertEquals(listOf("scopeKey"), indexColumns(sql, "index_quota_events_scope"))
        assertEquals(listOf("observedAt"), indexColumns(sql, "index_quota_events_observed"))
        sql.query("PRAGMA foreign_key_list(`quota_events`)").use { assertTrue(!it.moveToFirst()) }

        assertEquals(
            normalize(TABLE, QUOTA_EVENTS),
            normalize(TABLE, requireNotNull(objectDdl(sql, "quota_events"))),
        )
        assertEquals(
            normalize(TABLE, QUOTA_EVENTS_SCOPE_INDEX),
            normalize(TABLE, requireNotNull(objectDdl(sql, "index_quota_events_scope"))),
        )
        assertEquals(
            normalize(TABLE, QUOTA_EVENTS_OBSERVED_INDEX),
            normalize(TABLE, requireNotNull(objectDdl(sql, "index_quota_events_observed"))),
        )
    }

    // ── schema helpers ──

    /**
     * DDL canonical form：代入 `${TABLE_NAME}`、去掉 `IF NOT EXISTS `，並移除所有
     * 空白（Room 生成的括號前空格與 SQLite 落庫文字不完全一致；空白無語意，欄位
     * 名／型別／順序／PK／索引定義仍逐字比對）。
     */
    private fun normalize(name: String, ddl: String): String = ddl
        .replace("\${TABLE_NAME}", name)
        .replace("IF NOT EXISTS ", "")
        .replace(Regex("\\s+"), "")
        .trim()

    private fun objectDdl(db: SupportSQLiteDatabase, name: String): String? =
        db.query("SELECT sql FROM sqlite_master WHERE name = ? AND sql IS NOT NULL", arrayOf(name)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Map<String, Triple<String, Int, Int>> =
        buildMap {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        Triple(
                            cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                        ),
                    )
                }
            }
        }

    private fun indexColumns(db: SupportSQLiteDatabase, index: String): List<String> = buildList {
        db.query("PRAGMA index_info(`$index`)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private companion object {
        const val PROVIDER = "p-x014-quotalow"
        const val ACCOUNT = "a-x014-quotalow"
        const val CREDENTIAL_A = "c-x014-a"
        const val CREDENTIAL_B = "c-x014-b"
        const val KEY_SECRET = "x014-synthetic-inference-key"
        const val BASE_URL = "https://quotalow-fixture-x014.example.com"
        const val TOKEN_PATH = "/api/usage/token/"
        const val NOW_MILLIS = 1_700_000_000_000L
        const val MANUAL_SOURCE = "USER"
        const val SEED_EVENT_ID = "quota-x014-seed"
        const val TABLE = "quota_events"

        /** committed 15.json `quota_events` createSql（`${TABLE_NAME}` 未代入）。 */
        const val QUOTA_EVENTS = "CREATE TABLE IF NOT EXISTS " +
            "`\${TABLE_NAME}` (`eventId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, " +
            "`fromState` TEXT, `toState` TEXT, `observedAt` INTEGER NOT NULL, " +
            "`source` TEXT NOT NULL, `evidenceId` TEXT, PRIMARY KEY(`eventId`))"

        const val QUOTA_EVENTS_SCOPE_INDEX = "CREATE INDEX IF NOT EXISTS " +
            "`index_quota_events_scope` ON `\${TABLE_NAME}` (`scopeKey`)"

        const val QUOTA_EVENTS_OBSERVED_INDEX = "CREATE INDEX IF NOT EXISTS " +
            "`index_quota_events_observed` ON `\${TABLE_NAME}` (`observedAt`)"

        // rc.25 source-derived synthetic token bodies（loopback only，非產品資料）。
        val TOKEN_UNLIMITED =
            """{"code":true,"data":{"total_granted":0,"total_used":0,"unlimited_quota":true,"expires_at":0}}"""
        val TOKEN_ZERO_AVAILABLE =
            """{"code":true,"data":{"total_granted":500,"total_used":500,"total_available":0,"unlimited_quota":false,"expires_at":0}}"""
        val TOKEN_NEGATIVE_AVAILABLE =
            """{"code":true,"data":{"total_granted":500,"total_used":900,"total_available":-400,"unlimited_quota":false,"expires_at":0}}"""
        val TOKEN_EXPIRED =
            """{"code":true,"data":{"expires_at":1600000000,"unlimited_quota":false}}"""
        val TOKEN_POSITIVE_AVAILABLE =
            """{"code":true,"data":{"total_granted":1000,"total_used":400,"total_available":600,"unlimited_quota":false,"expires_at":0}}"""
        val TOKEN_MISSING_FIELDS =
            """{"code":true,"data":{"object":"token_usage"}}"""
    }
}