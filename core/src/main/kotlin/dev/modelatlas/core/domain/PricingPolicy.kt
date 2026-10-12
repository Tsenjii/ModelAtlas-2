package dev.modelatlas.core.domain

import java.math.BigDecimal

data class PriceAssessment(
    val state: PriceState,
    val cacheCostsExtra: Boolean,
)

/**
 * 首版定價判定。
 *
 * - 只支援 operation == "text" 或 "CHAT_COMPLETIONS"，其他一律 UNKNOWN（不猜）。
 * - 判定只讀數字，從不讀 modelId / 名稱（名稱含 free 也不免費）。
 * - 必要 input / output 未知 → UNKNOWN；fixed 未知且未明確 absent → UNKNOWN。
 * - 零值用 signum 判斷（"0.00" 亦為零）；任一主腿負價 → UNKNOWN（負價不當免費）。
 * - FREE_NOW：input/output 皆零 且 fixed 零或明確 absent。
 * - PARTIALLY_FREE：token 至少一腿零但非 FREE_NOW（含 token 全零但收固定費）。
 * - 其餘皆已知且 token 雙腿正價 → PAID。
 * - cache 只決定 cacheCostsExtra（任一 cache 正價即 true），獨立於 state 呈現。
 */
object PricingPolicy {
    val SUPPORTED_OPERATIONS = setOf("text", "CHAT_COMPLETIONS")

    fun evaluate(snapshot: PriceSnapshot?): PriceAssessment {
        if (snapshot == null) return PriceAssessment(PriceState.UNKNOWN, false)
        val extra = isPositive(snapshot.cacheRead) || isPositive(snapshot.cacheWrite)
        if (snapshot.scope.operation !in SUPPORTED_OPERATIONS) {
            return PriceAssessment(PriceState.UNKNOWN, extra)
        }
        val input = snapshot.input
        val output = snapshot.output
        val fixed = snapshot.fixedRequest
        if (input == null || output == null) return PriceAssessment(PriceState.UNKNOWN, extra)
        if (fixed == null && !snapshot.fixedRequestKnownAbsent) {
            return PriceAssessment(PriceState.UNKNOWN, extra)
        }
        if (isNegative(input) || isNegative(output) || (fixed != null && isNegative(fixed))) {
            return PriceAssessment(PriceState.UNKNOWN, extra)
        }
        val inputFree = isZero(input)
        val outputFree = isZero(output)
        val fixedFree = (fixed == null && snapshot.fixedRequestKnownAbsent) ||
            (fixed != null && isZero(fixed))
        if (inputFree && outputFree && fixedFree) {
            return PriceAssessment(PriceState.FREE_NOW, extra)
        }
        if (inputFree || outputFree) {
            return PriceAssessment(PriceState.PARTIALLY_FREE, extra)
        }
        return PriceAssessment(PriceState.PAID, extra)
    }

    private fun isZero(v: BigDecimal): Boolean = v.signum() == 0

    private fun isPositive(v: BigDecimal?): Boolean = v != null && v.signum() > 0

    private fun isNegative(v: BigDecimal): Boolean = v.signum() < 0
}
