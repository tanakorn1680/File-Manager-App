package com.releasemanager.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.EditMode
import com.releasemanager.data.EditingFile
import com.releasemanager.data.StagedFile
import kotlinx.coroutines.Job

/**
 * Web: #screen-editor. Quick Edit commits the file straight away ("Commit"); Stage Mode keeps the
 * edit in the queue ("Stage") and returns to the file list.
 */
class EditorScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenEditor)
    private val textTitle: TextView = root.findViewById(R.id.textTitle)
    private val dirtyDot: View = root.findViewById(R.id.viewDirtyDot)
    private val btnStage: TextView = root.findViewById(R.id.btnStage)
    private val btnCommit: MaterialButton = root.findViewById(R.id.btnCommit)
    private val editorText: EditText = root.findViewById(R.id.editorText)

    private var loadJob: Job? = null

    /** True once the file's real content is in the editor; Commit / Stage stay off until then. */
    private var loaded = false
    private var saving = false

    /** Set while the code (not the user) fills the text field, so it is not counted as an edit. */
    private var programmaticChange = false

    init {
        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBack() }
        btnStage.setOnClickListener { stageCurrentFile() }
        btnCommit.setOnClickListener { openCommitDialog(null) }
        editorText.onTextChanged {
            if (!programmaticChange) {
                setDirty(editorText.text.toString() != state.editorOriginal)
            }
        }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    /** Web: openEditor(file). */
    fun open(file: EditingFile) {
        loadJob?.cancel()
        state.editingFile = file
        state.editorOriginal = ""
        loaded = false
        setDirty(false)
        textTitle.text = file.name
        setEditorText("Loading…")
        updateControls()

        // Stage Mode shows only "Stage", Quick Edit only "Commit"
        val stageMode = state.editMode == EditMode.STAGE
        btnStage.visibility = if (stageMode) View.VISIBLE else View.GONE
        btnCommit.visibility = if (stageMode) View.GONE else View.VISIBLE

        ctx.show(Screen.EDITOR)

        // A file that is already staged opens with its staged (not the GitHub) version
        if (stageMode) {
            val queued = state.stagedFiles[file.path]
            if (queued != null && queued.action != "delete") {
                val text = try {
                    val disk = queued.diskPath
                    if (disk != null) java.io.File(disk).readText(Charsets.UTF_8)
                    else String(Base64.decode(queued.content.orEmpty(), Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    null
                } catch (e: java.io.IOException) {
                    null
                }
                if (text != null) {
                    showLoaded(text)
                    return
                }
            }
        }

        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        loadJob = ctx.launchSafe(onError = {
            setEditorText("")
            ctx.toast.error(it.userMessage(), 5000)
        }) {
            val data = api.getFile(repo, file.path, branch)
            if (state.editingFile !== file) return@launchSafe // another file was opened meanwhile
            file.sha = data.sha
            showLoaded(data.text)
        }
    }

    private fun showLoaded(text: String) {
        state.editorOriginal = text
        setEditorText(text)
        setDirty(false)
        loaded = true
        updateControls()
    }

    private fun setEditorText(text: String) {
        programmaticChange = true
        editorText.setText(text)
        programmaticChange = false
    }

    private fun setDirty(dirty: Boolean) {
        state.editorDirty = dirty
        dirtyDot.visibility = if (dirty) View.VISIBLE else View.GONE
    }

    /** The field is locked and Commit / Stage disabled until the real content has loaded. */
    private fun updateControls() {
        editorText.isEnabled = loaded
        btnCommit.isEnabled = loaded && !saving
        btnStage.isEnabled = loaded
        btnStage.alpha = if (loaded) 1f else 0.4f
    }

    // ── Back ─────────────────────────────────────────────────────────────────

    /** Web: #btn-back-editor. Also what the system Back button does on this screen. */
    fun onBack() {
        if (state.editMode == EditMode.STAGE) {
            // Stage Mode: Back drops the unsaved edits of this file (the staged version stays)
            leave()
        } else {
            guardEditorBack { leave() }
        }
    }

    private fun leave() {
        loadJob?.cancel()
        setDirty(false)
        ctx.show(Screen.FILES)
    }

    /** Web: guardEditorBack() — with unsaved changes ask what to do, otherwise just continue. */
    private fun guardEditorBack(onContinue: () -> Unit) {
        if (!state.editorDirty) {
            onContinue()
            return
        }
        val name = state.editingFile?.name ?: "this file"
        val body = SpannableStringBuilder("You have unsaved changes in ").also {
            val start = it.length
            it.append(name)
            it.setSpan(StyleSpan(Typeface.BOLD), start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            it.append(". What would you like to do?")
        }
        Dialogs.confirm(
            act,
            title = "Unsaved changes",
            body = body,
            positive = "Save & Continue",
            negative = "Discard",
            positiveStyle = Dialogs.ButtonStyle.PRIMARY,
            cancelable = false,
            onNegative = {
                setDirty(false)
                onContinue()
            }
        ) { openCommitDialog(onContinue) }
    }

    // ── Stage Mode ───────────────────────────────────────────────────────────

    /** Web: stageCurrentFile() — keep the edit in the queue, nothing goes to GitHub. */
    private fun stageCurrentFile() {
        val file = state.editingFile ?: return
        if (!loaded) return
        val content = Base64.encodeToString(editorText.text.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // Re-saving replaces the queued version; a file that was only ever queued stays "create"/"upload"
        val queued = state.stagedFiles[file.path]
        val action = if (queued != null && (queued.action == "create" || queued.action == "upload")) {
            queued.action
        } else {
            "edit"
        }
        state.stagedFiles[file.path] = StagedFile(
            path = file.path, name = file.name, sha = file.sha,
            type = "file", action = action, content = content
        )

        setDirty(false)
        ctx.files.updateStagedBadge()
        ctx.toast.success("\"${file.name}\" saved")

        // Back to the list: the user picks the next file to edit
        ctx.show(Screen.FILES)
        ctx.files.renderFiles()
    }

    // ── Quick Edit: commit now ───────────────────────────────────────────────

    /** Web: the "Commit changes" dialog. [pending] is what to do after a successful save ("Save & Continue"). */
    private fun openCommitDialog(pending: (() -> Unit)?) {
        val file = state.editingFile ?: return
        if (!loaded || saving) return
        val default = "Update ${file.name}"
        Dialogs.prompt(
            act, title = "Commit changes", label = "Commit message",
            initial = default, positive = "Commit", allowEmpty = true
        ) { text -> save(text.trim().ifEmpty { default }, pending) }
    }

    private fun save(message: String, pending: (() -> Unit)?) {
        val file = state.editingFile ?: return
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val text = editorText.text.toString()
        val content = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        saving = true
        updateControls()
        ctx.toast.show("Saving…")
        ctx.launchSafe {
            try {
                val newSha = api.putFile(repo, branch, file.path, content, file.sha, message)
                // The next commit needs the new blob sha
                file.sha = newSha ?: api.getFile(repo, file.path, branch).sha
                ctx.toast.success("Saved")
                // What was saved is the new baseline; anything typed while saving is still unsaved
                state.editorOriginal = text
                setDirty(editorText.text.toString() != text)
                pending?.invoke()
            } finally {
                saving = false
                updateControls()
            }
        }
    }
}
