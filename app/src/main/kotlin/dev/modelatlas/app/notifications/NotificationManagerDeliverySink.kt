package dev.modelatlas.app.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import dev.modelatlas.app.R
import dev.modelatlas.app.data.DeliverySink

/**
 * X-014 production [DeliverySink]：把已寫入事件投遞到 Android 通知通道（單一 rule）。
 *
 * 決策順序為 canonical（PRODUCT_SPEC §5／§16）：**設定閘門 → OS／channel 閘門 → 投遞 → 成功**。
 *  1. 使用者關閉該 rule → 回 true（已處理；使用者刻意靜默不得被當失敗重試，也不得記未投遞）。
 *  2. 缺通知權限／通知被整體關閉／channel importance=IMPORTANCE_NONE → 回 false
 *     （交由 [dev.modelatlas.app.data.EventPipeline] 依
 *     [dev.modelatlas.app.data.EventPipeline.MAX_DELIVERY_ATTEMPTS] 重試後標記未投遞；永不假裝已送）。
 *  3. 投遞成功 → true；投遞拋出（例如 OEM 層 SecurityException）→ false，不 crash。
 *
 * 鐵律：
 * - X-003 secret boundary：通知文字只有 resource 字串＋公開 scopeKey（providerId），
 *   永不含 key／PAT／header／raw body／帳號憑證；本類不主動請求權限（屬 [NotificationPermission]）。
 * - 每個實例只服務單一 rule（預設 [NotificationRule.OUTAGE_RECOVERY]）：其他 rule 各有各的通道，
 *   價格與服務狀態文案不得互相冒充。
 * - 本類不寫任何 DB、不刪事件、不改設定；未投遞事實由 append-only 事件歷史承載。
 *
 * 可測試性：primary constructor 全為注入 seam（純 lambda），Android 依賴全在 production
 * secondary constructor 綁定 [Context]。因此 JVM 單測可完全以記憶體 fake 注入，
 * 不需 Robolectric／mock 框架、也不碰 framework。minSdk 26，直接用 framework
 * [Notification.Builder]／[NotificationManager]，不引入新依賴。
 */
class NotificationManagerDeliverySink(
    private val isRuleEnabled: () -> Boolean,
    private val hasPermission: () -> Boolean,
    private val areNotificationsEnabled: () -> Boolean,
    private val channelImportance: () -> Int?,
    private val titleText: () -> String,
    private val bodyText: (scopeKey: String) -> String,
    private val postNotification: (notificationId: Int, title: String, body: String) -> Unit,
) : DeliverySink {

    /**
     * production 綁定：真實 [Context]＋真實設定 store ＋單一 rule。
     * 預設語意完全等同 PRODUCT_SPEC §16：rule 開關 → 權限 → 通知總開關 → channel importance。
     */
    constructor(
        context: Context,
        settingsStore: NotificationSettingsStore,
        rule: NotificationRule = NotificationRule.OUTAGE_RECOVERY,
    ) : this(
        // 每次呼叫都重新讀 state（不快取：使用者中途改設定立即生效）。
        isRuleEnabled = { settingsStore.state.value.enabled(rule) },
        hasPermission = { NotificationPermission.hasPermission(context) },
        areNotificationsEnabled = {
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
        },
        // 冪等建立 channel 後讀 importance（minSdk 26 恆有 API 26 路徑）。
        channelImportance = {
            NotificationPermission.ensureChannel(context)
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(NotificationPermission.CHANNEL_ID)
                ?.importance
        },
        titleText = {
            when (rule) {
                NotificationRule.OUTAGE_RECOVERY -> context.getString(R.string.notification_rule_outage_recovery)
                NotificationRule.PRICE_CHANGE -> context.getString(R.string.notification_rule_price_change)
                NotificationRule.QUOTA_LOW -> context.getString(R.string.notification_rule_quota_low)
                // 其他 rule 各有各的 sink 實例；此實例收到即為接線錯誤，fail-closed。
                else -> throw IllegalStateException("rule not served by this sink: $rule")
            }
        },
        bodyText = { scopeKey ->
            when (rule) {
                NotificationRule.OUTAGE_RECOVERY ->
                    context.getString(R.string.notification_outage_recovery_body, scopeKey)
                NotificationRule.PRICE_CHANGE ->
                    context.getString(R.string.notification_price_change_body, scopeKey)
                NotificationRule.QUOTA_LOW ->
                    context.getString(R.string.notification_quota_low_body, scopeKey)
                else -> throw IllegalStateException("rule not served by this sink: $rule")
            }
        },
        postNotification = { notificationId, title, body ->
            val notification = Notification.Builder(context, NotificationPermission.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(notificationId, notification)
        },
    )

    override fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean {
        // 1) 使用者意圖優先：rule 關閉＝已處理（true），不重試、不記未投遞。
        if (!isRuleEnabled()) return true
        // 2) OS 閘門：權限／通知總開關／channel importance 缺一即不可投遞（false → 管線重試）。
        if (!hasPermission()) return false
        if (!areNotificationsEnabled()) return false
        if (channelImportance() == NotificationManager.IMPORTANCE_NONE) return false
        // 3) 投遞：內容只有 resource 字串＋scopeKey（providerId），無任何 secret。
        return try {
            // Function-type lambda: positional args only (named args prohibited for fn types).
            postNotification(
                eventId.hashCode() and 0x7FFFFFFF,
                titleText(),
                bodyText(scopeKey),
            )
            true
        } catch (_: Throwable) {
            // 投遞失敗（含 OEM 層 SecurityException）視為未送：回 false 交由管線重試，不 crash。
            false
        }
    }

    /**
     * 標記未投遞：診斷語意上的終點（管線已用盡重試上限）。
     * 刻意不做事——不改設定、不刪事件、不寫 DB、不迴圈、不記任何含 secret 的 log。
     */
    override fun markUndelivered(eventId: String) {
        // no-op（事件本身已 append-only 留在 EventStore，未投遞事實由事件歷史承載）。
    }
}
