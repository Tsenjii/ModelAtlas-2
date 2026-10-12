package dev.modelatlas.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.app.notifications.NotificationRule
import dev.modelatlas.app.notifications.NotificationSettingsState

/** Controlled settings only; the parent owns scrolling, persistence and save feedback. */
@Composable
fun NotificationSettingsSection(
    state: NotificationSettingsState,
    onMasterChanged: (Boolean) -> Unit,
    onRuleChanged: (NotificationRule, Boolean) -> Unit,
    enabled: Boolean = true,
    permissionBlocked: Boolean = false,
    onOpenSystemSettings: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.notification_settings_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        NotificationToggleRow(
            label = stringResource(R.string.notification_master_label),
            checked = state.masterEnabled,
            enabled = enabled,
            tag = "notification_master",
            onCheckedChange = onMasterChanged,
        )
        if (state.masterEnabled && permissionBlocked) {
            val warningColor = MaterialTheme.colorScheme.error
            Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = warningColor,
                    // Use the core bell with an off slash; no extended-icon dependency.
                    modifier = Modifier.size(20.dp).drawWithContent {
                        drawContent()
                        drawLine(warningColor, Offset.Zero, Offset(size.width, size.height), 2.dp.toPx())
                    },
                )
                Text(
                    text = stringResource(R.string.notification_permission_denied_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = onOpenSystemSettings,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.notification_permission_open_settings))
            }
        }
        Text(
            text = stringResource(R.string.notification_master_off_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NotificationRule.entries.forEach { rule ->
            NotificationToggleRow(
                label = stringResource(rule.labelResource()),
                // Show the saved choice, not the effective master/rule conjunction.
                checked = rule in state.enabledRules,
                enabled = enabled,
                tag = "notification_rule_${rule.name}",
                onCheckedChange = { checked -> onRuleChanged(rule, checked) },
            )
        }
    }
}

@Composable
private fun NotificationToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.let {
                if (enabled) it else it.copy(alpha = 0.38f)
            },
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            // The labelled row is the single accessible switch and touch target.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@StringRes
private fun NotificationRule.labelResource(): Int = when (this) {
    NotificationRule.BECAME_FREE -> R.string.notification_rule_became_free
    NotificationRule.QUOTA_LOW -> R.string.notification_rule_quota_low
    NotificationRule.PENDING_CHECKIN -> R.string.notification_rule_pending_checkin
    NotificationRule.FAVORITE_RECOVERED -> R.string.notification_rule_favorite_recovered
    NotificationRule.PRICE_CHANGE -> R.string.notification_rule_price_change
    NotificationRule.OUTAGE_RECOVERY -> R.string.notification_rule_outage_recovery
}
