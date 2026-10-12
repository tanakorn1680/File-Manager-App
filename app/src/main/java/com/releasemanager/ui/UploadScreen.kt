package com.releasemanager.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.EditMode
import com.releasemanager.data.StagedFile
import com.releasemanager.data.UploadItem
import com.releasemanager.network.GitPhase
import com.releasemanager.util.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Web: #screen-upload — "Upload Files" and "Upload ZIP". Quick Edit commits straight to GitHub
 * (one commit per chunk read); Stage Mode only queues the files for "Commit all".
 */
class UploadScreen(private val ctx: AppContext) {

    enum class Mode { FILES, ZIP }

    private class Picked(val uri: Uri, val name: String, val size: Long)

    private class ZipTarget(val entry: ZipEntry, val name: String, val path: String)

    private enum class Kind { NORMAL, WORKING, DONE, ERROR }

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenUpload)
    private val textTitle: TextView = root.findViewById(R.id.textTitle)
    private val dropZone: View = root.findViewById(R.id.dropZone)
    private val textZone: TextView = root.findViewById(R.id.textZone)
    private val textZoneSub: TextView = root.findViewById(R.id.textZoneSub)
    private val fileList: LinearLayout = root.findViewById(R.id.fileListContainer)
    private val progressGroup: View = root.findViewById(R.id.progressGroup)
    private val label1: TextView = root.findViewById(R.id.textProgressLabel1)
    private val progress1: ProgressBar = root.findViewById(R.id.progress1)
    private val label2: TextView = root.findViewById(R.id.textProgressLabel2)
    private val progress2: ProgressBar = root.findViewById(R.id.progress2)
    private val commitWrap: View = root.findViewById(R.id.commitWrap)
    private val inputCommitMsg: EditText = root.findViewById(R.id.inputCommitMsg)
    private val btnDoUpload: MaterialButton = root.findViewById(R.id.btnDoUpload)

    private var mode = Mode.FILES
    private var picked: List<Picked> = emptyList()
    private val rowStatus = mutableListOf<TextView>()
    private var summaryMode = false
    private var uploading = false

    init {
        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { ctx.show(Screen.FILES) }
        dropZone.setOnClickListener { pick() }
        btnDoUpload.setOnClickListener { startUpload() }
    }

    /** Web: openUploadScreen(mode). */
    fun open(newMode: Mode) {
        // An upload from a moment ago is still running: just show its progress again
        if (uploading) {
            ctx.show(Screen.UPLOAD)
            return
        }
        mode = newMode
        picked = emptyList()
        rowStatus.clear()
        fileList.removeAllViews()

        val zip = mode == Mode.ZIP
        textTitle.text = if (zip) "Upload ZIP" else "Upload Files"
        textZone.text = if (zip) "Tap to select ZIP file" else "Tap to select files"
        textZoneSub.text = if (zip) "Pick a .zip from your device" else "Pick files from your device"
        fileList.visibility = View.GONE
        commitWrap.visibility = View.GONE
        btnDoUpload.visibility = View.GONE
        progressGroup.visibility = View.GONE
        progress1.progress = 0
        progress2.progress = 0
        btnDoUpload.text = if (zip) "Upload & Extract" else "Upload"
        inputCommitMsg.setText(if (zip) "Upload ZIP contents" else "Upload files")

        ctx.show(Screen.UPLOAD)
    }

    // ── Picking ──────────────────────────────────────────────────────────────

    private fun pick() {
        if (uploading) return
        if (mode == Mode.ZIP) {
            act.pickZip { uri -> if (uri != null) handlePicked(listOf(uri)) }
        } else {
            act.pickFiles { uris -> if (uris.isNotEmpty()) handlePicked(uris) }
        }
    }

    /** Web: handleDroppedFiles(). */
    private fun handlePicked(uris: List<Uri>) {
        ctx.launchSafe {
            val described = withContext(Dispatchers.IO) { uris.map { describe(it) } }
            if (mode == Mode.ZIP) {
                val zip = described.first()
                if (!zip.name.endsWith(".zip", ignoreCase = true)) {
                    ctx.toast.error("Please select a ZIP file")
                    return@launchSafe
                }
                picked = listOf(zip)
            } else {
                picked = described
            }
            renderUploadList(picked)
        }
    }

    private fun describe(uri: Uri): Picked {
        var name: String? = null
        var size = -1L
        act.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        return Picked(uri, name ?: uri.lastPathSegment ?: "file", size)
    }

    /** Web: renderUploadList() — above UPLOAD_LIST_ROW_CAP files a single summary row is shown. */
    private fun renderUploadList(files: List<Picked>) {
        fileList.removeAllViews()
        rowStatus.clear()
        summaryMode = files.size > UPLOAD_LIST_ROW_CAP
        val inflater = LayoutInflater.from(act)

        fun addRow(name: String, status: String) {
            val row = inflater.inflate(R.layout.item_upload_row, fileList, false)
            row.findViewById<TextView>(R.id.textRowName).text = name
            val statusView = row.findViewById<TextView>(R.id.textRowStatus)
            statusView.text = status
            rowStatus.add(statusView)
            fileList.addView(row)
        }

        if (summaryMode) {
            addRow("${files.size} files selected", sizeLabel(files.sumOf { maxOf(it.size, 0L) }))
        } else {
            files.forEach { addRow(it.name, sizeLabel(it.size)) }
        }
        fileList.visibility = View.VISIBLE
        commitWrap.visibility = View.VISIBLE
        btnDoUpload.visibility = View.VISIBLE
    }

    private fun sizeLabel(size: Long) = if (size > 0) formatSize(size) else ""

    // ── Upload ───────────────────────────────────────────────────────────────

    private fun startUpload() {
        if (uploading || picked.isEmpty()) return

        // Whole files are read into memory to be committed, so refuse the ones that cannot fit up front
        // instead of failing half way through
        if (mode == Mode.FILES) {
            val tooBig = picked.firstOrNull { it.size > maxFileBytes() }
            if (tooBig != null) {
                ctx.toast.error(tooLargeMessage(tooBig.name, tooBig.size), 8000)
                return
            }
        }

        uploading = true
        btnDoUpload.isEnabled = false
        root.hideKeyboard()
        progress1.progress = 0
        progress2.progress = 0
        progressGroup.visibility = View.VISIBLE

        ctx.launchSafe {
            try {
                if (mode == Mode.ZIP) doZipUpload() else doFilesUpload()
            } finally {
                uploading = false
                btnDoUpload.isEnabled = true
            }
        }
    }

    /**
     * Web: doFilesUpload(). Quick Edit works like Termux: clone, copy the files in, ONE commit, ONE git push —
     * any number of files, any size up to GitHub's 100 MB, nothing read into memory. Stage Mode only queues.
     */
    private suspend fun doFilesUpload() {
        if (state.editMode == EditMode.STAGE) doFilesStage() else doFilesPush()
    }

    private class PushSource(val path: String, val open: () -> java.io.InputStream)

    /** Clone → copy every source in → one commit → one push. [onCopying] gets the index of the file being copied. */
    private suspend fun pushSources(sources: List<PushSource>, message: String, onCopying: (Int) -> Unit) {
        val api = ctx.api ?: throw IOException("Not connected to GitHub")
        val repo = state.currentRepo ?: throw IOException("No repository selected")
        val branch = state.currentBranch
        val total = sources.size
        val session = api.openGitSession(act.cacheDir, repo, branch) { phase, text, pct ->
            act.runOnUiThread {
                label1.text = text
                if (phase == GitPhase.PUSH && pct >= 0) setProgress(progress1, 0.5 + pct / 200.0)
            }
        }
        try {
            for ((i, src) in sources.withIndex()) {
                label1.text = "Copying ${i + 1}/$total…"
                onCopying(i)
                session.addFile(src.path, src.open)
                setProgress(progress1, (i + 1).toDouble() / total * 0.5)
            }
            session.commitAndPush(message)
            setProgress(progress1, 1.0)
        } finally {
            session.close()
        }
    }

    private suspend fun doFilesPush() {
        val dir = state.currentPath
        val files = picked
        val total = files.size
        val commitMsg = inputCommitMsg.text.toString().ifEmpty { "Upload files" }

        label1.text = "Uploading files"
        label2.visibility = View.GONE
        progress2.visibility = View.GONE

        try {
            val sources = files.map { f ->
                PushSource(if (dir.isNotEmpty()) "$dir/${f.name}" else f.name) { openPicked(f) }
            }
            pushSources(sources, commitMsg) { i ->
                setStatus(i, if (summaryMode) "Copying ${i + 1}/$total…" else "Copying…", Kind.WORKING)
            }
            if (summaryMode) setStatus(0, "✓ $total files", Kind.DONE)
            else for (j in 0 until total) setStatus(j, "✓", Kind.DONE)
            ctx.toast.success("$total file${if (total > 1) "s" else ""} uploaded")
            delay(AFTER_UPLOAD_DELAY_MS)
            backToFiles(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing was committed: one push is all-or-nothing
            if (summaryMode) setStatus(0, "Failed", Kind.ERROR)
            else for (j in 0 until total) setStatus(j, "Failed", Kind.ERROR)
            ctx.toast.error(e.userMessage(), 8000)
        }
    }

    /** Stage Mode: files are read (in chunks) into memory and queued for "Commit all". */
    private suspend fun doFilesStage() {
        val dir = state.currentPath
        val files = picked
        val total = files.size

        label1.text = "Staging files"
        label2.visibility = View.GONE
        progress2.visibility = View.GONE

        var i = 0
        var doneUpTo = 0 // rows before this are staged
        try {
            while (i < total) {
                val chunk = mutableListOf<Pair<Int, UploadItem>>() // (row index, item)
                var chunkChars = 0L
                while (i < total && chunk.size < READ_CHUNK_FILES && chunkChars < READ_CHUNK_CHARS) {
                    val f = files[i]
                    setStatus(i, if (summaryMode) "Reading ${i + 1}/$total…" else "Reading…", Kind.WORKING)
                    val content = readBase64(f)
                    val targetPath = if (dir.isNotEmpty()) "$dir/${f.name}" else f.name
                    chunk.add(i to UploadItem(targetPath, f.name, content))
                    chunkChars += content.length
                    i++
                }
                for ((index, item) in chunk) {
                    state.stagedFiles[item.path] = StagedFile(item.path, item.name, null, "file", "upload", item.content)
                    if (!summaryMode) setStatus(index, "Staged", Kind.DONE)
                }
                if (summaryMode) setStatus(0, "$i/$total staged", Kind.WORKING)
                setProgress(progress1, i.toDouble() / total)
                doneUpTo = i
            }

            setProgress(progress1, 1.0)
            ctx.files.updateStagedBadge()
            ctx.toast.success("Staged $total file${if (total > 1) "s" else ""} — press Commit All to apply")
            delay(AFTER_UPLOAD_DELAY_MS)
            backToFiles(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rows that were never attempted must not stay on a stale "Reading…" state
            if (!summaryMode) for (j in doneUpTo until total) setStatus(j, "Failed", Kind.ERROR)
            ctx.toast.error(e.userMessage())
        }
    }

    /** Web: doZipUpload(). Quick Edit unpacks straight into one git push; Stage Mode queues in memory. */
    private suspend fun doZipUpload() {
        val dir = state.currentPath
        val pick = picked.firstOrNull() ?: return
        val stage = state.editMode == EditMode.STAGE
        val commitMsg = inputCommitMsg.text.toString().ifEmpty { "Upload ZIP contents" }
        var tempFile: File? = null

        label1.text = "Reading ZIP"
        label2.visibility = View.GONE
        progress2.visibility = View.GONE
        progress2.progress = 0

        try {
            setStatus(0, "Reading ZIP…", Kind.WORKING)
            setProgress(progress1, 0.05)

            // ZipFile needs a real file, so the picked document is copied into the app cache first
            val copy = withContext(Dispatchers.IO) { copyToCache(pick) }
            tempFile = copy
            val zip = try {
                withContext(Dispatchers.IO) { ZipFile(copy) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Cannot read ZIP file. Please make sure it is a standard ZIP (not RAR, 7z, or password-protected).")
            }

            zip.use {
                val targets = collectZipTargets(zip, dir)
                if (targets.isEmpty()) {
                    setStatus(0, "Failed", Kind.ERROR)
                    ctx.toast.error("ZIP contains no files")
                    return
                }
                val tooBig = targets.firstOrNull { it.entry.size > maxFileBytes() }
                if (tooBig != null) throw IOException(tooLargeMessage(tooBig.name, tooBig.entry.size))

                val total = targets.size
                if (stage) {
                    var read = 0
                    while (read < total) {
                        val chunk = mutableListOf<UploadItem>()
                        var chunkChars = 0L
                        while (read < total && chunk.size < READ_CHUNK_FILES && chunkChars < READ_CHUNK_CHARS) {
                            val t = targets[read]
                            val content = withContext(Dispatchers.IO) { readZipEntry(zip, t) }
                            chunk.add(UploadItem(t.path, t.name, content))
                            chunkChars += content.length
                            read++
                            setProgress(progress1, read.toDouble() / total)
                        }
                        chunk.forEach {
                            state.stagedFiles[it.path] = StagedFile(it.path, it.name, null, "file", "upload", it.content)
                        }
                        setStatus(0, "$read/$total staged", Kind.WORKING)
                    }
                    setProgress(progress1, 1.0)
                    setStatus(0, "$total files staged", Kind.DONE)
                    ctx.files.updateStagedBadge()
                    ctx.toast.success("Staged $total files from ZIP — press Commit All to apply")
                } else {
                    val sources = targets.map { t -> PushSource(t.path) { zip.getInputStream(t.entry) } }
                    pushSources(sources, commitMsg) { i -> setStatus(0, "Copying ${i + 1}/$total…", Kind.WORKING) }
                    setStatus(0, "✓ $total files", Kind.DONE)
                    ctx.toast.success("Uploaded $total files from ZIP")
                }
                delay(AFTER_UPLOAD_DELAY_MS)
                backToFiles(stage)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(0, "Failed", Kind.ERROR)
            // Show ZIP errors for longer: they can be long
            ctx.toast.error(e.userMessage(), 8000)
        } finally {
            tempFile?.let { f -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { f.delete() } }
        }
    }

    /** Web: the ZIP-Slip / duplicate / malformed-path filtering, entry by entry. */
    private fun collectZipTargets(zip: ZipFile, dir: String): List<ZipTarget> {
        val byPath = LinkedHashMap<String, ZipTarget>() // lower-cased target path → entry (GitHub is case-insensitive in practice)
        var duplicateCount = 0
        val skippedMalformed = mutableListOf<String>()

        for (entry in zip.entries().toList()) {
            if (entry.isDirectory) continue
            val normalized = entry.name.replace('\\', '/')
            if (normalized.contains("..") || normalized.startsWith("/")) continue
            if (normalized == ".DS_Store" || normalized.startsWith("__MACOSX/") || normalized.contains("/.DS_Store")) continue
            val targetPath = if (dir.isNotEmpty()) "$dir/$normalized" else normalized
            if (findMalformedSegment(targetPath) != null) {
                skippedMalformed.add(targetPath)
                continue
            }
            val key = targetPath.lowercase()
            if (byPath.containsKey(key)) duplicateCount++
            byPath[key] = ZipTarget(entry, normalized, targetPath) // the last occurrence wins
        }

        if (duplicateCount > 0) {
            ctx.toast.show(
                "Skipped $duplicateCount duplicate path${if (duplicateCount > 1) "s" else ""} in ZIP (kept the last version of each)",
                durationMs = 4000
            )
        }
        if (skippedMalformed.isNotEmpty()) {
            val preview = skippedMalformed.take(3).joinToString(", ")
            val more = if (skippedMalformed.size > 3) " and ${skippedMalformed.size - 3} more" else ""
            ctx.toast.show(
                "Skipped ${skippedMalformed.size} file(s) with invalid paths: $preview$more",
                durationMs = 8000
            )
        }
        return byPath.values.toList()
    }

    /**
     * Segments GitHub's Trees API calls "malformed": empty, "." / "..", ".git", leading/trailing
     * whitespace or a trailing dot (Windows-made ZIPs often have these without the author noticing).
     */
    private fun findMalformedSegment(targetPath: String): String? {
        for (seg in targetPath.split('/')) {
            if (seg.isEmpty()) return "(empty segment)"
            if (seg == "." || seg == "..") return seg
            if (seg == ".git") return seg
            if (seg != seg.trim()) return seg
            if (seg.endsWith(".")) return seg
        }
        return null
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    private suspend fun readBase64(f: Picked): String = withContext(Dispatchers.IO) {
        try {
            val bytes = act.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
                ?: throw IOException("Could not read \"${f.name}\"")
            if (bytes.size > maxFileBytes()) throw IOException(tooLargeMessage(f.name, bytes.size.toLong()))
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory to read \"${f.name}\"")
        }
    }

    private fun readZipEntry(zip: ZipFile, target: ZipTarget): String =
        try {
            val bytes = zip.getInputStream(target.entry).use { it.readBytes() }
            if (bytes.size > maxFileBytes()) throw IOException(tooLargeMessage(target.name, bytes.size.toLong()))
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory to read \"${target.name}\"")
        }

    private fun copyToCache(pick: Picked): File {
        val out = File(act.cacheDir, "upload_zip_${System.currentTimeMillis()}.zip")
        try {
            act.contentResolver.openInputStream(pick.uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Could not open the selected ZIP")
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return out
    }

    private fun maxFileBytes() =
        if (state.editMode == EditMode.STAGE) MAX_FILE_BYTES else GITHUB_MAX_FILE_BYTES

    private fun tooLargeMessage(name: String, size: Long) =
        if (state.editMode == EditMode.STAGE) {
            "\"$name\" is ${formatSize(size)} — over the ${formatSize(MAX_FILE_BYTES)} limit for Stage Mode " +
                "(staged files are kept in memory). Use Quick Edit for big files."
        } else {
            "\"$name\" is ${formatSize(size)} — GitHub does not accept files over " +
                "${formatSize(GITHUB_MAX_FILE_BYTES)} in a repository. Attach large files to a release instead."
        }

    private fun openPicked(f: Picked): java.io.InputStream =
        act.contentResolver.openInputStream(f.uri) ?: throw IOException("Could not read \"${f.name}\"")

    // ── UI helpers ───────────────────────────────────────────────────────────

    private fun setStatus(index: Int, text: String, kind: Kind) {
        val view = rowStatus.getOrNull(if (summaryMode) 0 else index) ?: return
        view.text = text
        view.setTextColor(
            act.color(
                when (kind) {
                    Kind.NORMAL -> R.color.text_dim
                    Kind.WORKING -> R.color.accent
                    Kind.DONE -> R.color.success
                    Kind.ERROR -> R.color.danger
                }
            )
        )
    }

    private fun setProgress(bar: ProgressBar, fraction: Double) {
        bar.progress = (fraction * 100).toInt().coerceIn(0, 100)
    }

    /** Web: `setTimeout(() => { showScreen('files'); … }, 1200)` — but never yank the user back from elsewhere. */
    private fun backToFiles(stage: Boolean) {
        if (act.current == Screen.UPLOAD) ctx.show(Screen.FILES)
        if (stage) ctx.files.renderFiles() else ctx.files.loadFiles()
    }

    private companion object {
        /** Web: UPLOAD_LIST_ROW_CAP. */
        const val UPLOAD_LIST_ROW_CAP = 300

        /** Web: READ_CHUNK_SIZE — files read before they are committed / staged. */
        const val READ_CHUNK_FILES = 200

        /** Extra bound for the app: base64 characters held in memory per chunk (a phone has far less RAM than a browser tab). */
        const val READ_CHUNK_CHARS = 24L * 1024 * 1024

        /** Stage Mode keeps whole files in memory until Commit All, so it stays bounded. */
        const val MAX_FILE_BYTES = 50L * 1024 * 1024

        /** GitHub itself refuses files over 100 MB in a repository, whatever tool is used. */
        const val GITHUB_MAX_FILE_BYTES = 100L * 1024 * 1024

        const val AFTER_UPLOAD_DELAY_MS = 1200L
    }
}
