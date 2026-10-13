package com.releasemanager.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.ReleaseAsset
import com.releasemanager.data.TokenStore
import com.releasemanager.network.GitHubApi
import com.releasemanager.network.UploadJobs
import com.releasemanager.network.UriAccess
import com.releasemanager.service.Transfer
import com.releasemanager.service.TransferKind
import com.releasemanager.service.TransferManager
import com.releasemanager.service.TransferState
import com.releasemanager.util.formatSize
import kotlinx.coroutines.launch

class ReleaseDetailActivity : AppCompatActivity() {

    private lateinit var api: GitHubApi
    private lateinit var repo: String
    private var releaseId: Long = -1

    private lateinit var dropZone: View
    private lateinit var uploadProgressGroup: View
    private lateinit var textUploadLabel: TextView
    private lateinit var progressUpload: ProgressBar
    private lateinit var smoothUpload: SmoothProgress
    private lateinit var btnCancelUpload: MaterialButton
    private lateinit var recyclerAssets: RecyclerView
    private lateinit var textEmptyAssets: TextView
    private lateinit var btnDeleteRelease: View
    private lateinit var appToast: AppToast

    private lateinit var assetAdapter: AssetAdapter

    /**
     * The upload runs in TransferService (background, progress in the notification), so it carries on when this
     * screen is left or the app is closed. This is the one this screen is showing, if any.
     */
    private var uploadTransfer: Transfer? = null
    private val transferListener = TransferManager.Listener { t -> onTransferUpdate(t) }

