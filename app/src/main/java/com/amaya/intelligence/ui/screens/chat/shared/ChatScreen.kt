package com.amaya.intelligence.ui.screens.chat.shared

import com.amaya.intelligence.ui.viewmodels.ChatViewModel

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.ai.displayName
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.ui.components.shared.ConversationModeSheet
import com.amaya.intelligence.ui.components.shared.LocalhostLinkBottomSheet
import com.amaya.intelligence.ui.components.shared.LocalhostLinkInfo
import com.amaya.intelligence.ui.components.shared.LocalhostLinkInfoParser
import com.amaya.intelligence.ui.components.shared.ModelSelectorSheet
import com.amaya.intelligence.ui.components.remote.WindowsBridgeChatPanelViewModel
import com.amaya.intelligence.ui.components.remote.WindowsBridgeSessionInfoSheet
import com.amaya.intelligence.ui.components.local.SessionInfoSheet
import com.amaya.intelligence.ui.components.local.TodoSheet
import com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet
import com.amaya.intelligence.ui.activities.agent.local.LocalAgentConfigActivity
import com.amaya.intelligence.ui.activities.browser.BrowserOperatorActivity
import com.amaya.intelligence.utils.NetworkUtils
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


/**
 * How close to the end of the content the user has to be for the list to keep
 * following new content. Anything stricter (pixel-exact) makes a few pixels of
 * drift silently disable auto-follow.
 */
private val CHAT_AUTO_FOLLOW_THRESHOLD = 72.dp

/**
 * How long after the user sends a message the list keeps following growth even if the
 * provider has not flagged the turn active yet. Long enough to cover the round trip from
 * "message appended" to "streaming", short enough that it can't be mistaken for a tap.
 */
private const val CHAT_FOLLOW_BURST_MS = 800L

/**
 * Non-observable flag telling the two auto-follow coroutines apart: while it is
 * set, the list is being scrolled by us, not dragged by the user. Deliberately
 * not a [androidx.compose.runtime.MutableState] — flipping it must not recompose.
 */
private class ChatScrollOwner {
    var following = false
}

/**
 * How far the rendered content now reaches past the bottom of the viewport,
 * in pixels. 0 means the tail of the conversation is fully on screen.
 *
 * `canScrollForward` is the authority here: during streaming the tail item is
 * pushed below the viewport and drops out of `visibleItemsInfo` entirely, so a
 * measurement based on visible items alone would report "nothing below" exactly
 * when the most content is hidden.
 */
private fun LazyListState.distanceToContentEnd(): Int {
    if (!canScrollForward) return 0
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return 0
    // Tail item not rendered at all — it is somewhere below, distance unknown
    // but definitely more than a viewport.
    val last = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
        ?: return info.viewportEndOffset - info.viewportStartOffset
    return ((last.offset + last.size) - info.viewportEndOffset).coerceAtLeast(0)
}

/** Instant move to the end of the content. No-op when already there. */
private suspend fun LazyListState.snapToContentEnd() {
    if (!canScrollForward) return
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return
    val last = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
    if (last == null) {
        scrollToItem(total - 1, Int.MAX_VALUE)
        return
    }
    val distance = (last.offset + last.size) - info.viewportEndOffset
    if (distance > 0) scrollBy(distance.toFloat())
}

