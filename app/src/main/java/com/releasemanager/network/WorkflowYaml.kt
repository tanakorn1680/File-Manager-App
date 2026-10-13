package com.releasemanager.network

/** One `workflow_dispatch` input, as GitHub shows it in the "Run workflow" form. */
class WorkflowInput(
    val name: String,
    val description: String,
    val required: Boolean,
    /** string | boolean | choice | number | environment (GitHub's types); anything else behaves like string. */
    val type: String,
    val default: String?,
    val options: List<String>
)

/** What a workflow file says about starting it by hand. */
class DispatchInfo(val manual: Boolean, val inputs: List<WorkflowInput>)

/**
 * GitHub's REST API lists workflows but not their inputs: those live only in the workflow file
 * (`on: workflow_dispatch: inputs:`), and GitHub's own page reads them from there. This reads just
 * enough YAML for that: nested maps, lists, quoted / flow / block scalars, comments.
 */
object WorkflowYaml {

    private class Line(val indent: Int, val text: String, val raw: String)

    fun dispatchInfo(yaml: String): DispatchInfo {
        val parsed = try {
            Parser(yaml).parse()
        } catch (e: RuntimeException) {
            null
        }
        val root = parsed as? Map<*, *> ?: return DispatchInfo(false, emptyList())

        val on = root["on"] ?: root["true"] ?: return DispatchInfo(false, emptyList())
        val dispatch: Any? = when (on) {
            is String -> if (on == "workflow_dispatch") emptyMap<String, Any?>() else return DispatchInfo(false, emptyList())
            is List<*> -> if (on.contains("workflow_dispatch")) emptyMap<String, Any?>() else return DispatchInfo(false, emptyList())
            is Map<*, *> -> if (on.containsKey("workflow_dispatch")) on["workflow_dispatch"] else return DispatchInfo(false, emptyList())
            else -> return DispatchInfo(false, emptyList())
        }

        val inputsMap = (dispatch as? Map<*, *>)?.get("inputs") as? Map<*, *> ?: return DispatchInfo(true, emptyList())
        val inputs = inputsMap.entries.mapNotNull { (k, v) ->
            val name = k as? String ?: return@mapNotNull null
            val def = v as? Map<*, *> ?: emptyMap<String, Any?>()
            val type = (def["type"] as? String)?.lowercase() ?: "string"
            val options = (def["options"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            WorkflowInput(
                name = name,
                description = (def["description"] as? String).orEmpty(),
                required = (def["required"] as? String)?.lowercase() == "true",
                type = type,
                default = def["default"] as? String,
                options = options
            )
        }
        return DispatchInfo(true, inputs)
    }

    private class Parser(source: String) {
        private val lines: MutableList<Line> = ArrayList()
        private var pos = 0

        init {
            for (raw in source.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val text = stripComment(raw).trimEnd()
                if (text.isBlank() || text.trim() == "---" || text.trim() == "...") continue
                if (text.trimStart().startsWith("%")) continue
                lines.add(Line(text.length - text.trimStart().length, text.trim(), raw))
            }
        }

        fun parse(): Any? = if (lines.isEmpty()) null else block(lines[0].indent)

        private fun isSeq(l: Line) = l.text == "-" || l.text.startsWith("- ")

        private fun block(indent: Int): Any? =
            if (isSeq(lines[pos])) sequence(indent) else mapping(indent)

        private fun mapping(indent: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (pos < lines.size) {
                val l = lines[pos]
                if (l.indent != indent || isSeq(l)) break
                val colon = keyEnd(l.text)
                if (colon < 0) throw IllegalStateException("not a mapping line: ${l.text}")
                val key = scalar(l.text.substring(0, colon).trim())
                val rest = l.text.substring(colon + 1).trim()
                pos++
                map[key] = when {
                    rest.isEmpty() -> {
                        val next = lines.getOrNull(pos)
                        when {
                            next == null -> null
                            next.indent > indent -> block(next.indent)
                            // `key:` followed by a list at the same indent is valid YAML
                            next.indent == indent && isSeq(next) -> sequence(indent)
                            else -> null
                        }
                    }
                    rest[0] == '|' || rest[0] == '>' -> blockScalar(indent, folded = rest[0] == '>')
                    else -> value(rest)
                }
            }
            return map
        }

        private fun sequence(indent: Int): List<Any?> {
            val list = ArrayList<Any?>()
            while (pos < lines.size) {
                val l = lines[pos]
                if (l.indent != indent || !isSeq(l)) break
                val rest = l.text.substring(1).trim()
                if (rest.isEmpty()) {
                    pos++
                    val next = lines.getOrNull(pos)
                    list.add(if (next != null && next.indent > indent) block(next.indent) else null)
                } else if (looksLikeMapping(rest)) {
                    // "- name: x" — the mapping starts right after the dash, so re-seat this line there
                    val offset = l.text.length - l.text.substring(1).trimStart().length
                    lines[pos] = Line(indent + offset, rest, l.raw)
                    list.add(mapping(indent + offset))
                } else {
                    pos++
                    list.add(value(rest))
                }
            }
            return list
        }

        /** `|` and `>` blocks: every following line indented deeper than the key belongs to the text. */
        private fun blockScalar(indent: Int, folded: Boolean): String {
            val parts = ArrayList<String>()
            while (pos < lines.size && lines[pos].indent > indent) {
                parts.add(lines[pos].raw.trim())
                pos++
            }
            return parts.joinToString(if (folded) " " else "\n")
        }

        private fun looksLikeMapping(s: String): Boolean {
            if (s.startsWith("[") || s.startsWith("{")) return false
            return keyEnd(s) >= 0
        }

        private fun value(s: String): Any? {
            val t = s.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                val inner = t.substring(1, t.length - 1)
                return if (inner.isBlank()) emptyList<Any?>() else splitTop(inner).map { value(it) }
            }
            if (t.startsWith("{") && t.endsWith("}")) {
                val inner = t.substring(1, t.length - 1)
                val map = LinkedHashMap<String, Any?>()
                if (inner.isNotBlank()) {
                    for (part in splitTop(inner)) {
                        val c = keyEnd(part)
                        if (c < 0) map[scalar(part.trim())] = null
                        else map[scalar(part.substring(0, c).trim())] = value(part.substring(c + 1))
                    }
                }
                return map
            }
            return scalar(t)
        }

        companion object {
            /** Index of the `:` that ends a key (followed by a space or the end), skipping quoted text; -1 if none. */
            fun keyEnd(s: String): Int {
                var quote = 0.toChar()
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (quote != 0.toChar()) {
                        if (c == '\\' && quote == '"') i++
                        else if (c == quote) quote = 0.toChar()
                    } else if (c == '"' || c == '\'') {
                        if (i == 0 || s[i - 1] == ' ' || s[i - 1] == '{' || s[i - 1] == '[' || s[i - 1] == ',') quote = c
                    } else if (c == ':' && (i + 1 == s.length || s[i + 1] == ' ')) {
                        return i
                    }
                    i++
                }
                return -1
            }

            /** Splits "a, b, 'c, d'" at the commas that are not inside quotes or nested brackets. */
            fun splitTop(s: String): List<String> {
                val out = ArrayList<String>()
                val cur = StringBuilder()
                var depth = 0
                var quote = 0.toChar()
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (quote != 0.toChar()) {
                        cur.append(c)
                        if (c == '\\' && quote == '"' && i + 1 < s.length) { cur.append(s[i + 1]); i++ }
                        else if (c == quote) quote = 0.toChar()
                    } else when (c) {
                        '"', '\'' -> { quote = c; cur.append(c) }
                        '[', '{' -> { depth++; cur.append(c) }
                        ']', '}' -> { depth--; cur.append(c) }
                        ',' -> if (depth == 0) { out.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
                        else -> cur.append(c)
                    }
                    i++
                }
                if (cur.isNotBlank()) out.add(cur.toString().trim())
                return out
            }

            /** Removes a trailing `# comment` (a `#` at the start or after whitespace, outside quotes). */
            fun stripComment(line: String): String {
                var quote = 0.toChar()
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    if (quote != 0.toChar()) {
                        if (c == '\\' && quote == '"') i++
                        else if (c == quote) quote = 0.toChar()
                    } else if ((c == '"' || c == '\'') && (i == 0 || " [{,:-".indexOf(line[i - 1]) >= 0)) {
                        quote = c
                    } else if (c == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t')) {
                        return line.substring(0, i)
                    }
                    i++
                }
                return line
            }

            /** A plain or quoted scalar as text ("true", 'it''s', "a\"b" …). */
            fun scalar(s: String): String {
                val t = s.trim()
                if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
                    val body = t.substring(1, t.length - 1)
                    val sb = StringBuilder()
                    var i = 0
                    while (i < body.length) {
                        val c = body[i]
                        if (c == '\\' && i + 1 < body.length) {
                            i++
                            when (val n = body[i]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                else -> sb.append(n)
                            }
                        } else {
                            sb.append(c)
                        }
                        i++
                    }
                    return sb.toString()
                }
                if (t.length >= 2 && t.first() == '\'' && t.last() == '\'') {
                    return t.substring(1, t.length - 1).replace("''", "'")
                }
                return t
            }
        }
    }
}
