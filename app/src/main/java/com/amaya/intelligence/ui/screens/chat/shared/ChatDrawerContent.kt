package com.amaya.intelligence.ui.screens.chat.shared

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.ai.displayName
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.ui.res.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val UNCATEGORIZED_WORKSPACE_KEY = "uncategorized"

// =============================================================================
// Color Tokens - iOS Grouped Style
// =============================================================================

private data class IosDrawerColors(
    val groupedBackground: Color,
    val groupSurface: Color,
    val border: Color,
    val separator: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val headerText: Color,
    val activeIndicator: Color,
    val chevronTint: Color
)

@Composable
private fun iosDrawerColors(isDark: Boolean): IosDrawerColors {
    return if (isDark) {
        IosDrawerColors(
            groupedBackground = Color(0xFF0B0B0F),
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            separator = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            headerText = Color(0xFFEBEBF5).copy(alpha = 0.48f),
            activeIndicator = Color(0xFF0A84FF),
            chevronTint = Color(0xFFC7C7CC).copy(alpha = 0.5f)
        )
    } else {
        IosDrawerColors(
            groupedBackground = Color(0xFFF2F2F7),
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            separator = Color(0xFF3C3C43).copy(alpha = 0.13f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            headerText = Color(0xFF3C3C43).copy(alpha = 0.52f),
            activeIndicator = Color(0xFF0A84FF),
            chevronTint = Color(0xFFC7C7CC)
        )
    }
}

// =============================================================================
// Grouped Surface Container
// =============================================================================

@Composable
private fun IosGroupSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)
    
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.groupSurface,
        border = BorderStroke(0.7.dp, colors.border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Column(content = content)
    }
}

// =============================================================================
// Row Icon (Filled Style)
// =============================================================================

@Composable
private fun IosRowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)
    
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colors.iconBackground),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.iconTint,
            modifier = Modifier.size(17.dp)
        )
    }
}

// =============================================================================
// Row with Chevron
// =============================================================================

@Composable
private fun IosRowWithChevron(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    showChevron: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)
    
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IosRowIcon(icon = icon)
            
            Spacer(Modifier.width(12.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = colors.primaryText,
                modifier = Modifier.weight(1f)
            )
            
            if (showChevron) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.chevronTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// =============================================================================
// Row Separator - Full Width
// =============================================================================

@Composable
private fun IosRowSeparator(
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(colors.separator)
    )
}

// =============================================================================
// Active Indicator Dot
// =============================================================================

@Composable
private fun ActiveDot(
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)
    
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(colors.activeIndicator)
    )
}



