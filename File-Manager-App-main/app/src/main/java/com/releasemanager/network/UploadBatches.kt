package com.releasemanager.network

import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.UploadItem
import kotlinx.coroutines.CancellationException

/**
 * Port of splitIntoBatches() / uploadInBatches() from the web app's app.js.
 *
 * The web needs them because Vercel caps a request body at 4.5MB. The device has no such cap, but
 * the same grouping is kept on purpose: a huge selection or ZIP becomes several moderate commits
 * (progress is real, a failure loses one batch instead of everything, and a single tree request
 * never carries hundreds of MB of inlined text) — the same trade-off the web documents.
 */
const val UPLOAD_BATCH_MAX_BYTES = 3_670_016L   // 3.5 MB of base64 text per commit
const val UPLOAD_BATCH_MAX_FILES = 20           // binary files each cost one blob request

/** Groups [items] (base64 `content`) under the byte / count budget; does no I/O. */
fun splitIntoBatches(items: List<UploadItem>): List<List<UploadItem>> {
    val batches = mutableListOf<List<UploadItem>>()
    var current = mutableListOf<UploadItem>()
    var currentBytes = 0L

    for (item in items) {
        val size = item.content.length.toLong()
        val wouldOverflow = current.isNotEmpty() &&
            (currentBytes + size > UPLOAD_BATCH_MAX_BYTES || current.size >= UPLOAD_BATCH_MAX_FILES)
        if (wouldOverflow) {
            batches.add(current)
            current = mutableListOf()
            currentBytes = 0L
        }
        current.add(item)
        currentBytes += size
    }
    if (current.isNotEmpty()) batches.add(current)
    return batches
}

/**
 * Commits [items] as one or more commits (see [splitIntoBatches]). [onProgress] is called after each
 * batch lands with (files done so far, total files). Returns the number of files committed.
 */
suspend fun GitHubApi.uploadInBatches(
    repo: String,
    branch: String,
    items: List<UploadItem>,
    message: String,
    onProgress: ((done: Int, total: Int) -> Unit)? = null
): Int {
    val batches = splitIntoBatches(items)
    val batchCount = batches.size
    var done = 0

    for ((b, batch) in batches.withIndex()) {
        val label = if (batchCount > 1) "$message (batch ${b + 1}/$batchCount)" else message
        try {
            uploadFiles(repo, branch, batch, label)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GitHubApiException(
                "Batch ${b + 1}/$batchCount (files ${done + 1}-${done + batch.size} of ${items.size}) failed: ${e.message}",
                (e as? GitHubApiException)?.httpStatus ?: 0
            )
        }
        done += batch.size
        onProgress?.invoke(done, items.size)
    }
    return done
}
