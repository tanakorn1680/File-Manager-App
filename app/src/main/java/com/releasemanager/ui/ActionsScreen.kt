package com.releasemanager.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.widget.ImageButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.RunArtifact
import com.releasemanager.data.WorkflowRun
import com.releasemanager.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * App-only screen (the web has no Actions view): the workflow runs of the open repository.
 * Tapping a run lists the artifacts it produced (e.g. a built APK); tapping an artifact saves its ZIP.
 */
class ActionsScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenActions)
    private val list: RecyclerView = root.findViewById(R.id.listRuns)
    private val stateView = StateView(root)

    private val adapter = RunAdapter { openRun(it) }

    private var loadJob: Job? = null
    private var runJob: Job? = null

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter

        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { ctx.show(Screen.FILES) }
        root.findViewById<ImageButton>(R.id.btnRefreshRuns).setOnClickListener { load() }
    }

    fun enter() {
        ctx.show(Screen.ACTIONS)
        load()
    }

    fun load() {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        stateView.showLoading()
        adapter.submitList(emptyList())
        loadJob?.cancel()
        runJob?.cancel()
        loadJob = ctx.launchSafe(onError = { stateView.showError(it.userMessage()) { load() } }) {
            val runs = api.listWorkflowRuns(repo)
            adapter.submitList(runs)
            if (runs.isEmpty()) {
                stateView.showEmpty("No workflow runs yet.")
            } else {
                stateView.hide()
            }
        }
    }

    // ── One run: its artifacts + a link to the run on GitHub ─────────────────

    private fun openRun(run: WorkflowRun) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        runJob?.cancel()
        ctx.toast.show("Loading artifacts…")
        runJob = ctx.launchSafe {
            val artifacts = api.listRunArtifacts(repo, run.id)
            // The user may have left the screen while this was loading
            if (act.current != Screen.ACTIONS) return@launchSafe
            showRunSheet(repo, run, artifacts)
        }
    }

    private fun showRunSheet(repo: String, run: WorkflowRun, artifacts: List<RunArtifact>) {
        val items = mutableListOf<Dialogs.SheetItem>()
        for (artifact in artifacts) {
            items.add(
                Dialogs.SheetItem(
                    label = artifact.name,
                    iconRes = R.drawable.ic_download,
                    sub = if (artifact.expired) "Expired — GitHub has deleted it" else formatSize(artifact.size),
                    onClick = {
                        if (artifact.expired) {
                            ctx.toast.error("This artifact has expired")
                        } else {
                            saveArtifact(repo, artifact)
                        }
                    }
                )
            )
        }
        items.add(
            Dialogs.SheetItem(
                label = "Open on GitHub",
                iconRes = R.drawable.ic_external,
                sub = if (artifacts.isEmpty()) "No artifacts for this run" else null,
                separatorBefore = artifacts.isNotEmpty(),
                onClick = { openInBrowser(run.htmlUrl) }
            )
        )
        Dialogs.sheet(act, run.title.ifBlank { run.workflowName }, items)
    }

    private fun openInBrowser(url: String) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            ctx.toast.error("No app can open this link")
        }
    }

    // ── Download (Storage Access Framework: the user picks where the ZIP is saved) ──

    private fun saveArtifact(repo: String, artifact: RunArtifact) {
        val fileName = artifact.name.ifBlank { "artifact" }.replace('/', '_') + ".zip"
        act.createDocument(fileName, "application/zip") { uri ->
            if (uri == null) return@createDocument
            downloadArtifact(repo, artifact, fileName, uri)
        }
    }

    private fun downloadArtifact(repo: String, artifact: RunArtifact, fileName: String, uri: Uri) {
        val api = ctx.api ?: return
        ctx.toast.show("Downloading ${artifact.name}…")
        ctx.launchSafe(onError = {
            discardDocument(uri)
            ctx.toast.error(it.userMessage())
        }) {
            withContext(Dispatchers.IO) {
                val out = act.contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("Could not open the destination file")
                out.use { api.downloadArtifact(repo, artifact.id, it) }
            }
            ctx.toast.success("Saved $fileName")
        }
    }

    /** A failed download would leave an empty file behind at the chosen location. */
    private fun discardDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(act.contentResolver, uri) }
    }
}
