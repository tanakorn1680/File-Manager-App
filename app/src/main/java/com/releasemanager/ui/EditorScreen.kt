package com.releasemanager.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.content.Context
import android.text.method.KeyListener
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.EditMode
import com.releasemanager.data.EditingFile
import com.releasemanager.data.StagedFile
import com.releasemanager.util.CompiledSearch
import com.releasemanager.util.SearchOptions
import com.releasemanager.util.TextSearch
import kotlinx.coroutines.Job

/** At most this many matches around the current one are painted, so a very common query stays fast. */
private const val HIGHLIGHT_WINDOW = 400

/**
 * Web: #screen-editor. Quick Edit commits the file straight away ("Commit"); Stage Mode keeps the
 * edit in the queue ("Stage") and returns to the file list.
 *
 * Everything here runs against the in-memory text that came straight from the GitHub API — there is
 * never a local copy to download: Commit / Stage both send this same buffer back over the API. On
 * top of that, the field behaves like a small code editor: line numbers kept in sync with its own
 * scroll, pinch-to-zoom, a word-wrap toggle, a read-only (view only, still scrollable) toggle, a
 * find / replace bar (match case, whole word, regex), jump-to-line, a quick-keys bar (Tab, cursor
 * arrows, symbols) and lightweight syntax colouring by file extension (see SyntaxHighlighter).
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
    private val btnFind: ImageButton = root.findViewById(R.id.btnEditorFind)

    // Find / replace bar and quick keys
    private val findBar: View = root.findViewById(R.id.findBar)
    private val findInput: EditText = root.findViewById(R.id.findInput)
    private val replaceInput: EditText = root.findViewById(R.id.replaceInput)
    private val replaceRow: View = root.findViewById(R.id.replaceRow)
    private val findResults: TextView = root.findViewById(R.id.findResults)
    private val chipCase: TextView = root.findViewById(R.id.btnFindCase)
    private val chipWord: TextView = root.findViewById(R.id.btnFindWord)
    private val chipRegex: TextView = root.findViewById(R.id.btnFindRegex)
    private val btnToggleReplace: ImageButton = root.findViewById(R.id.btnToggleReplace)
    private val btnFindPrev: TextView = root.findViewById(R.id.btnFindPrev)
    private val btnFindNext: TextView = root.findViewById(R.id.btnFindNext)
    private val btnReplaceOne: TextView = root.findViewById(R.id.btnReplaceOne)
    private val btnReplaceAll: TextView = root.findViewById(R.id.btnReplaceAll)
    private val quickBar: View = root.findViewById(R.id.quickBar)
    private val quickKeys: LinearLayout = root.findViewById(R.id.quickKeys)

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
    private var lastSearch = ""

    private var searchOptions = SearchOptions()
    private var compiledSearch: CompiledSearch = CompiledSearch.Empty
    private var matches = TextSearch.Matches.EMPTY
    private var currentMatch = -1
    /** Where the next search starts from: the start of the match last shown (or the caret when the bar opened). */
    private var searchOrigin = 0
    private val matchSpans = ArrayList<Any>()
    private var pendingFindRefresh: Runnable? = null
    private var suppressFindWatcher = false
    private var language = SyntaxHighlighter.Lang.NONE

    private val minTextSizeSp = 10f
    private val maxTextSizeSp = 26f
    private val defaultTextSizeSp = 14f
    private var textSizeSp = defaultTextSizeSp

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingUpdate: Runnable? = null

    private val scaleDetector = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setTextSize((textSizeSp * detector.scaleFactor).coerceIn(minTextSizeSp, maxTextSizeSp))
            return true
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
                if (isFindOpen()) scheduleFindRefresh()
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
    }

    init {
        setupFindBar()
        buildQuickKeys()
        editorText.setOnFocusChangeListener { _, _ -> updateQuickBar() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    /** Web: openEditor(file). */
    fun open(file: EditingFile) {
        loadJob?.cancel()
        closeFind()
        state.editingFile = file
        state.editorOriginal = ""
        loaded = false
        language = SyntaxHighlighter.languageFor(file.name)
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
        btnFind.isEnabled = loaded
        btnFind.alpha = if (loaded) 1f else 0.4f
        updateQuickBar()
    }

    // ── Back ─────────────────────────────────────────────────────────────────

    /** Web: #btn-back-editor. Also what the system Back button does on this screen. */
    fun onBack() {
        if (isFindOpen()) {
            closeFind()
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
        closeFind()
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

    private fun setTextSize(sp: Float, force: Boolean = false) {
        val clamped = sp.coerceIn(minTextSizeSp, maxTextSizeSp)
        if (!force && clamped == textSizeSp) return
        textSizeSp = clamped
        editorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        lineNumbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        editorText.post { recomputeLineNumbers() }
    }

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
                Dialogs.SheetItem("Find & replace", R.drawable.ic_search, onClick = { openFind() }),
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

    // ── Find & replace ───────────────────────────────────────────────────────

    private fun isFindOpen() = findBar.visibility == View.VISIBLE

    private fun setupFindBar() {
        btnFind.setOnClickListener { openFind() }
        root.findViewById<View>(R.id.btnFindClose).setOnClickListener { closeFind() }
        btnFindNext.setOnClickListener { step(+1) }
        btnFindPrev.setOnClickListener { step(-1) }
        btnReplaceOne.setOnClickListener { onReplaceTapped(all = false) }
        btnReplaceAll.setOnClickListener { onReplaceTapped(all = true) }
        btnToggleReplace.setOnClickListener { setReplaceVisible(replaceRow.visibility != View.VISIBLE) }

        chipCase.setOnClickListener { searchOptions = searchOptions.copy(caseSensitive = !searchOptions.caseSensitive); optionsChanged() }
        chipWord.setOnClickListener { searchOptions = searchOptions.copy(wholeWord = !searchOptions.wholeWord); optionsChanged() }
        chipRegex.setOnClickListener { searchOptions = searchOptions.copy(regex = !searchOptions.regex); optionsChanged() }

        // Live search: every keystroke re-searches from where the search started, so the match grows in place
        findInput.onTextChanged {
            if (!suppressFindWatcher) {
                lastSearch = findInput.text.toString()
                refreshMatches(jump = true)
            }
        }
        findInput.setOnEditorActionListener { _, _, _ ->
            step(+1)
            true
        }
        replaceInput.setOnEditorActionListener { _, _, _ ->
            onReplaceTapped(all = false)
            true
        }
        findInput.setOnFocusChangeListener { _, _ -> updateQuickBar() }
        replaceInput.setOnFocusChangeListener { _, _ -> updateQuickBar() }
        updateOptionChips()
    }

    /** Opens the bar under the editor; a short single-line selection becomes the query. */
    private fun openFind() {
        if (!loaded) return
        val a = editorText.selectionStart
        val b = editorText.selectionEnd
        val selected = if (a in 0 until b && b - a <= 120) {
            editorText.text?.subSequence(a, b)?.toString()?.takeIf { '\n' !in it }
        } else {
            null
        }
        val seed = selected ?: lastSearch
        searchOrigin = if (a >= 0) a else 0

        findBar.visibility = View.VISIBLE
        suppressFindWatcher = true
        findInput.setText(seed)
        findInput.selectAll()
        suppressFindWatcher = false
        lastSearch = seed

        updateOptionChips()
        updateQuickBar()
        refreshMatches(jump = true)

        findInput.requestFocus()
        findInput.post {
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeFind() {
        pendingFindRefresh?.let { mainHandler.removeCallbacks(it) }
        pendingFindRefresh = null
        clearMatchSpans()
        matches = TextSearch.Matches.EMPTY
        currentMatch = -1
        if (!isFindOpen()) return
        val inFindFields = findInput.hasFocus() || replaceInput.hasFocus()
        findBar.visibility = View.GONE
        // Carry on typing where the last match was found
        if (inFindFields) editorText.requestFocus()
        updateQuickBar()
    }

    private fun setReplaceVisible(show: Boolean) {
        replaceRow.visibility = if (show) View.VISIBLE else View.GONE
        btnToggleReplace.rotation = if (show) 180f else 0f
        btnToggleReplace.contentDescription = if (show) "Hide replace" else "Show replace"
        if (show) replaceInput.requestFocus()
    }

    private fun optionsChanged() {
        updateOptionChips()
        refreshMatches(jump = true)
    }

    private fun updateOptionChips() {
        for ((chip, on) in listOf(
            chipCase to searchOptions.caseSensitive,
            chipWord to searchOptions.wholeWord,
            chipRegex to searchOptions.regex
        )) {
            chip.isActivated = on
            chip.setTextColor(act.color(if (on) R.color.accent else R.color.text_muted))
        }
    }

    /**
     * Searches the whole text again for the query in the bar and repaints the matches. [jump] selects
     * (and scrolls to) the first match at/after [searchOrigin]; without it the list only follows
     * the caret, which is what an edit in the text needs.
     */
    private fun refreshMatches(jump: Boolean) {
        pendingFindRefresh?.let { mainHandler.removeCallbacks(it) }
        pendingFindRefresh = null
        clearMatchSpans()
        if (!isFindOpen()) return

        compiledSearch = TextSearch.compile(findInput.text.toString(), searchOptions)
        val compiled = compiledSearch
        matches = if (compiled is CompiledSearch.Ok) {
            TextSearch.findAll(editorText.text.toString(), compiled.pattern)
        } else {
            TextSearch.Matches.EMPTY
        }

        currentMatch = if (matches.count == 0) {
            -1
        } else {
            val from = if (jump) searchOrigin else editorText.selectionStart.coerceAtLeast(0)
            TextSearch.indexAtOrAfter(matches.starts, from).let { if (it >= matches.count) 0 else it }
        }

        if (jump && currentMatch >= 0) {
            goTo(currentMatch)
        } else {
            applyMatchSpans()
            updateResultsLabel()
        }
    }

    private fun scheduleFindRefresh() {
        pendingFindRefresh?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingFindRefresh = null
            refreshMatches(jump = false)
        }
        pendingFindRefresh = r
        mainHandler.postDelayed(r, 250)
    }

    /** Makes the list current before an action that relies on it (a pending edit may have shifted it). */
    private fun flushFindRefresh() {
        if (pendingFindRefresh != null) refreshMatches(jump = false)
    }

    private fun step(direction: Int) {
        flushFindRefresh()
        if (matches.count == 0) {
            ctx.toast.show(if (findInput.text.isNullOrEmpty()) "Type something to search for" else "No results")
            return
        }
        val next = if (currentMatch < 0) {
            TextSearch.indexAtOrAfter(matches.starts, editorText.selectionEnd.coerceAtLeast(0))
                .let { if (it >= matches.count) 0 else it }
        } else {
            (currentMatch + direction + matches.count) % matches.count
        }
        goTo(next)
    }

    /** Makes match [index] the current one: painted stronger, selected in the text, scrolled into view. */
    private fun goTo(index: Int) {
        if (index !in 0 until matches.count) return
        currentMatch = index
        val start = matches.starts[index]
        val end = matches.ends[index]
        searchOrigin = start
        clearMatchSpans()
        applyMatchSpans()
        updateResultsLabel()
        val len = editorText.length()
        editorText.setSelection(start.coerceIn(0, len), end.coerceIn(0, len))
        revealRange(start)
    }

    private fun updateResultsLabel() {
        val total = matches.count.toString() + if (matches.truncated) "+" else ""
        val compiled = compiledSearch
        val (text, colorRes) = when {
            compiled is CompiledSearch.Invalid -> "Invalid pattern" to R.color.danger
            compiled is CompiledSearch.Empty -> "Results: 0" to R.color.text_muted
            matches.count == 0 -> "No results" to R.color.warning_text
            currentMatch >= 0 -> "${currentMatch + 1} of $total" to R.color.text_muted
            else -> "Results: $total" to R.color.text_muted
        }
        findResults.text = text
        findResults.setTextColor(act.color(colorRes))
        val usable = matches.count > 0
        for (b in listOf(btnFindPrev, btnFindNext, btnReplaceOne, btnReplaceAll)) b.alpha = if (usable) 1f else 0.4f
    }

    // Matches are painted with background spans of their own; the syntax colouring only touches its own spans.
    private fun applyMatchSpans() {
        val ed = editorText.text ?: return
        if (matches.count == 0) return
        val centre = currentMatch.coerceAtLeast(0)
        val from = (centre - HIGHLIGHT_WINDOW).coerceAtLeast(0)
        val to = (centre + HIGHLIGHT_WINDOW).coerceAtMost(matches.count)
        val other = (act.color(R.color.success) and 0x00FFFFFF) or 0x59000000
        val strong = (act.color(R.color.warning) and 0x00FFFFFF) or 0xA6000000.toInt()
        for (i in from until to) {
            val s = matches.starts[i]
            val e = matches.ends[i]
            if (e > ed.length) continue
            val span = BackgroundColorSpan(if (i == currentMatch) strong else other)
            ed.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            matchSpans.add(span)
        }
    }

    private fun clearMatchSpans() {
        val ed = editorText.text
        if (ed != null) for (span in matchSpans) ed.removeSpan(span)
        matchSpans.clear()
    }

    /** Scrolls so the line holding [offset] is on screen (a third of the way down when it has to move), without taking focus. */
    private fun revealRange(offset: Int) {
        editorText.post {
            val layout = editorText.layout ?: return@post
            val s = offset.coerceIn(0, editorText.length())
            val line = layout.getLineForOffset(s)
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            val visibleH = editorText.height - editorText.totalPaddingTop - editorText.totalPaddingBottom
            var y = editorText.scrollY
            if (top < y || bottom > y + visibleH) y = top - visibleH / 3
            y = y.coerceIn(0, (layout.height - visibleH).coerceAtLeast(0))

            var x = editorText.scrollX
            if (!wrapEnabled) {
                val px = layout.getPrimaryHorizontal(s).toInt()
                val visibleW = editorText.width - editorText.totalPaddingLeft - editorText.totalPaddingRight
                if (px < x || px > x + visibleW - act.dp(24)) x = (px - visibleW / 4).coerceAtLeast(0)
            } else {
                x = 0
            }
            editorText.scrollTo(x, y)
        }
    }

    // ── Replace ──────────────────────────────────────────────────────────────

    private fun onReplaceTapped(all: Boolean) {
        // First tap just reveals the replace field
        if (replaceRow.visibility != View.VISIBLE) {
            setReplaceVisible(true)
            return
        }
        if (manualReadOnly) {
            ctx.toast.show("Read-only mode is on — turn it off to replace")
            return
        }
        if (all) replaceAll() else replaceCurrent()
    }

    private fun replaceCurrent() {
        flushFindRefresh()
        val compiled = compiledSearch as? CompiledSearch.Ok ?: run {
            ctx.toast.show("Type something to search for")
            return
        }
        if (currentMatch !in 0 until matches.count) {
            step(+1)
            return
        }
        val ed = editorText.text ?: return
        val start = matches.starts[currentMatch]
        val end = matches.ends[currentMatch]
        if (end > ed.length) return
        val replacement = try {
            TextSearch.replacementFor(ed.toString(), compiled.pattern, start, end, replaceInput.text.toString(), searchOptions.regex)
        } catch (e: RuntimeException) {
            ctx.toast.show("Invalid replacement text")
            return
        }
        ed.replace(start, end, replacement)
        val after = start + replacement.length
        editorText.setSelection(after.coerceIn(0, editorText.length()))
        searchOrigin = after
        refreshMatches(jump = true) // on to the next match
    }

    private fun replaceAll() {
        flushFindRefresh()
        val compiled = compiledSearch as? CompiledSearch.Ok ?: run {
            ctx.toast.show("Type something to search for")
            return
        }
        val ed = editorText.text ?: return
        val result = try {
            TextSearch.replaceAll(ed.toString(), compiled.pattern, replaceInput.text.toString(), searchOptions.regex)
        } catch (e: RuntimeException) {
            ctx.toast.show("Invalid replacement text")
            return
        }
        if (result.count == 0) {
            ctx.toast.show("No results to replace")
            return
        }
        val caret = editorText.selectionStart.coerceAtLeast(0)
        val scroll = editorText.scrollY
        clearMatchSpans()
        programmaticChange = true
        try {
            ed.replace(0, ed.length, result.text)
        } finally {
            programmaticChange = false
        }
        setDirty(result.text != state.editorOriginal)
        scheduleContentUpdate()
        editorText.setSelection(caret.coerceAtMost(editorText.length()))
        editorText.post { editorText.scrollTo(editorText.scrollX, scroll) }
        searchOrigin = caret
        refreshMatches(jump = false)
        ctx.toast.success("Replaced ${result.count} match${if (result.count == 1) "" else "es"}")
    }

    // ── Quick keys ───────────────────────────────────────────────────────────

    /** The bar is shown while text is being edited (or the find bar is open); never over a read-only file. */
    private fun updateQuickBar() {
        val editing = loaded && !manualReadOnly && editorText.hasFocus()
        quickBar.visibility = if (editing || (loaded && isFindOpen())) View.VISIBLE else View.GONE
    }

    /** Keys act on the field that has focus: the find / replace fields while they are in use, else the text. */
    private fun keyTarget(): EditText = when {
        findInput.hasFocus() -> findInput
        replaceInput.hasFocus() -> replaceInput
        else -> editorText
    }

    private fun buildQuickKeys() {
        quickKeys.addView(quickKey("Tab", 56) { insertText(tabText()) })
        quickKeys.addView(arrowKey("←") { moveCaret(-1, 0) })
        quickKeys.addView(arrowKey("→") { moveCaret(+1, 0) })
        quickKeys.addView(arrowKey("↑") { moveCaret(0, -1) })
        quickKeys.addView(arrowKey("↓") { moveCaret(0, +1) })
        quickKeys.addView(View(act).apply {
            setBackgroundColor(act.color(R.color.border))
            layoutParams = LinearLayout.LayoutParams(act.dp(1), act.dp(24)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(act.dp(6), 0, act.dp(6), 0)
            }
        })
        for (symbol in "{}()[]<>\"';:=+-*/\\_|&!?#$%@,.~`^") {
            val text = symbol.toString()
            quickKeys.addView(quickKey(text, 44) { insertText(text) })
        }
    }

    /** A 44dp-wide, 40dp-tall tile; it never takes focus, so the keyboard stays open while it is tapped. */
    private fun quickKey(label: String, widthDp: Int, onClick: () -> Unit): TextView = TextView(act).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.MONOSPACE
        setTextColor(act.color(R.color.text_primary))
        background = act.getDrawable(R.drawable.bg_quick_key)
        isClickable = true
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(act.dp(widthDp), act.dp(40)).apply {
            setMargins(act.dp(3), act.dp(6), act.dp(3), act.dp(6))
        }
        setOnClickListener { onClick() }
    }

    /** Cursor keys repeat while held, so a long line can be crossed without tapping fifty times. */
    private fun arrowKey(label: String, move: () -> Unit): TextView {
        val key = quickKey(label, 44) { }
        val repeat = object : Runnable {
            override fun run() {
                move()
                mainHandler.postDelayed(this, 70)
            }
        }
        key.setOnClickListener(null)
        key.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    move()
                    mainHandler.postDelayed(repeat, 350)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mainHandler.removeCallbacks(repeat)
                }
            }
            true
        }
        return key
    }

    private fun tabText(): String {
        val name = state.editingFile?.name.orEmpty().lowercase()
        val wantsRealTab = name == "makefile" || name.endsWith(".go") || name.endsWith(".mk") || name.endsWith(".tsv")
        return if (wantsRealTab) "\t" else "    "
    }

    private fun insertText(text: String) {
        val target = keyTarget()
        if (target === editorText && (!loaded || manualReadOnly)) return
        val ed = target.text
        val a = target.selectionStart
        val b = target.selectionEnd
        if (a < 0 || b < 0) {
            ed.append(text)
        } else {
            ed.replace(minOf(a, b), maxOf(a, b), text)
        }
    }

    private fun isMark(c: Char) = Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /** Moves the caret one character sideways (skipping Thai vowel / tone marks) or one visual line up / down. */
    private fun moveCaret(dx: Int, dy: Int) {
        val target = keyTarget()
        val text = target.text
        val len = text.length
        val sel = target.selectionEnd.coerceIn(0, len)
        val pos = when {
            dx < 0 -> {
                var p = (sel - 1).coerceAtLeast(0)
                while (p > 0 && isMark(text[p])) p--
                p
            }
            dx > 0 -> {
                var p = (sel + 1).coerceAtMost(len)
                while (p < len && isMark(text[p])) p++
                p
            }
            else -> {
                val layout = target.layout ?: return
                val line = layout.getLineForOffset(sel)
                val toLine = line + dy
                if (toLine < 0 || toLine >= layout.lineCount) return
                layout.getOffsetForHorizontal(toLine, layout.getPrimaryHorizontal(sel))
            }
        }
        target.setSelection(pos.coerceIn(0, len))
        if (target === editorText) revealRange(pos)
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
}
