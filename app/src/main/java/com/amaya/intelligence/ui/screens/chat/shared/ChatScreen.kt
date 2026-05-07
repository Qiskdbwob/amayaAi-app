package com.amaya.intelligence.ui.screens.chat.shared

import com.amaya.intelligence.ui.viewmodels.ChatViewModel

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.ui.components.shared.ConfirmationDialog
import com.amaya.intelligence.ui.components.shared.ConversationModeSheet
import com.amaya.intelligence.ui.components.shared.LocalhostLinkBottomSheet
import com.amaya.intelligence.ui.components.shared.LocalhostLinkInfo
import com.amaya.intelligence.ui.components.shared.LocalhostLinkInfoParser
import com.amaya.intelligence.ui.components.shared.ModelSelectorSheet
import com.amaya.intelligence.ui.components.local.SessionInfoSheet
import com.amaya.intelligence.ui.components.local.TodoSheet
import com.amaya.intelligence.utils.NetworkUtils
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val CHAT_DRAWER_LOG_TAG = "ChatDrawerDebug"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    activeReminderCount: Int = -1,
    isRemoteModeOverride: Boolean? = null,
    config: ChatScreenConfig? = null,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkspace: () -> Unit = {},
    onNavigateToRemoteSession: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val confirmationRequest by viewModel.confirmationRequest.collectAsState()
    val todoItems by viewModel.todoItems.collectAsState()
    val localReminderCount by viewModel.activeReminderCount.collectAsState()
    val effectiveReminderCount = if (activeReminderCount >= 0) activeReminderCount else localReminderCount
    val scrollEventFlow = viewModel.scrollEvent
    val conversationsFlow = viewModel.conversations

    val isRemoteMode = isRemoteModeOverride ?: uiState.sessionMode.isRemote()
    val connectionState = uiState.connectionState
    val workspaces by viewModel.workspaces.collectAsState()

    // Action delegates
    val doSendMessage: (String) -> Unit = remember(viewModel) { { viewModel.sendMessage(it) } }
    val doSendMessageWithImage: (String, String, String, String) -> Unit = remember(viewModel) {
        { content, base64, mime, name -> viewModel.sendMessageWithImage(content, base64, mime, name) }
    }
    val doStopGeneration: () -> Unit = remember(viewModel) { { viewModel.stopGeneration() } }
    val doClearConversation: () -> Unit = remember(viewModel) { { viewModel.clearConversation() } }
    val doRespondToConfirmation: (Boolean) -> Unit = remember(viewModel) { { viewModel.respondToConfirmation(it) } }
    val doSetSelectedAgent: (String) -> Unit = remember(viewModel) { { viewModel.setSelectedAgent(it) } }
    val doLoadConversation: (Long) -> Unit = remember(viewModel) { { viewModel.loadConversation(it) } }
    val doDeleteConversation: (Long) -> Unit = remember(viewModel) { { viewModel.deleteConversation(it) } }
    val doClearError: () -> Unit = remember(viewModel) { { viewModel.clearError() } }
    val doHasMoreConversations: () -> Boolean = remember(viewModel) { { viewModel.hasMoreConversations() } }
    val doLoadMoreConversations: () -> Unit = remember(viewModel) { { viewModel.loadMoreConversations() } }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showModelSelector by remember { mutableStateOf(false) }
    var showSessionInfo by remember { mutableStateOf(false) }
    var showTodoSheet by remember { mutableStateOf(false) }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var inputBarHeight by remember { mutableStateOf(0) }
    var attachedFilePath by remember(uiState.conversationId) { mutableStateOf<String?>(null) }
    var attachedImageBase64 by remember(uiState.conversationId) { mutableStateOf<String?>(null) }
    var attachedImageMimeType by remember(uiState.conversationId) { mutableStateOf<String?>(null) }
    var attachedImageName by remember(uiState.conversationId) { mutableStateOf<String?>(null) }
    var showConversationModeSheet by remember { mutableStateOf(false) }

    var showLocalhostLinkSheet by remember { mutableStateOf(false) }
    var selectedLocalhostLink by remember { mutableStateOf<LocalhostLinkInfo?>(null) }
    var localIp by remember { mutableStateOf("127.0.0.1") }
    
    // Get local IP address on launch as fallback
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            localIp = NetworkUtils.getLocalIpAddress()
        }
    }

    // Lifted input text state
    var inputText by remember(uiState.conversationId) { mutableStateOf("") }

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

    val activeAgentId = uiState.activeAgentId
    val activeProviderId = uiState.activeProviderId
    val activeAgent = uiState.agentConfigs.find { it.id == activeAgentId }
    val selectedModel = uiState.selectedModel.ifBlank { activeAgent?.modelId ?: "" }
    val selectedModelItem = uiState.agentConfigs.firstOrNull { item ->
        item.modelId == selectedModel && (activeProviderId.isBlank() || item.providerId == activeProviderId)
    } ?: activeAgent ?: uiState.agentConfigs.firstOrNull()
    val selectedAgentFallbackLabel = config?.selectedAgentFallbackLabel ?: "Select Agent"
    val streamingLabel = config?.streamingLabel ?: "Streaming"
    val idleLabel = config?.idleLabel ?: "Idle"

    val onToolAccept: ((ToolExecution) -> Unit)? = remember(viewModel) {
        { execution: ToolExecution -> viewModel.respondToToolInteraction(execution.toolCallId, true) }
    }
    val onToolDecline: ((ToolExecution) -> Unit)? = remember(viewModel) {
        { execution: ToolExecution -> viewModel.respondToToolInteraction(execution.toolCallId, false) }
    }

    val displayMessages = remember(uiState.messages) {
        uiState.messages.filter {
            it.content.isNotBlank() ||
            !it.thinking.isNullOrBlank() ||
            it.steps.isNotEmpty()
        }
    }

    // Auto-scroll
    var shouldAutoScroll by remember { mutableStateOf(true) }

    val performScrollToBottom: suspend (Boolean) -> Unit = { animated ->
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total > 0) {
            if (animated) {
                val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
                if (lastVisible != null) {
                    val distance = (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
                    if (distance > 0) {
                        listState.animateScrollBy(distance.toFloat())
                    }
                } else {
                    listState.animateScrollToItem(total - 1)
                    val newInfo = listState.layoutInfo
                    val newLast = newInfo.visibleItemsInfo.lastOrNull { it.index == total - 1 }
                    if (newLast != null) {
                        val newDistance = (newLast.offset + newLast.size) - newInfo.viewportEndOffset
                        if (newDistance > 0) {
                            listState.animateScrollBy(newDistance.toFloat())
                        }
                    }
                }
            } else {
                listState.scrollToItem(total - 1, Int.MAX_VALUE)
            }
        }
    }

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                shouldAutoScroll = false
            }
        }
    }

    LaunchedEffect(listState.canScrollForward) {
        if (!listState.canScrollForward) {
            shouldAutoScroll = true
        }
    }

    LaunchedEffect(Unit) {
        scrollEventFlow.collect { reason ->
            when (reason) {
                ChatViewModel.ScrollReason.NEW_MESSAGE -> {
                    shouldAutoScroll = true
                    delay(150)
                    performScrollToBottom(true)
                }
                ChatViewModel.ScrollReason.NEW_TOOL -> {
                    if (shouldAutoScroll) {
                        delay(150)
                        performScrollToBottom(true)
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState.isStreaming, shouldAutoScroll) {
        if (!uiState.isStreaming || !shouldAutoScroll) return@LaunchedEffect
        while (true) {
            performScrollToBottom(false)
            delay(120)
        }
    }

    LaunchedEffect(displayMessages.size) {
        if (shouldAutoScroll) {
            delay(100)
            performScrollToBottom(true)
        }
    }

    LaunchedEffect(isRemoteMode, displayMessages.size) {
        if (!isRemoteMode || displayMessages.isEmpty()) return@LaunchedEffect

        // Remote prompts can arrive outside the local send path, so no scroll event is emitted.
        // Keep the latest remote turn visible whenever the message list grows.
        if (shouldAutoScroll) {
            delay(120)
            performScrollToBottom(true)
        }
    }

    confirmationRequest?.let { request ->
        ConfirmationDialog(
            request = request,
            onConfirm = { doRespondToConfirmation(true) },
            onDismiss = { doRespondToConfirmation(false) }
        )
    }

    BackHandler(enabled = uiState.messages.isNotEmpty() || (isRemoteMode && isRemoteModeOverride == true)) {
        if (isRemoteMode) onExit() else doClearConversation()
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val conversations by conversationsFlow.collectAsState()
    val selectedModelLabel = (selectedModelItem?.name ?: selectedModel).ifBlank { selectedAgentFallbackLabel }
    val activeConversationTitle = remember(conversations, uiState.conversationId) {
        conversations.firstOrNull { it.id.toString() == uiState.conversationId }?.title
    }
    val topBarTitle = if (!activeConversationTitle.isNullOrBlank() && uiState.messages.isNotEmpty()) {
        activeConversationTitle
    } else {
        selectedModelLabel
    }
    val topBarSubtitle = if (!activeConversationTitle.isNullOrBlank() && uiState.messages.isNotEmpty()) {
        selectedModelLabel
    } else {
        ""
    }

    // WindowInsets
    val statusBarInsets = WindowInsets.statusBars.asPaddingValues()
    val statusBarHeight = statusBarInsets.calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val headerDp = statusBarHeight + 84.dp
    val bottomDp = 80.dp + navBarHeight
    val bgColor = MaterialTheme.colorScheme.background

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val chatShift = configuration.screenWidthDp.dp * 0.18f
    val drawerTargetOpen = drawerState.targetValue == DrawerValue.Open
    val drawerProgress by animateFloatAsState(
        targetValue = if (drawerTargetOpen) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "chatDrawerProgress"
    )
    val drawerDragAnim = remember { Animatable(0f) }
    var drawerDragPx by remember { mutableFloatStateOf(0f) }
    var drawerDragging by remember { mutableStateOf(false) }
    var drawerDragStartX by remember { mutableFloatStateOf(0f) }
    var drawerDragStartY by remember { mutableFloatStateOf(0f) }
    var drawerDragLastX by remember { mutableFloatStateOf(0f) }
    var drawerDragLastY by remember { mutableFloatStateOf(0f) }
    val displayedDrawerDragPx = if (drawerDragging) drawerDragPx else drawerDragAnim.value
    var drawerWidthPx by remember { mutableFloatStateOf(with(density) { configuration.screenWidthDp.dp.toPx() }.coerceAtLeast(1f)) }
    val effectiveDrawerProgress = (drawerProgress + (displayedDrawerDragPx / drawerWidthPx)).coerceIn(0f, 1f)
    var lastLoggedEffectiveDrawerProgress by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(Unit) {
        snapshotFlow {
            "current=${drawerState.currentValue}, target=${drawerState.targetValue}, " +
                "running=${drawerState.isAnimationRunning}, isOpen=${drawerState.isOpen}"
        }.collect { state ->
            android.util.Log.d(CHAT_DRAWER_LOG_TAG, "state $state")
        }
    }

    LaunchedEffect(effectiveDrawerProgress) {
        val shouldLog = lastLoggedEffectiveDrawerProgress < 0f ||
            kotlin.math.abs(effectiveDrawerProgress - lastLoggedEffectiveDrawerProgress) >= 0.05f ||
            effectiveDrawerProgress <= 0.01f ||
            effectiveDrawerProgress >= 0.99f
        if (shouldLog) {
            android.util.Log.d(
                CHAT_DRAWER_LOG_TAG,
                "progress effective=$effectiveDrawerProgress, drawerProgress=$drawerProgress, " +
                    "dragPx=$displayedDrawerDragPx, dragging=$drawerDragging, width=$drawerWidthPx"
            )
            lastLoggedEffectiveDrawerProgress = effectiveDrawerProgress
        }
    }

    suspend fun closeDrawerFromDragPosition(snapPx: Float) {
        android.util.Log.d(
            CHAT_DRAWER_LOG_TAG,
            "closeFromDrag start snapPx=$snapPx, start=($drawerDragStartX,$drawerDragStartY), " +
                "last=($drawerDragLastX,$drawerDragLastY), current=${drawerState.currentValue}, " +
                "target=${drawerState.targetValue}, progress=$effectiveDrawerProgress"
        )
        // Keep rendering from the exact finger release coordinate, then move the same
        // drag offset fully off-screen before asking DrawerState to close. Calling
        // drawerState.close() first can retarget drawerProgress from 1f and create
        // a one-frame bounce back to open.
        drawerDragAnim.snapTo(snapPx)
        drawerDragging = false
        drawerDragAnim.animateTo(
            targetValue = -drawerWidthPx,
            animationSpec = tween(180, easing = FastOutSlowInEasing)
        )
        android.util.Log.d(
            CHAT_DRAWER_LOG_TAG,
            "closeFromDrag before drawerState.close animValue=${drawerDragAnim.value}, " +
                "current=${drawerState.currentValue}, target=${drawerState.targetValue}, progress=$effectiveDrawerProgress"
        )
        drawerState.close()
        android.util.Log.d(
            CHAT_DRAWER_LOG_TAG,
            "closeFromDrag after drawerState.close current=${drawerState.currentValue}, " +
                "target=${drawerState.targetValue}, progress=$effectiveDrawerProgress"
        )
        // Do not reset the drag offset immediately. drawerProgress is still 1f for
        // the next frame after DrawerState flips to Closed; resetting here is the
        // bounce seen in the logs (effective progress jumps 0 -> 1 -> 0).
        delay(350)
        if (drawerState.currentValue == DrawerValue.Closed && drawerState.targetValue == DrawerValue.Closed) {
            drawerDragAnim.snapTo(0f)
            drawerDragPx = 0f
            android.util.Log.d(CHAT_DRAWER_LOG_TAG, "closeFromDrag drag offset reset after close animation")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = with(density) { chatShift.toPx() } * effectiveDrawerProgress
                    val scale = 1f - (0.035f * effectiveDrawerProgress)
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape((24f * effectiveDrawerProgress).dp))
        ) {
            val drawerOpen = drawerState.isOpen
            var showSkeletonOverride by remember { mutableStateOf(false) }

            LaunchedEffect(uiState.conversationId) {
                if (isRemoteMode && uiState.conversationId != null) {
                    showSkeletonOverride = true
                    delay(1200)
                    showSkeletonOverride = false
                } else {
                    showSkeletonOverride = false
                }
            }

            // Content area
            if (uiState.messages.isEmpty()) {
                ChatEmptyContent(
                    isRemoteMode = isRemoteMode,
                    connectionState = connectionState,
                    uiState = uiState,
                    showSkeletonOverride = showSkeletonOverride,
                    headerDp = headerDp,
                    bottomDp = bottomDp,
                    drawerOpen = drawerOpen,
                    onInputTextChange = { inputText = it },
                    onSendMessage = doSendMessage,
                    onNavigateToWorkspace = onNavigateToWorkspace,
                    workspaces = workspaces
                )
            } else {
                ChatMessageList(
                    listState = listState,
                    displayMessages = displayMessages,
                    isLoading = uiState.isLoading,
                    isStreaming = uiState.isStreaming,
                    isRemoteMode = isRemoteMode,
                    headerDp = headerDp,
                    inputBarHeight = inputBarHeight,
                    drawerOpen = drawerOpen,
                    onToolAccept = onToolAccept,
                    onToolDecline = onToolDecline,
                    onLocalhostLinkClick = { annotationItem ->
                        selectedLocalhostLink = LocalhostLinkInfoParser.parse(annotationItem, serverIp)
                        showLocalhostLinkSheet = true
                    },
                    onContentResized = {
                        if (shouldAutoScroll) {
                            scope.launch { performScrollToBottom(true) }
                        }
                    },
                    onScrollToBottomClick = {
                        shouldAutoScroll = true
                        scope.launch { performScrollToBottom(true) }
                    },
                    shouldAutoScroll = shouldAutoScroll,
                    scope = scope
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
                isStreaming = uiState.isStreaming,
                streamingLabel = streamingLabel,
                idleLabel = idleLabel,
                onMenuClick = {
                    keyboardController?.hide()
                    scope.launch {
                        android.util.Log.d(
                            CHAT_DRAWER_LOG_TAG,
                            "menuClick open requested current=${drawerState.currentValue}, target=${drawerState.targetValue}"
                        )
                        drawerDragAnim.snapTo(0f)
                        drawerDragPx = 0f
                        drawerState.open()
                        android.util.Log.d(
                            CHAT_DRAWER_LOG_TAG,
                            "menuClick open finished current=${drawerState.currentValue}, target=${drawerState.targetValue}"
                        )
                    }
                },
                onTitleClick = { showModelSelector = true },
                onMoreClick = {
                    when {
                        isRemoteMode -> viewModel.refreshState()
                        todoItems.isNotEmpty() -> showTodoSheet = true
                        else -> showSessionInfo = true
                    }
                },
                modifier = Modifier.align(Alignment.TopStart)
            )

            // Bottom section
            ChatBottomSection(
                modifier = Modifier.align(Alignment.BottomStart),
                inputText = inputText,
                onInputTextChange = { inputText = it },
                isRemoteMode = isRemoteMode,
                uiState = uiState,
                connectionState = connectionState,
                drawerOpen = drawerOpen,
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
                onSendMessageWithImage = doSendMessageWithImage,
                onClearImageAttachment = {
                    attachedImageBase64 = null
                    attachedImageMimeType = null
                    attachedImageName = null
                },
                onStopGeneration = doStopGeneration,
                onNavigateToWorkspace = onNavigateToWorkspace,
                onShowConversationModeSheet = { showConversationModeSheet = true },
                onInputBarHeightChange = { inputBarHeight = it }
            )
        }

        if (effectiveDrawerProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .graphicsLayer {
                        translationX = with(density) { chatShift.toPx() } * effectiveDrawerProgress
                        val scale = 1f - (0.035f * effectiveDrawerProgress)
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape((24f * effectiveDrawerProgress).dp))
                    .clickable {
                        scope.launch {
                            android.util.Log.d(
                                CHAT_DRAWER_LOG_TAG,
                                "scrimClick close requested current=${drawerState.currentValue}, target=${drawerState.targetValue}, " +
                                    "progress=$effectiveDrawerProgress"
                            )
                            drawerState.close()
                            android.util.Log.d(
                                CHAT_DRAWER_LOG_TAG,
                                "scrimClick close finished current=${drawerState.currentValue}, target=${drawerState.targetValue}, " +
                                    "progress=$effectiveDrawerProgress"
                            )
                        }
                    }
            )
        }

        // Only render and intercept gestures when drawer is open or animating
        val drawerInteractive = drawerState.isOpen || drawerState.isAnimationRunning || effectiveDrawerProgress > 0.01f
        if (drawerInteractive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        drawerWidthPx = size.width.coerceAtLeast(1f)
                        translationX = (-size.width * (1f - effectiveDrawerProgress)) +
                            (with(density) { (-28).dp.toPx() } * (1f - effectiveDrawerProgress))
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                drawerDragging = true
                                drawerDragPx = drawerDragAnim.value
                                drawerDragStartX = offset.x
                                drawerDragStartY = offset.y
                                drawerDragLastX = offset.x
                                drawerDragLastY = offset.y
                                android.util.Log.d(
                                    CHAT_DRAWER_LOG_TAG,
                                    "dragStart pointer=(${offset.x},${offset.y}), dragPx=$drawerDragPx, " +
                                        "current=${drawerState.currentValue}, target=${drawerState.targetValue}, " +
                                        "progress=$effectiveDrawerProgress, width=$drawerWidthPx"
                                )
                                scope.launch { drawerDragAnim.stop() }
                            },
                            onDragEnd = {
                                val snapPx = drawerDragPx
                                android.util.Log.d(
                                    CHAT_DRAWER_LOG_TAG,
                                    "dragEnd snapPx=$snapPx, start=($drawerDragStartX,$drawerDragStartY), " +
                                        "last=($drawerDragLastX,$drawerDragLastY), current=${drawerState.currentValue}, " +
                                        "target=${drawerState.targetValue}, progress=$effectiveDrawerProgress"
                                )
                                scope.launch { closeDrawerFromDragPosition(snapPx) }
                            },
                            onDragCancel = {
                                val snapPx = drawerDragPx
                                android.util.Log.d(
                                    CHAT_DRAWER_LOG_TAG,
                                    "dragCancel snapPx=$snapPx, start=($drawerDragStartX,$drawerDragStartY), " +
                                        "last=($drawerDragLastX,$drawerDragLastY), current=${drawerState.currentValue}, " +
                                        "target=${drawerState.targetValue}, progress=$effectiveDrawerProgress"
                                )
                                scope.launch { closeDrawerFromDragPosition(snapPx) }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                drawerDragLastX = change.position.x
                                drawerDragLastY = change.position.y
                                drawerDragPx = (drawerDragPx + dragAmount).coerceIn(-drawerWidthPx, 0f)
                                android.util.Log.d(
                                    CHAT_DRAWER_LOG_TAG,
                                    "dragMove pointer=(${change.position.x},${change.position.y}), " +
                                        "dragAmount=$dragAmount, dragPx=$drawerDragPx, progress=$effectiveDrawerProgress"
                                )
                            }
                        )
                    }
                    .zIndex(2f)
            ) {
            ChatDrawerContent(
                drawerState = drawerState,
                activeConversationId = uiState.conversationId,
                isRemoteMode = isRemoteMode,
                sessionMode = uiState.sessionMode,
                workspacePath = uiState.workspacePath,
                isLoadingConversations = uiState.isLoadingConversations,
                connectionState = connectionState,
                conversations = conversations,
                onLoadConversation = doLoadConversation,
                onDeleteConversation = doDeleteConversation,
                onClearConversation = doClearConversation,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToWorkspace = onNavigateToWorkspace,
                onNavigateToRemoteSession = onNavigateToRemoteSession,
                onExit = onExit,
                hasMoreConversations = doHasMoreConversations,
                loadMoreConversations = doLoadMoreConversations,
                scope = scope
            )
        }
        }
    }

    // Bottom sheets
    if (showTodoSheet && todoItems.isNotEmpty()) {
        TodoSheet(
            items = todoItems,
            onDismiss = { showTodoSheet = false }
        )
    }

    if (showConversationModeSheet && isRemoteMode) {
        ConversationModeSheet(
            currentMode = uiState.conversationMode,
            onSelect = { mode ->
                viewModel.setConversationMode(mode)
                showConversationModeSheet = false
            },
            onDismiss = { showConversationModeSheet = false }
        )
    }

    if (showModelSelector) {
        ModelSelectorSheet(
            agentItems = uiState.agentConfigs,
            activeAgentId = activeAgentId,
            activeModel = selectedModel,
            activeProviderId = activeProviderId.ifBlank { selectedModelItem?.providerId.orEmpty() },
            isRemote = isRemoteMode,
            onSelect = { item ->
                doSetSelectedAgent(item.id)
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
            providerId = selectedModelItem?.providerId ?: activeAgent?.providerId,
            providerNameOverride = selectedModelItem?.providerName,
            modelDisplayNameOverride = selectedModelItem?.name,
            sourceLabelOverride = selectedModelItem?.sourceLabel,
            contextWindowTokensOverride = selectedModelItem?.contextWindowTokens,
            maxOutputTokensOverride = selectedModelItem?.maxOutputTokens,
            capabilityLabelsOverride = selectedModelItem?.capabilityLabels.orEmpty(),
            inputPriceOverride = selectedModelItem?.inputPricePerMillionTokens,
            outputPriceOverride = selectedModelItem?.outputPricePerMillionTokens
        )
    }
}

