package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.Capability

/**
 * Selected offering gate shared by Compose and ViewModel.
 * The exact provider/model/endpoint/operation/group and selected-key projection
 * must match; classification from other credentials or groups must not lend media.
 */
internal fun selectedChatCapabilities(
    rows: List<ModelRow>,
    providerId: String,
    providerModelId: String,
    endpointId: String,
    operation: String,
    selectedGroup: String?,
): Set<Capability> = rows.firstOrNull { row ->
    row.providerId == providerId &&
        row.providerModelId == providerModelId &&
        row.endpointId == endpointId &&
        row.operation == operation &&
        row.group == selectedGroup &&
        row.selectedKeyUsable &&
        operation == "CHAT_COMPLETIONS"
}?.capabilities.orEmpty()

/** Compatibility: unknown/unverified Vision remains strictly unavailable. */
internal fun selectedVisionCapabilities(
    rows: List<ModelRow>,
    providerId: String,
    providerModelId: String,
    endpointId: String,
    operation: String,
    selectedGroup: String?,
): Set<Capability> = selectedChatCapabilities(
    rows, providerId, providerModelId, endpointId, operation, selectedGroup,
).takeIf { Capability.VISION in it }.orEmpty()
