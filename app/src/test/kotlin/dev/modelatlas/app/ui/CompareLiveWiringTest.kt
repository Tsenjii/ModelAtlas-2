package dev.modelatlas.app.ui

import dev.modelatlas.app.R
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.models.CompareLiveRowResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 repair worker B：Compare 真接線邏輯單測（JVM，pure Kotlin）。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §8 Compare Live、§3 telemetry、§10 FREE 判定）。
 * 測試 fixture 只存在於本 test sourceSet，且以 `wiringFixture*` 明示命名，不作產品資料。
 */
class CompareLiveWiringTest {

    private fun wiringFixtureInput(
        offeringId: String,
        providerId: String = "p1",
        modelId: String = "m1",
    ) = CompareLiveWiring.CompareOfferingInput(
        offeringId = offeringId,
        providerId = providerId,
        accountId = "a1",
        credentialId = "c1",
        endpointId = "e1",
        endpointUrl = "https://api.example.com/v1",
        providerModelId = modelId,
        priceState = "UNKNOWN",
        quotaState = "UNKNOWN",
    )

    private fun wiringFixtureRow(success: Boolean, offeringId: String = "o1") =
        CompareLiveRowResult(
            offeringId = offeringId,
            providerId = "p1",
            endpointId = "e1",
            providerModelId = "m1",
            canonicalId = "canon",
            success = success,
            outcome = if (success) Outcome.SUCCESS else Outcome.RATE_LIMIT,
        )

    @Test
    fun promptIsSingleSharedNonEmpty() {
        val prompt = CompareLiveWiring.comparePrompt()
        assertTrue(prompt.isNotEmpty())
        assertTrue(prompt.all { it.role.isNotBlank() && it.content.isNotBlank() })
    }

    @Test
    fun blankCanonicalOrEmptyInputsReturnNull() {
        assertNull(CompareLiveWiring.buildRequest("", listOf(wiringFixtureInput("o1"))))
        assertNull(CompareLiveWiring.buildRequest("canon", emptyList()))
    }

    @Test
    fun blankScopeRowIsSkippedFailClosed() {
        val rows = listOf(
            wiringFixtureInput("o1"),
            wiringFixtureInput("o2").copy(endpointUrl = ""),
            wiringFixtureInput("o3").copy(providerModelId = "  "),
        )
        val req = CompareLiveWiring.buildRequest("canon", rows)
        assertNotNull(req)
        assertEquals(listOf("o1"), req!!.offerings.map { it.offeringId })
    }

    @Test
    fun allFilteredReturnsNullWithoutFakeResult() {
        val rows = listOf(wiringFixtureInput("o1").copy(endpointUrl = ""))
        assertNull(CompareLiveWiring.buildRequest("canon", rows))
    }

    @Test
    fun duplicateOfferingIdsRejectWholeBatch() {
        val rows = listOf(wiringFixtureInput("o1"), wiringFixtureInput("o1"))
        assertNull(CompareLiveWiring.buildRequest("canon", rows))
    }

    @Test
    fun capsOfferingsInInputOrder() {
        val rows = (1..10).map { wiringFixtureInput("o$it", providerId = "p$it") }
        val req = CompareLiveWiring.buildRequest("canon", rows, maxOfferings = 3)
        assertNotNull(req)
        assertEquals(listOf("o1", "o2", "o3"), req!!.offerings.map { it.offeringId })
    }

    @Test
    fun requestCarriesSharedPromptAndBudget() {
        val req = CompareLiveWiring.buildRequest("canon", listOf(wiringFixtureInput("o1")))
        assertNotNull(req)
        assertEquals(CompareLiveWiring.comparePrompt(), req!!.messages)
        assertEquals(CompareLiveWiring.COMPARE_MAX_OUTPUT_TOKENS, req.maxOutputTokens)
    }

    @Test
    fun summaryCountsSuccessAndFailure() {
        assertEquals("", CompareLiveWiring.summaryText(emptyList(), "%1\$d/%2\$d/%3\$d"))
        val rows = listOf(wiringFixtureRow(true, "o1"), wiringFixtureRow(false, "o2"))
        assertEquals("1/1/2", CompareLiveWiring.summaryText(rows, "%1\$d/%2\$d/%3\$d"))
    }

    @Test
    fun errorTextNeverCarriesRemoteMessage() {
        assertEquals(R.string.compare_status_cancelled, compareErrorText("CANCELLED"))
        assertEquals(R.string.compare_fail_auth, compareErrorText("AUTH"))
        assertEquals(R.string.compare_fail_network, compareErrorText("NETWORK"))
        assertEquals(R.string.compare_fail_generic, compareErrorText("UNKNOWN"))
        assertNull(compareErrorText(null))
    }
}
