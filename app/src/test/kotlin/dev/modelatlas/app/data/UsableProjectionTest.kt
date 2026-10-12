package dev.modelatlas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MODEL-007 JVM 單測（fixture 僅存於 test sourceSet，不當產品資料；無 secret、無網路/DB）。
 */
class UsableProjectionTest {

    private fun offering(
        id: String = "off-1",
        endpointId: String = "e1",
        modelId: String = "m1",
        source: String = "MANUAL",
        operation: String = "CHAT_COMPLETIONS",
    ) = OfferingInput(
        id = id,
        providerId = "p1",
        endpointId = endpointId,
        providerModelId = modelId,
        manual = true,
        source = source,
        operation = operation,
    )

    /**
     * MAJOR3 fail-closed 後：helper 預設帶具體 endpoint/operation 明確值；
     * null（未知 scope）只在專門的未知列測試中顯式傳入，不再靠預設透傳。
     */
    private fun availability(
        id: String = "av-1",
        offeringId: String = "off-1",
        accountId: String = "a1",
        credentialId: String = "c1",
        group: String? = null,
        endpointId: String? = "e1",
        operation: String? = "CHAT_COMPLETIONS",
        priceState: String = "FREE_NOW",
        quotaState: String = "QUOTA_AVAILABLE",
    ) = AvailabilityInput(
        id = id,
        offeringId = offeringId,
        accountId = accountId,
        credentialId = credentialId,
        group = group,
        endpointId = endpointId,
        operation = operation,
        priceState = priceState,
        quotaState = quotaState,
    )

    @Test
    fun picksSelectedCredentialAvailability() {
        val rows = UsableProjection.usableOfferings(
            offerings = listOf(offering()),
            availabilities = listOf(availability()),
            selectedAccountId = "a1",
            selectedCredentialId = "c1",
            selectedGroup = null,
        )
        assertEquals(1, rows.size)
        assertEquals("FREE_NOW", rows[0].priceState)
        assertEquals("QUOTA_AVAILABLE", rows[0].quotaState)
        assertTrue(rows[0].hasAvailability)
        assertEquals("MANUAL", rows[0].sourceLabel)
    }

    @Test
    fun switchCredential_isolates() {
        val availabilities = listOf(
            availability(id = "av-1", credentialId = "c1", priceState = "FREE_NOW"),
            availability(id = "av-2", credentialId = "c2", priceState = "PAID"),
        )
        val forC1 = UsableProjection.usableOfferings(
            listOf(offering()), availabilities, "a1", "c1", null,
        )
        val forC2 = UsableProjection.usableOfferings(
            listOf(offering()), availabilities, "a1", "c2", null,
        )
        assertEquals("FREE_NOW", forC1[0].priceState)
        assertEquals("PAID", forC2[0].priceState)
    }

    @Test
    fun switchGroup_isolates() {
        val availabilities = listOf(
            availability(id = "av-g1", group = "default", priceState = "FREE_NOW"),
            availability(id = "av-g2", group = "vip", priceState = "PAID"),
        )
        val forDefault = UsableProjection.usableOfferings(
            listOf(offering()), availabilities, "a1", "c1", "default",
        )
        val forVip = UsableProjection.usableOfferings(
            listOf(offering()), availabilities, "a1", "c1", "vip",
        )
        assertEquals("FREE_NOW", forDefault[0].priceState)
        assertEquals("PAID", forVip[0].priceState)
        assertEquals("default", forDefault[0].group)
        assertEquals("vip", forVip[0].group)
    }

    @Test
    fun switchAccount_isolates() {
        val availabilities = listOf(
            availability(id = "av-a1", accountId = "a1", quotaState = "QUOTA_AVAILABLE"),
            availability(id = "av-a2", accountId = "a2", quotaState = "QUOTA_EXHAUSTED"),
        )
        val forA1 = UsableProjection.usableOfferings(
            listOf(offering()), availabilities, "a1", "c1", null,
        )
        val forA2 = UsableProjection.usableOfferings(
            listOf(offering()), availabilities, "a2", "c1", null,
        )
        assertEquals("QUOTA_AVAILABLE", forA1[0].quotaState)
        assertEquals("QUOTA_EXHAUSTED", forA2[0].quotaState)
    }

