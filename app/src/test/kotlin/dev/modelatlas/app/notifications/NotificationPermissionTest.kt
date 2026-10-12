package dev.modelatlas.app.notifications

import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-024 通知權限純函式驗證（BACKLOG X-024 驗收 1；PRODUCT_SPEC §16 投遞前提）。
 *
 * 只驗證不依賴 Android runtime 的純函式與常數：channel metadata 不得含憑證資訊、
 * API 版本守衛與 manifest 宣告檢查。真機授權查詢屬 device test，不在此偽造。
 */
class NotificationPermissionTest {

    /** X-024 驗收 1：channel metadata 不含任何 provider／帳號／憑證字樣。 */
    @Test
    fun channelMetadataIsSecretFreeAndPermissionGuardIsVersionAware() {
        val metadata = NotificationPermission.channelMetadata()
        assertTrue(metadata.id.isNotBlank())
        assertTrue(metadata.name.isNotBlank())
        assertTrue(metadata.description.isNotBlank())

        val haystack = (metadata.id + " " + metadata.name + " " + metadata.description).lowercase()
        for (forbidden in listOf("key", "token", "secret", "pat", "credential", "password")) {
            assertFalse("channel metadata 不得含 '$forbidden'", haystack.contains(forbidden))
        }

        // minSdk 26：API 33+ 才需 runtime request。
        assertFalse(NotificationPermission.requiresRuntimeRequest(32))
        assertTrue(NotificationPermission.requiresRuntimeRequest(33))

        // API < 33 由 manifest 宣告即生效；33+ 需使用者授予。
        assertTrue(NotificationPermission.isGranted(32, PackageManager.PERMISSION_DENIED))
        assertFalse(NotificationPermission.isGranted(33, PackageManager.PERMISSION_DENIED))
        assertTrue(NotificationPermission.isGranted(33, PackageManager.PERMISSION_GRANTED))

        assertTrue(NotificationPermission.isDeclared(listOf(NotificationPermission.POST_NOTIFICATIONS)))
        assertFalse(NotificationPermission.isDeclared(listOf("android.permission.INTERNET")))
    }
}
