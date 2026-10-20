package dev.modelatlas.core.workspace

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProtocolTest {
    @Test fun integerCountersAndNullableGroupsSurviveWireRoundTrip() {
        val original=WorkspaceRecord("android:snapshot_facts","[\"fact\"]",revision=9007199254740993L,
            data=buildJsonObject { put("group",JsonNull); put("count",9007199254740993L); put("price","0.000000001") })
        val raw=WorkspaceJson.encodeToString(WorkspaceRecord.serializer(),original)
        val decoded=WorkspaceJson.decodeFromString(WorkspaceRecord.serializer(),raw)
        assertEquals(original,decoded)
        assertEquals(9007199254740993L,decoded.data.getValue("count").jsonPrimitive.long)
        assertEquals(JsonNull,decoded.data["group"])
        assertEquals("0.000000001",decoded.data.getValue("price").jsonPrimitive.content)
    }
    @Test fun secretDigestMatchesTheGoTypedEnvelopeIncludingHtmlEscapes() {
        val secret=WorkspaceSecret("example<&>\u2028",listOf(WorkspaceAuthField("X-Key",false,"fixture-secret")))
        val goJson="""{"key":"example\u003c\u0026\u003e\u2028","fields":[{"name":"X-Key","query":false,"secret":"fixture-secret"}]}"""
        assertEquals(workspaceDigest(goJson),secret.digest())
        assertEquals(workspaceDigest("""{"key":"synthetic"}"""),WorkspaceSecret("synthetic").digest())
        assertFalse(secret.toString().contains("fixture-secret"))
        assertFalse(WorkspaceAuthField("name",false,"fixture-secret").toString().contains("fixture-secret"))
    }
    @Test fun destinationMustBeAnHttpsOriginWithoutCredentialsOrRouting() {
        assertEquals("https://atlas.example:8443",WorkspaceTransport.origin("https://atlas.example:8443/"))
        for(raw in listOf("http://atlas.example","https://user:secret@atlas.example","https://atlas.example/api","https://atlas.example/?key=secret","https://atlas.example/#fragment")) {
            try { WorkspaceTransport.origin(raw); fail("Accepted unsafe destination") } catch(_:IllegalArgumentException) { }
        }
    }
}
