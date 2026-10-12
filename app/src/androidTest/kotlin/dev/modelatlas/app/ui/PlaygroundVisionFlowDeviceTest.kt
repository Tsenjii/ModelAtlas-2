package dev.modelatlas.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.R
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.RoomCommitter
import dev.modelatlas.app.data.VisionImagePayload
import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.multimodal.ImageUrlPart
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.ReviewApproval
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual anonymous Room connection -> VM -> Engine -> HTTP interceptor; no external network. */
@RunWith(AndroidJUnit4::class)
class PlaygroundVisionFlowDeviceTest {
    private lateinit var app: AtlasApplication
    private lateinit var database: AtlasDatabase
    private lateinit var repository: AtlasRepository
    private lateinit var client: OkHttpClient
    private lateinit var vm: AtlasViewModel
    private val store = ViewModelStore()
    private val requests = CopyOnWriteArrayList<Request>()

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        repository = AtlasRepository(database)
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req
            val sse = "data: {\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                "data: [DONE]\n\n"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "text/event-stream")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val transport = OpenAiTransport(
            CredentialAccess { _, _ -> error("Anonymous image inference must not read any secret") },
            client,
        )
        app = AtlasApplication()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(app, context)
        fun inject(field: String, value: Any) {
            AtlasApplication::class.java.getDeclaredField(field).apply { isAccessible = true }.set(app, value)
        }
        inject("database", database)
        inject("repository", repository)
        inject("engine", InferenceEngine(transport, repository))
        @Suppress("UNCHECKED_CAST")
        val ready = AtlasApplication::class.java.getDeclaredField("_ready").apply {
            isAccessible = true
        }.get(app) as MutableStateFlow<Boolean>
        ready.value = true
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return AtlasViewModel(app) as T
                    }
                })[AtlasViewModel::class.java]
            }
        }
    }

    @After fun tearDown() {
        try {
            if (::vm.isInitialized) runBlocking { withContext(Dispatchers.Main.immediate) {
                vm.cancel()
                store.clear()
            } }
            if (::app.isInitialized) app.applicationScope.cancel()
            if (::client.isInitialized) {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        } finally {
            if (::database.isInitialized) database.close()
        }
    }

    @Test fun realPlaygroundVisionWiresContentArrayAndNeverBorrowsCredential() = runBlocking {
        val snapshot = OnboardingSnapshot(
            workflowId = UUID.randomUUID().toString(),
            flowType = OnboardingFlowType.ADVANCED,
            providerName = "匿名圖片模型",
            baseUrl = "https://vision.fixture.invalid/v1",
            credentials = emptyList(),
            anonymousConfirmed = true,
            manualModelIds = listOf("fixture-model"),
            reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "測試確認"),
            createdAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_000_001L,
        )
        val receipt = RoomCommitter(database).commit(snapshot)
        val record = withTimeout(15_000L) {
            repository.connections.first { it.any { row -> row.providerId == receipt.providerId } }
                .single { it.providerId == receipt.providerId }
        }
        assertTrue(record.anonymous)
        withTimeout(15_000L) {
            vm.state.first { it.connections.any { item -> item.id == record.id && item.enabled } }
        }
        val image = VisionImagePayload.prepare(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01),
            "fixture-photo.jpg",
        )

        withContext(Dispatchers.Main.immediate) {
            vm.selectConnection(record.id)
            val rowsField = AtlasViewModel::class.java.getDeclaredField("_allModelRows").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val rows = rowsField.get(vm) as MutableStateFlow<List<ModelRow>>
            rows.value = listOf(ModelRow(
                providerId = record.providerId, providerModelId = record.modelId,
                canonicalId = "fixture-canonical", endpointId = record.endpointId,
                operation = "CHAT_COMPLETIONS", group = null,
                selectedKeyUsable = true, capabilities = setOf(Capability.VISION),
                supportsVision = true,
            ))
            vm.sendWithImage("請描述圖片", image)
        }
        val state = withTimeout(15_000L) { vm.state.first {
            !it.busy && it.messages.lastOrNull()?.text == "ok"
        } }
        assertNull(state.error)
        assertEquals("fixture-photo.jpg", state.messages.first().attachmentName)
        assertEquals(1, requests.size)
        assertNull(requests.single().header("Authorization"))
        val body = Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
        val parts = JSONObject(body).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("請描述圖片", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(image.url, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertFalse("輸出不得包含檔案絕對路徑", body.contains("/storage/emulated"))

        // Regression: a subsequent TEXT-ONLY turn still retransmits the first
        // Vision message from the same conversation. The target must continue
        // exposing the selected model's verified VISION capability.
        withContext(Dispatchers.Main.immediate) {
            vm.send("接著說")
        }
        val followUp = withTimeout(15_000L) { vm.state.first {
            !it.busy && it.messages.size == 4 && it.messages.lastOrNull()?.text == "ok"
        } }
        assertNull(followUp.error)
        assertEquals(2, requests.size)
        assertNull(requests[1].header("Authorization"))
        val followUpBody = Buffer().also { requests[1].body!!.writeTo(it) }.readUtf8()
        val messages = JSONObject(followUpBody).getJSONArray("messages")
        assertEquals(3, messages.length())
        val originalImage = messages.getJSONObject(0).getJSONArray("content")
            .getJSONObject(1).getJSONObject("image_url").getString("url")
        assertEquals(image.url, originalImage)
        assertEquals("接著說", messages.getJSONObject(2).getString("content"))

        // Capability revoked between turns: fail locally, never borrow stale
        // image permission or silently strip the previous media context.
        withContext(Dispatchers.Main.immediate) {
            val field = AtlasViewModel::class.java.getDeclaredField("_allModelRows").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val rows = field.get(vm) as MutableStateFlow<List<ModelRow>>
            rows.value = rows.value.map { it.copy(capabilities = emptySet(), supportsVision = false) }
            vm.send("不可以借用已撤銷的圖片權限")
        }
        assertEquals(app.getString(R.string.playground_vision_unavailable), vm.state.value.error)
        assertEquals(2, requests.size)
        assertEquals(4, vm.state.value.messages.size)
    }

    /** Negative proof at the real VM boundary: a foreign group cannot authorize image upload. */
    @Test fun visionKnownOnlyForForeignGroupNeverReachesHttp() = runBlocking {
        val snapshot = OnboardingSnapshot(
            workflowId = UUID.randomUUID().toString(),
            flowType = OnboardingFlowType.ADVANCED,
            providerName = "隔離測試 Vision",
            baseUrl = "https://vision.fixture.invalid/v1",
            credentials = emptyList(),
            anonymousConfirmed = true,
            manualModelIds = listOf("fixture-model"),
            reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "測試確認"),
            createdAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_000_001L,
        )
        val receipt = RoomCommitter(database).commit(snapshot)
        val record = withTimeout(15_000L) {
            repository.connections.first { it.any { row -> row.providerId == receipt.providerId } }
                .single { it.providerId == receipt.providerId }
        }
        withTimeout(15_000L) {
            vm.state.first { it.connections.any { item -> item.id == record.id && item.enabled } }
        }
        val image = VisionImagePayload.prepare(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01),
            "foreign-group.jpg",
        )
        withContext(Dispatchers.Main.immediate) {
            vm.selectConnection(record.id)
            val field = AtlasViewModel::class.java.getDeclaredField("_allModelRows").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val rows = field.get(vm) as MutableStateFlow<List<ModelRow>>
            rows.value = listOf(ModelRow(
                providerId = record.providerId, providerModelId = record.modelId,
                canonicalId = "fixture-canonical", endpointId = record.endpointId,
                operation = "CHAT_COMPLETIONS", group = "different-group",
                selectedKeyUsable = true, capabilities = setOf(Capability.VISION),
                supportsVision = true,
            ))
            // The selected group is null, so no other group may lend Vision.
            vm.sendWithImage("should never leave device", image)
        }
        assertTrue("image refusal may not create a chat message", vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.busy)
        assertEquals(app.getString(R.string.playground_vision_unavailable), vm.state.value.error)
        assertEquals("no HTTP request is permitted on foreign-group capability", 0, requests.size)
    }


    @Test fun directVmCallerCannotBypassLocalImageValidation() = runBlocking {
        val snapshot = OnboardingSnapshot(
            workflowId = UUID.randomUUID().toString(),
            flowType = OnboardingFlowType.ADVANCED,
            providerName = "非法附件攔截",
            baseUrl = "https://vision.fixture.invalid/v1",
            credentials = emptyList(), anonymousConfirmed = true,
            manualModelIds = listOf("fixture-model"),
            reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "確認"),
            createdAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_000_001L,
        )
        val receipt = RoomCommitter(database).commit(snapshot)
        val record = withTimeout(15_000L) {
            repository.connections.first { it.any { row -> row.providerId == receipt.providerId } }
                .single { it.providerId == receipt.providerId }
        }
        withTimeout(15_000L) {
            vm.state.first { it.connections.any { item -> item.id == record.id && item.enabled } }
        }
        withContext(Dispatchers.Main.immediate) {
            vm.selectConnection(record.id)
            val field = AtlasViewModel::class.java.getDeclaredField("_allModelRows").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val rows = field.get(vm) as MutableStateFlow<List<ModelRow>>
            rows.value = listOf(ModelRow(
                providerId = record.providerId,
                providerModelId = record.modelId,
                canonicalId = "fixture-canonical",
                endpointId = record.endpointId,
                operation = "CHAT_COMPLETIONS", group = null,
                selectedKeyUsable = true, supportsVision = true,
                capabilities = setOf(Capability.VISION),
            ))
            vm.sendWithImage("拒絕遠端圖片 URL", ImageUrlPart("https://example.invalid/untrusted.png"))
        }
        assertEquals(app.getString(R.string.playground_image_error), vm.state.value.error)
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.busy)
        assertEquals(0, requests.size)
    }
}
