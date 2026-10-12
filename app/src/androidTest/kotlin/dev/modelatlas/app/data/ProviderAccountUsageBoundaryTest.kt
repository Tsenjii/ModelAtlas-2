package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NINT-005A caps-success boundary tests（device androidTest；本檔唯一新增）。
 *
 * 契約：TRACEABILITY NINT-005A、Contract §2／§6.4／§23.3、PRODUCT_SPEC §10／§12／§18、
 * RESEARCH_POLICY evidence-first／來源不互冒充、AGENTS 永久鐵律（Unknown!=0、counter
 * 語意 UNSPECIFIED、providerAccount!=local／selectedKey、source／roles safe）。
 *
 * 範圍鎖定（committed 權威＝production 實際：`NewApiSnapshotStore.kt` `accountQuotaObserver`、
 * `AtlasRepository.kt` `providerAccountUsage` 真 caller、`AtlasDatabase.kt` `MIGRATION_12_13`
 * （Room13 usage facts 建立；DB 現 v14）、`docs/TRACEABILITY.md` NINT-005A ACCEPTED）：
 * - 只測 caps Success 路徑的守門硬化：caps evidence 的 source／version／support／HTTP
 *   任一被竄改即零寫入（fail-closed、IllegalArgumentException、facts／heads／evidence
 *   計數不動、typed reader 不動、訊息不回顯 ref）。
 * - 先立一個合法 positive count 好觀測，再做竄改，證明好資料不被污染、壞資料零寫入。
 * - 真 in-memory Room13＋真 ProviderAccountUsageStore；零 HTTP、零 vault、零真 key／PAT、
 *   零 mock 產品資料；夾具全部本檔內嵌（不碰 StoreTest／shared code）。
 */
@RunWith(AndroidJUnit4::class)
class ProviderAccountUsageBoundaryTest {

    private val open = mutableListOf<AtlasDatabase>()

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    // ===== 1. 合法 positive count：精確欄位＋typed reader =====

