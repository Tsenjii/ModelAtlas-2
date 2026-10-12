package dev.modelatlas.core.newapi

import java.math.BigDecimal
import java.math.MathContext

/**
 * New API `billing_expr` 安全解析器（Phase 4 落點）。
 *
 * 研究依據：`docs/ADAPTER_NEWAPI_RESEARCH.md` §4（觀測日期 2026-09-27，
 * New API `v1.0.0-rc.25` tag 原始碼為準，seekai.cc 實機觀測為輔）。
 *
 * 已確認事實（才可依賴）：
 * - `billing_mode` 目前僅觀測到 `"tiered_expr"`；`billing_expr` 為站台自訂運算式字串。
 * - 係數單位為 USD/1M tokens；換算 `quota = exprCost / 1_000_000 * quota_per_unit * group_ratio`
 *  （`relay/helper/price.go ModelPriceHelper`，研究 §4.3）。
 * - `quota_per_unit` 來自同一次 session 的 `/api/status`；未知時不可用預設 500000 硬塞。
 * - 站內 `HandleGroupRatio` 預設 1.0，但 parser 仍要求呼叫方顯式傳入，不自行猜測。
 * - `group_ratio == 0` 在站內計價會得 0，但 ModelAtlas 是否 FREE_NOW 仍由 domain
 *   `PricingPolicy` 依完整 snapshot 判定，本 parser 只回傳數字，不決定 FREE/PAID。
 *
 * 未確認文法（研究 §6.5 明確標 U）：`billing_expr` 完整運算子、函數、變數名、
 * 指數寫法、單位後綴、三元/比較/邏輯、字串、成員存取等皆未知。
 * 本 parser 只接受下方「最小安全子集」，其餘一律回 [PricingExprResult.Unknown]，
 * 絕不宣稱相容上游完整文法，絕不猜 0。
 *
 * 支援子集：
 * - `expr := term (('+' | '-') term)*`
 * - `term := factor (('*' | '/') factor)*`
 * - `factor := ('+' | '-') factor | primary`
 * - `primary := number | variable | '(' expr ')'`
 * - `number := DIGIT+ ('.' DIGIT+)?`（十進位字串直轉 BigDecimal，不經 Double；
 *   不支援指數 `1e6`、十六進位、前導/尾隨小數點、單位後綴）
 * - `variable := [A-Za-z_][A-Za-z0-9_]{1,64}`（大小寫敏感；呼叫方以 map 提供綁定，
 *   缺綁定即 Unknown，不猜 0；不支援 `a.b`、`$x`、函數呼叫 `f(...)`）
 * - 空白（space/tab/LF/CR）可忽略。
 *
 * 明確不支援（一律 Unknown，不 eval）：
 * 函數呼叫、 `%` `^` `**` `&&` `||` `!` `=` `<` `>` `?` `:` `,` `;`
 * `[...]` `{...}` 引號字串、`.` 成員存取/前導小數點、`\` `$` `#` `@` 等。
 *
 * 安全保證：
 * - 手寫 lexer + 遞迴下降 parser；不用 ScriptEngine/eval/編譯/反射。
 * - 全程 BigDecimal(String) 精確運算；除法用 DECIMAL128；乘加減為精確運算。
 * - 限制長度/Token 數/深度/節點數/字面量長度/變數個數，防 DoS。
 * - 除零、空式、未知變數、不可靠匯率一律 Unknown；Unknown 絕不等於 0。
 * - quota 換算不做 floor/四捨五入；整數 quota 截斷由儲存層顯式處理
 *  （ratio 模式的 floor 語義未確認適用於 tiered 路徑，故不擅自取整）。
 * - 不讀 credential/config，不做網路，不記 secret。
 */
