package com.amaya.intelligence.ui.screens.chat.shared

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
    val activeBackground: Color,
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
            activeBackground = Color.White.copy(alpha = 0.10f),
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
            activeBackground = Color.Black.copy(alpha = 0.06f),
            chevronTint = Color(0xFFC7C7CC)
        )
    }
}

@Composable
internal fun Modifier.shimmeringText(
    shimmering: Boolean,
    baseColor: Color,
    shimmerColor: Color = if (isSystemInDarkTheme()) Color.White else Color.Black
): Modifier {
    if (!shimmering) return this
    val transition = rememberInfiniteTransition(label = "text_shimmer")
    val shimmerProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )

    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            val peakX = (shimmerProgress * (w * 3f)) - w
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to baseColor.copy(alpha = 0.5f),
                    0.5f to shimmerColor,
                    1f to baseColor.copy(alpha = 0.5f),
                    startX = peakX - (w * 0.5f),
                    endX = peakX + (w * 0.5f)
                ),
                blendMode = BlendMode.SrcIn
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IosRowWithChevron(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    title: String,
    subtitle: String? = null,
    showChevron: Boolean = true,
    expanded: Boolean? = null,
    selected: Boolean = false,
    trailingProgress: Boolean = false,
    unread: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)

    Surface(
        color = if (selected) colors.activeBackground else Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    else Modifier.clickable(onClick = onClick)
                )
                .heightIn(min = 48.dp)
                .drawBehind {
                    if (expanded == true) {
                        val strokeWidth = 1.dp.toPx()
                        val color = colors.border
                        val iconCenterX = 32.dp.toPx()
                        val centerY = size.height / 2
                        drawLine(
                            color = color,
                            start = androidx.compose.ui.geometry.Offset(iconCenterX, centerY),
                            end = androidx.compose.ui.geometry.Offset(iconCenterX, size.height),
                            strokeWidth = strokeWidth
                        )
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                IosRowIcon(icon = icon)
                Spacer(Modifier.width(12.dp))
            }

            Column(Modifier.weight(1f).fadingEdge()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.shimmeringText(trailingProgress, colors.primaryText)
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (trailingProgress) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            if (unread) {
                UnreadDot()
                Spacer(Modifier.width(8.dp))
            }
            if (showChevron) {
                val rotation by animateFloatAsState(
                    targetValue = if (expanded == true) 90f else 0f,
                    animationSpec = spring(),
                    label = "chevron_rotation"
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.chevronTint,
                    modifier = Modifier.size(18.dp).rotate(rotation)
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
    modifier: Modifier = Modifier,
    startIndent: androidx.compose.ui.unit.Dp = 0.dp
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(colors.separator)
    )
}

@Composable
private fun IosChildRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
    selected: Boolean = false,
    streaming: Boolean = false,
    unread: Boolean = false,
    isLastChild: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val colors = iosDrawerColors(isDark)

    Surface(
        onClick = onClick,
        color = if (selected) colors.activeBackground else Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .drawBehind {
                    drawLine(
                        color = colors.separator,
                        start = androidx.compose.ui.geometry.Offset(60.dp.toPx(), 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = 0.5.dp.toPx()
                    )

                    val strokeWidth = 1.dp.toPx()
                    val color = colors.border
                    val parentIconCenterX = 32.dp.toPx()
                    val childIconLeftEdge = 44.dp.toPx()
                    val centerY = size.height / 2
                    val cornerRadius = 12.dp.toPx()

                    val path = androidx.compose.ui.graphics.Path().apply {
                        if (isLastChild) {
                            moveTo(parentIconCenterX, 0f)
                            lineTo(parentIconCenterX, centerY - cornerRadius)
                            quadraticTo(
                                parentIconCenterX, centerY,
                                parentIconCenterX + cornerRadius, centerY
                            )
                            lineTo(childIconLeftEdge, centerY)
                        } else {
                            moveTo(parentIconCenterX, 0f)
                            lineTo(parentIconCenterX, size.height)
                            moveTo(parentIconCenterX, centerY)
                            lineTo(childIconLeftEdge, centerY)
                        }
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                        )
                    )
                }
                .padding(start = 44.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                IosRowIcon(icon = icon)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).fadingEdge()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.shimmeringText(streaming, colors.primaryText)
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (streaming) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            if (unread) {
                UnreadDot()
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

// =============================================================================
// Unread Indicator Dot
// =============================================================================

@Composable
private fun UnreadDot(
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
    assistantMode: com.amaya.intelligence.domain.models.AssistantMode,
    ownerLabel: String,
    agentMembers: List<com.amaya.intelligence.data.local.entity.AgentEntity>,
    delegationTasks: List<com.amaya.intelligence.data.local.entity.DelegationTaskEntity>,
    projects: List<com.amaya.intelligence.data.local.entity.ProjectEntity>,
    agentGroups: List<com.amaya.intelligence.data.local.entity.AgentGroupEntity>,
    allAgents: List<com.amaya.intelligence.data.local.entity.AgentEntity>,
    allLocalConversations: List<ConversationEntity>,
    runningSessions: List<com.amaya.intelligence.domain.models.RunningSession>,
    isLoadingConversations: Boolean,
    connectionState: ConnectionState,
    conversations: List<ConversationEntity>,
    onLoadConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onClearConversation: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    onNavigateToAgents: () -> Unit = {},
    onOpenChat: () -> Unit = onClearConversation,
    onOpenProject: (com.amaya.intelligence.data.local.entity.ProjectEntity, Long?) -> Unit = { _, _ -> },
    onOpenAgent: (com.amaya.intelligence.data.local.entity.AgentGroupEntity, com.amaya.intelligence.data.local.entity.AgentEntity, Long?) -> Unit = { _, _, _ -> },
    onNavigateToRemoteSession: () -> Unit,
    onNavigateToOpencode: (() -> Unit)? = null,
    onExit: () -> Unit,
    hasMoreConversations: () -> Boolean,
    loadMoreConversations: () -> Unit,
    scope: CoroutineScope,
    sessionDisconnectVisible: Boolean = false,
    onRequestSessionDisconnect: () -> Unit = {}
) {
    var unreadSet by remember { mutableStateOf(setOf<Long>()) }
    var lastUpdatedMap by remember { mutableStateOf(mapOf<Long, Long>()) }

    LaunchedEffect(allLocalConversations) {
        val newMap = mutableMapOf<Long, Long>()
        val newUnread = unreadSet.toMutableSet()
        for (conv in allLocalConversations) {
            val oldTime = lastUpdatedMap[conv.id]
            if (oldTime != null && conv.updatedAt > oldTime && conv.id.toString() != activeConversationId) {
                newUnread.add(conv.id)
            }
            newMap[conv.id] = conv.updatedAt
        }
        activeConversationId?.toLongOrNull()?.let { newUnread.remove(it) }

        lastUpdatedMap = newMap
        unreadSet = newUnread
    }

    LaunchedEffect(activeConversationId) {
        activeConversationId?.toLongOrNull()?.let { activeId ->
            val newUnread = unreadSet.toMutableSet()
            newUnread.remove(activeId)
            unreadSet = newUnread
        }
    }

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

    val filteredConversations = remember(conversations, allLocalConversations, searchQuery, isRemoteMode) {
        val source = if (isRemoteMode) conversations else allLocalConversations
        if (searchQuery.isBlank()) source
        else source.filter { it.title.contains(searchQuery, ignoreCase = true) }
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
                    assistantMode = assistantMode,
                    ownerLabel = ownerLabel,
                    agentMembers = agentMembers,
                    delegationTasks = delegationTasks,
                    projects = projects,
                    agentGroups = agentGroups,
                    allAgents = allAgents,
                    allLocalConversations = allLocalConversations,
                    runningSessions = runningSessions,
                    isLoadingConversations = isLoadingConversations,
                    conversations = filteredConversations,
                    unreadSet = unreadSet,
                    activeConversationId = activeConversationId,
                    conversationListState = conversationListState,
                    onClearConversation = { closeDrawerThen(onClearConversation) },
                    onOpenChat = { closeDrawerThen(onOpenChat) },
                    onOpenProject = { project, conversationId -> closeDrawerThen { onOpenProject(project, conversationId) } },
                    onOpenAgent = { group, agent, conversationId -> closeDrawerThen { onOpenAgent(group, agent, conversationId) } },
                    onNavigateToWorkspace = { closeDrawerThen(onNavigateToWorkspace) },
                    onNavigateToAgents = { closeDrawerThen(onNavigateToAgents) },
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
                if (assistantMode != com.amaya.intelligence.domain.models.AssistantMode.AGENT) ExtendedFloatingActionButton(
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
                        text = when (assistantMode) {
                            com.amaya.intelligence.domain.models.AssistantMode.CHAT -> "Chat"
                            com.amaya.intelligence.domain.models.AssistantMode.PROJECT -> "Project Chat"
                            com.amaya.intelligence.domain.models.AssistantMode.AGENT -> "Agent"
                        },
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
                                color = colors.primaryText.copy(alpha = 0.85f),
                                modifier = Modifier.fillMaxWidth().fadingEdge()
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
    assistantMode: com.amaya.intelligence.domain.models.AssistantMode,
    ownerLabel: String,
    agentMembers: List<com.amaya.intelligence.data.local.entity.AgentEntity>,
    delegationTasks: List<com.amaya.intelligence.data.local.entity.DelegationTaskEntity>,
    projects: List<com.amaya.intelligence.data.local.entity.ProjectEntity>,
    agentGroups: List<com.amaya.intelligence.data.local.entity.AgentGroupEntity>,
    allAgents: List<com.amaya.intelligence.data.local.entity.AgentEntity>,
    allLocalConversations: List<ConversationEntity>,
    runningSessions: List<com.amaya.intelligence.domain.models.RunningSession>,
    isLoadingConversations: Boolean,
    conversations: List<ConversationEntity>,
    unreadSet: Set<Long>,
    activeConversationId: String?,
    conversationListState: androidx.compose.foundation.lazy.LazyListState,
    onClearConversation: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenProject: (com.amaya.intelligence.data.local.entity.ProjectEntity, Long?) -> Unit,
    onOpenAgent: (com.amaya.intelligence.data.local.entity.AgentGroupEntity, com.amaya.intelligence.data.local.entity.AgentEntity, Long?) -> Unit,
    onNavigateToWorkspace: () -> Unit,
    onNavigateToAgents: () -> Unit,
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

    var expandedGroupId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    var expandedProjectId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }

    androidx.compose.runtime.LaunchedEffect(assistantMode, ownerLabel, agentGroups, projects) {
        if (assistantMode == com.amaya.intelligence.domain.models.AssistantMode.AGENT) {
            expandedGroupId = agentGroups.firstOrNull { it.name == ownerLabel }?.id
        }
        if (assistantMode == com.amaya.intelligence.domain.models.AssistantMode.PROJECT) {
            expandedProjectId = projects.firstOrNull { it.name == ownerLabel }?.id
        }
    }

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

        val showLocalHierarchy = !isRemoteMode && sessionMode != IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE
        val showRemoteSessionRow = !isRemoteMode
        val showOpencodeRow = onNavigateToOpencode != null

        // Upper Section (Scrollable if too many projects/agents)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            if (showLocalHierarchy) {
                Text(
                    "AGENTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                IosGroupSurface(modifier = Modifier.fillMaxWidth()) {
                    agentGroups.forEachIndexed { index, group ->
                        val members = allAgents.filter { it.groupId == group.id }
                            val expanded = expandedGroupId == group.id
                            IosRowWithChevron(
                                icon = Icons.Default.Groups,
                                title = group.name,
                                subtitle = "${members.size} agents",
                                expanded = expanded,
                                selected = assistantMode == com.amaya.intelligence.domain.models.AssistantMode.AGENT && group.name == ownerLabel && !expanded,
                                onClick = { expandedGroupId = if (expanded) null else group.id }
                            )
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically(animationSpec = spring()),
                                exit = shrinkVertically(animationSpec = spring())
                            ) {
                                Column {
                                    members.forEachIndexed { mIndex, agent ->
                                        val session = allLocalConversations.firstOrNull {
                                            it.assistantMode == "AGENT" && it.ownerId == group.id.toString() && it.agentId == agent.id
                                        }
                                        IosChildRow(
                                            icon = Icons.Default.SmartToy,
                                            title = agent.name,
                                            subtitle = agent.role.ifBlank { "Agent" },
                                            selected = assistantMode == com.amaya.intelligence.domain.models.AssistantMode.AGENT && session?.id?.toString() == activeConversationId,
                                            streaming = session?.id?.let { id -> runningSessions.any { it.conversationId == id } } == true,
                                            unread = session?.id?.let { id -> unreadSet.contains(id) } == true,
                                            isLastChild = mIndex == members.lastIndex,
                                            onClick = { onOpenAgent(group, agent, session?.id) }
                                        )
                                    }
                                }
                            }
                            if (index != agentGroups.lastIndex) IosRowSeparator()
                        }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "PROJECTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                IosGroupSurface(modifier = Modifier.fillMaxWidth()) {
                    projects.forEachIndexed { index, project ->
                            val sessions = allLocalConversations.filter { it.assistantMode == "PROJECT" && it.ownerId == project.id.toString() }
                                .sortedByDescending { it.updatedAt }
                            val expanded = expandedProjectId == project.id
                            IosRowWithChevron(
                                icon = Icons.Default.FolderOpen,
                                title = project.name,
                                subtitle = "${sessions.size} chats",
                                expanded = expanded,
                                selected = assistantMode == com.amaya.intelligence.domain.models.AssistantMode.PROJECT && project.name == ownerLabel && !expanded,
                                onClick = { expandedProjectId = if (expanded) null else project.id }
                            )
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically(animationSpec = spring()),
                                exit = shrinkVertically(animationSpec = spring())
                            ) {
                                Column {
                                    // New Chat for this Project
                                    IosChildRow(
                                        icon = Icons.Default.Add,
                                        title = "New Chat",
                                        selected = false,
                                        isLastChild = sessions.isEmpty(),
                                        onClick = { onOpenProject(project, null) }
                                    )
                                    sessions.forEachIndexed { sIndex, session ->
                                        IosChildRow(
                                            title = session.title.ifBlank { "New chat" },
                                            selected = session.id.toString() == activeConversationId,
                                            streaming = runningSessions.any { it.conversationId == session.id },
                                            unread = unreadSet.contains(session.id),
                                            isLastChild = sIndex == sessions.lastIndex,
                                            onClick = { onOpenProject(project, session.id) }
                                        )
                                    }
                                }
                            }
                            if (index != projects.lastIndex) IosRowSeparator()
                        }
                    }

                // CHATS
                val localChats = allLocalConversations.filter { it.assistantMode == "CHAT" && (it.ownerId.isNullOrEmpty() || it.ownerId == "null") }
                    .sortedByDescending { it.updatedAt }
                Spacer(Modifier.height(16.dp))
                Text(
                    "CHATS",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                IosGroupSurface(modifier = Modifier.fillMaxWidth()) {
                    IosRowWithChevron(
                        icon = Icons.Default.Add,
                        title = "New Chat",
                        showChevron = false,
                        onClick = onOpenChat
                    )
                    localChats.forEach { conv ->
                        IosRowSeparator()
                        val isActive = conv.id.toString() == activeConversationId
                        IosRowWithChevron(
                            icon = null,
                            title = conv.title.ifBlank { "New chat" },
                            showChevron = false,
                            selected = isActive,
                            trailingProgress = runningSessions.any { it.conversationId == conv.id },
                            unread = unreadSet.contains(conv.id),
                            onClick = { onLoadConversation(conv.id) },
                            onLongClick = { onConversationLongClick(conv) }
                        )
                    }
                }
            } else if (showRemoteSessionRow || showOpencodeRow) {
                IosGroupSurface(Modifier.fillMaxWidth()) {
                    if (showOpencodeRow) IosRowWithChevron(Icons.Default.Terminal, "Opencode", onClick = { onNavigateToOpencode?.invoke() })
                    if (showOpencodeRow && showRemoteSessionRow) IosRowSeparator()
                    if (showRemoteSessionRow) IosRowWithChevron(Icons.Default.Devices, "Remote Session", onClick = onNavigateToRemoteSession)
                }
            }
        } // End Upper Section

        // Lower Section: Chats (LazyColumn with remaining weight)
        if (!showLocalHierarchy) {
            val chats = conversations
            if (chats.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                IosGroupSurface(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = conversationListState,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(items = chats, key = { it.id }) { conv ->
                            val isActive = conv.id.toString() == activeConversationId
                            ConversationDrawerItem(
                                conv = conv,
                                isActive = isActive,
                                isLast = conv == chats.lastOrNull(),
                                streaming = runningSessions.any { it.conversationId == conv.id },
                                unread = unreadSet.contains(conv.id),
                                onClick = { onLoadConversation(conv.id) },
                                onLongClick = { onConversationLongClick(conv) }
                            )
                        }
                    }
                }
            } else if (isLoadingConversations) {
                Spacer(Modifier.height(16.dp))
                IosGroupSurface(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
            } else if (!isLoadingConversations && conversations.isEmpty()) {
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

    val targetLen = to.length
    val frame = (fraction * 24).toInt()
    return buildString(targetLen) {
        repeat(targetLen) { index ->
            // Each character has its own resolve threshold, staggered evenly across the animation.
            // This makes characters lock in left-to-right smoothly, with the last one finishing
            // at fraction ~0.92 instead of 1.0, eliminating the "last char lag".
            val charThreshold = (index + 1).toFloat() / (targetLen + 1).toFloat()
            append(when {
                fraction >= charThreshold -> to[index]
                to[index].isWhitespace() -> to[index]
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
        overflow = TextOverflow.Clip,
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
    streaming: Boolean = false,
    unread: Boolean = false,
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
            .background(if (isActive) colors.activeBackground else Color.Transparent)
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
                modifier = Modifier.weight(1f).fadingEdge().shimmeringText(streaming, colors.primaryText)
            )

            if (streaming) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }

            if (unread) {
                UnreadDot()
            }

        }
    }

    // Add separator if not last
    if (!isLast) {
        IosRowSeparator()
    }
}

private fun Modifier.fadingEdge(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0.85f to Color.Black,
                1.0f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }
