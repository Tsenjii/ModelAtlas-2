package dev.modelatlas.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6 repair worker B：Compare／Detail／Filter 真接線 device 測試（device 源集，可跑）。
 *
 * TRACEABILITY：MODEL-008（Compare 可點：同 prompt request 建構＋回填＋取消／失敗語意）、
 * MODEL-009（Detail 歷史真實＋local／external 分欄）、MODEL-005（Filter 進階可用）。
 * - 沿用 repo 既有 device-test 模式（AndroidJUnit4，不新增 compose-ui-test 依賴）：
 *   斷言接線層契約（request 建構／空態／分欄／availability 推導）。
 * - 完整 composition 渲染斷言需主 AI 補 ui-test-junit4 依賴後再寫 createComposeRule；
 *   flow 端到端（真跑 runner＋真查 DB）由主 AI 在 VM/Repo hook 接線後補 device flow test。
 */
@RunWith(AndroidJUnit4::class)
class CompareDetailRepairDeviceTest {

    private fun deviceFixtureRow(
        canonicalId: String,
        providerId: String = "p1",
    ) = ModelRow(
        providerId = providerId,
        providerModelId = canonicalId,
        canonicalId = canonicalId,
        priceState = PriceState.UNKNOWN,
        quotaState = QuotaState.UNKNOWN,
    )

    @Test
    fun compareRequestNeedsDistinctScopeIds() {
        val ok = CompareLiveWiring.buildRequest(
            "canon",
            listOf(
                CompareLiveWiring.CompareOfferingInput(
                    offeringId = "o1",
                    providerId = "p1",
                    accountId = "a1",
                    credentialId = "c1",
                    endpointId = "e1",
                    endpointUrl = "https://api.example.com/v1",
                    providerModelId = "m1",
                ),
            ),
        )
        assertTrue(ok != null)
        assertEquals(CompareLiveWiring.comparePrompt(), ok!!.messages)
        // offeringId 重複整批拒絕（不錯位回填）。
        val dup = CompareLiveWiring.buildRequest(
            "canon",
            listOf(
                CompareLiveWiring.CompareOfferingInput(
                    offeringId = "o1",
                    providerId = "p1",
                    accountId = "a1",
                    credentialId = "c1",
                    endpointId = "e1",
                    endpointUrl = "https://api.example.com/v1",
                    providerModelId = "m1",
                ),
                CompareLiveWiring.CompareOfferingInput(
                    offeringId = "o1",
                    providerId = "p2",
                    accountId = "a2",
                    credentialId = "c2",
                    endpointId = "e2",
                    endpointUrl = "https://other.example.com/v1",
                    providerModelId = "m1",
                ),
            ),
        )
        assertTrue(dup == null)
    }

    @Test
    fun compareEmptyAndErrorTextsAreHonest() {
        assertEquals(R.string.compare_empty_no_offerings, compareEmptyText(false))
        assertEquals(R.string.compare_empty_no_results, compareEmptyText(true))
        assertEquals(R.string.compare_status_cancelled, compareErrorText("CANCELLED"))
    }

    @Test
    fun detailHistorySplitSeparatesExternal() {
        val state = ModelDetailStateBuilder.build(
            canonicalId = "canon-device",
            priceState = PriceState.UNKNOWN,
            quotaState = QuotaState.UNKNOWN,
            priceHistory = listOf(
                DetailPricePoint(observedAt = 5L, sourceLabel = "official"),
            ),
            extraEvents = listOf(
                DetailHistoryEvent(7L, "EXTERNAL", "UNKNOWN", "", "external"),
            ),
        )
        val (local, external) = splitDetailHistory(state.history)
        assertEquals(1, local.size)
        assertEquals(1, external.size)
        assertEquals(R.string.value_unknown, detailPriceBadgeText(state.priceState))
        assertEquals(R.string.value_unknown, detailQuotaBadgeText(state.quotaState))
    }

    @Test
    fun filterAvailabilityReflectsRealRows() {
        val rows = listOf(
            deviceFixtureRow("a", "p1").copy(inputPrice = 1.0),
            deviceFixtureRow("b", "p2"),
        )
        val withKey = FilterAvailabilityWiring.derive(rows, hasSelectedKey = true)
        assertTrue(withKey.hasPriceData)
        assertEquals(listOf("p1", "p2"), withKey.providers)
        assertTrue(withKey.hasKeyAvailabilityData)
        assertFalse(withKey.hasCollectionData)
        val withoutKey = FilterAvailabilityWiring.derive(rows, hasSelectedKey = false)
        assertFalse(withoutKey.hasKeyAvailabilityData)
    }
}
