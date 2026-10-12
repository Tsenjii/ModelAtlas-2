package dev.modelatlas.core.newapi

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * NINT-005A core-only：`/api/user/self` 帳號用的**隱私邊界**，跑既有 production
 * [NewApiAdapter.fetchAccountQuota]，不做 core production 變更、不重複 primitive
 * 解析（另見 `NewApiAccountUsageParseTest.kt`）。
 *
 * 契約來源：
 * - `docs/PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md` §2／§6.4／§23.3
 * - `docs/PRODUCT_SPEC.md` §10／§12／§18
 * - docs/TRACEABILITY.md:130「NINT-005A — ACCEPTED（account requestCount backend 子片）」
 *   （綠 `61a2870`／`37099071890`）；privacy test ownership＝本檔 committed 測試本身
 *
 * 被釘住的產品事實：
 * - `/api/user/self` 的 username / email / id / token 等 PII **不是**帳號用量事實；
 *   typed 結果只保留 [NewApiAccountQuota] 的 6 個安全欄位，未知欄位一律忽略，
 *   `request_count` 仍以精確 Long 保留。
 * - server 若把送出的憑證原封 echo 回回應（raw、URL-encoded 或 base64 變體），
 *   既有 production `safeText` / `redactSecrets` guard 必須丟棄該字串；raw body、
 *   server `message` 與原文永不進入 typed result 或 evidence metadata。
 *
 * fixture 全部為 shape-derived 合成 canary（含明確 fake credential）；非產品資料、
 * 非真實站台回應。未知不補 0、counter semantics 固定 UNSPECIFIED。
 */
class NewApiAccountUsagePrivacyTest {
    private lateinit var server: MockWebServer

    private val rc25 = NewApiVersion.of(NewApiVersion.RC25)

