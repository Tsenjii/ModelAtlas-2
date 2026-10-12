package dev.modelatlas.core.newapi

import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 測試用的合成 fixture 與 deadline；不得當成產品資料或真實站台回應。
private const val CALL_GUARD_MS = 15_000L
private const val CANCEL_GUARD_MS = 2_000L
private const val REQUEST_TIMEOUT_SECONDS = 3L
private const val VAULT_REFUSAL = "VAULT_REFUSED_THIS_REF_IS_NOT_BOUND_TO_THIS_ROLE"

private const val PAT_REF = "safe-ref-account-pat-fixture"
private const val PAT_SECRET = "fixture-pat-secret-ZZ99XX88"
private const val KEY_REF = "safe-ref-inference-key-fixture"
private const val KEY_SECRET = "fixture-sk-inference-key-ZZ88YY77"
private const val MODEL_FIXTURE = "fixture-model-a"
private const val PRICING_HASH_FIXTURE = "fixture-pricing-hash-0001"

private object FixedEpochClock : Clock {
    const val EPOCH_MILLIS = 1_760_000_000_000L
    override fun epochMillis(): Long = EPOCH_MILLIS
    override fun monotonicMillis(): Long = EPOCH_MILLIS
}

/**
 * `NewApiAdapter` 的 HTTP 邊界與安全邊界單測。
 *
 * 依據 `docs/ADAPTER_NEWAPI_RESEARCH.md`（rc.25 tag + 2026-09-27 實機觀測）與
 * `NewApiAdapter` 的設計原則，逐條釘住：能力解析、版本閘、憑證角色分離、
 * 401/429 不重試不 fallback、redirect 不跟不洩憑證、body 上限、錯誤 body 不讀、
 * notice/about 不帶原文、以及「401 pricing 維持 UNKNOWN ≠ 空清單」。
 */
class NewApiAdapterTest {
    private lateinit var server: MockWebServer

    private val rc25 = NewApiVersion.of(NewApiVersion.RC25)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- 1. `/api/status` 能力解析 ----

    @Test
    fun statusExposesVersionQuotaUnitAndPricingModuleGate() = runBlocking {
        server.enqueue(ok(statusBody(modules = modulesJson(enabled = true, requireAuth = true))))

        val success = guarded { adapter(vaultWithBoth()).fetchCapabilities(baseUrl()) }.success()
        val caps = success.value

        assertEquals(NewApiVersionSupport.CERTIFIED_RC25, caps.version.support)
        assertEquals(NewApiVersion.RC25, caps.version.raw)
        assertDecimal("500000", caps.quotaPerUnit)
        assertEquals("USD", caps.quotaDisplayType)
        assertEquals(true, caps.checkinEnabled)
        // `HeaderNavModules` 是 JSON 字串，必須二次 parse（研究 §3.1）。
        assertEquals(true, caps.pricingModule.enabled)
        assertEquals(true, caps.pricingModule.requireAuth)
        assertEquals(NewApiEndpoint.entries.associateWith { it.path }, caps.certifiedEndpoints)
        assertEquals("newapi/api/status", success.evidence.source)
        assertEquals(200, success.evidence.httpStatus)
        assertEquals(NewApiVersionSupport.CERTIFIED_RC25, success.evidence.versionSupport)
        assertEquals(FixedEpochClock.EPOCH_MILLIS, success.evidence.observedAt)
        assertEquals("/api/status", takeRequest().path)
    }

    @Test
    fun statusKeepsAbsentFieldsUnknownAndGivesUnknownVersionNoEndpoints() = runBlocking {
        server.enqueue(
            ok(
                statusBody(
                    version = "v0.9.9-fictional",
                    quotaPerUnit = null,
                    quotaDisplayType = null,
                    checkinEnabled = null,
                ),
            ),
        )

        val caps = guarded { adapter(vaultWithBoth()).fetchCapabilities(baseUrl()) }.success().value

        assertEquals(NewApiVersionSupport.UNKNOWN, caps.version.support)
        assertEquals("v0.9.9-fictional", caps.version.raw)
        // 未知欄位是 null，不是 0 也不是預設值。
        assertNull(caps.quotaPerUnit)
        assertNull(caps.quotaDisplayType)
        assertNull(caps.checkinEnabled)
        assertNull(caps.pricingModule.enabled)
        assertNull(caps.pricingModule.requireAuth)
        assertTrue("未知版本不得提供任何 version-specific endpoint", caps.certifiedEndpoints.isEmpty())
    }

