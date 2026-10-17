package com.releasemanager.ui

/** Picture formats the in-app viewer can show, told apart by file extension. */
object ImageTypes {

    /** Formats the built-in web view draws itself, with their MIME type. */
    private val WEB = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg",
        "png" to "image/png", "apng" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon", "cur" to "image/x-icon",
        "avif" to "image/avif"
    )

    /** Formats Android's own image decoder reads; the web view cannot, so they are converted before showing. */
    private val DECODER = setOf("heic", "heif")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String): Boolean = ext(name).let { it in WEB || it in DECODER }

    fun webMime(name: String): String? = WEB[ext(name)]

    fun needsDecoder(name: String): Boolean = ext(name) in DECODER
}
