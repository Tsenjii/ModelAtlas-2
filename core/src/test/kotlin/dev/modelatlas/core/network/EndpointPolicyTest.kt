package dev.modelatlas.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPolicyTest {
    private fun accepted(baseUrl: String): String = EndpointPolicy.chatEndpoint(baseUrl)

    private fun rejected(url: String): IllegalArgumentException {
        val result = runCatching { EndpointPolicy.validate(url) }
        assertTrue("expected rejection for $url", result.isFailure)
        return result.exceptionOrNull() as IllegalArgumentException
    }

    @Test
    fun httpsBaseIsAcceptedAndChatPathIsAppended() {
        assertEquals(
            "https://api.fixture.invalid/v1/chat/completions",
            accepted("https://api.fixture.invalid/v1"),
        )
        assertEquals(
            "https://api.fixture.invalid:8443/openai/v1/chat/completions",
            accepted("https://api.fixture.invalid:8443/openai/v1"),
        )
    }

    @Test
    fun rootPathAndTrailingSlashesAreNormalized() {
        assertEquals("https://api.fixture.invalid/chat/completions", accepted("https://api.fixture.invalid"))
        assertEquals("https://api.fixture.invalid/chat/completions", accepted("https://api.fixture.invalid/"))
        assertEquals(
            "https://api.fixture.invalid/openai/v1/chat/completions",
            accepted("https://api.fixture.invalid/openai/v1/"),
        )
        assertEquals(
            "https://api.fixture.invalid/v1/chat/completions",
            accepted("  https://api.fixture.invalid/v1/  "),
        )
    }

    @Test
    fun plainHttpIsRejected() {
        assertEquals("HTTPS required", rejected("http://api.fixture.invalid/v1").message)
        assertEquals("HTTPS required", rejected("http://127.0.0.1:8080/v1").message)
    }

    @Test
    fun userInfoIsRejected() {
        assertEquals(
            "HTTPS required",
            rejected("https://user:fixture-secret@api.fixture.invalid/v1").message,
        )
        assertEquals(
            "HTTPS required",
            rejected("https://user@api.fixture.invalid/v1").message,
        )
    }

    @Test
    fun queryIsRejected() {
        assertEquals(
            "Query and fragment not allowed",
            rejected("https://api.fixture.invalid/v1?api-key=fixture-secret").message,
        )
    }

    @Test
    fun fragmentIsRejected() {
        assertEquals(
            "Query and fragment not allowed",
            rejected("https://api.fixture.invalid/v1#fixture-fragment").message,
        )
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        assertEquals("Invalid endpoint", rejected("ftp://api.fixture.invalid/v1").message)
        assertEquals("Invalid endpoint", rejected("file:///etc/passwd").message)
        assertEquals("Invalid endpoint", rejected("wss://api.fixture.invalid/v1").message)
    }

    @Test
    fun malformedInputIsRejectedWithoutEchoingIt() {
        val failure = rejected("   ")
        assertEquals("Invalid endpoint", failure.message)
        assertTrue(failure.message.orEmpty().startsWith("Invalid"))
    }

    @Test
    fun chatEndpointAlsoRejectsInsecureBases() {
        val result = runCatching { accepted("http://api.fixture.invalid/v1") }
        assertEquals("HTTPS required", (result.exceptionOrNull() as IllegalArgumentException).message)
    }

    @Test
    fun originKeepsHostAndPortOnly() {
        val origin = EndpointPolicy.origin(EndpointPolicy.validate("https://api.fixture.invalid:8443/v1/chat/completions"))
        assertEquals("https://api.fixture.invalid:8443", origin)
        assertTrue(!origin.contains("/v1"))
        assertTrue(!origin.contains("@"))
    }

    @Test
    fun originIsStableAcrossPathsOfTheSameCredentialScope() {
        val first = EndpointPolicy.origin(EndpointPolicy.validate("https://api.fixture.invalid/v1"))
        val second = EndpointPolicy.origin(EndpointPolicy.validate("https://api.fixture.invalid/v2/chat/completions"))
        val otherHost = EndpointPolicy.origin(EndpointPolicy.validate("https://other.fixture.invalid/v1"))
        val otherPort = EndpointPolicy.origin(EndpointPolicy.validate("https://api.fixture.invalid:8443/v1"))

        assertEquals(first, second)
        assertNotEquals(first, otherHost)
        assertNotEquals(first, otherPort)
    }

    @Test
    fun chatEndpointIsIdempotentForFullChatUrls() {
        val full = "https://api.fixture.invalid/v1/chat/completions"
        assertEquals(full, EndpointPolicy.chatEndpoint(full))
        assertEquals(full, EndpointPolicy.chatEndpoint("$full/"))
        assertEquals(full, EndpointPolicy.chatEndpoint("  $full  "))
    }

    @Test
    fun chatEndpointNormalizesBasePaths() {
        assertEquals(
            "https://api.fixture.invalid/v1/chat/completions",
            EndpointPolicy.chatEndpoint("https://api.fixture.invalid/v1/"),
        )
        assertEquals(
            "https://api.fixture.invalid/chat/completions",
            EndpointPolicy.chatEndpoint("https://api.fixture.invalid"),
        )
    }
}
