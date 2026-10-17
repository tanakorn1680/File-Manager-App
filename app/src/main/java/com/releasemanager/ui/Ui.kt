package com.releasemanager.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.releasemanager.R

/** dp → px, rounded (used where a size cannot be expressed in XML). */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)

fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
}

/** MIME type guessed from the file extension, for the "save as" picker. */
fun mimeTypeFor(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

/** Runs [block] after every edit of the field (web: the `input` event). */
fun EditText.onTextChanged(block: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = block()
    })
}

/** Message to show for a failed call; some IOExceptions carry no message at all. */
fun Throwable.userMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/** Last path segment of "owner/repo" (web: `name.split('/')[1] || name`). */
fun String.repoShortName(): String = substringAfter('/', this)

/**
 * Pull-to-refresh for a list screen. The layout wraps the list together with its loading/empty overlay,
 * so "can the content still scroll up" has to be asked of [list] itself.
 */
fun SwipeRefreshLayout.setup(list: RecyclerView, onRefresh: () -> Unit) {
    setColorSchemeColors(context.color(R.color.accent))
    setProgressBackgroundColorSchemeColor(context.color(R.color.surface))
    setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
    setOnRefreshListener { onRefresh() }
}
