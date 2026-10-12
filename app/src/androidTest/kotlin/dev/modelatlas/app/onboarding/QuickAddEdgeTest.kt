package dev.modelatlas.app.onboarding

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.RoomWorkflowStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingStatus
import dev.modelatlas.core.onboarding.OnboardingStepId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [QuickAddService] 的輸入邊界與固定安全錯誤（不發任何網路請求、不落任何產品資料）。
 *
 * 互補於 [QuickAddServiceTest]（真 loopback 整合）：本檔只釘「驗證閘門」與
 * 「workflow 不存在 / 未就緒 / 已結束」等拒絕路徑，全部以真 [AtlasDatabase]、
 * 真 [AndroidCredentialVault] 觀察 vault 與資料表是否被動到。
 *
 * 覆蓋：
 * 1. `startQuickAdd` 空白 `workflowId` 在寫 vault 前即拒。
 * 2. 非法 `websiteUrl` 在寫 vault 前即拒。
 * 3. `secretTransient` 含控制字元即拒（固定 `憑證格式無效`）。
 * 4. 超長 `secretTransient` 即拒。
 * 5. 所有入口對空 `workflowId` 一律固定 `workflow 不存在`。
 * 6. 未知 `workflowId`：`loadSnapshot` 回 null、`reviewSummary` 固定 not-found。
 * 7. 已有 snapshot 但尚無 review：`reviewSummary` 固定 `review 尚未就緒`。
 * 8. 已結束（ABANDONED）workflow 不可再 `editQuick`（固定 `workflow 已結束`）。
 */
@RunWith(AndroidJUnit4::class)
class QuickAddEdgeTest {
    private lateinit var context: Context
    private lateinit var vault: AndroidCredentialVault
    private lateinit var database: AtlasDatabase
    private lateinit var service: QuickAddService

    private val databaseName = "quickadd-edge-${UUID.randomUUID()}.db"
    private val secret = "quickadd-edge-fixture-2a9f7c4e1b"
    private val validBase = "https://api.fixture-edge.invalid/v1"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        vault = AndroidCredentialVault(context)
        database = Room.databaseBuilder(context, AtlasDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val engine = InferenceEngine(
            OpenAiTransport(vault, OkHttpClient(), allowLoopbackHttpForTests = true),
            AtlasRepository(database),
        )
        service = QuickAddService(
            vault = vault,
            engine = engine,
            database = database,
            probeClient = OkHttpClient(),
            allowLoopbackHttpForTests = true,
        )
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        runCatching { context.deleteDatabase(databaseName) }
    }

    @Test
    fun startQuickAddRejectsBlankWorkflowIdBeforeWritingVaultOrSnapshot() = runBlocking {
        val vaultBefore = vaultKeys()
        val failure = runCatching {
            service.startQuickAdd(validBase, secretTransient = secret, workflowId = "   ")
        }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals("a rejected start must not touch the vault", vaultBefore, vaultKeys())
        assertEquals(0, countRows("onboarding_snapshots"))
    }

    @Test
    fun startQuickAddRejectsInvalidWebsiteUrlBeforeVaultWrite() = runBlocking {
        val vaultBefore = vaultKeys()
        val failure = runCatching {
            service.startQuickAdd(validBase, websiteUrl = "not-a-url", secretTransient = secret)
        }
        assertEquals("輸入無效", failure.exceptionOrNull()?.message)
        assertEquals(vaultBefore, vaultKeys())
        assertEquals(0, countRows("onboarding_snapshots"))
    }

    @Test
    fun startQuickAddRejectsSecretWithControlCharacters() = runBlocking {
        val vaultBefore = vaultKeys()
        val failure = runCatching {
            service.startQuickAdd(validBase, secretTransient = "abc\ndef")
        }
        assertEquals("憑證格式無效", failure.exceptionOrNull()?.message)
        assertEquals(vaultBefore, vaultKeys())
        assertEquals(0, countRows("onboarding_snapshots"))
    }

    @Test
    fun startQuickAddRejectsOverlongSecret() = runBlocking {
        val vaultBefore = vaultKeys()
        val failure = runCatching {
            service.startQuickAdd(validBase, secretTransient = "a".repeat(8193))
        }
        assertEquals("憑證格式無效", failure.exceptionOrNull()?.message)
        assertEquals(vaultBefore, vaultKeys())
        assertEquals(0, countRows("onboarding_snapshots"))
    }

    @Test
    fun blankWorkflowIdIsRejectedByEveryEntryPoint() = runBlocking {
        val calls: List<suspend () -> Any?> = listOf(
            { service.loadSnapshot("") },
            { service.reviewSummary("") },
            { service.approveAndCommit("") },
            { service.abandon("") },
            { service.resume("") },
            { service.retryStep("", OnboardingStepId.INSPECT) },
            { service.skipStep("", OnboardingStepId.INSPECT) },
            { service.editQuick("") },
        )
        for (call in calls) {
            val problem = runCatching { call() }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $problem", problem is IllegalStateException)
            assertEquals("workflow 不存在", problem?.message)
        }
        assertEquals(0, countRows("onboarding_snapshots"))
    }

    @Test
    fun unknownWorkflowIdIsReportedAsNotFound() = runBlocking {
        val id = UUID.randomUUID().toString()
        assertNull(service.loadSnapshot(id))
        assertEquals("workflow 不存在", runCatching { service.reviewSummary(id) }.exceptionOrNull()?.message)
    }

    @Test
    fun reviewSummaryWithoutReviewItemsThrowsReviewNotReady() = runBlocking {
        val id = "edge-review-${UUID.randomUUID()}"
        RoomWorkflowStore(database).save(
            OnboardingSnapshot(workflowId = id, flowType = OnboardingFlowType.QUICK, baseUrl = validBase),
        )
        assertEquals("review 尚未就緒", runCatching { service.reviewSummary(id) }.exceptionOrNull()?.message)
    }

    @Test
    fun editQuickRefusesFinishedWorkflow() = runBlocking {
        val id = "edge-finished-${UUID.randomUUID()}"
        RoomWorkflowStore(database).save(
            OnboardingSnapshot(
                workflowId = id,
                flowType = OnboardingFlowType.QUICK,
                status = OnboardingStatus.ABANDONED,
                baseUrl = validBase,
            ),
        )
        val failure = runCatching { service.editQuick(id) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals("workflow 已結束", failure.exceptionOrNull()?.message)
    }

    private fun vaultKeys(): Set<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.keys.toSet()

    private fun countRows(table: String): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val PREFERENCES = "credential_ciphertexts"
    }
}
