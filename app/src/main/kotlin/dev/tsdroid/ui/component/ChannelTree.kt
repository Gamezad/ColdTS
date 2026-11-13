package dev.tsdroid.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.tslib.Channel
import dev.tslib.User
import kotlin.math.ceil

@Composable
fun ChannelTree(
    channels: List<Channel>,
    users: List<User>,
    onChannelClick: (Long) -> Unit,
    onChannelLongClick: ((Channel) -> Unit)? = null,
    onUserClick: ((User) -> Unit)? = null,
    onUserLongClick: ((User) -> Unit)? = null,
    mutedUserIds: Set<Int> = emptySet(),
    channelIcons: Map<Long, ImageBitmap> = emptyMap(),
    userAvatars: Map<String, ImageBitmap> = emptyMap(),
    onWhisperClick: ((Int) -> Unit)? = null,
    friendUids: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val safeUsers = remember(users) { users.filterNotNull() }
    val treeItems = remember(channels, safeUsers) { buildTreeItems(channels, safeUsers) }
    val userCountByChannel = remember(safeUsers) {
        safeUsers.groupingBy { it.channelId }.eachCount()
    }

    // TeamSpeak channel names are conventionally laid out left-to-right — keep
    // the tree LTR even when the app language is RTL (e.g. Persian).
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
    LazyColumn(modifier = modifier) {
        items(treeItems, key = { item ->
            when (item) {
                is TreeItem.ChannelNode -> "ch_${item.channel.id}"
                is TreeItem.SpacerNode -> "spacer_${item.channel.id}"
                is TreeItem.UserNode -> "usr_${item.user.id}"
            }
        }) { item ->
            when (item) {
                is TreeItem.ChannelNode -> ChannelRow(
                    channel = item.channel,
                    depth = item.depth,
                    userCount = userCountByChannel[item.channel.id] ?: 0,
                    onClick = { onChannelClick(item.channel.id) },
                    onLongClick = onChannelLongClick?.let { handler -> { handler(item.channel) } },
                    icon = channelIcons[item.channel.iconId],
                )
                is TreeItem.SpacerNode -> SpacerRow(item.spacer, item.depth)
                is TreeItem.UserNode -> UserItem(
                    user = item.user,
                    avatar = item.user.uid?.let { userAvatars[it] },
                    modifier = Modifier.fillMaxWidth().padding(start = (item.depth.coerceIn(0, 7) * 20 + 32).dp),
                    onClick = onUserClick?.let { { it(item.user) } },
                    onToggleMute = onUserLongClick?.let { { it(item.user) } },
                    isLocallyMuted = item.user.id in mutedUserIds,
                    onWhisperClick = onWhisperClick,
                    isFriend = item.user.uid != null && item.user.uid in friendUids,
                )
            }
        }
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    depth: Int,
    userCount: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: ImageBitmap? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(
                start = (depth.coerceIn(0, 7) * 20).dp,
                top = 6.dp,
                bottom = 6.dp,
                end = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                if (userCount > 0) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (userCount > 0) {
            Text(
                text = "($userCount)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** Render TS spacer channels as decoration, without room actions, icons or counts. */
@Composable
private fun SpacerRow(spacer: ChannelSpacer, depth: Int) {
    val text = spacer.text
    if (text.isBlank()) {
        Spacer(Modifier.fillMaxWidth().height(12.dp))
        return
    }

    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val modifier = Modifier.fillMaxWidth().padding(start = (depth.coerceIn(0, 7) * 20 + 8).dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
    when (spacer.alignment) {
        SpacerAlignment.CENTER -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.28f))
            Text(text.trim(), Modifier.padding(horizontal = 8.dp), color = color, style = style,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.28f))
        }
        SpacerAlignment.REPEAT -> BoxWithConstraints(modifier.clipToBounds()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val patternWidth = measurer.measure(text, style = style, maxLines = 1).size.width.coerceAtLeast(1)
            val count = ceil(with(density) { maxWidth.toPx() } / patternWidth).toInt().coerceIn(1, 256)
            Text(text.repeat(count), color = color.copy(alpha = 0.8f), style = style,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        else -> Text(
            text = text.trim(),
            modifier = modifier,
            color = color,
            style = style,
            textAlign = if (spacer.alignment == SpacerAlignment.RIGHT) TextAlign.Right else TextAlign.Left,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
