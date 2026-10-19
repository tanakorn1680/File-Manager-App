package com.releasemanager.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.releasemanager.util.formatSize
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** WATCH = following a GitHub Actions build until it ends (see [RunWatcher]). */
enum class TransferKind { UPLOAD, DOWNLOAD, WATCH }

enum class TransferState { RUNNING, SUCCESS, FAILED, CANCELLED }

/** What a finished transfer reports: the text for the "done" notification / toast, plus an optional payload. */
class TransferOutcome(val message: String, val result: Any? = null)

/**
 * One upload or download that runs inside [TransferService], so it keeps going when the app is closed.
 * The fields are written from the worker thread and read on the main thread.
 */
class Transfer internal constructor(
    val id: Int,
    val kind: TransferKind,
    /** What is being transferred ("app.apk", "12 files"); the notification title is built from it. */
    val title: String,
    /** Free-form key so a screen can find its own transfer again after it was re-created. */
    val tag: String?,
    internal val block: suspend (TransferProgress) -> TransferOutcome
) {
    @Volatile var state: TransferState = TransferState.RUNNING
        internal set

    /** 0..1, or -1 while the length is not known. */
    @Volatile var fraction: Float = -1f
        internal set

    @Volatile var label: String = ""
        internal set

    /** Index of the file being copied (-1 = none yet) and the text for its row. */
    @Volatile var itemIndex: Int = -1
        internal set

    @Volatile var itemText: String = ""
        internal set

    /**
     * Multi-step transfers (an upload: clone, copy, push): the step being worked on (-1 = none yet), how far
     * through it (0..1, -1 = not known) and the text shown beside it. Earlier steps are finished.
     */
    @Volatile var step: Int = -1
        internal set

    @Volatile var stepFraction: Float = -1f
        internal set

    @Volatile var stepStatus: String = ""
        internal set

    /** Result text once finished (success message, error message, or "Cancelled"). */
    @Volatile var message: String? = null
        internal set

    @Volatile var result: Any? = null
        internal set

    @Volatile internal var job: Job? = null
    @Volatile internal var started: Boolean = false
    @Volatile internal var lastEmit: Long = 0L

    /** Highest fraction reported so far: progress only ever moves forward. */
    internal var peak: Float = 0f

    /** The same, for the step being worked on. */
    internal var stepPeak: Float = 0f

    val isRunning: Boolean get() = state == TransferState.RUNNING
}

/** Handed to the transfer's block so it can report progress; every call is safe from any thread. */
class TransferProgress internal constructor(private val t: Transfer) {

    /** Forward-only: a lower value than one already reported is raised to it; -1 (unknown) is passed through. */
    private fun settle(fraction: Float): Float = synchronized(t) {
        if (fraction < 0f) -1f else fraction.coerceIn(0f, 1f).coerceAtLeast(t.peak).also { t.peak = it }
    }

    /** Sets the fraction (-1 = unknown) and optionally the text. */
    fun update(fraction: Float, label: String? = null) {
        t.fraction = settle(fraction)
        if (label != null) t.label = label
        TransferManager.changed(t, force = false)
    }

    /**
     * Reports a step of a multi-step transfer: [index] is the step being worked on, [fraction] how far through
     * that step it is (-1 = not known), [status] the text for its row, and [overall] where the whole transfer
     * sits on the single bar of the notification. Steps and their fractions only move forward.
     */
    fun step(index: Int, fraction: Float, status: String, overall: Float, label: String? = null) {
        var stepChanged = false
        synchronized(t) {
            if (index < t.step) return
            if (index != t.step) {
                t.step = index
                t.stepPeak = 0f
                stepChanged = true
            }
            t.stepFraction = if (fraction < 0f) -1f else fraction.coerceIn(0f, 1f).coerceAtLeast(t.stepPeak).also { t.stepPeak = it }
            t.stepStatus = status
        }
        t.fraction = settle(overall)
        if (label != null) t.label = label
        // A new step is always passed on at once, so a short one is not skipped by the rate limit
        TransferManager.changed(t, force = stepChanged)
    }

    fun label(text: String) {
        t.label = text
        TransferManager.changed(t, force = false)
    }

    /** Row in the upload list the screen should mark as "working". */
    fun item(index: Int, text: String) {
        t.itemIndex = index
        t.itemText = text
        TransferManager.changed(t, force = false)
    }

    /** Byte progress of the current stream: [prefix] + "12.3 MB / 40.0 MB". */
    fun bytes(prefix: String, done: Long, total: Long, base: Float = 0f, span: Float = 1f) {
        if (!TransferManager.due(t)) return
        t.fraction = if (total > 0) settle(base + span * (done.toDouble() / total).toFloat()) else -1f
        t.label = if (total > 0) "$prefix ${formatSize(done)} / ${formatSize(total)}" else "$prefix ${formatSize(done)}"
        TransferManager.changed(t, force = true)
    }
}

/**
 * The single place screens go to start / cancel / watch background transfers.
 * Call [start] on the main thread (it always is: it comes from a click or a picker result).
 */
object TransferManager {

    fun interface Listener {
        /** Called on the main thread for every progress step and once more when the transfer ends. */
        fun onTransferChanged(t: Transfer)
    }

    private const val MIN_EMIT_GAP_MS = 400L

    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger(0)
    private val transfers = ConcurrentHashMap<Int, Transfer>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }

    fun removeListener(l: Listener) { listeners.remove(l) }

    /**
     * Queues [block] to run in [TransferService] and returns right away. The block must not capture an
     * Activity or a View (the app may be closed while it runs); it may throw to report a failure.
     */
    fun start(
        context: Context,
        kind: TransferKind,
        title: String,
        tag: String? = null,
        block: suspend (TransferProgress) -> TransferOutcome
    ): Transfer {
        val app = context.applicationContext
        val t = Transfer(ids.incrementAndGet(), kind, title, tag, block)
        transfers[t.id] = t
        try {
            ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java))
        } catch (e: Exception) {
            // e.g. the system refused to start a foreground service
            t.message = e.message ?: "Could not start the background service"
            t.state = TransferState.FAILED
        }
        changed(t, force = true)
        return t
    }

    fun cancel(id: Int) {
        transfers[id]?.job?.cancel()
    }

    fun running(): List<Transfer> = transfers.values.filter { it.isRunning }.sortedBy { it.id }

    /** The running transfer carrying [tag], if any. */
    fun find(tag: String): Transfer? = running().firstOrNull { it.tag == tag }

    /** Transfers the service has not picked up yet; marks them as taken. */
    internal fun takePending(): List<Transfer> =
        transfers.values.filter { it.isRunning && !it.started }.sortedBy { it.id }.onEach { it.started = true }

    internal fun due(t: Transfer): Boolean = SystemClock.elapsedRealtime() - t.lastEmit >= MIN_EMIT_GAP_MS

    /** Tells listeners (and so the notification) that [t] changed; progress updates are rate-limited. */
    internal fun changed(t: Transfer, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val terminal = !t.isRunning
        if (!force && !terminal && now - t.lastEmit < MIN_EMIT_GAP_MS) return
        t.lastEmit = now
        main.post { dispatch(t) }
    }

    private fun dispatch(t: Transfer) {
        for (l in listeners) l.onTransferChanged(t)
        if (!t.isRunning) transfers.remove(t.id)
    }
}
