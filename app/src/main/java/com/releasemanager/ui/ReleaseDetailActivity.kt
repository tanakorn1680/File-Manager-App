package com.releasemanager.ui


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
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var btnDeleteRelease: MaterialButton

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

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = tagName
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.textTag).text = tagName
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

        assetAdapter = AssetAdapter { asset, position -> confirmDeleteAsset(asset, position) }
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
                toast("Could not load assets: ${e.message}")
            }
        }
    }

    // ── Upload ───────────────────────────────────────────────────────────

    private fun startUpload(uri: Uri) {
        val fileName = queryFileName(uri) ?: "upload_${System.currentTimeMillis()}"
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
                // GitHub's upload endpoint (and OkHttp's streaming RequestBody)
                // needs a real file with a known length — a content:// Uri's
                // stream doesn't reliably expose that. So the picked file is
                // first copied into this app's own cache dir, then streamed
                // from there. This copy itself is sequential disk I/O (not
                // held in memory at once), so it stays viable for large
                // files too — the ~1GB case this app exists for.
                textUploadLabel.text = "Copying $fileName into app storage…"
                tempFile = withContext(Dispatchers.IO) { copyUriToCacheFile(uri, fileName) }

                textUploadLabel.text = "Uploading $fileName…"
                val asset = api.uploadAsset(repo, releaseId, tempFile, mimeType) { sent, total ->
                    runOnUiThread {
                        val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
                        progressUpload.progress = pct
                        textUploadLabel.text = "Uploading $fileName… ${formatSize(sent)} / ${formatSize(total)}"
                    }
                }

                assetAdapter.addItem(asset)
                textEmptyAssets.visibility = View.GONE
                toast("Uploaded")
            } catch (e: GitHubApiException) {
                toast("Upload failed: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                toast("Upload cancelled")
            } catch (e: Exception) {
                toast("Upload failed: ${e.message}")
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete file?")
            .setMessage("Delete \"${asset.name}\" from this release?")
            .setPositiveButton("Delete") { _, _ -> deleteAsset(asset, position) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAsset(asset: ReleaseAsset, position: Int) {
        lifecycleScope.launch {
            try {
                api.deleteAsset(repo, asset.id)
                assetAdapter.removeAt(position)
                if (assetAdapter.isEmpty()) textEmptyAssets.visibility = View.VISIBLE
                toast("Deleted")
            } catch (e: Exception) {
                toast("Could not delete: ${e.message}")
            }
        }
    }

    private fun confirmDeleteRelease() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete release?")
            .setMessage("Delete this release and all its assets? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteRelease() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRelease() {
        lifecycleScope.launch {
            try {
                api.deleteRelease(repo, releaseId)
                toast("Release deleted")
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                toast("Could not delete release: ${e.message}")
            }
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_REPO = "extra_repo"
        const val EXTRA_RELEASE_ID = "extra_release_id"
        const val EXTRA_TAG_NAME = "extra_tag_name"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_BODY = "extra_body"
    }
}
