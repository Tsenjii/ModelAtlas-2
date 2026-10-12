package dev.modelatlas.app.data

import dev.modelatlas.core.domain.PricingScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-006 D5／D6（provider-scope range port）：JVM 層只驗真實 [PricingScope.storageKey]
 * 與 range 的前綴關係、長度前綴邊界、roundtrip；Unicode 排序 exactness 必須由
 * device 上的真 SQLite 驗證（T9 device leg），本檔絕不用 Kotlin [String.compareTo]
 * 當 SQL BINARY 的 oracle——UTF-16 碼元順序遇到 supplementary char 與 UTF-8 byte
 * order 不同，用 compareTo 冒充 SQL 會得到假 PASS／假 FAIL。
 *
 * 純 JVM 純函式測試，不碰 DB／網路；構造值為本地 fixture，非產品資料。
 */
class ProviderScopeRangeTest {

    private fun scope(
        providerId: String,
        accountId: String? = "a",
        credentialId: String? = "k",
        modelId: String = "m",
        group: String? = "g",
        endpointId: String = "e",
        operation: String = "CHAT_COMPLETIONS",
    ) = PricingScope(providerId, accountId, credentialId, modelId, group, endpointId, operation)

    @Test
    fun rangeBoundsAreLengthPrefixedProviderLegPlusSafeUmax() {
        val (lo, hi) = providerScopeRange("p")
        assertEquals("1:p", lo)
        assertEquals(lo + "\uDBFF\uDFFF", hi)
        assertTrue(hi.startsWith(lo))
    }

    @Test
    fun supplementaryProviderUsesKotlinUtf16LengthInLo() {
        val (lo, hi) = providerScopeRange("p😀")
        assertEquals("3:p😀", lo)
        assertEquals(lo + "\uDBFF\uDFFF", hi)
        val key = scope("p😀").storageKey()
        assertTrue(key.startsWith(lo))
    }

    @Test
    fun everySameProviderStorageKeyStartsWithLo() {
        val providers = listOf("p", "p1", "p2", "pp", "a:b", "😀", "p😀", "abc", "abcd")
        for (p in providers) {
            val (lo, _) = providerScopeRange(p)
            val key = scope(p, accountId = "acc", credentialId = "k", modelId = "m", group = "g", endpointId = "e", operation = "op").storageKey()
            assertTrue("$p key $key should start with $lo", key.startsWith(lo))
        }
    }

    @Test
    fun otherProviderKeysDoNotShareLoPrefix() {
        val (lo, _) = providerScopeRange("p")
        for (q in listOf("p1", "p2", "pp", "a", "abc", "😀")) {
            val key = scope(q).storageKey()
            assertFalse("$q key $key must not start with $lo", key.startsWith(lo))
        }
        val boundaryLow = scope("p1").storageKey()
        assertFalse(boundaryLow.startsWith(lo))
    }

    @Test
    fun lengthPrefixPreventsDigitBoundaryCollision() {
        val (lo9, _) = providerScopeRange("123456789")
        val (lo10, _) = providerScopeRange("1234567890")
        assertEquals("9:123456789", lo9)
        assertEquals("10:1234567890", lo10)
        assertFalse(scope("1234567890").storageKey().startsWith(lo9))
        assertFalse(scope("123456789").storageKey().startsWith(lo10))
    }

    @Test
    fun storageKeyRoundTripsThroughParseScopeKey() {
        val s = scope("prov", accountId = "a", credentialId = null, modelId = "m", group = null, endpointId = "e", operation = "op")
        val parts = parseScopeKey(s.storageKey())
        assertNotNull(parts)
        assertEquals(listOf("prov", "a", null, "m", null, "e", "op"), parts)
    }

    @Test
    fun supplementaryDimensionsRoundTrip() {
        val s = scope("p😀", accountId = "😀acc", credentialId = "🔑k", modelId = "m😀m", group = "😀", endpointId = "e😀p", operation = "O😀P")
        val parts = parseScopeKey(s.storageKey())
        assertNotNull(parts)
        assertEquals(listOf("p😀", "😀acc", "🔑k", "m😀m", "😀", "e😀p", "O😀P"), parts)
    }

    @Test
    fun emptyAndNullDimsAreDistinctButParseable() {
        val s = scope("p", accountId = "", credentialId = null, modelId = "m", group = "", endpointId = "e", operation = "op")
        val parts = parseScopeKey(s.storageKey())
        assertNotNull(parts)
        assertEquals(listOf("p", "", null, "m", "", "e", "op"), parts)
    }

    @Test
    fun malformedKeysFailClosed() {
        assertNull(parseScopeKey("1:p2:x"))
        assertNull(parseScopeKey("-2:a1:b1:c1:d1:e1:f"))
        assertNull(parseScopeKey("abc:p1:a1:b1:c1:d1:e1:f"))
        assertNull(parseScopeKey("1:p1:a1:b1:c1:d1:e1:fEXTRA"))
        assertNull(parseScopeKey("1:p1:a1:b1:c1:d1:e"))
        assertNull(parseScopeKey(""))
    }
}
