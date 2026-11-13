package dev.tsdroid.bridge

import android.util.Log
import dev.tslib.Channel
import dev.tslib.Client
import dev.tslib.ConnectionState
import dev.tslib.Event
import dev.tslib.Identity
import dev.tslib.ServerInfo
import dev.tslib.User
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TsFileEntry(
    val name: String,
    val size: Long,
    val datetime: Long,
    val isFile: Boolean,
)

class TsClient {

    companion object {
        private const val TAG = "TsClient"
        private const val INITIAL_CONNECT_SETTLE_MS = 300L
        private const val RECONNECT_AFTER_DISCONNECT_DELAY_MS = 1_500L
        private const val DISCONNECT_MIN_FLUSH_MS = 500L
        private const val DISCONNECT_MAX_FLUSH_MS = 2_000L
        private const val DISCONNECT_POLL_MS = 20L
        private const val INITIAL_STATE_RESYNC_MS = 1_500L
        private const val LOCAL_STATE_RESYNC_MS = 10_000L
        // Icons, avatars and permission lookups are automatic server commands.
        // Space them out even on servers with strict anti-flood settings.
        private const val AUTOMATIC_COMMAND_SPACING_MS = 1_200L
        private const val CHAT_COMMAND_SPACING_MS = 750L
        private val SNAPSHOT_EVENTS = setOf(
            "connected", "disconnected", "connection_lost", "channel_created", "channel_deleted",
            "channel_edited", "channel_joined", "channel_permissions_updated", "user_joined",
            "user_left", "user_moved", "user_updated", "text_message", "user_kicked_from_channel",
            "user_kicked_from_server", "user_banned", "server_group_assigned", "server_group_removed",
        )
    }

    @Volatile
    private var nativeThread: Thread? = null

