package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NINT-005A transaction / lifecycle units (device androidTest;本檔唯一新增).
 *
 * 契約: TRACEABILITY NINT-005A, Contract §2/6.4/23.3, PRODUCT_SPEC §10/12/18,
 * RESEARCH_POLICY evidence-first. Single slice NINT005A, baseline main875cfba full-green;
 * main 已擁有 observer/repo/DB13 hook,本檔只測 transaction 邊界,不碰 production/舊測試.
 *
 * - 真 in-memory Room v13 + 真 [ProviderAccountUsageStore],零 HTTP.
 * - 夾具全部 nested 在本檔,無共用 helper,無真 key/PAT/secret,無 vault.
 * - provider/account 皆非 local/selected-key;source/roles 皆 safe 常數.
 * - Unknown != 0: null 計數永不補 0;counterSemantics 恆 UNSPECIFIED.
 */
@RunWith(AndroidJUnit4::class)
class ProviderAccountUsageTransactionTest {

    private val open = mutableListOf<AtlasDatabase>()

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    // ===== 1. post-evidence failure 必須整筆 rollback,保留 prior-good =====

    @Test
    fun postEvidenceFailureRollsBackOwnTransactionPreservingPriorGood() = runBlocking {
        val db = memoryDb()
        val provider = "usage-txn-provider"
        val account = "usage-txn-account"
        val patRef = "pat-usage-txn"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        // Prior-good: certified rc.25 真 2xx, count 77 (真值,非 Unknown).
        val prior = store.record(goodObservation(provider, account, patRef, 1_000L, 77L))
        val priorLatestId = prior.latest!!.evidenceId
        assertEquals(77L, prior.lastGood!!.requestCountRaw)
        assertEquals("UNSPECIFIED", prior.lastGood!!.counterSemantics)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, prior.lastGood!!.source)
        assertFalse(provider == "local" || account == "local")
        assertFalse(provider.contains("selected") || account.contains("selected"))

        val scopeKey = providerAccountUsageScopeKey(provider, account)
        val kind = ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name
        val headBefore = db.providerAccountUsageDao().head(scopeKey, kind)!!
        assertEquals(priorLatestId, headBefore.latestEvidenceId)
        assertEquals(1L, count(db, "provider_account_usage_facts"))
        assertEquals(1L, count(db, "provider_account_usage_heads"))
        assertEquals(1L, count(db, "evidence"))

