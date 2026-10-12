package dev.modelatlas.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.app.onboarding.AdvancedAuthInput
import dev.modelatlas.app.onboarding.AdvancedCredentialInput
import dev.modelatlas.core.domain.*
import dev.modelatlas.core.onboarding.*

internal class CredentialDraft(ref: SafeCredentialRef? = null) {
    var label by mutableStateOf(ref?.label.orEmpty())
    var account by mutableStateOf(ref?.accountRef.orEmpty())
    var secret by mutableStateOf("")
}
internal class AuthDraft(ref: CustomAuthFieldRef? = null) {
    var location by mutableStateOf(ref?.location ?: CredentialLocation.HEADER)
    var name by mutableStateOf(ref?.name.orEmpty())
    var value by mutableStateOf("")
    var confirmed by mutableStateOf(ref?.overrideConfirmed ?: false)
}

internal fun clearAdvancedFormSecrets(
    credentials: List<CredentialDraft>,
    accountCredentials: List<CredentialDraft>,
    authFields: List<AuthDraft>,
) {
    credentials.forEach { it.secret = "" }
    accountCredentials.forEach { it.secret = "" }
    authFields.forEach { it.value = "" }
}
private class EndpointDraft(ref: EndpointRef? = null) {
    var type by mutableStateOf(ref?.endpointType ?: EndpointType.CHAT_COMPLETIONS)
    var url by mutableStateOf(ref?.url ?: "chat/completions")
    var enabled by mutableStateOf(ref?.enabled ?: true)
}
private class PriceDraft(ref: ManualPricingInput? = null) {
    var model by mutableStateOf(ref?.modelId.orEmpty())
    var group by mutableStateOf(ref?.group.orEmpty())
    var endpoint by mutableStateOf(ref?.endpointType ?: EndpointType.CHAT_COMPLETIONS)
    var operation by mutableStateOf(ref?.operation ?: "CHAT_COMPLETIONS")
    var amount by mutableStateOf(ref?.decimalAmount.orEmpty())
    var currency by mutableStateOf(ref?.currency.orEmpty())
    var unit by mutableStateOf(ref?.perUnit ?: PricePerUnit.PER_REQUEST)
    var effectiveFrom by mutableStateOf(ref?.effectiveFrom.orEmpty())
    var note by mutableStateOf(ref?.note.orEmpty())
}

