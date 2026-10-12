package com.releasemanager.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.ReleaseAsset
import com.releasemanager.data.TokenStore
import com.releasemanager.network.GitHubApi
import com.releasemanager.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ReleaseDetailActivity : AppCompatActivity() {

    private lateinit var api: GitHubApi
    private lateinit var repo: String
    private var releaseId: Long = -1

    private lateinit var dropZone: View
    private lateinit var uploadProgressGroup: View
    private lateinit var textUploadLabel: TextView
    private lateinit var progressUpload: ProgressBar
    private lateinit var btnCancelUpload: MaterialButton
    private lateinit var recyclerAssets: RecyclerView
    private lateinit var textEmptyAssets: TextView
    private lateinit var btnDeleteRelease: View
    private lateinit var appToast: AppToast

    private lateinit var assetAdapter: AssetAdapter
    private var uploadJob: Job? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) startUpload(uri)
    }

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

        dropZone.setOnClickListener { pickFileLauncher.launch("*/*") }
        btnCancelUpload.setOnClickListener { cancelUpload() }
        btnDeleteRelease.setOnClickListener { confirmDeleteRelease() }

        loadAssets()
    }

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
        val fileName = queryFileName(uri) ?: "upload_${System.currentTimeMillis()}"
        val size = queryFileSize(uri)
        if (size >= RELEASE_ASSET_LIMIT_BYTES) {
            appToast.error("\"$fileName\" is ${formatSize(size)} — GitHub accepts release files under 2 GiB.", 8000)
            return
        }
        val mimeType = contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

        dropZone.visibility = View.GONE
        uploadProgressGroup.visibility = View.VISIBLE
        progressUpload.progress = 0
        textUploadLabel.text = "Preparing $fileName…"

        uploadJob = lifecycleScope.launch {
            var tempFile: File? = null
            try {
                // GitHub's upload endpoint needs the exact length up front. When the file provider reports it,
                // the bytes are streamed straight from the picked file (nothing copied, nothing held in memory);
                // only when it does not is the file copied into the app cache first.
                val onProgress: (Long, Long) -> Unit = { sent, total ->
                    runOnUiThread {
                        val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
                        progressUpload.progress = pct
                        textUploadLabel.text = "Uploading $fileName… ${formatSize(sent)} / ${formatSize(total)}"
                    }
                }
                val asset = if (size > 0) {
                    // Size is known: stream straight from the picked file, no copy into app storage
                    textUploadLabel.text = "Uploading $fileName…"
                    api.uploadAssetStream(
                        repo, releaseId, fileName, mimeType, size,
                        { contentResolver.openInputStream(uri) ?: throw java.io.IOException("Could not open selected file") },
                        onProgress
                    )
                } else {
                    // The provider did not report a size (GitHub needs the exact length): copy first, then upload
                    textUploadLabel.text = "Copying $fileName into app storage…"
                    val copy = withContext(Dispatchers.IO) { copyUriToCacheFile(uri, fileName) }
                    tempFile = copy
                    textUploadLabel.text = "Uploading $fileName…"
                    api.uploadAsset(repo, releaseId, copy, mimeType, onProgress, fileName)
                }

                assetAdapter.addItem(asset)
                textEmptyAssets.visibility = View.GONE
                appToast.success("Uploaded")
            } catch (e: GitHubApiException) {
                appToast.error("Upload failed: ${e.message}", 5000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                appToast.show("Upload cancelled")
            } catch (e: Exception) {
                appToast.error("Upload failed: ${e.message}", 5000)
            } finally {
                tempFile?.delete()
                dropZone.visibility = View.VISIBLE
                uploadProgressGroup.visibility = View.GONE
                uploadJob = null
            }
        }
    }

    private fun cancelUpload() {
        uploadJob?.cancel()
    }

    private fun copyUriToCacheFile(uri: Uri, fileName: String): File {
        val outFile = File(cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        } ?: throw java.io.IOException("Could not open selected file")
        return outFile
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
