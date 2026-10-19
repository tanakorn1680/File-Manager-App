package com.releasemanager.ui

import android.content.res.ColorStateList
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.EditMode
import com.releasemanager.data.STAGED_DIR
import com.releasemanager.data.StagedFile
import com.releasemanager.network.PickedSource
import com.releasemanager.network.UploadJobs
import com.releasemanager.network.UriAccess
import com.releasemanager.network.ZipScan
import com.releasemanager.service.Transfer
import com.releasemanager.service.TransferKind
import com.releasemanager.service.TransferState
import com.releasemanager.util.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Web: #screen-upload — "Upload Files" and "Upload ZIP". Quick Edit commits straight to GitHub — that work runs
 * in TransferService (background, progress in the notification), so it carries on if the app is closed;
 * Stage Mode only queues the files for "Commit all".
 */
class UploadScreen(private val ctx: AppContext) {

    enum class Mode { FILES, ZIP }

    private class Picked(val uri: Uri, val name: String, val size: Long)

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
    private val smooth1 = SmoothProgress(progress1)
    private val smooth2 = SmoothProgress(progress2)

    // Quick Edit upload: one bar per real step, each in its own colour (blue, purple, green)
    private class StepRow(val status: TextView, val bar: ProgressBar, val smooth: SmoothProgress, val color: Int)

    private val stepGroup: LinearLayout = root.findViewById(R.id.stepGroup)
    private val steps: List<StepRow>

    init {
        val names = listOf("Clone", "Copy", "Push")
        val colors = listOf(R.color.accent, R.color.step_copy, R.color.success)
        steps = names.indices.map { i ->
            val row = LayoutInflater.from(act).inflate(R.layout.item_upload_step, stepGroup, false)
            row.findViewById<TextView>(R.id.textStepName).text = I18n.tr(names[i])
            val bar = row.findViewById<ProgressBar>(R.id.progressStep)
            val color = act.color(colors[i])
            bar.progressTintList = ColorStateList.valueOf(color)
            stepGroup.addView(row)
            StepRow(row.findViewById(R.id.textStepStatus), bar, SmoothProgress(bar), color)
        }
    }
    private val commitWrap: View = root.findViewById(R.id.commitWrap)
    private val inputCommitMsg: EditText = root.findViewById(R.id.inputCommitMsg)
    private val btnDoUpload: MaterialButton = root.findViewById(R.id.btnDoUpload)

    private var mode = Mode.FILES
    private var picked: List<Picked> = emptyList()
    private val rowStatus = mutableListOf<TextView>()
    private var summaryMode = false
    private var uploading = false

    /** The commit message was typed by the user: picking other files no longer replaces it. */
    private var messageEdited = false
    private var settingMessage = false

    /** The background upload this screen is showing (Quick Edit only), null when none. */
    private var transfer: Transfer? = null

    init {
        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { ctx.show(Screen.FILES) }
        dropZone.setOnClickListener { pick() }
        btnDoUpload.setOnClickListener { startUpload() }
        inputCommitMsg.doAfterTextChanged { if (!settingMessage) messageEdited = true }
    }

    /** Sets the commit message from code (not counted as the user editing it). */
    private fun setMessage(text: String) {
        settingMessage = true
        inputCommitMsg.setText(text)
        settingMessage = false
    }

    /** "Upload ZIP my-archive", "Upload photo.jpg", "Upload a.txt, b.txt, c.txt and 4 more". */
    private fun defaultMessage(files: List<Picked>): String {
        if (mode == Mode.ZIP) return "Upload ZIP ${files.first().name.dropLast(".zip".length)}"
        val names = files.map { it.name }
        val shown = names.take(MESSAGE_NAMES).joinToString(", ")
        return if (names.size <= MESSAGE_NAMES) "Upload $shown" else "Upload $shown and ${names.size - MESSAGE_NAMES} more"
    }

