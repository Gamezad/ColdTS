package dev.tsdroid.ui.component

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.tslib.Channel
import dev.tslib.User

sealed class TreeItem {
    data class ChannelNode(val channel: Channel, val depth: Int) : TreeItem()
    data class UserNode(val user: User, val depth: Int) : TreeItem()
}

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
    // Filter nulls early — JNI arrays can contain null elements
    @Suppress("USELESS_CAST")
    val safeUsers = remember(users) { users.filterNotNull() }
    val treeItems = remember(channels, safeUsers) {
        val items = buildTreeItems(channels, safeUsers)
        Log.d("ChannelTree", "Built ${items.size} tree items (${items.count { it is TreeItem.ChannelNode }} channels, ${items.count { it is TreeItem.UserNode }} users) from ${channels.size} channels, ${safeUsers.size} users")
        items
    }
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
                is TreeItem.UserNode -> UserItem(
                    user = item.user,
                    avatar = item.user.uid?.let { userAvatars[it] },
                    modifier = Modifier.fillMaxWidth().padding(start = (item.depth * 24 + 32).dp),
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
                start = (depth * 24).dp,
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

private fun buildTreeItems(channels: List<Channel>, users: List<User>): List<TreeItem> {
    if (channels.isEmpty()) return emptyList()

    // Filter out any null elements that may come from JNI
    @Suppress("USELESS_CAST")
    val safeChannels = channels.filterNotNull()
    @Suppress("USELESS_CAST")
    val safeUsers = users.filterNotNull()
    if (safeChannels.isEmpty()) return emptyList()

    val byParent = safeChannels.groupBy { it.parentId }
    val usersByChannel = safeUsers.groupBy { it.channelId }
    val knownIds = safeChannels.map { it.id }.toHashSet()

    // Roots are top-level channels (parentId == 0) PLUS any orphan whose parent
    // is missing from the server list — previously those were silently dropped,
    // which is why not all channels were shown. Sorted by server order.
    val roots = safeChannels
        .filter { it.parentId == 0L || it.parentId !in knownIds }
        .sortedWith(compareBy({ it.order }, { it.id }))

    val items = mutableListOf<TreeItem>()
    val visited = HashSet<Long>()

    fun addChannel(channel: Channel, depth: Int) {
        if (!visited.add(channel.id)) return  // cycle guard
        items.add(TreeItem.ChannelNode(channel, depth))
        usersByChannel[channel.id]?.forEach { user ->
            items.add(TreeItem.UserNode(user, depth + 1))
        }
        byParent[channel.id]
            ?.sortedWith(compareBy({ it.order }, { it.id }))
            ?.forEach { child -> addChannel(child, depth + 1) }
    }

    roots.forEach { addChannel(it, 0) }

    // Guarantee: never hide a channel the server reported — append any channel
    // the hierarchy walk did not reach (broken/looped parent links) at root level.
    safeChannels
        .filter { it.id !in visited }
        .sortedWith(compareBy({ it.order }, { it.id }))
        .forEach { items.add(TreeItem.ChannelNode(it, 0)) }

    Log.d(
        "ChannelTree",
        "Built ${items.size} items (${items.count { it is TreeItem.ChannelNode }} channels, " +
            "${items.count { it is TreeItem.UserNode }} users) from ${safeChannels.size} channels, ${safeUsers.size} users"
    )
    return items
}
