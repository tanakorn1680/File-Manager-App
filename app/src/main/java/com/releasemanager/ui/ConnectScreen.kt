package com.releasemanager.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.releasemanager.R
import com.releasemanager.network.GitHubApi
import com.releasemanager.service.RunWatcher

/**
 * App-only screen: the web keeps its GitHub token on the server, the app asks for the user's own
 * Personal Access Token once and keeps it encrypted on the device (TokenStore).
 */
class ConnectScreen(private val ctx: AppContext) {

    private val root: View = ctx.activity.findViewById(R.id.screenConnect)
    private val inputToken: TextInputEditText = root.findViewById(R.id.inputToken)
    private val textStatus: TextView = root.findViewById(R.id.textConnectStatus)
    private val btnConnect: MaterialButton = root.findViewById(R.id.btnConnect)

    private var connecting = false

    init {
        btnConnect.setOnClickListener { connect() }
        inputToken.imeOptions = EditorInfo.IME_ACTION_DONE
        inputToken.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                connect()
                true
            } else {
                false
            }
        }
    }

    /** On launch: connect straight away with the saved token, if there is one. */
    fun autoConnect() {
        val saved = ctx.tokenStore.getToken()
        if (saved.isNullOrBlank()) return
        inputToken.setText(saved)
        connect()
    }

    /** After Disconnect: empty field, no stale status line. */
    fun reset() {
        inputToken.setText("")
        textStatus.visibility = View.GONE
        setConnecting(false)
    }

    private fun connect() {
        if (connecting) return
        val token = inputToken.text?.toString()?.trim().orEmpty()
        if (token.isEmpty()) {
            showStatus("Enter a token first", isError = true)
            return
        }

        root.hideKeyboard()
        setConnecting(true)
        val client = GitHubApi(token)
        // A commit pushed by this app that triggers GitHub Actions gets a "build succeeded / failed" notification
        client.onCommitted = { repo, sha -> RunWatcher.afterCommit(ctx.activity, client, repo, sha) }
        ctx.launchSafe(onError = { showStatus("Connection failed: ${it.userMessage()}", isError = true) }) {
            try {
                val user = client.verifyTokenAndGetUser()
                ctx.api = client
                ctx.tokenStore.saveToken(token)
                showStatus("Connected: ${user.login}", isError = false)
                ctx.repos.enter()
            } finally {
                setConnecting(false)
            }
        }
    }

    private fun setConnecting(value: Boolean) {
        connecting = value
        btnConnect.isEnabled = !value
        btnConnect.text = I18n.tr(if (value) "Connecting…" else "Connect")
    }

    private fun showStatus(message: String, isError: Boolean) {
        textStatus.text = message
        textStatus.visibility = View.VISIBLE
        textStatus.setTextColor(ctx.activity.color(if (isError) R.color.danger else R.color.success))
    }
}
