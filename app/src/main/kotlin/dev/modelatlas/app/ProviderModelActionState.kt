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
    val httpStatus: Int? = null,
) {
    val busy: Boolean get() = status == "RUNNING"
}

/** Only safe metadata and finalized single-test states are exposed to Compose. */
data class ProviderModelBatchState(
    val providerId: String? = null,
    val connectionId: String? = null,
    val group: String? = null,
    val models: List<String> = emptyList(),
    val results: List<ProviderModelActionState> = emptyList(),
    val running: Boolean = false,
    val status: String = "IDLE",
) {
    companion object {
        fun validModels(models: List<String>): Boolean = models.size in 1..100 &&
            models.distinct().size == models.size && models.all { model ->
                model.isNotBlank() && model == model.trim() && model.length <= 512 &&
                    model.none { it.isISOControl() }
            }
    }
}
