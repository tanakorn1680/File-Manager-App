package com.releasemanager.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.KeyListener
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
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
 *
 * Everything here runs against the in-memory text that came straight from the GitHub API — there is
 * never a local copy to download: Commit / Stage both send this same buffer back over the API. On
 * top of that, the field behaves like a small code editor: line numbers kept in sync with its own
 * scroll, smooth pinch-to-zoom, a word-wrap toggle, a read-only (view only, still scrollable) toggle,
 * an MT Manager-style find bar (live match count, prev/next, case toggle) with jump-to-line, a
 * quick-symbol row for fast insertion, and lightweight syntax colouring by file extension (see
 * SyntaxHighlighter).
 */
class EditorScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenEditor)
    private val textTitle: TextView = root.findViewById(R.id.textTitle)
    private val dirtyDot: View = root.findViewById(R.id.viewDirtyDot)
    private val btnStage: TextView = root.findViewById(R.id.btnStage)
    private val btnCommit: MaterialButton = root.findViewById(R.id.btnCommit)
    private val btnMenu: ImageButton = root.findViewById(R.id.btnEditorMenu)
    private val editorText: EditText = root.findViewById(R.id.editorText)
    private val lineNumbers: TextView = root.findViewById(R.id.editorLineNumbers)

    // Find bar (MT Manager style)
    private val findBar: View = root.findViewById(R.id.findBar)
    private val findInput: EditText = root.findViewById(R.id.findInput)
    private val findCount: TextView = root.findViewById(R.id.findCount)
    private val btnFindCase: ImageButton = root.findViewById(R.id.btnFindCase)
    private val btnFindPrev: ImageButton = root.findViewById(R.id.btnFindPrev)
    private val btnFindNext: ImageButton = root.findViewById(R.id.btnFindNext)
    private val btnFindClose: ImageButton = root.findViewById(R.id.btnFindClose)

    // Quick-symbol row
    private val symbolBarScroll: HorizontalScrollView = root.findViewById(R.id.symbolBarScroll)
    private val symbolBar: LinearLayout = root.findViewById(R.id.symbolBar)

    private var loadJob: Job? = null

    /** True once the file's real content is in the editor; Commit / Stage stay off until then. */
    private var loaded = false
    private var saving = false

    /** Set while the code (not the user) fills the text field, so it is not counted as an edit. */
    private var programmaticChange = false

    // ── Editor UX state (persists across files for the rest of the session) ───
    private val defaultKeyListener: KeyListener? = editorText.keyListener
    private var wrapEnabled = true
    private var manualReadOnly = false
    private var language = SyntaxHighlighter.Lang.NONE

    private val minTextSizeSp = 10f
    private val maxTextSizeSp = 26f
    private val defaultTextSizeSp = 14f
    private var textSizeSp = defaultTextSizeSp

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingUpdate: Runnable? = null

    // ── Find state ───────────────────────────────────────────────────────────
    private var findMatches: List<Int> = emptyList()
    private var findIndex = -1
    private var findQuery = ""
    private var findCaseSensitive = false
    private var findBarOpen = false

    /**
     * Pinch-to-zoom: during an active gesture only the text size itself is updated (cheap) — the
     * line-number gutter rebuild (recomputeLineNumbers, which walks the whole Layout) is debounced
     * to run once after the fingers settle instead of on every onScale tick, so a pinch on a long
     * file stays smooth instead of stuttering behind a backlog of gutter rebuilds.
     */
    private val scaleDetector = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setTextSize((textSizeSp * detector.scaleFactor).coerceIn(minTextSizeSp, maxTextSizeSp), settleGutter = false)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scheduleGutterRefresh()
        }
    })

    init {
        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBack() }
        btnStage.setOnClickListener { stageCurrentFile() }
        btnCommit.setOnClickListener { openCommitDialog(null) }
        btnMenu.setOnClickListener { openEditorMenu() }
        editorText.onTextChanged {
            if (!programmaticChange) {
                setDirty(editorText.text.toString() != state.editorOriginal)
                scheduleContentUpdate()
                if (findBarOpen) scheduleFindRefresh()
            }
        }
        // Pinch-to-zoom: the detector only observes the stream (it never consumes it), so normal
        // typing, caret placement, selection and the field's own touch-scrolling all keep working.
        editorText.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }
        editorText.setOnScrollChangeListener { _, _, scrollY, _, _ -> lineNumbers.scrollTo(0, scrollY) }
        editorText.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                editorText.post { recomputeLineNumbers() }
            }
        }

        setupFindBar()
        buildSymbolBar()
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    /** Web: openEditor(file). */
    fun open(file: EditingFile) {
        loadJob?.cancel()
        state.editingFile = file
        state.editorOriginal = ""
        loaded = false
        language = SyntaxHighlighter.languageFor(file.name)
        setDirty(false)
        textTitle.text = file.name
        setEditorText("Loading…")
        updateControls()
        if (findBarOpen) closeFindBar() else { findMatches = emptyList(); findIndex = -1 }

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
        applyWrapMode()
        setTextSize(textSizeSp, force = true)
        editorText.post {
            applyHighlight()
            recomputeLineNumbers()
        }
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

    /**
     * The field is locked (via the key listener, not isEnabled) and Commit / Stage disabled until
     * the real content has loaded, or while "Read-only mode" is on. Using the key listener rather
     * than isEnabled keeps scrolling and text selection/copy working in both cases.
     */
    private fun updateControls() {
        editorText.keyListener = if (loaded && !manualReadOnly) defaultKeyListener else null
        btnCommit.isEnabled = loaded && !saving
        btnStage.isEnabled = loaded
        btnStage.alpha = if (loaded) 1f else 0.4f
        btnMenu.isEnabled = loaded
        btnMenu.alpha = if (loaded) 1f else 0.4f
    }

    // ── Back ─────────────────────────────────────────────────────────────────

    /** Web: #btn-back-editor. Also what the system Back button does on this screen. */
    fun onBack() {
        if (findBarOpen) {
            // Same two-step Back as MT Manager / most editors: close the find bar first
            closeFindBar()
            return
        }
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

    // ── Line numbers ─────────────────────────────────────────────────────────

    /**
     * Rebuilds the gutter from editorText's own Layout so wrapped (soft-broken) rows get a blank
     * cell instead of a wrong number — a visual row only gets a number when it is the first row of
     * its logical (newline-delimited) line.
     */
    private fun recomputeLineNumbers() {
        val layout = editorText.layout ?: return
        val text = editorText.text ?: return
        val visualLines = layout.lineCount
        if (visualLines > 20_000) return // guard against pathological files; gutter just stays as-is

        val sb = StringBuilder()
        var logicalNo = 0
        for (i in 0 until visualLines) {
            val start = layout.getLineStart(i)
            val isNewLogicalLine = start == 0 || text[start - 1] == '\n'
            if (isNewLogicalLine) sb.append(++logicalNo)
            if (i != visualLines - 1) sb.append('\n')
        }
        lineNumbers.text = sb
        lineNumbers.scrollTo(0, editorText.scrollY)
    }

    // ── Debounced highlight + gutter refresh while typing ──────────────────────

    private fun scheduleContentUpdate() {
        pendingUpdate?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            applyHighlight()
            recomputeLineNumbers()
        }
        pendingUpdate = r
        mainHandler.postDelayed(r, 150)
    }

    private fun applyHighlight() {
        val editable = editorText.text ?: return
        SyntaxHighlighter.highlight(editable, language, ::colorFor)
    }

    private fun colorFor(category: String): Int = when (category) {
        "comment" -> act.color(R.color.text_dim)
        "string" -> act.color(R.color.success)
        "number" -> act.color(R.color.warning_text)
        "keyword", "tag" -> act.color(R.color.accent)
        "attr" -> act.color(R.color.warning)
        else -> act.color(R.color.text_primary)
    }

    // ── Zoom ─────────────────────────────────────────────────────────────────

    /**
     * [settleGutter] = false (the default while a pinch is actively changing the size) skips the
     * gutter rebuild entirely; the caller is expected to call [scheduleGutterRefresh] once the
     * gesture ends. Every other caller (reset zoom, opening a file, the menu) wants the gutter kept
     * in sync immediately, so they get the old behaviour.
     */
    private fun setTextSize(sp: Float, force: Boolean = false, settleGutter: Boolean = true) {
        val clamped = sp.coerceIn(minTextSizeSp, maxTextSizeSp)
        if (!force && clamped == textSizeSp) return
        textSizeSp = clamped
        editorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        lineNumbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        if (settleGutter) editorText.post { recomputeLineNumbers() } else scheduleGutterRefresh()
    }

    /** Debounced gutter rebuild used while a pinch gesture is in progress (see [scaleDetector]). */
    private fun scheduleGutterRefresh() {
        mainHandler.removeCallbacks(gutterRefresh)
        mainHandler.postDelayed(gutterRefresh, 120)
    }

    private val gutterRefresh = Runnable { editorText.post { recomputeLineNumbers() } }

    private fun resetZoom() = setTextSize(defaultTextSizeSp, force = true)

    // ── Word wrap ────────────────────────────────────────────────────────────

    private fun applyWrapMode() {
        // Built-in TextView behaviour: horizontally-scrolling + multiline == no wrap, independent
        // vertical AND horizontal touch scrolling, no extra ScrollView needed.
        editorText.setHorizontallyScrolling(!wrapEnabled)
        editorText.post { recomputeLineNumbers() }
    }

    private fun toggleWrap() {
        wrapEnabled = !wrapEnabled
        applyWrapMode()
    }

    // ── Read-only (view only, still scrollable/selectable) ─────────────────────

    private fun toggleReadOnly() {
        manualReadOnly = !manualReadOnly
        updateControls()
    }

    // ── Overflow menu ────────────────────────────────────────────────────────

    private fun openEditorMenu() {
        if (!loaded) return
        val wrapLabel = (if (wrapEnabled) "✓ " else "") + "Word wrap"
        val readOnlyLabel = (if (manualReadOnly) "✓ " else "") + "Read-only mode"
        Dialogs.sheet(
            act, state.editingFile?.name ?: "Editor",
            listOf(
                Dialogs.SheetItem("Find in file", R.drawable.ic_search, onClick = { openFindBar() }),
                Dialogs.SheetItem("Jump to line", R.drawable.ic_arrow_right, onClick = { openJumpToLineDialog() }),
                Dialogs.SheetItem(
                    wrapLabel, R.drawable.ic_check_square,
                    sub = "Wrap long lines instead of scrolling sideways",
                    separatorBefore = true,
                    onClick = { toggleWrap() }
                ),
                Dialogs.SheetItem(
                    readOnlyLabel, R.drawable.ic_eye,
                    sub = "Browse safely — scrolling and selection still work",
                    onClick = { toggleReadOnly() }
                ),
                Dialogs.SheetItem(
                    "Reset zoom", R.drawable.ic_refresh,
                    sub = "Pinch the text to resize it",
                    separatorBefore = true,
                    onClick = { resetZoom() }
                )
            )
        )
    }

    // ── Find (MT Manager-style persistent bar: live match count, prev/next, case toggle) ──────

    private fun setupFindBar() {
        btnFindClose.setOnClickListener { closeFindBar() }
        btnFindCase.setOnClickListener {
            findCaseSensitive = !findCaseSensitive
            btnFindCase.alpha = if (findCaseSensitive) 1f else 0.45f
            runFind(keepIndexNear = true)
        }
        btnFindPrev.setOnClickListener { stepFind(-1) }
        btnFindNext.setOnClickListener { stepFind(1) }
        findInput.onTextChanged {
            findQuery = findInput.text.toString()
            runFind(keepIndexNear = false)
        }
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                stepFind(1)
                true
            } else {
                false
            }
        }
        btnFindCase.alpha = 0.45f
    }

    private fun openFindBar() {
        if (!loaded) return
        findBarOpen = true
        findBar.visibility = View.VISIBLE
        findInput.setText(findQuery)
        findInput.setSelection(findInput.text.length)
        findInput.showKeyboard()
        runFind(keepIndexNear = false)
    }

    private fun closeFindBar() {
        findBarOpen = false
        findBar.visibility = View.GONE
        clearFindHighlights()
        findInput.hideKeyboard()
        editorText.requestFocus()
    }

    /** Re-run while typing (debounced) so a fast typist doesn't trigger a full-text scan per key. */
    private fun scheduleFindRefresh() {
        mainHandler.removeCallbacks(findRefresh)
        mainHandler.postDelayed(findRefresh, 150)
    }

    private val findRefresh = Runnable { runFind(keepIndexNear = true) }

    /**
     * Rebuilds [findMatches] for the current query against the live editor text, highlights every
     * match, and keeps [findIndex] pointing at the same match (by position) when [keepIndexNear] is
     * true — e.g. after a Match case toggle or an edit elsewhere in the file — rather than always
     * snapping back to the first result.
     */
    private fun runFind(keepIndexNear: Boolean) {
        val text = editorText.text?.toString().orEmpty()
        val query = findQuery
        val previousStart = findMatches.getOrNull(findIndex)

        clearFindHighlights()
        findMatches = if (query.isEmpty() || text.isEmpty()) {
            emptyList()
        } else {
            buildList {
                var from = 0
                while (true) {
                    val idx = text.indexOf(query, from, ignoreCase = !findCaseSensitive)
                    if (idx < 0) break
                    add(idx)
                    from = idx + query.length
                }
            }
        }

        findIndex = when {
            findMatches.isEmpty() -> -1
            keepIndexNear && previousStart != null -> {
                val carried = findMatches.indexOfFirst { it >= previousStart }
                if (carried >= 0) carried else 0
            }
            else -> 0
        }

        highlightMatches()
        updateFindCount()
        if (findIndex >= 0) revealMatch(findIndex, selectIt = query.isNotEmpty() && keepIndexNear)
    }

    private fun stepFind(direction: Int) {
        if (findMatches.isEmpty()) {
            if (findQuery.isNotEmpty()) ctx.toast.show("\"$findQuery\" not found")
            return
        }
        findIndex = (findIndex + direction + findMatches.size) % findMatches.size
        updateFindCount()
        revealMatch(findIndex, selectIt = true)
    }

    private fun updateFindCount() {
        findCount.text = if (findMatches.isEmpty()) {
            if (findQuery.isEmpty()) "" else "0/0"
        } else {
            "${findIndex + 1}/${findMatches.size}"
        }
    }

    /** Soft highlight on every match (MT Manager dims all hits, not just the current one). */
    private fun highlightMatches() {
        val editable = editorText.text ?: return
        val len = findQuery.length
        if (len == 0) return
        for (start in findMatches) {
            editable.setSpan(
                FindHighlightSpan(act.color(R.color.accent_light)),
                start, (start + len).coerceAtMost(editable.length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun clearFindHighlights() {
        val editable = editorText.text ?: return
        editable.getSpans(0, editable.length, FindHighlightSpan::class.java).forEach { editable.removeSpan(it) }
    }

    /** Marker subclass so highlight spans can be found + cleared without touching syntax-colour spans. */
    private class FindHighlightSpan(color: Int) : BackgroundColorSpan(color)

    private fun revealMatch(index: Int, selectIt: Boolean) {
        val start = findMatches.getOrNull(index) ?: return
        val end = (start + findQuery.length).coerceAtMost(editorText.text?.length ?: start)
        if (selectIt) editorText.setSelection(start, end)
        editorText.post {
            editorText.layout?.let { layout ->
                val line = layout.getLineForOffset(start)
                val y = layout.getLineTop(line)
                editorText.scrollTo(editorText.scrollX, (y - editorText.height / 3).coerceAtLeast(0))
                lineNumbers.scrollTo(0, editorText.scrollY)
            }
        }
    }

    // ── Jump to line ─────────────────────────────────────────────────────────

    private fun openJumpToLineDialog() {
        if (!loaded) return
        Dialogs.prompt(
            act, title = "Jump to line", label = "Line number",
            hint = "1–${currentLineCount()}", positive = "Go", allowEmpty = false
        ) { value ->
            val n = value.trim().toIntOrNull()
            if (n != null && n > 0) jumpToLine(n) else ctx.toast.show("Enter a line number")
        }
    }

    private fun currentLineCount(): Int = editorText.text?.count { it == '\n' }?.plus(1) ?: 1

    private fun jumpToLine(n: Int) {
        val text = editorText.text?.toString() ?: return
        var idx = 0
        var line = 1
        while (line < n) {
            val next = text.indexOf('\n', idx)
            if (next < 0) break
            idx = next + 1
            line++
        }
        selectAndReveal(idx, idx)
    }

    // ── Shared: move the caret/selection and scroll it into view ───────────────

    private fun selectAndReveal(start: Int, end: Int) {
        val len = editorText.text?.length ?: 0
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        editorText.requestFocus()
        editorText.setSelection(s, e)
        editorText.post {
            editorText.layout?.let { layout ->
                val line = layout.getLineForOffset(s)
                val y = layout.getLineTop(line)
                editorText.scrollTo(editorText.scrollX, (y - editorText.height / 3).coerceAtLeast(0))
                lineNumbers.scrollTo(0, editorText.scrollY)
            }
        }
    }

    // ── Quick-symbol row (MT Manager style: one tap inserts at the caret) ──────

    /** Web: none — native-only convenience row above the keyboard for symbols that are slow to reach. */
    private fun buildSymbolBar() {
        val keys = listOf(
            "Tab" to "\t", "→" to "    ", "/" to "/", "\\" to "\\", "|" to "|",
            "+" to "+", "-" to "-", "*" to "*", "=" to "=", "_" to "_",
            "<" to "<", ">" to ">", "(" to "(", ")" to ")", "{" to "{", "}" to "}",
            "[" to "[", "]" to "]", "\"" to "\"", "'" to "'", ";" to ";", ":" to ":", "#" to "#"
        )
        val inflater = android.view.LayoutInflater.from(act)
        for ((label, insert) in keys) {
            val key = inflater.inflate(R.layout.item_symbol_key, symbolBar, false) as TextView
            key.text = label
            key.setOnClickListener { insertAtCaret(insert) }
            symbolBar.addView(key)
        }
    }

    /** Replaces the current selection (a bare caret counts as an empty selection) with [text]. */
    private fun insertAtCaret(text: String) {
        if (!loaded || manualReadOnly) return
        val editable = editorText.text ?: return
        val s = editorText.selectionStart.coerceIn(0, editable.length)
        val e = editorText.selectionEnd.coerceIn(0, editable.length)
        editable.replace(minOf(s, e), maxOf(s, e), text)
        editorText.setSelection(minOf(s, e) + text.length)
    }
}