enum class PricingExprUnknownReason {
    /** 空字串或僅空白。 */
    EMPTY,
    /** 超過 [PricingExpressionParser.MAX_EXPRESSION_LENGTH]。 */
    TOO_LONG,
    /** Token/深度/節點數/字面量/變數個數超限。 */
    TOO_COMPLEX,
    /** 已支援字元但結構錯誤（空括號、缺運算元、括號不配對、尾隨運算子等）。 */
    SYNTAX_ERROR,
    /** 研究未確認語法：函數、指數、%、^、比較、三元、字串、成員存取等。 */
    UNSUPPORTED_SYNTAX,
    /** 變數無綁定。 */
    UNKNOWN_VARIABLE,
    /** 除數為零（含 `1/(2-2)`、變數零除數）。 */
    DIVISION_BY_ZERO,
    /** quota 換算匯率不可靠：null、零或負 quota_per_unit，null 或負 group_ratio。 */
    INVALID_RATE,
}

sealed interface PricingExprResult {
    data class Known(val value: BigDecimal) : PricingExprResult
    data class Unknown(val reason: PricingExprUnknownReason) : PricingExprResult
}

fun PricingExprResult.knownValueOrNull(): BigDecimal? =
    (this as? PricingExprResult.Known)?.value

fun PricingExprResult.unknownReasonOrNull(): PricingExprUnknownReason? =
    (this as? PricingExprResult.Unknown)?.reason

object PricingExpressionParser {
    const val MAX_EXPRESSION_LENGTH = 2000
    const val MAX_TOKENS = 500
    const val MAX_DEPTH = 32
    const val MAX_NODES = 1000
    const val MAX_IDENTIFIER_LENGTH = 64
    const val MAX_NUMBER_LITERAL_LENGTH = 128
    const val MAX_VARIABLES = 64
    const val MAX_DECIMAL_PRECISION = 4096
    const val MAX_DECIMAL_SCALE = 4096

    val DIVIDE_CONTEXT: MathContext = MathContext.DECIMAL128
    private val MILLION = BigDecimal("1000000")

    /**
     * 純算術求值，回傳 exprCost（單位 USD/1M tokens，照原文數值語義）。
     * 未知變數/不支援語法/除零等回 Unknown，不猜 0。
     */
    fun evaluate(
        expression: String,
        variables: Map<String, BigDecimal> = emptyMap(),
    ): PricingExprResult {
        if (expression.isBlank()) {
            return PricingExprResult.Unknown(PricingExprUnknownReason.EMPTY)
        }
        if (expression.length > MAX_EXPRESSION_LENGTH) {
            return PricingExprResult.Unknown(PricingExprUnknownReason.TOO_LONG)
        }
        if (variables.size > MAX_VARIABLES || variables.values.any { !bounded(it) }) {
            return PricingExprResult.Unknown(PricingExprUnknownReason.TOO_COMPLEX)
        }
        return try {
            val tokens = lex(expression)
            if (tokens.size > MAX_TOKENS + 1) {
                PricingExprResult.Unknown(PricingExprUnknownReason.TOO_COMPLEX)
            } else {
                val parser = Parser(tokens, variables)
                PricingExprResult.Known(parser.parse())
            }
        } catch (e: Abort) {
            PricingExprResult.Unknown(e.reason)
        } catch (_: ArithmeticException) {
            PricingExprResult.Unknown(PricingExprUnknownReason.TOO_COMPLEX)
        }
    }

