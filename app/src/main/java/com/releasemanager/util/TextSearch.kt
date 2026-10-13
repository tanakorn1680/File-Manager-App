package com.releasemanager.util

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The three toggles of the editor's find bar. */
data class SearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false
)

/** The query compiled once: usable, nothing typed yet, or a regular expression that does not parse. */
sealed class CompiledSearch {
    class Ok(val pattern: Pattern) : CompiledSearch()
    object Empty : CompiledSearch()
    class Invalid(val reason: String) : CompiledSearch()
}

/** Find / replace logic for the text editor, kept free of any Android view so it stays simple to reason about. */
object TextSearch {

    /** More matches than this are not listed (the count then shows "N+"). */
    const val MAX_MATCHES = 20_000

    /** Offsets of every match, in order. */
    class Matches(val starts: List<Int>, val ends: List<Int>, val truncated: Boolean) {
        val count: Int get() = starts.size

        companion object {
            val EMPTY = Matches(emptyList(), emptyList(), false)
        }
    }

    class ReplaceResult(val text: String, val count: Int)

    fun compile(query: String, options: SearchOptions): CompiledSearch {
        if (query.isEmpty()) return CompiledSearch.Empty
        var source = if (options.regex) query else Pattern.quote(query)
        if (options.wholeWord) {
            // A "word" is letters (incl. Thai and its vowel / tone marks), digits and underscore
            val word = "[\\p{L}\\p{M}\\p{N}_]"
            source = "(?<!$word)(?:$source)(?!$word)"
        }
        var flags = Pattern.MULTILINE // ^ and $ mean start / end of a LINE, as in a code editor
        if (!options.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return try {
            CompiledSearch.Ok(Pattern.compile(source, flags))
        } catch (e: PatternSyntaxException) {
            CompiledSearch.Invalid(e.description ?: "Invalid pattern")
        }
    }

    /** Every non-empty match of [pattern] in [text]. */
    fun findAll(text: String, pattern: Pattern): Matches {
        val m = pattern.matcher(text)
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var truncated = false
        while (m.find()) {
            if (m.end() == m.start()) continue // an empty match (e.g. "a*") is nothing to select
            if (starts.size >= MAX_MATCHES) {
                truncated = true
                break
            }
            starts.add(m.start())
            ends.add(m.end())
        }
        return Matches(starts, ends, truncated)
    }

    /** Index of the first match starting at or after [offset]; equals the match count when there is none. */
    fun indexAtOrAfter(starts: List<Int>, offset: Int): Int {
        val i = starts.binarySearch(offset)
        return if (i >= 0) i else -i - 1
    }

    /**
     * What the match at [start, end) is replaced with. In regex mode the replacement may use groups
     * ($1) and escapes; otherwise it is taken literally. Throws a RuntimeException for a bad group reference.
     */
    fun replacementFor(text: String, pattern: Pattern, start: Int, end: Int, replacement: String, regex: Boolean): String {
        if (!regex) return replacement
        val m = pattern.matcher(text)
        if (!m.find(start) || m.start() != start || m.end() != end) return replacement
        val out = StringBuffer()
        m.appendReplacement(out, replacement) // appends text[0, start) first, then the expanded replacement
        return out.substring(start)
    }

    /** Replaces every non-empty match. Throws a RuntimeException for a bad group reference (regex mode). */
    fun replaceAll(text: String, pattern: Pattern, replacement: String, regex: Boolean): ReplaceResult {
        val rep = if (regex) replacement else Matcher.quoteReplacement(replacement)
        val m = pattern.matcher(text)
        val out = StringBuffer(text.length + 16)
        var count = 0
        while (m.find()) {
            if (m.end() == m.start()) continue
            m.appendReplacement(out, rep)
            count++
        }
        m.appendTail(out)
        return ReplaceResult(out.toString(), count)
    }
}
