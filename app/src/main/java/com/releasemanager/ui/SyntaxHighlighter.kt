package com.releasemanager.ui

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * A small regex-based colourer for the in-app editor (no download needed to get highlighting).
 * Not a real lexer — just enough pattern matching to make comments / strings / numbers / keywords
 * stand out, in the app's own palette, for the file types people actually keep in a repo.
 */
object SyntaxHighlighter {

    enum class Lang { KOTLIN, JAVA, JS, JSON, XML, PYTHON, SHELL, YAML, MARKDOWN, CSS, CLIKE, NONE }

    /** Marker subclass so we can find + clear only the spans we added, not ones from elsewhere. */
    private class HLSpan(color: Int) : ForegroundColorSpan(color)

    fun languageFor(fileName: String): Lang {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts", "gradle" -> Lang.KOTLIN
            "java" -> Lang.JAVA
            "js", "jsx", "mjs", "cjs", "ts", "tsx" -> Lang.JS
            "json", "webmanifest" -> Lang.JSON
            "xml", "html", "htm", "svg" -> Lang.XML
            "py" -> Lang.PYTHON
            "sh", "bash", "zsh" -> Lang.SHELL
            "yml", "yaml" -> Lang.YAML
            "md", "markdown" -> Lang.MARKDOWN
            "css", "scss" -> Lang.CSS
            "c", "h", "cpp", "hpp", "cc", "cs", "m", "mm", "swift", "go", "rs", "php", "rb", "dart" -> Lang.CLIKE
            else -> Lang.NONE
        }
    }

    private val KEYWORDS: Map<Lang, Set<String>> = mapOf(
        Lang.KOTLIN to setOf(
            "fun", "val", "var", "if", "else", "for", "while", "do", "when", "class", "object",
            "interface", "return", "break", "continue", "import", "package", "private", "public",
            "protected", "internal", "override", "companion", "init", "this", "super", "null",
            "true", "false", "is", "as", "in", "try", "catch", "finally", "throw", "suspend",
            "const", "lateinit", "data", "sealed", "enum", "typealias", "by", "vararg"
        ),
        Lang.JAVA to setOf(
            "public", "private", "protected", "class", "interface", "extends", "implements",
            "static", "final", "void", "int", "long", "double", "float", "boolean", "char", "byte",
            "short", "new", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "import", "package", "try", "catch", "finally", "throw", "throws", "this",
            "super", "null", "true", "false", "enum", "abstract", "synchronized"
        ),
        Lang.JS to setOf(
            "function", "const", "let", "var", "if", "else", "for", "while", "do", "switch", "case",
            "break", "continue", "return", "import", "export", "default", "class", "extends", "new",
            "this", "super", "null", "undefined", "true", "false", "try", "catch", "finally", "throw",
            "async", "await", "typeof", "instanceof", "from", "of", "in"
        ),
        Lang.PYTHON to setOf(
            "def", "class", "if", "elif", "else", "for", "while", "try", "except", "finally",
            "import", "from", "as", "return", "yield", "break", "continue", "pass", "lambda",
            "with", "in", "is", "not", "and", "or", "None", "True", "False", "self", "raise",
            "global", "nonlocal"
        ),
        Lang.SHELL to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "function", "return",
            "export", "local", "case", "esac", "in", "echo", "exit"
        ),
        Lang.CLIKE to setOf(
            "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return",
            "class", "struct", "public", "private", "protected", "static", "const", "void", "int",
            "long", "double", "float", "char", "bool", "new", "delete", "import", "package", "func",
            "var", "let", "null", "nullptr", "true", "false", "try", "catch", "throw"
        )
    )

    /** Files bigger than this skip highlighting outright — not worth the main-thread regex pass. */
    private const val MAX_LEN = 200_000

    private val STRING_RE = Regex(""""(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'""")
    private val NUMBER_RE = Regex("""\b\d+(\.\d+)?[fFLl]?\b""")
    private val LINE_COMMENT_RE = Regex("""//[^\n]*""")
    private val BLOCK_COMMENT_RE = Regex("""/\*[\s\S]*?\*/""")
    private val HASH_COMMENT_RE = Regex("""#[^\n]*""")
    private val XML_COMMENT_RE = Regex("""<!--[\s\S]*?-->""")
    private val XML_TAG_RE = Regex("""</?[A-Za-z][\w.:-]*""")
    private val XML_ATTR_RE = Regex("""\s([\w:-]+)=["']""")
    private val JSON_KEY_RE = Regex(""""([^"\\]*)"\s*:""")
    private val YAML_KEY_RE = Regex("""(?m)^\s*([\w.-]+)\s*:""")
    private val MD_HEADER_RE = Regex("""(?m)^#{1,6}\s.*$""")
    private val MD_LINK_RE = Regex("""\[[^\]]*]\([^)]*\)""")
    private val MD_CODE_RE = Regex("""`[^`\n]*`""")
    private val CSS_SELECTOR_RE = Regex("""(?m)^\s*[.#]?[\w-]+(?=\s*\{)""")
    private val KEYWORD_RE = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /**
     * Re-colours [editable] in place for [lang]. [color] maps a category name ("comment", "string",
     * "number", "keyword", "tag", "attr") to a resolved theme colour int. Safe to call repeatedly —
     * it clears its own previous spans first, so it is meant to be re-run after every edit.
     */
    fun highlight(editable: Editable, lang: Lang, color: (String) -> Int) {
        for (s in editable.getSpans(0, editable.length, HLSpan::class.java)) editable.removeSpan(s)
        if (lang == Lang.NONE || editable.isEmpty() || editable.length > MAX_LEN) return

        val text = editable.toString()

        fun paint(range: IntRange, categoryColor: Int) {
            if (range.isEmpty()) return
            val start = range.first.coerceIn(0, editable.length)
            val end = (range.last + 1).coerceIn(start, editable.length)
            if (end > start) editable.setSpan(HLSpan(categoryColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (lang != Lang.MARKDOWN) {
            STRING_RE.findAll(text).forEach { paint(it.range, color("string")) }
            NUMBER_RE.findAll(text).forEach { paint(it.range, color("number")) }
        }

        when (lang) {
            Lang.XML -> {
                XML_COMMENT_RE.findAll(text).forEach { paint(it.range, color("comment")) }
                XML_TAG_RE.findAll(text).forEach { paint(it.range, color("tag")) }
                XML_ATTR_RE.findAll(text).forEach { m -> paint(m.groups[1]!!.range, color("attr")) }
            }
            Lang.JSON -> {
                JSON_KEY_RE.findAll(text).forEach { m -> paint(m.groups[1]!!.range, color("attr")) }
            }
            Lang.YAML -> {
                HASH_COMMENT_RE.findAll(text).forEach { paint(it.range, color("comment")) }
                YAML_KEY_RE.findAll(text).forEach { m -> paint(m.groups[1]!!.range, color("attr")) }
            }
            Lang.MARKDOWN -> {
                MD_HEADER_RE.findAll(text).forEach { paint(it.range, color("keyword")) }
                MD_LINK_RE.findAll(text).forEach { paint(it.range, color("tag")) }
                MD_CODE_RE.findAll(text).forEach { paint(it.range, color("string")) }
            }
            Lang.SHELL, Lang.PYTHON -> {
                HASH_COMMENT_RE.findAll(text).forEach { paint(it.range, color("comment")) }
            }
            Lang.CSS -> {
                BLOCK_COMMENT_RE.findAll(text).forEach { paint(it.range, color("comment")) }
                CSS_SELECTOR_RE.findAll(text).forEach { paint(it.range, color("tag")) }
            }
            Lang.KOTLIN, Lang.JAVA, Lang.JS, Lang.CLIKE -> {
                LINE_COMMENT_RE.findAll(text).forEach { paint(it.range, color("comment")) }
                BLOCK_COMMENT_RE.findAll(text).forEach { paint(it.range, color("comment")) }
            }
            Lang.NONE -> Unit
        }

        KEYWORDS[lang]?.let { words ->
            KEYWORD_RE.findAll(text).forEach { m -> if (m.value in words) paint(m.range, color("keyword")) }
        }
    }
}
