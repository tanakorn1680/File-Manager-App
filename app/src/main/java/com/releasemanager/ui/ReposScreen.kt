package com.releasemanager.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.AppSettings
import com.releasemanager.data.EditMode
import com.releasemanager.data.RepoRef
import com.releasemanager.data.RepoSortKey
import com.releasemanager.data.sortedForDisplay
import com.releasemanager.network.DownloadJobs
import kotlinx.coroutines.Job

/** Web: #screen-repos — repo list with search, create, delete, visibility and the edit-mode sheet. */
class ReposScreen(private val ctx: AppContext) {

    private val state = ctx.state
    private val root: View = ctx.activity.findViewById(R.id.screenRepos)
    private val inputSearch: EditText = root.findViewById(R.id.inputSearch)
    private val list: RecyclerView = root.findViewById(R.id.listRepos)
    private val stateView = StateView(root)

    private val adapter = RepoAdapter(
        onOpen = { openRepo(it) },
        onEdit = { openEditModeSheet(it) },
        onMore = { openRepoAction(it) }
    )

    private var loadJob: Job? = null

    init {
        list.layoutManager = LinearLayoutManager(ctx.activity)
        list.adapter = adapter

        root.findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { openMenuSheet() }

        inputSearch.onTextChanged { render() }
        inputSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                v.hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    fun enter() {
        ctx.show(Screen.REPOS)
        load()
    }

    /** After Disconnect: nothing from the old account stays in the list or the search box. */
    fun reset() {
        loadJob?.cancel()
        inputSearch.setText("")
        adapter.submitList(emptyList())
    }

    // ── Loading / rendering ──────────────────────────────────────────────────

    fun load() {
        val api = ctx.api ?: return
        stateView.showLoading("Loading repositories…")
        adapter.submitList(emptyList())
        loadJob?.cancel()
        loadJob = ctx.launchSafe(onError = { stateView.showError(it.userMessage()) { load() } }) {
            state.repos = api.listRepos()
            render()
        }
    }

    /** Web: renderRepos(filtered) — the search matches the full name and the description. */
    private fun render() {
        val q = inputSearch.text.toString().lowercase()
        val filtered = state.repos.filter {
            it.fullName.lowercase().contains(q) || (it.description ?: "").lowercase().contains(q)
        }
        adapter.submitList(filtered.sortedForDisplay(state.repoSortKey, state.repoSortAsc))
        if (filtered.isEmpty()) stateView.showEmpty("No repositories found.") else stateView.hide()
    }

    // ── Menu (the 3-line button) ─────────────────────────────────────────────

    private fun openMenuSheet() {
        Dialogs.sheet(
            ctx.activity,
            "More options",
            listOf(
                Dialogs.SheetItem("New repository", R.drawable.ic_plus, onClick = { openCreateRepoDialog() }),
                Dialogs.SheetItem("Sort", R.drawable.ic_sort, onClick = { openSortSheet() }),
                Dialogs.SheetItem("Refresh", R.drawable.ic_refresh, onClick = { load() }),
                Dialogs.SheetItem(
                    "Settings", R.drawable.ic_settings,
                    separatorBefore = true,
                    onClick = { openSettingsSheet() }
                ),
                Dialogs.SheetItem(
                    "Disconnect", R.drawable.ic_logout,
                    danger = true,
                    onClick = { confirmDisconnect() }
                )
            )
        )
    }

    private fun openSettingsSheet() {
        Dialogs.sheet(
            ctx.activity,
            "Settings",
            listOf(
                Dialogs.SheetItem(
                    "Language", R.drawable.ic_globe,
                    sub = if (I18n.isThai) "ไทย" else "English",
                    onClick = { openLanguageSheet() }
                )
            )
        )
    }

    private fun openLanguageSheet() {
        fun item(code: String, name: String) = Dialogs.SheetItem(
            (if (I18n.lang == code) "✓ " else "") + name, R.drawable.ic_globe,
            onClick = { setLanguage(code) }
        )
        Dialogs.sheet(
            ctx.activity,
            "Language",
            listOf(item(AppSettings.LANG_EN, "English"), item(AppSettings.LANG_TH, "ไทย"))
        )
    }

    /** The texts are inflated per language, so the screen is rebuilt; the saved token reconnects on its own. */
    private fun setLanguage(code: String) {
        if (I18n.lang == code) return
        I18n.setLanguage(ctx.activity, code)
        ctx.activity.recreate()
    }

    // ── Sort ─────────────────────────────────────────────────────────────────

    private fun openSortSheet() {
        val items = RepoSortKey.values().map { key ->
            val selected = key == state.repoSortKey
            Dialogs.SheetItem(
                (if (selected) "✓ " else "") + key.label, R.drawable.ic_sort,
                onClick = {
                    state.repoSortKey = key
                    render()
                }
            )
        }.toMutableList()
        val order = if (state.repoSortAsc) "Low → High (ascending)" else "High → Low (descending)"
        items.add(
            Dialogs.SheetItem(
                "Order: $order", R.drawable.ic_refresh,
                sub = "Tap to reverse",
                separatorBefore = true,
                onClick = {
                    state.repoSortAsc = !state.repoSortAsc
                    render()
                }
            )
        )
        Dialogs.sheet(ctx.activity, "Sort repositories", items)
    }


    private fun openRepo(repo: RepoRef) {
        ctx.files.openRepo(repo.fullName, repo.defaultBranch)
    }

    // ── Repo action sheet (long-press / three dots) ──────────────────────────

    private fun openRepoAction(repo: RepoRef) {
        Dialogs.sheet(
            ctx.activity,
            repo.fullName.repoShortName(),
            listOf(
                Dialogs.SheetItem("Open", R.drawable.ic_arrow_right, onClick = { openRepo(repo) }),
                Dialogs.SheetItem(
                    "Details", R.drawable.ic_repo,
                    onClick = { RepoDetailsSheet.show(ctx, repo) }
                ),
                Dialogs.SheetItem(
                    "Download as ZIP", R.drawable.ic_download,
                    onClick = { downloadRepoZip(repo) }
                ),
                Dialogs.SheetItem(
                    "Change Visibility", R.drawable.ic_eye,
                    onClick = { confirmVisibility(repo) }
                ),
                Dialogs.SheetItem(
                    "Rename Repository", R.drawable.ic_pencil,
                    onClick = { openRenameRepoDialog(repo) }
                ),
                Dialogs.SheetItem(
                    "Delete Repository", R.drawable.ic_trash,
                    danger = true, separatorBefore = true,
                    onClick = { openDeleteRepoDialog(repo) }
                )
            )
        )
    }

    /** Save-as picker, then the ZIP is written by TransferService (progress notification, survives closing the app). */
    private fun downloadRepoZip(repo: RepoRef) {
        val api = ctx.api ?: return
        val branch = repo.defaultBranch
        val fullName = repo.fullName
        val fileName = fullName.repoShortName() + (if (branch.isNotBlank()) "-$branch" else "") + ".zip"
        ctx.activity.createDocument(fileName, "application/zip") { uri ->
            if (uri == null) return@createDocument
            val cr = ctx.activity.applicationContext.contentResolver
            ctx.startDownload(fileName) { progress ->
                DownloadJobs.repoZip(api, cr, uri, fullName, branch, fileName, progress)
            }
        }
    }

    // ── Edit-mode sheet (pencil button) ──────────────────────────────────────

    private fun openEditModeSheet(repo: RepoRef) {
        Dialogs.sheet(
            ctx.activity,
            repo.fullName.repoShortName(),
            listOf(
                Dialogs.SheetItem(
                    "Quick Edit", R.drawable.ic_edit_square,
                    sub = "แก้ทีละไฟล์ commit ทันที",
                    iconBoxBgRes = R.drawable.bg_edit_mode_icon,
                    iconTintRes = R.color.accent,
                    onClick = {
                        state.editMode = EditMode.QUICK
                        openRepo(repo)
                    }
                ),
                Dialogs.SheetItem(
                    "Stage Mode", R.drawable.ic_check_square,
                    sub = "แก้หลายไฟล์ค้างไว้ commit ทีเดียว",
                    iconBoxBgRes = R.drawable.bg_edit_mode_icon_stage,
                    iconTintRes = R.color.warning_text,
                    onClick = {
                        state.editMode = EditMode.STAGE
                        openRepo(repo)
                    }
                )
            )
        )
    }

    // ── Visibility ───────────────────────────────────────────────────────────

    private fun confirmVisibility(repo: RepoRef) {
        val toPrivate = !repo.private
        val current = I18n.tr(if (repo.private) "Private" else "Public")
        val target = I18n.tr(if (toPrivate) "Private" else "Public")
        val warning = if (toPrivate) {
            "The repository will no longer be publicly accessible."
        } else {
            "Anyone on the internet will be able to see this repository."
        }

        val body = SpannableStringBuilder()
        body.appendBold(repo.fullName.repoShortName())
        body.append("\n\n" + I18n.tr("Current:") + " ")
        body.appendBold(current)
        body.append(" → ")
        body.appendBold(target)
        body.append("\n\n")
        val warnStart = body.length
        body.append(I18n.tr(warning))
        body.setSpan(
            ForegroundColorSpan(ctx.activity.color(R.color.text_muted)),
            warnStart, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        Dialogs.confirm(
            ctx.activity,
            title = "Change Visibility",
            body = body,
            positive = "Make $target",
            positiveStyle = if (toPrivate) Dialogs.ButtonStyle.OUTLINE else Dialogs.ButtonStyle.DANGER
        ) { doSetVisibility(repo, toPrivate) }
    }

    private fun doSetVisibility(repo: RepoRef, toPrivate: Boolean) {
        val api = ctx.api ?: return
        ctx.toast.show("Updating…")
        ctx.launchSafe {
            api.setVisibility(repo.fullName, toPrivate)
            state.repos = state.repos.map {
                if (it.fullName == repo.fullName) it.copy(private = toPrivate) else it
            }
            ctx.toast.success("Set to ${if (toPrivate) "Private" else "Public"}")
            render()
        }
    }

    // ── Rename repository ────────────────────────────────────────────────────

    private fun openRenameRepoDialog(repo: RepoRef) {
        val shortName = repo.fullName.repoShortName()
        Dialogs.prompt(
            ctx.activity, title = "Rename Repository", label = "New name",
            initial = shortName, positive = "Rename"
        ) { text ->
            val newName = text.trim()
            when {
                newName == shortName -> Unit
                !NAME_REGEX.matches(newName) ->
                    ctx.toast.error("Use letters, numbers, hyphens, underscores, or dots only.")
                else -> renameRepo(repo, newName)
            }
        }
    }

    private fun renameRepo(repo: RepoRef, newName: String) {
        val api = ctx.api ?: return
        ctx.toast.show("Renaming…")
        ctx.launchSafe {
            val renamed = api.renameRepo(repo.fullName, newName)
            state.repos = state.repos.map { if (it.fullName == repo.fullName) renamed else it }
            // The repo may be the one opened earlier: keep pointing at it under its new name
            if (state.currentRepo == repo.fullName) state.currentRepo = renamed.fullName
            render()
            ctx.toast.success("Renamed to ${renamed.fullName.repoShortName()}")
        }
    }

    // ── Create repository ────────────────────────────────────────────────────

    private fun openCreateRepoDialog() {
        val (dialog, view) = Dialogs.create(ctx.activity, R.layout.dialog_create_repo)
        val label = view.findViewById<TextView>(R.id.labelRepoName)
        val inputName = view.findViewById<EditText>(R.id.inputRepoName)
        val hint = view.findViewById<TextView>(R.id.textNameHint)
        val inputDesc = view.findViewById<EditText>(R.id.inputRepoDesc)
        val switchPrivate = view.findViewById<SwitchCompat>(R.id.switchPrivate)
        val switchInit = view.findViewById<SwitchCompat>(R.id.switchInit)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnNegative)
        val btnCreate = view.findViewById<MaterialButton>(R.id.btnPositive)

        // "Repository name *" with the star in the danger colour
        label.text = SpannableStringBuilder(I18n.tr("Repository name ")).also {
            val start = it.length
            it.append("*")
            it.setSpan(
                ForegroundColorSpan(ctx.activity.color(R.color.danger)),
                start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Web: validate the repo name while typing
        inputName.onTextChanged {
            val value = inputName.text.toString().trim()
            when {
                value.isEmpty() -> hint.text = ""
                NAME_REGEX.matches(value) -> {
                    hint.text = I18n.tr("✓ Valid name")
                    hint.setTextColor(ctx.activity.color(R.color.success))
                }
                else -> {
                    hint.text = I18n.tr("Use letters, numbers, hyphens, underscores, or dots only.")
                    hint.setTextColor(ctx.activity.color(R.color.danger))
                }
            }
        }

        fun submit() {
            val name = inputName.text.toString().trim()
            if (name.isEmpty()) {
                ctx.toast.error("Repository name is required")
                return
            }
            if (!NAME_REGEX.matches(name)) {
                ctx.toast.error("Invalid repository name")
                return
            }
            val description = inputDesc.text.toString().trim()
            val isPrivate = switchPrivate.isChecked
            val autoInit = switchInit.isChecked
            dialog.dismiss()
            createRepo(name, description, isPrivate, autoInit)
        }

        btnCancel.setOnClickListener { dialog.cancel() }
        btnCreate.setOnClickListener { submit() }
        inputName.imeOptions = EditorInfo.IME_ACTION_DONE
        inputName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        inputName.requestFocus()
    }

    private fun createRepo(name: String, description: String, isPrivate: Boolean, autoInit: Boolean) {
        val api = ctx.api ?: return
        ctx.toast.show("Creating repository…")
        ctx.launchSafe {
            val repo = api.createRepo(name, description, isPrivate, autoInit)
            state.repos = listOf(repo) + state.repos
            render()
            ctx.toast.success("Repository \"$name\" created")
        }
    }

    // ── Delete repository (type the name to confirm) ─────────────────────────

    private fun openDeleteRepoDialog(repo: RepoRef) {
        val shortName = repo.fullName.repoShortName()
        val (dialog, view) = Dialogs.create(ctx.activity, R.layout.dialog_delete_repo)
        val bodyText = view.findViewById<TextView>(R.id.dialogBody)
        val input = view.findViewById<EditText>(R.id.inputConfirm)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnNegative)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnPositive)

        bodyText.text = SpannableStringBuilder().also {
            if (I18n.isThai) {
                it.append("จะลบ ")
                it.appendBold("\"$shortName\"")
                it.append(" และเนื้อหาทั้งหมดอย่างถาวร ")
                it.appendBold("ไม่สามารถย้อนกลับได้")
            } else {
                it.append("This will permanently delete ")
                it.appendBold("\"$shortName\"")
                it.append(" and all its contents. ")
                it.appendBold("This cannot be undone.")
            }
        }
        input.hint = shortName
        btnDelete.isEnabled = false

        input.onTextChanged {
            val match = input.text.toString() == shortName
            btnDelete.isEnabled = match
            input.setBackgroundResource(if (match) R.drawable.bg_input_danger else R.drawable.bg_input)
        }

        btnCancel.setOnClickListener { dialog.cancel() }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            deleteRepo(repo)
        }
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        input.requestFocus()
    }

    private fun deleteRepo(repo: RepoRef) {
        val api = ctx.api ?: return
        ctx.toast.show("Deleting repository…")
        ctx.launchSafe {
            api.deleteRepo(repo.fullName)
            state.repos = state.repos.filter { it.fullName != repo.fullName }
            render()
            ctx.toast.success("Repository deleted")
        }
    }

    // ── Disconnect (app only) ────────────────────────────────────────────────

    private fun confirmDisconnect() {
        Dialogs.confirm(
            ctx.activity,
            title = "Disconnect?",
            body = "This removes the saved token from this device. You will need to enter it again to reconnect.",
            positive = "Disconnect"
        ) { ctx.disconnect() }
    }

    private fun SpannableStringBuilder.appendBold(text: String) {
        val start = length
        append(text)
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private companion object {
        val NAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")
    }
}
