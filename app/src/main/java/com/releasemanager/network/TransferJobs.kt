package com.releasemanager.network

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.releasemanager.data.FileEntry
import com.releasemanager.service.TransferOutcome
import com.releasemanager.service.TransferProgress
import com.releasemanager.util.copyStream
import com.releasemanager.util.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * The work of every upload / download, written without any reference to an Activity or a View:
 * these functions run inside TransferService, possibly after the app was closed. Everything they
 * need (api, ContentResolver, cache dir, paths, Uris) is passed in as plain values.
 */

/** A file the user picked for upload. */
class PickedSource(val uri: Uri, val name: String, val size: Long = -1L)

/** One file to put into the clone: [open] is called on a background thread. */
class PushSource(val path: String, val size: Long = -1L, val open: () -> InputStream)

/**
 * Document Uris from the system pickers are only readable while the Activity that received them is alive,
 * unless the app takes a persistable grant — which is what lets a transfer outlive the screen.
 */
object UriAccess {
    fun persist(cr: ContentResolver, uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            cr.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // A provider without persistable grants: the Uri still works while the app is open
        }
    }

    fun release(cr: ContentResolver, uri: Uri) {
        try {
            cr.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Nothing was persisted for it
        }
    }
}

// ── ZIP scanning (shared by the stage-mode screen code and the background upload) ────────────────

class ZipTarget(val entry: ZipEntry, val name: String, val path: String)

class ZipScanResult(
    val targets: List<ZipTarget>,
    val duplicateCount: Int,
    val skippedMalformed: List<String>
) {
    val duplicateMessage: String?
        get() = if (duplicateCount > 0)
            "Skipped $duplicateCount duplicate path${if (duplicateCount > 1) "s" else ""} in ZIP (kept the last version of each)"
        else null

    val malformedMessage: String?
        get() = if (skippedMalformed.isNotEmpty()) {
            val preview = skippedMalformed.take(3).joinToString(", ")
            val more = if (skippedMalformed.size > 3) " and ${skippedMalformed.size - 3} more" else ""
            "Skipped ${skippedMalformed.size} file(s) with invalid paths: $preview$more"
        } else null
}

object ZipScan {
    /** Web: the ZIP-Slip / duplicate / malformed-path filtering, entry by entry. */
    fun collect(zip: ZipFile, dir: String): ZipScanResult {
        val byPath = LinkedHashMap<String, ZipTarget>() // lower-cased target path → entry (GitHub is case-insensitive in practice)
        var duplicateCount = 0
        val skippedMalformed = mutableListOf<String>()

        for (entry in zip.entries().toList()) {
            if (entry.isDirectory) continue
            val normalized = entry.name.replace('\\', '/')
            if (normalized.contains("..") || normalized.startsWith("/")) continue
            if (normalized == ".DS_Store" || normalized.startsWith("__MACOSX/") || normalized.contains("/.DS_Store")) continue
            val targetPath = if (dir.isNotEmpty()) "$dir/$normalized" else normalized
            if (findMalformedSegment(targetPath) != null) {
                skippedMalformed.add(targetPath)
                continue
            }
            val key = targetPath.lowercase()
            if (byPath.containsKey(key)) duplicateCount++
            byPath[key] = ZipTarget(entry, normalized, targetPath) // the last occurrence wins
        }
        return ZipScanResult(byPath.values.toList(), duplicateCount, skippedMalformed)
    }

    /**
     * Segments GitHub's Trees API calls "malformed": empty, "." / "..", ".git", leading/trailing
     * whitespace or a trailing dot (Windows-made ZIPs often have these without the author noticing).
     */
    private fun findMalformedSegment(targetPath: String): String? {
        for (seg in targetPath.split('/')) {
            if (seg.isEmpty()) return "(empty segment)"
            if (seg == "." || seg == "..") return seg
            if (seg == ".git") return seg
            if (seg != seg.trim()) return seg
            if (seg.endsWith(".")) return seg
        }
        return null
    }
}

// ── Uploads ──────────────────────────────────────────────────────────────────────────────────────

/** Where each stage sits on the 0..1 bar of a push; JGit's own percentages restart for every task. */
private const val PART_CLONE_END = 0.10f   // 0.00–0.10  clone: "Receiving objects"
private const val PART_COPY_END = 0.20f    // 0.10–0.20  copying the files in, by bytes
private const val PART_PUSH_START = 0.25f  // 0.20–0.25  commit, then 0.25–1.00 "Writing objects"

