package dev.modelatlas.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-004 B1/B2：price／quota head 精確 scope 比對（spec-cr004 vectors 1–15）。
 *
 * - matchesPriceHead：group raw exact（null==null；null ≠ "" ≠ 具體值）、operation exact
 *   （未知 scope 只配未知 scope）、blank endpoint 收緊（blank 不再繞過）。
 * - matchingQuotaHeads：token-over-account tier 不變；tier 內 declared global pool
 *   passport（modelId==""＋endpoint 未知＋operation 未知）vs 非 pool fail-closed exact。
 * - K3 guard：head state 衝突仍 fail-closed UNKNOWN，不發明 source precedence。
 * fixture 只存在本 test sourceSet，以 `*Fixture` 明示，不作產品資料。
 */
@RunWith(AndroidJUnit4::class)
class ModelRowMapperHeadScopeTest {

    private fun offeringFixture(
        id: String = "o1",
        providerId: String = "p1",
        modelId: String = "model-b",
        endpointId: String = "e1",
        operation: String = "CHAT_COMPLETIONS",
    ) = OfferingRef(
        id = id,
        providerId = providerId,
        endpointId = endpointId,
        providerModelId = modelId,
        operation = operation,
        manual = true,
        source = "MANUAL",
    )

    private fun priceHeadFixture(
        providerId: String = "p1",
        accountId: String? = "a1",
        credentialId: String? = null,
        modelId: String = "model-b",
        group: String? = null,
        endpointId: String? = "e1",
        operation: String? = "CHAT_COMPLETIONS",
        state: String = "FREE_NOW",
    ) = PriceQuotaHead(
        kind = ModelRowMapper.KIND_PRICE,
        providerId = providerId,
        accountId = accountId,
        credentialId = credentialId,
        modelId = modelId,
        group = group,
        endpointId = endpointId,
        operation = operation,
        state = state,
    )

    private fun quotaHeadFixture(
        kind: String = ModelRowMapper.KIND_ACCOUNT_QUOTA,
        providerId: String = "p1",
        accountId: String? = "a1",
        credentialId: String? = null,
        modelId: String = "",
        group: String? = null,
        endpointId: String? = null,
        operation: String? = "UNKNOWN",
        state: String = "QUOTA_AVAILABLE",
    ) = PriceQuotaHead(
        kind = kind,
        providerId = providerId,
        accountId = accountId,
        credentialId = credentialId,
        modelId = modelId,
        group = group,
        endpointId = endpointId,
        operation = operation,
        state = state,
    )

    private fun selectedFixture(
        accountId: String = "a1",
        credentialId: String = "",
        group: String? = null,
    ) = SelectedKey(accountId, credentialId, group)

    private fun priceStateOf(vararg heads: PriceQuotaHead, offering: OfferingRef = offeringFixture(), selected: SelectedKey = selectedFixture()): PriceState =
        ModelRowMapper.build(
            offerings = listOf(offering),
            availabilities = emptyList(),
            selected = selected,
            priceQuotaHeads = heads.toList(),
        ).single().priceState

    private fun quotaStateOf(vararg heads: PriceQuotaHead, offering: OfferingRef = offeringFixture(), selected: SelectedKey = selectedFixture()): QuotaState =
        ModelRowMapper.build(
            offerings = listOf(offering),
            availabilities = emptyList(),
            selected = selected,
            priceQuotaHeads = heads.toList(),
        ).single().quotaState

    // ---- vector 1：foreign group price head 不再投影 FREE ----

    @Test
    fun foreignGroupPriceHeadDoesNotProjectFree() {
        val selected = selectedFixture(group = "vip")
        assertEquals(
            PriceState.UNKNOWN,
            priceStateOf(priceHeadFixture(group = "free", state = "FREE_NOW"), selected = selected),
        )
        assertEquals(
            PriceState.PAID,
            priceStateOf(priceHeadFixture(group = "vip", state = "PAID"), selected = selected),
        )
    }

    // ---- vector 2：null selected.group 只命中 null-group head ----

    @Test
    fun nullGroupSelectedOnlyMatchesNullGroupHead() {
        val selected = selectedFixture(group = null)
        assertEquals(
            PriceState.UNKNOWN,
            priceStateOf(priceHeadFixture(group = "vip", state = "FREE_NOW"), selected = selected),
        )
        assertEquals(
            PriceState.PAID,
            priceStateOf(priceHeadFixture(group = null, state = "PAID"), selected = selected),
        )
    }

    // ---- vector 3：positive control（同 group 仍匹配） ----

