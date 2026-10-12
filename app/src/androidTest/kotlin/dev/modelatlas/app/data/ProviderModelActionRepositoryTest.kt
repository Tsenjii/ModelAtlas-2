package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.SafeCredentialRef
import dev.modelatlas.core.onboarding.CredentialLocation
import dev.modelatlas.core.onboarding.CustomAuthFieldRef
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROV-001 Provider Detail「新增模型 / 立即測試」＋ MODEL-006 manual hidden offering 的資料層驗收。
 *
 * TRACEABILITY：PROV-001（Provider Detail 模型動作）、MODEL-006（manual hidden offering）。
 * Spec：PRODUCT_SPEC §4（Provider Detail Models）、§6（Account/Credential/Group 語意）、
 * §8（manual offering 需實測成功 evidence 才可見）、§10（provenance 分槽、Unknown≠0/Free）。
 *
 * 測試對象是 [AtlasRepository.addProviderModelCandidate] 與
 * [AtlasRepository.verifyProviderModelCandidate] 兩個真 ports，搭配真記憶體 Room 與真
 * [InferenceEngine]（以 [AtlasRepository] 作 TelemetrySink）產生的 sample：
 * 驗證只能靠「本次請求的精確 sampleId」授權，禁止用 latest 類查詢猜測 evidence。
 *
 * 本檔不實作 VM/UI 行為，只驗證資料層不變條件：
 * - 新增＝USER evidence ＋ hidden manual offering，冪等；未驗證不出現在任何連線投影，
 *   不建 availability（不可用）；
 * - 驗證需 exact id final SUCCESS ＋ MANUAL_TEST ＋ completedAt ＋ 七維 scope 全等於 target
 *   ＋ 對應 evidence 持久化；promote 與 availability 同 transaction，新 price/quota 恒 UNKNOWN；
 * - 既有 availability 為 last-good 不被改寫，group 不符 fail-closed 不覆寫；
 * - official 列驗證成功亦零寫入；匿名（committed auth NONE）不捏造 credential/availability；
 * - 任何失敗／未知／非 MANUAL_TEST／scope 不符一律不 promote，且不刪任何既有列。
 *
 * Fixture 僅存於 androidTest，不當產品資料；只用 safe credential refs（c1/c2/c3），
 * 無 raw key/PAT，無真實 HTTP。
 */
@RunWith(AndroidJUnit4::class)
class ProviderModelActionRepositoryTest {
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

    // =====================================================================
    // 測試 1：新增＝USER evidence ＋ hidden candidate，未驗證不可見不可用
    // =====================================================================

