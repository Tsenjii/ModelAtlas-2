package dev.modelatlas.app.onboarding

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.OnboardingSnapshotEntity
import dev.modelatlas.app.data.RoomWorkflowStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.onboarding.OnboardingStatus
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.ReviewApproval
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Onboarding UI bridge 測試：真 DB + 真 vault + MockWebServer loopback。
 *
 * 覆蓋 service 新 API 與 ViewModel 固定 API 的重要邊界：
 * 1. `snapshots` Flow 在存 vault 後、探測前即有 snapshot 可觀察，且只含 safe ref。
 * 2. `retryStep` 不重置總預算；`skipStep` 拒絕 REVIEW/COMMIT 且訊息固定安全。
 * 3. `editQuick` origin 變更不可沿用舊 key、保留總預算、清 approval；改名不重新探測。
 * 4. ViewModel `startQuick` 經 Room observe 更新 snapshot/resumable，錯誤只設布林不洩漏。
 * 5. ViewModel `cancel` 後 snapshot 可恢復、vault ref 保留；`close` 只離開不放棄單。
 * 6. `snapshots` Flow 解碼錯誤固定安全訊息。
 *
 * 不讀真實憑證；secret 為固定 fixture 字串，teardown 只刪本檔建立的 vault ref 與 DB。
 */
