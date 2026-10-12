package dev.modelatlas.core.newapi

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `NewApiAdapter` perf-metrics core fetch 單測（NINT-003 core-only）。
 *
 * fixture 為 shape-derived 合成資料，依據：
 * - `docs/PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md` §6.1–§6.2 + §2（來源分離）
 * - PERF-SHAPE-B `docs/ADAPTER_NEWAPI_PERF_RESEARCH.md`「精確 rc.25 JSON shape」
 *   ＋「不可推導／不可填補」，upstream rc.25 tag `v1.0.0-rc.25` = `f1164142`
 * - PERF-SHAPE-A 同 doc「路由與認證」（路由／條件認證／params）
 *
 * 本檔只驗 core adapter；persist / wire / UI（NINT-004）不在範圍。
 */
class NewApiPerfAdapterTest {
    private lateinit var server: MockWebServer

    private val rc25 = NewApiVersion.of(NewApiVersion.RC25)

    // 檔域私有用名改以 class 內 companion／nested object 承載，避免與同 package
    // 既有 `NewApiAdapterTest.kt` 的 top-level 私有 fixture 重名（同片紅頭修復）。
    private companion object {
        const val CALL_GUARD_MS = 15_000L
        const val REQUEST_TIMEOUT_SECONDS = 3L
        const val VAULT_REFUSAL = "VAULT_REFUSED_THIS_REF_IS_NOT_BOUND_TO_THIS_ROLE"

        const val PAT_REF = "safe-ref-account-pat-fixture"
        const val PAT_SECRET = "fixture-pat-secret-ZZ99XX88"
        const val KEY_REF = "safe-ref-inference-key-fixture"
        const val KEY_SECRET = "fixture-sk-inference-key-ZZ88YY77"
        const val MODEL_FIXTURE = "fixture-model-a"
        const val SCHEMA_FIXTURE = "fixture-schema-opaque-01"
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

    // ---- 1. summary 200 shape ----

    @Test
    fun perfSummaryParsesRc25ShapePreservingPercentAndZero() = runBlocking {
        server.enqueue(ok(perfSummaryBody()))
        val vault = vaultWithPat()

        val success = guarded {
            adapter(vault).fetchPerfSummary(baseUrl(), rc25, pat = AccountPatRef(PAT_REF))
        }.success()

        val models = success.value.models
        assertEquals(2, models.size)
        val modelA = models[0]
        assertEquals(MODEL_FIXTURE, modelA.modelName)
        assertEquals(1234L, modelA.avgLatencyMs)
        // percent 0–100 passthrough；永不除以 100。
        assertEquals(98.37, modelA.successRate, 0.0)
        assertEquals(42.5, modelA.avgTps, 0.0)
        assertEquals(listOf(100.0, 99.5, 98.37), modelA.recentSuccessRates)
        assertNull(
            "rc.25 request_count 為 json:\"-\"，缺失必須是 Unknown(null)，不是 0",
            modelA.requestCount,
        )
        // 0 保留為 0（rc.25 無樣本/真 0ms 不可區分）；不得猜成 null。
        val modelB = models[1]
        assertEquals(0L, modelB.avgLatencyMs)
        assertEquals(0.0, modelB.successRate, 0.0)
        assertEquals(0.0, modelB.avgTps, 0.0)
        assertNull("omitempty key 缺失 ⇒ null（與顯式 [] 不同）", modelB.recentSuccessRates)

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/perf-metrics/summary", request.requestUrl?.encodedPath)
        assertEquals("24", request.requestUrl?.queryParameter("hours"))
        assertNull("summary 不得帶 model param", request.requestUrl?.queryParameter("model"))
        assertNull("summary 不得帶 group param", request.requestUrl?.queryParameter("group"))
        assertEquals("Bearer $PAT_SECRET", request.getHeader("Authorization"))
        assertEquals("newapi/api/perf-metrics/summary", success.evidence.source)
        assertEquals(200, success.evidence.httpStatus)
        assertEquals(FixedEpochClock.EPOCH_MILLIS, success.evidence.observedAt)
    }

    @Test
    fun perfSummaryRejectsServerEchoOfSentCredential() = runBlocking {
        // F-01 回歸：已送出 PAT 時，server 在 model_name 回顯憑證必須整筆 MALFORMED。
        server.enqueue(
            ok(
                """{"success":true,"data":{"models":[{"model_name":"$PAT_SECRET","avg_latency_ms":1,""" +
                    """"success_rate":100,"avg_tps":1}]}}""",
            ),
        )

        val result = guarded {
            adapter(vaultWithPat()).fetchPerfSummary(baseUrl(), rc25, pat = AccountPatRef(PAT_REF))
        }

        assertEquals(NewApiStatus.MALFORMED, result.failure().status)
        assertFalse(result.toString().contains(PAT_SECRET))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun perfSummaryIgnoresMainOnlyAndUnknownKeysButNeverFabricatesRecentRates() = runBlocking {
        // main-only：summary / window_* / recent_success_series；未知 key 一律忽略。
        server.enqueue(
            ok(
                """{"success":true,"data":{"summary":{"avg_latency_ms":9},"window_start":1,"window_end":2,""" +
                    """"future_key":{"x":1},"models":[{"model_name":"$MODEL_FIXTURE","avg_latency_ms":10,""" +
                    """"success_rate":100,"avg_tps":1,"recent_success_series":[{"ts":1,"success_rate":100}]}]}}""",
            ),
        )

        val model = guarded { adapter(vaultWithBoth()).fetchPerfSummary(baseUrl(), rc25) }
            .success().value.models.single()

        assertEquals(10L, model.avgLatencyMs)
        assertNull(
            "main-only recent_success_series 不得冒充 rc.25 recent_success_rates",
            model.recentSuccessRates,
        )
    }

    @Test
    fun perfSummaryExplicitEmptyModelsIsAuthoritativeEmptyNotMalformed() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":{"models":[]}}"""))

