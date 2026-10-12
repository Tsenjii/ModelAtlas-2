package dev.modelatlas.core.domain

import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MYM-005 TEST-UPTIME-A：My Uptime scope／cancel／空窗骨架（JVM 純函式，快測）。
 *
 * 依據：Contract §23.4（My Uptime＝Provider×Account×Credential×Group×Model×Endpoint＋Time；
 * Manual／Playground／Compare Live／Assistant 真 inference 同 scope 累積；
 * User Stop／Cancel 分子分母全排除；Provider Success／External 分離）→ D-009（7d 預設、
 * rolling-60s 另命名；本檔只鎖 cancel／scope／空窗，不碰窗預設與 rolling 命名）
 * → committed production `app/src/main/kotlin/dev/modelatlas/app/data/MyUptime.kt`
 *   （`MyUptimeScope`／`MyUptimeAggregator.summarize`；MYM-005 ACCEPTED＝
 *   docs/TRACEABILITY.md:268，綠 `10ed091`）（本檔＝`MyUptimeScopeCancelTest` 一半；
 * windowed 查詢／7d 預設／rolling-60s／半開邊界的 device 驗證屬 `MyUptimeWindowDaoTest`）。
 *
 * DATA 已落地（原「未交付」註記已過時，改寫）：`MyUptime.kt` 已 committed 且
 * MYM-005 ACCEPTED（docs/TRACEABILITY.md:268，綠 `10ed091`／`36965478450`）；app 側
 * 直調對等測試＝`MyUptimeAggregatorTest`（11 cases）、windowed device 驗證＝
 * `MyUptimeWindowDaoTest`。core 測試模組不可依賴 app 模組，因此本檔維持 test-local
 * 純函式骨架鎖定語意：[SkeletonScope]（六維全必填精確匹配，null group 只等於 null，
 * 不當萬用字元；與 `PerformanceScope.matches` 的 null-即-不篩不同）＋[SkeletonWindow]
 * （半開 `[start, end)`，複用 `PerformanceWindow` 語意）＋[SkeletonUptime.summarize]
 * （CANCELLED 分子分母全排除、INTERRUPTED 保留、空窗回 null）。骨架與
 * `MyUptimeAggregator.summarize` 語意對等（app 側直調測試已釘住），期望值
 * （分子／分母／null）兩側一致。
 *
 * 純記憶體測試，不碰 DB／網路；樣本為本地構造值，非產品資料。無 sleep、無時間放寬。
 */
class MyUptimeScopeCancelTest {

    /** 未來 DATA `MyUptimeScope` 的 test-local 鏡像：六維全必填，精確相等才算同 scope。 */
    private data class SkeletonScope(
        val providerId: String,
        val accountId: String,
        val safeCredentialId: String,
        val group: String?,
        val modelId: String,
        val endpointId: String,
    ) {
        fun matches(sample: PerformanceSample): Boolean =
            sample.providerId == providerId &&
                sample.accountId == accountId &&
                sample.safeCredentialId == safeCredentialId &&
                sample.group == group &&
                sample.providerModelId == modelId &&
                sample.endpointId == endpointId
    }

    /** `PerformanceWindow` 語意的 test-local 鏡像：半開區間，落 start 計、落 end 不計。 */
    private data class SkeletonWindow(
        val startInclusive: Long,
        val endExclusive: Long,
    ) {
        fun contains(timestampMillis: Long): Boolean =
            timestampMillis >= startInclusive && timestampMillis < endExclusive
    }

    /** 空窗即 Unknown（null），不補 0 的最小結果。 */
    private data class SkeletonSummary(
        val decided: Int,
        val successes: Int,
        val successRate: Double?,
    )

    /** 未來 DATA `MyUptime` 聚合器的 test-local 鏡像（只做 scope＋窗＋cancel 語意）。 */
    private object SkeletonUptime {
        fun summarize(
            samples: List<PerformanceSample>,
            scope: SkeletonScope,
            window: SkeletonWindow,
        ): SkeletonSummary {
            val scoped = samples.filter { window.contains(it.requestStartedAt) && scope.matches(it) }
            // CANCELLED 分子分母全排除；RUNNING 在途不算 decided（沿 X-016 decided 語意）；
            // INTERRUPTED 保留在分母（恢復後可查的真實歷史，非使用者取消）。
            val decidedSamples = scoped.filter { it.outcome != Outcome.RUNNING && it.outcome != Outcome.CANCELLED }
            val decided = decidedSamples.size
            val successes = decidedSamples.count { it.outcome == Outcome.SUCCESS }
            return SkeletonSummary(
                decided = decided,
                successes = successes,
                successRate = if (decided > 0) successes.toDouble() / decided.toDouble() else null,
            )
        }
    }

    private val scope = SkeletonScope(
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "cred-1",
        group = "g1",
        modelId = "m1",
        endpointId = "e1",
    )
    private val window = SkeletonWindow(startInclusive = 0L, endExclusive = 10_000L)

