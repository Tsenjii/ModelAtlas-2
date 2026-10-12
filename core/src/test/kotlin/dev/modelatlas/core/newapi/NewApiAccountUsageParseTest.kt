package dev.modelatlas.core.newapi

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NINT-005A core-only：`/api/user/self` 帳號 `request_count` 的實際 adapter 解析邊界。
 *
 * 本檔只驗 **既有 production `NewApiAdapter.fetchAccountQuota`** 的 primitive 解析行為，
 * 不做 core production 變更、不放寬既有 quota expectation。fixture 為 shape-derived
 * 合成資料，僅供測試；非產品資料、非真實站台回應。
 *
 * 傳輸面：fetch 傳入 HTTPS fixture base，走真實 `EndpointPolicy.validate`
 *（不開 `allowLoopbackHttpForTests`）；test-only OkHttpClient 的 loopback
 * interceptor 把請求改寫到本地 MockWebServer，不觸碰任何真實站台。
 *
 * 契約來源：
 * - `docs/PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md` §2／§6.4／§23.3
 * - `docs/PRODUCT_SPEC.md` §10／§12／§18
 * - docs/TRACEABILITY.md:130「NINT-005A — ACCEPTED（account requestCount backend 子片）」
 *   （綠 `61a2870`／`37099071890`）；parse test ownership＝本檔 committed 測試本身
 *
 * 已釘住的事實（`request_count` 經 `JsonObject.longOrNull` → kotlinx
 * `parseLongImpl` = JSON 數字語法、非 `toLongOrNull`）：
 * - 真 0 → `0L`（Provider 事實，不是 Unknown）。
 * - 缺欄 / JSON null / 引號字串 → `null`（Unknown，不補 0）。
 * - 小數 / 超出 Long 範圍 → `null`（非 Long；不得四捨五入、截斷）。
 * - 負數 / `Long.MIN_VALUE` → 原樣負值（adapter 不 clamp；store 之後才判 MALFORMED）。
 * - `Long.MAX_VALUE` → 精確。
 *
 * Counter semantics 固定 UNSPECIFIED：本檔不推論累計／窗口／rate／delta，也不把
 * request_count 混成 selected key／model／group 用量；只讀 account endpoint 的事實。
 */
class NewApiAccountUsageParseTest {
    private lateinit var server: MockWebServer

    private val rc25 = NewApiVersion.of(NewApiVersion.RC25)

    private companion object {
        const val CALL_GUARD_MS = 15_000L
        const val REQUEST_TIMEOUT_SECONDS = 3L
        const val ACCOUNT_PAT_REF = "safe-ref-account-pat-nint005a-fixture"
        const val ACCOUNT_PAT_SECRET = "fixture-pat-secret-NINT005A-AA11BB22"
        const val VAULT_REFUSAL = "VAULT_REFUSED_THIS_REF_IS_NOT_BOUND_TO_THIS_ROLE"
    }

    private object FixedEpochClock : Clock {
        const val EPOCH_MILLIS = 1_760_000_000_000L
        override fun epochMillis(): Long = EPOCH_MILLIS
        override fun monotonicMillis(): Long = EPOCH_MILLIS
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- 1. 真 0 與 Long.MAX_VALUE 皆為精確事實 ----

    @Test
    fun zeroAndExactLongMaxRequestCountArePreservedWithoutFabrication() = runBlocking {
        server.enqueue(ok(accountBody("\"request_count\":0")))
        server.enqueue(ok(accountBody("\"request_count\":9223372036854775807")))

        val vault = accountVault()
        val subject = adapter(vault)

        val zero = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()
        assertEquals("真 0 是 provider 事實，不得當成 Unknown", 0L, zero.value.requestCount)
        // 既有 quota 解析不得因新欄位而回歸。
        assertEquals(500000L, zero.value.quota)
        assertEquals(100L, zero.value.usedQuota)

        val max = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()
        assertEquals(
            "Long.MAX_VALUE 必須精確保留，不得截斷或轉成 Unknown",
            Long.MAX_VALUE,
            max.value.requestCount,
        )

        assertEquals("newapi/api/user/self", max.evidence.source)
        assertEquals(200, max.evidence.httpStatus)
        // 只走 account endpoint、只解析一把安全 ref、無額外 HTTP。
        assertEquals(
            listOf(ACCOUNT_PAT_REF + "@" + baseOrigin(), ACCOUNT_PAT_REF + "@" + baseOrigin()),
            vault.asked,
        )
        repeat(2) {
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/user/self", request.requestUrl?.encodedPath)
            assertEquals("Bearer $ACCOUNT_PAT_SECRET", request.getHeader("Authorization"))
        }
        assertEquals(2, server.requestCount)
    }

    // ---- 2. 缺欄 / JSON null / 引號字串 → Unknown(null) ----