    @Test
    fun unreadableStatusBodyIsMalformedNotAnEmptyCapabilitySet() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":"not-an-object"}"""))

        val result = guarded { adapter(vaultWithBoth()).fetchCapabilities(baseUrl()) }

        assertEquals(NewApiStatus.MALFORMED, result.failure().status)
        assertEquals(200, result.failure().evidence.httpStatus)
    }

    // ---- 2. 未知版本不發 pricing（也不發任何受閘端點）----

    @Test
    fun unknownVersionNeverIssuesPricingNorResolvesItsCredential() = runBlocking {
        val vault = vaultWithBoth()

        val result = guarded {
            adapter(vault).fetchPricing(baseUrl(), AccountPatRef(PAT_REF), NewApiVersion.of("v0.9.9-fictional"))
        }

        assertEquals(NewApiStatus.UNKNOWN_VERSION, result.failure().status)
        assertEquals(0, server.requestCount)
        assertTrue("未認證版本連憑證都不該解析", vault.asked.isEmpty())
    }

    @Test
    fun unknownVersionGatesEveryVersionSpecificEndpoint() = runBlocking {
        val vault = vaultWithBoth()
        val subject = adapter(vault)
        val unknown = NewApiVersion.of(NewApiVersion.RC25.replace("rc.25", "rc.26"))
        val base = baseUrl()

        val statuses = listOf(
            subject.fetchNotice(base, unknown).failure().status,
            subject.fetchPricing(base, AccountPatRef(PAT_REF), unknown).failure().status,
            subject.fetchAccountQuota(base, AccountPatRef(PAT_REF), unknown).failure().status,
            subject.fetchTokenQuota(base, InferenceKeyRef(KEY_REF), unknown).failure().status,
        )

        assertEquals(List(4) { NewApiStatus.UNKNOWN_VERSION }, statuses)
        assertEquals(0, server.requestCount)
        assertTrue(vault.asked.isEmpty())
    }

    // ---- 3. PAT 與 inference key 分離、不可互換 ----

    @Test
    fun patAuthorisesDashboardEndpointsAndInferenceKeyAuthorisesTokenUsage() = runBlocking {
        server.enqueue(ok(pricingBody()))
        val patVault = vaultWithPat()
        val pricing = guarded {
            adapter(patVault).fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }.success()
        val pricingRequest = takeRequest()

        assertEquals("GET", pricingRequest.method)
        assertEquals("/api/pricing", pricingRequest.path)
        assertEquals("Bearer $PAT_SECRET", pricingRequest.getHeader("Authorization"))
        assertEquals(PAT_REF + "@" + baseOrigin(), patVault.asked.single())
        assertEquals(PRICING_HASH_FIXTURE, pricing.value.pricingVersion)
        assertEquals(PRICING_HASH_FIXTURE, pricing.evidence.pricingVersion)
        assertEquals(1, pricing.value.items.size)
        assertEquals(MODEL_FIXTURE, pricing.value.items.single().modelName)
        assertDecimal("1.5", pricing.value.items.single().modelRatio)
        assertEquals(listOf("openai"), pricing.value.items.single().supportedEndpointTypes)
        assertEquals("/v1/chat/completions", pricing.value.supportedEndpoint.getValue("openai").path)
        assertEquals("POST", pricing.value.supportedEndpoint.getValue("openai").method)
        // `group_ratio` 的值可能是 number 或字串（研究 §3.6），不可猜數值。
        assertDecimal("1", pricing.value.groupRatio.getValue("default").exact)
        assertTrue(pricing.value.groupRatio.getValue("auto").nonNumeric)
        assertNull(pricing.value.groupRatio.getValue("auto").exact)

        server.enqueue(ok(tokenUsageBody()))
        val keyVault = vaultWithKey()
        val quota = guarded {
            adapter(keyVault).fetchTokenQuota(baseUrl(), InferenceKeyRef(KEY_REF), rc25)
        }.success()
        val keyRequest = takeRequest()

        assertEquals("/api/usage/token/", keyRequest.path)
        assertEquals("Bearer $KEY_SECRET", keyRequest.getHeader("Authorization"))
        assertEquals(KEY_REF + "@" + baseOrigin(), keyVault.asked.single())
        assertEquals(QuotaState.QUOTA_AVAILABLE, quota.value.state)
        assertEquals(NewApiExpiryMeaning.ZERO_AMBIGUOUS, quota.value.expiryMeaning)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun patAndInferenceKeyAreNotInterchangeable() = runBlocking {
        val patOnly = vaultWithPat()
        val keyOnly = vaultWithKey()

        // 拿 PAT 的 safe ref 去當 inference key（反之亦然）：角色綁定的 vault 必須拒絕。
        val patAsKey = guarded {
            adapter(keyOnly).fetchTokenQuota(baseUrl(), InferenceKeyRef(PAT_REF), rc25)
        }
        val keyAsPat = guarded {
            adapter(patOnly).fetchPricing(baseUrl(), AccountPatRef(KEY_REF), rc25)
        }

        assertEquals(NewApiStatus.CREDENTIAL_UNAVAILABLE, patAsKey.failure().status)
        assertEquals(NewApiStatus.CREDENTIAL_UNAVAILABLE, keyAsPat.failure().status)
        assertEquals("憑證解析失敗時一個 request 都不該送出", 0, server.requestCount)
        assertEquals(1, patOnly.asked.size)
        assertEquals(1, keyOnly.asked.size)
        assertFalse("結果不得帶出 PAT", patAsKey.toString().contains(PAT_SECRET))
        assertFalse("結果不得帶出 inference key", keyAsPat.toString().contains(KEY_SECRET))
        assertFalse("vault 內部錯誤不得外洩", patAsKey.toString().contains(VAULT_REFUSAL))
        assertFalse("vault 內部錯誤不得外洩", keyAsPat.toString().contains(VAULT_REFUSAL))
    }

    @Test
    fun blankOrControlCharacterCredentialsAreRefusedBeforeAnyRequest() = runBlocking {
        val vault = CredentialAccess { _, _ -> "\n" }
        val result = guarded {
            adapter(vault).fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }

        assertEquals(NewApiStatus.CREDENTIAL_UNAVAILABLE, result.failure().status)
        assertEquals(0, server.requestCount)
    }

    // ---- 4. 401 / 429 不重試、不 fallback ----

    @Test
    fun unauthorizedPricingIsNotRetriedAndDoesNotFallBack() = runBlocking {
        // 錯誤 body 刻意 echo 憑證：adapter 不得把它帶出。
        server.enqueue(errorResponse(401, """{"success":false,"code":"AUTH_UNAUTHORIZED","message":"$PAT_SECRET"}"""))
        // 第二份是健康回應：任何 retry 都會把它消耗掉並回 Success。
        server.enqueue(ok(pricingBody()))

        val result = guarded {
            adapter(vaultWithPat()).fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }
        val failure = result.failure()

        assertEquals(NewApiStatus.HTTP_UNAUTHORIZED, failure.status)
        assertEquals(401, failure.evidence.httpStatus)
        assertEquals(1, server.requestCount)
        assertFalse("server message 不得外洩", result.toString().contains(PAT_SECRET))
    }

    @Test
    fun rateLimitedTokenUsageIsNotRetriedAndDoesNotFallBack() = runBlocking {
        server.enqueue(errorResponse(429, """{"success":false,"message":"rate limited"}"""))
        server.enqueue(ok(tokenUsageBody()))

        val result = guarded {
            adapter(vaultWithKey()).fetchTokenQuota(baseUrl(), InferenceKeyRef(KEY_REF), rc25)
        }
        val failure = result.failure()

        assertEquals(NewApiStatus.HTTP_TOO_MANY_REQUESTS, failure.status)
        assertEquals(429, failure.evidence.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun disabledPricingModuleAndRejectedAccountBothFailClosed() = runBlocking {
        val patAdapter = adapter(vaultWithPat())

        server.enqueue(errorResponse(403, """{"success":false,"message":"pricing is disabled"}"""))
        val disabled = guarded {
            patAdapter.fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }
        assertEquals(NewApiStatus.HTTP_FORBIDDEN, disabled.failure().status)

        server.enqueue(errorResponse(401, """{"success":false,"code":"AUTH_UNAUTHORIZED","message":"x"}"""))
        val account = guarded {
            patAdapter.fetchAccountQuota(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }
        assertEquals(NewApiStatus.HTTP_UNAUTHORIZED, account.failure().status)
        assertEquals(2, server.requestCount)
    }

    // ---- 5. redirect 不跟、不洩憑證 ----

    @Test
    fun crossOriginRedirectIsNotFollowedAndNoCredentialReachesTheOtherOrigin() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://127.0.0.1:${other.port}/api/pricing"),
            )

            val result = guarded {
                adapter(vaultWithPat()).fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
            }
            val failure = result.failure()

            assertEquals(NewApiStatus.HTTP_REDIRECT, failure.status)
            assertEquals(302, failure.evidence.httpStatus)
            assertEquals(1, server.requestCount)
            assertEquals(0, other.requestCount)
            assertNull(other.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("Bearer $PAT_SECRET", takeRequest().getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun sameOriginRedirectIsAlsoNotFollowed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", baseUrl() + "/api/pricing"),
        )

        val result = guarded {
            adapter(vaultWithPat()).fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }

        assertEquals(NewApiStatus.HTTP_REDIRECT, result.failure().status)
        assertEquals(1, server.requestCount)
    }

    // ---- 6. body 上限、錯誤 body 不讀 ----

    @Test
    fun oversizedSuccessBodyIsRejectedInsteadOfBuffered() = runBlocking {
        val padded = statusBody(modules = modulesJson(enabled = true, requireAuth = true))
            .dropLast(1) + ",\"logo\":\"" + "a".repeat(300_000) + "\"}"
        server.enqueue(ok(padded))

        val result = guarded { adapter(vaultWithBoth()).fetchCapabilities(baseUrl()) }

        assertEquals(NewApiStatus.RESPONSE_TOO_LARGE, result.failure().status)
        assertEquals(200, result.failure().evidence.httpStatus)
    }

    @Test
    fun errorBodyIsNeverReadEvenWhenItExceedsTheSizeLimit() = runBlocking {
        // 若錯誤 body 被讀取，結果會是 RESPONSE_TOO_LARGE；必須維持 HTTP_UNAUTHORIZED。
        val noise = "e".repeat(512_000)
        server.enqueue(errorResponse(401, """{"success":false,"message":"$noise $PAT_SECRET"}"""))

        val result = guarded {
            adapter(vaultWithPat()).fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }

        assertEquals(NewApiStatus.HTTP_UNAUTHORIZED, result.failure().status)
        assertFalse("錯誤 body 不得被讀出或外洩", result.toString().contains(PAT_SECRET))
    }

    // ---- 7. notice / about 不帶原文 ----

    @Test
    fun noticeReturnsPresenceOnlyAndDropsServerAuthoredText() = runBlocking {
        val hostile = "TG: https://t.me/fixture-channel<b>Station</b><script>alert(1)</script>"
        server.enqueue(ok("""{"success":true,"message":"","data":"$hostile"}"""))

        val success = guarded { adapter(vaultWithBoth()).fetchNotice(baseUrl(), rc25) }.success()

        assertTrue(success.value.present)
        val rendered = success.toString()
        assertFalse("站方公告原文不得離開 adapter", rendered.contains("t.me"))
        assertFalse("站方公告原文不得離開 adapter", rendered.contains("script"))
        assertFalse("站方公告原文不得離開 adapter", rendered.contains("TG:"))
        assertEquals("newapi/api/notice", success.evidence.source)
    }

    @Test
    fun absentNoticeAndRejectedEnvelopeAreDistinctFromPresence() = runBlocking {
        server.enqueue(ok("""{"success":true,"message":"","data":null}"""))
        val absent = guarded { adapter(vaultWithBoth()).fetchNotice(baseUrl(), rc25) }.success()
        assertFalse("data 為 null 是『沒有公告』，不是解析失敗", absent.value.present)

        server.enqueue(ok("""{"success":false,"message":"nope"}"""))
        val rejected = guarded { adapter(vaultWithBoth()).fetchNotice(baseUrl(), rc25) }
        assertEquals(NewApiStatus.MALFORMED, rejected.failure().status)
    }

    @Test
    fun aboutHtmlIsNeverRequestedNorSurfaced() = runBlocking {
        // 刻意塞站方可完全控制的 HTML（含外站連結），模擬研究 §3.3 的 `/api/about` 回應。
        val aboutHtml = "<style>body{color:red}</style><a href='https://evil.fixture.invalid'>x</a>"
        server.enqueue(ok("""{"success":true,"message":"","data":"$aboutHtml"}"""))

        val notice = guarded { adapter(vaultWithBoth()).fetchNotice(baseUrl(), rc25) }.success()

        assertTrue(notice.value.present)
        assertEquals("/api/notice", takeRequest().path)
        assertEquals(1, server.requestCount)
        assertFalse("站方介紹原文不得離開 adapter", notice.toString().contains("evil.fixture.invalid"))
        assertFalse(
            "adapter 不得對 /api/about 發請求（僅存在旗標，不落地原文）",
            NewApiEndpoint.entries.any { it.path.contains("about") },
        )
    }

    // ---- 8. 401 pricing 維持 UNKNOWN（與權威空清單不同）----

    @Test
    fun unauthorizedPricingStaysUnknownAndIsDistinctFromAnAuthoritativeEmptyList() = runBlocking {
        val patAdapter = adapter(vaultWithPat())

        server.enqueue(errorResponse(401, """{"success":false,"code":"AUTH_TOKEN_EXPIRED","message":"expired"}"""))
        val rejected = guarded {
            patAdapter.fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }
        val failure = rejected.failure()

        assertEquals(NewApiStatus.HTTP_UNAUTHORIZED, failure.status)
        assertEquals("newapi/api/pricing", failure.evidence.source)
        assertEquals(401, failure.evidence.httpStatus)
        assertNull("被拒絕的讀取不得宣稱任何 pricing version", failure.evidence.pricingVersion)
        assertFalse("被拒絕的讀取不得產出價格事實", rejected is NewApiResult.Success)

        server.enqueue(ok("""{"success":true,"data":[],"pricing_version":"fixture-pricing-hash-0002"}"""))
        val empty = guarded {
            patAdapter.fetchPricing(baseUrl(), AccountPatRef(PAT_REF), rc25)
        }.success()

        assertTrue("data:[] 是官方事實的空清單，不是 UNKNOWN", empty.value.items.isEmpty())
        assertEquals(0, empty.value.skippedItems)
        assertEquals("fixture-pricing-hash-0002", empty.value.pricingVersion)
        assertEquals(2, server.requestCount)
    }

    // ---- 9. 停滯的 server 不得讓呼叫端 hang（取消 / withTimeout 都走同一條路徑）----
    //
    // 一律用 guard（呼叫端 deadline）而非等待 adapter 內建的 20s read timeout：
    // `MockResponse.setHeadersDelay` 會讓 server 的 handler 執行緒停在 `Thread.sleep`，
    // tearDown 中斷它時會丟 IOException，且整個測試要多等 20s。
    // 因此改用 `SocketPolicy.NO_RESPONSE`（server 永遠不寫任何位元組，也不佔 sleep 計時器）。
    //
    // 未覆蓋項（誠實標記，不用假造的斷言掩蓋）：`NewApiStatus.TIMEOUT` 的實際映射。
    // adapter 在 `client.newBuilder()` 硬編 20s read timeout，要走真逾時必須真的等 20s，
    // 且會留下一個停滯的連線。本檔只證明「呼叫端 deadline / 取消能穿透到 socket，且連線被釋放」。

    @Test
    fun cancellationOrTimeoutUnblocksPromptlyAndReleasesTheConnection() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            adapter(vaultWithBoth()).fetchCapabilities(baseUrl())
        }
        // 先等請求真的抵達再取消，否則這個測試可能只在測「還沒送出去的請求」。
        val deadline = System.currentTimeMillis() + 5_000L
        while (server.requestCount == 0 && System.currentTimeMillis() < deadline) delay(50L)
        assertEquals("請求必須真的送到 server，否則這個測試沒測到東西", 1, server.requestCount)

        // join 必須在 guard 內完成：被取消的呼叫不得停在 socket 上。
        withTimeout(CANCEL_GUARD_MS) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)

        // 被取消的呼叫釋放了連線：後續請求仍能正常完成（也是 tearDown 乾淨的前提）。
        server.enqueue(ok(statusBody()))
        val recovered = guarded { adapter(vaultWithBoth()).fetchCapabilities(baseUrl()) }.success()
        assertEquals(NewApiVersion.RC25, recovered.value.version.raw)
        assertEquals(2, server.requestCount)
    }

    // ---- fixture / harness ----

    private fun adapter(credentials: CredentialAccess) = NewApiAdapter(
        credentials = credentials,
        clock = FixedEpochClock,
        allowLoopbackHttpForTests = true,
    )

    private fun baseUrl(mock: MockWebServer = server, path: String = "/v1"): String =
        "http://127.0.0.1:${mock.port}$path"

    private fun baseOrigin(mock: MockWebServer = server): String = "http://127.0.0.1:${mock.port}"

    private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(CALL_GUARD_MS) { block() }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // 不命名為 `error`，避免遮蔽 kotlin.error。
    private fun errorResponse(code: Int, body: String = "") = MockResponse().setResponseCode(code).setBody(body)

    private fun takeRequest(mock: MockWebServer = server): RecordedRequest =
        requireNotNull(mock.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "expected a recorded request"
        }

    private fun assertDecimal(expected: String, actual: BigDecimal?, label: String = "decimal") {
        assertNotNull("$label 必須是已知值，未知時為 null 而不是 0", actual)
        assertEquals("$label 不符", 0, BigDecimal(expected).compareTo(actual))
    }

    /** 角色綁定的 vault double：只回傳本角色持有的 ref，且記錄被詢問的 (ref, origin)。 */
    private class RoleVault(private val values: Map<String, String>) : CredentialAccess {
        val asked: MutableList<String> = mutableListOf()

        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            return values[credentialId] ?: throw IllegalStateException(VAULT_REFUSAL)
        }
    }

    private fun vaultWithPat() = RoleVault(mapOf(PAT_REF to PAT_SECRET))

    private fun vaultWithKey() = RoleVault(mapOf(KEY_REF to KEY_SECRET))

    private fun vaultWithBoth() = RoleVault(mapOf(PAT_REF to PAT_SECRET, KEY_REF to KEY_SECRET))

    private fun modulesJson(enabled: Boolean, requireAuth: Boolean) =
        "{\\\"pricing\\\":{\\\"enabled\\\":$enabled,\\\"requireAuth\\\":$requireAuth}}"

    private fun statusBody(
        version: String? = NewApiVersion.RC25,
        quotaPerUnit: String? = "500000",
        quotaDisplayType: String? = "\"USD\"",
        checkinEnabled: String? = "true",
        modules: String? = null,
    ): String {
        val data = buildList {
            add("\"version\":" + (version?.let { "\"$it\"" } ?: "null"))
            if (quotaPerUnit != null) add("\"quota_per_unit\":$quotaPerUnit")
            if (quotaDisplayType != null) add("\"quota_display_type\":$quotaDisplayType")
            if (checkinEnabled != null) add("\"checkin_enabled\":$checkinEnabled")
            if (modules != null) add("\"HeaderNavModules\":\"$modules\"")
        }.joinToString(",", prefix = "{", postfix = "}")
        return "{\"success\":true,\"message\":\"\",\"data\":$data}"
    }

    private fun pricingBody(
        modelName: String = MODEL_FIXTURE,
        pricingVersion: String = PRICING_HASH_FIXTURE,
    ) = """{"success":true,"message":"","data":[{"model_name":"$modelName","quota_type":0,"model_ratio":1.5,"completion_ratio":2,"enable_groups":["all"],"supported_endpoint_types":["openai"]}],"group_ratio":{"default":1,"auto":"auto-localized-ratio"},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"$pricingVersion"}"""

    private fun tokenUsageBody() =
        """{"code":true,"message":"ok","data":{"object":"token_usage","name":"fixture-key-label","total_granted":1000,"total_used":400,"total_available":600,"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""

    @Suppress("UNCHECKED_CAST")
    private fun <T> NewApiResult<T>.success(): NewApiResult.Success<T> =
        this as? NewApiResult.Success<T> ?: error("expected NewApiResult.Success, got $this")

    private fun NewApiResult<*>.failure(): NewApiResult.Failure =
        this as? NewApiResult.Failure ?: error("expected NewApiResult.Failure, got $this")
}
