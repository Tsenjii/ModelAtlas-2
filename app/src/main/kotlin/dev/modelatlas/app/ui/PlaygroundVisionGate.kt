package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.Capability

/**
 * Scope-exact gate shared by Composer and ViewModel; never guess Vision from model name.
 * Rows originate from stored classification and selected-key usability projections.
 */
internal fun selectedVisionCapabilities(
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
        Capability.VISION in row.capabilities &&
        operation == "CHAT_COMPLETIONS"
}?.capabilities.orEmpty()