    @Test
    fun missingNullAndQuotedStringRequestCountAreUnknownNotZero() = runBlocking {
        server.enqueue(ok(accountBody(requestCountField = null)))
        server.enqueue(ok(accountBody("\"request_count\":null")))
        server.enqueue(ok(accountBody("\"request_count\":\"7\"")))

        val subject = adapter(accountVault())
        val missing = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()
        val explicitNull = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()
        val quoted = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()

        assertNull("缺欄是 Unknown(null)，不是 0", missing.value.requestCount)
        assertNull("JSON null 是 Unknown(null)，不是 0", explicitNull.value.requestCount)
        assertNull("引號字串不是 provider 的數值事實，不得 coercion 成數字", quoted.value.requestCount)
        // 這些既非 MALFORMED，也未汙染其他 account 欄位。
        assertEquals(500000L, quoted.value.quota)
        assertEquals("newapi/api/user/self", quoted.evidence.source)
        assertEquals(200, quoted.evidence.httpStatus)
        assertEquals(3, server.requestCount)
    }

    // ---- 3. 小數 / 溢出 → 非 Long(null)，且不得讓整筆 account MALFORMED ----

    @Test
    fun fractionalAndOverflowRequestCountAreNonLongNullWithoutMalformingAccount() = runBlocking {
        server.enqueue(ok(accountBody("\"request_count\":1.5")))
        server.enqueue(ok(accountBody("\"request_count\":9223372036854775808")))

        val subject = adapter(accountVault())
        val fraction = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()
        val overflow = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()

        assertNull("小數不是 Long 事實，必須 null 而非四捨五入", fraction.value.requestCount)
        assertNull("超出 Long 範圍必須 null 而非截斷", overflow.value.requestCount)
        // 單一欄位非 Long 不得使整筆帳號 quota 解析失敗。
        assertEquals(500000L, fraction.value.quota)
        assertEquals(100L, overflow.value.usedQuota)
        assertEquals("newapi/api/user/self", overflow.evidence.source)
        assertEquals(2, server.requestCount)
    }

    // ---- 4. 負數原樣保留（store 之後才判 MALFORMED）----

    @Test
    fun negativeRequestCountIsPreservedRawForDownstreamStoreRejection() = runBlocking {
        server.enqueue(ok(accountBody("\"request_count\":-5")))
        server.enqueue(ok(accountBody("\"request_count\":-9223372036854775808")))

        val subject = adapter(accountVault())
        val negative = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()
        val minLong = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()

        assertEquals(
            "adapter 必須原樣保留負 raw（由 store 判 MALFORMED），不得 clamp 成 0",
            -5L,
            negative.value.requestCount,
        )
        assertEquals(
            "Long.MIN_VALUE 必須精確保留，不得溢出成 Unknown",
            Long.MIN_VALUE,
            minLong.value.requestCount,
        )
        assertTrue(
            "不得把負 count 偽裝成 0",
            negative.value.requestCount != 0L,
        )
        assertEquals("newapi/api/user/self", negative.evidence.source)
        assertEquals(2, server.requestCount)
    }

    // ---- fixture / harness（全部 test-only）----

    private fun adapter(credentials: CredentialAccess) = NewApiAdapter(
        credentials = credentials,
        client = loopbackTestClient(),
        clock = FixedEpochClock,
    )

    private fun baseUrl(): String = "https://nint005a-fixture.invalid/v1"

    private fun baseOrigin(): String = "https://nint005a-fixture.invalid:443"

    /**
     * test-only 傳輸迂迴：保留真實 HTTPS base（`EndpointPolicy.validate` 照走），
     * 只在 client 層把 scheme/host/port 改寫到本地 MockWebServer。
     * `.invalid` 保證即使改寫失效也不會命中真實站台。
     */
    private fun loopbackTestClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val loopback = original.url.newBuilder()
                .scheme("http")
                .host("127.0.0.1")
                .port(server.port)
                .build()
            chain.proceed(original.newBuilder().url(loopback).build())
        }
        .build()

    private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(CALL_GUARD_MS) { block() }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun takeRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "expected a recorded request"
        }

    /**
     * 合成 `/api/user/self` 成功封套。`requestCountField` 為完整的 JSON 片段
     * （例如 `"request_count":0`、`"request_count":null`、`"request_count":"7"`）；
     * 傳 null 表示「缺欄」，用以區分 missing 與 explicit null。
     */
    private fun accountBody(requestCountField: String? = null): String {
        val fields = buildList {
            add("\"quota\":500000")
            add("\"used_quota\":100")
            add("\"group\":\"default\"")
            add("\"role\":1")
            add("\"status\":1")
            if (requestCountField != null) add(requestCountField)
        }.joinToString(",")
        return "{\"success\":true,\"message\":\"\",\"data\":{$fields}}"
    }

    private fun accountVault() = AccountVault(ACCOUNT_PAT_REF, ACCOUNT_PAT_SECRET)

    /** 角色綁定的 vault double：只回傳本角色 ref，並記錄被詢問的 (ref, origin)。 */
    private class AccountVault(
        private val ref: String,
        private val secret: String,
    ) : CredentialAccess {
        val asked: MutableList<String> = mutableListOf()

        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            if (credentialId != ref) throw IllegalStateException(VAULT_REFUSAL)
            return secret
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> NewApiResult<T>.success(): NewApiResult.Success<T> =
        this as? NewApiResult.Success<T> ?: error("expected NewApiResult.Success, got $this")
}
