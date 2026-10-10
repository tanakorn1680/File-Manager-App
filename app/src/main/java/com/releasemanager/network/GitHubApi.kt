package com.releasemanager.network

import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.GitHubUser
import com.releasemanager.data.Release
import com.releasemanager.data.ReleaseAsset
import com.releasemanager.data.RepoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to GitHub's REST API directly from the device — no server of ours
 * sits in between, which is the entire point of this app existing: the
 * web version (Vercel) is capped at ~4.5MB per request by the hosting
 * platform itself, a limit that does not apply here because there is no
 * platform in the middle to impose it. Two GitHub hosts are used, matching
 * GitHub's own API split: api.github.com for everything except the actual
 * asset bytes, and uploads.github.com for the upload itself.
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

    suspend fun verifyTokenAndGetUser(): GitHubUser {
        val req = authedRequest("https://api.github.com/user").get().build()
        val json = execute(req) ?: throw GitHubApiException("Empty response from GitHub", 0)
        return GitHubUser(login = json.getString("login"))
    }

    suspend fun listRepos(): List<RepoRef> {
        val req = authedRequest(
            "https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member"
        ).get().build()
        val arr = executeArray(req)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RepoRef(fullName = o.getString("full_name"), private = o.getBoolean("private"))
        }
    }

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
            name = o.optString("name", null),
            body = o.optString("body", null),
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            assets = assets
        )
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
        val bufferedCountingSink = okio.buffer(countingSink)
        delegate.writeTo(bufferedCountingSink)
        bufferedCountingSink.flush()
    }
}
