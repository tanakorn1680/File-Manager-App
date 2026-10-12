package com.releasemanager.data

/**
 * Mirrors the subset of GitHub's release JSON this app actually uses.
 * Field names here are Kotlin-idiomatic; JsonParser.kt maps GitHub's
 * snake_case JSON keys (tag_name, browser_download_url, etc.) onto these.
 */
data class Release(
    val id: Long,
    val tagName: String,
    val name: String?,
    val body: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>
)

data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val downloadCount: Int,
    val browserDownloadUrl: String
)

data class RepoRef(
    val fullName: String,   // "owner/repo"
    val private: Boolean,
    val description: String? = null,
    val defaultBranch: String = "main"
)

data class GitHubUser(
    val login: String
)

/** One row of a Contents API directory listing (web: getTree() in api/github.js). */
data class FileEntry(
    val name: String,
    val path: String,
    val type: String,   // "file" | "dir" | "symlink" | "submodule"
    val size: Long,
    val sha: String?
) {
    val isDir: Boolean get() = type == "dir"
}

/** Text file fetched for the editor (web: getFile()). */
data class FileContent(
    val text: String,
    val sha: String,
    val size: Long
)

/** A path to delete; folders are expanded into their files before deleting. */
data class DeleteTarget(
    val path: String,
    val isDir: Boolean
)

/** A file ready to be committed: `content` is base64 (web: items passed to uploadInBatches). */
data class UploadItem(
    val path: String,
    val name: String,
    val content: String
)

/**
 * Stage Mode entry (web: state.stagedFiles values).
 * action: "edit" | "create" | "upload" | "delete"; content is base64 (null for delete).
 */
data class StagedFile(
    val path: String,
    val name: String,
    val sha: String?,
    val type: String,
    val action: String,
    val content: String?
)

/** File currently open in the editor (web: state.editingFile). */
class EditingFile(
    val path: String,
    var sha: String?,
    val name: String
)

enum class EditMode { QUICK, STAGE }

/** Thrown for any non-2xx GitHub API response, carrying the message GitHub sent back. */
class GitHubApiException(message: String, val httpStatus: Int) : Exception(message)