    private companion object {
        const val CALL_GUARD_MS = 15_000L
        const val REQUEST_TIMEOUT_SECONDS = 3L

        // 明確 fake credential：只存在測試 fixture，永遠不是真實 key/PAT。
        const val ACCOUNT_PAT_REF = "safe-ref-account-privacy-nint005a"
        const val ACCOUNT_PAT_SECRET = "fixture-fake-pat-privacy-NINT005A-ZZ99AA"
        const val VAULT_REFUSAL = "VAULT_REFUSED_THIS_REF_IS_NOT_BOUND_TO_THIS_ROLE"

        // 回應原文 canary：證明 raw body / server message 不會外洩到 result。
        const val RAW_BODY_CANARY = "CANARY_RAW_BODY_NINT005A_MUST_NOT_LEAK"

        // Account 回應中夾帶的 PII canary；typed result 不得帶出任何一個。
        val PII_CANARIES = listOf(
            "canary-user-name-pii",
            "canary-display-name-pii",
            "canary-mailbox-pii@example.test",
            "canary-access-token-pii",
            "canary-password-pii",
            "canary-stripe-customer-pii",
            "canary-aff-code-pii",
        )
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

    // ---- 1. PII 欄位被忽略，typed request_count 仍精確保留 ----

    @Test
    fun accountSelfPiiFieldsAreIgnoredWhileTypedRequestCountIsPreserved() = runBlocking {
        server.enqueue(ok(piiAccountBody()))

        val vault = RoleVault()
        val subject = adapter(vault)

        val result = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()

        // typed 帳號用量事實保留；未知 PII 不影響 request_count 的 Long 精確值。
        assertEquals("request_count 必須是 provider typed 事實", 42L, result.value.requestCount)
        assertEquals(500_000L, result.value.quota)
        assertEquals(100L, result.value.usedQuota)
        assertEquals("default", result.value.group)
        assertEquals(1, result.value.role)
        assertEquals(1, result.value.status)

        val rendered = result.toString()
        for (canary in PII_CANARIES) {
            assertFalse(
                "PII canary '$canary' 不得出現在 typed result/evidence ($rendered)",
                rendered.contains(canary),
            )
        }
        assertFalse(
            "raw response body / server message 不得進入 result metadata",
            rendered.contains(RAW_BODY_CANARY),
        )

        // evidence 只承載安全 metadata，never raw body / PII。
        assertEquals("newapi/api/user/self", result.evidence.source)
        assertEquals(200, result.evidence.httpStatus)
        assertEquals(NewApiVersion.RC25, result.evidence.providerVersion)
        assertNull("account 端點無 pricing_version", result.evidence.pricingVersion)

        assertEquals(
            listOf(ACCOUNT_PAT_REF + "@" + baseOrigin()),
            vault.asked,
        )
        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/user/self", request.requestUrl?.encodedPath)
        assertEquals("Bearer $ACCOUNT_PAT_SECRET", request.getHeader("Authorization"))
        assertEquals("不得有第二個 user/self 請求", 1, server.requestCount)
    }

    // ---- 2. echo 回憑證被既有真 guard 丟棄，raw body 不進 result ----

    @Test
    fun echoedCredentialIsRejectedByRealGuardAndRawBodyNeverEntersResultMetadata() = runBlocking {
        val base64Secret = Base64.getEncoder().encodeToString(ACCOUNT_PAT_SECRET.toByteArray())
        // 第一個回應在 group 與 message 原封 echo fake credential；
        // 第二個回應在 group echo base64 變體，證明既有 guard 的多變體行為。
        server.enqueue(ok(echoAccountBody("\"group\":\"$ACCOUNT_PAT_SECRET\"")))
        server.enqueue(ok(echoAccountBody("\"group\":\"$base64Secret\"")))

        val vault = RoleVault()
        val subject = adapter(vault)

        val rawEcho = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()
        val encodedEcho = guarded {
            subject.fetchAccountQuota(baseUrl(), AccountPatRef(ACCOUNT_PAT_REF), rc25)
        }.success()

        // 既有 production echo guard 必須拒絕 echo 字串（group 落地為 null）。
        assertNull("raw credential echo 必須被既有 guard 丟棄", rawEcho.value.group)
        assertNull("base64 credential echo 必須被既有 guard 丟棄", encodedEcho.value.group)
        // guard 只丟棄不安全字串，不得讓整筆 typed account 事實消失。
        assertEquals(42L, rawEcho.value.requestCount)
        assertEquals(500_000L, rawEcho.value.quota)

        for (result in listOf(rawEcho, encodedEcho)) {
            val rendered = result.toString()
            assertFalse("raw fake credential 不得外洩", rendered.contains(ACCOUNT_PAT_SECRET))
            assertFalse("base64 fake credential 不得外洩", rendered.contains(base64Secret))
            assertFalse("raw body canary 不得外洩", rendered.contains(RAW_BODY_CANARY))
            assertEquals("newapi/api/user/self", result.evidence.source)
            assertEquals(200, result.evidence.httpStatus)
            assertEquals(NewApiVersion.RC25, result.evidence.providerVersion)
            assertNull(result.evidence.pricingVersion)
        }

        assertEquals(
            listOf(
                ACCOUNT_PAT_REF + "@" + baseOrigin(),
                ACCOUNT_PAT_REF + "@" + baseOrigin(),
            ),
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

    // ---- fixture / harness（全部 test-only，nested）----

    private fun adapter(credentials: CredentialAccess) = NewApiAdapter(
        credentials = credentials,
        clock = FixedEpochClock,
        allowLoopbackHttpForTests = true,
    )

    private fun baseUrl(): String = "http://127.0.0.1:${server.port}/v1"

    private fun baseOrigin(): String = "http://127.0.0.1:${server.port}"

    private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(CALL_GUARD_MS) { block() }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun takeRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "expected a recorded request"
        }

    /**
     * 成功封套，data 內含 typed 帳號欄位與大量未知 PII canary；root message 亦夾帶
     * raw body canary。全部合成，僅供 privacy 邊界測試。
     */
    private fun piiAccountBody(): String = buildString {
        append("{\"success\":true,\"message\":\"")
        append(RAW_BODY_CANARY)
        append("\",\"data\":{\"quota\":500000,\"used_quota\":100,\"request_count\":42,")
        append("\"group\":\"default\",\"role\":1,\"status\":1,")
        append("\"id\":9001,")
        append("\"username\":\"${PII_CANARIES[0]}\",")
        append("\"display_name\":\"${PII_CANARIES[1]}\",")
        append("\"email\":\"${PII_CANARIES[2]}\",")
        append("\"access_token\":\"${PII_CANARIES[3]}\",")
        append("\"password\":\"${PII_CANARIES[4]}\",")
        append("\"stripe_customer\":\"${PII_CANARIES[5]}\",")
        append("\"aff_code\":\"${PII_CANARIES[6]}\"}}")
    }

    /**
     * 其餘 typed 欄位固定，`group` 由呼叫端以完整 JSON 片段注入（raw 或 base64 echo）。
     */
    private fun echoAccountBody(groupField: String): String = buildString {
        append("{\"success\":true,\"message\":\"")
        append(RAW_BODY_CANARY)
        append("\",\"data\":{\"quota\":500000,\"used_quota\":100,\"request_count\":42,")
        append(groupField)
        append(",\"role\":1,\"status\":1}}")
    }

    /** 角色綁定的 vault double：只回傳本角色 ref，並記錄被詢問的 (ref, origin)。 */
    private class RoleVault(
        private val ref: String = ACCOUNT_PAT_REF,
        private val secret: String = ACCOUNT_PAT_SECRET,
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