    @Test
    fun noAvailability_marksUnknownButKeepsRow() {
        val rows = UsableProjection.usableOfferings(
            offerings = listOf(offering(), offering(id = "off-2", modelId = "m2")),
            availabilities = listOf(availability()),
            selectedAccountId = "a1",
            selectedCredentialId = "c-other",
            selectedGroup = null,
        )
        assertEquals(2, rows.size)
        assertEquals("UNKNOWN", rows[0].priceState)
        assertEquals("UNKNOWN", rows[0].quotaState)
        assertFalse(rows[0].hasAvailability)
        assertFalse(rows[1].hasAvailability)
    }

    @Test
    fun emptyOfferings_returnsEmpty() {
        val rows = UsableProjection.usableOfferings(
            offerings = emptyList(),
            availabilities = listOf(availability()),
            selectedAccountId = "a1",
            selectedCredentialId = "c1",
            selectedGroup = null,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun emptyAvailabilities_allUnknownNoCrash() {
        val rows = UsableProjection.usableOfferings(
            offerings = listOf(offering()),
            availabilities = emptyList(),
            selectedAccountId = "a1",
            selectedCredentialId = "c1",
            selectedGroup = null,
        )
        assertEquals(1, rows.size)
        assertEquals("UNKNOWN", rows[0].priceState)
        assertEquals("UNKNOWN", rows[0].quotaState)
    }

    @Test
    fun conflictingStates_collapseToUnknown() {
        val availabilities = listOf(
            availability(id = "av-1", priceState = "FREE_NOW"),
            availability(id = "av-2", priceState = "PAID"),
        )
        val rows = UsableProjection.usableOfferings(
            listOf(offering()), availabilities, "a1", "c1", null,
        )
        assertEquals("UNKNOWN", rows[0].priceState)
        assertTrue(rows[0].hasAvailability)
    }

    @Test
    fun blankStates_normalizedToUnknown_neverFree() {
        val rows = UsableProjection.usableOfferings(
            listOf(offering()),
            listOf(availability(priceState = "  ", quotaState = "bogus")),
            "a1", "c1", null,
        )
        assertEquals("UNKNOWN", rows[0].priceState)
        assertEquals("UNKNOWN", rows[0].quotaState)
        assertFalse(rows[0].priceState == "FREE_NOW" || rows[0].priceState == "0")
    }

    @Test
    fun sourceLabel_preservesManual_neverOfficial() {
        val rows = UsableProjection.usableOfferings(
            listOf(offering(source = "MANUAL")),
            listOf(availability()),
            "a1", "c1", null,
        )
        assertEquals("MANUAL", rows[0].sourceLabel)
        assertFalse(rows[0].sourceLabel == "OFFICIAL")
    }

    @Test
    fun endpointMismatch_ignored() {
        val rows = UsableProjection.usableOfferings(
            listOf(offering(endpointId = "e1")),
            listOf(availability().copy(endpointId = "e-other", priceState = "PAID")),
            "a1", "c1", null,
        )
        assertEquals("UNKNOWN", rows[0].priceState)
        assertFalse(rows[0].hasAvailability)
    }

    @Test
    fun operationMismatch_ignored() {
        val rows = UsableProjection.usableOfferings(
            listOf(offering().copy(operation = "CHAT_COMPLETIONS")),
            listOf(availability(priceState = "PAID").copy(operation = "EMBEDDINGS")),
            "a1", "c1", null,
        )
        assertEquals("UNKNOWN", rows[0].priceState)
        assertFalse(rows[0].hasAvailability)
    }

    // MAJOR3：null endpoint 不匹配具體 offering（不跨 endpoint 污染）。
    @Test
    fun nullEndpointAgainstConcrete_noMatch() {
        val rows = UsableProjection.usableOfferings(
            offerings = listOf(offering(endpointId = "e1")),
            availabilities = listOf(availability(endpointId = null, priceState = "PAID")),
            selectedAccountId = "a1",
            selectedCredentialId = "c1",
            selectedGroup = null,
        )
        assertEquals("UNKNOWN", rows[0].priceState)
        assertFalse(rows[0].hasAvailability)
    }

    // MAJOR3：null endpoint 僅匹配 offering 端同為未知的 legacy 同列。
    @Test
    fun nullEndpointMatchesUnknownOffering() {
        val rows = UsableProjection.usableOfferings(
            offerings = listOf(offering(endpointId = "UNKNOWN")),
            availabilities = listOf(availability(endpointId = null, priceState = "PAID")),
            selectedAccountId = "a1",
            selectedCredentialId = "c1",
            selectedGroup = null,
        )
        assertEquals("PAID", rows[0].priceState)
        assertTrue(rows[0].hasAvailability)
    }

    // MAJOR3：null operation 不匹配具體 offering（不跨 operation 污染）。
    @Test
    fun nullOperationAgainstConcrete_noMatch() {
        val rows = UsableProjection.usableOfferings(
            offerings = listOf(offering(operation = "CHAT_COMPLETIONS")),
            availabilities = listOf(availability(operation = null, priceState = "PAID")),
            selectedAccountId = "a1",
            selectedCredentialId = "c1",
            selectedGroup = null,
        )
        assertEquals("UNKNOWN", rows[0].priceState)
        assertFalse(rows[0].hasAvailability)
    }

    // MAJOR3：null operation 僅匹配 offering 端同為未知的 legacy 同列。
    @Test
    fun nullOperationMatchesUnknownOffering() {
        val rows = UsableProjection.usableOfferings(
            offerings = listOf(offering(operation = "UNKNOWN")),
            availabilities = listOf(availability(operation = null, priceState = "PAID")),
            selectedAccountId = "a1",
            selectedCredentialId = "c1",
            selectedGroup = null,
        )
        assertEquals("PAID", rows[0].priceState)
        assertTrue(rows[0].hasAvailability)
    }

    // MINOR2：任意來源字串不透傳，一律回 UNKNOWN。
    @Test
    fun sourceLabel_unknownString_mapsToUnknown() {
        val rows = UsableProjection.usableOfferings(
            listOf(offering(source = "SOME_VENDOR_FEED")),
            listOf(availability()),
            "a1", "c1", null,
        )
        assertEquals("UNKNOWN", rows[0].sourceLabel)
    }

    // MINOR2：白名單來源原樣保留。
    @Test
    fun sourceLabel_whitelistPassthrough() {
        val allowed = listOf("MANUAL", "USER", "OFFICIAL", "OBSERVED", "EXTERNAL")
        for (label in allowed) {
            val rows = UsableProjection.usableOfferings(
                listOf(offering(source = label)),
                listOf(availability()),
                "a1", "c1", null,
            )
            assertEquals(label, rows[0].sourceLabel)
        }
    }

    // MINOR2：空白來源＋非 manual 回 UNKNOWN（不猜來源）。
    @Test
    fun sourceLabel_blankNonManual_unknown() {
        val rows = UsableProjection.usableOfferings(
            listOf(offering(source = "  ", operation = "CHAT_COMPLETIONS").copy()),
            listOf(availability()),
            "a1", "c1", null,
        )
        // offering() 預設 manual=true；此列顯式關 manual 驗證非 manual 分支。
        val nonManual = OfferingInput(
            id = "off-1",
            providerId = "p1",
            endpointId = "e1",
            providerModelId = "m1",
            manual = false,
            source = "  ",
            operation = "CHAT_COMPLETIONS",
        )
        val rows2 = UsableProjection.usableOfferings(
            listOf(nonManual),
            listOf(availability()),
            "a1", "c1", null,
        )
        assertEquals("UNKNOWN", rows2[0].sourceLabel)
        // manual 空白仍回 MANUAL（既有語意保留）。
        assertEquals("MANUAL", rows[0].sourceLabel)
    }

    @Test
    fun compositeKey_groupingProof() {
        val availabilities = listOf(
            availability(id = "av-1", credentialId = "c1", group = null),
            availability(id = "av-2", credentialId = "c2", group = null),
            availability(id = "av-3", credentialId = "c1", group = "vip"),
        )
        val grouped = UsableProjection.groupBySelectedKey(availabilities)
        assertEquals(3, grouped.size)
        val keys = grouped.keys
        assertEquals(3, keys.distinct().size)
        assertTrue(grouped.values.all { it.size == 1 })
    }

    // MINOR3：同 selected key 下不同 offering 列不合併。
    @Test
    fun groupKey_includesOfferingId() {
        val availabilities = listOf(
            availability(id = "av-1", offeringId = "off-1"),
            availability(id = "av-2", offeringId = "off-2"),
        )
        val grouped = UsableProjection.groupBySelectedKey(availabilities)
        assertEquals(2, grouped.size)
        assertTrue(grouped.values.all { it.size == 1 })
    }

    // MINOR3：全同鍵同 offering 列合併為一組。
    @Test
    fun groupKey_identicalRows_shareGroup() {
        val availabilities = listOf(
            availability(id = "av-1", offeringId = "off-1"),
            availability(id = "av-2", offeringId = "off-1"),
        )
        val grouped = UsableProjection.groupBySelectedKey(availabilities)
        assertEquals(1, grouped.size)
        assertEquals(2, grouped.values.first().size)
    }
}
