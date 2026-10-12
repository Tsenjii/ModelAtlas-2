package dev.modelatlas.core.models

import dev.modelatlas.core.domain.CanonicalModel
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.domain.normalizeModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 session AD：MODEL-001／002 正規化邊界 JVM 單測（pure Kotlin，無 Android／DB／網路依賴）。
 *
 * TRACEABILITY：MODEL-001（canonical 正規化身份）、MODEL-002（alias 解析與去重）。
 * 對應 PRODUCT_SPEC §7（模型正規化：大小寫／分隔符／版本後綴；原始 ID 永遠保存）、
 * §6（同一 canonical 不因不同 key 重複建立）。
 *
 * 與既有 `ModelIdentityTest` 互斥：本檔只打「邊界與退化輸入」，不重複 happy path。
 * 契約要點（依 `core/domain/Domain.kt` 的 `normalizeModelId` 實作）：
 * 1. 分隔符集合**只有** `_ . / : @` 與空白（regex `[_./:@\s]+`）；其他標點原樣保留，
 *    不得被臆測折疊，否則不同模型會被錯併。
 * 2. 空／全分隔符輸入正規化後仍為空字串；本層不猜、不擲錯，交由呼叫端拒絕。
 * 3. `canonicalIdFor` 恆等於 `normalizeModelId`，無雜湊、無截斷、無長度上限。
 * 4. 尾端 `-latest` 只剝一層，且非 token 邊界錨定。
 *
 * 測試 fixture 只存在於本 test sourceSet，以 `bounds*` 明示命名，不作產品資料。
 * 本檔僅新建，未修改任何既有檔。
 */
class ModelIdentityBoundsTest {

    // ---- 空 / 空白 / 純符號：退化輸入不得被偽裝成合法身份 ----

    @Test
    fun blankAndSeparatorOnlyInputsNormalizeToEmpty() {
        assertEquals("", normalizeModelId(""))
        assertEquals("", normalizeModelId("   "))
        assertEquals("", normalizeModelId("\t\n\r "))
        assertEquals("", normalizeModelId("---"))
        assertEquals("", normalizeModelId("..."))
        assertEquals("", normalizeModelId("_ . / : @"))
        assertEquals("", canonicalIdFor(""))
        assertEquals("", canonicalIdFor("  ---  "))
        // 空正規化名不得被偽裝成非空 id（下游 Room/PricingScope 層會自行拒絕）。
        assertEquals("", canonicalIdFor("   "))
    }

    // ---- 分隔符集合邊界：只有宣告過的符號會被折疊 ----

    @Test
    fun onlyDeclaredSeparatorsAreFoldedOtherPunctuationSurvives() {
        assertEquals("a-b", normalizeModelId("a_b"))
        assertEquals("a-b", normalizeModelId("a.b"))
        assertEquals("a-b", normalizeModelId("a/b"))
        assertEquals("a-b", normalizeModelId("a:b"))
        assertEquals("a-b", normalizeModelId("a b"))
        assertEquals("a-b", normalizeModelId("a@b"))

        // 未宣告的標點必須原樣保留（原始 ID 永遠保存），不得臆測折疊。
        assertEquals("a+b", normalizeModelId("a+b"))
        assertEquals("a,b", normalizeModelId("a,b"))
        assertEquals("a=b", normalizeModelId("a=b"))
        assertEquals("a*b?c", normalizeModelId("a*b?c"))
        assertEquals("a!b", normalizeModelId("a!b"))
        // 混串中只有 '@' 屬分隔符，其餘標點不動。
        assertEquals("a!-#$%^&*()b", normalizeModelId("a!@#\$%^&*()b"))

        assertNotEquals(normalizeModelId("model-v1"), normalizeModelId("model!v1"))
        assertNotEquals(normalizeModelId("model-v1"), normalizeModelId("model+v1"))
    }

    // ---- 分隔符連續串／頭尾破折號：壓成單一 '-' ----

    @Test
    fun separatorRunsAndEdgeDashesCollapseToSingleDash() {
        assertEquals("a-b", normalizeModelId("--a--b--"))
        assertEquals("a-b", normalizeModelId("-a_b-"))
        assertEquals("a-b", normalizeModelId(" - a - b - "))
        assertEquals("a-b", normalizeModelId("a__..//::@@b"))
        assertEquals("a-b", normalizeModelId("a \t\n b"))
        val runHeavy = "a" + "_".repeat(500) + "b"
        val normalized = normalizeModelId(runHeavy)
        assertEquals("a-b", normalized)
        assertTrue(normalized.length < runHeavy.length)
    }

    // ---- '-latest' 後綴：只剝尾端一層，且不錨定 token 邊界 ----