        val success = guarded { adapter(vaultWithBoth()).fetchPerfSummary(baseUrl(), rc25) }.success()

        assertTrue(success.value.models.isEmpty())
    }

    @Test
    fun perfRecentRatesDistinguishAbsentFromExplicitEmpty() = runBlocking {
        server.enqueue(
            ok(
                """{"success":true,"data":{"models":[{"model_name":"$MODEL_FIXTURE","avg_latency_ms":5,""" +
                    """"success_rate":50,"avg_tps":1,"recent_success_rates":[]}]}}""",
            ),
        )

        val model = guarded { adapter(vaultWithBoth()).fetchPerfSummary(baseUrl(), rc25) }
            .success().value.models.single()

        assertEquals(emptyList<Double>(), model.recentSuccessRates)
        assertNull(model.requestCount)
    }

    // ---- 2. per-model query 200 shape ----

    @Test
    fun perfQueryParsesGroupsSeriesSchemaAndEpochSeconds() = runBlocking {
        server.enqueue(ok(perfQueryBody()))

        val success = guarded {
            adapter(vaultWithBoth()).fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25, group = "free")
        }.success()

        val query = success.value
        assertEquals(MODEL_FIXTURE, query.modelName)
        // opaque schema passthrough：不驗證、不寫死 rc.25 常數。
        assertEquals(SCHEMA_FIXTURE, query.seriesSchema)
        val group = query.groups.single()
        assertEquals("free", group.group)
        assertEquals(120L, group.avgTtftMs)
        assertEquals(2500L, group.avgLatencyMs)
        assertEquals(87.654321, group.successRate, 0.0)
        assertEquals(33.25, group.avgTps, 0.0)
        val first = group.series[0]
        assertEquals(1_760_000_000L, first.tsEpochSeconds)
        assertEquals(0L, first.avgTtftMs)
        assertEquals(2300L, first.avgLatencyMs)
        assertEquals(99.9, first.successRate, 0.0)
        val second = group.series[1]
        assertEquals(1_760_003_600L, second.tsEpochSeconds)
        assertEquals(150L, second.avgTtftMs)

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/perf-metrics", request.requestUrl?.encodedPath)
        assertEquals(MODEL_FIXTURE, request.requestUrl?.queryParameter("model"))
        assertEquals("free", request.requestUrl?.queryParameter("group"))
        assertEquals("24", request.requestUrl?.queryParameter("hours"))
        assertEquals("newapi/api/perf-metrics", success.evidence.source)
        assertEquals(200, success.evidence.httpStatus)
    }

    @Test
    fun perfQueryUnknownModelIsTypedEmptyNotError() = runBlocking {
        // rc.25：未知 model ⇒ 200 + groups:[]，不是 404/error。
        server.enqueue(
            ok("""{"success":true,"data":{"model_name":"fixture-model-missing","series_schema":"$SCHEMA_FIXTURE","groups":[]}}"""),
        )

        val success = guarded {
            adapter(vaultWithBoth()).fetchPerfQuery(baseUrl(), "fixture-model-missing", rc25)
        }.success()

        assertTrue(success.value.groups.isEmpty())
    }

    // ---- 3. 輸入驗證：model 必填、hours 正規化、group passthrough ----

    @Test
    fun perfQueryBlankModelIsRejectedBeforeAnyRequestOrCredentialUse() = runBlocking {
        val vault = vaultWithPat()
        val subject = adapter(vault)

        val whitespace = guarded {
            subject.fetchPerfQuery(baseUrl(), "   ", rc25, pat = AccountPatRef(PAT_REF))
        }
        val empty = guarded {
            subject.fetchPerfQuery(baseUrl(), "", rc25, pat = AccountPatRef(PAT_REF))
        }

        assertEquals(NewApiStatus.INPUT_INVALID, whitespace.failure().status)
        assertEquals(NewApiStatus.INPUT_INVALID, empty.failure().status)
        assertEquals("model 無效時一個 request 都不該送出", 0, server.requestCount)
        assertTrue("model 驗證必須先於憑證解析", vault.asked.isEmpty())
    }

    @Test
    fun perfHoursDefaultAndClampMirrorServerWindow() = runBlocking {
        // rc.25 server：Atoi 失敗 silent 保持 24；<=0 -> 24；>720 -> 720。
        assertEquals(24, NewApiPerfWindow.normalize(Int.MIN_VALUE))
        assertEquals(24, NewApiPerfWindow.normalize(-1))
        assertEquals(24, NewApiPerfWindow.normalize(0))
        assertEquals(1, NewApiPerfWindow.normalize(1))
        assertEquals(24, NewApiPerfWindow.normalize(24))
        assertEquals(720, NewApiPerfWindow.normalize(720))
        assertEquals(720, NewApiPerfWindow.normalize(721))
        assertEquals(720, NewApiPerfWindow.normalize(Int.MAX_VALUE))

        val cases = listOf(
            null to "24",
            0 to "24",
            -7 to "24",
            1 to "1",
            720 to "720",
            9999 to "720",
        )
        val subject = adapter(vaultWithBoth())
        for ((input, expected) in cases) {
            server.enqueue(ok("""{"success":true,"data":{"models":[]}}"""))
            val result = if (input == null) {
                guarded { subject.fetchPerfSummary(baseUrl(), rc25) }
            } else {
                guarded { subject.fetchPerfSummary(baseUrl(), rc25, hours = input) }
            }
            result.success()
            assertEquals("hours=$input", expected, takeRequest().requestUrl?.queryParameter("hours"))
        }
    }

    @Test
    fun perfGroupPassesThroughAndBlankGroupIsOmitted() = runBlocking {
        val subject = adapter(vaultWithBoth())

        server.enqueue(ok(emptyQueryBody()))
        guarded {
            subject.fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25, group = "svip")
        }.success()
        assertEquals("svip", takeRequest().requestUrl?.queryParameter("group"))

        server.enqueue(ok(emptyQueryBody()))
        guarded {
            subject.fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25, group = "   ")
        }.success()
        assertNull("空白 group 視同未指定（server 語意 all groups）", takeRequest().requestUrl?.queryParameter("group"))

        val unsafe = guarded {
            subject.fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25, group = "free\nx")
        }
        assertEquals(NewApiStatus.INPUT_INVALID, unsafe.failure().status)
        assertEquals(2, server.requestCount)
    }

    // ---- 4. HTTP 失敗封套：400/500/401，不讀錯誤 body、不重試 ----

    @Test
    fun perfHttpErrorEnvelopesMapToTypedFailuresAndNeverRetainBodyText() = runBlocking {
        val subject = adapter(vaultWithPat())

        server.enqueue(errorResponse(400, """{"success":false,"message":"model is required"}"""))
        val badRequest = guarded {
            subject.fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25, pat = AccountPatRef(PAT_REF))
        }
        assertEquals(NewApiStatus.HTTP_CLIENT_ERROR, badRequest.failure().status)
        assertEquals(400, badRequest.failure().evidence.httpStatus)
        assertFalse("400 message 不得外洩", badRequest.toString().contains("model is required"))

        server.enqueue(
            errorResponse(500, """{"success":false,"message":"raw-driver-text-marker $PAT_SECRET"}"""),
        )
        val serverError = guarded {
            subject.fetchPerfSummary(baseUrl(), rc25, pat = AccountPatRef(PAT_REF))
        }
        assertEquals(NewApiStatus.HTTP_SERVER_ERROR, serverError.failure().status)
        assertEquals(500, serverError.failure().evidence.httpStatus)
        assertFalse("500 raw driver message 不得保留或外洩", serverError.toString().contains("raw-driver-text-marker"))
        assertFalse("錯誤 body echo 的憑證不得外洩", serverError.toString().contains(PAT_SECRET))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun perfUnauthorizedIsNotRetriedAndDoesNotFallBack() = runBlocking {
        server.enqueue(
            errorResponse(401, """{"success":false,"code":"AUTH_UNAUTHORIZED","message":"$PAT_SECRET"}"""),
        )
        // 第二份是健康回應：任何 retry 都會消耗它並回 Success。
        server.enqueue(ok("""{"success":true,"data":{"models":[]}}"""))

        val result = guarded {
            adapter(vaultWithPat()).fetchPerfSummary(baseUrl(), rc25, pat = AccountPatRef(PAT_REF))
        }

        assertEquals(NewApiStatus.HTTP_UNAUTHORIZED, result.failure().status)
        assertEquals(401, result.failure().evidence.httpStatus)
        assertEquals(1, server.requestCount)
        assertFalse(result.toString().contains(PAT_SECRET))
    }

    @Test
    fun perfRateLimitIsNotRetried() = runBlocking {
        server.enqueue(errorResponse(429, """{"success":false,"message":"rate limited"}"""))
        server.enqueue(ok("""{"success":true,"data":{"models":[]}}"""))

        val result = guarded {
            adapter(vaultWithBoth()).fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25)
        }

        assertEquals(NewApiStatus.HTTP_TOO_MANY_REQUESTS, result.failure().status)
        assertEquals(429, result.failure().evidence.httpStatus)
        assertEquals(1, server.requestCount)
    }

    // ---- 5. 嚴格 shape：malformed 一律 MALFORMED，不偽裝空清單 ----

    @Test
    fun perfSummaryMalformedEnvelopesAndShapesAreMalformed() = runBlocking {
        val malformedBodies = listOf(
            "not json",
            """{"success":false,"message":"nope"}""",
            """{"success":true,"data":"not-an-object"}""",
            """{"success":true,"data":{}}""",
            """{"success":true,"data":{"models":"nope"}}""",
            """{"success":true,"data":{"models":[42]}}""",
            """{"success":true,"data":{"models":[{"model_name":"m","avg_latency_ms":1,"success_rate":100}]}}""",
            """{"success":true,"data":{"models":[{"model_name":"m","avg_latency_ms":"1","success_rate":100,"avg_tps":1}]}}""",
            """{"success":true,"data":{"models":[{"model_name":"m","avg_latency_ms":1,"success_rate":100,"avg_tps":1,"recent_success_rates":[100,"x"]}]}}""",
        )
        val subject = adapter(vaultWithBoth())
        for (body in malformedBodies) {
            server.enqueue(ok(body))
            val result = guarded { subject.fetchPerfSummary(baseUrl(), rc25) }
            assertEquals("body=$body", NewApiStatus.MALFORMED, result.failure().status)
            assertEquals("body=$body", 200, result.failure().evidence.httpStatus)
        }
        assertEquals(malformedBodies.size, server.requestCount)
    }

    @Test
    fun perfQueryMalformedShapesAreMalformedButExplicitEmptyIsNot() = runBlocking {
        val malformedBodies = listOf(
            """{"success":true,"data":{"model_name":"m","series_schema":"s"}}""",
            """{"success":true,"data":{"model_name":"m","series_schema":"s","groups":{}}}""",
            """{"success":true,"data":{"series_schema":"s","groups":[]}}""",
            """{"success":true,"data":{"model_name":"m","groups":[]}}""",
            """{"success":true,"data":{"model_name":"m","series_schema":"s","groups":[{"group":"g","avg_ttft_ms":0,"avg_latency_ms":0,"success_rate":100,"avg_tps":1}]}}""",
            """{"success":true,"data":{"model_name":"m","series_schema":"s","groups":[{"group":"g","avg_ttft_ms":0,"avg_latency_ms":0,"success_rate":100,"avg_tps":1,"series":[{"ts":1,"avg_ttft_ms":0,"avg_latency_ms":0,"success_rate":100}]}]}}""",
        )
        val subject = adapter(vaultWithBoth())
        for (body in malformedBodies) {
            server.enqueue(ok(body))
            val result = guarded { subject.fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25) }
            assertEquals("body=$body", NewApiStatus.MALFORMED, result.failure().status)
        }

        // 顯式空 groups 仍是成功的 typed empty。
        server.enqueue(ok(emptyQueryBody()))
        val empty = guarded { subject.fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25) }.success()
        assertTrue(empty.value.groups.isEmpty())
        assertEquals(malformedBodies.size + 1, server.requestCount)
    }

    // ---- 6. version gate 與 perf 認證隔離（GAP-V1）----

    @Test
    fun perfFetchRefusesUnknownVersionWithoutRequestOrCredential() = runBlocking {
        val vault = vaultWithBoth()
        val unknown = NewApiVersion.of("v0.9.9-fictional")

        val summary = guarded {
            adapter(vault).fetchPerfSummary(baseUrl(), unknown, pat = AccountPatRef(PAT_REF))
        }
        val query = guarded {
            adapter(vault).fetchPerfQuery(baseUrl(), MODEL_FIXTURE, unknown, pat = AccountPatRef(PAT_REF))
        }

        assertEquals(NewApiStatus.UNKNOWN_VERSION, summary.failure().status)
        assertEquals(NewApiStatus.UNKNOWN_VERSION, query.failure().status)
        assertEquals(0, server.requestCount)
        assertTrue("未認證版本連憑證都不該解析", vault.asked.isEmpty())
    }

    @Test
    fun perfCertificationStaysIsolatedFromTheFiveEndpointMap() {
        val rc = NewApiVersion.of(NewApiVersion.RC25)

        // 既有 5 端點認證 map 不得因 perf 而擴張。
        assertEquals(5, NewApiEndpoint.entries.size)
        assertEquals(NewApiEndpoint.entries.associateWith { it.path }, rc.certifiedEndpoints)
        assertTrue(rc.certifiedEndpoints.keys.none { it.path.contains("perf") })

        // perf 走獨立顯式認證。
        assertEquals(NewApiPerfEndpoint.entries.associateWith { it.path }, rc.certifiedPerfEndpoints)
        assertEquals(2, rc.certifiedPerfEndpoints.size)

        assertTrue(NewApiVersion.UNKNOWN.certifiedEndpoints.isEmpty())
        assertTrue(NewApiVersion.UNKNOWN.certifiedPerfEndpoints.isEmpty())
    }

    // ---- 7. 認證角色：可選 PAT、絕不用 inference key ----

    @Test
    fun perfCredentialIsOptionalDashboardPatAndNeverInferenceKey() = runBlocking {
        val patVault = vaultWithPat()
        server.enqueue(ok("""{"success":true,"data":{"models":[]}}"""))
        guarded {
            adapter(patVault).fetchPerfSummary(baseUrl(), rc25, pat = AccountPatRef(PAT_REF))
        }.success()
        val authed = takeRequest()
        assertEquals("Bearer $PAT_SECRET", authed.getHeader("Authorization"))
        assertFalse(
            "perf 只可用 dashboard PAT；inference key 永不進 /api/*",
            authed.getHeader("Authorization").orEmpty().contains(KEY_SECRET),
        )
        assertEquals(PAT_REF + "@" + baseOrigin(), patVault.asked.single())

        // 沒有 credential 不是錯誤：匿名送出且不解析 vault。
        val anonVault = vaultWithBoth()
        server.enqueue(ok("""{"success":true,"data":{"models":[]}}"""))
        guarded { adapter(anonVault).fetchPerfSummary(baseUrl(), rc25) }.success()
        val anonymous = takeRequest()
        assertNull("匿名請求不得帶 Authorization", anonymous.getHeader("Authorization"))
        assertTrue("匿名請求不得解析任何憑證", anonVault.asked.isEmpty())

        // PAT 角色沒有這把 safe ref 時 fail closed，不得 fallback、不得送 request。
        val keyOnly = vaultWithKey()
        val refused = guarded {
            adapter(keyOnly).fetchPerfQuery(baseUrl(), MODEL_FIXTURE, rc25, pat = AccountPatRef(PAT_REF))
        }
        assertEquals(NewApiStatus.CREDENTIAL_UNAVAILABLE, refused.failure().status)
        assertEquals("憑證不可用時不得送出 request", 2, server.requestCount)
        assertFalse(refused.toString().contains(PAT_SECRET))
        assertFalse(refused.toString().contains(KEY_SECRET))
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
    private fun errorResponse(code: Int, body: String = "") =
        MockResponse().setResponseCode(code).setBody(body)

    private fun takeRequest(mock: MockWebServer = server): RecordedRequest =
        requireNotNull(mock.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "expected a recorded request"
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

    private fun emptyQueryBody() =
        """{"success":true,"data":{"model_name":"$MODEL_FIXTURE","series_schema":"$SCHEMA_FIXTURE","groups":[]}}"""

    private fun perfSummaryBody() =
        """{"success":true,"data":{"models":[""" +
            """{"model_name":"$MODEL_FIXTURE","avg_latency_ms":1234,"success_rate":98.37,"avg_tps":42.5,"recent_success_rates":[100,99.5,98.37]},""" +
            """{"model_name":"fixture-model-b","avg_latency_ms":0,"success_rate":0,"avg_tps":0}""" +
            """]}}"""

    private fun perfQueryBody() =
        """{"success":true,"data":{"model_name":"$MODEL_FIXTURE","series_schema":"$SCHEMA_FIXTURE","groups":[""" +
            """{"group":"free","avg_ttft_ms":120,"avg_latency_ms":2500,"success_rate":87.654321,"avg_tps":33.25,"series":[""" +
            """{"ts":1760000000,"avg_ttft_ms":0,"avg_latency_ms":2300,"success_rate":99.9,"avg_tps":31.5},""" +
            """{"ts":1760003600,"avg_ttft_ms":150,"avg_latency_ms":2700,"success_rate":80.0,"avg_tps":35.0}""" +
            """]}]}}"""

    @Suppress("UNCHECKED_CAST")
    private fun <T> NewApiResult<T>.success(): NewApiResult.Success<T> =
        this as? NewApiResult.Success<T> ?: error("expected NewApiResult.Success, got $this")

    private fun NewApiResult<*>.failure(): NewApiResult.Failure =
        this as? NewApiResult.Failure ?: error("expected NewApiResult.Failure, got $this")
}
