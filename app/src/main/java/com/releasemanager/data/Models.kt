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
    val private: Boolean
)

data class GitHubUser(
    val login: String
)

/** Thrown for any non-2xx GitHub API response, carrying the message GitHub sent back. */
class GitHubApiException(message: String, val httpStatus: Int) : Exception(message)
