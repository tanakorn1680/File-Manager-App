package com.releasemanager.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.releasemanager.R

/**
 * Web: toast(msg, type, duration). An in-layout view rather than a system Toast so it can be
 * coloured per type; a new message replaces the current one and restarts the timer.
 */
class AppToast(private val view: TextView) {

    enum class Type { DEFAULT, SUCCESS, ERROR }

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { fadeOut() }

    fun show(message: String, type: Type = Type.DEFAULT, durationMs: Long = 3000) {
        handler.removeCallbacks(hideRunnable)
        view.animate().cancel()
        view.text = I18n.tr(message)
        view.setBackgroundResource(
            when (type) {
                Type.SUCCESS -> R.drawable.bg_toast_success
                Type.ERROR -> R.drawable.bg_toast_error
                Type.DEFAULT -> R.drawable.bg_toast
            }
        )
        if (view.visibility != View.VISIBLE) view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideRunnable, durationMs)
    }

    fun success(message: String, durationMs: Long = 3000) = show(message, Type.SUCCESS, durationMs)

    fun error(message: String, durationMs: Long = 3000) = show(message, Type.ERROR, durationMs)

    private fun fadeOut() {
        view.animate().alpha(0f).setDuration(200).withEndAction {
            view.visibility = View.GONE
        }.start()
    }
}