    private fun sample(
        id: String,
        providerId: String = "p1",
        accountId: String = "a1",
        safeCredentialId: String = "cred-1",
        group: String? = "g1",
        modelId: String = "m1",
        endpointId: String = "e1",
        outcome: Outcome = Outcome.SUCCESS,
        startedAt: Long = 1_000L,
        source: TelemetrySource = TelemetrySource.PLAYGROUND,
    ): PerformanceSample = PerformanceSample(
        id = id,
        providerId = providerId,
        accountId = accountId,
        safeCredentialId = safeCredentialId,
        endpointId = endpointId,
        providerModelId = modelId,
        canonicalModelId = null,
        group = group,
        operation = "CHAT_COMPLETIONS",
        source = source,
        requestStartedAt = startedAt,
        completedAt = if (outcome == Outcome.RUNNING) null else startedAt + 500L,
        usage = Usage(),
        httpStatus = null,
        outcome = outcome,
    )

    @Test
    fun cancelledExcludedFromNumeratorAndDenominator() {
        val rows = listOf(
            sample("ok1", outcome = Outcome.SUCCESS),
            sample("ok2", outcome = Outcome.SUCCESS),
            sample("cx", outcome = Outcome.CANCELLED),
            sample("srv", outcome = Outcome.SERVER),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        // 分母若含 CANCELLED 會是 4、分子若含會是 3；正確語意：分母 3、分子 2。
        assertEquals(3, summary.decided)
        assertEquals(2, summary.successes)
        assertEquals(2.0 / 3.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun allCancelledWindowReturnsNullNotZero() {
        val rows = listOf(
            sample("cx1", outcome = Outcome.CANCELLED),
            sample("cx2", outcome = Outcome.CANCELLED),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        // 全取消＝無 decided 樣本＝Unknown，不補 0／不補 0.0。
        assertEquals(0, summary.decided)
        assertEquals(0, summary.successes)
        assertNull(summary.successRate)
    }

    @Test
    fun interruptedIsRetainedInDenominator() {
        val rows = listOf(
            sample("ok", outcome = Outcome.SUCCESS),
            sample("killed", outcome = Outcome.INTERRUPTED),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        // INTERRUPTED 是進程被殺的真實歷史（開機 recover 寫入），保留在分母、不算成功。
        assertEquals(2, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(0.5, summary.successRate!!, 1e-9)
    }

    @Test
    fun scopeIsolatedByAccount() {
        val rows = listOf(
            sample("same", accountId = "a1"),
            sample("other", accountId = "a2"),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        assertEquals(1, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun scopeIsolatedByCredential() {
        val rows = listOf(
            sample("same", safeCredentialId = "cred-1"),
            sample("other", safeCredentialId = "cred-2"),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        assertEquals(1, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun scopeIsolatedByGroup() {
        val rows = listOf(
            sample("same", group = "g1"),
            sample("other", group = "g2"),
            sample("nullGroup", group = null),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        assertEquals(1, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun scopeIsolatedByEndpoint() {
        val rows = listOf(
            sample("same", endpointId = "e1"),
            sample("other", endpointId = "e2"),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        assertEquals(1, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun scopeIsolatedByProviderAndModel() {
        val rows = listOf(
            sample("same"),
            sample("otherProvider", providerId = "p2"),
            sample("otherModel", modelId = "m2"),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        assertEquals(1, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun emptyWindowReturnsNullNotZero() {
        val summary = SkeletonUptime.summarize(emptyList(), scope, window)
        assertEquals(0, summary.decided)
        assertEquals(0, summary.successes)
        // Unknown != 0：空窗回 null，禁止 0／0.0 冒充已知。
        assertNull(summary.successRate)
    }

    @Test
    fun mixedSourcesShareSameScope() {
        val rows = listOf(
            sample("manual", source = TelemetrySource.MANUAL_TEST),
            sample("play", source = TelemetrySource.PLAYGROUND),
            sample("compare", source = TelemetrySource.COMPARE_LIVE),
            sample("assistant", source = TelemetrySource.ASSISTANT_TEST),
        )
        val summary = SkeletonUptime.summarize(rows, scope, window)
        // 同 scope 真實請求不分來源匯總；來源維度不做隔離。
        assertEquals(4, summary.decided)
        assertEquals(4, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun windowIsHalfOpenOnRequestStartedAt() {
        val pinned = SkeletonWindow(startInclusive = 5_000L, endExclusive = 10_000L)
        val rows = listOf(
            sample("before", startedAt = 4_999L),
            sample("atStart", startedAt = 5_000L),
            sample("inside", startedAt = 9_999L),
            sample("atEnd", startedAt = 10_000L),
        )
        val summary = SkeletonUptime.summarize(rows, scope, pinned)
        assertEquals(2, summary.decided)
        assertEquals(2, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }
}
