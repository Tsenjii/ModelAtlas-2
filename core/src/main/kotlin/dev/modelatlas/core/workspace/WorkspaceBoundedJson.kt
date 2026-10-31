package dev.modelatlas.core.workspace

import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.json.*

/** The 1.8.0 stream writer copies a whole quoted String into its char buffer.
 * Keep character and UTF-8 buffers bounded; preserve native v1 fields/defaults. */
internal class WorkspaceBoundedJson(output: OutputStream, private val fingerprint: Boolean = false) {
    private val writer = OutputStreamWriter(output, Charsets.UTF_8)
    private val chars = CharArray(8192)
    private var used = 0
    private fun drain() { if (used > 0) { writer.write(chars, 0, used); used = 0 } }
    private fun char(value: Char) { if (used == chars.size) drain(); chars[used++] = value }
    private fun text(value: String, start: Int = 0, end: Int = value.length) {
        var at = start
        while (at < end) {
            if (used == chars.size) drain()
            val n = minOf(end - at, chars.size - used)
            value.toCharArray(chars, used, at, at + n); used += n; at += n
        }
    }
    private fun unicode(value: Char) {
        text("\\u")
        var shift = 12
        while (shift >= 0) { char("0123456789abcdef"[(value.code shr shift) and 15]); shift -= 4 }
    }
    private fun quote(value: String, key: Boolean = false) {
        char('"'); var start = 0; var at = 0
        while (at < value.length) {
            val c = value[at]
            val escaped = when (c) {
                '"' -> "\\\""; '\\' -> "\\\\"; '\b' -> "\\b"; '\u000c' -> "\\f"
                '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> null
            }
            val invalid = when {
                c.isHighSurrogate() -> at + 1 == value.length || !value[at + 1].isLowSurrogate()
                c.isLowSurrogate() -> at == 0 || !value[at - 1].isHighSurrogate()
                else -> false
            }
            if (escaped != null || c.code < 32 || invalid) {
                text(value, start, at)
                when {
                    escaped != null -> text(escaped)
                    c.code < 32 -> unicode(c)
                    fingerprint -> unicode(c)
                    else -> {
                        require(!key) { "同步欄位名稱含無效 Unicode，本機資料已保留" }
                        char('?') // Native 1.8.0 UTF-8 replacement.
                    }
                }
                start = at + 1
            }
            at++
        }
        text(value, start, value.length); char('"')
    }
    private inline fun <T> array(values: Iterable<T>, write: (T) -> Unit) {
        char('['); var first = true
        for (value in values) { if (!first) char(','); first = false; write(value) }; char(']')
    }
    private inline fun <T> map(values: Map<String, T>, write: (T) -> Unit) {
        char('{'); var first = true
        for ((key, value) in values) { if (!first) char(','); first = false; quote(key, key = true); char(':'); write(value) }; char('}')
    }
    fun record(v: WorkspaceRecord) {
        text("{\"kind\":"); quote(v.kind); text(",\"id\":"); quote(v.id)
        text(",\"revision\":"); text(v.revision.toString()); text(",\"base_revision\":"); text(v.baseRevision.toString())
        text(",\"data\":"); element(v.data); text(",\"deleted\":"); text(v.deleted.toString())
        text(",\"origin_device\":"); quote(v.originDevice); text(",\"seq\":"); text(v.seq.toString()); char('}')
    }
    private fun secret(v: WorkspaceSecret) {
        text("{\"key\":"); quote(v.key); text(",\"fields\":")
        array(v.fields) { field ->
            text("{\"name\":"); quote(field.name); text(",\"query\":"); text(field.query.toString())
            text(",\"secret\":"); quote(field.secret); char('}')
        }; char('}')
    }
    fun exchange(v: WorkspaceExchange) {
        text("{\"protocol\":"); text(v.protocol.toString()); text(",\"cursor\":"); text(v.cursor.toString())
        text(",\"changes\":"); array(v.changes, ::record); text(",\"secrets\":"); map(v.secrets, ::secret)
        text(",\"fetch\":"); array(v.fetch, ::record); char('}')
    }
    fun reply(v: WorkspaceReply) {
        text("{\"protocol\":"); text(v.protocol.toString()); text(",\"workspace_id\":"); quote(v.workspaceId)
        text(",\"cursor\":"); text(v.cursor.toString()); text(",\"has_more\":"); text(v.hasMore.toString())
        text(",\"records\":"); array(v.records, ::record); text(",\"accepted\":"); array(v.accepted, ::record)
        text(",\"conflicts\":"); array(v.conflicts, ::record); text(",\"secrets\":"); map(v.secrets, ::secret); char('}')
    }
    fun element(value: JsonElement, depth: Int = 0) {
        require(depth <= 256) { "同步資料巢狀過深，本機資料已保留" }
        when (value) {
            JsonNull -> text("null")
            is JsonObject -> map(value) { element(it, depth + 1) }
            is JsonArray -> array(value) { element(it, depth + 1) }
            is JsonPrimitive -> if (value.isString) quote(value.content) else {
                require(value.content.length <= 4096) { "同步數值過長，本機資料已保留" }
                if (fingerprint) {
                    require(value.content == "true" || value.content == "false" || jsonNumber(value.content)) { "同步數值格式無效，本機資料已保留" }
                    text(value.content)
                } else text(WorkspaceJson.encodeToString(JsonElement.serializer(), value))
            }
        }
    }
    fun finish() { drain(); writer.flush() }
    private fun jsonNumber(value: String): Boolean {
        var i = 0
        if (i < value.length && value[i] == '-') i++
        if (i == value.length) return false
        if (value[i] == '0') i++ else {
            if (value[i] !in '1'..'9') return false
            while (i < value.length && value[i] in '0'..'9') i++
        }
        if (i < value.length && value[i] == '.') {
            i++; val start = i
            while (i < value.length && value[i] in '0'..'9') i++
            if (i == start) return false
        }
        if (i < value.length && (value[i] == 'e' || value[i] == 'E')) {
            i++
            if (i < value.length && (value[i] == '+' || value[i] == '-')) i++
            val start = i
            while (i < value.length && value[i] in '0'..'9') i++
            if (i == start) return false
        }
        return i == value.length
    }
}
