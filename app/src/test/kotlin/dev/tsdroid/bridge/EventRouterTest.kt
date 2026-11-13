package dev.tsdroid.bridge

import dev.tslib.Event
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class EventRouterTest {
    @Test fun audioCannotEvictChatAndMessagesAreNotReplayed() = runBlocking {
        val router = EventRouter()
        val slowAudio = launch(start = CoroutineStart.UNDISPATCHED) {
            router.audioEvents.collect { delay(20) }
        }
        repeat(4_000) { router.route(Event("audio_received", emptyMap())) }
        router.route(Event("text_message", mapOf("message" to "first")))
        repeat(4_000) { router.route(Event("audio_received", emptyMap())) }
        router.route(Event("text_message", mapOf("message" to "second")))
        assertEquals("first", withTimeout(1_000) { router.messages.first() }.data["message"])
        assertEquals("second", withTimeout(1_000) { router.messages.first() }.data["message"])
        assertNull(withTimeoutOrNull(50) { router.messages.first() })
        slowAudio.cancelAndJoin()
    }

    @Test fun boundedChatQueueKeepsTheMostRecentMessages() = runBlocking {
        val router = EventRouter()
        repeat(1_026) { router.route(Event("text_message", mapOf("message" to it))) }
        assertEquals(2, withTimeout(1_000) { router.messages.first() }.data["message"])
        assertEquals(3, withTimeout(1_000) { router.messages.first() }.data["message"])
    }

    @Test fun oldServerQueueCanBeCleared() = runBlocking {
        val router = EventRouter()
        router.route(Event("text_message", mapOf("message" to "old")))
        router.clearMessages()
        assertNull(withTimeoutOrNull(50) { router.messages.first() })
    }

    @Test fun onlyExplicitNicknameCollisionsAreRetried() {
        assertTrue(IllegalStateException("nickname already in use").isNicknameCollisionFailure())
        assertTrue(IllegalStateException("outer", IllegalStateException("nickname is taken")).isNicknameCollisionFailure())
        assertFalse(IllegalStateException("network timeout").isNicknameCollisionFailure())
        assertEquals(3, MAX_NICKNAME_COLLISION_ATTEMPTS)
    }
}
