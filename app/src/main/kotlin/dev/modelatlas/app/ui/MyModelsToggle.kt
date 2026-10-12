package dev.modelatlas.app.ui

import dev.modelatlas.app.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * ③-1 My Models 明確加入／移除按鈕（controlled，主 AI 親寫）。
 *
 * - 顯示文字完全由 [isMyModel] 決定（加入／移出），狀態由 VM Flow 真值驅動，
 *   不在 UI 層猜測或快取。
 * - 單一 Button 語意、可點擊，最小 48dp；無 DB／vault／網路操作。
 */
@Composable
fun MyModelsToggle(
    isMyModel: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inFlight: Boolean = false,
    error: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onToggle,
            enabled = enabled && !inFlight,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { role = Role.Button },
        ) {
            Text(
                text = if (isMyModel) stringResource(R.string.my_models_remove)
                else stringResource(R.string.my_models_add),
                color = if (enabled && !inFlight) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
        if (error) {
            Text(
                text = stringResource(R.string.my_models_toggle_error_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
