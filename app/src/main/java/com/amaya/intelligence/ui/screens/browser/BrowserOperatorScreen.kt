package com.amaya.intelligence.ui.screens.browser

import android.view.ViewGroup
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tab
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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.browser.BrowserUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserOperatorScreen(
    browserSessionManager: BrowserSessionManager,
    onClose: () -> Unit,
    onPickFiles: (Array<String>) -> Unit = {},
    onAuthHandoff: () -> Unit = {},
    onOpenDownload: (com.amaya.intelligence.impl.local.browser.BrowserDownload) -> Unit = {},
    onDeleteDownload: (com.amaya.intelligence.impl.local.browser.BrowserDownload) -> Unit = {}
) {
    val state by browserSessionManager.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(state.activeUrl.takeUnless { it == "about:blank" }.orEmpty()) }
    var showTakeControlPrompt by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showEvaluate by remember { mutableStateOf(false) }
    var confirmClearSiteData by remember { mutableStateOf(false) }
    var evaluateExpression by remember { mutableStateOf("document.title") }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.uploadRequestNonce) {
        if (state.uploadRequestNonce > 0L && state.uploadPending) onPickFiles(state.uploadAcceptTypes.ifEmpty { listOf("*/*") }.toTypedArray())
    }

    LaunchedEffect(state.activeUrl) {
        if (state.activeUrl != "about:blank") address = state.activeUrl
    }

    LaunchedEffect(state.isAssistantStreaming, state.browserAccessActive, state.assistantStreamUpdatedAt, state.agentTouchNonce) {
        if (state.isAssistantStreaming && state.browserAccessActive) {
            browserSessionManager.hideSoftKeyboardForAgent()
        }
    }

    BackHandler {
        when {
            menuOpen -> menuOpen = false
            state.uploadPending -> browserSessionManager.cancelPendingUpload()
            state.tabs.firstOrNull { it.id == state.activeTabId }?.canGoBack == true -> scope.launch { browserSessionManager.execute("go_back", emptyMap()) }
            else -> onClose()
        }
    }

    val browserView = remember(state.activeTabId) { browserSessionManager.acquireSharedBrowserView() }

    // Backgrounding this host destroys the GeckoView surface while the screen stays
    // composed. Report it so the session moves to its offscreen display instead of
    // losing Gecko's content process to Android reclaim.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> browserSessionManager.onHostVisibilityChanged(true)
                Lifecycle.Event.ON_STOP -> browserSessionManager.onHostVisibilityChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            browserSessionManager.releaseSharedBrowserView()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color(0xFF0B0B10)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            BrowserTopChrome(
                state = state,
                address = address,
                onAddressChange = { address = it },
                onGo = { scope.launch { browserSessionManager.execute("open_url", mapOf("url" to address)) } },
                onBack = { scope.launch { browserSessionManager.execute("go_back", emptyMap()) } },
                onForward = { scope.launch { browserSessionManager.execute("go_forward", emptyMap()) } },
                onReload = { scope.launch { browserSessionManager.execute("reload_page", emptyMap()) } },
                onNewTab = { scope.launch { browserSessionManager.execute("new_page", emptyMap()) } },
                onSelectTab = { pageId -> scope.launch { browserSessionManager.switchToTab(pageId) } },
                onCloseTab = { pageId -> scope.launch { browserSessionManager.switchToTab(pageId); browserSessionManager.execute("close_page", emptyMap()) } },
                onClose = onClose,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onPickFiles = { onPickFiles(arrayOf("*/*")) },
                onAuthHandoff = onAuthHandoff,
                onHistory = { showHistory = true },
                onDownloads = { showDownloads = true },
                onScreenshot = { scope.launch { browserSessionManager.captureScreenshotToWorkspace() } },
                onEvaluate = { showEvaluate = true },
                onClearSiteData = { confirmClearSiteData = true }
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp)
                    .clipToBounds()
            ) {
                key(state.activeTabId) {
                    AndroidView(
                        factory = {
                            (browserView.parent as? ViewGroup)?.removeView(browserView)
                            browserView
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
                            .clipToBounds()
                    )
                }

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
            }

            if (state.progress in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF0A84FF),
                    trackColor = Color.Transparent
                )
            }

            BrowserControlDock(
                state = state,
                address = address,
                onAddressChange = { address = it },
                onGo = { scope.launch { browserSessionManager.execute("open_url", mapOf("url" to address)) } },
                onClose = onClose,
                onStop = { browserSessionManager.cancelFromUser() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 360.dp)
            )
        }
    }

    if (confirmClearSiteData) {
        AlertDialog(
            onDismissRequest = { confirmClearSiteData = false },
            title = { Text("Clear site data?") },
            text = { Text("Cookies and site storage for the active origin will be removed.") },
            confirmButton = { Button(onClick = { confirmClearSiteData = false; browserSessionManager.clearActiveSiteData() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearSiteData = false }) { Text("Cancel") } }
        )
    }
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("History") },
            text = { Text(state.sessionHistory.asReversed().joinToString("\n") { "${it.title}\n${it.url}" }.ifBlank { "No pages yet" }) },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
        )
    }
    if (showDownloads) {
        AlertDialog(
            onDismissRequest = { showDownloads = false },
            title = { Text("Downloads") },
            text = {
                if (state.downloads.isEmpty()) Text("No downloads") else Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.downloads.asReversed().forEach { download ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onOpenDownload(download) }, modifier = Modifier.weight(1f)) {
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text(download.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(download.relativePath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TextButton(onClick = { onDeleteDownload(download) }) { Text("Delete") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDownloads = false }) { Text("Close") } }
        )
    }
    if (showEvaluate) {
        AlertDialog(
            onDismissRequest = { showEvaluate = false },
            title = { Text("Evaluate JavaScript") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        value = evaluateExpression,
                        onValueChange = { evaluateExpression = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                    state.evaluateResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = { showEvaluate = false; scope.launch { browserSessionManager.execute("evaluate", mapOf("expression" to evaluateExpression)) } }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showEvaluate = false }) { Text("Cancel") } }
        )
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
                TextButton(onClick = { showTakeControlPrompt = false }) { Text("Keep running") }
            }
        )
    }
}