    @Test
    fun sameGroupPriceHeadStillMatches() {
        assertEquals(
            PriceState.FREE_NOW,
            priceStateOf(
                priceHeadFixture(group = "vip", state = "FREE_NOW"),
                selected = selectedFixture(group = "vip"),
            ),
        )
    }

    // ---- vector 4：operation mismatch 排除；分歧不再被 UNKNOWN 掩蓋 ----

    @Test
    fun operationMismatchExcluded() {
        val offering = offeringFixture(operation = "B")
        assertEquals(
            PriceState.UNKNOWN,
            priceStateOf(priceHeadFixture(operation = "A", state = "FREE_NOW"), offering = offering),
        )
        assertEquals(
            PriceState.PAID,
            priceStateOf(
                priceHeadFixture(operation = "A", state = "FREE_NOW"),
                priceHeadFixture(operation = "B", state = "PAID"),
                offering = offering,
            ),
        )
    }

    // ---- vector 5：unknown-scope head operation 只配未知 scope offering ----

    @Test
    fun unknownOperationHeadOnlyMatchesUnknownScopeOffering() {
        assertEquals(
            PriceState.UNKNOWN,
            priceStateOf(
                priceHeadFixture(operation = null, state = "FREE_NOW"),
                offering = offeringFixture(operation = "CHAT_COMPLETIONS"),
            ),
        )
        assertEquals(
            PriceState.FREE_NOW,
            priceStateOf(
                priceHeadFixture(operation = null, state = "FREE_NOW"),
                offering = offeringFixture(operation = "UNKNOWN"),
            ),
        )
        assertEquals(
            PriceState.UNKNOWN,
            priceStateOf(
                priceHeadFixture(operation = "UNKNOWN", state = "FREE_NOW"),
                offering = offeringFixture(operation = "B"),
            ),
        )
    }

    // ---- vector 6：blank head endpoint 不再繞過具體 endpoint 比對 ----

    @Test
    fun blankEndpointHeadNoLongerBypasses() {
        assertEquals(
            PriceState.UNKNOWN,
            priceStateOf(priceHeadFixture(endpointId = "", state = "FREE_NOW")),
        )
        assertEquals(
            PriceState.FREE_NOW,
            priceStateOf(priceHeadFixture(endpointId = "e1", state = "FREE_NOW")),
        )
    }

    // ---- vector 7：null head endpoint（legacy unknown）只配未知 scope offering endpoint ----

    @Test
    fun nullEndpointHeadLegacyUnknownOnly() {
        assertEquals(
            PriceState.UNKNOWN,
            priceStateOf(
                priceHeadFixture(endpointId = null, state = "FREE_NOW"),
                offering = offeringFixture(endpointId = "e1"),
            ),
        )
        assertEquals(
            PriceState.FREE_NOW,
            priceStateOf(
                priceHeadFixture(endpointId = null, state = "FREE_NOW"),
                offering = offeringFixture(endpointId = ""),
            ),
        )
    }

    // ---- vector 8：token-over-account tier 保留；account-only fallback 仍可用 ----

    @Test
    fun tokenTierStillWinsOverAccount() {
        val tokenHead = quotaHeadFixture(
            kind = ModelRowMapper.KIND_TOKEN_QUOTA,
            credentialId = "k1",
            state = "QUOTA_LOW",
        )
        val accountHead = quotaHeadFixture(state = "QUOTA_AVAILABLE")
        val selected = selectedFixture(credentialId = "k1")
        assertEquals(
            QuotaState.QUOTA_LOW,
            quotaStateOf(tokenHead, accountHead, selected = selected),
        )
        assertEquals(
            QuotaState.QUOTA_AVAILABLE,
            quotaStateOf(accountHead, selected = selected),
        )
    }

    // ---- vector 9：declared account pool 護照匹配具體 offering（兩 tier 皆存活） ----

    @Test
    fun declaredAccountPoolMatchesConcreteOffering() {
        val accountPool = quotaHeadFixture(state = "QUOTA_AVAILABLE")
        val tokenPool = quotaHeadFixture(
            kind = ModelRowMapper.KIND_TOKEN_QUOTA,
            credentialId = "k1",
            state = "QUOTA_LOW",
        )
        val offering = offeringFixture()
        assertEquals(
            QuotaState.QUOTA_AVAILABLE,
            quotaStateOf(accountPool, offering = offering),
        )
        assertEquals(
            QuotaState.QUOTA_LOW,
            quotaStateOf(tokenPool, offering = offering, selected = selectedFixture(credentialId = "k1")),
        )
    }

    // ---- vector 10：per-model quota head 精確比對（Gemini/Groq 跨 model 污染回歸） ----

