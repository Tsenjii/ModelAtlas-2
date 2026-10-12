package dev.modelatlas.core.newapi

import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.QuotaState
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 本檔所有字串皆為合成 fixture（safe refs / 假 hash / 假模型名），僅供邊界斷言，
// 不得當成產品資料或真實站台回應。
private const val PROVIDER_FIXTURE = "provider-fixture-01"
private const val ACCOUNT_FIXTURE = "safe-account-fixture-01"
private const val KEY_FIXTURE = "safe-key-fixture-01"
private const val ENDPOINT_FIXTURE = "endpoint-fixture-01"
private const val MODEL_FIXTURE = "fixture-model-a"
private const val TOP_HASH_FIXTURE = "fixture-top-hash-0001"
private const val ITEM_HASH_FIXTURE = "fixture-item-hash-0002"
private const val OBSERVED_FIXTURE = 1_760_000_000_000L
private const val SECRET_FIXTURE = "fixture-secret-must-never-leak-ZZ99"

private val RC25 = NewApiVersion.of(NewApiVersion.RC25)

/**
 * `NewApiDomainMapper` 純函式邊界單測：status/pricing/account/token → domain。
 *
 * 釘住：精確 decimal 不經 float、Unknown 保持 null（0 與 null 絕不混同）、
 * billing_expr 永不求值、PAT/account 與 inference key 角色分離、pricing envelope
 * variants（空清單 vs 失敗、雙 pricing_version、string ratio、`all` 展開、空 endpoint types）
 * 嚴格處理、Evidence（version/source/observedAt）完整傳遞、無 secret/raw/PII。
 */
class NewApiDomainMapperTest {
    private fun accountScope() = NewApiAccountScope(PROVIDER_FIXTURE, ACCOUNT_FIXTURE, ENDPOINT_FIXTURE)
    private fun keyScope() = NewApiKeyScope(PROVIDER_FIXTURE, KEY_FIXTURE, ENDPOINT_FIXTURE)

    private fun pricingEvidence(topVersion: String? = TOP_HASH_FIXTURE) = NewApiEvidence(
        source = "newapi/api/pricing",
        observedAt = OBSERVED_FIXTURE,
        providerVersion = NewApiVersion.RC25,
        versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
        httpStatus = 200,
        pricingVersion = topVersion,
    )

    private fun accountEvidence() = NewApiEvidence(
        source = "newapi/api/user/self",
        observedAt = OBSERVED_FIXTURE,
        providerVersion = NewApiVersion.RC25,
        versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
        httpStatus = 200,
    )

    private fun tokenEvidence() = NewApiEvidence(
        source = "newapi/api/usage/token",
        observedAt = OBSERVED_FIXTURE,
        providerVersion = NewApiVersion.RC25,
        versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
        httpStatus = 200,
    )

    private fun ratioItem(
        modelName: String = MODEL_FIXTURE,
        modelRatio: BigDecimal? = BigDecimal("1.5"),
        completionRatio: BigDecimal? = BigDecimal("2"),
        enableGroups: List<String> = listOf("default"),
        endpointTypes: List<String> = listOf("openai"),
    ) = NewApiPricingItem(
        modelName = modelName,
        quotaType = 0,
        modelRatio = modelRatio,
        modelPrice = null,
        completionRatio = completionRatio,
        cacheRatio = null,
        createCacheRatio = null,
        imageRatio = null,
        audioRatio = null,
        audioCompletionRatio = null,
        enableGroups = enableGroups,
        supportedEndpointTypes = endpointTypes,
        billingMode = null,
        billingExpression = null,
        vendorId = null,
        pricingVersion = null,
    )

    private fun pricingResult(
        items: List<NewApiPricingItem>,
        groupRatio: Map<String, NewApiRatio> = mapOf("default" to NewApiRatio(BigDecimal("1"), false)),
        usableGroups: List<String> = listOf("default"),
        topVersion: String? = TOP_HASH_FIXTURE,
    ) = NewApiResult.Success(
        pricingEvidence(topVersion),
        NewApiPricing(
            items = items,
            skippedItems = 0,
            groupRatio = groupRatio,
            usableGroups = usableGroups,
            supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
            autoGroups = emptyList(),
            pricingVersion = topVersion,
        ),
    )

