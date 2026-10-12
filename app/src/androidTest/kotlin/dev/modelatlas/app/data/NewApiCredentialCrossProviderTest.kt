package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-001 F1 follow-up（authority：`docs/CORRECTNESS_REPAIR_AUDIT.md` A5/CR-001）：
 * cross-provider PAT owner 必須在 same-account mismatch 之前判斷——
 * foreign-owner PAT 拋 `Cross-provider refs rejected`（既有
 * `ProviderPerfStoreTest.foreignAccountPatIsRejectedBeforeNetworkOrVaultResolution`
 * 既有期望，不得改 expectation）；foreign-first 候選只跳過，valid 同帳號第二項照選。
 *
 * 真 in-memory Room13，唯讀選擇器：成功/失敗前後 write-table counts 不變；
 * 零 HTTP、零 vault、零真 key/PAT。fixture 僅本檔，不當產品資料。
 */
@RunWith(AndroidJUnit4::class)
class NewApiCredentialCrossProviderTest {

    private val open = mutableListOf<AtlasDatabase>()

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    @Test
    fun foreignOwnerPatThrowsCrossProviderRejected() = runBlocking {
        val db = memoryDb()
        val provider = "cxp-f1-p1"
        val account = "cxp-f1-a1"
        val pat = "cxp-f1-pat"
        seed(db, provider, account, pat)
        // 誠實模擬 DB corruption：credential owner 指向他 provider 的帳號。
        db.providerDao().insert(ProviderEntity("cxp-other-p", "P other"))
        db.accountDao().insert(AccountEntity("cxp-other-a", "cxp-other-p"))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE credentials SET accountId = 'cxp-other-a' WHERE credentialId = ?",
            arrayOf(pat),
        )
        val before = readOnlyCounts(db)
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { NewApiCredentialSelector(db).resolvePerfPat(provider, account) }
        }
        assertEquals("Cross-provider refs rejected", error.message)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun foreignFirstPatFallsBackToValidSameAccount() = runBlocking {
        val db = memoryDb()
        val provider = "cxp-f2-p1"
        val account = "cxp-f2-a1"
        seed(db, provider, account, "cxp-f2-pat-good")
        db.providerDao().insert(ProviderEntity("cxp-other-p2", "P other"))
        db.accountDao().insert(AccountEntity("cxp-other-a2", "cxp-other-p2"))
        db.credentialDao().insert(CredentialEntity("cxp-f2-pat-foreign", "cxp-other-a2", enabled = true))
        // 首項 foreign owner（只跳過）＋同帳號 valid 第二項；其他帳號 PAT 永不借用。
        val updated = db.providerConfigDao().updateSnapshotJson(
            providerId = provider,
            snapshotJson = ProviderManualConfig(
                providerId = provider,
                workflowId = "wf-$provider-cxp",
                authMode = AuthMode.BEARER,
                credentials = listOf(
                    SafeCredentialRef("cxp-f2-pat-foreign", "foreign", CredentialRole.ACCOUNT),
                    SafeCredentialRef("cxp-f2-pat-good", "good", CredentialRole.ACCOUNT),
                ),
            ).toEntity().snapshotJson,
        )
        assertEquals(1, updated)
        val before = readOnlyCounts(db)
        val resolved = NewApiCredentialSelector(db).resolvePerfPat(provider, account)
        assertEquals(account, resolved?.accountId)
        assertEquals("cxp-f2-pat-good", resolved?.pat?.value)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== harness（fixture 僅本檔） =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    private suspend fun seed(db: AtlasDatabase, provider: String, account: String, pat: String) {
        db.providerDao().insert(ProviderEntity(provider, "P $provider"))
        db.accountDao().insert(AccountEntity(account, provider))
        db.credentialDao().insert(CredentialEntity(pat, account, enabled = true))
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = provider,
                workflowId = "wf-$provider-cxp",
                authMode = AuthMode.BEARER,
                credentials = listOf(SafeCredentialRef(pat, "pat", CredentialRole.ACCOUNT)),
            ).toEntity(),
        )
    }

    private fun tableCount(db: AtlasDatabase, table: String): Long {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            assertTrue(it.moveToFirst())
            return it.getLong(0)
        }
    }

    private fun readOnlyCounts(db: AtlasDatabase): Map<String, Long> =
        linkedMapOf(
            "samples" to tableCount(db, "samples"),
            "evidence" to tableCount(db, "evidence"),
            "snapshot_facts" to tableCount(db, "snapshot_facts"),
            "snapshot_heads" to tableCount(db, "snapshot_heads"),
            "provider_perf_facts" to tableCount(db, "provider_perf_facts"),
            "provider_perf_heads" to tableCount(db, "provider_perf_heads"),
            "provider_account_usage_facts" to tableCount(db, "provider_account_usage_facts"),
            "provider_account_usage_heads" to tableCount(db, "provider_account_usage_heads"),
            "my_models" to tableCount(db, "my_models"),
        )

    private fun assertReadOnlyUnchanged(db: AtlasDatabase, before: Map<String, Long>) {
        for ((table, count) in before) assertEquals("table $table", count, tableCount(db, table))
    }
}
