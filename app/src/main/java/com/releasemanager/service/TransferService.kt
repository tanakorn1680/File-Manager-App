package com.releasemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.releasemanager.R
import com.releasemanager.ui.MainActivity
import com.releasemanager.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs uploads and downloads in the background (same idea as bankconfirm's KeepAliveService):
 *  - a foreground service, so closing or swiping away the app does not stop the transfer;
 *  - a PARTIAL_WAKE_LOCK while something is running, so the CPU keeps going with the screen off;
 *  - one notification per transfer with a progress bar and a Cancel button; when it ends the same
 *    notification turns into "complete" / "failed" and stays until it is dismissed.
 * One of the running transfers owns the foreground notification; when it ends another one takes over.
 */
class TransferService : Service(), TransferManager.Listener {

    companion object {
        private const val TAG = "TransferService"
        private const val CHANNEL_PROGRESS = "transfer_progress"
        private const val CHANNEL_DONE = "transfer_done"
        private const val NOTIF_BASE = 4000
        private const val NO_ID = -1
        private const val WAKE_TAG = "com.releasemanager:transfer"
        private const val WAKE_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        const val ACTION_CANCEL = "com.releasemanager.action.CANCEL_TRANSFER"
        const val EXTRA_ID = "transfer_id"

        private fun notifId(t: Transfer) = NOTIF_BASE + t.id
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var fgId = NO_ID

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannels()
        TransferManager.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferManager.cancel(intent.getIntExtra(EXTRA_ID, -1))
            if (TransferManager.running().isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        val pending = TransferManager.takePending()
        val running = TransferManager.running()

        // startForegroundService() requires startForeground() within a few seconds, whatever happens next
        if (fgId == NO_ID) {
            val first = running.firstOrNull()
            if (first != null) {
                promote(first)
            } else {
                ServiceCompat.startForeground(this, NOTIF_BASE, placeholder(), foregroundType())
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        acquireWakeLock()
        pending.forEach { launchTransfer(it) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TransferManager.removeListener(this)
        // Only reached with work left when the system kills the service: drop the stale notifications
        TransferManager.running().forEach { nm.cancel(notifId(it)) }
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Running transfers ────────────────────────────────────────────────────

    private fun launchTransfer(t: Transfer) {
        t.job = scope.launch {
            try {
                val outcome = t.block(TransferProgress(t))
                t.message = outcome.message
                t.result = outcome.result
                t.state = TransferState.SUCCESS
            } catch (e: CancellationException) {
                t.message = "Cancelled"
                t.state = TransferState.CANCELLED
            } catch (e: Throwable) {
                Log.w(TAG, "transfer failed", e)
                t.message = if (e is OutOfMemoryError) "Not enough memory on this device." else e.userMessage()
                t.state = TransferState.FAILED
            }
            TransferManager.changed(t, force = true)
        }
    }

    /** Main thread: a progress step, or the end of a transfer. */
    override fun onTransferChanged(t: Transfer) {
        if (t.isRunning) {
            nm.notify(notifId(t), progressNotification(t))
        } else {
            finish(t)
        }
    }

    private fun finish(t: Transfer) {
        val id = notifId(t)
        val others = TransferManager.running().filter { it.id != t.id }
        if (id == fgId) {
            val next = others.firstOrNull()
            if (next != null) {
                promote(next)
            } else {
                // Keep the notification (it becomes the result) but stop being a foreground service
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                fgId = NO_ID
            }
        }
        when (t.state) {
            TransferState.SUCCESS, TransferState.FAILED -> nm.notify(id, resultNotification(t))
            else -> nm.cancel(id)
        }
        if (others.isEmpty()) {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun promote(t: Transfer) {
        fgId = notifId(t)
        ServiceCompat.startForeground(this, fgId, progressNotification(t), foregroundType())
    }

    private fun foregroundType(): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    // ── Wake lock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock release: ${e.message}")
        }
        wakeLock = null
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun verb(t: Transfer) = if (t.kind == TransferKind.UPLOAD) "Uploading" else "Downloading"

    /** The app logo (white shape, as status-bar icons must be) for every notification; the accent colour tints it in the shade. */
    private fun accent() = ContextCompat.getColor(this, R.color.accent)

    private fun progressNotification(t: Transfer): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(accent())
            .setContentTitle("${verb(t)} ${t.title}")
            .setContentText(t.label.ifBlank { "Starting…" })
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Cancel", cancelIntent(t))
        if (t.fraction >= 0f) {
            val pct = (t.fraction * 100).toInt().coerceIn(0, 100)
            b.setProgress(100, pct, false).setSubText("$pct%")
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun resultNotification(t: Transfer): Notification {
        val ok = t.state == TransferState.SUCCESS
        val noun = if (t.kind == TransferKind.UPLOAD) "Upload" else "Download"
        return NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(accent())
            .setContentTitle(if (ok) "$noun complete" else "$noun failed")
            .setContentText(if (ok) t.message ?: t.title else "${t.title}: ${t.message ?: "error"}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (ok) t.message ?: t.title else "${t.title}: ${t.message ?: "error"}"))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setCategory(if (ok) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun placeholder(): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(getString(R.string.app_name))
            .setSilent(true)
            .build()

    private fun openApp(): PendingIntent {
        // The launcher intent brings the existing task forward instead of stacking a second copy
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelIntent(t: Transfer): PendingIntent {
        val intent = Intent(this, TransferService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_ID, t.id)
        return PendingIntent.getService(this, t.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels() {
        if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Uploads & downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of uploads and downloads running in the background"
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Transfer results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shown when an upload or download finishes or fails"
                }
            )
        }
    }
}
