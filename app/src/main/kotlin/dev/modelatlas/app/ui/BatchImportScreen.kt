package dev.modelatlas.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.core.onboarding.batch.BatchSnapshot
import dev.modelatlas.core.onboarding.batch.BatchStatus
import dev.modelatlas.core.onboarding.batch.BatchParseException
import dev.modelatlas.core.onboarding.batch.StrictBatchJsonParser

/** 僅由 remember 持有；不提供 Saver，也不把原文放入診斷字串。 */
internal class TransientFormInput {
    var value by mutableStateOf("")
    fun clear() { value = "" }

    fun submitBatch(onStart: (String) -> Unit): Boolean {
        try {
            try {
                StrictBatchJsonParser.parse(value)
            } catch (_: BatchParseException) {
                return false
            }
            onStart(value)
            return true
        } finally {
            clear()
        }
    }
}

internal fun closeBatchForm(busy: Boolean, input: TransientFormInput, onCancel: () -> Unit, onClose: () -> Unit) {
    input.clear()
    if (busy) onCancel() else onClose()
}

@Composable
fun BatchImportScreen(
    batches: List<BatchSnapshot>,
    busy: Boolean,
    onStart: (String) -> Unit,
    onResume: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val input = remember { TransientFormInput() }
    var confirmStart by remember { mutableStateOf(false) }
    var resumeId by remember { mutableStateOf<String?>(null) }
    var invalidInput by remember { mutableStateOf(false) }
    DisposableEffect(input) { onDispose { input.clear() } }
    fun close() {
        confirmStart = false
        resumeId = null
        closeBatchForm(busy, input, onCancel, onClose)
    }
    BackHandler { close() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.batch_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.batch_description))
        OutlinedTextField(input.value, { input.value = it; invalidInput = false }, label = { Text(stringResource(R.string.batch_json)) },
            supportingText = { Text(stringResource(R.string.form_audit_batch_masked)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = invalidInput,
            modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 4, maxLines = 8)
        if (invalidInput) Text(stringResource(R.string.form_audit_batch_invalid), color = MaterialTheme.colorScheme.error)
        Button(onClick = { confirmStart = true }, enabled = !busy && input.value.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.batch_start))
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { close() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_cancel_probe)) }
        }
        batches.forEach { batch ->
            Text(stringResource(R.string.batch_status, batch.batchId.take(8), batch.status.name), style = MaterialTheme.typography.titleMedium)
            batch.stopReason?.let { Text(it.name) }
            if (batch.status != BatchStatus.COMPLETED) OutlinedButton(onClick = { resumeId = batch.batchId }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.batch_resume))
            }
            batch.items.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.batch_item, item.index + 1, item.status.name))
                        Text(item.draft.providerName ?: stringResource(R.string.onboarding_draft))
                        Text(stringResource(R.string.onboarding_request_count, item.requestsSpent))
                        item.failure?.let { Text(it.name) }
                        item.workflowReason?.let { Text(it.name) }
                        item.workflowId?.let { id ->
                            TextButton(onClick = { onOpen(id) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.batch_open_review))
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { close() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) }
    }
    if (confirmStart || resumeId != null) AlertDialog(
        onDismissRequest = { confirmStart = false; resumeId = null },
        title = { Text(stringResource(R.string.form_audit_batch_confirm_title)) },
        text = { Text(stringResource(R.string.form_audit_batch_confirm_body)) },
        confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                val id = resumeId
                confirmStart = false
                resumeId = null
                if (id == null) invalidInput = !input.submitBatch(onStart)
                else { input.clear(); onResume(id) }
            }) { Text(stringResource(R.string.form_audit_batch_confirm_action)) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { confirmStart = false; resumeId = null }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