/**
 * Clone → copy every source in → one commit → one push. The bar only ever moves forward, and shows
 * "working" (unknown length) whenever there is no honest number — the clone before it starts receiving,
 * and a push of only a few large objects, where git reports no progress inside an object.
 */
suspend fun pushSources(
    api: GitHubApi,
    cacheDir: File,
    repo: String,
    branch: String,
    message: String,
    sources: List<PushSource>,
    progress: TransferProgress
) {
    val total = sources.size
    val bytesKnown = total > 0 && sources.all { it.size >= 0 }
    val totalBytes = if (bytesKnown) sources.sumOf { it.size } else 0L
    var high = 0f
    fun forward(fraction: Float, label: String) {
        high = maxOf(high, fraction.coerceIn(0f, 1f))
        progress.update(high, label)
    }

    progress.update(-1f, "Cloning $repo…")
    val session = api.openGitSession(cacheDir, repo, branch) { phase, text, pct ->
        when {
            phase == GitPhase.CLONE_DATA && pct >= 0 -> forward(PART_CLONE_END * pct / 100f, text)
            phase == GitPhase.PUSH && pct >= 0 -> forward(PART_PUSH_START + (1f - PART_PUSH_START) * pct / 100f, text)
            phase == GitPhase.PUSH -> progress.update(-1f, text)
            else -> progress.label(text)
        }
    }
    try {
        var copied = 0L
        for ((i, src) in sources.withIndex()) {
            progress.item(i, "Copying ${i + 1}/$total…")
            session.addFile(src.path, onBytes = { n ->
                copied += n
                val share = if (bytesKnown && totalBytes > 0) copied.toFloat() / totalBytes else i.toFloat() / total
                val detail = if (bytesKnown && totalBytes > 0) " — ${formatSize(copied)} / ${formatSize(totalBytes)}" else ""
                forward(PART_CLONE_END + (PART_COPY_END - PART_CLONE_END) * share, "Copying ${i + 1}/$total$detail")
            }, open = src.open)
            if (!(bytesKnown && totalBytes > 0)) {
                forward(PART_CLONE_END + (PART_COPY_END - PART_CLONE_END) * (i + 1) / total, "Copying ${i + 1}/$total…")
            }
        }
        forward(PART_COPY_END, "Preparing commit…")
        session.commitAndPush(message)
        progress.update(1f, "Done")
    } finally {
        session.close()
    }
}

object UploadJobs {

    /** GitHub itself refuses files over 100 MB in a repository, whatever tool is used. */
    const val GITHUB_MAX_FILE_BYTES = 100L * 1024 * 1024

    fun tooLargeMessage(name: String, size: Long) =
        "\"$name\" is ${formatSize(size)} — GitHub does not accept files over " +
            "${formatSize(GITHUB_MAX_FILE_BYTES)} in a repository. Attach large files to a release instead."

    /** Upload Files (Quick Edit): every picked file in ONE commit. */
    suspend fun pushFiles(
        api: GitHubApi,
        cr: ContentResolver,
        cacheDir: File,
        repo: String,
        branch: String,
        dir: String,
        files: List<PickedSource>,
        message: String,
        progress: TransferProgress
    ): TransferOutcome {
        try {
            val sources = files.map { f ->
                PushSource(if (dir.isNotEmpty()) "$dir/${f.name}" else f.name, f.size) {
                    cr.openInputStream(f.uri) ?: throw IOException("Could not read \"${f.name}\"")
                }
            }
            pushSources(api, cacheDir, repo, branch, message, sources, progress)
            val total = files.size
            return TransferOutcome("$total file${if (total > 1) "s" else ""} uploaded")
        } finally {
            files.forEach { UriAccess.release(cr, it.uri) }
        }
    }

