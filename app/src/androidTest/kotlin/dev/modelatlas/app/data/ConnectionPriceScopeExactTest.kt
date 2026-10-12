package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PricingScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-004 B1 repo parity（device androidTest；真 in-memory Room＋真
 * [AtlasRepository] connections flow）：鎖定 [AtlasRepository.resolvePriceState]
 * 的 exact scope policy（spec-cr004-result §1.3／§3 vectors 16–19）。
 *
 * - 16：具體 group 的 PRICE head 不得污染無 group 維度的 connection；只命中
 *   null-group head（caller group 未知＝null）。
 * - 17：head.operation 對 persisted `record.protocol` 的 writer 真值
 *   （`operationFromProtocol` 推導）精確比對；別 operation 排除。
 * - 18：真 conflict（未知 scope protocol 下 blank／"UNKNOWN" operation 兩 head
 *   分歧）仍回 `UNKNOWN`；無候選沿用 DAO 舊值。
 * - 19：endpoint 精確守門保留。
 *
 * Head 一律以 typed fixture 直接落庫，scopeKey 經 production
 * [PricingScope.storageKey] 編碼、kind 固定 PRICE；無網路、無 sleep／timeout、
 * 不放寬斷言；fixture 僅本檔，不當產品資料。
 */
@RunWith(AndroidJUnit4::class)
class ConnectionPriceScopeExactTest {

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

    // ===== 16. 具體 group 的 PRICE head 不污染 connection；null-group head 正常投影 =====