@RunWith(AndroidJUnit4::class)
class OnboardingBridgeTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private lateinit var vault: AndroidCredentialVault
    private lateinit var service: QuickAddService
    private lateinit var viewModel: OnboardingViewModel
    private lateinit var server: MockWebServer

    private var database: AtlasDatabase? = null
    private var vaultKeysBefore: Set<String> = emptySet()
    private var requestsAtSetup: Int = 0
    private var consumedRequests: Int = 0

    private val databaseName = "onboarding-bridge-" + UUID.randomUUID() + ".db"
    private val ownedRefs = mutableListOf<String>()
    private val secret = "bridge-fixture-secret-9c2e4a7d1b5f"
    private val newSecret = "bridge-fixture-secret-rotate-3d8f2a6c9e1b"
    private val models = listOf("fixture-model-a", "fixture-model-b")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as Application
        vault = AndroidCredentialVault(context)
        database = Room.databaseBuilder(context, AtlasDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val repository = AtlasRepository(requireDb())
        val engine = InferenceEngine(
            OpenAiTransport(vault, OkHttpClient(), allowLoopbackHttpForTests = true),
            repository,
        )
        server = MockWebServer()
        server.start()
        service = QuickAddService(
            vault = vault,
            engine = engine,
            database = requireDb(),
            probeClient = OkHttpClient(),
            allowLoopbackHttpForTests = true,
        )
        viewModel = OnboardingViewModel(app, service)
        vaultKeysBefore = vaultPreferenceKeys()
        runBlocking { assertLoopbackCleartextReachable() }
        requestsAtSetup = server.requestCount
        consumedRequests = server.requestCount
    }

    @After
    fun tearDown() {
        runCatching { viewModel.close() }
        runCatching { server.shutdown() }
        for (ref in ownedRefs) runCatching { vault.remove(ref) }
        ownedRefs.clear()
        deleteDatabaseFiles()
    }

    @Test
    fun snapshotsFlowEmitsImmediatelyAndHoldsOnlySafeRef() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val ref = onlyOwnedRef(workflowId)
        val list = awaitFlow { service.snapshots.first() }
        val snapshot = list.first { it.workflowId == workflowId }
        assertEquals(listOf(ref), snapshot.safeCredentialIds)
        assertTrue(vault.has(ref))
        val json = requireDb().onboardingSnapshotDao().getByWorkflow(workflowId)!!.snapshotJson
        assertFalse(json.contains(secret))
        assertTrue(json.contains(ref))
        assertEquals(1, list.count { it.workflowId == workflowId })
    }

    @Test
    fun retryPreservesTotalBudget() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        onlyOwnedRef(workflowId)
        val before = requireNotNull(service.loadSnapshot(workflowId)).budget.totalRequests
        assertTrue(before > 0)
        enqueueInspect()
        enqueueCatalogFlow()
        service.retryStep(workflowId, OnboardingStepId.INSPECT)
        val after = requireNotNull(service.loadSnapshot(workflowId)).budget.totalRequests
        assertTrue("retry must not reset total budget", after >= before)
        assertNull(service.loadSnapshot(workflowId)!!.reviewApproval)
    }

    @Test
    fun skipRejectsReviewWithFixedSafeMessage() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        onlyOwnedRef(workflowId)
        val failure = runCatching { service.skipStep(workflowId, OnboardingStepId.REVIEW) }
        assertTrue(failure.isFailure)
        val message = failure.exceptionOrNull()!!.message.orEmpty()
        assertFalse(message.contains(secret))
        assertFalse(message.contains("127.0.0.1"))
    }

    @Test
    fun editQuickOriginChangeDropsOldKeyAndPreservesBudget() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val oldRef = onlyOwnedRef(workflowId)
        val beforeBudget = requireNotNull(service.loadSnapshot(workflowId)).budget.totalRequests
        enqueueInspect()
        enqueueCatalogFlow()
        val newBase = loopbackLocalhostBase()
        service.editQuick(workflowId, baseUrl = newBase, secretTransient = newSecret)
        val edited = requireNotNull(service.loadSnapshot(workflowId))
        assertEquals(newBase, edited.baseUrl)
        assertEquals(1, edited.safeCredentialIds.size)
        val newRef = edited.safeCredentialIds.single()
        assertTrue(newRef != oldRef)
        ownedRefs += newRef
        assertTrue(vault.has(newRef))
        assertFalse("origin change must not reuse old key", vault.has(oldRef))
        assertTrue("must not reset total budget", edited.budget.totalRequests >= beforeBudget)
        assertNull(edited.reviewApproval)
    }

    @Test
    fun editQuickRenameDoesNotReprobe() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        onlyOwnedRef(workflowId)
        val before = requireNotNull(service.loadSnapshot(workflowId))
        val requestsBefore = server.requestCount
        service.editQuick(workflowId, providerName = "Renamed Provider")
        val after = requireNotNull(service.loadSnapshot(workflowId))
        assertEquals("Renamed Provider", after.providerName)
        assertEquals(before.budget.totalRequests, after.budget.totalRequests)
        assertEquals(before.catalog, after.catalog)
        assertEquals(requestsBefore, server.requestCount)
        assertNull(after.reviewApproval)
    }

    @Test
    fun editQuickClearsApproval() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        onlyOwnedRef(workflowId)
        val stamped = requireNotNull(service.loadSnapshot(workflowId)).copy(
            reviewApproval = ReviewApproval(approved = true, approvedAtMillis = 1L, approvedBy = "test"),
        )
        RoomWorkflowStore(requireDb()).save(stamped)
        assertNotNull(requireNotNull(service.loadSnapshot(workflowId)).reviewApproval)
        service.editQuick(workflowId, providerName = "Another Name")
        assertNull(service.loadSnapshot(workflowId)!!.reviewApproval)
    }

    @Test
    fun viewModelStartQuickPublishesSnapshotAndResumableWithoutLeak() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        viewModel.startQuick(loopbackBase(), "", secret, "")
        // ViewModel 預先產生 currentId 並即時 observe：DRAFT/RUNNING 只是過渡快照，
        // 終態（本路徑為 NEEDS_INPUT，見 QuickAddServiceTest service 層斷言）才可斷言。
        val terminal = awaitFlow {
            viewModel.state.first { (it.snapshot?.status == OnboardingStatus.NEEDS_INPUT && !it.busy) || it.error }
        }
        val snapshot = terminal.snapshot
        assertNotNull(snapshot)
        assertEquals(OnboardingStatus.NEEDS_INPUT, snapshot!!.status)
        val state = viewModel.state.value
        assertFalse(state.busy)
        assertFalse(state.error)
        assertTrue(state.resumable.any { it.workflowId == snapshot.workflowId })
        ownedRefs += snapshot.safeCredentialIds
        assertFalse(state.toString().contains(secret))
    }

    @Test
    fun viewModelInvalidInputSetsErrorBooleanWithoutLeak() = within {
        viewModel.startQuick("not-a-url", "", secret, "")
        val state = awaitFlow { viewModel.state.first { it.error || it.snapshot != null } }
        assertTrue(state.error)
        assertNull(state.snapshot)
        assertFalse(state.toString().contains(secret))
    }

    @Test
    fun viewModelCancelKeepsSnapshotAndVaultRef() = within {
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                    .setHeadersDelay(500, TimeUnit.MILLISECONDS),
            )
        }
        viewModel.startQuick(loopbackBase(), "", secret, "")
        // 先等 snapshot 可觀察（證明已存 vault 且首 persist 落地），再取消：
        // 否則取消可能搶在 job 首次 persist 前，根本無 snapshot 可恢復，屬於測試競態而非產品語意。
        awaitFlow { viewModel.state.first { it.snapshot != null } }
        viewModel.cancel()
        awaitFlow { viewModel.state.first { !it.busy } }
        val snapshot = viewModel.state.value.snapshot
            ?: awaitFlow { service.snapshots.first { list -> list.isNotEmpty() } }.first()
        ownedRefs += snapshot.safeCredentialIds
        assertTrue(snapshot.safeCredentialIds.isNotEmpty())
        assertTrue(vault.has(snapshot.safeCredentialIds.single()))
        assertNotNull(service.loadSnapshot(snapshot.workflowId))
    }

    @Test
    fun viewModelCloseLeavesWorkflowWithoutAbandon() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        viewModel.startQuick(loopbackBase(), "", secret, "")
        // 同上：等終態 NEEDS_INPUT 再 close，避免在探測中途取消留下 RUNNING/CANCELLED 的非確定狀態。
        val snapshot = awaitFlow {
            viewModel.state.first { (it.snapshot?.status == OnboardingStatus.NEEDS_INPUT && !it.busy) || it.error }
        }.snapshot
        assertNotNull(snapshot)
        val workflowId = snapshot!!.workflowId
        ownedRefs += snapshot.safeCredentialIds
        viewModel.close()
        assertNull(viewModel.state.value.snapshot)
        val persisted = service.loadSnapshot(workflowId)
        assertNotNull(persisted)
        assertTrue(persisted!!.status != OnboardingStatus.ABANDONED)
        assertTrue(vault.has(snapshot.safeCredentialIds.single()))
        assertTrue(viewModel.state.value.resumable.any { it.workflowId == workflowId })
    }

    @Test
    fun snapshotsFlowDecodeErrorUsesFixedSafeMessage() = within {
        requireDb().onboardingSnapshotDao().upsert(
            OnboardingSnapshotEntity(
                workflowId = "corrupt-" + UUID.randomUUID(),
                updatedAt = System.currentTimeMillis(),
                snapshotJson = "{not-json-secret:" + secret + "}",
            ),
        )
        val failure = runCatching { awaitFlow { service.snapshots.first() } }
        assertTrue(failure.isFailure)
        assertEquals("snapshot \u89E3\u6790\u5931\u6557", failure.exceptionOrNull()!!.message)
        assertFalse(failure.exceptionOrNull()!!.message.orEmpty().contains(secret))
    }

    private fun <T> within(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(TEST_TIMEOUT_MS) { block() } }

    private suspend fun <T> awaitFlow(block: suspend () -> T): T = withTimeout(FLOW_TIMEOUT_MS) { block() }

    private fun requireDb(): AtlasDatabase = database ?: error("database already closed")

    private fun loopbackBase(): String = "http://$LOOPBACK_HOST:${server.port}$BASE_PATH"

    private fun loopbackLocalhostBase(): String = "http://localhost:${server.port}$BASE_PATH"

    private suspend fun snapshotOf(workflowId: String) =
        requireNotNull(service.loadSnapshot(workflowId)) { "snapshot missing: $workflowId" }

    private suspend fun onlyOwnedRef(workflowId: String): String {
        val ref = snapshotOf(workflowId).safeCredentialIds.single()
        ownedRefs += ref
        return ref
    }

    private fun drainRequests(): List<RecordedRequest> {
        val pending = server.requestCount - consumedRequests
        val out = ArrayList<RecordedRequest>(pending)
        repeat(pending) { index ->
            val request = server.takeRequest(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (request == null) throw AssertionError("missing request #${consumedRequests + index}")
            consumedRequests++
            out += request
        }
        return out
    }

    private fun vaultPreferenceKeys(): Set<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.keys.toSet()

    private fun closeDatabase() {
        database?.let { runCatching { it.close() } }
        database = null
    }

    private fun deleteDatabaseFiles() {
        closeDatabase()
        runCatching { context.deleteDatabase(databaseName) }
        val path = context.getDatabasePath(databaseName).path
        for (suffix in listOf("", "-wal", "-shm", "-journal")) runCatching { File(path + suffix).delete() }
    }

    private fun enqueueInspect() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)) }
    }

    private fun enqueueCatalog(modelIds: List<String>) {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", JSON).setBody(catalog(modelIds)),
        )
    }

    private fun catalog(ids: List<String>): String = buildString {
        append("""{"object":"list","data":[""")
        ids.forEachIndexed { index, id ->
            if (index > 0) append(',')
            append("""{"id":"$id","object":"model"}""")
        }
        append("]}")
    }

    private fun enqueueCatalogFlow() {
        enqueueCatalog(models)
        enqueueCatalog(models)
        repeat(MAX_TEST_MODELS) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", SSE)
                    .setHeadersDelay(HEADERS_DELAY_MS, TimeUnit.MILLISECONDS)
                    .setBody(chatStream()),
            )
        }
    }

    private fun chatStream(): String = buildString {
        append("""data: {"model":"fixture-model-a","choices":[{"index":0,"delta":{"content":"ok"}}]}""")
        append("\n\n")
        append("""data: {"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
        append("\n\n")
        append("data: [DONE]\n\n")
    }

    private suspend fun assertLoopbackCleartextReachable() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", JSON))
        val call = OkHttpClient().newCall(
            Request.Builder().url(loopbackBase()).head().build(),
        )
        val outcome = runCatching { call.execute().use { it.code } }
        val error = outcome.exceptionOrNull()
        if (error != null) {
            throw AssertionError(
                "loopback cleartext is not usable on this device (API ${android.os.Build.VERSION.SDK_INT}; " +
                    "the app manifest sets usesCleartextTraffic=false). QuickAddService integration tests " +
                    "need app/build.gradle.kts androidTestImplementation(mockwebserver:4.12.0) applied AND a " +
                    "debug/androidTest network security policy that permits cleartext to 127.0.0.1",
                error,
            )
        }
        assertEquals(200, outcome.getOrThrow())
        val recorded = requireNotNull(server.takeRequest(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertEquals("HEAD", recorded.method)
        assertEquals(BASE_PATH, recorded.path)
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val BASE_PATH = "/v1"
        const val JSON = "application/json"
        const val SSE = "text/event-stream"
        const val PREFERENCES = "credential_ciphertexts"
        const val MAX_TEST_MODELS = 3
        const val TEST_TIMEOUT_MS = 180_000L
        const val FLOW_TIMEOUT_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val HEADERS_DELAY_MS = 50L
    }
}
