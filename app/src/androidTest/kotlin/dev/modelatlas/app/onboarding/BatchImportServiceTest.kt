package dev.modelatlas.app.onboarding

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.OnboardingSecretRefusedException
import dev.modelatlas.app.data.RoomWorkflowStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.OnboardingStepStatus
import dev.modelatlas.core.onboarding.batch.BatchFailure
import dev.modelatlas.core.onboarding.batch.BatchImportParser
import dev.modelatlas.core.onboarding.batch.BatchItemStatus
import dev.modelatlas.core.onboarding.batch.BatchParseError
import dev.modelatlas.core.onboarding.batch.BatchParseException
import dev.modelatlas.core.onboarding.batch.BatchSnapshot
import dev.modelatlas.core.onboarding.batch.BatchStatus
import dev.modelatlas.core.onboarding.batch.StrictBatchJsonParser
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真 Room（獨立檔案 DB）、service、workflow、ViewModel 與 inference pipeline。
 * HTTPS 回應由 application interceptor 提供；不存取外網，也不涵蓋 socket/TLS。
 * 預設 parser 無憑證，應停 NEEDS_INPUT；Review 案例明示注入匿名測試設定，
 * 不把這個測試接縫誤當產品 JSON 支援匿名確認。所有 fixture 皆為測試資料。
 */
@RunWith(AndroidJUnit4::class)
class BatchImportServiceTest {
    private lateinit var context: Context
    private lateinit var database: AtlasDatabase
    private lateinit var vault: AndroidCredentialVault
    private lateinit var client: OkHttpClient
    private val databaseName = "batch-device-${UUID.randomUUID()}.db"
    private val requests = CopyOnWriteArrayList<Request>()
    private val viewModelStores = mutableListOf<ViewModelStore>()
    private val gates = mutableListOf<CountDownLatch>()

