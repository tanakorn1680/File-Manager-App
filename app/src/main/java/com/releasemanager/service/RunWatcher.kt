package com.releasemanager.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.releasemanager.data.WorkflowRun
import com.releasemanager.network.GitHubApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Tells the user how a GitHub Actions build ended. A watch is a [TransferKind.WATCH] transfer, so it lives in
 * [TransferService] (keeps going with the app closed) and ends in the same "complete" / "failed" notification
 * as uploads: green ✓ when every workflow passed, red ✕ when one did not.
 *
 * It starts by itself in two cases: a commit the app just pushed (the repo has workflows), and unfinished runs
 * seen on the Actions screen. GitHub has no push channel to a phone, so the run is polled every 15 seconds.
 */
object RunWatcher {

    private const val POLL_MS = 15_000L
    private const val START_WAIT_MS = 90_000L          // how long to wait for a pushed commit to start a run
    private const val MAX_WATCH_MS = 3L * 60 * 60 * 1000
    private const val MAX_ERRORS = 8                   // polls in a row that may fail before watching stops

    private val GOOD = setOf("success", "skipped", "neutral")
    private val main = Handler(Looper.getMainLooper())

    /** [sha] was just pushed to [repo]. Callable from any thread; stays quiet when no run follows. */
    fun afterCommit(context: Context, api: GitHubApi, repo: String, sha: String) {
        val app = context.applicationContext
        main.post { watch(app, api, repo, sha, waitForStart = true) }
    }

    /** Main thread: watches every commit that still has an unfinished run in [runs]. */
    fun watchUnfinished(context: Context, api: GitHubApi, repo: String, runs: List<WorkflowRun>) {
        val app = context.applicationContext
        runs.filter { it.status != "completed" && it.headSha.isNotBlank() }
            .map { it.headSha }.distinct().take(3)
            .forEach { watch(app, api, repo, it, waitForStart = false) }
    }

    /** After Disconnect: the token of the old account must not keep polling. */
    fun stopAll() {
        TransferManager.running().filter { it.kind == TransferKind.WATCH }.forEach { TransferManager.cancel(it.id) }
    }

    private fun watch(app: Context, api: GitHubApi, repo: String, sha: String, waitForStart: Boolean) {
        val tag = "watch:$repo@$sha"
        if (TransferManager.find(tag) != null) return
        TransferManager.start(app, TransferKind.WATCH, repo.substringAfter('/'), tag) { progress ->
            follow(api, repo, sha, waitForStart, progress)
        }
    }

    private suspend fun follow(
        api: GitHubApi, repo: String, sha: String, waitForStart: Boolean, progress: TransferProgress
    ): TransferOutcome {
        val t0 = SystemClock.elapsedRealtime()
        var errors = 0
        var lastCount = -1
        progress.update(-1f, "Waiting for the build to start…")
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - t0
            val runs = try {
                api.listRunsForSha(repo, sha)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++errors >= MAX_ERRORS) throw CancellationException("Lost connection while watching")
                delay(POLL_MS)
                continue
            }
            errors = 0

            if (runs.isEmpty()) {
                // Nothing started for this commit (no workflow listens to this branch): end without a notification
                if (!waitForStart || elapsed > START_WAIT_MS) throw CancellationException("No build for this commit")
            } else {
                val done = runs.count { it.status == "completed" }
                // Two polls with the same set of runs, so a second workflow that starts a moment later is not missed
                if (done == runs.size && runs.size == lastCount) return finished(runs)
                lastCount = runs.size
                val active = runs.firstOrNull { it.status != "completed" }
                if (active != null) {
                    val state = if (active.status == "in_progress") "running" else "queued"
                    val more = if (runs.size > 1) " · $done/${runs.size} done" else ""
                    progress.update(-1f, "${active.workflowName} — $state$more")
                }
            }
            if (elapsed > MAX_WATCH_MS) throw CancellationException("Stopped watching")
            delay(POLL_MS)
        }
    }

    /** All runs ended: a result for a green notification, or an exception (red) naming what did not pass. */
    private fun finished(runs: List<WorkflowRun>): TransferOutcome {
        val bad = runs.filter { r -> val c = r.conclusion; c == null || c !in GOOD }
        if (bad.isEmpty()) {
            val r = runs.first()
            return TransferOutcome(
                if (runs.size == 1) "${r.workflowName} #${r.runNumber} succeeded" else "All ${runs.size} workflows succeeded"
            )
        }
        val msg = if (bad.size == 1) {
            "${bad[0].workflowName} #${bad[0].runNumber} ${describe(bad[0].conclusion)}"
        } else {
            "${bad.size} of ${runs.size} workflows failed: " + bad.joinToString { it.workflowName }
        }
        throw IOException(msg)
    }

    private fun describe(conclusion: String?): String = when (conclusion) {
        "failure" -> "failed"
        "cancelled" -> "was cancelled"
        "timed_out" -> "timed out"
        "action_required" -> "needs your action"
        "startup_failure" -> "failed to start"
        else -> conclusion ?: "did not finish"
    }
}
