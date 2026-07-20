package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.domain.models.ConversationMode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

data class ChatMentionAgent(val id: Long, val name: String, val role: String, val groupName: String)

@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    resetKey: Any? = null,
    isStreaming: Boolean,
    attachedFilePath: String? = null,
    attachedImageBase64: String? = null,
    attachedImageName: String? = null,
    onAttachFile: () -> Unit = {},
    onAttachImage: (() -> Unit)? = null,
    onClearAttachment: () -> Unit = {},
    onClearImageAttachment: () -> Unit = {},
    conversationMode: ConversationMode = ConversationMode.PLANNING,
    conversationModeLabel: String? = null,
    conversationModeIsFast: Boolean = conversationMode == ConversationMode.FAST,
    showConversationModeSelector: Boolean = false,
    onShowConversationModeSelector: () -> Unit = {},
    workspacePath: String? = null,
    assistantMode: AssistantMode = AssistantMode.CHAT,
    ownerLabel: String = "Chat",
    showWorkspaceCard: Boolean = true,
    onWorkspaceClick: () -> Unit = {},
    mentionAgents: List<ChatMentionAgent> = emptyList(),
    onSearchWorkspaceFiles: suspend (String) -> List<com.amaya.intelligence.domain.models.ProjectFileEntry> = { emptyList() },
    modelLabel: String = "Select Model",
    modelId: String = "",
    modelProviderId: String? = null,
    modelIconType: String? = null,
    onSelectModel: () -> Unit = {},
    effort: com.amaya.intelligence.data.remote.api.ThinkingEffort = com.amaya.intelligence.data.remote.api.ThinkingEffort.MEDIUM,
    onEffortChange: (com.amaya.intelligence.data.remote.api.ThinkingEffort) -> Unit = {},
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val hasAttachment = attachedFilePath != null || attachedImageBase64 != null
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val referenceColor = MaterialTheme.colorScheme.primary
    val referenceTransformation = remember(referenceColor) { ComposerReferenceTransformation(referenceColor) }
    val expansion = imeAnimationProgress()
    val composerCornerRadius = lerp(28.dp, 24.dp, expansion)
    val composerHorizontalPadding = lerp(8.dp, 12.dp, expansion)
    val composerVerticalPadding = lerp(8.dp, 10.dp, expansion)
    val composerOuterTopPadding = lerp(8.dp, 6.dp, expansion)
    val composerOuterBottomPadding = lerp(10.dp, 8.dp, expansion)

    val wsName = remember(workspacePath) {
        workspacePath?.substringAfterLast("/").orEmpty()
    }
    val hasWorkspace = remember(workspacePath) { !workspacePath.isNullOrBlank() }
    var commandMode by remember(resetKey) { mutableStateOf<ComposerCommand?>(null) }
    var commandQuery by remember(resetKey) { mutableStateOf("") }
    var fileResults by remember(resetKey) { mutableStateOf(emptyList<com.amaya.intelligence.domain.models.ProjectFileEntry>()) }
    var isSearchingFiles by remember(resetKey) { mutableStateOf(false) }
    var commandPillHeightPx by remember(resetKey) { mutableIntStateOf(36) }
    val density = LocalDensity.current

    LaunchedEffect(text, hasWorkspace) {
        val token = text.substringAfterLast(' ')
        val trigger = token.firstOrNull().takeIf { it == '@' || it == '/' }
        commandMode = when (trigger) {
            '@' -> ComposerCommand.MENTIONS
            '/' -> ComposerCommand.ACTIONS
            else -> null
        }
        commandQuery = trigger?.let { token.drop(1).trim() }.orEmpty()
        if (commandMode == ComposerCommand.MENTIONS && hasWorkspace) {
            isSearchingFiles = true
            fileResults = runCatching { onSearchWorkspaceFiles(commandQuery) }.getOrDefault(emptyList())
            isSearchingFiles = false
        } else if (commandMode != ComposerCommand.MENTIONS) {
            fileResults = emptyList()
            isSearchingFiles = false
        }
    }

    val pillColor = remember(isDark) {
        if (isDark) Color(0xFF1F2126).copy(alpha = 0.94f)
        else Color(0xFFF8F8FA).copy(alpha = 0.96f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = composerOuterTopPadding, bottom = composerOuterBottomPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showConversationModeSelector) {
            Surface(
                onClick = onShowConversationModeSelector,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        if (conversationModeIsFast) Icons.Default.Bolt else Icons.Default.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = conversationModeLabel
                            ?: if (conversationMode == ConversationMode.PLANNING) "Planning" else "Fast",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Attached file pill
        if (attachedFilePath != null) {
            val fileName = attachedFilePath.substringAfterLast("/")
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(text = fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp))
                    Box(
                        modifier = Modifier.size(14.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                            .clickable { onClearAttachment() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove",
                            modifier = Modifier.size(9.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        // Attached image pill
        if (attachedImageBase64 != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(text = attachedImageName ?: "Image",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp))
                    Box(
                        modifier = Modifier.size(14.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                            .clickable { onClearImageAttachment() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove",
                            modifier = Modifier.size(9.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        val placeholderText = remember(assistantMode, hasWorkspace, wsName) {
            when (assistantMode) {
                AssistantMode.CHAT -> "Ask anything"
                AssistantMode.PROJECT -> "Ask about $wsName"
                AssistantMode.AGENT -> "Message the agent group"
            }
        }
        val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f)
        val canSend = text.isNotBlank() || hasAttachment
        val submitMessage = {
            if (isStreaming) {
                onStopGeneration()
            } else if (canSend) {
                val message = text.trim()
                onTextChange("")
                onSendMessage(message)
            }
        }


        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom
        ) {
            val displayCommandMode = remember { mutableStateOf(commandMode) }
            val displayQuery = remember { mutableStateOf(commandQuery) }
            val displayAgents = remember { mutableStateOf(mentionAgents) }
            val displayFiles = remember { mutableStateOf(fileResults) }

            if (commandMode != null) {
                displayCommandMode.value = commandMode
                displayQuery.value = commandQuery
                displayAgents.value = mentionAgents
                displayFiles.value = fileResults
            }

            val pillListState = androidx.compose.foundation.lazy.rememberLazyListState()

            val topShadowAlpha by derivedStateOf {
                if (pillListState.firstVisibleItemIndex > 0) {
                    0.35f
                } else {
                    val offset = pillListState.firstVisibleItemScrollOffset.toFloat()
                    val maxOffset = 60f
                    val fraction = (offset / maxOffset).coerceIn(0f, 1f)
                    0.35f * fraction
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = commandMode != null,
                enter = androidx.compose.animation.expandVertically(
                    expandFrom = Alignment.Bottom,
                    animationSpec = androidx.compose.animation.core.spring(stiffness = 400f, dampingRatio = 0.85f)
                ) + androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.spring(stiffness = 400f, dampingRatio = 0.85f)
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(200)
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    animationSpec = androidx.compose.animation.core.spring(stiffness = 400f, dampingRatio = 0.85f)
                ) + androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.spring(stiffness = 400f, dampingRatio = 0.85f)
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = 1.dp)
                    .zIndex(0f)
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp))
                    .pillShadowOverlay(topShadowAlpha = topShadowAlpha)
            ) {
                ComposerCommandPill(
                    mode = displayCommandMode.value,
                    query = displayQuery.value,
                    agents = displayAgents.value,
                    files = displayFiles.value,
                    isSearching = isSearchingFiles,
                    listState = pillListState,
                    onSelect = { value ->
                        val token = text.substringAfterLast(' ')
                        onTextChange(text.dropLast(token.length) + value + " ")
                        commandMode = null
                    }
                )
            }

            Surface(
                shape = RoundedCornerShape(composerCornerRadius),
                color = pillColor,
                border = BorderStroke(
                    0.7.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.15f else 0.10f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                ComposerLayout(
                    expansion = expansion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = composerHorizontalPadding,
                            vertical = composerVerticalPadding
                        ),
                    attachment = {
                        ComposerAttachmentButton(
                            isStreaming = isStreaming,
                            onAttachFile = onAttachFile,
                            onAttachImage = onAttachImage
                        )
                    },
                    input = {
                        Box(
                            modifier = Modifier.heightIn(min = 40.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = text,
                                onValueChange = onTextChange,
                                visualTransformation = referenceTransformation,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                maxLines = 5,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        if (text.isEmpty()) {
                                            Text(
                                                text = placeholderText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = placeholderColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        inner()
                                    }
                                }
                            )
                        }
                    },
                    model = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReasoningEffortButton(
                                effort = effort,
                                onEffortChange = onEffortChange,
                                enabled = true
                            )
                            Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = onSelectModel,
                            enabled = expansion > 0.5f,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ModelLeadingIcon(
                                    modelId = modelId,
                                    providerId = modelProviderId,
                                    iconType = modelIconType,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                        }
                    },
                    send = {
                        ComposerSendButton(
                            isStreaming = isStreaming,
                            canSend = canSend,
                            onClick = submitMessage,
                            onEmptyClick = {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ComposerLayout(
    expansion: Float,
    modifier: Modifier = Modifier,
    attachment: @Composable () -> Unit,
    input: @Composable () -> Unit,
    model: @Composable () -> Unit,
    send: @Composable () -> Unit
) {
    val controlsProgress = expansion
    val inputProgress = expansion
    val modelProgress = expansion

    Layout(
        modifier = modifier,
        content = {
            Box(
                modifier = Modifier.graphicsLayer {
                    val scale = 1f - (0.1f * controlsProgress)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
            ) {
                attachment()
            }
            input()
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = modelProgress
                    val scale = 1f - (0.1f * controlsProgress)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
            ) {
                model()
            }
            Box(
                modifier = Modifier.graphicsLayer {
                    val scale = 1f - (0.1f * controlsProgress)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
            ) {
                send()
            }
        }
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val collapsedGap = 12.dp.roundToPx()
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val attachmentPlaceable = measurables[0].measure(looseConstraints)
        val sendPlaceable = measurables[3].measure(looseConstraints)
        val modelPlaceable = measurables[2].measure(
            looseConstraints.copy(
                maxWidth = (constraints.maxWidth - attachmentPlaceable.width - sendPlaceable.width - gap * 2)
                    .coerceAtLeast(0)
            )
        )
        val collapsedLeftInset = attachmentPlaceable.width + collapsedGap
        val collapsedRightInset = sendPlaceable.width + collapsedGap
        val expandedLeftInset = 8.dp.roundToPx()
        val inputLeftInset = (
            collapsedLeftInset + ((expandedLeftInset - collapsedLeftInset) * inputProgress)
        ).roundToInt()
        val inputRightInset = (collapsedRightInset * (1f - inputProgress)).roundToInt()
        val inputWidth = (constraints.maxWidth - inputLeftInset - inputRightInset).coerceAtLeast(0)
        val inputPlaceable = measurables[1].measure(
            constraints.copy(
                minWidth = inputWidth,
                maxWidth = inputWidth,
                minHeight = 0
            )
        )
        val topHeight = maxOf(inputPlaceable.height, attachmentPlaceable.height, sendPlaceable.height)
        val controlsRowY = topHeight + gap
        val controlsRowHeight = maxOf(
            attachmentPlaceable.height,
            modelPlaceable.height,
            sendPlaceable.height
        )
        val expandedHeight = controlsRowY + controlsRowHeight
        val layoutHeight = (
            topHeight + ((expandedHeight - topHeight) * controlsProgress)
        ).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val attachmentStartY = (topHeight - attachmentPlaceable.height) / 2
        val sendStartY = (topHeight - sendPlaceable.height) / 2
        val attachmentY = (
            attachmentStartY + ((controlsRowY - attachmentStartY) * controlsProgress)
        ).roundToInt()
        val sendY = (
            sendStartY + ((controlsRowY - sendStartY) * controlsProgress)
        ).roundToInt()
        val inputY = (topHeight - inputPlaceable.height) / 2
        val visualScale = 1f - (0.1f * controlsProgress)
        val modelRightVisualShift = sendPlaceable.width * (1f - visualScale)
        val modelXOffset = modelRightVisualShift.roundToInt()
        val modelX = constraints.maxWidth - sendPlaceable.width - gap - modelPlaceable.width + modelXOffset
        val modelY = controlsRowY + (controlsRowHeight - modelPlaceable.height) / 2

        layout(constraints.maxWidth, layoutHeight) {
            attachmentPlaceable.placeRelative(0, attachmentY)
            inputPlaceable.placeRelative(inputLeftInset, inputY)
            modelPlaceable.placeRelative(modelX, modelY)
            sendPlaceable.placeRelative(constraints.maxWidth - sendPlaceable.width, sendY)
        }
    }
}

@Composable
private fun ComposerAttachmentButton(
    isStreaming: Boolean,
    onAttachFile: () -> Unit,
    onAttachImage: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isStreaming) 0.05f else 0.08f))
                .clickable(enabled = !isStreaming) { showMenu = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Attach",
                modifier = Modifier.size(23.dp),
                tint = if (isStreaming) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
            modifier = Modifier.widthIn(min = 180.dp)
        ) {
            DropdownMenuItem(
                text = { Text("Attach file") },
                onClick = {
                    showMenu = false
                    onAttachFile()
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp)),
                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) }
            )
            if (onAttachImage != null) {
                DropdownMenuItem(
                    text = { Text("Attach image") },
                    onClick = {
                        showMenu = false
                        onAttachImage()
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
private fun ComposerSendButton(
    isStreaming: Boolean,
    canSend: Boolean,
    onClick: () -> Unit,
    onEmptyClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (isStreaming) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                else if (canSend) Color(0xFF0A84FF)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            .clickable {
                if (isStreaming || canSend) onClick() else onEmptyClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (isStreaming) {
            Icon(
                Icons.Default.Stop,
                contentDescription = "Stop",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                modifier = Modifier
                    .size(18.dp)
                    .offset(x = 1.dp), // optical alignment
                tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

private class ComposerReferenceTransformation(private val referenceColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val matches = COMPOSER_REFERENCE_LINK.findAll(text.text).toList()
        if (matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val rendered = AnnotatedString.Builder()
        val originalToTransformed = IntArray(text.length + 1)
        val transformedToOriginal = mutableListOf<Int>()
        var originalIndex = 0
        var transformedIndex = 0
        matches.forEach { match ->
            while (originalIndex < match.range.first) {
                originalToTransformed[originalIndex] = transformedIndex
                rendered.append(text[originalIndex])
                transformedToOriginal += originalIndex
                originalIndex++
                transformedIndex++
            }
            val label = match.groupValues[1]
            val labelStart = match.range.first
            val labelContentStart = labelStart + 1
            val transformedStart = transformedIndex
            rendered.pushStyle(SpanStyle(color = referenceColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            label.forEachIndexed { offset, char ->
                rendered.append(char)
                transformedToOriginal += labelContentStart + offset
                transformedIndex++
            }
            rendered.pop()
            for (index in match.range) originalToTransformed[index] = transformedStart
            originalToTransformed[labelContentStart.coerceAtMost(text.length)] = transformedStart
            originalToTransformed[(labelContentStart + label.length).coerceAtMost(text.length)] = transformedIndex
            originalIndex = match.range.last + 1
        }
        while (originalIndex < text.length) {
            originalToTransformed[originalIndex] = transformedIndex
            rendered.append(text[originalIndex])
            transformedToOriginal += originalIndex
            originalIndex++
            transformedIndex++
        }
        originalToTransformed[text.length] = transformedIndex
        transformedToOriginal += text.length
        return TransformedText(rendered.toAnnotatedString(), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                originalToTransformed[offset.coerceIn(0, text.length)].coerceIn(0, transformedIndex)

            override fun transformedToOriginal(offset: Int): Int =
                transformedToOriginal[offset.coerceIn(0, transformedToOriginal.lastIndex)].coerceIn(0, text.length)
        })
    }
}

private val COMPOSER_REFERENCE_LINK = Regex("\\[([^]\\n]+)]\\((?:agent|workspace|command):[^)]+\\)")

private enum class ComposerCommand { MENTIONS, ACTIONS }

private data class ComposerSuggestion(val label: String, val detail: String, val value: String)

@Composable
private fun ComposerCommandPill(
    mode: ComposerCommand?,
    query: String,
    agents: List<ChatMentionAgent>,
    files: List<com.amaya.intelligence.domain.models.ProjectFileEntry>,
    isSearching: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    if (mode == null) return
    val actions = remember {
        listOf(
            ComposerSuggestion("Explain", "Explain selected context", "/explain"),
            ComposerSuggestion("Review", "Review code or text", "/review"),
            ComposerSuggestion("Plan", "Draft a concise implementation plan", "/plan")
        )
    }
    val filteredActions = actions.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    val filteredAgents = agents.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.role.contains(query, ignoreCase = true)
    }
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Box {
            Column(Modifier.animateContentSize().heightIn(max = 240.dp)) {
                val isEmpty = mode == ComposerCommand.MENTIONS && filteredAgents.isEmpty() && files.isEmpty() && !isSearching
                androidx.compose.animation.Crossfade(targetState = isEmpty, label = "pillCrossfade") { showEmpty ->
                    if (showEmpty) {
                        Text(
                            if (query.isBlank()) "No agents or workspace files" else "No matching agents or files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 230.dp), state = listState, contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)) {
                    if (mode == ComposerCommand.MENTIONS) {
                        if (filteredAgents.isNotEmpty()) {
                            items(filteredAgents, key = { "agent:${it.groupName}:${it.name}" }) { agent ->
                                ComposerSuggestionRow(
                                    icon = Icons.Default.SmartToy,
                                    label = agent.name,
                                    detail = listOf(agent.groupName, agent.role).filter(String::isNotBlank).joinToString(" · "),
                                    onClick = { onSelect(com.amaya.intelligence.domain.models.agentMentionMarkdown(agent.id, agent.name)) }
                                )
                            }
                        }
                        if (files.isNotEmpty()) {
                            items(files, key = { "file:${it.path}" }) { file ->
                                ComposerSuggestionRow(
                                    icon = if (file.type == "directory") Icons.Default.Folder else Icons.Default.Description,
                                    label = file.name,
                                    detail = file.path,
                                    onClick = { onSelect(com.amaya.intelligence.domain.models.workspaceMentionMarkdown(file.path)) }
                                )
                            }
                        }
                        if (isSearching) item {
                            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else {
                        items(filteredActions, key = { it.value }) { action ->
                            ComposerSuggestionRow(Icons.Default.Terminal, action.label, action.detail) {
                                onSelect(com.amaya.intelligence.domain.models.commandMarkdown(action.value))
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun ComposerSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun ComposerSuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ReasoningEffortButton(
    effort: com.amaya.intelligence.data.remote.api.ThinkingEffort,
    onEffortChange: (com.amaya.intelligence.data.remote.api.ThinkingEffort) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val isActive = effort != com.amaya.intelligence.data.remote.api.ThinkingEffort.NONE

    Box {
        Surface(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            modifier = Modifier.height(40.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = "Reasoning effort",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Text(
                        text = effort.label(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
            modifier = Modifier.widthIn(min = 180.dp)
        ) {
            com.amaya.intelligence.data.remote.api.ThinkingEffort.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text(level.label()) },
                    onClick = {
                        onEffortChange(level)
                        expanded = false
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (level == effort) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            else Color.Transparent
                        ),
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

private fun com.amaya.intelligence.data.remote.api.ThinkingEffort.label(): String = when (this) {
    com.amaya.intelligence.data.remote.api.ThinkingEffort.NONE -> "Off"
    com.amaya.intelligence.data.remote.api.ThinkingEffort.LOW -> "Low"
    com.amaya.intelligence.data.remote.api.ThinkingEffort.MEDIUM -> "Medium"
    com.amaya.intelligence.data.remote.api.ThinkingEffort.HIGH -> "High"
}

private fun Modifier.pillShadowOverlay(topShadowAlpha: Float = 0.35f): Modifier = this
    .drawWithContent {
        drawContent()
        val gradientHeight = 16.dp.toPx()

        if (topShadowAlpha > 0f) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = topShadowAlpha),
                    1.0f to androidx.compose.ui.graphics.Color.Transparent,
                    startY = 0f,
                    endY = gradientHeight
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop
            )
        }

        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0.0f to androidx.compose.ui.graphics.Color.Transparent,
                1.0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
                startY = (size.height - gradientHeight).coerceAtLeast(0f),
                endY = size.height
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop
        )
    }