    @Test
    fun addPersistsUserEvidenceAndKeepsCandidateHiddenAndOutOfConnections() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)

        val offeringId = repo.addProviderModelCandidate(record, NEW_MODEL, null)

        // 冪等鍵沿用既有 manual offering helper 的三元組推導。
        assertEquals("manual:p1:e1:$NEW_MODEL", offeringId)
        val offering = db.offeringDao().getById(offeringId)!!
        assertTrue("使用者新增的 offering 必須是 manual 家族", offering.manual)
        assertEquals("USER 來源不得被冒充成其他來源", "USER", offering.source)
        assertTrue("未驗證候選必須 hidden（§8 實測成功才可見）", offering.hidden)
        assertNull("未驗證不得帶 verification evidence", offering.verificationEvidenceId)
        assertEquals("p1", offering.providerId)
        assertEquals("e1", offering.endpointId)
        assertEquals(NEW_MODEL, offering.providerModelId)

        // USER evidence 真實持久化，且不得冒充成功。
        val userEvidence = evidenceFor(db, "p1", NEW_MODEL)
        assertTrue("新增必須留下 USER evidence", userEvidence.any { it.source == "USER" })
        assertTrue("USER evidence 必須能回溯實際 offering", userEvidence.any { it.requestId == offeringId })
        assertTrue("沒有請求的證據不得記成成功", userEvidence.none { it.outcome == "SUCCESS" })

        // 未驗證不可用：不建 availability，且不進任何連線投影。
        assertTrue(availabilityFor(db, "c1", offeringId).isEmpty())
        assertTrue(repo.connections.first().none { it.modelId == NEW_MODEL })
        // 既有官方列不受影響。
        assertEquals("PAID", repo.connections.first().single { it.id == "av-base" }.priceState)
    }

    // =====================================================================
    // 測試 2：新增冪等；重送不新建、不清除 evidence、不升格可見
    // =====================================================================

    @Test
    fun repeatedAddIsIdempotentAndNeverUnverifiesOrDeletesEvidence() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)

        val first = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        val evidenceAfterFirst = evidenceFor(db, "p1", NEW_MODEL).size
        val second = repo.addProviderModelCandidate(record, NEW_MODEL, "g-other")

        assertEquals("同三元組重送必須冪等回同一 id", first, second)
        val rows = db.offeringDao().byProvider("p1").filter { it.providerModelId == NEW_MODEL }
        assertEquals("重送不得建立第二列 offering", 1, rows.size)
        assertTrue("重送不得把候選升格為可見", rows[0].hidden)
        assertNull(rows[0].verificationEvidenceId)
        // 失敗/未驗證不刪資料：先前 USER evidence 仍在，且未被清空。
        assertTrue(evidenceFor(db, "p1", NEW_MODEL).size >= evidenceAfterFirst)
        // 未驗證候選仍然不可用。
        assertTrue(availabilityFor(db, "c1", first).isEmpty())
    }

    // =====================================================================
    // 測試 3：disabled / 跨 provider / 未 commit 匿名一律拒絕，且無部分寫入
    // =====================================================================

    @Test
    fun addRejectsDisabledCrossProviderAndUncommittedAnonymousRefs() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        seedOtherProvider(db)
        // Role metadata deliberately admits the refs, so ownership/enabled checks themselves
        // must reject them; the legacy availability-anchor gate cannot mask a missing check.
        db.providerConfigDao().insert(ProviderManualConfig(
            providerId = "p1", workflowId = "ownership-check", authMode = AuthMode.BEARER,
            credentials = listOf("c1", "c2", "c3").map { SafeCredentialRef(it, "fixture ref") },
        ).toEntity())

        assertTrue("disabled 憑證不得被選用", addRejected(repo, record.copy(credentialId = "c2"), NEW_MODEL))
        assertTrue("跨 provider 帳戶必須拒絕", addRejected(repo, record.copy(accountId = "a2"), NEW_MODEL))
        assertTrue("跨帳戶憑證必須拒絕", addRejected(repo, record.copy(credentialId = "c3"), NEW_MODEL))
        assertTrue("跨 provider endpoint 必須拒絕", addRejected(repo, record.copy(endpointId = "e2"), NEW_MODEL))
        assertTrue(
            "未 commit auth NONE 的匿名連線不得新增候選",
            addRejected(repo, anonymousRecordLike(record), NEW_MODEL),
        )

        // 全部拒絕：不得留下部分寫入（唯一 offering 是既有的官方列）。
        assertEquals(listOf("cat:m-base"), db.offeringDao().byProvider("p1").map { it.id })
        assertTrue(evidenceFor(db, "p1", NEW_MODEL).isEmpty())
    }

    // =====================================================================
    // 測試 4：成功路徑——精確 sample 授權 promote，evidence/availability 同時落地
    // =====================================================================

    @Test
    fun successfulManualTestPromotesCandidateWithPersistedEvidenceAndUnknownPriceQuota() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        val offeringId = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        val target = candidateTarget()

        val sampleId = manualTestSampleId(repo, target)

        val storedSample = db.sampleDao().getById(sampleId)!!
        assertEquals("SUCCESS", storedSample.outcome)
        assertNotNull("final sample 必須有 completedAt", storedSample.completedAt)
        assertEquals("MANUAL_TEST", storedSample.source)
        val evidence = db.evidenceDao().getById("sample:$sampleId")
        assertNotNull("本次 sample 必須有對應持久化 evidence", evidence)
        val persisted = requireNotNull(evidence)
        assertEquals(sampleId, persisted.requestId)
        assertEquals("SUCCESS", persisted.outcome)
        assertEquals("MANUAL_TEST", persisted.source)

        assertTrue("精確 sampleId 成功時必須回 true", repo.verifyProviderModelCandidate(offeringId, record, target, sampleId))

        val promoted = db.offeringDao().getById(offeringId)!!
        assertFalse("驗證成功後候選必須可見", promoted.hidden)
        assertEquals("sample:$sampleId", promoted.verificationEvidenceId)
        assertEquals("manual/USER 不得被升格成官方來源", "USER", promoted.source)

        // availability 與 promote 同 transaction 落地，且新 price/quota 恒 UNKNOWN。
        val availability = availabilityFor(db, "c1", offeringId).single()
        assertEquals("a1", availability.accountId)
        assertEquals("c1", availability.credentialId)
        assertNull(availability.group)
        assertEquals("Unknown≠0/Free：成功測試不得捏造價格", "UNKNOWN", availability.priceState)
        assertEquals("Unknown≠0/Free：成功測試不得捏造配額", "UNKNOWN", availability.quotaState)

        // 可見後才進連線投影；既有官方列 last-good 不動。
        val projected = repo.connections.first()
        val candidateRow = projected.single { it.modelId == NEW_MODEL }
        assertEquals(offeringId, candidateRow.offeringId)
        assertEquals("UNKNOWN", candidateRow.priceState)
        assertEquals("UNKNOWN", candidateRow.quotaState)
        val base = projected.single { it.id == "av-base" }
        assertEquals("PAID", base.priceState)
        assertEquals("QUOTA_AVAILABLE", base.quotaState)
    }

    // =====================================================================
    // 測試 5：同 scope 的最新成功不得替本次失敗 sample 背書
    // =====================================================================

    @Test
    fun decoyLatestSuccessDoesNotAuthorizeTheRequestedFailedSampleId() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        val offeringId = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        val target = candidateTarget()

        // 同 scope 的一次成功（若實作誤用 latest 類查詢，它就是會被拿到的證據）。
        val decoyId = manualTestSampleId(repo, target)
        assertEquals("SUCCESS", db.sampleDao().getById(decoyId)?.outcome)
        // 本次請求失敗。
        val failedId = manualTestSampleId(repo, target, fail = true)
        assertEquals("NETWORK", db.sampleDao().getById(failedId)?.outcome)

        assertTrue(
            "同 scope 的成功不得替本次失敗 sample 授權",
            verifyRejected(repo, offeringId, record, target, failedId),
        )
        assertTrue("失敗請求後候選必須維持 hidden", db.offeringDao().getById(offeringId)!!.hidden)
        assertTrue(availabilityFor(db, "c1", offeringId).isEmpty())
        assertTrue(repo.connections.first().none { it.modelId == NEW_MODEL })
        // 失敗不得刪除任何既有列（候選與官方列都保留）。
        assertNotNull(db.offeringDao().getById(offeringId))
        assertNotNull(db.offeringDao().getById("cat:m-base"))
        // 反向證明授權只跟 requested id 走：同一候選改用它自己的成功 sample 就可 promote。
        assertTrue(repo.verifyProviderModelCandidate(offeringId, record, target, decoyId))
        assertFalse(db.offeringDao().getById(offeringId)!!.hidden)
    }

    // =====================================================================
    // 測試 6：未完成／不存在／非 MANUAL_TEST 皆不得 promote，且不得補完 sample
    // =====================================================================

    @Test
    fun nonFinalUnknownAndNonManualTestSampleIdsCannotPromote() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        val offeringId = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        val target = candidateTarget()

        // 進程中斷遺留的 durable 起始列（engine 落 durable RUNNING 後被殺）。
        val runningId = "running-${NEW_MODEL}-manual"
        repo.save(
            PerformanceSample(
                id = runningId,
                providerId = "p1",
                accountId = "a1",
                safeCredentialId = "c1",
                endpointId = "e1",
                providerModelId = NEW_MODEL,
                canonicalModelId = null,
                group = null,
                operation = "CHAT_COMPLETIONS",
                source = TelemetrySource.MANUAL_TEST,
                requestStartedAt = 5_000L,
                completedAt = null,
                outcome = Outcome.RUNNING,
            ),
        )
        assertTrue("未完成 sample 不得授權", verifyRejected(repo, offeringId, record, target, runningId))
        val stillRunning = db.sampleDao().getById(runningId)!!
        assertEquals("RUNNING", stillRunning.outcome)
        assertNull("驗證失敗不得補完 sample 的 completedAt", stillRunning.completedAt)

        assertTrue("不存在的 sample id 不得授權", verifyRejected(repo, offeringId, record, target, "no-such-sample"))

        val playgroundId = manualTestSampleId(repo, target, source = TelemetrySource.PLAYGROUND)
        assertEquals("SUCCESS", db.sampleDao().getById(playgroundId)?.outcome)
        assertTrue("scope 完全相同但來源非 MANUAL_TEST 仍不得授權", verifyRejected(repo, offeringId, record, target, playgroundId))

        assertTrue("所有拒絕後候選仍保留且未驗證", db.offeringDao().getById(offeringId)!!.hidden)
        assertNull(db.offeringDao().getById(offeringId)!!.verificationEvidenceId)
        assertTrue(availabilityFor(db, "c1", offeringId).isEmpty())

        // 真正的 MANUAL_TEST 成功仍可 promote（前面的拒絕不是一刀切）。
        val goodId = manualTestSampleId(repo, target)
        assertTrue(repo.verifyProviderModelCandidate(offeringId, record, target, goodId))
        assertFalse(db.offeringDao().getById(offeringId)!!.hidden)
    }

    // =====================================================================
    // 測試 7：七維 scope ＋ key ＋ canonical ＋ source 任一不符即拒絕
    // =====================================================================

    @Test
    fun everyScopeDimensionMismatchIsRejectedAndCandidateStaysHidden() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        val offeringId = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        val expected = candidateTarget()

        val mismatches = listOf(
            "provider" to candidateTarget(providerId = "p-other"),
            "account" to candidateTarget(accountId = "a-other"),
            "key" to candidateTarget(credentialId = "c-other"),
            "endpoint" to candidateTarget(endpointId = "e-other"),
            "model" to candidateTarget(modelId = "m-other"),
            "group" to candidateTarget(group = "g-other"),
            "operation" to candidateTarget(operation = "RESPONSES"),
            "canonical" to candidateTarget(canonicalModelId = "canon-other"),
        )
        for ((label, drifted) in mismatches) {
            val sampleId = manualTestSampleId(repo, drifted)
            assertTrue("$label 不符的 sample 不得授權候選", verifyRejected(repo, offeringId, record, expected, sampleId))
            val after = db.offeringDao().getById(offeringId)!!
            assertTrue("$label 不符後候選必須維持 hidden", after.hidden)
            assertNull("$label 不符後不得寫入 verification evidence", after.verificationEvidenceId)
        }
        assertTrue(availabilityFor(db, "c1", offeringId).isEmpty())
        assertTrue("未被驗證的候選不得進連線投影", repo.connections.first().none { it.modelId == NEW_MODEL })
    }

    // =====================================================================
    // 測試 8：既有 availability 是 last-good；v14（CR-003 B3）exact group scope：
    // 同 credential 的 null 與具名 group 各自獨立共存、互不覆寫；
    // wrong-sample-group guard（實際 sample group 與 requested group 不符）仍在
    // ——負向拒絕是真 guard，不是 v13 unique-triple 產物；正向 promote 建立
    // 自己的 scope 列，既有 exact 列 last-good 原樣保留。
    // =====================================================================

    @Test
    fun existingAvailabilityIsLastGoodAndGroupMismatchNeverOverwritesIt() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        val offeringId = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-prev",
                offeringId = offeringId,
                accountId = "a1",
                credentialId = "c1",
                priceState = "PAID",
                quotaState = "QUOTA_LOW",
                group = "g-vip",
            ),
        )

        // 負向（強化）：同一 null-group 實際 sample 配 requested VIP target——
        // 實際 sample group（null）與 requested group（g-vip）不符，wrong-sample-group
        // guard 必須在任何寫入前 fail-closed 拒絕、不得 promote。
        val nullSample = manualTestSampleId(repo, candidateTarget(group = null))
        assertNull("實際 sample group 必須是 null（證明 mismatch 方向）", db.sampleDao().getById(nullSample)!!.group)
        assertTrue("wrong sample group 必須 fail-closed 拒絕", verifyRejected(repo, offeringId, record, candidateTarget(group = "g-vip"), nullSample))
        assertEquals("g-vip", availabilityFor(db, "c1", offeringId).single().group)
        assertEquals("wrong sample group 時 last-good 價格原樣保留", "PAID", availabilityFor(db, "c1", offeringId).single().priceState)
        assertEquals("wrong sample group 時 last-good 配額原樣保留", "QUOTA_LOW", availabilityFor(db, "c1", offeringId).single().quotaState)
        assertTrue("wrong sample group 時不得 promote", db.offeringDao().getById(offeringId)!!.hidden)
        assertNull("wrong sample group 時不得寫入 verification evidence", db.offeringDao().getById(offeringId)!!.verificationEvidenceId)

        // 正向：matching NULL target 以同一實際 null sample promote——v14 group 共存：
        // 建立自己的 null row（distinct id、UNKNOWN/UNKNOWN），VIP last-good 完全不動。
        assertTrue(repo.verifyProviderModelCandidate(offeringId, record, candidateTarget(group = null), nullSample))
        val rows = availabilityFor(db, "c1", offeringId)
        assertEquals("null group 與 g-vip 必須各自獨立共存", 2, rows.size)
        val nullRow = rows.single { it.group == null }
        val vipRow = rows.single { it.group == "g-vip" }
        assertEquals("av-prev", vipRow.id)
        assertTrue("新 null row 必須有 distinct id（不得覆寫 VIP 列）", nullRow.id != vipRow.id)
        assertNull(nullRow.group)
        assertEquals("Unknown≠0/Free：新 null row 不得複製 VIP 的價格", "UNKNOWN", nullRow.priceState)
        assertEquals("Unknown≠0/Free：新 null row 不得複製 VIP 的配額", "UNKNOWN", nullRow.quotaState)
        assertEquals("g-vip", vipRow.group)
        assertEquals("VIP last-good 價格不得被 null promote 覆寫", "PAID", vipRow.priceState)
        assertEquals("VIP last-good 配額不得被 null promote 覆寫", "QUOTA_LOW", vipRow.quotaState)
        assertFalse(db.offeringDao().getById(offeringId)!!.hidden)

        // 後續 matching VIP sample/target：exact 列存在即 last-good，VIP 舊欄位原樣保留。
        val vipSample = manualTestSampleId(repo, candidateTarget(group = "g-vip"))
        assertEquals("g-vip", db.sampleDao().getById(vipSample)!!.group)
        assertTrue(repo.verifyProviderModelCandidate(offeringId, record, candidateTarget(group = "g-vip"), vipSample))
        val kept = availabilityFor(db, "c1", offeringId).single { it.group == "g-vip" }
        assertEquals("av-prev", kept.id)
        assertEquals("g-vip", kept.group)
        assertEquals("last-good 價格不得被驗證覆寫", "PAID", kept.priceState)
        assertEquals("last-good 配額不得被驗證覆寫", "QUOTA_LOW", kept.quotaState)
        assertEquals("exact 列存在不得新建第二列", 2, availabilityFor(db, "c1", offeringId).size)
        assertFalse(db.offeringDao().getById(offeringId)!!.hidden)
    }

    // =====================================================================
    // 測試 9：匿名（committed auth NONE）不捏造 credential/availability
    // =====================================================================

    @Test
    fun anonymousCommittedNoneCandidateVerifiesWithoutFabricatingCredentials() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedAnonymousConnection(db, repo)

        val offeringId = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        assertTrue("匿名連線不得帶 credential ref", record.credentialId.isEmpty())
        assertTrue("匿名連線不得寫入任何 credentials 列", db.credentialDao().byAccount(record.accountId).isEmpty())
        assertTrue("匿名候選未驗證不得進連線投影", repo.connections.first().none { it.modelId == NEW_MODEL })

        val target = candidateTarget(
            providerId = record.providerId,
            accountId = record.accountId,
            endpointId = record.endpointId,
            credentialId = null,
        )
        val sampleId = manualTestSampleId(repo, target)
        assertEquals("匿名 sample 不得帶 credential ref", "", db.sampleDao().getById(sampleId)!!.safeCredentialId)

        assertTrue(repo.verifyProviderModelCandidate(offeringId, record, target, sampleId))

        val promoted = db.offeringDao().getById(offeringId)!!
        assertFalse(promoted.hidden)
        assertEquals("sample:$sampleId", promoted.verificationEvidenceId)
        assertTrue("驗證後仍不得捏造 credentials 列", db.credentialDao().byAccount(record.accountId).isEmpty())
        assertEquals("匿名不得建立 availability（不捏造 credential）", 0, availabilityCountFor(db, offeringId))
        // 匿名分支直接投影 offerings：promote 後才出現。
        val projected = repo.connections.first().single { it.modelId == NEW_MODEL }
        assertTrue(projected.anonymous)
        assertEquals("", projected.credentialId)
        assertEquals(offeringId, projected.offeringId)
    }

    // =====================================================================
    // 測試 10：官方列驗證成功也零寫入（manual/official 分槽）
    // =====================================================================

    @Test
    fun officialOfferingVerificationSucceedsWithoutMutatingOfficialRow() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        // 另開一個未驗證候選，證明官方驗證不會連帶 promote 別列。
        val candidateId = repo.addProviderModelCandidate(record, NEW_MODEL, null)
        val target = candidateTarget(modelId = BASE_MODEL)

        val sampleId = manualTestSampleId(repo, target)
        assertTrue("官方列驗證成功應回 true", repo.verifyProviderModelCandidate("cat:m-base", record, target, sampleId))

        val official = db.offeringDao().getById("cat:m-base")!!
        assertFalse(official.manual)
        assertEquals("OFFICIAL", official.source)
        assertFalse(official.hidden)
        assertNull("官方列不得被寫入 manual 驗證 evidence", official.verificationEvidenceId)
        // 官方路徑零寫入：既不新增 availability，也不改既有 last-good。
        assertEquals(1, availabilityFor(db, "c1", "cat:m-base").size)
        assertEquals("PAID", availabilityFor(db, "c1", "cat:m-base").single().priceState)
        assertTrue("官方驗證不得連帶 promote 其他候選", db.offeringDao().getById(candidateId)!!.hidden)
    }

    @Test
    fun addReusesPersistedManualOrOfficialIdsWithoutCreatingTwins() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val record = seedBaseConnection(db, repo)
        val officialBefore = db.offeringDao().getById("cat:m-base")
        assertEquals("cat:m-base", repo.addProviderModelCandidate(record, BASE_MODEL, null))
        assertEquals(officialBefore, db.offeringDao().getById("cat:m-base"))
        db.offeringDao().insert(OfferingEntity(
            id = "legacy-manual-id", providerId = "p1", endpointId = "e1",
            providerModelId = NEW_MODEL, manual = true, source = "MANUAL", hidden = false,
        ))
        assertEquals("legacy-manual-id", repo.addProviderModelCandidate(record, NEW_MODEL, null))
        assertEquals(2, db.offeringDao().byProvider("p1").size)
        assertTrue(evidenceFor(db, "p1", NEW_MODEL).isEmpty())
    }

    @Test
    fun customOnlySuccessCannotDonateVerificationToAnotherSelectedAnchor() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val first = seedBaseConnection(db, repo)
        repo.attachCredential("cat:m-base", "a1", "c4", "second anchor", "av-anchor2")
        db.providerConfigDao().insert(ProviderManualConfig(
            providerId = "p1", workflowId = "custom-anchors", authMode = AuthMode.CUSTOM_HEADER,
            customAuthFields = listOf(
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-One", "c1"),
                CustomAuthFieldRef(CredentialLocation.HEADER, "X-Two", "c4"),
            ),
        ).toEntity())
        val second = repo.connections.first().single { it.id == "av-anchor2" }
        val candidate = repo.addProviderModelCandidate(first, NEW_MODEL, null)
        val target = candidateTarget(credentialId = null).copy(authFields = listOf(
            InferenceAuthField("X-One", "c1"), InferenceAuthField("X-Two", "c4"),
        ))
        val firstSample = manualTestSampleId(repo, target)
        assertTrue(repo.verifyProviderModelCandidate(candidate, first, target, firstSample))
        // Availability by itself is not proof of which anchor owned the successful request.
        db.availabilityDao().insert(CredentialAvailabilityEntity("av-other-candidate", candidate, "a1", "c4"))
        assertEquals(listOf(ScopedVerifiedModel("e1", NEW_MODEL)),
            db.sampleDao().customAnchorVerifiedModels("p1", "a1", "c1", "e1", "CHAT_COMPLETIONS", null))
        assertTrue(db.sampleDao().customAnchorVerifiedModels("p1", "a1", "c4", "e1", "CHAT_COMPLETIONS", null).isEmpty())
        assertTrue("A claimed sample cannot be rebound to another anchor",
            verifyRejected(repo, candidate, second, target, firstSample))
        assertFalse(dev.modelatlas.app.ui.ProviderDetailLoader.loadDetail(
            db, "p1", selectedConnectionId = second.id,
        ).models.single { it.modelId == NEW_MODEL }.verified)
        val ownSample = manualTestSampleId(repo, target)
        assertTrue(repo.verifyProviderModelCandidate(candidate, second, target, ownSample))
        assertTrue(dev.modelatlas.app.ui.ProviderDetailLoader.loadDetail(
            db, "p1", selectedConnectionId = second.id,
        ).models.single { it.modelId == NEW_MODEL }.verified)
        // The earlier anchor retains its own last-good proof after another anchor's success.
        assertEquals(listOf(ScopedVerifiedModel("e1", NEW_MODEL)),
            db.sampleDao().customAnchorVerifiedModels("p1", "a1", "c1", "e1", "CHAT_COMPLETIONS", null))
    }

    // =====================================================================
    // Fixtures / helpers（僅本檔使用）
    // =====================================================================

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    /**
     * 一組已 commit 的具憑證連線：provider/account/key(c1 enabled, c2 disabled)/endpoint，
     * 外加一列既有官方 offering＋availability（last-good 基準）。
     */
    private suspend fun seedBaseConnection(db: AtlasDatabase, repo: AtlasRepository): ConnectionRecord {
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        db.accountDao().insert(AccountEntity(accountId = "a1", providerId = "p1"))
        db.credentialDao().insert(CredentialEntity(credentialId = "c1", accountId = "a1"))
        db.credentialDao().insert(CredentialEntity(credentialId = "c2", accountId = "a1", enabled = false))
        db.endpointDao().insert(EndpointEntity(endpointId = "e1", providerId = "p1", baseUrl = BASE_URL, endpointUrl = ENDPOINT_URL))
        db.offeringDao().insert(
            OfferingEntity(
                id = "cat:m-base",
                providerId = "p1",
                endpointId = "e1",
                providerModelId = BASE_MODEL,
                manual = false,
                source = "OFFICIAL",
                hidden = false,
                verificationEvidenceId = null,
            ),
        )
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-base",
                offeringId = "cat:m-base",
                accountId = "a1",
                credentialId = "c1",
                priceState = "PAID",
                quotaState = "QUOTA_AVAILABLE",
            ),
        )
        return repo.connections.first().single { it.id == "av-base" }
    }

    /** 第二個 provider（跨 provider/跨帳戶 refs 測試用）。 */
    private suspend fun seedOtherProvider(db: AtlasDatabase) {
        db.providerDao().insert(ProviderEntity(providerId = "p2", name = "P2"))
        db.accountDao().insert(AccountEntity(accountId = "a2", providerId = "p2"))
        db.credentialDao().insert(CredentialEntity(credentialId = "c3", accountId = "a2"))
        db.endpointDao().insert(EndpointEntity(endpointId = "e2", providerId = "p2", baseUrl = OTHER_BASE_URL, endpointUrl = OTHER_ENDPOINT_URL))
    }

    /**
     * 匿名（auth NONE）已 commit 連線：provider_configurations 帶 authMode=NONE，
     * 連線投影走匿名分支（credentialId 空字串，不寫 credentials 表）。
     */
    private suspend fun seedAnonymousConnection(db: AtlasDatabase, repo: AtlasRepository): ConnectionRecord {
        db.providerDao().insert(ProviderEntity(providerId = "p-anon", name = "P Anonymous"))
        db.accountDao().insert(AccountEntity(accountId = "a-anon", providerId = "p-anon"))
        db.endpointDao().insert(
            EndpointEntity(endpointId = "e-anon", providerId = "p-anon", baseUrl = BASE_URL, endpointUrl = ENDPOINT_URL),
        )
        val config = ProviderManualConfig(
            providerId = "p-anon",
            workflowId = "wf-anon",
            authMode = AuthMode.NONE,
            anonymousConfirmed = true,
        )
        db.providerConfigDao().insert(
            ProviderConfigurationEntity(
                providerId = "p-anon",
                workflowId = config.workflowId,
                authMode = AuthMode.NONE.name,
                snapshotJson = config.encodeToSnapshotJson(),
            ),
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = "cat:anon-base",
                providerId = "p-anon",
                endpointId = "e-anon",
                providerModelId = BASE_MODEL,
                manual = false,
                source = "OFFICIAL",
                hidden = false,
            ),
        )
        return repo.connections.first().single { it.anonymous }
    }

    /** 匿名連線投影的 record 形狀（credentialId 空字串；p1 未 commit auth NONE）。 */
    private fun anonymousRecordLike(record: ConnectionRecord): ConnectionRecord = record.copy(
        id = "anon:${record.offeringId}:${record.accountId}",
        credentialId = "",
        anonymous = true,
        credentialEnabled = true,
    )

    /** 對齊 VM 的 target 組法（新模型不帶 canonical、32 token 預算）。 */
    private fun candidateTarget(
        providerId: String = "p1",
        accountId: String = "a1",
        credentialId: String? = "c1",
        endpointId: String = "e1",
        modelId: String = NEW_MODEL,
        canonicalModelId: String? = null,
        group: String? = null,
        operation: String = "CHAT_COMPLETIONS",
    ): InferenceTarget = InferenceTarget(
        providerId = providerId,
        accountId = accountId,
        credentialId = credentialId,
        endpointId = endpointId,
        endpointUrl = ENDPOINT_URL,
        modelId = modelId,
        canonicalModelId = canonicalModelId,
        group = group,
        operation = operation,
        maxOutputTokens = 32,
    )

    /**
     * 真 [InferenceEngine] 跑一次手動測試，回傳本次請求的精確 sampleId
     * （[StreamEvent.RequestSent.sampleId]），sample/evidence 由 [AtlasRepository] 落庫。
     * `fail=true` 模擬網路失敗：sample 仍 durable 且有 id，只是 outcome 非成功。
     */
    private suspend fun manualTestSampleId(
        repo: AtlasRepository,
        target: InferenceTarget,
        source: TelemetrySource = TelemetrySource.MANUAL_TEST,
        fail: Boolean = false,
    ): String {
        val transport = if (fail) {
            FailingScriptedTransport(IOException("fixture network failure"))
        } else {
            ScriptedTransport(
                listOf(
                    StreamEvent.RequestSent(epochMillis = 1_000L, monotonicMillis = 100L),
                    StreamEvent.Headers(200, emptyMap(), 110L),
                    StreamEvent.Delta("ok", observedMonotonicMs = 120L),
                    StreamEvent.TokenUsage(Usage(inputTokens = 1L, outputTokens = 1L, totalTokens = 2L)),
                    StreamEvent.Finished,
                ),
            )
        }
        var sampleId: String? = null
        try {
            InferenceEngine(transport, repo)
                .execute(target, listOf(PromptMessage("user", "Reply OK.")), source = source)
                .collect { event -> if (event is StreamEvent.RequestSent) sampleId = event.sampleId }
        } catch (_: InferenceFailure) {
            // 失敗請求正是本測項情境；sample 已在 engine finally 落庫。
        }
        return requireNotNull(sampleId) { "engine must expose the exact sample id of this request" }
    }

    private class ScriptedTransport(private val script: List<StreamEvent>) : InferenceTransport {
        override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
            for (event in script) emit(event)
        }
    }

    private class FailingScriptedTransport(private val failure: Throwable) : InferenceTransport {
        override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
            emit(StreamEvent.RequestSent(epochMillis = 1_000L, monotonicMillis = 100L))
            throw failure
        }
    }

    private suspend fun addRejected(repo: AtlasRepository, record: ConnectionRecord, modelId: String, group: String? = null): Boolean =
        try {
            repo.addProviderModelCandidate(record, modelId, group)
            false
        } catch (_: IllegalArgumentException) {
            true
        }

    private suspend fun verifyRejected(
        repo: AtlasRepository,
        offeringId: String,
        record: ConnectionRecord,
        target: InferenceTarget,
        sampleId: String,
    ): Boolean = try {
        repo.verifyProviderModelCandidate(offeringId, record, target, sampleId)
        false
    } catch (_: IllegalArgumentException) {
        true
    }

    private suspend fun availabilityFor(db: AtlasDatabase, credentialId: String, offeringId: String): List<CredentialAvailabilityEntity> =
        db.availabilityDao().byCredential(credentialId).filter { it.offeringId == offeringId }

    /** 全表掃描（不經任何 credential 維度）：證明該 offering 真的沒有 availability 列。 */
    private fun availabilityCountFor(db: AtlasDatabase, offeringId: String): Int {
        var count = 0
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM credential_availability WHERE offeringId = ?",
            arrayOf<Any?>(offeringId),
        ).use { cursor -> if (cursor.moveToFirst()) count = cursor.getInt(0) }
        return count
    }

    private data class EvidenceRow(
        val id: String,
        val requestId: String,
        val outcome: String,
        val source: String,
        val providerId: String,
        val modelId: String,
    )

    private fun evidenceFor(db: AtlasDatabase, providerId: String, modelId: String): List<EvidenceRow> {
        val out = mutableListOf<EvidenceRow>()
        db.openHelper.readableDatabase.query(
            "SELECT id, requestId, outcome, source, providerId, modelId FROM evidence " +
                "WHERE providerId = ? AND modelId = ?",
            arrayOf<Any?>(providerId, modelId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += EvidenceRow(
                    id = cursor.getString(0),
                    requestId = cursor.getString(1),
                    outcome = cursor.getString(2),
                    source = cursor.getString(3),
                    providerId = cursor.getString(4),
                    modelId = cursor.getString(5),
                )
            }
        }
        return out
    }

    private companion object {
        const val BASE_MODEL = "m-base"
        const val NEW_MODEL = "m-new"
        const val HOST = "provider-model-action.fixture.invalid"
        const val BASE_URL = "https://$HOST/v1"
        const val ENDPOINT_URL = "https://$HOST/v1/chat/completions"
        const val OTHER_HOST = "provider-model-action-other.fixture.invalid"
        const val OTHER_BASE_URL = "https://$OTHER_HOST/v1"
        const val OTHER_ENDPOINT_URL = "https://$OTHER_HOST/v1/chat/completions"
    }
}
