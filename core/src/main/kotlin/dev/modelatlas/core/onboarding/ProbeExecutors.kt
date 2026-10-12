package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.network.CatalogProbe
import dev.modelatlas.core.network.CatalogStatus
import dev.modelatlas.core.network.EndpointPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import dev.modelatlas.core.network.ProbeStatus
import dev.modelatlas.core.network.ProviderTypeFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList

/**
 * 真實探測執行器（PROVIDER_ONBOARDING_CONTRACT §1.2 / §4 / §5）。
 *
 * - 不做 Room / UI，只做 core 網路探測與分類。
 * - 所有回傳只含 safe refs / 分類 / 計數與安全訊息；不含 URL / key / server body。
 * - `requestsSpent` 永遠是實際發送數；未發送（INPUT_INVALID / 缺 credential / 預算不足）記 0。
 * - iO 與未知例外一律轉安全分類，不透出例外訊息。
 * - 取消（CancellationException）一律向上传遞，不轉分類。
 */
class ProbeStepExecutor(
    private val probe: CatalogProbe,
    private val engine: InferenceEngine,
    // Same convention as the transports: loopback http only for unit tests.
    private val allowLoopbackHttpForTests: Boolean = false,
    /**
     * `PRICING_QUOTA_NOTICE` 的能力探測來源（PROV-002）。
     * null＝未接線：沿用舊 placeholder（PARTIAL＋全 UNKNOWN＋0 發送），不猜測；
     * 非 null＝已知 adapter 走能力介面真嘗試，不支援／失敗才 Unknown。
     */
    private val noticeSource: PricingQuotaNoticeSource? = null,
) : StepExecutor {
    override suspend fun execute(
        snapshot: OnboardingSnapshot,
        step: OnboardingStepId,
        grant: RequestGrant,
    ): StepResult {
        if (grant.rateLimitStopped) {
            return StepResult(
                step = step,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.RATE_LIMITED_STOP,
                message = SAFE_STOPPED_BY_429,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        return when (step) {
            OnboardingStepId.INSPECT -> inspect(snapshot, grant)
            OnboardingStepId.DETECT_TYPE -> detectType(snapshot, grant)
            OnboardingStepId.AUTH -> auth(snapshot, grant)
            OnboardingStepId.CREDENTIAL_TEST -> credentialTest(snapshot, grant)
            OnboardingStepId.LIST_MODELS -> listModels(snapshot, grant)
            OnboardingStepId.TEST_MODEL -> testModel(snapshot, grant)
            OnboardingStepId.PRICING_QUOTA_NOTICE -> pricingQuotaNotice(snapshot, grant)
            OnboardingStepId.REVIEW, OnboardingStepId.COMMIT -> StepResult(
                step = step,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.NOT_APPLICABLE,
                message = SAFE_NOT_APPLICABLE,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun inspect(snapshot: OnboardingSnapshot, grant: RequestGrant): StepResult {
        val base = snapshot.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.INSPECT,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.INPUT_INVALID,
                message = SAFE_INPUT_INVALID,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (!grant.tryReserve(1)) {
            return StepResult(
                step = OnboardingStepId.INSPECT,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.BUDGET_EXHAUSTED,
                message = SAFE_BUDGET_EXHAUSTED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        try {
            val result = probe.inspect(base)
            if (result.status == ProbeStatus.INPUT_INVALID) {
                return StepResult(
                    step = OnboardingStepId.INSPECT,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    requestsSpent = 0,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.INPUT_INVALID,
                    message = SAFE_INPUT_INVALID,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            val evidence = result.typeEvidence?.let { listOf(it) } ?: emptyList()
            return when (result.status) {
                ProbeStatus.OK,
                ProbeStatus.HTTP_UNAUTHORIZED,
                ProbeStatus.HTTP_FORBIDDEN,
                ProbeStatus.HTTP_NOT_FOUND,
                ProbeStatus.HTTP_CLIENT_ERROR,
                ProbeStatus.HTTP_UNSUPPORTED,
                ProbeStatus.RESPONSE_TOO_LARGE,
                ProbeStatus.CATALOG_UNSUPPORTED,
                ProbeStatus.CATALOG_MALFORMED -> StepResult(
                    step = OnboardingStepId.INSPECT,
                    status = OnboardingStepStatus.SUCCESS,
                    attempt = grant.attempt,
                    evidenceIds = evidence,
                    confidence = if (result.status == ProbeStatus.OK) EvidenceConfidence.HIGH else EvidenceConfidence.MEDIUM,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                ProbeStatus.HTTP_TOO_MANY_REQUESTS -> StepResult(
                    step = OnboardingStepId.INSPECT,
                    status = OnboardingStepStatus.PARTIAL,
                    attempt = grant.attempt,
                    evidenceIds = evidence,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.RATE_LIMITED,
                    message = SAFE_RATE_LIMITED,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                ProbeStatus.HTTP_SERVER_ERROR -> failedInspect(grant, OnboardingFailureReason.SERVER_ERROR, evidence)
                ProbeStatus.NETWORK_UNREACHABLE -> failedInspect(grant, OnboardingFailureReason.NETWORK_UNREACHABLE, evidence)
                ProbeStatus.TIMEOUT -> failedInspect(grant, OnboardingFailureReason.TIMEOUT, evidence)
                ProbeStatus.TLS_FAILED -> failedInspect(grant, OnboardingFailureReason.TLS_FAILED, evidence)
                else -> failedInspect(grant, OnboardingFailureReason.UNKNOWN, evidence)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return StepResult(
                step = OnboardingStepId.INSPECT,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 1,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.UNKNOWN,
                message = SAFE_FAILURE,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun failedInspect(
        grant: RequestGrant,
        reason: OnboardingFailureReason,
        evidence: List<String>,
    ): StepResult = StepResult(
        step = OnboardingStepId.INSPECT,
        status = OnboardingStepStatus.FAILED,
        attempt = grant.attempt,
        evidenceIds = evidence,
        confidence = EvidenceConfidence.UNKNOWN,
        requestsSpent = 1,
        requestLimit = grant.requestLimit,
        reason = reason,
        message = safeMessageFor(reason),
        updatedAtMillis = System.currentTimeMillis(),
    )

    /**
     * 僅由 fingerprint 決定 adapter，否則 UNKNOWN 永不猜。
     * 主機 / 路徑 / 模型名稱文字不算證據；網路失敗亦回 SUCCESS+UNKNOWN 讓流程繼續。
     */
    private suspend fun detectType(snapshot: OnboardingSnapshot, grant: RequestGrant): StepResult {
        val base = snapshot.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.DETECT_TYPE,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.INPUT_INVALID,
                message = SAFE_INPUT_INVALID,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (!grant.tryReserve(1)) {
            return StepResult(
                step = OnboardingStepId.DETECT_TYPE,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.BUDGET_EXHAUSTED,
                message = SAFE_BUDGET_EXHAUSTED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        try {
            val result = probe.inspect(base)
            if (result.status == ProbeStatus.INPUT_INVALID) {
                return StepResult(
                    step = OnboardingStepId.DETECT_TYPE,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    requestsSpent = 0,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.INPUT_INVALID,
                    message = SAFE_INPUT_INVALID,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            if (result.status == ProbeStatus.HTTP_TOO_MANY_REQUESTS) {
                return StepResult(
                    step = OnboardingStepId.DETECT_TYPE,
                    status = OnboardingStepStatus.PARTIAL,
                    attempt = grant.attempt,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.RATE_LIMITED,
                    message = SAFE_RATE_LIMITED,
                    adapterType = AdapterType.UNKNOWN,
                    confidence = EvidenceConfidence.UNKNOWN,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            val adapter = when (result.providerType) {
                ProviderTypeFingerprint.OPENAI_COMPATIBLE -> AdapterType.OPENAI_COMPATIBLE
                ProviderTypeFingerprint.UNKNOWN -> AdapterType.UNKNOWN
            }
            val evidence = result.typeEvidence?.let { listOf(it) } ?: emptyList()
            // 明確 wire signal 才給 MEDIUM，否則 LOW；永不硬猜其他型別。
            val confidence = if (adapter == AdapterType.UNKNOWN) EvidenceConfidence.LOW else EvidenceConfidence.MEDIUM
            return StepResult(
                step = OnboardingStepId.DETECT_TYPE,
                status = OnboardingStepStatus.SUCCESS,
                attempt = grant.attempt,
                evidenceIds = evidence,
                confidence = confidence,
                requestsSpent = 1,
                requestLimit = grant.requestLimit,
                adapterType = adapter,
                updatedAtMillis = System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return StepResult(
                step = OnboardingStepId.DETECT_TYPE,
                status = OnboardingStepStatus.SUCCESS,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.LOW,
                requestsSpent = 1,
                requestLimit = grant.requestLimit,
                adapterType = AdapterType.UNKNOWN,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 依 snapshot 決定 mode（NONE / BEARER）。缺 credential 回 NEEDS_INPUT，不發請求。
     */
    private fun auth(snapshot: OnboardingSnapshot, grant: RequestGrant): StepResult {
        val mode = if (snapshot.authModeOverride == AuthMode.NONE) AuthMode.NONE else AuthMode.BEARER
        val ids = collectSafeIds(snapshot)
        if (mode != AuthMode.NONE && ids.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.AUTH,
                status = OnboardingStepStatus.NEEDS_INPUT,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.UNKNOWN,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                message = SAFE_NEEDS_CREDENTIAL,
                authMode = mode,
                safeCredentialIds = emptyList(),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        return StepResult(
            step = OnboardingStepId.AUTH,
            status = OnboardingStepStatus.SUCCESS,
            attempt = grant.attempt,
            confidence = EvidenceConfidence.MEDIUM,
            requestsSpent = 0,
            requestLimit = grant.requestLimit,
            authMode = mode,
            safeCredentialIds = ids,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun credentialTest(snapshot: OnboardingSnapshot, grant: RequestGrant): StepResult {
        val base = snapshot.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.CREDENTIAL_TEST,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.INPUT_INVALID,
                message = SAFE_INPUT_INVALID,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val credentialId = firstSafeId(snapshot)
        val requires = snapshot.authModeOverride != AuthMode.NONE
        if (requires && credentialId == null) {
            return StepResult(
                step = OnboardingStepId.CREDENTIAL_TEST,
                status = OnboardingStepStatus.NEEDS_INPUT,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                message = SAFE_NEEDS_CREDENTIAL,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (!grant.tryReserve(1)) {
            return StepResult(
                step = OnboardingStepId.CREDENTIAL_TEST,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.BUDGET_EXHAUSTED,
                message = SAFE_BUDGET_EXHAUSTED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        try {
            val result = probe.listModels(base, credentialId)
            if (result.status == CatalogStatus.CREDENTIAL_UNAVAILABLE) {
                return StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.NEEDS_INPUT,
                    attempt = grant.attempt,
                    requestsSpent = 0,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                    message = SAFE_NEEDS_CREDENTIAL,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            if (result.status == CatalogStatus.INPUT_INVALID) {
                return StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    requestsSpent = 0,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.INPUT_INVALID,
                    message = SAFE_INPUT_INVALID,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            val reachable = result.httpStatus != null
            val finding = CredentialTestFinding(
                httpStatus = result.httpStatus,
                reachable = reachable,
                anonymous = credentialId == null,
            )
            return when (result.status) {
                CatalogStatus.OK,
                CatalogStatus.CATALOG_UNSUPPORTED,
                CatalogStatus.CATALOG_MALFORMED,
                CatalogStatus.RESPONSE_TOO_LARGE -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.SUCCESS,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.HIGH,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    credentialTest = finding,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.HTTP_UNAUTHORIZED,
                CatalogStatus.HTTP_FORBIDDEN -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.AUTH_FAILED,
                    message = SAFE_AUTH_FAILED,
                    credentialTest = finding,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.HTTP_TOO_MANY_REQUESTS -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.PARTIAL,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.RATE_LIMITED,
                    message = SAFE_RATE_LIMITED,
                    credentialTest = finding,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.HTTP_NOT_FOUND -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.MODEL_NOT_FOUND,
                    message = SAFE_FAILURE,
                    credentialTest = finding,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.HTTP_CLIENT_ERROR,
                CatalogStatus.HTTP_UNSUPPORTED -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.PROTOCOL_MISMATCH,
                    message = SAFE_FAILURE,
                    credentialTest = finding,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.HTTP_SERVER_ERROR -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.SERVER_ERROR,
                    message = SAFE_FAILURE,
                    credentialTest = finding,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.NETWORK_UNREACHABLE -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.NETWORK_UNREACHABLE,
                    message = SAFE_FAILURE,
                    credentialTest = finding.copy(reachable = false),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.TIMEOUT -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.TIMEOUT,
                    message = SAFE_FAILURE,
                    credentialTest = finding.copy(reachable = false),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CatalogStatus.TLS_FAILED -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.TLS_FAILED,
                    message = SAFE_FAILURE,
                    credentialTest = finding.copy(reachable = false),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                else -> StepResult(
                    step = OnboardingStepId.CREDENTIAL_TEST,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.UNKNOWN,
                    message = SAFE_FAILURE,
                    credentialTest = finding,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return StepResult(
                step = OnboardingStepId.CREDENTIAL_TEST,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.UNKNOWN,
                requestsSpent = 1,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.UNKNOWN,
                message = SAFE_FAILURE,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 同 listModels 單次呼叫，轉 CatalogFinding。
     * totalCount null 保持 null 不當 0：CatalogFinding 以 supported=false 表「不支援列舉」
     *（Review 側 modelsFound=null），supported=true+空清單才表 0；truncated 原樣保留。
     */
    private suspend fun listModels(snapshot: OnboardingSnapshot, grant: RequestGrant): StepResult {
        val base = snapshot.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.LIST_MODELS,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.INPUT_INVALID,
                message = SAFE_INPUT_INVALID,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val credentialId = firstSafeId(snapshot)
        val requires = snapshot.authModeOverride != AuthMode.NONE
        if (requires && credentialId == null) {
            return StepResult(
                step = OnboardingStepId.LIST_MODELS,
                status = OnboardingStepStatus.NEEDS_INPUT,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                message = SAFE_NEEDS_CREDENTIAL,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (!grant.tryReserve(1)) {
            return StepResult(
                step = OnboardingStepId.LIST_MODELS,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.BUDGET_EXHAUSTED,
                message = SAFE_BUDGET_EXHAUSTED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        try {
            val result = probe.listModels(base, credentialId)
            if (result.status == CatalogStatus.CREDENTIAL_UNAVAILABLE) {
                return StepResult(
                    step = OnboardingStepId.LIST_MODELS,
                    status = OnboardingStepStatus.NEEDS_INPUT,
                    attempt = grant.attempt,
                    requestsSpent = 0,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                    message = SAFE_NEEDS_CREDENTIAL,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            if (result.status == CatalogStatus.INPUT_INVALID) {
                return StepResult(
                    step = OnboardingStepId.LIST_MODELS,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    requestsSpent = 0,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.INPUT_INVALID,
                    message = SAFE_INPUT_INVALID,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            if (result.status == CatalogStatus.OK) {
                val models = result.modelIds.map {
                    ModelFinding(
                        modelId = it,
                        origin = ModelOrigin.CATALOG,
                        verification = ModelVerification.UNVERIFIED,
                    )
                }
                return StepResult(
                    step = OnboardingStepId.LIST_MODELS,
                    status = OnboardingStepStatus.SUCCESS,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.MEDIUM,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    catalog = CatalogFinding(
                        supported = true,
                        models = models,
                        truncated = result.truncated,
                        totalCount = result.totalCount,
                    ),
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            if (result.status == CatalogStatus.HTTP_TOO_MANY_REQUESTS) {
                return StepResult(
                    step = OnboardingStepId.LIST_MODELS,
                    status = OnboardingStepStatus.PARTIAL,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.RATE_LIMITED,
                    message = SAFE_RATE_LIMITED,
                    catalog = CatalogFinding(supported = false),
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            if (result.status == CatalogStatus.HTTP_UNAUTHORIZED || result.status == CatalogStatus.HTTP_FORBIDDEN) {
                return StepResult(
                    step = OnboardingStepId.LIST_MODELS,
                    status = OnboardingStepStatus.FAILED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.AUTH_FAILED,
                    message = SAFE_AUTH_FAILED,
                    catalog = CatalogFinding(supported = false),
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            if (
                result.status == CatalogStatus.CATALOG_UNSUPPORTED ||
                result.status == CatalogStatus.CATALOG_MALFORMED ||
                result.status == CatalogStatus.HTTP_NOT_FOUND ||
                result.status == CatalogStatus.HTTP_CLIENT_ERROR ||
                result.status == CatalogStatus.HTTP_UNSUPPORTED ||
                result.status == CatalogStatus.RESPONSE_TOO_LARGE
            ) {
                return StepResult(
                    step = OnboardingStepId.LIST_MODELS,
                    status = OnboardingStepStatus.SKIPPED,
                    attempt = grant.attempt,
                    confidence = EvidenceConfidence.UNKNOWN,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                    reason = OnboardingFailureReason.NOT_APPLICABLE,
                    message = SAFE_NOT_APPLICABLE,
                    catalog = CatalogFinding(supported = false),
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            val reason = when (result.status) {
                CatalogStatus.NETWORK_UNREACHABLE -> OnboardingFailureReason.NETWORK_UNREACHABLE
                CatalogStatus.TIMEOUT -> OnboardingFailureReason.TIMEOUT
                CatalogStatus.TLS_FAILED -> OnboardingFailureReason.TLS_FAILED
                CatalogStatus.HTTP_SERVER_ERROR -> OnboardingFailureReason.SERVER_ERROR
                else -> OnboardingFailureReason.UNKNOWN
            }
            return StepResult(
                step = OnboardingStepId.LIST_MODELS,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.UNKNOWN,
                requestsSpent = 1,
                requestLimit = grant.requestLimit,
                reason = reason,
                message = safeMessageFor(reason),
                catalog = CatalogFinding(supported = false),
                updatedAtMillis = System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return StepResult(
                step = OnboardingStepId.LIST_MODELS,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.UNKNOWN,
                requestsSpent = 1,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.UNKNOWN,
                message = SAFE_FAILURE,
                catalog = CatalogFinding(supported = false),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 對 catalog＋manual 模型各 1 次 engine.execute。
     * source=BACKGROUND_PROBE、maxOutputTokens=1、price/quota UNKNOWN。
     * 嚴守 grant.requestLimit / maxTestModels；429 立停後續並標 stoppedByRateLimit。
     */
    private suspend fun testModel(snapshot: OnboardingSnapshot, grant: RequestGrant): StepResult {
        val base = snapshot.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.INPUT_INVALID,
                message = SAFE_INPUT_INVALID,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val credentialId = firstSafeId(snapshot)
        val requires = snapshot.authModeOverride != AuthMode.NONE
        if (requires && credentialId == null) {
            return StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.NEEDS_INPUT,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                message = SAFE_NEEDS_CREDENTIAL,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val targets = collectTestTargets(snapshot)
        if (targets.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.NEEDS_INPUT,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.NO_MODELS_TO_TEST,
                message = SAFE_NO_MODELS,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val chatUrl = buildChatUrl(base)
        if (chatUrl == null) {
            return StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.INPUT_INVALID,
                message = SAFE_INPUT_INVALID,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val findings = ArrayList<ModelFinding>()
        val perModelReason = ArrayList<OnboardingFailureReason>()
        var spent = 0
        var stoppedByRateLimit = false
        val allowed = minOf(grant.requestLimit, grant.maxTestModels, targets.size).coerceAtLeast(0)
        for (modelId in targets.take(allowed)) {
            if (spent >= grant.requestLimit) break
            if (findings.size >= grant.maxTestModels) break
            if (!grant.tryReserve(1)) break
            // Probing happens before commit, so provider/account/endpoint rows do not exist yet:
            // scope telemetry to this workflow instead of sharing static ids across probes.
            val scopeBase = snapshot.workflowId.ifBlank { PROBE_PROVIDER_FALLBACK }
            val target = InferenceTarget(
                providerId = scopeBase,
                accountId = "$scopeBase-account",
                credentialId = credentialId,
                endpointId = "$scopeBase-endpoint",
                endpointUrl = chatUrl,
                modelId = modelId,
                group = snapshot.group,
                operation = PROBE_OPERATION,
                maxOutputTokens = 1,
            )
            try {
                engine.execute(
                    target = target,
                    messages = listOf(PromptMessage(ROLE_USER, PROBE_PROMPT)),
                    source = TelemetrySource.BACKGROUND_PROBE,
                    priceState = PRICE_UNKNOWN,
                    quotaState = QUOTA_UNKNOWN,
                ).toList()
                spent += 1
                findings += ModelFinding(
                    modelId = modelId,
                    origin = originFor(modelId, snapshot),
                    verification = ModelVerification.VERIFIED,
                    source = EvidenceSource.PROBE,
                    confidence = EvidenceConfidence.HIGH,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: InferenceFailure) {
                spent += 1
                if (e.outcome == Outcome.RATE_LIMIT || e.status == 429) {
                    findings += ModelFinding(
                        modelId = modelId,
                        origin = originFor(modelId, snapshot),
                        verification = ModelVerification.FAILED,
                        source = EvidenceSource.PROBE,
                        confidence = EvidenceConfidence.LOW,
                    )
                    perModelReason += OnboardingFailureReason.RATE_LIMITED
                    stoppedByRateLimit = true
                    break
                }
                val reason = when (e.outcome) {
                    Outcome.AUTH -> OnboardingFailureReason.AUTH_FAILED
                    Outcome.MODEL_UNAVAILABLE -> OnboardingFailureReason.MODEL_NOT_FOUND
                    Outcome.SERVER -> OnboardingFailureReason.SERVER_ERROR
                    Outcome.TIMEOUT -> OnboardingFailureReason.TIMEOUT
                    Outcome.NETWORK -> OnboardingFailureReason.NETWORK_UNREACHABLE
                    Outcome.PARSER -> OnboardingFailureReason.PROTOCOL_MISMATCH
                    else -> OnboardingFailureReason.UNKNOWN
                }
                findings += ModelFinding(
                    modelId = modelId,
                    origin = originFor(modelId, snapshot),
                    verification = ModelVerification.FAILED,
                    source = EvidenceSource.PROBE,
                    confidence = EvidenceConfidence.LOW,
                )
                perModelReason += reason
            } catch (_: IllegalArgumentException) {
                findings += ModelFinding(
                    modelId = modelId,
                    origin = originFor(modelId, snapshot),
                    verification = ModelVerification.FAILED,
                    source = EvidenceSource.PROBE,
                    confidence = EvidenceConfidence.LOW,
                )
                perModelReason += OnboardingFailureReason.INPUT_INVALID
            } catch (_: Exception) {
                spent += 1
                findings += ModelFinding(
                    modelId = modelId,
                    origin = originFor(modelId, snapshot),
                    verification = ModelVerification.FAILED,
                    source = EvidenceSource.PROBE,
                    confidence = EvidenceConfidence.LOW,
                )
                perModelReason += OnboardingFailureReason.UNKNOWN
            }
        }
        if (findings.isEmpty() && !stoppedByRateLimit) {
            return StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = spent,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.BUDGET_EXHAUSTED,
                message = SAFE_BUDGET_EXHAUSTED,
                modelTests = TestModelFinding(findings = emptyList(), testedTotal = 0, stoppedByRateLimit = false),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (stoppedByRateLimit) {
            return StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.PARTIAL,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.LOW,
                requestsSpent = spent,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.RATE_LIMITED,
                message = SAFE_RATE_LIMITED,
                modelTests = TestModelFinding(
                    findings = findings,
                    testedTotal = findings.size,
                    stoppedByRateLimit = true,
                ),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val verified = findings.count { it.verification == ModelVerification.VERIFIED }
        return when {
            verified == findings.size -> StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.SUCCESS,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.HIGH,
                requestsSpent = spent,
                requestLimit = grant.requestLimit,
                modelTests = TestModelFinding(
                    findings = findings,
                    testedTotal = findings.size,
                    stoppedByRateLimit = false,
                ),
                updatedAtMillis = System.currentTimeMillis(),
            )
            verified > 0 -> StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.PARTIAL,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.LOW,
                requestsSpent = spent,
                requestLimit = grant.requestLimit,
                modelTests = TestModelFinding(
                    findings = findings,
                    testedTotal = findings.size,
                    stoppedByRateLimit = false,
                ),
                updatedAtMillis = System.currentTimeMillis(),
            )
            else -> StepResult(
                step = OnboardingStepId.TEST_MODEL,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.LOW,
                requestsSpent = spent,
                requestLimit = grant.requestLimit,
                reason = overallFailure(perModelReason),
                message = SAFE_FAILURE,
                modelTests = TestModelFinding(
                    findings = findings,
                    testedTotal = findings.size,
                    stoppedByRateLimit = false,
                ),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 已知 adapter 走能力介面真嘗試；未接線（[noticeSource] null）沿用舊 placeholder。
     *
     * 真嘗試順序（預算內優先順序，見 `ProbeBudgetLimits`；`PRICING_QUOTA_NOTICE` 上限 3）：
     * 1. `capabilities`（匿名）：版本／能力偵測的唯一前置關卡；未認證則後續全記
     *    Unsupported 且不再發 request（未知版本不猜相容）。
     * 2. `quota`（憑證）：PAT（ACCOUNT 角色）優先走帳號層級，否則 inference key 走
     *    key 層級；匿名／無憑證記 Unsupported，不阻擋全流程（契約 §1.4）。
     * 3. `pricing`（PAT 限定）：Quick Add 無 PAT 故記 Unsupported＋evidence，不猜價格。
     * 4. `notice`（匿名）：預算有剩才試；預算用盡記 skipped-budget evidence。
     *
     * - 任一探測命中 429 立即停手（不盲目重試消耗 quota，契約 §4／§5），整步 PARTIAL＋
     *   RATE_LIMITED；quota 無更佳事實時記 RATE_LIMIT_ONLY 候選。
     * - pricingStatus：官方非零→VERIFIED_PAID；items 非空且全零無未知→VERIFIED_FREE；
     *   其餘（空／部分未知／未取）一律 UNKNOWN（缺價格≠免費）。
     * - health 雙軌維持 UNKNOWN：metadata 抓取成功≠健康訊號，不拿它冒充 health。
     * - checkinUrl 永不推導（base／website 不拼路徑）；requiresDailyCheckin 僅在官方
     *   checkinEnabled==true 時為 true，否則 null（未知）。
     * - 失敗一律非 authoritative：只記分類＋evidence，不寫反向事實（例如 pricing 查無
     *   不寫 price＝0，維持 UNKNOWN＋evidence）。
     */
    private suspend fun pricingQuotaNotice(snapshot: OnboardingSnapshot, grant: RequestGrant): StepResult {
        val source = noticeSource ?: return StepResult(
            step = OnboardingStepId.PRICING_QUOTA_NOTICE,
            status = OnboardingStepStatus.PARTIAL,
            attempt = grant.attempt,
            evidenceIds = listOf(EVIDENCE_PRICING_UNKNOWN),
            confidence = EvidenceConfidence.UNKNOWN,
            requestsSpent = 0,
            requestLimit = grant.requestLimit,
            pricingQuota = PricingQuotaFinding(
                pricingStatus = PriceStatus.UNKNOWN,
                quotaStatus = QuotaState.UNKNOWN,
                officialHealth = HealthState.UNKNOWN,
                observedHealth = HealthState.UNKNOWN,
                noticeFetched = false,
                checkinUrl = null,
                requiresDailyCheckin = false,
            ),
            updatedAtMillis = System.currentTimeMillis(),
        )
        return try {
            pricingQuotaNoticeVia(source, snapshot, grant)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StepResult(
                step = OnboardingStepId.PRICING_QUOTA_NOTICE,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                confidence = EvidenceConfidence.UNKNOWN,
                requestsSpent = grant.reservedRequests,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.UNKNOWN,
                message = SAFE_FAILURE,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun pricingQuotaNoticeVia(
        source: PricingQuotaNoticeSource,
        snapshot: OnboardingSnapshot,
        grant: RequestGrant,
    ): StepResult {
        val base = snapshot.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) {
            return StepResult(
                step = OnboardingStepId.PRICING_QUOTA_NOTICE,
                status = OnboardingStepStatus.FAILED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.INPUT_INVALID,
                message = SAFE_INPUT_INVALID,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val evidence = ArrayList<String>()
        var spent = 0
        var successes = 0
        var failures = 0
        var capabilities: NoticeCapabilities? = null
        var quotaState = QuotaState.UNKNOWN
        var pricing: PricingObservation? = null
        var noticeFetched: Boolean? = null

        fun tally(attempt: NoticeAttempt<*>) {
            spent += attempt.requestsSpent
            evidence += attempt.outcome.evidenceId()
            when (attempt.outcome) {
                is NoticeProbeOutcome.Success -> successes += 1
                is NoticeProbeOutcome.Failure -> failures += 1
                is NoticeProbeOutcome.Unsupported -> Unit
            }
        }

        fun partialSoFar(): StepResult = buildPricingResult(
            grant = grant,
            evidence = evidence,
            spent = spent,
            successes = successes,
            failures = failures,
            complete = false,
            capabilities = capabilities,
            quotaState = quotaState,
            pricing = pricing,
            noticeFetched = noticeFetched,
        )

        // 1) capabilities：匿名，唯一前置關卡。預算不足以開場即整步 SKIPPED。
        if (!grant.tryReserve(1)) {
            return StepResult(
                step = OnboardingStepId.PRICING_QUOTA_NOTICE,
                status = OnboardingStepStatus.SKIPPED,
                attempt = grant.attempt,
                requestsSpent = 0,
                requestLimit = grant.requestLimit,
                reason = OnboardingFailureReason.BUDGET_EXHAUSTED,
                message = SAFE_BUDGET_EXHAUSTED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        val capsAttempt = source.probeCapabilities(base)
        tally(capsAttempt)
        if (capsAttempt.outcome.isRateLimited()) {
            return rateLimitedPricingResult(grant, evidence, spent, quotaState)
        }
        capabilities = (capsAttempt.outcome as? NoticeProbeOutcome.Success)?.value
        val certified = capabilities?.certifiedNewApi == true
        val versionRaw = capabilities?.versionRaw

        // 2) quota：PAT 優先，否則 inference key；匿名／無憑證記不支援。
        if (certified) {
            val patRef = snapshot.credentials
                .firstOrNull { it.role == CredentialRole.ACCOUNT }
                ?.safeCredentialId?.trim().orEmpty()
            val keyRef = firstSafeId(snapshot)
            when {
                patRef.isNotEmpty() -> {
                    if (!grant.tryReserve(1)) {
                        evidence += EVIDENCE_QUOTA_SKIPPED_BUDGET
                        return partialSoFar()
                    }
                    val quotaAttempt = source.probeAccountQuota(base, patRef, versionRaw)
                    tally(quotaAttempt)
                    if (quotaAttempt.outcome.isRateLimited()) {
                        return rateLimitedPricingResult(grant, evidence, spent, quotaState)
                    }
                    (quotaAttempt.outcome as? NoticeProbeOutcome.Success)?.value?.let {
                        quotaState = it.state
                    }
                }
                keyRef != null -> {
                    if (!grant.tryReserve(1)) {
                        evidence += EVIDENCE_QUOTA_SKIPPED_BUDGET
                        return partialSoFar()
                    }
                    val quotaAttempt = source.probeTokenQuota(base, keyRef, versionRaw)
                    tally(quotaAttempt)
                    if (quotaAttempt.outcome.isRateLimited()) {
                        return rateLimitedPricingResult(grant, evidence, spent, quotaState)
                    }
                    (quotaAttempt.outcome as? NoticeProbeOutcome.Success)?.value?.let {
                        quotaState = it.state
                    }
                }
                else -> evidence += EVIDENCE_QUOTA_NO_CREDENTIAL
            }
        } else {
            evidence += EVIDENCE_QUOTA_UNSUPPORTED
        }

        // 3) pricing：PAT 限定；Quick Add 無 PAT 故記不支援，不猜價格。
        if (certified) {
            val patRef = snapshot.credentials
                .firstOrNull { it.role == CredentialRole.ACCOUNT }
                ?.safeCredentialId?.trim().orEmpty()
            if (patRef.isEmpty()) {
                evidence += EVIDENCE_PRICING_NO_PAT
            } else {
                if (!grant.tryReserve(1)) {
                    evidence += EVIDENCE_PRICING_SKIPPED_BUDGET
                    return partialSoFar()
                }
                val pricingAttempt = source.probePricing(base, patRef, versionRaw)
                tally(pricingAttempt)
                if (pricingAttempt.outcome.isRateLimited()) {
                    return rateLimitedPricingResult(grant, evidence, spent, quotaState)
                }
                (pricingAttempt.outcome as? NoticeProbeOutcome.Success)?.value?.let {
                    pricing = it
                }
            }
        } else {
            evidence += EVIDENCE_PRICING_UNSUPPORTED
        }

        // 4) notice：匿名；預算有剩才試。
        if (certified) {
            if (!grant.tryReserve(1)) {
                evidence += EVIDENCE_NOTICE_SKIPPED_BUDGET
                return partialSoFar()
            }
            val noticeAttempt = source.probeNotice(base, versionRaw)
            tally(noticeAttempt)
            if (noticeAttempt.outcome.isRateLimited()) {
                return rateLimitedPricingResult(grant, evidence, spent, quotaState)
            }
            (noticeAttempt.outcome as? NoticeProbeOutcome.Success)?.value?.let {
                noticeFetched = it.present
            }
        } else {
            evidence += EVIDENCE_NOTICE_UNSUPPORTED
        }

        // 全量跑完（預算內）：complete=true，零失敗且有成功才 SUCCESS。
        return buildPricingResult(
            grant = grant,
            evidence = evidence,
            spent = spent,
            successes = successes,
            failures = failures,
            complete = true,
            capabilities = capabilities,
            quotaState = quotaState,
            pricing = pricing,
            noticeFetched = noticeFetched,
        )
    }

    private fun buildPricingResult(
        grant: RequestGrant,
        evidence: List<String>,
        spent: Int,
        successes: Int,
        failures: Int,
        complete: Boolean = true,
        capabilities: NoticeCapabilities?,
        quotaState: QuotaState,
        pricing: PricingObservation?,
        noticeFetched: Boolean?,
    ): StepResult {
        val pricingStatus = when {
            pricing == null -> PriceStatus.UNKNOWN
            pricing.paidItems > 0 -> PriceStatus.VERIFIED_PAID
            pricing.itemsTotal > 0 && pricing.unknownItems == 0 -> PriceStatus.VERIFIED_FREE
            else -> PriceStatus.UNKNOWN
        }
        // 預算截斷（complete=false）即使零失敗也只能 PARTIAL：有能力根本沒試到。
        val status = if (complete && failures == 0 && successes > 0) {
            OnboardingStepStatus.SUCCESS
        } else {
            OnboardingStepStatus.PARTIAL
        }
        val confidence = when {
            pricing != null -> EvidenceConfidence.HIGH
            successes > 0 -> EvidenceConfidence.MEDIUM
            else -> EvidenceConfidence.UNKNOWN
        }
        return StepResult(
            step = OnboardingStepId.PRICING_QUOTA_NOTICE,
            status = status,
            attempt = grant.attempt,
            evidenceIds = evidence.distinct(),
            confidence = confidence,
            requestsSpent = spent,
            requestLimit = grant.requestLimit,
            pricingQuota = PricingQuotaFinding(
                pricingStatus = pricingStatus,
                quotaStatus = quotaState,
                officialHealth = HealthState.UNKNOWN,
                observedHealth = HealthState.UNKNOWN,
                noticeFetched = noticeFetched,
                checkinUrl = null,
                requiresDailyCheckin = if (capabilities?.checkinEnabled == true) true else null,
            ),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun rateLimitedPricingResult(
        grant: RequestGrant,
        evidence: List<String>,
        spent: Int,
        quotaState: QuotaState,
    ): StepResult {
        // 429 是觀測事實：無更佳 quota 事實時記 RATE_LIMIT_ONLY 候選（契約 §4），不裝成已知配額。
        val limited = if (quotaState == QuotaState.UNKNOWN) QuotaState.RATE_LIMIT_ONLY else quotaState
        return StepResult(
            step = OnboardingStepId.PRICING_QUOTA_NOTICE,
            status = OnboardingStepStatus.PARTIAL,
            attempt = grant.attempt,
            evidenceIds = evidence.distinct(),
            confidence = EvidenceConfidence.UNKNOWN,
            requestsSpent = spent,
            requestLimit = grant.requestLimit,
            reason = OnboardingFailureReason.RATE_LIMITED,
            message = SAFE_RATE_LIMITED,
            pricingQuota = PricingQuotaFinding(
                pricingStatus = PriceStatus.UNKNOWN,
                quotaStatus = limited,
                officialHealth = HealthState.UNKNOWN,
                observedHealth = HealthState.UNKNOWN,
                noticeFetched = null,
                checkinUrl = null,
                requiresDailyCheckin = null,
            ),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun NoticeProbeOutcome<*>.evidenceId(): String = when (this) {
        is NoticeProbeOutcome.Success -> evidenceId
        is NoticeProbeOutcome.Unsupported -> evidenceId
        is NoticeProbeOutcome.Failure -> evidenceId
    }

    private fun NoticeProbeOutcome<*>.isRateLimited(): Boolean =
        this is NoticeProbeOutcome.Failure && rateLimited

    /**
     * 探測用 inference refs：只取 INFERENCE role。`safeCredentialIds`
     * （QuickAdd／舊快照，無 role 欄）視為 inference；ACCOUNT（PAT）永不進探測，
     * 避免 PAT 被當 Bearer 打 inference endpoint。
     */
    private fun collectSafeIds(snapshot: OnboardingSnapshot): List<String> {
        val ids = ArrayList<String>()
        ids += snapshot.safeCredentialIds.map { it.trim() }.filter { it.isNotEmpty() }
        ids += snapshot.credentials
            .filter { it.role == CredentialRole.INFERENCE }
            .map { it.safeCredentialId.trim() }
            .filter { it.isNotEmpty() }
        return ids.distinct()
    }

    private fun firstSafeId(snapshot: OnboardingSnapshot): String? = collectSafeIds(snapshot).firstOrNull()

    private fun collectTestTargets(snapshot: OnboardingSnapshot): List<String> {
        val catalogIds = snapshot.catalog?.takeIf { it.supported }?.models?.map { it.modelId } ?: emptyList()
        return (catalogIds + snapshot.manualModelIds + snapshot.suggestedModelIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun originFor(modelId: String, snapshot: OnboardingSnapshot): ModelOrigin {
        val inCatalog = snapshot.catalog?.takeIf { it.supported }?.models?.any { it.modelId == modelId } == true
        if (inCatalog) return ModelOrigin.CATALOG
        if (snapshot.manualModelIds.any { it.trim() == modelId }) return ModelOrigin.USER_MANUAL
        return ModelOrigin.SUGGESTED
    }

    /** HTTPS-only, idempotent path join; invalid bases become INPUT_INVALID upstream. */
    private fun buildChatUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (allowLoopbackHttpForTests && isLoopbackBase(trimmed)) {
            val path = trimmed.trimEnd('/')
            if (path.endsWith(CHAT_SUFFIX)) return path
            return "$path$CHAT_SUFFIX"
        }
        return runCatching { EndpointPolicy.chatEndpoint(baseUrl) }.getOrNull()
    }

    private fun isLoopbackBase(trimmed: String): Boolean {
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        if (parsed.host !in LOOPBACK_HOSTS) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        return parsed.query == null && parsed.fragment == null
    }

    private fun overallFailure(reasons: List<OnboardingFailureReason>): OnboardingFailureReason {
        if (reasons.isEmpty()) return OnboardingFailureReason.UNKNOWN
        if (reasons.all { it == OnboardingFailureReason.AUTH_FAILED }) return OnboardingFailureReason.AUTH_FAILED
        if (reasons.all { it == OnboardingFailureReason.MODEL_NOT_FOUND }) return OnboardingFailureReason.MODEL_NOT_FOUND
        if (reasons.all { it == OnboardingFailureReason.TIMEOUT }) return OnboardingFailureReason.TIMEOUT
        if (reasons.all { it == OnboardingFailureReason.NETWORK_UNREACHABLE }) return OnboardingFailureReason.NETWORK_UNREACHABLE
        if (reasons.all { it == OnboardingFailureReason.SERVER_ERROR }) return OnboardingFailureReason.SERVER_ERROR
        return OnboardingFailureReason.UNKNOWN
    }

    private fun safeMessageFor(reason: OnboardingFailureReason): String = when (reason) {
        OnboardingFailureReason.INPUT_INVALID -> SAFE_INPUT_INVALID
        OnboardingFailureReason.AUTH_FAILED -> SAFE_AUTH_FAILED
        OnboardingFailureReason.AUTH_MISSING_CREDENTIAL -> SAFE_NEEDS_CREDENTIAL
        OnboardingFailureReason.RATE_LIMITED -> SAFE_RATE_LIMITED
        OnboardingFailureReason.RATE_LIMITED_STOP -> SAFE_STOPPED_BY_429
        OnboardingFailureReason.BUDGET_EXHAUSTED -> SAFE_BUDGET_EXHAUSTED
        OnboardingFailureReason.NO_MODELS_TO_TEST -> SAFE_NO_MODELS
        OnboardingFailureReason.NOT_APPLICABLE -> SAFE_NOT_APPLICABLE
        else -> SAFE_FAILURE
    }

    private companion object {
        const val SAFE_FAILURE = "步驟執行失敗"
        const val SAFE_INPUT_INVALID = "輸入無效"
        const val SAFE_AUTH_FAILED = "認證失敗"
        const val SAFE_NEEDS_CREDENTIAL = "缺少憑證，需要輸入"
        const val SAFE_RATE_LIMITED = "已遇速率限制，停止重試"
        const val SAFE_STOPPED_BY_429 = "已遇 429，停止後續探測"
        const val SAFE_BUDGET_EXHAUSTED = "探測預算用盡，未執行"
        const val SAFE_NO_MODELS = "沒有可測模型，等待 catalog 或手動 model id"
        const val SAFE_NOT_APPLICABLE = "不適用，已略過"
        const val EVIDENCE_PRICING_UNKNOWN = "pricing-unknown-no-official-source"
        const val EVIDENCE_QUOTA_UNSUPPORTED = "quota-unsupported-not-newapi"
        const val EVIDENCE_QUOTA_NO_CREDENTIAL = "quota-no-credential"
        const val EVIDENCE_QUOTA_SKIPPED_BUDGET = "quota-skipped-budget"
        const val EVIDENCE_PRICING_UNSUPPORTED = "pricing-unsupported-not-newapi"
        const val EVIDENCE_PRICING_NO_PAT = "pricing-no-pat-advanced-only"
        const val EVIDENCE_PRICING_SKIPPED_BUDGET = "pricing-skipped-budget"
        const val EVIDENCE_NOTICE_UNSUPPORTED = "notice-unsupported-not-newapi"
        const val EVIDENCE_NOTICE_SKIPPED_BUDGET = "notice-skipped-budget"
        const val CHAT_SUFFIX = "/chat/completions"
        const val PROBE_PROVIDER_FALLBACK = "probe-provider"
        const val PROBE_OPERATION = "CHAT_COMPLETIONS"
        const val PROBE_PROMPT = "hi"
        const val ROLE_USER = "user"
        const val PRICE_UNKNOWN = "UNKNOWN"
        const val QUOTA_UNKNOWN = "UNKNOWN"
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
