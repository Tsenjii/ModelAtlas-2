package dev.modelatlas.app.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * X-024 — Android bridge for [NotificationPermission].
 *
 * Creates the notification channel (API 26+) with the sanitized metadata and
 * exposes a permission probe. The `POST_NOTIFICATIONS` manifest entry and the
 * API 33 runtime request flow are integrator steps (not auto-started here).
 */
object NotificationChannelBridge {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NotificationPermission.CHANNEL_ID) != null) return

        val metadata = NotificationPermission.channelMetadata()
        val channel = NotificationChannel(
            metadata.id,
            metadata.name,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = metadata.description
        }
        manager.createNotificationChannel(channel)
    }

    fun isPermissionGranted(context: Context): Boolean =
        NotificationPermission.isGranted(Build.VERSION.SDK_INT) { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
}
