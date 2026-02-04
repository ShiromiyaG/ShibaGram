package com.shirou.shibagram.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.domain.model.ChatFolder
import com.shirou.shibagram.domain.model.ViewingMode
import kotlinx.coroutines.launch

/**
 * Navigation rail for desktop layout.
 */
@Composable
fun ShibaGramNavigationRail(
    selectedIndex: Int,
    onIndexSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        NavigationRailItem(
            selected = selectedIndex == 0,
            onClick = { onIndexSelected(0) },
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("Home") }
        )
        
        NavigationRailItem(
            selected = selectedIndex == 1,
            onClick = { onIndexSelected(1) },
            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Channels") },
            label = { Text("Channels") }
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        NavigationRailItem(
            selected = selectedIndex == 2,
            onClick = { onIndexSelected(2) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") }
        )
    }
}

/**
 * Top app bar for desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShibaGramTopBar(
    title: String,
    onSearchClick: () -> Unit,
    onViewModeChange: (ViewingMode) -> Unit,
    currentViewMode: ViewingMode,
    modifier: Modifier = Modifier
) {
    var showViewModeMenu by remember { mutableStateOf(false) }
    
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        modifier = modifier,
        actions = {
            // Search
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search"
                )
            }
            
            // View mode toggle
            Box {
                IconButton(onClick = { showViewModeMenu = true }) {
                    Icon(
                        imageVector = when (currentViewMode) {
                            ViewingMode.GRID -> Icons.Default.GridView
                            ViewingMode.LIST -> Icons.Default.ViewList
                            ViewingMode.COMPACT -> Icons.Default.ViewCompact
                        },
                        contentDescription = "View mode"
                    )
                }
                
                DropdownMenu(
                    expanded = showViewModeMenu,
                    onDismissRequest = { showViewModeMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Grid") },
                        onClick = {
                            onViewModeChange(ViewingMode.GRID)
                            showViewModeMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.GridView, contentDescription = null)
                        },
                        trailingIcon = {
                            if (currentViewMode == ViewingMode.GRID) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("List") },
                        onClick = {
                            onViewModeChange(ViewingMode.LIST)
                            showViewModeMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ViewList, contentDescription = null)
                        },
                        trailingIcon = {
                            if (currentViewMode == ViewingMode.LIST) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Compact") },
                        onClick = {
                            onViewModeChange(ViewingMode.COMPACT)
                            showViewModeMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ViewCompact, contentDescription = null)
                        },
                        trailingIcon = {
                            if (currentViewMode == ViewingMode.COMPACT) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Search bar component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search videos..."
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            } else {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
        ),
        shape = MaterialTheme.shapes.extraLarge
    )
}

/**
 * Folder tabs component for filtering channels by Telegram folders.
 * Supports drag scrolling with mouse.
 */
@Composable
fun FolderTabs(
    folders: List<ChatFolder>,
    selectedFolderId: Int?,
    onFolderSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var dragOffset by remember { mutableStateOf(0f) }
    
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount.x
                    coroutineScope.launch {
                        listState.scrollBy(-dragAmount.x)
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // "All" tab
        item {
            FilterChip(
                selected = selectedFolderId == null,
                onClick = { onFolderSelect(null) },
                label = { Text("All Chats") },
                leadingIcon = if (selectedFolderId == null) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp)) }
                }
            )
        }
        
        // Folder tabs
        items(folders) { folder ->
            FilterChip(
                selected = selectedFolderId == folder.id,
                onClick = { onFolderSelect(folder.id) },
                label = { Text(folder.title) },
                leadingIcon = if (selectedFolderId == folder.id) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    { Icon(getFolderIcon(folder.icon), contentDescription = null, modifier = Modifier.size(18.dp)) }
                }
            )
        }
    }
}

/**
 * Get icon for folder based on Telegram folder icon name.
 */
@Composable
private fun getFolderIcon(iconName: String?): androidx.compose.ui.graphics.vector.ImageVector {
    return when (iconName?.lowercase()) {
        "all" -> Icons.Default.List
        "unread" -> Icons.Default.MarkUnreadChatAlt
        "unmuted" -> Icons.Default.VolumeUp
        "bots" -> Icons.Default.SmartToy
        "channels" -> Icons.Default.Campaign
        "groups" -> Icons.Default.Group
        "private" -> Icons.Default.Person
        "custom" -> Icons.Default.Folder
        "setup" -> Icons.Default.Settings
        "cat" -> Icons.Default.Pets
        "crown" -> Icons.Default.Star
        "favorite" -> Icons.Default.Favorite
        "flower" -> Icons.Default.LocalFlorist
        "game" -> Icons.Default.SportsEsports
        "home" -> Icons.Default.Home
        "love" -> Icons.Default.FavoriteBorder
        "mask" -> Icons.Default.TheaterComedy
        "party" -> Icons.Default.Celebration
        "sport" -> Icons.Default.SportsSoccer
        "study" -> Icons.Default.School
        "trade" -> Icons.Default.TrendingUp
        "travel" -> Icons.Default.Flight
        "work" -> Icons.Default.Work
        else -> Icons.Default.Folder
    }
}

/**
 * Empty state component.
 */
@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

/**
 * Loading indicator.
 */
@Composable
fun LoadingContent(
    modifier: Modifier = Modifier,
    message: String = "Loading..."
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
