package com.releasemanager.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R
import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.RunArtifact
import com.releasemanager.data.Workflow
import com.releasemanager.data.WorkflowRun
import com.releasemanager.network.DownloadJobs
import com.releasemanager.network.GitHubApi
import com.releasemanager.network.RunLogReport
import com.releasemanager.network.WorkflowInput
import com.releasemanager.service.RunWatcher
import com.releasemanager.util.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * App-only screen (the web has no Actions view): the workflows of the open repository and their runs.
 * The selector at the top picks one workflow (or all of them) to list runs for, and "Run workflow" starts
 * one by hand — branch and inputs are filled in a form, like on GitHub's Actions tab.
 * Tapping a run lists the artifacts it produced (e.g. a built APK); tapping an artifact saves its ZIP.
 */
class ActionsScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenActions)
    private val list: RecyclerView = root.findViewById(R.id.listRuns)
    private val stateView = StateView(root)
    private val btnWorkflow: View = root.findViewById(R.id.btnWorkflow)
    private val textWorkflow: TextView = root.findViewById(R.id.textWorkflow)
    private val btnRun: MaterialButton = root.findViewById(R.id.btnRunWorkflow)

    private val adapter = RunAdapter { openRun(it) }

    private var loadJob: Job? = null
    private var runJob: Job? = null

    /** The repository's workflows, the one the list is filtered to (null = all), and the repo they belong to. */
    private var workflows: List<Workflow> = emptyList()
    private var selected: Workflow? = null
    private var loadedRepo: String? = null

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter

        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { ctx.show(Screen.FILES) }
        root.findViewById<ImageButton>(R.id.btnRefreshRuns).setOnClickListener { load() }
        btnWorkflow.setOnClickListener { chooseFilter() }
        btnRun.setOnClickListener { runClicked() }
    }

    fun enter() {
        if (state.currentRepo != loadedRepo) {
            workflows = emptyList()
            selected = null
            updateSelector()
        }
        ctx.show(Screen.ACTIONS)
        load()
    }

    /** [refreshWorkflows] false keeps the workflow list already fetched (changing the filter only needs the runs). */
    fun load(refreshWorkflows: Boolean = true) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        loadedRepo = repo
        stateView.showLoading()
        adapter.submitList(emptyList())
        loadJob?.cancel()
        runJob?.cancel()
        loadJob = ctx.launchSafe(onError = { stateView.showError(it.userMessage()) { load() } }) {
            if (refreshWorkflows || workflows.isEmpty()) loadWorkflows(api, repo)
            val filter = selected
            val runs = api.listWorkflowRuns(repo, workflowId = filter?.id)
            adapter.submitList(runs)
            // A build still running: tell the user how it ended, even if the app is closed by then
            if (runs.any { it.status != "completed" }) {
                act.requestNotificationPermission()
                RunWatcher.watchUnfinished(act, api, repo, runs)
            }
            if (runs.isEmpty()) {
                stateView.showEmpty(if (filter != null) "No runs of ${filter.name} yet." else "No workflow runs yet.")
            } else {
                stateView.hide()
            }
        }
    }

    // ── Workflows: the filter and "Run workflow" ─────────────────────────────

    /** The runs below still load when this fails; the selector just keeps what it had. */
    private suspend fun loadWorkflows(api: GitHubApi, repo: String) {
        try {
            workflows = api.listWorkflows(repo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep the previous list
        }
        val keep = selected
        if (keep != null) selected = workflows.firstOrNull { it.id == keep.id }
        updateSelector()
    }

    private fun updateSelector() {
        textWorkflow.text = selected?.name ?: "All workflows"
    }

    private fun select(workflow: Workflow?) {
        if (workflow?.id == selected?.id) return
        selected = workflow
        updateSelector()
        load(refreshWorkflows = false)
    }

    private fun chooseFilter() {
        if (workflows.isEmpty()) {
            ctx.toast.show("No workflows in this repository")
            return
        }
        val rows: List<Workflow?> = listOf(null) + workflows
        val labels = rows.map { w -> w?.name ?: "All workflows" }
        val current = rows.indexOfFirst { it?.id == selected?.id }
        popupList(btnWorkflow, labels, current) { select(rows[it]) }
    }

    private fun runClicked() {
        val workflow = selected
        if (workflow != null) {
            openRunDialog(workflow)
            return
        }
        // Like GitHub: a workflow has to be picked before it can be run
        val runnable = workflows.filter { it.active }
        if (runnable.isEmpty()) {
            ctx.toast.show(if (workflows.isEmpty()) "No workflows in this repository" else "All workflows are disabled")
            return
        }
        popupList(btnRun, runnable.map { it.name }, -1) { openRunDialog(runnable[it]) }
    }

    /** A dropdown under [anchor] — the same look as the branch picker. */
    private fun popupList(anchor: View, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val adapter = object : ArrayAdapter<String>(act, R.layout.item_dropdown, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as TextView
                val active = position == current
                row.text = I18n.tr(if (active) "${labels[position]} ✓" else labels[position])
                row.setTextColor(act.color(if (active) R.color.accent else R.color.text_primary))
                row.typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                return row
            }
        }
        val popup = ListPopupWindow(act)
        popup.anchorView = anchor
        popup.setAdapter(adapter)
        popup.isModal = true
        popup.width = maxOf(anchor.width, act.dp(220))
        popup.verticalOffset = act.dp(4)
        popup.setBackgroundDrawable(ContextCompat.getDrawable(act, R.drawable.bg_dropdown))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onPick(position)
        }
        popup.show()
    }

    /** One input of the form: [read] gives its current value as the text GitHub expects; [box] is what turns red when it is missing. */
    private class Field(val input: WorkflowInput, val label: String, val box: View?, val read: () -> String)

    /**
     * GitHub's "Run workflow" form: the branch to run from, then the inputs the workflow file declares under
     * `workflow_dispatch` (text, number, true/false switch, choice list). Inputs are read from the chosen branch's
     * copy of the file, so switching branch reloads them.
     */
    private fun openRunDialog(workflow: Workflow) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val (dialog, view) = Dialogs.create(act, R.layout.dialog_run_workflow)
        val textBranch = view.findViewById<TextView>(R.id.textRunBranch)
        val container = view.findViewById<LinearLayout>(R.id.inputsContainer)
        val textStatus = view.findViewById<TextView>(R.id.textRunStatus)
        val textError = view.findViewById<TextView>(R.id.textRunError)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnNegative)
        val btnStart = view.findViewById<MaterialButton>(R.id.btnPositive)
        view.findViewById<TextView>(R.id.textRunWorkflow).text = I18n.tr("${workflow.name} · ${workflow.fileName}")

        var branches = listOf(state.currentBranch)
        var branch = state.currentBranch
        val fields = ArrayList<Field>()
        var inputsJob: Job? = null
        var running = false
        textBranch.text = branch

        fun loadInputs() {
            inputsJob?.cancel()
            fields.clear()
            container.removeAllViews()
            btnStart.isEnabled = false
            textError.visibility = View.GONE
            textStatus.text = I18n.tr("Reading the workflow's inputs…")
            textStatus.visibility = View.VISIBLE
            val ref = branch
            inputsJob = ctx.launchSafe(onError = { e ->
                textStatus.text = I18n.tr("Could not read ${workflow.fileName} on \"$ref\": ${e.userMessage()}")
            }) {
                val info = api.getDispatchInfo(repo, workflow.path, ref)
                if (!info.manual) {
                    textStatus.text = I18n.tr("${workflow.fileName} on \"$ref\" has no workflow_dispatch trigger, so it cannot be run by hand.")
                    return@launchSafe
                }
                info.inputs.forEach { addField(container, fields, it) }
                textStatus.text = I18n.tr(if (info.inputs.isEmpty()) "This workflow takes no inputs." else "")
                textStatus.visibility = if (info.inputs.isEmpty()) View.VISIBLE else View.GONE
                btnStart.isEnabled = true
            }
        }

        val branchesJob = ctx.launchSafe(onError = { /* the picker just keeps the current branch */ }) {
            val list = api.listBranches(repo)
            if (list.isNotEmpty()) branches = if (branch in list) list else listOf(branch) + list
        }

        textBranch.setOnClickListener {
            popupList(textBranch, branches, branches.indexOf(branch)) { i ->
                if (branches[i] != branch) {
                    branch = branches[i]
                    textBranch.text = branch
                    loadInputs()
                }
            }
        }
        btnCancel.setOnClickListener { dialog.cancel() }
        btnStart.setOnClickListener {
            if (running) return@setOnClickListener
            val values = LinkedHashMap<String, String>()
            var missing: String? = null
            for (f in fields) {
                val v = f.read()
                val bad = f.input.required && v.isBlank()
                f.box?.setBackgroundResource(if (bad) R.drawable.bg_input_danger else R.drawable.bg_input)
                if (bad && missing == null) missing = f.label
                if (!bad && (v.isNotBlank() || f.input.default != null)) values[f.input.name] = v
            }
            val firstMissing = missing
            if (firstMissing != null) {
                textError.text = I18n.tr("\"${firstMissing.removeSuffix(" *")}\" is required.")
                textError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            running = true
            btnStart.isEnabled = false
            btnStart.text = I18n.tr("Starting…")
            textError.visibility = View.GONE
            view.hideKeyboard()
            val ref = branch
            ctx.launchSafe(onError = { e ->
                running = false
                btnStart.isEnabled = true
                btnStart.text = I18n.tr("Run workflow")
                textError.text = dispatchError(e)
                textError.visibility = View.VISIBLE
            }) {
                api.dispatchWorkflow(repo, workflow.id, ref, values)
                dialog.dismiss()
                ctx.toast.success("Workflow started: ${workflow.name} on $ref")
                // GitHub needs a moment before the new run shows up in the list
                delay(RUN_LIST_DELAY_MS)
                if (act.current == Screen.ACTIONS && state.currentRepo == repo) load(refreshWorkflows = false)
            }
        }
        dialog.setOnDismissListener {
            inputsJob?.cancel()
            branchesJob.cancel()
        }

        loadInputs()
        dialog.show()
    }

    private fun addField(container: LinearLayout, fields: MutableList<Field>, input: WorkflowInput) {
        val inflater = LayoutInflater.from(act)
        val label = input.description.ifBlank { input.name } + if (input.required) " *" else ""
        when {
            input.type == "boolean" -> {
                val row = inflater.inflate(R.layout.item_wf_input_bool, container, false)
                row.findViewById<TextView>(R.id.wfLabel).text = label
                val toggle = row.findViewById<SwitchCompat>(R.id.wfSwitch)
                toggle.isChecked = input.default?.lowercase() == "true"
                container.addView(row)
                fields.add(Field(input, label, null) { if (toggle.isChecked) "true" else "false" })
            }
            input.type == "choice" && input.options.isNotEmpty() -> {
                val row = inflater.inflate(R.layout.item_wf_input_choice, container, false)
                row.findViewById<TextView>(R.id.wfLabel).text = label
                val select = row.findViewById<TextView>(R.id.wfSelect)
                var value = input.default?.takeIf { it in input.options } ?: input.options.first()
                select.text = value
                select.setOnClickListener {
                    popupList(select, input.options, input.options.indexOf(value)) { i ->
                        value = input.options[i]
                        select.text = value
                    }
                }
                container.addView(row)
                fields.add(Field(input, label, select) { value })
            }
            else -> {
                val row = inflater.inflate(R.layout.item_wf_input_text, container, false)
                row.findViewById<TextView>(R.id.wfLabel).text = label
                val edit = row.findViewById<EditText>(R.id.wfInput)
                if (input.type == "number") {
                    edit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                edit.setText(input.default.orEmpty())
                container.addView(row)
                fields.add(Field(input, label, edit) { edit.text.toString() })
            }
        }
    }

    /** GitHub's own message, plus what to do about the usual cause: a token that may not run workflows. */
    private fun dispatchError(e: Exception): String {
        val msg = e.userMessage()
        return when ((e as? GitHubApiException)?.httpStatus) {
            403 -> "$msg — the token needs permission to run workflows (fine-grained: Actions → Read and write; classic: the \"workflow\" scope)."
            404 -> "$msg — the token cannot run workflows in this repository, or the branch no longer exists."
            else -> msg
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
        val failed = isFailedRun(run)
        val items = mutableListOf<Dialogs.SheetItem>()

        // A failed run: the log is what the person came for, so it goes first
        if (failed) addLogItems(items, repo, run, failed = true)

        artifacts.forEachIndexed { index, artifact ->
            items.add(
                Dialogs.SheetItem(
                    label = artifact.name,
                    iconRes = R.drawable.ic_download,
                    sub = if (artifact.expired) "Expired — GitHub has deleted it" else formatSize(artifact.size),
                    separatorBefore = index == 0 && items.isNotEmpty(),
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

        if (!failed) addLogItems(items, repo, run, failed = false)

        items.add(
            Dialogs.SheetItem(
                label = "Open on GitHub",
                iconRes = R.drawable.ic_external,
                sub = if (artifacts.isEmpty()) "No artifacts for this run" else null,
                separatorBefore = items.isNotEmpty(),
                onClick = { openInBrowser(run.htmlUrl) }
            )
        )
        Dialogs.sheet(act, run.title.ifBlank { run.workflowName }, items)
    }

    // ── Logs (only a finished run has them) ──────────────────────────────────

    private fun isFailedRun(run: WorkflowRun): Boolean =
        run.status == "completed" &&
            (run.conclusion == "failure" || run.conclusion == "timed_out" || run.conclusion == "startup_failure")

    private fun addLogItems(items: MutableList<Dialogs.SheetItem>, repo: String, run: WorkflowRun, failed: Boolean) {
        if (run.status != "completed") return
        items.add(
            Dialogs.SheetItem(
                label = if (failed) "Download failure log for AI" else "Download log for AI",
                iconRes = R.drawable.ic_download,
                sub = "One .txt: failed step, error lines and the end of the log",
                iconTintRes = if (failed) R.color.danger else null,
                separatorBefore = !failed && items.isNotEmpty(),
                onClick = { saveAiLog(repo, run) }
            )
        )
        items.add(
            Dialogs.SheetItem(
                label = "Download full logs (.zip)",
                iconRes = R.drawable.ic_archive,
                sub = "Raw log of every job",
                onClick = { saveFullLogs(repo, run) }
            )
        )
    }

    private fun saveAiLog(repo: String, run: WorkflowRun) {
        val fileName = "${repo.repoShortName()}-run${run.runNumber}-log.txt"
        act.createDocument(fileName, "text/plain") { uri ->
            if (uri == null) return@createDocument
            writeAiLog(repo, run, fileName, uri)
        }
    }

    private fun writeAiLog(repo: String, run: WorkflowRun, fileName: String, uri: Uri) {
        val api = ctx.api ?: return
        ctx.toast.show("Collecting log…")
        ctx.launchSafe(onError = {
            discardDocument(uri)
            ctx.toast.error(it.userMessage())
        }) {
            val report = RunLogReport.build(api, repo, run)
            withContext(Dispatchers.IO) {
                val out = act.contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("Could not open the destination file")
                out.use { it.write(report.toByteArray(Charsets.UTF_8)) }
            }
            ctx.toast.success("Saved $fileName")
        }
    }

    private fun saveFullLogs(repo: String, run: WorkflowRun) {
        val fileName = "${repo.repoShortName()}-run${run.runNumber}-logs.zip"
        act.createDocument(fileName, "application/zip") { uri ->
            if (uri == null) return@createDocument
            downloadFullLogs(repo, run, fileName, uri)
        }
    }

    /** Runs in TransferService: carries on (with a progress notification) if the app is closed. */
    private fun downloadFullLogs(repo: String, run: WorkflowRun, fileName: String, uri: Uri) {
        val api = ctx.api ?: return
        val cr = act.applicationContext.contentResolver
        val runId = run.id
        ctx.startDownload(fileName) { progress ->
            DownloadJobs.runLogs(api, cr, uri, repo, runId, fileName, progress)
        }
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

    /** Runs in TransferService: carries on (with a progress notification) if the app is closed. */
    private fun downloadArtifact(repo: String, artifact: RunArtifact, fileName: String, uri: Uri) {
        val api = ctx.api ?: return
        val cr = act.applicationContext.contentResolver
        val artifactId = artifact.id
        ctx.startDownload(fileName) { progress ->
            DownloadJobs.artifact(api, cr, uri, repo, artifactId, fileName, progress)
        }
    }

    /** A failed download would leave an empty file behind at the chosen location. */
    private fun discardDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(act.contentResolver, uri) }
    }

    private companion object {
        const val RUN_LIST_DELAY_MS = 3500L
    }
}
