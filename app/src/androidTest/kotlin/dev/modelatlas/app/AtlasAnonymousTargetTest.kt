package dev.modelatlas.app

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.data.RoomCommitter
import dev.modelatlas.app.data.decodeProviderManualConfig
import dev.modelatlas.app.data.encodeToSnapshotJson
import dev.modelatlas.app.data.ensureSnapshotJsonHasNoSecretRefs
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialLocation
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.CustomAuthFieldRef
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.ReviewApproval
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
 * 真 Room commit／查詢 → AtlasViewModel → InferenceEngine → OpenAiTransport → Room telemetry。
 * 僅在 OkHttp application interceptor 回覆測試 SSE，檢查實際組好的 HTTPS 請求；不宣稱測到 socket／TLS。
 * Application 沒有公開注入入口，因此只對本檔建立、未呼叫 onCreate 的實例反射注入；不改全域 Application。
 * vault 使用 UUID ref 與純測試值，清理只移除本檔 refs；Room 完全使用獨立記憶體資料庫。
 */
@RunWith(AndroidJUnit4::class)
class AtlasAnonymousTargetTest {
    private lateinit var app: AtlasApplication
    private lateinit var database: AtlasDatabase
    private lateinit var repository: AtlasRepository
    private lateinit var vault: AndroidCredentialVault
    private lateinit var client: OkHttpClient
    private lateinit var viewModel: AtlasViewModel
    private val store = ViewModelStore()
    private val ownedRefs = mutableListOf<String>()
    private val targets = CopyOnWriteArrayList<InferenceTarget>()
    private val promptBatches = CopyOnWriteArrayList<List<PromptMessage>>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val resolutions = CopyOnWriteArrayList<Pair<String, String>>()
    private val resolutionsAtTarget = CopyOnWriteArrayList<Int>()
    private var responseCode = 200
    private var responseText = "ok"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        repository = AtlasRepository(database)
        vault = AndroidCredentialVault(context)
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (responseCode == 200) {
                "data: {\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$responseText\"}}]}\n\n" +
                    "data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n" +
                    "data: [DONE]\n\n"
            } else responseText
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(responseCode).message("測試回應")
                .header("Content-Type", "text/event-stream")
                .header("x-ratelimit-remaining-requests", responseText)
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val transport = OpenAiTransport(CredentialAccess { ref, origin ->
            resolutions += ref to origin
            vault.bearerToken(ref, origin)
        }, client)
        val observingTransport = object : InferenceTransport {
            override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> {
                targets += target
                promptBatches += messages.toList()
                resolutionsAtTarget += resolutions.size
                return transport.stream(target, messages)
            }
        }
        app = AtlasApplication()
        // 只附加資源／receiver 所需 Context；不啟動正式 DB 或 recoverInterrupted。
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(app, context)
        inject("database", database)
        inject("repository", repository)
        inject("vault", vault)
        inject("engine", InferenceEngine(observingTransport, repository))
        @Suppress("UNCHECKED_CAST")
        val ready = AtlasApplication::class.java.getDeclaredField("_ready").apply {
            isAccessible = true
        }.get(app) as MutableStateFlow<Boolean>
        ready.value = true
        onMain {
            viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            })[AtlasViewModel::class.java]
        }
    }

    @After
    fun tearDown() {
        // 先等取消的 finally 完成 telemetry，再清掉 ViewModel 與資料庫。
        try {
            if (::viewModel.isInitialized) {
                onMain { viewModel.cancel() }
                awaitUi { !it.busy }
            }
        } finally {
            onMain { store.clear() }
            if (::app.isInitialized) app.applicationScope.cancel()
            if (::client.isInitialized) {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
            try {
                if (::vault.isInitialized) {
                    var cleanupFailure: Exception? = null
                    ownedRefs.forEach { ref ->
                        try {
                            vault.remove(ref)
                        } catch (failure: Exception) {
                            if (cleanupFailure == null) cleanupFailure = failure
                        }
                    }
                    cleanupFailure?.let { throw it }
                }
            } finally {
                if (::database.isInitialized) database.close()
            }
        }
    }

    @Test
    fun selectingAnonymousAfterBearerPassesNullAndSendsNoAuthorization() {
        val bearerRef = storeSecret(BEARER_VALUE)
        val bearer = commit("具憑證連線", listOf(SafeCredentialRef(bearerRef, "推理憑證")))
        val anonymous = commit("匿名連線")
        assertTrue(anonymous.anonymous)
        assertEquals("", anonymous.credentialId)
        awaitConnections(bearer, anonymous)

        sendAndAwait(bearer, Outcome.SUCCESS)
        assertTrue("前置請求必須真的使用 bearer", requests.single().header("Authorization") == "Bearer $BEARER_VALUE")
        val readsBeforeAnonymous = resolutions.size
        val sample = sendAndAwait(anonymous, Outcome.SUCCESS)

        val target = targets.last()
        assertEquals(anonymous.providerId, target.providerId)
        assertEquals(anonymous.accountId, target.accountId)
        assertEquals(anonymous.endpointId, target.endpointId)
        assertEquals(anonymous.modelId, target.modelId)
        assertNull("匿名 target 不可把空字串當成 vault ref", target.credentialId)
        assertTrue(target.authFields.isEmpty())
        assertEquals("匿名請求不得讀取憑證", readsBeforeAnonymous, resolutions.size)
        assertNull(requests.last().header("Authorization"))
        assertNull(requests.last().url.query)
        assertEquals("POST", requests.last().method)
        assertEquals("/v1/chat/completions", requests.last().url.encodedPath)
        assertEquals(2, requests.size)
        assertEquals(anonymous.id, viewModel.state.value.selectedId)
        assertEquals(listOf("測試提示", "ok"), viewModel.state.value.messages.map { it.text })
        // telemetry 目前契約以空字串保存匿名 scope；target 的 null 語意不可混淆。
        assertEquals("", sample.safeCredentialId)
        assertEquals("UNKNOWN", sample.priceState)
        assertEquals("UNKNOWN", sample.quotaState)
        assertEquals(1L, sample.usage.outputTokens)
        assertNotNull(sample.completedAt)
        assertNoSecretOutsideRequest()
    }

    @Test
    fun customHeaderAndQueryPassOnlyRefsExcludeAccountPatAndRedactEchoes() {
        val fixture = customConnection()
        // 故意由遠端回顯兩種 custom auth 值，確認聊天與 rate-limit telemetry 都經去敏。
        responseText = "$HEADER_VALUE $QUERY_VALUE"
        val sample = sendAndAwait(fixture.record, Outcome.SUCCESS)
        assertCustomRequest(fixture)
        assertNoSecretOutsideRequest()
        assertEquals("[REDACTED] [REDACTED]", viewModel.state.value.messages.last().text)
        assertEquals("[REDACTED] [REDACTED]", sample.rateLimitHeaders["x-ratelimit-remaining-requests"])
        assertEquals(fixture.bearerRef, sample.safeCredentialId)
    }

    @Test
    fun customAuthFailurePersistsSafeTelemetryAndDoesNotExposeRemoteBody() {
        val fixture = customConnection()
        responseCode = 401
        responseText = "$BEARER_VALUE $HEADER_VALUE $QUERY_VALUE"
        val sample = sendAndAwait(fixture.record, Outcome.AUTH)
        assertCustomRequest(fixture)
        assertNoSecretOutsideRequest()
        assertEquals(401, sample.httpStatus)
        assertNotNull(viewModel.state.value.error)
        assertEquals("", viewModel.state.value.messages.last().text)
        assertEquals(1, requests.size)
        assertEquals("[REDACTED] [REDACTED] [REDACTED]", sample.rateLimitHeaders["x-ratelimit-remaining-requests"])
    }

    @Test
    fun cancel_marksTruncatedAssistantWithoutFabricationAndKeepsHistorySuccessfulOnly() {
        val record = commit("取消截斷連線")
        awaitConnections(record)
        val hangingTransport = object : InferenceTransport {
            override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
                emit(StreamEvent.Delta("partial", false))
                awaitCancellation()
            }
        }
        inject("engine", InferenceEngine(hangingTransport, repository))
        onMain {
            viewModel.selectConnection(record.id)
            viewModel.send("第一句")
        }
        awaitUi { state ->
            state.messages.any { it.role == "assistant" && it.text.contains("partial") }
        }
        onMain { viewModel.cancel() }
        val cancelled = awaitUi { !it.busy }
        val assistantText = cancelled.messages.last { it.role == "assistant" }.text
        // 不捏造內容：只有已到達的 partial 加上截斷標記。
        assertEquals("partial" + CANCEL_TRUNCATION_MARK, assistantText)
        assertTrue(assistantText.endsWith(CANCEL_TRUNCATION_MARK))
        // history 只收成功 pair：取消不得寫入。
        val field = AtlasViewModel::class.java.getDeclaredField("historyByConnection").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val history = (field.get(viewModel) as Map<String, List<PromptMessage>>)[record.id].orEmpty()
        assertTrue("取消的 pair 不可進入模型上下文", history.isEmpty())
        // 冪等：重複取消不得疊加標記。
        onMain { viewModel.cancel() }
        val again = awaitUi { !it.busy }
        assertEquals(assistantText, again.messages.last { it.role == "assistant" }.text)
    }


    @Test
    fun switchingOnlyGroupClearsChatHistoryAndDoesNotReplayOldPrompt() {
        val bearerRef = storeSecret(BEARER_VALUE)
        val record = commit("群組歷史隔離", listOf(SafeCredentialRef(bearerRef, "推理憑證")))
        awaitConnections(record)
        onMain {
            viewModel.selectConnection(record.id)
            viewModel.selectModelKey(record.accountId, record.credentialId, "scope-a")
        }
        runBlocking { withTimeout(TIMEOUT_MS) { viewModel.selectedGroup.first { it == "scope-a" } } }

        sendAndAwait(record, Outcome.SUCCESS)
        assertEquals("scope-a", targets.single().group)
        assertEquals(listOf("測試提示"), promptBatches.single().map { it.content })
        assertTrue(viewModel.state.value.messages.isNotEmpty())

        // Same ConnectionRecord, same key, DIFFERENT group: this is a new chat scope.
        onMain { viewModel.selectModelKey(record.accountId, record.credentialId, "scope-b") }
        runBlocking { withTimeout(TIMEOUT_MS) { viewModel.selectedGroup.first { it == "scope-b" } } }
        assertTrue("換 group 不得繼續顯示上一群組聊天", viewModel.state.value.messages.isEmpty())
        val historyField = AtlasViewModel::class.java.getDeclaredField("historyByConnection").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val history = historyField.get(viewModel) as Map<String, List<PromptMessage>>
        assertTrue("換 group 必須清空舊 group 的 prompt context", history[record.id].orEmpty().isEmpty())

        onMain { viewModel.send("新的群組提示") }
        awaitUi { !it.busy && it.messages.lastOrNull()?.text == "ok" }
        assertEquals(2, targets.size)
        assertEquals("scope-b", targets.last().group)
        assertEquals(
            "上一個 group 的 prompt / assistant 不可夾帶到新的請求",
            listOf("新的群組提示"),
            promptBatches.last().map { it.content },
        )
        assertNoSecretOutsideRequest()
    }

    @Test
    fun switchingGroupWhileStreamingCancelsOldAnswerWithoutReappearing() {
        val bearerRef = storeSecret(BEARER_VALUE)
        val record = commit("群組切換取消", listOf(SafeCredentialRef(bearerRef, "推理憑證")))
        awaitConnections(record)
        val hangingTransport = object : InferenceTransport {
            override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
                emit(StreamEvent.Delta("scope-a-partial", false))
                awaitCancellation()
            }
        }
        inject("engine", InferenceEngine(hangingTransport, repository))
        onMain {
            viewModel.selectConnection(record.id)
            viewModel.selectModelKey(record.accountId, record.credentialId, "scope-a")
            viewModel.send("來自 scope-a")
        }
        awaitUi { it.messages.any { m -> m.role == "assistant" && m.text.contains("scope-a-partial") } }
        onMain { viewModel.selectModelKey(record.accountId, record.credentialId, "scope-b") }
        val settled = awaitUi { !it.busy && it.messages.isEmpty() }
        assertTrue("已取消的上一群組回應不得復活", settled.messages.isEmpty())
        assertEquals("scope-b", viewModel.selectedGroup.value)
        val sample = runBlocking { withTimeout(TIMEOUT_MS) {
            repository.samples.first { rows ->
                rows.any { it.providerId == record.providerId && it.outcome == Outcome.CANCELLED && it.completedAt != null }
            }.first { it.providerId == record.providerId }
        } }
        assertEquals(Outcome.CANCELLED, sample.outcome)
        assertEquals("scope-a", sample.group)
        assertNoSecretOutsideRequest()
    }

    private fun customConnection(): CustomFixture {
        val bearer = storeSecret(BEARER_VALUE)
        val header = storeSecret(HEADER_VALUE)
        val query = storeSecret(QUERY_VALUE)
        val pat = storeSecret(PAT_VALUE)
        val record = commit(
            "自訂授權連線",
            listOf(SafeCredentialRef(bearer, "推理憑證"), SafeCredentialRef(pat, "帳號 PAT", CredentialRole.ACCOUNT)),
            listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Fixture-Key", header),
                CustomAuthFieldRef(CredentialLocation.QUERY, "fixture_key", query),
            ),
        )
        awaitConnections(record)
        return CustomFixture(record, bearer, header, query, pat)
    }

    private fun assertCustomRequest(fixture: CustomFixture) {
        val target = targets.single()
        assertSecretFree(target.toString())
        assertEquals(fixture.bearerRef, target.credentialId)
        assertEquals(
            listOf(InferenceAuthField("X-Fixture-Key", fixture.headerRef), InferenceAuthField("fixture_key", fixture.queryRef, query = true)),
            target.authFields,
        )
        assertEquals("transport 收到 target 時尚未解析任何值", listOf(0), resolutionsAtTarget.toList())
        assertEquals(listOf(fixture.bearerRef, fixture.headerRef, fixture.queryRef), resolutions.map { it.first })
        assertTrue(resolutions.all { it.second == ORIGIN })
        assertFalse(resolutions.any { it.first == fixture.patRef })
        val request = requests.single()
        // 帶值的比較用布林斷言，失敗訊息不列印任何 secret 或完整 Request。
        assertTrue("bearer 授權應解析自指定 ref", request.header("Authorization") == "Bearer $BEARER_VALUE")
        assertTrue("header 應解析自指定 ref", request.header("X-Fixture-Key") == HEADER_VALUE)
        assertTrue("query 應解析自指定 ref", request.url.queryParameter("fixture_key") == QUERY_VALUE)
        assertNull(request.header("X-Account-Pat"))
        assertEquals(setOf("fixture_key"), request.url.queryParameterNames)
        val payload = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertSecretFree(payload)
        assertFalse("prompt payload 不得附帶 refs", ownedRefs.any { it in payload })
    }

    // ===== P0-4 fail-closed：API_KEY custom-only 可用，缺 ref / load 失敗拒發 =====

    @Test
    fun apiKeyCustomOnly_sendsWithoutBearerAndWithSingleCustomHeader() {
        val headerRef = storeSecret(HEADER_VALUE)
        val record = commit(
            "API_KEY自訂連線",
            credentials = emptyList(),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Api-Key", headerRef),
            ),
            authModeOverride = AuthMode.API_KEY,
        )
        awaitConnections(record)
        val sample = sendAndAwait(record, Outcome.SUCCESS)
        // P0-3 可用 Connection：management / active 皆有列，credentialId 為 custom anchor。
        assertEquals(headerRef, record.credentialId)
        // P0-4：custom-only 不送 Bearer，只送單一 custom header；vault 值只在 HTTP 邊界解析。
        val target = targets.single()
        assertSecretFree(target.toString())
        assertNull("API_KEY custom-only 不可以 Bearer 送出 custom 值", target.credentialId)
        assertEquals(
            listOf(InferenceAuthField("X-Api-Key", headerRef)),
            target.authFields,
        )
        val request = requests.single()
        assertNull(request.header("Authorization"))
        assertTrue("custom header 應解析自指定 ref", request.header("X-Api-Key") == HEADER_VALUE)
        assertTrue("API_KEY 單欄不得附帶 query 參數", request.url.queryParameterNames.isEmpty())
        assertNoSecretOutsideRequest()
        // MODEL-008 / PLAY-002：custom-only 不帶 Bearer，但 selected safe ref 必須仍能
        // 在本機 telemetry 精確歸屬這把 key，不可把「未送 Bearer」誤寫成匿名樣本。
        assertEquals(headerRef, target.telemetryCredentialId)
        assertEquals(headerRef, sample.safeCredentialId)
    }

    @Test
    fun customOnly_missingVaultSecret_refusesWithoutNewRequest() {
        val headerRef = storeSecret(HEADER_VALUE)
        val record = commit(
            "缺憑證自訂連線",
            credentials = emptyList(),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Api-Key", headerRef),
            ),
            authModeOverride = AuthMode.API_KEY,
        )
        awaitConnections(record)
        // 取回空：vault 刪 key 後，transport 在 HTTP 邊界前拋出，不得發出新 HTTP 請求。
        runBlocking { vault.remove(headerRef) }
        ownedRefs.remove(headerRef)
        val requestsBefore = requests.size
        val samplesBefore = runBlocking { repository.samples.first().size }
        onMain {
            viewModel.selectConnection(record.id)
            viewModel.send("測試提示")
        }
        val state = awaitUi { !it.busy && it.error != null }
        assertNotNull(state.error)
        assertEquals(requestsBefore, requests.size)
        // 缺 ref 不得新增成功 telemetry；失敗 telemetry 允許（engine 內 fail-closed），但不得有成功樣本。
        val samplesAfter = runBlocking { repository.samples.first() }
        assertTrue(samplesAfter.none { it.providerId == record.providerId && it.outcome == Outcome.SUCCESS })
        assertTrue(samplesAfter.size >= samplesBefore)
        assertNoSecretOutsideRequest()
    }

    @Test
    fun corruptProviderConfig_loadFails_refusesSendWithoutNewRequest() {
        val headerRef = storeSecret(HEADER_VALUE)
        val record = commit(
            "損毀設定連線",
            credentials = emptyList(),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Api-Key", headerRef),
            ),
            authModeOverride = AuthMode.API_KEY,
        )
        awaitConnections(record)
        // load 失敗：破壞 provider_config snapshotJson，使 toManualConfig 拋出，send 必須 fail-closed。
        runBlocking {
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE provider_configurations SET snapshotJson = ? WHERE providerId = ?",
                    arrayOf<Any?>("corrupted-json{{", record.providerId),
                )
            }
        }
        val requestsBefore = requests.size
        onMain {
            viewModel.selectConnection(record.id)
            viewModel.send("測試提示")
        }
        val state = awaitUi { !it.busy && it.error != null }
        assertNotNull(state.error)
        assertEquals(requestsBefore, requests.size)
        assertNoSecretOutsideRequest()
    }

    @Test
    fun commitCustomFieldOverlappingPatRefIsRejectedWithoutPartialRows() {
        val pat = storeSecret(PAT_VALUE)
        val header = storeSecret(HEADER_VALUE)
        val snapshot = OnboardingSnapshot(
            workflowId = UUID.randomUUID().toString(),
            flowType = OnboardingFlowType.ADVANCED,
            providerName = "重疊綁定連線",
            baseUrl = BASE_URL,
            credentials = listOf(SafeCredentialRef(pat, "帳號 PAT", CredentialRole.ACCOUNT)),
            safeCredentialIds = listOf(pat),
            customAuthFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Account-Pat", pat),
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Fixture-Key", header),
            ),
            authModeOverride = AuthMode.CUSTOM_HEADER,
            manualModelIds = listOf("fixture-model"),
            reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "測試確認"),
            createdAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_000_001L,
        )
        val failure = runCatching { runBlocking { RoomCommitter(database).commit(snapshot) } }.exceptionOrNull()
        assertTrue(
            "PAT 重疊 custom field 必須在 commit 拒絕",
            failure is IllegalArgumentException && "Credential role conflict" in (failure.message ?: ""),
        )
        assertNoSecretOutsideRequest()
    }

    @Test
    fun selectedAccountPatRoleCannotBeSentAsBearerEvenIfProviderHasOtherInferenceRef() {
        val selectedRef = storeSecret(BEARER_VALUE)
        val otherRef = storeSecret(PAT_VALUE)
        val record = commit(
            "選定憑證角色變動",
            listOf(
                SafeCredentialRef(selectedRef, "推理憑證"),
                SafeCredentialRef(otherRef, "帳號 PAT", CredentialRole.ACCOUNT),
            ),
        )
        awaitConnections(record)
        assertEquals(selectedRef, record.credentialId)

        // 模擬舊／外部管理資料在 ViewModel 快取後改了 role：provider 層仍有
        // inference key，但選定的 ref 已成為 ACCOUNT PAT。不得錯借 role 送 Bearer。
        val dao = database.providerConfigDao()
        val original = runBlocking { dao.getByProvider(record.providerId) }!!
        val modified = decodeProviderManualConfig(original.snapshotJson).copy(
            credentials = listOf(
                SafeCredentialRef(selectedRef, "帳號 PAT", CredentialRole.ACCOUNT),
                SafeCredentialRef(otherRef, "推理憑證", CredentialRole.INFERENCE),
            ),
        )
        val snapshot = modified.encodeToSnapshotJson()
        ensureSnapshotJsonHasNoSecretRefs(snapshot)
        runBlocking { dao.updateSnapshotJson(record.providerId, snapshot) }

        onMain {
            viewModel.selectConnection(record.id)
            viewModel.send("測試提示")
        }
        val state = awaitUi { !it.busy && it.error != null }
        assertNotNull(state.error)
        assertTrue("不得將所選 PAT 當 Bearer 送到網路", requests.isEmpty())
        assertTrue("應在進 transport 前拒絕", targets.isEmpty())
        assertTrue("不得解析任何 PAT/Key 明文", resolutions.isEmpty())
        assertNoSecretOutsideRequest()
    }

    @Test
    fun legacyOverlappingConfigPatFieldIsFilteredAtSendTime() {
        // Legacy 髒資料（guard 之前寫入的重疊 config）：直接覆寫 config JSON 模擬，
        // send 端過濾必須擋下 PAT header，只送 header＋query。
        val fixture = customConnection()
        val dao = database.providerConfigDao()
        val entity = runBlocking { dao.getByProvider(fixture.record.providerId) }!!
        val dirty = decodeProviderManualConfig(entity.snapshotJson).copy(
            customAuthFields = decodeProviderManualConfig(entity.snapshotJson).customAuthFields +
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Account-Pat", fixture.patRef),
        )
        ensureSnapshotJsonHasNoSecretRefs(dirty.encodeToSnapshotJson())
        runBlocking { dao.updateSnapshotJson(fixture.record.providerId, dirty.encodeToSnapshotJson()) }
        responseText = "$HEADER_VALUE $QUERY_VALUE"
        val sample = sendAndAwait(fixture.record, Outcome.SUCCESS)
        assertCustomRequest(fixture)
        assertNoSecretOutsideRequest()
        assertEquals(Outcome.SUCCESS, sample.outcome)
    }

    private fun commit(
        name: String,
        credentials: List<SafeCredentialRef> = emptyList(),
        customFields: List<CustomAuthFieldRef> = emptyList(),
        authModeOverride: AuthMode? = null,
    ): ConnectionRecord = runBlocking {
        val snapshot = OnboardingSnapshot(
            workflowId = UUID.randomUUID().toString(),
            flowType = OnboardingFlowType.ADVANCED,
            providerName = name,
            baseUrl = BASE_URL,
            credentials = credentials,
            safeCredentialIds = credentials.map { it.safeCredentialId },
            customAuthFields = customFields,
            authModeOverride = authModeOverride ?: when {
                customFields.isNotEmpty() -> AuthMode.CUSTOM_HEADER
                credentials.isEmpty() -> AuthMode.NONE
                else -> AuthMode.BEARER
            },
            anonymousConfirmed = credentials.isEmpty(),
            manualModelIds = listOf("fixture-model"),
            reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "測試確認"),
            createdAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_000_001L,
        )
        val receipt = RoomCommitter(database).commit(snapshot)
        withTimeout(TIMEOUT_MS) {
            repository.connections.first { rows -> rows.any { it.providerId == receipt.providerId } }
                .single { it.providerId == receipt.providerId }
        }
    }

    private fun sendAndAwait(record: ConnectionRecord, outcome: Outcome): PerformanceSample {
        awaitConnections(record)
        onMain {
            viewModel.selectConnection(record.id)
            viewModel.send("測試提示")
        }
        val sample = runBlocking {
            withTimeout(TIMEOUT_MS) {
                repository.samples.first { rows ->
                    rows.any { it.providerId == record.providerId && it.completedAt != null }
                }.single { it.providerId == record.providerId }
            }
        }
        assertEquals(outcome, sample.outcome)
        val state = awaitUi { !it.busy && it.samples.any { item -> item.id == sample.id && item.outcome == outcome.name } }
        assertSecretFree(state.toString())
        if (outcome == Outcome.SUCCESS) assertNull(state.error)
        assertEquals(record.providerId, sample.providerId)
        assertEquals(record.accountId, sample.accountId)
        assertEquals(record.endpointId, sample.endpointId)
        return sample
    }

    private fun awaitConnections(vararg records: ConnectionRecord) {
        awaitUi { state -> records.all { record ->
            state.connections.any { it.id == record.id && it.enabled } &&
                state.managedConnections.any { it.id == record.id && it.enabled }
        } }
    }

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState = runBlocking {
        withTimeout(TIMEOUT_MS) { viewModel.state.first(predicate) }
    }

    private fun storeSecret(value: String): String = UUID.randomUUID().toString().also {
        ownedRefs += it
        vault.store(it, ORIGIN, value)
    }

    private fun assertNoSecretOutsideRequest() {
        assertSecretFree(targets.toString())
        assertSecretFree(viewModel.state.value.toString())
        assertSecretFree(runBlocking { repository.samples.first() }.toString())
        // 逐欄掃描隔離 DB 的所有使用者資料表，包含原始 config JSON 與 telemetry JSON。
        val db = database.openHelper.readableDatabase
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        for (table in tables) {
            val quoted = table.replace("\"", "\"\"")
            db.query("SELECT * FROM \"$quoted\"").use { cursor ->
                while (cursor.moveToNext()) {
                    repeat(cursor.columnCount) { column ->
                        if (cursor.getType(column) == android.database.Cursor.FIELD_TYPE_STRING) {
                            assertSecretFree(cursor.getString(column))
                        }
                    }
                }
            }
        }
    }

    private fun assertSecretFree(text: String) {
        assertFalse("安全邊界之外不可包含測試憑證值", listOf(BEARER_VALUE, HEADER_VALUE, QUERY_VALUE, PAT_VALUE).any { it in text })
    }

    private fun inject(name: String, value: Any) {
        AtlasApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.set(app, value)
    }

    private fun onMain(block: () -> Unit) = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }

    private data class CustomFixture(
        val record: ConnectionRecord,
        val bearerRef: String,
        val headerRef: String,
        val queryRef: String,
        val patRef: String,
    )

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://atlas-target.fixture.invalid/v1"
        const val ORIGIN = "https://atlas-target.fixture.invalid:443"
        const val BEARER_VALUE = "fixture-only-bearer-value-82c7"
        const val HEADER_VALUE = "fixture-only-header-value-45d9"
        const val QUERY_VALUE = "fixture-only-query-value-61e3"
        const val PAT_VALUE = "fixture-only-account-value-97b2"
    }
}
