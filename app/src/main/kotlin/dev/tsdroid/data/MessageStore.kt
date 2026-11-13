package dev.tsdroid.data

import android.content.Context
import android.util.Log
import dev.tsdroid.viewmodel.ChatMessage
import dev.tsdroid.viewmodel.FileAttachment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MessageStore(private val context: Context) {
    private val messagesDir = File(context.filesDir, "messages")
    private val filesNeedingBackup = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun load(serverAddress: String): Pair<List<ChatMessage>, Map<String, List<ChatMessage>>> {
        val file = fileFor(serverAddress)
        if (!file.exists()) return emptyList<ChatMessage>() to emptyMap()
        return try {
            MessageCodec.decode(file.readText()).also { filesNeedingBackup.remove(file.absolutePath) }
        } catch (e: Exception) {
            Log.e("MessageStore", "Failed to load messages for $serverAddress", e)
            // Never replace an unreadable history until a backup was secured.
            if (backupUnreadableFile(file)) filesNeedingBackup.remove(file.absolutePath)
            else filesNeedingBackup.add(file.absolutePath)
            emptyList<ChatMessage>() to emptyMap()
        }
    }

    fun save(
        serverAddress: String,
        channelMessages: List<ChatMessage>,
        privateMessages: Map<String, List<ChatMessage>>,
    ) {
        try {
            messagesDir.mkdirs()
            val file = fileFor(serverAddress)
            if (file.absolutePath in filesNeedingBackup) {
                if (file.exists() && !backupUnreadableFile(file)) return
                filesNeedingBackup.remove(file.absolutePath)
            }
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(MessageCodec.encode(channelMessages, privateMessages))
            if (!temporary.renameTo(file)) {
                // Keep the original rather than risking a partial overwrite.
                Log.w("MessageStore", "Failed to replace saved history for $serverAddress")
                temporary.delete()
            }
        } catch (e: Exception) {
            Log.e("MessageStore", "Failed to save messages for $serverAddress", e)
        }
    }

    private fun backupUnreadableFile(file: File): Boolean = try {
        file.copyTo(File(file.parentFile, file.name + ".invalid-" + UUID.randomUUID()))
        true
    } catch (e: Exception) {
        Log.w("MessageStore", "Failed to back up unreadable history", e)
        false
    }

    private fun fileFor(address: String) =
        File(messagesDir, address.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json")
}

/** Also reads the previous format, whose private conversations used temporary client IDs. */
internal object MessageCodec {
    private const val MAX_MESSAGES = 500

    fun encode(channel: List<ChatMessage>, privateMessages: Map<String, List<ChatMessage>>): String {
        val privateJson = JSONObject()
        privateMessages.forEach { (peer, messages) -> privateJson.put(peer, encodeMessages(messages)) }
        return JSONObject().put("channel", encodeMessages(channel)).put("private", privateJson).toString()
    }

    fun decode(json: String): Pair<List<ChatMessage>, Map<String, List<ChatMessage>>> {
        if (json.isBlank()) return emptyList<ChatMessage>() to emptyMap()
        val root = JSONObject(escapeLegacyControls(json))
        val channelArray = root.optJSONArray("channel") ?: throw IllegalArgumentException("Invalid channel history")
        val privateJson = root.optJSONObject("private") ?: throw IllegalArgumentException("Invalid private history")
        val channel = decodeMessages(channelArray, false)
        val privateMessages = mutableMapOf<String, List<ChatMessage>>()
        val keys = privateJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // Old numeric IDs can be reused by a different person after
            // reconnecting. Keep the history, but never use that ID to send.
            val safeKey = if (key.toIntOrNull() != null) "legacy:$key" else key
            val messages = privateJson.optJSONArray(key) ?: throw IllegalArgumentException("Invalid private conversation")
            if (privateMessages.containsKey(safeKey)) throw IllegalArgumentException("Conflicting private conversation keys")
            privateMessages[safeKey] = decodeMessages(messages, true)
        }
        return channel to privateMessages
    }

    // The old hand-written serializer left literal control characters (e.g.
    // tabs) in JSON strings. Quote them without altering existing escapes.
    private fun escapeLegacyControls(json: String): String = buildString(json.length) {
        var inString = false
        var escaped = false
        for (c in json) {
            if (!inString) {
                append(c)
                if (c == '"') inString = true
            } else if (escaped) {
                append(c)
                escaped = false
            } else when {
                c == '\\' -> { append(c); escaped = true }
                c == '"' -> { append(c); inString = false }
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    private fun encodeMessages(messages: List<ChatMessage>): JSONArray = JSONArray().apply {
        messages.takeLast(MAX_MESSAGES).forEach { msg ->
            val obj = JSONObject()
                .put("id", msg.id).put("s", msg.sender).put("t", msg.text)
                .put("ts", msg.timestamp).put("me", msg.isMe).put("sid", msg.senderId)
            msg.peerName?.let { obj.put("peer", it) }
            msg.fileAttachment?.let { file ->
                obj.put("fa", JSONObject().put("fn", file.fileName).put("fs", file.fileSize)
                    .put("fi", file.fileId).put("im", file.isImage).put("ch", file.channelId))
            }
            put(obj)
        }
    }

    private fun decodeMessages(array: JSONArray?, isPrivate: Boolean): List<ChatMessage> {
        if (array == null) return emptyList()
        val messages = ArrayList<ChatMessage>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: throw IllegalArgumentException("Invalid history message")
            if (obj.opt("s") !is String || obj.opt("t") !is String) {
                throw IllegalArgumentException("Invalid message fields")
            }
            if (obj.has("fa") && !obj.isNull("fa") && obj.optJSONObject("fa") == null) {
                throw IllegalArgumentException("Invalid attachment")
            }
            val fa = obj.optJSONObject("fa")?.let { file ->
                if (file.opt("fn") !is String || (file.has("fi") && file.opt("fi") !is String)) {
                    throw IllegalArgumentException("Invalid attachment fields")
                } else FileAttachment(
                    fileName = file.optString("fn", ""),
                    fileSize = file.optLong("fs", 0L),
                    fileId = file.optString("fi", ""),
                    isImage = file.optBoolean("im", false),
                    channelId = file.optLong("ch", 0L),
                )
            }
            messages.add(ChatMessage(
                id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                sender = obj.optString("s", ""),
                text = obj.optString("t", ""),
                timestamp = obj.optLong("ts", System.currentTimeMillis()),
                isMe = obj.optBoolean("me", false),
                isPrivate = isPrivate,
                senderId = obj.optInt("sid", 0),
                peerName = obj.optString("peer", "").takeIf { it.isNotBlank() },
                fileAttachment = fa,
            ))
        }
        return messages
    }
}
