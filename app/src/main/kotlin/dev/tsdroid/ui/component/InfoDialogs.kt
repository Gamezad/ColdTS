package dev.tsdroid.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tsdroid.han.R
import dev.tslib.Channel
import dev.tslib.ServerInfo
import dev.tslib.User

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(116.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun codecName(codec: Byte): String = when (codec.toInt()) {
    0 -> "Speex Narrowband"
    1 -> "Speex Wideband"
    2 -> "Speex Ultra-Wideband"
    3 -> "CELT Mono"
    4 -> "Opus Voice"
    5 -> "Opus Music"
    else -> "Codec #${codec}"
}

/**
 * Classic TeamSpeak client info dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientInfoDialog(
    user: User,
    channelName: String?,
    avatar: ImageBitmap? = null,
    isLocallyMuted: Boolean = false,
    onOpenChat: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleWhisper: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.size(12.dp))
                }
                Column {
                    Text(user.nickname, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.client_info),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Status flags (strings resolved outside buildList — @Composable calls
                // cannot happen inside plain lambdas)
                val strTalking = stringResource(R.string.status_talking)
                val strMicMuted = stringResource(R.string.status_muted)
                val strOutMuted = stringResource(R.string.status_output_muted)
                val strAway = stringResource(R.string.status_away)
                val strRecording = stringResource(R.string.status_recording)
                val strPriority = stringResource(R.string.priority_speaker)
                val strCommander = stringResource(R.string.channel_commander)
                val strQuery = stringResource(R.string.server_query)
                val statuses = buildList {
                    if (user.isTalking) add(strTalking)
                    if (user.isInputMuted || !user.hasInputHardware) add(strMicMuted)
                    if (user.isOutputMuted) add(strOutMuted)
                    if (user.isAway) add(strAway)
                    if (user.isRecording) add(strRecording)
                    if (user.isPrioritySpeaker) add(strPriority)
                    if (user.isChannelCommander) add(strCommander)
                    if (user.isQuery()) add(strQuery)
                }
                if (statuses.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        statuses.take(3).forEach { s ->
                            FilterChip(
                                selected = false,
                                onClick = {},
                                label = { Text(s, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }

                SectionTitle(stringResource(R.string.status_label))
                InfoRow(stringResource(R.string.status_label), statuses.joinToString(" · "))

                SectionTitle(stringResource(R.string.client_info))
                InfoRow(stringResource(R.string.unique_id), user.uid ?: "")
                InfoRow(stringResource(R.string.database_id), user.databaseId.toString())
                InfoRow(stringResource(R.string.current_channel), channelName ?: "")
                InfoRow(stringResource(R.string.talk_power), user.talkPower.toString())
                InfoRow(stringResource(R.string.platform_label), user.platform ?: "")
                InfoRow(stringResource(R.string.version_label), user.version ?: "")
                InfoRow(stringResource(R.string.country_label), user.country ?: "")
                if (!user.serverGroups.isNullOrEmpty()) {
                    InfoRow(
                        stringResource(R.string.server_groups),
                        user.serverGroups.joinToString(", "),
                    )
                }
                InfoRow(stringResource(R.string.channel_group), user.channelGroup.toString())
                InfoRow(stringResource(R.string.description_label), user.description ?: "")
                InfoRow(stringResource(R.string.away_message_label), user.awayMessage ?: "")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onOpenChat) {
                    Icon(
                        Icons.Default.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.open_private_chat))
                }
                TextButton(onClick = onToggleMute) {
                    Icon(
                        if (isLocallyMuted) Icons.AutoMirrored.Filled.VolumeUp
                        else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(
                            if (isLocallyMuted) R.string.unmute_user else R.string.mute_user,
                        )
                    )
                }
                TextButton(onClick = onToggleWhisper) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.whisper_toggle))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/**
 * Classic TeamSpeak channel info dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoDialog(
    channel: Channel,
    userCount: Int,
    isCurrentChannel: Boolean,
    onSwitch: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Column {
                Text(channel.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.channel_info),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val strDefault = stringResource(R.string.channel_default)
                val strPermanent = stringResource(R.string.channel_permanent)
                val strSemiPermanent = stringResource(R.string.channel_semi_permanent)
                val strPassword = stringResource(R.string.channel_password_protected)
                val flags = buildList {
                    if (channel.isDefault) add(strDefault)
                    if (channel.isPermanent) add(strPermanent)
                    if (channel.isSemiPermanent) add(strSemiPermanent)
                    if (channel.hasPassword) add(strPassword)
                }
                if (flags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        flags.forEach { f ->
                            FilterChip(
                                selected = false,
                                onClick = {},
                                label = { Text(f, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                SectionTitle(stringResource(R.string.channel_info))
                InfoRow(stringResource(R.string.topic_label), channel.topic ?: "")
                InfoRow(stringResource(R.string.description_label), channel.description ?: "")
                InfoRow(stringResource(R.string.codec_label), codecName(channel.codec))
                InfoRow(stringResource(R.string.codec_quality_label), channel.codecQuality.toString())
                InfoRow(
                    stringResource(R.string.max_clients_label),
                    if (channel.maxClients < 0) stringResource(R.string.unlimited)
                    else "${channel.maxClients}",
                )
                InfoRow(stringResource(R.string.clients_online), "$userCount")
                InfoRow(stringResource(R.string.needed_talk_power), channel.neededTalkPower.toString())
            }
        },
        confirmButton = {
            if (!isCurrentChannel) {
                TextButton(onClick = onSwitch) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.switch_to_channel))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/**
 * Classic TeamSpeak server info dialog.
 */
@Composable
fun ServerInfoDialog(
    serverInfo: ServerInfo,
    onDismiss: () -> Unit,
) {
    val uptimeSeconds = serverInfo.uptime
    val days = (uptimeSeconds / 86400).toInt()
    val hours = ((uptimeSeconds % 86400) / 3600).toInt()
    val minutes = ((uptimeSeconds % 3600) / 60).toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Column {
                Text(
                    serverInfo.name ?: stringResource(R.string.server),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.server_info),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                InfoRow(stringResource(R.string.version_label), serverInfo.version ?: "")
                InfoRow(stringResource(R.string.platform_label), serverInfo.platform ?: "")
                InfoRow(stringResource(R.string.clients_online), "${serverInfo.clientsOnline} / ${serverInfo.maxClients}")
                InfoRow(stringResource(R.string.channels_count), "${serverInfo.channelsOnline}")
                InfoRow(
                    stringResource(R.string.uptime_label),
                    stringResource(R.string.uptime_days, days, hours, minutes),
                )
                if (!serverInfo.welcomeMessage.isNullOrBlank()) {
                    SectionTitle(stringResource(R.string.welcome_message_label))
                    Text(
                        serverInfo.welcomeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
