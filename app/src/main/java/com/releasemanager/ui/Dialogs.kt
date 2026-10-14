package com.releasemanager.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.releasemanager.R

/**
 * Web overlays: `.overlay.center > .dialog` (centred card, max 400px, 16px side margin) and
 * `.overlay > .sheet` (bottom sheet with 20px top corners). Dismissing by tapping outside or Back
 * is the web's "click the backdrop" behaviour.
 */
object Dialogs {

    enum class ButtonStyle { PRIMARY, DANGER, OUTLINE }

    /** One `.sheet-item` row. [iconBoxBgRes] gives the 40dp tinted tile the edit-mode sheet uses. */
    class SheetItem(
        val label: String,
        val iconRes: Int,
        val onClick: () -> Unit,
        val danger: Boolean = false,
        val sub: String? = null,
        val iconBoxBgRes: Int? = null,
        val iconTintRes: Int? = null,
        val separatorBefore: Boolean = false
    )

    /** Inflates [layoutRes] into a centred dialog; the caller wires the views and calls show(). */
    fun create(activity: Activity, layoutRes: Int): Pair<Dialog, View> {
        val dialog = Dialog(activity, R.style.Theme_App_Dialog)
        val view = LayoutInflater.from(dialog.context).inflate(layoutRes, null)
        I18n.translateTree(view)
        // Explicit params: with a null parent the layout's own match_parent height would be lost
        dialog.setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val width = minOf(activity.resources.displayMetrics.widthPixels - activity.dp(32), activity.dp(400))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog to view
    }

    fun applyStyle(button: MaterialButton, style: ButtonStyle) {
        val ctx = button.context
        when (style) {
            ButtonStyle.PRIMARY -> Unit
            ButtonStyle.DANGER ->
                button.backgroundTintList = ColorStateList.valueOf(ctx.color(R.color.danger))
            ButtonStyle.OUTLINE -> {
                button.backgroundTintList = ColorStateList.valueOf(ctx.color(R.color.transparent))
                button.strokeColor = ColorStateList.valueOf(ctx.color(R.color.border))
                button.strokeWidth = ctx.dp(1)
                button.setTextColor(ctx.color(R.color.text_primary))
            }
        }
    }

    /**
     * Web: appConfirm() and the fixed two-button dialogs.
     * [onNegative] null → the left button just cancels; set → it dismisses and runs (e.g. "Discard").
     * [onCancel] runs when the dialog is cancelled (left button without [onNegative], tap outside, Back).
     */
    fun confirm(
        activity: Activity,
        title: String,
        body: CharSequence,
        positive: String = "Confirm",
        negative: String = "Cancel",
        positiveStyle: ButtonStyle = ButtonStyle.DANGER,
        cancelable: Boolean = true,
        onNegative: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ) {
        val (dialog, view) = create(activity, R.layout.dialog_confirm)
        view.findViewById<TextView>(R.id.dialogTitle).text = I18n.tr(title)
        view.findViewById<TextView>(R.id.dialogBody).text = I18n.tr(body)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnNegative)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnPositive)
        btnNegative.text = I18n.tr(negative)
        btnPositive.text = I18n.tr(positive)
        applyStyle(btnPositive, positiveStyle)

        btnNegative.setOnClickListener {
            if (onNegative != null) {
                dialog.dismiss()
                onNegative()
            } else {
                dialog.cancel()
            }
        }
        btnPositive.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.setCancelable(cancelable)
        dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.show()
    }

    /**
     * Web: the single-field dialogs (rename, new file, new folder, commit message). The text is
     * handed back untrimmed; with [allowEmpty] false an empty field keeps the dialog open, as the
     * web's `if (!value) return;` does.
     */
    fun prompt(
        activity: Activity,
        title: String,
        label: String,
        initial: String = "",
        hint: String? = null,
        positive: String = "OK",
        allowEmpty: Boolean = false,
        onSubmit: (String) -> Unit
    ) {
        val (dialog, view) = create(activity, R.layout.dialog_prompt)
        view.findViewById<TextView>(R.id.dialogTitle).text = I18n.tr(title)
        view.findViewById<TextView>(R.id.dialogLabel).text = I18n.tr(label)
        val input = view.findViewById<EditText>(R.id.dialogInput)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnNegative)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnPositive)
        btnPositive.text = I18n.tr(positive)
        input.setText(initial)
        input.setSelection(input.text.length)
        if (hint != null) input.hint = I18n.tr(hint)

        fun submit() {
            val text = input.text.toString()
            if (!allowEmpty && text.isBlank()) return
            dialog.dismiss()
            onSubmit(text)
        }

        btnPositive.setOnClickListener { submit() }
        btnNegative.setOnClickListener { dialog.cancel() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        input.requestFocus()
    }

    /** Web: `.sheet` — handle, title, rows. Tapping a row closes the sheet, then runs its action. */
    fun sheet(activity: Activity, title: String, items: List<SheetItem>): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity, R.style.Theme_App_BottomSheetDialog)
        val inflater = LayoutInflater.from(dialog.context)
        val view = inflater.inflate(R.layout.sheet_actions, null)
        view.findViewById<TextView>(R.id.sheetTitle).text = I18n.tr(title)
        val container = view.findViewById<LinearLayout>(R.id.sheetItems)

        for (item in items) {
            if (item.separatorBefore) container.addView(separator(activity))

            val row = inflater.inflate(R.layout.item_sheet, container, false)
            val label = row.findViewById<TextView>(R.id.sheetLabel)
            val sub = row.findViewById<TextView>(R.id.sheetSub)
            val icon = row.findViewById<ImageView>(R.id.sheetIcon)
            val box = row.findViewById<FrameLayout>(R.id.sheetIconBox)

            label.text = I18n.tr(item.label)
            icon.setImageResource(item.iconRes)
            if (item.sub != null) {
                sub.text = I18n.tr(item.sub)
                sub.visibility = View.VISIBLE
            }
            if (item.danger) {
                label.setTextColor(activity.color(R.color.danger))
                icon.imageTintList = ColorStateList.valueOf(activity.color(R.color.danger))
            }
            if (item.iconTintRes != null) {
                icon.imageTintList = ColorStateList.valueOf(activity.color(item.iconTintRes))
            }
            if (item.iconBoxBgRes != null) {
                box.setBackgroundResource(item.iconBoxBgRes)
                box.layoutParams = box.layoutParams.apply {
                    width = activity.dp(40)
                    height = activity.dp(40)
                }
            }
            row.setOnClickListener {
                dialog.dismiss()
                item.onClick()
            }
            container.addView(row)
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            // Expanded straight away so the sheet is never stuck half-open in landscape
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        return dialog
    }

    /** Web: `.sheet-sep` — 1px line, 4px vertical margin. */
    private fun separator(activity: Activity): View = View(activity).apply {
        setBackgroundColor(activity.color(R.color.border))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)).apply {
            topMargin = activity.dp(4)
            bottomMargin = activity.dp(4)
        }
    }
}
