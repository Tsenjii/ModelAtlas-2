package dev.modelatlas.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-014 [NotificationManagerDeliverySink] JVM 驗證（純記憶體 fake）。
 *
 * 走 primary constructor 注入全部 seam：不碰 Android framework、不需 Robolectric／mock 框架、
 * 不呼叫任何真實通知 API。設定開關以真實 [NotificationSettingsState.enabled] 語意在 fake 中重現
 * （fake 值為測試構造值，非產品資料）。
 */
class NotificationManagerDeliverySinkTest {

    /** 記錄投遞呼叫（不建立真實 [android.app.Notification]）。 */
    private class PostRecorder {
        var calls: Int = 0
        var lastTitle: String? = null
        var lastBody: String? = null

        fun record(title: String, body: String) {
            calls++
            lastTitle = title
            lastBody = body
        }
    }

    private class Harness(
        masterEnabled: Boolean,
        enabledRules: Set<NotificationRule>,
        private val hasPermission: Boolean = true,
        private val areNotificationsEnabled: Boolean = true,
        private val channelImportance: Int? = IMPORTANCE_DEFAULT,
        private val throwOnPost: Boolean = false,
    ) {
        val recorder = PostRecorder()
        private val state = NotificationSettingsState(
            masterEnabled = masterEnabled,
            enabledRules = enabledRules,
        )

        val sink = NotificationManagerDeliverySink(
            isRuleEnabled = { state.enabled(NotificationRule.OUTAGE_RECOVERY) },
            hasPermission = { hasPermission },
            areNotificationsEnabled = { areNotificationsEnabled },
            channelImportance = { channelImportance },
            titleText = { FAKE_TITLE },
            bodyText = { scopeKey -> "$scopeKey $FAKE_BODY" },
            postNotification = { _, title, body ->
                if (throwOnPost) throw SecurityException("fake dispatch refusal")
                recorder.record(title, body)
            },
        )

        fun deliver(): Boolean =
            sink.deliver("provider-fake-event", "provider-fake", "OBSERVED", 0L)
    }

    @Test
    fun masterOff_isProcessedWithoutNotifying() {
        val harness = Harness(masterEnabled = false, enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY))
        assertTrue(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    @Test
    fun ruleOff_isProcessedWithoutNotifying() {
        val harness = Harness(masterEnabled = true, enabledRules = emptySet())
        assertTrue(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    @Test
    fun missingPermission_isNotDelivered() {
        val harness = Harness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY),
            hasPermission = false,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    @Test
    fun notificationsGloballyOff_isNotDelivered() {
        val harness = Harness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY),
            areNotificationsEnabled = false,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    @Test
    fun channelImportanceNone_isNotDelivered() {
        val harness = Harness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY),
            channelImportance = IMPORTANCE_NONE,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    @Test
    fun channelImportanceDefault_notifiesOnceWithScopedText() {
        val harness = Harness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY),
            channelImportance = IMPORTANCE_DEFAULT,
        )
        assertTrue(harness.deliver())
        assertEquals(1, harness.recorder.calls)
        // X-003：投遞內容只含 resource 文字＋scopeKey（providerId）。
        assertEquals(FAKE_TITLE, harness.recorder.lastTitle)
        assertEquals("provider-fake $FAKE_BODY", harness.recorder.lastBody)
    }

    @Test
    fun dispatchFailure_isNotDeliveredAndDoesNotThrow() {
        val harness = Harness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY),
            throwOnPost = true,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    @Test
    fun repeatedCalls_areGatedFreshEachTime() {
        val harness = Harness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY),
        )
        assertTrue(harness.deliver())
        assertTrue(harness.deliver())
        assertEquals(2, harness.recorder.calls)
    }

    @Test
    fun markUndelivered_isDiagnosticNoOp() {
        val harness = Harness(masterEnabled = true, enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY))
        harness.sink.markUndelivered("provider-fake-event")
        assertEquals(0, harness.recorder.calls)
    }

    private companion object {
        // android.app.NotificationManager 的 importance 常數以純值重現，
        // 避免 JVM 測試觸及 framework（值與 framework 一致：NONE=0／DEFAULT=3）。
        const val IMPORTANCE_NONE = 0
        const val IMPORTANCE_DEFAULT = 3
        const val FAKE_TITLE = "fake title"
        const val FAKE_BODY = "fake body"
    }
}