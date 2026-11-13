package dev.tsdroid.bridge

internal const val MAX_NICKNAME_COLLISION_ATTEMPTS = 3

internal fun nicknameWithCollisionSuffix(nickname: String, attempt: Int): String {
    if (attempt <= 0) return nickname
    return buildString(nickname.length + attempt.toString().length) {
        append(nickname)
        append(attempt)
    }
}

internal fun Throwable.isNicknameCollisionFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message?.lowercase().orEmpty()
        if (
            "nickname" in message &&
            (
                "already" in message ||
                "in use" in message ||
                "taken" in message ||
                "duplicate" in message ||
                "exists" in message
            )
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
