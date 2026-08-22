package dev.tsdroid.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.tsdroid.data.SettingsStore
import java.io.File

/**
 * ColdTs custom wallpaper: an image picked by the user from their phone,
 * drawn behind the UI with adjustable opacity. The themed (ice-blue)
 * surface shows through according to the opacity setting.
 */
@Composable
fun AppWallpaper() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val wallpaperPath by settingsStore.wallpaperPath.collectAsStateWithLifecycle(initialValue = "")
    val opacity by settingsStore.wallpaperOpacity.collectAsStateWithLifecycle(initialValue = 0.35f)

    if (wallpaperPath.isBlank()) return
    val file = File(wallpaperPath)
    if (!file.exists()) return

    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = opacity.coerceIn(0f, 1f) },
    )
}
