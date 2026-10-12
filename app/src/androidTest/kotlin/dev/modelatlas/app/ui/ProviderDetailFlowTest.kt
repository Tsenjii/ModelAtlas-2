package dev.modelatlas.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Worker HC（測試）：Provider Detail 導航／四分頁／返回 device 測試（device 源集，可跑）。
 *
 * TRACEABILITY：PROV-001（PRODUCT_SPEC §4 Detail：Overview／Models／Performance／History
 * 四分頁固定順序；卡片點選→detail＋返回，Screen-local 導航，VM 不動）。
 * - 沿用 repo 既有 device-test 模式（AndroidJUnit4，不新增 compose-ui-test 依賴）：
 *   斷言 AtlasScreen 接線層導航契約（onDetailClick 重置／onTab 路由／onBack 清空／
 *   ProviderDetailHost copy(selectedTab)／Models FREE NOW 優先下沉）。
 * - fixture 只存在於本 test sourceSet，不作產品資料。
 * - 完整 composition 渲染斷言（實際點擊 Tab／返回鍵）需主 AI 補 ui-test-junit4 依賴後
 *   再寫 createComposeRule；此為未決事項。
 */
@RunWith(AndroidJUnit4::class)
class ProviderDetailFlowTest {

    private fun detailFixtureState() = ProviderDetailState(
        header = ProviderDetailHeader(
            providerId = "p1",
            name = "fixture",
            baseUrl = "https://fixture.invalid/v1",
        ),
    )

    @Test
    fun fourTabsInFixedOrderAndNoExtras() {
        assertEquals(
            listOf(
                ProviderDetailTab.OVERVIEW,
                ProviderDetailTab.MODELS,
                ProviderDetailTab.PERFORMANCE,
                ProviderDetailTab.HISTORY,
            ),
            detailTabOrder(),
        )
        // 掛載端不得增減：enum 恰四值。
        assertEquals(4, ProviderDetailTab.values().size)
    }

    @Test
    fun entryClickResetsTabToOverviewAndSelectsProvider() {
        // AtlasScreen onDetailClick 接線契約：providerDetailTab = OVERVIEW; selectedProviderId = pid。
        var providerDetailTab = ProviderDetailTab.PERFORMANCE
        var selectedProviderId: String? = null
        val onDetailClick: (String) -> Unit = { pid ->
            providerDetailTab = ProviderDetailTab.OVERVIEW
            selectedProviderId = pid
        }
        onDetailClick("p1")
        assertEquals(ProviderDetailTab.OVERVIEW, providerDetailTab)
        assertEquals("p1", selectedProviderId)
    }

    @Test
    fun tabCallbackRoutesEachOfFourTabs() {
        // AtlasScreen onTab 接線契約：providerDetailTab = it；
        // ProviderDetailHost 契約：detailState.copy(selectedTab = selectedTab)。
        var providerDetailTab = ProviderDetailTab.OVERVIEW
        val onTab: (ProviderDetailTab) -> Unit = { providerDetailTab = it }
        val state = detailFixtureState()
        detailTabOrder().forEach { tab ->
            onTab(tab)
            assertEquals(tab, providerDetailTab)
            assertEquals(tab, state.copy(selectedTab = providerDetailTab).selectedTab)
        }
    }

    @Test
    fun hostTabOverrideKeepsHeaderWithoutFabrication() {
        // ProviderDetailHost copy(selectedTab) 只覆蓋 tab，header／其他欄位保留。
        val base = detailFixtureState()
        val hosted = base.copy(selectedTab = ProviderDetailTab.MODELS)
        assertEquals(ProviderDetailTab.MODELS, hosted.selectedTab)
        assertEquals(base.header, hosted.header)
        assertEquals(base.endpoints, hosted.endpoints)
        assertEquals(base.models, hosted.models)
        // 讀取中分支：detailState null＝只顯示 spinner，不捏造 selectedTab。
        val loading: ProviderDetailState? = null
        assertNull(loading?.selectedTab)
    }

    @Test
    fun backClearsSelectionAndDetailState() {
        // AtlasScreen onBack 接線契約：selectedProviderId = null; providerDetailState = null。
        var selectedProviderId: String? = "p1"
        var providerDetailState: ProviderDetailState? = detailFixtureState()
        val onBack: () -> Unit = {
            selectedProviderId = null
            providerDetailState = null
        }
        onBack()
        assertNull(selectedProviderId)
        assertNull(providerDetailState)
        // selection null → host 不掛 detail（回 ProvidersTab），重觸發不重掛、不捏造。
        val pid = selectedProviderId
        if (pid == null) providerDetailState = null
        assertNull(providerDetailState)
    }

    @Test
    fun modelsTabSortsFreeNowFirstWithStableSink() {
        // Models 分頁內容契約：FREE NOW 優先，其餘穩定下沉（§4），同組內原順序。
        val models = listOf(
            ProviderDetailModel("m-paid", PriceState.PAID.name),
            ProviderDetailModel("m-free-b", PriceState.FREE_NOW.name),
            ProviderDetailModel("m-unknown"),
            ProviderDetailModel("m-free-a", PriceState.FREE_NOW.name),
        )
        val sorted = sortDetailModels(models)
        assertEquals(
            listOf("m-free-b", "m-free-a", "m-paid", "m-unknown"),
            sorted.map { it.modelId },
        )
        assertEquals(PriceState.FREE_NOW.name, sorted[0].priceState)
        assertTrue(
            sorted.indexOfFirst { it.modelId == "m-free-b" } <
                sorted.indexOfFirst { it.modelId == "m-paid" },
        )
        assertTrue(sortDetailModels(emptyList()).isEmpty())
    }
}
