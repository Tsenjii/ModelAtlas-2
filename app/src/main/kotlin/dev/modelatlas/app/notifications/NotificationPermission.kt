package dev.modelatlas.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * X-024（PRODUCT_SPEC §16 Notifications 投遞前提）通知執行期權限與 channel 基礎。
 *
 * 本物件負責：
 * 1. manifest 宣告檢查（[POST_NOTIFICATIONS] 是否被宣告）——純函式 [isDeclared] 可單測。
 * 2. API 版本守衛與執行期授權查詢（minSdk 26；API 33+ 才需要 runtime request）。
 * 3. 通知 channel 建立與其 metadata；metadata **不得**含 provider 憑證／raw secret。
 *
 * 本物件不負責任何通知內容或事件判定（屬 X-013／X-014），也不主動請求權限。
 */
object NotificationPermission {

    /** Android 13（API 33）起通知需執行期權限；宣告於 AndroidManifest。 */
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** 首次需要 runtime request 的 SDK 版本。 */
    const val RUNTIME_REQUEST_MIN_SDK = 33

    /** 通知 channel id（穩定）。 */
    const val CHANNEL_ID = "modelatlas.sync.alerts"

    /** channel 顯示名稱（不含任何 provider／帳號／憑證資訊）。 */
    const val CHANNEL_NAME = "ModelAtlas alerts"

    /** channel 描述（不含任何 provider／帳號／憑證資訊）。 */
    const val CHANNEL_DESCRIPTION = "Background sync and service status alerts"

    /** API 33+ 才需要 runtime request；26–32 由 manifest 宣告即生效。 */
    fun requiresRuntimeRequest(sdkInt: Int): Boolean = sdkInt >= RUNTIME_REQUEST_MIN_SDK

    /**
     * 授權判定：API 33 前一律視為已授權；API 33+ 需 [permissionResult] 為 GRANTED。
     * 純函式，與 [PackageManager] 解耦以便單測。
     */
    fun isGranted(sdkInt: Int, permissionResult: Int): Boolean =
        !requiresRuntimeRequest(sdkInt) || permissionResult == PackageManager.PERMISSION_GRANTED

    fun shouldRequestOnMasterEnable(sdkInt: Int, permissionResult: Int): Boolean =
        requiresRuntimeRequest(sdkInt) && !isGranted(sdkInt, permissionResult)

    /** manifest 是否宣告 [POST_NOTIFICATIONS]（純函式，輸入為已宣告權限清單）。 */
    fun isDeclared(declaredPermissions: Collection<String>): Boolean =
        declaredPermissions.contains(POST_NOTIFICATIONS)

    /** 讀取本 app 於 manifest 宣告的權限；供裝置測試／診斷使用。 */
    fun declaredPermissions(context: Context): List<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions?.toList().orEmpty()
    }

    /** 目前是否確實持有通知權限（含 API 版本守衛）。 */
    fun hasPermission(context: Context): Boolean =
        isGranted(Build.VERSION.SDK_INT, context.checkSelfPermission(POST_NOTIFICATIONS))

    /** channel metadata：名稱與描述為常數，不含 provider／憑證資訊。 */
    fun channelMetadata(): NotificationChannelMetadata =
        NotificationChannelMetadata(
            id = CHANNEL_ID,
            name = CHANNEL_NAME,
            description = CHANNEL_DESCRIPTION,
        )

    /**
     * 冪等建立通知 channel；API 26 以下為 no-op（minSdk 26 目前恆成立，保留守衛）。
     * 只設定中性 metadata，不在此放任何 provider 或 secret。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }
}

/** 通知 channel 的中性 metadata（可單測，無 Android 相依）。 */
data class NotificationChannelMetadata(
    val id: String,
    val name: String,
    val description: String,
)
