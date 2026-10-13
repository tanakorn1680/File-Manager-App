package com.releasemanager.network

import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.WorkflowJob
import com.releasemanager.data.WorkflowRun

/**
 * Builds ONE plain-text file out of a workflow run that can be handed to an AI as it is:
 * what ran, which job / step failed, the error lines, GitHub's own error annotations, and the
 * end of the failing job's log (where the real error almost always is).
 */
object RunLogReport {

    /** Lines kept from the end of each job log. */
    private const val TAIL_LINES = 500

    /** Jobs whose logs are included when several failed (keeps the file a sensible size). */
    private const val MAX_LOG_JOBS = 5

    suspend fun build(api: GitHubApi, repo: String, run: WorkflowRun): String {
        val jobs = try {
            api.listRunJobs(repo, run.id)
        } catch (e: GitHubApiException) {
            emptyList()
        }
        val failedJobs = jobs.filter { isFailed(it.conclusion) }
        // Nothing failed (cancelled / successful run): include all jobs so the file is still useful
        val logJobs = (if (failedJobs.isNotEmpty()) failedJobs else jobs).take(MAX_LOG_JOBS)

        val sb = StringBuilder()
        sb.appendLine("# GitHub Actions run log (for AI debugging)")
        sb.appendLine()
        sb.appendLine("Task for the AI: find the root cause of this failure and tell me exactly which file(s) to change and how.")
        sb.appendLine()
        sb.appendLine("Repository : $repo")
        sb.appendLine("Workflow   : ${run.workflowName} #${run.runNumber}")
        if (run.title.isNotBlank()) sb.appendLine("Title      : ${run.title}")
        sb.appendLine("Branch     : ${run.branch}")
        sb.appendLine("Event      : ${run.event}")
        if (run.headSha.isNotBlank()) sb.appendLine("Commit     : ${run.headSha}")
        sb.appendLine("Result     : ${if (run.status == "completed") run.conclusion.orEmpty() else run.status}")
        sb.appendLine("Run URL    : ${run.htmlUrl}")
        sb.appendLine()

        // ── Jobs and steps ──────────────────────────────────────────────
        sb.appendLine("## Jobs and steps")
        if (jobs.isEmpty()) {
            sb.appendLine("No jobs were created for this run (for example a workflow file with a syntax error).")
            sb.appendLine("Open the run page for the exact message: ${run.htmlUrl}")
        }
        for (job in jobs) {
            sb.appendLine("${mark(job.status, job.conclusion)} ${job.name}")
            for (step in job.steps) {
                sb.appendLine("    ${mark(step.status, step.conclusion)} ${step.number}. ${step.name}")
            }
        }
        sb.appendLine()

        // ── Per failing job: annotations, error lines, log tail ─────────
        for (job in logJobs) {
            appendJob(sb, api, repo, job)
        }
        if (logJobs.size < (if (failedJobs.isNotEmpty()) failedJobs.size else jobs.size)) {
            sb.appendLine("(Only the first $MAX_LOG_JOBS jobs are included — download the full logs for the rest.)")
        }
        return sb.toString()
    }

    private suspend fun appendJob(sb: StringBuilder, api: GitHubApi, repo: String, job: WorkflowJob) {
        sb.appendLine("## Job: ${job.name}")

        val annotations = try {
            api.listJobAnnotations(repo, job.id)
        } catch (e: GitHubApiException) {
            emptyList()
        }
        if (annotations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### GitHub annotations (what the run page shows in red)")
            annotations.forEach { sb.appendLine("- $it") }
        }

        try {
            val log = api.fetchJobLog(repo, job.id, TAIL_LINES)
            if (log.errors.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("### Error lines found in the log")
                log.errors.forEach { sb.appendLine("- $it") }
            }
            sb.appendLine()
            val shown = log.tail.size
            sb.appendLine("### Log — last $shown of ${log.totalLines} lines")
            sb.appendLine("```")
            log.tail.forEach { sb.appendLine(it) }
            sb.appendLine("```")
        } catch (e: GitHubApiException) {
            sb.appendLine()
            sb.appendLine("(Log of this job is not available: ${e.message}. Logs expire after 90 days and are only readable once the job has finished.)")
        }
        sb.appendLine()
    }

    private fun isFailed(conclusion: String?) =
        conclusion == "failure" || conclusion == "timed_out" || conclusion == "startup_failure"

    private fun mark(status: String, conclusion: String?): String = when {
        status != "completed" -> "[${status.replace('_', ' ')}]"
        conclusion == "success" -> "[ok]"
        conclusion == "failure" || conclusion == "timed_out" -> "[FAIL]"
        else -> "[${conclusion.orEmpty().replace('_', ' ').ifBlank { "unknown" }}]"
    }
}
