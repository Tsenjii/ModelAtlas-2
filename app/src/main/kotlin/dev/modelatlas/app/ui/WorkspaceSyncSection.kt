package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.R
import dev.modelatlas.app.data.DeviceWorkspaceConflict
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkspaceSyncSection(viewModel: AtlasViewModel) {
    val state by viewModel.workspaceSyncStatus.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(android.os.Build.MODEL) }
    var code by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var resetTransfer by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<DeviceWorkspaceConflict?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshWorkspaceSync() }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(stringResource(R.string.workspace_sync_title),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.workspace_sync_description),style=MaterialTheme.typography.bodySmall)
        if(state.busy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(state.problem) Text(stringResource(R.string.workspace_sync_problem),color=MaterialTheme.colorScheme.error)
        if(state.connected) {
            Text(state.center,style=MaterialTheme.typography.bodySmall)
            Text(stringResource(if(state.shareSecrets) R.string.workspace_sync_keys_allowed else R.string.workspace_sync_keys_local),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.workspace_sync_counts,state.pending,state.stored))
            if(state.lastSuccess>0) Text(stringResource(R.string.workspace_sync_last,DateFormat.getDateTimeInstance().format(Date(state.lastSuccess))),style=MaterialTheme.typography.bodySmall)
            if(state.pendingProjection>0) Text(stringResource(R.string.workspace_sync_compatibility,state.pendingProjection),style=MaterialTheme.typography.bodySmall)
            if(state.paused) Text(stringResource(R.string.workspace_sync_paused),style=MaterialTheme.typography.bodySmall)
            if(state.transferStage.isNotEmpty()) {
                val phase=when(state.transferStage) {
                    "BUILDING" -> R.string.workspace_sync_phase_prepare
                    "DOWNLOADING" -> R.string.workspace_sync_phase_download
                    "CONSUMED" -> R.string.workspace_sync_phase_confirm
                    else -> R.string.workspace_sync_phase_upload
                }
                Text(stringResource(phase),style=MaterialTheme.typography.bodySmall)
                val context=androidx.compose.ui.platform.LocalContext.current
                Text(stringResource(if(state.downloadBytes>0)R.string.workspace_sync_transfer_progress else R.string.workspace_sync_transfer_waiting,
                    android.text.format.Formatter.formatShortFileSize(context,state.uploadedBytes),
                    android.text.format.Formatter.formatShortFileSize(context,state.uploadBytes),
                    android.text.format.Formatter.formatShortFileSize(context,state.downloadedBytes),
                    android.text.format.Formatter.formatShortFileSize(context,state.downloadBytes)),style=MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick={if(state.paused)viewModel.pauseWorkspace(false) else viewModel.syncWorkspace()},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(stringResource(if(state.paused || state.transferStage.isNotEmpty())R.string.workspace_sync_resume else R.string.workspace_sync_now))
            }
            if(!state.paused && (state.busy || state.transferStage.isNotEmpty())) TextButton(onClick={viewModel.pauseWorkspace(true)},modifier=Modifier.heightIn(min=48.dp)) {
                Text(stringResource(R.string.workspace_sync_pause))
            }
            if(state.paused && state.transferStage.isNotEmpty() && state.transferStage!="CONSUMED") TextButton(onClick={resetTransfer=true},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp)) {
                Text(stringResource(R.string.workspace_sync_reset_transfer))
            }
            TextButton(onClick={disconnect=true},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.workspace_sync_disconnect)) }
        } else {
            OutlinedTextField(value=url,onValueChange={url=it},enabled=!state.busy,label={Text(stringResource(R.string.workspace_sync_url))},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=name,onValueChange={name=it.take(120)},enabled=!state.busy,label={Text(stringResource(R.string.workspace_sync_device))},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=code,onValueChange={code=it.take(43)},enabled=!state.busy,label={Text(stringResource(R.string.workspace_sync_code))},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Text(stringResource(R.string.workspace_sync_pair_help),style=MaterialTheme.typography.bodySmall)
            Button(onClick={ val value=code; code=""; viewModel.connectWorkspace(url.trim(),name.trim(),value) },enabled=!state.busy&&code.length==43&&name.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(stringResource(R.string.workspace_sync_connect)) }
        }
        for(item in state.conflicts) {
            OutlinedButton(onClick={conflict=item},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(stringResource(R.string.workspace_sync_conflict,item.kind)) }
        }
    }
    if(disconnect) AlertDialog(onDismissRequest={disconnect=false},title={Text(stringResource(R.string.workspace_sync_disconnect))},text={Text(stringResource(R.string.workspace_sync_disconnect_help))},confirmButton={TextButton(onClick={disconnect=false;viewModel.disconnectWorkspace()},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.workspace_sync_disconnect))}},dismissButton={TextButton(onClick={disconnect=false},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.action_close))}})
    if(resetTransfer) AlertDialog(onDismissRequest={resetTransfer=false},title={Text(stringResource(R.string.workspace_sync_reset_transfer))},text={Text(stringResource(R.string.workspace_sync_reset_help))},confirmButton={TextButton(onClick={resetTransfer=false;viewModel.resetWorkspaceTransfer()},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.workspace_sync_reset_transfer))}},dismissButton={TextButton(onClick={resetTransfer=false},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.action_close))}})
    conflict?.let { item ->
        AlertDialog(onDismissRequest={conflict=null},title={Text(stringResource(R.string.workspace_sync_conflict_title))},text={
            Column(modifier=Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.workspace_sync_conflict_help))
                Text(stringResource(R.string.workspace_sync_local),style=MaterialTheme.typography.titleSmall)
                Text(item.local,style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_sync_remote),style=MaterialTheme.typography.titleSmall)
                Text(item.remote,style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={
            Column {
                TextButton(onClick={conflict=null;viewModel.resolveWorkspaceConflict(item.id,item.localHash,true)},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.workspace_sync_keep_local))}
                TextButton(onClick={conflict=null;viewModel.resolveWorkspaceConflict(item.id,item.localHash,false)},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.workspace_sync_use_remote))}
            }
        },dismissButton={TextButton(onClick={conflict=null},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.action_close))}})
    }
}
