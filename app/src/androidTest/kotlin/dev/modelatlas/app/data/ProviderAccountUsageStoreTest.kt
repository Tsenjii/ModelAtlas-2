package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NINT-005A provider-reported account request count store tests（device androidTest；本檔唯一新增）。
 *
 * 契約來源：Contract §2.2（PROVIDER_WIDE／PROVIDER_REPORTED 與 MY_KEY_LOCAL 分離）、
 * §6.4（`PROVIDER_ACCOUNT_USAGE` 專用槽，`/api/user/self` 等 structured surfaces）、
 * §23.3（加入 My Models 後自動接上 usage，缺資料保持 Unknown／No data）；
 * PRODUCT_SPEC §10（provenance 分槽）／§12（PAT＝account／key＝inference 不互冒充）／
 * §18（完成條件）；RESEARCH_POLICY（Unknown≠0、證據優先、來源不互冒充）。
 *
 * 測試方式（review 必讀）：
 * - 真 in-memory Room（當前 version 13 entities＋real DAOs）＋真 [ProviderAccountUsageStore]。
 * - **本檔完全沒有 HTTP 棧**：所有觀測都是 [AccountQuotaObservation] typed 域物件夾具，
 *   欄位形狀照真 `NewApiAdapter` 產出的 `NewApiEvidence`／`NewApiAccountQuota`／
 *   `NewApiCapabilities` 逐欄複製（source 字串、rc.25 version、httpStatus、observedAt），
 *   不是自己發明的假 payload。HTTP 次序、`/api/user/self` 只打一次、adapter 解析行為
 *   分別由 observer／flow／core parse 測試檔負責，本檔不重複冒充。
 * - 夾具全部為測試專用：`sk-ab` 只是為了走到 safe-ref 拒絕路徑的短假字串（本檔無任何
 *   真 key／PAT／secret，也沒有 vault、沒有 Authorization）；帳號 `group/role/status`
 *   帶哨兵值 [GROUP_CANARY] 專門驗證「不落 role／status／group metadata」。
 * - 拒絕路徑斷言：**零寫入＋明確 fail-closed**是硬條件（守門拋 `IllegalArgumentException`，
 *   facts／heads／evidence／typed reader 全不動，訊息不得回顯 ref）。靜默不寫同樣不接受：
 *   那會讓「守門失效」與「本來沒資料」無法區分。repository 的 observer hook 才是負責把
 *   ordinary 例外 fail-open、cancel 往上传的那一層，本檔不重複測 hook。
 * - Counter semantics 一律 UNSPECIFIED：不得猜累計／視窗／rate／delta，也不得為了
 *   「有個數字」而造 label；0 是真觀測值、null 是 Unknown，兩者不可互換。
 * - 每個 case 自帶 isolated DB，只對自己 DB 斷言；不做全域 DB 斷言。
 */
@RunWith(AndroidJUnit4::class)
class ProviderAccountUsageStoreTest {

    private val open = mutableListOf<AtlasDatabase>()

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    // ===== 1. happy path：認證 session 的 account 回應逐欄落庫＋provenance＋typed reader =====

    @Test
    fun certifiedObservationStoresExactCountWithProvenanceAndTypedReader() = runBlocking {
        val db = memoryDb()
        val provider = "usage-ok-p1"
        val account = "usage-acc-p1"
        val patRef = "pat-usage-p1"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        // Observer 介面路徑（repository production 接的就是這一條）也必須真的落庫。
        store.onObserved(goodObservation(provider, account, patRef, 5_000L, requestCount = 42L))
        // store 路徑另外再記一次，證明兩條入口同一真相且 evidence id 每次都新。
        val returned = store.record(goodObservation(provider, account, patRef, 6_000L, requestCount = 43L))

        val read = store.observation(provider, account)
        assertNotNull(read.latest)
        assertNotNull(read.lastGood)
        assertEquals(read.latest!!.evidenceId, returned.latest!!.evidenceId)
        assertEquals(read.lastGood!!.evidenceId, returned.lastGood!!.evidenceId)

        val fact = read.latest!!
        assertEquals(43L, fact.requestCountRaw)
        assertEquals("OK", fact.status)
        assertEquals(200, fact.httpStatus)
        assertEquals(NewApiVersion.RC25, fact.providerVersion)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, fact.source)
        assertEquals(ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name, fact.kind)
        assertEquals(provider, fact.providerId)
        assertEquals(account, fact.accountId)
        assertEquals(patRef, fact.credentialId)
        assertEquals(6_000L, fact.observedAt)
        assertTrue("usage evidence id 必須是 usage: 前綴", fact.evidenceId.startsWith("usage:"))
        val firstRound = store.history(provider, account).first { it.observedAt == 5_000L }
        assertNotEquals("每輪都是全新 evidence", firstRound.evidenceId, fact.evidenceId)

        // accountEndpointObserved 只在真的拿到 account HTTP 回應時為 true。
        assertTrue(fact.accountEndpointObserved)

        // Counter semantics 固定 UNSPECIFIED：不猜累計／視窗／rate／delta。
        assertTrue("counter semantics 必須標 UNSPECIFIED", fact.counterSemantics.contains("UNSPECIFIED"))

