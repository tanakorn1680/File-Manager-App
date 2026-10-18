package com.releasemanager.ui

import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.releasemanager.R
import com.releasemanager.data.DeleteTarget
import com.releasemanager.data.EditMode
import com.releasemanager.data.EditingFile
import com.releasemanager.data.FileEntry
import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.STAGED_DIR
import com.releasemanager.data.StagedFile
import com.releasemanager.data.UploadItem
import com.releasemanager.network.DownloadJobs
import com.releasemanager.network.PushSource
import com.releasemanager.network.pushSources
import com.releasemanager.service.TransferKind
import com.releasemanager.service.TransferOutcome
import com.releasemanager.service.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Web: #screen-files — folder browser, select mode, file actions, Stage Mode queue. */
class FilesScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenFiles)
    private val swipe: SwipeRefreshLayout = root.findViewById(R.id.swipeFiles)
    private val textTitle: TextView = root.findViewById(R.id.textTitle)
    private val textBranch: TextView = root.findViewById(R.id.textBranch)
    private val btnBranch: View = root.findViewById(R.id.btnBranch)
    private val btnCommitStaged: View = root.findViewById(R.id.btnCommitStaged)
    private val textStagedBadge: TextView = root.findViewById(R.id.textStagedBadge)
    private val breadcrumb: LinearLayout = root.findViewById(R.id.breadcrumbContainer)
    private val list: RecyclerView = root.findViewById(R.id.listFiles)
    private val fabAdd: FloatingActionButton = root.findViewById(R.id.fabAdd)
    private val selectBar: View = root.findViewById(R.id.selectBar)
    private val textSelectCount: TextView = root.findViewById(R.id.textSelectCount)
    private val stateView = StateView(root)

    /** Previews of the pictures in the list, kept in memory only. */
    private val thumbs = Thumbnails(act.lifecycleScope)

    private val adapter = FileAdapter(
        selected = state.selected,
        staged = state.stagedFiles.keys,
        onClick = { onFileClick(it) },
        onLongClick = { enterSelectMode(it) },
        thumbnails = thumbs,
        thumbSource = { file ->
            val api = ctx.api
            val repo = state.currentRepo
            if (api == null || repo == null) {
                null
            } else {
                val fetch: suspend (OutputStream) -> Unit = { sink ->
                    api.downloadFile(repo, file.path, state.currentBranch, sink, null, file.size)
                }
                fetch
            }
        }
    )

    /** What the list currently shows (remote listing + staged changes) — selection looks entries up here. */
    private var display: List<FileEntry> = emptyList()
    private var loadJob: Job? = null
    private var committing = false

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        swipe.setup(list) { loadFiles(silent = true) }

        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBack() }
        btnBranch.setOnClickListener { showBranchDropdown() }
        btnCommitStaged.setOnClickListener { openCommitStagedDialog() }
        root.findViewById<ImageButton>(R.id.btnReleases).setOnClickListener { ctx.releases.enter() }
        root.findViewById<ImageButton>(R.id.btnActions).setOnClickListener { ctx.actions.enter() }
        root.findViewById<View>(R.id.btnHistory).setOnClickListener { ctx.history.enter() }
        root.findViewById<ImageButton>(R.id.btnSelectMode).setOnClickListener {
            if (state.selectMode) {
                exitSelectMode()
            } else {
                state.selectMode = true
                updateSelectBar()
            }
        }
        root.findViewById<MaterialButton>(R.id.btnCancelSelect).setOnClickListener { exitSelectMode() }
        root.findViewById<MaterialButton>(R.id.btnSelectDelete).setOnClickListener {
            confirmDelete(selectedEntries())
        }
        root.findViewById<MaterialButton>(R.id.btnSelectDownload).setOnClickListener { downloadSelected() }
        fabAdd.setOnClickListener { openFabSheet() }
    }

    // ── Opening a repo / loading ─────────────────────────────────────────────

    /** Web: openRepo(name, branch). */
    fun openRepo(name: String, branch: String) {
        // Switching to a different repo drops the staged queue of the old one
        if (state.currentRepo != name) {
            state.stagedFiles.clear()
            clearStagedDisk()
        }
        state.currentRepo = name
        state.currentBranch = branch.ifEmpty { "main" }
        state.currentPath = ""
        state.selectMode = false
        state.selected.clear()
        state.branches = emptyList()
        textTitle.text = name.repoShortName()
        textBranch.text = state.currentBranch

        ctx.show(Screen.FILES)
        updateSelectBar()
        updateStagedBadge()
        loadFiles()
        loadBranches()
    }

    /** Web: loadFiles(). [onLoaded] runs once the listing is on screen. */
    fun loadFiles(silent: Boolean = false, onLoaded: (() -> Unit)? = null) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val path = state.currentPath

        updateBreadcrumb()
        if (!silent) {
            swipe.isRefreshing = false
            stateView.showLoading()
            display = emptyList()
            adapter.submitList(display)
        }

        loadJob?.cancel() // a slower, older load must not overwrite a newer one
        loadJob = ctx.launchSafe(onError = { e ->
            swipe.isRefreshing = false
            if (e is GitHubApiException && e.httpStatus == 404 && path.isNotEmpty() && hasStagedUnder(path)) {
                // A folder that only exists in the Stage Mode queue (new folder) has nothing on GitHub yet
                state.files = emptyList()
                renderFiles()
                onLoaded?.invoke()
            } else {
                val msg = e.userMessage()
                val is404 = msg.contains("404") || msg.lowercase().contains("not found")
                val shown = if (is404 && path.isEmpty()) "Repository is empty or branch not found." else msg
                if (silent) ctx.toast.error(shown) else stateView.showError(shown) { loadFiles() }
            }
        }) {
            state.files = api.getTree(repo, path, branch)
            renderFiles()
            swipe.isRefreshing = false
            onLoaded?.invoke()
        }
    }

    /** Deletes the copies Stage Mode kept on disk for uploads (called when the queue is committed or dropped). */
    private fun clearStagedDisk() {
        java.io.File(ctx.activity.cacheDir, STAGED_DIR).deleteRecursively()
    }

    private fun hasStagedUnder(path: String) = state.stagedFiles.keys.any { it.startsWith("$path/") }

    /** Web: renderFiles() — hides staged deletes, shows staged new files (here: per folder). */
    fun renderFiles() {
        display = buildDisplay()
        adapter.submitList(display)
        if (display.isEmpty()) {
            stateView.showEmpty("This folder is empty.", R.drawable.ic_empty_folder)
        } else {
            stateView.hide()
        }
    }

    private fun buildDisplay(): List<FileEntry> {
        val staged = state.stagedFiles.values
        val deleted = staged.filter { it.action == "delete" }.map { it.path }.toSet()
        val result = state.files.filter { it.path !in deleted }.toMutableList()

        val prefix = if (state.currentPath.isEmpty()) "" else state.currentPath + "/"
        for (sf in staged) {
            if (sf.action != "create" && sf.action != "upload") continue
            if (!sf.path.startsWith(prefix)) continue
            val rel = sf.path.substring(prefix.length)
            val slash = rel.indexOf('/')
            if (slash < 0) {
                if (result.none { it.path == sf.path }) {
                    result.add(FileEntry(sf.name, sf.path, "file", 0L, null))
                }
            } else {
                // Queued inside a sub-folder that does not exist on GitHub yet: show the folder
                val dirName = rel.substring(0, slash)
                val dirPath = prefix + dirName
                if (result.none { it.path == dirPath }) {
                    result.add(FileEntry(dirName, dirPath, "dir", 0L, null))
                }
            }
        }
        return result.sortedWith(
            compareBy<FileEntry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    // ── Click handling / select mode ─────────────────────────────────────────

    private fun onFileClick(file: FileEntry) {
        if (state.selectMode) {
            toggleSelect(file)
            return
        }
        if (file.isDir) {
            state.currentPath = file.path
            loadFiles()
        } else {
            openFileAction(file)
        }
    }

    private fun toggleSelect(file: FileEntry) {
        if (!state.selected.remove(file.path)) state.selected.add(file.path)
        adapter.refreshPath(file.path)
        updateSelectBar()
    }

    private fun enterSelectMode(file: FileEntry) {
        if (!state.selectMode) {
            state.selectMode = true
            updateSelectBar()
        }
        if (state.selected.add(file.path)) {
            adapter.refreshPath(file.path)
            updateSelectBar()
        }
    }

    fun exitSelectMode() {
        state.selectMode = false
        state.selected.clear()
        updateSelectBar()
        adapter.refresh()
    }

    private fun updateSelectBar() {
        selectBar.visibility = if (state.selectMode) View.VISIBLE else View.GONE
        fabAdd.visibility = if (state.selectMode) View.GONE else View.VISIBLE
        textSelectCount.text = I18n.tr("${state.selected.size} selected")
    }

    private fun selectedEntries(): List<FileEntry> =
        state.selected.mapNotNull { path -> display.firstOrNull { it.path == path } }

    // ── Breadcrumb ───────────────────────────────────────────────────────────

    private fun updateBreadcrumb() {
        breadcrumb.removeAllViews()
        val parts = if (state.currentPath.isEmpty()) emptyList() else state.currentPath.split('/')
        addCrumb("Root", "", isLast = parts.isEmpty())
        parts.forEachIndexed { i, part ->
            addSeparator()
            addCrumb(part, parts.take(i + 1).joinToString("/"), isLast = i == parts.lastIndex)
        }
        (breadcrumb.parent as? HorizontalScrollView)?.post { (breadcrumb.parent as HorizontalScrollView).fullScroll(View.FOCUS_RIGHT) }
    }

    private fun addCrumb(label: String, path: String, isLast: Boolean) {
        val crumb = TextView(act).apply {
            text = label
            textSize = 13f
            setPadding(0, act.dp(4), 0, act.dp(4))
            if (isLast) {
                setTextColor(act.color(R.color.text_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            } else {
                setTextColor(act.color(R.color.accent))
                setOnClickListener {
                    if (path != state.currentPath) {
                        state.currentPath = path
                        loadFiles()
                    }
                }
            }
        }
        breadcrumb.addView(crumb)
    }

    private fun addSeparator() {
        val sep = TextView(act).apply {
            text = "›"
            textSize = 13f
            setTextColor(act.color(R.color.text_dim))
            setPadding(act.dp(4), 0, act.dp(4), 0)
        }
        breadcrumb.addView(sep)
    }

    // ── Back navigation ──────────────────────────────────────────────────────

    /** Web: #btn-back-files. Also what the system Back button does on this screen. */
    fun onBack() {
        if (state.selectMode) {
            exitSelectMode()
            return
        }
        if (state.currentPath.isNotEmpty()) {
            state.currentPath = state.currentPath.substringBeforeLast('/', "")
            loadFiles()
            return
        }
        // Leaving the repo: warn about staged changes that were never committed
        if (state.stagedFiles.isNotEmpty()) {
            guardStagedBack {
                state.stagedFiles.clear()
                clearStagedDisk()
                updateStagedBadge()
                ctx.show(Screen.REPOS)
            }
        } else {
            ctx.show(Screen.REPOS)
        }
    }

    // ── Branches ─────────────────────────────────────────────────────────────

    private fun loadBranches() {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        ctx.launchSafe(onError = { /* web: the branch list is optional, ignore failures */ }) {
            val branches = api.listBranches(repo)
            if (state.currentRepo == repo) state.branches = branches
        }
    }

    private fun showBranchDropdown() {
        if (state.branches.isEmpty()) return
        val branchAdapter = object : ArrayAdapter<String>(act, R.layout.item_dropdown, state.branches) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as TextView
                val branch = getItem(position).orEmpty()
                val active = branch == state.currentBranch
                row.text = I18n.tr(if (active) "$branch ✓" else branch)
                row.setTextColor(act.color(if (active) R.color.accent else R.color.text_primary))
                row.typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                return row
            }
        }
        val popup = ListPopupWindow(act)
        popup.anchorView = btnBranch
        popup.setAdapter(branchAdapter)
        popup.isModal = true
        popup.width = act.dp(200)
        popup.setDropDownGravity(Gravity.END)
        popup.verticalOffset = act.dp(4)
        popup.setBackgroundDrawable(ContextCompat.getDrawable(act, R.drawable.bg_dropdown))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            val branch = state.branches[position]
            if (branch != state.currentBranch) {
                state.currentBranch = branch
                textBranch.text = branch
                loadFiles()
            }
        }
        popup.show()
    }

    // ── File action sheet ────────────────────────────────────────────────────

    private fun openImage(file: FileEntry) {
        val repo = state.currentRepo ?: return
        ImageViewerActivity.openRepoFile(act, repo, file.path, state.currentBranch, file.name, file.size)
    }

    private fun openFileAction(file: FileEntry) {
        val items = mutableListOf<Dialogs.SheetItem>()
        // Staged-only files (sha == null) are not on GitHub yet: nothing to open, download or rename
        val onGitHub = file.sha != null
        if (!file.isDir && onGitHub) {
            // Text files open straight into the in-app editor (no download needed); anything else
            // still falls back to saving a copy, since there is nothing in-app to view it with.
            val editable = isTextFile(file.name)
            val image = ImageTypes.isImage(file.name)
            // Pictures are shown in the app straight from GitHub: nothing is saved on the device
            if (image) {
                items.add(Dialogs.SheetItem("View", R.drawable.ic_eye, onClick = { openImage(file) }))
            }
            if (editable || !image) {
                items.add(
                    Dialogs.SheetItem(
                        "Open", if (editable) R.drawable.ic_edit_square else R.drawable.ic_external,
                        onClick = {
                            if (editable) ctx.editor.open(EditingFile(file.path, file.sha, file.name))
                            else saveCopy(file)
                        }
                    )
                )
            }
        }
        if (!file.isDir && onGitHub) {
            items.add(Dialogs.SheetItem("Download", R.drawable.ic_download, onClick = { saveCopy(file) }))
            items.add(Dialogs.SheetItem("Rename", R.drawable.ic_pencil, onClick = { openRenameDialog(file) }))
        }
        items.add(
            Dialogs.SheetItem(
                "Delete", R.drawable.ic_trash,
                danger = true, separatorBefore = items.isNotEmpty(),
                onClick = { confirmDelete(listOf(file)) }
            )
        )
        Dialogs.sheet(act, file.name, items)
    }

    // ── Download (Storage Access Framework: the user picks where it is saved) ─

    /** Web: downloadSingle() / the "Open" action. [afterPick] runs once a destination was chosen. */
    private fun saveCopy(file: FileEntry, afterPick: () -> Unit = {}) {
        act.createDocument(file.name, mimeTypeFor(file.name)) { uri ->
            if (uri == null) return@createDocument
            afterPick()
            downloadSingle(file, uri)
        }
    }

    /** Runs in TransferService: carries on (with a progress notification) if the app is closed. */
    private fun downloadSingle(file: FileEntry, uri: Uri) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val cr = act.applicationContext.contentResolver
        val path = file.path
        val name = file.name
        val size = file.size
        ctx.startDownload(name) { progress ->
            DownloadJobs.file(api, cr, uri, repo, path, branch, name, progress, size)
        }
    }

    private fun downloadSelected() {
        val files = selectedEntries().filter { !it.isDir && it.sha != null }
        if (files.isEmpty()) {
            ctx.toast.error("No files selected")
            return
        }
        if (files.size == 1) {
            saveCopy(files[0]) { exitSelectMode() }
            return
        }
        val repo = state.currentRepo ?: return
        act.createDocument("${repo.repoShortName()}-files.zip", "application/zip") { uri ->
            if (uri == null) return@createDocument
            exitSelectMode()
            downloadZip(files, uri)
        }
    }

    /** Web: downloadMultiple() — several files go into one ZIP, written in the background. */
    private fun downloadZip(files: List<FileEntry>, uri: Uri) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val cr = act.applicationContext.contentResolver
        ctx.startDownload("${files.size} files") { progress ->
            DownloadJobs.filesZip(api, cr, uri, repo, branch, files, progress)
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    private fun confirmDelete(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val count = entries.size
        val hasFolder = entries.any { it.isDir }
        val title = if (count == 1) "Delete \"${entries[0].name}\"?" else "Delete $count items?"
        val body = when {
            hasFolder -> "This will delete the folder and all its contents. This cannot be undone."
            count == 1 -> "This action cannot be undone."
            else -> "This will permanently delete $count items."
        }
        Dialogs.confirm(act, title = title, body = body, positive = "Delete") { doDelete(entries) }
    }

    private fun doDelete(entries: List<FileEntry>) {
        val count = entries.size

        // Stage Mode: queue the deletions in memory — nothing goes to GitHub yet
        if (state.editMode == EditMode.STAGE) {
            entries.forEach { stageDelete(it) }
            updateStagedBadge()
            ctx.toast.success("$count file${if (count > 1) "s" else ""} staged for deletion")
            exitSelectMode()
            renderFiles()
            return
        }

        // Quick Edit: commit immediately
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val targets = entries.map { DeleteTarget(it.path, it.isDir) }
        val message = if (count == 1) "Delete ${entries[0].name}" else "Delete $count items"
        ctx.toast.show("Deleting…")
        ctx.launchSafe {
            api.deleteFiles(repo, branch, targets, message)
            ctx.toast.success("Deleted successfully")
            exitSelectMode()
            loadFiles()
        }
    }

    /**
     * Queues one deletion. Things that exist only in the queue (never committed) are simply
     * dropped from it instead of queuing a delete for a path GitHub has never seen.
     */
    private fun stageDelete(file: FileEntry) {
        if (file.isDir) {
            // Whatever was queued inside the folder goes away with it
            state.stagedFiles.keys.removeAll { it.startsWith(file.path + "/") }
            if (state.files.none { it.path == file.path }) return // folder exists only in the queue
        } else {
            val queued = state.stagedFiles[file.path]
            if (file.sha == null && queued != null && queued.action != "delete") {
                state.stagedFiles.remove(file.path)
                return
            }
        }
        state.stagedFiles[file.path] = StagedFile(
            path = file.path, name = file.name, sha = file.sha,
            type = file.type, action = "delete", content = null
        )
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    private fun openRenameDialog(file: FileEntry) {
        Dialogs.prompt(
            act, title = "Rename", label = "New name", initial = file.name, positive = "Rename"
        ) { text ->
            val newName = text.trim()
            val dir = if (file.path.contains('/')) file.path.substring(0, file.path.lastIndexOf('/') + 1) else ""
            val newPath = dir + newName
            if (newPath != file.path) renameFile(file, newPath)
        }
    }

    private fun renameFile(file: FileEntry, newPath: String) {
        // A queued change is keyed by the old path and would re-create the old file at commit time
        if (state.stagedFiles.containsKey(file.path)) {
            ctx.toast.error("Commit or discard the staged change for this file before renaming it", 5000)
            return
        }
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        ctx.toast.show("Renaming…")
        ctx.launchSafe {
            api.renameFile(repo, branch, file.path, newPath)
            ctx.toast.success("Renamed")
            loadFiles()
        }
    }

    // ── FAB: upload / new file / new folder ──────────────────────────────────

    private fun openFabSheet() {
        val title = state.currentPath.ifEmpty { "/" }
        Dialogs.sheet(
            act, title,
            listOf(
                Dialogs.SheetItem("Upload Files", R.drawable.ic_upload, onClick = {
                    ctx.upload.open(UploadScreen.Mode.FILES)
                }),
                Dialogs.SheetItem("Upload ZIP", R.drawable.ic_archive, onClick = {
                    ctx.upload.open(UploadScreen.Mode.ZIP)
                }),
                Dialogs.SheetItem("New File", R.drawable.ic_file_plus, onClick = { openNewFileDialog() }),
                Dialogs.SheetItem("New Folder", R.drawable.ic_folder_plus, onClick = { openNewFolderDialog() })
            )
        )
    }

    private fun openNewFileDialog() {
        Dialogs.prompt(
            act, title = "New File", label = "File name", hint = "example.html", positive = "Create"
        ) { text -> createFile(text.trim()) }
    }

    private fun createFile(name: String) {
        val path = if (state.currentPath.isNotEmpty()) "${state.currentPath}/$name" else name

        if (state.editMode == EditMode.STAGE) {
            state.stagedFiles[path] = StagedFile(path, name, null, "file", "create", "")
            updateStagedBadge()
            ctx.toast.success("Staged new file \"$name\" — press Commit All to apply")
            renderFiles()
            return
        }

        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        ctx.toast.show("Creating…")
        ctx.launchSafe {
            api.putFile(repo, branch, path, "", null, "Create $name")
            ctx.toast.success("Created $name")
            // Open the editor for the new file once the listing shows it
            loadFiles {
                val entry = state.files.firstOrNull { it.path == path }
                if (entry != null && isTextFile(name)) {
                    ctx.editor.open(EditingFile(path, entry.sha, name))
                }
            }
        }
    }

    private fun openNewFolderDialog() {
        Dialogs.prompt(
            act, title = "New Folder", label = "Folder name", hint = "assets", positive = "Create"
        ) { text -> createFolder(text.trim()) }
    }

    private fun createFolder(name: String) {
        val keepPath = if (state.currentPath.isNotEmpty()) "${state.currentPath}/$name/.gitkeep" else "$name/.gitkeep"

        if (state.editMode == EditMode.STAGE) {
            state.stagedFiles[keepPath] = StagedFile(keepPath, "$name/.gitkeep", null, "file", "create", "")
            updateStagedBadge()
            ctx.toast.success("Staged new folder \"$name\" — press Commit All to apply")
            renderFiles()
            return
        }

        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        ctx.toast.show("Creating folder…")
        ctx.launchSafe {
            api.putFile(repo, branch, keepPath, "", null, "Create folder $name")
            ctx.toast.success("Folder created")
            loadFiles()
        }
    }

    // ── Stage Mode: "Commit all" ─────────────────────────────────────────────

    /** Web: updateStagedBadge() — the "Commit all (N)" button only exists in Stage Mode. */
    fun updateStagedBadge() {
        val count = state.stagedFiles.size
        when {
            state.editMode == EditMode.STAGE && count > 0 -> {
                textStagedBadge.text = count.toString()
                textStagedBadge.visibility = View.VISIBLE
                btnCommitStaged.visibility = View.VISIBLE
                btnCommitStaged.alpha = 1f
                btnCommitStaged.isEnabled = true
            }
            state.editMode == EditMode.STAGE -> {
                // Kept visible at 0 but greyed out and not tappable
                textStagedBadge.visibility = View.GONE
                btnCommitStaged.visibility = View.VISIBLE
                btnCommitStaged.alpha = 0.4f
                btnCommitStaged.isEnabled = false
            }
            else -> {
                textStagedBadge.visibility = View.GONE
                btnCommitStaged.visibility = View.GONE
            }
        }
    }

    /** Web: guardStagedBack() — leaving the repo with uncommitted staged files. */
    private fun guardStagedBack(onDiscard: () -> Unit) {
        val body = SpannableStringBuilder("You have ").also {
            val start = it.length
            it.append(state.stagedFiles.size.toString())
            it.setSpan(StyleSpan(Typeface.BOLD), start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            it.append(" staged file(s) that haven't been committed yet. If you leave, they will be discarded.")
        }
        Dialogs.confirm(
            act,
            title = "Uncommitted staged changes",
            body = body,
            positive = "Commit First",
            negative = "Discard & Leave",
            positiveStyle = Dialogs.ButtonStyle.PRIMARY,
            onNegative = onDiscard
        ) { openCommitStagedDialog() }
    }

    /** Web: openCommitStagedDialog(). */
    fun openCommitStagedDialog() {
        val count = state.stagedFiles.size
        if (count == 0) {
            ctx.toast.error("No staged files")
            return
        }
        val staged = state.stagedFiles.values.toList()
        val (dialog, view) = Dialogs.create(act, R.layout.dialog_staged_commit)

        view.findViewById<TextView>(R.id.textStagedCount).text = count.toString()
        view.findViewById<TextView>(R.id.textStagedPlural).text = I18n.tr(if (count == 1) "file" else "files")

        // List of what will be committed, with the action of each change
        val actionLabel = mapOf("delete" to "Delete", "create" to "New", "upload" to "Upload", "edit" to "Edit")
        val rows = view.findViewById<LinearLayout>(R.id.stagedList)
        val inflater = LayoutInflater.from(act)
        for (file in staged.take(MAX_STAGED_ROWS)) {
            val row = inflater.inflate(R.layout.item_staged_file, rows, false)
            row.findViewById<TextView>(R.id.textStagedAction).text = actionLabel[file.action] ?: "Edit"
            row.findViewById<TextView>(R.id.textStagedName).text = file.name
            rows.addView(row)
        }
        if (staged.size > MAX_STAGED_ROWS) {
            val row = inflater.inflate(R.layout.item_staged_file, rows, false)
            row.findViewById<TextView>(R.id.textStagedName).text = I18n.tr("… and ${staged.size - MAX_STAGED_ROWS} more")
            rows.addView(row)
        }
        if (count > 5) {
            // Long queues scroll inside the dialog instead of stretching it
            view.findViewById<ScrollView>(R.id.stagedScroll).layoutParams.height = act.dp(200)
        }

        // Default commit message that says what is in the queue
        val defaultMessage = if (count == 1) {
            val f = staged[0]
            val verb = when (f.action) {
                "delete" -> "Delete"
                "create" -> "Create"
                else -> "Update"
            }
            "$verb ${f.name}"
        } else {
            val parts = mutableListOf<String>()
            if (staged.any { it.action == "create" || it.action == "upload" }) parts.add("add")
            if (staged.any { it.action == "edit" }) parts.add("update")
            if (staged.any { it.action == "delete" }) parts.add("delete")
            "${parts.joinToString(", ")} $count files"
        }
        val input = view.findViewById<EditText>(R.id.inputStagedMsg)
        input.setText(defaultMessage)
        input.setSelection(input.text.length)

        fun confirm() {
            val message = input.text.toString()
            dialog.dismiss()
            doCommitStaged(message)
        }
        view.findViewById<MaterialButton>(R.id.btnNegative).setOnClickListener { dialog.cancel() }
        view.findViewById<MaterialButton>(R.id.btnPositive).setOnClickListener { confirm() }
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirm()
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    /**
     * Web: doCommitStaged() — uploads/edits first, then the deletions. The clone/commit/push runs in
     * TransferService, so it carries on (with a progress notification) if the app is closed.
     */
    private fun doCommitStaged(rawMessage: String) {
        if (committing) return
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val all = state.stagedFiles.values.toList()
        if (all.isEmpty()) return
        val message = rawMessage.trim().ifEmpty { "Update ${all.size} files" }

        val toDelete = all.filter { it.action == "delete" }
        val toUpsert = all.filter { it.action != "delete" } // create, upload, edit
        val cacheDir = act.cacheDir
        // Paths already on GitHub: if the deletions fail afterwards, a retry must not re-send these
        val pushed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val count = all.size
        val plural = if (count > 1) "s" else ""

        committing = true
        ctx.toast.show("Committing — progress is in the notification")
        ctx.startTransfer(
            TransferKind.UPLOAD, "$count change$plural", "repo:$repo",
            onUpdate = { t ->
                if (!t.isRunning) {
                    committing = false
                    synchronized(pushed) { pushed.toList() }.forEach { state.stagedFiles.remove(it) }
                    if (t.state == TransferState.SUCCESS) toDelete.forEach { state.stagedFiles.remove(it.path) }
                    updateStagedBadge()
                    when (t.state) {
                        TransferState.SUCCESS -> {
                            ctx.toast.success("Committed $count change$plural")
                            loadFiles()
                        }
                        TransferState.CANCELLED -> ctx.toast.show("Commit cancelled")
                        else -> ctx.toast.error(t.message ?: "Commit failed")
                    }
                }
            }
        ) { progress ->
            api.onRateLimitWait = { sec ->
                if (sec > 0) progress.label("GitHub rate limit — waiting ${sec}s, will continue…")
            }
            try {
                if (toUpsert.isNotEmpty()) {
                    // One clone, one commit, one push for all staged files (like Termux)
                    val sources = toUpsert.map { sf ->
                        val disk = sf.diskPath
                        PushSource(sf.path) {
                            if (disk != null) File(disk).inputStream()
                            else ByteArrayInputStream(Base64.decode(sf.content.orEmpty(), Base64.DEFAULT))
                        }
                    }
                    pushSources(api, cacheDir, repo, branch, message, sources, progress)
                    toUpsert.forEach { sf ->
                        pushed.add(sf.path)
                        sf.diskPath?.let { File(it).delete() }
                    }
                }
                if (toDelete.isNotEmpty()) {
                    progress.update(-1f, "Deleting files…")
                    val targets = toDelete.map { DeleteTarget(it.path, it.type == "dir") }
                    api.deleteFiles(repo, branch, targets, message)
                }
                TransferOutcome("Committed $count change$plural")
            } finally {
                api.onRateLimitWait = null
            }
        }
    }

    private companion object {
        const val MAX_STAGED_ROWS = 200

        // Web: isTextFile() — the Edit action is only offered for these
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "html", "htm", "css", "js", "ts", "jsx", "tsx", "json", "xml",
            "yaml", "yml", "toml", "ini", "cfg", "conf", "sh", "bash", "zsh", "py", "rb",
            "php", "java", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "kt", "vue",
            "svelte", "sql", "gitignore", "env", "lock", "log", "csv", "svg", "dockerfile",
            "makefile", "editorconfig", "prettierrc", "eslintrc", "babelrc"
        )

        fun isTextFile(name: String): Boolean {
            val ext = name.substringAfterLast('.').lowercase()
            return ext in TEXT_EXTENSIONS || !name.contains('.')
        }
    }
}
