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
    val defaultBranch: String = "main",
    val sizeKb: Long = 0,           // GitHub reports repo size in KB
    val updatedAt: String = ""      // pushed_at (falls back to updated_at); ISO-8601 sorts as text
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

/** One GitHub Actions workflow run (GET /repos/:repo/actions/runs). App-only: the web has no Actions view. */
data class WorkflowRun(
    val id: Long,
    val workflowName: String,
    val title: String,          // display_title: the commit message / PR title that triggered the run
    val branch: String,
    val event: String,          // push, workflow_dispatch, pull_request, ...
    val status: String,         // queued | in_progress | completed | waiting | ...
    val conclusion: String?,    // success | failure | cancelled | skipped | ...; null until completed
    val runNumber: Int,
    val actor: String,
    val createdAt: String,      // ISO-8601, UTC
    val htmlUrl: String,
    val headSha: String = ""    // commit the run was triggered for
)

/** One workflow of a repository (GET /repos/:repo/actions/workflows): a file in .github/workflows. */
data class Workflow(
    val id: Long,
    val name: String,
    val path: String,       // ".github/workflows/build-apk.yml"
    val state: String       // active | disabled_manually | disabled_inactivity | ...
) {
    val active: Boolean get() = state == "active"
    val fileName: String get() = path.substringAfterLast('/')
}

/** A file bundle a run uploaded with actions/upload-artifact; downloads as a ZIP. */
data class RunArtifact(
    val id: Long,
    val name: String,
    val size: Long,
    val expired: Boolean
)

/** One step of a workflow job (GET /repos/:repo/actions/runs/:id/jobs). */
data class WorkflowStep(
    val number: Int,
    val name: String,
    val status: String,
    val conclusion: String?
)

/** One job of a workflow run, with its steps — tells which step failed. */
data class WorkflowJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val steps: List<WorkflowStep>
)

/** The useful part of a job log: the last lines, plus every line that looks like an error. */
data class JobLog(
    val tail: List<String>,
    val errors: List<String>,
    val totalLines: Int
)

/** One commit of a branch (GET /repos/:repo/commits) — one entry of the edit history. */
data class CommitInfo(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,       // ISO-8601, UTC
    val htmlUrl: String
) {
    val shortSha: String get() = sha.take(7)
    val title: String get() = message.lineSequence().firstOrNull().orEmpty().trim()
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

/** A file ready to be committed: `content` is base64. */
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
    val content: String?,
    /** Staged upload kept on disk (not in memory) so Stage Mode has no size cap of its own; [content] is null then. */
    val diskPath: String? = null
)

/** File currently open in the editor (web: state.editingFile). */
class EditingFile(
    val path: String,
    var sha: String?,
    val name: String
)

/** Cache sub-folder holding Stage Mode uploads (kept on disk, not in memory) until Commit All. */
const val STAGED_DIR = "staged-uploads"

enum class EditMode { QUICK, STAGE }

/** Thrown for any non-2xx GitHub API response, carrying the message GitHub sent back. */
class GitHubApiException(
    message: String,
    val httpStatus: Int,
    /** Seconds GitHub asked us to wait (rate limit); null for every other error. */
    val retryAfterSec: Long? = null
) : Exception(message)