@Composable
fun AdvancedAddForm(
    busy: Boolean,
    onReview: (OnboardingSnapshot, List<AdvancedCredentialInput>, List<AdvancedAuthInput>) -> Unit,
    onClose: () -> Unit,
    initial: OnboardingSnapshot? = null,
) {
    var name by remember { mutableStateOf(initial?.providerName.orEmpty()) }
    var website by remember { mutableStateOf(initial?.websiteUrl.orEmpty()) }
    var base by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var adapter by remember { mutableStateOf(initial?.adapterTypeOverride ?: AdapterType.UNKNOWN) }
    var mode by remember { mutableStateOf(initial?.authModeOverride ?: AuthMode.BEARER) }
    var anonymousConfirmed by remember { mutableStateOf(initial?.anonymousConfirmed ?: false) }
    var confirmAnonymous by remember { mutableStateOf(false) }
    val credentials = remember { mutableStateListOf(*initial?.credentials.orEmpty().filter { it.role == CredentialRole.INFERENCE }.map { CredentialDraft(it) }.ifEmpty { listOf(CredentialDraft()) }.toTypedArray()) }
    val accountCredentials = remember { mutableStateListOf(*initial?.credentials.orEmpty().filter { it.role == CredentialRole.ACCOUNT }.map { CredentialDraft(it) }.toTypedArray()) }
    val authFields = remember { mutableStateListOf(*initial?.customAuthFields.orEmpty().map { AuthDraft(it) }.toTypedArray()) }
    val endpoints = remember { mutableStateListOf(*initial?.endpointProfiles.orEmpty().map { EndpointDraft(it) }.ifEmpty { listOf(EndpointDraft()) }.toTypedArray()) }
    val prices = remember { mutableStateListOf(*initial?.manualPricing.orEmpty().map { PriceDraft(it) }.toTypedArray()) }
    var group by remember { mutableStateOf(initial?.group.orEmpty()) }
    var models by remember { mutableStateOf(initial?.manualModelIds.orEmpty().joinToString("\n")) }
    var quota by remember { mutableStateOf(initial?.manualQuotaState ?: QuotaState.UNKNOWN) }
    var quotaNote by remember { mutableStateOf(initial?.manualQuotaNote.orEmpty()) }
    var classify by remember { mutableStateOf(initial?.manualClassification != null) }
    var category by remember { mutableStateOf(initial?.manualClassification?.primaryCategory ?: ModelCategory.OTHER) }
    var capabilities by remember { mutableStateOf(initial?.manualClassification?.capabilities.orEmpty()) }
    var modalities by remember { mutableStateOf(initial?.manualClassification?.modalities.orEmpty()) }
    var types by remember { mutableStateOf(initial?.manualClassification?.endpointTypes.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var checkin by remember { mutableStateOf(initial?.checkinUrl.orEmpty()) }
    var tags by remember { mutableStateOf(initial?.tags.orEmpty().joinToString(",")) }
    fun clearSecrets() {
        clearAdvancedFormSecrets(credentials, accountCredentials, authFields)
    }
    DisposableEffect(Unit) { onDispose { clearSecrets() } }
    BackHandler { clearSecrets(); onClose() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.onboarding_advanced), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.advanced_description))
        if (initial != null) Text(stringResource(R.string.advanced_keep_credential))
        OnboardingField(name, { name = it }, R.string.label_name, busy)
        OnboardingField(base, { base = it }, R.string.label_base_url, busy, KeyboardType.Uri)
        OnboardingField(website, { website = it }, R.string.onboarding_website, busy, KeyboardType.Uri)
        EnumChoice(R.string.advanced_adapter, adapter, AdapterType.values().toList(), !busy) { adapter = it }
        EnumChoice(R.string.onboarding_auth, mode, AuthMode.values().toList(), !busy) {
            clearSecrets()
            authFields.forEach { field -> field.confirmed = false }
            mode = it
            anonymousConfirmed = false
            if (it == AuthMode.NONE) confirmAnonymous = true
            if (it in setOf(AuthMode.API_KEY, AuthMode.CUSTOM_HEADER) && authFields.isEmpty()) authFields += AuthDraft()
            if (it == AuthMode.PAT_ACCOUNT && accountCredentials.isEmpty()) accountCredentials += CredentialDraft()
        }
        if (mode == AuthMode.NONE) {
            Text(stringResource(if (anonymousConfirmed) R.string.advanced_anonymous_confirmed else R.string.advanced_anonymous_unconfirmed))
            if (!anonymousConfirmed) OutlinedButton(onClick = { confirmAnonymous = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.advanced_anonymous_confirm))
            }
        }
        if (mode in setOf(AuthMode.BEARER, AuthMode.PAT_ACCOUNT)) {
            Text(stringResource(R.string.advanced_inference_credentials), style = MaterialTheme.typography.titleMedium)
            CredentialRows(credentials, busy)
        }
        if (mode == AuthMode.PAT_ACCOUNT) {
            Text(stringResource(R.string.advanced_account_credentials), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.advanced_pat_boundary))
            CredentialRows(accountCredentials, busy)
        }
        if (mode in setOf(AuthMode.API_KEY, AuthMode.CUSTOM_HEADER)) {
            Text(stringResource(R.string.advanced_custom_auth), style = MaterialTheme.typography.titleMedium)
            authFields.forEach { field -> key(field) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnumChoice(R.string.advanced_location, field.location, CredentialLocation.values().toList(), !busy) { field.location = it; field.confirmed = false }
                        OnboardingField(field.name, { field.name = it; field.confirmed = false }, R.string.advanced_field_name, busy)
                        SecretField(field.value, { field.value = it }, busy)
                        if (mode == AuthMode.CUSTOM_HEADER && field.location == CredentialLocation.HEADER && field.name.equals("Authorization", true)) {
                            ToggleRow(R.string.advanced_authorization_confirm, field.confirmed, !busy) { field.confirmed = it }
                        }
                        TextButton(onClick = { field.value = ""; authFields.remove(field) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.advanced_remove))
                        }
                    }
                }
            } }
            if (mode == AuthMode.CUSTOM_HEADER || authFields.isEmpty()) OutlinedButton(onClick = { authFields += AuthDraft() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.advanced_add_auth))
            }
        }
        Text(stringResource(R.string.advanced_endpoints), style = MaterialTheme.typography.titleMedium)
        endpoints.forEach { endpoint -> key(endpoint) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnumChoice(R.string.advanced_endpoint_type, endpoint.type, EndpointType.values().toList(), !busy) { endpoint.type = it }
                    OnboardingField(endpoint.url, { endpoint.url = it }, R.string.advanced_endpoint_url, busy, KeyboardType.Uri)
                    Text(stringResource(R.string.advanced_protocol, endpoint.type.name))
                    ToggleRow(R.string.advanced_enabled, endpoint.enabled, !busy) { endpoint.enabled = it }
                    TextButton(onClick = { endpoints.remove(endpoint) }, enabled = !busy && endpoints.size > 1, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.advanced_remove)) }
                }
            }
        } }
        OutlinedButton(onClick = { endpoints += EndpointDraft() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.advanced_add_endpoint)) }
        OnboardingField(group, { group = it }, R.string.advanced_group, busy)
        Text(stringResource(R.string.advanced_scope_note), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            models, { models = it }, label = { Text(stringResource(R.string.advanced_models)) },
            supportingText = { Text(stringResource(R.string.advanced_manual_note)) },
            modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2,
        )
        Text(stringResource(R.string.advanced_manual_pricing), style = MaterialTheme.typography.titleMedium)
        prices.forEach { price -> key(price) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OnboardingField(price.model, { price.model = it }, R.string.label_model, busy)
                    OnboardingField(price.group, { price.group = it }, R.string.advanced_group, busy)
                    EnumChoice(R.string.advanced_endpoint_type, price.endpoint, EndpointType.values().toList(), !busy) { price.endpoint = it }
                    OnboardingField(price.operation, { price.operation = it }, R.string.advanced_operation, busy)
                    OnboardingField(price.amount, { price.amount = it }, R.string.advanced_amount, busy, KeyboardType.Decimal)
                    OnboardingField(price.currency, { price.currency = it }, R.string.advanced_currency, busy)
                    EnumChoice(R.string.advanced_price_unit, price.unit, PricePerUnit.values().filter { it != PricePerUnit.UNKNOWN }, !busy) { price.unit = it }
                    OnboardingField(price.effectiveFrom, { price.effectiveFrom = it }, R.string.advanced_effective_from, busy)
                    OnboardingField(price.note, { price.note = it }, R.string.advanced_basis, busy)
                    Text(stringResource(R.string.advanced_low_confidence))
                    TextButton(onClick = { prices.remove(price) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.advanced_remove)) }
                }
            }
        } }
        OutlinedButton(onClick = { prices += PriceDraft() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.advanced_add_price)) }
        EnumChoice(R.string.label_quota, quota, QuotaState.values().toList(), !busy) { quota = it }
        OnboardingField(quotaNote, { quotaNote = it }, R.string.advanced_quota_basis, busy)
        ToggleRow(R.string.advanced_classification, classify, !busy) { classify = it }
        if (classify) {
            EnumChoice(R.string.advanced_category, category, ModelCategory.values().toList(), !busy) { category = it }
            MultiChoice(R.string.advanced_capabilities, Capability.values().toList(), capabilities, !busy) { capabilities = it }
            MultiChoice(R.string.advanced_modalities, Modality.values().toList(), modalities, !busy) { modalities = it }
            MultiChoice(R.string.advanced_endpoint_type, EndpointType.values().toList(), types, !busy) { types = it }
            Text(stringResource(R.string.advanced_low_confidence))
        }
        OnboardingField(checkin, { checkin = it }, R.string.label_checkin_url, busy, KeyboardType.Uri)
        OnboardingField(tags, { tags = it }, R.string.advanced_tags, busy)
        OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.advanced_notes)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(
            enabled = !busy && name.isNotBlank() && base.isNotBlank() && models.isNotBlank() && (mode != AuthMode.NONE || anonymousConfirmed),
            onClick = {
                val refs = if (mode in setOf(AuthMode.BEARER, AuthMode.PAT_ACCOUNT)) credentials.map {
                    AdvancedCredentialInput(it.label, it.secret, CredentialRole.INFERENCE, it.account.ifBlank { null })
                } else emptyList()
                val pats = if (mode == AuthMode.PAT_ACCOUNT) accountCredentials.map {
                    AdvancedCredentialInput(it.label, it.secret, CredentialRole.ACCOUNT, it.account.ifBlank { null })
                } else emptyList()
                val fields = if (mode in setOf(AuthMode.API_KEY, AuthMode.CUSTOM_HEADER)) authFields.map {
                    AdvancedAuthInput(it.location, it.name, it.value, it.confirmed)
                } else emptyList()
                // 交棒前先清空表單；callback 即使拋出例外也不留下原始值。
                clearSecrets()
                onReview(
                    OnboardingSnapshot(
                        workflowId = "", flowType = OnboardingFlowType.ADVANCED,
                        providerName = name.trim(), baseUrl = base.trim(), websiteUrl = website.trim().ifBlank { null },
                        adapterTypeOverride = adapter.takeIf { it != AdapterType.UNKNOWN },
                        authModeOverride = mode, anonymousConfirmed = anonymousConfirmed,
                        endpointProfiles = endpoints.map { EndpointRef(it.type, it.url, it.type.name, it.enabled) },
                        manualModelIds = models.lines(), group = group,
                        manualPricing = prices.map {
                            ManualPricingInput(it.model.ifBlank { null }, it.group.ifBlank { null }, it.endpoint,
                                it.operation, it.amount, it.currency, it.unit, it.effectiveFrom.ifBlank { null }, note = it.note)
                        },
                        manualQuotaState = quota, manualQuotaNote = quotaNote,
                        manualClassification = if (classify) ManualClassificationInput(category, capabilities, modalities, types) else null,
                        notes = notes.ifBlank { null }, checkinUrl = checkin.trim().ifBlank { null }, tags = tags.split(','),
                    ), refs + pats, fields,
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.advanced_prepare_review)) }
        TextButton(onClick = { clearSecrets(); onClose() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) }
    }
    if (confirmAnonymous) AlertDialog(
        onDismissRequest = { confirmAnonymous = false },
        title = { Text(stringResource(R.string.advanced_anonymous_confirm)) },
        text = { Text(stringResource(R.string.advanced_anonymous_body)) },
        confirmButton = { TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { anonymousConfirmed = true; confirmAnonymous = false }) { Text(stringResource(R.string.advanced_anonymous_confirm)) } },
        dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { confirmAnonymous = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CredentialRows(rows: MutableList<CredentialDraft>, busy: Boolean) {
    rows.forEach { row -> key(row) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OnboardingField(row.label, { row.label = it }, R.string.advanced_credential_label, busy)
                OnboardingField(row.account, { row.account = it }, R.string.advanced_account, busy)
                SecretField(row.secret, { row.secret = it }, busy)
                TextButton(onClick = { row.secret = ""; rows.remove(row) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.advanced_remove)) }
            }
        }
    } }
    OutlinedButton(onClick = { rows += CredentialDraft() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.advanced_add_credential)) }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, busy: Boolean) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(R.string.advanced_secret)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}

@Composable
internal fun <T : Enum<T>> EnumChoice(label: Int, selected: T, values: List<T>, enabled: Boolean, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(selected.name) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), onClick = { onChange(item); expanded = false }) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(stringResource(label), Modifier.weight(1f).padding(top = 12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T : Enum<T>> MultiChoice(label: Int, values: List<T>, selected: Set<T>, enabled: Boolean, onChange: (Set<T>) -> Unit) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected = value in selected, onClick = { onChange(if (value in selected) selected - value else selected + value) },
                label = { Text(value.name) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}
