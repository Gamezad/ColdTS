package dev.tsdroid.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ChatComposerTest {
    @Test fun failedSendKeepsDraftAndCanBeRetried() = runBlocking {
        val composer = ChatComposer(this)
        composer.edit("hello")
        val result = CompletableDeferred<ChatSendResult>()
        assertTrue(composer.submit { result.await() })
        assertEquals(ChatSendStatus.SENDING, composer.status.value)
        assertFalse(composer.submit { ChatSendResult.SENT })
        result.complete(ChatSendResult.FAILED)
        yield()
        assertEquals("hello", composer.draft.value)
        assertEquals(ChatSendStatus.FAILED, composer.status.value)
        assertTrue(composer.submit { ChatSendResult.SENT })
        yield()
        assertEquals("", composer.draft.value)
        assertEquals(ChatSendStatus.IDLE, composer.status.value)
    }

    @Test fun typingWhileSendingNeverErasesTheNewDraft() = runBlocking {
        val composer = ChatComposer(this)
        composer.edit("old")
        val result = CompletableDeferred<ChatSendResult>()
        composer.submit { result.await() }
        composer.edit("new")
        result.complete(ChatSendResult.SENT)
        yield()
        assertEquals("new", composer.draft.value)
        assertEquals(ChatSendStatus.IDLE, composer.status.value)
    }

    @Test fun editingBackToTheSameTextStillKeepsTheNewDraft() = runBlocking {
        val composer = ChatComposer(this)
        composer.edit("old")
        val result = CompletableDeferred<ChatSendResult>()
        composer.submit { result.await() }
        composer.edit("other")
        composer.edit("old")
        result.complete(ChatSendResult.SENT)
        yield()
        assertEquals("old", composer.draft.value)
    }

    @Test fun partialWhisperKeepsDraftAndShowsPartialStatus() = runBlocking {
        val composer = ChatComposer(this)
        composer.edit("hello")
        composer.submit { ChatSendResult.PARTIAL }
        yield()
        assertEquals("hello", composer.draft.value)
        assertEquals(ChatSendStatus.PARTIAL, composer.status.value)
        composer.edit("hello again")
        assertEquals(ChatSendStatus.IDLE, composer.status.value)
    }
}
