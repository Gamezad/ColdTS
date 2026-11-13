package dev.tsdroid.bridge

import dev.tslib.User
import org.junit.Assert.*
import org.junit.Test

class PeerIdentityTest {
    private fun user(id: Int, uid: String?, databaseId: Long, name: String) = User(
        id, uid, databaseId, 1, name, 0, false, false, false, true, true,
        false, false, false, false, false, 0, null, longArrayOf(), 1,
        "Android", "1", null, null, null, 0,
    )

    @Test fun stableIdentitiesSurviveReconnectAndClientIdReuse() {
        val alice = user(7, "alice-uid", 40, "Alice")
        val reconnected = user(22, "alice-uid", 40, "Alice")
        val replacement = user(7, "other-uid", 41, "Other")
        assertEquals(peerKey(alice, "old"), peerKey(reconnected, "new"))
        assertNotEquals(peerKey(alice, "old"), peerKey(replacement, "old"))
        assertTrue(matchesRecipient(alice, "alice-uid", 40, "Alice"))
        assertFalse(matchesRecipient(replacement, "alice-uid", 40, "Alice"))
    }

    @Test fun databaseAndSessionFallbacksRemainDistinct() {
        assertEquals("db:40", peerKey(user(7, "", 40, "Alice"), "one"))
        assertTrue(matchesRecipient(user(7, "", 40, "Alice"), null, 40, "Alice"))
        assertFalse(matchesRecipient(user(7, "", 41, "Alice"), null, 40, "Alice"))
        val unknown = user(7, "", 0, "Alice")
        assertNotEquals(peerKey(unknown, "one"), peerKey(unknown, "two"))
        assertFalse(matchesRecipient(unknown, null, null, null))
    }

    @Test fun departedSenderIsNotMatchedToAReusedClientId() {
        val replacement = user(7, "bob", 41, "Bob")
        val key = incomingPeerKey(7, "Alice", listOf(replacement), "session")
        assertTrue(key.startsWith("unresolved:"))
        assertNotEquals(peerKey(replacement, "session"), key)
        assertEquals("uid:bob", incomingPeerKey(7, "Bob", listOf(replacement), "session"))
    }
}
