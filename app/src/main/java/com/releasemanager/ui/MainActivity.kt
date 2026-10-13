package com.releasemanager.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.releasemanager.R
import com.releasemanager.data.AppState
import com.releasemanager.data.TokenStore
import com.releasemanager.network.UriAccess

/**
 * Single-activity host. Every web `.screen` is an <include>d layout in activity_main.xml with its
 * own controller class; [showScreen] shows one and hides the rest (web: showScreen()).
 * The release detail page stays a separate activity (ReleaseDetailActivity).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ctx: AppContext
    private val roots = mutableMapOf<Screen, View>()

    var current: Screen = Screen.CONNECT
        private set

    // ── System file pickers (Storage Access Framework) ───────────────────────

    private class CreateDocumentRequest(val name: String, val mime: String)

    /** Like ActivityResultContracts.CreateDocument, but with the MIME type chosen per request. */
    private class CreateDocument : ActivityResultContract<CreateDocumentRequest, Uri?>() {
        override fun createIntent(context: Context, input: CreateDocumentRequest): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.mime)
                .putExtra(Intent.EXTRA_TITLE, input.name)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    private var pickFilesCallback: ((List<Uri>) -> Unit)? = null
    private var pickZipCallback: ((Uri?) -> Unit)? = null
    private var createDocumentCallback: ((Uri?) -> Unit)? = null

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val callback = pickFilesCallback
        pickFilesCallback = null
        callback?.invoke(uris)
    }

    private val pickZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val callback = pickZipCallback
        pickZipCallback = null
        callback?.invoke(uri)
    }

    private val createDocumentLauncher = registerForActivityResult(CreateDocument()) { uri ->
        val callback = createDocumentCallback
        createDocumentCallback = null
        callback?.invoke(uri)
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android 13+: without this permission the progress notification of a background transfer is not shown. */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // The Uris the pickers return are only readable while this Activity lives, unless a persistable grant is
    // taken — and a transfer must keep reading/writing after the app was closed, so every picked Uri gets one
    // here (the transfer gives it back when it ends).

    /** Upload Files: any files, several at once. */
    fun pickFiles(onResult: (List<Uri>) -> Unit) {
        pickFilesCallback = { uris ->
            uris.forEach { UriAccess.persist(contentResolver, it, write = false) }
            onResult(uris)
        }
        pickFilesLauncher.launch(arrayOf("*/*"))
    }

    /** Upload ZIP: one document (some providers label ZIPs as octet-stream, so that is allowed too). */
    fun pickZip(onResult: (Uri?) -> Unit) {
        pickZipCallback = { uri ->
            if (uri != null) UriAccess.persist(contentResolver, uri, write = false)
            onResult(uri)
        }
        pickZipLauncher.launch(
            arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream")
        )
    }

    /** Download: the user chooses where the file is saved; [onResult] gets null when they cancel. */
    fun createDocument(name: String, mime: String, onResult: (Uri?) -> Unit) {
        createDocumentCallback = { uri ->
            if (uri != null) UriAccess.persist(contentResolver, uri, write = true)
            onResult(uri)
        }
        createDocumentLauncher.launch(CreateDocumentRequest(name, mime))
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ctx = AppContext(
            activity = this,
            state = AppState(),
            tokenStore = TokenStore(this),
            toast = AppToast(findViewById<TextView>(R.id.toastView))
        )

        roots[Screen.CONNECT] = findViewById(R.id.screenConnect)
        roots[Screen.REPOS] = findViewById(R.id.screenRepos)
        roots[Screen.FILES] = findViewById(R.id.screenFiles)
        roots[Screen.EDITOR] = findViewById(R.id.screenEditor)
        roots[Screen.UPLOAD] = findViewById(R.id.screenUpload)
        roots[Screen.RELEASES] = findViewById(R.id.screenReleases)
        roots[Screen.RELEASE_NEW] = findViewById(R.id.screenReleaseNew)
        roots[Screen.ACTIONS] = findViewById(R.id.screenActions)
        roots[Screen.HISTORY] = findViewById(R.id.screenHistory)

        ctx.connect = ConnectScreen(ctx)
        ctx.repos = ReposScreen(ctx)
        ctx.files = FilesScreen(ctx)
        ctx.editor = EditorScreen(ctx)
        ctx.upload = UploadScreen(ctx)
        ctx.releases = ReleasesScreen(ctx)
        ctx.releaseNew = ReleaseNewScreen(ctx)
        ctx.actions = ActionsScreen(ctx)
        ctx.history = HistoryScreen(ctx)

        showScreen(Screen.CONNECT)
        ctx.attachTransfers()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        // A token saved earlier: connect straight away
        ctx.connect.autoConnect()
    }

    override fun onDestroy() {
        // Running uploads/downloads belong to TransferService and carry on; only this screen stops listening
        ctx.detachTransfers()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Back from ReleaseDetailActivity (a release or asset may have been deleted/uploaded there):
        // the web reloads the list whenever you come back from the detail page
        if (current == Screen.RELEASES) ctx.releases.load()
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    fun showScreen(screen: Screen) {
        current = screen
        for ((s, view) in roots) {
            view.visibility = if (s == screen) View.VISIBLE else View.GONE
        }
        window.decorView.hideKeyboard()
    }

    /** System Back = the on-screen back arrow of the visible screen. */
    private fun handleBack() {
        when (current) {
            Screen.CONNECT, Screen.REPOS -> finish()
            Screen.FILES -> ctx.files.onBack()
            Screen.EDITOR -> ctx.editor.onBack()
            Screen.UPLOAD -> showScreen(Screen.FILES)
            Screen.RELEASES -> showScreen(Screen.FILES)
            Screen.RELEASE_NEW -> showScreen(Screen.RELEASES)
            Screen.ACTIONS -> showScreen(Screen.FILES)
            Screen.HISTORY -> showScreen(Screen.FILES)
        }
    }
}
