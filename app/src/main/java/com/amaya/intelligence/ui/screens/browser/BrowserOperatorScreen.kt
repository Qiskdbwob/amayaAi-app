package com.amaya.intelligence.ui.screens.browser
import com.amaya.intelligence.ui.components.shared.MarkdownText

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import com.amaya.intelligence.domain.models.ToolInfoIcon
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.browser.BrowserToolLog
import com.amaya.intelligence.impl.local.browser.BrowserUiState
import com.amaya.intelligence.impl.local.browser.BrowserAgentStatus
import com.amaya.intelligence.ui.components.shared.ToolLeadIconPill
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserOperatorScreen(
    browserSessionManager: BrowserSessionManager,
    onClose: () -> Unit
) {
    val state by browserSessionManager.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(state.activeUrl.takeUnless { it == "about:blank" }.orEmpty()) }
    var showTakeControlPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(state.activeUrl) {
        if (state.activeUrl != "about:blank") address = state.activeUrl
    }

    LaunchedEffect(state.isAssistantStreaming, state.browserAccessActive, state.assistantStreamUpdatedAt, state.agentTouchNonce) {
        if (state.isAssistantStreaming && state.browserAccessActive) {
            browserSessionManager.hideSoftKeyboardForAgent()
        }
    }

    val webView = remember { browserSessionManager.acquireSharedWebView() }

    DisposableEffect(webView) {
        onDispose { }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color(0xFF0B0B10)
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            // WebView with status bar safe area (like Chrome).
            AndroidView(
                factory = {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView
                },
                update = { view ->
                    val agentActive = state.isAssistantStreaming && state.browserAccessActive
                    if (agentActive) browserSessionManager.hideSoftKeyboardForAgent()
                    view.setOnTouchListener { _, event ->
                        if (agentActive && !browserSessionManager.isDispatchingAgentInput()) {
                            if (event.action == android.view.MotionEvent.ACTION_DOWN) showTakeControlPrompt = true
                            true
                        } else false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            )

            BrowserScreenGlowOverlay(Modifier.fillMaxSize(), agentActive = state.isAssistantStreaming && state.browserAccessActive)

            val agentActive = state.isAssistantStreaming && state.browserAccessActive
            if (agentActive) {
                AgentBrowserActiveBorder(Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                showTakeControlPrompt = true
                                tryAwaitRelease()
                            })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                showTakeControlPrompt = true
                            }
                        }
                )
            }

            AgentCursorOverlay(state = state, agentActive = agentActive)

            // Thin progress bar under the status bar
            if (state.progress in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    color = Color(0xFF0A84FF),
                    trackColor = Color.Transparent
                )
            }

            // ── Bottom dock: summary + tabs + log/url/actions/thinking ──
            BrowserControlDock(
                state = state,
                address = address,
                onAddressChange = { address = it },
                onGo = { scope.launch { browserSessionManager.execute("open_url", mapOf("url" to address)) } },
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            )
        }
    }

    if (showTakeControlPrompt) {
        AlertDialog(
            onDismissRequest = { showTakeControlPrompt = false },
            title = { Text("Agent sedang aktif") },
            text = { Text("Ambil alih browser secara manual atau biarkan agent lanjut?") },
            confirmButton = {
                Button(onClick = {
                    showTakeControlPrompt = false
                    browserSessionManager.cancelFromUser()
                }) { Text("Take control") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { showTakeControlPrompt = false }) { Text("Keep running") }
                    TextButton(onClick = {
                        showTakeControlPrompt = false
                        scope.launch { browserSessionManager.execute("pause_session", emptyMap()) }
                    }) { Text("Pause agent") }
                }
            }
        )
    }

    state.safetyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Input sensitif") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prompt.reason)
                    prompt.fieldLabel?.let { Text("Field: $it", fontWeight = FontWeight.Medium) }
                    prompt.selector?.let { Text("Selector: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = { Button(onClick = { browserSessionManager.allowSensitiveAndResume() }) { Text("Izinkan sekali") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { browserSessionManager.userManualInput() }) { Text("Isi manual") }
                    TextButton(onClick = { browserSessionManager.skipSensitiveStep() }) { Text("Skip") }
                    TextButton(onClick = { browserSessionManager.cancelFromUser() }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun BrowserScreenGlowOverlay(modifier: Modifier = Modifier, agentActive: Boolean) {
    val transition = rememberInfiniteTransition(label = "browser_screen_glow")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200), RepeatMode.Reverse),
        label = "screen_glow_pulse"
    )
    Canvas(modifier = modifier) {
        val activeBoost = if (agentActive) 0.035f else 0f
        val topGlow = 0.055f + pulse * 0.03f + activeBoost
        val cornerGlow = 0.04f + pulse * 0.02f + activeBoost * 0.65f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFBFEFFF).copy(alpha = topGlow),
                    Color(0xFF0A84FF).copy(alpha = topGlow * 0.52f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.50f, size.height * 0.06f),
                radius = size.minDimension * 1.02f
            ),
            blendMode = BlendMode.Screen
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF64D2FF).copy(alpha = cornerGlow),
                    Color(0xFF0A84FF).copy(alpha = cornerGlow * 0.58f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.12f, size.height * 0.16f),
                radius = size.minDimension * 0.82f
            ),
            blendMode = BlendMode.Screen
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFEAF7FF).copy(alpha = cornerGlow * 0.55f),
                    Color(0xFF5AA9FF).copy(alpha = cornerGlow * 0.34f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.90f, size.height * 0.90f),
                radius = size.minDimension * 0.88f
            ),
            blendMode = BlendMode.Screen
        )
    }
}

