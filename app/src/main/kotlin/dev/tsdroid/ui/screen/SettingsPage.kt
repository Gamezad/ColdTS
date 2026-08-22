package dev.tsdroid.ui.screen

import android.app.Activity
import android.content.pm.PackageManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.han.R
import kotlinx.coroutines.launch

@Composable
fun SettingsPage(
    onNavigateToAbout: () -> Unit,
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val showLinkThumbnails by settingsStore.showLinkThumbnails.collectAsStateWithLifecycle(initialValue = false)
    val autoLoadImages by settingsStore.autoLoadImages.collectAsStateWithLifecycle(initialValue = true)
    val enableFloatingWindow by settingsStore.enableFloatingWindow.collectAsStateWithLifecycle(initialValue = false)
    val noiseSuppression by settingsStore.noiseSuppression.collectAsStateWithLifecycle(initialValue = true)
    val audioGain by settingsStore.audioGain.collectAsStateWithLifecycle(initialValue = 1.0f)

    val languageOptions = listOf(
        "system" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_english),
        "fa" to stringResource(R.string.language_persian),
        "zh" to stringResource(R.string.language_simplified_chinese),
        "fr" to stringResource(R.string.language_french),
    )
    val selectedLanguageTag by settingsStore.language.collectAsStateWithLifecycle(initialValue = "system")
    val selectedLanguageLabel = languageOptions.firstOrNull { it.first == selectedLanguageTag }?.second
        ?: stringResource(R.string.language_system)
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var pendingLanguageTag by remember { mutableStateOf<String?>(null) }
    val activity = context as? Activity

    pendingLanguageTag?.let { languageTag ->
        val label = languageOptions.firstOrNull { it.first == languageTag }?.second ?: languageTag
        AlertDialog(
            onDismissRequest = { pendingLanguageTag = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.language_change_title)) },
            text = { Text(stringResource(R.string.language_change_message, label)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        settingsStore.setLanguage(languageTag)
                        activity?.recreate()
                    }
                    pendingLanguageTag = null
                }) {
                    Text(stringResource(R.string.restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguageTag = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Language
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { languageMenuExpanded = true }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.language_change_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                Text(
                    text = selectedLanguageLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false },
                ) {
                    languageOptions.forEach { (tag, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                pendingLanguageTag = tag
                                languageMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Auto reconnect
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.auto_reconnect),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoReconnect,
                onCheckedChange = onAutoReconnectChange,
            )
        }

        // Audio gain
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "${stringResource(R.string.audio_gain)} : ${stringResource(R.string.audio_gain_value, audioGain)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Slider(
                value = audioGain,
                onValueChange = { scope.launch { settingsStore.setAudioGain(it) } },
                valueRange = 1.0f..8.0f,
                steps = 13,
            )
        }

        // Show link thumbnails
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.show_link_thumbnails),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = showLinkThumbnails,
                onCheckedChange = { scope.launch { settingsStore.setShowLinkThumbnails(it) } },
            )
        }

        // Auto load images
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.auto_load_images),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoLoadImages,
                onCheckedChange = { scope.launch { settingsStore.setAutoLoadImages(it) } },
            )
        }

        // Enable floating window
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.enable_floating_window),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enableFloatingWindow,
                onCheckedChange = { scope.launch { settingsStore.setEnableFloatingWindow(it) } },
            )
        }

        // Custom wallpaper: user-picked image + adjustable opacity
        val wallpaperPath by settingsStore.wallpaperPath.collectAsStateWithLifecycle(initialValue = "")
        val wallpaperOpacity by settingsStore.wallpaperOpacity.collectAsStateWithLifecycle(initialValue = 0.35f)
        val wallpaperPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                scope.launch { settingsStore.setWallpaperFromUri(uri) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { wallpaperPicker.launch(arrayOf("image/*")) }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.wallpaper_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (wallpaperPath.isBlank()) stringResource(R.string.wallpaper_choose)
                else stringResource(R.string.wallpaper_change),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (wallpaperPath.isNotBlank()) {
            // Opacity slider
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "${stringResource(R.string.wallpaper_opacity)} : ${stringResource(R.string.percent_format, (wallpaperOpacity * 100).toInt())}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = wallpaperOpacity,
                    onValueChange = { scope.launch { settingsStore.setWallpaperOpacity(it) } },
                    valueRange = 0f..1f,
                )
            }

            // Remove wallpaper
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { settingsStore.clearWallpaper() } }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.wallpaper_remove),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Noise suppression
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.noise_suppression),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = noiseSuppression,
                onCheckedChange = { scope.launch { settingsStore.setNoiseSuppression(it) } },
            )
        }

        // About
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToAbout() }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.about_software),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(32.dp))

        // Version info + update check
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var updateInfo by remember { mutableStateOf<dev.tsdroid.update.UpdateInfo?>(null) }
        var updateError by remember { mutableStateOf<String?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var isLatestVersion by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        var downloadError by remember { mutableStateOf<String?>(null) }

        if (showUpdateDialog && updateInfo != null) {
            AlertDialog(
                onDismissRequest = { if (!isDownloading) { showUpdateDialog = false } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = { Text(stringResource(R.string.update_available, updateInfo!!.versionName)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (isDownloading) {
                            Text(
                                text = "${stringResource(R.string.update_downloading)} ${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (downloadError != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = downloadError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            val changelog = updateInfo!!.changelog
                            val displayText = changelog.take(2000)
                            Text(
                                text = displayText.ifBlank { stringResource(R.string.update_no_changelog) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (changelog.length > 2000) {
                                Text("...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!isDownloading) {
                        FilledTonalButton(onClick = {
                            isDownloading = true
                            downloadError = null
                            downloadProgress = 0f
                            scope.launch {
                                val success = dev.tsdroid.update.InAppUpdater.downloadAndInstall(
                                    context = context,
                                    downloadUrl = updateInfo!!.downloadUrl,
                                    onProgress = { progress ->
                                        downloadProgress = progress.progress
                                        if (progress.state == dev.tsdroid.update.InAppUpdater.DownloadState.FAILED) {
                                            downloadError = progress.error
                                            isDownloading = false
                                        } else if (progress.state == dev.tsdroid.update.InAppUpdater.DownloadState.DONE) {
                                            showUpdateDialog = false
                                        }
                                    }
                                )
                                isDownloading = false
                            }
                        }) {
                            Text(stringResource(R.string.update_download))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(stringResource(R.string.update_later))
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!isCheckingUpdate) {
                        isCheckingUpdate = true
                        isLatestVersion = false
                        updateInfo = null
                        updateError = null
                        scope.launch {
                            val result = dev.tsdroid.update.UpdateChecker.checkForUpdate(versionName, context)
                            updateInfo = result.update
                            updateError = result.error
                            if (result.update != null) {
                                showUpdateDialog = true
                            } else if (result.error != null) {
                                // API failed — keep isLatestVersion false, show error via text
                            } else {
                                isLatestVersion = true
                            }
                            isCheckingUpdate = false
                        }
                    }
                }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stringResource(R.string.app_name)} v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (isCheckingUpdate) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else if (updateInfo != null) {
                Text(
                    text = stringResource(R.string.update_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (updateError != null) {
                Text(
                    text = updateError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (isLatestVersion) {
                Text(
                    text = stringResource(R.string.update_already_latest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.update_check),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
