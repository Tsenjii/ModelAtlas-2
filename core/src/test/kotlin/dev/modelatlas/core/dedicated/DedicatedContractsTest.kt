package dev.modelatlas.core.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共用契約邊界測試（ADAPT-007；SPEC §9/§10）。
 *
 * 釘住：capability 獨立缺失不拖垮整體、official/local provenance 分離、
 * per-model 不合併、429/402/410 分開。測試 fixture 皆為合成，不得當產品資料。
 */
class DedicatedContractsTest {

    @Test
    fun capabilityMissingDoesNotTakeDownTheWholeSync() {
        val okQuota = DedicatedResult.Success(
            DedicatedEvidence("t/quota", 1L, DedicatedProvenance.OFFICIAL_DOC),
            listOf(
                QuotaPool(
                    scopeKind = QuotaScopeKind.PLATFORM_GLOBAL,
                    modelId = null,
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                ),
            ),
        )
        // fetchNotice 獨立缺失（例：NVIDIA hosted 無結構化端點）與 pricing 失敗並存時，
        // 成功的 quota 仍在；呼叫端對 Failure 保留 last-good（此處只斷言不刪除）。
        val noticeMissing: DedicatedResult<List<QuotaPool>> = DedicatedResult.Unsupported(
            DedicatedEvidence("t/notice", 1L, DedicatedProvenance.UNKNOWN),
            "no structured notice endpoint",
        )
        val pricingFailed: DedicatedResult<List<QuotaPool>> = DedicatedResult.Failure(
            DedicatedStatus.HTTP_UNAUTHORIZED_401,
            DedicatedEvidence("t/pricing", 1L, DedicatedProvenance.OBSERVED, 401),
        )

        val survived = DedicatedSync.combineIndependent(listOf(okQuota, noticeMissing, pricingFailed))
        assertEquals(1, survived.size)

        // 缺失與失敗並存時，成功的 quota 仍在；呼叫端對 Failure 保留 last-good（此處只斷言不刪除）。
        val mixed = listOf<DedicatedResult<String>>(
            DedicatedResult.Success(DedicatedEvidence("t/a", 1L, DedicatedProvenance.OFFICIAL_DOC), "a"),
            DedicatedResult.Unsupported(DedicatedEvidence("t/b", 1L, DedicatedProvenance.UNKNOWN), "no capability"),
            DedicatedResult.Failure(
                DedicatedStatus.HTTP_UNAUTHORIZED_401,
                DedicatedEvidence("t/c", 1L, DedicatedProvenance.OBSERVED, 401),
            ),
        )
        assertEquals(listOf("a"), DedicatedSync.combineIndependent(mixed))
    }

    @Test
    fun officialAndLocalProvenanceAreNeverConfused() {
        val official = DedicatedProvenance.OFFICIAL_DOC
        val viaIndex = DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX
        val staff = DedicatedProvenance.OFFICIAL_STAFF_FORUM
        assertTrue(official.isOfficialFamily())
        assertTrue(viaIndex.isOfficialFamily())
        assertTrue(staff.isOfficialFamily())
        assertFalse(DedicatedProvenance.OBSERVED.isOfficialFamily())
        assertFalse(DedicatedProvenance.EXTERNAL.isOfficialFamily())
        assertFalse(DedicatedProvenance.LOCAL_TELEMETRY.isOfficialFamily())
        assertFalse(DedicatedProvenance.SELF_HOSTED_OFFICIAL.isOfficialFamily())
        assertFalse(DedicatedProvenance.SELF_REPORTED.isOfficialFamily())
        assertFalse(DedicatedProvenance.MANUAL.isOfficialFamily())
    }

