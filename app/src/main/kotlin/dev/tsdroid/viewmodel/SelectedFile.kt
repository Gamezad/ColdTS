package dev.tsdroid.viewmodel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Where the user was when the system file picker was opened. */
internal data class UploadOrigin(val session: String, val channelId: Long, val folder: String) {
    fun matches(currentSession: String?, currentChannelId: Long?, connected: Boolean): Boolean =
        connected && session == currentSession && channelId == currentChannelId
}

internal class SelectedFileTooLargeException : IOException("Selected file is too large")

/** Read no more than the configured limit, even if a provider reports a wrong size. */
internal fun readSelectedFile(input: InputStream, limit: Int, checkCancelled: () -> Unit = {}): ByteArray {
    require(limit >= 0 && limit < Int.MAX_VALUE)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        checkCancelled()
        val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) return output.toByteArray()
            if (total == limit) throw SelectedFileTooLargeException()
            output.write(byte)
            total++
        } else {
            if (count > limit - total) throw SelectedFileTooLargeException()
            output.write(buffer, 0, count)
            total += count
        }
    }
}

/** A provider's display name must not change the remote folder. */
internal fun safeUploadName(displayName: String): String =
    displayName.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.code < 0x20 || it.code == 0x7f }
        .take(240).trim()
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
