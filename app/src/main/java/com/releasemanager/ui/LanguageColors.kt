package com.releasemanager.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** GitHub's language colours for the languages this app's users meet most; others get a stable colour from their name. */
private val LANGUAGE_COLORS = mapOf(
    "HTML" to "#E34C26",
    "CSS" to "#663399",
    "SCSS" to "#C6538C",
    "JavaScript" to "#F1E05A",
    "TypeScript" to "#3178C6",
    "Kotlin" to "#A97BFF",
    "Java" to "#B07219",
    "Python" to "#3572A5",
    "Lua" to "#4B4BD1",
    "PLpgSQL" to "#336790",
    "SQL" to "#E38C00",
    "Shell" to "#89E051",
    "Batchfile" to "#C1F12E",
    "C" to "#8A8A8A",
    "C++" to "#F34B7D",
    "C#" to "#178600",
    "Go" to "#00ADD8",
    "Rust" to "#DEA584",
    "Swift" to "#F05138",
    "Dart" to "#00B4AB",
    "PHP" to "#4F5D95",
    "Ruby" to "#CC342D",
    "Vue" to "#41B883",
    "Dockerfile" to "#384D54",
    "Makefile" to "#427819",
    "GLSL" to "#5686A5"
)

fun languageColor(name: String): Int {
    LANGUAGE_COLORS[name]?.let { return Color.parseColor(it) }
    val hue = ((name.hashCode().toLong() and 0x7fffffffL) % 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
}

fun ovalDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}
