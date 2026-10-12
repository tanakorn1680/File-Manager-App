package com.releasemanager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the GitHub Personal Access Token on-device, encrypted via a key
 * held in the Android Keystore (EncryptedSharedPreferences) rather than
 * plain SharedPreferences. This was a deliberate tradeoff the user chose:
 * convenience (enter the token once, reuse it) over re-entering it every
 * launch — encryption-at-rest narrows, but does not eliminate, the risk if
 * the device itself is lost or unlocked by someone else.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "release_manager_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun getLastRepo(): String? = prefs.getString(KEY_LAST_REPO, null)

    fun saveLastRepo(repo: String) {
        prefs.edit().putString(KEY_LAST_REPO, repo).apply()
    }

    companion object {
        private const val KEY_TOKEN = "github_token"
        private const val KEY_LAST_REPO = "last_repo"
    }
}
