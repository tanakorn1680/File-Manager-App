package com.releasemanager.ui

import androidx.lifecycle.lifecycleScope
import com.releasemanager.R
import com.releasemanager.data.AppState
import com.releasemanager.data.TokenStore
import com.releasemanager.network.DownloadJobs
import com.releasemanager.network.GitHubApi
import com.releasemanager.service.Transfer
import com.releasemanager.service.TransferKind
import com.releasemanager.service.TransferManager
import com.releasemanager.service.TransferOutcome
import com.releasemanager.service.TransferProgress
import com.releasemanager.service.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The web's `.screen` sections; MainActivity shows exactly one at a time (web: showScreen()). */
enum class Screen { CONNECT, REPOS, FILES, EDITOR, UPLOAD, RELEASES, RELEASE_NEW, ACTIONS, HISTORY }

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
    lateinit var history: HistoryScreen

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

    // ── Background transfers (TransferService) ───────────────────────────────

    /** Per-transfer callbacks; they live and die with this Activity, the transfer itself does not. */
    private val transferHandlers = HashMap<Int, (Transfer) -> Unit>()

    private val transferListener = TransferManager.Listener { t ->
        val handler = transferHandlers[t.id]
        if (handler != null) {
            handler(t)
            if (!t.isRunning) transferHandlers.remove(t.id)
        }
    }

    fun attachTransfers() = TransferManager.addListener(transferListener)

    fun detachTransfers() {
        TransferManager.removeListener(transferListener)
        transferHandlers.clear()
    }

    /**
     * Starts [block] as an upload/download that keeps running in the background (foreground service with a
     * progress notification) even if the app is closed. [onUpdate] is called on the main thread with every
     * progress step and with the final state, but only while this Activity is still alive.
     * [block] must not touch the Activity, a View or [state]: copy what it needs into local values first.
     */
    fun startTransfer(
        kind: TransferKind,
        title: String,
        tag: String? = null,
        onUpdate: ((Transfer) -> Unit)? = null,
        block: suspend (TransferProgress) -> TransferOutcome
    ): Transfer {
        activity.requestNotificationPermission()
        val t = TransferManager.start(activity, kind, title, tag, block)
        if (onUpdate != null) transferHandlers[t.id] = onUpdate
        return t
    }

    /** A download: the result is announced with a toast (and always with a notification). */
    fun startDownload(title: String, block: suspend (TransferProgress) -> TransferOutcome): Transfer {
        toast.show("Downloading $title — progress is in the notification")
        return startTransfer(TransferKind.DOWNLOAD, title, null, { toastResult(it) }, block)
    }

    /**
     * "Download as ZIP" for a whole repository: asks for the ZIP layout, then where to save it, then
     * downloads [branch] in the background (progress notification, survives closing the app).
     */
    fun downloadRepoZip(repoFullName: String, branch: String) {
        val name = repoFullName.repoShortName()
        val ref = branch.ifEmpty { "main" }
        Dialogs.sheet(
            activity, "Download $name as ZIP",
            listOf(
                Dialogs.SheetItem(
                    "Files at the root", R.drawable.ic_archive,
                    sub = "No wrapper folder — extracts straight into any folder",
                    iconBoxBgRes = R.drawable.bg_edit_mode_icon,
                    iconTintRes = R.color.accent,
                    onClick = { saveRepoZip(repoFullName, ref, wrapFolder = false) }
                ),
                Dialogs.SheetItem(
                    "Same as GitHub", R.drawable.ic_folder,
                    sub = "Everything inside one owner-repo-commit folder",
                    iconBoxBgRes = R.drawable.bg_edit_mode_icon_stage,
                    iconTintRes = R.color.warning_text,
                    onClick = { saveRepoZip(repoFullName, ref, wrapFolder = true) }
                )
            )
        )
    }

    private fun saveRepoZip(repoFullName: String, ref: String, wrapFolder: Boolean) {
        val client = api ?: return
        val shortName = repoFullName.repoShortName()
        val fileName = "$shortName-${ref.replace('/', '-')}.zip"
        activity.createDocument(fileName, "application/zip") { uri ->
            if (uri == null) return@createDocument
            // Copied into locals: the transfer must not touch the Activity, a View or the app state
            val cr = activity.applicationContext.contentResolver
            val cacheDir = activity.cacheDir
            startDownload(fileName) { progress ->
                DownloadJobs.repoZip(client, cr, cacheDir, uri, repoFullName, ref, wrapFolder, fileName, progress)
            }
        }
    }

    /** Toast for the final state of a transfer; progress steps are ignored. */
    fun toastResult(t: Transfer) {
        when (t.state) {
            TransferState.SUCCESS -> toast.success(t.message ?: "Done")
            TransferState.FAILED -> toast.error(t.message ?: "Failed", 8000)
            TransferState.CANCELLED -> toast.show("Cancelled")
            TransferState.RUNNING -> Unit
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