    @Test
    fun latestSuffixIsStrippedOnceAndOnlyAtTheEnd() {
        assertEquals("my-model", normalizeModelId("my-model:latest"))
        assertEquals("my-model", normalizeModelId("MY_MODEL:LATEST"))
        assertEquals("my-model", normalizeModelId("my-model@latest"))
        assertEquals("my-model", normalizeModelId("my-model-latest-"))
        // 只剝一層，第二層保留。
        assertEquals("my-model-latest", normalizeModelId("my-model:latest:latest"))
        // 非尾端／無前導破折號時不剝。
        assertEquals("latest-model", normalizeModelId("latest-model"))
        assertEquals("latest", normalizeModelId("-latest"))
        // 觀察到的邊界：剝除不是 token 錨定，模型名本身以 "-latest" 結尾會被併到短名。
        // 這是既有契約的可見後果（不臆測、不修正），記錄以防日後無人察覺。
        assertEquals("my", normalizeModelId("my-latest"))
        assertEquals("my-model", canonicalIdFor("my-model:latest"))
    }

    // ---- 版本／前綴差異必須保持相異：不得過度合併 ----

    @Test
    fun versionAndPrefixDifferencesRemainDistinct() {
        assertEquals("gpt-4o", normalizeModelId("GPT_4O"))
        assertEquals(normalizeModelId("gpt-4o"), normalizeModelId("GPT_4o"))
        assertNotEquals(normalizeModelId("model-v1"), normalizeModelId("model-v2"))
        assertNotEquals(normalizeModelId("gpt-4"), normalizeModelId("gpt-4o"))
        assertNotEquals(normalizeModelId("gpt-4o"), normalizeModelId("gpt-4o-mini"))
        // 不隱含插入分隔符：「gpt4o」與「gpt-4o」是兩個不同身份。
        assertNotEquals(normalizeModelId("gpt4o"), normalizeModelId("gpt-4o"))
        assertEquals("model-v1-0", normalizeModelId("model_v1.0"))
        assertNotEquals(canonicalIdFor("GPT-4o"), canonicalIdFor("GPT-4o-X"))
    }

    // ---- 超長輸入：不得截斷、不得雜湊化 ----

    @Test
    fun veryLongInputIsPreservedNotTruncated() {
        val letters = "a".repeat(4096)
        assertEquals(4096, normalizeModelId(letters).length)
        assertEquals(letters, normalizeModelId(letters))
        assertEquals(letters, canonicalIdFor(letters))

        val runHeavy = "a" + "_".repeat(5000) + "b"
        assertEquals("a-b", normalizeModelId(runHeavy))

        val withSuffix = "b".repeat(2000) + ":latest"
        val stripped = normalizeModelId(withSuffix)
        assertEquals(2000, stripped.length)
        assertEquals("b".repeat(2000), stripped)
        assertEquals(2000, canonicalIdFor(withSuffix).length)
    }

    // ---- Unicode／locale 邊界 ----

    @Test
    fun unicodeLettersSurviveAndCaseFoldingIsLocaleIndependent() {
        assertEquals("模型-v1", normalizeModelId("模型_V1"))
        assertEquals("deepseek-深度求索", normalizeModelId("DeepSeek 深度求索"))
        assertEquals("模型", normalizeModelId("模型"))
        assertEquals("café-model", normalizeModelId("CAFÉ-Model"))
        // 非字母數字的 symbol 既不是分隔符也不是可 trim 的 '-'，必須保留。
        assertEquals("模型🙂", normalizeModelId("模型🙂"))
        // 一律以 root locale 折疊大小寫，不受預設／系統 locale 影響。
        assertEquals("i", normalizeModelId("I"))
        assertEquals(normalizeModelId("MODEL"), normalizeModelId("model"))
    }

    // ---- 退化輸入的呼叫端契約：拒絕，不偽造 ----

    @Test
    fun degenerateCandidatesAreRejectedOrLeftUnresolvedNotFabricated() {
        assertThrows(IllegalArgumentException::class.java) {
            deduplicateCanonical(emptyList(), "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            deduplicateCanonical(emptyList(), "---")
        }
        assertThrows(IllegalArgumentException::class.java) {
            deduplicateCanonical(emptyList(), " _ . / : @ ")
        }
        // 解析層不擲錯也不偽造：退化輸入回空 canonical，由呼叫端拒絕。
        assertEquals("", resolveCanonicalId(""))
        assertEquals("", resolveCanonicalId("  ---  "))

        // 帶頭尾空白的候選仍命中既有 canonical，且既有 displayName 不被候選覆寫。
        val existing = CanonicalModel(canonicalId = "gpt-4o", normalizedName = "gpt-4o", displayName = "GPT-4o")
        val sameByShape = deduplicateCanonical(listOf(existing), "  GPT_4O  ", "被覆寫的別名")
        assertEquals("gpt-4o", sameByShape.canonicalId)
        assertEquals("GPT-4o", sameByShape.displayName)
        assertEquals("gpt-4o", deduplicateCanonical(listOf(existing), "gpt.4o").canonicalId)
        // 空白 displayName 回退到 trim 過的原始 ID，不填零值、不拿別的名字冒充。
        val created = deduplicateCanonical(emptyList(), "New-Model", "   ")
        assertEquals("new-model", created.canonicalId)
        assertEquals("new-model", created.normalizedName)
        assertEquals("New-Model", created.displayName)

        assertTrue(isSameCanonical("gpt-4o", "gpt_4o"))
        assertTrue(isSameCanonical("gpt-4o", "gpt-4o:latest"))
        assertFalse(isSameCanonical("gpt-4o", "gpt-4o-mini"))
    }
}
