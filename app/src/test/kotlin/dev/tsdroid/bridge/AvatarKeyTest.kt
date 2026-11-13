package dev.tsdroid.bridge

import org.junit.Assert.*
import org.junit.Test

class AvatarKeyTest {
    @Test fun cacheSeparatesServersUsersAndAvatarVersions() {
        val key = AvatarKey("server:9987", "user/=", "hash-one")
        assertEquals(key.fileName(), key.copy().fileName())
        assertNotEquals(key.fileName(), key.copy(server = "other:9987").fileName())
        assertNotEquals(key.fileName(), key.copy(uid = "other/=").fileName())
        assertNotEquals(key.fileName(), key.copy(version = "hash-two").fileName())
        assertTrue(key.fileName().matches(Regex("[0-9a-f]{64}")))
    }
}
