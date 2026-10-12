package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.ProviderModelActionState
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.data.ProviderManualConfig
import dev.modelatlas.app.data.toEntity
import dev.modelatlas.app.data.encodeToSnapshotJson
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialLocation
import dev.modelatlas.core.onboarding.CustomAuthFieldRef
import dev.modelatlas.core.onboarding.SafeCredentialRef
import dev.modelatlas.core.onboarding.CredentialRole
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROV-001 / MODEL-006：Provider Detail「新增模型 / 立即測試」的真 VM 編排 device 測試。
 *
 * TRACEABILITY：PROV-001（`PRODUCT_SPEC` §4 Detail Models、§6 selected Credential／Group）、
 * MODEL-006（§8 Manual Model ID hidden/manual offering、§10 provenance）。
 *
 * 本檔只驗**編排與競態**，全部經真 VM port 與真 Room 落庫觀察，不做 Compose 渲染斷言
 * （Provider Detail 的渲染層覆蓋由 UI worker 的 device test 負責）：
 * - 精確 sample 關聯：只有「本次 probe 的 RequestSent.sampleId 對應到 final SUCCESS
 *   ＋ MANUAL_TEST ＋完整 scope」才可 promote；比它更新的 decoy 成功樣本不得被拿來 promote。
 * - single-flight：進行中的動作不被第二個 tap 蓋掉、不重複發請求，且不與 Playground send 並行。
 * - 取消／離開頁面／換 key／換 group：一律收斂為 CANCELLED、遙測落 CANCELLED、候選維持 hidden，
 *   不得留下孤兒請求或過期 SUCCESS。
 * - ADD 完全不碰網路、不產生 inference 遙測，且只有 ADD 成功才清 draft。
 * - fail-closed：外來 provider、停用 key 一律不發請求、不寫 offering、不寫 telemetry。
 *
 * 真實度：真 `AtlasApplication`（真 Room ＋真 Keystore vault）＋真 `AtlasViewModel`；
 * 只以 `providerModelTestEngine` 注入 `InferenceEngine(fake InferenceTransport, app.repository)`，
 * 因此遙測仍經真 `AtlasRepository` 落庫，而網路層完全不碰（無 HTTP、無 quota）。
 * fixture（測試憑證值、provider／連線、decoy sample）只存在本 test sourceSet，
 * teardown 只刪本檔自己的 rows 與 vault refs。
 */
@RunWith(AndroidJUnit4::class)
class ProviderModelActionViewModelTest {

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore
    private val transport = FakeProbeTransport()

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        transport.reset()
        viewModelStore = ViewModelStore()
        onMain {
            viewModel = ViewModelProvider(viewModelStore, AtlasViewModelFactory(app, transport))
                .get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        // 收斂仍在途的動作（final telemetry 以 NonCancellable 落庫）後再清資料，避免留下孤兒列。
        if (::viewModel.isInitialized) {
            runCatching {
                onMain { viewModel.cancelProviderModelAction() }
                if (viewModel.providerModelAction.value.busy) awaitAction("CANCELLED")
            }
        }
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ---- 1. 精確 sample promote（MANUAL_TEST scope ＋ 本次 sampleId，decoy 不可冒充）----

    @Test
    fun verifiedProbePromotesOnlyFromItsOwnExactSample() {
        val fixture = givenConnection("exact")
        selectAndAwait(fixture)
        val record = recordOf(fixture.connectionId)
        val decoyId = seedNewerDecoySuccess(record, MODEL_EXACT)
        val knownSampleIds = sampleIds(fixture.providerId)
        assertTrue("decoy 必須在 probe 之前就存在（否則本測試證明力不足）", decoyId in knownSampleIds)
        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.COMPLETE

        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_EXACT) }
        val done = awaitAction("SUCCESS")
        assertEquals("TEST", done.kind)
        assertEquals(MODEL_EXACT, done.modelId)
        assertEquals(fixture.connectionId, done.connectionId)

        // 只有本次 probe 的 sampleId 可以支撐 promote。
        assertEquals("單次 minimal probe：不得重試或重複發請求", 1, transport.callCount)
        val probeIds = sampleIds(fixture.providerId) - knownSampleIds
        assertEquals(1, probeIds.size)
        val probeId = probeIds.single()
        val offering = requireOffering(fixture.providerId, MODEL_EXACT)
        assertEquals("0", offering["hidden"])
        val verification = offering["verificationEvidenceId"]
        assertNotNull("已驗證 offering 必須帶驗證 evidence", verification)
        assertFalse(verification.isNullOrBlank())
        assertTrue(
            "promote 必須引用本次 sample（evidence=${verification.orEmpty()} probe=$probeId）",
            verification.orEmpty().contains(probeId),
        )
        assertFalse(
            "不得以較新的其他 sample 冒充本次驗證（decoy=$decoyId）",
            verification.orEmpty().contains(decoyId),
        )

        // 真 durable 遙測：MANUAL_TEST source ＋完整 scope，未知價格保持 UNKNOWN。
        val sample = sampleOf(probeId)!!
        assertTrue(
            "decoy 必須真的比本次 probe 新（否則 latest 類猜測不會暴露）",
            sampleOf(decoyId)!!["startedAt"].orEmpty().toLong() > sample["startedAt"].orEmpty().toLong(),
        )
        assertEquals(TelemetrySource.MANUAL_TEST.name, sample["source"])
        assertEquals(Outcome.SUCCESS.name, sample["outcome"])
        assertEquals(record.accountId, sample["accountId"])
        assertEquals(record.credentialId, sample["safeCredentialId"])
        assertEquals(record.endpointId, sample["endpointId"])
        assertEquals(MODEL_EXACT, sample["modelId"])
        assertEquals("UNKNOWN", sample["priceState"])
        assertEquals("UNKNOWN", sample["quotaState"])
        assertFalse("真 Delta 必須被計成 TTFT", sample["ttftMs"].isNullOrBlank())
        assertFalse(sample["completedAt"].isNullOrBlank())

