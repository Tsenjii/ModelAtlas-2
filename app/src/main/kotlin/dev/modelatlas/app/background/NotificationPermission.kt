package dev.modelatlas.app.background

/**
 * X-024 — notification runtime permission + channel metadata (pure part).
 *
 * X-014 depends on this being available, so the contract is defined here
 * without referencing Android framework types, keeping it JVM-testable.
 * The Android bridge (`NotificationChannelBridge`) consumes these values.
 */
object NotificationPermission {

    /** `Manifest.permission.POST_NOTIFICATIONS` (API 33+). Manifest wiring is an integrator step. */
    const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    const val REQUIRED_API = 33

    const val CHANNEL_ID = "modelatlas.events"

    /** Runtime permission is only required on API 33+. minSdk 26 requires the version guard. */
    fun requiresRuntimePermission(sdkInt: Int): Boolean = sdkInt >= REQUIRED_API

    /**
     * @param selfPermission a `context::checkSelfPermission` style probe
     * @return true when posting is allowed; on API < 33 always true (no runtime grant).
     */
    fun isGranted(sdkInt: Int, selfPermission: (String) -> Boolean): Boolean =
        !requiresRuntimePermission(sdkInt) || selfPermission(POST_NOTIFICATIONS_PERMISSION)

    /**
     * Channel metadata. MUST NOT contain provider/credential/secret material
     * (BACKLOG X-024 acceptance 1).
     */
    fun channelMetadata(): NotificationChannelMetadata = NotificationChannelMetadata(
        id = CHANNEL_ID,
        name = "ModelAtlas 事件通知",
        description = "模型價格、額度與服務狀態變更通知",
    )

    /**
     * Permission-denied must be silent (record "not delivered"), never crash and
     * never pretend delivery (X-014 acceptance 6).
     */
    fun dispatch(permitted: Boolean, send: () -> Unit): NotificationDispatchResult {
        if (!permitted) return NotificationDispatchResult.NotDelivered(reason = NOT_DELIVERED_PERMISSION_MISSING)
        return try {
            send()
            NotificationDispatchResult.Delivered
        } catch (t: Throwable) {
            NotificationDispatchResult.NotDelivered(reason = t.javaClass.simpleName)
        }
    }

    const val NOT_DELIVERED_PERMISSION_MISSING = "POST_NOTIFICATIONS_DENIED"
}

data class NotificationChannelMetadata(
    val id: String,
    val name: String,
    val description: String,
)

sealed interface NotificationDispatchResult {
    data object Delivered : NotificationDispatchResult
    data class NotDelivered(val reason: String) : NotificationDispatchResult
}