// =============================================================================
// Main Composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatDrawerContent(
    drawerState: DrawerState,
    activeConversationId: String?,
    isRemoteMode: Boolean,
    sessionMode: IntelligenceSessionManager.SessionMode,
    workspacePath: String?,
    isLoadingConversations: Boolean,
    connectionState: ConnectionState,
    conversations: List<ConversationEntity>,
    onLoadConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onClearConversation: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    onNavigateToRemoteSession: () -> Unit,
    onNavigateToOpencode: (() -> Unit)? = null,
    onExit: () -> Unit,
    hasMoreConversations: () -> Boolean,
    loadMoreConversations: () -> Unit,
    scope: CoroutineScope,
    sessionDisconnectVisible: Boolean = false,
    onRequestSessionDisconnect: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val conversationListState = rememberLazyListState()
    var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    val closeDrawerThen: ((() -> Unit) -> Unit) = { action ->
        scope.launch {
            drawerState.close()
            action()
        }
    }

    LaunchedEffect(conversations.firstOrNull()?.id) {
        if (conversations.isNotEmpty() && conversationListState.firstVisibleItemIndex > 0) {
            conversationListState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            val layoutInfo = conversationListState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 5
        }.collect { shouldLoad ->
            if (shouldLoad && hasMoreConversations()) {
                loadMoreConversations()
            }
        }
    }

    BackHandler(enabled = isSearchExpanded) {
        searchQuery = ""
        isSearchExpanded = false
    }
    BackHandler(enabled = drawerState.isOpen && !isSearchExpanded) {
        scope.launch { drawerState.close() }
    }

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.groupedBackground,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Expanded search mode
            androidx.compose.animation.AnimatedVisibility(
                visible = isSearchExpanded,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(250, easing = FastOutSlowInEasing)) +
                       slideOutHorizontally(tween(250, easing = FastOutSlowInEasing)) { it }
            ) {
                DrawerSearchContent(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    searchFocusRequester = searchFocusRequester,
                    conversations = filteredConversations,
                    onLoadConversation = { id ->
                        searchQuery = ""
                        isSearchExpanded = false
                        closeDrawerThen { onLoadConversation(id) }
                    },
                    onCancel = {
                        searchQuery = ""
                        isSearchExpanded = false
                    }
                )
            }

            // Normal sidebar mode - iOS grouped style
            androidx.compose.animation.AnimatedVisibility(
                visible = !isSearchExpanded,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it },
                exit = fadeOut(tween(250, easing = FastOutSlowInEasing)) +
                       slideOutHorizontally(tween(250, easing = FastOutSlowInEasing)) { -it }
            ) {
                DrawerNormalContent(
                    isRemoteMode = isRemoteMode,
                    sessionMode = sessionMode,
                    workspacePath = workspacePath,
                    isLoadingConversations = isLoadingConversations,
                    conversations = filteredConversations,
                    activeConversationId = activeConversationId,
                    conversationListState = conversationListState,
                    onClearConversation = { closeDrawerThen(onClearConversation) },
                    onNavigateToWorkspace = { closeDrawerThen(onNavigateToWorkspace) },
                    onNavigateToRemoteSession = { closeDrawerThen(onNavigateToRemoteSession) },
                    onNavigateToOpencode = onNavigateToOpencode?.let { callback ->
                        { closeDrawerThen(callback) }
                    },
                    onNavigateToSettings = { closeDrawerThen(onNavigateToSettings) },
                    onExit = { closeDrawerThen(onExit) },
                    onLoadConversation = { id -> closeDrawerThen { onLoadConversation(id) } },
                    onConversationLongClick = { conversationToDelete = it },
                    onSearchClick = { isSearchExpanded = true },
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    scope = scope
                )
            }

            if (!isSearchExpanded) {
                if (sessionDisconnectVisible) {
                    androidx.compose.material3.SmallFloatingActionButton(
                        onClick = {
                            closeDrawerThen(onRequestSessionDisconnect)
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 140.dp, bottom = 26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = "Disconnect session",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { closeDrawerThen(onClearConversation) },
                    containerColor = Color(0xFF0A84FF),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Chat",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
            
            // Right outline
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(0.7.dp)
                    .background(colors.border)
            )
        }
    }

    // Delete confirmation dialog
    conversationToDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            title = { Text("Delete Conversation?") },
            text = { Text("\"${conv.title.ifEmpty { "New Chat" }}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            onDeleteConversation(conv.id)
                            conversationToDelete = null
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// =============================================================================
// Search Content
// =============================================================================

@Composable
private fun DrawerSearchContent(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    conversations: List<ConversationEntity>,
    onLoadConversation: (Long) -> Unit,
    onCancel: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.groupSurface,
                border = BorderStroke(0.7.dp, colors.border),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(17.dp),
                        tint = colors.secondaryText)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = colors.primaryText
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Search conversations",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.secondaryText
                                )
                            }
                            inner()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(colors.iconBackground)
                                .clickable { onSearchQueryChange("") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Clear, null, modifier = Modifier.size(11.dp),
                                tint = colors.secondaryText)
                        }
                    }
                }
            }
            Text(
                "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onCancel() }
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            when {
                conversations.isEmpty() && searchQuery.isBlank() -> item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No conversations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryText)
                    }
                }
                searchQuery.isNotBlank() && conversations.isEmpty() -> item(key = "no-results") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No results for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.secondaryText)
                    }
                }
                else -> items(conversations, key = { it.id }) { conv ->
                    Surface(
                        onClick = { onLoadConversation(conv.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            HyperText(
                                text = conv.title.ifEmpty { "New Chat" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.primaryText.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(300)
            searchFocusRequester.requestFocus()
        }
    }
}

// =============================================================================
// Normal Content - iOS Grouped Style
// =============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerNormalContent(
    isRemoteMode: Boolean,
    sessionMode: IntelligenceSessionManager.SessionMode,
    workspacePath: String?,
    isLoadingConversations: Boolean,
    conversations: List<ConversationEntity>,
    activeConversationId: String?,
    conversationListState: androidx.compose.foundation.lazy.LazyListState,
    onClearConversation: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    onNavigateToRemoteSession: () -> Unit,
    onNavigateToOpencode: (() -> Unit)?,
    onNavigateToSettings: () -> Unit,
    onExit: () -> Unit,
    onLoadConversation: (Long) -> Unit,
    onConversationLongClick: (ConversationEntity) -> Unit,
    onSearchClick: () -> Unit,
    onCloseDrawer: () -> Unit,
    scope: CoroutineScope
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Amaya",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText,
                modifier = Modifier.weight(1f)
            )
            
            // Search button
            Surface(
                onClick = onSearchClick,
                shape = CircleShape,
                color = colors.iconBackground,
                border = BorderStroke(1.dp, colors.border),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = colors.iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(8.dp))
            
            // Settings button
            Surface(
                onClick = onNavigateToSettings,
                shape = CircleShape,
                color = colors.iconBackground,
                border = BorderStroke(1.dp, colors.border),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = colors.iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Quick Access card
        val showProjects = sessionMode != IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE
        val showRemoteSessionRow = !isRemoteMode
        val showOpencodeRow = onNavigateToOpencode != null
        if (showProjects || showRemoteSessionRow || showOpencodeRow) {
            IosGroupSurface(modifier = Modifier.fillMaxWidth()) {
                if (showProjects) {
                    IosRowWithChevron(
                        icon = Icons.Default.FolderOpen,
                        title = "Projects",
                        onClick = onNavigateToWorkspace
                    )
                }

                if (showOpencodeRow) {
                    if (showProjects) IosRowSeparator()
                    IosRowWithChevron(
                        icon = Icons.Default.Terminal,
                        title = "Opencode",
                        onClick = { onNavigateToOpencode?.invoke() }
                    )
                }

                if (showRemoteSessionRow) {
                    if (showProjects || showOpencodeRow) IosRowSeparator()
                    IosRowWithChevron(
                        icon = Icons.Default.Devices,
                        title = "Remote Session",
                        onClick = onNavigateToRemoteSession
                    )
                }
            }
        }

        // Recent Conversations card
        if (conversations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            IosGroupSurface(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = conversationListState,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items = conversations, key = { it.id }) { conv ->
                        val isActive = conv.id.toString() == activeConversationId
                        ConversationDrawerItem(
                            conv = conv,
                            isActive = isActive,
                            isLast = conv == conversations.lastOrNull(),
                            onClick = { onLoadConversation(conv.id) },
                            onLongClick = { onConversationLongClick(conv) }
                        )
                    }
                }
            }
        }

        // Loading state
        if (isLoadingConversations && conversations.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            IosGroupSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    repeat(3) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.7f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.iconBackground)
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.4f)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.iconBackground.copy(alpha = 0.5f))
                                )
                            }
                        }
                        if (it < 2) IosRowSeparator()
                    }
                }
            }
        }

        // Empty state
        if (!isLoadingConversations && conversations.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            IosGroupSurface(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No conversations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}

// =============================================================================
// Conversation Item
// =============================================================================

private const val HYPER_TEXT_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val HYPER_TEXT_DURATION_MS = 900

internal fun hyperTextFrame(from: String, to: String, progress: Float): String {
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction == 0f) return from
    if (fraction == 1f) return to

    val maxLength = maxOf(from.length, to.length)
    val visibleLength = (maxLength + (to.length - maxLength) * fraction).roundToInt()
    val resolvedLength = (to.length * fraction).toInt()
    val frame = (fraction * 24).toInt()
    return buildString(visibleLength) {
        repeat(visibleLength) { index ->
            append(when {
                index < resolvedLength -> to[index]
                index < to.length && to[index].isWhitespace() -> to[index]
                index < from.length && from[index].isWhitespace() -> ' '
                else -> HYPER_TEXT_GLYPHS[Math.floorMod(to.hashCode() + index * 31 + frame * 17, HYPER_TEXT_GLYPHS.length)]
            })
        }
    }
}

@Composable
internal fun rememberHyperText(text: String): String {
    var target by remember { mutableStateOf(text) }
    var rendered by remember { mutableStateOf(text) }

    LaunchedEffect(text) {
        if (text == target) return@LaunchedEffect
        val from = rendered
        target = text
        Animatable(0f).animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = HYPER_TEXT_DURATION_MS, easing = FastOutSlowInEasing)
        ) {
            rendered = hyperTextFrame(from, text, value)
        }
        rendered = text
    }
    return rendered
}

@Composable
private fun HyperText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val rendered = rememberHyperText(text)
    Text(
        text = rendered,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = style,
        color = color,
        modifier = modifier.clearAndSetSemantics { contentDescription = text }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationDrawerItem(
    conv: ConversationEntity,
    isActive: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HyperText(
                text = conv.title.ifEmpty { "New Chat" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = colors.primaryText,
                modifier = Modifier.weight(1f)
            )
            
            if (isActive) {
                Spacer(Modifier.width(8.dp))
                ActiveDot()
            }
        }
    }

    // Add separator if not last
    if (!isLast) {
        IosRowSeparator()
    }
}
