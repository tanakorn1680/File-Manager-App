package com.releasemanager.network

import android.util.Base64
import com.releasemanager.data.DeleteTarget
import com.releasemanager.data.FileContent
import com.releasemanager.data.FileEntry
import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.CommitInfo
import com.releasemanager.data.GitHubUser
import com.releasemanager.data.JobLog
import com.releasemanager.data.Release
import com.releasemanager.data.ReleaseAsset
import com.releasemanager.data.RepoRef
import com.releasemanager.data.RunArtifact
import com.releasemanager.data.UploadItem
import com.releasemanager.data.WorkflowJob
import com.releasemanager.data.WorkflowRun
import com.releasemanager.data.WorkflowStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to GitHub's REST API directly from the device — no server of ours
 * sits in between, which is the entire point of this app existing: the
 * web version (Vercel) is capped at ~4.5MB per request by the hosting
 * platform itself, a limit that does not apply here because there is no
 * platform in the middle to impose it. Two GitHub hosts are used, matching
 * GitHub's own API split: api.github.com for everything except the actual
 * asset bytes, and uploads.github.com for the upload itself.
 *
 * The repo / file / git operations below are ports of api/github.js from the
 * web version (same GitHub endpoints, same order of calls).
 */
class GitHubApi(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        // Writing a ~1GB file over a slow mobile connection can legitimately
        // take a long time; a short write timeout would abort a healthy,
        // still-progressing upload. Progress is surfaced via onProgress
        // below so the user can see it's still moving rather than guess.
        .writeTimeout(60, TimeUnit.MINUTES)
        .build()

    private fun authedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/vnd.github+json")
        .addHeader("X-GitHub-Api-Version", "2022-11-28")

    private suspend fun execute(request: Request): JSONObject? = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(bodyStr).optString("message", "HTTP ${resp.code}") }
                          catch (e: Exception) { "HTTP ${resp.code}" }
                throw GitHubApiException(msg, resp.code)
            }
            if (bodyStr.isBlank()) null else JSONObject(bodyStr)
        }
    }

    private suspend fun executeArray(request: Request): JSONArray = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(bodyStr).optString("message", "HTTP ${resp.code}") }
                          catch (e: Exception) { "HTTP ${resp.code}" }
                throw GitHubApiException(msg, resp.code)
            }
            JSONArray(bodyStr)
        }
    }

    // ── Helpers for the file-manager calls ───────────────────────────────

    /**
     * Like execute()/executeArray() but for endpoints whose response can be either a
     * JSON object or a JSON array (e.g. the Contents API: array for a folder, object
     * for a file) or empty (204). Returns JSONObject, JSONArray or null.
     */
    private suspend fun call(request: Request): Any? {
        var waits = 0
        while (true) {
            try {
                return callOnce(request)
            } catch (e: GitHubApiException) {
                val wait = e.retryAfterSec
                if (wait == null || waits >= MAX_RATE_LIMIT_WAITS) throw e
                waits++
                waitOutRateLimit(wait)
            }
        }
    }

    private suspend fun callOnce(request: Request): Any? = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GitHubApiException(errorMessage(bodyStr, resp.code), resp.code, rateLimitWaitSec(resp, bodyStr))
            }
            if (bodyStr.isBlank()) null else JSONTokener(bodyStr).nextValue()
        }
    }

    /** Seconds to wait if this failed response is a GitHub rate limit (primary or secondary), else null. */
    private fun rateLimitWaitSec(resp: Response, body: String): Long? {
        if (resp.code != 403 && resp.code != 429) return null
        resp.header("Retry-After")?.toLongOrNull()?.let { return it + 1 }
        val reset = resp.header("x-ratelimit-reset")?.toLongOrNull()
        if (resp.header("x-ratelimit-remaining") == "0" && reset != null) {
            return (reset - System.currentTimeMillis() / 1000).coerceAtLeast(1) + 1
        }
        // Secondary limits without a header: GitHub asks for at least a minute
        if (resp.code == 429 || body.contains("rate limit", ignoreCase = true)) return 60L
        return null
    }

    /** Called on the main thread once a second while waiting out a rate limit (0 = done waiting). */
    @Volatile var onRateLimitWait: ((secondsLeft: Int) -> Unit)? = null

    private suspend fun waitOutRateLimit(seconds: Long) {
        var left = seconds.coerceIn(1L, MAX_RATE_LIMIT_WAIT_SEC).toInt()
        while (left > 0) {
            onRateLimitWait?.let { cb -> withContext(Dispatchers.Main) { cb(left) } }
            delay(1000)
            left--
        }
        onRateLimitWait?.let { cb -> withContext(Dispatchers.Main) { cb(0) } }
    }

    private fun errorMessage(bodyStr: String, code: Int): String =
        try { JSONObject(bodyStr).optString("message", "HTTP $code") } catch (e: Exception) { "HTTP $code" }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Encodes each path segment but keeps the slashes ("a b/c#d" → "a%20b/c%23d"). */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { urlEncode(it) }

    private fun contentsUrl(repo: String, path: String, branch: String): String {
        val p = if (path.isEmpty()) "" else "/" + encodePath(path)
        return "https://api.github.com/repos/$repo/contents$p?ref=${urlEncode(branch)}"
    }

    private fun jsonBody(o: JSONObject): RequestBody =
        o.toString().toRequestBody("application/json".toMediaTypeOrNull())

    /** GitHub sends `"name": null` for unset fields; org.json's optString(key, null) would return "null". */
    private fun optStr(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key)

    private fun treeEntry(path: String, sha: Any?): JSONObject =
        JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", sha)

    suspend fun verifyTokenAndGetUser(): GitHubUser {
        val req = authedRequest("https://api.github.com/user").get().build()
        val json = execute(req) ?: throw GitHubApiException("Empty response from GitHub", 0)
        return GitHubUser(login = json.getString("login"))
    }

    private var userLogin: String? = null

    /**
     * Starts an upload into [repo] that works like Termux's git: clone → copy files → one commit → one push.
     * See [GitSession]. [report] runs on a background thread.
     */
    suspend fun openGitSession(
        cacheDir: File,
        repo: String,
        branch: String,
        report: (GitPhase, String, Int) -> Unit
    ): GitSession {
        val login = userLogin ?: verifyTokenAndGetUser().login.also { userLogin = it }
        return GitSession.open(cacheDir, repo, branch, token, login, report)
    }

    // ── Repositories ─────────────────────────────────────────────────────

    suspend fun listRepos(): List<RepoRef> {
        val req = authedRequest(
            "https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member"
        ).get().build()
        val arr = executeArray(req)
        return (0 until arr.length()).map { i -> parseRepo(arr.getJSONObject(i)) }
    }

    suspend fun createRepo(name: String, description: String, isPrivate: Boolean, autoInit: Boolean): RepoRef {
        if (!Regex("^[a-zA-Z0-9._-]+$").matches(name)) {
            throw GitHubApiException("Invalid repository name. Use letters, numbers, hyphens, underscores, or dots.", 0)
        }
        val payload = JSONObject().apply {
            put("name", name.trim())
            put("description", description.trim())
            put("private", isPrivate)
            put("auto_init", autoInit)
        }
        val req = authedRequest("https://api.github.com/user/repos").post(jsonBody(payload)).build()
        val json = call(req) as JSONObject
        return parseRepo(json)
    }

    suspend fun setVisibility(repo: String, isPrivate: Boolean) {
        val payload = JSONObject().put("private", isPrivate)
        call(authedRequest("https://api.github.com/repos/$repo").patch(jsonBody(payload)).build())
    }

    suspend fun deleteRepo(repo: String) {
        // GitHub DELETE /repos/:owner/:repo returns 204 No Content
        call(authedRequest("https://api.github.com/repos/$repo").delete().build())
    }

    /** Renames a repository (same owner). GitHub keeps redirecting the old name; the new RepoRef carries the new full name. */
    suspend fun renameRepo(repo: String, newName: String): RepoRef {
        val name = newName.trim()
        if (!Regex("^[a-zA-Z0-9._-]+$").matches(name)) {
            throw GitHubApiException("Invalid repository name. Use letters, numbers, hyphens, underscores, or dots.", 0)
        }
        val payload = JSONObject().put("name", name)
        val json = call(authedRequest("https://api.github.com/repos/$repo").patch(jsonBody(payload)).build()) as? JSONObject
            ?: throw GitHubApiException("Empty response from GitHub", 0)
        return parseRepo(json)
    }

    // ── Branches / tree / file ───────────────────────────────────────────

    suspend fun listBranches(repo: String): List<String> {
        val req = authedRequest("https://api.github.com/repos/$repo/branches?per_page=100").get().build()
        val arr = executeArray(req)
        return (0 until arr.length()).map { i -> arr.getJSONObject(i).getString("name") }
    }

    /** Folder listing, folders first then by name (web: getTree). */
    suspend fun getTree(repo: String, path: String, branch: String): List<FileEntry> {
        val data = call(authedRequest(contentsUrl(repo, path, branch)).get().build())
        val items: List<JSONObject> = when (data) {
            is JSONArray -> (0 until data.length()).map { i -> data.getJSONObject(i) }
            is JSONObject -> listOf(data)
            else -> emptyList()
        }
        return items.map { o ->
            FileEntry(
                name = o.getString("name"),
                path = o.getString("path"),
                type = o.getString("type"),
                size = o.optLong("size", 0L),
                sha = optStr(o, "sha")
            )
        }.sortedWith(
            compareBy<FileEntry> { it.type != "dir" }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    /** Text content + blob sha for the editor (web: getFile). */
    suspend fun getFile(repo: String, path: String, branch: String): FileContent {
        val data = call(authedRequest(contentsUrl(repo, path, branch)).get().build())
        val o = data as? JSONObject ?: throw GitHubApiException("Not a file", 0)
        // The Contents API only inlines base64 content up to 1 MB. Past that it answers with
        // an empty `content` and encoding "none" — opening that in the editor and saving
        // would overwrite the file with nothing, so refuse instead.
        if (o.optString("encoding") != "base64") {
            throw GitHubApiException("This file is too large to open here (GitHub only returns content up to 1 MB).", 0)
        }
        val bytes = Base64.decode(o.getString("content"), Base64.DEFAULT)
        return FileContent(String(bytes, Charsets.UTF_8), o.getString("sha"), o.optLong("size", 0L))
    }

    /** Streams the raw bytes of a file into [sink] (works up to 100 MB; does not close [sink]). */
    suspend fun downloadFile(repo: String, path: String, branch: String, sink: OutputStream) {
        withContext(Dispatchers.IO) {
            val req = authedRequest(contentsUrl(repo, path, branch))
                .header("Accept", "application/vnd.github.raw+json")
                .get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    throw GitHubApiException(errorMessage(bodyStr, resp.code), resp.code)
                }
                val body = resp.body ?: throw GitHubApiException("Empty response from GitHub", 0)
                body.byteStream().copyTo(sink)
            }
        }
    }

    /**
     * Creates (sha == null) or updates (sha = current blob sha) one file via the Contents API.
     * Returns the new blob sha, so the editor can commit again without re-reading the file.
     */
    suspend fun putFile(repo: String, branch: String, path: String, contentBase64: String, sha: String?, message: String): String? {
        val payload = JSONObject().apply {
            put("message", message)
            put("content", contentBase64)
            put("branch", branch)
            if (sha != null) put("sha", sha)
        }
        val result = call(
            authedRequest("https://api.github.com/repos/$repo/contents/${encodePath(path)}")
                .put(jsonBody(payload)).build()
        ) as? JSONObject
        return result?.optJSONObject("content")?.let { optStr(it, "sha") }
    }

    // ── Git data API: commits made of tree changes ───────────────────────

    /** Current commit sha and its root tree sha for a branch (web: getBranchTree). */
    private suspend fun getBranchTree(repo: String, branch: String): Pair<String, String> {
        val ref = call(
            authedRequest("https://api.github.com/repos/$repo/git/ref/heads/${encodePath(branch)}").get().build()
        ) as JSONObject
        val commitSha = ref.getJSONObject("object").getString("sha")
        val commit = call(
            authedRequest("https://api.github.com/repos/$repo/git/commits/$commitSha").get().build()
        ) as JSONObject
        return Pair(commitSha, commit.getJSONObject("tree").getString("sha"))
    }

    /** tree → commit → move the branch ref. [entries] are git tree entries. */
    private suspend fun commitTree(
        repo: String, branch: String, parentSha: String, baseTreeSha: String,
        entries: JSONArray, message: String
    ) {
        val treePayload = JSONObject().put("base_tree", baseTreeSha).put("tree", entries)
        val tree = call(
            authedRequest("https://api.github.com/repos/$repo/git/trees").post(jsonBody(treePayload)).build()
        ) as JSONObject

        val commitPayload = JSONObject()
            .put("message", message)
            .put("tree", tree.getString("sha"))
            .put("parents", JSONArray().put(parentSha))
        val commit = call(
            authedRequest("https://api.github.com/repos/$repo/git/commits").post(jsonBody(commitPayload)).build()
        ) as JSONObject

        val refPayload = JSONObject().put("sha", commit.getString("sha"))
        call(
            authedRequest("https://api.github.com/repos/$repo/git/refs/heads/${encodePath(branch)}")
                .patch(jsonBody(refPayload)).build()
        )
    }

    /** Deletes files (folders are expanded to their files) in one commit; returns the file count. */
    suspend fun deleteFiles(repo: String, branch: String, targets: List<DeleteTarget>, message: String): Int {
        val (commitSha, treeSha) = getBranchTree(repo, branch)

        val paths = ArrayList<String>()
        for (t in targets) {
            if (t.isDir) listFolderFiles(repo, branch, t.path, paths) else paths.add(t.path)
        }
        if (paths.isEmpty()) throw GitHubApiException("No files found to delete", 0)

        // A tree entry with a null sha removes that path.
        val entries = JSONArray()
        for (p in paths) entries.put(treeEntry(p, JSONObject.NULL))
        commitTree(repo, branch, commitSha, treeSha, entries, message)
        return paths.size
    }

    /** Recursively collects every file path under a folder via the Contents API (no truncation risk). */
    private suspend fun listFolderFiles(repo: String, branch: String, folderPath: String, out: MutableList<String>) {
        val data = call(authedRequest(contentsUrl(repo, folderPath, branch)).get().build())
        val items: List<JSONObject> = when (data) {
            is JSONArray -> (0 until data.length()).map { i -> data.getJSONObject(i) }
            is JSONObject -> listOf(data)
            else -> emptyList()
        }
        for (o in items) {
            when (o.getString("type")) {
                "file" -> out.add(o.getString("path"))
                "dir" -> listFolderFiles(repo, branch, o.getString("path"), out)
            }
        }
    }

    /**
     * Renames a file in a single commit by moving its existing blob to the new path,
     * so no file content passes through the app (the Contents API would only carry
     * up to 1 MB, and an empty read would turn into an empty file).
     */
    suspend fun renameFile(repo: String, branch: String, oldPath: String, newPath: String) {
        if (pathExists(repo, branch, newPath)) {
            throw GitHubApiException("\"$newPath\" already exists", 422)
        }
        val old = call(authedRequest(contentsUrl(repo, oldPath, branch)).get().build()) as? JSONObject
            ?: throw GitHubApiException("Not a file", 0)
        val blobSha = old.getString("sha")

        val (commitSha, treeSha) = getBranchTree(repo, branch)
        val entries = JSONArray()
        entries.put(treeEntry(newPath, blobSha))
        entries.put(treeEntry(oldPath, JSONObject.NULL))
        commitTree(repo, branch, commitSha, treeSha, entries, "Rename $oldPath to $newPath")
    }

    private suspend fun pathExists(repo: String, branch: String, path: String): Boolean =
        try {
            call(authedRequest(contentsUrl(repo, path, branch)).get().build())
            true
        } catch (e: GitHubApiException) {
            if (e.httpStatus == 404) false else throw e
        }

    // ── Actions (workflow runs and their artifacts) ──────────────────────

    /** Latest workflow runs of the repository, newest first. */
    suspend fun listWorkflowRuns(repo: String, perPage: Int = 30): List<WorkflowRun> {
        val req = authedRequest("https://api.github.com/repos/$repo/actions/runs?per_page=$perPage").get().build()
        val json = execute(req) ?: throw GitHubApiException("Empty response from GitHub", 0)
        val arr = json.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until arr.length()).map { i -> parseRun(arr.getJSONObject(i)) }
    }

    /** Artifacts a run uploaded (actions/upload-artifact). Expired ones are listed but cannot be downloaded. */
    suspend fun listRunArtifacts(repo: String, runId: Long): List<RunArtifact> {
        val req = authedRequest("https://api.github.com/repos/$repo/actions/runs/$runId/artifacts?per_page=100").get().build()
        val json = execute(req) ?: throw GitHubApiException("Empty response from GitHub", 0)
        val arr = json.optJSONArray("artifacts") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            RunArtifact(
                id = a.getLong("id"),
                name = a.getString("name"),
                size = a.optLong("size_in_bytes", 0L),
                expired = a.optBoolean("expired", false)
            )
        }
    }

    /**
     * Streams an artifact's ZIP into [sink] (does not close it). GitHub answers with a redirect to
     * temporary storage; OkHttp follows it and drops the Authorization header for the other host.
     */
    suspend fun downloadArtifact(repo: String, artifactId: Long, sink: OutputStream) {
        withContext(Dispatchers.IO) {
            val req = authedRequest("https://api.github.com/repos/$repo/actions/artifacts/$artifactId/zip").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    throw GitHubApiException(errorMessage(bodyStr, resp.code), resp.code)
                }
                val body = resp.body ?: throw GitHubApiException("Empty response from GitHub", 0)
                body.byteStream().copyTo(sink)
            }
        }
    }

    /** Jobs of a run (latest attempt) with their steps — tells which step failed. */
    suspend fun listRunJobs(repo: String, runId: Long): List<WorkflowJob> {
        val data = call(
            authedRequest("https://api.github.com/repos/$repo/actions/runs/$runId/jobs?per_page=100&filter=latest").get().build()
        ) as? JSONObject ?: return emptyList()
        val arr = data.optJSONArray("jobs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            val stepsArr = j.optJSONArray("steps") ?: JSONArray()
            WorkflowJob(
                id = j.getLong("id"),
                name = optStr(j, "name") ?: "job",
                status = optStr(j, "status") ?: "",
                conclusion = optStr(j, "conclusion"),
                steps = (0 until stepsArr.length()).map { k ->
                    val st = stepsArr.getJSONObject(k)
                    WorkflowStep(
                        number = st.optInt("number", k + 1),
                        name = optStr(st, "name") ?: "step",
                        status = optStr(st, "status") ?: "",
                        conclusion = optStr(st, "conclusion")
                    )
                }
            )
        }
    }

    /**
     * Error / warning annotations of a job — the lines GitHub shows in red on the run page
     * ("Process completed with exit code 1", compiler errors, invalid workflow syntax ...).
     */
    suspend fun listJobAnnotations(repo: String, jobId: Long): List<String> {
        val arr = call(
            authedRequest("https://api.github.com/repos/$repo/check-runs/$jobId/annotations?per_page=50").get().build()
        ) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.getJSONObject(i)
            val level = optStr(a, "annotation_level") ?: return@mapNotNull null
            if (level == "notice") return@mapNotNull null
            val path = optStr(a, "path").orEmpty()
            val line = a.optInt("start_line", 0)
            val msg = optStr(a, "message").orEmpty().trim()
            val where = if (path.isNotEmpty() && path != ".github") {
                path + (if (line > 0) ":$line" else "") + " — "
            } else {
                ""
            }
            "[$level] $where$msg"
        }
    }

    /**
     * Streams one job's log and keeps only what matters: the last [tailLines] lines and every
     * line that looks like an error (so a huge Gradle log never has to fit in memory).
     * Timestamps and colour codes are stripped to keep the text short for an AI.
     */
    suspend fun fetchJobLog(repo: String, jobId: Long, tailLines: Int): JobLog = withContext(Dispatchers.IO) {
        val req = authedRequest("https://api.github.com/repos/$repo/actions/jobs/$jobId/logs").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val bodyStr = resp.body?.string().orEmpty()
                throw GitHubApiException(errorMessage(bodyStr, resp.code), resp.code)
            }
            val body = resp.body ?: throw GitHubApiException("Empty response from GitHub", 0)
            val tail = ArrayDeque<String>()
            val errors = LinkedHashSet<String>()
            var total = 0
            body.charStream().useLines { lines ->
                for (raw in lines) {
                    val line = LOG_ANSI.replace(LOG_TIMESTAMP.replace(raw, ""), "")
                    total++
                    tail.addLast(line)
                    if (tail.size > tailLines) tail.removeFirst()
                    if (errors.size < MAX_LOG_ERRORS) {
                        when {
                            line.contains("##[error]") -> errors.add(line.substringAfter("##[error]").trim())
                            line.startsWith("e: ") || line.contains(": error:") -> errors.add(line.trim())
                        }
                    }
                }
            }
            JobLog(tail = tail.toList(), errors = errors.filter { it.isNotEmpty() }, totalLines = total)
        }
    }

    /** Streams the ZIP with the raw logs of every job of a run into [sink] (does not close it). */
    suspend fun downloadRunLogs(repo: String, runId: Long, sink: OutputStream) {
        withContext(Dispatchers.IO) {
            val req = authedRequest("https://api.github.com/repos/$repo/actions/runs/$runId/logs").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    throw GitHubApiException(errorMessage(bodyStr, resp.code), resp.code)
                }
                val body = resp.body ?: throw GitHubApiException("Empty response from GitHub", 0)
                body.byteStream().copyTo(sink)
            }
        }
    }

    // ── History: commits of a branch, and going back to one of them ──────

    /** One page of a branch's commits, newest first. */
    suspend fun listCommits(repo: String, branch: String, page: Int, perPage: Int = 30): List<CommitInfo> {
        val url = "https://api.github.com/repos/$repo/commits?sha=${urlEncode(branch)}&per_page=$perPage&page=$page"
        val arr = call(authedRequest(url).get().build()) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.getJSONObject("commit")
            val gitAuthor = c.optJSONObject("author")
            val login = o.optJSONObject("author")?.let { optStr(it, "login") }
            CommitInfo(
                sha = o.getString("sha"),
                message = optStr(c, "message").orEmpty(),
                author = login ?: gitAuthor?.let { optStr(it, "name") }.orEmpty(),
                date = gitAuthor?.let { optStr(it, "date") }.orEmpty(),
                htmlUrl = optStr(o, "html_url").orEmpty()
            )
        }
    }

    /**
     * Puts the branch back to exactly how it looked at [targetSha] — without rewriting history:
     * a NEW commit is added on top whose file tree is the old commit's tree (a normal
     * fast-forward, no force push), so the restore itself can be undone the same way.
     */
    suspend fun restoreToCommit(repo: String, branch: String, targetSha: String, message: String) {
        val (headSha, _) = getBranchTree(repo, branch)
        if (headSha == targetSha) throw GitHubApiException("That is already the current version", 0)

        val target = call(
            authedRequest("https://api.github.com/repos/$repo/git/commits/$targetSha").get().build()
        ) as JSONObject
        val treeSha = target.getJSONObject("tree").getString("sha")

        val commitPayload = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", JSONArray().put(headSha))
        val commit = call(
            authedRequest("https://api.github.com/repos/$repo/git/commits").post(jsonBody(commitPayload)).build()
        ) as JSONObject

        val refPayload = JSONObject().put("sha", commit.getString("sha"))
        call(
            authedRequest("https://api.github.com/repos/$repo/git/refs/heads/${encodePath(branch)}")
                .patch(jsonBody(refPayload)).build()
        )
    }

    // ── Releases ─────────────────────────────────────────────────────────

    suspend fun listReleases(repo: String): List<Release> {
        val req = authedRequest("https://api.github.com/repos/$repo/releases?per_page=50").get().build()
        val arr = executeArray(req)
        return (0 until arr.length()).map { i -> parseRelease(arr.getJSONObject(i)) }
    }

    suspend fun createRelease(
        repo: String,
        tagName: String,
        name: String,
        body: String,
        draft: Boolean,
        prerelease: Boolean
    ): Release {
        val payload = JSONObject().apply {
            put("tag_name", tagName)
            put("name", name.ifBlank { tagName })
            put("body", body)
            put("draft", draft)
            put("prerelease", prerelease)
        }
        val req = authedRequest("https://api.github.com/repos/$repo/releases")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val json = execute(req) ?: throw GitHubApiException("Empty response from GitHub", 0)
        return parseRelease(json)
    }

    suspend fun deleteRelease(repo: String, releaseId: Long) = withContext(Dispatchers.IO) {
        val req = authedRequest("https://api.github.com/repos/$repo/releases/$releaseId").delete().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 204) {
                val bodyStr = resp.body?.string().orEmpty()
                val msg = try { JSONObject(bodyStr).optString("message", "HTTP ${resp.code}") }
                          catch (e: Exception) { "HTTP ${resp.code}" }
                throw GitHubApiException(msg, resp.code)
            }
        }
    }

    suspend fun deleteAsset(repo: String, assetId: Long) = withContext(Dispatchers.IO) {
        val req = authedRequest("https://api.github.com/repos/$repo/releases/assets/$assetId").delete().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 204) {
                val bodyStr = resp.body?.string().orEmpty()
                val msg = try { JSONObject(bodyStr).optString("message", "HTTP ${resp.code}") }
                          catch (e: Exception) { "HTTP ${resp.code}" }
                throw GitHubApiException(msg, resp.code)
            }
        }
    }

    /**
     * Uploads a release asset by streaming it from disk straight into the
     * HTTP request body (a custom RequestBody that reads the file via
     * Okio's `source()`, never loading the whole thing into a ByteArray).
     * This is what makes a ~1GB upload practical on a phone: memory use
     * stays flat regardless of file size, and progress is reported via
     * onProgress as bytes actually leave the device — not estimated, not
     * faked, read from the real number of bytes OkHttp has written so far.
     */
    suspend fun uploadAsset(
        repo: String,
        releaseId: Long,
        file: File,
        mimeType: String,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit,
        name: String = file.name
    ): ReleaseAsset {
        val totalBytes = file.length()
        val fileBody = file.asRequestBody(mimeType.toMediaTypeOrNull())
        return postAsset(repo, releaseId, name, mimeType, ProgressRequestBody(fileBody, totalBytes, onProgress))
    }

    /**
     * Same upload, but streamed straight from [open] (e.g. a content:// Uri) — no copy into app storage first,
     * so no free space is needed beyond the file itself. [size] must be the exact byte length (GitHub needs
     * Content-Length); [open] may be called again if OkHttp retries the connection.
     */
    suspend fun uploadAssetStream(
        repo: String,
        releaseId: Long,
        name: String,
        mimeType: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): ReleaseAsset {
        val body = StreamRequestBody(mimeType.toMediaTypeOrNull(), size, open)
        return postAsset(repo, releaseId, name, mimeType, ProgressRequestBody(body, size, onProgress))
    }

    private suspend fun postAsset(
        repo: String,
        releaseId: Long,
        name: String,
        mimeType: String,
        body: RequestBody
    ): ReleaseAsset = withContext(Dispatchers.IO) {
        val encodedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val url = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets?name=$encodedName"

        val req = authedRequest(url)
            .addHeader("Content-Type", mimeType)
            .post(body)
            .build()

        val json = execute(req) ?: throw GitHubApiException("Empty response from GitHub", 0)
        ReleaseAsset(
            id = json.getLong("id"),
            name = json.getString("name"),
            size = json.getLong("size"),
            downloadCount = json.optInt("download_count", 0),
            browserDownloadUrl = json.getString("browser_download_url")
        )
    }

    private fun parseRepo(o: JSONObject): RepoRef = RepoRef(
        fullName = o.getString("full_name"),
        private = o.getBoolean("private"),
        description = optStr(o, "description"),
        defaultBranch = optStr(o, "default_branch") ?: "main",
        sizeKb = o.optLong("size", 0L),
        updatedAt = optStr(o, "pushed_at") ?: optStr(o, "updated_at") ?: ""
    )

    private fun parseRun(o: JSONObject): WorkflowRun = WorkflowRun(
        id = o.getLong("id"),
        workflowName = optStr(o, "name") ?: "Workflow",
        title = optStr(o, "display_title") ?: optStr(o, "name") ?: "",
        branch = optStr(o, "head_branch") ?: "",
        event = optStr(o, "event") ?: "",
        status = optStr(o, "status") ?: "",
        conclusion = optStr(o, "conclusion"),
        runNumber = o.optInt("run_number", 0),
        actor = o.optJSONObject("actor")?.let { optStr(it, "login") } ?: "",
        createdAt = optStr(o, "run_started_at") ?: optStr(o, "created_at") ?: "",
        htmlUrl = optStr(o, "html_url") ?: "",
        headSha = optStr(o, "head_sha") ?: ""
    )

    private fun parseRelease(o: JSONObject): Release {
        val assetsArr = o.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until assetsArr.length()).map { i ->
            val a = assetsArr.getJSONObject(i)
            ReleaseAsset(
                id = a.getLong("id"),
                name = a.getString("name"),
                size = a.getLong("size"),
                downloadCount = a.optInt("download_count", 0),
                browserDownloadUrl = a.getString("browser_download_url")
            )
        }
        return Release(
            id = o.getLong("id"),
            tagName = o.getString("tag_name"),
            name = optStr(o, "name"),
            body = optStr(o, "body"),
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            assets = assets
        )
    }

    private companion object {
        /** Rate-limit waits per request before giving up (each up to MAX_RATE_LIMIT_WAIT_SEC). */
        const val MAX_RATE_LIMIT_WAITS = 40
        const val MAX_RATE_LIMIT_WAIT_SEC = 3_700L

        /** Error lines kept per job log. */
        const val MAX_LOG_ERRORS = 60

        /** "2026-10-13T08:15:30.1234567Z " that GitHub puts in front of every log line (and a BOM on the first). */
        val LOG_TIMESTAMP = Regex("^\\uFEFF?\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ?")

        /** Colour / cursor escape sequences. */
        val LOG_ANSI = Regex("\\u001B\\[[0-9;]*[A-Za-z]")
    }
}

/** Request body with a known length whose bytes come from an InputStream, copied through in small pieces. */
private class StreamRequestBody(
    private val type: MediaType?,
    private val length: Long,
    private val open: () -> InputStream
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        open().source().use { sink.writeAll(it) }
    }
}

/**
 * Wraps a file RequestBody to report upload progress. OkHttp doesn't
 * expose this natively — writeTo() is the only hook that sees bytes
 * actually being written to the network, so progress is derived from
 * wrapping the sink passed into it, counting bytes as they pass through.
 */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val totalBytes: Long,
    private val onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        var bytesWritten = 0L
        val countingSink = object : okio.ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, totalBytes)
            }
        }
        val bufferedCountingSink = countingSink.buffer()
        delegate.writeTo(bufferedCountingSink)
        bufferedCountingSink.flush()
    }
}

