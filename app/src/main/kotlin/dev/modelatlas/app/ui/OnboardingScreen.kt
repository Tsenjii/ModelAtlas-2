package dev.modelatlas.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.core.onboarding.*

/** 欄位含機密：整個表單只使用 remember，永不寫入 Bundle。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddForm(busy: Boolean, onStart: (String, String, String, String) -> Unit, onClose: () -> Unit, initial: OnboardingSnapshot? = null) = AtlasTheme {
    var base by remember(initial?.workflowId) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var website by remember(initial?.workflowId) { mutableStateOf(initial?.websiteUrl.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { secret = "" } }
    var model by remember(initial?.workflowId) { mutableStateOf(initial?.suggestedModelIds?.firstOrNull().orEmpty()) }
    BackHandler { secret = ""; onClose() }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AtlasSpace.lg),
                verticalArrangement = Arrangement.spacedBy(AtlasSpace.xl),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    AtlasBadge(stringResource(R.string.onboarding_badge_step1), AtlasTone.ACCENT)
                    Text(stringResource(R.string.onboarding_quick), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.onboarding_quick_description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AtlasCard {
                    AtlasSectionHeading(stringResource(R.string.onboarding_heading_connect), stringResource(R.string.onboarding_connect_desc))
                    OnboardingField(base, { base = it }, R.string.label_base_url, busy, KeyboardType.Uri)
                    OnboardingField(website, { website = it }, R.string.onboarding_website, busy, KeyboardType.Uri)
                }
                AtlasCard {
                    AtlasSectionHeading(stringResource(R.string.onboarding_heading_access), stringResource(R.string.onboarding_access_desc))
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.onboarding_optional_key)) },
                        supportingText = { Text(stringResource(R.string.helper_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = AtlasRadius.field,
                    )
                    OnboardingField(model, { model = it }, R.string.onboarding_optional_model, busy)
                }
                Text(stringResource(R.string.onboarding_probe_cost), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().padding(AtlasSpace.lg), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    DetailActionGroup {
                        TextButton(onClick = { secret = ""; onClose() }, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action)) {
                            Text(stringResource(R.string.action_close))
                        }
                        Button(
                            onClick = { val submitted = secret; secret = ""; onStart(base, website, submitted, model) },
                            enabled = !busy && base.isNotBlank(),
                            modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action),
                        ) { Text(stringResource(R.string.onboarding_start)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OnboardingField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    busy: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) = AtlasTheme {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = AtlasRadius.field,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingProgressScreen(
    snapshot: OnboardingSnapshot,
    busy: Boolean,
    onCancel: () -> Unit,
    onRetry: (OnboardingStepId) -> Unit,
    onSkip: (OnboardingStepId) -> Unit,
    onCommit: () -> Unit,
    onAbandon: () -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onUpdateReview: (String, List<String>, String) -> Unit,
) = AtlasTheme {
    var confirmAbandon by remember { mutableStateOf(false) }
    var confirmProbe by remember(snapshot.workflowId) { mutableStateOf<OnboardingStepId?>(null) }
    var selectionDirty by remember(snapshot.workflowId) { mutableStateOf(false) }
    val blocked = snapshot.status == OnboardingStatus.BLOCKED
    val ended = snapshot.status in setOf(OnboardingStatus.COMMITTED, OnboardingStatus.ABANDONED)
    val hasFooter = busy || (snapshot.reviewItems != null && !blocked && !ended)
    BackHandler { if (busy) onCancel() else onClose() }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding().then(if (hasFooter) Modifier else Modifier.navigationBarsPadding())) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AtlasSpace.lg),
                verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    AtlasBadge(stringResource(R.string.onboarding_badge_step2), AtlasTone.ACCENT)
                    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    DetailIdentityText(snapshot.providerName ?: stringResource(R.string.onboarding_draft), stringResource(R.string.label_name))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AtlasBadge(
                        stringResource(R.string.onboarding_status, snapshot.status.name),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                // 次要操作可換行；取消與保存固定於底部拇指區。
                DetailActionGroup {
                    if (!busy && !ended) OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.touch)) {
                        Text(stringResource(R.string.onboarding_edit))
                    }
                    if (!busy && !ended) TextButton(onClick = { confirmAbandon = true }, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.touch)) {
                        Text(stringResource(R.string.onboarding_abandon))
                    }
                    if (!blocked) TextButton(onClick = { if (busy) onCancel(); onClose() }, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.touch)) {
                        Text(stringResource(R.string.onboarding_close_resume))
                    }
                }
                OnboardingStepId.ORDERED.forEach { step ->
                    val status = snapshot.stepStatuses[step] ?: OnboardingStepStatus.PENDING
                    val result = snapshot.results.lastOrNull { it.step == step }
                    AtlasCard(subdued = status == OnboardingStepStatus.PENDING) {
                        AtlasSectionHeading(stringResource(R.string.onboarding_step, step.ordinal + 1, step.wireName))
                        AtlasBadge(status.name, when (status) {
                            OnboardingStepStatus.SUCCESS -> AtlasTone.ACCENT
                            OnboardingStepStatus.FAILED -> AtlasTone.ERROR
                            OnboardingStepStatus.PARTIAL, OnboardingStepStatus.NEEDS_INPUT, OnboardingStepStatus.STALE -> AtlasTone.CAUTION
                            else -> AtlasTone.NEUTRAL
                        })
                        result?.reason?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (!busy && !ended && !blocked && step.ordinal < OnboardingStepId.REVIEW.ordinal &&
                            (status in setOf(OnboardingStepStatus.FAILED, OnboardingStepStatus.NEEDS_INPUT, OnboardingStepStatus.STALE) ||
                                result?.reason in setOf(OnboardingFailureReason.CANCELLED, OnboardingFailureReason.INTERRUPTED) ||
                                (snapshot.flowType == OnboardingFlowType.ADVANCED && status == OnboardingStepStatus.SKIPPED))
                        ) {
                            DetailActionGroup {
                                TextButton(onClick = {
                                    if (snapshot.flowType == OnboardingFlowType.ADVANCED && step == OnboardingStepId.TEST_MODEL) confirmProbe = step
                                    else onRetry(step)
                                }, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.touch)) {
                                    Text(stringResource(R.string.onboarding_retry))
                                }
                                // 網路/auth 關卡必須修正；只允許略過非關鍵探測。
                                if (step in setOf(OnboardingStepId.LIST_MODELS, OnboardingStepId.TEST_MODEL, OnboardingStepId.PRICING_QUOTA_NOTICE)) {
                                    TextButton(onClick = { onSkip(step) }, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.touch)) {
                                        Text(stringResource(R.string.onboarding_skip))
                                    }
                                }
                            }
                        }
                    }
                }
                snapshot.reviewItems?.let { review ->
                    OnboardingReview(snapshot, review)
                    if (!blocked && !ended) ReviewSelection(snapshot, busy, onUpdateReview) { selectionDirty = it }
                }
            }
            if (hasFooter) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(AtlasSpace.lg), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                        if (selectionDirty) Text(stringResource(R.string.onboarding_apply_before_commit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DetailActionGroup {
                            if (busy) OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action)) {
                                Text(stringResource(R.string.onboarding_cancel_probe))
                            }
                            if (snapshot.reviewItems != null && !blocked && !ended) {
                                Button(
                                    onClick = onCommit,
                                    enabled = canSubmitReview(snapshot, busy, selectionDirty),
                                    modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action),
                                ) { Text(stringResource(R.string.onboarding_commit)) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmAbandon) AlertDialog(
        onDismissRequest = { confirmAbandon = false },
        title = { Text(stringResource(R.string.onboarding_abandon)) },
        text = { Text(stringResource(R.string.onboarding_abandon_body)) },
        confirmButton = {
            TextButton(onClick = { confirmAbandon = false; onAbandon() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_abandon)) }
        },
        dismissButton = {
            TextButton(onClick = { confirmAbandon = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    confirmProbe?.let { step ->
        AlertDialog(
            onDismissRequest = { confirmProbe = null },
            title = { Text(stringResource(R.string.onboarding_confirm_test)) },
            text = { Text(stringResource(R.string.onboarding_confirm_test_body, ProbeBudgetLimits.MAX_TEST_MODELS)) },
            confirmButton = {
                TextButton(onClick = { confirmProbe = null; onRetry(step) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onboarding_confirm_test))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmProbe = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ReviewSelection(snapshot: OnboardingSnapshot, busy: Boolean, onSave: (String, List<String>, String) -> Unit, onDirty: (Boolean) -> Unit) {
    val candidates = (snapshot.manualModelIds + snapshot.catalog?.models.orEmpty().map { it.modelId } + snapshot.suggestedModelIds).distinct()
    var name by remember(snapshot.workflowId, snapshot.providerName) { mutableStateOf(snapshot.providerName.orEmpty()) }
    var selected by remember(snapshot.workflowId, snapshot.selectedModelIds, candidates) {
        mutableStateOf((snapshot.selectedModelIds ?: candidates).toSet())
    }
    var manual by remember(snapshot.workflowId) { mutableStateOf("") }
    val dirty = name != snapshot.providerName.orEmpty() || selected != (snapshot.selectedModelIds ?: candidates).toSet() || manual.isNotBlank()
    LaunchedEffect(dirty) { onDirty(dirty) }
    AtlasCard {
        AtlasSectionHeading(stringResource(R.string.onboarding_selection))
        OnboardingField(name, { name = it }, R.string.label_name, busy)
        if (candidates.isEmpty()) {
            DetailEmptyState(
                stringResource(R.string.models_empty),
                stringResource(R.string.onboarding_no_candidates_hint),
                Icons.Filled.Info,
            )
        }
        candidates.forEach { model ->
            var showModel by remember(snapshot.workflowId, model) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Checkbox 與整個文字標籤共用選取區；詳情是相鄰的獨立操作。
                Row(
                    Modifier.weight(1f).heightIn(min = AtlasSpace.action)
                        .toggleable(
                            value = model in selected, enabled = !busy, role = Role.Checkbox,
                            onValueChange = { selected = if (it) selected + model else selected - model },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = model in selected, enabled = !busy, onCheckedChange = null)
                    Text(
                        model, Modifier.weight(1f).padding(vertical = AtlasSpace.sm),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { showModel = true }, modifier = Modifier.size(AtlasSpace.touch)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.provider_detail_open) + " · " + model)
                }
            }
            if (showModel) DetailValueDialog(model, stringResource(R.string.label_model)) { showModel = false }
        }
        OnboardingField(manual, { manual = it }, R.string.onboarding_manual_fallback, busy)
        Text(stringResource(R.string.advanced_manual_note), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onSave(name, (selected + listOfNotNull(manual.trim().takeIf { it.isNotEmpty() })).toList(), manual); manual = "" },
            enabled = !busy && (selected.isNotEmpty() || manual.isNotBlank()),
            modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch),
        ) { Text(stringResource(R.string.onboarding_save_selection)) }
        OnboardingReviewValue(
            stringResource(R.string.onboarding_selection_saved, snapshot.selectedModelIds?.joinToString() ?: stringResource(R.string.onboarding_all_candidates)),
            stringResource(R.string.onboarding_selection),
        )
        if (dirty) AtlasBadge(stringResource(R.string.onboarding_apply_before_commit), AtlasTone.CAUTION)
    }
}

@Composable
private fun OnboardingReview(snapshot: OnboardingSnapshot, review: ReviewItems) {
    val unknown = stringResource(R.string.value_unknown)
    Text(stringResource(R.string.onboarding_review), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    if (review.unknownFields.isNotEmpty() || snapshot.stepStatuses.values.any { it in setOf(OnboardingStepStatus.FAILED, OnboardingStepStatus.PARTIAL, OnboardingStepStatus.SKIPPED) }) {
        Surface(shape = AtlasRadius.card, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(AtlasSpace.lg), verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
                Text(stringResource(R.string.onboarding_warnings), style = MaterialTheme.typography.titleSmall)
                if (canSubmitReview(snapshot, busy = false, dirty = false)) Text(stringResource(R.string.onboarding_ready_with_warnings))
                review.unknownFields.forEach { OnboardingReviewValue(it, stringResource(R.string.onboarding_unknown_fields)) }
                OnboardingStepId.ORDERED.forEach { step ->
                    if (snapshot.stepStatuses[step] in setOf(OnboardingStepStatus.FAILED, OnboardingStepStatus.PARTIAL, OnboardingStepStatus.SKIPPED)) {
                        Text(stringResource(R.string.onboarding_step_gap, step.wireName, snapshot.stepStatuses.getValue(step).name))
                    }
                }
            }
        }
    }
    ReviewCard(R.string.onboarding_type, known(review.providerType.name), review.providerTypeBasis ?: unknown)
    ReviewCard(
        R.string.onboarding_auth,
        review.auth.authMode.name,
        review.auth.credentialLabels.joinToString().ifEmpty { unknown },
        review.auth.credentialTestStatus.name,
        review.auth.failureReason?.name ?: stringResource(R.string.onboarding_no_failure),
        review.auth.customAuthFieldNames.joinToString().ifEmpty { stringResource(R.string.onboarding_no_custom_fields) },
    )
    val found = if (snapshot.catalog?.supported == false) stringResource(R.string.onboarding_catalog_unsupported)
        else review.modelsFound?.toString() ?: unknown
    ReviewCard(
        R.string.onboarding_models,
        stringResource(R.string.onboarding_found, found),
        stringResource(R.string.onboarding_verified, review.modelsVerified?.toString() ?: unknown, review.modelsTestedTotal?.toString() ?: unknown),
        review.modelsVerifiedIds.joinToString("\n").ifEmpty { stringResource(R.string.onboarding_no_verified_ids) },
    )
    if (snapshot.catalog?.truncated == true) Text(stringResource(R.string.onboarding_catalog_truncated))
    ReviewCard(R.string.label_price, known(review.pricingStatus.name))
    if (review.pricingStatus == PriceStatus.UNKNOWN) Text(stringResource(R.string.onboarding_price_unknown_basis))
    ReviewCard(R.string.label_quota, known(review.quotaStatus.name))
    if (review.quotaStatus == dev.modelatlas.core.domain.QuotaState.UNKNOWN) Text(stringResource(R.string.onboarding_quota_unknown_basis))
    ReviewCard(R.string.label_official_health, known(review.officialHealth.name))
    ReviewCard(R.string.label_observed_health, known(review.observedHealth.name))
    ReviewCard(R.string.onboarding_unknown_fields, review.unknownFields.joinToString("\n").ifEmpty { stringResource(R.string.onboarding_no_gaps) })
    AtlasSectionHeading(stringResource(R.string.onboarding_evidence))
    ReviewItem.values().forEach { item ->
        val evidence = reviewEvidence(snapshot, item)
        ReviewCard(reviewItemLabel(item), confidenceText(reviewConfidence(snapshot, review, item)),
            evidence.joinToString("\n").ifEmpty { unknown })
    }
    ReviewCard(
        R.string.onboarding_cost,
        stringResource(R.string.onboarding_request_count, review.requestsSpent),
        stringResource(if (review.rateLimited) R.string.onboarding_rate_limited else R.string.onboarding_not_rate_limited),
    )
    ReviewCard(R.string.label_checkin_url, review.checkinUrl ?: unknown,
        stringResource(if (review.requiresDailyCheckin) R.string.onboarding_checkin_required else R.string.onboarding_checkin_not_required))
    if (snapshot.flowType == OnboardingFlowType.ADVANCED) {
        ReviewCard(R.string.advanced_adapter,
            stringResource(R.string.advanced_override_comparison,
                snapshot.detection?.adapterType?.name ?: unknown,
                snapshot.adapterTypeOverride?.name ?: unknown))
        ReviewCard(R.string.advanced_group, snapshot.group ?: unknown, stringResource(R.string.advanced_scope_note))
        ReviewCard(R.string.advanced_custom_auth,
            snapshot.customAuthFields.joinToString("\n") { "${it.location.name} · ${it.name}" }.ifEmpty { unknown })
        ReviewCard(R.string.advanced_endpoints,
            snapshot.endpointProfiles.joinToString("\n") { "${it.endpointType.name} · ${it.protocol} · ${it.url}" })
        ReviewCard(R.string.advanced_models, snapshot.manualModelIds.joinToString("\n"), stringResource(R.string.advanced_manual_note))
        ReviewCard(R.string.advanced_manual_pricing,
            snapshot.manualPricing.joinToString("\n") {
                "${it.modelId ?: unknown} · ${it.group ?: unknown} · ${it.endpointType?.name ?: unknown} · ${it.operation ?: unknown}\n" +
                    "${it.decimalAmount} ${it.currency} / ${it.perUnit.name}\n${it.effectiveFrom ?: unknown} · ${it.note.orEmpty()}"
            }.ifEmpty { unknown }, stringResource(R.string.advanced_low_confidence))
        ReviewCard(R.string.advanced_quota_basis, snapshot.manualQuotaNote?.takeIf { it.isNotBlank() } ?: unknown)
        ReviewCard(R.string.advanced_classification,
            snapshot.manualClassification?.let {
                "${it.primaryCategory.name}\n${it.capabilities.joinToString()}\n${it.modalities.joinToString()}\n${it.endpointTypes.joinToString()}"
            } ?: unknown)
        ReviewCard(R.string.advanced_notes, snapshot.notes ?: unknown)
        ReviewCard(R.string.advanced_tags, snapshot.tags.joinToString().ifEmpty { unknown })
    }
}

@Composable
private fun known(value: String): String = if (value == "UNKNOWN") stringResource(R.string.value_unknown) else value

@Composable
private fun ReviewCard(title: Int, vararg values: String) {
    val label = stringResource(title)
    AtlasCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
            AtlasSectionHeading(label)
            values.forEach {
                if (it.contains(stringResource(R.string.value_unknown)) || it == confidenceText(EvidenceConfidence.LOW) || it == confidenceText(EvidenceConfidence.UNKNOWN)) {
                    Surface(shape = AtlasRadius.field, color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                        OnboardingReviewValue(it, label, Modifier.padding(AtlasSpace.md))
                    }
                } else OnboardingReviewValue(it, label)
            }
        }
    }
}

/** 依實際排版判斷溢位；短事實保留純文字，長值可開啟完整可選取內容。 */
@Composable
private fun OnboardingReviewValue(value: String, label: String, modifier: Modifier = Modifier) {
    var overflowing by remember(value) { mutableStateOf(false) }
    var showValue by remember(value) { mutableStateOf(false) }
    val detailsLabel = stringResource(R.string.provider_detail_open)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflowing = it.hasVisualOverflow },
        )
        if (overflowing) {
            TextButton(
                onClick = { showValue = true },
                modifier = Modifier.heightIn(min = AtlasSpace.touch)
                    .semantics { contentDescription = "$label · $detailsLabel" },
            ) { Text(detailsLabel) }
        }
    }
    if (showValue) DetailValueDialog(value, label) { showValue = false }
}