    @Volatile
    private var respond: (Request) -> Response = { success(it) }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = openDatabase()
        vault = AndroidCredentialVault(context)
        client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            respond(chain.request())
        }.build()
    }

    @After
    fun tearDown() {
        gates.forEach { it.countDown() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModelStores.forEach { it.clear() }
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun strictParseRunsItemsInOrderAndMissingCredentialsStayNeedsInput() = within {
        val result = service().start(input("first", "second"))

        assertEquals(BatchStatus.COMPLETED, result.status)
        assertEquals(listOf("batch-item-1", "batch-item-2"), result.items.map { it.itemId })
        assertTrue(result.items.all { it.status == BatchItemStatus.NEEDS_INPUT && it.attempts == 1 })
        assertEquals(listOf("/first", "/first", "/second", "/second"), paths())
        assertTrue(requests.all { it.method == "HEAD" && it.header("Authorization") == null })
        for (item in result.items) {
            val workflow = requireNotNull(RoomWorkflowStore(database).load(requireNotNull(item.workflowId)))
            assertEquals(OnboardingStepStatus.NEEDS_INPUT, workflow.stepStatuses[OnboardingStepId.AUTH])
            assertTrue(workflow.credentials.isEmpty())
            assertTrue(workflow.safeCredentialIds.isEmpty())
        }
        assertEquals(result, RoomBatchStateStore(database.batchSnapshotDao()).load(result.batchId))
        assertNoCommit()
    }

    @Test
    fun viewModelPublishesRoomReviewWithoutCommittingOrReprobingOnResume() = within {
        val svc = service(anonymousFixture = true)
        val vm = viewModel(svc)
        withContext(Dispatchers.Main) { vm.start(input("first", "second")) }
        val settled = vm.state.first { !it.busy && it.batches.singleOrNull()?.status == BatchStatus.COMPLETED }
        assertFalse(settled.error)
        val batch = settled.batches.single()
        assertReview(batch)
        assertEquals(
            listOf("/first", "/first", "/first/models", "/first/models", "/first/chat/completions",
                "/second", "/second", "/second/models", "/second/models", "/second/chat/completions"),
            paths(),
        )
        assertEquals(batch, svc.snapshots.first().single())
        assertTrue(requests.all { it.header("Authorization") == null })
        val before = requests.size
        withContext(Dispatchers.Main) { vm.resume(batch.batchId) }
        vm.state.first { !it.busy }
        withContext(Dispatchers.Main) { vm.retryItem(batch.batchId, batch.items.first().itemId) }
        vm.state.first { !it.busy }
        assertFalse(vm.state.value.error)
        assertEquals(before, requests.size)
        assertEquals(batch, svc.load(batch.batchId))
        val samples = database.sampleDao().observeLatest().first()
        assertEquals(2, samples.size)
        assertTrue(samples.all { it.outcome == "SUCCESS" && it.source == "BACKGROUND_PROBE" })
        // telemetry 既有 schema 用空字串保存匿名 scope；transport target 才是 null。
        assertTrue(samples.all { it.safeCredentialId.isEmpty() })
        assertNoCommit()
    }

    @Test
    fun cancelStopsSchedulingAndReopenedRoomResumesOnlyPendingItems() = within {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { gates += it }
        respond = { request ->
            if (request.url.encodedPath == "/first" && requests.size == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "測試閘門逾時" }
            }
            success(request)
        }
        val svc = service(anonymousFixture = true)
        val vm = viewModel(svc)
        withContext(Dispatchers.Main) { vm.start(input("first", "second")) }
        assertTrue("首筆探測必須已開始", entered.await(10, TimeUnit.SECONDS))
        try {
            val running = svc.snapshots.first { it.singleOrNull()?.items?.first()?.status == BatchItemStatus.RUNNING }.single()
            assertEquals(BatchItemStatus.PENDING, running.items[1].status)
            withContext(Dispatchers.Main) { vm.cancel() }
            assertTrue(vm.state.value.busy)
            assertEquals(listOf("/first"), paths())
        } finally {
            release.countDown()
        }
        val paused = vm.state.first { !it.busy && it.batches.singleOrNull()?.status == BatchStatus.PAUSED }.batches.single()
        assertEquals(BatchFailure.CANCELLED, paused.stopReason)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, paused.items[0].status)
        assertEquals(BatchItemStatus.PENDING, paused.items[1].status)
        assertNull(paused.items[1].workflowId)
        assertEquals(5, requests.size)
        withContext(Dispatchers.Main) { viewModelStores.forEach { it.clear() } }
        database.close()
        database = openDatabase()

        val recovered = service(anonymousFixture = true)
        assertEquals(listOf(paused.batchId), recovered.listBatchIds())
        assertEquals(paused, recovered.load(paused.batchId))
        val completed = recovered.resume(paused.batchId)
        assertReview(completed)
        assertEquals(paused.items.first(), completed.items.first())
        assertEquals(10, requests.size)
        assertEquals(1, completed.items[1].attempts)
        assertNoCommit()
    }

    @Test
    fun rateLimitKeepsEarlierReviewAndLeavesLaterItemUnscheduled() = within {
        respond = { request ->
            if (request.url.encodedPath == "/limited") response(request, 429, "remote-body-fixture")
            else success(request)
        }
        val svc = service(anonymousFixture = true)
        val vm = viewModel(svc)
        withContext(Dispatchers.Main) { vm.start(input("first", "limited", "last")) }
        val state = vm.state.first { !it.busy && it.batches.singleOrNull()?.status == BatchStatus.PAUSED }
        assertFalse(state.error)
        val batch = state.batches.single()
        assertEquals(BatchFailure.RATE_LIMITED, batch.stopReason)
        assertEquals(listOf(BatchItemStatus.AWAITING_REVIEW, BatchItemStatus.RATE_LIMITED, BatchItemStatus.PENDING),
            batch.items.map { it.status })
        assertEquals(1, batch.items[1].requestsSpent)
        assertTrue(batch.items[1].rateLimited)
        assertEquals(0, batch.items[2].attempts)
        assertNull(batch.items[2].workflowId)
        assertEquals(6, requests.size)
        assertEquals(1, paths().count { it == "/limited" })
        assertTrue(paths().none { it.startsWith("/last") })
        assertEquals(batch, RoomBatchStateStore(database.batchSnapshotDao()).load(batch.batchId))
        assertFalse(requireNotNull(database.batchSnapshotDao().get(batch.batchId)).snapshotJson.contains("remote-body-fixture"))
        assertNoCommit()
    }

    @Test
    fun secretInputHasFixedErrorsAndViewModelNeverRetainsRawInput() = within {
        val value = "sk-" + UUID.randomUUID().toString().replace("-", "")
        val cases = listOf(
            Triple("""[{"baseUrl":"https://batch.fixture.invalid/first","apiKey":"$value"}]""",
                BatchParseError.SECRET_FIELD_REFUSED, "匯入不接受 secret 欄位，原始金鑰請在安全輸入後另行綁定"),
            Triple("""[{"baseUrl":"https://batch.fixture.invalid/first","providerName":"$value"}]""",
                BatchParseError.SECRET_VALUE_REFUSED, "欄位值疑似含有 secret，已拒絕"),
        )
        val svc = service()
        val vm = viewModel(svc)
        for ((raw, expected, message) in cases) {
            val failure = runCatching { svc.start(raw) }.exceptionOrNull()
            assertTrue(failure is BatchParseException)
            assertEquals(expected, (failure as BatchParseException).error)
            assertEquals(message, failure.message)
            assertFalse(failure.toString().contains(value))
            withContext(Dispatchers.Main) { vm.start(raw) }
            val state = vm.state.first { !it.busy && it.error }
            assertTrue(state.batches.isEmpty())
            assertFalse(state.toString().contains(value))
            withContext(Dispatchers.Main) { vm.dismissError() }
            assertFalse(vm.state.value.error)
        }
        assertTrue(svc.listBatchIds().isEmpty())
        assertEquals(0, countRows("onboarding_snapshots"))
        assertTrue(requests.isEmpty())
        assertNoCommit()
    }

    @Test
    fun roomSecretGuardRejectsOverwriteAndPreservesLastGoodSnapshot() = within {
        val clean = service().start(input("first"))
        val store = RoomBatchStateStore(database.batchSnapshotDao())
        val value = "sk-" + UUID.randomUUID().toString().replace("-", "")
        val tainted = clean.copy(items = clean.items.map { it.copy(draft = it.draft.copy(providerName = value)) })
        val failure = runCatching { store.save(tainted) }.exceptionOrNull()
        assertTrue(failure is OnboardingSecretRefusedException)
        assertEquals("onboarding snapshot 疑似含有 secret 字樣，拒絕寫入", failure?.message)
        assertFalse(failure.toString().contains(value))
        assertEquals(clean, store.load(clean.batchId))
        assertFalse(requireNotNull(database.batchSnapshotDao().get(clean.batchId)).snapshotJson.contains(value))
        assertEquals(clean, store.snapshots.first().single())
        assertNoCommit()
    }

    private fun openDatabase(): AtlasDatabase =
        Room.databaseBuilder(context, AtlasDatabase::class.java, databaseName).build()

    private fun service(anonymousFixture: Boolean = false): BatchImportService {
        val parser = if (anonymousFixture) BatchImportParser { raw ->
            // 先真實嚴格 parse，再僅為測試啟用 NONE；產品 parser 不提供此選項。
            val parsed = StrictBatchJsonParser.parse(raw)
            parsed.copy(items = parsed.items.map { item ->
                item.copy(draft = item.draft.copy(authModeOverride = AuthMode.NONE, anonymousConfirmed = true))
            })
        } else StrictBatchJsonParser
        val noCredential = CredentialAccess { _, _ -> throw AssertionError("匿名測試不得解析憑證") }
        return BatchImportService(
            vault = vault,
            engine = InferenceEngine(OpenAiTransport(noCredential, client), AtlasRepository(database)),
            database = database,
            probeClient = client,
            parser = parser,
        )
    }

    private suspend fun viewModel(service: BatchImportService): BatchImportViewModel = withContext(Dispatchers.Main) {
        val vm = BatchImportViewModel(context.applicationContext as AtlasApplication, service)
        ViewModelStore().also { it.put("batch-fixture", vm); viewModelStores += it }
        vm
    }

    private suspend fun assertReview(batch: BatchSnapshot) {
        assertEquals(BatchStatus.COMPLETED, batch.status)
        assertTrue(batch.items.all { it.status == BatchItemStatus.AWAITING_REVIEW && it.reviewReached })
        assertEquals(batch.items.size, batch.items.map { it.workflowId }.toSet().size)
        for (item in batch.items) {
            val workflow = requireNotNull(RoomWorkflowStore(database).load(requireNotNull(item.workflowId)))
            assertNull(workflow.reviewApproval)
            assertNull(workflow.commitReceipt)
            val review = requireNotNull(workflow.reviewItems)
            assertEquals(1, review.modelsFound)
            assertEquals(1, review.modelsVerified)
            assertEquals("UNKNOWN", review.pricingStatus.name)
            assertEquals("UNKNOWN", review.quotaStatus.name)
            assertEquals(OnboardingStepStatus.NEEDS_INPUT, workflow.stepStatuses[OnboardingStepId.REVIEW])
            assertNull(database.onboardingCommitDao().getByWorkflow(workflow.workflowId))
        }
    }

    private fun assertNoCommit() {
        for (table in listOf("providers", "accounts", "credentials", "endpoints", "offerings",
            "credential_availability", "onboarding_commits")) {
            assertEquals("匯入不可寫產品資料：$table", 0, countRows(table))
        }
    }

    private fun countRows(table: String): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun paths(): List<String> = requests.map { it.url.encodedPath }

    private fun input(vararg paths: String): String = paths.joinToString(prefix = "[", postfix = "]") {
        """{"baseUrl":"https://batch.fixture.invalid/$it","suggestedModelId":"fixture-model"}"""
    }

    private fun success(request: Request): Response = when (request.method) {
        "HEAD" -> response(request, 200, "")
        "GET" -> response(request, 200, """{"data":[{"id":"fixture-model"}]}""")
        "POST" -> response(request, 200,
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                "data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n" +
                "data: [DONE]\n\n", "text/event-stream")
        else -> throw AssertionError("非預期的 HTTP method")
    }

    private fun response(request: Request, code: Int, body: String, type: String = "application/json"): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()

    private fun <T> within(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(30_000) { block() } }
}
