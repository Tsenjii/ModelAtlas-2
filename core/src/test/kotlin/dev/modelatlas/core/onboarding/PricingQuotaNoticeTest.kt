package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.RecordingTelemetry
import dev.modelatlas.core.inference.ScriptedTransport
import dev.modelatlas.core.network.CatalogProbe
import dev.modelatlas.core.newapi.NewApiAdapter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PROV-002 repair 測試：`pricingQuotaNotice` 真嘗試語意。
 *
 * Spec：`PRODUCT_SPEC.md` §5／§9／§12；`PROVIDER_ONBOARDING_CONTRACT.md` §1.2／§4／§5。
 *
 * - 假 source 矩陣：認證關卡、quota／pricing 推導、429 停手、預算扣留、無 PAT 不猜價格。
 * - 真 HTTP（MockWebServer＋真 `NewApiAdapter`＋真 `NewApiPricingQuotaNoticeSource`）：
 *   已知 adapter 面真的被打到（path 斷言），不支援（404）才 Unknown。
 * - 未接線（source null）沿用舊 placeholder：見既有 `ProbeExecutorTest.pricingNoticeStaysUnknownWithoutGuessing`。
 */
class PricingQuotaNoticeTest {
    private lateinit var server: MockWebServer
    private val credentialValue = "fixture-notice-token-must-not-leak"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.port}/v1"

    private fun dummyEngine(): InferenceEngine =
        InferenceEngine(ScriptedTransport(emptyList()), RecordingTelemetry())

    private fun idleProbe(): CatalogProbe = CatalogProbe(
        credentials = CredentialAccess { _, _ -> error("pricing path must not use the catalog probe") },
        allowLoopbackHttpForTests = true,
    )

    private fun keyedSnapshot(): OnboardingSnapshot = quickDraft().copy(baseUrl = baseUrl())

    private fun patSnapshot(): OnboardingSnapshot = quickDraft().copy(
        baseUrl = baseUrl(),
        credentials = listOf(
            SafeCredentialRef("pat-fixture", "account", CredentialRole.ACCOUNT),
            SafeCredentialRef(SAFE_CREDENTIAL_FIXTURE, "main", CredentialRole.INFERENCE),
        ),
    )

    private fun certifiedCaps(checkin: Boolean? = true) = NoticeAttempt(
        NoticeProbeOutcome.Success(
            NoticeCapabilities(true, "v1.0.0-rc.25", checkin, "500000"),
            "ev-caps",
        ),
        1,
    )

    private fun unsupportedCaps() = NoticeAttempt<NoticeCapabilities>(
        NoticeProbeOutcome.Unsupported("ev-caps-unsupported"),
        1,
    )

    private fun scripted(
        caps: NoticeAttempt<NoticeCapabilities> = certifiedCaps(),
        notice: NoticeAttempt<NoticePresence> = NoticeAttempt(NoticeProbeOutcome.Success(NoticePresence(true), "ev-notice"), 1),
        tokenQuota: NoticeAttempt<KeyQuotaObservation> = NoticeAttempt(
            NoticeProbeOutcome.Success(KeyQuotaObservation(QuotaState.QUOTA_AVAILABLE, 5000L, false), "ev-token"),
            1,
        ),
        accountQuota: NoticeAttempt<AccountQuotaObservation> = NoticeAttempt(
            NoticeProbeOutcome.Success(AccountQuotaObservation(QuotaState.QUOTA_AVAILABLE, 10000L, 4000L), "ev-account"),
            1,
        ),
        pricing: NoticeAttempt<PricingObservation> = NoticeAttempt(
            NoticeProbeOutcome.Success(PricingObservation(2, 0, 2, 0), "ev-pricing"),
            1,
        ),
        calls: MutableList<String> = mutableListOf(),
    ): PricingQuotaNoticeSource = object : PricingQuotaNoticeSource {
        override suspend fun probeCapabilities(baseUrl: String): NoticeAttempt<NoticeCapabilities> {
            calls += "capabilities"
            return caps
        }

        override suspend fun probeNotice(baseUrl: String, versionRaw: String?): NoticeAttempt<NoticePresence> {
            calls += "notice"
            return notice
        }

        override suspend fun probeTokenQuota(baseUrl: String, keyRef: String, versionRaw: String?): NoticeAttempt<KeyQuotaObservation> {
            calls += "tokenQuota:$keyRef"
            return tokenQuota
        }

        override suspend fun probeAccountQuota(baseUrl: String, patRef: String, versionRaw: String?): NoticeAttempt<AccountQuotaObservation> {
            calls += "accountQuota:$patRef"
            return accountQuota
        }

        override suspend fun probePricing(baseUrl: String, patRef: String, versionRaw: String?): NoticeAttempt<PricingObservation> {
            calls += "pricing:$patRef"
            return pricing
        }
    }

    private fun runPricing(
        source: PricingQuotaNoticeSource,
        snapshot: OnboardingSnapshot,
        requestLimit: Int = 3,
    ): StepResult {
        val executor = ProbeStepExecutor(idleProbe(), dummyEngine(), allowLoopbackHttpForTests = true, noticeSource = source)
        return runBlocking {
            executor.execute(
                snapshot,
                OnboardingStepId.PRICING_QUOTA_NOTICE,
                RequestGrant(OnboardingStepId.PRICING_QUOTA_NOTICE, 1, requestLimit, 3, false),
            )
        }
    }

    @Test
    fun uncertifiedCapabilitiesGateDeeperProbes() = runBlocking {
        val calls = mutableListOf<String>()
        val result = runPricing(scripted(caps = unsupportedCaps(), calls = calls), keyedSnapshot())

        assertEquals(listOf("capabilities"), calls)
        assertEquals(1, result.requestsSpent)
        assertEquals(OnboardingStepStatus.PARTIAL, result.status)
        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
        assertEquals(QuotaState.UNKNOWN, result.pricingQuota?.quotaStatus)
        assertEquals(HealthState.UNKNOWN, result.pricingQuota?.officialHealth)
        assertEquals(HealthState.UNKNOWN, result.pricingQuota?.observedHealth)
        assertNull(result.pricingQuota?.noticeFetched)
        assertTrue(result.evidenceIds.contains("ev-caps-unsupported"))
    }

    @Test
    fun tokenQuotaAvailableAndNoticeFlowToFinding() {
        val calls = mutableListOf<String>()
        val result = runPricing(scripted(calls = calls), keyedSnapshot())

        assertEquals(listOf("capabilities", "tokenQuota:$SAFE_CREDENTIAL_FIXTURE", "notice"), calls)
        assertEquals(3, result.requestsSpent)
        assertEquals(OnboardingStepStatus.SUCCESS, result.status)
        assertEquals(QuotaState.QUOTA_AVAILABLE, result.pricingQuota?.quotaStatus)
        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
        assertEquals(true, result.pricingQuota?.noticeFetched)
        assertEquals(true, result.pricingQuota?.requiresDailyCheckin)
        assertNull(result.pricingQuota?.checkinUrl)
        assertTrue(result.evidenceIds.contains("ev-token"))
    }

    @Test
    fun noPatMeansPricingUnsupportedWithoutGuessing() {
        val calls = mutableListOf<String>()
        val result = runPricing(scripted(calls = calls), keyedSnapshot())

        assertFalse(calls.any { it.startsWith("pricing:") })
        assertFalse(calls.any { it.startsWith("accountQuota:") })
        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
        assertTrue(result.evidenceIds.any { it.contains("no-pat") })
    }

    @Test
    fun patFlowAttemptsAccountQuotaAndPricingWithinBudget() {
        val calls = mutableListOf<String>()
        val paid = NoticeAttempt(
            NoticeProbeOutcome.Success(PricingObservation(2, 1, 1, 0), "ev-pricing"),
            1,
        )
        val result = runPricing(scripted(pricing = paid, calls = calls), patSnapshot())

        assertEquals(listOf("capabilities", "accountQuota:pat-fixture", "pricing:pat-fixture"), calls)
        assertEquals(3, result.requestsSpent)
        assertEquals(PriceStatus.VERIFIED_PAID, result.pricingQuota?.pricingStatus)
        assertEquals(EvidenceConfidence.HIGH, result.confidence)
        assertEquals(QuotaState.QUOTA_AVAILABLE, result.pricingQuota?.quotaStatus)
        assertTrue(result.evidenceIds.contains("notice-skipped-budget"))
    }

    @Test
    fun allZeroPricingDerivesVerifiedFree() {
        val free = NoticeAttempt(
            NoticeProbeOutcome.Success(PricingObservation(3, 0, 3, 0), "ev-pricing"),
            1,
        )
        val result = runPricing(scripted(pricing = free), patSnapshot())

        assertEquals(PriceStatus.VERIFIED_FREE, result.pricingQuota?.pricingStatus)
        assertEquals(EvidenceConfidence.HIGH, result.confidence)
    }

    @Test
    fun unknownPricingItemsStayUnknownInsteadOfFree() {
        val unknown = NoticeAttempt(
            NoticeProbeOutcome.Success(PricingObservation(2, 0, 0, 2), "ev-pricing"),
            1,
        )
        val result = runPricing(scripted(pricing = unknown), patSnapshot())

        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
    }

    @Test
    fun emptyPricingItemsStayUnknown() {
        val empty = NoticeAttempt(
            NoticeProbeOutcome.Success(PricingObservation(0, 0, 0, 0), "ev-pricing"),
            1,
        )
        val result = runPricing(scripted(pricing = empty), patSnapshot())

        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
    }

    @Test
    fun rateLimitedQuotaStopsFurtherAttempts() {
        val limited = NoticeAttempt<KeyQuotaObservation>(
            NoticeProbeOutcome.Failure(OnboardingFailureReason.RATE_LIMITED, "ev-token-limited", rateLimited = true),
            1,
        )
        val calls = mutableListOf<String>()
        val result = runPricing(scripted(tokenQuota = limited, calls = calls), keyedSnapshot())

        assertEquals(listOf("capabilities", "tokenQuota:$SAFE_CREDENTIAL_FIXTURE"), calls)
        assertEquals(2, result.requestsSpent)
        assertEquals(OnboardingStepStatus.PARTIAL, result.status)
        assertEquals(OnboardingFailureReason.RATE_LIMITED, result.reason)
        assertEquals(QuotaState.RATE_LIMIT_ONLY, result.pricingQuota?.quotaStatus)
        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
    }

    @Test
    fun exhaustedTokenQuotaIsPreserved() {
        val exhausted = NoticeAttempt(
            NoticeProbeOutcome.Success(KeyQuotaObservation(QuotaState.QUOTA_EXHAUSTED, 0L, false), "ev-token"),
            1,
        )
        val result = runPricing(scripted(tokenQuota = exhausted), keyedSnapshot())

        assertEquals(QuotaState.QUOTA_EXHAUSTED, result.pricingQuota?.quotaStatus)
    }

    @Test
    fun budgetExhaustionSkipsRemainingProbes() {
        val calls = mutableListOf<String>()
        val result = runPricing(scripted(calls = calls), keyedSnapshot(), requestLimit = 1)

        assertEquals(listOf("capabilities"), calls)
        assertEquals(1, result.requestsSpent)
        assertEquals(OnboardingStepStatus.PARTIAL, result.status)
        assertTrue(result.evidenceIds.any { it.contains("skipped-budget") })
    }

    @Test
    fun anonymousSnapshotMarksQuotaUnsupportedButStillTriesNotice() {
        val anonymous = quickDraft().copy(
            baseUrl = baseUrl(),
            safeCredentialIds = emptyList(),
            credentials = emptyList(),
        )
        val calls = mutableListOf<String>()
        val result = runPricing(scripted(calls = calls), anonymous)

        assertEquals(listOf("capabilities", "notice"), calls)
        assertEquals(2, result.requestsSpent)
        assertEquals(true, result.pricingQuota?.noticeFetched)
        assertEquals(QuotaState.UNKNOWN, result.pricingQuota?.quotaStatus)
    }

    // ------------------------------------------------------- real HTTP probes

    private fun realSource(): NewApiPricingQuotaNoticeSource = NewApiPricingQuotaNoticeSource(
        NewApiAdapter(
            credentials = CredentialAccess { _, _ -> credentialValue },
            allowLoopbackHttpForTests = true,
        ),
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun realNewApiSurfaceIsAttemptedInOrder() {
        server.enqueue(jsonResponse("""{"success":true,"data":{"version":"v1.0.0-rc.25","checkin_enabled":true}}"""))
        server.enqueue(jsonResponse("""{"code":true,"data":{"total_available":5000,"total_granted":10000,"total_used":5000,"unlimited_quota":false,"expires_at":0}}"""))
        server.enqueue(jsonResponse("""{"success":true,"data":{"content":"maintenance tonight"}}"""))
        val executor = ProbeStepExecutor(idleProbe(), dummyEngine(), allowLoopbackHttpForTests = true, realSource())

        val result = runBlocking {
            executor.execute(
                keyedSnapshot(),
                OnboardingStepId.PRICING_QUOTA_NOTICE,
                RequestGrant(OnboardingStepId.PRICING_QUOTA_NOTICE, 1, 3, 3, false),
            )
        }

        val paths = (1..3).map { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path }
        assertEquals(listOf("/api/status", "/api/usage/token/", "/api/notice"), paths)
        assertNull("no fourth request may be sent", server.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(3, result.requestsSpent)
        assertEquals(OnboardingStepStatus.SUCCESS, result.status)
        assertEquals(QuotaState.QUOTA_AVAILABLE, result.pricingQuota?.quotaStatus)
        assertEquals(true, result.pricingQuota?.noticeFetched)
        assertEquals(true, result.pricingQuota?.requiresDailyCheckin)
        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
        assertFalse(result.toString().contains(credentialValue))
    }

    @Test
    fun realNonNewApiSurfaceStaysUnknownAfterGenuineAttempt() {
        server.enqueue(MockResponse().setResponseCode(404))
        val executor = ProbeStepExecutor(idleProbe(), dummyEngine(), allowLoopbackHttpForTests = true, realSource())

        val result = runBlocking {
            executor.execute(
                keyedSnapshot(),
                OnboardingStepId.PRICING_QUOTA_NOTICE,
                RequestGrant(OnboardingStepId.PRICING_QUOTA_NOTICE, 1, 3, 3, false),
            )
        }

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/api/status", recorded.path)
        assertEquals(1, result.requestsSpent)
        assertEquals(OnboardingStepStatus.PARTIAL, result.status)
        assertEquals(PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
        assertEquals(QuotaState.UNKNOWN, result.pricingQuota?.quotaStatus)
        assertTrue(result.evidenceIds.contains("newapi-not-newapi-surface"))
    }
}