internal fun canSubmitReview(snapshot: OnboardingSnapshot, busy: Boolean, dirty: Boolean): Boolean {
    if (busy || dirty || snapshot.status in setOf(OnboardingStatus.BLOCKED, OnboardingStatus.COMMITTED, OnboardingStatus.ABANDONED)) return false
    val review = snapshot.reviewItems ?: return false
    if (snapshot.authDecision?.missingCredential == true || review.auth.failureReason in setOf(OnboardingFailureReason.AUTH_FAILED, OnboardingFailureReason.AUTH_MISSING_CREDENTIAL)) return false
    if (snapshot.stepStatuses[OnboardingStepId.INSPECT] == OnboardingStepStatus.FAILED) return false
    if (snapshot.authModeOverride == AuthMode.NONE && !snapshot.anonymousConfirmed) return false
    val reviewStatus = snapshot.stepStatuses[OnboardingStepId.REVIEW]
    val awaitingApproval = reviewStatus == OnboardingStepStatus.NEEDS_INPUT &&
        snapshot.results.lastOrNull { it.step == OnboardingStepId.REVIEW }?.reason == OnboardingFailureReason.REVIEW_NOT_APPROVED
    if (!awaitingApproval && reviewStatus !in setOf(OnboardingStepStatus.SUCCESS, OnboardingStepStatus.PARTIAL, OnboardingStepStatus.SKIPPED)) return false
    // 跨 module public property 不可 smart cast：先取本地 val。
    val selected = snapshot.selectedModelIds
    return (snapshot.manualModelIds + snapshot.catalog?.models.orEmpty().map { it.modelId }).any {
        it.isNotBlank() && (selected == null || it in selected)
    }
}