    /** Web: openUploadScreen(mode). */
    fun open(newMode: Mode) {
        // An upload from a moment ago is still running: just show its progress again
        if (uploading) {
            ctx.show(Screen.UPLOAD)
            return
        }
        mode = newMode
        releasePicked()
        picked = emptyList()
        rowStatus.clear()
        fileList.removeAllViews()

        val zip = mode == Mode.ZIP
        textTitle.text = I18n.tr(if (zip) "Upload ZIP" else "Upload Files")
        textZone.text = I18n.tr(if (zip) "Tap to select ZIP file" else "Tap to select files")
        textZoneSub.text = I18n.tr(if (zip) "Pick a .zip from your device" else "Pick files from your device")
        fileList.visibility = View.GONE
        commitWrap.visibility = View.GONE
        btnDoUpload.visibility = View.GONE
        progressGroup.visibility = View.GONE
        smooth1.reset()
        smooth2.reset()
        resetSteps()
        btnDoUpload.text = I18n.tr(if (zip) "Upload & Extract" else "Upload")
        messageEdited = false
        setMessage(if (zip) "Upload ZIP contents" else "Upload files")

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
            releasePicked()
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
            if (!messageEdited) setMessage(defaultMessage(picked))
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
        // A copy made for a source that cannot be kept readable (see UriAccess.copyToCache) is a plain file
        if (size < 0 && uri.scheme == "file") size = uri.path?.let { File(it).length() } ?: -1L
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

        // Whole files go through the clone, so refuse the ones GitHub would reject up front
        // instead of failing half way through
        if (mode == Mode.FILES) {
            val tooBig = picked.firstOrNull { it.size > UploadJobs.GITHUB_MAX_FILE_BYTES }
            if (tooBig != null) {
                ctx.toast.error(UploadJobs.tooLargeMessage(tooBig.name, tooBig.size), 8000)
                return
            }
        }

        uploading = true
        btnDoUpload.isEnabled = false
        root.hideKeyboard()
        smooth1.reset()
        smooth2.reset()
        resetSteps()
        progressGroup.visibility = View.VISIBLE

        // A commit-push upload has three real steps; staging is a single local copy
        val push = state.editMode != EditMode.STAGE
        stepGroup.visibility = if (push) View.VISIBLE else View.GONE
        progress1.visibility = if (push) View.GONE else View.VISIBLE
        if (push) {
            startPushTransfer()
            return
        }

        // Stage Mode only copies into app storage: quick, local, stays on this screen
        ctx.launchSafe {
            try {
                if (mode == Mode.ZIP) doZipStage() else doFilesStage()
            } finally {
                uploading = false
                btnDoUpload.isEnabled = true
                releasePicked()
            }
        }
    }

    /**
     * Quick Edit (web: doFilesUpload / doZipUpload): clone, copy the files in, ONE commit, ONE git push — any
     * number of files, any size up to GitHub's 100 MB, nothing read into memory. It runs in TransferService, so
     * closing the app does not stop it; progress is shown here while the screen is open and in the notification.
     */
    private fun startPushTransfer() {
        val api = ctx.api
        val repo = state.currentRepo
        if (api == null || repo == null) {
            ctx.toast.error(if (api == null) "Not connected to GitHub" else "No repository selected")
            uploading = false
            btnDoUpload.isEnabled = true
            progressGroup.visibility = View.GONE
            return
        }

        // Plain copies of everything the background work needs: it must not reach into this screen
        val branch = state.currentBranch
        val dir = state.currentPath
        val cr = act.applicationContext.contentResolver
        val cacheDir = act.cacheDir
        val files = picked.map { PickedSource(it.uri, it.name) }
        val zip = mode == Mode.ZIP
        val message = inputCommitMsg.text.toString().ifEmpty { if (zip) "Upload ZIP contents" else "Upload files" }

        label1.text = I18n.tr(if (zip) "Reading ZIP" else "Uploading files")
        label2.visibility = View.GONE
        progress2.visibility = View.GONE
        if (zip) setStatus(0, "Reading ZIP…", Kind.WORKING)

        val title = if (zip || files.size == 1) files[0].name else "${files.size} files"
        transfer = ctx.startTransfer(TransferKind.UPLOAD, title, "repo:$repo", ::onTransferUpdate) { progress ->
            if (zip) {
                UploadJobs.pushZip(api, cr, cacheDir, repo, branch, dir, files[0].uri, message, progress)
            } else {
                UploadJobs.pushFiles(api, cr, cacheDir, repo, branch, dir, files, message, progress)
            }
        }
    }

