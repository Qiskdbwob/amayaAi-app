package com.amaya.intelligence.ui.screens.chat.shared

import com.amaya.intelligence.ui.viewmodels.ChatViewModel

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.ai.displayName
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.ui.components.shared.ConfirmationDialog
import com.amaya.intelligence.ui.components.shared.ConversationModeSheet
import com.amaya.intelligence.ui.components.shared.LocalhostLinkBottomSheet
import com.amaya.intelligence.ui.components.shared.LocalhostLinkInfo
import com.amaya.intelligence.ui.components.shared.LocalhostLinkInfoParser
import com.amaya.intelligence.ui.components.shared.ModelSelectorSheet
import com.amaya.intelligence.ui.components.remote.WindowsBridgeChatPanelViewModel
import com.amaya.intelligence.ui.components.remote.WindowsBridgeSessionInfoSheet
import com.amaya.intelligence.ui.components.local.SessionInfoSheet
import com.amaya.intelligence.ui.components.local.TodoSheet
import com.amaya.intelligence.utils.NetworkUtils
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


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
    val confirmationRequest by viewModel.confirmationRequest.collectAsState()
    val todoItems by viewModel.todoItems.collectAsState()
    val localReminderCount by viewModel.activeReminderCount.collectAsState()
    val effectiveReminderCount = if (activeReminderCount >= 0) activeReminderCount else localReminderCount
    val scrollEventFlow = viewModel.scrollEvent
    val conversationsFlow = viewModel.conversations

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
    val doRespondToConfirmation: (Boolean) -> Unit = remember(viewModel) { { viewModel.respondToConfirmation(it) } }
    val doSetSelectedAgent: (String) -> Unit = remember(viewModel) { { viewModel.setSelectedAgent(it) } }
    val doLoadConversation: (Long) -> Unit = remember(viewModel) { { viewModel.loadConversation(it) } }
    val doDeleteConversation: (Long) -> Unit = remember(viewModel) { { viewModel.deleteConversation(it) } }
    val doClearError: () -> Unit = remember(viewModel) { { viewModel.clearError() } }
    val doHasMoreConversations: () -> Boolean = remember(viewModel) { { viewModel.hasMoreConversations() } }
    val doLoadMoreConversations: () -> Unit = remember(viewModel) { { viewModel.loadMoreConversations() } }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerVisible = drawerState.currentValue == DrawerValue.Open ||
        drawerState.targetValue == DrawerValue.Open

    var showModelSelector by remember { mutableStateOf(false) }
    var showSessionInfo by remember { mutableStateOf(false) }
    var showBridgeSessionInfo by remember { mutableStateOf(false) }
    var showTodoSheet by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val inputBarHeight = remember { mutableIntStateOf(0) }
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

    val inputText = remember(uiState.conversationId) { mutableStateOf("") }

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

    val onToolAccept: ((ToolExecution) -> Unit)? = remember(viewModel) {
        { execution: ToolExecution -> viewModel.respondToToolInteraction(execution.toolCallId, true) }
    }
    val onToolDecline: ((ToolExecution) -> Unit)? = remember(viewModel) {
        { execution: ToolExecution -> viewModel.respondToToolInteraction(execution.toolCallId, false) }
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

    LaunchedEffect(uiState.isStreaming, shouldAutoScroll, drawerVisible) {
        if (!uiState.isStreaming || !shouldAutoScroll || drawerVisible) return@LaunchedEffect
        while (true) {
            performScrollToBottom(false)
            delay(16)
        }
    }

    LaunchedEffect(displayMessages.size, drawerVisible) {
        if (shouldAutoScroll && !drawerVisible) {
            delay(100)
            performScrollToBottom(true)
        }
    }

    LaunchedEffect(isRemoteMode, displayMessages.size, drawerVisible) {
        if (!isRemoteMode || displayMessages.isEmpty() || drawerVisible) return@LaunchedEffect

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
    val selectedModelLabel = (selectedModelItem?.name ?: selectedModel).ifBlank { selectedAgentFallbackLabel }
    val activeConversationTitle = remember(conversations, uiState.conversationId) {
        conversations.firstOrNull { it.id.toString() == uiState.conversationId }?.title
    }
    val topBarTitle = activeConversationTitle
        ?.takeIf { uiState.messages.isNotEmpty() }
        ?: "Amaya"

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
                    isLoadingConversations = uiState.isLoadingConversations,
                    connectionState = connectionState,
                    conversations = conversations,
                    onLoadConversation = doLoadConversation,
                    onDeleteConversation = doDeleteConversation,
                    onClearConversation = doClearConversation,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToWorkspace = onNavigateToWorkspace,
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
                    isLoading = uiState.isLoading,
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
                isRemoteMode = isRemoteMode,
                isBridgeMode = isBridgeMode,
                onMenuClick = {
                    keyboardController?.hide()
                    scope.launch { drawerState.open() }
                },
                onMoreClick = {
                    when {
                        isBridgeMode -> showBridgeSessionInfo = true
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
                onSendMessageWithImage = doSendMessageWithImage,
                onClearImageAttachment = {
                    attachedImageBase64 = null
                    attachedImageMimeType = null
                    attachedImageName = null
                },
                onStopGeneration = doStopGeneration,
                onNavigateToWorkspace = onNavigateToWorkspace,
                showConversationModeSelector = config?.showConversationModeSelector ?: isRemoteMode,
                onShowConversationModeSheet = { showConversationModeSheet = true },
                modelLabel = selectedModelLabel,
                onSelectModel = {
                    keyboardController?.hide()
                    showModelSelector = true
                },
                onInputBarHeightChange = { inputBarHeight.intValue = it }
            )
        }
    }

    // Bottom sheets
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
    isRemoteMode: Boolean,
    isBridgeMode: Boolean,
    onMenuClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val micaColor = if (isDark) Color(0xFF1D1F24).copy(alpha = 0.92f) else Color(0xFFF7F7FA).copy(alpha = 0.94f)
    val orbColor = if (isDark) Color(0xFF202228).copy(alpha = 0.92f) else Color(0xFFFAFAFC).copy(alpha = 0.96f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)


    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 2.dp)
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
            shape = RoundedCornerShape(999.dp),
            color = micaColor,
            border = BorderStroke(0.7.dp, borderColor),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .width(176.dp)
                .height(44.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LiquidOrbButton(
            icon = if (isRemoteMode && !isBridgeMode) Icons.Default.Refresh else Icons.Default.MoreVert,
            contentDescription = if (isRemoteMode && !isBridgeMode) "Refresh" else "More",
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
