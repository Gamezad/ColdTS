package dev.tsdroid.bridge

import java.security.MessageDigest

/** Keep cached files separate for different server addresses, including ports. */
internal fun fileCacheServerKey(address: String): String {
    val readable = address.take(64).replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "server" }
    return "$readable-${digestPrefix(address)}"
}

/** A path from a chat message must not escape the app's cache directory. */
internal fun fileCacheRelativePath(channelId: Long, remotePath: String): String {
    val rawName = remotePath.substringAfterLast('/').take(120)
    val safeName = rawName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
    return "$channelId/${digestPrefix(remotePath)}/$safeName"
}

private fun digestPrefix(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extract a TS file URL from plain text or BBCode without truncating an IPv6 host. */
internal fun extractFileLink(text: String): String? {
    val scheme = "ts3file://"
    val start = text.indexOf(scheme, ignoreCase = true)
    if (start < 0) return null
    val candidate = text.substring(start).takeWhile { !it.isWhitespace() }
    val searchFrom = if (candidate.getOrNull(scheme.length) == '[') {
        val end = candidate.indexOf(']', scheme.length + 1)
        if (end < 0) return null
        end + 1
    } else scheme.length
    val markup = candidate.indexOf('[', searchFrom)
    return if (markup < 0) candidate else candidate.substring(0, markup)
}

internal data class FileServerEndpoint(val host: String, val port: Int) {
    val authority: String get() = if (':' in host) "[$host]" else host
}

internal fun fileServerEndpoint(address: String): FileServerEndpoint? {
    val value = address.trim()
    val (host, portText) = when {
        value.startsWith('[') -> {
            val end = value.indexOf(']')
            if (end < 0) return null
            val suffix = value.substring(end + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
            value.substring(1, end) to suffix.removePrefix(":").ifEmpty { "9987" }
        }
        value.count { it == ':' } > 1 -> value to "9987" // unbracketed IPv6 without a port
        else -> value.substringBefore(':') to value.substringAfter(':', "9987")
    }
    if (host.trimEnd('.').isBlank() || host.any { it.isWhitespace() || it in "/?#[]" }) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return FileServerEndpoint(host, port)
}

/** Never use a link to another server as a request against the current one. */
internal fun isSameFileServer(address: String, linkHost: String?, linkPort: String?): Boolean {
    val endpoint = fileServerEndpoint(address) ?: return false
    val otherPort = (linkPort ?: "9987").toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
    val otherHost = linkHost?.removePrefix("[")?.removeSuffix("]") ?: return false
    if (endpoint.port != otherPort) return false
    // A trailing dot and case do not change a DNS name. Compare numeric IPv6
    // spellings too, but never resolve untrusted DNS aliases on the UI thread.
    val left = endpoint.host.trimEnd('.')
    val right = otherHost.trimEnd('.')
    if (left.equals(right, ignoreCase = true)) return true
    val numericV6 = Regex("[0-9a-fA-F:.]+")
    if (':' !in left || ':' !in right || !numericV6.matches(left) || !numericV6.matches(right)) return false
    return try {
        java.net.InetAddress.getByName(left).address.contentEquals(java.net.InetAddress.getByName(right).address)
    } catch (_: Exception) { false }
}
