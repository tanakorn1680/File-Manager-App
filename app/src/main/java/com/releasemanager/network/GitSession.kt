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

    /** The commit made by [commitAndPush] (what the push delivered), once it exists. */
    var headSha: String? = null
        private set

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

    /**
     * Commits what was added and pushes it. Returns true when something was pushed.
     *
     * [batch] marks one push of several in a very large upload: files identical to what the repo already has
     * are skipped (false is returned, which is what lets a retried upload carry on where it stopped), and the
     * working copy of the files just pushed is deleted so the next batch has room on the device.
     */
    suspend fun commitAndPush(message: String, batch: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        try {
            report(GitPhase.OTHER, "Preparing commit…", -1)
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()

            val ident = PersonIdent(login, "$login@users.noreply.github.com")
            try {
                headSha = git.commit().setMessage(message.ifBlank { "Upload files" })
                    .setAuthor(ident).setCommitter(ident)
                    .setAllowEmpty(false).setSign(false).call().name
            } catch (e: EmptyCommitException) {
                if (batch) {
                    discardAdded()
                    return@withContext false
                }
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
            if (batch) discardAdded()
            true
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    /** Deletes the working copy of the files added since the last commit (they are in the commit, and on GitHub). */
    private fun discardAdded() {
        paths.forEach { File(dir, it).delete() }
        paths.clear()
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

        override fun onUpdate(taskName: String, workCurr: Int) = emit(taskName, -1)

        override fun onEndTask(taskName: String, workCurr: Int) = emit(taskName, -1)

        override fun onUpdate(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int) =
            emit(taskName, percentDone)

        override fun onEndTask(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int) =
            emit(taskName, percentDone)

        override fun isCancelled(): Boolean = job?.isActive == false

        private fun emit(title: String, pct: Int) {
            report(phase, if (pct >= 0) "$label — $title $pct%" else "$label — $title…", pct)
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
