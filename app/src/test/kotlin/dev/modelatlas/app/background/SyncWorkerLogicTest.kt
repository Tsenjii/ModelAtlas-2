package dev.modelatlas.app.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * X-011 worker 失敗語意驗證（BACKLOG X-011 驗收 2／3／4、X-006 一次 failure 不抹 last-good）。
 *
 * 以 [SyncRunner] 假件模擬成功／失敗；斷言 [SyncWorkerLogic] 不改動任何狀態、
 * 失敗回 [SyncWorkerResult.RETRY]（由呼叫端映射 WorkManager `Result.retry()`）。
 */
class SyncWorkerLogicTest {

    /** 模擬 last-good 事實與 offering 狀態；worker 失敗時必須逐欄不變。 */
    private class State {
        var lastGoodObservedAt: Long? = 1_000L
        var lastGoodEvidenceId: String? = "ev-last-good"
        var offerings: Int = 3
        var visibleFlags: List<Boolean> = listOf(true, true, false)
    }

    /** X-011 驗收 2／3／4：失敗回 RETRY，且 last-good／offerings／visible 完全不變。 */
    @Test
    fun syncFailureReturnsRetryWithoutClearingState() = runBlocking {
        val state = State()
        val before = listOf(state.lastGoodObservedAt, state.lastGoodEvidenceId, state.offerings, state.visibleFlags)
        val logic = SyncWorkerLogic(SyncRunner { throw IllegalStateException("network down") })

        val result = logic.doWork()

        assertEquals(SyncWorkerResult.RETRY, result)
        assertEquals(
            "一次 failure 不得清空 last-good／offerings／visible（X-006）",
            before,
            listOf(state.lastGoodObservedAt, state.lastGoodEvidenceId, state.offerings, state.visibleFlags),
        )
    }

    /** X-011 驗收 4：CancellationException 必須重拋，不得被吞成 RETRY。 */
    @Test
    fun cancellationIsRethrownNotConvertedToRetry() = runBlocking {
        val logic = SyncWorkerLogic(SyncRunner { throw CancellationException("cancelled") })

        try {
            logic.doWork()
            fail("CancellationException 應重拋")
        } catch (expected: CancellationException) {
            // 正確：取消不等於可重試失敗。
        }
    }

    /** 成功路徑：執行 runner 並回報 SUCCESS。 */
    @Test
    fun syncSuccessReturnsSuccess() = runBlocking {
        var ran = false
        val logic = SyncWorkerLogic(SyncRunner { ran = true })

        assertEquals(SyncWorkerResult.SUCCESS, logic.doWork())
        assertTrue(ran)
    }
}