    // OpenDocument rather than GetContent: only its Uri can be kept readable after the app is closed
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) startUpload(uri)
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_release_detail)

        repo = intent.getStringExtra(EXTRA_REPO) ?: run { finish(); return }
        releaseId = intent.getLongExtra(EXTRA_RELEASE_ID, -1)
        val tagName = intent.getStringExtra(EXTRA_TAG_NAME).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME)
        val body = intent.getStringExtra(EXTRA_BODY)

        val token = TokenStore(this).getToken()
        if (token.isNullOrBlank()) { finish(); return }
        api = GitHubApi(token)

        appToast = AppToast(findViewById(R.id.toastView))

        findViewById<TextView>(R.id.textTitle).text = tagName
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.textTag).text = tagName
        findViewById<View>(R.id.textDraft).visibility =
            if (intent.getBooleanExtra(EXTRA_DRAFT, false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.textPrerelease).visibility =
            if (intent.getBooleanExtra(EXTRA_PRERELEASE, false)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.textName).text = name?.ifBlank { tagName } ?: tagName
        val textBody = findViewById<TextView>(R.id.textBody)
        if (!body.isNullOrBlank()) {
            textBody.text = body
            textBody.visibility = View.VISIBLE
        }

        dropZone = findViewById(R.id.dropZone)
        uploadProgressGroup = findViewById(R.id.uploadProgressGroup)
        textUploadLabel = findViewById(R.id.textUploadLabel)
        progressUpload = findViewById(R.id.progressUpload)
        smoothUpload = SmoothProgress(progressUpload)
        btnCancelUpload = findViewById(R.id.btnCancelUpload)
        recyclerAssets = findViewById(R.id.recyclerAssets)
        textEmptyAssets = findViewById(R.id.textEmptyAssets)
        btnDeleteRelease = findViewById(R.id.btnDeleteRelease)

        assetAdapter = AssetAdapter(
            onOpen = { asset -> openAsset(asset) },
            onDelete = { asset, position -> confirmDeleteAsset(asset, position) }
        )
        recyclerAssets.layoutManager = LinearLayoutManager(this)
        recyclerAssets.adapter = assetAdapter

        dropZone.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        btnCancelUpload.setOnClickListener { cancelUpload() }
        btnDeleteRelease.setOnClickListener { confirmDeleteRelease() }

        loadAssets()

        // Back on a release whose upload is still running in the background: show its progress again
        TransferManager.addListener(transferListener)
        TransferManager.find(uploadTag())?.let { t ->
            uploadTransfer = t
            dropZone.visibility = View.GONE
            uploadProgressGroup.visibility = View.VISIBLE
            showProgress(t)
        }
    }

    override fun onDestroy() {
        // The upload itself belongs to TransferService and is not affected
        TransferManager.removeListener(transferListener)
        super.onDestroy()
    }

    private fun uploadTag() = "release:$repo:$releaseId"

    private fun loadAssets() {
        lifecycleScope.launch {
            try {
                val releases = api.listReleases(repo)
                val release = releases.firstOrNull { it.id == releaseId }
                val assets = release?.assets ?: emptyList()
                assetAdapter.submitList(assets)
                textEmptyAssets.visibility = if (assets.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                appToast.error("Could not load assets: ${e.message}")
            }
        }
    }

    // ── Upload ───────────────────────────────────────────────────────────

    private fun startUpload(uri: Uri) {
        // Keep the Uri readable after the app is closed; the background upload gives it back when done
        UriAccess.persist(contentResolver, uri, write = false)

        val fileName = queryFileName(uri) ?: "upload_${System.currentTimeMillis()}"
        val size = queryFileSize(uri)
        if (size >= RELEASE_ASSET_LIMIT_BYTES) {
            UriAccess.release(contentResolver, uri)
            appToast.error("\"$fileName\" is ${formatSize(size)} — GitHub accepts release files under 2 GiB.", 8000)
            return
        }
        val mimeType = contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

        dropZone.visibility = View.GONE
        uploadProgressGroup.visibility = View.VISIBLE
        smoothUpload.reset()
        textUploadLabel.text = "Preparing $fileName…"

        requestNotificationPermission()

        // Plain copies of what the background work needs: it must not reach into this Activity
        val apiRef = api
        val repoRef = repo
        val releaseIdRef = releaseId
        val cr = applicationContext.contentResolver
        val cache = cacheDir

        // GitHub's upload endpoint needs the exact length up front. When the file provider reports it, the bytes
        // are streamed straight from the picked file (nothing copied, nothing held in memory); only when it does
        // not is the file copied into the app cache first.
        uploadTransfer = TransferManager.start(this, TransferKind.UPLOAD, fileName, uploadTag()) { progress ->
            UploadJobs.releaseAsset(apiRef, cr, cache, repoRef, releaseIdRef, uri, fileName, mimeType, size, progress)
        }
    }

    private fun showProgress(t: Transfer) {
        textUploadLabel.text = if (t.label.isBlank()) "Uploading ${t.title}…" else "${t.title}: ${t.label}"
        if (t.fraction >= 0f) smoothUpload.set(t.fraction)
    }

    /** Progress and the final result of the background upload, while this screen is open. */
    private fun onTransferUpdate(t: Transfer) {
        if (t.id != uploadTransfer?.id) return
        if (t.isRunning) {
            showProgress(t)
            return
        }

        uploadTransfer = null
        dropZone.visibility = View.VISIBLE
        uploadProgressGroup.visibility = View.GONE
        when (t.state) {
            TransferState.SUCCESS -> {
                (t.result as? ReleaseAsset)?.let { asset ->
                    assetAdapter.addItem(asset)
                    textEmptyAssets.visibility = View.GONE
                }
                appToast.success("Uploaded")
            }
            TransferState.CANCELLED -> appToast.show("Upload cancelled")
            else -> appToast.error("Upload failed: ${t.message}", 5000)
        }
    }

    private fun cancelUpload() {
        uploadTransfer?.let { TransferManager.cancel(it.id) }
    }

    /** Android 13+: without this permission the progress notification of a background upload is not shown. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        BatteryExemption.askOnce(this)
    }

    /** Exact size in bytes as the file provider reports it, or -1 when it does not. */
    private fun queryFileSize(uri: Uri): Long {
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                size = cursor.getLong(idx)
            }
        }
        return size
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    // ── Delete ───────────────────────────────────────────────────────────

    private fun confirmDeleteAsset(asset: ReleaseAsset, position: Int) {
        Dialogs.confirm(
            this,
            title = "Delete file?",
            body = "Delete \"${asset.name}\" from this release?",
            positive = "Delete"
        ) { deleteAsset(asset, position) }
    }

    private fun deleteAsset(asset: ReleaseAsset, position: Int) {
        lifecycleScope.launch {
            try {
                api.deleteAsset(repo, asset.id)
                assetAdapter.removeAt(position)
                if (assetAdapter.isEmpty()) textEmptyAssets.visibility = View.VISIBLE
                appToast.success("Deleted")
            } catch (e: Exception) {
                appToast.error("Could not delete: ${e.message}")
            }
        }
    }

    private fun confirmDeleteRelease() {
        Dialogs.confirm(
            this,
            title = "Delete release?",
            body = "Delete this release and all its assets? This cannot be undone.",
            positive = "Delete"
        ) { deleteRelease() }
    }

    private fun deleteRelease() {
        lifecycleScope.launch {
            try {
                api.deleteRelease(repo, releaseId)
                appToast.success("Release deleted")
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                appToast.error("Could not delete release: ${e.message}")
            }
        }
    }

    /** Web: the asset name is a link to its download URL. */
    private fun openAsset(asset: ReleaseAsset) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(asset.browserDownloadUrl)))
        } catch (e: ActivityNotFoundException) {
            appToast.error("No app can open this link")
        }
    }

    companion object {
        const val EXTRA_REPO = "extra_repo"
        const val EXTRA_RELEASE_ID = "extra_release_id"
        const val EXTRA_TAG_NAME = "extra_tag_name"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_BODY = "extra_body"
        const val EXTRA_DRAFT = "extra_draft"
        const val EXTRA_PRERELEASE = "extra_prerelease"

        /** GitHub rejects release assets of 2 GiB or more. */
        const val RELEASE_ASSET_LIMIT_BYTES = 2L * 1024 * 1024 * 1024
    }
}
