package dev.tsdroid.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ChatSendStatus { IDLE, SENDING, FAILED, PARTIAL }

internal enum class ChatSendResult { SENT, FAILED, PARTIAL }

/** Keep the user's draft until a queued native send succeeds. */
internal class ChatComposer(private val scope: CoroutineScope) {
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()
    private val _status = MutableStateFlow(ChatSendStatus.IDLE)
    val status: StateFlow<ChatSendStatus> = _status.asStateFlow()
    private var revision = 0L

    fun edit(value: String) {
        if (_draft.value != value) {
            revision++
            _draft.value = value
        }
        if (_status.value == ChatSendStatus.FAILED || _status.value == ChatSendStatus.PARTIAL) {
            _status.value = ChatSendStatus.IDLE
        }
    }

    fun reject(): Boolean {
        if (_status.value != ChatSendStatus.SENDING) _status.value = ChatSendStatus.FAILED
        return false
    }

    fun submit(send: suspend (String) -> ChatSendResult): Boolean {
        val text = _draft.value
        if (text.isBlank() || _status.value == ChatSendStatus.SENDING) return false
        val submittedRevision = revision
        _status.value = ChatSendStatus.SENDING
        scope.launch {
            val result = try {
                send(text)
            } catch (e: CancellationException) {
                _status.value = ChatSendStatus.IDLE
                throw e
            } catch (_: Exception) {
                ChatSendResult.FAILED
            }
            _status.value = when (result) {
                ChatSendResult.SENT -> {
                    // Do not erase text typed while this send was in flight.
                    if (revision == submittedRevision) _draft.value = ""
                    ChatSendStatus.IDLE
                }
                ChatSendResult.FAILED -> ChatSendStatus.FAILED
                ChatSendResult.PARTIAL -> ChatSendStatus.PARTIAL
            }
        }
        return true
    }
}
