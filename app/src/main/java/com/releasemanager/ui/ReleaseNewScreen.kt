package com.releasemanager.ui

import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import com.releasemanager.R

/** Web: #screen-release-new (in the old app this was a dialog). */
class ReleaseNewScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenReleaseNew)
    private val inputTag: EditText = root.findViewById(R.id.inputTag)
    private val inputName: EditText = root.findViewById(R.id.inputName)
    private val inputBody: EditText = root.findViewById(R.id.inputBody)
    private val checkDraft: CheckBox = root.findViewById(R.id.checkDraft)
    private val checkPrerelease: CheckBox = root.findViewById(R.id.checkPrerelease)
    private val btnCreate: MaterialButton = root.findViewById(R.id.btnCreateRelease)

    init {
        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { ctx.show(Screen.RELEASES) }
        btnCreate.setOnClickListener { create() }
    }

    /** Web: #btn-new-release — opens with an empty form. */
    fun open() {
        inputTag.setText("")
        inputName.setText("")
        inputBody.setText("")
        checkDraft.isChecked = false
        checkPrerelease.isChecked = false
        ctx.show(Screen.RELEASE_NEW)
    }

    private fun create() {
        val tagName = inputTag.text.toString().trim()
        if (tagName.isEmpty()) {
            ctx.toast.error("Tag is required")
            return
        }
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        val name = inputName.text.toString().trim()
        val body = inputBody.text.toString()
        val draft = checkDraft.isChecked
        val prerelease = checkPrerelease.isChecked

        root.hideKeyboard()
        btnCreate.isEnabled = false
        ctx.launchSafe {
            try {
                api.createRelease(repo, tagName, name, body, draft, prerelease)
                ctx.toast.success("Release created")
                ctx.show(Screen.RELEASES)
                ctx.releases.load()
            } finally {
                btnCreate.isEnabled = true
            }
        }
    }
}
