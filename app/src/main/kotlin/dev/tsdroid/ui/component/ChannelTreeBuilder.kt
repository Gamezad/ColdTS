package dev.tsdroid.ui.component

import dev.tslib.Channel
import dev.tslib.User
import java.util.ArrayDeque

internal enum class SpacerAlignment { LEFT, CENTER, RIGHT, REPEAT }

internal data class ChannelSpacer(val alignment: SpacerAlignment, val text: String)

// The number makes a channel name unique; it is not the spacer's height.
private val SPACER_NAME = Regex("""^\s*\[([clr*]?)spacer\d*]""", RegexOption.IGNORE_CASE)

internal fun parseChannelSpacer(name: String): ChannelSpacer? {
    val match = SPACER_NAME.find(name) ?: return null
    val alignment = when (match.groupValues[1].lowercase()) {
        "c" -> SpacerAlignment.CENTER
        "r" -> SpacerAlignment.RIGHT
        "*" -> SpacerAlignment.REPEAT
        else -> SpacerAlignment.LEFT
    }
    return ChannelSpacer(alignment, name.substring(match.range.last + 1))
}

/** For a plain label outside the tree, hide spacer markup there as well. */
internal fun displayChannelName(name: String): String = parseChannelSpacer(name)?.text?.trim() ?: name

internal sealed class TreeItem {
    data class ChannelNode(val channel: Channel, val depth: Int) : TreeItem()
    data class SpacerNode(val channel: Channel, val depth: Int, val spacer: ChannelSpacer) : TreeItem()
    data class UserNode(val user: User, val depth: Int) : TreeItem()
}

/** TeamSpeak's order field is the ID of the *previous sibling*, not a sort index. */
internal fun orderSiblings(channels: List<Channel>): List<Channel> {
    if (channels.size < 2) return channels
    val input = channels.sortedBy { it.id }
    val byPredecessor = input.groupBy { it.order.toLong() and 0xffffffffL }
    val siblingIds = input.mapTo(HashSet()) { it.id }
    val visited = HashSet<Long>()
    val result = ArrayList<Channel>(input.size)

    fun appendChain(first: Channel) {
        var current: Channel? = first
        while (current != null && visited.add(current.id)) {
            result.add(current)
            current = byPredecessor[current.id]?.firstOrNull { it.id !in visited }
        }
    }

    // A well-formed list begins after ID 0. Keep all branches if a server sends
    // duplicate predecessor links, and preserve chains with a missing predecessor.
    byPredecessor[0L].orEmpty().forEach(::appendChain)
    input.filter { (it.order.toLong() and 0xffffffffL) !in siblingIds }.forEach(::appendChain)
    input.forEach(::appendChain) // cycles and other malformed links
    return result
}

/** Flatten the complete hierarchy while retaining orphans and guarding against cycles. */
internal fun buildTreeItems(channels: List<Channel>, users: List<User>): List<TreeItem> {
    // JNI arrays can contain null entries despite their platform types.
    val safeChannels = channels.filterNotNull().distinctBy { it.id }
    if (safeChannels.isEmpty()) return emptyList()
    val safeUsers = users.filterNotNull().distinctBy { it.id }
    val knownIds = safeChannels.mapTo(HashSet()) { it.id }
    val children = safeChannels.groupBy { it.parentId }.mapValues { orderSiblings(it.value) }
    val usersByChannel = safeUsers.groupBy { it.channelId }
    val roots = children[0L].orEmpty() + children.keys
        .filter { it != 0L && it !in knownIds }
        .sorted()
        .flatMap { children[it].orEmpty() }

    val result = ArrayList<TreeItem>(safeChannels.size + safeUsers.size)
    val visited = HashSet<Long>()
    val stack = ArrayDeque<Pair<Channel, Int>>()

    fun appendTree(root: Channel) {
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (channel, depth) = stack.removeLast()
            if (!visited.add(channel.id)) continue
            val spacer = parseChannelSpacer(channel.name)
            result.add(if (spacer == null) TreeItem.ChannelNode(channel, depth)
                else TreeItem.SpacerNode(channel, depth, spacer))
            usersByChannel[channel.id].orEmpty()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nickname ?: "" })
                .forEach { result.add(TreeItem.UserNode(it, depth + 1)) }
            children[channel.id].orEmpty().asReversed().forEach { stack.addLast(it to depth + 1) }
        }
    }

    roots.forEach(::appendTree)
    // In a cyclic/broken hierarchy there may be no root at all. Still show the
    // channel and its users/children rather than silently dropping them.
    safeChannels.sortedBy { it.id }.forEach { if (it.id !in visited) appendTree(it) }
    return result
}
