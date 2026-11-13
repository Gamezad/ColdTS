package dev.tsdroid.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tsdroid.han.R
import dev.tslib.Channel
import dev.tslib.ConnectionState
import dev.tslib.User
import dev.tsdroid.ui.component.AppWallpaper
import dev.tsdroid.ui.component.ChannelInfoDialog
import dev.tsdroid.ui.component.ChannelTree
import dev.tsdroid.ui.component.displayChannelName
import dev.tsdroid.ui.component.ChatView
import dev.tsdroid.ui.component.ClientInfoDialog
import dev.tsdroid.ui.component.FileManagerDialog
import dev.tsdroid.ui.component.ServerInfoDialog
import dev.tsdroid.ui.component.ShareTarget
import dev.tsdroid.viewmodel.ChatMessage
import dev.tsdroid.viewmodel.ChatSendStatus
import dev.tsdroid.viewmodel.DownloadState
import dev.tsdroid.viewmodel.FileAttachment
import dev.tsdroid.viewmodel.ServerViewModel
import kotlinx.coroutines.flow.StateFlow
import dev.tsdroid.service.WhisperManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onDisconnected: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: ServerViewModel = viewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val channelIcons by viewModel.channelIcons.collectAsStateWithLifecycle()
    val userAvatars by viewModel.avatars.collectAsStateWithLifecycle()
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val privateMessages by viewModel.privateMessages.collectAsStateWithLifecycle()
    val messageText by viewModel.messageDraft.collectAsStateWithLifecycle()
    val chatSendStatus by viewModel.chatSendStatus.collectAsStateWithLifecycle()
    val isPttMode by viewModel.isPttMode.collectAsStateWithLifecycle()
    val isOutputMuted by viewModel.isOutputMuted.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val unreadChannel by viewModel.unreadChannel.collectAsStateWithLifecycle()
    val unreadPrivate by viewModel.unreadPrivate.collectAsStateWithLifecycle()
    val audioGain by viewModel.audioGain.collectAsStateWithLifecycle()
    val showLinkThumbnails by viewModel.showLinkThumbnails.collectAsStateWithLifecycle()
    val autoLoadImages by viewModel.autoLoadImages.collectAsStateWithLifecycle()
    val enableFloatingWindow by viewModel.enableFloatingWindow.collectAsStateWithLifecycle()
    val noiseSuppression by viewModel.noiseSuppression.collectAsStateWithLifecycle()
    val mutedUserIds by viewModel.mutedUserIds.collectAsStateWithLifecycle()
    val friendUids by viewModel.friends.collectAsStateWithLifecycle()
    val userVolumes by viewModel.userVolumes.collectAsStateWithLifecycle()
    val fileManagerOpen by viewModel.fileManagerOpen.collectAsStateWithLifecycle()
    val fileList by viewModel.fileList.collectAsStateWithLifecycle()
    val previewImageBytes by viewModel.previewImageBytes.collectAsStateWithLifecycle()
    val previewImageName by viewModel.previewImageName.collectAsStateWithLifecycle()
    val currentFilePath by viewModel.currentFilePath.collectAsStateWithLifecycle()
    val fileManagerLoading by viewModel.fileManagerLoading.collectAsStateWithLifecycle()
    val channelPermissions by viewModel.currentChannelPermissions.collectAsStateWithLifecycle()

    var chatOpen by rememberSaveable { mutableStateOf(false) }
    var chatEverOpened by rememberSaveable { mutableStateOf(false) }
    var chatTab by rememberSaveable { mutableIntStateOf(0) }
    var pmPeerKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Info dialogs (classic TeamSpeak features)
    var clientInfoTarget by remember { mutableStateOf<User?>(null) }
    var channelInfoTarget by remember { mutableStateOf<Channel?>(null) }
    var showServerInfo by remember { mutableStateOf(false) }

    // Whisper state 鈥?read directly from WhisperManager
    val whisperTargetNames = WhisperManager.whisperTargetNames
    val whisperFirstTargetName = whisperTargetNames.firstOrNull()
    val textOnlyWhisper = WhisperManager.isWhisperActive && WhisperManager.isTextFallback

    // A TeamSpeak client ID is temporary. Conversation keys use the user's
    // permanent UID so a reconnect cannot route a reply to the wrong person.
    val pmTarget = pmPeerKey?.let { key -> users.find { viewModel.peerKeyFor(it) == key } }
    val context = LocalContext.current
    val pmConversationUsers = remember(privateMessages, users, pmPeerKey) {
        (privateMessages.keys + listOfNotNull(pmPeerKey)).distinct().map { key ->
            val liveUser = users.find { viewModel.peerKeyFor(it) == key }
            val history = privateMessages[key].orEmpty()
            val name = liveUser?.nickname
                ?: history.lastOrNull { !it.peerName.isNullOrBlank() }?.peerName
                ?: history.lastOrNull { !it.isMe }?.sender
                ?: context.getString(R.string.unknown_sender)
            key to name
        }
    }

    val totalUnreadPrivate = unreadPrivate.values.sum()

    // Sync chat state to ViewModel for unread tracking
    LaunchedEffect(chatOpen, chatTab, pmPeerKey) {
        viewModel.setChatState(chatOpen, chatTab, pmPeerKey)
    }

    DisposableEffect(Unit) {
        viewModel.bindToService()
        onDispose {}
    }

    // Navigate away on disconnect 鈥?one-shot via LaunchedEffect
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            onDisconnected()
        }
    }
    if (connectionState == ConnectionState.DISCONNECTED) return

    // Show floating window when entering ServerScreen if enabled
    LaunchedEffect(enableFloatingWindow) {
        if (enableFloatingWindow) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
                // Permission not granted, don't show
            } else {
                dev.tsdroid.service.TsConnectionService.instance?.showFloatingWindow()
            }
        } else {
            dev.tsdroid.service.TsConnectionService.instance?.hideFloatingWindow()
        }
    }

    val totalUnread = unreadChannel + totalUnreadPrivate

    Box(modifier = Modifier.fillMaxSize()) {
        AppWallpaper()

        Scaffold(
            containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = serverInfo?.name ?: stringResource(R.string.server),
                        modifier = Modifier.clickable { showServerInfo = true },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                actions = {
                    IconButton(onClick = { showServerInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.server_info))
                    }
                    IconButton(onClick = { viewModel.toggleFileManager() }) {
                        Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.file_manager))
                    }
                    IconButton(onClick = { viewModel.disconnect() }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.disconnect))
                    }
                },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Chat FAB with badge
                    Box {
                        IconButton(onClick = {
                            chatOpen = !chatOpen
                        }) {
                            Icon(
                                Icons.Default.ChatBubble,
                                contentDescription = stringResource(R.string.chat),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (totalUnread > 0) {
                            Badge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp),
                            ) {
                                Text("$totalUnread")
                            }
                        }
                    }

                    // Center button: PTT or Mute depending on MODE (not mute state)
                    if (isPttMode) {
                        // PTT mode: hold to talk
                        var isPressed by remember { mutableStateOf(false) }
                        val pttBackground = if (isPressed) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        val pttTint = if (isPressed) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(pttBackground)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isPressed = true
                                            viewModel.setPushToTalk(true)
                                            tryAwaitRelease()
                                            viewModel.setPushToTalk(false)
                                            isPressed = false
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    if (textOnlyWhisper) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = stringResource(if (textOnlyWhisper) R.string.whisper_text_only else R.string.push_to_talk),
                                    modifier = Modifier.size(28.dp),
                                    tint = pttTint,
                                )
                                Text(
                                    stringResource(if (textOnlyWhisper) R.string.mute else R.string.ptt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = pttTint,
                                )
                            }
                        }
                    } else {
                        // Voice activity mode: click to mute and go back to PTT
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .clickable { viewModel.toggleVoiceMode() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.MicOff,
                                    contentDescription = stringResource(R.string.mute_mic),
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    stringResource(R.string.mute),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    // Toggle voice mode (PTT 鈫?Voice Activity)
                    IconButton(onClick = { viewModel.toggleVoiceMode() }) {
                        Icon(
                            if (isPttMode) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = stringResource(if (isPttMode) R.string.unmute_mic else R.string.mute_mic),
                            tint = if (isPttMode) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Whisper indicator 鈥?shows active state, click to stop
                    if (WhisperManager.isWhisperActive && whisperFirstTargetName != null) {
                        IconButton(onClick = { viewModel.toggleWhisper(WhisperManager.whisperTargets.first()) }) {
                            Icon(
                                Icons.Default.Forum,
                                contentDescription = stringResource(R.string.stop_whisper),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {},
                            enabled = false,
                        ) {
                            Icon(
                                Icons.Default.Forum,
                                contentDescription = stringResource(R.string.whisper),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            )
                        }
                    }

                    // Toggle Output Mute (Deafen)
                    IconButton(onClick = { viewModel.toggleOutputMute() }) {
                        Icon(
                            if (isOutputMuted) Icons.Default.HeadsetOff else Icons.Default.Headset,
                            contentDescription = stringResource(if (isOutputMuted) R.string.sound_unmute else R.string.sound_mute),
                            tint = if (isOutputMuted) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Channel tree - full screen
            ChannelTree(
                channels = channels,
                users = users,
                onChannelClick = { channelId -> viewModel.moveToChannel(channelId) },
                onChannelLongClick = { channel -> channelInfoTarget = channel },
                onUserClick = { user -> clientInfoTarget = user },
                onUserLongClick = { user -> viewModel.toggleMuteUser(user.id) },
                onWhisperClick = { userId -> viewModel.toggleWhisper(userId) },
                friendUids = friendUids,
                mutedUserIds = mutedUserIds,
                channelIcons = channelIcons,
                userAvatars = userAvatars,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            )

            // File manager 鈥?slides up from bottom, fills content area
            val fileManagerProgress by animateFloatAsState(
                targetValue = if (fileManagerOpen) 0f else 1f,
                animationSpec = tween(300),
                label = "fileManager",
            )
            if (fileManagerOpen || fileManagerProgress < 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = size.height * fileManagerProgress },
                ) {
                    FileManagerDialog(
                        currentPath = currentFilePath,
                        files = fileList,
                        isLoading = fileManagerLoading,
                        users = users,
                        permissionHints = channelPermissions,
                        onNavigateToFolder = { viewModel.navigateToFolder(it) },
                        onNavigateUp = { viewModel.navigateUp() },
                        onRefresh = { viewModel.refreshFileList() },
                        onDownload = { viewModel.downloadFileFromManager(it) },
                        onDelete = { viewModel.deleteFileInChannel(it) },
                        onRename = { old, new -> viewModel.renameFileInChannel(old, new) },
                        onCreateDirectory = { viewModel.createDirectoryInChannel(it) },
                        onBeginUpload = viewModel::beginManagerUpload,
                        onFilePicked = viewModel::completeManagerUpload,
                        onShareFile = { target, name, size ->
                            when (target) {
                                is ShareTarget.Channel -> viewModel.shareFile(null, name, size)
                                is ShareTarget.PrivateMessage -> viewModel.shareFile(target.user, name, size)
                            }
                        },
                        onDismiss = { viewModel.closeFileManager() },
                        onPreviewImage = { fileName ->
                            viewModel.previewImageFile(fileName)
                        },
                    )
                }
            }

            // Chat panel 鈥?slides up from bottom, fills content area
            // Once opened, stay composed so re-opening is instant (no recomposition)
            if (chatOpen) chatEverOpened = true
            val chatProgress by animateFloatAsState(
                targetValue = if (chatOpen) 0f else 1f,
                animationSpec = tween(300),
                label = "chat",
            )
            if (chatEverOpened) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer { translationY = size.height * chatProgress },
                ) {
                    ChatPanel(
                        chatTab = chatTab,
                        onTabChange = { chatTab = it },
                        channelMessages = channelMessages,
                        privateMessages = pmPeerKey?.let { key ->
                            privateMessages[key] ?: emptyList()
                        } ?: privateMessages.values.flatten().sortedBy { it.timestamp },
                        messageText = messageText,
                        onMessageChange = viewModel::updateMessageDraft,
                        sendStatus = chatSendStatus,
                        pmTarget = pmTarget,
                        selectedPmPeerKey = pmPeerKey,
                        pmConversationUsers = pmConversationUsers,
                        onSelectPmUser = { peerKey -> pmPeerKey = peerKey },
                        onClearPmTarget = { pmPeerKey = null },
                        onSend = {
                            if (WhisperManager.isWhisperActive && whisperFirstTargetName != null) {
                                val firstTarget = WhisperManager.whisperTargets.firstOrNull()
                                if (viewModel.sendWhisperMessage() && firstTarget != null) {
                                    users.find { it.id == firstTarget }?.let { pmPeerKey = viewModel.peerKeyFor(it) }
                                    chatTab = 1
                                }
                            } else {
                                when (chatTab) {
                                    0 -> viewModel.sendChannelMessage()
                                    1 -> pmTarget?.let { viewModel.sendPrivateMessage(it) }
                                }
                            }
                        },
                        onClose = { chatOpen = false },
                        unreadChannel = unreadChannel,
                        unreadPrivateTotal = totalUnreadPrivate,
                        unreadPrivatePerUser = unreadPrivate,
                        showLinkThumbnails = showLinkThumbnails,
                        autoLoadImages = autoLoadImages && chatOpen,
                        canUploadFiles = (channelPermissions and dev.tslib.Channel.PERM_FILE_UPLOAD) != 0L,
                        onBeginUpload = { viewModel.beginChatUpload(chatTab == 1, pmTarget) },
                        onFilePicked = viewModel::completeChatUpload,
                        onDownload = { attachment, automatic -> viewModel.downloadAttachment(attachment, automatic) },
                        isWhisperActive = WhisperManager.isWhisperActive,
                        isTextWhisper = WhisperManager.isTextFallback,
                        whisperTargetName = whisperFirstTargetName,
                    )
                }
            }
        }
    }

    // Client info dialog (tap on a user)
    clientInfoTarget?.let { user ->
        ClientInfoDialog(
            user = user,
            channelName = channels.find { it.id == user.channelId }?.name?.let(::displayChannelName),
            avatar = user.uid?.let { userAvatars[it] },
            isLocallyMuted = user.id in mutedUserIds,
            isFriend = user.uid != null && user.uid in friendUids,
            onToggleFriend = { viewModel.toggleFriend(user.uid) },
            volumePercent = user.uid?.let { userVolumes[it] ?: 100 } ?: 100,
            onVolumeChange = { pct -> viewModel.setUserVolume(user.uid, pct) },
            onOpenChat = {
                clientInfoTarget = null
                pmPeerKey = viewModel.peerKeyFor(user)
                chatTab = 1
                chatOpen = true
            },
            onToggleMute = {
                viewModel.toggleMuteUser(user.id)
                clientInfoTarget = null
            },
            onToggleWhisper = {
                viewModel.toggleWhisper(user.id)
                clientInfoTarget = null
            },
            onDismiss = { clientInfoTarget = null },
        )
    }

    // Channel info dialog (long-press on a channel)
    channelInfoTarget?.let { channel ->
        val myClientId = viewModel.myClientId
        ChannelInfoDialog(
            channel = channel,
            userCount = users.count { it.channelId == channel.id },
            isCurrentChannel = users.find { it.id == myClientId }?.channelId == channel.id,
            onSwitch = {
                viewModel.moveToChannel(channel.id)
                channelInfoTarget = null
            },
            onDismiss = { channelInfoTarget = null },
        )
    }

    // Server info dialog (tap server name / info button)
    serverInfo?.let { info ->
        if (showServerInfo) {
            ServerInfoDialog(
                serverInfo = info,
                onDismiss = { showServerInfo = false },
            )
        }
    }

    // Image preview overlay
    if (previewImageBytes != null) {
        Dialog(onDismissRequest = { viewModel.closePreview() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .clipToBounds()
                    .clickable { viewModel.closePreview() },
                contentAlignment = Alignment.Center,
            ) {
                previewImageBytes?.let { bytes ->
                    val bitmap = remember(bytes) {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = previewImageName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    chatTab: Int,
    onTabChange: (Int) -> Unit,
    channelMessages: List<ChatMessage>,
    privateMessages: List<ChatMessage>,
    messageText: String,
    onMessageChange: (String) -> Unit,
    sendStatus: ChatSendStatus,
    pmTarget: User?,
    selectedPmPeerKey: String?,
    pmConversationUsers: List<Pair<String, String>>,
    onSelectPmUser: (String) -> Unit,
    onClearPmTarget: () -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
    unreadChannel: Int,
    unreadPrivateTotal: Int,
    unreadPrivatePerUser: Map<String, Int>,
    showLinkThumbnails: Boolean,
    autoLoadImages: Boolean = true,
    canUploadFiles: Boolean = true,
    onBeginUpload: () -> Boolean = { false },
    onFilePicked: (android.net.Uri?) -> Unit = {},
    onDownload: ((FileAttachment, Boolean) -> StateFlow<DownloadState>)? = null,
    isWhisperActive: Boolean = false,
    isTextWhisper: Boolean = false,
    whisperTargetName: String? = null,
) {
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = onFilePicked,
    )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .padding(8.dp),
        ) {
            // Header: tabs + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabRow(
                    selectedTabIndex = chatTab,
                    modifier = Modifier.weight(1f),
                    containerColor = Color.Transparent,
                ) {
                    Tab(
                        selected = chatTab == 0,
                        onClick = { onTabChange(0) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.tab_channel))
                                if (unreadChannel > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Badge { Text("$unreadChannel") }
                                }
                            }
                        },
                    )
                    Tab(
                        selected = chatTab == 1,
                        onClick = { onTabChange(1) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.tab_private))
                                if (unreadPrivateTotal > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Badge { Text("$unreadPrivateTotal") }
                                }
                            }
                        },
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }

            // PM conversation selector
            if (chatTab == 1 && pmConversationUsers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // "All" chip
                    FilterChip(
                        selected = selectedPmPeerKey == null,
                        onClick = { onClearPmTarget() },
                        label = { Text(stringResource(R.string.filter_all)) },
                        leadingIcon = if (selectedPmPeerKey == null) {
                            { Icon(Icons.Default.ChatBubble, null, Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    // One chip per conversation user
                    pmConversationUsers.forEach { (peerKey, nickname) ->
                        val isSelected = selectedPmPeerKey == peerKey
                        val userUnread = unreadPrivatePerUser[peerKey] ?: 0
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectPmUser(peerKey) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(nickname)
                                    if (userUnread > 0) {
                                        Spacer(Modifier.width(4.dp))
                                        Badge { Text("$userUnread") }
                                    }
                                }
                            },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Person, null, Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }

            // Whisper mode indicator
            if (isWhisperActive && whisperTargetName != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "${stringResource(R.string.whisper)} ${whisperTargetName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (isTextWhisper) {
                                Text(stringResource(R.string.whisper_text_only), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Messages
            val messages = when (chatTab) {
                0 -> channelMessages
                1 -> privateMessages
                else -> emptyList()
            }
            key(chatTab, selectedPmPeerKey) {
                ChatView(
                    messages = messages,
                    showLinkThumbnails = showLinkThumbnails,
                    autoLoadImages = autoLoadImages,
                    onDownload = onDownload,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            val canSend = messageText.isNotBlank() && sendStatus != ChatSendStatus.SENDING &&
                (chatTab == 0 || pmTarget != null || (isWhisperActive && whisperTargetName != null))
            if (sendStatus == ChatSendStatus.FAILED || sendStatus == ChatSendStatus.PARTIAL) {
                Text(
                    text = stringResource(if (sendStatus == ChatSendStatus.PARTIAL)
                        R.string.chat_send_partial else R.string.chat_send_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Message input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (canUploadFiles) {
                    IconButton(
                        onClick = {
                            if (onBeginUpload()) try {
                                filePickerLauncher.launch("*/*")
                            } catch (_: Exception) {
                                onFilePicked(null)
                                android.widget.Toast.makeText(context, R.string.file_selection_failed, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = (chatTab == 0 || pmTarget != null) && !isWhisperActive,
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach_file))
                    }
                }
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                isWhisperActive && whisperTargetName != null ->
                                    "${stringResource(R.string.whisper)} ${whisperTargetName}..."
                                chatTab == 0 -> stringResource(R.string.message_channel_placeholder)
                                else -> stringResource(R.string.message_private_placeholder, pmTarget?.nickname ?: "?")
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    enabled = chatTab == 0 || pmTarget != null || (isWhisperActive && whisperTargetName != null),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
        }
    }
}