    private fun assertDecimal(expected: String, actual: BigDecimal?) {
        assertTrue("expected known decimal $expected, got null", actual != null)
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    // ---- ratio 換算：精確 decimal，不經 float ----

    @Test
    fun ratioPricingConvertsUsdPer1KWithExactDecimal() {
        // 1.5 × 1 × 1000 / 500000 = 0.003；output 另乘 completion 2 → 0.006。
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(listOf(ratioItem())),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        assertEquals(1, mapped.snapshots.size)
        val snapshot = mapped.snapshots.single()
        assertDecimal("0.003", snapshot.input)
        assertDecimal("0.006", snapshot.output)
        assertNull("ratio 模式無 cache 資訊即 null，不猜 0", snapshot.cacheRead)
        assertNull(snapshot.cacheWrite)
        assertNull(snapshot.fixedRequest)
        assertTrue("ratio 模式固定費已知 absent", snapshot.fixedRequestKnownAbsent)
        // 完整 scope：PAT 角色 credential 必 null，group/operation 展開正確。
        assertEquals(PROVIDER_FIXTURE, snapshot.scope.providerId)
        assertEquals(ACCOUNT_FIXTURE, snapshot.scope.accountId)
        assertNull(snapshot.scope.credentialId)
        assertEquals(MODEL_FIXTURE, snapshot.scope.modelId)
        assertEquals("default", snapshot.scope.group)
        assertEquals(ENDPOINT_FIXTURE, snapshot.scope.endpointId)
        assertEquals("CHAT_COMPLETIONS", snapshot.scope.operation)
        // Evidence：source / observedAt / version / pricingVersion 完整傳遞。
        assertEquals(OBSERVED_FIXTURE, snapshot.observedAt)
        assertEquals("newapi/api/pricing", snapshot.source)
        assertTrue(snapshot.id.isNotBlank())
        assertTrue(snapshot.evidenceId?.isNotBlank() == true)
        assertEquals(NewApiVersion.RC25, mapped.evidence.providerVersion)
        assertEquals(TOP_HASH_FIXTURE, mapped.topPricingVersion)
        assertEquals(TOP_HASH_FIXTURE, mapped.evidence.pricingVersion)
    }

    @Test
    fun quotaPerUnitUnknownKeepsNullNotZero() {
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(listOf(ratioItem())),
            null,
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        val snapshot = mapped.snapshots.single()
        assertNull("Q 未知即 UNKNOWN，不可用預設 500000 硬塞，不猜 0", snapshot.input)
        assertNull(snapshot.output)
        assertEquals(NewApiPriceUnpricedReason.QUOTA_PER_UNIT_UNKNOWN, mapped.details.single().unpricedReason)
    }

    @Test
    fun stringGroupRatioKeepsUnknownWithoutGuessing() {
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(
                listOf(ratioItem(enableGroups = listOf("auto"))),
                groupRatio = mapOf("auto" to NewApiRatio(null, true)),
                usableGroups = listOf("auto"),
            ),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        val snapshot = mapped.snapshots.single()
        assertNull("字串 ratio（如自動群組在地化字串）不猜數值", snapshot.input)
        assertNull(snapshot.output)
        assertTrue(mapped.details.single().groupRatioNonNumeric)
        assertEquals(NewApiPriceUnpricedReason.GROUP_RATIO_NON_NUMERIC, mapped.details.single().unpricedReason)
    }

    // ---- billing_expr 永不求值 ----

    @Test
    fun tieredExprIsNeverEvaluatedEvenWhenItLooksArithmetic() {
        val item = ratioItem().copy(
            quotaType = 0,
            billingMode = "tiered_expr",
            billingExpression = "1+1",
        )
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(listOf(item)),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        val snapshot = mapped.snapshots.single()
        assertNull(snapshot.input)
        assertNull(snapshot.output)
        assertNull(snapshot.fixedRequest)
        assertFalse(snapshot.fixedRequestKnownAbsent)
        val detail = mapped.details.single()
        assertEquals(NewApiPriceUnpricedReason.BILLING_EXPR_PRESENT, detail.unpricedReason)
        assertEquals("1+1", detail.billingExpression)
        assertTrue("expr hash 必須存在以供變更偵測", detail.billingExpressionHash?.isNotBlank() == true)
    }

    // ---- quota_type=1 fixed ----

    @Test
    fun fixedPricingMapsToFixedOnlyWithNullTokenLegs() {
        val item = NewApiPricingItem(
            modelName = MODEL_FIXTURE,
            quotaType = 1,
            modelRatio = null,
            modelPrice = BigDecimal("0.01"),
            completionRatio = null,
            cacheRatio = null,
            createCacheRatio = null,
            imageRatio = null,
            audioRatio = null,
            audioCompletionRatio = null,
            enableGroups = listOf("default"),
            supportedEndpointTypes = listOf("openai"),
            billingMode = null,
            billingExpression = null,
            vendorId = null,
            pricingVersion = null,
        )
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(listOf(item)),
            // fixed 不需 Q：即使 Q 未知，fixed 仍可知。
            null,
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        val snapshot = mapped.snapshots.single()
        // 0.01 × group ratio 1（fixture 預設）= 0.01 USD/次。
        assertDecimal("0.01", snapshot.fixedRequest)
        assertFalse(snapshot.fixedRequestKnownAbsent)
        assertNull("fixed 模式 per-token 腿為 null（非 0），不足以判定即 UNKNOWN 由 Policy 判定", snapshot.input)
        assertNull(snapshot.output)
    }

    // ---- envelope：空 vs 失敗、雙版本 ----

    @Test
    fun unauthorizedPricingIsUnmappableAndDistinctFromAuthoritativeEmpty() {
        val failureEvidence = NewApiEvidence(
            source = "newapi/api/pricing",
            observedAt = OBSERVED_FIXTURE,
            providerVersion = NewApiVersion.RC25,
            versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
            httpStatus = 401,
        )
        val rejected = NewApiDomainMapper.mapPricing(
            NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED, failureEvidence),
            BigDecimal("500000"),
            accountScope(),
        )
        assertTrue(rejected is NewApiPricingMapping.Unmappable)
        assertEquals(NewApiStatus.HTTP_UNAUTHORIZED, (rejected as NewApiPricingMapping.Unmappable).status)

        val empty = NewApiDomainMapper.mapPricing(
            pricingResult(emptyList(), usableGroups = listOf("default")),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped
        assertTrue("data:[] 是官方權威空，非 UNKNOWN", empty.authoritativeEmpty)
        assertTrue(empty.snapshots.isEmpty())
        assertFalse(empty.usableGroupsEmpty)
    }

    @Test
    fun pricingVersionsTopAndItemArePreservedSeparately() {
        val item = ratioItem().copy(pricingVersion = ITEM_HASH_FIXTURE)
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(listOf(item), topVersion = TOP_HASH_FIXTURE),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        assertEquals(TOP_HASH_FIXTURE, mapped.evidence.pricingVersion)
        assertEquals(TOP_HASH_FIXTURE, mapped.topPricingVersion)
        assertEquals(ITEM_HASH_FIXTURE, mapped.details.single().itemPricingVersion)
        assertEquals(TOP_HASH_FIXTURE, mapped.details.single().topPricingVersion)
    }

    @Test
    fun enableAllExpandsToUsableGroupsWithoutLiteralAll() {
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(
                listOf(ratioItem(enableGroups = listOf("all"))),
                groupRatio = mapOf(
                    "default" to NewApiRatio(BigDecimal("1"), false),
                    "Surprise" to NewApiRatio(BigDecimal("2"), false),
                ),
                usableGroups = listOf("default", "Surprise"),
            ),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        val groups = mapped.snapshots.map { it.scope.group }.distinct().sortedBy { it }
        assertEquals(listOf("Surprise", "default"), groups)
        assertFalse("不得產出字面 group=all 的假 scope", groups.contains("all"))
        // 不同 group 即不同 scope，不互相覆蓋。
        assertEquals(2, mapped.snapshots.map { it.scope }.distinct().size)
        val surprise = mapped.snapshots.single { it.scope.group == "Surprise" }
        // Surprise group ratio 2 → input 1.5×2×1000/500000 = 0.006。
        assertDecimal("0.006", surprise.input)
    }

    @Test
    fun emptyEndpointTypesGivesUnknownOperationInsteadOfGuessingChat() {
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(listOf(ratioItem(endpointTypes = emptyList()))),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        assertEquals(1, mapped.snapshots.size)
        assertEquals("UNKNOWN", mapped.snapshots.single().scope.operation)
        assertNull(mapped.details.single().endpointTypeRaw)
        assertNull(mapped.details.single().endpointTypeMapped)
    }

    @Test
    fun endpointTypeMappingIsStrictAndCaseSensitive() {
        assertEquals(EndpointType.CHAT_COMPLETIONS, NewApiDomainMapper.mapEndpointType("openai"))
        assertEquals(EndpointType.RESPONSES, NewApiDomainMapper.mapEndpointType("openai-response"))
        assertEquals(EndpointType.RESPONSES, NewApiDomainMapper.mapEndpointType("openai-response-compact"))
        assertEquals(EndpointType.ANTHROPIC_MESSAGES, NewApiDomainMapper.mapEndpointType("anthropic"))
        assertEquals(EndpointType.GEMINI_NATIVE, NewApiDomainMapper.mapEndpointType("gemini"))
        assertEquals(EndpointType.IMAGES_GENERATION, NewApiDomainMapper.mapEndpointType("image-generation"))
        assertEquals(EndpointType.EMBEDDINGS, NewApiDomainMapper.mapEndpointType("embeddings"))
        assertEquals(EndpointType.RERANK, NewApiDomainMapper.mapEndpointType("jina-rerank"))
        // 未列一律 CUSTOM，不猜 IMAGES_EDIT / AUDIO；大小寫敏感。
        assertEquals(EndpointType.CUSTOM, NewApiDomainMapper.mapEndpointType("openai-video"))
        assertEquals(EndpointType.CUSTOM, NewApiDomainMapper.mapEndpointType("image-edit"))
        assertEquals(EndpointType.CUSTOM, NewApiDomainMapper.mapEndpointType("OpenAI"))
        assertEquals(EndpointType.CUSTOM, NewApiDomainMapper.mapEndpointType("unknown-future-type"))
    }

    // ---- account quota ----

    @Test
    fun accountQuotaKeepsNullUnknownAndZeroExhaustedWithRoleSeparation() {
        val nullMapped = NewApiDomainMapper.mapAccountQuota(
            NewApiResult.Success(accountEvidence(), NewApiAccountQuota(null, null, null, null, null, null)),
            accountScope(),
        ) as NewApiAccountQuotaMapping.Mapped
        assertEquals(QuotaState.UNKNOWN, nullMapped.snapshot.state)
        assertNull(nullMapped.detail.quota)
        assertEquals(ACCOUNT_FIXTURE, nullMapped.snapshot.scope.accountId)
        assertNull("帳號層級 credential 必 null", nullMapped.snapshot.scope.credentialId)
        assertEquals("newapi/api/user/self", nullMapped.snapshot.source)
        assertEquals(OBSERVED_FIXTURE, nullMapped.snapshot.observedAt)

        val zeroMapped = NewApiDomainMapper.mapAccountQuota(
            NewApiResult.Success(accountEvidence(), NewApiAccountQuota(0L, 10L, 5L, "default", 1, 1)),
            accountScope(),
        ) as NewApiAccountQuotaMapping.Mapped
        assertEquals(QuotaState.QUOTA_EXHAUSTED, zeroMapped.snapshot.state)
        assertEquals(0L, zeroMapped.detail.quota)
        assertEquals(10L, zeroMapped.detail.usedQuota)
        assertEquals("default", zeroMapped.snapshot.scope.group)

        val positiveMapped = NewApiDomainMapper.mapAccountQuota(
            NewApiResult.Success(accountEvidence(), NewApiAccountQuota(500000L, 100L, 3L, "default", 1, 1)),
            accountScope(),
        ) as NewApiAccountQuotaMapping.Mapped
        assertEquals(QuotaState.QUOTA_AVAILABLE, positiveMapped.snapshot.state)
    }

    // ---- token quota ----

    @Test
    fun tokenQuotaPassthroughPreservesAdapterStateAndAmbiguousExpiry() {
        val value = NewApiTokenQuota(
            totalGranted = 1000L,
            totalUsed = 400L,
            totalAvailable = 600L,
            unlimitedQuota = false,
            modelLimitsEnabled = false,
            expiresAtRaw = 0L,
            expiryMeaning = NewApiExpiryMeaning.ZERO_AMBIGUOUS,
            expired = null,
            state = QuotaState.QUOTA_AVAILABLE,
        )
        val mapped = NewApiDomainMapper.mapTokenQuota(
            NewApiResult.Success(tokenEvidence(), value),
            keyScope(),
        ) as NewApiTokenQuotaMapping.Mapped

        assertEquals(QuotaState.QUOTA_AVAILABLE, mapped.snapshot.state)
        assertEquals(KEY_FIXTURE, mapped.snapshot.scope.credentialId)
        assertNull("Key 層級 account 必 null（與 PAT 層級隔離）", mapped.snapshot.scope.accountId)
        assertEquals("newapi/api/usage/token", mapped.snapshot.source)
        assertEquals(NewApiExpiryMeaning.ZERO_AMBIGUOUS, mapped.detail.expiryMeaning)
        assertEquals(0L, mapped.detail.expiresAtRaw)
        assertNull("0 在此端點語義 ambiguous，不猜 expired", mapped.detail.expired)
        assertEquals(600L, mapped.detail.totalAvailable)
    }

    @Test
    fun quotaFailuresAreUnmappableToRetainLastGood() {
        val evidence = NewApiEvidence(
            source = "newapi/api/usage/token",
            observedAt = OBSERVED_FIXTURE,
            providerVersion = NewApiVersion.RC25,
            versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
            httpStatus = 401,
        )
        val rejected = NewApiDomainMapper.mapTokenQuota(
            NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED, evidence),
            keyScope(),
        )
        assertTrue(rejected is NewApiTokenQuotaMapping.Unmappable)

        val accountRejected = NewApiDomainMapper.mapAccountQuota(
            NewApiResult.Failure(
                NewApiStatus.HTTP_FORBIDDEN,
                accountEvidence().copy(httpStatus = 403),
            ),
            accountScope(),
        )
        assertTrue(accountRejected is NewApiAccountQuotaMapping.Unmappable)
    }

