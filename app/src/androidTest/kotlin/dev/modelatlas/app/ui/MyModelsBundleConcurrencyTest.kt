package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.ExternalStore
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.MyModelsStore
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.lmspeed.LMSPEED_SOURCE
import dev.modelatlas.core.lmspeed.LmspeedFetcher
import dev.modelatlas.core.lmspeed.LmspeedRawModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MYM-004 repair regression——bundle enrichment concurrency（Contract §23.3／§23.9；D-008）。
 *
 * 使用者確認的 production bug：`AtlasViewModel.maybeEnrichMyModel` 的 bundle single-flight
 * 以 providerId 為 key，同 provider 的第二個 model／scope 在 inflight 時被直接
 * `return true` 丟掉（silent false success），沒有 queue／join／replay；`remove` 又在
 * mutex 外。本檔以**真 `AtlasApplication`（真 Room / Keystore）＋真 `AtlasViewModel`**
 * 走 production `maybeEnrichMyModel → AtlasRepository.enrichMyModelBundle` 路徑，禁止
 * 只驗回傳 Boolean、禁止只驗 test-only map、禁止 sleep 猜 race、禁止放寬 timeout。
 *
 * ## Controlled port seam（本檔唯一依賴的 production 契約）
 *
 * 為了能在「bundle 真正執行中」放置 deterministic barrier，本檔要求 repair 後的
 * `AtlasViewModel.maybeEnrichMyModel` 增加最後一個 optional 參數並透傳 repository：
 *
 * ```
 * suspend fun maybeEnrichMyModel(
 *     providerId: String,
 *     accountId: String? = null,
 *     credentialId: String? = null,
 *     group: String? = null,
 *     providerModelId: String? = null,
 *     lmspeedFetcher: LmspeedFetcher? = null,   // NEW：controlled port；production caller 不傳即 null
 * ): Boolean
 * ```
 *
 * 呼叫端（`addMyModelExplicit` 等 production caller）維持原呼叫不變；`lmspeedFetcher`
 * 一路傳到 `AtlasRepository.enrichMyModelBundle(..., lmspeedFetcher)`，讓測試以
 * [ScriptedFetcher] 在真 production 呼叫鏈內暫停／釋放／失敗／取消 expensive external
 * enrichment 步驟，並以真 DB 副作用（alias／external observation）作證據。
 *
 * ## Barrier 與 ownership 定義
 *
 * - Barrier 全部使用 [CompletableDeferred]（可取消的 suspend gate）；測試只等待
 *   deterministic 事件，`withTimeout` 僅是安全網，不是同步手段，更未放寬既有 15s。
 * - Leader 擁有 exact-key work：唯一執行 bundle、唯一清理 registry、取消時
 *   cleanup＋rethrow；follower 只 `await` 同一 work，回傳 leader 真結果，自己取消
 *   不得 cancel leader、不得移除別人 registry（見 [cancellingFollowerDoesNotCancelLeaderOfSameExactKey]）。
 * - Membership 由真 `MyModelsStore.addToMyModels` 寫入（與 `addMyModelExplicit` 相同順序：
 *   先 membership，再 enrichment）；bundle 不寫 membership、失敗不刪 membership。
 * - Per-model 證據：fresh model 的 alias（NORMALIZATION／LOW／未 verified）＋各次
 *   execution 專屬 external observation（unique slug）；不以 `true` 當完成證據。
 *
 * ## 覆蓋（使用者原話 Test 1–7）
 *
 * 1. [sameProviderDifferentModelConcurrentAddRunsBothBundles]：A 暫停時加入 B；A／B
 *    membership、alias、evidence 都在，兩個 bundle 都真正執行（各一次）。
 * 2. [sameExactKeySecondCallerJoinsRealCompletionWithoutDuplicateEnrichment]：同 key
 *    兩個 caller；只跑一次 expensive enrichment，第二個非立即假成功（leader 未釋放前
 *    不完成），取得真 completion。
 * 3. [sameProviderSameModelDifferentGroupsBothExecute]：group g1／g2 兩 scope 都跑；
 *    另驗 null 與具名 group 不互相覆蓋。
 * 4. [sameProviderDifferentCredentialScopesDoNotShareCompletion]：同 provider 不同
 *    account／credential 兩 scope 隔離，不互相視為完成。
 * 5. [firstBundleFailureStillRunsQueuedSecondBundle]：A fail-open 後 queued B 照跑，
 *    不卡死、membership 不掉。
 * 6. [firstBundleCancellationStillRunsQueuedSecondBundle]：A cancel 後 cleanup＋rethrow，
 *    B 不遺失。
 * 7. [registryCleanupLetsSameKeyRunAgainAfterSuccessFailureAndCancel]：成功／失敗／
 *    cancel 後同 key 可重跑，無 stale。
 * 8. [cancellingFollowerDoesNotCancelLeaderOfSameExactKey]：follower cancel ownership。
 *
 * tearDown 只清本檔 fixture：provider 家族列＋alias＋由 alias 反查的 canonical＋
 * `mym004-conc` 前綴 external 兩表＋vault refs；不碰其他測試／產品資料。
 */
