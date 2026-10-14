package com.releasemanager.ui

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.releasemanager.R

/**
 * Web: setLoading() / setError() / the `.state-msg` blocks. One overlay (layout_state.xml) drawn
 * over a list: spinner while loading, icon + message (+ Retry) for errors and empty lists.
 * `root` is the screen's root view, so each screen finds its own copy of the overlay.
 */
class StateView(root: View) {

    private val container: View = root.findViewById(R.id.stateRoot)
    private val spinner: ProgressBar = root.findViewById(R.id.stateSpinner)
    private val icon: ImageView = root.findViewById(R.id.stateIcon)
    private val text: TextView = root.findViewById(R.id.stateText)
    private val retry: MaterialButton = root.findViewById(R.id.stateRetry)

    init {
        // The overlay sits over the list, so it must not let touches fall through to rows under it
        container.isClickable = true
    }

    fun showLoading(message: String = "Loading…") {
        spinner.visibility = View.VISIBLE
        icon.visibility = View.GONE
        retry.visibility = View.GONE
        text.text = I18n.tr(message)
        container.visibility = View.VISIBLE
    }

    fun showError(message: String, onRetry: (() -> Unit)?) {
        spinner.visibility = View.GONE
        icon.setImageResource(R.drawable.ic_alert_circle)
        icon.visibility = View.VISIBLE
        text.text = I18n.tr(message)
        if (onRetry != null) {
            retry.visibility = View.VISIBLE
            retry.setOnClickListener { onRetry() }
        } else {
            retry.visibility = View.GONE
        }
        container.visibility = View.VISIBLE
    }

    fun showEmpty(message: String, iconRes: Int? = null) {
        spinner.visibility = View.GONE
        retry.visibility = View.GONE
        if (iconRes != null) {
            icon.setImageResource(iconRes)
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }
        text.text = I18n.tr(message)
        container.visibility = View.VISIBLE
    }

    fun hide() {
        container.visibility = View.GONE
    }
}