@Composable
private fun AgentBrowserActiveBorder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "agent_browser_border")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "border_phase"
    )
    Canvas(
        modifier = modifier.blur(10.dp)
    ) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF0A84FF).copy(alpha = 0.10f + phase * 0.08f),
                    Color(0xFF64D2FF).copy(alpha = 0.22f + phase * 0.10f),
                    Color(0xFFEAF7FF).copy(alpha = 0.08f + phase * 0.05f)
                )
            ),
            style = Stroke(width = 16.dp.toPx())
        )
    }
}

@Composable
private fun AgentCursorOverlay(state: BrowserUiState, agentActive: Boolean) {
    // Only show cursor when the agent has actually touched an element.
    val touchX = state.agentTouchX ?: return
    val touchY = state.agentTouchY ?: return
    val density = LocalDensity.current
    val cursorSize = 10.dp
    val cursorSizePx = with(density) { cursorSize.toPx() }
    // WebView has statusBarsPadding(), so offset cursor Y by the status bar height.
    val statusBarTop = WindowInsets.statusBars.getTop(density).toFloat()
    val targetX = touchX
    val targetY = touchY + statusBarTop

    // Smooth animated position — cursor glides to each new target.
    val animX = remember { Animatable(targetX) }
    val animY = remember { Animatable(targetY) }
    LaunchedEffect(targetX, targetY) {
        launch { animX.animateTo(targetX, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) }
        launch { animY.animateTo(targetY, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) }
    }

    val x = animX.value
    val y = animY.value
    val tapScale = remember { Animatable(1f) }
    val pulse = rememberInfiniteTransition(label = "agent_cursor_pulse")
    val idleScale by pulse.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
        label = "cursor_idle_scale"
    )
    LaunchedEffect(state.agentTouchNonce) {
        tapScale.snapTo(1.55f)
        tapScale.animateTo(1f, tween(220))
    }
    val finalScale = tapScale.value * if (agentActive) idleScale else 1f
    Box(
        modifier = Modifier
            .offset { IntOffset((x - cursorSizePx / 2f).roundToInt(), (y - cursorSizePx / 2f).roundToInt()) }
            .size(cursorSize * finalScale)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFF5FBFF).copy(alpha = 0.96f),
                        Color(0xFF8EDCFF).copy(alpha = 0.88f),
                        Color(0xFF0A84FF).copy(alpha = 0.48f),
                        Color.Transparent
                    ),
                    radius = size.minDimension * 0.70f
                ),
                radius = size.minDimension * 0.54f
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.88f),
                radius = size.minDimension * 0.09f
            )
        }
    }
}

