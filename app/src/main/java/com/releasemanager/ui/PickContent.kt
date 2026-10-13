package com.releasemanager.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * The "choose a file" screen with every source: the document providers (Drive, Downloads, Termux ...) AND
 * other apps that can hand out files (file managers, Photos, Music, Recorder ...). That is ACTION_GET_CONTENT —
 * the same picker as a web page or a chat app opens. (ACTION_OPEN_DOCUMENT lists the document providers only.)
 * The persistable flag asks providers that support it for a grant that outlives the app; for the rest the caller
 * copies the file (see UriAccess.copyToCache).
 */
class PickContent(private val multiple: Boolean) : ActivityResultContract<Array<String>, List<Uri>>() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
    }
}