    /** Upload ZIP (Quick Edit): unpack straight into one commit. */
    suspend fun pushZip(
        api: GitHubApi,
        cr: ContentResolver,
        cacheDir: File,
        repo: String,
        branch: String,
        dir: String,
        zipUri: Uri,
        message: String,
        progress: TransferProgress
    ): TransferOutcome {
        var temp: File? = null
        try {
            progress.update(-1f, "Reading ZIP…")
            // ZipFile needs a real file, so the picked document is copied into the app cache first
            val copy = copyUriToCache(cr, cacheDir, zipUri, "upload_zip_${System.currentTimeMillis()}.zip", progress, "Reading ZIP…")
            temp = copy
            val zip = try {
                withContext(Dispatchers.IO) { ZipFile(copy) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Cannot read ZIP file. Please make sure it is a standard ZIP (not RAR, 7z, or password-protected).")
            }
            return zip.use {
                val scan = ZipScan.collect(zip, dir)
                val targets = scan.targets
                if (targets.isEmpty()) throw IOException("ZIP contains no files")
                val tooBig = targets.firstOrNull { it.entry.size > GITHUB_MAX_FILE_BYTES }
                if (tooBig != null) throw IOException(tooLargeMessage(tooBig.name, tooBig.entry.size))

                val sources = targets.map { t -> PushSource(t.path, t.entry.size) { zip.getInputStream(t.entry) } }
                pushSources(api, cacheDir, repo, branch, message, sources, progress)

                val skipped = listOfNotNull(
                    scan.duplicateCount.takeIf { it > 0 }?.let { "$it duplicate" },
                    scan.skippedMalformed.size.takeIf { it > 0 }?.let { "$it invalid path" }
                )
                TransferOutcome(
                    "Uploaded ${targets.size} files from ZIP" +
                        if (skipped.isNotEmpty()) " (skipped ${skipped.joinToString(", ")})" else ""
                )
            }
        } finally {
            temp?.let { f -> withContext(NonCancellable + Dispatchers.IO) { f.delete() } }
            UriAccess.release(cr, zipUri)
        }
    }

    /**
     * One file as a release asset. With a known [size] the bytes stream straight from the picked file;
     * without one GitHub's required Content-Length is unknown, so the file is copied into the cache first.
     * The created asset comes back in [TransferOutcome.result].
     */
    suspend fun releaseAsset(
        api: GitHubApi,
        cr: ContentResolver,
        cacheDir: File,
        repo: String,
        releaseId: Long,
        uri: Uri,
        fileName: String,
        mimeType: String,
        size: Long,
        progress: TransferProgress
    ): TransferOutcome {
        var temp: File? = null
        try {
            val onProgress = { sent: Long, total: Long -> progress.bytes("Uploading", sent, total) }
            val asset = if (size > 0) {
                progress.update(-1f, "Uploading…")
                api.uploadAssetStream(
                    repo, releaseId, fileName, mimeType, size,
                    { cr.openInputStream(uri) ?: throw IOException("Could not open selected file") },
                    onProgress
                )
            } else {
                progress.update(-1f, "Copying into app storage…")
                val copy = copyUriToCache(cr, cacheDir, uri, "upload_${System.currentTimeMillis()}_$fileName", progress, "Copying…")
                temp = copy
                progress.update(-1f, "Uploading…")
                api.uploadAsset(repo, releaseId, copy, mimeType, onProgress, fileName)
            }
            return TransferOutcome("Uploaded $fileName", asset)
        } finally {
            temp?.let { f -> withContext(NonCancellable + Dispatchers.IO) { f.delete() } }
            UriAccess.release(cr, uri)
        }
    }

    private suspend fun copyUriToCache(
        cr: ContentResolver,
        cacheDir: File,
        uri: Uri,
        fileName: String,
        progress: TransferProgress,
        prefix: String
    ): File = withContext(Dispatchers.IO) {
        val out = File(cacheDir, fileName)
        try {
            val input = cr.openInputStream(uri) ?: throw IOException("Could not open the selected file")
            input.use { src ->
                out.outputStream().use { dst ->
                    copyStream(src, dst, -1L) { done, _ -> progress.bytes(prefix, done, -1L) }
                }
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        out
    }
}

// ── Downloads ────────────────────────────────────────────────────────────────────────────────────

/** Every download writes into a document the user chose in the system "save as" picker. */
object DownloadJobs {

    suspend fun file(
        api: GitHubApi, cr: ContentResolver, uri: Uri,
        repo: String, path: String, branch: String, name: String,
        progress: TransferProgress
    ): TransferOutcome {
        writeTo(cr, uri) { out ->
            api.downloadFile(repo, path, branch, out) { done, total -> progress.bytes("Downloading", done, total) }
        }
        return TransferOutcome("Saved $name")
    }

    /** Several files go into one ZIP. */
    suspend fun filesZip(
        api: GitHubApi, cr: ContentResolver, uri: Uri,
        repo: String, branch: String, files: List<FileEntry>,
        progress: TransferProgress
    ): TransferOutcome {
        val n = files.size
        writeTo(cr, uri) { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                for ((i, f) in files.withIndex()) {
                    progress.update(i.toFloat() / n, "Downloading ${i + 1}/$n…")
                    zip.putNextEntry(ZipEntry(f.path))
                    api.downloadFile(repo, f.path, branch, zip) { done, total ->
                        progress.bytes("Downloading ${i + 1}/$n —", done, total, base = i.toFloat() / n, span = 1f / n)
                    }
                    zip.closeEntry()
                }
            }
        }
        return TransferOutcome("Downloaded $n files")
    }

    /**
     * The whole repository (one branch) as a single ZIP.
     *
     * [wrapFolder] = true saves GitHub's own layout, everything inside one "owner-repo-<commit>/" folder.
     * false repacks it so the files sit at the ZIP's root: it extracts straight into whatever folder the
     * user picks, and goes back through Upload ZIP without an extra folder level.
     * Progress: 0–70 % downloading, 70–100 % repacking (the root-level layout only).
     */
    suspend fun repoZip(
        api: GitHubApi, cr: ContentResolver, cacheDir: File, uri: Uri,
        repo: String, branch: String, wrapFolder: Boolean, fileName: String,
        progress: TransferProgress
    ): TransferOutcome {
        writeTo(cr, uri) { out ->
            if (wrapFolder) {
                api.downloadRepoZip(repo, branch, out) { done, total -> progress.bytes("Downloading", done, total) }
            } else {
                val temp = File(cacheDir, "repo_zip_${System.currentTimeMillis()}.zip")
                try {
                    withContext(Dispatchers.IO) {
                        temp.outputStream().buffered().use { sink ->
                            api.downloadRepoZip(repo, branch, sink) { done, total ->
                                progress.bytes("Downloading", done, total, span = 0.7f)
                            }
                        }
                    }
                    repackWithoutRoot(temp, out, progress)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { temp.delete() }
                }
            }
        }
        return TransferOutcome("Saved $fileName")
    }

    /** Copies [src] into [out], dropping the single top-level folder GitHub wraps every entry in. */
    private suspend fun repackWithoutRoot(src: File, out: OutputStream, progress: TransferProgress) {
        withContext(Dispatchers.IO) {
            ZipFile(src).use { zip ->
                val entries = zip.entries().toList()
                val root = entries.firstOrNull { it.name.contains('/') }?.name?.substringBefore('/').orEmpty()
                val prefix = if (root.isEmpty()) "" else "$root/"
                val total = entries.size
                ZipOutputStream(out.buffered()).use { dst ->
                    for ((i, entry) in entries.withIndex()) {
                        val name = if (prefix.isNotEmpty() && entry.name.startsWith(prefix)) entry.name.substring(prefix.length) else entry.name
                        if (name.isEmpty()) continue // the wrapper folder itself
                        if (i % 20 == 0) progress.update(0.7f + 0.3f * i / total, "Packing ${i + 1}/$total…")
                        val copy = ZipEntry(name)
                        if (entry.time != -1L) copy.time = entry.time
                        dst.putNextEntry(copy)
                        if (!entry.isDirectory) zip.getInputStream(entry).use { copyStream(it, dst) }
                        dst.closeEntry()
                    }
                }
            }
        }
    }

    suspend fun runLogs(
        api: GitHubApi, cr: ContentResolver, uri: Uri,
        repo: String, runId: Long, fileName: String,
        progress: TransferProgress
    ): TransferOutcome {
        writeTo(cr, uri) { out ->
            api.downloadRunLogs(repo, runId, out) { done, total -> progress.bytes("Downloading", done, total) }
        }
        return TransferOutcome("Saved $fileName")
    }

    suspend fun artifact(
        api: GitHubApi, cr: ContentResolver, uri: Uri,
        repo: String, artifactId: Long, fileName: String,
        progress: TransferProgress
    ): TransferOutcome {
        writeTo(cr, uri) { out ->
            api.downloadArtifact(repo, artifactId, out) { done, total -> progress.bytes("Downloading", done, total) }
        }
        return TransferOutcome("Saved $fileName")
    }

    /**
     * Opens the chosen document, runs [block], and — when it fails or is cancelled — deletes the document:
     * a failed download would otherwise leave an empty file behind at the chosen location.
     */
    private suspend fun writeTo(cr: ContentResolver, uri: Uri, block: suspend (OutputStream) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val out = cr.openOutputStream(uri, "w") ?: throw IOException("Could not open the destination file")
                out.use { block(it) }
            }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(cr, uri) }
            throw e
        } finally {
            UriAccess.release(cr, uri)
        }
    }
}
