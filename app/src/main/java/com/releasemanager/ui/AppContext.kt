package com.releasemanager.ui

import androidx.lifecycle.lifecycleScope
import com.releasemanager.data.AppState
import com.releasemanager.data.TokenStore
import com.releasemanager.network.GitHubApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The web's `.screen` sections; MainActivity shows exactly one at a time (web: showScreen()). */
enum class Screen { CONNECT, REPOS, FILES, EDITOR, UPLOAD, RELEASES, RELEASE_NEW, ACTIONS }

/**
 * Everything the screen controllers share: the web's `state` object, the API client, the toast,
 * and the other screens (the web's global functions call each other the same way).
 */
class AppContext(
    val activity: MainActivity,
    val state: AppState,
    val tokenStore: TokenStore,
    val toast: AppToast
) {
    var api: GitHubApi? = null

    lateinit var connect: ConnectScreen
    lateinit var repos: ReposScreen
    lateinit var files: FilesScreen
    lateinit var editor: EditorScreen
    lateinit var upload: UploadScreen
    lateinit var releases: ReleasesScreen
    lateinit var releaseNew: ReleaseNewScreen
    lateinit var actions: ActionsScreen

    val scope: CoroutineScope get() = activity.lifecycleScope

    fun show(screen: Screen) = activity.showScreen(screen)

    /**
     * Launches [block]; an exception other than cancellation goes to [onError] (default: error toast)
     * instead of crashing the app. `finally` work belongs inside [block].
     */
    fun launchSafe(
        onError: (Exception) -> Unit = { toast.error(it.userMessage()) },
        block: suspend CoroutineScope.() -> Unit
    ): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    /** Forget the token and everything loaded with it, then go back to the token screen. */
    fun disconnect() {
        tokenStore.clearToken()
        api = null
        state.reset()
        repos.reset()
        connect.reset()
        show(Screen.CONNECT)
    }
}
