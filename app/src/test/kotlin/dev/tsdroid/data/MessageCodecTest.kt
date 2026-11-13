package dev.tsdroid.data

import dev.tsdroid.viewmodel.ChatMessage
import dev.tsdroid.viewmodel.FileAttachment
import org.junit.Assert.*
import org.junit.Test

class MessageCodecTest {
    @Test fun roundTripsEscapesAttachmentsAndStableIds() {
        val text = "Hello\t\"world\"\\trail\nدوست❄"
        val channel = ChatMessage(sender = "Alice", text = text, timestamp = 123, senderId = 19, id = "a")
        val privateMessage = ChatMessage(
            sender = "Me", text = "file", timestamp = 123, isMe = true, isPrivate = true,
            senderId = 99, fileAttachment = FileAttachment("a & b.png", 42, "/folder/a & b.png", true, 77),
            id = "b", peerName = "Alice",
        )
        val encoded = MessageCodec.encode(listOf(channel), mapOf("uid:abc/=" to listOf(privateMessage)))
        val (savedChannel, savedPrivate) = MessageCodec.decode(encoded)
        assertEquals(listOf(channel), savedChannel)
        assertEquals(listOf(privateMessage), savedPrivate["uid:abc/="])
    }

    @Test fun legacyClientIdsAreNeverTreatedAsCurrentRecipients() {
        val json = """{"channel":[{"s":"A","t":"hi","ts":99,"me":false,"sid":42}],"private":{"42":[{"s":"B","t":"there","ts":99,"me":false,"sid":42}]}}"""
        val (channel, privateMessages) = MessageCodec.decode(json)
        assertEquals(42, channel.single().senderId)
        assertEquals(setOf("legacy:42"), privateMessages.keys)
        assertNotEquals(channel.single().id, privateMessages.values.single().single().id)
    }

    @Test fun acceptsLiteralControlCharactersFromLegacySerializer() {
        val json = "{\"channel\":[{\"s\":\"A\",\"t\":\"tab\there\u0001\",\"ts\":7}],\"private\":{}}"
        val (channel, _) = MessageCodec.decode(json)
        assertEquals("tab\there\u0001", channel.single().text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedHistoryShapeInsteadOfSilentlyReplacingIt() {
        MessageCodec.decode("""{"channel":[],"private":"not an object"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedEntriesRatherThanDroppingThem() {
        MessageCodec.decode("""{"channel":[{"s":"A","t":"valid"}, {"s":"B"}],"private":{}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCollidingLegacyConversationKeys() {
        MessageCodec.decode("""{"channel":[],"private":{"42":[],"legacy:42":[]}}""")
    }

    @Test fun legacyAttachmentsAndEscapedBackslashesRemainReadable() {
        val json = """{"channel":[{"s":"A","t":"C:\\dir\\","ts":7,"me":false,"sid":3,"fa":{"fn":"x.png","fs":4,"fi":"/x.png","im":true,"ch":9}}],"private":{}}"""
        val message = MessageCodec.decode(json).first.single()
        assertEquals("C:\\dir\\", message.text)
        assertEquals(FileAttachment("x.png", 4, "/x.png", true, 9), message.fileAttachment)
    }

    @Test fun limitsPersistedMessages() {
        val many = (1..501).map { ChatMessage("S", "$it") }
        val restored = MessageCodec.decode(MessageCodec.encode(many, emptyMap())).first
        assertEquals(500, restored.size)
        assertEquals("2", restored.first().text)
    }
}