        // Scope：provider×account 自有長度前綴 namespace，不是共用 PricingScope encoder，
        // 也不含 credential／endpoint／model／group。
        val endpointId = "e-$provider"
        assertEquals(providerAccountUsageScopeKey(provider, account), fact.scopeKey)
        assertNotEquals(
            NewApiSnapshotStore.accountQuotaScope(provider, account).storageKey(), fact.scopeKey,
        )
        assertFalse(fact.scopeKey.contains(patRef))
        assertFalse(fact.scopeKey.contains(endpointId))
        assertFalse(fact.scopeKey.contains(GROUP_CANARY))

        // Evidence 鏈：providerId／status／timestamp／source 與 fact 一致；modelId 空
        // （帳號層級總量不是 per-model），無 body／無 PII。
        val evidence = db.evidenceDao().getById(fact.evidenceId)!!
        assertEquals(fact.evidenceId, evidence.requestId)
        assertEquals(provider, evidence.providerId)
        assertEquals("", evidence.modelId)
        assertEquals("OK", evidence.outcome)
        assertEquals(200, evidence.status)
        assertEquals(fact.observedAt, evidence.timestamp)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, evidence.source)
        assertFalse("帳號 group／role／status 不落庫", evidence.toString().contains(GROUP_CANARY))

        // FK RESTRICT：只捕獲特定約束例外，其他例外照常拋出（不吞例外假 pass）。
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM evidence WHERE id = ?", arrayOf(fact.evidenceId))
            throw AssertionError("FK RESTRICT must block evidence delete")
        } catch (_: SQLiteConstraintException) {
        }
        assertNotNull(store.observation(provider, account).latest)

        // 欄位集合鎖死：只允許鎖定欄位，沒有 username／email／body／window／rate／delta 欄。
        assertEquals(
            listOf(
                "evidenceId", "scopeKey", "kind", "providerId", "accountId", "credentialId",
                "observedAt", "status", "providerVersion", "httpStatus", "source",
                "requestCountRaw", "counterSemantics", "accountEndpointObserved",
            ),
            columns(db, "provider_account_usage_facts"),
        )
        assertEquals(
            listOf(
                "scopeKey", "kind", "latestEvidenceId", "latestObservedAt",
                "lastGoodEvidenceId", "lastGoodObservedAt",
            ),
            columns(db, "provider_account_usage_heads"),
        )

        // 零副作用（isolated DB）：quota／price／perf／samples／My Models／external 全零。
        assertEquals(2L, count(db, "provider_account_usage_facts"))
        assertEquals(1L, count(db, "provider_account_usage_heads"))
        assertEquals(2L, count(db, "evidence"))
        assertEquals(0L, count(db, "snapshot_facts"))
        assertEquals(0L, count(db, "snapshot_heads"))
        assertEquals(0L, count(db, "quota_snapshots"))
        assertEquals(0L, count(db, "price_snapshots"))
        assertEquals(0L, count(db, "provider_perf_facts"))
        assertEquals(0L, count(db, "samples"))
        assertEquals(0L, count(db, "my_models"))
        assertEquals(0L, count(db, "external_observations"))
    }

    // ===== 2. 0 是真觀測值，不是 Unknown =====

    @Test
    fun zeroRequestCountIsStoredAsRealObservationNotUnknown() = runBlocking {
        val db = memoryDb()
        val provider = "usage-zero"
        val account = "usage-acc-zero"
        val patRef = "pat-usage-zero"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        val stored = store.record(
            goodObservation(
                provider, account, patRef, 1_000L,
                requestCount = 0L, quota = 0L, usedQuota = 0L,
            ),
        )

        assertEquals(0L, stored.lastGood!!.requestCountRaw)
        assertEquals("0 不得被當 Unknown 丟掉", 0L, store.observation(provider, account).lastGood!!.requestCountRaw)
        assertTrue(stored.lastGood!!.accountEndpointObserved)
        // usedQuota／quota 屬 ACCOUNT_QUOTA 槽，usage 槽不得複製或改寫它們。
        assertEquals(0L, count(db, "quota_snapshots"))
        assertEquals(0L, count(db, "snapshot_facts"))
    }

    // ===== 3. 缺欄 null：保留歷史但不推進 last-good（Unknown≠0） =====

    @Test
    fun missingRequestCountKeepsHistoryWithoutPromotingLastGood() = runBlocking {
        val db = memoryDb()
        val provider = "usage-missing"
        val account = "usage-acc-missing"
        val patRef = "pat-usage-missing"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        // Phase A：只有 no-data 觀測 → 有 latest（history 可稽核），但沒有 last-good。
        val first = store.record(goodObservation(provider, account, patRef, 1_000L, requestCount = null))
        assertNotNull("no-data 仍要留歷史", first.latest)
        assertNull("no-data 不得當 good", first.lastGood)
        assertNull(first.latest!!.requestCountRaw)
        assertTrue("真 2xx account 回應仍是 observed endpoint", first.latest!!.accountEndpointObserved)
        assertEquals(1, store.history(provider, account).size)

        // Phase B：先有一輪 good，再來一輪 no-data → last-good 不動，latest 換新。
        store.record(goodObservation(provider, account, patRef, 2_000L, requestCount = 7L))
        val afterNoData = store.record(goodObservation(provider, account, patRef, 3_000L, requestCount = null))
        assertEquals(3_000L, afterNoData.latest!!.observedAt)
        assertNull(afterNoData.latest!!.requestCountRaw)
        assertEquals(2_000L, afterNoData.lastGood!!.observedAt)
        assertEquals(7L, afterNoData.lastGood!!.requestCountRaw)
        assertEquals(3, store.history(provider, account).size)
    }

    // ===== 4. 負值：MALFORMED 標記＋保留 last-good =====

    @Test
    fun negativeRequestCountIsMalformedAndPreservesLastGood() = runBlocking {
        val db = memoryDb()
        val provider = "usage-negative"
        val account = "usage-acc-negative"
        val patRef = "pat-usage-negative"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        store.record(goodObservation(provider, account, patRef, 1_000L, requestCount = 100L))
        val malformed = store.record(goodObservation(provider, account, patRef, 2_000L, requestCount = -1L))

        val marker = malformed.latest!!
        assertEquals("MALFORMED", marker.status)
        assertNull("負值不得落成數字", marker.requestCountRaw)
        assertEquals(200, marker.httpStatus)
        assertTrue("負值也是真的 account 回應", marker.accountEndpointObserved)
        assertEquals("last-good 不得被負值污染", 1_000L, malformed.lastGood!!.observedAt)
        assertEquals(100L, malformed.lastGood!!.requestCountRaw)
        assertEquals("evidence outcome 跟 status name 一致", "MALFORMED", db.evidenceDao().getById(marker.evidenceId)!!.outcome)
        assertEquals(2, store.history(provider, account).size)
    }

    // ===== 5. Long 精度逐位保留＋reset（較新的較小值）不被當雜訊丟棄 =====

    @Test
    fun longPrecisionAndNewerSmallerCountAreAcceptedExactly() = runBlocking {
        val db = memoryDb()
        val provider = "usage-precision"
        val account = "usage-acc-precision"
        val patRef = "pat-usage-precision"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        // 2^53+1 與 Long.MAX_VALUE：任何 double／int 中途轉換都會失真。
        val beyondDouble = 9_007_199_254_740_993L
        store.record(goodObservation(provider, account, patRef, 1_000L, requestCount = beyondDouble))
        assertEquals(beyondDouble, store.observation(provider, account).lastGood!!.requestCountRaw)

        store.record(goodObservation(provider, account, patRef, 2_000L, requestCount = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, store.observation(provider, account).lastGood!!.requestCountRaw)

        // Reset：站方計數歸零／重算後變小是合法事實，不得假設單調而拒收。
        val reset = store.record(goodObservation(provider, account, patRef, 3_000L, requestCount = 5L))
        assertEquals(5L, reset.lastGood!!.requestCountRaw)
        assertEquals(3_000L, reset.lastGood!!.observedAt)
        assertEquals(3, store.history(provider, account).size)
    }

    // ===== 6. 同時與較舊觀測：append 歷史，不搶 head、不去重 =====

    @Test
    fun equalAndOlderTimestampsAppendWithoutStealingHeadOrLastGood() = runBlocking {
        val db = memoryDb()
        val provider = "usage-order"
        val account = "usage-acc-order"
        val patRef = "pat-usage-order"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        val newest = store.record(goodObservation(provider, account, patRef, 3_000L, requestCount = 30L))
        // 同時衝突：去重／覆蓋都會吃掉一個真觀測。
        val tie = store.record(goodObservation(provider, account, patRef, 3_000L, requestCount = 31L))
        assertEquals("同時不得搶 head", newest.latest!!.evidenceId, tie.latest!!.evidenceId)
        assertEquals(newest.lastGood!!.evidenceId, tie.lastGood!!.evidenceId)
        // 較舊觀測：歷史保留，head／last-good 不退。
        val older = store.record(goodObservation(provider, account, patRef, 1_000L, requestCount = 10L))
        assertEquals(3_000L, older.latest!!.observedAt)
        assertEquals(3_000L, older.lastGood!!.observedAt)
        assertEquals(30L, older.lastGood!!.requestCountRaw)

        val history = store.history(provider, account)
        assertEquals("三個觀測都要留", 3, history.size)
        assertEquals(listOf(3_000L, 3_000L, 1_000L), history.map { it.observedAt })
        assertEquals(setOf(30L, 31L, 10L), history.mapNotNull { it.requestCountRaw }.toSet())
    }

    // ===== 7. capabilities 失敗：沿用真實 status source，絕不改寫成 account source =====

    @Test
    fun capabilitiesFailureKeepsStatusSourceAndNullCount() = runBlocking {
        val db = memoryDb()
        val provider = "usage-capsfail"
        val account = "usage-acc-capsfail"
        val patRef = "pat-usage-capsfail"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        // Phase A：network 無回應（httpStatus null）。
        val network = store.record(
            AccountQuotaObservation(
                providerId = provider,
                accountId = account,
                patRef = AccountPatRef(patRef),
                validatedBaseUrl = base(provider),
                capabilities = NewApiResult.Failure(
                    NewApiStatus.NETWORK_UNREACHABLE,
                    evidence(CAPS_SOURCE, 1_000L, null, null),
                ),
                // main 的 quota sync 會把 caps 失敗沿用到 result；source 已被改寫成 account。
                result = NewApiResult.Failure(
                    NewApiStatus.NETWORK_UNREACHABLE,
                    evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, 1_000L, null, null),
                ),
            ),
        )
        val networkMarker = network.latest!!
        assertFalse("HTTP-null 網路失敗不算成功觀測", networkMarker.accountEndpointObserved)
        assertNull("失敗／未知標記不得帶數字", networkMarker.requestCountRaw)
        assertNull(networkMarker.httpStatus)
        assertNull(networkMarker.providerVersion)
        assertEquals("NETWORK_UNREACHABLE", networkMarker.status)
        assertEquals("沿用 caps 真實 source，不得改寫成 account source", CAPS_SOURCE, networkMarker.source)
        assertEquals(CAPS_SOURCE, db.evidenceDao().getById(networkMarker.evidenceId)!!.source)
        assertNull(network.lastGood)

        // Phase B：/api/status 真的回 5xx → httpStatus 保留，但不標 accountEndpointObserved。
        val serverError = store.record(
            AccountQuotaObservation(
                providerId = provider,
                accountId = account,
                patRef = AccountPatRef(patRef),
                validatedBaseUrl = base(provider),
                capabilities = NewApiResult.Failure(
                    NewApiStatus.HTTP_SERVER_ERROR,
                    evidence(CAPS_SOURCE, 2_000L, null, 500),
                ),
                result = NewApiResult.Failure(
                    NewApiStatus.HTTP_SERVER_ERROR,
                    evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, 2_000L, null, 500),
                ),
            ),
        )
        val serverMarker = serverError.latest!!
        assertEquals("HTTP_SERVER_ERROR", serverMarker.status)
        assertEquals(500, serverMarker.httpStatus)
        assertEquals(CAPS_SOURCE, serverMarker.source)
        assertEquals(2_000L, serverMarker.observedAt)
        assertFalse(serverMarker.accountEndpointObserved)
        assertNull(serverMarker.requestCountRaw)
        assertNull(serverError.lastGood)
        assertEquals(2, store.history(provider, account).size)
    }

    // ===== 8. capabilities 成功但 account 端點失敗：account source 保留、last-good 不動 =====

    @Test
    fun accountEndpointFailureAfterCertifiedCapabilitiesKeepsAccountSource() = runBlocking {
        val db = memoryDb()
        val provider = "usage-acctfail"
        val account = "usage-acc-acctfail"
        val patRef = "pat-usage-acctfail"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        store.record(goodObservation(provider, account, patRef, 1_000L, requestCount = 50L))

        val unauthorized = store.record(
            observation(
                provider, account, patRef,
                capsSuccess(2_000L),
                accountFailure(2_000L, NewApiStatus.HTTP_UNAUTHORIZED, 401, rc25()),
            ),
        )
        val marker = unauthorized.latest!!
        assertEquals("HTTP_UNAUTHORIZED", marker.status)
        assertEquals(401, marker.httpStatus)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, marker.source)
        assertNull(marker.requestCountRaw)
        assertTrue("真的有 account HTTP 回應（401 也是回應）", marker.accountEndpointObserved)
        assertEquals("last-good 不因失敗回退", 1_000L, unauthorized.lastGood!!.observedAt)
        assertEquals(50L, unauthorized.lastGood!!.requestCountRaw)
        assertEquals("HTTP_UNAUTHORIZED", db.evidenceDao().getById(marker.evidenceId)!!.outcome)

        // 200 但 body 不可解讀 → MALFORMED，同樣不得污染 last-good。
        val malformed = store.record(
            observation(
                provider, account, patRef,
                capsSuccess(3_000L),
                accountFailure(3_000L, NewApiStatus.MALFORMED, 200, rc25()),
            ),
        )
        assertEquals("MALFORMED", malformed.latest!!.status)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, malformed.latest!!.source)
        assertNull(malformed.latest!!.requestCountRaw)
        assertEquals(1_000L, malformed.lastGood!!.observedAt)
        assertEquals(3, store.history(provider, account).size)
    }

    // ===== 9. caps／result 版本不一致即零寫入；認證只認同一 session rc25，不借 stored head =====

    @Test
    fun mismatchedSessionEvidenceIsRejectedAndStoredQuotaHeadIsNotBorrowed() = runBlocking {
        val db = memoryDb()
        val provider = "usage-uncert"
        val account = "usage-acc-uncert"
        val patRef = "pat-usage-uncert"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        // 前置：DB 裡有「已持久化的官方 ACCOUNT_QUOTA last-good triple」。
        // 它是 quota 槽的真事實，**不是** usage 的認證來源（不得跨 head 借認證）。
        val quotaScope = NewApiSnapshotStore.accountQuotaScope(provider, account)
        db.evidenceDao().insert(
            EvidenceEntity(
                "usage-seed-ev", "usage-seed-ev", provider, "", "OK", 200, 500L,
                NewApiSnapshotStore.ACCOUNT_SOURCE,
            ),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = "usage-seed-ev",
                scopeKey = quotaScope.storageKey(),
                kind = SnapshotKind.ACCOUNT_QUOTA.name,
                observedAt = 500L,
                snapshotId = "usage-seed-ev",
                state = QuotaState.QUOTA_AVAILABLE.name,
                status = "OK",
                confidence = "OFFICIAL",
                providerVersion = NewApiVersion.RC25,
                pricingVersion = null,
                itemPricingVersion = null,
                quotaPerUnit = null,
                groupRatio = null,
                statusObservedAt = 500L,
                remainingRaw = 10_000L,
                usedRaw = 777L,
                grantedRaw = null,
                unlimited = null,
                expiresAtRaw = null,
                expiryMeaning = null,
                expressionHash = null,
                priceUnit = null,
            ),
        )
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(
                quotaScope.storageKey(), SnapshotKind.ACCOUNT_QUOTA.name,
                "usage-seed-ev", 500L, "usage-seed-ev", 500L,
            ),
        )

        // Phase A：caps 是認證的 rc.25，但 result 帶數字卻沒有版本／HTTP 回應。
        // 即使 DB 已有 certified ACCOUNT_QUOTA head，也不得因此把 42 當 usage good。
        assertRejected(
            db,
            observation(
                provider, account, patRef,
                capsSuccess(1_000L),
                NewApiResult.Success(
                    evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, 1_000L, null, null),
                    NewApiAccountQuota(500_000L, 12_345L, 42L, GROUP_CANARY, 1, 1),
                ),
            ),
            baselineEvidence = 1L,
        )

        // Phase B：result 宣稱其他版本（站方版本漂移）→ 同樣零寫入。
        assertRejected(
            db,
            observation(
                provider, account, patRef,
                capsSuccess(2_000L),
                NewApiResult.Success(
                    evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, 2_000L, NewApiVersion.of("v9.9.9"), 200),
                    NewApiAccountQuota(500_000L, 12_345L, 43L, GROUP_CANARY, 1, 1),
                ),
            ),
            baselineEvidence = 1L,
        )

        // Phase C：caps 與 result 同屬本次 session 的 rc.25 → 就算沒有任何 usage stored head
        // 也能落 good（認證來自本次觀測本身，不是任何其他 head）。
        val certified = store.record(goodObservation(provider, account, patRef, 3_000L, requestCount = 44L))
        assertEquals(44L, certified.lastGood!!.requestCountRaw)
        assertEquals(3_000L, certified.lastGood!!.observedAt)
        assertTrue(certified.lastGood!!.accountEndpointObserved)
        // quota 槽完全不受影響（usage 不得重寫／搬走 usedQuota，也不得被 quota 認證帶動）。
        assertEquals(1L, count(db, "snapshot_facts"))
        assertEquals(1L, count(db, "snapshot_heads"))
        assertEquals(0L, count(db, "quota_snapshots"))
        assertEquals(777L, db.snapshotFactDao().evidence("usage-seed-ev")!!.usedRaw)
    }

    // ===== 10. 歷史：每種標記都留、排序正確、其他槽零副作用 =====

    @Test
    fun historyKeepsEveryMarkerAndOtherSlotsStayUntouched() = runBlocking {
        val db = memoryDb()
        val provider = "usage-history"
        val account = "usage-acc-history"
        val patRef = "pat-usage-history"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        store.record(goodObservation(provider, account, patRef, 1_000L, requestCount = 100L))
        store.record(goodObservation(provider, account, patRef, 2_000L, requestCount = null))
        store.record(goodObservation(provider, account, patRef, 3_000L, requestCount = -5L))
        store.record(
            observation(
                provider, account, patRef,
                capsSuccess(4_000L),
                accountFailure(4_000L, NewApiStatus.HTTP_FORBIDDEN, 403, rc25()),
            ),
        )
        val last = store.record(goodObservation(provider, account, patRef, 5_000L, requestCount = 120L))

        val history = store.history(provider, account)
        assertEquals(5, history.size)
        assertEquals(listOf(5_000L, 4_000L, 3_000L, 2_000L, 1_000L), history.map { it.observedAt })
        assertEquals(
            listOf("OK", "HTTP_FORBIDDEN", "MALFORMED", "OK", "OK"),
            history.map { it.status },
        )
        assertEquals(listOf<Long?>(120L, null, null, null, 100L), history.map { it.requestCountRaw })
        assertEquals(5_000L, last.lastGood!!.observedAt)
        assertEquals("每個 scope 只有一個 head", 1L, count(db, "provider_account_usage_heads"))

        // Counter semantics 一律 UNSPECIFIED；不得出現 window／rate／delta／cumulative 標籤。
        for (fact in history) {
            assertTrue(fact.counterSemantics.contains("UNSPECIFIED"))
            val row = fact.toString().lowercase()
            for (forbidden in listOf("window", "rate", "delta", "cumulative", "reset", "perday")) {
                assertFalse("usage 欄位不得出現 $forbidden 語意標籤：$row", row.contains(forbidden))
            }
            assertFalse("帳號 group 不落庫", row.contains(GROUP_CANARY))
        }

        // 其他槽零副作用（isolated DB）。
        assertEquals(0L, count(db, "snapshot_facts"))
        assertEquals(0L, count(db, "snapshot_heads"))
        assertEquals(0L, count(db, "quota_snapshots"))
        assertEquals(0L, count(db, "price_snapshots"))
        assertEquals(0L, count(db, "provider_perf_facts"))
        assertEquals(0L, count(db, "provider_perf_heads"))
        assertEquals(0L, count(db, "samples"))
        assertEquals(0L, count(db, "my_models"))
        assertEquals(0L, count(db, "external_observations"))
        assertEquals("只有本 slice 的 evidence", 5L, count(db, "evidence"))
    }

    // ===== 11. source 守門：非本次 account/caps 來源的 evidence 一律零寫入 =====

    @Test
    fun unexpectedEvidenceSourceIsRejectedWithZeroWrites() = runBlocking {
        // Phase A：result 掛外部／其他來源（external 指標不得冒充 provider-reported）。
        val external = memoryDb()
        seedScope(external, "usage-src-ext", "usage-acc-src-ext", "pat-usage-src-ext")
        assertRejected(
            external,
            observation(
                "usage-src-ext", "usage-acc-src-ext", "pat-usage-src-ext",
                capsSuccess(1_000L),
                NewApiResult.Success(
                    evidence(EXTERNAL_SOURCE, 1_000L, rc25(), 200),
                    NewApiAccountQuota(1L, 2L, 99L, GROUP_CANARY, 1, 1),
                ),
            ),
        )

        // Phase B：result source 是 token 端點（key 層級 usage 不得冒充帳號層級）。
        val tokenSource = memoryDb()
        seedScope(tokenSource, "usage-src-tok", "usage-acc-src-tok", "pat-usage-src-tok")
        assertRejected(
            tokenSource,
            observation(
                "usage-src-tok", "usage-acc-src-tok", "pat-usage-src-tok",
                capsSuccess(1_000L),
                NewApiResult.Success(
                    evidence(NewApiSnapshotStore.TOKEN_SOURCE, 1_000L, rc25(), 200),
                    NewApiAccountQuota(1L, 2L, 98L, GROUP_CANARY, 1, 1),
                ),
            ),
        )

        // Phase C：caps evidence 掛別的 source（status 失敗不得借用 pricing 來源）。
        val capsSource = memoryDb()
        seedScope(capsSource, "usage-src-cap", "usage-acc-src-cap", "pat-usage-src-cap")
        assertRejected(
            capsSource,
            AccountQuotaObservation(
                providerId = "usage-src-cap",
                accountId = "usage-acc-src-cap",
                patRef = AccountPatRef("pat-usage-src-cap"),
                validatedBaseUrl = base("usage-src-cap"),
                capabilities = NewApiResult.Failure(
                    NewApiStatus.NETWORK_UNREACHABLE,
                    evidence(NewApiSnapshotStore.PRICE_SOURCE, 1_000L, null, null),
                ),
                result = NewApiResult.Failure(
                    NewApiStatus.NETWORK_UNREACHABLE,
                    evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, 1_000L, null, null),
                ),
            ),
        )
    }

    // ===== 12. credential 角色／歸屬／啟用守門 =====

    @Test
    fun credentialRoleAndOwnershipGuardsRejectWithZeroWrites() = runBlocking {
        // 12a：只有 INFERENCE 角色 → 帳號 usage 永不借 inference key。
        val inferenceOnly = memoryDb()
        seedScope(inferenceOnly, "usage-role-inf", "usage-acc-role-inf", "pat-usage-role-inf", roles = listOf(CredentialRole.INFERENCE))
        assertRejected(inferenceOnly, goodObservation("usage-role-inf", "usage-acc-role-inf", "pat-usage-role-inf", 1_000L, 5L))

        // 12b：同一 ref 雙角色 → 角色不純即 fail-closed。
        val dualRole = memoryDb()
        seedScope(
            dualRole, "usage-role-dual", "usage-acc-role-dual", "pat-usage-role-dual",
            roles = listOf(CredentialRole.ACCOUNT, CredentialRole.INFERENCE),
        )
        assertRejected(dualRole, goodObservation("usage-role-dual", "usage-acc-role-dual", "pat-usage-role-dual", 1_000L, 5L))

        // 12c：明確停用的 ACCOUNT ref → 不靜默降級、不改用別的 credential。
        val disabled = memoryDb()
        seedScope(disabled, "usage-role-off", "usage-acc-role-off", "pat-usage-role-off", credentialEnabled = false)
        assertRejected(disabled, goodObservation("usage-role-off", "usage-acc-role-off", "pat-usage-role-off", 1_000L, 5L))

        // 12d：credential 屬別的帳號。
        val foreignAccount = memoryDb()
        seedScope(foreignAccount, "usage-own-acc", "usage-acc-own", "pat-usage-own")
        foreignAccount.providerDao().insert(ProviderEntity("usage-other-provider", "Other"))
        foreignAccount.accountDao().insert(AccountEntity("usage-acc-foreign", "usage-other-provider"))
        foreignAccount.openHelper.writableDatabase.execSQL(
            "UPDATE credentials SET accountId = ? WHERE credentialId = ?",
            arrayOf("usage-acc-foreign", "pat-usage-own"),
        )
        assertRejected(foreignAccount, goodObservation("usage-own-acc", "usage-acc-own", "pat-usage-own", 1_000L, 5L))

        // 12e：provider config 缺席（PAT 未掛在該 provider 的 ACCOUNT 角色）。
        val noConfig = memoryDb()
        seedScope(noConfig, "usage-no-cfg", "usage-acc-no-cfg", "pat-usage-no-cfg", writeConfig = false)
        assertRejected(noConfig, goodObservation("usage-no-cfg", "usage-acc-no-cfg", "pat-usage-no-cfg", 1_000L, 5L))

        // 12f：provider／account 完全不存在（空 DB）→ 不得建立任何孤兒 scope。
        val ghost = memoryDb()
        assertRejected(ghost, goodObservation("usage-ghost-provider", "usage-ghost-account", "pat-usage-ghost", 1_000L, 5L))

        // 12g：raw `sk-` 前綴 ref（本檔唯一 sk- 字串，非真 key）→ persistence 邊界拒收。
        val rawRef = memoryDb()
        seedScope(rawRef, "usage-raw-ref", "usage-acc-raw-ref", "sk-ab")
        assertRejected(rawRef, goodObservation("usage-raw-ref", "usage-acc-raw-ref", "sk-ab", 1_000L, 5L))
    }

    // ===== 13. origin 守門：provider 端點單一 origin 且等於本次觀測的 validated base =====

    @Test
    fun originGuardsRejectSplitEndpointsAndForeignBaseUrl() = runBlocking {
        // 13a：同 provider 兩個 origin → fail-closed（不挑一個就用）。
        val split = memoryDb()
        seedScope(
            split, "usage-origin-split", "usage-acc-origin-split", "pat-usage-origin-split",
            extraEndpoint = "https://mirror.example.com/v1",
        )
        assertRejected(split, goodObservation("usage-origin-split", "usage-acc-origin-split", "pat-usage-origin-split", 1_000L, 5L))

        // 13b：DB 單一 origin，但本次觀測帶別的 origin（跨站資料不得寫進本 provider）。
        val foreignBase = memoryDb()
        seedScope(foreignBase, "usage-origin-base", "usage-acc-origin-base", "pat-usage-origin-base")
        assertRejected(
            foreignBase,
            goodObservation(
                "usage-origin-base", "usage-acc-origin-base", "pat-usage-origin-base", 1_000L, 5L,
                baseUrl = "https://elsewhere.example.com/v1",
            ),
        )
    }

    // ===== 14. scope 隔離：provider×account 各自獨立，跨 provider 帳號不得借用 =====

    @Test
    fun scopeIsolationKeepsProvidersAndAccountsApart() = runBlocking {
        val db = memoryDb()
        val providerA = "usage-iso-a"
        val providerB = "usage-iso-b"
        seedScope(db, providerA, "usage-acc-a1", "pat-usage-a1", secondAccount = "usage-acc-a2", secondPatRef = "pat-usage-a2")
        seedScope(db, providerB, "usage-acc-b1", "pat-usage-b1")
        val store = ProviderAccountUsageStore(db)

        store.record(goodObservation(providerA, "usage-acc-a1", "pat-usage-a1", 1_000L, 11L))
        store.record(goodObservation(providerA, "usage-acc-a2", "pat-usage-a2", 2_000L, 22L))
        store.record(goodObservation(providerB, "usage-acc-b1", "pat-usage-b1", 3_000L, 33L))

        assertEquals(11L, store.observation(providerA, "usage-acc-a1").lastGood!!.requestCountRaw)
        assertEquals(22L, store.observation(providerA, "usage-acc-a2").lastGood!!.requestCountRaw)
        assertEquals(33L, store.observation(providerB, "usage-acc-b1").lastGood!!.requestCountRaw)
        assertEquals(1, store.history(providerA, "usage-acc-a1").size)
        assertEquals(1, store.history(providerA, "usage-acc-a2").size)
        assertEquals(1, store.history(providerB, "usage-acc-b1").size)
        assertEquals(3L, count(db, "provider_account_usage_facts"))
        assertEquals("每個 provider×account 一個 head", 3L, count(db, "provider_account_usage_heads"))

        // 未觀測過的 scope：typed reader 回 null（Unknown），不是 0／不是免費。
        val unknown = store.observation(providerA, "usage-acc-missing")
        assertNull(unknown.latest)
        assertNull(unknown.lastGood)
        assertEquals(emptyList<ProviderAccountUsageFactEntity>(), store.history(providerA, "usage-acc-missing"))

        // 跨 provider 借帳號 → 零寫入，既有 scope 不受影響。
        assertRejected(
            db,
            goodObservation(providerB, "usage-acc-a1", "pat-usage-a1", 4_000L, 44L),
            baselineFacts = 3L,
            baselineHeads = 3L,
            baselineEvidence = 3L,
        )
        assertEquals(11L, store.observation(providerA, "usage-acc-a1").lastGood!!.requestCountRaw)
        assertEquals(33L, store.observation(providerB, "usage-acc-b1").lastGood!!.requestCountRaw)
    }

    // ===== harness（fixture 僅本檔、僅測試用；無 HTTP、無 vault、無真 secret） =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    /**
     * Provider × Account × Endpoint × Credential × provider_configurations 真列。
     *
     * DB endpoint 維持 `https://…example.com/v1`（生產 `EndpointPolicy` HTTPS gate 要求），
     * 只在觀測裡以同一個 base 餵給 store；本檔不建立任何 HTTP client。
     */
    private suspend fun seedScope(
        db: AtlasDatabase,
        provider: String,
        account: String,
        patRef: String,
        roles: List<CredentialRole> = listOf(CredentialRole.ACCOUNT),
        writeConfig: Boolean = true,
        credentialEnabled: Boolean = true,
        extraEndpoint: String? = null,
        secondAccount: String? = null,
        secondPatRef: String? = null,
    ) {
        db.providerDao().insert(ProviderEntity(provider, "P $provider"))
        db.accountDao().insert(AccountEntity(account, provider))
        db.endpointDao().insert(
            EndpointEntity("e-$provider", provider, base(provider), "${base(provider)}/chat/completions"),
        )
        if (extraEndpoint != null) {
            db.endpointDao().insert(
                EndpointEntity("e2-$provider", provider, extraEndpoint, "$extraEndpoint/chat/completions"),
            )
        }
        db.credentialDao().insert(CredentialEntity(patRef, account, enabled = credentialEnabled))
        val refs = roles.map { SafeCredentialRef(patRef, if (it == CredentialRole.ACCOUNT) "PAT" else "KEY", it) }.toMutableList()
        if (secondAccount != null && secondPatRef != null) {
            db.accountDao().insert(AccountEntity(secondAccount, provider))
            db.credentialDao().insert(CredentialEntity(secondPatRef, secondAccount))
            refs += SafeCredentialRef(secondPatRef, "PAT", CredentialRole.ACCOUNT)
        }
        if (writeConfig) {
            db.providerConfigDao().insert(
                ProviderManualConfig(
                    providerId = provider,
                    workflowId = "wf-$provider-usage",
                    authMode = AuthMode.BEARER,
                    credentials = refs,
                ).toEntity(),
            )
        }
    }

    /**
     * 守門拒絕的硬條件：**零寫入＋明確 fail-closed**。
     *
     * store 必須擲 `IllegalArgumentException`（`require` 語意），讓 repository 的 observer
     * hook 判 ordinary／cancel 並 fail-open，而不是安靜吞掉違規觀測。靜默不寫同樣不接受：
     * 那會讓「守門失效」和「本來就沒資料」無法區分。
     * 拒絕訊息只允許靜態文案，不得回顯 provider／account／credential ref。
     */
    private suspend fun assertRejected(
        db: AtlasDatabase,
        observation: AccountQuotaObservation,
        baselineFacts: Long = 0L,
        baselineHeads: Long = 0L,
        baselineEvidence: Long = 0L,
    ) {
        val error = runCatching { ProviderAccountUsageStore(db).record(observation) }.exceptionOrNull()
        assertTrue("guard 必須 fail-closed，實際得到：$error", error is IllegalArgumentException)
        val message = error?.message ?: ""
        assertFalse(
            "拒絕訊息不得回顯 ref：$message",
            message.contains(observation.providerId) ||
                message.contains(observation.accountId) ||
                message.contains(observation.patRef.value),
        )
        assertEquals(baselineFacts, count(db, "provider_account_usage_facts"))
        assertEquals(baselineHeads, count(db, "provider_account_usage_heads"))
        assertEquals(baselineEvidence, count(db, "evidence"))
        val read = ProviderAccountUsageStore(db).observation(observation.providerId, observation.accountId)
        assertNull(read.latest)
        assertNull(read.lastGood)
    }

    /** 真 `NewApiAdapter` 觀測形狀：provider／account／PAT／base ＋ caps／result typed 結果。 */
    private fun observation(
        provider: String,
        account: String,
        patRef: String,
        capabilities: NewApiResult<NewApiCapabilities>,
        result: NewApiResult<NewApiAccountQuota>,
        baseUrl: String = base(provider),
    ) = AccountQuotaObservation(
        providerId = provider,
        accountId = account,
        patRef = AccountPatRef(patRef),
        validatedBaseUrl = baseUrl,
        capabilities = capabilities,
        result = result,
    )

    /** 同一 session 認證 rc.25 的 account 成功觀測；`group/role/status` 帶哨兵值驗不落庫。 */
    private fun goodObservation(
        provider: String,
        account: String,
        patRef: String,
        observedAt: Long,
        requestCount: Long?,
        quota: Long? = 500_000L,
        usedQuota: Long? = 12_345L,
        baseUrl: String = base(provider),
    ) = observation(
        provider, account, patRef,
        capsSuccess(observedAt),
        NewApiResult.Success(
            evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, observedAt, rc25(), 200),
            NewApiAccountQuota(quota, usedQuota, requestCount, GROUP_CANARY, 1, 1),
        ),
        baseUrl = baseUrl,
    )

    private fun capsSuccess(observedAt: Long): NewApiResult<NewApiCapabilities> {
        val version = rc25()
        return NewApiResult.Success(
            evidence(CAPS_SOURCE, observedAt, version, 200),
            NewApiCapabilities(
                version = version,
                quotaPerUnit = null,
                quotaDisplayType = "USD",
                checkinEnabled = true,
                pricingModule = NewApiModuleAccess(true, true),
                certifiedEndpoints = version.certifiedEndpoints,
            ),
        )
    }

    private fun accountFailure(
        observedAt: Long,
        status: NewApiStatus,
        httpStatus: Int?,
        version: NewApiVersion?,
    ): NewApiResult<NewApiAccountQuota> = NewApiResult.Failure(
        status, evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, observedAt, version, httpStatus),
    )

    /** adapter evidence 形狀：version.raw／version.support／httpStatus 逐欄照抄。 */
    private fun evidence(
        source: String,
        observedAt: Long,
        version: NewApiVersion?,
        httpStatus: Int?,
    ) = NewApiEvidence(
        source = source,
        observedAt = observedAt,
        providerVersion = version?.raw,
        versionSupport = version?.support ?: NewApiVersionSupport.UNKNOWN,
        httpStatus = httpStatus,
    )

    private fun rc25(): NewApiVersion = NewApiVersion.of(NewApiVersion.RC25)

    private fun base(provider: String): String = "https://$provider.example.com/v1"

    private fun count(db: AtlasDatabase, table: String): Long {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            return it.getLong(0)
        }
    }

    private fun columns(db: AtlasDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            val index = cursor.getColumnIndexOrThrow("name")
            val out = ArrayList<String>()
            while (cursor.moveToNext()) out += cursor.getString(index)
            out
        }

    private companion object {
        /** `NewApiAdapter.SOURCE_STATUS` 是 private companion 常數，此處以真值字串重現。 */
        const val CAPS_SOURCE = "newapi/api/status"

        /** 帳號 `group` 哨兵值：任何 usage 欄位／evidence 都不該出現它。 */
        const val GROUP_CANARY = "usage-group-canary"

        /** 外部觀測真實 source token（`ExternalCanonicalMapping` 預設）；不得冒充 provider-reported。 */
        const val EXTERNAL_SOURCE = "LMSPEED"
    }
}
