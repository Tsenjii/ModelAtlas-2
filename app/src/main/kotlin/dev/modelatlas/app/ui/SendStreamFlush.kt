package dev.modelatlas.app.ui

import android.os.SystemClock

internal const val FLUSH_INTERVAL_MS = 50L

internal class SendStreamFlushController(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val canPublish: () -> Boolean,
    private val publish: (String) -> Unit,
) {
    private val builder = StringBuilder()
    private var lastFlushMs = 0L

    fun onDelta(text: String) {
        builder.append(text)
        val now = nowMs()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) flushPending()
    }

    fun flushPending() {
        if (builder.isEmpty()) return
        if (!canPublish()) return
        publish(builder.toString())
        lastFlushMs = nowMs()
    }
}
