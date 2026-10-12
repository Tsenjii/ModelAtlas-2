package dev.modelatlas.core.lmspeed

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P7 repair `LmspeedOkHttpTransport` 生產傳輸測試（TRACEABILITY LMS-001／LMS-005；純 JVM）。
 *
 * 只用 loopback MockWebServer（`allowLoopbackHttpForTests`，產品預設仍只允 `https://lmspeed.net`）＋
 * fixture key（`fixture-*`，不當產品資料）；釘住：單 request 真打 HTTP、禁 redirect（不跟隨、
 * 不轉送憑證、只打一次）、body 有界、401→AUTH、憑證回聲列剔除、 enrichment 欄位搬運。
 */
class LmspeedOkHttpTransportTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun transport(
        pageSize: Int = 50,
        locale: String = "en",
    ) = LmspeedOkHttpTransport(
        client = OkHttpClient(),
        baseUrl = server.url("/").toString().trimEnd('/'),
        pageSize = pageSize,
        locale = locale,
        allowLoopbackHttpForTests = true,
    )

    private fun listJson(vararg models: String) =
        """{"tier":"free","locale":"en","data":{"models":[${models.joinToString(",")}],
            "pagination":{"page":1,"page_size":50,"total":${models.size},"total_pages":1}}}"""

    private fun modelJson(
        slug: String = "fixture-alpha",
        extra: String = "",
    ) = """{"slug":"$slug","name":"Fixture Alpha","developer":"FixtureWorks",
        "lmspeed_url":"https://lmspeed.net/model/$slug",
        "description":"Fixture description",
        "context_window_tokens":1048576,"max_output_tokens":384000,
        "capabilities":["TEXT","TOOL_CALLING"],
        "stats":{"average_tokens_per_second":49.5,"average_first_token_latency":7150.25},
        "benchmark_summary":[{"metric_key":"overall_score"}],
        "pricing_summary":{"input_price_per_million":1.74}$extra}"""

    @Test
    fun successParsesEnrichmentFieldsInSingleRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(listJson(modelJson())))

        val rows = transport().fetch("fixture-lmspeed-public-key-abc123")

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/api/v1/models?page=1&page_size=50&sort=popular&locale=en&include=capabilities", request.path)
        assertEquals("fixture-lmspeed-public-key-abc123", request.getHeader("x-api-key"))
        val row = rows.single()
        assertEquals("fixture-alpha", row.slug)
        assertEquals("Fixture description", row.description)
        assertEquals(1048576, row.contextWindowTokens)
        assertEquals(384000, row.maxOutputTokens)
        assertEquals(listOf("TEXT", "TOOL_CALLING"), row.capabilities)
        assertEquals(49.5, row.externalTps!!, 0.0)
        assertEquals(7150.25, row.externalFirstTokenLatency!!, 0.0)
        assertTrue(row.benchmarkRef!!.contains("overall_score"))
        assertTrue(row.pricingRef!!.contains("1.74"))
    }

    @Test
    fun unauthorizedMapsToAuth() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}"""))
        try {
            transport().fetch("fixture-lmspeed-public-key-abc123")
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.AUTH, e.category)
            assertEquals(401, e.httpStatus)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectIsNotFollowedAndSurfacesUnknown() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/api/v1/models?page=1").toString()),
        )
        try {
            transport().fetch("fixture-lmspeed-public-key-abc123")
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            // 禁 redirect：不跟隨（只打一次）、不轉送憑證，分類 UNKNOWN。
            assertEquals(LmspeedFetchFailure.UNKNOWN, e.category)
            assertEquals(302, e.httpStatus)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oversizedBodyIsBoundedAsUnknown() = runBlocking {
        val big = "x".repeat((LmspeedOkHttpTransport.MAX_RESPONSE_BYTES + 16).toInt())
        server.enqueue(MockResponse().setResponseCode(200).setBody(listJson(modelJson(extra = "")) + big))
        try {
            transport().fetch("fixture-lmspeed-public-key-abc123")
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.UNKNOWN, e.category)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun credentialEchoRowIsDroppedWithoutFailingBatch() = runBlocking {
        val key = "fixture-secret-key-abc123"
        val echoSlug = "model-$key-echo"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                listJson(
                    """{"slug":"$echoSlug","name":"Echo"}""",
                    modelJson(slug = "fixture-good"),
                ),
            ),
        )
        val rows = transport().fetch(key)
        assertEquals(1, rows.size)
        assertEquals("fixture-good", rows.single().slug)
    }

    @Test
    fun blankKeyNeverHitsNetwork() = runBlocking {
        try {
            transport().fetch("   ")
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE, e.category)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun malformedJsonSurfacesUnknown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json{{{"))
        try {
            transport().fetch("fixture-lmspeed-public-key-abc123")
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.UNKNOWN, e.category)
        }
    }

    @Test
    fun missingSlugRowSkippedKeepsBatch() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                listJson("""{"name":"NoSlug"}""", modelJson(slug = "fixture-kept")),
            ),
        )
        val rows = transport().fetch("fixture-lmspeed-public-key-abc123")
        assertEquals(1, rows.size)
        assertEquals("fixture-kept", rows.single().slug)
    }

    @Test
    fun exceptionNeverCarriesKey() = runBlocking {
        val key = "fixture-lmspeed-public-key-abc123"
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        try {
            transport().fetch(key)
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertTrue(!(e.message ?: "").contains(key))
            assertTrue(!e.toString().contains(key))
            assertEquals(500, e.httpStatus)
        }
    }
}
