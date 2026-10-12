package com.releasemanager.data

import android.content.Context

/**
 * Names the person gave to versions (commits) of a repository, kept on the device.
 * Plain SharedPreferences: a name is not a secret, and it must survive "Disconnect".
 * Keyed by repo + commit sha, so a name stays with its version whatever branch is open.
 */
class HistoryNames(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("history_names", Context.MODE_PRIVATE)

    private fun key(repo: String, sha: String) = "${repo.lowercase()}@$sha"

    fun get(repo: String, sha: String): String? =
        prefs.getString(key(repo, sha), null)?.takeIf { it.isNotBlank() }

    /** An empty [name] removes the name. */
    fun set(repo: String, sha: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) clear(repo, sha) else prefs.edit().putString(key(repo, sha), trimmed).apply()
    }

    fun clear(repo: String, sha: String) {
        prefs.edit().remove(key(repo, sha)).apply()
    }
}