    /**
     * tiered 換算：`quota = exprCost / 1_000_000 * quotaPerUnit * groupRatio`。
     *
     * - `quotaPerUnit` 必須同 session 取自 `/api/status` 且 > 0；null/<=0 即 Unknown
     *  （不可用預設 500000 硬塞，研究 §4）。
     * - `groupRatio` 必須顯式傳入；null/負即 Unknown；0 允許（回 Known(0)，
     *   但是否 FREE 由 PricingPolicy 判定，本函數不決定）。
     * - 先檢匯率再算 expr，匯率無效即 Unknown；expr Unknown 則原樣透出。
     * - 不做 floor/捨入；負值照實回 Known（由 PricingPolicy 判 UNKNOWN）。
     */
    fun evaluateQuota(
        expression: String,
        variables: Map<String, BigDecimal> = emptyMap(),
        quotaPerUnit: BigDecimal?,
        groupRatio: BigDecimal?,
    ): PricingExprResult {
        if (quotaPerUnit == null || quotaPerUnit.signum() <= 0) {
            return PricingExprResult.Unknown(PricingExprUnknownReason.INVALID_RATE)
        }
        if (groupRatio == null || groupRatio.signum() < 0) {
            return PricingExprResult.Unknown(PricingExprUnknownReason.INVALID_RATE)
        }
        if (!bounded(quotaPerUnit) || !bounded(groupRatio)) return PricingExprResult.Unknown(PricingExprUnknownReason.TOO_COMPLEX)
        val cost = evaluate(expression, variables)
        val costValue = (cost as? PricingExprResult.Known)?.value ?: return cost
        return try {
            val quota = costValue.divide(MILLION, DIVIDE_CONTEXT)
                .multiply(quotaPerUnit)
                .multiply(groupRatio)
            if (bounded(quota)) PricingExprResult.Known(quota) else PricingExprResult.Unknown(PricingExprUnknownReason.TOO_COMPLEX)
        } catch (e: ArithmeticException) {
            PricingExprResult.Unknown(PricingExprUnknownReason.SYNTAX_ERROR)
        }
    }

    private class Abort(val reason: PricingExprUnknownReason) : Exception()

    private fun bounded(value: BigDecimal): Boolean = value.precision() <= MAX_DECIMAL_PRECISION &&
        value.scale().toLong() in -MAX_DECIMAL_SCALE.toLong()..MAX_DECIMAL_SCALE.toLong()

    private fun checked(value: BigDecimal): BigDecimal {
        if (!bounded(value)) throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
        return value
    }

    private sealed interface Token {
        data class Number(val text: String) : Token
        data class Ident(val name: String) : Token
        object Plus : Token
        object Minus : Token
        object Star : Token
        object Slash : Token
        object LParen : Token
        object RParen : Token
        object Eof : Token
    }

