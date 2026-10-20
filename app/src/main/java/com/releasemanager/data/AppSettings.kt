package com.releasemanager.data

import android.content.Context

/** App-wide preferences that are not secrets (the token lives in [TokenStore]). */
object AppSettings {
    const val LANG_EN = "en"
    const val LANG_TH = "th"

    /** How an upload shows its progress: one bar per step (Clone, Copy, Push) or a single overall bar. */
    const val BARS_DETAILED = 3
    const val BARS_COMPACT = 1

    private const val FILE = "app_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_UPLOAD_BARS = "upload_progress_bars"

    fun language(context: Context): String =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANG_EN) ?: LANG_EN

    /** [BARS_DETAILED] (the default) or [BARS_COMPACT]. */
    fun uploadBars(context: Context): Int =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_UPLOAD_BARS, BARS_DETAILED)

    fun setUploadBars(context: Context, bars: Int) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_UPLOAD_BARS, if (bars == BARS_COMPACT) BARS_COMPACT else BARS_DETAILED).apply()
    }

    fun setLanguage(context: Context, code: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, code).apply()
    }
}
