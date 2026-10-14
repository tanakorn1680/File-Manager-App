package com.releasemanager.ui

import android.content.Context
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.releasemanager.data.AppSettings

/**
 * Thai / English switch. The app's texts are written in English at the place they are used;
 * [tr] looks the text up in [TH_STRINGS] when the language is Thai and returns it unchanged otherwise
 * (or when there is no entry). Entries containing %s are patterns: the parts matched by %s are
 * translated on their own and put back, so "Set to Private" works through "Set to %s".
 */
object I18n {

    @Volatile
    var lang: String = AppSettings.LANG_EN
        private set

    private var inited = false

    val isThai: Boolean get() = lang == AppSettings.LANG_TH

    /** Safe to call from every entry point (activities, the service); only the first call reads the setting. */
    fun init(context: Context) {
        if (inited) return
        lang = AppSettings.language(context)
        inited = true
    }

    fun setLanguage(context: Context, code: String) {
        AppSettings.setLanguage(context, code)
        lang = code
        inited = true
    }

    private class Pattern(val regex: Regex, val parts: List<String>, val target: String)

    private val exact: Map<String, String> by lazy { TH_STRINGS.filterKeys { !it.contains("%s") } }

    private val patterns: List<Pattern> by lazy {
        TH_STRINGS.filterKeys { it.contains("%s") }.map { (k, v) ->
            val pieces = k.split("%s")
            val rx = pieces.joinToString("(.+?)") { Regex.escape(it) }
            Pattern(Regex("^$rx$", RegexOption.DOT_MATCHES_ALL), pieces, v)
        }.sortedByDescending { p -> p.parts.sumOf { it.length } }
    }

    fun tr(s: String): String {
        if (!isThai || s.isEmpty()) return s
        exact[s]?.let { return it }
        for (p in patterns) {
            val m = p.regex.matchEntire(s) ?: continue
            var i = 1
            return Regex("%s").replace(p.target) { tr(m.groupValues[i++]) }
        }
        return s
    }

    /** Plain strings are translated; styled text (spans) is left as it is. */
    fun tr(s: CharSequence): CharSequence = if (s is String) tr(s) else s

    /** Translates the static texts of an inflated layout: labels, buttons, hints, content descriptions. */
    fun translateTree(root: View) {
        if (!isThai) return
        root.contentDescription?.let { root.contentDescription = tr(it.toString()) }
        if (root is EditText) {
            root.hint?.let { root.hint = tr(it.toString()) }
        } else if (root is TextView) {
            val t = root.text
            if (t != null && t.isNotEmpty() &&
                (t !is Spanned || t.getSpans(0, t.length, Any::class.java).isEmpty())
            ) {
                val plain = t.toString()
                val translated = tr(plain)
                if (translated != plain) root.text = translated
            }
            root.hint?.let { root.hint = tr(it.toString()) }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) translateTree(root.getChildAt(i))
        }
    }
}
