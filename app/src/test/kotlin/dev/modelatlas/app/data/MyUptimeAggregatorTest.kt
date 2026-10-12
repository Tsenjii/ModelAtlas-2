package dev.modelatlas.app.data

import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MYM-005 APPTEST-REWIRE：My Uptime app 側直調測試（JVM 純函式）。
 *
 * 依據：AGENTS.md → Contract §23.4（My Uptime＝Provider×Account×Credential×Group×Model×Endpoint＋Time；
 * Manual／Playground／Compare Live／Assistant 真 inference 同 scope 累積；
 * User Stop／Cancel 分子分母全排除；Provider Success／External 分離）
 * → 簽名定案＝committed `app/src/main/kotlin/dev/modelatlas/app/data/MyUptime.kt`
 *   （`MyUptimeScope`／`MyUptimeAggregator.summarize`）；
 * → 11 cases 權威＝本檔 committed 測試本身（session 骨架報告非 durable）；
 * → TESTA2 停手理由：core 源集測試看不見 app 類，不硬湊（路線記錄見下段）。
 *
 * 本檔為 TESTA2 建議路線 (1)：在 app 源集新增對等直調測試，11 case 名與斷言原樣搬運；
 * SkeletonScope→MyUptimeScope、SkeletonWindow→PerformanceWindow／preset.windowFor、
 * summarize→MyUptimeAggregator.summarize 直調。core 骨架檔不動（去留主 AI 定）。
 *
 * 純記憶體測試，不碰 DB／網路；樣本為本地構造值，非產品資料。無 sleep、無時間放寬。
 */
class MyUptimeAggregatorTest {

    private val scope = MyUptimeScope(
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "cred-1",
        modelId = "m1",
        endpointId = "e1",
        group = "g1",
    )
    private val window = PerformanceWindow(startInclusive = 0L, endExclusive = 10_000L)

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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
        assertEquals(1, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun emptyWindowReturnsNullNotZero() {
        val summary = MyUptimeAggregator.summarize(emptyList(), scope, window)
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
        val summary = MyUptimeAggregator.summarize(rows, scope, window)
        // 同 scope 真實請求不分來源匯總；來源維度不做隔離。
        assertEquals(4, summary.decided)
        assertEquals(4, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun windowIsHalfOpenOnRequestStartedAt() {
        // 等值於 PerformanceWindow(5000, 10000)：落 start 計、落 end 不計。
        val pinned = MyUptimePreset.CUSTOM.windowFor(nowMillis = 10_000L, customWindowMillis = 5_000L)
        val rows = listOf(
            sample("before", startedAt = 4_999L),
            sample("atStart", startedAt = 5_000L),
            sample("inside", startedAt = 9_999L),
            sample("atEnd", startedAt = 10_000L),
        )
        val summary = MyUptimeAggregator.summarize(rows, scope, pinned)
        assertEquals(2, summary.decided)
        assertEquals(2, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
    }
}