@Composable
private fun BrowserBottomBar(
    address: String,
    onAddressChange: (String) -> Unit,
    state: BrowserUiState,
    onClose: () -> Unit,
    onGo: () -> Unit
) {
    val pillShape = RoundedCornerShape(22.dp)
    val statusDotColor = agentPillColor(state)

    Surface(
        color = Color(0xFF1C1C1E),
        shape = pillShape,
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Agent status dot
            val dotPulse = rememberInfiniteTransition(label = "agent_dot")
            val dotAlpha by dotPulse.animateFloat(
                initialValue = 0.50f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "dot_alpha"
            )
            Canvas(Modifier.size(8.dp)) {
                drawCircle(color = statusDotColor.copy(alpha = dotAlpha))
                drawCircle(color = Color.White.copy(alpha = 0.12f * dotAlpha), radius = size.minDimension * 0.24f)
            }

            // Lock icon
            Icon(
                if (state.activeUrl.startsWith("https://")) Icons.Default.Lock else Icons.Default.Public,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (state.activeUrl.startsWith("https://")) Color(0xFF34C759).copy(alpha = 0.68f) else Color.White.copy(alpha = 0.44f)
            )

            // URL text field
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White.copy(alpha = 0.86f),
                        letterSpacing = 0.2.sp
                    ),
                    cursorBrush = SolidColor(Color(0xFF0A84FF)),
                    decorationBox = { inner ->
                        if (address.isEmpty()) {
                            Text(
                                "Search or enter address",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.34f)
                            )
                        }
                        inner()
                    }
                )
            }

            // Tab count
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White.copy(alpha = 0.08f)
            ) {
                Text(
                    state.tabs.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.76f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Close
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(17.dp), tint = Color.White.copy(alpha = 0.58f))
            }
        }
    }
}

@Composable
private fun AgentMessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val pillShape = RoundedCornerShape(22.dp)
    Surface(
        color = Color(0xFF1C1C1E),
        shape = pillShape,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White.copy(alpha = 0.86f)
                    ),
                    cursorBrush = SolidColor(Color(0xFF0A84FF)),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                "Message agent\u2026",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.30f)
                            )
                        }
                        inner()
                    }
                )
            }

            Surface(
                shape = CircleShape,
                color = if (value.isNotBlank()) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.08f),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(enabled = value.isNotBlank()) { onSend() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Send",
                        modifier = Modifier.size(18.dp),
                        tint = if (value.isNotBlank()) Color.White else Color.White.copy(alpha = 0.28f)
                    )
                }
            }
        }
    }
}


@Composable
private fun BrowserLogOverlay(
    state: BrowserUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedLogs = remember(state.logs) {
        state.logs.sortedBy { it.timestamp }.takeLast(30)
    }
    val streamText = cleanBrowserThinkText(state.assistantStreamText)
    val liveLog = if (streamText.isNotBlank() && state.assistantStreamUpdatedAt > 0L) {
        BrowserToolLog(
            id = "live_stream",
            toolName = if (hasOpenThinkingTag(state.assistantStreamText)) "thinking" else "assistant",
            argumentsPreview = "", status = if (hasOpenThinkingTag(state.assistantStreamText)) "running" else "completed", message = streamText,
            timestamp = state.assistantStreamUpdatedAt
        )
    } else null
    val allLogs = if (liveLog != null) sortedLogs + liveLog else sortedLogs

    // Header: prefer running tool, else latest item
    val header = state.lastError?.let {
        BrowserToolLog(toolName = "browser", argumentsPreview = "", status = "error", message = it)
    } ?: allLogs.lastOrNull { it.toolName != "thinking" && it.toolName != "assistant" && it.status == "running" }
       ?: allLogs.lastOrNull()

    if (header != null) {
        BrowserLogPill(header, allLogs, expanded, onToggle, modifier)
    }
}