    @Test
    fun validPositiveCountPersistsExactFields() = runBlocking {
        val db = nestedMemoryDb()
        val provider = "bnd-valid-p1"
        val account = "bnd-valid-a1"
        val patRef = "pat-bnd-valid-1"
        nestedSeedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        val stored = store.record(
            nestedGoodObservation(provider, account, patRef, 1_000L, requestCount = 12_345L),
        )

        val fact = stored.latest!!
        assertNotNull(stored.lastGood)
        assertEquals(stored.latest!!.evidenceId, store.observation(provider, account).latest!!.evidenceId)
        // Exact fields in locked ports order.
        assertEquals(providerAccountUsageScopeKey(provider, account), fact.scopeKey)
        assertEquals(ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name, fact.kind)
        assertEquals(provider, fact.providerId)
        assertEquals(account, fact.accountId)
        assertEquals(patRef, fact.credentialId)
        assertEquals(1_000L, fact.observedAt)
        assertEquals(NewApiStatus.OK.name, fact.status)
        assertEquals(NewApiVersion.RC25, fact.providerVersion)
        assertEquals(200, fact.httpStatus)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, fact.source)
        assertEquals(12_345L, fact.requestCountRaw)
        assertEquals(ProviderAccountUsageStore.COUNTER_SEMANTICS_UNSPECIFIED, fact.counterSemantics)
        assertTrue(fact.accountEndpointObserved)
        assertTrue("usage evidence id 必須是 usage: 前綴", fact.evidenceId.startsWith("usage:"))
        // Counter 語意固定 UNSPECIFIED，不猜 cumulative／window／rate／delta。
        assertFalse(fact.counterSemantics.contains("cumulative", ignoreCase = true))
        // providerAccount scope 自有 namespace：不含 credential／group 哨兵，不等於 quota scope。
        assertFalse(fact.scopeKey.contains(patRef))
        assertFalse(fact.scopeKey.contains(NESTED_GROUP_CANARY))
        assertNotNull(store.observation(provider, account).lastGood)
        assertEquals(12_345L, store.observation(provider, account).lastGood!!.requestCountRaw)
        // Evidence 鏈一致：modelId 空（帳號總量非 per-model）、無 PII。
        val evidence = db.evidenceDao().getById(fact.evidenceId)!!
        assertEquals("", evidence.modelId)
        assertEquals(provider, evidence.providerId)
        assertEquals("OK", evidence.outcome)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, evidence.source)
        assertFalse(evidence.toString().contains(NESTED_GROUP_CANARY))
        // 欄位集合鎖死（locked ports 順序）。
        assertEquals(
            listOf(
                "evidenceId", "scopeKey", "kind", "providerId", "accountId", "credentialId",
                "observedAt", "status", "providerVersion", "httpStatus", "source",
                "requestCountRaw", "counterSemantics", "accountEndpointObserved",
            ),
            nestedColumns(db, "provider_account_usage_facts"),
        )
        // 零副作用：只寫本槽＋evidence。
        assertEquals(1L, nestedCount(db, "provider_account_usage_facts"))
        assertEquals(1L, nestedCount(db, "provider_account_usage_heads"))
        assertEquals(1L, nestedCount(db, "evidence"))
        assertEquals(0L, nestedCount(db, "snapshot_facts"))
        assertEquals(0L, nestedCount(db, "provider_perf_facts"))
        assertEquals(0L, nestedCount(db, "samples"))
    }

    // ===== 2. caps Success 的 source／version／support 竄改：零寫入 =====

    @Test
    fun spoofedCapsSuccessSourceVersionSupportRejectedWithZeroWrites() = runBlocking {
        val db = nestedMemoryDb()
        val provider = "bnd-caps-spoof"
        val account = "bnd-acc-caps-spoof"
        val patRef = "pat-bnd-caps-spoof"
        nestedSeedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        // 先立合法好觀測（positive count），後續竄改不得動它。
        store.record(nestedGoodObservation(provider, account, patRef, 2_000L, requestCount = 7L))
        assertEquals(7L, store.observation(provider, account).lastGood!!.requestCountRaw)

        // 2a：caps evidence source 竄改成 pricing（借別槽來源冒充 status）。
        nestedAssertCapsSpoofRejected(
            db,
            nestedObservation(
                provider, account, patRef,
                nestedCapsSuccessWith(
                    observedAt = 3_000L,
                    source = NewApiSnapshotStore.PRICE_SOURCE,
                    version = nestedRc25(),
                    httpStatus = 200,
                ),
                nestedAccountSuccess(3_000L, requestCount = 99L),
            ),
        )

        // 2b：caps evidence version raw 與 caps value 脫鉤（站方版本漂移冒充同 session）。
        nestedAssertCapsSpoofRejected(
            db,
            nestedObservation(
                provider, account, patRef,
                NewApiResult.Success(
                    NewApiEvidence(NESTED_CAPS_SOURCE, 4_000L, "v9.9.9",
                        NewApiVersionSupport.UNKNOWN, 200),
                    nestedCapsValue(nestedRc25()),
                ),
                nestedAccountSuccess(4_000L, requestCount = 99L),
            ),
        )

        // 2c：caps evidence support 與 raw 脫鉤（raw 是 rc25 但 support 報 UNKNOWN）。
        val supportSpoofed = NewApiEvidence(
            source = NESTED_CAPS_SOURCE,
            observedAt = 5_000L,
            providerVersion = NewApiVersion.RC25,
            versionSupport = NewApiVersionSupport.UNKNOWN,
            httpStatus = 200,
        )
        val capsValue = nestedCapsValue(nestedRc25())
        nestedAssertCapsSpoofRejected(
            db,
            AccountQuotaObservation(
                providerId = provider,
                accountId = account,
                patRef = AccountPatRef(patRef),
                validatedBaseUrl = nestedBase(provider),
                capabilities = NewApiResult.Success(supportSpoofed, capsValue),
                result = nestedAccountSuccess(5_000L, requestCount = 99L),
            ),
        )

        // 好觀測原樣保留，history 只有最初一筆。
        assertEquals(7L, store.observation(provider, account).lastGood!!.requestCountRaw)
        assertEquals(2_000L, store.observation(provider, account).lastGood!!.observedAt)
        assertEquals(1, store.history(provider, account).size)
        assertEquals(1L, nestedCount(db, "provider_account_usage_facts"))
        assertEquals(1L, nestedCount(db, "provider_account_usage_heads"))
        assertEquals(1L, nestedCount(db, "evidence"))
    }

    // ===== 3. caps Success 的 HTTP 竄改（null／非 2xx）：零寫入 =====

    @Test
    fun spoofedCapsSuccessHttpRejectedWithZeroWrites() = runBlocking {
        val db = nestedMemoryDb()
        val provider = "bnd-caps-http"
        val account = "bnd-acc-caps-http"
        val patRef = "pat-bnd-caps-http"
        nestedSeedScope(db, provider, account, patRef)
        val store = ProviderAccountUsageStore(db)

        store.record(nestedGoodObservation(provider, account, patRef, 2_000L, requestCount = 7L))

        // 3a：caps Success 但 evidence 無 HTTP（network／未知版本標記冒充成功觀測）。
        nestedAssertCapsSpoofRejected(
            db,
            nestedObservation(
                provider, account, patRef,
                nestedCapsSuccessWith(
                    observedAt = 3_000L,
                    source = NESTED_CAPS_SOURCE,
                    version = nestedRc25(),
                    httpStatus = null,
                ),
                nestedAccountSuccess(3_000L, requestCount = 99L),
            ),
        )

        // 3b：caps Success 但 evidence 是 500（失敗狀態碼冒充成功觀測）。
        nestedAssertCapsSpoofRejected(
            db,
            nestedObservation(
                provider, account, patRef,
                nestedCapsSuccessWith(
                    observedAt = 4_000L,
                    source = NESTED_CAPS_SOURCE,
                    version = nestedRc25(),
                    httpStatus = 500,
                ),
                nestedAccountSuccess(4_000L, requestCount = 99L),
            ),
        )

        assertEquals(7L, store.observation(provider, account).lastGood!!.requestCountRaw)
        assertEquals(2_000L, store.observation(provider, account).latest!!.observedAt)
        assertEquals(1, store.history(provider, account).size)
        assertEquals(1L, nestedCount(db, "provider_account_usage_facts"))
        assertEquals(1L, nestedCount(db, "provider_account_usage_heads"))
        assertEquals(1L, nestedCount(db, "evidence"))
    }

    // ===== 內嵌夾具（僅本檔、僅測試用；無 HTTP、無 vault、無真 secret） =====

    private fun nestedMemoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    private suspend fun nestedSeedScope(
        db: AtlasDatabase,
        provider: String,
        account: String,
        patRef: String,
    ) {
        db.providerDao().insert(ProviderEntity(provider, "P $provider"))
        db.accountDao().insert(AccountEntity(account, provider))
        db.endpointDao().insert(
            EndpointEntity("e-$provider", provider, nestedBase(provider), "${nestedBase(provider)}/chat/completions"),
        )
        db.credentialDao().insert(CredentialEntity(patRef, account, enabled = true))
        val refs = listOf(SafeCredentialRef(patRef, "PAT", CredentialRole.ACCOUNT))
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = provider,
                workflowId = "wf-$provider-bnd",
                authMode = AuthMode.BEARER,
                credentials = refs,
            ).toEntity(),
        )
    }

    private suspend fun nestedAssertCapsSpoofRejected(
        db: AtlasDatabase,
        observation: AccountQuotaObservation,
    ) {
        val factsBefore = nestedCount(db, "provider_account_usage_facts")
        val headsBefore = nestedCount(db, "provider_account_usage_heads")
        val evidenceBefore = nestedCount(db, "evidence")
        val error = runCatching { ProviderAccountUsageStore(db).record(observation) }.exceptionOrNull()
        assertTrue("caps 竄改必須 fail-closed，實際得到：$error", error is IllegalArgumentException)
        val message = error?.message ?: ""
        assertFalse(
            "拒絕訊息不得回顯 ref：$message",
            message.contains(observation.providerId) ||
                message.contains(observation.accountId) ||
                message.contains(observation.patRef.value),
        )
        assertEquals(factsBefore, nestedCount(db, "provider_account_usage_facts"))
        assertEquals(headsBefore, nestedCount(db, "provider_account_usage_heads"))
        assertEquals(evidenceBefore, nestedCount(db, "evidence"))
    }

    private fun nestedObservation(
        provider: String,
        account: String,
        patRef: String,
        capabilities: NewApiResult<NewApiCapabilities>,
        result: NewApiResult<NewApiAccountQuota>,
    ) = AccountQuotaObservation(
        providerId = provider,
        accountId = account,
        patRef = AccountPatRef(patRef),
        validatedBaseUrl = nestedBase(provider),
        capabilities = capabilities,
        result = result,
    )

    private fun nestedGoodObservation(
        provider: String,
        account: String,
        patRef: String,
        observedAt: Long,
        requestCount: Long?,
    ) = nestedObservation(
        provider, account, patRef,
        nestedCapsSuccessWith(observedAt, NESTED_CAPS_SOURCE, nestedRc25(), 200),
        nestedAccountSuccess(observedAt, requestCount),
    )

    private fun nestedCapsValue(version: NewApiVersion) = NewApiCapabilities(
        version = version,
        quotaPerUnit = null,
        quotaDisplayType = "USD",
        checkinEnabled = true,
        pricingModule = NewApiModuleAccess(true, true),
        certifiedEndpoints = version.certifiedEndpoints,
    )

    private fun nestedCapsSuccessWith(
        observedAt: Long,
        source: String,
        version: NewApiVersion,
        httpStatus: Int?,
    ): NewApiResult<NewApiCapabilities> {
        val capsValue = nestedCapsValue(version)
        return NewApiResult.Success(
            NewApiEvidence(
                source = source,
                observedAt = observedAt,
                providerVersion = version.raw,
                versionSupport = version.support,
                httpStatus = httpStatus,
            ),
            capsValue,
        )
    }

    private fun nestedAccountSuccess(
        observedAt: Long,
        requestCount: Long?,
    ): NewApiResult<NewApiAccountQuota> = NewApiResult.Success(
        NewApiEvidence(
            source = NewApiSnapshotStore.ACCOUNT_SOURCE,
            observedAt = observedAt,
            providerVersion = nestedRc25().raw,
            versionSupport = nestedRc25().support,
            httpStatus = 200,
        ),
        NewApiAccountQuota(500_000L, 12_345L, requestCount, NESTED_GROUP_CANARY, 1, 1),
    )

    private fun nestedRc25(): NewApiVersion = NewApiVersion.of(NewApiVersion.RC25)

    private fun nestedBase(provider: String): String = "https://$provider.example.com/v1"

    private fun nestedCount(db: AtlasDatabase, table: String): Long {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            return it.getLong(0)
        }
    }

    private fun nestedColumns(db: AtlasDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            val index = cursor.getColumnIndexOrThrow("name")
            val out = ArrayList<String>()
            while (cursor.moveToNext()) out += cursor.getString(index)
            out
        }

    private companion object {
        const val NESTED_CAPS_SOURCE = "newapi/api/status"
        const val NESTED_GROUP_CANARY = "bnd-group-canary"
    }
}