@Composable
private fun ChatFloatingTopBar(
    title: String,
    subtitle: String,
    isRemoteMode: Boolean,
    isStreaming: Boolean,
    streamingLabel: String,
    idleLabel: String,
    onMenuClick: () -> Unit,
    onTitleClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val micaColor = if (isDark) Color(0xFF1D1F24).copy(alpha = 0.92f) else Color(0xFFF7F7FA).copy(alpha = 0.94f)
    val orbColor = if (isDark) Color(0xFF202228).copy(alpha = 0.92f) else Color(0xFFFAFAFC).copy(alpha = 0.96f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    val effectiveSubtitle = subtitle
    val secondaryText = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.68f else 0.60f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        LiquidOrbButton(
            icon = Icons.Default.Menu,
            contentDescription = "Menu",
            color = orbColor,
            borderColor = borderColor,
            onClick = onMenuClick,
            modifier = Modifier.align(Alignment.CenterStart)
        )

        Surface(
            onClick = onTitleClick,
            shape = RoundedCornerShape(999.dp),
            color = micaColor,
            border = BorderStroke(0.7.dp, borderColor),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .width(224.dp)
                .heightIn(min = 52.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = if (effectiveSubtitle.isBlank()) 14.dp else 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            lineHeight = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (effectiveSubtitle.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isRemoteMode) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(if (isStreaming) MaterialTheme.colorScheme.primary else secondaryText.copy(alpha = 0.7f))
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                text = effectiveSubtitle,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
                                color = secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        LiquidOrbButton(
            icon = if (isRemoteMode) Icons.Default.Refresh else Icons.Default.MoreVert,
            contentDescription = if (isRemoteMode) "Refresh" else "More",
            color = orbColor,
            borderColor = borderColor,
            onClick = onMoreClick,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
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
        modifier = modifier.size(44.dp)
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
