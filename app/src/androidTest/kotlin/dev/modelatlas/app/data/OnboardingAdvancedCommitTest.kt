package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.onboarding.AdapterType
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialLocation
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.CustomAuthFieldRef
import dev.modelatlas.core.onboarding.DetectionFinding
import dev.modelatlas.core.onboarding.EndpointRef
import dev.modelatlas.core.onboarding.EvidenceConfidence
import dev.modelatlas.core.onboarding.EvidenceSource
import dev.modelatlas.core.onboarding.ManualPricingInput
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.PricePerUnit
import dev.modelatlas.core.onboarding.PriceStatus
import dev.modelatlas.core.onboarding.PricingQuotaFinding
import dev.modelatlas.core.onboarding.ReviewApproval
import dev.modelatlas.core.onboarding.SafeCredentialRef
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

/** Advanced commit 測試：多 endpoint 笛卡爾 offering、PAT 隔離、匿名無假 key、嚴格 URL 與回滾。Fixture 只在 androidTest。 */
@RunWith(AndroidJUnit4::class)
class OnboardingAdvancedCommitTest {
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
        workflowId: String = "wf-adv-" + UUID.randomUUID(),
        baseUrl: String = "https://api.example.com/v1",
        providerName: String? = "進階供應商",
        models: List<String> = listOf("m-a", "m-b"),
        credentials: List<SafeCredentialRef> = listOf(
            SafeCredentialRef("inf-" + UUID.randomUUID(), "推理憑證", CredentialRole.INFERENCE),
        ),
        endpoints: List<EndpointRef> = listOf(
            EndpointRef(
                EndpointType.CHAT_COMPLETIONS,
                "https://api.example.com/v1/chat/completions",
                "CHAT_COMPLETIONS",
                enabled = true,
            ),
        ),
        detection: DetectionFinding? = null,
        adapterOverride: AdapterType? = null,
        checkinUrl: String? = null,
        pricingQuota: PricingQuotaFinding? = null,
        manualPricing: List<ManualPricingInput> = emptyList(),
        customFields: List<CustomAuthFieldRef> = emptyList(),
        authModeOverride: AuthMode? = null,
    ): OnboardingSnapshot = OnboardingSnapshot(
        workflowId = workflowId,
        flowType = OnboardingFlowType.ADVANCED,
        providerName = providerName,
        baseUrl = baseUrl,
        credentials = credentials,
        safeCredentialIds = credentials.map { it.safeCredentialId },
        customAuthFields = customFields,
        authModeOverride = authModeOverride,
        endpointProfiles = endpoints,
        manualModelIds = models,
        manualPricing = manualPricing,
        detection = detection,
        adapterTypeOverride = adapterOverride,
        checkinUrl = checkinUrl,
        pricingQuota = pricingQuota,
        reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "tester"),
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_000_001L,
    )

    @Test
    fun multiEndpoint_cartesianOfferings_disabledIgnored_relativeResolved() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-multi-" + UUID.randomUUID()
        val credId = "inf-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a", "m-b"),
            credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
            endpoints = listOf(
                EndpointRef(
                    EndpointType.CHAT_COMPLETIONS,
                    "https://api.example.com/v1/chat/completions",
                    "CHAT_COMPLETIONS",
                    enabled = true,
                ),
                EndpointRef(
                    EndpointType.EMBEDDINGS,
                    "embeddings",
                    "HTTPS",
                    enabled = true,
                ),
                EndpointRef(
                    EndpointType.RERANK,
                    "https://api.example.com/v1/rerank",
                    "RERANK",
                    enabled = false,
                ),
            ),
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        val rows = AtlasRepository(db).managementConnections.first()
        // 2 enabled endpoints x 2 models x 1 inference cred = 4 connections。
        assertEquals(4, rows.size)
        assertEquals(2, rows.map { it.endpointId }.toSet().size)
        assertEquals(4, rows.map { it.offeringId }.toSet().size)
        val endpointById = rows.map { it.endpointId }.toSet().associateWith {
            db.endpointDao().getById(it)!!
        }
        val urls = endpointById.values.map { it.endpointUrl }.toSet()
        assertTrue(urls.contains("https://api.example.com/v1/chat/completions"))
        assertTrue(urls.contains("https://api.example.com/v1/embeddings"))
        assertFalse(urls.any { it.contains("rerank") })
        // `HTTPS` 協議字串必須正規化為 endpointType，不可寫入非推理協議。
        val protocols = endpointById.values.map { it.protocol }.toSet()
        assertFalse(protocols.contains("HTTPS"))
        assertTrue(protocols.contains("CHAT_COMPLETIONS"))
        assertTrue(protocols.contains("EMBEDDINGS"))
        // offering 逐 endpoint x model 展開，source 固定 ONBOARDING。
        for (offeringId in rows.map { it.offeringId }.toSet()) {
            val offering = db.offeringDao().getById(offeringId)
            assertNotNull(offering)
            assertEquals("ONBOARDING", offering?.source)
        }
        // 冪等重放不增列。
        val replayed = committer.commit(snapshot)
        assertEquals(receipt, replayed)
        assertEquals(4, AtlasRepository(db).managementConnections.first().size)
    }

    @Test
    fun patAccountRole_metadataOnly_noInferenceAvailability() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-pat-" + UUID.randomUUID()
        val patId = "pat-" + UUID.randomUUID()
        val infId = "inf-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(
                SafeCredentialRef(patId, "帳號PAT", CredentialRole.ACCOUNT, accountRef = "team"),
                SafeCredentialRef(infId, "推理憑證", CredentialRole.INFERENCE, accountRef = "team"),
            ),
            endpoints = listOf(
                EndpointRef(
                    EndpointType.CHAT_COMPLETIONS,
                    "https://api.example.com/v1/chat/completions",
                    "CHAT_COMPLETIONS",
                    enabled = true,
                ),
            ),
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        // 兩種 role 都保存 metadata。
        assertEquals("帳號PAT", db.credentialDao().getById(patId)?.label)
        assertEquals("推理憑證", db.credentialDao().getById(infId)?.label)
        // 同組帳號：PAT 與推理憑證綁同一 account。
        assertEquals(
            db.credentialDao().getById(patId)?.accountId,
            db.credentialDao().getById(infId)?.accountId,
        )
        // PAT 絕不進 inference availability。
        assertEquals(0, db.availabilityDao().byCredential(patId).size)
        assertEquals(1, db.availabilityDao().byCredential(infId).size)
        val rows = AtlasRepository(db).managementConnections.first()
        assertEquals(1, rows.size)
        assertEquals(infId, rows[0].credentialId)
    }

    @Test
    fun sameCredentialId_conflictingRoles_rejectedAndRolledBack() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-roleconflict-" + UUID.randomUUID()
        val dupId = "dup-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(
                SafeCredentialRef(dupId, "推理", CredentialRole.INFERENCE),
                SafeCredentialRef(dupId, "帳號", CredentialRole.ACCOUNT),
            ),
        )
        try {
            committer.commit(snapshot)
            fail("expected role conflict reject")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("Credential role conflict"))
        }
        assertNull(db.onboardingCommitDao().getByWorkflow(workflowId))
        assertNull(db.credentialDao().getById(dupId))
        assertTrue(AtlasRepository(db).managementConnections.first().isEmpty())
    }

    @Test
    fun anonymous_noInference_noAvailability_noFakeKey() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-anon-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("anon-model"),
            credentials = emptyList(),
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        val providerId = receipt.providerId!!
        assertNotNull(db.providerDao().getById(providerId))
        // 匿名仍建 account（供簽到歸屬），但不建 credential / availability；
        // config authMode=NONE 使 offering-direct 匿名列可見（credentialId=""，不合成假 key）。
        assertEquals(1, db.accountDao().byProvider(providerId).size)
        val managed = AtlasRepository(db).managementConnections.first()
        assertEquals(1, managed.size)
        assertTrue(managed[0].anonymous)
        assertTrue(managed[0].credentialId.isEmpty())
        assertEquals("UNKNOWN", managed[0].priceState)
        assertEquals(1, AtlasRepository(db).connections.first().size)
        assertNotNull(db.onboardingCommitDao().getByWorkflow(workflowId))
    }

    @Test
    fun patOnly_noInference_noAvailability() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-patonly-" + UUID.randomUUID()
        val patId = "pat-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(
                SafeCredentialRef(patId, "帳號PAT", CredentialRole.ACCOUNT),
            ),
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        assertNotNull(db.credentialDao().getById(patId))
        assertEquals(0, db.availabilityDao().byCredential(patId).size)
        assertTrue(AtlasRepository(db).managementConnections.first().isEmpty())
    }

    @Test
    fun crossOriginEnabled_rejectedWithoutPartialRows() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-cross-" + UUID.randomUUID()
        val credId = "inf-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
            endpoints = listOf(
                EndpointRef(
                    EndpointType.CHAT_COMPLETIONS,
                    "https://api.example.com/v1/chat/completions",
                    "CHAT_COMPLETIONS",
                    enabled = true,
                ),
                EndpointRef(
                    EndpointType.CHAT_COMPLETIONS,
                    "https://other.example.com/v1/chat/completions",
                    "CHAT_COMPLETIONS",
                    enabled = true,
                ),
            ),
        )
        try {
            committer.commit(snapshot)
            fail("expected cross-origin reject")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("Cross-origin"))
            // 錯誤訊息不可攜帶 URL，避免洩漏拓撲。
            assertFalse((e.message ?: "").contains("other.example"))
        }
        assertNull(db.onboardingCommitDao().getByWorkflow(workflowId))
        assertNull(db.credentialDao().getById(credId))
        assertTrue(AtlasRepository(db).managementConnections.first().isEmpty())
    }

    @Test
    fun strictPolicy_rejectsHttpQueryAndZeroEnabled() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val credId = "inf-" + UUID.randomUUID()
        // http 非 HTTPS 拒絕。
        try {
            committer.commit(
                approvedSnapshot(
                    workflowId = "wf-http-" + UUID.randomUUID(),
                    models = listOf("m-a"),
                    credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
                    endpoints = listOf(
                        EndpointRef(
                            EndpointType.CHAT_COMPLETIONS,
                            "http://api.example.com/v1/chat/completions",
                            "CHAT_COMPLETIONS",
                            enabled = true,
                        ),
                    ),
                ),
            )
            fail("expected http reject")
        } catch (_: IllegalArgumentException) {
        }
        // query 拒絕。
        try {
            committer.commit(
                approvedSnapshot(
                    workflowId = "wf-query-" + UUID.randomUUID(),
                    models = listOf("m-a"),
                    credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
                    endpoints = listOf(
                        EndpointRef(
                            EndpointType.CHAT_COMPLETIONS,
                            "https://api.example.com/v1/chat/completions?x=1",
                            "CHAT_COMPLETIONS",
                            enabled = true,
                        ),
                    ),
                ),
            )
            fail("expected query reject")
        } catch (_: IllegalArgumentException) {
        }
        // 全 disabled 拒絕，不 fallback。
        try {
            committer.commit(
                approvedSnapshot(
                    workflowId = "wf-disabled-" + UUID.randomUUID(),
                    models = listOf("m-a"),
                    credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
                    endpoints = listOf(
                        EndpointRef(
                            EndpointType.CHAT_COMPLETIONS,
                            "https://api.example.com/v1/chat/completions",
                            "CHAT_COMPLETIONS",
                            enabled = false,
                        ),
                    ),
                ),
            )
            fail("expected zero-enabled reject")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(AtlasRepository(db).managementConnections.first().isEmpty())
        assertNull(db.credentialDao().getById(credId))
    }

    @Test
    fun adapterOverrideAndManualCheckin_winOverProbe() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-priority-" + UUID.randomUUID()
        val credId = "inf-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            baseUrl = "https://api.example.com/v1",
            providerName = null,
            models = listOf("m-a"),
            credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
            endpoints = emptyList(),
            detection = DetectionFinding(AdapterType.OPENAI_COMPATIBLE, EvidenceConfidence.LOW, "auto"),
            adapterOverride = AdapterType.GEMINI_NATIVE,
            checkinUrl = "https://manual.example.com/checkin",
            pricingQuota = PricingQuotaFinding(
                pricingStatus = PriceStatus.UNKNOWN,
                quotaStatus = QuotaState.UNKNOWN,
                officialHealth = HealthState.UNKNOWN,
                observedHealth = HealthState.UNKNOWN,
                noticeFetched = true,
                checkinUrl = "https://probe.example.com/notice",
                requiresDailyCheckin = false,
            ),
        )
        val receipt = committer.commit(snapshot)
        val provider = db.providerDao().getById(receipt.providerId!!)
        assertNotNull(provider)
        // override 優先於 detection。
        assertTrue((provider?.name ?: "").contains("(GEMINI_NATIVE)"))
        assertFalse((provider?.name ?: "").contains("OPENAI"))
        // 手填簽到優先於探測。
        assertTrue((provider?.checkinUrl ?: "").contains("manual.example.com"))
        assertFalse((provider?.checkinUrl ?: "").contains("probe.example.com"))
        assertEquals(true, provider?.requiresDailyCheckin)
    }

    @Test
    fun manualPricingInput_doesNotFakePrice_unknownPreserved() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-manualprice-" + UUID.randomUUID()
        val credId = "inf-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
            manualPricing = listOf(
                ManualPricingInput(
                    modelId = "m-a",
                    decimalAmount = "0.01",
                    currency = "USD",
                    perUnit = PricePerUnit.INPUT_TOKEN,
                    source = EvidenceSource.USER_MANUAL,
                    note = "手填參考",
                ),
            ),
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        val rows = AtlasRepository(db).managementConnections.first()
        assertEquals(1, rows.size)
        // 手填定價無對應欄位可無損保存：不可假寫 $0 / PAID，維持 UNKNOWN。
        assertEquals("UNKNOWN", rows[0].priceState)
        assertEquals("UNKNOWN", rows[0].quotaState)
    }

    @Test
    fun commitWritesManualConfigAlongsideProductRows() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-config-" + UUID.randomUUID()
        val credId = "inf-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(SafeCredentialRef(credId, "推理憑證", CredentialRole.INFERENCE)),
            manualPricing = listOf(
                ManualPricingInput(
                    modelId = "m-a",
                    decimalAmount = "0.01",
                    currency = "USD",
                    perUnit = PricePerUnit.INPUT_TOKEN,
                    source = EvidenceSource.USER_MANUAL,
                    note = "手填參考",
                ),
            ),
        )
        val receipt = committer.commit(snapshot)
        val config = db.providerConfigDao().getByWorkflow(workflowId)?.toManualConfig()
        assertNotNull(config)
        assertEquals(receipt.providerId, config!!.providerId)
        // 手填原值保留，不寫成官方價：金額 / 幣別 / 單位都在 config，不在 availability。
        assertEquals("0.01", config.manualPricing.single().decimalAmount)
        assertEquals("USD", config.manualPricing.single().currency)
        assertEquals(PricePerUnit.INPUT_TOKEN, config.manualPricing.single().perUnit)
        assertEquals(EvidenceSource.USER_MANUAL, config.manualPricing.single().source)
    }

    // ===== P0-3：API_KEY / CUSTOM_HEADER custom anchor =====

    @Test
    fun apiKeyCustomOnly_singleAnchor_producesOneUsableConnectionWithoutBearer() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-apikey-" + UUID.randomUUID()
        val customId = "custom-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = emptyList(),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Api-Key", customId),
            ),
            authModeOverride = AuthMode.API_KEY,
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        val providerId = receipt.providerId!!
        // custom anchor 寫入 credential metadata（僅 ref + 欄位名，不含值）。
        val stored = db.credentialDao().getById(customId)
        assertNotNull(stored)
        assertTrue((stored?.label ?: "").contains("X-Api-Key"))
        assertFalse((stored?.label ?: "").contains(customId))
        // 單 endpoint × 單 model × 單 anchor = 1 availability → 1 可用 connection。
        assertEquals(1, db.availabilityDao().byCredential(customId).size)
        val rows = AtlasRepository(db).managementConnections.first()
        assertEquals(1, rows.size)
        assertEquals(providerId, rows[0].providerId)
        assertEquals(customId, rows[0].credentialId)
        assertEquals("UNKNOWN", rows[0].priceState)
        // provider_config 保留 API_KEY 與 custom ref，供推理時取全部欄位（Bearer 由 VM 置 null）。
        val config = db.providerConfigDao().getByWorkflow(workflowId)?.toManualConfig()
        assertNotNull(config)
        assertEquals(AuthMode.API_KEY, config!!.authMode)
        assertEquals(customId, config.customAuthFields.single().safeCredentialId)
        // DB 不得殘留 secret 值：掃描 credential / availability / config 欄位。
        val scanned = listOfNotNull(
            stored?.label, stored?.credentialId,
            db.providerConfigDao().getByWorkflow(workflowId)?.snapshotJson,
        ).joinToString("\n")
        assertFalse(scanned.contains("fixture-secret-should-never-appear"))
    }

    @Test
    fun customHeaderMultiField_eachAnchorProducesAvailability() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-custom-" + UUID.randomUUID()
        val headerId = "custom-h-" + UUID.randomUUID()
        val queryId = "custom-q-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = emptyList(),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Custom-Key", headerId),
                CustomAuthFieldRef(CredentialLocation.QUERY, "api_key", queryId),
            ),
            authModeOverride = AuthMode.CUSTOM_HEADER,
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        // 1 offering × 2 anchors = 2 availabilities（與多 inference 憑證語意一致）。
        assertEquals(1, db.availabilityDao().byCredential(headerId).size)
        assertEquals(1, db.availabilityDao().byCredential(queryId).size)
        val rows = AtlasRepository(db).managementConnections.first()
        assertEquals(2, rows.size)
        assertEquals(setOf(headerId, queryId), rows.map { it.credentialId }.toSet())
        val config = db.providerConfigDao().getByWorkflow(workflowId)?.toManualConfig()
        assertEquals(AuthMode.CUSTOM_HEADER, config!!.authMode)
        assertEquals(2, config.customAuthFields.size)
    }

    @Test
    fun customField_missingRef_rejectedWithoutPartialRows() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-custom-missing-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = emptyList(),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Api-Key", null),
            ),
            authModeOverride = AuthMode.API_KEY,
        )
        try {
            committer.commit(snapshot)
            fail("expected custom auth ref reject")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("custom auth ref"))
        }
        assertNull(db.onboardingCommitDao().getByWorkflow(workflowId))
        assertTrue(AtlasRepository(db).managementConnections.first().isEmpty())
    }

    @Test
    fun customField_overlappingPatId_rejectedAsRoleConflict() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-custom-pat-" + UUID.randomUUID()
        val patId = "pat-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(
                SafeCredentialRef(patId, "帳號PAT", CredentialRole.ACCOUNT),
            ),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Api-Key", patId),
            ),
            authModeOverride = AuthMode.API_KEY,
        )
        try {
            committer.commit(snapshot)
            fail("expected role conflict reject")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("Credential role conflict"))
        }
        assertNull(db.onboardingCommitDao().getByWorkflow(workflowId))
        assertNull(db.credentialDao().getById(patId))
        assertTrue(AtlasRepository(db).managementConnections.first().isEmpty())
    }

    @Test
    fun mixedInferenceAndCustom_usesInferenceOnly_noDuplicateConnections() = runBlocking {
        val db = memoryDb()
        val committer = RoomCommitter(db)
        val workflowId = "wf-mixed-" + UUID.randomUUID()
        val infId = "inf-" + UUID.randomUUID()
        val headerId = "custom-h-" + UUID.randomUUID()
        val snapshot = approvedSnapshot(
            workflowId = workflowId,
            models = listOf("m-a"),
            credentials = listOf(SafeCredentialRef(infId, "推理憑證", CredentialRole.INFERENCE)),
            customFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Extra", headerId),
            ),
            authModeOverride = AuthMode.CUSTOM_HEADER,
        )
        val receipt = committer.commit(snapshot)
        assertNotNull(receipt.providerId)
        // 有 INFERENCE 時沿用既有行為：只用 inference 建 availability，避免重複 connection；
        // custom metadata 仍寫入（供推理時取全部欄位）。
        assertEquals(1, db.availabilityDao().byCredential(infId).size)
        assertEquals(0, db.availabilityDao().byCredential(headerId).size)
        assertNotNull(db.credentialDao().getById(headerId))
        val rows = AtlasRepository(db).managementConnections.first()
        assertEquals(1, rows.size)
        assertEquals(infId, rows[0].credentialId)
    }
}