/** Animated move to the end of the content — for explicit user actions only. */
private suspend fun LazyListState.animateToContentEnd() {
    if (!canScrollForward) return
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == total - 1 }) {
        animateScrollToItem(total - 1)
    }
    // Settle the remainder: the tail item can be taller than the viewport.
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 } ?: return
    val distance = (last.offset + last.size) - info.viewportEndOffset
    if (distance > 0) animateScrollBy(distance.toFloat())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    bridgeViewModel: WindowsBridgeChatPanelViewModel = hiltViewModel(),
    activeReminderCount: Int = -1,
    isRemoteModeOverride: Boolean? = null,
    config: ChatScreenConfig? = null,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkspace: () -> Unit = {},
    onNavigateToAgents: () -> Unit = {},
    onNavigateToRemoteSession: () -> Unit = {},
    onNavigateToOpencode: (() -> Unit)? = null,
    onExit: () -> Unit = {},
    sessionDisconnectName: String? = null,
    onConfirmSessionDisconnect: (() -> Unit)? = null,
    /**
     * Optional slot rendered below the top app bar and above the message list.
     * Used by opencode (Plan/Build pill, permission card) so the overlay moves
     * with the chat content when the drawer opens instead of sitting on top
     * of the drawer.
     */
    topOverlay: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val todoItems by viewModel.todoItems.collectAsState()
    val localReminderCount by viewModel.activeReminderCount.collectAsState()
    val effectiveReminderCount = if (activeReminderCount >= 0) activeReminderCount else localReminderCount
    val scrollEventFlow = viewModel.scrollEvent
    val conversationsFlow = viewModel.conversations
    val ownerLabel by viewModel.ownerLabel.collectAsState()
    val activeAgentGroupLabel by viewModel.activeAgentGroupLabel.collectAsState()
    val activeAgentMembers by viewModel.activeAgentMembers.collectAsState()
    val activeDelegationTasks by viewModel.activeDelegationTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val agentGroups by viewModel.agentGroups.collectAsState()
    val allAgents by viewModel.allAgents.collectAsState()
    val allLocalConversations by viewModel.allLocalConversations.collectAsState()
    val runningSessions by viewModel.runningSessions.collectAsState()

    val isRemoteMode = isRemoteModeOverride ?: uiState.sessionMode.isRemote()
    val isBridgeMode = uiState.sessionMode ==
        com.amaya.intelligence.domain.ai.IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE
    val bridgeState by bridgeViewModel.state.collectAsState()
    val connectionState = uiState.connectionState
    val workspaces by viewModel.workspaces.collectAsState()

    // Action delegates
    val doSendMessage: (String) -> Unit = remember(viewModel) { { viewModel.sendMessage(it) } }
    val doSendMessageWithImage: (String, String, String, String) -> Unit = remember(viewModel) {
        { content, base64, mime, name -> viewModel.sendMessageWithImage(content, base64, mime, name) }
    }
    val doStopGeneration: () -> Unit = remember(viewModel) { { viewModel.stopGeneration() } }
    val doClearConversation: () -> Unit = remember(viewModel) { { viewModel.clearConversation() } }
    val doSelectModel: (String) -> Unit = remember(viewModel) { { viewModel.selectModel(it) } }
    val doLoadConversation: (Long) -> Unit = remember(viewModel) { { viewModel.loadConversation(it) } }
    val doDeleteConversation: (Long) -> Unit = remember(viewModel) { { viewModel.deleteConversation(it) } }
    val doClearVisibleHistory: (Boolean) -> Unit = remember(viewModel) { { viewModel.clearVisibleHistory(it) } }
    val doCompactConversation: (String) -> Unit = remember(viewModel) { { focus -> viewModel.compactConversation(focus) } }
    val doCancelCompactConversation: () -> Unit = remember(viewModel) { { viewModel.cancelCompactConversation() } }
    val doClearError: () -> Unit = remember(viewModel) { { viewModel.clearError() } }
    val doHasMoreConversations: () -> Boolean = remember(viewModel) { { viewModel.hasMoreConversations() } }
    val doLoadMoreConversations: () -> Unit = remember(viewModel) { { viewModel.loadMoreConversations() } }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerVisible = drawerState.currentValue == DrawerValue.Open ||
        drawerState.targetValue == DrawerValue.Open

    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(drawerState.targetValue, drawerState.isAnimationRunning) {
        if (drawerState.targetValue == DrawerValue.Open) {
            keyboardController?.hide()
        }
    }

    var showModelSelector by remember { mutableStateOf(false) }
    var showSessionInfo by remember { mutableStateOf(false) }
    var showBridgeSessionInfo by remember { mutableStateOf(false) }
    var showTodoSheet by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    val inputBarHeight = remember { mutableIntStateOf(0) }
    val conversationKey = uiState.conversationId.orEmpty()
    val composerKey = "${uiState.assistantMode}:${uiState.ownerId}:${uiState.agentId}:${uiState.conversationId}"
    var attachedFilePath by remember(composerKey) { mutableStateOf<String?>(null) }
    var attachedImageBase64 by remember(composerKey) { mutableStateOf<String?>(null) }
    var attachedImageMimeType by remember(composerKey) { mutableStateOf<String?>(null) }
    var attachedImageName by remember(composerKey) { mutableStateOf<String?>(null) }
    var showConversationModeSheet by remember { mutableStateOf(false) }
    var showAgentMenu by remember { mutableStateOf(false) }
    var showDeleteAgentChatSheet by remember { mutableStateOf(false) }

    var showLocalhostLinkSheet by remember { mutableStateOf(false) }
    var selectedLocalhostLink by remember { mutableStateOf<LocalhostLinkInfo?>(null) }
    var localIp by remember { mutableStateOf("127.0.0.1") }

    // Get local IP address on launch as fallback
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            localIp = NetworkUtils.getLocalIpAddress()
        }
    }

    val inputText = remember(composerKey) { mutableStateOf("") }

    // Use server IP from extension if available, otherwise fallback to device local IP
    val serverIp = uiState.serverIp ?: localIp

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val resolvedPath = withContext(Dispatchers.IO) {
                    var path: String? = null
                    try {
                        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) path = cursor.getString(0)
                        }
                    } catch (_: Exception) { }

                    if (path == null) {
                        val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "file"
                        val cacheFile = File(context.cacheDir, "attach_$fileName")
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                cacheFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            path = cacheFile.absolutePath
                        } catch (_: Exception) { }
                    }
                    path
                }
                attachedFilePath = resolvedPath
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val contentResolver = context.contentResolver
                        val rawMimeType = contentResolver.getType(uri) ?: "image/*"
                        val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "image"

                        // Load and compress image to avoid API limits
                        val inputStream = contentResolver.openInputStream(uri)
                        if (inputStream == null) return@withContext null

                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream.close()

                        if (bitmap == null) return@withContext null

                        // Scale down if too large (max 2048px on longest side)
                        val maxDim = 2048
                        val width = bitmap.width
                        val height = bitmap.height
                        val scaledBitmap = if (width > maxDim || height > maxDim) {
                            val scale = maxDim.toFloat() / maxOf(width, height)
                            val newWidth = (width * scale).toInt()
                            val newHeight = (height * scale).toInt()
                            android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                        } else {
                            bitmap
                        }

                        // Adaptive compression: compress until under 180KB base64 (safe for inline upload)
                        // This avoids artifact upload issues with large images
                        // Base64 is ~33% larger than binary, so target ~135KB binary
                        val maxBinarySize = 135_000 // ~135KB binary = ~180KB base64
                        var quality = 85
                        var bytes: ByteArray
                        val outputStream = java.io.ByteArrayOutputStream()

                        do {
                            outputStream.reset()
                            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
                            bytes = outputStream.toByteArray()
                            quality -= 10
                        } while (bytes.size > maxBinarySize && quality >= 30)

                        outputStream.close()

                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        android.util.Log.d("ChatScreen", "Image compressed: ${bytes.size} bytes -> ${base64.length} base64 chars, final quality=$quality")

                        // Recycle bitmaps to free memory
                        if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                        bitmap.recycle()

                        // Always use JPEG since we compressed as JPEG
                        Triple(base64, "image/jpeg", fileName.removeSuffix(".png").removeSuffix(".webp") + ".jpg")
                    } catch (e: Exception) {
                        android.util.Log.e("ChatScreen", "Image processing failed", e)
                        null
                    }
                }
                result?.let { (base64, mime, name) ->
                    android.util.Log.d("ChatScreen", "Image attached: base64Len=${base64.length}, mime=$mime, name=$name")
                    attachedImageBase64 = base64
                    attachedImageMimeType = mime
                    attachedImageName = name
                }
            }
        }
    }

    val activeModelKey = uiState.activeModelKey
    val selectedModelItem = uiState.modelOptions.firstOrNull { it.id == activeModelKey }
    val selectedModel = uiState.selectedModel.ifBlank { selectedModelItem?.modelId.orEmpty() }
    val selectedModelFallbackLabel = config?.selectedModelFallbackLabel ?: "Select Model"

    val onToolAccept: ((ToolExecution) -> Unit)? = remember(viewModel) {
        { execution: ToolExecution ->
            viewModel.respondToToolInteraction(execution.metadata["approvalId"] ?: execution.toolCallId, true)
        }
    }
    val onToolDecline: ((ToolExecution) -> Unit)? = remember(viewModel) {
        { execution: ToolExecution ->
            viewModel.respondToToolInteraction(execution.metadata["approvalId"] ?: execution.toolCallId, false)
        }
    }

    val displayMessages by remember(uiState.messages) {
        derivedStateOf {
            uiState.messages.filter {
                it.content.isNotBlank() ||
                !it.thinking.isNullOrBlank() ||
                it.steps.isNotEmpty()
            }
        }
    }

    // ── Auto-follow ──────────────────────────────────────────────────────────
    // The composer floats over the list, so the list has to be told to keep the
    // tail of the conversation above it. Three concerns, kept deliberately apart:
    //
    //   [contentReach]      observation only — how far the rendered content now
    //                       reaches past the bottom of the viewport.
    //   position tracking   arms/disarms following from the user's own scrolling,
    //                       continuously, so nothing is recomputed when content
    //                       lands and there is no race with the layout pass.
    //   the follow decision reacts to the content growing, never to the user
    //                       moving, and moves the list instantly.
    var shouldAutoScroll by remember { mutableStateOf(true) }
    // Opening another conversation must start armed. Following is disarmed by the user
    // scrolling up, and that flag used to survive the session switch — so a session
    // opened after reading back through the previous one never followed at all.
    LaunchedEffect(conversationKey) { shouldAutoScroll = true }
    val scrollOwner = remember { ChatScrollOwner() }
    val drawerVisibleState = rememberUpdatedState(drawerVisible)
    val contentReach = remember(listState) { snapshotFlow { listState.distanceToContentEnd() } }

    // "Is the model still producing?" — the signal that separates content arriving from
    // content the user unfolded.
    val turnActiveState = rememberUpdatedState(uiState.isLoading || uiState.isStreaming)

    // A short window in which growth is followed even though no turn is running.
    //
    // The turn gate alone is too blunt: the bottom of the screen moves for reasons that
    // are nothing to do with the user unfolding a card — the keyboard opening, the
    // composer gaining a line or a banner, a fresh message landing before the provider
    // has flagged the turn active. Each of those arms this window; a tap on a card
    // header does not.
    val followBurst = remember { mutableStateOf(false) }
    val armFollowBurst = remember {
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    LaunchedEffect(Unit) {
        // collectLatest: a fresh arm restarts the window rather than queueing behind it.
        armFollowBurst.collectLatest {
            followBurst.value = true
            try {
                delay(CHAT_FOLLOW_BURST_MS)
            } finally {
                followBurst.value = false
            }
        }
    }

    // Bottom chrome watcher. A shrinking viewport is the keyboard coming up; a growing
    // composer is an extra line or a banner. Either way the tail has to stay above it.
    LaunchedEffect(listState, inputBarHeight) {
        var previousViewport = 0
        var previousComposer = inputBarHeight.intValue
        snapshotFlow {
            val info = listState.layoutInfo
            (info.viewportEndOffset - info.viewportStartOffset) to inputBarHeight.intValue
        }.collect { (viewport, composer) ->
            val viewportShrank = previousViewport != 0 && viewport < previousViewport
            val composerGrew = composer > previousComposer
            previousViewport = viewport
            previousComposer = composer
            if (viewportShrank || composerGrew) armFollowBurst.tryEmit(Unit)
        }
    }

    // Position tracking. Content growing past the bottom must NEVER disarm
    // following — that is the whole point of streaming — so the disarm branch
    // requires a scroll that we did not start ourselves. Re-arming is
    // threshold-based so a few pixels of drift do not strand the user.
    LaunchedEffect(contentReach, density) {
        val thresholdPx = with(density) { CHAT_AUTO_FOLLOW_THRESHOLD.toPx() }
        contentReach.collect { reach ->
            when {
                reach <= thresholdPx -> shouldAutoScroll = true
                listState.isScrollInProgress && !scrollOwner.following -> shouldAutoScroll = false
            }
        }
    }

    // The follow decision. Growth is "the content reaches further past the
    // bottom than it did a moment ago" — a new message, a live tool card, and
    // streaming text all look identical from here. The move is instant: a
    // duration-based animation would spend the whole answer chasing text that
    // grows faster than it can settle.
    //
    // Gated on the turn being active (or the burst window above), because there is one
    // other thing that makes content grow: the user opening a card. Following that pins
    // the bottom edge and shoves the header they just tapped up off the screen — worst
    // with a work-summary card, which unfolds a whole timeline at once. Model output and
    // moving chrome follow; a tap does not.
    LaunchedEffect(contentReach) {
        var previousReach = 0
        contentReach.collect { reach ->
            val grew = reach > previousReach
            previousReach = reach
            if (!grew || !shouldAutoScroll || drawerVisibleState.value) return@collect
            if (!turnActiveState.value && !followBurst.value) return@collect
            if (listState.isScrollInProgress && !scrollOwner.following) return@collect
            scrollOwner.following = true
            try {
                listState.snapToContentEnd()
            } finally {
                scrollOwner.following = false
                // Anything still hanging below counts as fresh growth, so a
                // single burst that outruns one scroll settles on the next pass.
                previousReach = 0
            }
        }
    }

    // The one explicit, user-triggered jump — the only place a duration
    // animation belongs.
    val jumpToBottom: () -> Unit = {
        shouldAutoScroll = true
        scope.launch {
            scrollOwner.following = true
            try {
                listState.animateToContentEnd()
            } finally {
                scrollOwner.following = false
            }
        }
    }

    LaunchedEffect(Unit) {
        scrollEventFlow.collect { reason ->
            when (reason) {
                // The user just sent something: re-arm following. The growth
                // observer above carries them to it as soon as it renders.
                ChatViewModel.ScrollReason.NEW_MESSAGE -> {
                    shouldAutoScroll = true
                    armFollowBurst.tryEmit(Unit)
                }
                // Tool cards are ordinary content growth — nothing extra to do.
                ChatViewModel.ScrollReason.NEW_TOOL -> Unit
            }
        }
    }

    BackHandler(
        enabled = uiState.messages.isNotEmpty() ||
            (isRemoteMode && (isRemoteModeOverride == true || onConfirmSessionDisconnect != null))
    ) {
        if (isRemoteMode) {
            if (onConfirmSessionDisconnect != null) {
                showDisconnectDialog = true
            } else {
                onExit()
            }
        } else doClearConversation()
    }

    val conversations by conversationsFlow.collectAsState()
    val selectedModelLabel = (selectedModelItem?.name ?: selectedModel).ifBlank { selectedModelFallbackLabel }
    val activeConversationTitle = remember(conversations, allLocalConversations, uiState.conversationId) {
        (conversations + allLocalConversations).firstOrNull { it.id.toString() == uiState.conversationId }?.title
    }
    val activeAgentName = allAgents.firstOrNull { it.id == uiState.agentId }?.name
    val topBarTitle = when (uiState.assistantMode) {
        com.amaya.intelligence.domain.models.AssistantMode.CHAT -> activeConversationTitle?.takeIf { uiState.messages.isNotEmpty() }.orEmpty()
        com.amaya.intelligence.domain.models.AssistantMode.PROJECT -> activeConversationTitle?.takeIf { uiState.messages.isNotEmpty() } ?: "New Chat"
        com.amaya.intelligence.domain.models.AssistantMode.AGENT -> activeAgentName ?: ownerLabel
    }
    val topBarSubtitle = if (isRemoteMode) null else when (uiState.assistantMode) {
        com.amaya.intelligence.domain.models.AssistantMode.CHAT -> null
        com.amaya.intelligence.domain.models.AssistantMode.PROJECT -> uiState.workspacePath.orEmpty()
        com.amaya.intelligence.domain.models.AssistantMode.AGENT -> activeAgentGroupLabel
    }

    // WindowInsets
    val statusBarInsets = WindowInsets.statusBars.asPaddingValues()
    val statusBarHeight = statusBarInsets.calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val headerDp = statusBarHeight + 84.dp
    val bottomDp = 80.dp + navBarHeight
    val bgColor = MaterialTheme.colorScheme.background

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.46f),
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                drawerShape = RectangleShape,
                drawerContainerColor = Color.Transparent,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                ChatDrawerContent(
                    drawerState = drawerState,
                    activeConversationId = uiState.conversationId,
                    isRemoteMode = isRemoteMode,
                    sessionMode = uiState.sessionMode,
                    workspacePath = uiState.workspacePath,
                    assistantMode = uiState.assistantMode,
                    ownerLabel = ownerLabel,
                    agentMembers = activeAgentMembers,
                    delegationTasks = activeDelegationTasks,
                    projects = projects,
                    agentGroups = agentGroups,
                    allAgents = allAgents,
                    allLocalConversations = allLocalConversations,
                    runningSessions = runningSessions,
                    isLoadingConversations = uiState.isLoadingConversations,
                    connectionState = connectionState,
                    conversations = conversations,
                    onLoadConversation = doLoadConversation,
                    onDeleteConversation = doDeleteConversation,
                    onClearConversation = doClearConversation,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToWorkspace = onNavigateToWorkspace,
                    onNavigateToAgents = onNavigateToAgents,
                    onOpenChat = {
                        viewModel.selectChatTarget(com.amaya.intelligence.domain.models.AssistantMode.CHAT)
                    },
                    onOpenProject = { project, conversationId ->
                        viewModel.selectChatTarget(
                            mode = com.amaya.intelligence.domain.models.AssistantMode.PROJECT,
                            ownerId = project.id.toString(),
                            workspacePath = project.rootPath,
                            conversationId = conversationId
                        )
                    },
                    onOpenAgent = { group, agent, _ ->
                        viewModel.selectChatTarget(
                            mode = com.amaya.intelligence.domain.models.AssistantMode.AGENT,
                            ownerId = group.id.toString(),
                            workspacePath = group.workspacePath,
                            agentId = agent.id
                        )
                    },
                    onNavigateToRemoteSession = onNavigateToRemoteSession,
                    onNavigateToOpencode = onNavigateToOpencode,
                    onExit = onExit,
                    hasMoreConversations = doHasMoreConversations,
                    loadMoreConversations = doLoadMoreConversations,
                    scope = scope,
                    sessionDisconnectVisible = isRemoteMode &&
                        (isBridgeMode && bridgeState.isConnected ||
                            !isBridgeMode && onConfirmSessionDisconnect != null),
                    onRequestSessionDisconnect = { showDisconnectDialog = true }
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
            // Content area
            if (uiState.messages.isEmpty()) {
                ChatEmptyContent(
                    isRemoteMode = isRemoteMode,
                    connectionState = connectionState,
                    uiState = uiState,

                    headerDp = headerDp,
                    bottomDp = bottomDp,
                    drawerOpen = drawerVisible,
                    onInputTextChange = { inputText.value = it },
                    onSendMessage = doSendMessage,
                    onNavigateToWorkspace = onNavigateToWorkspace,
                    workspaces = workspaces,
                    bridgeState = bridgeState,
                    isStreaming = uiState.isStreaming
                )
            } else {
                ChatMessageList(
                    listState = listState,
                    displayMessages = displayMessages,
                    conversationKey = conversationKey,
                    isLoading = uiState.isLoading && !uiState.isLoadingHistory,
                    isStreaming = uiState.isStreaming,
                    isRemoteMode = isRemoteMode,
                    headerDp = headerDp,
                    inputBarHeight = inputBarHeight,
                    drawerOpen = drawerVisible,
                    onToolAccept = onToolAccept,
                    onToolDecline = onToolDecline,
                    onLocalhostLinkClick = { annotationItem ->
                        selectedLocalhostLink = LocalhostLinkInfoParser.parse(annotationItem, serverIp)
                        showLocalhostLinkSheet = true
                    },
                    onScrollToBottomClick = jumpToBottom,
                    shouldAutoScroll = shouldAutoScroll
                )
            }

            // Localhost Link Bottom Sheet
            if (showLocalhostLinkSheet && selectedLocalhostLink != null) {
                LocalhostLinkBottomSheet(
                    linkInfo = selectedLocalhostLink!!,
                    localIp = serverIp,
                    onDismiss = {
                        showLocalhostLinkSheet = false
                        selectedLocalhostLink = null
                    },
                    onCopyLink = { url ->
                        // Copy link callback - can be used for analytics or additional handling
                    },
                    onOpenLink = { url ->
                        // Open link callback - can be used for analytics or additional handling
                    }
                )
            }

            // Gradient scrim for status bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.TopStart)
                    .background(LocalAmayaGradients.current.topScrim)
            )

            ChatFloatingTopBar(
                title = topBarTitle,
                subtitle = topBarSubtitle,
                isRemoteMode = isRemoteMode,
                isBridgeMode = isBridgeMode,
                onMenuClick = {
                    keyboardController?.hide()
                    scope.launch { drawerState.open() }
                },
                onTitleClick = {
                    when {
                        isBridgeMode -> showBridgeSessionInfo = true
                        isRemoteMode -> viewModel.refreshState()
                        todoItems.isNotEmpty() -> showTodoSheet = true
                        else -> showSessionInfo = true
                    }
                },
                showNewChat = uiState.assistantMode != com.amaya.intelligence.domain.models.AssistantMode.AGENT,
                onNewChatClick = {
                    keyboardController?.hide()
                    doClearConversation()
                },
                showAgentMenu = uiState.assistantMode == com.amaya.intelligence.domain.models.AssistantMode.AGENT && uiState.agentId != null,
                onAgentMenuClick = { showAgentMenu = true },
                modifier = Modifier.align(Alignment.TopStart),
                agentMenu = {
                    AgentChatMenu(
                        expanded = showAgentMenu,
                        onOpenBrowser = {
                            showAgentMenu = false
                            (context as? android.app.Activity)?.let(BrowserOperatorActivity::start)
                        },
                        onConfigure = {
                            showAgentMenu = false
                            (context as? android.app.Activity)?.let { activity ->
                                uiState.agentId?.let { LocalAgentConfigActivity.startForResult(activity, it) }
                            }
                        },
                        onDeleteChat = {
                            showAgentMenu = false
                            showDeleteAgentChatSheet = true
                        },
                        onDismiss = { showAgentMenu = false }
                    )
                }
            )

            // Bottom section
            ChatBottomSection(
                modifier = Modifier.align(Alignment.BottomStart),
                inputText = inputText,
                isRemoteMode = isRemoteMode,
                uiState = uiState,
                connectionState = connectionState,
                drawerOpen = drawerVisible,
                bgColor = bgColor,
                attachedFilePath = attachedFilePath,
                attachedImageBase64 = attachedImageBase64,
                attachedImageMimeType = attachedImageMimeType,
                attachedImageName = attachedImageName,
                filePicker = filePicker,
                imagePicker = imagePicker,
                keyboardController = keyboardController,
                scope = scope,
                onClearError = doClearError,
                onSendMessage = doSendMessage,
                supportsImages = selectedModelItem?.supportsImages == true,
                onSendMessageWithImage = doSendMessageWithImage,
                onClearImageAttachment = {
                    attachedImageBase64 = null
                    attachedImageMimeType = null
                    attachedImageName = null
                },
                onStopGeneration = doStopGeneration,
                onCancelCompactConversation = doCancelCompactConversation,
                onNavigateToWorkspace = onNavigateToWorkspace,
                ownerLabel = ownerLabel,
                mentionAgents = if (uiState.assistantMode == com.amaya.intelligence.domain.models.AssistantMode.AGENT) {
                    activeAgentMembers.filter { it.id != uiState.agentId }.map {
                        com.amaya.intelligence.ui.components.shared.ChatMentionAgent(it.localId, it.name, it.role, activeAgentGroupLabel)
                    }
                } else emptyList(),
                onSearchWorkspaceFiles = { query -> viewModel.searchWorkspaceFiles(query) },
                showConversationModeSelector = config?.showConversationModeSelector ?: isRemoteMode,
                onShowConversationModeSheet = { showConversationModeSheet = true },
                modelLabel = selectedModelLabel,
                modelId = selectedModelItem?.modelId.orEmpty(),
                modelProviderId = selectedModelItem?.providerId,
                modelIconType = selectedModelItem?.iconType,
                effort = uiState.effort,
                onEffortChange = { viewModel.setEffort(it) },
                onCompactConversation = doCompactConversation,
                onSelectModel = {
                    keyboardController?.hide()
                    showModelSelector = true
                },
                onInputBarHeightChange = { inputBarHeight.intValue = it }
            )
        }
    }

    // Bottom sheets
    if (showDeleteAgentChatSheet) {
        var deleteContext by remember(uiState.conversationId) { mutableStateOf(false) }
        StandardModalBottomSheet(
            onDismissRequest = { showDeleteAgentChatSheet = false },
            title = "Delete chat"
        ) {
            Text("Clear this agent chat from the screen and history.")
            Row(
                modifier = Modifier.fillMaxWidth().clickable { deleteContext = !deleteContext },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = deleteContext, onCheckedChange = { deleteContext = it })
                Spacer(Modifier.width(8.dp))
                Text("Delete context too")
            }
            Button(
                onClick = {
                    showDeleteAgentChatSheet = false
                    doClearVisibleHistory(deleteContext)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Yes") }
            OutlinedButton(
                onClick = { showDeleteAgentChatSheet = false },
                modifier = Modifier.fillMaxWidth()
            ) { Text("No") }
        }
    }

    if (showTodoSheet && todoItems.isNotEmpty()) {
        TodoSheet(
            items = todoItems,
            onDismiss = { showTodoSheet = false }
        )
    }

    if (showConversationModeSheet && isRemoteMode && config?.showConversationModeSelector != false) {
        val providerModes = remember(uiState.sessionMode) {
            com.amaya.intelligence.impl.ide.IdeProviderFactory.get(uiState.sessionMode.ideId)
                ?.conversationModes
                ?: com.amaya.intelligence.domain.models.ConversationModeOption.ANTIGRAVITY
        }
        val selectedId = uiState.conversationModeId
            ?: uiState.conversationMode.wireValue
        ConversationModeSheet(
            options = providerModes,
            selectedId = selectedId,
            onSelect = { option ->
                viewModel.setConversationModeId(option.id)
                showConversationModeSheet = false
            },
            onDismiss = { showConversationModeSheet = false }
        )
    }

    if (showModelSelector) {
        ModelSelectorSheet(
            modelOptions = uiState.modelOptions,
            activeModelKey = activeModelKey,
            onSelect = { item ->
                doSelectModel(item.id)
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false }
        )
    }

    if (showSessionInfo && !isRemoteMode) {
        SessionInfoSheet(
            totalTokens = uiState.totalInputTokens + uiState.totalOutputTokens,
            activeModel = selectedModel,
            activeReminderCount = effectiveReminderCount,
            onDismiss = { showSessionInfo = false },
            inputTokens = uiState.totalInputTokens,
            outputTokens = uiState.totalOutputTokens,
            providerId = selectedModelItem?.providerId,
            providerNameOverride = selectedModelItem?.providerName,
            modelDisplayNameOverride = selectedModelItem?.name
        )
    }

    if (showBridgeSessionInfo && isBridgeMode) {
        var pendingEnableAgentControl by remember { mutableStateOf(false) }
        WindowsBridgeSessionInfoSheet(
            state = bridgeState,
            onToggleAgentControl = {
                if (bridgeState.isAgentControlEnabled) {
                    bridgeViewModel.disableAgentControl()
                } else {
                    pendingEnableAgentControl = true
                }
            },
            onCapture = { bridgeViewModel.captureScreen() },
            onClearCapture = { bridgeViewModel.clearCapture() },
            onDisconnect = {
                bridgeViewModel.disconnect()
                onExit()
            },
            onDismiss = { showBridgeSessionInfo = false }
        )
        if (pendingEnableAgentControl) {
            com.amaya.intelligence.ui.components.remote.WindowsBridgeAgentControlDialog(
                onConfirm = {
                    pendingEnableAgentControl = false
                    bridgeViewModel.confirmEnableAgentControl()
                },
                onDismiss = { pendingEnableAgentControl = false }
            )
        }
    }

    if (showDisconnectDialog) {
        val disconnectName = sessionDisconnectName
            ?: if (isBridgeMode) "Windows Bridge" else uiState.sessionMode.displayName()
        com.amaya.intelligence.ui.components.shared.SessionDisconnectDialog(
            sessionName = disconnectName,
            onConfirm = {
                showDisconnectDialog = false
                val handler = onConfirmSessionDisconnect
                if (handler != null) {
                    handler()
                } else if (isBridgeMode) {
                    bridgeViewModel.disconnect()
                }
                onExit()
            },
            onDismiss = { showDisconnectDialog = false }
        )
    }
}

