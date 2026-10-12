package dev.modelatlas.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * X-014 quota delivery gate（D-2026-10-09-031 item 2／review F-03）。
 *
 * [DeliverySink] carries no state, so the just-written persisted row is the durable truth.
 * Recovery and other edges remain recorded-not-notified: the row must exist for history
 * even when delivery is skipped. Only an exact `toState == "QUOTA_LOW"` reaches [inner].
 * Missing rows also fail closed; returning true means processed silently, matching the
 * settings-gate skip in NotificationManagerDeliverySink, with no retry or undelivered mark.
 */
class QuotaLowDeliverySink(private val inner: DeliverySink, private val store: EventStore) : DeliverySink {
    override fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean {
        // Bridge the synchronous delivery port to the suspend Room read after the write commits.
        val row = runBlocking(Dispatchers.IO) { store.quotaEventById(eventId) }
        if (row?.toState != "QUOTA_LOW") return true
        return inner.deliver(eventId, scopeKey, source, observedAt)
    }

    override fun markUndelivered(eventId: String) {
        inner.markUndelivered(eventId)
    }
}
