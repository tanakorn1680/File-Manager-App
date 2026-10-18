package com.releasemanager.ui

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Collects a download in memory without the extra copy [ByteArrayOutputStream.toByteArray] makes,
 * and refuses to grow past [limit] bytes.
 */
class MemorySink(initial: Int, private val limit: Int) : ByteArrayOutputStream(initial) {

    class TooLarge : IOException("too large")

    @Synchronized
    override fun write(b: Int) {
        if (count + 1 > limit) throw TooLarge()
        super.write(b)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (count + len > limit) throw TooLarge()
        super.write(b, off, len)
    }

    /** The backing array; only the first [length] bytes are the download. */
    fun bytes(): ByteArray = buf

    fun length(): Int = count
}
