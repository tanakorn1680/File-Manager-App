package com.releasemanager.network

import com.releasemanager.data.GitHubApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.InputStream

enum class GitPhase { CLONE, PUSH, OTHER }

/**
 * Uploads into a repository the way `git clone` + `git add` + `git commit` + `git push` does in Termux:
 * the repo is cloned into the app cache, files are copied in, and everything goes up as ONE commit
 * in ONE push. Nothing is held in memory per file (JGit streams to and from disk), and the GitHub REST
 * limits (request size, requests per minute) do not apply — only GitHub's own git limits do
 * (100 MB per file).
 *
 * Usage: open() → addFile()/addBytes() for every file → commitAndPush() → close().
 * [report] is called from a background thread: (phase, text, percent or -1).
 */
class GitSession private constructor(
    private val dir: File,
    private val git: Git,
    private val login: String,
    private val branch: String,
    private val token: String,
    private val report: (GitPhase, String, Int) -> Unit
) {
    private val paths = ArrayList<String>()

    /** Copies one file into the working copy; [open] is called on a background thread. */
    suspend fun addFile(path: String, open: () -> InputStream) = withContext(Dispatchers.IO) {
        try {
            val target = targetFor(path)
            open().use { src -> target.outputStream().use { src.copyTo(it, 64 * 1024) } }
            paths.add(path)
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    suspend fun addBytes(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            targetFor(path).writeBytes(bytes)
            paths.add(path)
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    private fun targetFor(path: String): File {
        val target = File(dir, path)
        val inside = target.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        if (!inside || path.split('/').any { it == ".git" }) throw GitHubApiException("Invalid path: $path", 0)
        target.parentFile?.mkdirs()
        return target
    }

    suspend fun commitAndPush(message: String) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        try {
            report(GitPhase.OTHER, "Preparing commit…", -1)
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()

            val ident = PersonIdent(login, "$login@users.noreply.github.com")
            try {
                git.commit().setMessage(message.ifBlank { "Upload files" })
                    .setAuthor(ident).setCommitter(ident)
                    .setAllowEmpty(false).setSign(false).call()
            } catch (e: EmptyCommitException) {
                throw GitHubApiException("Nothing to commit — these files are identical to what is already in the repo.", 0)
            }

            // Less memory and CPU on a phone: no delta search, one thread
            val cfg = git.repository.config
            cfg.setBoolean("pack", null, "deltaCompression", false)
            cfg.setInt("pack", null, "threads", 1)
            cfg.save()

            report(GitPhase.PUSH, "Pushing…", -1)
            val results = git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("HEAD:refs/heads/$branch"))
                .setCredentialsProvider(credentials(token))
                .setProgressMonitor(Monitor(GitPhase.PUSH, "Pushing", job, report))
                .setTimeout(1800)
                .call()

            for (r in results) {
                for (u in r.remoteUpdates) {
                    when (u.status) {
                        RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD,
                        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                            throw GitHubApiException("The branch changed on GitHub while uploading — try again.", 409)
                        else -> {
                            val details = listOfNotNull(u.message, r.messages).map { it.trim() }
                                .filter { it.isNotEmpty() }.joinToString(" ")
                            throw GitHubApiException(
                                "GitHub rejected the push (${u.status})" + if (details.isNotEmpty()) ": $details" else "", 0
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    /** Always call this (finally): releases the repo and deletes the working copy. */
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        try { git.close() } catch (_: Exception) { }
        dir.parentFile?.deleteRecursively()
    }

    /**
     * Progress + cancellation for JGit. (NullProgressMonitor has a private constructor in this JGit version,
     * so this extends BatchingProgressMonitor, which also throttles updates to about every half second.)
     */
    private class Monitor(
        private val phase: GitPhase,
        private val label: String,
        private val job: Job?,
        private val report: (GitPhase, String, Int) -> Unit
    ) : BatchingProgressMonitor() {

        /**
         * Percent (0–100) of the whole clone / push. JGit starts every task (counting, compressing, writing …)
         * at 0 % again, so the per-task percentages are placed in their slice of the phase and the result
         * only ever moves forward — that is what lets a progress bar follow the real work.
         */
        private var overall = 0

        override fun onUpdate(taskName: String, workCurr: Int) = emit(taskName, -1)

        override fun onEndTask(taskName: String, workCurr: Int) = emit(taskName, -1)

        override fun onUpdate(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int) =
            emit(taskName, percentDone)

        override fun onEndTask(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int) =
            emit(taskName, percentDone)

        override fun isCancelled(): Boolean = job?.isActive == false

        private fun emit(title: String, pct: Int) {
            val text = if (pct >= 0) "$label — $title $pct%" else "$label — $title…"
            if (pct >= 0) overall = maxOf(overall, phasePercent(title, pct))
            // The text carries the task's own percentage; the number handed on is the phase's overall one
            report(phase, text, if (pct >= 0) overall else -1)
        }

        /** Where JGit's task [title] sits inside the clone / push, as a percentage of the whole phase. */
        private fun phasePercent(title: String, pct: Int): Int {
            val (lo, hi) = when (phase) {
                GitPhase.CLONE -> when {
                    title.contains("receiving", ignoreCase = true) -> 0 to 80
                    title.contains("resolving", ignoreCase = true) -> 80 to 92
                    title.contains("checking out", ignoreCase = true) -> 92 to 100
                    else -> return overall
                }
                else -> when {
                    title.contains("finding", ignoreCase = true) -> 0 to 5
                    title.contains("counting", ignoreCase = true) -> 5 to 12
                    title.contains("compressing", ignoreCase = true) -> 12 to 25
                    // Real bytes leave the phone here; stop short of 100 % until GitHub has accepted the push
                    title.contains("writing", ignoreCase = true) -> 25 to 97
                    else -> return overall
                }
            }
            return lo + (hi - lo) * pct.coerceIn(0, 100) / 100
        }
    }

    companion object {
        private var configured = false

        private fun credentials(token: String) = UsernamePasswordCredentialsProvider("x-access-token", token)

        private fun configure(home: File) {
            if (configured) return
            // Android has no real home directory; JGit looks for ~/.gitconfig there
            System.setProperty("user.home", home.absolutePath)
            FS.DETECTED.setUserHome(home)
            // Keep JGit's caches small and stream anything big instead of loading it
            WindowCacheConfig().apply {
                packedGitLimit = 16L * 1024 * 1024
                deltaBaseCacheLimit = 4 * 1024 * 1024
                streamFileThreshold = 8 * 1024 * 1024
            }.install()
            configured = true
        }

        /** Clones [repo]'s [branch] into the app cache (the same download a `git clone` does). */
        suspend fun open(
            cacheDir: File,
            repo: String,
            branch: String,
            token: String,
            login: String,
            report: (GitPhase, String, Int) -> Unit
        ): GitSession = withContext(Dispatchers.IO) {
            configure(cacheDir)
            val root = File(cacheDir, "git-work")
            root.deleteRecursively() // leftovers from an earlier run the system killed
            val dir = File(root, "w${System.currentTimeMillis()}").apply { mkdirs() }
            val job = currentCoroutineContext()[Job]
            try {
                report(GitPhase.CLONE, "Cloning $repo…", -1)
                val git = Git.cloneRepository()
                    .setURI("https://github.com/$repo.git")
                    .setDirectory(dir)
                    .setBranchesToClone(listOf("refs/heads/$branch"))
                    .setBranch("refs/heads/$branch")
                    .setCredentialsProvider(credentials(token))
                    .setProgressMonitor(Monitor(GitPhase.CLONE, "Cloning", job, report))
                    .setTimeout(1800)
                    .call()
                GitSession(dir, git, login, branch, token, report)
            } catch (e: Throwable) {
                root.deleteRecursively()
                throw friendly(e)
            }
        }

        private fun friendly(e: Throwable): Exception = when (e) {
            is CancellationException -> e
            is GitHubApiException -> e
            is CanceledException -> CancellationException("Cancelled")
            is OutOfMemoryError ->
                GitHubApiException("Not enough memory on this device to finish the upload.", 0)
            else -> {
                val m = e.message.orEmpty()
                when {
                    m.contains("not authorized", ignoreCase = true) || m.contains("401") ->
                        GitHubApiException("GitHub rejected the token — it needs write access to this repository.", 401)
                    m.contains("not found", ignoreCase = true) ->
                        GitHubApiException("Repository or branch not found: $m", 404)
                    else -> GitHubApiException(m.ifBlank { e.javaClass.simpleName }, 0)
                }
            }
        }
    }
}