        // Real SQLite trigger: fact insert 前強制 RAISE(ABORT).
        // Store 先插 evidence 再插 fact,同一 withTransaction 內第二步失敗必須連 evidence 一起 rollback.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER usage_txn_abort BEFORE INSERT ON provider_account_usage_facts " +
                "BEGIN SELECT RAISE(ABORT, 'nint005a-txn-abort'); END",
        )
        try {
            val error = runCatching {
                store.record(goodObservation(provider, account, patRef, 2_000L, 78L))
            }.exceptionOrNull()
            assertNotNull("trigger ABORT 必須讓 record 拋出", error)
            assertTrue(
                "必須是 SQLite 約束 ABORT (chain 含 SQLiteConstraintException),實際:$error",
                isConstraintAbort(error),
            )
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS usage_txn_abort")
        }

        // Rollback 證明:新 evidence/fact/head 全無,prior-good 原樣保留.
        assertEquals(1L, count(db, "provider_account_usage_facts"))
        assertEquals(1L, count(db, "provider_account_usage_heads"))
        assertEquals(1L, count(db, "evidence"))
        assertEquals(1, store.history(provider, account).size)
        val after = store.observation(provider, account)
        assertNotNull(after.latest)
        assertNotNull(after.lastGood)
        assertEquals("失敗輪不得推進 latest", priorLatestId, after.latest!!.evidenceId)
        assertEquals(1_000L, after.latest!!.observedAt)
        assertEquals(77L, after.latest!!.requestCountRaw)
        assertEquals(priorLatestId, after.lastGood!!.evidenceId)
        assertEquals(77L, after.lastGood!!.requestCountRaw)
        // Unknown != 0: lastGood 是真值 77,不是 null 補 0,也不是被失敗輪清空.
        assertTrue(after.lastGood!!.requestCountRaw != null && after.lastGood!!.requestCountRaw == 77L)
        assertEquals("UNSPECIFIED", after.lastGood!!.counterSemantics)
        val headAfter = db.providerAccountUsageDao().head(scopeKey, kind)!!
        assertEquals(headBefore, headAfter)
        // 同一 scope 下無 2_000L 孤兒 evidence/fact.
        assertTrue(store.history(provider, account).none { it.observedAt == 2_000L })
        assertEquals(0L, countWhere(db, "SELECT COUNT(*) FROM evidence WHERE timestamp = 2000"))

        // Recovery: trigger 移除後 DB 仍可用,新一輪照常落庫推進.
        val recovered = store.record(goodObservation(provider, account, patRef, 3_000L, 79L))
        assertEquals(79L, recovered.latest!!.requestCountRaw)
        assertEquals(79L, recovered.lastGood!!.requestCountRaw)
        assertEquals(2, store.history(provider, account).size)
        assertEquals(2L, count(db, "provider_account_usage_facts"))
        assertEquals(2L, count(db, "evidence"))
    }

    // ===== 2. credential metadata 刪除不得帶走 usage facts/history =====

    @Test
    fun credentialMetadataDeletionKeepsUsageFactsReadable() = runBlocking {
        val db = memoryDb()
        val provider = "usage-txn-cred-provider"
        val account = "usage-txn-cred-account"
        val patRef = "pat-usage-txn-cred"
        seedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        val stored = store.record(goodObservation(provider, account, patRef, 1_000L, 55L))
        val factId = stored.latest!!.evidenceId
        assertEquals(55L, stored.lastGood!!.requestCountRaw)
        assertEquals("UNSPECIFIED", stored.lastGood!!.counterSemantics)
        assertFalse(provider == "local" || account == "local")
        assertFalse(patRef.contains("selected"))

        // 真 DAO 刪除 credential metadata (非 raw SQL 繞過).
        assertNotNull(db.credentialDao().getById(patRef))
        assertEquals(1, db.credentialDao().deleteById(patRef))
        assertNull("credential metadata 應已刪除", db.credentialDao().getById(patRef))

        // Usage facts/history 仍可讀: facts 對 credential 無 FK,只留 provenance 字串.
        val read = store.observation(provider, account)
        assertNotNull(read.latest)
        assertNotNull(read.lastGood)
        assertEquals(factId, read.latest!!.evidenceId)
        assertEquals(factId, read.lastGood!!.evidenceId)
        assertEquals(55L, read.latest!!.requestCountRaw)
        assertEquals("UNSPECIFIED", read.latest!!.counterSemantics)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, read.latest!!.source)
        assertEquals(patRef, read.latest!!.credentialId)
        assertTrue(read.latest!!.accountEndpointObserved)

        val history = store.history(provider, account)
        assertEquals(1, history.size)
        assertEquals(factId, history.first().evidenceId)
        assertEquals(55L, history.first().requestCountRaw)

        val fact = db.providerAccountUsageDao().fact(factId)!!
        assertEquals(provider, fact.providerId)
        assertEquals(account, fact.accountId)
        assertEquals(patRef, fact.credentialId)
        assertNotNull("evidence 不因 credential 刪除消失", db.evidenceDao().getById(factId))
        assertEquals(1L, count(db, "provider_account_usage_facts"))
        assertEquals(1L, count(db, "provider_account_usage_heads"))
        assertEquals(1L, count(db, "evidence"))

        // 結構證明: facts DDL 無 REFERENCES credentials (provenance 只存字串,非 FK).
        val ddl = db.openHelper.readableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'provider_account_usage_facts'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0) ?: ""
        }
        assertFalse("usage facts 不得 FK 到 credentials:$ddl", ddl.contains("credentials"))
        // Unknown != 0:保留的是真值 55,不是刪除後補 0/清空.
        assertEquals(55L, store.observation(provider, account).lastGood!!.requestCountRaw)
    }

    // ===== harness (全部 nested 在本檔;無 HTTP/無 vault/無真 secret) =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    private suspend fun seedScope(
        db: AtlasDatabase,
        provider: String,
        account: String,
        patRef: String,
    ) {
        db.providerDao().insert(ProviderEntity(provider, "P $provider"))
        db.accountDao().insert(AccountEntity(account, provider))
        db.endpointDao().insert(
            EndpointEntity("e-$provider", provider, base(provider), "${base(provider)}/chat/completions"),
        )
        db.credentialDao().insert(CredentialEntity(patRef, account, enabled = true))
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = provider,
                workflowId = "wf-$provider-txn",
                authMode = AuthMode.BEARER,
                credentials = listOf(SafeCredentialRef(patRef, "PAT", CredentialRole.ACCOUNT)),
            ).toEntity(),
        )
    }

    private fun goodObservation(
        provider: String,
        account: String,
        patRef: String,
        observedAt: Long,
        requestCount: Long?,
        baseUrl: String = base(provider),
    ) = AccountQuotaObservation(
        providerId = provider,
        accountId = account,
        patRef = AccountPatRef(patRef),
        validatedBaseUrl = baseUrl,
        capabilities = capsSuccess(observedAt),
        result = NewApiResult.Success(
            evidence(NewApiSnapshotStore.ACCOUNT_SOURCE, observedAt, rc25(), 200),
            NewApiAccountQuota(500_000L, 12_345L, requestCount, TXN_GROUP, 1, 1),
        ),
    )

    private fun capsSuccess(observedAt: Long): NewApiResult<NewApiCapabilities> {
        val version = rc25()
        return NewApiResult.Success(
            evidence(CAPS_SOURCE, observedAt, version, 200),
            NewApiCapabilities(
                version = version,
                quotaPerUnit = null,
                quotaDisplayType = "USD",
                checkinEnabled = true,
                pricingModule = NewApiModuleAccess(true, true),
                certifiedEndpoints = version.certifiedEndpoints,
            ),
        )
    }

    private fun evidence(
        source: String,
        observedAt: Long,
        version: NewApiVersion?,
        httpStatus: Int?,
    ) = NewApiEvidence(
        source = source,
        observedAt = observedAt,
        providerVersion = version?.raw,
        versionSupport = version?.support ?: NewApiVersionSupport.UNKNOWN,
        httpStatus = httpStatus,
    )

    private fun rc25(): NewApiVersion = NewApiVersion.of(NewApiVersion.RC25)

    private fun base(provider: String): String = "https://$provider.example.com/v1"

    private fun count(db: AtlasDatabase, table: String): Long =
        countWhere(db, "SELECT COUNT(*) FROM $table")

    private fun countWhere(db: AtlasDatabase, sql: String): Long {
        db.openHelper.readableDatabase.query(sql).use {
            assertTrue(it.moveToFirst())
            return it.getLong(0)
        }
    }

    private fun isConstraintAbort(error: Throwable?): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            if (cur is SQLiteConstraintException) return true
            cur = cur.cause
        }
        return false
    }

    private companion object {
        const val CAPS_SOURCE = "newapi/api/status"
        const val TXN_GROUP = "usage-txn-group-canary"
    }
}