@Composable
private fun ChatFloatingTopBar(
    title: String,
    subtitle: String?,
    isRemoteMode: Boolean,
    isBridgeMode: Boolean,
    onMenuClick: () -> Unit,
    onTitleClick: () -> Unit,
    showNewChat: Boolean,
    onNewChatClick: () -> Unit,
    showAgentMenu: Boolean,
    onAgentMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    agentMenu: @Composable () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val orbColor = if (isDark) Color(0xFF202228).copy(alpha = 0.92f) else Color(0xFFFAFAFC).copy(alpha = 0.96f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)


    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiquidOrbButton(
            icon = Icons.Default.Menu,
            contentDescription = "Menu",
            color = orbColor,
            borderColor = borderColor,
            onClick = onMenuClick
        )

        Spacer(modifier = Modifier.width(12.dp))

        if (title.isNotEmpty() || !subtitle.isNullOrBlank()) {
            FadingTitleLayout(
                title = title,
                subtitle = subtitle,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTitleClick
                    )
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.width(18.dp))

        when {
            // The menu is composed inside the button's own Box so the popup anchors to the orb.
            // It used to be emitted at screen level with a hardcoded DpOffset(180.dp, 48.dp),
            // which put it wherever that offset happened to land on a given screen size.
            showAgentMenu -> Box {
                LiquidOrbButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "Agent menu",
                    color = orbColor,
                    borderColor = borderColor,
                    onClick = onAgentMenuClick
                )
                agentMenu()
            }
            showNewChat -> LiquidOrbButton(
                icon = Icons.Default.Add,
                contentDescription = "New Chat",
                color = orbColor,
                borderColor = borderColor,
                onClick = onNewChatClick
            )
            else -> Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun AgentChatMenu(
    expanded: Boolean,
    onOpenBrowser: () -> Unit,
    onConfigure: () -> Unit,
    onDeleteChat: () -> Unit,
    onDismiss: () -> Unit
) {
    com.amaya.intelligence.ui.components.shared.AmayaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        alignment = com.amaya.intelligence.ui.components.shared.AmayaMenuAlignment.End,
        placement = com.amaya.intelligence.ui.components.shared.AmayaMenuPlacement.Below
    ) {
        com.amaya.intelligence.ui.components.shared.AmayaDropdownMenuItem(
            text = "Open browser",
            icon = Icons.Default.OpenInBrowser,
            onClick = onOpenBrowser
        )
        com.amaya.intelligence.ui.components.shared.AmayaDropdownMenuItem(
            text = "Configure agent",
            icon = Icons.Default.Settings,
            onClick = onConfigure
        )
        com.amaya.intelligence.ui.components.shared.AmayaDropdownMenuItem(
            text = "Delete chat",
            icon = Icons.Default.Delete,
            destructive = true,
            onClick = onDeleteChat
        )
    }
}

