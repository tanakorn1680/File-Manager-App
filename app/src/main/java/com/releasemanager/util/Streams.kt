package com.releasemanager.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies [input] into [output] in 64 KB pieces and checks for cancellation between pieces, so a transfer
 * that the user cancelled from the notification stops within one chunk instead of running to the end.
 * [onProgress] gets (bytes copied so far, [total]); [total] is -1 when the length is not known.
 * Neither stream is closed.
 */
suspend fun copyStream(
    input: InputStream,
    output: OutputStream,
    total: Long = -1L,
    onProgress: ((done: Long, total: Long) -> Unit)? = null
): Long {
    val job: Job? = currentCoroutineContext()[Job]
    val buffer = ByteArray(64 * 1024)
    var done = 0L
    while (true) {
        job?.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        done += read
        onProgress?.invoke(done, total)
    }
    return done
}
