package com.releasemanager.data

import android.content.Context

/** App-wide preferences that are not secrets (the token lives in [TokenStore]). */
object AppSettings {
    const val LANG_EN = "en"
    const val LANG_TH = "th"

    private const val FILE = "app_settings"
    private const val KEY_LANGUAGE = "language"

    fun language(context: Context): String =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANG_EN) ?: LANG_EN

    fun setLanguage(context: Context, code: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, code).apply()
    }
}
