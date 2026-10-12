package com.releasemanager.network

import android.util.Base64
import com.releasemanager.data.DeleteTarget
import com.releasemanager.data.FileContent
import com.releasemanager.data.FileEntry
import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.GitHubUser
import com.releasemanager.data.Release
import com.releasemanager.data.ReleaseAsset
import com.releasemanager.data.RepoRef
import com.releasemanager.data.RunArtifact
import com.releasemanager.data.UploadItem
import com.releasemanager.data.WorkflowRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
    private suspend fun call(request: Request): Any? = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GitHubApiException(errorMessage(bodyStr, resp.code), resp.code)
            if (bodyStr.isBlank()) null else JSONTokener(bodyStr).nextValue()
        }
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

    /**
     * A NUL byte or any byte sequence that isn't valid UTF-8 means the file is binary.
     * Only text can be inlined as tree `content`; binary needs a base64 blob (web: isSafeUtf8Text).
     */
    private fun isSafeUtf8Text(bytes: ByteArray): Boolean {
        for (b in bytes) if (b == 0.toByte()) return false
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    private val staleRefRegex =
        Regex("reference cannot be updated|update is not a fast forward", RegexOption.IGNORE_CASE)

    /**
     * Commits [items] (base64 content) in one commit (web: uploadFiles).
     *
     * Text files are inlined into the tree-create call; binary files first get their own
     * blob. Rapid back-to-back commits can hit "Reference cannot be updated" because
     * GitHub's read-after-write consistency lags the previous ref update, so the HEAD
     * read + tree + commit + ref update is retried with a small backoff, as on the web.
     */
    suspend fun uploadFiles(repo: String, branch: String, items: List<UploadItem>, message: String) {
        try {
            uploadFilesImpl(repo, branch, items, message)
        } catch (e: OutOfMemoryError) {
            throw GitHubApiException("Not enough memory to upload this many / this large files at once. Try fewer or smaller files.", 0)
        }
    }

    private suspend fun uploadFilesImpl(repo: String, branch: String, items: List<UploadItem>, message: String) {
        val treeItems = JSONArray()
        val binaryItems = ArrayList<UploadItem>()
        // Decoding and UTF-8 checking up to a few MB is CPU work: keep it off the main thread
        withContext(Dispatchers.Default) {
            for (f in items) {
                val bytes = Base64.decode(f.content, Base64.DEFAULT)
                if (isSafeUtf8Text(bytes)) {
                    treeItems.put(
                        JSONObject().put("path", f.path).put("mode", "100644").put("type", "blob")
                            .put("content", String(bytes, Charsets.UTF_8))
                    )
                } else {
                    binaryItems.add(f)
                }
            }
        }
        // Sequential on purpose: keeps a binary-heavy batch under GitHub's
        // per-minute content-creation limits.
        for (f in binaryItems) {
            val blobPayload = JSONObject().put("content", f.content).put("encoding", "base64")
            val blob = call(
                authedRequest("https://api.github.com/repos/$repo/git/blobs").post(jsonBody(blobPayload)).build()
            ) as JSONObject
            treeItems.put(treeEntry(f.path, blob.getString("sha")))
        }

        var lastErr: GitHubApiException? = null
        for (attempt in 0..MAX_REF_RETRIES) {
            if (attempt > 0) delay(300L * attempt)
            try {
                val (commitSha, baseTreeSha) = getBranchTree(repo, branch)
                commitTree(repo, branch, commitSha, baseTreeSha, treeItems, message.ifBlank { "Upload files" })
                return
            } catch (e: GitHubApiException) {
                lastErr = e
                val retryable = staleRefRegex.containsMatchIn(e.message.orEmpty())
                if (!retryable || attempt == MAX_REF_RETRIES) throw e
            }
        }
        throw lastErr ?: GitHubApiException("Upload failed", 0)
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
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): ReleaseAsset = withContext(Dispatchers.IO) {
        val totalBytes = file.length()
        val fileBody = file.asRequestBody(mimeType.toMediaTypeOrNull())
        val progressBody = ProgressRequestBody(fileBody, totalBytes, onProgress)

        val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        val url = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets?name=$encodedName"

        val req = authedRequest(url)
            .addHeader("Content-Type", mimeType)
            .post(progressBody)
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
        htmlUrl = optStr(o, "html_url") ?: ""
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
        const val MAX_REF_RETRIES = 4
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
