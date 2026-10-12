package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.onboarding.AdapterType
import dev.modelatlas.core.onboarding.AuthDecision
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CatalogFinding
import dev.modelatlas.core.onboarding.CommitRejectedException
import dev.modelatlas.core.onboarding.CredentialTestFinding
import dev.modelatlas.core.onboarding.DetectionFinding
import dev.modelatlas.core.onboarding.EndpointRef
import dev.modelatlas.core.onboarding.EvidenceConfidence
import dev.modelatlas.core.onboarding.EvidenceSource
import dev.modelatlas.core.onboarding.ModelFinding
import dev.modelatlas.core.onboarding.ModelOrigin
import dev.modelatlas.core.onboarding.ModelVerification
import dev.modelatlas.core.onboarding.OnboardingFailureReason
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.OnboardingStepStatus
import dev.modelatlas.core.onboarding.PriceStatus
import dev.modelatlas.core.onboarding.PricingQuotaFinding
import dev.modelatlas.core.onboarding.ReviewApproval
import dev.modelatlas.core.onboarding.ReviewItem
import dev.modelatlas.core.onboarding.ReviewItems
import dev.modelatlas.core.onboarding.SafeCredentialRef
import dev.modelatlas.core.onboarding.StepResult
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Onboarding 持久化與提交測試：snapshot 整份覆寫、commit 多表落地與冪等、拒絕與 guard 邊界。Fixture 只在 androidTest。 */
@RunWith(AndroidJUnit4::class)
class OnboardingStoreTest {
    private lateinit var context: Context
    private val open = mutableListOf<AtlasDatabase>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    private fun approvedSnapshot(
        workflowId: String = "wf-test-" + UUID.randomUUID(),
        models: List<String> = listOf("test-model-a", "test-model-b"),
        catalogModels: List<String> = emptyList(),
        credentials: List<SafeCredentialRef> = listOf(SafeCredentialRef("safe-test-1", "測試憑證")),
        notes: String? = null,
        approval: ReviewApproval? = ReviewApproval(true, 1_700_000_000_000L, "tester"),
    ): OnboardingSnapshot = OnboardingSnapshot(
        workflowId = workflowId,
        flowType = OnboardingFlowType.ADVANCED,
        providerName = "測試供應商",
        baseUrl = "https://api.example.com/v1",
        credentials = credentials,
        safeCredentialIds = credentials.map { it.safeCredentialId },
        endpointProfiles = listOf(
            EndpointRef(
                EndpointType.CHAT_COMPLETIONS,
                "https://api.example.com/v1/chat/completions",
                "CHAT_COMPLETIONS",
            ),
        ),
        manualModelIds = models,
        catalog = if (catalogModels.isEmpty()) {
            null
        } else {
            CatalogFinding(
                supported = true,
                models = catalogModels.map {
                    ModelFinding(it, ModelOrigin.CATALOG, ModelVerification.UNVERIFIED)
                },
            )
        },
        notes = notes,
        reviewApproval = approval,
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_000_001L,
    )

    @Test
    fun snapshotRoundTrip_preservesEqualityAndMissingLoadsNull() = runBlocking {
        val db = memoryDb()
        val store = RoomWorkflowStore(db)
        val snapshot = approvedSnapshot().copy(
            detection = DetectionFinding(AdapterType.OPENAI_COMPATIBLE, EvidenceConfidence.LOW, "ev-detect"),
            authDecision = AuthDecision(AuthMode.BEARER, true, listOf("safe-test-1")),
            credentialTest = CredentialTestFinding(httpStatus = 200, reachable = true, anonymous = false),
            pricingQuota = PricingQuotaFinding(
                PriceStatus.UNKNOWN,
                QuotaState.UNKNOWN,
                HealthState.UNKNOWN,
                HealthState.UNKNOWN,
            ),
            reviewItems = ReviewItems(
                providerType = AdapterType.OPENAI_COMPATIBLE,
                modelsFound = 1,
                modelsVerified = 1,
                modelsVerifiedIds = listOf("test-model-a"),
                quotaStatus = QuotaState.UNKNOWN,
                confidences = mapOf(ReviewItem.PROVIDER_TYPE to EvidenceConfidence.LOW),
            ),
            results = listOf(
                StepResult(
                    step = OnboardingStepId.INSPECT,
                    status = OnboardingStepStatus.SUCCESS,
                    attempt = 1,
                    evidenceIds = listOf("ev-inspect"),
                    confidence = EvidenceConfidence.HIGH,
                    requestsSpent = 1,
                    requestLimit = 1,
                    updatedAtMillis = 1_700_000_000_002L,
                ),
                StepResult(
                    step = OnboardingStepId.LIST_MODELS,
                    status = OnboardingStepStatus.PARTIAL,
                    attempt = 1,
                    confidence = EvidenceConfidence.LOW,
                    requestsSpent = 1,
                    requestLimit = 1,
                    updatedAtMillis = 1_700_000_000_003L,
                ),
            ),
        )
        store.save(snapshot)
        assertEquals(snapshot, store.load(snapshot.workflowId))
        // 整份覆寫：第二次保存取代，不留舊版本。
        val updated = snapshot.copy(updatedAtMillis = 1_700_000_000_009L)
        store.save(updated)
        assertEquals(updated, store.load(snapshot.workflowId))
        assertNull(store.load("wf-missing"))
    }