    @Test
    fun concreteModelQuotaHeadExact() {
        val offering = offeringFixture(modelId = "model-b")
        val wrongModel = quotaHeadFixture(
            modelId = "model-a",
            group = null,
            endpointId = "e1",
            operation = "CHAT_COMPLETIONS",
            state = "QUOTA_AVAILABLE",
        )
        val sameModel = wrongModel.copy(modelId = "model-b", state = "QUOTA_LOW")
        assertEquals(
            QuotaState.UNKNOWN,
            quotaStateOf(wrongModel, offering = offering),
        )
        assertEquals(
            QuotaState.QUOTA_LOW,
            quotaStateOf(sameModel, offering = offering),
        )
    }

    // ---- vector 11：null modelId 非 wildcard（fail-closed 排除） ----

    @Test
    fun nullModelIdQuotaHeadNotWildcard() {
        // PriceQuotaHead.modelId 是非 null String（loader 端 null→""，屬 CR-010 邊界；
        // 本測試以反射在 storage 層注入 null，鎖定「null modelId 不得當 wildcard」的
        // 防禦規則：非 pool passport 一律 fail-closed 排除）。
        val degraded = quotaHeadFixture(modelId = "", endpointId = "e1", operation = "CHAT_COMPLETIONS")
        val field = PriceQuotaHead::class.java.getDeclaredField("modelId")
        field.isAccessible = true
        field.set(degraded, null)
        assertNull(field.get(degraded))
        assertEquals(
            QuotaState.UNKNOWN,
            quotaStateOf(degraded),
        )
    }

    // ---- vector 12：group-dimensioned pool 精確；null-group pool 表 account-wide ----

    @Test
    fun groupDimensionedPoolExact() {
        val vipPool = quotaHeadFixture(modelId = "", group = "vip", state = "QUOTA_LOW")
        assertEquals(
            QuotaState.QUOTA_LOW,
            quotaStateOf(vipPool, selected = selectedFixture(group = "vip")),
        )
        assertEquals(
            QuotaState.UNKNOWN,
            quotaStateOf(vipPool, selected = selectedFixture(group = null)),
        )
        val accountWidePool = quotaHeadFixture(modelId = "", group = null, state = "QUOTA_AVAILABLE")
        assertEquals(
            QuotaState.QUOTA_AVAILABLE,
            quotaStateOf(accountWidePool, selected = selectedFixture(group = "vip")),
        )
    }

    // ---- vector 13：pool 護照要求 endpoint 未知＋operation 未知 ----

    @Test
    fun poolSignatureRequiresUnknownEndpointAndGlobalOperation() {
        assertEquals(
            QuotaState.UNKNOWN,
            quotaStateOf(
                quotaHeadFixture(modelId = "", endpointId = null, operation = "CHAT_COMPLETIONS", state = "QUOTA_AVAILABLE"),
            ),
        )
        assertEquals(
            QuotaState.UNKNOWN,
            quotaStateOf(
                quotaHeadFixture(modelId = "", endpointId = "e1", operation = "UNKNOWN", state = "QUOTA_AVAILABLE"),
            ),
        )
    }

    // ---- vector 14：跨 model quota 分歧不再被 UNKNOWN 掩蓋 ----

    @Test
    fun crossModelQuotaConflictNoLongerMasksAsUnknown() {
        val offering = offeringFixture(modelId = "model-b")
        val headA = quotaHeadFixture(
            modelId = "model-a",
            group = null,
            endpointId = "e1",
            operation = "CHAT_COMPLETIONS",
            state = "QUOTA_AVAILABLE",
        )
        val headB = headA.copy(modelId = "model-b", state = "QUOTA_LOW")
        assertEquals(
            QuotaState.QUOTA_LOW,
            quotaStateOf(headA, headB, offering = offering),
        )
    }

    // ---- vector 15：K3 guard——同 scope head 衝突仍 UNKNOWN，provenance 不變 ----

    @Test
    fun priceHeadConflictStillUnknown() {
        val headFree = priceHeadFixture(group = "vip", state = "FREE_NOW")
        val headPaid = headFree.copy(state = "PAID", inputPrice = 1.0, outputPrice = 2.0)
        val selected = selectedFixture(group = "vip")
        val rows = ModelRowMapper.build(
            offerings = listOf(offeringFixture()),
            availabilities = emptyList(),
            selected = selected,
            priceQuotaHeads = listOf(headFree, headPaid),
        )
        assertEquals(PriceState.UNKNOWN, rows.single().priceState)
        assertNull(rows.single().inputPrice)
        assertNull(rows.single().outputPrice)
        assertEquals("manual", rows.single().sourceLabel)
    }
}