@Composable
private fun BrowserTopChrome(
    state: BrowserUiState,
    address: String,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onClose: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onPickFiles: () -> Unit,
    onAuthHandoff: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onScreenshot: () -> Unit,
    onEvaluate: () -> Unit,
    onClearSiteData: () -> Unit
) {
    var tabsOpen by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF1D1F24) else Color(0xFFF7F7FA)
    val iconTint = if (isDark) Color.White.copy(alpha = 0.86f) else Color.Black.copy(alpha = 0.86f)
    val fieldBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)

    Column(Modifier.fillMaxWidth().background(bgColor)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Home, "Home", tint = iconTint)
            }
            Surface(
                color = fieldBg,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (address.startsWith("https://")) Icons.Default.Lock else Icons.Default.Public,
                        "Page security",
                        Modifier.size(16.dp),
                        tint = if (address.startsWith("https://")) Color(0xFF34C759) else iconTint.copy(alpha = 0.55f)
                    )
                    BasicTextField(
                        value = address,
                        onValueChange = onAddressChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = iconTint),
                        cursorBrush = SolidColor(Color(0xFF0A84FF)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        decorationBox = { inner ->
                            if (address.isBlank()) Text("Search or type URL", color = iconTint.copy(alpha = 0.38f))
                            inner()
                        }
                    )
                }
            }
            IconButton(onClick = onNewTab) {
                Icon(Icons.Default.Add, "New Tab", tint = iconTint)
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp)
            ) {
                Box(
                    modifier = Modifier.size(20.dp).clickable { tabsOpen = !tabsOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(2.dp, iconTint),
                        modifier = Modifier.matchParentSize()
                    ) {}
                    Text(
                        text = state.tabs.size.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = iconTint,
                        fontSize = 11.sp
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = tabsOpen,
                    onDismissRequest = { tabsOpen = false },
                    modifier = Modifier.background(bgColor)
                ) {
                    state.tabs.forEach { tab ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tab.title.ifBlank { "New Page" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = iconTint, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { onCloseTab(tab.id) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, "Close tab", tint = iconTint, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            onClick = { tabsOpen = false; onSelectTab(tab.id) },
                            modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { onMenuOpenChange(!menuOpen) }) {
                    Icon(Icons.Default.MoreVert, "Browser menu", tint = iconTint)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                    modifier = Modifier.background(bgColor)
                ) {
                    val backEnabled = state.tabs.firstOrNull { it.id == state.activeTabId }?.canGoBack == true
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Back", color = if (backEnabled) iconTint else iconTint.copy(alpha = 0.38f)) }, onClick = { onMenuOpenChange(false); onBack() }, enabled = backEnabled)
                    val forwardEnabled = state.tabs.firstOrNull { it.id == state.activeTabId }?.canGoForward == true
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Forward", color = if (forwardEnabled) iconTint else iconTint.copy(alpha = 0.38f)) }, onClick = { onMenuOpenChange(false); onForward() }, enabled = forwardEnabled)
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Reload", color = iconTint) }, onClick = { onMenuOpenChange(false); onReload() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("History", color = iconTint) }, onClick = { onMenuOpenChange(false); onHistory() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Downloads (${state.downloads.size})", color = iconTint) }, onClick = { onMenuOpenChange(false); onDownloads() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Upload", color = iconTint) }, onClick = { onMenuOpenChange(false); onPickFiles() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Screenshot", color = iconTint) }, onClick = { onMenuOpenChange(false); onScreenshot() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Evaluate", color = iconTint) }, onClick = { onMenuOpenChange(false); onEvaluate() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Verify in browser", color = iconTint) }, onClick = { onMenuOpenChange(false); onAuthHandoff() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Site data", color = iconTint) }, onClick = { onMenuOpenChange(false); onClearSiteData() })
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
    }
}

/* Deferred browser-surface cleanup: keep browser-specific renderer logic intact. */

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
    // Coordinates are local to the resized GeckoView viewport.
    val targetX = touchX
    val targetY = touchY

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
