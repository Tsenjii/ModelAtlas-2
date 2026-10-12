package dev.modelatlas.app.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ProviderModelActionState
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Worker UI-TEST-PROV：Provider Detail「新增模型 / 立即測試」真實 Compose 渲染測試。
 *
 * TRACEABILITY：PROV-001 / MODEL-006（PRODUCT_SPEC §4 Detail Models、§6 已選連線
 * 歸屬與不猜另一把 key、§8 Manual Model ID hidden/manual offering）。
 *
 * - 以 `createComposeRule` 實際 composition production `ProviderDetailScreen`，
 *   以 `mutableStateOf<ProviderDetailState>` 驅動流程：輸入 draft → 點動作 →
 *   推進 VM 回填的 `action`（RUNNING／FAILURE／CANCELLED／SUCCESS）→ 斷言畫面。
 *   斷言的是真實節點狀態（按鈕 enabled/disabled、輸入框實際內容、cancel 是否被
 *   呼叫、安全錯誤訊息是否渲染），不是只在 test 內重演 enable 邏輯或比較 fixture 欄位。
 * - 動作按鈕以「文字 + 按鈕點擊語意（`hasClickAction`）」收斂：
 *   `provider_detail_add_models_heading`（區段標題）與 `provider_detail_add_model`
 *   （按鈕）字面相同（皆為「新增模型」），純文字比對會命中兩個節點
 *   （HEAD e277a97 / run36747181436 五個測試皆因此失敗）。device 語意樹顯示標題沒有
 *   `OnClick`、按鈕有（停用時仍保留），故此比對可精確鎖定動作按鈕且不影響停用斷言。
 *   捲動與斷言共用同一 matcher。
 * - Detail 為 `LazyColumn`，header／動作範圍卡之後的輸入框、狀態卡、模型列可能在
 *   首屏外。所有斷言前都以 `scrollTo` 先捲到目標節點；捲動目標用
 *   `hasScrollToIndexAction()` 選出內容清單（tab 列為水平捲動，不是目標），並在初始
 *   互動（輸入 draft、第一次點按鈕）之前就先捲過去。
 *   `performScrollToNode` 作用於 `SemanticsNodeInteraction`，故先 `.onFirst()` 選出
 *   單一捲動容器再呼叫，不對互動集合直接呼叫。
 * - 文字斷言一律經 `InstrumentationRegistry` 讀 `R.string`（含 status line 的
 *   format 參數），不硬編文案。
 * - 驗收點：
 *   1. 點「新增模型」後 draft 保留（不提前清）；只有 SUCCESS ADD 且 clearToken
 *      遞增才清空（`shouldClearModelDraft` 契約的實際畫面結果）；
 *   2. FAILURE / CANCELLED 保留 draft，且新增按鈕回到可再送出；
 *   3. RUNNING 時新增／測試 disabled（阻擋重複送出），「新增中…／測試中…」文案
 *      生效，且「取消動作」可見並真的被呼叫；
 *   4. FAILURE 時安全本地錯誤訊息實際渲染，結束後不再顯示取消、可重試；
 *   5. `onAddModel` / `onTestNow` 為 null 時按鈕 truly disabled（點擊不觸發
 *      callback）且明示「尚未接上產品流程」；
 *   6. `actionScope == null`（未選連線）時所有動作 disabled 並顯示選擇指引，
 *      不猜另一把 key。
 * - fixture 只存在於本 test sourceSet，不作產品資料。
 */
