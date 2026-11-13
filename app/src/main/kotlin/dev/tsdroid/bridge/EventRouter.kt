package dev.tsdroid.bridge

import dev.tslib.Event
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Keep lossy, high-volume voice frames separate from buffered chat messages. */
internal class EventRouter {
    private val controlEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events = controlEvents.asSharedFlow()
    private val voiceFrames = MutableSharedFlow<Event>(
        extraBufferCapacity = 96, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioEvents = voiceFrames.asSharedFlow()
    // Buffer until the chat screen attaches, but do not replay already-consumed
    // messages to a recreated screen. Bound the queue for a long background session.
    private val messageQueue = Channel<Event>(1024, BufferOverflow.DROP_OLDEST)
    val messages = messageQueue.receiveAsFlow()

    suspend fun route(event: Event) {
        when (event.type) {
            "audio_received" -> voiceFrames.tryEmit(event)
            "text_message" -> messageQueue.trySend(event)
            // Transfer and error events are handled by TsClient before routing;
            // avoid copying large file payloads through unrelated collectors.
            "file_downloaded", "file_uploaded", "file_transfer_failed",
            "file_list_received", "command_error" -> Unit
            else -> controlEvents.emit(event)
        }
    }

    fun clearMessages() {
        while (messageQueue.tryReceive().isSuccess) { /* drain previous session */ }
    }
}
