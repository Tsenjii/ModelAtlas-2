package dev.modelatlas.app.data

import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceTarget

/**
 * Playground 出站 target：原 selected ConnectionRecord 是唯一 scope 真相。
 *
 * - Bearer ref 只傳選中的 inference credential；custom-only/anonymous 不借 Bearer。
 * - telemetryCredentialId 是同一 selected safe ref（custom-only 即使不送 Bearer 仍可歸因）。
 * - canonical/group/endpoint operation 不得退化成 null / CHAT_COMPLETIONS 預設值。
 * - 此 builder 只組裝安全 refs，不查 Vault、不持有 raw secret、不觸碰網路。
 */
internal fun buildPlaygroundInferenceTarget(
    record: ConnectionRecord,
    bearerCredentialId: String?,
    authFields: List<InferenceAuthField>,
    selectedGroup: String?,
): InferenceTarget {
    require(record.anonymous || record.credentialId.isNotBlank()) {
        "Selected credential reference missing"
    }
    require(
        bearerCredentialId == null ||
            (!record.anonymous && bearerCredentialId == record.credentialId)
    ) {
        "Bearer credential must match selected connection"
    }
    return InferenceTarget(
        providerId = record.providerId,
        accountId = record.accountId,
        credentialId = bearerCredentialId,
        telemetryCredentialId = if (record.anonymous) null else record.credentialId,
        endpointId = record.endpointId,
        endpointUrl = record.endpointUrl,
        modelId = record.modelId,
        canonicalModelId = record.canonicalModelId,
        group = selectedGroup,
        operation = record.protocol,
        authFields = authFields,
    )
}