internal fun reviewEvidence(snapshot: OnboardingSnapshot, item: ReviewItem): List<String> {
    val step = when (item) {
        ReviewItem.PROVIDER_TYPE -> OnboardingStepId.DETECT_TYPE
        ReviewItem.AUTH -> OnboardingStepId.CREDENTIAL_TEST
        ReviewItem.MODELS_FOUND -> OnboardingStepId.LIST_MODELS
        ReviewItem.MODELS_VERIFIED -> OnboardingStepId.TEST_MODEL
        else -> OnboardingStepId.PRICING_QUOTA_NOTICE
    }
    if (snapshot.stepStatuses[step] in setOf(OnboardingStepStatus.STALE, OnboardingStepStatus.SUPERSEDED)) return emptyList()
    return snapshot.results.lastOrNull { it.step == step }
        ?.takeUnless { it.status in setOf(OnboardingStepStatus.SUPERSEDED, OnboardingStepStatus.STALE) }?.evidenceIds.orEmpty()
}

internal fun reviewConfidence(snapshot: OnboardingSnapshot, review: ReviewItems, item: ReviewItem): EvidenceConfidence {
    if (item == ReviewItem.PROVIDER_TYPE && snapshot.adapterTypeOverride != null) return EvidenceConfidence.LOW
    if (item == ReviewItem.QUOTA && snapshot.pricingQuota == null && snapshot.manualQuotaState != null &&
        snapshot.manualQuotaState != dev.modelatlas.core.domain.QuotaState.UNKNOWN) return EvidenceConfidence.LOW
    return review.confidences[item] ?: EvidenceConfidence.UNKNOWN
}

private fun reviewItemLabel(item: ReviewItem): Int = when (item) {
    ReviewItem.PROVIDER_TYPE -> R.string.onboarding_type
    ReviewItem.AUTH -> R.string.onboarding_auth
    ReviewItem.MODELS_FOUND -> R.string.onboarding_found_evidence
    ReviewItem.MODELS_VERIFIED -> R.string.onboarding_verified_evidence
    ReviewItem.PRICING -> R.string.label_price
    ReviewItem.QUOTA -> R.string.label_quota
    ReviewItem.HEALTH_OFFICIAL -> R.string.label_official_health
    ReviewItem.HEALTH_OBSERVED -> R.string.label_observed_health
}

@Composable
private fun confidenceText(confidence: EvidenceConfidence): String = when (confidence) {
    EvidenceConfidence.LOW -> stringResource(R.string.onboarding_confidence_low)
    EvidenceConfidence.UNKNOWN -> stringResource(R.string.onboarding_confidence_unknown)
    else -> confidence.name
}
