package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ConversationMode

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

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
    onWorkspaceClick: () -> Unit = {},
    modelLabel: String = "Select Model",
    modelId: String = "",
    modelProviderId: String? = null,
    modelIconType: String? = null,
    onSelectModel: () -> Unit = {},
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val hasAttachment = attachedFilePath != null || attachedImageBase64 != null
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
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

        val placeholderText = remember(hasWorkspace, wsName) {
            if (hasWorkspace) "Ask anything on $wsName" else "Ask anything"
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


        // Card + Input — precisely stacked to hide border gap
        val cardHeight = 36.dp
        val overlap = 1.dp
        Box(modifier = Modifier.fillMaxWidth()) {
            // Workspace card — narrower, bottom corners flat
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                ),
                color = pillColor,
                border = BorderStroke(
                    0.7.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.15f else 0.10f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(cardHeight)
                    .align(Alignment.TopCenter)
                    .zIndex(0f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onWorkspaceClick,
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasWorkspace) wsName else "No Project",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Main input — full width, placed precisely to overlap 1dp and hide the seam
            Surface(
                shape = RoundedCornerShape(composerCornerRadius),
                color = pillColor,
                border = BorderStroke(
                    0.7.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.15f else 0.10f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = cardHeight - overlap)
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
                            expansion = expansion,
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
                        val modelHeight = androidx.compose.ui.unit.lerp(40.dp, 32.dp, expansion)
                        val iconSize = androidx.compose.ui.unit.lerp(18.dp, 14.dp, expansion)
                        Surface(
                            onClick = onSelectModel,
                            enabled = expansion > 0.5f,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            modifier = Modifier.height(modelHeight)
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
                                    modifier = Modifier.size(iconSize),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    },
                    send = {
                        ComposerSendButton(
                            expansion = expansion,
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
            attachment()
            input()
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = modelProgress
                    scaleX = 0.96f + (0.04f * modelProgress)
                    scaleY = 0.96f + (0.04f * modelProgress)
                    transformOrigin = TransformOrigin(1f, 1f)
                }
            ) {
                model()
            }
            send()
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
        val modelX = constraints.maxWidth - sendPlaceable.width - gap - modelPlaceable.width
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
    expansion: Float,
    isStreaming: Boolean,
    onAttachFile: () -> Unit,
    onAttachImage: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }
    val buttonSize = androidx.compose.ui.unit.lerp(40.dp, 32.dp, expansion)
    val iconSize = androidx.compose.ui.unit.lerp(23.dp, 18.dp, expansion)

    Box {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isStreaming) 0.05f else 0.08f))
                .clickable(enabled = !isStreaming) { showMenu = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Attach",
                modifier = Modifier.size(iconSize),
                tint = if (isStreaming) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Attach file") },
                onClick = {
                    showMenu = false
                    onAttachFile()
                },
                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) }
            )
            if (onAttachImage != null) {
                DropdownMenuItem(
                    text = { Text("Attach image") },
                    onClick = {
                        showMenu = false
                        onAttachImage()
                    },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
private fun ComposerSendButton(
    expansion: Float,
    isStreaming: Boolean,
    canSend: Boolean,
    onClick: () -> Unit,
    onEmptyClick: () -> Unit
) {
    val buttonSize = androidx.compose.ui.unit.lerp(40.dp, 32.dp, expansion)
    val iconSize = androidx.compose.ui.unit.lerp(18.dp, 14.dp, expansion)
    val stopIconSize = androidx.compose.ui.unit.lerp(16.dp, 12.dp, expansion)

    Box(
        modifier = Modifier
            .size(buttonSize)
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
                modifier = Modifier.size(stopIconSize),
                tint = MaterialTheme.colorScheme.error
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                modifier = Modifier
                    .size(iconSize)
                    .offset(x = 1.dp), // optical alignment
                tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
    }
}
