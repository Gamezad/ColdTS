package dev.tsdroid.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val KEY_AUDIO_GAIN = floatPreferencesKey("audio_gain")
private val KEY_SHOW_LINK_THUMBNAILS = booleanPreferencesKey("show_link_thumbnails")
private val KEY_AUTO_LOAD_IMAGES = booleanPreferencesKey("auto_load_images")
private val KEY_LANGUAGE = stringPreferencesKey("language")
private val KEY_ENABLE_FLOATING_WINDOW = booleanPreferencesKey("enable_floating_window")
private val KEY_NOISE_SUPPRESSION = booleanPreferencesKey("noise_suppression")
private val KEY_WALLPAPER_PATH = stringPreferencesKey("wallpaper_path")
private val KEY_WALLPAPER_OPACITY = floatPreferencesKey("wallpaper_opacity")
private val KEY_DEFAULT_NICKNAME = stringPreferencesKey("default_nickname")
private val KEY_FRIENDS = stringSetPreferencesKey("friend_uids")

class SettingsStore(private val context: Context) {

    val audioGain: Flow<Float> = context.settingsDataStore.data
        .map { it[KEY_AUDIO_GAIN] ?: 1.0f }

    val showLinkThumbnails: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_SHOW_LINK_THUMBNAILS] ?: false }

    val autoLoadImages: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_AUTO_LOAD_IMAGES] ?: true }

    val language: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_LANGUAGE] ?: "system" }

    val enableFloatingWindow: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_ENABLE_FLOATING_WINDOW] ?: true }

    /** Absolute path of the user-selected wallpaper file ("" = no wallpaper). */
    val wallpaperPath: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_WALLPAPER_PATH] ?: "" }

    /** Visibility of the wallpaper over the themed surface (0f..1f). */
    val wallpaperOpacity: Flow<Float> = context.settingsDataStore.data
        .map { it[KEY_WALLPAPER_OPACITY] ?: 0.35f }

    /**
     * The default nickname: remembered from the last successful connection so
     * the user only has to type it once.
     */
    val defaultNickname: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_DEFAULT_NICKNAME] ?: DEFAULT_NICKNAME }

    /** Unique IDs (uid) of users marked as friends. */
    val friends: Flow<Set<String>> = context.settingsDataStore.data
        .map { it[KEY_FRIENDS] ?: emptySet() }

    suspend fun setAudioGain(gain: Float) {
        context.settingsDataStore.edit { it[KEY_AUDIO_GAIN] = gain }
    }

    suspend fun setShowLinkThumbnails(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_LINK_THUMBNAILS] = enabled }
    }

    suspend fun setAutoLoadImages(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_LOAD_IMAGES] = enabled }
    }

    suspend fun setLanguage(language: String) {
        context.settingsDataStore.edit { it[KEY_LANGUAGE] = language }
    }

    suspend fun setEnableFloatingWindow(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_ENABLE_FLOATING_WINDOW] = enabled }
    }

    /**
     * Copy a user-picked image (SAF uri) into the app's private storage and
     * use it as the wallpaper. The old wallpaper file (if any) is deleted.
     * @return true on success.
     */
    suspend fun setWallpaperFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val oldPath = context.settingsDataStore.data.first()[KEY_WALLPAPER_PATH] ?: ""
            val ext = when (context.contentResolver.getType(uri)) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> ".jpg"
            }
            val dest = File(context.filesDir, "custom_wallpaper$ext")
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
            input.use { src -> dest.outputStream().use { src.copyTo(it) } }
            if (oldPath.isNotBlank() && oldPath != dest.absolutePath) {
                try { File(oldPath).delete() } catch (_: Exception) {}
            }
            context.settingsDataStore.edit { it[KEY_WALLPAPER_PATH] = dest.absolutePath }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Remove the custom wallpaper. */
    suspend fun clearWallpaper() = withContext(Dispatchers.IO) {
        val oldPath = context.settingsDataStore.data.first()[KEY_WALLPAPER_PATH] ?: ""
        if (oldPath.isNotBlank()) {
            try { File(oldPath).delete() } catch (_: Exception) {}
        }
        context.settingsDataStore.edit { it.remove(KEY_WALLPAPER_PATH) }
    }

    suspend fun setWallpaperOpacity(value: Float) {
        context.settingsDataStore.edit { it[KEY_WALLPAPER_OPACITY] = value.coerceIn(0f, 1f) }
    }

    /** Persist the nickname used for the last successful connection. */
    suspend fun setDefaultNickname(nickname: String) {
        if (nickname.isBlank()) return
        context.settingsDataStore.edit { it[KEY_DEFAULT_NICKNAME] = nickname }
    }

    /** Mark/unmark a user (by unique id) as a friend. */
    suspend fun toggleFriend(uid: String) {
        if (uid.isBlank()) return
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_FRIENDS] ?: emptySet()
            prefs[KEY_FRIENDS] = if (uid in current) current - uid else current + uid
        }
    }

    companion object {
        /** Nickname used until the user connects with a custom one. */
        const val DEFAULT_NICKNAME = "ColdTsUser"
    }

    val noiseSuppression: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_NOISE_SUPPRESSION] ?: true }

    suspend fun setNoiseSuppression(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_NOISE_SUPPRESSION] = enabled }
    }
}
