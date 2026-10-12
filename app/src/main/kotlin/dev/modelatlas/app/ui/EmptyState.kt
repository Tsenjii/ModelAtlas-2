package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * P6 wave-2 worker F：統一空態元件。
 *
 * TRACEABILITY：PRODUCT_SPEC §2（空狀態明示；不渲染假資料）、MODEL-004/008/009 空分支共用。
 *
 * - 圖示＋標題＋說明＋動作按鈕 slot；slot 為空即不佔位。
 * - 小屏（寬 <360dp）或大字級（fontScale >1.3）時字級收斂一級，避免裁切換行。
 * - 只做呈現；不讀資料來源，未知一律明示（呼叫端給什麼顯示什麼）。
 */
@Composable
internal fun AtlasEmptyStateCard(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val convergeTypography = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            Modifier
                .fillMaxWidth()
                .padding(if (compact) AtlasSpace.lg else AtlasSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AtlasSpace.md),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(if (compact) 48.dp else 64.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(AtlasSpace.icon),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                title,
                style = if (convergeTypography) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                description,
                style = if (convergeTypography) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    action()
                }
            }
        }
    }
}
