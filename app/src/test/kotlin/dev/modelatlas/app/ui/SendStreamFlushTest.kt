package dev.modelatlas.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PK-21 SendStreamFlushController JVM 單測（pure Kotlin，無 Android 依賴，無協程）.
 * Publication is caller-dispatched only: the controller owns no coroutine, thread, or
 * timer — every publish happens synchronously inside onDelta/flushPending on the caller.
 */
class SendStreamFlushTest {

    private var fakeNow: Long = 0L
    private var canPublishFlag: Boolean = true
    private val snapshots = mutableListOf<String>()

    private fun controller() = SendStreamFlushController(
        nowMs = { fakeNow },
        canPublish = { canPublishFlag },
        publish = { snapshots += it },
    )

    @Test
    fun manyDeltasAcrossFlushWindowsYieldExactFinalText() {
        val c = controller()
        val deltas = listOf("Hel", "lo", ", ", "wor", "ld", "!")
        fakeNow = 1000L
        c.onDelta(deltas[0])
        c.onDelta(deltas[1])
        fakeNow = 1060L
        c.onDelta(deltas[2])
        c.onDelta(deltas[3])
        fakeNow = 1120L
        c.onDelta(deltas[4])
        c.flushPending()
        c.onDelta(deltas[5])
        c.flushPending()
        assertEquals(deltas.joinToString(""), snapshots.last())
    }

    @Test
    fun deltasWithinOneWindowStayBounded() {
        val c = controller()
        fakeNow = 1000L
        repeat(20) { c.onDelta("t$it") }
        assertTrue(snapshots.size <= 3)
        c.flushPending()
        assertTrue(snapshots.size <= 3)
        assertTrue(snapshots.isNotEmpty())
        assertEquals((0 until 20).joinToString("") { "t$it" }, snapshots.last())
    }

    @Test
    fun firstDeltaPublishesImmediately() {
        val c = controller()
        fakeNow = 1000L
        c.onDelta("first")
        assertEquals(listOf("first"), snapshots)
    }

    @Test
    fun staleJobPublishesNothing() {
        val c = controller()
        fakeNow = 1000L
        c.onDelta("a")
        assertEquals(listOf("a"), snapshots)
        canPublishFlag = false
        c.onDelta("b")
        c.flushPending()
        assertEquals(listOf("a"), snapshots)
    }

    @Test
    fun selectionChangeIsolatesOldBuilder() {
        val c = controller()
        fakeNow = 1000L
        c.onDelta("old")
        assertEquals(listOf("old"), snapshots)
        canPublishFlag = false
        c.onDelta("stale-tail")
        c.flushPending()
        assertEquals(listOf("old"), snapshots)
        canPublishFlag = true
        assertEquals(listOf("old"), snapshots)
        fakeNow = 1060L
        c.onDelta("new")
        assertEquals("oldstale-tailnew", snapshots.last())
    }

    @Test
    fun bufferedTailFlushesExactlyOnce() {
        val c = controller()
        fakeNow = 1000L
        c.onDelta("a")
        c.onDelta("b")
        c.onDelta("c")
        assertEquals(listOf("a"), snapshots)
        c.flushPending()
        assertEquals(listOf("a", "abc"), snapshots)
    }

    @Test
    fun everySnapshotIsPrefixOfFinal() {
        val c = controller()
        val deltas = listOf("one", "two", "three", "four", "five")
        fakeNow = 1000L
        c.onDelta(deltas[0])
        fakeNow = 1060L
        c.onDelta(deltas[1])
        fakeNow = 1120L
        c.onDelta(deltas[2])
        fakeNow = 1180L
        c.onDelta(deltas[3])
        fakeNow = 1240L
        c.onDelta(deltas[4])
        c.flushPending()
        val final = snapshots.last()
        assertTrue(snapshots.isNotEmpty())
        snapshots.forEach { assertTrue(final.startsWith(it)) }
        assertEquals(deltas.joinToString(""), final)
    }

    @Test
    fun emptyTextNeverTriggersPublish() {
        val c = controller()
        fakeNow = 1000L
        c.onDelta("")
        c.flushPending()
        assertTrue(snapshots.isEmpty())
    }
}