    private fun isAsciiLetter(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z'

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    private fun lex(src: String): List<Token> {
        val out = ArrayList<Token>(64)
        var i = 0
        fun push(t: Token) {
            out.add(t)
            if (out.size > MAX_TOKENS + 1) throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
        }
        while (i < src.length) {
            val c = src[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                isAsciiDigit(c) -> {
                    val start = i
                    while (i < src.length && isAsciiDigit(src[i])) i++
                    if (i < src.length && src[i] == '.') {
                        if (i + 1 < src.length && isAsciiDigit(src[i + 1])) {
                            i++
                            while (i < src.length && isAsciiDigit(src[i])) i++
                        } else {
                            throw Abort(PricingExprUnknownReason.SYNTAX_ERROR)
                        }
                    }
                    val text = src.substring(start, i)
                    if (text.length > MAX_NUMBER_LITERAL_LENGTH) {
                        throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
                    }
                    if (i < src.length && (isAsciiLetter(src[i]) || src[i] == '_')) {
                        throw Abort(PricingExprUnknownReason.UNSUPPORTED_SYNTAX)
                    }
                    try {
                        BigDecimal(text)
                    } catch (e: NumberFormatException) {
                        throw Abort(PricingExprUnknownReason.SYNTAX_ERROR)
                    }
                    push(Token.Number(text))
                }
                isAsciiLetter(c) || c == '_' -> {
                    val start = i
                    while (i < src.length &&
                        (isAsciiLetter(src[i]) || isAsciiDigit(src[i]) || src[i] == '_')
                    ) {
                        i++
                    }
                    val name = src.substring(start, i)
                    if (name.length > MAX_IDENTIFIER_LENGTH) {
                        throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
                    }
                    push(Token.Ident(name))
                }
                c == '+' -> { push(Token.Plus); i++ }
                c == '-' -> { push(Token.Minus); i++ }
                c == '*' -> { push(Token.Star); i++ }
                c == '/' -> { push(Token.Slash); i++ }
                c == '(' -> { push(Token.LParen); i++ }
                c == ')' -> { push(Token.RParen); i++ }
                c == '.' -> throw Abort(PricingExprUnknownReason.UNSUPPORTED_SYNTAX)
                else -> throw Abort(PricingExprUnknownReason.UNSUPPORTED_SYNTAX)
            }
        }
        out.add(Token.Eof)
        return out
    }

    private class Parser(
        private val tokens: List<Token>,
        private val variables: Map<String, BigDecimal>,
    ) {
        private var pos = 0
        private var nodes = 0

        private fun peek(): Token = tokens.getOrNull(pos) ?: throw Abort(PricingExprUnknownReason.SYNTAX_ERROR)
        private fun next(): Token = peek().also { pos++ }

        private fun countNode() {
            nodes++
            if (nodes > PricingExpressionParser.MAX_NODES) {
                throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
            }
        }

        fun parse(): BigDecimal {
            val v = parseExpr(0)
            if (peek() !is Token.Eof) throw Abort(PricingExprUnknownReason.SYNTAX_ERROR)
            return v
        }

        private fun parseExpr(depth: Int): BigDecimal {
            if (depth > PricingExpressionParser.MAX_DEPTH) {
                throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
            }
            var v = parseTerm(depth + 1)
            while (true) {
                when (peek()) {
                    is Token.Plus -> {
                        next()
                        countNode()
                        v = checked(v.add(parseTerm(depth + 1)))
                    }
                    is Token.Minus -> {
                        next()
                        countNode()
                        v = checked(v.subtract(parseTerm(depth + 1)))
                    }
                    else -> return v
                }
            }
        }

        private fun parseTerm(depth: Int): BigDecimal {
            if (depth > PricingExpressionParser.MAX_DEPTH) {
                throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
            }
            var v = parseFactor(depth + 1)
            while (true) {
                when (peek()) {
                    is Token.Star -> {
                        next()
                        countNode()
                        v = checked(v.multiply(parseFactor(depth + 1)))
                    }
                    is Token.Slash -> {
                        next()
                        countNode()
                        val rhs = parseFactor(depth + 1)
                        if (rhs.signum() == 0) {
                            throw Abort(PricingExprUnknownReason.DIVISION_BY_ZERO)
                        }
                        v = checked(v.divide(rhs, PricingExpressionParser.DIVIDE_CONTEXT))
                    }
                    else -> return v
                }
            }
        }

        private fun parseFactor(depth: Int): BigDecimal {
            if (depth > PricingExpressionParser.MAX_DEPTH) {
                throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
            }
            return when (peek()) {
                is Token.Plus -> {
                    next()
                    countNode()
                    parseFactor(depth + 1)
                }
                is Token.Minus -> {
                    next()
                    countNode()
                    parseFactor(depth + 1).negate()
                }
                else -> parsePrimary(depth + 1)
            }
        }

        private fun parsePrimary(depth: Int): BigDecimal {
            if (depth > PricingExpressionParser.MAX_DEPTH) {
                throw Abort(PricingExprUnknownReason.TOO_COMPLEX)
            }
            countNode()
            return when (val t = peek()) {
                is Token.Number -> {
                    next()
                    BigDecimal(t.text)
                }
                is Token.Ident -> {
                    next()
                    if (peek() is Token.LParen) {
                        throw Abort(PricingExprUnknownReason.UNSUPPORTED_SYNTAX)
                    }
                    variables[t.name]
                        ?: throw Abort(PricingExprUnknownReason.UNKNOWN_VARIABLE)
                }
                is Token.LParen -> {
                    next()
                    if (peek() is Token.RParen) {
                        throw Abort(PricingExprUnknownReason.SYNTAX_ERROR)
                    }
                    val v = parseExpr(depth + 1)
                    val closing = next()
                    if (closing !is Token.RParen) {
                        throw Abort(PricingExprUnknownReason.SYNTAX_ERROR)
                    }
                    v
                }
                else -> throw Abort(PricingExprUnknownReason.SYNTAX_ERROR)
            }
        }
    }
}
