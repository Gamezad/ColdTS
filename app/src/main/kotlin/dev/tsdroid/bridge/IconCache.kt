package dev.tsdroid.bridge

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class IconCache(private val cacheDir: File) {

    companion object {
        private const val TAG = "IconCache"
        private const val RETRY_DELAY_MS = 5 * 60 * 1000L
    }

    private val memoryCache = ConcurrentHashMap<Long, ImageBitmap>()
    private val loading = ConcurrentHashMap<Long, Boolean>()
    private val failedUntil = ConcurrentHashMap<Long, Long>()
    private val iconsDir = File(cacheDir, "icons").also { it.mkdirs() }

    fun getIcon(iconId: Long): ImageBitmap? = memoryCache[iconId]

    fun hasCached(iconId: Long): Boolean =
        memoryCache.containsKey(iconId) || File(iconsDir, iconId.toString()).exists()

    suspend fun loadIcon(iconId: Long, tsClient: TsClient) {
        if (iconId == 0L) return
        val session = tsClient.sessionId ?: return
        if (!tsClient.isConnected) return
        if (memoryCache.containsKey(iconId)) return
        if ((failedUntil[iconId] ?: 0L) > System.nanoTime() / 1_000_000L) return
        if (loading.putIfAbsent(iconId, true) != null) return

        try {
            // Try disk cache first
            val diskFile = File(iconsDir, iconId.toString())
            var bytes: ByteArray? = null

            if (diskFile.exists() && diskFile.length() > 0) {
                bytes = diskFile.readBytes()
            } else {
                // Download from server: icons are on channel 0, path /icon_{id}
                val path = "/icon_$iconId"
                bytes = tsClient.downloadFile(0L, path, automatic = true, expectedSession = session)
                if (tsClient.sessionId != session || !tsClient.isConnected) return
                if (bytes != null && bytes.isNotEmpty()) {
                    diskFile.writeBytes(bytes)
                }
            }

            if (tsClient.sessionId != session || !tsClient.isConnected) return
            if (bytes != null && bytes.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    memoryCache[iconId] = bitmap.asImageBitmap()
                } else {
                    Log.w(TAG, "Failed to decode icon $iconId (${bytes.size} bytes)")
                    diskFile.delete()
                    failedUntil[iconId] = System.nanoTime() / 1_000_000L + RETRY_DELAY_MS
                }
            } else {
                failedUntil[iconId] = System.nanoTime() / 1_000_000L + RETRY_DELAY_MS
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (tsClient.sessionId == session && tsClient.isConnected) {
                failedUntil[iconId] = System.nanoTime() / 1_000_000L + RETRY_DELAY_MS
            }
            Log.w(TAG, "Failed to load icon $iconId", e)
        } finally {
            loading.remove(iconId)
        }
    }
}