    @Test
    fun commit_writesAllTables_andReplayReturnsSameReceipt() = runBlocking {
        val db = memoryDb()
        val store = RoomWorkflowStore(db)
        val committer = RoomCommitter(db)
        val workflowId = "wf-commit-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("test-model-a", "test-model-b"),
            catalogModels = listOf("catalog-model-c"),
            credentials = listOf(
                SafeCredentialRef("safe-test-1", "測試憑證一"),
                SafeCredentialRef("safe-test-2", "測試憑證二"),
            ),
        )
        store.save(snapshot)
        val receipt = committer.commit(store.load(workflowId)!!)
        assertEquals("commit-$workflowId", receipt.commitId)
        assertNotNull(receipt.providerId)
        val providerId = receipt.providerId!!

        // 3 模型 × 2 憑證 = 6 筆 availability 投影。
        val rows = AtlasRepository(db).managementConnections.first()
        assertEquals(6, rows.size)
        assertEquals(setOf(providerId), rows.map { it.providerId }.toSet())
        assertTrue(rows.all { it.priceState == "UNKNOWN" && it.quotaState == "UNKNOWN" })
        // 單一 endpoint，多模型各一 offering。
        assertEquals(1, rows.map { it.endpointId }.toSet().size)
        assertEquals(3, rows.map { it.offeringId }.toSet().size)
        for (offeringId in rows.map { it.offeringId }.toSet()) {
            val offering = db.offeringDao().getById(offeringId)
            assertNotNull(offering)
            assertEquals("ONBOARDING", offering?.source)
            val expectedManual = offering?.providerModelId in setOf("test-model-a", "test-model-b")
            assertEquals(expectedManual, offering?.manual)
        }
        assertNotNull(db.endpointDao().getById(rows.first().endpointId))
        val provider = db.providerDao().getById(providerId)
        assertEquals("測試供應商", provider?.name)
        assertNull(provider?.checkinUrl)
        assertEquals(false, provider?.requiresDailyCheckin)
        assertEquals(1, db.accountDao().byProvider(providerId).size)
        assertEquals("測試憑證一", db.credentialDao().getById("safe-test-1")?.label)
        assertEquals("測試憑證二", db.credentialDao().getById("safe-test-2")?.label)
        assertNotNull(db.onboardingCommitDao().getByWorkflow(workflowId))

        // 冪等重放：同 receipt，不建第二個 provider。
        val replayed = committer.commit(snapshot)
        assertEquals(receipt, replayed)
        val after = AtlasRepository(db).managementConnections.first()
        assertEquals(6, after.size)
        assertEquals(setOf(providerId), after.map { it.providerId }.toSet())
    }

    @Test
    fun commit_withoutApprovalOrModels_rejectedAndWritesNothing() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        try {
            committer.commit(approvedSnapshot(approval = null))
            fail("expected review rejection")
        } catch (e: CommitRejectedException) {
            assertEquals(listOf(OnboardingFailureReason.REVIEW_NOT_APPROVED), e.reasons)
        }
        try {
            committer.commit(
                approvedSnapshot(
                    workflowId = "wf-declined",
                    approval = ReviewApproval(false, 1_700_000_000_000L, "tester"),
                ),
            )
            fail("expected declined approval rejection")
        } catch (e: CommitRejectedException) {
            assertEquals(listOf(OnboardingFailureReason.REVIEW_NOT_APPROVED), e.reasons)
        }
        try {
            committer.commit(approvedSnapshot(workflowId = "wf-nomodel", models = emptyList()))
            fail("expected no-models rejection")
        } catch (e: CommitRejectedException) {
            assertEquals(listOf(OnboardingFailureReason.NO_MODELS_TO_TEST), e.reasons)
        }
        assertTrue(AtlasRepository(db).managementConnections.first().isEmpty())
        assertNull(db.onboardingCommitDao().getByWorkflow("wf-nomodel"))
    }

    @Test
    fun save_withSecretWords_rejectedAndLoadsNull() = runBlocking {
        val db = memoryDb()
        val store = RoomWorkflowStore(db)
        val leaky = approvedSnapshot(
            notes = "備註含 secret: apiKey=sk-test-leak-0123456789",
        )
        try {
            store.save(leaky)
            fail("expected secret guard")
        } catch (e: OnboardingSecretRefusedException) {
            assertFalse((e.message ?: "").contains("sk-test-leak"))
        }
        assertNull(store.load(leaky.workflowId))
        assertNull(db.onboardingSnapshotDao().getByWorkflow(leaky.workflowId))
    }

    @Test
    fun commit_crossAccountCredential_rollsBackWithoutPartialRows() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(
            ConnectionRecord(
                id = "seed-av",
                providerId = "seed-p",
                accountId = "seed-a",
                credentialId = "safe-collide",
                endpointId = "seed-e",
                name = "Seed",
                baseUrl = "https://seed.example.com/v1",
                endpointUrl = "https://seed.example.com/v1/chat/completions",
                modelId = "seed-model",
            ),
        )
        val committer = RoomCommitter(db)
        // 同一 vault ref 已綁他帳號：transaction 內拒絕，provider/account 不得殘留。
        val colliding = approvedSnapshot(
            credentials = listOf(SafeCredentialRef("safe-collide", "新憑證")),
        )
        try {
            committer.commit(colliding)
            fail("expected cross-account reject")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("Cross-account"))
        }
        assertNull(db.onboardingCommitDao().getByWorkflow(colliding.workflowId))
        val rows = repo.managementConnections.first()
        assertEquals(1, rows.size)
        assertEquals("seed-p", rows[0].providerId)
        assertEquals("主要憑證", db.credentialDao().getById("safe-collide")?.label)
    }
}
