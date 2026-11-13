package dev.tsdroid.bridge

import java.security.MessageDigest

/** Avatar paths use the UID, but their contents can differ per server and version. */
internal data class AvatarKey(val server: String, val uid: String, val version: String) {
    fun fileName(): String = MessageDigest.getInstance("SHA-256")
        .digest("$server\u0000$uid\u0000$version".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