    @Test
    fun connectionIgnoresForeignGroupPriceHead() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection(id = "av-group", modelId = "m-group", endpointId = "e-group", priceState = "UNKNOWN"))

        // group="vip" 的 FREE_NOW head：connection 無 group 維度（caller 未知＝null），
        // 必須排除 → 沿用 DAO 舊值 UNKNOWN，不得投影 FREE_NOW。
        seedPriceHead(
            db,
            PricingScope(PROVIDER, ACCOUNT, null, "m-group", "vip", "e-group", "CHAT_COMPLETIONS"),
            state = "FREE_NOW",
            observedAt = 1_000L,
        )
        assertEquals("UNKNOWN", repo.connections.first().single { it.id == "av-group" }.priceState)

        // null-group PAID head：caller 未知只配 null 維 → 精確命中。
        seedPriceHead(
            db,
            PricingScope(PROVIDER, ACCOUNT, null, "m-group", null, "e-group", "CHAT_COMPLETIONS"),
            state = "PAID",
            observedAt = 2_000L,
        )
        assertEquals("PAID", repo.connections.first().single { it.id == "av-group" }.priceState)
    }

    // ===== 17. operation 對 record.protocol 精確 =====

    @Test
    fun connectionOperationExactFromRecordProtocol() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection(id = "av-op", modelId = "m-op", endpointId = "e-op", priceState = "UNKNOWN"))

        // record.protocol＝CHAT_COMPLETIONS（persisted endpoint protocol）：op "text"
        // 的 head 排除 → 沿用 DAO 舊值（若 operation 被忽略會誤投影 FREE_NOW）。
        seedPriceHead(
            db,
            PricingScope(PROVIDER, ACCOUNT, null, "m-op", null, "e-op", "text"),
            state = "FREE_NOW",
            observedAt = 1_000L,
        )
        assertEquals("UNKNOWN", repo.connections.first().single { it.id == "av-op" }.priceState)

        // 同 operation CHAT_COMPLETIONS 的 head 精確命中。
        seedPriceHead(
            db,
            PricingScope(PROVIDER, ACCOUNT, null, "m-op", null, "e-op", "CHAT_COMPLETIONS"),
            state = "PAID",
            observedAt = 2_000L,
        )
        assertEquals("PAID", repo.connections.first().single { it.id == "av-op" }.priceState)
    }

    // ===== 18. conflict 仍 UNKNOWN；無候選保留 DAO 舊值 =====

    @Test
    fun connectionPriceConflictStillUnknownAndNoCandidateKeepsDaoOldValue() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)

        // 真 conflict：persisted protocol 為未知 scope（"UNKNOWN"，寫入端
        // operationFromProtocol 對 blank／非法 protocol 的真值），兩個未知
        // 表示（blank／"UNKNOWN"）的 PRICE head 同 scope 其餘維但 state 分歧
        // → 候選不一致必須回 UNKNOWN（不得挑一列、不得沿用 DAO 舊值 PAID）。
        seedUnknownProtocolConnection(
            db,
            id = "av-conflict",
            modelId = "m-conflict",
            endpointId = "e-conflict",
            priceState = "PAID",
        )
        seedPriceHead(
            db,
            PricingScope("p-conflict", "a-conflict", null, "m-conflict", null, "e-conflict", ""),
            state = "FREE_NOW",
            observedAt = 1_000L,
        )
        seedPriceHead(
            db,
            PricingScope("p-conflict", "a-conflict", null, "m-conflict", null, "e-conflict", "UNKNOWN"),
            state = "PAID",
            observedAt = 2_000L,
        )
        assertEquals("UNKNOWN", repo.connections.first().single { it.id == "av-conflict" }.priceState)

        // 無候選（僅存在 model 不符的 head）→ 沿用 DAO 舊值，不投影他人 state。
        repo.addConnection(connection(id = "av-quiet", modelId = "m-quiet", endpointId = "e-quiet", priceState = "PARTIALLY_FREE"))
        seedPriceHead(
            db,
            PricingScope(PROVIDER, ACCOUNT, null, "m-other", null, "e-quiet", "CHAT_COMPLETIONS"),
            state = "FREE_NOW",
            observedAt = 1_000L,
        )
        assertEquals("PARTIALLY_FREE", repo.connections.first().single { it.id == "av-quiet" }.priceState)
    }

    // ===== 19. endpoint 精確守門保留 =====

    @Test
    fun connectionEndpointExactGuardRetained() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection(id = "av-ep", modelId = "m-ep", endpointId = "e-ep-a", priceState = "UNKNOWN"))

        // 同 provider／account／model／group／operation，但 endpoint 不同 → 排除。
        seedPriceHead(
            db,
            PricingScope(PROVIDER, ACCOUNT, null, "m-ep", null, "e-ep-b", "CHAT_COMPLETIONS"),
            state = "FREE_NOW",
            observedAt = 1_000L,
        )
        assertEquals("UNKNOWN", repo.connections.first().single { it.id == "av-ep" }.priceState)

        // 精確 endpoint → 命中。
        seedPriceHead(
            db,
            PricingScope(PROVIDER, ACCOUNT, null, "m-ep", null, "e-ep-a", "CHAT_COMPLETIONS"),
            state = "PAID",
            observedAt = 2_000L,
        )
        assertEquals("PAID", repo.connections.first().single { it.id == "av-ep" }.priceState)
    }

    // ===== harness（fixture 僅本檔，不當產品資料） =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    private fun connection(
        id: String,
        modelId: String,
        endpointId: String,
        priceState: String,
    ) = ConnectionRecord(
        id = id,
        providerId = PROVIDER,
        accountId = ACCOUNT,
        credentialId = CREDENTIAL,
        endpointId = endpointId,
        name = "CR-004 $id",
        baseUrl = BASE_URL,
        endpointUrl = ENDPOINT_URL,
        modelId = modelId,
        priceState = priceState,
        offeringId = "off-$id",
    )

    /** typed PRICE head 直接落庫：scopeKey 經 production 編碼，evidence／fact／head 三件一致。 */
    private suspend fun seedPriceHead(
        db: AtlasDatabase,
        scope: PricingScope,
        state: String,
        observedAt: Long,
    ) {
        val key = scope.storageKey()
        val evidenceId = "cr004-ev:$key"
        db.evidenceDao().insert(
            EvidenceEntity(
                id = evidenceId,
                requestId = evidenceId,
                providerId = scope.providerId,
                modelId = scope.modelId,
                outcome = "OK",
                status = 200,
                timestamp = observedAt,
                source = NewApiSnapshotStore.PRICE_SOURCE,
            ),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId,
                scopeKey = key,
                kind = SnapshotKind.PRICE.name,
                observedAt = observedAt,
                snapshotId = evidenceId,
                state = state,
                status = "OK",
                confidence = "OFFICIAL",
                providerVersion = null,
                pricingVersion = null,
                itemPricingVersion = null,
                quotaPerUnit = null,
                groupRatio = null,
                statusObservedAt = observedAt - 1,
                remainingRaw = null,
                usedRaw = null,
                grantedRaw = null,
                unlimited = null,
                expiresAtRaw = null,
                expiryMeaning = null,
                expressionHash = null,
                priceUnit = "USD_PER_1K_TOKENS",
            ),
        )
        db.snapshotFactDao().upsertHead(
            SnapshotHeadEntity(key, SnapshotKind.PRICE.name, evidenceId, observedAt, evidenceId, observedAt),
        )
    }

    /** endpoint.protocol＝未知 scope（"UNKNOWN"）的連線列：真 DAO 寫入後經 connections 投影讀回。 */
    private suspend fun seedUnknownProtocolConnection(
        db: AtlasDatabase,
        id: String,
        modelId: String,
        endpointId: String,
        priceState: String,
    ) {
        val providerId = "p-conflict"
        val accountId = "a-conflict"
        val credentialId = "c-conflict"
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P conflict"))
        db.accountDao().insert(AccountEntity(accountId = accountId, providerId = providerId))
        db.credentialDao().insert(CredentialEntity(credentialId = credentialId, accountId = accountId))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = endpointId,
                providerId = providerId,
                baseUrl = BASE_URL,
                endpointUrl = ENDPOINT_URL,
                protocol = "UNKNOWN",
            ),
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = "off-$id",
                providerId = providerId,
                endpointId = endpointId,
                providerModelId = modelId,
                manual = true,
                source = "MANUAL",
            ),
        )
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = id,
                offeringId = "off-$id",
                accountId = accountId,
                credentialId = credentialId,
                priceState = priceState,
                quotaState = "UNKNOWN",
            ),
        )
    }

    companion object {
        private const val PROVIDER = "p-cr004"
        private const val ACCOUNT = "a-cr004"
        private const val CREDENTIAL = "c-cr004"
        private const val BASE_URL = "https://cr004.fixture.invalid/v1"
        private const val ENDPOINT_URL = "https://cr004.fixture.invalid/v1/chat/completions"
    }
}
