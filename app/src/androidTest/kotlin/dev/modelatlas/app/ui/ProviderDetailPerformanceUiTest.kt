package dev.modelatlas.app.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-005 C3／P4 device UI text 測試：Provider Detail Performance 成功率文字。
 *
 * TRACEABILITY：CORRECTNESS_REPAIR_AUDIT C3（ratio 以百分比呈現、null 明示未知、
 * provider-reported 0..100 單位不與 local telemetry ratio 混用）；spec-cr005 P4。
 *
 * 只掛載真 production [ProviderDetailScreen] 並斷言實際 semantics 文字節點，
 * 不重演 formatter 邏輯、不改 production／UI／schema／資源：
 * - 呼叫端在 `ProviderDetailScreen.kt:933–934` 對 `success1h／success24h` 呼叫
 *   `ProviderDetailLoader.successRatePercentLabel`；本測試鎖定畫面呈現結果。
 * - `successRatePercentLabel` 只接受 local-telemetry 比例（0..1）。provider-reported
 *   perf 以 0..100 單位保存、不得被 rescale／混入此 formatter（Iron Law：來源分離）。
 * - header 全部欄位給已知值，讓畫面唯一的「未知」只可能來自 performance 缺值，
 *   可精確計數，不需猜其他區塊文案。
 * - 沿用既有 device harness（`ProviderModelActionScreenTest`）的唯一 barrier：
 *   `waitForIdle()` ＋ 以 `hasScrollToIndexAction()` 選出內容 LazyColumn 再
 *   `performScrollToNode`；無 sleep、無 `waitUntil`／timeout 放寬。
 * - fixture 只存在本 test sourceSet，不作產品資料；無 raw secret。
 */
@RunWith(AndroidJUnit4::class)
class ProviderDetailPerformanceUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    /** 掛載真 screen；模型動作 callback 一律 null（本測試不經動作路徑）。 */
    private fun mount(state: ProviderDetailState) {
        compose.setContent {
            ProviderDetailScreen(
                state = state,
                onBack = {},
                onTab = {},
                onSync = {},
                onTestNow = null,
                onAddModel = null,
                onOpenCheckin = {},
                onCompleteCheckin = {},
                onCancelAction = {},
            )
        }
        compose.waitForIdle()
    }

    /** 內容 LazyColumn 是第一個具 `ScrollToIndex` 語意的節點（tab 列為水平捲動）。 */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasScrollToIndexAction())
            .onFirst()
            .performScrollToNode(matcher)
        compose.waitForIdle()
    }

    private fun revealText(text: String) = scrollTo(hasText(text))

    /**
     * Header 全給已知值：畫面不得因此產生任何「未知」，故 performance 區塊缺值可精確計數。
     */
    private fun knownHeader() = ProviderDetailHeader(
        providerId = "p1",
        name = "fixture provider",
        baseUrl = "https://fixture.invalid/v1",
        adapterType = "OPENAI",
        officialHealth = "HEALTHY",
        observedHealth = "HEALTHY",
        priceState = "PAID",
        quotaState = "QUOTA_AVAILABLE",
        credentialCount = 0,
        freeNowCount = 0,
    )

    private fun performanceState(performance: ProviderDetailPerformance) = ProviderDetailState(
        header = knownHeader(),
        performance = performance,
        selectedTab = ProviderDetailTab.PERFORMANCE,
    )

    // ---- 1：local ratio 0.8 → "80%"，絕不顯示 raw "0.8" ----

    @Test
    fun successRatioRendersAsPercentLabelNotRawRatio() {
        mount(
            performanceState(
                ProviderDetailPerformance(
                    tps = 1.0,
                    ttftMs = 10L,
                    latencyMs = 20L,
                    success1h = null,
                    success24h = 0.8,
                    requestCount = 30L,
                ),
            ),
        )

        revealText("80%")
        compose.onNodeWithText("80%").assertExists()
        compose.onNodeWithText("0.8").assertDoesNotExist()
        // success1h = null：只有這一個 performance 缺值 → 畫面恰一個「未知」。
        compose.onAllNodesWithText(str(R.string.value_unknown)).assertCountEquals(1)
    }

    // ---- 2：null success → 未知字串，不是 0／100／raw "null"（Unknown != 0） ----

    @Test
    fun nullSuccessRatiosRenderUnknownStringNotZeroOrNull() {
        mount(
            performanceState(
                ProviderDetailPerformance(
                    tps = 1.0,
                    ttftMs = 10L,
                    latencyMs = 20L,
                    success1h = null,
                    success24h = null,
                    requestCount = 30L,
                ),
            ),
        )

        revealText("30")
        // 兩個 success 欄位皆未知；header／其他 metrics 皆已知。
        compose.onAllNodesWithText(str(R.string.value_unknown)).assertCountEquals(2)
        compose.onNodeWithText("null").assertDoesNotExist()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("100%").assertDoesNotExist()
    }

    // ---- 3：provider-reported 0..100 單位不得混入 local ratio formatter ----

    @Test
    fun providerReportedPercentScaleDoesNotMixIntoLocalRatioFormatter() {
        mount(
            performanceState(
                ProviderDetailPerformance(
                    tps = 1.0,
                    ttftMs = 10L,
                    latencyMs = 20L,
                    success1h = null,
                    success24h = 80.0,
                    requestCount = 30L,
                ),
            ),
        )

        revealText("30")
        // 0..100 單位（如 store 的 provider-reported perf）不得被 rescale 成 "8000%"，
        // 也不得被當成本地 0..1 比例而顯示 "80%"；不得原樣顯示數字。
        compose.onNodeWithText("8000%").assertDoesNotExist()
        compose.onNodeWithText("80%").assertDoesNotExist()
        compose.onNodeWithText("80.0").assertDoesNotExist()
        // fail-closed：超出 [0,1] → 未知；連同 success1h 共兩個未知。
        compose.onAllNodesWithText(str(R.string.value_unknown)).assertCountEquals(2)
    }

    // ---- 4：真帳號 identity 下，多憑證為同一 account 的子列（spec P5 UI） ----

    @Test
    fun multiCredentialAccountRendersChildrenUnderSingleAccount() {
        mount(
            ProviderDetailState(
                header = knownHeader(),
                accounts = listOf(
                    ProviderDetailAccount(
                        accountId = "acct-A",
                        credentials = listOf(
                            ProviderDetailCredential(credentialId = "cred-1", label = "主要憑證"),
                            ProviderDetailCredential(credentialId = "cred-2", label = "備用憑證", enabled = false),
                        ),
                    ),
                ),
                selectedTab = ProviderDetailTab.OVERVIEW,
            ),
        )

        revealText("主要憑證")
        compose.onNodeWithText("主要憑證").assertExists()
        revealText("備用憑證")
        compose.onNodeWithText("備用憑證").assertExists()
        // 單一真實 account 區塊；credentialId 不得冒充 account identity。
        compose.onAllNodesWithText(str(R.string.playground_account) + " · acct-A").assertCountEquals(1)
        compose.onAllNodesWithText("cred-1").assertCountEquals(0)
        compose.onAllNodesWithText("cred-2").assertCountEquals(0)
    }
}
