package dev.modelatlas.app.data

/**
 * MODEL-008 cross-provider multi-key compare selections (in-memory DTO only, no schema).
 *
 * TRACEABILITY: MODEL-008 (PRODUCT_SPEC §8 Compare Live, §6 selected-context, §10 FREE/pricing scope).
 * - [CompareKeySelection] is an explicit per-provider key choice: no first-key / foreign fallback.
 *   `credentialId == null` means anonymous and is only valid for a real confirmed NONE provider
 *   (committed `provider_configurations.authMode='NONE'` + `anonymousConfirmed` + matching account).
 * - [CompareKeyChoice] is a displayable choice: safe refs only (provider/account/credential ids +
 *   labels), never raw keys / PAT.
 * - In-memory only: no Room mutation, no row mutation, no secret values.
 * - Missing data => UNKNOWN downstream; sync failure must not wipe last-good (we never write).
 */
data class CompareKeySelection(
    val providerId: String,
    val accountId: String,
    val credentialId: String?,
    val group: String? = null,
)

/**
 * Displayable key choice for the compare picker.
 *
 * - [selection] is the exact scope to use for the request (provider/account/credential/group).
 * - [providerName] is the provider display name (fallback to providerId when unknown; never a secret).
 * - [credentialLabel] is the credential label (safe metadata); empty/anonymous label for null credential.
 */
data class CompareKeyChoice(
    val selection: CompareKeySelection,
    val providerName: String,
    val credentialLabel: String,
)
