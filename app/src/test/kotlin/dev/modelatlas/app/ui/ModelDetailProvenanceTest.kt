package dev.modelatlas.app.ui

import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 repair worker B：Detail provenance 分欄＋來源白名單單測（JVM，pure Kotlin）。
 *
 * TRACEABILITY：MODEL-009（PRODUCT_SPEC §10 provenance 分離、§11 telemetry/history）。
 * 測試 fixture 只存在於本 test sourceSet，且以 `provenanceFixture*` 明示命名，不作產品資料。
 */
class ModelDetailProvenanceTest {

    private fun provenanceFixtureEvent(source: String, section: String = "PRICE") =
        DetailHistoryEvent(
            observedAt = 10L,
            section = section,
            stateLabel = "UNKNOWN",
            sourceLabel = source,
        )

    @Test
    fun externalGoesToExternalColumnOnly() {
        val (local, external) = splitDetailHistory(
            listOf(
                provenanceFixtureEvent("official"),
                provenanceFixtureEvent("observed", "TELEMETRY"),
                provenanceFixtureEvent("manual"),
                provenanceFixtureEvent("external", "EXTERNAL"),
                provenanceFixtureEvent(""),
            ),
        )
        assertEquals(4, local.size)
        assertEquals(1, external.size)
        assertEquals("EXTERNAL", external.single().section)
    }

    @Test
    fun unknownSourceStaysLocalNeverPosesAsExternal() {
        val (local, external) = splitDetailHistory(listOf(provenanceFixtureEvent("weird-source")))
        assertEquals(1, local.size)
        assertTrue(external.isEmpty())
    }

    @Test
    fun provenanceEmptyTexts() {
        assertEquals(R.string.detail_no_local, detailProvenanceEmptyText(false))
        assertEquals(R.string.detail_no_external, detailProvenanceEmptyText(true))
    }

    @Test
    fun priceSourceWhitelist() {
        assertEquals("manual", ModelDetailLoader.priceSourceLabel("USER"))
        assertEquals("official", ModelDetailLoader.priceSourceLabel("newapi/api/pricing"))
        assertEquals("official", ModelDetailLoader.priceSourceLabel("openrouter/models"))
        assertEquals("", ModelDetailLoader.priceSourceLabel("some-blog"))
        assertEquals("", ModelDetailLoader.priceSourceLabel(""))
    }

    @Test
    fun quotaKindUnknownIsSkipped() {
        assertEquals(DetailQuotaKind.ACCOUNT_QUOTA, ModelDetailLoader.quotaKindFor("ACCOUNT_QUOTA"))
        assertEquals(
            DetailQuotaKind.DEDICATED_TOKEN_QUOTA,
            ModelDetailLoader.quotaKindFor("DEDICATED_TOKEN_QUOTA"),
        )
        assertEquals(null, ModelDetailLoader.quotaKindFor("SOME_NEW_KIND"))
    }

    @Test
    fun kindWhitelists() {
        assertTrue(ModelDetailLoader.isPriceKind("PRICE"))
        assertTrue(ModelDetailLoader.isPriceKind("MANUAL_PRICE"))
        assertTrue(!ModelDetailLoader.isPriceKind("ACCOUNT_QUOTA"))
        assertTrue(ModelDetailLoader.isQuotaKind("TOKEN_QUOTA"))
        assertTrue(!ModelDetailLoader.isQuotaKind("PRICE"))
    }

    @Test
    fun stateParsingIsFailClosed() {
        assertEquals(PriceState.FREE_NOW, ModelDetailLoader.priceStateOf(" free_now "))
        assertEquals(PriceState.UNKNOWN, ModelDetailLoader.priceStateOf("FREE-ish"))
        assertEquals(PriceState.UNKNOWN, ModelDetailLoader.priceStateOf(null))
        assertEquals(QuotaState.QUOTA_AVAILABLE, ModelDetailLoader.quotaStateOf("QUOTA_AVAILABLE"))
        assertEquals(QuotaState.UNKNOWN, ModelDetailLoader.quotaStateOf(null))
    }
}
