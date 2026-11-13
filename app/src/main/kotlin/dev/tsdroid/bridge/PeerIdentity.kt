package dev.tsdroid.bridge

import dev.tslib.User

/** Message stores are per-server, so the server's database ID is a useful fallback. */
internal fun peerKey(user: User, sessionId: String?): String = when {
    !user.uid.isNullOrBlank() -> "uid:${user.uid}"
    user.databaseId > 0L -> "db:${user.databaseId}"
    else -> "session:$sessionId:id:${user.id}:name:${user.nickname}"
}

internal fun incomingPeerKey(senderId: Int, senderName: String, users: List<User>, sessionId: String?): String {
    val user = users.firstOrNull { it.id == senderId && it.nickname == senderName }
    // If the sender left before the snapshot, never route a reply by their old ID.
    return user?.let { peerKey(it, sessionId) }
        ?: "unresolved:$sessionId:id:$senderId:name:$senderName"
}

internal fun matchesRecipient(actual: User?, uid: String?, databaseId: Long?, nickname: String?): Boolean {
    if (actual == null) return false
    return when {
        !uid.isNullOrBlank() -> actual.uid == uid
        databaseId != null && databaseId > 0 -> actual.databaseId == databaseId
        !nickname.isNullOrBlank() -> actual.nickname == nickname
        else -> false
    }
}
