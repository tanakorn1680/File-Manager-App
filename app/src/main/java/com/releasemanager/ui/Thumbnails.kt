package com.releasemanager.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Small previews for the picture rows of a list. A row asks for its picture when it is bound; the picture
 * is fetched into memory, shrunk, kept in a memory cache and drawn in the row. Nothing is written to the device.
 */
class Thumbnails(private val scope: CoroutineScope) {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Pictures that cannot be previewed (too large, undecodable); not asked for again. */
    private val noPreview: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val gate = Semaphore(PARALLEL)

    /**
     * Calls [onReady] with the preview of [key] when there is one. [fetch] streams the original picture;
     * [sizeBytes] is its size, used to skip pictures too big to be worth fetching for a thumbnail.
     * Returns the job to cancel when the row is reused (null when the answer came at once or there is none).
     */
    fun bind(key: String, sizeBytes: Long, fetch: suspend (OutputStream) -> Unit, onReady: (Bitmap) -> Unit): Job? {
        cache.get(key)?.let { onReady(it); return null }
        if (key in noPreview || sizeBytes !in 1..MAX_SOURCE_BYTES) return null
        return scope.launch {
            val bitmap = try {
                gate.withPermit { withContext(Dispatchers.IO) { fetchAndDecode(key, sizeBytes, fetch) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null   // network trouble: no preview now, but the row may ask again
            }
            if (bitmap != null) {
                cache.put(key, bitmap)
                onReady(bitmap)
            }
        }
    }

    private suspend fun fetchAndDecode(key: String, sizeBytes: Long, fetch: suspend (OutputStream) -> Unit): Bitmap? {
        val sink = MemorySink(sizeBytes.toInt(), MAX_SOURCE_BYTES.toInt())
        try {
            fetch(sink)
        } catch (e: MemorySink.TooLarge) {
            noPreview.add(key)
            return null
        }
        val bitmap = decode(sink.bytes(), sink.length())
        if (bitmap == null) noPreview.add(key)
        return bitmap
    }

    private fun decode(data: ByteArray, length: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, length, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, length, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val PARALLEL = 4

        /** Pictures bigger than this are not fetched for a thumbnail (a folder of photos would cost a lot of data). */
        private const val MAX_SOURCE_BYTES = 4L * 1024 * 1024

        /** Previews are decoded no smaller than this on their short side, so they stay sharp in a 44dp slot. */
        private const val TARGET_PX = 160

        private fun cacheBytes(): Int = minOf(Runtime.getRuntime().maxMemory() / 16, 32L * 1024 * 1024).toInt()

        /** The row's icon slot becomes a rounded picture, a little larger than the icon. */
        fun showThumbnail(view: ImageView, bitmap: Bitmap) {
            val c = view.context
            val side = c.dp(44)
            val radius = c.dp(8).toFloat()
            view.layoutParams = view.layoutParams.apply { width = side; height = side }
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            view.clipToOutline = true
            view.setImageBitmap(bitmap)
        }

        /** Puts the row back to its plain icon (rows are reused while scrolling). */
        fun showIcon(view: ImageView, iconRes: Int) {
            val c = view.context
            val side = c.dp(22)
            if (view.layoutParams.width != side || view.layoutParams.height != side) {
                view.layoutParams = view.layoutParams.apply { width = side; height = side }
            }
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.clipToOutline = false
            view.setImageResource(iconRes)
        }
    }
}
