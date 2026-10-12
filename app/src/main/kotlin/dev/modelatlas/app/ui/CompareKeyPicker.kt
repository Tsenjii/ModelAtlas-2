package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.app.data.CompareKeyChoice
import dev.modelatlas.app.data.CompareKeySelection

/**
 * MODEL-008 cross-provider multi-key picker (controlled, main-owned).
 *
 * - Renders only the supplied [choices]; never auto-selects, never reads DB/keys.
 * - Each row is a single [Role.Checkbox] toggle target (>=48dp), label + key/group text.
 * - [enabled]=false keeps rows visible but non-interactive. Cap/count policy lives in the caller.
 */
@Composable
fun CompareKeyPicker(
    choices: List<CompareKeyChoice>,
    selections: Set<CompareKeySelection>,
    onSelectionChange: (CompareKeySelection, Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.compare_key_picker_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        choices.forEach { choice ->
            val selection = choice.selection
            val checked = selection in selections
            // credentialLabel stays its own exact Text node so rows remain addressable
            // by key label; the structural group renders separately when not already in it.
            val group = selection.group?.takeIf { it.isNotBlank() && !choice.credentialLabel.contains(it) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { onSelectionChange(selection, it) },
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = choice.providerName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                    Text(
                        text = choice.credentialLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    if (group != null) {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }
    }
}
