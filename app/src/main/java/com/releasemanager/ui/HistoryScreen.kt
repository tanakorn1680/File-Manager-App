package com.releasemanager.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.releasemanager.R
import com.releasemanager.data.CommitInfo
import com.releasemanager.data.HistoryNames
import kotlinx.coroutines.Job

/**
 * App-only screen: the edit history (commits) of the open repository and branch.
 * Tap a version to name it or to go back to it ("Restore"); the arrow in the top bar undoes
 * the last change. Going back never deletes anything — it adds a new commit that looks like
 * the chosen version (see GitHubApi.restoreToCommit), so a restore can itself be undone.
 */
class HistoryScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenHistory)
    private val textTitle: TextView = root.findViewById(R.id.textTitle)
    private val list: RecyclerView = root.findViewById(R.id.listHistory)
    private val stateView = StateView(root)
    private val swipe: SwipeRefreshLayout = root.findViewById(R.id.swipeHistory)
    private val names = HistoryNames(act)

    private val commits = mutableListOf<CommitInfo>()
    private val adapter = HistoryAdapter(
        nameOf = { commit -> state.currentRepo?.let { names.get(it, commit.sha) } },
        onClick = { openCommitSheet(it) }
    )

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var page = 1
    private var hasMore = true
    private var loadingMore = false
    private var restoring = false

    init {
        val layoutManager = LinearLayoutManager(act)
        list.layoutManager = layoutManager
        list.adapter = adapter
        // Older versions load as the list is scrolled towards its end
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() >= commits.size - 5) loadMore()
            }
        })

        swipe.setup(list) { load(silent = true) }
        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { ctx.show(Screen.FILES) }
        root.findViewById<ImageButton>(R.id.btnRefreshHistory).setOnClickListener { load() }
        root.findViewById<ImageButton>(R.id.btnUndoLast).setOnClickListener { undoLast() }
    }

    fun enter() {
        ctx.show(Screen.HISTORY)
        textTitle.text = I18n.tr("History · ${state.currentBranch}")
        load()
    }

    fun load(silent: Boolean = false) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch

        if (!silent) {
            swipe.isRefreshing = false
            stateView.showLoading()
            commits.clear()
            adapter.submitList(emptyList())
            page = 1
            hasMore = true
            loadingMore = false
        }
        loadJob?.cancel()
        moreJob?.cancel()
        loadJob = ctx.launchSafe(onError = {
            swipe.isRefreshing = false
            if (silent) ctx.toast.error(it.userMessage()) else stateView.showError(it.userMessage()) { load() }
        }) {
            val first = api.listCommits(repo, branch, 1, PAGE_SIZE)
            commits.clear()
            commits.addAll(first)
            page = 1
            hasMore = first.size >= PAGE_SIZE
            loadingMore = false
            adapter.submitList(commits.toList())
            if (commits.isEmpty()) stateView.showEmpty("No commits yet.") else stateView.hide()
            swipe.isRefreshing = false
        }
    }

    private fun loadMore() {
        if (!hasMore || loadingMore || commits.isEmpty()) return
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val next = page + 1
        loadingMore = true
        moreJob = ctx.launchSafe {
            try {
                val more = api.listCommits(repo, branch, next, PAGE_SIZE)
                commits.addAll(more)
                page = next
                hasMore = more.size >= PAGE_SIZE
                adapter.submitList(commits.toList())
            } finally {
                loadingMore = false
            }
        }
    }

    // ── One version: name it / go back to it ─────────────────────────────────

    private fun labelOf(commit: CommitInfo): String {
        val repo = state.currentRepo
        val name = if (repo != null) names.get(repo, commit.sha) else null
        return name ?: commit.title.ifBlank { commit.shortSha }
    }

    private fun openCommitSheet(commit: CommitInfo) {
        val repo = state.currentRepo ?: return
        val name = names.get(repo, commit.sha)
        val isCurrent = commits.firstOrNull()?.sha == commit.sha

        val items = mutableListOf<Dialogs.SheetItem>()
        items.add(
            Dialogs.SheetItem(
                label = if (name == null) "Name this version" else "Rename",
                iconRes = R.drawable.ic_pencil,
                onClick = { promptName(commit, name) }
            )
        )
        if (name != null) {
            items.add(
                Dialogs.SheetItem(
                    label = "Remove name",
                    iconRes = R.drawable.ic_trash,
                    onClick = {
                        names.clear(repo, commit.sha)
                        adapter.refresh()
                        ctx.toast.success("Name removed")
                    }
                )
            )
        }
        if (!isCurrent) {
            items.add(
                Dialogs.SheetItem(
                    label = "Restore this version (Undo)",
                    iconRes = R.drawable.ic_undo,
                    sub = "Adds a new commit that matches this version — nothing is deleted",
                    separatorBefore = true,
                    onClick = { confirmRestore(commit, undoLast = false) }
                )
            )
        }
        items.add(
            Dialogs.SheetItem(
                label = "Open on GitHub",
                iconRes = R.drawable.ic_external,
                separatorBefore = true,
                onClick = { openInBrowser(commit.htmlUrl) }
            )
        )
        Dialogs.sheet(act, labelOf(commit).take(60), items)
    }

    private fun promptName(commit: CommitInfo, current: String?) {
        val repo = state.currentRepo ?: return
        Dialogs.prompt(
            act,
            title = "Name this version",
            label = "Name",
            initial = current.orEmpty(),
            hint = "e.g. Before UI redesign",
            positive = "Save"
        ) { text ->
            names.set(repo, commit.sha, text)
            adapter.refresh()
            ctx.toast.success("Saved")
        }
    }

    // ── Restore / Undo ───────────────────────────────────────────────────────

    /** Top-bar arrow: go back to the version just before the current one. */
    private fun undoLast() {
        if (commits.size < 2) {
            ctx.toast.error(if (commits.isEmpty()) "History is still loading" else "Nothing to undo")
            return
        }
        confirmRestore(commits[1], undoLast = true)
    }

    private fun confirmRestore(target: CommitInfo, undoLast: Boolean) {
        val branch = state.currentBranch
        val staged = state.stagedFiles.size
        val body = buildString {
            append(if (undoLast) "Go back to the version before the last change:\n" else "Go back to:\n")
            append("\"${labelOf(target)}\" (${target.shortSha})")
            append("\n\nThe files of \"$branch\" will look exactly as they did then. ")
            append("This adds a new commit on top; nothing is deleted from the history, so you can undo it again.")
            if (staged > 0) append("\n\nThe $staged staged change(s) in your queue are not affected.")
        }
        Dialogs.confirm(
            act,
            title = if (undoLast) "Undo last change?" else "Restore this version?",
            body = body,
            positive = if (undoLast) "Undo" else "Restore",
            positiveStyle = Dialogs.ButtonStyle.PRIMARY
        ) { doRestore(target) }
    }

    private fun doRestore(target: CommitInfo) {
        if (restoring) return
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val branch = state.currentBranch
        val message = "Restore to \"${labelOf(target)}\" (${target.shortSha})"
        restoring = true
        ctx.toast.show("Restoring…")
        ctx.launchSafe {
            try {
                api.restoreToCommit(repo, branch, target.sha, message)
                ctx.toast.success("Restored")
                // The files may have changed anywhere: show the root of the repo again
                state.currentPath = ""
                ctx.files.loadFiles()
                load()
            } finally {
                restoring = false
            }
        }
    }

    private fun openInBrowser(url: String) {
        if (url.isBlank()) return
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            ctx.toast.error("No app can open this link")
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
