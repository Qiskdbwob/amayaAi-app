package com.amaya.intelligence.ui.screens.chat.shared

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.ai.displayName
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.ui.res.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val UNCATEGORIZED_WORKSPACE_KEY = "uncategorized"

private fun normalizeWorkspacePath(path: String?): String? {
    return path
        ?.replace("\\", "/")
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
}

private fun groupConversationsByWorkspace(conversations: List<ConversationEntity>): Map<String, List<ConversationEntity>> {
    return conversations.groupBy { conversation ->
        normalizeWorkspacePath(conversation.workspacePath) ?: UNCATEGORIZED_WORKSPACE_KEY
    }
}

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
    onExit: () -> Unit,
    hasMoreConversations: () -> Boolean,
    loadMoreConversations: () -> Unit,
    scope: CoroutineScope
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val conversationListState = rememberLazyListState()
    var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    LaunchedEffect(conversations.firstOrNull()?.id) {
        if (conversations.isNotEmpty() && conversationListState.firstVisibleItemIndex > 0) {
            conversationListState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(conversationListState.layoutInfo) {
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
    val drawerBg = if (isDark) Color(0xFF050505) else Color(0xFFF7F7F8)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = drawerBg,
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
                        onLoadConversation(id)
                        searchQuery = ""
                        isSearchExpanded = false
                        scope.launch { drawerState.close() }
                    },
                    onCancel = {
                        searchQuery = ""
                        isSearchExpanded = false
                    }
                )
            }

            // Normal sidebar mode
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
                    onClearConversation = {
                        onClearConversation()
                        scope.launch { drawerState.close() }
                    },
                    onNavigateToWorkspace = {
                        onNavigateToWorkspace()
                        scope.launch { drawerState.close() }
                    },
                    onNavigateToRemoteSession = {
                        onNavigateToRemoteSession()
                        scope.launch { drawerState.close() }
                    },
                    onNavigateToSettings = {
                        onNavigateToSettings()
                        scope.launch { drawerState.close() }
                    },
                    onExit = {
                        scope.launch { drawerState.close() }
                        onExit()
                    },
                    onLoadConversation = { id ->
                        onLoadConversation(id)
                        scope.launch { drawerState.close() }
                    },
                    onConversationLongClick = { conversationToDelete = it },
                    onSearchClick = { isSearchExpanded = true },
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    scope = scope
                )
            }

            if (!isSearchExpanded) {
                Surface(
                    onClick = {
                        onClearConversation()
                        scope.launch { drawerState.close() }
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFF0A84FF),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp), tint = Color.White)
                        Text(
                            "Chat",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
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

@Composable
private fun DrawerSearchContent(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    conversations: List<ConversationEntity>,
    onLoadConversation: (Long) -> Unit,
    onCancel: () -> Unit
) {
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Search conversations",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
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
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                .clickable { onSearchQueryChange("") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Clear, null, modifier = Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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

        if (searchQuery.isNotBlank() || conversations.isNotEmpty()) {
            Text(
                if (searchQuery.isBlank()) "Recent" else "Results",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            when {
                conversations.isEmpty() -> item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No conversations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    }
                }
                searchQuery.isNotBlank() && conversations.isEmpty() -> item(key = "no-results") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No results for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
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
                            Text(
                                conv.title.ifEmpty { "New Chat" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
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

@Composable
private fun DrawerNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
        )
        Spacer(Modifier.width(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

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
    onNavigateToSettings: () -> Unit,
    onExit: () -> Unit,
    onLoadConversation: (Long) -> Unit,
    onConversationLongClick: (ConversationEntity) -> Unit,
    onSearchClick: () -> Unit,
    onCloseDrawer: () -> Unit,
    scope: CoroutineScope
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Amaya",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(21.dp)
                            .clickable(onClick = onSearchClick)
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                            .clickable(onClick = onNavigateToSettings),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        DrawerNavRow(
            icon = Icons.Default.FolderOpen,
            label = "Projects",
            onClick = onNavigateToWorkspace
        )
        Spacer(Modifier.height(2.dp))

        if (!isRemoteMode) {
            DrawerNavRow(
                icon = Icons.Default.Cast,
                label = "Remote Session",
                onClick = onNavigateToRemoteSession
            )
            Spacer(Modifier.height(2.dp))
        }

        DrawerNavRow(
            icon = Icons.Default.MoreHoriz,
            label = "More",
            onClick = onSearchClick
        )

        Spacer(Modifier.height(22.dp))

        if (conversations.isNotEmpty()) {
            Text(
                "Recents",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            state = conversationListState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 0.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            when {
                isLoadingConversations -> {
                    items(5) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.7f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.4f)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                                )
                            }
                        }
                    }
                }
                conversations.isEmpty() -> item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ChatBubbleOutline, null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                            }
                            Text("No conversations yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                        }
                    }
                }
                else -> {
                    items(items = conversations, key = { it.id }) { conv ->
                        ConversationDrawerItem(
                            conv = conv,
                            active = conv.id.toString() == activeConversationId,
                            showWorkspaceBadge = false,
                            onClick = { onLoadConversation(conv.id) },
                            onLongClick = { onConversationLongClick(conv) }
                        )
                    }
                }
            }
        }

    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationDrawerItem(
    conv: ConversationEntity,
    active: Boolean,
    showWorkspaceBadge: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val activeColor = if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = activeColor,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                conv.title.ifEmpty { "New Chat" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.92f),
                modifier = Modifier.weight(1f)
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0A84FF))
                )
            }
        }
    }
}
