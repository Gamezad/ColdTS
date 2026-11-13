package dev.tsdroid.ui.component

import dev.tslib.Channel
import dev.tslib.User
import org.junit.Assert.*
import org.junit.Test

class ChannelTreeBuilderTest {
    private fun channel(id: Long, parent: Long = 0, order: Int = 0, name: String = "ch$id") =
        Channel(id, parent, name, null, null, order, true, false, false, false,
            4, 7, -1, -1, 0, 0, 0)

    private fun user(id: Int, channelId: Long) = User(
        id, "uid$id", 1, channelId, "User$id", 0, false, false, false, true, true,
        false, false, false, false, false, 0, null, longArrayOf(), 1,
        "Android", "1", null, null, null, 0,
    )

    private fun channelIds(items: List<TreeItem>) = items.mapNotNull {
        when (it) {
            is TreeItem.ChannelNode -> it.channel.id
            is TreeItem.SpacerNode -> it.channel.id
            is TreeItem.UserNode -> null
        }
    }

    @Test fun parsesTeamSpeakSpacers() {
        assertEquals(ChannelSpacer(SpacerAlignment.LEFT, ""), parseChannelSpacer("[spacer0]"))
        assertEquals(ChannelSpacer(SpacerAlignment.CENTER, " Title"), parseChannelSpacer("[cSpAcEr123] Title"))
        assertEquals(SpacerAlignment.RIGHT, parseChannelSpacer("[rspacer]Right")?.alignment)
        assertEquals(SpacerAlignment.LEFT, parseChannelSpacer("[lspacer]Left")?.alignment)
        assertEquals(ChannelSpacer(SpacerAlignment.REPEAT, " - "), parseChannelSpacer("[*spacer99] - "))
        assertNull(parseChannelSpacer("Room [spacer1]"))
        assertNull(parseChannelSpacer("[spacer-x]"))
        assertEquals("Title", displayChannelName("[cspacer0] Title "))
        assertEquals("", displayChannelName("[spacer]"))
    }

    @Test fun followsPreviousSiblingLinksAndIncludesBrokenHierarchy() {
        val channels = listOf(
            channel(3, order = 7), channel(7, order = 50), channel(50),
            channel(102, parent = 50, order = 101), channel(101, parent = 50),
            channel(200, parent = 999), channel(301, parent = 302), channel(302, parent = 301),
            channel(400, name = "[cspacer0]Title"),
        )
        val items = buildTreeItems(channels, listOf(user(5, 302)))
        assertEquals(listOf(50L, 101L, 102L, 7L, 3L, 400L, 200L, 301L, 302L), channelIds(items))
        assertEquals(1, items.count { it is TreeItem.SpacerNode })
        assertEquals(1, items.count { it is TreeItem.UserNode })
    }

    @Test fun preservesDuplicateAndMissingPredecessorChains() {
        val siblings = listOf(channel(9), channel(2), channel(7, order = 2),
            channel(8, order = 777), channel(6, order = 8))
        assertEquals(listOf(2L, 7L, 9L, 8L, 6L), orderSiblings(siblings).map { it.id })
    }

    @Test fun kotlinTreeTreatsSignedPredecessorAsUnsignedId() {
        val high = 2_147_483_649L
        assertEquals(listOf(high, 4L), orderSiblings(listOf(channel(4, order = high.toInt()), channel(high))).map { it.id })
    }

    @Test fun javaTreeIgnoresDuplicateChannelIds() {
        val tree = dev.tslib.ChannelTree.fromChannels(arrayOf(channel(1), channel(1, parent = 1), channel(2, parent = 1)))
        try {
            assertEquals(listOf(1L), tree.roots.map { it.id })
            assertEquals(listOf(2L), tree.getChildren(1).map { it.id })
            assertEquals(listOf(1L, 2L), tree.pathTo(2).map { it.id })
        } finally { tree.close() }
    }

    @Test fun javaChannelTreeUsesTheSameUnsignedLinkedOrder() {
        val high = 2_147_483_649L
        val roots = arrayOf(channel(4, order = high.toInt()), channel(high), channel(7, order = 999))
        val tree = dev.tslib.ChannelTree.fromChannels(roots)
        assertEquals(listOf(high, 4L, 7L), tree.roots.map { it.id })
        assertTrue(tree.printTree().indexOf("ch$high") < tree.printTree().indexOf("ch4"))
        tree.close()

        val cycle = dev.tslib.ChannelTree.fromChannels(arrayOf(channel(1, parent = 2), channel(2, parent = 1)))
        assertEquals(2, cycle.pathTo(1).size)
        cycle.close()
    }

    @Test fun deepTreesDoNotOverflowOrLoseChannels() {
        val channels = (1..2500).map { channel(it.toLong(), if (it == 1) 0 else (it - 1).toLong()) }
        assertEquals(2500, channelIds(buildTreeItems(channels, emptyList())).size)
    }
}
