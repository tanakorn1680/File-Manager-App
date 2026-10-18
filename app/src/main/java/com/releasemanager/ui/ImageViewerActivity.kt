package com.releasemanager.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.releasemanager.R
import com.releasemanager.data.TokenStore
import com.releasemanager.network.GitHubApi
import com.releasemanager.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Shows a picture of a repository or of a release straight from GitHub. The bytes are fetched into memory
 * and handed to the web view from there: nothing is written to the device. The web view brings pinch zoom
 * and panning and draws jpg, png, gif, webp, bmp, svg, ico and avif; heic/heif are converted in memory first.
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var groupLoading: View
    private lateinit var textLoading: TextView
    private lateinit var textError: TextView

    // The picture as the web view will receive it
    private var imageBytes: ByteArray = ByteArray(0)
    private var imageLength = 0
    private var imageMime = "image/png"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        setContentView(R.layout.activity_image_viewer)
        I18n.translateTree(findViewById(android.R.id.content))

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        findViewById<TextView>(R.id.textTitle).text = name
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        webView = findViewById(R.id.webImage)
        groupLoading = findViewById(R.id.groupLoading)
        textLoading = findViewById(R.id.textLoading)
        textError = findViewById(R.id.textError)

        val token = TokenStore(this).getToken()
        if (token.isNullOrBlank()) { finish(); return }

        setUpWebView()
        load(GitHubApi(token), name)
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun setUpWebView() {
        webView.setBackgroundColor(color(R.color.bg))
        webView.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webView.webViewClient = object : WebViewClient() {
            // The picture comes from memory; any other web address the page might ask for is refused.
            // The page itself (the main frame, and the data: address it is loaded from) is left to the web view.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                return when {
                    request.isForMainFrame -> null
                    url.toString() == IMAGE_URL ->
                        WebResourceResponse(imageMime, null, 200, "OK", emptyMap(), ByteArrayInputStream(imageBytes, 0, imageLength))
                    url.scheme == "http" || url.scheme == "https" ->
                        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    else -> null
                }
            }
        }
    }

    private fun load(api: GitHubApi, name: String) {
        val size = intent.getLongExtra(EXTRA_SIZE, -1L)
        if (size > MAX_PREVIEW_BYTES) {
            showError(I18n.tr("This image is too large to preview. Use Download instead."))
            return
        }
        lifecycleScope.launch {
            try {
                val sink = MemorySink(if (size > 0) size.toInt() else 64 * 1024, MAX_PREVIEW_BYTES.toInt())
                val onProgress: (Long, Long) -> Unit = { done, total ->
                    runOnUiThread {
                        textLoading.text = if (total > 0) {
                            "${I18n.tr("Loading image…")} ${(done * 100 / total).toInt()}%"
                        } else {
                            "${I18n.tr("Loading image…")} ${formatSize(done)}"
                        }
                    }
                }
                when (intent.getStringExtra(EXTRA_KIND)) {
                    KIND_ASSET -> api.downloadReleaseAsset(
                        intent.getStringExtra(EXTRA_REPO).orEmpty(),
                        intent.getLongExtra(EXTRA_ASSET_ID, -1L),
                        sink, onProgress
                    )
                    else -> api.downloadFile(
                        intent.getStringExtra(EXTRA_REPO).orEmpty(),
                        intent.getStringExtra(EXTRA_PATH).orEmpty(),
                        intent.getStringExtra(EXTRA_BRANCH).orEmpty(),
                        sink, onProgress, sizeHint = size
                    )
                }

                val webMime = ImageTypes.webMime(name)
                if (webMime != null) {
                    imageBytes = sink.bytes()
                    imageLength = sink.length()
                    imageMime = webMime
                } else {
                    withContext(Dispatchers.Default) { convert(sink.bytes(), sink.length()) }
                }
                show()
            } catch (e: MemorySink.TooLarge) {
                showError(I18n.tr("This image is too large to preview. Use Download instead."))
            } catch (e: UnsupportedFormat) {
                showError(I18n.tr("This picture format cannot be shown on this Android version."))
            } catch (e: Exception) {
                showError("${I18n.tr("Could not open the image")}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** heic/heif: decoded by Android and re-encoded (in memory) as something the web view draws. */
    private fun convert(data: ByteArray, length: Int) {
        if (Build.VERSION.SDK_INT < 28) throw UnsupportedFormat()
        val out = decodeWithAndroid(data, length)
        imageBytes = out.first
        imageLength = out.first.size
        imageMime = out.second
    }

    @RequiresApi(28)
    private fun decodeWithAndroid(data: ByteArray, length: Int): Pair<ByteArray, String> {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(data, 0, length))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) decoder.setTargetSampleSize((longest + MAX_SIDE - 1) / MAX_SIDE)
        }
        val png = bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
        bitmap.recycle()
        return out.toByteArray() to if (png) "image/png" else "image/jpeg"
    }

    private fun show() {
        val bg = String.format("#%06X", 0xFFFFFF and color(R.color.bg))
        // A vector picture has no pixel size of its own, so it is stretched to the screen; pictures keep theirs
        val fit = if (imageMime == "image/svg+xml") {
            "width:100%;height:100%;object-fit:contain"
        } else {
            "max-width:100%;max-height:100%;object-fit:contain"
        }
        val html = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=0.5, maximum-scale=10, user-scalable=yes">
            <style>
            html,body{margin:0;height:100%;background:$bg}
            body{display:flex;align-items:center;justify-content:center}
            img{$fit}
            </style></head>
            <body><img src="$IMAGE_URL"></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://image.local/", html, "text/html", "utf-8", null)
        groupLoading.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        groupLoading.visibility = View.GONE
        textError.text = message
        textError.visibility = View.VISIBLE
    }

    private class UnsupportedFormat : IOException()

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_BRANCH = "branch"
        private const val EXTRA_ASSET_ID = "asset_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val KIND_FILE = "file"
        private const val KIND_ASSET = "asset"

        private const val IMAGE_URL = "https://image.local/image"

        /** Pictures above this are not previewed (they would have to fit in memory several times over). */
        private const val MAX_PREVIEW_BYTES = 40L * 1024 * 1024

        /** heic/heif pictures larger than this on their longest side are decoded at a smaller scale. */
        private const val MAX_SIDE = 4096

        fun openRepoFile(context: Context, repo: String, path: String, branch: String, name: String, size: Long) {
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java)
                    .putExtra(EXTRA_KIND, KIND_FILE)
                    .putExtra(EXTRA_REPO, repo)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_BRANCH, branch)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_SIZE, size)
            )
        }

        fun openReleaseAsset(context: Context, repo: String, assetId: Long, name: String, size: Long) {
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java)
                    .putExtra(EXTRA_KIND, KIND_ASSET)
                    .putExtra(EXTRA_REPO, repo)
                    .putExtra(EXTRA_ASSET_ID, assetId)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_SIZE, size)
            )
        }
    }
}