    @Test
    fun perModelQuotaPoolsAreNeverMerged() {
        val pools = listOf(
            QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-a", rpd = QuotaDimension.known(1000), tpd = QuotaDimension.known(200000), provenance = DedicatedProvenance.OFFICIAL_DOC),
            QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-b", rpd = QuotaDimension.known(14400), tpd = QuotaDimension.known(500000), provenance = DedicatedProvenance.OFFICIAL_DOC),
        )
        DedicatedSync.assertNoMerge(pools)
        // 兩模型數值各自保留，不平均。
        assertEquals(1000L, pools[0].rpd.value)
        assertEquals(14400L, pools[1].rpd.value)
        assertEquals(200000L, pools[0].tpd.value)
        assertEquals(500000L, pools[1].tpd.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateModelPoolsAreRejected() {
        DedicatedSync.assertNoMerge(
            listOf(
                QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-a", provenance = DedicatedProvenance.OFFICIAL_DOC),
                QuotaPool(QuotaScopeKind.PER_ORG_MODEL, "m-a", provenance = DedicatedProvenance.OFFICIAL_DOC),
            ),
        )
    }

    @Test
    fun sameModelInDifferentProjectsOrSlicesIsNotAMerge() {
        // 同 scope＋同 model 但 project 不同 → 兩筆並存（Gemini per project × model）。
        DedicatedSync.assertNoMerge(
            listOf(
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-1",
                    quotaSlice = QuotaPoolSlice.STANDARD_INTERACTIVE,
                ),
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-2",
                    quotaSlice = QuotaPoolSlice.STANDARD_INTERACTIVE,
                ),
            ),
        )
        // 同 project×model 但切片不同（標準／priority／batch）→ 三筆並存，不合併。
        DedicatedSync.assertNoMerge(
            QuotaPoolSlice.entries.map { slice ->
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-1",
                    quotaSlice = slice,
                )
            },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateProjectModelSliceIsRejected() {
        DedicatedSync.assertNoMerge(
            listOf(
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-1",
                    quotaSlice = QuotaPoolSlice.PRIORITY,
                ),
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-1",
                    quotaSlice = QuotaPoolSlice.PRIORITY,
                ),
            ),
        )
    }

    @Test
    fun status429402410AreNeverFoldedIntoClientError() {
        assertEquals(DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429, DedicatedSync.statusOf(429))
        assertEquals(DedicatedStatus.HTTP_PAYMENT_REQUIRED_402, DedicatedSync.statusOf(402))
        assertEquals(DedicatedStatus.HTTP_GONE_410, DedicatedSync.statusOf(410))
        assertEquals(DedicatedStatus.HTTP_CLIENT_ERROR, DedicatedSync.statusOf(418))
        assertEquals(DedicatedStatus.HTTP_NOT_FOUND_404, DedicatedSync.statusOf(404))
    }

    @Test
    fun unknownDimensionsStayNullNeverZero() {
        val dim = QuotaDimension.unknown()
        assertNull(dim.value)
        assertEquals(QuotaValueState.UNKNOWN, dim.state)
        assertFalse(dim.upperBound)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nullSliceMustNotCoexistWithExplicitSlices() {
        // null = 未區分（涵蓋全體）：同一 project×model 下 null 與明確切片並存
        // 無法證明不重疊，屬合併風險，必須拒絕（ADAPT-005／ADAPT-007；X-004）。
        DedicatedSync.assertNoMerge(
            listOf(
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-1",
                    quotaSlice = null,
                ),
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-1",
                    quotaSlice = QuotaPoolSlice.STANDARD_INTERACTIVE,
                ),
            ),
        )
    }

    @Test
    fun nullSliceAloneAndExplicitSlicesAlonePass() {
        // 單一 null（無切片概念 provider，如 Groq／OpenRouter 全域池）放行。
        DedicatedSync.assertNoMerge(
            listOf(
                QuotaPool(
                    QuotaScopeKind.PER_ORG_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    quotaSlice = null,
                ),
            ),
        )
        // 不同 owner 下 null 與明確切片各存一筆放行（鍵不同，無重疊）。
        DedicatedSync.assertNoMerge(
            listOf(
                QuotaPool(
                    QuotaScopeKind.PER_ORG_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    quotaSlice = null,
                ),
                QuotaPool(
                    QuotaScopeKind.PER_PROJECT_MODEL,
                    "m-a",
                    provenance = DedicatedProvenance.OFFICIAL_DOC,
                    projectId = "proj-1",
                    quotaSlice = QuotaPoolSlice.PRIORITY,
                ),
            ),
        )
    }

    @Test
    fun errorToStatusWiringNeverFolds429402410() {
        // DedicatedError → DedicatedStatus 接線（ADAPT-007；SPEC §9）：
        // 429／402／410 永不折成 CLIENT_ERROR。
        fun errorOf(httpStatus: Int) = DedicatedError(
            httpStatus = httpStatus,
            providerCode = null,
            category = DedicatedErrorCategory.UNKNOWN,
            retryable = false,
            provenance = DedicatedProvenance.OBSERVED,
        )
        assertEquals(DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429, DedicatedSync.statusOf(errorOf(429)))
        assertEquals(DedicatedStatus.HTTP_PAYMENT_REQUIRED_402, DedicatedSync.statusOf(errorOf(402)))
        assertEquals(DedicatedStatus.HTTP_GONE_410, DedicatedSync.statusOf(errorOf(410)))
        assertEquals(DedicatedStatus.HTTP_CLIENT_ERROR, DedicatedSync.statusOf(errorOf(418)))
        assertEquals(DedicatedStatus.HTTP_NOT_FOUND_404, DedicatedSync.statusOf(errorOf(404)))
        // 傳輸成功但應用層有錯（mid-stream）：status 仍為傳輸層，category 承載語意。
        assertEquals(DedicatedStatus.UNKNOWN, DedicatedSync.statusOf(errorOf(200)))
    }
}
