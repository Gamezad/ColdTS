package dev.tsdroid.bridge

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.tslib.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AvatarCache(cacheDir: File) {
    companion object {
        private const val TAG = "AvatarCache"
        private const val RETRY_DELAY_MS = 5 * 60 * 1000L
    }

    private val memoryCache = ConcurrentHashMap<AvatarKey, ImageBitmap>()
    private val loading = ConcurrentHashMap<AvatarKey, CompletableDeferred<ImageBitmap?>>()
    private val failedUntil = ConcurrentHashMap<AvatarKey, Long>()
    private val avatarsDir = File(cacheDir, "avatars").also { it.mkdirs() }

    private fun key(user: User, client: TsClient): AvatarKey? {
        val server = client.serverAddress?.takeIf { it.isNotBlank() } ?: return null
        val uid = user.uid?.takeIf { it.isNotBlank() } ?: return null
        val version = user.avatarId?.takeIf { it.isNotBlank() } ?: return null
        return AvatarKey(server, uid, version)
    }

    fun getAvatar(user: User, client: TsClient): ImageBitmap? = key(user, client)?.let { memoryCache[it] }

    fun hasNoAvatar(user: User, client: TsClient): Boolean = key(user, client)?.let {
        (failedUntil[it] ?: 0L) > System.nanoTime() / 1_000_000L
    } ?: true

    /** Convert a TS3 base64 UID to the nibble-encoded avatar path. */
    fun uidToAvatarPath(base64Uid: String): String {
        val bytes = Base64.decode(base64Uid, Base64.DEFAULT)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            sb.append(('a' + (unsigned shr 4)))
            sb.append(('a' + (unsigned and 0x0F)))
        }
        return "/avatar_$sb"
    }

    suspend fun loadAvatar(user: User, client: TsClient): ImageBitmap? {
        val key = key(user, client) ?: return null
        val session = client.sessionId ?: return null
        if (!client.isConnected) return null
        memoryCache[key]?.let { return it }
        if (hasNoAvatar(user, client)) return null

        // The overlay and the server screen may ask for the same avatar at once.
        val pending = CompletableDeferred<ImageBitmap?>()
        val existing = loading.putIfAbsent(key, pending)
        if (existing != null) return existing.await()

        try {
            val file = File(avatarsDir, key.fileName())
            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                    val image = bitmap.asImageBitmap()
                    memoryCache[key] = image
                    pending.complete(image)
                    return image
                }
                file.delete()
            }

            val bytes = client.downloadFile(0L, uidToAvatarPath(key.uid), automatic = true, expectedSession = session)
            if (client.sessionId != session || !client.isConnected) {
                pending.complete(null)
                return null
            }
            val bitmap = bytes?.takeIf { it.isNotEmpty() }
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bytes == null || bitmap == null) {
                if (client.isConnected && client.sessionId == session) {
                    failedUntil[key] = System.nanoTime() / 1_000_000L + RETRY_DELAY_MS
                }
                pending.complete(null)
                return null
            }
            // Separate cache instances can request the same avatar; readers
            // should never see a partially written disk image.
            val temporary = File(avatarsDir, file.name + "." + UUID.randomUUID() + ".tmp")
            try {
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(file)) Log.w(TAG, "Could not persist avatar for ${key.uid}")
            } finally {
                temporary.delete()
            }
            val image = bitmap.asImageBitmap()
            memoryCache[key] = image
            pending.complete(image)
            return image
        } catch (e: CancellationException) {
            pending.complete(null)
            throw e
        } catch (e: Exception) {
            if (client.isConnected && client.sessionId == session) {
                failedUntil[key] = System.nanoTime() / 1_000_000L + RETRY_DELAY_MS
            }
            Log.w(TAG, "Failed to load avatar for ${key.uid}", e)
            pending.complete(null)
            return null
        } finally {
            loading.remove(key, pending)
        }
    }
}
