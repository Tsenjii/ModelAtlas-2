package dev.modelatlas.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NoticeFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: NoticeFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = NoticeFetcher(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            allowLoopbackHttpForTests = true,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun httpsUrl(path: String = "/notice.json"): String = server.url(path).toString()

    @Test
    fun fetchReturnsBodyAndStoresEtag() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody("{\"notice\":\"ok\"}"),
        )
        val result = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.OK, result.status)
        assertEquals(200, result.httpStatus)
        assertEquals("{\"notice\":\"ok\"}", result.content)
        assertEquals("\"v1\"", result.etag)
        assertFalse(result.fromCache)
        assertTrue(server.takeRequest().getHeader("If-None-Match") == null)
    }

    @Test
    fun rejectsInsecureHttpWithoutAnyRequest() = runBlocking {
        val result = fetcher.fetch("http://example.com/notice.json")
        assertEquals(NoticeStatus.INSECURE_SCHEME, result.status)
        assertNull(result.content)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsInvalidAndCredentialBearingUrlsWithoutAnyRequest() = runBlocking {
        assertEquals(NoticeStatus.INPUT_INVALID, fetcher.fetch("not a url").status)
        assertEquals(
            NoticeStatus.INPUT_INVALID,
            fetcher.fetch("https://user:pass@example.com/notice.json").status,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun doesNotFollowRedirects() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", httpsUrl("/moved.json"))
                .setBody(""),
        )
        val result = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.HTTP_UNSUPPORTED, result.status)
        assertEquals(302, result.httpStatus)
        assertNull(result.content)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oversizedBodyIsRejectedAsTooLarge() = runBlocking {
        val oversized = "x".repeat(NoticeBodyLimitForTest.MAX_BODY_BYTES + 1)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"big\"")
                .setBody(oversized),
        )
        val result = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.RESPONSE_TOO_LARGE, result.status)
        assertNull(result.content)
        // An oversized response must not poison the ETag cache.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v2\"")
                .setBody("small"),
        )
        val next = fetcher.fetch(httpsUrl())
        assertTrue(server.takeRequest().getHeader("If-None-Match") == null)
        assertEquals(NoticeStatus.OK, next.status)
        assertEquals("small", next.content)
    }

    @Test
    fun secondFetchSendsIfNoneMatchAndServesCachedBodyOn304() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody("body-1"),
        )
        val first = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.OK, first.status)
        server.enqueue(MockResponse().setResponseCode(304))
        val second = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.NOT_MODIFIED, second.status)
        assertEquals(304, second.httpStatus)
        assertEquals("body-1", second.content)
        assertEquals("\"v1\"", second.etag)
        assertTrue(second.fromCache)
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun newEtagReplacesCachedEntryOnFresh200() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody("body-1"),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v2\"").setBody("body-2"),
        )
        server.enqueue(MockResponse().setResponseCode(304))
        fetcher.fetch(httpsUrl())
        val refreshed = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.OK, refreshed.status)
        assertEquals("body-2", refreshed.content)
        val third = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.NOT_MODIFIED, third.status)
        assertEquals("body-2", third.content)
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals("\"v2\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun serverErrorsMapToDedicatedStatusesAndCarryNoBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("secret error page"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("secret error page"))
        val notFound = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.HTTP_CLIENT_ERROR, notFound.status)
        assertEquals(404, notFound.httpStatus)
        assertNull(notFound.content)
        val unavailable = fetcher.fetch(httpsUrl())
        assertEquals(NoticeStatus.HTTP_SERVER_ERROR, unavailable.status)
        assertEquals(503, unavailable.httpStatus)
        assertNull(unavailable.content)
    }
}

/** Test-only mirror of the fetcher's body cap so tests can build a payload one byte over it. */
private object NoticeBodyLimitForTest {
    val MAX_BODY_BYTES: Int = 262_144
}