        // 只用「已選中那條連線」的 safe refs；未驗證 manual model 不得捏造 canonical 對應。
        val target = transport.lastTarget()
        assertEquals(record.providerId, target.providerId)
        assertEquals(record.accountId, target.accountId)
        assertEquals(record.credentialId, target.credentialId)
        assertEquals(record.endpointId, target.endpointId)
        assertEquals(record.endpointUrl, target.endpointUrl)
        assertEquals(record.protocol, target.operation)
        assertEquals(MODEL_EXACT, target.modelId)
        assertNull("未驗證 manual model 不得自稱有 canonical 對應", target.canonicalModelId)
        assertNoSecretInOwnRows(fixture.providerId)
        assertNoSecret(transport.promptText())
        assertNoSecret(done.toString())
    }

    // ---- 2. 首次 probe 失敗：候選保持 hidden，decoy 成功樣本不得 promote ----

    @Test
    fun failedFirstProbeLeavesCandidateHiddenAndDecoyCannotPromote() {
        val fixture = givenConnection("decoy")
        selectAndAwait(fixture)
        val record = recordOf(fixture.connectionId)
        val decoyId = seedNewerDecoySuccess(record, MODEL_DECOY)
        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.FAIL

        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_DECOY) }
        val done = awaitAction("FAILURE")
        assertEquals("TEST", done.kind)
        assertFalse(done.message.isNullOrBlank())

        val offering = requireOffering(fixture.providerId, MODEL_DECOY)
        assertEquals("1", offering["hidden"])
        assertTrue(
            "失敗的本次 probe 不得留下任何驗證 evidence（decoy=$decoyId）",
            offering["verificationEvidenceId"].isNullOrBlank(),
        )
        // decoy 是 last-good 事實：不得因本次失敗被清掉。
        assertNotNull("既有觀測不得因一次失敗被刪", sampleOf(decoyId))
        val probeId = sampleIds(fixture.providerId).minus(decoyId).single()
        assertEquals(Outcome.SERVER.name, sampleOf(probeId)!!["outcome"])
        assertEquals(1, transport.callCount)
        assertNoSecretInOwnRows(fixture.providerId)
        assertNoSecret(done.toString())
    }

    // ---- 3. 重新驗證失敗不得改寫既有 verified evidence（last-good ＋ 精確引用）----

    @Test
    fun failedRetestKeepsEarlierVerificationAndDoesNotRepointIt() {
        val fixture = givenConnection("retest")
        selectAndAwait(fixture)
        val record = recordOf(fixture.connectionId)
        val decoyId = seedNewerDecoySuccess(record, MODEL_RETEST)
        val knownSampleIds = sampleIds(fixture.providerId)
        assertTrue("decoy 必須在 probe 之前就存在（否則本測試證明力不足）", decoyId in knownSampleIds)
        val decoyStartedAt = sampleOf(decoyId)!!["startedAt"].orEmpty().toLong()

        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.COMPLETE
        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_RETEST) }
        awaitAction("SUCCESS")
        val firstProbeId = (sampleIds(fixture.providerId) - knownSampleIds).single()
        assertTrue(
            "首次 probe 的 sample 必須比 decoy 舊（否則 latest 類猜測不會暴露）",
            sampleOf(firstProbeId)!!["startedAt"].orEmpty().toLong() < decoyStartedAt,
        )
        val verifiedRef = requireOffering(fixture.providerId, MODEL_RETEST)["verificationEvidenceId"]
        assertNotNull("首次驗證必須留下 evidence 引用", verifiedRef)
        assertTrue(
            "首次驗證必須引用本次 sample（evidence=${verifiedRef.orEmpty()} probe=$firstProbeId）",
            verifiedRef.orEmpty().contains(firstProbeId),
        )

        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.FAIL
        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_RETEST) }
        val done = awaitAction("FAILURE")
        assertNotEquals("SUCCESS", done.status)
        val secondProbeId = (sampleIds(fixture.providerId) - knownSampleIds - firstProbeId).single()
        assertNotEquals("兩次 probe 必須各有自己的 sample id", firstProbeId, secondProbeId)
        assertEquals(Outcome.SERVER.name, sampleOf(secondProbeId)!!["outcome"])

        val offering = requireOffering(fixture.providerId, MODEL_RETEST)
        assertEquals("一次失敗不得把已驗證 offering 收回 hidden", "0", offering["hidden"])
        assertEquals(
            "既有驗證 evidence 不得被改指向本次失敗或其他 sample",
            verifiedRef,
            offering["verificationEvidenceId"],
        )
        assertFalse(offering["verificationEvidenceId"].orEmpty().contains(decoyId))
        assertFalse(
            "失敗的第二次 probe 不得成為驗證 evidence 來源",
            offering["verificationEvidenceId"].orEmpty().contains(secondProbeId),
        )
        assertEquals("每次 probe 都是獨立 sample", 2, transport.callCount)
        val outcomes = sampleIds(fixture.providerId).minus(decoyId).map { sampleOf(it)!!["outcome"] }
        assertEquals(listOf(Outcome.SUCCESS.name, Outcome.SERVER.name), outcomes)
        assertNoSecretInOwnRows(fixture.providerId)
        assertNoSecret(done.toString())
    }

    // ---- 4. 外來 provider：拒絕、不 fallback 到別的 key、不發任何請求 ----

    @Test
    fun foreignProviderIsRejectedWithoutRequestOrKeyFallback() {
        val selected = givenConnection("owner")
        val foreign = givenConnection("foreign")
        selectAndAwait(selected)
        transport.mode = FakeProbeTransport.Mode.GATE

        onMain { viewModel.testProviderModel(foreign.providerId, MODEL_FOREIGN) }
        val done = awaitAction("FAILURE")
        assertEquals("外來 provider 不得發出任何請求", 0, transport.callCount)
        assertNull("外來 provider 不得建立候選 offering", offeringOf(foreign.providerId, MODEL_FOREIGN))
        assertTrue("外來 provider 不得寫入任何 telemetry", sampleRows(foreign.providerId).isEmpty())
        assertTrue("已選中的 provider 也不得因而被送 probe", sampleRows(selected.providerId).isEmpty())
        assertFalse(done.message.isNullOrBlank())
        assertNoSecret(done.toString())
    }

    // ---- 5. single-flight：第二個 tap 被忽略，且不與 Playground send 並行 ----

    @Test
    fun secondActionIsIgnoredWhileProbeInFlightAndBlocksChatSend() {
        val fixture = givenConnection("singleflight")
        selectAndAwait(fixture)
        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.GATE

        onMain {
            viewModel.testProviderModel(fixture.providerId, MODEL_FIRST_TAP)
            viewModel.testProviderModel(fixture.providerId, MODEL_SECOND_TAP)
        }
        awaitProbeStarted(0, fixture, MODEL_FIRST_TAP)
        val running = viewModel.providerModelAction.value
        assertEquals("RUNNING", running.status)
        assertEquals(MODEL_FIRST_TAP, running.modelId)
        assertEquals("同一次動作期間只允許一個 probe", 1, transport.callCount)

        val row = await {
            viewModel.allModelRows.first { rows -> rows.any { it.providerId == fixture.providerId } }
                .first { it.providerId == fixture.providerId }
        }
        // Reject before building any request (including an incomplete row), without reaching HTTP.
        onMain { viewModel.requestCompareLive(row.copy(canonicalId = "")) }
        assertFalse(viewModel.compareRunning.value)
        assertEquals("BUSY", viewModel.compareError.value)
        assertEquals(1, sampleRows(fixture.providerId).size)

        onMain { viewModel.send(MANUAL_TEST_PROMPT_MUST_NOT_BE_SENT) }
        val chatState = viewModel.state.value
        assertFalse("probe 進行中時 Playground 必須拒絕發送", chatState.busy)
        assertNotNull("probe 進行中時 send 必須回填安全錯誤", chatState.error)
        assertTrue(chatState.messages.isEmpty())
        assertEquals("send 不得偷發第二個請求", 1, transport.callCount)

        onMain { viewModel.cancelProviderModelAction() }
        assertEquals("CANCELLED", awaitAction("CANCELLED").status)
        val samples = sampleRows(fixture.providerId)
        assertEquals("被忽略的第二個動作不得產生任何 telemetry", 1, samples.size)
        assertEquals(MODEL_FIRST_TAP, samples.single()["modelId"])
        assertEquals(1, transport.callCount)
    }

    // ---- 6. 取消：durable start 先落庫，最終 CANCELLED，候選不 promote ----

    @Test
    fun cancellationFinalizesDurableTelemetryAsCancelledWithoutPromotion() {
        val fixture = givenConnection("cancel")
        selectAndAwait(fixture)
        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.GATE

        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_CANCEL) }
        awaitProbeStarted(0, fixture, MODEL_CANCEL)
        val durable = sampleRows(fixture.providerId).single()
        assertEquals("串流前必須先落 durable RUNNING", Outcome.RUNNING.name, durable["outcome"])
        assertTrue("durable start 不得預先補 completedAt", durable["completedAt"].isNullOrBlank())

        onMain { viewModel.cancelProviderModelAction() }
        val done = awaitAction("CANCELLED")
        assertEquals(MODEL_CANCEL, done.modelId)
        assertFalse(done.busy)
        assertEquals("取消不得推進 draft clear token", 0, viewModel.providerModelAction.value.clearToken)
        assertEquals(
            "取消只更新同一筆 durable sample",
            durable["id"],
            sampleRows(fixture.providerId).single()["id"],
        )
        val final = sampleOf(durable["id"].orEmpty())!!
        assertEquals(Outcome.CANCELLED.name, final["outcome"])
        assertFalse("取消仍要補 completedAt（不留 RUNNING）", final["completedAt"].isNullOrBlank())
        val offering = requireOffering(fixture.providerId, MODEL_CANCEL)
        assertEquals("1", offering["hidden"])
        assertTrue(offering["verificationEvidenceId"].isNullOrBlank())
        assertEquals(1, transport.callCount)
    }

    // ---- 7. 換 key：取消在途 probe，不留過期 SUCCESS、不用別的 key 補發 ----

    @Test
    fun switchingToAnotherKeyCancelsProbeWithoutStaleSuccess() {
        val first = givenConnection("switch-a")
        val second = givenConnection("switch-b")
        selectAndAwait(first)
        awaitRowsSettled(first.providerId)
        transport.mode = FakeProbeTransport.Mode.GATE

        onMain { viewModel.testProviderModel(first.providerId, MODEL_SWITCH) }
        awaitProbeStarted(0, first, MODEL_SWITCH)
        selectAndAwait(second)
        val done = awaitAction("CANCELLED")
        assertNotEquals("SUCCESS", done.status)

        assertEquals(MODEL_SWITCH, done.modelId)
        assertEquals(Outcome.CANCELLED.name, sampleRows(first.providerId).single()["outcome"])
        assertEquals("1", requireOffering(first.providerId, MODEL_SWITCH)["hidden"])
        assertTrue("換 key 後不得改用另一把 key 補發", sampleRows(second.providerId).isEmpty())
        assertEquals("不得因換 key 而多發請求", 1, transport.callCount)
    }

    // ---- 8. 換 group：同 key 內切 group 也要取消，且 probe 用的是當下明確的 group ----

    @Test
    fun switchingGroupMidProbeCancelsWithoutStaleSuccess() {
        val fixture = givenConnection("group")
        selectAndAwait(fixture)
        awaitRowsSettled(fixture.providerId)
        onMain { viewModel.selectModelKey(fixture.accountId, fixture.credentialId, GROUP_ONE) }
        awaitGroup(GROUP_ONE)
        transport.mode = FakeProbeTransport.Mode.GATE

        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_GROUP) }
        awaitProbeStarted(0, fixture, MODEL_GROUP)
        assertEquals("probe 必須使用當下明確選定的 group", GROUP_ONE, transport.lastTarget().group)
        assertEquals(GROUP_ONE, sampleRows(fixture.providerId).single()["group"])

        onMain { viewModel.selectModelKey(fixture.accountId, fixture.credentialId, GROUP_TWO) }
        awaitGroup(GROUP_TWO)
        val done = awaitAction("CANCELLED")
        assertNotEquals("SUCCESS", done.status)
        assertEquals(Outcome.CANCELLED.name, sampleRows(fixture.providerId).single()["outcome"])
        assertEquals("1", requireOffering(fixture.providerId, MODEL_GROUP)["hidden"])
        assertEquals("換 group 不得補發請求", 1, transport.callCount)
    }

    // ---- 9. ADD 不碰網路、不產生遙測，只有成功才清 draft ----

    @Test
    fun addModelNeverTouchesNetworkAndClearsDraftOnlyOnSuccess() {
        val fixture = givenConnection("add")
        selectAndAwait(fixture)
        // 與 custom-only 同因：fixture 自身寫入的 re-emission 級聯必須先收斂，
        // 否則 ADD 的 IO 視窗可能和 cascade 重疊，在 device 上間歇 CANCELLED。
        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.GATE
        val before = viewModel.providerModelAction.value

        onMain { viewModel.addProviderModel(fixture.providerId, BLANK_DRAFT) }
        val rejected = awaitAction("FAILURE")
        assertEquals("ADD", rejected.kind)
        assertEquals("失敗不得清 draft", before.clearToken, rejected.clearToken)
        assertEquals(before.refreshToken, rejected.refreshToken)
        assertEquals(0, transport.callCount)
        assertNull(offeringOf(fixture.providerId, BLANK_DRAFT))

        onMain { viewModel.addProviderModel(fixture.providerId, MODEL_ADD) }
        val added = awaitAction("SUCCESS")
        assertEquals("ADD", added.kind)
        assertEquals("ADD 成功才遞增 clearToken 一次", before.clearToken + 1, added.clearToken)
        assertTrue("成功後必須要求 UI 重算 rows", added.refreshToken > rejected.refreshToken)
        assertEquals("新增模型完全不需要網路", 0, transport.callCount)
        assertTrue("ADD 不得偽造 inference 遙測", sampleRows(fixture.providerId).isEmpty())
        val offering = requireOffering(fixture.providerId, MODEL_ADD)
        assertEquals("1", offering["manual"])
        assertTrue(
            "manual source 只接受 MANUAL/USER（實際 ${offering["source"]}）",
            offering["source"] in MANUAL_SOURCES,
        )
        assertEquals("未驗證的 manual offering 必須 hidden", "1", offering["hidden"])
        assertTrue(offering["verificationEvidenceId"].isNullOrBlank())
        assertNoSecretInOwnRows(fixture.providerId)

        // 立即測試成功也不清 draft：只有 ADD 成功才清。
        transport.mode = FakeProbeTransport.Mode.COMPLETE
        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_ADD_TESTED) }
        val tested = awaitAction("SUCCESS")
        assertEquals(added.clearToken, tested.clearToken)
    }

    // ---- 10. fail-closed：停用 key 後不發請求、不建 offering、不寫遙測 ----

    @Test
    fun disabledKeyFailsClosedWithoutAnyRequest() {
        val fixture = givenConnection("disabled")
        selectAndAwait(fixture)
        onMain { viewModel.setCredentialEnabled(fixture.connectionId, false) }
        awaitUi { state -> !state.saving && state.connections.none { it.id == fixture.connectionId } }
        transport.mode = FakeProbeTransport.Mode.GATE

        onMain { viewModel.testProviderModel(fixture.providerId, MODEL_DISABLED) }
        val done = awaitAction("FAILURE")
        assertEquals("停用 key 不得發出請求", 0, transport.callCount)
        assertTrue("停用 key 不得寫入 telemetry", sampleRows(fixture.providerId).isEmpty())
        assertNull("停用 key 不得建立候選 offering", offeringOf(fixture.providerId, MODEL_DISABLED))
        assertFalse(done.message.isNullOrBlank())
        assertNoSecret(done.toString())
    }

    // ---- 11. 離開頁面：只取消自己的動作 ----

    @Test
    fun leavingDetailScreenCancelsOnlyItsOwnAction() {
        val mine = givenConnection("leave-mine")
        val other = givenConnection("leave-other")
        val third = givenConnection("leave-third")
        // 先完成所有 fixture writes，再用沒有 pendingById 的 VM 觀察真 Room 投影。
        // 舊 VM 此時尚未發出任何 probe；transport 不重設，兩次 probe 槽位保持不變。
        onMain {
            viewModelStore.clear()
            viewModelStore = ViewModelStore()
            viewModel = ViewModelProvider(viewModelStore, AtlasViewModelFactory(app, transport))
                .get(AtlasViewModel::class.java)
        }
        val connectionIds = setOf(mine.connectionId, other.connectionId, third.connectionId)
        awaitUi { state -> state.connections.map { it.id }.containsAll(connectionIds) }
        selectAndAwait(mine)
        transport.mode = FakeProbeTransport.Mode.GATE

        onMain { viewModel.testProviderModel(mine.providerId, MODEL_LEAVE) }
        awaitProbeStarted(0, mine, MODEL_LEAVE)
        onMain { viewModel.leaveProviderDetail(other.providerId) }
        transport.release(0)
        assertEquals(
            "離開其他 provider 的頁面不得取消本次 probe",
            "SUCCESS",
            awaitAction("SUCCESS").status,
        )
        assertEquals(
            Outcome.SUCCESS.name,
            sampleOf(sampleIds(mine.providerId).single())!!["outcome"],
        )

        selectAndAwait(third)
        transport.mode = FakeProbeTransport.Mode.GATE
        onMain { viewModel.testProviderModel(third.providerId, MODEL_LEAVE_OWN) }
        awaitProbeStarted(1, third, MODEL_LEAVE_OWN)
        onMain { viewModel.leaveProviderDetail(third.providerId) }
        val cancelled = awaitAction("CANCELLED")
        assertEquals(MODEL_LEAVE_OWN, cancelled.modelId)
        assertEquals(
            Outcome.CANCELLED.name,
            sampleOf(sampleIds(third.providerId).single())!!["outcome"],
        )
        assertEquals("1", requireOffering(third.providerId, MODEL_LEAVE_OWN)["hidden"])
    }

    @Test
    fun anonymousNoneProbeUsesNoCredentialAndStillPersistsManualTestTelemetry() {
        val fixture = givenConnection("anonymous")
        runBlocking {
            app.database.providerConfigDao().insert(ProviderManualConfig(
                providerId = fixture.providerId, workflowId = UUID.randomUUID().toString(),
                authMode = AuthMode.NONE, anonymousConfirmed = true,
            ).toEntity())
            app.repository.deleteCredentialMetadata(fixture.credentialId)
        }
        val anonymous = runBlocking {
            withTimeout(TIMEOUT_MS) {
                app.repository.connections.first { rows -> rows.any { it.providerId == fixture.providerId && it.anonymous } }
                    .first { it.providerId == fixture.providerId && it.anonymous }
            }
        }
        awaitUi { it.connections.any { row -> row.id == anonymous.id } }
        onMain { viewModel.selectConnection(anonymous.id) }
        awaitUi { it.selectedId == anonymous.id }
        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.COMPLETE
        onMain { viewModel.testProviderModel(fixture.providerId, "anonymous-manual-model") }
        awaitAction("SUCCESS")
        assertNull(transport.lastTarget().credentialId)
        assertTrue(transport.lastTarget().authFields.isEmpty())
        val sample = sampleRows(fixture.providerId).single()
        assertEquals("", sample["safeCredentialId"])
        assertEquals(TelemetrySource.MANUAL_TEST.name, sample["source"])
        assertEquals(Outcome.SUCCESS.name, sample["outcome"])
        assertEquals("0", offeringOf(fixture.providerId, "anonymous-manual-model")!!["hidden"])
        assertTrue(queryRows("SELECT * FROM credentials WHERE accountId = ?", arrayOf(fixture.accountId)).isEmpty())
    }

    @Test
    fun committedCustomOnlyAnchorUsesItsSafeFieldsWithoutBearerFallback() {
        val fixture = givenConnection("custom-only")
        runBlocking {
            app.database.providerConfigDao().insert(ProviderManualConfig(
                providerId = fixture.providerId, workflowId = UUID.randomUUID().toString(),
                authMode = AuthMode.CUSTOM_HEADER,
                customAuthFields = listOf(CustomAuthFieldRef(
                    location = CredentialLocation.HEADER, name = "X-Api-Key", safeCredentialId = fixture.credentialId,
                )),
            ).toEntity())
        }
        // provider_configurations 同時被 connections／managementConnections 的 UNION 查詢引用，
        // 這次寫入必然排出一串 re-emission → refresh() → ensureSyncSupport() → refresh()。
        // 等這串 production refresh 自己收斂（由 modelRowsLoading 這個既有 production 訊號觀察），
        // 再開始 probe：不是放寬 expectation，而是不要讓 fixture 自己的寫入和 probe 的 IO 視窗重疊。
        selectAndAwait(fixture)
        awaitRowsSettled(fixture.providerId)
        transport.mode = FakeProbeTransport.Mode.COMPLETE
        onMain { viewModel.testProviderModel(fixture.providerId, "custom-only-manual-model") }
        awaitAction("SUCCESS")
        val target = transport.lastTarget()
        assertNull("Custom-only auth must not send the anchor as Bearer", target.credentialId)
        assertEquals(fixture.credentialId, target.authFields.single().credentialId)
        assertEquals("X-Api-Key", target.authFields.single().name)
        val sample = sampleRows(fixture.providerId).single()
        assertEquals("", sample["safeCredentialId"])
        assertEquals(TelemetrySource.MANUAL_TEST.name, sample["source"])
        assertEquals("0", offeringOf(fixture.providerId, "custom-only-manual-model")!!["hidden"])
        val detail = runBlocking { ProviderDetailLoader.loadDetail(
            app.database, fixture.providerId, selectedConnectionId = fixture.connectionId,
        ) }
        assertTrue("Custom-only result must project through its selected anchor and exact evidence",
            detail.models.single { it.modelId == "custom-only-manual-model" }.verified)
        assertNoSecretInOwnRows(fixture.providerId)
    }

    @Test
    fun unsupportedSyncSupportSettlesAndReevaluatesAfterRealConfigChanges() {
        val fixture = givenConnection("support-cache")
        selectAndAwait(fixture)
        val config = ProviderManualConfig(
            providerId = fixture.providerId, workflowId = UUID.randomUUID().toString(),
            authMode = AuthMode.PAT_ACCOUNT,
            credentials = listOf(SafeCredentialRef(fixture.credentialId, "fixture management", CredentialRole.ACCOUNT)),
        )
        runBlocking { app.database.providerConfigDao().insert(config.toEntity()) }
        awaitUi { state -> state.managedConnections.any { it.id == fixture.connectionId && it.supportsSync } }
        runBlocking {
            app.database.providerConfigDao().updateSnapshotJson(fixture.providerId,
                config.copy(credentials = listOf(SafeCredentialRef(
                    fixture.credentialId, "fixture inference", CredentialRole.INFERENCE,
                ))).encodeToSnapshotJson())
        }
        awaitUi { state -> state.managedConnections.any { it.id == fixture.connectionId && !it.supportsSync } }
        await { viewModel.modelRowsLoading.first { !it } }
        assertEquals("Support discovery must never trigger an inference probe", 0, transport.callCount)
    }

    // ===== fixtures / 觀察工具 =====

    private data class Fixture(
        val connectionId: String,
        val providerId: String,
        val accountId: String,
        val credentialId: String,
    )

    /** 走真 `saveConnection`（真 vault ＋真 Room ＋自動選取），只記下自己建立的 provider。 */
    private fun givenConnection(label: String): Fixture {
        val name = "Provider Action Fixture $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, "fixture-model-$label", FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        return Fixture(item.id, item.providerId, item.accountId, item.credentialId)
    }

    private fun selectAndAwait(fixture: Fixture) {
        onMain { viewModel.selectConnection(fixture.connectionId) }
        awaitUi { it.selectedId == fixture.connectionId }
        await { viewModel.selectedCredentialId.first { it == fixture.credentialId } }
    }

    private fun recordOf(connectionId: String): ConnectionRecord = runBlocking {
        withTimeout(TIMEOUT_MS) {
            app.repository.managementConnections
                .first { rows -> rows.any { it.id == connectionId } }
                .first { it.id == connectionId }
        }
    }

    /**
     * 放一筆「比本次 probe 更新」的 SUCCESS ＋ MANUAL_TEST 樣本（fixture，只存在測試）。
     * 任何以 latestFinal／最新一列猜測的實作都會錯拿它，因此本檔用它驗證精確關聯。
     */
    private fun seedNewerDecoySuccess(record: ConnectionRecord, modelId: String): String {
        val id = decoySampleId(modelId)
        val startedAt = System.currentTimeMillis() + DECOY_AHEAD_MS
        runBlocking {
            app.repository.save(
                PerformanceSample(
                    id = id,
                    providerId = record.providerId,
                    accountId = record.accountId,
                    safeCredentialId = record.credentialId,
                    endpointId = record.endpointId,
                    providerModelId = modelId,
                    canonicalModelId = null,
                    group = null,
                    operation = record.protocol,
                    source = TelemetrySource.MANUAL_TEST,
                    requestStartedAt = startedAt,
                    firstTokenAt = startedAt + 1L,
                    completedAt = startedAt + 2L,
                    ttftMs = 1L,
                    ttfbMs = 1L,
                    totalLatencyMs = 2L,
                    usage = Usage(inputTokens = 1L, outputTokens = 1L, totalTokens = 2L),
                    httpStatus = 200,
                    outcome = Outcome.SUCCESS,
                )
            )
        }
        return id
    }

    private fun decoySampleId(modelId: String): String = "decoy-$modelId-$RUN_TAG"

    private fun sampleRows(providerId: String): List<Map<String, String?>> = queryRows(
        "SELECT * FROM samples WHERE providerId = ? ORDER BY startedAt ASC, id ASC",
        arrayOf(providerId),
    )

    private fun sampleIds(providerId: String): Set<String> =
        sampleRows(providerId).mapNotNull { it["id"] }.toSet()

    private fun sampleOf(id: String): Map<String, String?>? =
        queryRows("SELECT * FROM samples WHERE id = ?", arrayOf(id)).singleOrNull()

    private fun offeringOf(providerId: String, modelId: String): Map<String, String?>? = queryRows(
        "SELECT * FROM offerings WHERE providerId = ? AND modelId = ?",
        arrayOf(providerId, modelId),
    ).singleOrNull()

    /** 期望存在的 persisted offering；缺席即為契約違反（比 nullable 鏈好讀）。 */
    private fun requireOffering(providerId: String, modelId: String): Map<String, String?> =
        offeringOf(providerId, modelId)
            ?: throw AssertionError("Expected a persisted offering for provider=$providerId model=$modelId")

    /**
 * 唯讀掃描：`openHelper.readableDatabase` 是 `SupportSQLiteDatabase`，只能用 `query(sql, args)`
 * （Android `SQLiteDatabase.rawQuery` 不存在於此介面），回傳值全部以字串讀取供斷言比較。
 */
    private fun queryRows(sql: String, args: Array<String>): List<Map<String, String?>> {
        val out = mutableListOf<Map<String, String?>>()
        app.database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, String?>(cursor.columnCount)
                for (index in 0 until cursor.columnCount) {
                    row[cursor.getColumnName(index)] = cursor.getString(index)
                }
                out += row
            }
        }
        return out
    }

    /** raw key 不得跨出 vault 邊界：telemetry／evidence／offering 都不得含測試憑證值。 */
    private fun assertNoSecretInOwnRows(providerId: String) {
        val rows = queryRows("SELECT * FROM samples WHERE providerId = ?", arrayOf(providerId)) +
            queryRows("SELECT * FROM evidence WHERE providerId = ?", arrayOf(providerId)) +
            queryRows("SELECT * FROM offerings WHERE providerId = ?", arrayOf(providerId))
        for (row in rows) {
            for ((column, value) in row) {
                assertFalse("telemetry 不得含測試憑證值（欄位 $column）", value?.contains(FIXTURE_SECRET) == true)
            }
        }
    }

    private fun assertNoSecret(text: String) {
        assertFalse("UI 邊界不得含測試憑證值", text.contains(FIXTURE_SECRET))
    }

    /** 只刪本檔建立的 provider 及其子資料，順序滿足 RESTRICT 外鍵；其他 session 的資料不動。 */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val ids = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM evidence WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_notices WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_events WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", ids)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", ids)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", ids)
            for (providerId in providerIds) {
                db.execSQL(
                    "DELETE FROM snapshot_facts WHERE scopeKey LIKE ?",
                    arrayOf("${providerId.length}:$providerId%"),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    /**
     * 等 VM 的 flow 收斂：viewModelScope 在 Main，以已排序的 Main barrier 推進 continuation；
     * 逾時即失敗（不用固定 sleep）。
     */
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

    private fun awaitAction(status: String, timeoutMillis: Long = TIMEOUT_MS): ProviderModelActionState =
        await(timeoutMillis) {
            viewModel.providerModelAction.first {
                it.status == status || it.status in setOf("SUCCESS", "FAILURE", "CANCELLED")
            }
        }.also { actual ->
            assertEquals("Unexpected terminal action; calls=${transport.callCount}, state=$actual", status, actual.status)
        }

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { viewModel.state.first(predicate) }

    /**
     * 等 fixture rows 收斂（`modelRowsLoading` 是既有 production 訊號，非本測試 invention）。
     * 用於「fixture 自己寫入會觸發 re-emission 級聯」之後、啟動 probe 之前。
     * 先顯式踢一次當前 context 的重算：切換選取時若舊 context 尚未套用，
     * `applySelectedContext` 會因比對相同而早退、根本沒觸發重算，此時光等 rows
     * 會永遠等不到（leave 曾因此 15s 逾時）。`refreshModelRows()` 是 production UI
     * 每次 emission 都會呼叫的公開入口，只保證「為當前選取算一次」，不改變任何斷言。
     */
    private fun awaitRowsSettled(providerId: String) {
        onMain { viewModel.refreshModelRows() }
        await {
            viewModel.modelRowsLoading.first { !it }
            viewModel.allModelRows.first { rows -> rows.any { it.providerId == providerId } }
            viewModel.modelRowsLoading.first { !it }
        }
    }

    private fun awaitGroup(group: String) = await { viewModel.selectedGroup.first { it == group } }

    /**
     * 等第 [index] 次 probe 的 stream 真正開始（槽位固定存在，未開始時是 suspend 而非越界）。
     * 與本次明確 provider/model 的 terminal state 競速；不把 leave 前一次 SUCCESS 當成本次結果。
     * 已完成的 started 訊號優先，未開始就 terminal 則立即以安全 scope 診斷失敗；逾時維持原值。
     */
    private fun awaitProbeStarted(index: Int, fixture: Fixture, modelId: String) = await {
        coroutineScope {
            val started = async(start = CoroutineStart.UNDISPATCHED) { transport.awaitStarted(index) }
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                viewModel.providerModelAction.first {
                    it.providerId == fixture.providerId && it.modelId == modelId &&
                        it.status in setOf("SUCCESS", "FAILURE", "CANCELLED")
                }
            }
            try {
                select<Unit> {
                    started.onAwait { }
                    terminal.onAwait { action ->
                        throw AssertionError(
                            "probe #$index terminal before started; calls=${transport.callCount}, " +
                                "expectedProvider=${fixture.providerId}, expectedModel=$modelId, " +
                                "expectedConnection=${fixture.connectionId}, status=${action.status}, " +
                                "connection=${action.connectionId}, group=${action.group}, " +
                                "clearToken=${action.clearToken}, refreshToken=${action.refreshToken}, " +
                                "selectedId=${viewModel.state.value.selectedId}, " +
                                "selectedAccount=${viewModel.selectedAccountId.value}, " +
                                "selectedCredential=${viewModel.selectedCredentialId.value}, " +
                                "selectedGroup=${viewModel.selectedGroup.value}",
                        )
                    }
                }
            } finally {
                started.cancel()
                terminal.cancel()
            }
        }
        assertTrue(
            "probe #$index 已發出開始訊號但 callCount=${transport.callCount} 不一致",
            transport.callCount > index,
        )
    }

    /**
     * 測試專用 transport：只產生事件序列，不做任何網路 I/O，也不碰 vault。
     * 事件順序沿用 core engine 測試慣例（RequestSent → Headers → Delta → usage → Finished），
     * 缺 Finished 即 parser failure；`GATE` 讓測試自己決定串流何時結束，
     * 以便在「請求進行中」這個時間點做競態斷言（single-flight、取消、換 key／group）。
     *
     * 訊號事先配置：[started]／[gates] 在 reset 時就建好固定槽位，測試端 `await`／`release(index)`
     * 因此永遠不會在 stream 尚未進入時越界；完成本身是 suspend 點，測試不需要 sleep 或 busy wait。
     */
    private class FakeProbeTransport : InferenceTransport {
        enum class Mode { COMPLETE, FAIL, GATE }

        @Volatile
        var mode: Mode = Mode.GATE

        private val targets = CopyOnWriteArrayList<InferenceTarget>()
        private val prompts = CopyOnWriteArrayList<List<PromptMessage>>()
        private val started = Array(MAX_PROBES) { CompletableDeferred<Unit>() }
        private val gates = Array(MAX_PROBES) { CompletableDeferred<Unit>() }
        private val probeIndex = AtomicInteger(0)

        val callCount: Int get() = targets.size

        fun lastTarget(): InferenceTarget = targets.last()

        fun promptText(): String = prompts.joinToString(separator = "\n") { list -> list.joinToString("\n") }

        fun reset() {
            targets.clear()
            prompts.clear()
            probeIndex.set(0)
            for (slot in 0 until MAX_PROBES) {
                started[slot] = CompletableDeferred()
                gates[slot] = CompletableDeferred()
            }
        }

        /** 槽位固定存在；尚未被該次 stream 完成時會 suspend，不會越界。 */
        suspend fun awaitStarted(index: Int) {
            require(index in 0 until MAX_PROBES) { "probe index out of range" }
            started[index].await()
        }

        fun release(index: Int) {
            require(index in 0 until MAX_PROBES) { "probe index out of range" }
            gates[index].complete(Unit)
        }

        override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
            val slot = probeIndex.getAndIncrement()
            require(slot < MAX_PROBES) { "fake transport exceeded $MAX_PROBES probes" }
            targets += target
            prompts += messages
            emit(StreamEvent.RequestSent(epochMillis = System.currentTimeMillis(), monotonicMillis = monotonic()))
            emit(
                StreamEvent.Headers(
                    status = 200,
                    rateLimits = mapOf("x-ratelimit-remaining-requests" to "9"),
                    observedMonotonicMs = monotonic(),
                ),
            )
            emit(StreamEvent.Delta("OK", observedMonotonicMs = monotonic()))
            emit(StreamEvent.TokenUsage(Usage(inputTokens = 3L, outputTokens = 2L, totalTokens = 5L)))
            started[slot].complete(Unit)
            when (mode) {
                Mode.COMPLETE -> emit(StreamEvent.Finished)
                Mode.FAIL -> throw InferenceFailure(Outcome.SERVER, 500)
                Mode.GATE -> {
                    gates[slot].await()
                    emit(StreamEvent.Finished)
                }
            }
        }

        private fun monotonic(): Long = System.nanoTime() / 1_000_000

        private companion object {
            /** 單一測試最多兩次 probe（single-flight 與 leave 兩階段）；槽位固定因此不需動態清單。 */
            const val MAX_PROBES = 8
        }
    }

    private class AtlasViewModelFactory(
        private val app: AtlasApplication,
        private val transport: InferenceTransport,
    ) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            // 只在此處注入測試用 engine；正式路徑仍走 app.engine。
            val engine = InferenceEngine(transport, app.repository)
            return AtlasViewModel(app, engine) as T
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val HOST = "provider-action.fixture.invalid"
        const val BASE_URL = "https://$HOST/v1"
        const val FIXTURE_SECRET = "provider-action-fixture-secret-never-persisted"
        const val DECOY_AHEAD_MS = 600_000L
        const val RUN_TAG = "fixture"
        val MANUAL_SOURCES = setOf("MANUAL", "USER")
        const val GROUP_ONE = "prov-action-group-one"
        const val GROUP_TWO = "prov-action-group-two"
        const val BLANK_DRAFT = "   "
        const val MANUAL_TEST_PROMPT_MUST_NOT_BE_SENT = "manual-test-probe-must-not-be-sent"

        const val MODEL_EXACT = "prov-action-model-exact"
        const val MODEL_DECOY = "prov-action-model-decoy"
        const val MODEL_RETEST = "prov-action-model-retest"
        const val MODEL_FOREIGN = "prov-action-model-foreign"
        const val MODEL_FIRST_TAP = "prov-action-model-first-tap"
        const val MODEL_SECOND_TAP = "prov-action-model-second-tap"
        const val MODEL_CANCEL = "prov-action-model-cancel"
        const val MODEL_SWITCH = "prov-action-model-switch"
        const val MODEL_GROUP = "prov-action-model-group"
        const val MODEL_ADD = "prov-action-model-add"
        const val MODEL_ADD_TESTED = "prov-action-model-add-tested"
        const val MODEL_DISABLED = "prov-action-model-disabled"
        const val MODEL_LEAVE = "prov-action-model-leave"
        const val MODEL_LEAVE_OWN = "prov-action-model-leave-own"
    }
}