    /** Progress and the final result of the background upload (only while this screen's Activity is alive). */
    private fun onTransferUpdate(t: Transfer) {
        if (t.id != transfer?.id) return

        if (t.isRunning) {
            if (t.label.isNotEmpty()) label1.text = t.label
            renderSteps(t)
            if (t.itemIndex >= 0) {
                val oneRow = summaryMode || mode == Mode.ZIP
                setStatus(if (oneRow) 0 else t.itemIndex, if (oneRow) t.itemText else "Copying…", Kind.WORKING)
            }
            return
        }

        transfer = null
        uploading = false
        btnDoUpload.isEnabled = true
        val total = picked.size
        fun markRows(text: String, kind: Kind) {
            if (summaryMode || mode == Mode.ZIP) setStatus(0, text, kind)
            else for (j in 0 until total) setStatus(j, text, kind)
        }
        when (t.state) {
            TransferState.SUCCESS -> {
                steps.forEach { stepDone(it) }
                if (summaryMode) setStatus(0, "✓ $total files", Kind.DONE)
                else if (mode == Mode.ZIP) setStatus(0, "✓ Done", Kind.DONE)
                else markRows("✓", Kind.DONE)
                ctx.toast.success(t.message ?: "Uploaded")
                ctx.launchSafe {
                    delay(AFTER_UPLOAD_DELAY_MS)
                    backToFiles(false)
                }
            }
            TransferState.FAILED -> {
                // Nothing was committed: one push is all-or-nothing
                failSteps(t, "Failed")
                markRows("Failed", Kind.ERROR)
                ctx.toast.error(t.message ?: "Upload failed", 8000)
            }
            TransferState.CANCELLED -> {
                failSteps(t, "Cancelled")
                markRows("Cancelled", Kind.ERROR)
                ctx.toast.show("Upload cancelled")
            }
            TransferState.RUNNING -> Unit
        }
    }

