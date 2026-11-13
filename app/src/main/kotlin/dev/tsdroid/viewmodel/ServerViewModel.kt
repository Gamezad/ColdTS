package dev.tsdroid.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import dev.tsdroid.bridge.AvatarCache
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.FileCache
import dev.tsdroid.bridge.IconCache
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.bridge.TsFileEntry
import dev.tsdroid.bridge.extractFileLink
import dev.tsdroid.bridge.fileCacheRelativePath
import dev.tsdroid.bridge.fileCacheServerKey
import dev.tsdroid.bridge.fileServerEndpoint
import dev.tsdroid.bridge.incomingPeerKey
import dev.tsdroid.bridge.isSameFileServer
import dev.tsdroid.bridge.matchesRecipient
import dev.tsdroid.bridge.peerKey
import dev.tsdroid.han.R
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.data.MessageStore
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.service.TsConnectionService
import dev.tsdroid.service.WhisperManager
import dev.tslib.Channel
import dev.tslib.ConnectionState
import dev.tslib.Event
import dev.tslib.ServerInfo
import dev.tslib.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileAttachment(
    val fileName: String,
    val fileSize: Long,
    val fileId: String,
    val isImage: Boolean,
    val channelId: Long = 0L,
)

data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean = false,
    val isPrivate: Boolean = false,
    val senderId: Int = 0,
    val fileAttachment: FileAttachment? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
    val peerName: String? = null,
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    data class Done(val image: ImageBitmap?, val fileUri: android.net.Uri? = null) : DownloadState()
    data class Error(val message: String, val retryable: Boolean = true) : DownloadState()
}

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ServerViewModel"
        private const val ATTACHMENT_RETRY_DELAY_MS = 5 * 60 * 1000L
        private const val MAX_SELECTED_FILE_BYTES = 10 * 1024 * 1024
    }

    private val messageStore = MessageStore(application)
    private val bookmarkStore = BookmarkStore(application)
    private val settingsStore = SettingsStore(application)
    private val iconCache = IconCache(application.cacheDir)
    private val avatarCache = AvatarCache(application.cacheDir)
    private val fileCache = FileCache(application)
    private var serverAddress: String? = null
    private var saveJob: Job? = null
    // Share in-flight transfers and keep automatic failures on a cooldown.
    private val downloadCache = java.util.concurrent.ConcurrentHashMap<String, StateFlow<DownloadState>>()
    private val failedAttachmentUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val _previewImageBytes = MutableStateFlow<ByteArray?>(null)
    val previewImageBytes: StateFlow<ByteArray?> = _previewImageBytes.asStateFlow()
    private val _previewImageName = MutableStateFlow<String?>(null)
    val previewImageName: StateFlow<String?> = _previewImageName.asStateFlow()

    private var tsClient: TsClient? = null
    private var audioBridge: AudioBridge? = null
    private var connectionService: TsConnectionService? = null

    private data class PendingUpload(
        val client: TsClient,
        val serverAddress: String,
        val origin: UploadOrigin,
        val recipient: User?,
        val shareInChat: Boolean,
    )
    // Retained across configuration changes while the system picker is open.
    private var pendingChatUpload: PendingUpload? = null
    private var pendingManagerUpload: PendingUpload? = null

    /** Client ID of the local user on the current server (null while not connected). */
    val myClientId: Int? get() = tsClient?.clientId

    /** Unique IDs of users marked as friends (persisted). */
    val friends: StateFlow<Set<String>> = settingsStore.friends
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Per-user playback volume in percent (0..150), keyed by unique id. */
    val userVolumes: StateFlow<Map<String, Int>> = settingsStore.userVolumes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    @Volatile
    private var currentUserVolumes: Map<String, Int> = emptyMap()

    private fun applyUserVolumes() {
        val bridge = audioBridge ?: return
        val byUid = currentUserVolumes
        if (byUid.isEmpty()) {
            bridge.setUserGains(emptyMap())
            return
        }
        val gains = HashMap<Int, Float>()
        for (u in _users.value) {
            val uid = u.uid ?: continue
            val pct = byUid[uid] ?: continue
            gains[u.id] = (pct / 100f).coerceIn(0f, 1.5f)
        }
        bridge.setUserGains(gains)
    }

    fun setUserVolume(uid: String?, percent: Int) {
        if (uid.isNullOrBlank()) return
        viewModelScope.launch { settingsStore.setUserVolume(uid, percent) }
    }

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    // Raw users from the server (isTalking always false in snapshots)
    private val _rawUsers = MutableStateFlow<List<User>>(emptyList())

    // Set of currently talking user IDs (tracked via talk_status events)
    private val _talkingUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val talkingUserIds: StateFlow<Set<Int>> = _talkingUserIds.asStateFlow()
    
    // Track local mic state for local user talking highlight
    private val _isLocalTalking = MutableStateFlow(false)

    private val _mutedUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val mutedUserIds: StateFlow<Set<Int>> = _mutedUserIds.asStateFlow()

    // Whisper (密聊) state — bridged from WhisperManager

    // Users with isTalking patched from talk status events
    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _channelIcons = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val channelIcons: StateFlow<Map<Long, ImageBitmap>> = _channelIcons.asStateFlow()

    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars.asStateFlow()
    private var avatarVersions: Map<String, String> = emptyMap()
    private val avatarLoads = java.util.concurrent.ConcurrentHashMap.newKeySet<Triple<String, String, String>>()

    private val chatComposer = ChatComposer(viewModelScope)
    val messageDraft: StateFlow<String> = chatComposer.draft
    val chatSendStatus: StateFlow<ChatSendStatus> = chatComposer.status

    fun updateMessageDraft(value: String) = chatComposer.edit(value)

    private val _channelMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val channelMessages: StateFlow<List<ChatMessage>> = _channelMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<ChatMessage>>> = _privateMessages.asStateFlow()

    // Separate PTT mode from actual mute state
    private val _isPttMode = MutableStateFlow(true) // true = PTT, false = voice activity
    val isPttMode: StateFlow<Boolean> = _isPttMode.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    val isLocalVoiceActive: StateFlow<Boolean> get() = audioBridge?.isLocalVoiceActive ?: MutableStateFlow(false)

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    // Unread message counters
    private val _unreadChannel = MutableStateFlow(0)
    val unreadChannel: StateFlow<Int> = _unreadChannel.asStateFlow()
    private val _unreadPrivate = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadPrivate: StateFlow<Map<String, Int>> = _unreadPrivate.asStateFlow()

    val audioGain: StateFlow<Float> = settingsStore.audioGain
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val showLinkThumbnails: StateFlow<Boolean> = settingsStore.showLinkThumbnails
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoLoadImages: StateFlow<Boolean> = settingsStore.autoLoadImages
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val enableFloatingWindow: StateFlow<Boolean> = settingsStore.enableFloatingWindow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val noiseSuppression: StateFlow<Boolean> = settingsStore.noiseSuppression
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // File manager state
    private val _fileManagerOpen = MutableStateFlow(false)
    val fileManagerOpen: StateFlow<Boolean> = _fileManagerOpen.asStateFlow()

    private val _fileList = MutableStateFlow<List<TsFileEntry>>(emptyList())
    val fileList: StateFlow<List<TsFileEntry>> = _fileList.asStateFlow()

    private val _currentFilePath = MutableStateFlow("/")
    val currentFilePath: StateFlow<String> = _currentFilePath.asStateFlow()

    private val _fileManagerLoading = MutableStateFlow(false)
    val fileManagerLoading: StateFlow<Boolean> = _fileManagerLoading.asStateFlow()
    private var fileManagerSession: String? = null
    private var fileManagerChannelId = 0L
    private var fileListGeneration = 0L

    /** Permission hints for the current channel (bitflags from Channel.PERM_*) */
    val currentChannelPermissions: StateFlow<Long> = combine(_channels, _rawUsers) { channels, users ->
        val myId = tsClient?.clientId ?: return@combine 0L
        val channelId = users.find { it.id == myId }?.channelId ?: return@combine 0L
        channels.find { it.id == channelId }?.permissionHints ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // Track which channels we've already queried permissions for
    private val queriedPermChannels = mutableSetOf<Long>()

    // Track chat visibility to avoid incrementing unread for visible tab
    var isChatOpen = false
    var activeChatTab = 0
    var activePmPeerKey: String? = null

    private var bound = false

    init {
        // Combine raw users + talking set to produce patched user list
        viewModelScope.launch {
            combine(_rawUsers, _talkingUserIds, _isLocalTalking) { users, talking, localTalking ->
                val myId = tsClient?.clientId
                
                users.map { user ->
                    val isLocallyTalking = (user.id == myId && localTalking)
                    val isRemoteTalking = user.id in talking
                    val shouldBeTalking = isLocallyTalking || isRemoteTalking
                    
                    if (shouldBeTalking && !user.isTalking) user.withTalking(true)
                    else if (!shouldBeTalking && user.isTalking) user.withTalking(false)
                    else user
                }
            }.collect { _users.value = it }
        }
        // Only query the current channel, once, after initial state has settled.
        viewModelScope.launch {
            combine(_channels, _rawUsers) { channels, users ->
                val myId = tsClient?.clientId ?: return@combine null
                val channelId = users.find { it.id == myId }?.channelId ?: return@combine null
                channelId to (channels.find { it.id == channelId }?.permissionHints ?: 0L)
            }.distinctUntilChanged().collectLatest { pair ->
                val (channelId, hints) = pair ?: return@collectLatest
                if (hints == 0L && channelId !in queriedPermChannels) {
                    delay(2_000) // after the local initial-state resync
                    if (tsClient?.queryChannelPermissions(channelId) == true) {
                        queriedPermChannels.add(channelId)
                    }
                }
            }
        }
    }

    fun bindToService() {
        if (bound) return
        bound = true // prevent duplicate collectors while waiting for the service

        viewModelScope.launch {
            var attempts = 0
            while (TsConnectionService.instance == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }
            
            val service = TsConnectionService.instance
            if (service == null) {
                Log.e(TAG, "Failed to bind to TsConnectionService: instance is null")
                bound = false
                return@launch
            }

            tsClient = service.tsClient
            audioBridge = service.audioBridge
            viewModelScope.launch {
                settingsStore.userVolumes.collect { vols ->
                    currentUserVolumes = vols
                    applyUserVolumes()
                }
            }
            viewModelScope.launch {
                _users.collect { applyUserVolumes() }
            }
            audioBridge?.setMutedUserIds(_mutedUserIds.value)
            connectionService = service
            queriedPermChannels.clear()

            val addr = service.tsClient.serverAddress
            if (!addr.isNullOrEmpty()) {
                val (channelMsgs, privateMsgs) = withContext(Dispatchers.IO) { messageStore.load(addr) }
                _channelMessages.value = channelMsgs.map { migrateMessage(it) }
                _privateMessages.value = privateMsgs.mapValues { (_, msgs) -> msgs.map { migrateMessage(it) } }
                // If the view model is cleared during the disk read, do not save
                // empty state over the existing history.
                serverAddress = addr
            }

            viewModelScope.launch {
                service.tsClient.channels.collect { channels ->
                    Log.d(TAG, "Channels updated: ${channels.size}")
                    _channels.value = channels
                    loadChannelIcons(channels)
                }
            }
            viewModelScope.launch {
                service.tsClient.users.collect {
                    _rawUsers.value = it
                    if (_fileManagerOpen.value &&
                        (service.tsClient.sessionId != fileManagerSession || currentChannelId() != fileManagerChannelId)) {
                        // An overlay move or reconnect invalidates the visible file list.
                        closeFileManager()
                    }
                    loadAvatars(it)
                }
            }
            viewModelScope.launch {
                var bookmarkUpdated = false
                service.tsClient.serverInfo.collect { info ->
                    _serverInfo.value = info
                    if (info != null && !bookmarkUpdated) {
                        bookmarkUpdated = true
                        val addr = serverAddress ?: service.tsClient.serverAddress ?: ""
                        if (addr.isNotEmpty()) {
                            bookmarkStore.updateServerInfo(addr, info.name, info.iconId)
                            // Download server icon if needed
                            if (info.iconId != 0L) {
                                iconCache.loadIcon(info.iconId, service.tsClient)
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                service.tsClient.state.collect { _connectionState.value = it }
            }
            viewModelScope.launch {
                service.tsClient.events.collect { handleEvent(it) }
            }
            viewModelScope.launch {
                service.tsClient.messages.collect { handleEvent(it) }
            }
            viewModelScope.launch {
                service.tsClient.commandErrors.collect { message ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // The connection may have closed while message history was loading.
            if (service.tsClient.isConnected && !service.audioBridge.isCapturing.value) {
                service.ensureAudioCapture(noiseSuppression.value)
            }
            // Apply persisted audio gain
            service.audioBridge.gainFactor = audioGain.value
            // Observe audio gain changes and apply live
            viewModelScope.launch {
                audioGain.collect { gain ->
                    service.audioBridge.gainFactor = gain
                }
            }
            // Observe audio state for local talking status
            viewModelScope.launch {
                service.audioBridge.isLocalVoiceActive.collect { _isLocalTalking.value = it }
            }
            viewModelScope.launch {
                service.audioBridge.isOutputMuted.collect { _isOutputMuted.value = it }
            }

            // The foreground service owns the event loop for the connection.
        }
    }

    private fun loadChannelIcons(channels: List<Channel>) {
        val client = tsClient ?: return
        val iconIds = channels.mapNotNull { ch ->
            if (ch.iconId != 0L) ch.iconId else null
        }.distinct()

        for (iconId in iconIds) {
            if (iconCache.getIcon(iconId) != null) {
                // Already in memory, make sure it's in the flow
                continue
            }
            viewModelScope.launch(Dispatchers.IO) {
                iconCache.loadIcon(iconId, client)
                val icon = iconCache.getIcon(iconId)
                if (icon != null) {
                    _channelIcons.update { it + (iconId to icon) }
                }
            }
        }
        // Also emit icons already cached in memory
        val cached = mutableMapOf<Long, ImageBitmap>()
        for (iconId in iconIds) {
            iconCache.getIcon(iconId)?.let { cached[iconId] = it }
        }
        if (cached.isNotEmpty() && cached != _channelIcons.value) {
            _channelIcons.value = _channelIcons.value + cached
        }
    }

    private fun loadAvatars(users: List<User>) {
        val client = tsClient ?: return
        val versions = users.mapNotNull { user ->
            val uid = user.uid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val version = user.avatarId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (user.isQuery) null else uid to version
        }.toMap()
        // Remove a stale image immediately when an avatar changes or disappears.
        val previous = avatarVersions
        avatarVersions = versions
        _avatars.update { cached -> cached.filterKeys { uid -> versions[uid] != null && versions[uid] == previous[uid] } }

        val session = client.sessionId ?: return
        for (user in users) {
            val uid = user.uid?.takeIf { it.isNotBlank() } ?: continue
            val version = user.avatarId?.takeIf { it.isNotBlank() } ?: continue
            if (user.isQuery) continue
            val cached = avatarCache.getAvatar(user, client)
            if (cached != null) {
                _avatars.update { it + (uid to cached) }
                continue
            }
            if (avatarCache.hasNoAvatar(user, client)) continue
            val request = Triple(session, uid, version)
            if (!avatarLoads.add(request)) continue
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (client.sessionId != session) return@launch
                    val avatar = avatarCache.loadAvatar(user, client)
                    if (avatar != null && client.sessionId == session &&
                        _rawUsers.value.any { it.uid == uid && it.avatarId == version }) {
                        _avatars.update { it + (uid to avatar) }
                    }
                } finally {
                    avatarLoads.remove(request)
                }
            }
        }
    }

    /** Prefer stable identities; never use an old temporary client ID after reconnect. */
    fun peerKeyFor(user: User): String = peerKey(user, tsClient?.sessionId)

    private fun peerKeyForClientId(id: Int, sender: String): String =
        incomingPeerKey(id, sender, tsClient?.users?.value.orEmpty(), tsClient?.sessionId)

    private fun addPrivateMessage(peerKey: String, message: ChatMessage) {
        _privateMessages.value = _privateMessages.value.toMutableMap().apply {
            this[peerKey] = (this[peerKey] ?: emptyList()) + message
        }
    }

    private fun handleEvent(event: Event) {
        when (event.type) {
            "talk_status_start" -> {
                val userId = (event.data["user_id"] as? Number)?.toInt() ?: return
                _talkingUserIds.value = _talkingUserIds.value + userId
            }
            "talk_status_stop" -> {
                val userId = (event.data["user_id"] as? Number)?.toInt() ?: return
                _talkingUserIds.value = _talkingUserIds.value - userId
            }
            "channel_deleted" -> {
                (event.data["channel_id"] as? Number)?.toLong()?.let { queriedPermChannels.remove(it) }
            }
            "text_message" -> {
                val target = event.data["target"] as? String ?: return
                val text = event.data["message"] as? String ?: return
                val senderId = (event.data["sender_id"] as? Number)?.toInt()
                // Our own sends are inserted only after the native send succeeds.
                if (senderId != null && senderId == tsClient?.clientId) return
                val sender = (event.data["sender_name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.unknown_sender)
                val attachment = if (text.length <= 10_000) parseFileAttachment(text) else null
                val displayText = attachment?.fileName ?: text.replace("\u0000", "")
                when (target.lowercase()) {
                    "private" -> {
                        val id = senderId ?: return
                        val key = peerKeyForClientId(id, sender)
                        addPrivateMessage(key, ChatMessage(
                            sender = sender, text = displayText, isPrivate = true, senderId = id,
                            peerName = sender, fileAttachment = attachment,
                        ))
                        if (!isChatOpen || activeChatTab != 1 ||
                            (activePmPeerKey != null && activePmPeerKey != key)) {
                            _unreadPrivate.value = _unreadPrivate.value.toMutableMap().apply {
                                this[key] = (this[key] ?: 0) + 1
                            }
                        }
                    }
                    // Server announcements and flood warnings should be visible,
                    // not silently discarded just because they contain a keyword.
                    "channel", "server" -> {
                        _channelMessages.value = _channelMessages.value + ChatMessage(
                            sender = sender, text = displayText, senderId = senderId ?: 0,
                            fileAttachment = attachment,
                        )
                        if (!isChatOpen || activeChatTab != 0) _unreadChannel.value++
                    }
                    else -> {
                        Log.w(TAG, "Unknown message target: $target")
                        return
                    }
                }
                scheduleSave()
            }
        }
    }

    private fun parseFileAttachment(text: String): FileAttachment? {
        // TS5 MyTeamSpeak JSON format
        if (text.startsWith("{\"msg_type\":")) {
            return try {
                val json = org.json.JSONObject(text)
                if (json.optString("msg_type") != "ts.file.myts") return null
                val fileName = json.optString("file_name", "")
                val fileSize = json.optLong("file_size", 0)
                val fileId = json.optString("file_id", "")
                if (fileName.isEmpty() || fileId.isEmpty()) return null
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                FileAttachment(fileName, fileSize, fileId, isImage)
            } catch (_: Exception) { null }
        }
        // TS3 file transfer URL format: ts3file://host?port=...&channel=...&filename=...&size=...
        val fileLink = extractFileLink(text)
        if (fileLink != null) {
            return try {
                val uri = android.net.Uri.parse(fileLink)
                val address = serverAddress ?: tsClient?.serverAddress ?: return null
                val queryPort = uri.getQueryParameter("port")
                val authorityPort = uri.port.takeIf { it >= 0 }?.toString()
                if (queryPort != null && authorityPort != null && queryPort.toIntOrNull() != authorityPort.toIntOrNull()) return null
                if (!isSameFileServer(address, uri.host, queryPort ?: authorityPort)) return null
                val fileName = uri.getQueryParameter("filename") ?: return null
                val fileSize = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
                val channelId = uri.getQueryParameter("channel")?.toLongOrNull() ?: 0L
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                val folder = uri.getQueryParameter("path")?.trim('/')?.takeIf { it.isNotEmpty() }
                val remotePath = "/" + (folder?.let { "$it/" } ?: "") + fileName.trimStart('/')
                FileAttachment(fileName, fileSize, fileId = remotePath, isImage, channelId = channelId)
            } catch (_: Exception) { null }
        }
        return null
    }

    /** Re-parse old saved messages that contain ts3file:// but have no fileAttachment. */
    private fun migrateMessage(msg: ChatMessage): ChatMessage {
        if (msg.fileAttachment != null) return msg
        val attachment = parseFileAttachment(msg.text) ?: return msg
        return msg.copy(text = attachment.fileName, fileAttachment = attachment)
    }

    fun setChatState(open: Boolean, tab: Int, peerKey: String? = null) {
        isChatOpen = open
        activeChatTab = tab
        activePmPeerKey = peerKey
        if (open && tab == 0) _unreadChannel.value = 0
        if (open && tab == 1) {
            _unreadPrivate.value = if (peerKey == null) emptyMap()
                else _unreadPrivate.value - peerKey
        }
    }

    fun sendChannelMessage(): Boolean {
        val client = tsClient ?: return chatComposer.reject()
        val session = client.sessionId ?: return chatComposer.reject()
        val channelId = currentChannelId()
        if (!client.isConnected || channelId == 0L) return chatComposer.reject()
        return chatComposer.submit { text ->
            if (client.sendChannelMessage(text, expectedChannelId = channelId, expectedSession = session)) {
                _channelMessages.value = _channelMessages.value + ChatMessage(
                    sender = getApplication<Application>().getString(R.string.me_sender), text = text, isMe = true,
                )
                scheduleSave()
                ChatSendResult.SENT
            } else ChatSendResult.FAILED
        }
    }

    private suspend fun sendPrivateTo(
        client: TsClient, user: User, peerKey: String, session: String, text: String,
    ): Boolean {
        if (!client.sendPrivateMessage(user.id, text, user.uid, user.databaseId, user.nickname, session)) return false
        addPrivateMessage(peerKey, ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender), text = text,
            isMe = true, isPrivate = true, senderId = user.id, peerName = user.nickname,
        ))
        scheduleSave()
        return true
    }

    fun sendPrivateMessage(user: User): Boolean {
        val client = tsClient ?: return chatComposer.reject()
        val session = client.sessionId ?: return chatComposer.reject()
        if (!client.isConnected) return chatComposer.reject()
        // Use the selected user's snapshot, not a fresh lookup by temporary ID.
        // TsClient verifies the identity again on the native thread before sending.
        val key = peerKey(user, session)
        return chatComposer.submit { text ->
            if (sendPrivateTo(client, user, key, session, text)) ChatSendResult.SENT else ChatSendResult.FAILED
        }
    }

    fun toggleWhisper(userId: Int) {
        val wasActive = WhisperManager.isWhisperActive
        // The native library currently supports only text fallback. Pause
        // channel audio before entering that mode, not after the UI changes.
        if (!wasActive) audioBridge?.setChannelVoiceSuppressed(true)
        WhisperManager.toggleWhisper(userId)
        val textOnly = WhisperManager.isWhisperActive && WhisperManager.isTextFallback
        if (textOnly) {
            _isPttMode.value = true
            if (!wasActive) Toast.makeText(getApplication(), R.string.whisper_text_only, Toast.LENGTH_LONG).show()
        } else {
            // Never resume voice activity automatically when whisper ends.
            audioBridge?.setMuted(true)
            audioBridge?.setChannelVoiceSuppressed(false)
            _isPttMode.value = true
        }
    }

    fun sendWhisperMessage(): Boolean {
        val client = tsClient ?: return chatComposer.reject()
        val session = client.sessionId ?: return chatComposer.reject()
        if (!WhisperManager.isWhisperActive || !client.isConnected) return chatComposer.reject()
        // Capture recipient identities and session now; IDs can be reused while sends are paced.
        val recipients = WhisperManager.whisperTargetUsers.map { it to peerKey(it, session) }
        if (recipients.isEmpty()) return chatComposer.reject()
        return chatComposer.submit { text ->
            val sent = recipients.count { (user, key) -> sendPrivateTo(client, user, key, session, text) }
            when (sent) {
                recipients.size -> ChatSendResult.SENT
                0 -> ChatSendResult.FAILED
                else -> ChatSendResult.PARTIAL
            }
        }
    }

    val whisperCandidateUsers: List<User>
        get() = _users.value.filter { it.id != tsClient?.clientId }

    fun moveToChannel(channelId: Long) {
        tsClient?.moveToChannel(channelId)
    }

    fun setAudioGain(gain: Float) {
        audioBridge?.gainFactor = gain
        viewModelScope.launch { settingsStore.setAudioGain(gain) }
    }

    fun setShowLinkThumbnails(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowLinkThumbnails(enabled) }
    }

    fun setAutoLoadImages(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoLoadImages(enabled) }
    }

    fun setEnableFloatingWindow(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setEnableFloatingWindow(enabled) }
    }

    fun toggleFriend(uid: String?) {
        if (uid.isNullOrBlank()) return
        viewModelScope.launch { settingsStore.toggleFriend(uid) }
    }

    fun setNoiseSuppression(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNoiseSuppression(enabled) }
    }

    fun toggleVoiceMode() {
        if (WhisperManager.isWhisperActive && WhisperManager.isTextFallback) {
            Toast.makeText(getApplication(), R.string.whisper_text_only, Toast.LENGTH_SHORT).show()
            return
        }
        val newPttMode = !_isPttMode.value
        _isPttMode.value = newPttMode
        // When switching to PTT mode, mute. When switching to VA, unmute.
        audioBridge?.setMuted(newPttMode)
    }

    fun toggleOutputMute() {
        audioBridge?.toggleOutputMute()
    }

    fun toggleMuteUser(clientId: Int) {
        val updated = if (clientId in _mutedUserIds.value) {
            _mutedUserIds.value - clientId
        } else {
            _mutedUserIds.value + clientId
        }
        _mutedUserIds.value = updated
        audioBridge?.setMutedUserIds(updated)
    }

    fun setPushToTalk(pressed: Boolean) {
        if (pressed && WhisperManager.isWhisperActive && WhisperManager.isTextFallback) {
            Toast.makeText(getApplication(), R.string.whisper_text_only, Toast.LENGTH_SHORT).show()
            return
        }
        // Only changes mute state, NOT isPttMode — avoids UI recomposition swap
        audioBridge?.setMuted(!pressed)
    }

    private fun currentChannelId(): Long {
        val client = tsClient ?: return 0
        val myId = client.clientId ?: return 0
        return client.users.value.find { it.id == myId }?.channelId ?: 0
    }

    private fun fileShareUrl(channelId: Long, folder: String, name: String, size: Long): String? {
        val address = serverAddress ?: return null
        val endpoint = fileServerEndpoint(address) ?: return null
        return android.net.Uri.Builder().scheme("ts3file").authority(endpoint.authority)
            .appendQueryParameter("port", endpoint.port.toString())
            .appendQueryParameter("channel", channelId.toString())
            .appendQueryParameter("path", folder)
            .appendQueryParameter("filename", name)
            .appendQueryParameter("isDir", "0")
            .appendQueryParameter("size", size.toString())
            .build().toString()
    }

    private fun fileAttachment(name: String, size: Long, channelId: Long, folder: String): FileAttachment {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        return FileAttachment(name, size, "${folder.trimEnd('/')}/$name", isImage, channelId)
    }

    private suspend fun sendFileLink(
        client: TsClient, target: User?, session: String, url: String, attachment: FileAttachment,
    ): Boolean {
        val sent = if (target == null) client.sendChannelMessage(url, attachment.channelId, session)
            else client.sendPrivateMessage(target.id, url, target.uid, target.databaseId, target.nickname, session)
        if (!sent) return false
        val message = ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender),
            text = attachment.fileName, isMe = true, isPrivate = target != null,
            senderId = target?.id ?: 0, peerName = target?.nickname, fileAttachment = attachment,
        )
        if (target == null) _channelMessages.value = _channelMessages.value + message
        else addPrivateMessage(peerKey(target, session), message)
        scheduleSave()
        return true
    }

    private fun prepareUpload(folder: String, recipient: User?, shareInChat: Boolean): PendingUpload? {
        val client = tsClient ?: return null
        val session = client.sessionId ?: return null
        val address = client.serverAddress ?: return null
        val channelId = currentChannelId()
        if (!client.isConnected || channelId <= 0) return null
        val upload = PendingUpload(client, address, UploadOrigin(session, channelId, folder), recipient, shareInChat)
        return upload.takeIf(::isUploadCurrent)
    }

    private fun isUploadCurrent(upload: PendingUpload): Boolean {
        val client = upload.client
        if (tsClient !== client || !upload.origin.matches(client.sessionId, currentChannelId(), client.isConnected)) {
            return false
        }
        val target = upload.recipient ?: return true
        val actual = client.users.value.firstOrNull { it.id == target.id }
        return matchesRecipient(actual, target.uid, target.databaseId, target.nickname)
    }

    /** Capture the destination before launching the external picker. */
    fun beginChatUpload(isPrivate: Boolean, target: User?): Boolean {
        val upload = if (isPrivate && target == null) null else prepareUpload("/", if (isPrivate) target else null, true)
        pendingChatUpload = upload
        if (upload == null) Toast.makeText(getApplication(), R.string.file_share_failed, Toast.LENGTH_SHORT).show()
        return upload != null
    }

    fun beginManagerUpload(): Boolean {
        val client = tsClient
        val upload = if (client != null && isFileManagerCurrent(client)) prepareUpload(_currentFilePath.value, null, false) else null
        pendingManagerUpload = upload
        if (upload == null) Toast.makeText(getApplication(), R.string.file_upload_failed, Toast.LENGTH_SHORT).show()
        return upload != null
    }

    fun completeChatUpload(uri: Uri?) {
        val upload = pendingChatUpload
        pendingChatUpload = null
        completeUpload(upload, uri)
    }

    fun completeManagerUpload(uri: Uri?) {
        val upload = pendingManagerUpload
        pendingManagerUpload = null
        completeUpload(upload, uri)
    }

    private fun completeUpload(upload: PendingUpload?, uri: Uri?) {
        if (uri == null) return // picker cancelled
        if (upload == null || !isUploadCurrent(upload)) {
            Toast.makeText(getApplication(), R.string.file_selection_failed, Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val (name, data) = try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    } ?: "file"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        readSelectedFile(input, MAX_SELECTED_FILE_BYTES) { coroutineContext.ensureActive() }
                    } ?: throw java.io.IOException("Could not open the selected file")
                    safeUploadName(displayName) to bytes
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SelectedFileTooLargeException) {
                Toast.makeText(getApplication(), R.string.file_too_large, Toast.LENGTH_SHORT).show()
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "Could not read selected file", e)
                Toast.makeText(getApplication(), R.string.file_selection_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!isUploadCurrent(upload)) {
                Toast.makeText(getApplication(), R.string.file_selection_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val origin = upload.origin
            val path = "${origin.folder.trimEnd('/')}/$name"
            val uploaded = upload.client.uploadFile(
                origin.channelId, path, data, overwrite = true, expectedSession = origin.session,
                expectedCurrentChannelId = origin.channelId,
            )
            if (uploaded) {
                // An overwrite makes any previous local copy of this path stale.
                val host = fileCacheServerKey(upload.serverAddress)
                val cachePath = fileCacheRelativePath(origin.channelId, path)
                val cacheKey = "$host/$cachePath"
                downloadCache.remove(cacheKey)
                failedAttachmentUntil.remove(cacheKey)
                withContext(Dispatchers.IO) { fileCache.remove(host, cachePath) }
            }
            val success = if (uploaded && upload.shareInChat && isUploadCurrent(upload)) {
                val url = fileShareUrl(origin.channelId, origin.folder, name, data.size.toLong())
                val attachment = fileAttachment(name, data.size.toLong(), origin.channelId, origin.folder)
                url != null && sendFileLink(upload.client, upload.recipient, origin.session, url, attachment)
            } else uploaded && !upload.shareInChat
            if (!success) {
                Toast.makeText(getApplication(), if (upload.shareInChat) R.string.file_share_failed else R.string.file_upload_failed, Toast.LENGTH_SHORT).show()
            } else if (!upload.shareInChat && isUploadCurrent(upload) && _currentFilePath.value == origin.folder) {
                delay(500)
                if (isUploadCurrent(upload) && _currentFilePath.value == origin.folder) refreshFileList()
            }
        }
    }

    fun downloadAttachment(attachment: FileAttachment, automatic: Boolean = false): StateFlow<DownloadState> {
        val context = getApplication<Application>()
        fun error(message: Int, retryable: Boolean = true): StateFlow<DownloadState> =
            MutableStateFlow(DownloadState.Error(context.getString(message), retryable))

        // MyTeamSpeak cloud file IDs are not TeamSpeak channel paths.
        if (attachment.fileId.isNotBlank() && !attachment.fileId.startsWith('/')) {
            return error(R.string.download_not_supported, retryable = false)
        }
        val client = tsClient ?: return error(R.string.file_download_failed)
        val session = client.sessionId ?: return error(R.string.file_download_failed)
        val address = serverAddress ?: client.serverAddress ?: return error(R.string.file_download_failed)
        if (!client.isConnected) return error(R.string.file_download_failed)
        val channelId = if (attachment.channelId != 0L) attachment.channelId else currentChannelId()
        if (channelId <= 0L) return error(R.string.file_download_failed)
        val remotePath = attachment.fileId.takeIf { it.startsWith('/') } ?: "/${attachment.fileName}"
        val host = fileCacheServerKey(address)
        val cachePath = fileCacheRelativePath(channelId, remotePath)
        val cacheKey = "$host/$cachePath"

        downloadCache[cacheKey]?.let { return it }
        if (automatic && (failedAttachmentUntil[cacheKey] ?: 0L) > System.nanoTime() / 1_000_000L) {
            return error(R.string.file_download_failed)
        }
        if (!automatic) failedAttachmentUntil.remove(cacheKey)
        val state = MutableStateFlow<DownloadState>(DownloadState.Downloading)
        downloadCache.putIfAbsent(cacheKey, state)?.let { return it }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = fileCache.get(host, cachePath)
                val bytes = cached ?: client.downloadFile(channelId, remotePath, automatic, session)
                if (bytes == null || client.sessionId != session || !client.isConnected) {
                    throw IllegalStateException("Attachment unavailable or connection changed")
                }
                if (cached == null) fileCache.put(host, cachePath, bytes)
                val done = if (attachment.isImage) {
                    val bitmap = decodeSampledBitmap(bytes, 1280)?.asImageBitmap()
                    val uri = fileCache.getUri(host, cachePath)
                    if (bitmap == null && uri == null) throw IllegalStateException("Could not open image")
                    DownloadState.Done(bitmap, uri)
                } else {
                    val uri = saveToDownloads(safeUploadName(attachment.fileName), bytes)
                        ?: throw IllegalStateException("Could not save file")
                    DownloadState.Done(null, uri)
                }
                failedAttachmentUntil.remove(cacheKey)
                state.value = done
            } catch (e: kotlinx.coroutines.CancellationException) {
                downloadCache.remove(cacheKey, state)
                state.value = DownloadState.Error(context.getString(R.string.file_download_failed))
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download attachment", e)
                if (client.isConnected && client.sessionId == session) {
                    failedAttachmentUntil[cacheKey] = System.nanoTime() / 1_000_000L + ATTACHMENT_RETRY_DELAY_MS
                }
                downloadCache.remove(cacheKey, state)
                state.value = DownloadState.Error(context.getString(R.string.file_download_failed))
            }
        }
        return state
    }

    private suspend fun saveToDownloads(fileName: String, data: ByteArray): android.net.Uri? {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        var pending: android.net.Uri? = null
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null
            pending = uri
            val output = resolver.openOutputStream(uri) ?: throw java.io.IOException("No output stream")
            output.use { it.write(data) }
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, contentValues, null, null) <= 0) throw java.io.IOException("Could not finalize file")
            pending = null
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.file_saved, fileName), Toast.LENGTH_SHORT).show()
            }
            uri
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "saveToDownloads failed for $fileName", e)
            null
        } finally {
            pending?.let { try { resolver.delete(it, null, null) } catch (_: Exception) {} }
        }
    }

    private fun decodeSampledBitmap(data: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
        // First pass: decode only bounds
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        // Calculate sample size (power of 2)
        val width = options.outWidth
        val height = options.outHeight
        var sampleSize = 1
        if (width > maxDimension || height > maxDimension) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while (halfWidth / sampleSize >= maxDimension || halfHeight / sampleSize >= maxDimension) {
                sampleSize *= 2
            }
        }

        // Second pass: decode with sample size
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
    }

    private fun isFileManagerCurrent(client: TsClient): Boolean =
        _fileManagerOpen.value && client.isConnected && client.sessionId == fileManagerSession &&
            fileManagerChannelId > 0 && currentChannelId() == fileManagerChannelId

    fun toggleFileManager() {
        if (_fileManagerOpen.value) {
            closeFileManager()
        } else {
            val client = tsClient ?: return
            val session = client.sessionId ?: return
            val channelId = currentChannelId()
            if (!client.isConnected || channelId <= 0) return
            fileManagerSession = session
            fileManagerChannelId = channelId
            _fileManagerOpen.value = true
            refreshFileList()
        }
    }

    fun closeFileManager() {
        fileListGeneration++
        fileManagerSession = null
        fileManagerChannelId = 0L
        _fileManagerOpen.value = false
        _fileManagerLoading.value = false
        _currentFilePath.value = "/"
        _fileList.value = emptyList()
    }

    fun refreshFileList() {
        val client = tsClient ?: return
        val session = fileManagerSession ?: return
        val channelId = fileManagerChannelId
        if (!isFileManagerCurrent(client)) return
        val path = _currentFilePath.value
        val generation = ++fileListGeneration
        _fileManagerLoading.value = true
        viewModelScope.launch {
            val files = client.listFiles(channelId, path, expectedSession = session)
            if (generation != fileListGeneration) return@launch
            _fileManagerLoading.value = false
            if (!_fileManagerOpen.value || client.sessionId != session || currentChannelId() != channelId ||
                _currentFilePath.value != path) return@launch
            _fileList.value = files ?: emptyList()
            if (files == null) Toast.makeText(getApplication(), R.string.file_list_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun navigateToFolder(folderName: String) {
        val client = tsClient ?: return
        if (!isFileManagerCurrent(client)) return
        val current = _currentFilePath.value
        _currentFilePath.value = if (current.endsWith("/")) "$current$folderName/" else "$current/$folderName/"
        refreshFileList()
    }

    fun navigateUp() {
        val client = tsClient ?: return
        if (!isFileManagerCurrent(client)) return
        val current = _currentFilePath.value.trimEnd('/')
        if (current == "" || current == "/") return
        val parent = current.substringBeforeLast('/', "/")
        _currentFilePath.value = if (parent.endsWith("/")) parent else "$parent/"
        refreshFileList()
    }

    fun deleteFileInChannel(name: String) {
        val client = tsClient ?: return
        if (!isFileManagerCurrent(client)) return
        val channelId = fileManagerChannelId
        val fullPath = _currentFilePath.value + name
        client.deleteFile(channelId, fullPath)
        viewModelScope.launch { delay(500); refreshFileList() }
    }

    fun renameFileInChannel(oldName: String, newName: String) {
        val client = tsClient ?: return
        if (!isFileManagerCurrent(client)) return
        val channelId = fileManagerChannelId
        val currentPath = _currentFilePath.value
        client.renameFile(channelId, currentPath + oldName, currentPath + newName)
        viewModelScope.launch { delay(500); refreshFileList() }
    }

    fun createDirectoryInChannel(dirName: String) {
        val client = tsClient ?: run { Log.w(TAG, "createDirectory: tsClient is null"); return }
        if (!isFileManagerCurrent(client)) return
        val channelId = fileManagerChannelId
        val fullPath = _currentFilePath.value + dirName
        Log.d(TAG, "createDirectory: channelId=$channelId path=$fullPath")
        try {
            client.createDirectory(channelId, fullPath)
        } catch (e: Exception) {
            Log.e(TAG, "createDirectory failed", e)
        }
        viewModelScope.launch { delay(500); refreshFileList() }
    }

    fun shareFile(target: User?, fileName: String, fileSize: Long) {
        val client = tsClient ?: return
        if (!isFileManagerCurrent(client)) return
        val session = client.sessionId ?: return
        val channelId = fileManagerChannelId
        val folder = _currentFilePath.value
        val url = fileShareUrl(channelId, folder, fileName, fileSize) ?: return
        val attachment = fileAttachment(fileName, fileSize, channelId, folder)
        viewModelScope.launch {
            if (!sendFileLink(client, target, session, url, attachment)) {
                Toast.makeText(getApplication(), R.string.file_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun downloadFileFromManager(fileName: String) {
        val client = tsClient ?: return
        if (!isFileManagerCurrent(client)) return
        val session = client.sessionId ?: return
        val address = serverAddress ?: client.serverAddress ?: return
        val channelId = fileManagerChannelId
        val remotePath = "/" + _currentFilePath.value.trimStart('/') + fileName
        val host = fileCacheServerKey(address)
        val cachePath = fileCacheRelativePath(channelId, remotePath)

        viewModelScope.launch(Dispatchers.IO) {
            val cached = fileCache.get(host, cachePath)
            val bytes = cached ?: client.downloadFile(channelId, remotePath, expectedSession = session)
            if (client.sessionId != session || !client.isConnected) return@launch
            if (bytes == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), R.string.file_download_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (cached == null) fileCache.put(host, cachePath, bytes)
            val dlUri = saveToDownloads(safeUploadName(fileName), bytes)
            withContext(Dispatchers.Main) {
                if (dlUri != null) openFileUri(dlUri, fileName)
                else Toast.makeText(getApplication(), R.string.file_download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun previewImageFile(fileName: String) {
        val client = tsClient ?: return
        if (!isFileManagerCurrent(client)) return
        val session = client.sessionId ?: return
        val address = serverAddress ?: client.serverAddress ?: return
        val channelId = fileManagerChannelId
        val remotePath = "/" + _currentFilePath.value.trimStart('/') + fileName
        val host = fileCacheServerKey(address)
        val cachePath = fileCacheRelativePath(channelId, remotePath)

        viewModelScope.launch(Dispatchers.IO) {
            val cached = fileCache.get(host, cachePath)
            val bytes = cached ?: client.downloadFile(channelId, remotePath, expectedSession = session)
            if (client.sessionId != session || !client.isConnected) return@launch
            if (bytes == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), R.string.file_download_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (cached == null) fileCache.put(host, cachePath, bytes)
            _previewImageBytes.value = bytes
            _previewImageName.value = fileName
        }
    }

    fun closePreview() {
        _previewImageBytes.value = null
        _previewImageName.value = null
    }

    private fun openFileUri(uri: android.net.Uri, fileName: String) {
        val context = getApplication<Application>()
        try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        saveNow()
        queriedPermChannels.clear()
        connectionService?.disconnect()
    }

    override fun onCleared() {
        saveNow()
        bound = false
        tsClient = null
        audioBridge = null
        connectionService = null
        super.onCleared()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(2000)
            saveNow()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        val addr = serverAddress ?: return
        messageStore.save(addr, _channelMessages.value, _privateMessages.value)
    }
}

/** Create a copy of User with isTalking changed (User fields are final). */
private fun User.withTalking(talking: Boolean): User = User(
    id, uid, databaseId, channelId, nickname, clientType,
    talking, isInputMuted, isOutputMuted, hasInputHardware, hasOutputHardware,
    isAway, isRecording, isPrioritySpeaker, isChannelCommander, isTalker,
    talkPower, awayMessage, serverGroups, channelGroup,
    platform, version, country, description, avatarId, iconId,
)