    private val nativeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TsClientNative").also { nativeThread = it }
    }.asCoroutineDispatcher()

    @Volatile
    private var client: Client? = null

    @Volatile
    private var cachedClientId: Int? = null

    @Volatile
    var serverAddress: String? = null
        private set

    private val eventRouter = EventRouter()
    val events: SharedFlow<Event> = eventRouter.events
    val audioEvents: SharedFlow<Event> = eventRouter.audioEvents
    val messages = eventRouter.messages

    @Volatile
    var sessionId: String? = null
        private set

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<Int> = _state.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _commandErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commandErrors: SharedFlow<String> = _commandErrors.asSharedFlow()

    private val downloadCallbacks = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private data class UploadCallback(val channelId: Long, val result: CompletableDeferred<Boolean>)
    private val uploadCallbacks = ConcurrentHashMap<String, UploadCallback>()
    private data class PendingFileList(val client: Client, val session: String, val result: CompletableDeferred<List<TsFileEntry>?>)
    private val fileListCallbacks = ConcurrentHashMap<String, PendingFileList>()

    private var eventLoopJob: Job? = null
    private val clientCoroutineScope = CoroutineScope(nativeDispatcher + SupervisorJob())
    private val connectMutex = Mutex()
    private val pacedCommandMutex = Mutex()
    private val urgentPacedRequests = AtomicInteger(0)
    private val chatCommandMutex = Mutex()
    private val uploadMutex = Mutex()
    @Volatile private var lastPacedCommandAt = 0L
    @Volatile private var lastPacedWasAutomatic = true

    private data class PendingDownload(val client: Client, val channelId: Long, val result: CompletableDeferred<ByteArray?>)
    private val pendingDownloads = ConcurrentHashMap<String, PendingDownload>()
    private var moveJob: Job? = null
    private val queriedPermissionHints = mutableMapOf<Long, Long>() // native-thread only
    private val audioSendPending = AtomicBoolean(false)

    val isConnected: Boolean
        get() = _state.value == ConnectionState.CONNECTED

    val clientId: Int?
        get() = cachedClientId

    suspend fun connect(
        address: String,
        identity: Identity,
        nickname: String,
        password: String? = null,
        channel: String? = null,
    ) = withContext(nativeDispatcher) {
        connectMutex.withLock {
            try {
                stopEventLoop()
                val hadExistingClient = disconnectOnNativeThread()
                eventRouter.clearMessages()

                delay(if (hadExistingClient) RECONNECT_AFTER_DISCONNECT_DELAY_MS else INITIAL_CONNECT_SETTLE_MS)
                
                serverAddress = address
                _state.value = ConnectionState.CONNECTING

                var lastFailure: Throwable? = null
                for (attempt in 0 until MAX_NICKNAME_COLLISION_ATTEMPTS) {
                    val candidateNickname = nicknameWithCollisionSuffix(nickname, attempt)
                    var pendingClient: Client? = null
                    var pendingClientConnected = false
                    var retrying = false
                    try {
                        try {
                            identity.setNickname(candidateNickname)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Failed to update identity nickname before connect", e)
                        }
                        val c = Client(address, identity, candidateNickname, password, channel)
                        pendingClient = c
                        c.waitConnected()
                        pendingClientConnected = true
                        // Do not disconnect just because another client has the same
                        // nickname: many servers allow that. Retry only when the
                        // server explicitly rejects the nickname.
                        client = c
                        pendingClient = null
                        sessionId = UUID.randomUUID().toString()
                        lastPacedCommandAt = monotonicMillis()
                        lastPacedWasAutomatic = true
                        _state.value = ConnectionState.CONNECTED
                        // Re-copy the native client's currently known channel and user state.
                        // Additional channel events are processed by the event loop.
                        try {
                            c.syncState()
                        } catch (_: Throwable) {
                        }
                        refreshState()
                        if (client == null) {
                            throw IllegalStateException("Connection closed during initial state sync")
                        }
                        return@withLock
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        lastFailure = e
                        retrying = e.isNicknameCollisionFailure()
                        Log.w(TAG, "Connection attempt failed with nickname '$candidateNickname'", e)
                        if (!retrying || attempt == MAX_NICKNAME_COLLISION_ATTEMPTS - 1) {
                            throw e
                        }
                    } finally {
                        pendingClient?.let {
                            if (pendingClientConnected) {
                                closeClient(it, "failed connection attempt")
                            } else {
                                destroyClient(it, "failed connection attempt before connected")
                            }
                        }
                        if (retrying && attempt < MAX_NICKNAME_COLLISION_ATTEMPTS - 1) {
                            delay(1_500L * (attempt + 1))
                        }
                    }
                }

                throw Exception(lastFailure.userMessage() ?: "Server rejected the connection", lastFailure)
            } catch (e: Throwable) {
                closeAfterNativeFailure()
                if (e is CancellationException) throw e

                Log.e("TS6_CRASH_PREVENTION", "Connection failed; native client cleaned up", e)
                throw Exception("Connection failed: ${e.message ?: "Server busy or rejected"}", e)
            }
        }
    }

    private fun Throwable?.userMessage(): String? {
        var current = this
        var fallback: String? = null
        while (current != null) {
            current.message?.takeIf { it.isNotBlank() }?.let { fallback = it }
            current = current.cause
        }
        return fallback
    }


    @Synchronized
    fun startEventLoop() {
        if (eventLoopJob?.isActive == true) return
        eventLoopJob = clientCoroutineScope.launch {
            var nextLocalResync = monotonicMillis() + INITIAL_STATE_RESYNC_MS
            var refreshCounter = 0
            try {
                while (isActive && client != null) {
                    ensureActive()
                    val c = client ?: break
                    val events = (c.processEvents() ?: emptyArray()).filterNotNull()

                    // syncState copies local connection state only, without a
                    // network request. This catches late channel batches and
                    // edits not represented by a high-level native event.
                    if (monotonicMillis() >= nextLocalResync) {
                        nextLocalResync = monotonicMillis() + LOCAL_STATE_RESYNC_MS
                        try { c.syncState() } catch (e: Exception) { Log.w(TAG, "Local state sync failed", e) }
                        refreshState()
                    }
                    refreshCounter++
                    if (events.any { it.type in SNAPSHOT_EVENTS } || refreshCounter >= 25) {
                        refreshState()
                        refreshCounter = 0
                    }
                    if (client !== c) break // refreshState may have closed a failed client

                    for (event in events) {
                        handleEvent(event)
                        eventRouter.route(event)
                    }
                    if (client === c && events.any { it.type == "disconnected" }) {
                        // The server already closed the connection. Destroy it
                        // without sending another disconnect or spinning on EOF.
                        client = null
                        resetState()
                        destroyClient(c, "remote disconnect")
                        break
                    }
                    delay(20)
                }
            } catch (e: CancellationException) {
                // Normal shutdown.
            } catch (e: Throwable) {
                if (client != null) {
                    Log.e(TAG, "Event loop native failure; closing connection", e)
                    closeAfterNativeFailure()
                }
            }
        }
    }

    @Synchronized
    fun stopEventLoop() {
        eventLoopJob?.cancel()
        eventLoopJob = null
    }

    private fun handleEvent(event: Event) {
        when (event.type) {
            "disconnected" -> _state.value = ConnectionState.DISCONNECTED
            "connected" -> _state.value = ConnectionState.CONNECTED
            "file_downloaded" -> {
                val path = event.data["path"] as? String ?: return
                val data = event.data["data"] as? ByteArray ?: return
                val channelId = (event.data["channel_id"] as? Number)?.toLong()
                val pending = pendingDownloads[path] ?: return
                // Success events include a channel ID; do not accept a late
                // response for the same path from a different channel.
                if (channelId != null && channelId != pending.channelId) return
                val callback = downloadCallbacks[path] ?: return
                if (pendingDownloads[path] !== pending) return
                Log.d(TAG, "File downloaded: $path (${data.size} bytes)")
                if (downloadCallbacks.remove(path, callback)) callback.complete(data)
            }
            "file_uploaded" -> {
                val path = event.data["path"] as? String ?: return
                val channelId = (event.data["channel_id"] as? Number)?.toLong()
                val pending = uploadCallbacks[path] ?: return
                if (channelId != null && channelId != pending.channelId) return
                Log.d(TAG, "File uploaded: $path")
                if (uploadCallbacks.remove(path, pending)) pending.result.complete(true)
            }
            "file_transfer_failed" -> {
                val path = event.data["path"] as? String ?: return
                val error = event.data["error"] as? String ?: "unknown"
                Log.w(TAG, "File transfer failed: $path — $error")
                downloadCallbacks.remove(path)?.completeExceptionally(
                    Exception("File transfer failed: $error")
                )
                uploadCallbacks.remove(path)?.result?.complete(false)
            }
            "file_list_received" -> {
                val channelId = (event.data["channel_id"] as? Number)?.toLong() ?: return
                val path = event.data["path"] as? String ?: return
                val filesJson = event.data["files"] as? String ?: return
                val entries = parseFileEntries(filesJson)
                Log.d(TAG, "File list received: channel=$channelId path=$path entries=${entries.size}")
                val key = "$channelId:$path"
                val pending = fileListCallbacks[key] ?: return
                if (pending.client === client && pending.session == sessionId && fileListCallbacks.remove(key, pending)) {
                    pending.result.complete(entries)
                }
            }
            "command_error" -> {
                val message = event.data["message"] as? String ?: return
                Log.w(TAG, "Command error: $message")
                _commandErrors.tryEmit(message)
            }
            "channel_deleted" -> {
                (event.data["channel_id"] as? Number)?.toLong()?.let { queriedPermissionHints.remove(it) }
            }
            "channel_permissions_updated" -> {
                val channelId = (event.data["channel_id"] as? Number)?.toLong() ?: return
                val hints = (event.data["permission_hints"] as? Number)?.toLong() ?: return
                Log.i(TAG, "Channel $channelId permissions updated: ${hints.toString(16)}")
                // Native syncState rebuilds its map and can discard queried hints.
                queriedPermissionHints[channelId] = hints
                refreshState()
            }
        }
    }

    private fun refreshState() {
        val c = client ?: return
        try {
            val channels = c.channels?.filterNotNull()?.map { channel ->
                val cached = queriedPermissionHints[channel.id]
                if (channel.permissionHints == 0L && cached != null) channel.withPermissionHints(cached) else channel
            }?.sortedBy { it.id } ?: emptyList()
            val users = c.users?.filterNotNull()?.sortedBy { it.id } ?: emptyList()
            if (!sameChannels(_channels.value, channels)) _channels.value = channels
            if (!sameUsers(_users.value, users)) _users.value = users
            val info = c.serverInfo
            if (!sameServerInfo(_serverInfo.value, info)) _serverInfo.value = info
            cachedClientId = c.clientId
            val st = c.state
            if (_state.value != st) _state.value = st
        } catch (e: Throwable) {
            Log.w(TAG, "refreshState failed", e)
            closeAfterNativeFailure()
        }
    }

    private fun closeAfterNativeFailure() {
        val c = client
        client = null
        resetState()
        if (c != null) {
            closeClient(c, "native failure")
        }
    }

    private fun disconnectOnNativeThread(): Boolean {
        stopEventLoop()
        val c = client
        client = null
        resetState()
        if (c != null) {
            closeClient(c, "disconnect")
            return true
        }
        return false
    }

    private fun resetState() {
        _state.value = ConnectionState.DISCONNECTED
        _channels.value = emptyList()
        _users.value = emptyList()
        _serverInfo.value = null
        cachedClientId = null
        sessionId = null
        queriedPermissionHints.clear()
        moveJob?.cancel()
        moveJob = null
        downloadCallbacks.values.forEach { it.completeExceptionally(IllegalStateException("Disconnected")) }
        uploadCallbacks.values.forEach { it.result.complete(false) }
        fileListCallbacks.values.forEach { it.result.complete(null) }
        downloadCallbacks.clear()
        uploadCallbacks.clear()
        fileListCallbacks.clear()
    }

    private fun closeClient(c: Client, reason: String) {
        var disconnectSent = false
        try {
            c.disconnect()
            disconnectSent = true
            Log.d(TAG, "Native disconnect command sent ($reason)")
        } catch (e: Throwable) {
            Log.w(TAG, "disconnect during $reason failed", e)
        }

        if (disconnectSent) {
            flushDisconnect(c, reason)
        }

        destroyClient(c, reason)
    }

    private fun flushDisconnect(c: Client, reason: String) {
        val startedAt = System.currentTimeMillis()
        val minFlushEnd = startedAt + DISCONNECT_MIN_FLUSH_MS
        val maxFlushEnd = startedAt + DISCONNECT_MAX_FLUSH_MS
        var observedDisconnected = false

        while (System.currentTimeMillis() < maxFlushEnd) {
            try {
                val events = c.processEvents() ?: emptyArray()
                if (events.any { it.type == "disconnected" }) {
                    observedDisconnected = true
                }
                if (!c.isConnected || c.state == ConnectionState.DISCONNECTED) {
                    observedDisconnected = true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "disconnect flush during $reason failed", e)
                break
            }

            if (observedDisconnected && System.currentTimeMillis() >= minFlushEnd) {
                break
            }

            try {
                Thread.sleep(DISCONNECT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        Log.d(TAG, "Disconnect flush complete ($reason, observedDisconnected=$observedDisconnected)")
    }

    private fun destroyClient(c: Client, reason: String) {
        try {
            c.close()
        } catch (e: Throwable) {
            Log.w(TAG, "close during $reason failed", e)
        }
    }

    private fun launchNativeCommand(name: String, block: Client.() -> Unit) {
        val expected = client ?: return
        clientCoroutineScope.launch {
            if (client !== expected) return@launch
            try {
                expected.block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "$name failed", e)
                _commandErrors.tryEmit(e.message ?: "$name failed")
            }
        }
    }

    /** Pace user text (especially multi-recipient whispers) without delaying audio. */
    private suspend fun sendChatCommand(
        name: String,
        expectedSession: String?,
        block: Client.() -> Unit,
    ): Boolean {
        val expected = client ?: return false
        val session = expectedSession ?: return false
        if (sessionId != session) return false
        // Keep multiple chat messages ordered, while giving them priority over
        // background icon/avatar requests on the shared command limiter.
        return chatCommandMutex.withLock {
            pacedCommand(expected, session, name, automatic = false, urgent = true, reportError = true, block = block)
        }
    }

    suspend fun sendChannelMessage(
        msg: String,
        expectedChannelId: Long? = null,
        expectedSession: String? = sessionId,
    ): Boolean = sendChatCommand("sendChannelMessage", expectedSession) {
            // A message waiting in the paced queue must not leak into a channel
            // the user joined after pressing Send.
            if (expectedChannelId != null && clientId?.let { getUser(it)?.channelId } != expectedChannelId) {
                throw IllegalStateException("The current channel changed before the message could be sent")
            }
            sendChannelMessage(msg)
        }

    suspend fun sendServerMessage(msg: String, expectedSession: String? = sessionId): Boolean =
        sendChatCommand("sendServerMessage", expectedSession) { sendServerMessage(msg) }

    suspend fun sendPrivateMessage(
        userId: Int,
        msg: String,
        expectedUid: String? = null,
        expectedDatabaseId: Long? = null,
        expectedNickname: String? = null,
        expectedSession: String? = sessionId,
    ): Boolean = sendChatCommand("sendPrivateMessage", expectedSession) {
        val target = getUser(userId)
        if (!matchesRecipient(target, expectedUid, expectedDatabaseId, expectedNickname)) {
            throw IllegalStateException("The private-message recipient is no longer connected")
        }
        sendPrivateMessage(userId, msg)
    }

    fun moveToChannel(channelId: Long) {
        val expected = client ?: return
        if (_users.value.firstOrNull { it.id == cachedClientId }?.channelId == channelId) return
        moveJob?.cancel()
        moveJob = clientCoroutineScope.launch {
            // Rapid taps should only send the last requested move.
            delay(250)
            if (client !== expected || _users.value.firstOrNull { it.id == cachedClientId }?.channelId == channelId) return@launch
            try { expected.moveToChannel(channelId) } catch (e: Exception) {
                Log.w(TAG, "moveToChannel failed", e)
                _commandErrors.tryEmit(e.message ?: "moveToChannel failed")
            }
        }
    }

    fun sendAudio(
        data: ByteArray, codec: Int, expectedSession: String? = sessionId,
        stillAllowed: () -> Boolean = { true },
    ) {
        val expected = client ?: return
        val session = expectedSession ?: return
        if (sessionId != session || !isConnected) return
        // Drop stale voice frames instead of accumulating a burst behind JNI.
        if (!audioSendPending.compareAndSet(false, true)) return
        clientCoroutineScope.launch {
            try {
                if (client === expected && sessionId == session && isConnected && stillAllowed()) {
                    expected.sendAudio(data, codec)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "sendAudio failed", e)
            } finally {
                audioSendPending.set(false)
            }
        }
    }

    fun setInputMuted(muted: Boolean) {
        launchNativeCommand("setInputMuted") { setInputMuted(muted) }
    }

    /**
     * Automatic transfers and text share one limiter, so a chat send cannot
     * coincide with an icon/avatar request. Voice frames never use this queue.
     */
    private suspend fun pacedCommand(
        expected: Client,
        session: String,
        name: String,
        automatic: Boolean,
        urgent: Boolean,
        reportError: Boolean = false,
        block: Client.() -> Unit,
    ): Boolean {
        if (urgent) urgentPacedRequests.incrementAndGet()
        try {
            while (client === expected && sessionId == session && isConnected) {
                var waitMs = 0L
                val result: Boolean? = pacedCommandMutex.withLock {
                    val now = monotonicMillis()
                    val spacing = if (automatic || lastPacedWasAutomatic) AUTOMATIC_COMMAND_SPACING_MS
                        else CHAT_COMMAND_SPACING_MS
                    val readyAt = lastPacedCommandAt + spacing
                    when {
                        !urgent && urgentPacedRequests.get() > 0 -> { waitMs = 50; null }
                        now < readyAt -> { waitMs = readyAt - now; null }
                        else -> withContext(nativeDispatcher) {
                            if (client !== expected || sessionId != session || !isConnected) return@withContext false
                            try {
                                expected.block()
                                true
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "$name failed", e)
                                if (reportError) _commandErrors.tryEmit(e.message ?: "$name failed")
                                false
                            } finally {
                                // A rejected command may still count toward anti-flood points.
                                lastPacedCommandAt = monotonicMillis()
                                lastPacedWasAutomatic = automatic
                            }
                        }
                    }
                }
                if (result != null) return result
                delay(waitMs.coerceAtLeast(1L))
            }
            return false
        } finally {
            if (urgent) urgentPacedRequests.decrementAndGet()
        }
    }

    suspend fun downloadFile(
        channelId: Long,
        path: String,
        automatic: Boolean = false,
        expectedSession: String? = sessionId,
    ): ByteArray? {
        // JNI failure callbacks contain only a path, not a channel ID. Share
        // identical transfers and serialize same-path transfers across channels.
        val expected = client ?: return null
        val session = expectedSession ?: return null
        while (client === expected && sessionId == session && isConnected) {
            val pending = PendingDownload(expected, channelId, CompletableDeferred())
            val existing = pendingDownloads.putIfAbsent(path, pending)
            if (existing != null) {
                val result = existing.result.await()
                if (client !== expected || sessionId != session || !isConnected) return null
                if (existing.client === expected && existing.channelId == channelId) return result
                continue
            }

            var result: ByteArray? = null
            val deferred = CompletableDeferred<ByteArray>()
            try {
                downloadCallbacks[path] = deferred
                val started = if (automatic) {
                    pacedCommand(expected, session, "downloadFile", automatic = true, urgent = false) {
                        downloadFile(channelId, path)
                    }
                } else withContext(nativeDispatcher) {
                    if (client !== expected || sessionId != session || !isConnected) return@withContext false
                    try {
                        expected.downloadFile(channelId, path)
                        true
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "downloadFile failed for $path", e)
                        false
                    }
                }
                if (started) {
                    result = withTimeoutOrNull(10_000) {
                        try { deferred.await() } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.w(TAG, "downloadFile failed for $path", e); null }
                    }
                }
                return result
            } finally {
                downloadCallbacks.remove(path, deferred)
                pendingDownloads.remove(path, pending)
                pending.result.complete(result)
            }
        }
        return null
    }

    suspend fun listFiles(channelId: Long, path: String, expectedSession: String? = sessionId): List<TsFileEntry>? {
        val expected = client ?: return null
        val session = expectedSession ?: return null
        if (sessionId != session || !isConnected) return null
        val key = "$channelId:$path"
        val pending = PendingFileList(expected, session, CompletableDeferred())
        val existing = fileListCallbacks.putIfAbsent(key, pending)
        if (existing != null) {
            val result = existing.result.await()
            return result.takeIf { existing.client === expected && existing.session == session &&
                client === expected && sessionId == session && isConnected }
        }
        try {
            val started = withContext(nativeDispatcher) {
                if (client !== expected || sessionId != session || !isConnected) return@withContext false
                try {
                    expected.listFiles(channelId, path)
                    true
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "listFiles failed for $path", e)
                    false
                }
            }
            if (!started) return null
            return withTimeoutOrNull(5_000) { pending.result.await() }
        } finally {
            if (fileListCallbacks.remove(key, pending)) pending.result.complete(null)
        }
    }

    fun deleteFile(channelId: Long, name: String) {
        launchNativeCommand("deleteFile") {
            this.deleteFile(channelId, name)
        }
    }

    fun renameFile(channelId: Long, oldName: String, newName: String) {
        launchNativeCommand("renameFile") {
            this.renameFile(channelId, oldName, newName)
        }
    }

    fun createDirectory(channelId: Long, dirname: String) {
        launchNativeCommand("createDirectory") {
            this.createDirectory(channelId, dirname)
        }
    }

    suspend fun queryChannelPermissions(channelId: Long): Boolean {
        val expected = client ?: return false
        val session = sessionId ?: return false
        return pacedCommand(expected, session, "queryChannelPermissions", automatic = true, urgent = true) {
            queryChannelPermissions(channelId)
        }
    }

    private fun parseFileEntries(json: String): List<TsFileEntry> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TsFileEntry(
                    name = obj.getString("name"),
                    size = obj.getLong("size"),
                    datetime = obj.getLong("datetime"),
                    isFile = obj.getBoolean("is_file"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFileEntries failed", e)
            emptyList()
        }
    }

    suspend fun uploadFile(
        channelId: Long,
        path: String,
        data: ByteArray,
        overwrite: Boolean = true,
        expectedSession: String? = sessionId,
        expectedCurrentChannelId: Long? = null,
    ): Boolean {
        val expected = client ?: return false
        val session = expectedSession ?: return false
        // Native upload callbacks identify only the path. Do not let concurrent
        // uploads overwrite each other's callback or cross into a new session.
        return uploadMutex.withLock {
            if (client !== expected || sessionId != session || !isConnected) return@withLock false
            val deferred = CompletableDeferred<Boolean>()
            val callback = UploadCallback(channelId, deferred)
            uploadCallbacks[path] = callback
            try {
                val started = withContext(nativeDispatcher) {
                    if (client !== expected || sessionId != session || !isConnected) return@withContext false
                    try {
                        if (expectedCurrentChannelId != null &&
                            expected.clientId?.let { expected.getUser(it)?.channelId } != expectedCurrentChannelId) {
                            return@withContext false
                        }
                        expected.uploadFile(channelId, path, data, overwrite)
                        true
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "uploadFile failed for $path", e)
                        false
                    }
                }
                if (!started) return@withLock false
                withTimeoutOrNull(30_000) {
                    try { deferred.await() } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        Log.w(TAG, "uploadFile await failed for $path", e)
                        false
                    }
                } ?: false
            } finally {
                uploadCallbacks.remove(path, callback)
            }
        }
    }

    fun disconnect() {
        if (Thread.currentThread() == nativeThread) {
            disconnectOnNativeThread()
        } else {
            runBlocking(nativeDispatcher) {
                disconnectOnNativeThread()
            }
        }
    }

    /** The service owns this client and must release its native thread on destruction. */
    fun release() {
        disconnect()
        clientCoroutineScope.cancel()
        nativeDispatcher.close()
    }
}

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
