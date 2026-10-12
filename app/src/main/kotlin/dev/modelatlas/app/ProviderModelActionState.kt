package dev.modelatlas.app

/** PROV-001 / MODEL-006: safe, scoped feedback for one explicit provider model action. */
data class ProviderModelActionState(
    val providerId: String? = null,
    val modelId: String? = null,
    val connectionId: String? = null,
    val group: String? = null,
    val kind: String? = null,
    val status: String = "IDLE",
    val message: String? = null,
    val clearToken: Int = 0,
    val refreshToken: Int = 0,
) {
    val busy: Boolean get() = status == "RUNNING"
}