@Composable
private fun FadingTitleLayout(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    var isOverflowing by remember { mutableStateOf(false) }
    val renderedTitle = rememberHyperText(title)

    androidx.compose.ui.layout.Layout(
        modifier = modifier,
        content = {
            Column(
                modifier = Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (isOverflowing) {
                            val fadeStartPx = 64.dp.toPx()
                            val fadeEndPx = 24.dp.toPx()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Black,
                                        0.2f to Color.Black.copy(alpha = 0.9f),
                                        0.4f to Color.Black.copy(alpha = 0.7f),
                                        0.6f to Color.Black.copy(alpha = 0.4f),
                                        0.8f to Color.Black.copy(alpha = 0.15f),
                                        1.0f to Color.Transparent
                                    ),
                                    startX = size.width - fadeStartPx,
                                    endX = size.width - fadeEndPx
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                    }
            ) {
                if (title.isNotEmpty()) {
                    Text(
                        text = renderedTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Session Info",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp).size(20.dp)
            )
        }
    ) { measurables, constraints ->
        val textMeasurable = measurables[0]
        val iconMeasurable = measurables[1]

        val iconPlaceable = iconMeasurable.measure(constraints.copy(minWidth = 0))
        val iconWidth = iconPlaceable.width

        val textIntrinsicWidth = textMeasurable.maxIntrinsicWidth(constraints.maxHeight)
        val totalIntrinsicWidth = textIntrinsicWidth + iconWidth

        val overflowing = totalIntrinsicWidth > constraints.maxWidth

        val textPlaceable = textMeasurable.measure(
            if (overflowing) {
                constraints.copy(maxWidth = constraints.maxWidth, minWidth = 0)
            } else {
                constraints.copy(maxWidth = textIntrinsicWidth, minWidth = 0)
            }
        )

        if (isOverflowing != overflowing) {
            isOverflowing = overflowing
        }

        val width = if (constraints.hasBoundedWidth && constraints.minWidth == constraints.maxWidth) {
            constraints.maxWidth
        } else if (overflowing) {
            constraints.maxWidth
        } else {
            totalIntrinsicWidth
        }

        val height = maxOf(textPlaceable.height, iconPlaceable.height)

        layout(width, height) {
            textPlaceable.placeRelative(0, (height - textPlaceable.height) / 2)
            if (overflowing) {
                iconPlaceable.placeRelative(width - iconWidth, (height - iconPlaceable.height) / 2)
            } else {
                iconPlaceable.placeRelative(textPlaceable.width, (height - iconPlaceable.height) / 2)
            }
        }
    }
}

@Composable
private fun LiquidOrbButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    color: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        border = BorderStroke(0.7.dp, borderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.size(42.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
