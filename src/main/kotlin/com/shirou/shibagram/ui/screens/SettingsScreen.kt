package com.shirou.shibagram.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.domain.model.PlayerType
import java.io.File
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * Settings screen with Material Design 3.
 */
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onLogoutClick: () -> Unit,
    onClearCacheClick: () -> Unit,
    modifier: Modifier = Modifier,
    autoPlayNext: Boolean = true,
    onAutoPlayNextChange: (Boolean) -> Unit = {},
    downloadPath: String = Paths.get(System.getProperty("user.home"), "Downloads", "ShibaGram").toString(),
    onDownloadPathChange: (String) -> Unit = {},
    maxCacheSizeGb: Float = 2f,
    onMaxCacheSizeChange: (Float) -> Unit = {},
    currentCacheSize: String = "Unknown",
    playerType: PlayerType = PlayerType.VLC,
    onPlayerTypeChange: (PlayerType) -> Unit = {},
    mpvPath: String = "",
    onMpvPathChange: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        // Header — bolder, more spacious
        Spacer(modifier = Modifier.height(20.dp))
        
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Appearance section — in a card
        SettingsSection(title = "Appearance") {
            SettingsItem(
                icon = Icons.Default.DarkMode,
                title = "Dark theme",
                subtitle = if (isDarkTheme) "On" else "Off",
                trailing = {
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = onDarkThemeChange
                    )
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Storage section
        SettingsSection(title = "Storage") {
            SettingsItem(
                icon = Icons.Default.Storage,
                title = if (cacheCleared) "Cache cleared" else "Clear cache",
                subtitle = if (cacheCleared) "All cache and downloads deleted" else "Current cache: $currentCacheSize"
            ,
                onClick = { showClearCacheDialog = true }
            )
            
            // Cache size limit slider
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DataUsage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Max cache size",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Limit: ${String.format("%.1f", maxCacheSizeGb)} GB — Old videos are auto-removed when exceeded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Slider(
                    value = maxCacheSizeGb,
                    onValueChange = onMaxCacheSizeChange,
                    valueRange = 0.5f..20f,
                    steps = 38, // 0.5 GB increments
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 40.dp)
                )
            }
            
            SettingsItem(
                icon = Icons.Default.Folder,
                title = "Download location",
                subtitle = downloadPath,
                onClick = { 
                    // Open folder chooser dialog
                    SwingUtilities.invokeLater {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Select Download Location"
                            currentDirectory = File(downloadPath).let { if (it.exists()) it else File(System.getProperty("user.home")) }
                        }
                        val result = chooser.showOpenDialog(null)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            val selectedFolder = chooser.selectedFile.absolutePath
                            onDownloadPathChange(selectedFolder)
                        }
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Playback section
        SettingsSection(title = "Playback") {
            SettingsItem(
                icon = Icons.Default.Subtitles,
                title = "Auto-play next",
                subtitle = "Automatically play next video in playlist",
                trailing = {
                    Switch(
                        checked = autoPlayNext,
                        onCheckedChange = onAutoPlayNextChange
                    )
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Player section — VLC or mpv selection
        SettingsSection(title = "Player") {
            // Player type toggle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Video player",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when (playerType) {
                                PlayerType.VLC -> "VLC — software rendering, widely compatible"
                                PlayerType.MPV -> "mpv — hardware accelerated, advanced features"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Segmented button row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlayerType.entries.forEach { type ->
                        val selected = playerType == type
                        FilterChip(
                            selected = selected,
                            onClick = { onPlayerTypeChange(type) },
                            label = { Text(type.displayName) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
            
            // MPV path configuration (only shown when mpv is selected)
            if (playerType == PlayerType.MPV) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "mpv path (optional)",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Leave empty to auto-detect from PATH",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = mpvPath,
                        onValueChange = onMpvPathChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Auto-detect") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                SwingUtilities.invokeLater {
                                    val chooser = JFileChooser().apply {
                                        fileSelectionMode = JFileChooser.FILES_ONLY
                                        dialogTitle = "Select mpv.exe"
                                        currentDirectory = File(System.getProperty("user.home"))
                                    }
                                    val result = chooser.showOpenDialog(null)
                                    if (result == JFileChooser.APPROVE_OPTION) {
                                        onMpvPathChange(chooser.selectedFile.absolutePath)
                                    }
                                }
                            }) {
                                Icon(Icons.Default.FolderOpen, "Browse")
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Account section
        SettingsSection(title = "Account") {
            SettingsItem(
                icon = Icons.Default.Logout,
                title = "Log out",
                subtitle = "Sign out of your Telegram account",
                onClick = { showLogoutDialog = true }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // About section
        SettingsSection(title = "About") {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "Version",
                subtitle = "1.0.0"
            )
            
            SettingsItem(
                icon = Icons.Default.Code,
                title = "Open source licenses",
                onClick = { /* Show licenses */ }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
    
    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = {
                Icon(Icons.Default.Logout, contentDescription = null)
            },
            title = { Text("Log out?") },
            text = { Text("You will need to sign in again to access your Telegram channels.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogoutClick()
                    }
                ) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Clear cache confirmation dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = {
                Icon(Icons.Default.Storage, contentDescription = null)
            },
            title = { Text("Clear cache?") },
            text = { Text("This will delete all cached files and downloaded videos. You will need to download videos again to watch them.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        onClearCacheClick()
                        cacheCleared = true
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            trailing?.invoke()
            
            if (onClick != null && trailing == null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}