@RunWith(AndroidJUnit4::class)
class MyModelsBundleConcurrencyTest {

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        viewModelStore = ViewModelStore()
        onMain {
            viewModel = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ===== Test 1：same provider different model concurrent add（核心 blocker） =====

    @Test
    fun sameProviderDifferentModelConcurrentAddRunsBothBundles() {
        val fixture = saveFixture("t1")
        val modelA = uniqueModel("t1-a")
        val modelB = uniqueModel("t1-b")
        addMembership(fixture.providerId, modelA)

        val aEntered = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()
        val slugA = uniqueSlug("t1-a")
        val slugB = uniqueSlug("t1-b")
        val fetcher = ScriptedFetcher { call ->
            when (call) {
                1 -> {
                    aEntered.complete(Unit)
                    aRelease.await()
                    rowsFor(slugA)
                }
                2 -> rowsFor(slugB)
                else -> unexpectedExecution(call)
            }
        }

        runBlocking {
            val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, modelA, fetcher)
            }
            withTimeout(TIMEOUT_MS) { aEntered.await() }

            // A 的 bundle 真正執行中（已進入 external 步驟），membership 仍在。
            assertTrue(
                "A membership must be visible while its bundle is in flight",
                MyModelsStore(app.database).isMyModel(fixture.providerId, modelA),
            )

            // 在 A 暫停時正式加入 B（與 addMyModelExplicit 相同順序：先 membership 後 enrichment）。
            assertTrue(
                "B membership must be written while A is in flight",
                MyModelsStore(app.database).addToMyModels(
                    fixture.providerId, modelB, source = "USER", nowMillis = System.currentTimeMillis(),
                ),
            )
            val jobB = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, modelB, fetcher)
            }

            // 核心 blocker：B 不得在 A 未完成前 silent false success。
            assertFalse("second model must not complete while the first bundle is in flight", jobB.isCompleted)
            assertEquals("B must not burn a second enrichment while queued", 1, fetcher.calls)

            aRelease.complete(Unit)
            assertTrue("A bundle must complete", withTimeout(TIMEOUT_MS) { jobA.await() })
            assertTrue("B bundle must complete after A", withTimeout(TIMEOUT_MS) { jobB.await() })
            assertEquals("each exact key runs its own bundle exactly once", 2, fetcher.calls)

            assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, modelA))
            assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, modelB))
        }

        assertAliasAttached(fixture.providerId, modelA)
        assertAliasAttached(fixture.providerId, modelB)
        assertExternalEvidence(slugA)
        assertExternalEvidence(slugB)
    }

    // ===== Test 2：same exact key concurrent duplicate =====

    @Test
    fun sameExactKeySecondCallerJoinsRealCompletionWithoutDuplicateEnrichment() {
        val fixture = saveFixture("t2")
        val model = uniqueModel("t2")
        addMembership(fixture.providerId, model)

        val leaderEntered = CompletableDeferred<Unit>()
        val leaderRelease = CompletableDeferred<Unit>()
        val slug = uniqueSlug("t2")
        val fetcher = ScriptedFetcher { call ->
            when (call) {
                1 -> {
                    leaderEntered.complete(Unit)
                    leaderRelease.await()
                    rowsFor(slug)
                }
                else -> unexpectedExecution(call)
            }
        }

        runBlocking {
            val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, fetcher)
            }
            withTimeout(TIMEOUT_MS) { leaderEntered.await() }

            val follower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, fetcher)
            }
            // 第二個 caller 必須 join 真 work，而不是立即假成功。
            assertFalse("duplicate caller must await the in-flight work", follower.isCompleted)
            assertEquals("duplicate must not re-run expensive enrichment", 1, fetcher.calls)

            leaderRelease.complete(Unit)
            assertTrue("leader must return its real result", withTimeout(TIMEOUT_MS) { leader.await() })
            assertTrue("follower must obtain the leader's real completion", withTimeout(TIMEOUT_MS) { follower.await() })
            assertEquals("exact key enrichment ran exactly once", 1, fetcher.calls)
        }

        assertAliasAttached(fixture.providerId, model)
        assertExternalEvidence(slug)
    }

    // ===== Test 3：same provider／model different group =====

    @Test
    fun sameProviderSameModelDifferentGroupsBothExecute() {
        val fixture = saveFixture("t3")
        val model = uniqueModel("t3")
        addMembership(fixture.providerId, model)

        val groupOne = "mym004-conc-g1-${UUID.randomUUID()}"
        val groupTwo = "mym004-conc-g2-${UUID.randomUUID()}"
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val slugOne = uniqueSlug("t3-g1")
        val slugTwo = uniqueSlug("t3-g2")
        val fetcher = ScriptedFetcher { call ->
            when (call) {
                1 -> {
                    firstEntered.complete(Unit)
                    firstRelease.await()
                    rowsFor(slugOne)
                }
                2 -> rowsFor(slugTwo)
                else -> unexpectedExecution(call)
            }
        }

        runBlocking {
            val jobOne = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, groupOne, model, fetcher)
            }
            withTimeout(TIMEOUT_MS) { firstEntered.await() }

            val jobTwo = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, groupTwo, model, fetcher)
            }
            assertFalse("different group scope must not share completion", jobTwo.isCompleted)
            assertEquals("different group scope must not share the same burst", 1, fetcher.calls)

            firstRelease.complete(Unit)
            assertTrue(withTimeout(TIMEOUT_MS) { jobOne.await() })
            assertTrue(withTimeout(TIMEOUT_MS) { jobTwo.await() })
            assertEquals("both group scopes must truly execute", 2, fetcher.calls)
        }

        // group=null 與具名 group 是不同 scope，不得互相 coalesce。
        val nullSlug = uniqueSlug("t3-null")
        val namedSlug = uniqueSlug("t3-named")
        runBlocking {
            assertTrue(
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, ScriptedFetcher { rowsFor(nullSlug) }),
            )
            assertTrue(
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, groupOne, model, ScriptedFetcher { rowsFor(namedSlug) }),
            )
        }
        assertExternalEvidence(nullSlug)
        assertExternalEvidence(namedSlug)
        assertAliasAttached(fixture.providerId, model)
    }

    // ===== Test 4：same provider different credential／account =====

    @Test
    fun sameProviderDifferentCredentialScopesDoNotShareCompletion() {
        val fixture = saveFixture("t4")
        val model = uniqueModel("t4")
        addMembership(fixture.providerId, model)
        val secondScope = seedSecondScope(fixture.providerId)

        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val slugOne = uniqueSlug("t4-c1")
        val slugTwo = uniqueSlug("t4-c2")
        val fetcher = ScriptedFetcher { call ->
            when (call) {
                1 -> {
                    firstEntered.complete(Unit)
                    firstRelease.await()
                    rowsFor(slugOne)
                }
                2 -> rowsFor(slugTwo)
                else -> unexpectedExecution(call)
            }
        }

        runBlocking {
            val jobOne = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, fetcher)
            }
            withTimeout(TIMEOUT_MS) { firstEntered.await() }

            val jobTwo = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, secondScope.first, secondScope.second, null, model, fetcher)
            }
            assertFalse("different credential scope must not be treated as complete", jobTwo.isCompleted)
            assertEquals("different credential scope must run separately", 1, fetcher.calls)

            firstRelease.complete(Unit)
            assertTrue(withTimeout(TIMEOUT_MS) { jobOne.await() })
            assertTrue(withTimeout(TIMEOUT_MS) { jobTwo.await() })
            assertEquals("both account/credential scopes must truly execute", 2, fetcher.calls)
        }

        assertAliasAttached(fixture.providerId, model)
        assertExternalEvidence(slugOne)
        assertExternalEvidence(slugTwo)
    }

    // ===== Test 5：first fails（fail-open），queued second still runs =====

    @Test
    fun firstBundleFailureStillRunsQueuedSecondBundle() {
        val fixture = saveFixture("t5")
        val modelA = uniqueModel("t5-a")
        val modelB = uniqueModel("t5-b")
        addMembership(fixture.providerId, modelA)
        addMembership(fixture.providerId, modelB)

        val aEntered = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()
        val slugB = uniqueSlug("t5-b")
        val fetcher = ScriptedFetcher { call ->
            when (call) {
                1 -> {
                    aEntered.complete(Unit)
                    aRelease.await()
                    throw IllegalStateException("fixture external refresh failure")
                }
                2 -> rowsFor(slugB)
                else -> unexpectedExecution(call)
            }
        }

        runBlocking {
            val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, modelA, fetcher)
            }
            withTimeout(TIMEOUT_MS) { aEntered.await() }

            val jobB = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, modelB, fetcher)
            }
            assertFalse("queued second bundle must not be lost", jobB.isCompleted)

            aRelease.complete(Unit)
            // A 的 external refresh 失敗走 fail-open（bundle 完成、membership 不掉），不得卡死 queue。
            assertTrue("fail-open bundle must still complete", withTimeout(TIMEOUT_MS) { jobA.await() })
            assertTrue("queued second bundle must still run", withTimeout(TIMEOUT_MS) { jobB.await() })
            assertEquals("failure must not leave a stale blocking entry", 2, fetcher.calls)

            assertTrue("failure must not drop A membership", MyModelsStore(app.database).isMyModel(fixture.providerId, modelA))
            assertTrue("failure must not drop B membership", MyModelsStore(app.database).isMyModel(fixture.providerId, modelB))
        }

        assertAliasAttached(fixture.providerId, modelA)
        assertAliasAttached(fixture.providerId, modelB)
        assertExternalEvidence(slugB)
    }

    // ===== Test 6：first cancelled，queued second still runs =====

    @Test
    fun firstBundleCancellationStillRunsQueuedSecondBundle() {
        val fixture = saveFixture("t6")
        val modelA = uniqueModel("t6-a")
        val modelB = uniqueModel("t6-b")
        addMembership(fixture.providerId, modelA)
        addMembership(fixture.providerId, modelB)

        val aEntered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val slugB = uniqueSlug("t6-b")
        val fetcher = ScriptedFetcher { call ->
            when (call) {
                1 -> {
                    aEntered.complete(Unit)
                    neverReleased.await()
                    emptyList()
                }
                2 -> rowsFor(slugB)
                else -> unexpectedExecution(call)
            }
        }

        runBlocking {
            val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, modelA, fetcher)
            }
            withTimeout(TIMEOUT_MS) { aEntered.await() }

            val jobB = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, modelB, fetcher)
            }
            assertFalse("queued second bundle must not be lost", jobB.isCompleted)

            jobA.cancel()
            val cancelled = try {
                jobA.await()
                false
            } catch (_: CancellationException) {
                true
            }
            assertTrue("cancelled leader must cleanup and rethrow, not fake success", cancelled)

            assertTrue("queued second bundle must run after leader cancellation", withTimeout(TIMEOUT_MS) { jobB.await() })
            assertEquals("both exact keys must have executed", 2, fetcher.calls)

            assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, modelA))
            assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, modelB))
        }

        assertAliasAttached(fixture.providerId, modelA)
        assertAliasAttached(fixture.providerId, modelB)
        assertExternalEvidence(slugB)
    }

    // ===== Test 7：registry cleanup after success／failure／cancel =====

    @Test
    fun registryCleanupLetsSameKeyRunAgainAfterSuccessFailureAndCancel() {
        val fixture = saveFixture("t7")
        val model = uniqueModel("t7")
        addMembership(fixture.providerId, model)

        val slugSuccess = uniqueSlug("t7-success")
        val slugRerun = uniqueSlug("t7-rerun")
        val slugAfterFailure = uniqueSlug("t7-after-failure")
        val slugAfterFailOpen = uniqueSlug("t7-after-fail-open")
        val slugAfterCancel = uniqueSlug("t7-after-cancel")
        val successFetcher = ScriptedFetcher { rowsFor(slugSuccess) }
        val rerunFetcher = ScriptedFetcher { rowsFor(slugRerun) }
        val afterFailureFetcher = ScriptedFetcher { rowsFor(slugAfterFailure) }
        val failingFetcher = ScriptedFetcher { throw IllegalStateException("fixture external refresh failure") }
        val afterFailOpenFetcher = ScriptedFetcher { rowsFor(slugAfterFailOpen) }
        val cancelEntered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val blockingFetcher = ScriptedFetcher {
            cancelEntered.complete(Unit)
            neverReleased.await()
            emptyList()
        }
        val afterCancelFetcher = ScriptedFetcher { rowsFor(slugAfterCancel) }

        runBlocking {
            // 1) 成功後，同 key 可再次真正執行（無 stale success）。
            assertTrue(enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, successFetcher))
            assertEquals(1, successFetcher.calls)
            assertTrue(enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, rerunFetcher))
            assertEquals("success must release the registry entry", 1, rerunFetcher.calls)

            // 2) 失敗（VM 層 false：非法 credential ref，不觸網）後，同 key 可重跑。
            assertFalse(
                enrich(fixture.providerId, fixture.accountId, "sk-fixture-raw-secret", null, model, successFetcher),
            )
            assertEquals("invalid input must not run expensive enrichment", 1, successFetcher.calls)
            assertTrue(enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, afterFailureFetcher))
            assertEquals("failure=false must release the registry entry", 1, afterFailureFetcher.calls)

            // 3) fail-open（external fetch 失敗）後，同 key 可重跑。
            assertTrue(enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, failingFetcher))
            assertEquals(1, failingFetcher.calls)
            assertTrue(enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, afterFailOpenFetcher))
            assertEquals("fail-open must release the registry entry", 1, afterFailOpenFetcher.calls)

            // 4) cancel 後，同 key 可重跑。
            val cancelJob = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, blockingFetcher)
            }
            withTimeout(TIMEOUT_MS) { cancelEntered.await() }
            cancelJob.cancel()
            val cancelled = try {
                cancelJob.await()
                false
            } catch (_: CancellationException) {
                true
            }
            assertTrue(cancelled)
            assertTrue(enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, afterCancelFetcher))
            assertEquals("cancel must release the registry entry", 1, afterCancelFetcher.calls)

            assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, model))
        }

        assertAliasAttached(fixture.providerId, model)
        listOf(slugSuccess, slugRerun, slugAfterFailure, slugAfterFailOpen, slugAfterCancel).forEach {
            assertExternalEvidence(it)
        }
    }

    // ===== Test 8：follower cancel ownership =====

    @Test
    fun cancellingFollowerDoesNotCancelLeaderOfSameExactKey() {
        val fixture = saveFixture("t8")
        val model = uniqueModel("t8")
        addMembership(fixture.providerId, model)

        val leaderEntered = CompletableDeferred<Unit>()
        val leaderRelease = CompletableDeferred<Unit>()
        val slug = uniqueSlug("t8")
        val fetcher = ScriptedFetcher { call ->
            when (call) {
                1 -> {
                    leaderEntered.complete(Unit)
                    leaderRelease.await()
                    rowsFor(slug)
                }
                else -> unexpectedExecution(call)
            }
        }

        runBlocking {
            val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, fetcher)
            }
            withTimeout(TIMEOUT_MS) { leaderEntered.await() }

            val follower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                enrich(fixture.providerId, fixture.accountId, fixture.credentialId, null, model, fetcher)
            }
            assertFalse(follower.isCompleted)

            follower.cancel()
            val followerCancelled = try {
                follower.await()
                false
            } catch (_: CancellationException) {
                true
            }
            assertTrue("follower must be cancellable", followerCancelled)
            assertFalse("follower cancel must not cancel the leader", leader.isCompleted)

            leaderRelease.complete(Unit)
            assertTrue("leader must still complete after follower cancel", withTimeout(TIMEOUT_MS) { leader.await() })
            assertEquals("follower cancel must not duplicate work", 1, fetcher.calls)
        }

        assertAliasAttached(fixture.providerId, model)
        assertExternalEvidence(slug)
    }

    // ===== controlled port / VM helpers =====

    /**
     * 真 production 入口：`AtlasViewModel.maybeEnrichMyModel`（見檔頭 controlled port 契約）。
     * 以 named arguments 呼叫，對參數順序不敏感；`lmspeedFetcher` 是唯一注入點。
     */
    private suspend fun enrich(
        providerId: String,
        accountId: String?,
        credentialId: String?,
        group: String?,
        providerModelId: String,
        fetcher: LmspeedFetcher?,
    ): Boolean = withTimeout(TIMEOUT_MS) {
        viewModel.maybeEnrichMyModel(
            providerId = providerId,
            accountId = accountId,
            credentialId = credentialId,
            group = group,
            providerModelId = providerModelId,
            lmspeedFetcher = fetcher,
        )
    }

    private class ScriptedFetcher(
        private val behavior: suspend (callIndex: Int) -> List<LmspeedRawModel>,
    ) : LmspeedFetcher {
        private val counter = AtomicInteger(0)
        val calls: Int get() = counter.get()

        override suspend fun fetchModels(): List<LmspeedRawModel> = behavior(counter.incrementAndGet())
    }

    private fun rowsFor(slug: String): List<LmspeedRawModel> = listOf(LmspeedRawModel(slug = slug))

    private fun unexpectedExecution(callIndex: Int): Nothing =
        throw AssertionError("unexpected bundle execution #$callIndex")

    // ===== fixture / evidence / cleanup =====

    private data class Fixture(val providerId: String, val accountId: String, val credentialId: String)

    /**
     * 真 production saveConnection 建立 provider／account／credential／endpoint；
     * seed model 自動入會並自動 bundle（等待 alias 落地＋同 exact key drain，讓後續
     * controlled run 起點確定）。測試模型用獨立 unique id，不與 seed 混用。
     */
    private fun saveFixture(label: String): Fixture {
        val name = "MYM-004 concurrency $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, SEED_MODEL, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId

        await { viewModel.myModelKeys.first { it.contains(MyModelKey(item.providerId, SEED_MODEL)) } }
        await { awaitAlias(item.providerId, SEED_MODEL) }
        // Drain：同 exact key 的 production entry 完成後才開始測試（不 sleep、不放寬 timeout）。
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                viewModel.maybeEnrichMyModel(
                    providerId = item.providerId,
                    accountId = item.accountId,
                    credentialId = item.credentialId,
                    group = null,
                    providerModelId = SEED_MODEL,
                    lmspeedFetcher = null,
                )
            }
        }
        return Fixture(item.providerId, item.accountId, item.credentialId)
    }

    /** 真 DB fixture：同 provider 的第二個 account／credential（Test 4 scope 隔離用）。 */
    private fun seedSecondScope(providerId: String): Pair<String, String> {
        val accountId = "mym004-conc-acc-${UUID.randomUUID()}"
        val credentialId = "mym004-conc-cred-${UUID.randomUUID()}"
        runBlocking {
            app.database.accountDao().insert(AccountEntity(accountId, providerId))
            app.database.credentialDao().insert(
                CredentialEntity(credentialId = credentialId, accountId = accountId, label = "concurrency fixture"),
            )
        }
        return accountId to credentialId
    }

    private fun addMembership(providerId: String, modelId: String) = runBlocking {
        assertTrue(
            MyModelsStore(app.database).addToMyModels(
                providerId, modelId, source = "USER", nowMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** per-model 真 DB 證據：bundle canonical／alias attach 落地且未冒充 VERIFIED。 */
    private fun assertAliasAttached(providerId: String, modelId: String) = runBlocking {
        val row = awaitAlias(providerId, modelId)
        assertEquals(MappingSource.NORMALIZATION.name, row.source)
        assertEquals(MappingConfidence.LOW.name, row.confidence)
        assertFalse(row.verified)
    }

    /** per-execution 真 DB 證據：該次 bundle external 步驟確實落 external observation。 */
    private fun assertExternalEvidence(slug: String) = runBlocking {
        val row = ExternalStore(app.database).latestObservationOf(LMSPEED_SOURCE, slug)
        assertNotNull("bundle must persist external observation for $slug", row)
        assertEquals(LMSPEED_SOURCE, row!!.source)
    }

    private fun uniqueModel(label: String): String = "mym004-conc-$label-${UUID.randomUUID()}"

    private fun uniqueSlug(label: String): String = "$EXTERNAL_PREFIX-$label-${UUID.randomUUID()}"

    // ===== harness（與 MyModelsFoundationTest／MyModelsBundleEvidenceTest 同形） =====

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
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

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { viewModel.state.first(predicate) }

    private suspend fun awaitAlias(providerId: String, modelId: String): dev.modelatlas.app.data.ProviderModelAliasEntity {
        var row = app.database.providerModelAliasDao().get(providerId, modelId)
        while (row == null) {
            yield()
            row = app.database.providerModelAliasDao().get(providerId, modelId)
        }
        return row!!
    }

    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM external_mappings WHERE externalId LIKE ?", arrayOf("$EXTERNAL_PREFIX-%"))
            db.execSQL("DELETE FROM external_observations WHERE externalModelId LIKE ?", arrayOf("$EXTERNAL_PREFIX-%"))
            val canonicalIds = mutableListOf<String>()
            db.query(
                "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($list)",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) canonicalIds += cursor.getString(0)
            }
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
            db.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($list)", args)
            if (canonicalIds.isNotEmpty()) {
                val canonicalPlaceholders = canonicalIds.joinToString(",") { "?" }
                db.execSQL(
                    "DELETE FROM canonical_models WHERE canonicalModelId IN ($canonicalPlaceholders) " +
                        "AND canonicalModelId NOT IN (SELECT canonicalModelId FROM provider_model_aliases)",
                    canonicalIds.toTypedArray<Any?>(),
                )
            }
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://mym004-concurrency.fixture.invalid/v1"
        const val SEED_MODEL = "mym004-concurrency-seed"
        const val FIXTURE_SECRET = "mym004-concurrency-fixture-secret-never-persisted"
        const val EXTERNAL_PREFIX = "mym004-conc"
    }
}