@Composable
private fun BrowserLogPill(
    log: BrowserToolLog,
    flowLogs: List<BrowserToolLog>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    val statusColor = browserStatusColor(log.status)

    Surface(
        shape = shape, color = Color(0xFF242424),
        modifier = modifier.fillMaxWidth().shadow(14.dp, shape, clip = false).clip(shape).clickable { onToggle() }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // --- Header row ---
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(shape = CircleShape, color = statusColor.copy(alpha = 0.18f), modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(browserLogIcon(log), null, tint = statusColor, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    text = browserPillTitle(log).replace(Regex("[*`]"), ""),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.90f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.58f))
            }

            // --- Expanded chronological flow ---
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)) + expandVertically(tween(200)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(150))
            ) {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(flowLogs.size) {
                    if (flowLogs.isNotEmpty()) listState.scrollToItem(flowLogs.size - 1)
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(top = 10.dp).heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(flowLogs.size, key = { flowLogs[it].id }) { i ->
                        val item = flowLogs[i]
                        val isText = item.toolName == "thinking" || item.toolName == "assistant"
                        if (isText) {
                            // Text card: dark surface with markdown
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                MarkdownText(
                                    text = cleanBrowserThinkText(item.message),
                                    color = Color.White.copy(alpha = 0.82f),
                                    compact = true,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        } else {
                            // Tool row: icon + label + status
                            val sc = browserStatusColor(item.status)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(shape = CircleShape, color = sc.copy(alpha = 0.15f), modifier = Modifier.size(22.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(browserLogIcon(item), null, tint = sc.copy(alpha = 0.85f), modifier = Modifier.size(12.dp))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        browserActionLabel(item.toolName),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.80f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.argumentsPreview.isNotBlank()) {
                                        Text(
                                            item.argumentsPreview, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = Color.White.copy(alpha = 0.42f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Surface(shape = CircleShape, color = sc, modifier = Modifier.size(6.dp)) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hasOpenThinkingTag(text: String): Boolean {
    val open = Regex("<think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
    val close = Regex("</think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
    return open != null && (close == null || open > close)
}

private fun browserLogIcon(log: BrowserToolLog) = when (log.toolName.lowercase()) {
    "thinking" -> Icons.Default.Lightbulb
    "assistant" -> Icons.Default.Public
    "open_url", "new_page", "new_tab" -> Icons.Default.Public
    "get_dom", "analyze_page", "observe" -> Icons.Default.Check
    "click_element", "click", "tap" -> Icons.Default.PlayArrow
    "type_text", "type", "search" -> Icons.Default.PlayArrow
    "scroll_page", "scroll", "swipe" -> Icons.Default.PlayArrow
    "evaluate_script" -> Icons.Default.Check
    else -> when (log.status.lowercase()) {
        "error", "cancelled" -> Icons.Default.Stop
        "paused", "waiting" -> Icons.Default.Pause
        else -> Icons.Default.PlayArrow
    }
}

private fun browserPillTitle(log: BrowserToolLog): String {
    if (log.toolName == "thinking" || log.toolName == "assistant") {
        val clean = log.message.takeLast(400).replace("\n", " ").trim()
        return clean.ifBlank { browserActionLabel(log.toolName) }
    }
    val label = browserActionLabel(log.toolName)
    val target = browserLogTarget(log)
    return if (target.isNotBlank()) "$label · $target" else label
}

private fun browserLogTarget(log: BrowserToolLog): String {
    val source = listOf(log.argumentsPreview, log.message).joinToString(" ")
    Regex("https?://[^\\s,]+", RegexOption.IGNORE_CASE).find(source)?.value?.let { url ->
        return runCatching { java.net.URI(url).host ?: url }.getOrDefault(url).removePrefix("www.")
    }
    Regex("url=([^,\\s]+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.let { return it }
    Regex("query=([^,]+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.let { return it.trim() }
    Regex("text=([^,]+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.let { return it.trim() }
    return ""
}

private fun cleanBrowserThinkText(raw: String): String {
    if (raw.isBlank()) return ""
    // Strip fully closed <think>...</think> blocks, keeping their inner text.
    var text = raw.replace(
        Regex("<think>(.*?)</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    ) { match -> match.groupValues.getOrNull(1).orEmpty() }
    // Strip remaining opening/closing think tags.
    text = text.replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
    // Strip any trailing partial HTML-like tag that is still being streamed (e.g. "<thi", "</").
    text = text.replace(Regex("<[a-zA-Z/]*$"), "")
    return text.trim()
}

private fun agentPillColor(state: BrowserUiState): Color = when {
    state.isCancelled -> Color(0xFFFF453A)
    state.lastError != null -> Color(0xFFFF453A)
    state.isPaused -> Color(0xFFFFB340)
    state.isAssistantStreaming && state.browserAccessActive -> Color(0xFF0A84FF)
    state.isAssistantStreaming -> Color(0xFF64D2FF)
    state.status == BrowserAgentStatus.BROWSING -> Color(0xFF0A84FF)
    state.status == BrowserAgentStatus.COMPLETED -> Color(0xFF34C759)
    else -> Color(0xFF8E8E93)
}

private fun agentPillLabel(state: BrowserUiState): String = when {
    state.isCancelled -> "Cancelled"
    state.lastError != null -> "Error"
    state.isPaused -> "Paused"
    state.isAssistantStreaming && state.browserAccessActive -> "Agent browsing"
    state.isAssistantStreaming -> "Thinking"
    state.status == BrowserAgentStatus.BROWSING -> "Browsing"
    state.status == BrowserAgentStatus.COMPLETED -> "Done"
    else -> "Ready"
}

private fun browserStatusColor(status: String): Color = when (status.lowercase()) {
    "success" -> Color(0xFF34C759)
    "running" -> Color(0xFF0A84FF)
    "paused", "waiting" -> Color(0xFFFFB340)
    "error", "cancelled" -> Color(0xFFFF453A)
    else -> Color(0xFF8E8E93)
}

private fun browserStatusLabel(status: String): String = when (status.lowercase()) {
    "running" -> "RUNNING"
    "success" -> "DONE"
    "paused", "waiting" -> "WAITING"
    "error" -> "ERROR"
    "cancelled" -> "CANCELLED"
    else -> status.uppercase()
}

private fun browserActionLabel(name: String): String {
    val normalized = name.removePrefix("browser.").lowercase()
    return when (normalized) {
        "new_page", "new_tab" -> "Open new tab"
        "open_url" -> "Open page"
        "get_dom", "analyze_page", "observe" -> "Observe page"
        "get_visible_text" -> "Read visible text"
        "click", "click_element" -> "Click element"
        "tap" -> "Tap screen"
        "type", "type_text" -> "Type text"
        "clear_input" -> "Clear field"
        "press_key" -> "Press key"
        "scroll", "scroll_page", "swipe" -> "Scroll page"
        "search" -> "Search page"
        "find_element" -> "Find element"
        "wait_for_element" -> "Wait for element"
        "evaluate_script" -> "Evaluate script"
        "get_screenshot" -> "Capture screenshot"
        "go_back" -> "Go back"
        "go_forward" -> "Go forward"
        "reload_page", "reload" -> "Reload page"
        "browser" -> "Browser agent"
        "assistant" -> "Agent message"
        "thinking" -> "Thinking"
        else -> normalized.split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