    /** Stage Mode: files are copied to app storage (not memory) and queued for "Commit all". */
    private suspend fun doFilesStage() {
        val dir = state.currentPath
        val files = picked
        val total = files.size

        label1.text = I18n.tr("Staging files")
        label2.visibility = View.GONE
        progress2.visibility = View.GONE

        var i = 0
        var doneUpTo = 0 // rows before this are staged
        try {
            while (i < total) {
                val f = files[i]
                setStatus(i, if (summaryMode) "Staging ${i + 1}/$total…" else "Staging…", Kind.WORKING)
                val targetPath = if (dir.isNotEmpty()) "$dir/${f.name}" else f.name
                val disk = withContext(Dispatchers.IO) { stageToDisk { openPicked(f) } }
                state.stagedFiles[targetPath] = StagedFile(targetPath, f.name, null, "file", "upload", null, disk.absolutePath)
                if (!summaryMode) setStatus(i, "Staged", Kind.DONE)
                i++
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

    /** Stage Mode only: the ZIP's files are copied to app storage and queued for "Commit all". */
    private suspend fun doZipStage() {
        val dir = state.currentPath
        val pick = picked.firstOrNull() ?: return
        var tempFile: File? = null

        label1.text = I18n.tr("Reading ZIP")
        label2.visibility = View.GONE
        progress2.visibility = View.GONE
        smooth2.reset()

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
                val tooBig = targets.firstOrNull { it.entry.size > UploadJobs.GITHUB_MAX_FILE_BYTES }
                if (tooBig != null) throw IOException(UploadJobs.tooLargeMessage(tooBig.name, tooBig.entry.size))

                val total = targets.size
                for ((read, t) in targets.withIndex()) {
                    val disk = withContext(Dispatchers.IO) { stageToDisk { zip.getInputStream(t.entry) } }
                    state.stagedFiles[t.path] = StagedFile(t.path, t.name, null, "file", "upload", null, disk.absolutePath)
                    setProgress(progress1, (read + 1).toDouble() / total)
                    setStatus(0, "${read + 1}/$total staged", Kind.WORKING)
                }
                setProgress(progress1, 1.0)
                setStatus(0, "$total files staged", Kind.DONE)
                ctx.files.updateStagedBadge()
                ctx.toast.success("Staged $total files from ZIP — press Commit All to apply")
                delay(AFTER_UPLOAD_DELAY_MS)
                backToFiles(true)
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

    /** Scans the ZIP and announces what was skipped (Stage Mode; the background upload puts it in its result). */
    private fun collectZipTargets(zip: ZipFile, dir: String): List<com.releasemanager.network.ZipTarget> {
        val scan = ZipScan.collect(zip, dir)
        scan.duplicateMessage?.let { ctx.toast.show(it, durationMs = 4000) }
        scan.malformedMessage?.let { ctx.toast.show(it, durationMs = 8000) }
        return scan.targets
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** Copies [open]'s stream into the app cache (sequential disk I/O, nothing held in memory) and returns that file. */
    private fun stageToDisk(open: () -> java.io.InputStream): File {
        val dir = File(act.cacheDir, STAGED_DIR).apply { mkdirs() }
        val out = File(dir, "s${System.nanoTime()}")
        try {
            open().use { input -> out.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return out
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

    private fun openPicked(f: Picked): java.io.InputStream =
        act.contentResolver.openInputStream(f.uri) ?: throw IOException("Could not read \"${f.name}\"")

    /** Gives back the persistable Uri grants taken when the files were picked (the background upload does this itself). */
    private fun releasePicked() {
        picked.forEach { UriAccess.release(act.contentResolver, it.uri) }
    }

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

    // ── The three steps ──────────────────────────────────────────────────────

    private fun resetSteps() {
        steps.forEach {
            it.smooth.reset()
            it.bar.progressTintList = ColorStateList.valueOf(it.color)
            it.status.text = ""
            it.status.setTextColor(act.color(R.color.text_dim))
        }
    }

    /** Finished steps show ✓, the current one its own progress, later ones stay empty. */
    private fun renderSteps(t: Transfer) {
        stepGroup.visibility = View.VISIBLE
        progress1.visibility = View.GONE
        steps.forEachIndexed { i, s ->
            when {
                i < t.step -> stepDone(s)
                i == t.step -> {
                    if (t.stepFraction >= 0f) s.smooth.set(t.stepFraction)
                    s.status.text = t.stepStatus.ifEmpty { "…" }
                    s.status.setTextColor(s.color)
                }
            }
        }
    }

    private fun stepDone(s: StepRow) {
        s.smooth.set(1f)
        s.status.text = "✓"
        s.status.setTextColor(s.color)
    }

    /** The step that was running when the upload stopped turns red, so it shows where it went wrong. */
    private fun failSteps(t: Transfer, text: String) {
        stepGroup.visibility = View.VISIBLE
        progress1.visibility = View.GONE
        steps.forEachIndexed { i, s ->
            when {
                i < t.step -> stepDone(s)
                i == t.step -> {
                    val danger = act.color(R.color.danger)
                    s.bar.progressTintList = ColorStateList.valueOf(danger)
                    s.status.text = I18n.tr(text)
                    s.status.setTextColor(danger)
                }
            }
        }
    }

    private fun setProgress(bar: ProgressBar, fraction: Double) {
        (if (bar === progress1) smooth1 else smooth2).set(fraction.toFloat())
    }

    /** Web: `setTimeout(() => { showScreen('files'); … }, 1200)` — but never yank the user back from elsewhere. */
    private fun backToFiles(stage: Boolean) {
        if (act.current == Screen.UPLOAD) ctx.show(Screen.FILES)
        if (stage) ctx.files.renderFiles() else ctx.files.loadFiles()
    }

    private companion object {
        /** Web: UPLOAD_LIST_ROW_CAP. */
        const val UPLOAD_LIST_ROW_CAP = 300

        const val AFTER_UPLOAD_DELAY_MS = 1200L

        /** How many file names the suggested commit message lists before it says "and N more". */
        const val MESSAGE_NAMES = 3
    }
}
