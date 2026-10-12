package dev.modelatlas.app.data

/** Local evidence key, safe refs only; length framing avoids ambiguous concatenation. */
internal fun manualVerificationScopeId(sampleId: String, credentialId: String): String =
    "manual-scope:${sampleId.length}:$sampleId:${credentialId.length}:$credentialId"

/** Separate from the recorder's requestId so its one-sample evidence lookup stays unchanged. */
internal fun manualVerificationScopeRequest(sampleId: String): String = "manual-scope:$sampleId"

/** Room read projection of positively verified custom-only models for one selected anchor. */
data class ScopedVerifiedModel(val endpointId: String, val providerModelId: String)