    // ---- scope 隔離與無洩漏 ----

    @Test
    fun differentGroupsAndOperationsDoNotOverwriteEachOther() {
        val item = ratioItem(
            enableGroups = listOf("default", "Surprise"),
            endpointTypes = listOf("openai", "anthropic"),
        )
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(
                listOf(item),
                groupRatio = mapOf(
                    "default" to NewApiRatio(BigDecimal("1"), false),
                    "Surprise" to NewApiRatio(BigDecimal("1"), false),
                ),
                usableGroups = listOf("default", "Surprise"),
            ),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped

        // 2 groups × 2 operations = 4 scopes，全部相異。
        assertEquals(4, mapped.snapshots.size)
        assertEquals(4, mapped.snapshots.map { it.scope }.distinct().size)
        assertEquals(4, mapped.snapshots.map { it.id }.distinct().size)
        assertTrue(mapped.snapshots.any { it.scope.operation == "CHAT_COMPLETIONS" })
        assertTrue(mapped.snapshots.any { it.scope.operation == "ANTHROPIC_MESSAGES" })
    }

    @Test
    fun mappedOutputsCarryNoSecretRawBodyOrPii() {
        val mapped = NewApiDomainMapper.mapPricing(
            pricingResult(listOf(ratioItem())),
            BigDecimal("500000"),
            accountScope(),
        ) as NewApiPricingMapping.Mapped
        val tokenMapped = NewApiDomainMapper.mapTokenQuota(
            NewApiResult.Success(
                tokenEvidence(),
                NewApiTokenQuota(1000L, 100L, 900L, false, false, null, NewApiExpiryMeaning.UNKNOWN, null, QuotaState.QUOTA_AVAILABLE),
            ),
            keyScope(),
        ) as NewApiTokenQuotaMapping.Mapped

        val rendered = mapped.toString() + tokenMapped.toString()
        assertFalse(rendered.contains(SECRET_FIXTURE))
        assertFalse(rendered.contains("sk-"))
        assertFalse(rendered.contains("Authorization"))
        // adapter 已排除 username/email；mapper 輸出更不應出現 PII 欄位名。
        assertFalse(rendered.contains("username"))
        assertFalse(rendered.contains("email"))
        // detail 的 billing hash 為 hex，不含原文以外的 secret。
        assertFalse(mapped.details.toString().contains(SECRET_FIXTURE))
    }

    @Test
    fun quotaPerUnitHelperNeverDefaults() {
        val capsEvidence = NewApiEvidence(
            source = "newapi/api/status",
            observedAt = OBSERVED_FIXTURE,
            providerVersion = NewApiVersion.RC25,
            versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
            httpStatus = 200,
        )
        val caps = NewApiCapabilities(RC25, BigDecimal("500000"), "USD", true, NewApiModuleAccess(true, true), emptyMap())
        assertEquals(0, BigDecimal("500000").compareTo(NewApiDomainMapper.quotaPerUnitOf(NewApiResult.Success(capsEvidence, caps))))

        val missing = caps.copy(quotaPerUnit = null)
        assertNull(NewApiDomainMapper.quotaPerUnitOf(NewApiResult.Success(capsEvidence, missing)))
        assertNull(
            NewApiDomainMapper.quotaPerUnitOf(
                NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED, capsEvidence),
            ),
        )
    }
}