@RunWith(AndroidJUnit4::class)
class ProviderModelActionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String = context.getString(id)

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 動作按鈕的語意選擇器（文字 + 可點擊的按鈕語意）。
     *
     * `provider_detail_add_models_heading`（區段標題）與 `provider_detail_add_model`
     * （按鈕 label）字面完全相同（皆為「新增模型」），只以文字比對會同時命中
     * `[Heading]` 與按鈕兩個節點（HEAD e277a97 / run36747181436 五個測試皆因此
     * `Expected exactly '1' node but found '2' nodes` 失敗）。
     *
     * device 語意樹（CI artifact）顯示兩者的差別正是按鈕帶點擊語意、標題沒有：
     * - 標題節點：`Text = '[新增模型]'`、`[Heading]`，Actions 只有文字相關，**無** `OnClick`；
     * - 按鈕節點：`Text = '[新增模型]'`、`Role = 'Button'`，Actions 含 `OnClick`。
     *
     * 因此以 `hasText(label) and hasClickAction()` 收斂到真正的動作按鈕：
     * - 排除同名的區段標題；
     * - `[Disabled]` 的按鈕仍保有 `OnClick` 語意（artifact 實測），故停用狀態的按鈕
     *   一樣能被定位，`assertIsNotEnabled` 的期望不受影響；
     * - 狀態卡的「立即測試：RUNNING」只是文字、沒有點擊語意，不會誤中。
     *
     * 不用 `SemanticsMatcher.expectValue(SemanticsProperties.Role, ...)`：該 API 為
     * `@ExperimentalTestApi` 且需額外 opt-in，在此檔無法本地驗證編譯，改用等價且穩定的
     * `hasClickAction()` 判定（兩者在本畫面都由 Material3 Button 提供）。
     */
    private fun actionButton(label: String): SemanticsMatcher =
        hasText(label) and hasClickAction()

    /**
     * Provider Detail 的內容是垂直 `LazyColumn`；tab 列是水平捲動的 `ScrollableTabRow`，
     * 以 `hasScrollAction()` 篩會把兩者都命中，捲動方向也可能挑錯。
     *
     * - 這裡以 `hasScrollToIndexAction()` 篩出 lazy 清單並取 tree order 的第一個：內容
     *   `LazyColumn` 是 scaffold 內容節點，是 tab 列（其 stickyHeader 後代）的祖先，
     *   前序走訪必然先遇到它，因此不會把水平 tab 列當成捲動目標。
     * - `performScrollToNode` 是 `SemanticsNodeInteraction` 的擴充，必須先選出單一
     *   節點再呼叫，不能對互動集合直接呼叫。
     */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasScrollToIndexAction())
            .onFirst()
            .performScrollToNode(matcher)
        compose.waitForIdle()
    }

    /** 捲到含有該文字的節點再斷言，避免把「尚未 composition」誤判成通過。 */
    private fun revealText(text: String) = scrollTo(hasText(text))

    private fun revealMatcher(matcher: SemanticsMatcher) = scrollTo(matcher)

    /** 捲到動作按鈕（文字＋Button role），再做 enabled/disabled／點擊斷言。 */
    private fun revealActionButton(label: String) = scrollTo(actionButton(label))

    /** 捲到「新增模型」輸入框（唯一可輸入語意節點）再輸入／檢查 draft。 */
    private fun revealDraftField() = scrollTo(hasSetTextAction())

    private fun baseState(
        action: ProviderModelActionState = ProviderModelActionState(),
        actionScope: ProviderDetailActionScope? = ProviderDetailActionScope(
            connectionId = "c1",
            connectionName = "fixture connection",
            credentialLabel = "fixture key",
        ),
        models: List<ProviderDetailModel> = listOf(
            ProviderDetailModel("fixture-model", PriceState.UNKNOWN.name),
        ),
    ) = ProviderDetailState(
        header = ProviderDetailHeader(
            providerId = "p1",
            name = "fixture provider",
            baseUrl = "https://fixture.invalid/v1",
        ),
        models = models,
        selectedTab = ProviderDetailTab.MODELS,
        action = action,
        actionScope = actionScope,
    )

    private data class Host(
        val state: MutableState<ProviderDetailState>,
        val addCalls: MutableList<String>,
        val testCalls: MutableList<String>,
        val cancelCalls: MutableList<String>,
    )

    /**
     * 掛載真實 `ProviderDetailScreen`。`onAddModel` / `onTestNow` 為 null 時即掛 null，
     * 讓畫面走「尚未接線」分支。
     */
    private fun mountScreen(
        initial: ProviderDetailState,
        withAdd: Boolean = true,
        withTest: Boolean = true,
    ): Host {
        val state = mutableStateOf(initial)
        val addCalls = mutableListOf<String>()
        val testCalls = mutableListOf<String>()
        val cancelCalls = mutableListOf<String>()
        compose.setContent {
            ProviderDetailScreen(
                state = state.value,
                onBack = {},
                onTab = {},
                onSync = {},
                onTestNow = if (withTest) { id -> testCalls += id } else null,
                onAddModel = if (withAdd) { id -> addCalls += id } else null,
                onOpenCheckin = {},
                onCompleteCheckin = {},
                onCancelAction = { cancelCalls += "cancel" },
            )
        }
        return Host(state, addCalls, testCalls, cancelCalls)
    }

    // ---- 1：點新增後 draft 保留；只有 SUCCESS ADD clearToken 遞增才清 ----

    @Test
    fun addCallbackFiresButDraftIsRetainedUntilSuccessAddClearToken() {
        val host = mountScreen(baseState())
        val hint = str(R.string.provider_detail_model_hint)
        val addLabel = str(R.string.provider_detail_add_model)

        // 空 draft：按鈕 disabled，點擊不觸發任何動作。
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).assertIsNotEnabled()
        compose.onNode(actionButton(addLabel)).performClick()
        compose.waitForIdle()
        assertTrue(host.addCalls.isEmpty())

        // 輸入框可能在首屏外（header + 動作範圍卡之後），先捲到可輸入節點再輸入。
        revealDraftField()
        compose.onNodeWithText(hint).performTextInput("gpt-fixture")
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).assertIsEnabled()
        compose.onNode(actionButton(addLabel)).performClick()
        compose.waitForIdle()

        // 點按已觸發真實 callback，但 draft 必須保留（不得提前清空輸入）。
        assertEquals(listOf("gpt-fixture"), host.addCalls)
        revealMatcher(hasSetTextAction() and hasText("gpt-fixture"))
        compose.onNode(hasSetTextAction() and hasText("gpt-fixture")).assertExists()

        // VM 回 SUCCESS ADD 且 clearToken 遞增 → draft 才清空（輸入內容消失）。
        host.state.value = host.state.value.copy(
            action = ProviderModelActionState(
                providerId = "p1", modelId = "gpt-fixture", connectionId = "c1",
                kind = "ADD", status = "SUCCESS", message = str(R.string.provider_detail_unverified),
                clearToken = 1,
            ),
        )
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("gpt-fixture")).assertDoesNotExist()
        // draft 清空後「新增模型」按鈕回到 disabled。
        compose.onNode(actionButton(addLabel)).assertIsNotEnabled()
    }

    // ---- 2：FAILURE / CANCELLED 保留 draft ----

    @Test
    fun failureKeepsDraftInInputField() {
        val host = mountScreen(baseState())
        val hint = str(R.string.provider_detail_model_hint)
        val addLabel = str(R.string.provider_detail_add_model)

        revealDraftField()
        compose.onNodeWithText(hint).performTextInput("failing-model")
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).performClick()
        compose.waitForIdle()

        val safeError = str(R.string.error_connection_disabled)
        host.state.value = host.state.value.copy(
            action = ProviderModelActionState(
                providerId = "p1", modelId = "failing-model", connectionId = "c1",
                kind = "ADD", status = "FAILURE", message = safeError,
            ),
        )
        compose.waitForIdle()
        // 失敗不得清 draft：輸入仍在，按鈕仍 enabled。
        revealMatcher(hasSetTextAction() and hasText("failing-model"))
        compose.onNode(hasSetTextAction() and hasText("failing-model")).assertExists()
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).assertIsEnabled()
        assertEquals(1, host.addCalls.size)
    }

    @Test
    fun cancelledKeepsDraftInInputField() {
        val host = mountScreen(baseState())
        val hint = str(R.string.provider_detail_model_hint)
        val addLabel = str(R.string.provider_detail_add_model)

        revealDraftField()
        compose.onNodeWithText(hint).performTextInput("cancel-model")
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).performClick()
        compose.waitForIdle()

        host.state.value = host.state.value.copy(
            action = ProviderModelActionState(
                providerId = "p1", modelId = "cancel-model", connectionId = "c1",
                kind = "ADD", status = "CANCELLED", message = null,
            ),
        )
        compose.waitForIdle()
        revealMatcher(hasSetTextAction() and hasText("cancel-model"))
        compose.onNode(hasSetTextAction() and hasText("cancel-model")).assertExists()
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).assertIsEnabled()
        assertEquals(1, host.addCalls.size)
    }

    // ---- 3：RUNNING 阻擋重複動作、顯示可呼叫取消 ----

    @Test
    fun runningDisablesAddAndTestAndShowsCallableCancel() {
        val host = mountScreen(baseState())
        // 進行中（ADD）時按鈕文案切為「新增中…」，且 disabled。
        val addingLabel = str(R.string.provider_detail_adding_model)
        val testLabel = str(R.string.provider_detail_test_now)
        val cancelLabel = str(R.string.provider_detail_action_cancel)

        // 先輸入讓 draft 有值；RUNNING 時新增按鈕必須仍 disabled。
        revealDraftField()
        compose.onNodeWithText(str(R.string.provider_detail_model_hint)).performTextInput("busy-model")
        host.state.value = host.state.value.copy(
            action = ProviderModelActionState(
                providerId = "p1", modelId = "busy-model", connectionId = "c1",
                kind = "ADD", status = "RUNNING", message = null,
            ),
        )
        compose.waitForIdle()
        revealActionButton(addingLabel)
        compose.onNode(actionButton(addingLabel)).assertIsNotEnabled()
        revealActionButton(testLabel)
        compose.onNode(actionButton(testLabel)).assertIsNotEnabled()
        // 進行中顯示進度狀態卡與可呼叫的取消按鈕。
        revealActionButton(cancelLabel)
        compose.onNode(actionButton(cancelLabel)).assertIsEnabled()
        compose.onNode(actionButton(cancelLabel)).performClick()
        assertEquals(listOf("cancel"), host.cancelCalls)

        // 阻擋重複送出：RUNNING 時點測試不得觸發。
        revealActionButton(testLabel)
        compose.onNode(actionButton(testLabel)).performClick()
        assertTrue(host.testCalls.isEmpty())
    }

    @Test
    fun runningTestShowsTestNowProgressAndCancel() {
        val host = mountScreen(baseState())
        val testLabel = str(R.string.provider_detail_test_now)
        val testingLabel = str(R.string.provider_detail_testing)
        val cancelLabel = str(R.string.provider_detail_action_cancel)

        // 先成功觸發一次測試（idle→callback），再推 RUNNING TEST。
        revealActionButton(testLabel)
        compose.onNode(actionButton(testLabel)).performClick()
        assertEquals(listOf("fixture-model"), host.testCalls)

        host.state.value = host.state.value.copy(
            action = ProviderModelActionState(
                providerId = "p1", modelId = "fixture-model", connectionId = "c1",
                kind = "TEST", status = "RUNNING", message = null,
            ),
        )
        compose.waitForIdle()
        // 測試進行中：該列按鈕 disabled 且換成「測試中…」；不重複送出。
        revealActionButton(testingLabel)
        compose.onNode(actionButton(testingLabel)).assertIsNotEnabled()
        revealActionButton(cancelLabel)
        compose.onNode(actionButton(cancelLabel)).assertIsEnabled()
        revealActionButton(testingLabel)
        compose.onNode(actionButton(testingLabel)).performClick()
        // 進行中點擊不得再送出：仍只有第一次那一次呼叫。
        assertEquals(listOf("fixture-model"), host.testCalls)
    }

    // ---- 4：安全本地錯誤訊息可見 ----

    @Test
    fun failureShowsSafeLocalizedErrorMessage() {
        val host = mountScreen(baseState())
        val safeError = str(R.string.error_connection_disabled)
        host.state.value = host.state.value.copy(
            action = ProviderModelActionState(
                providerId = "p1", modelId = "fixture-model", connectionId = "c1",
                kind = "TEST", status = "FAILURE", message = safeError,
            ),
        )
        compose.waitForIdle()
        // 失敗狀態列（立即測試：FAILURE）與安全錯誤訊息實際渲染在畫面上。
        revealText(safeError)
        compose.onNodeWithText(
            str(
                R.string.provider_detail_action_status_line,
                str(R.string.provider_detail_test_now),
                "FAILURE",
            ),
        ).assertExists()
        compose.onNodeWithText(safeError).assertExists()
        assertTrue(!safeError.contains("http://") && !safeError.contains("https://"))
        // 失敗時不顯示取消按鈕（以 Button role 判定：狀態卡文案不算取消按鈕）。
        compose.onAllNodes(actionButton(str(R.string.provider_detail_action_cancel)))
            .assertCountEquals(0)
        // 失敗後仍可重試：測試按鈕恢復 enabled（用 Button role 收斂，
        // 避免與狀態列「立即測試：FAILURE」文字混淆）。
        revealActionButton(str(R.string.provider_detail_test_now))
        compose.onNode(actionButton(str(R.string.provider_detail_test_now))).assertIsEnabled()
    }

    @Test
    fun successShowsSafeMessageAndNoCancel() {
        val host = mountScreen(baseState())
        val verified = str(R.string.provider_detail_verified)
        host.state.value = host.state.value.copy(
            action = ProviderModelActionState(
                providerId = "p1", modelId = "fixture-model", connectionId = "c1",
                kind = "TEST", status = "SUCCESS", message = verified,
            ),
        )
        compose.waitForIdle()
        // 成功訊息（本地安全字串）確實渲染在狀態卡上。
        revealText(verified)
        compose.onAllNodesWithText(verified).assertCountEquals(1)
        // 已結束：不再顯示取消按鈕（按鈕點擊語意判定）。
        compose.onAllNodes(actionButton(str(R.string.provider_detail_action_cancel)))
            .assertCountEquals(0)
    }

    // ---- 5：callback null 時 truly disabled 且明示尚未接線 ----

    @Test
    fun nullAddCallbackKeepsAddTrulyDisabledAndShowsUnavailable() {
        mountScreen(baseState(), withAdd = false)
        val hint = str(R.string.provider_detail_model_hint)
        val addLabel = str(R.string.provider_detail_add_model)
        val unavailable = str(R.string.provider_detail_actions_unavailable)

        revealDraftField()
        compose.onNodeWithText(hint).performTextInput("no-wire-model")
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).assertIsNotEnabled()
        // 新增列明示「尚未接上產品流程」。
        // onAddModel == null 只有新增卡那一處提示；測試 callback 仍在，故模型列無提示。
        compose.onAllNodesWithText(unavailable).assertCountEquals(1)
    }

    @Test
    fun nullTestCallbackKeepsTestNowTrulyDisabled() {
        val host = mountScreen(baseState(), withTest = false)
        val testLabel = str(R.string.provider_detail_test_now)
        val unavailable = str(R.string.provider_detail_actions_unavailable)
        revealActionButton(testLabel)
        compose.onNode(actionButton(testLabel)).assertIsNotEnabled()
        // 點擊 disabled 的「立即測試」不得觸發任何 callback。
        compose.onNode(actionButton(testLabel)).performClick()
        assertTrue(host.testCalls.isEmpty())
        // 該測試列也明示「尚未接上產品流程」。
        // onTestNow == null 只有模型列那一處提示；新增 callback 仍在，故新增卡無提示。
        compose.onAllNodesWithText(unavailable).assertCountEquals(1)
    }

    // ---- 附加真實流程：無可用連線（actionScope null）時全部 disabled ----

    @Test
    fun missingActionScopeDisablesAllActionsAndShowsGuidance() {
        mountScreen(baseState(actionScope = null))
        val addLabel = str(R.string.provider_detail_add_model)
        val testLabel = str(R.string.provider_detail_test_now)
        val guidance = str(R.string.provider_detail_action_scope_required)

        revealDraftField()
        compose.onNodeWithText(str(R.string.provider_detail_model_hint)).performTextInput("no-scope")
        revealActionButton(addLabel)
        compose.onNode(actionButton(addLabel)).assertIsNotEnabled()
        revealActionButton(testLabel)
        compose.onNode(actionButton(testLabel)).assertIsNotEnabled()
        revealText(guidance)
        compose.onNodeWithText(guidance).assertExists()
    }
}
