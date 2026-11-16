package com.euthopiar.ui.settings.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.euthopiar.data.ota.OtaConfigManager
import com.euthopiar.data.ota.OtaSyncState
import com.euthopiar.data.preferences.PreferencesRepository
import com.euthopiar.data.update.AppUpdateConfig
import com.euthopiar.data.update.AppUpdateManager
import com.euthopiar.data.update.ReleaseInfo
import com.euthopiar.data.update.UpdateCheckState
import com.euthopiar.ui.components.UpdateInfoDialog
import com.euthopiar.ui.settings.components.*
import com.euthopiar.ui.theme.AppFontFamily
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateSettingsPage(
    onBack: () -> Unit,
    onNavigateHome: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember(context) { AppUpdateManager.getInstance(context) }
    val updateState by updateManager.updateState.collectAsState()
    val downloadState by updateManager.downloadState.collectAsState()

    val preferencesRepository = remember(context) { PreferencesRepository.getInstance(context) }
    val autoCheckUpdates by preferencesRepository.autoCheckUpdates.collectAsState(initial = true)
    val includePrereleases by preferencesRepository.includePrereleases.collectAsState(initial = false)
    val lastUpdateCheckTimestamp by preferencesRepository.lastUpdateCheckTimestamp.collectAsState(initial = 0L)

    val currentVersion = remember(context, updateState) { updateManager.getCurrentVersionName() }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var activeDialogRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

    val isChecking = updateState is UpdateCheckState.Checking

    val formattedLastCheckTime = remember(lastUpdateCheckTimestamp) {
        if (lastUpdateCheckTimestamp <= 0L) {
            "Never"
        } else {
            try {
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                sdf.format(Date(lastUpdateCheckTimestamp))
            } catch (_: Exception) {
                "Recent"
            }
        }
    }

    val otaManager = remember(context) { OtaConfigManager.getInstance(context) }
    val otaConfig by otaManager.config.collectAsState()
    val otaSyncState by otaManager.syncState.collectAsState()
    val isOtaSyncing = otaSyncState is OtaSyncState.Syncing

    val formattedOtaLastSync = remember(otaSyncState) {
        when (val state = otaSyncState) {
            is OtaSyncState.Success -> {
                try {
                    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    sdf.format(Date(state.lastSyncTimestamp))
                } catch (_: Exception) {
                    "Just now"
                }
            }
            else -> "Cached"
        }
    }

    val availableRelease = (updateState as? UpdateCheckState.UpdateAvailable)?.release
    val upToDateRelease = (updateState as? UpdateCheckState.UpToDate)?.latestRelease
    val displayRelease = availableRelease ?: upToDateRelease

    LaunchedEffect(updateState) {
        if (updateState is UpdateCheckState.UpdateAvailable) {
            val available = (updateState as UpdateCheckState.UpdateAvailable).release
            activeDialogRelease = available
            showUpdateDialog = true
        }
    }

    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    val dialogRelease = activeDialogRelease ?: availableRelease ?: upToDateRelease

    SettingsScaffold(
        title = "Application Updates",
        onBack = onBack,
        modifier = modifier,
        overlay = { hazeState ->
            if (showUpdateDialog && dialogRelease != null) {
                UpdateInfoDialog(
                    release = dialogRelease,
                    currentVersion = currentVersion,
                    downloadState = downloadState,
                    hazeState = hazeState,
                    onDismiss = { showUpdateDialog = false },
                    onDownloadAndInstall = { rel -> updateManager.downloadAndInstallUpdate(rel) },
                    onTriggerInstall = { file, fallbackUrl -> updateManager.triggerInstallApk(file, fallbackUrl) },
                    onSkipVersion = { ver ->
                        updateManager.skipVersion(ver)
                        showUpdateDialog = false
                    }
                )
            }
        }
    ) { hazeState ->

        // Hero Status Block (Hairline-divided at bottom matching AboutSettingsPage)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 22.dp)
                .drawBehind {
                    drawLine(
                        color = SettingsTokens.HairlineDivider,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = SettingsTokens.HairlineWidth.toPx()
                    )
                }
        ) {
            // Glass Emblem Tile + Headline / Subtitle Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 52x52dp Glass Emblem Tile
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .settingsGlass(
                            shape = RoundedCornerShape(SettingsTokens.RadiusTileEmblem),
                            hazeState = hazeState,
                            blurRadius = 18.dp
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SystemUpdateAlt,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Dynamic Status Column
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val statusHeadline = if (!com.euthopiar.data.update.AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) {
                        "Software updates are temporarily disabled"
                    } else when (val state = updateState) {
                        is UpdateCheckState.Checking -> "Checking for updates..."
                        is UpdateCheckState.UpdateAvailable -> "Update v${state.release.versionName} Available"
                        is UpdateCheckState.UpToDate -> "MOVIE HUB is up to date"
                        is UpdateCheckState.Error -> "Update check failed"
                        is UpdateCheckState.Idle -> "Current Version: v$currentVersion"
                    }

                    Text(
                        text = statusHeadline,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            letterSpacing = 0.sp,
                            color = Color.White
                        )
                    )

                    Text(
                        text = "Installed: v$currentVersion · Last checked: $formattedLastCheckTime",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = SettingsTokens.TextWhite54
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Metadata Chips Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsMetadataChip(
                    text = "v$currentVersion",
                    hazeState = hazeState
                )
                SettingsMetadataChip(
                    text = "Official Manifest",
                    hazeState = hazeState
                )
                SettingsMetadataChip(
                    text = "PackageInstaller",
                    hazeState = hazeState
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons Row inside the Hero Block
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsPillButton(
                    text = if (!com.euthopiar.data.update.AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) "Updates Disabled" else if (isChecking) "Checking..." else "Check for Updates",
                    onClick = {
                        if (com.euthopiar.data.update.AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) {
                            scope.launch {
                                updateManager.checkForUpdates(forceManual = true)
                            }
                        }
                    },
                    isSolidWhite = updateState !is UpdateCheckState.UpdateAvailable,
                    isLoading = isChecking,
                    enabled = com.euthopiar.data.update.AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED && !isChecking,
                    hazeState = hazeState,
                    modifier = Modifier.weight(1f)
                )

                if (availableRelease != null) {
                    SettingsPillButton(
                        text = "View & Install v${availableRelease.versionName}",
                        onClick = {
                            activeDialogRelease = availableRelease
                            showUpdateDialog = true
                        },
                        isSolidWhite = true,
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Section 1: Update Preferences
        OpenSettingsSectionHeader(title = "Update Preferences")

        OpenSettingsToggleRow(
            title = "Automatic Update Checks",
            subtitle = "Check official release endpoint automatically when launching MOVIE HUB",
            checked = autoCheckUpdates,
            onCheckedChange = { enabled ->
                scope.launch {
                    preferencesRepository.setAutoCheckUpdates(enabled)
                }
            },
            hazeState = hazeState
        )

        OpenSettingsToggleRow(
            title = "Receive Pre-Release Updates",
            subtitle = "Include beta and release-candidate builds from GitHub",
            checked = includePrereleases,
            onCheckedChange = { enabled ->
                scope.launch {
                    preferencesRepository.setIncludePrereleases(enabled)
                    updateManager.checkForUpdates(forceManual = true)
                }
            },
            hazeState = hazeState
        )

        // Section 2: Latest Release Information
        OpenSettingsSectionHeader(title = "Latest Release Information")

        if (displayRelease != null) {
            OpenSettingsRow(
                title = "Latest Remote Release",
                subtitle = "${displayRelease.releaseTitle} (${displayRelease.tagName}) · Published ${displayRelease.publishedAt}",
                onClick = {
                    activeDialogRelease = displayRelease
                    showUpdateDialog = true
                },
                trailingContent = {
                    SettingsMetadataChip(
                        text = displayRelease.tagName,
                        hazeState = hazeState
                    )
                }
            )

            val changelogFirstLine = displayRelease.changelog.lines().firstOrNull { it.isNotBlank() }
                ?: "Tap to read full release notes"

            OpenSettingsRow(
                title = "Release Notes & Changelog",
                subtitle = changelogFirstLine,
                onClick = {
                    activeDialogRelease = displayRelease
                    showUpdateDialog = true
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = SettingsTokens.TextWhite42,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )

            OpenSettingsRow(
                title = "Direct APK Asset",
                subtitle = if (displayRelease.apkDownloadUrl != null) {
                    "Universal Release APK available for in-app install"
                } else {
                    "Open release page on GitHub"
                },
                onClick = {
                    activeDialogRelease = displayRelease
                    showUpdateDialog = true
                },
                trailingContent = {
                    SettingsPillButton(
                        text = if (displayRelease.apkDownloadUrl != null) "Download" else "Open",
                        isSolidWhite = false,
                        onClick = {
                            activeDialogRelease = displayRelease
                            showUpdateDialog = true
                        },
                        hazeState = hazeState
                    )
                }
            )
        } else {
            OpenSettingsRow(
                title = "Release Feed",
                subtitle = "Tap 'Check for Updates' above to query release manifest",
                trailingContent = {
                    SettingsMetadataChip(
                        text = "HTTPS Manifest",
                        hazeState = hazeState
                    )
                }
            )
        }

        // Section 3: Over-The-Air (OTA) Remote Configuration
        OpenSettingsSectionHeader(title = "Over-The-Air (OTA) Configuration")

        val otaStatusText = when (otaSyncState) {
            is OtaSyncState.Syncing -> "Syncing remote configuration..."
            is OtaSyncState.Success -> "Configuration active & up to date"
            is OtaSyncState.Error -> "Offline / Sync error (using safe cache)"
            is OtaSyncState.Idle -> "Active (Default Configuration)"
        }

        val otaStatusBadge = when (otaSyncState) {
            is OtaSyncState.Syncing -> "Syncing"
            is OtaSyncState.Success -> "Synced"
            is OtaSyncState.Error -> "Cached"
            is OtaSyncState.Idle -> "Active"
        }

        OpenSettingsRow(
            title = "OTA Status",
            subtitle = otaStatusText,
            trailingContent = {
                SettingsMetadataChip(
                    text = otaStatusBadge,
                    hazeState = hazeState
                )
            }
        )

        OpenSettingsRow(
            title = "Current Configuration Version",
            subtitle = "Config v${otaConfig.configVersion} (${otaConfig.configName}) · Target: MOVIE HUB v${currentVersion}",
            trailingContent = {
                SettingsMetadataChip(
                    text = "v${otaConfig.configVersion}",
                    hazeState = hazeState
                )
            }
        )

        OpenSettingsRow(
            title = "Last Configuration Sync",
            subtitle = formattedOtaLastSync,
            trailingContent = {
                SettingsPillButton(
                    text = if (isOtaSyncing) "Syncing..." else "Sync Now",
                    isLoading = isOtaSyncing,
                    enabled = !isOtaSyncing,
                    isSolidWhite = false,
                    onClick = {
                        scope.launch {
                            otaManager.fetchRemoteConfig(forceManual = true)
                        }
                    },
                    hazeState = hazeState
                )
            }
        )

        if (otaSyncState is OtaSyncState.Error) {
            val errState = otaSyncState as OtaSyncState.Error
            OpenSettingsRow(
                title = "Sync Error State",
                subtitle = "${errState.message} · Tap to retry",
                onClick = {
                    scope.launch {
                        otaManager.fetchRemoteConfig(forceManual = true)
                    }
                },
                trailingContent = {
                    SettingsMetadataChip(
                        text = "Retry",
                        hazeState = hazeState
                    )
                }
            )
        }

        // Section 4: Source & Releases
        OpenSettingsSectionHeader(title = "Source & Releases")

        OpenSettingsRow(
            title = "GitHub Releases Page",
            subtitle = "Browse all historical versions and APKs on GitHub",
            onClick = { openUrl(AppUpdateConfig.GITHUB_RELEASES_URL) },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = SettingsTokens.TextWhite42,
                    modifier = Modifier.size(16.dp)
                )
            }
        )

        // Section 4: Setup & Onboarding
        OpenSettingsSectionHeader(title = "Setup & Onboarding")

        OpenSettingsRow(
            title = "Replay Setup Walkthrough",
            subtitle = "Test the 3-step interactive spotlight guide",
            onClick = {
                onNavigateHome?.invoke() ?: onBack()
                com.euthopiar.ui.walkthrough.WalkthroughCoordinator.instance.start(isFreshInstall = false)
            },
            trailingContent = {
                SettingsMetadataChip(
                    text = "Guide",
                    hazeState = hazeState
                )
            }
        )
    }
}
