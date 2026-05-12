package com.amaya.intelligence.ui.screens.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.amaya.intelligence.ui.components.shared.ignoreNestedScrollForBottomSheet
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteSessionClient
import com.amaya.intelligence.impl.ide.IdeProviderFactory
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.ui.components.remote.WindowsBridgeChatPanelViewModel
import com.amaya.intelligence.ui.components.remote.WindowsBridgeConnectionSetupSheet
import androidx.compose.ui.graphics.Brush
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.res.UiStrings
import com.amaya.intelligence.ui.res.UiDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import com.amaya.intelligence.impl.common.mappers.RemoteIdeIcon
import kotlinx.coroutines.launch

/**
 * Remote Session screen — IDE selector + IP/Port input.
 *
 * If already connected: shows connected status with disconnect button (no IP/Port).
 * If disconnected: shows IDE list → IP + Port → Connect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSessionScreen(
    client: RemoteSessionClient,
    onBack: () -> Unit,
    onConnected: () -> Unit,
    onWindowsBridgeConnected: () -> Unit = onConnected,
    onOpencodeSelected: () -> Unit = {},
    bridgeViewModel: WindowsBridgeChatPanelViewModel = hiltViewModel()
) {
    val connectionState by client.connectionState.collectAsState()
    val errorMessage by client.errorMessage.collectAsState()
    val serverInfo by client.serverInfo.collectAsState()
    val bridgeState by bridgeViewModel.state.collectAsState()
    val gradients = LocalAmayaGradients.current
    val isDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val sessionColors = remoteSessionColors()

    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // Currently only Antigravity — expand in the future
    var selectedIde by remember { mutableStateOf<String?>(null) }

    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING

    // Auto-navigate to chat screen when connected
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            onConnected()
        }
    }
    var showConnectionSheet by remember { mutableStateOf(false) }
    var showBridgeSheet by remember { mutableStateOf(false) }
    var bridgeConnectInFlight by remember { mutableStateOf(false) }

    LaunchedEffect(bridgeState.isConnected, bridgeConnectInFlight) {
        if (bridgeState.isConnected && bridgeConnectInFlight) {
            bridgeConnectInFlight = false
            showBridgeSheet = false
            onWindowsBridgeConnected()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(sessionColors.groupedBackground)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // Standard spacing for the transparent header
                Spacer(Modifier.statusBarsPadding().height(52.dp))

                // ── Connected state ──────────────────────────────────
                if (isConnected) {
                    RemoteSessionSection("Connection") {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF34C759).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF34C759))
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                val providerName = selectedIde?.let { IdeProviderFactory.getIdeInfo(it)?.displayName }
                                    ?: UiStrings.App.REMOTE_NAME
                                Text(
                                    "${UiStrings.Connection.CONNECTED_TO} $providerName",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                        lineHeight = 19.sp
                                    ),
                                    color = sessionColors.primaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "ws://${serverInfo ?: ""}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 12.5.sp,
                                        lineHeight = 16.sp
                                    ),
                                    color = sessionColors.secondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { onConnected() },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    UiStrings.Connection.OPEN_CHAT,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                } else {
                    // ── Disconnected state: IDE selector ─────────────

                    // Error message
                    errorMessage?.let { error ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                            border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.20f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline, null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // IDE + Windows Bridge grouped list
                    val allIdes = remember {
                        IdeProviderFactory.getAll()
                            .filter { it.info.capabilities.requiresConnection }
                            // Opencode has its own dedicated landing flow, keep it out of the IDE list.
                            .filter { it.ideId != "opencode" }
                    }
                    val totalRows = allIdes.size + 2 // +1 Windows Bridge, +1 Opencode

                    RemoteSessionSection(UiStrings.Connection.REMOTE_CONNECTION) {
                        allIdes.forEachIndexed { index, provider ->
                            val info = provider.info
                            val iconSpec = RemoteIdeIcon.resolve(info.id, isDark)
                            val isFirst = index == 0
                            val isLast = false // Windows Bridge + Opencode always follow
                            IdeRow(
                                name = info.displayName,
                                description = info.description,
                                iconSpec = iconSpec,
                                enabled = provider.isEnabled,
                                isFirst = isFirst,
                                isLast = isLast,
                                onClick = {
                                    selectedIde = info.id
                                    showConnectionSheet = true
                                }
                            )
                            RemoteSessionDivider()
                        }
                        // Windows Bridge row
                        IdeRow(
                            name = "Windows Bridge",
                            description = "Control your Windows PC remotely",
                            iconSpec = RemoteIdeIcon.Spec(imageVector = Icons.Default.DesktopWindows, tintable = true),
                            enabled = true,
                            isFirst = allIdes.isEmpty(),
                            isLast = false,
                            onClick = { showBridgeSheet = true }
                        )
                        RemoteSessionDivider()
                        // Opencode row — always last. Dedicated landing screen.
                        IdeRow(
                            name = "Opencode",
                            description = "Run the opencode CLI agent through your Windows bridge",
                            iconSpec = RemoteIdeIcon.Spec(imageVector = Icons.Default.Terminal, tintable = true),
                            enabled = true,
                            isFirst = false,
                            isLast = true,
                            onClick = onOpencodeSelected
                        )
                    }

                    Spacer(Modifier.height(64.dp))
                }
            }

            // Scrims
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.TopCenter)
                    .background(gradients.topScrim)
            )

            // Header Overlay
            TopAppBar(
                title = { 
                    Text(
                        UiStrings.Connection.REMOTE_SESSION, 
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    ) 
                },
                navigationIcon = {
                    SettingsBackButton(onClick = onBack)
                },
                actions = {
                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                                .clickable { client.disconnect() }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(6.dp))
                                Text(UiStrings.Connection.DISCONNECT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        // Empty box to maintain symmetry/spacing if needed, 
                        // but usually there's no right-side button in disconnected state now
                        Spacer(Modifier.width(48.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                modifier = Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp),
                windowInsets = WindowInsets(0.dp)
            )

            if (showConnectionSheet) {
                ConnectionSetupSheet(
                    ipAddress = ipAddress,
                    onIpChange = { ipAddress = it },
                    port = port,
                    onPortChange = { port = it },
                    isConnecting = isConnecting,
                    onConnect = {
                        showConnectionSheet = false
                        port.toIntOrNull()?.let { client.connect(ipAddress, it) }
                    },
                    onDismiss = { showConnectionSheet = false }
                )
            }

            if (showBridgeSheet) {
                WindowsBridgeConnectionSetupSheet(
                    state = bridgeState,
                    onHostChange = bridgeViewModel::updateHost,
                    onPortChange = bridgeViewModel::updatePort,
                    onTokenChange = bridgeViewModel::updateToken,
                    onConnect = {
                        bridgeConnectInFlight = true
                        bridgeViewModel.connect()
                    },
                    onDismiss = { showBridgeSheet = false }
                )
            }
        }
    }
}

// ── iOS-style color tokens ───────────────────────────────────────────────────

private data class RemoteSessionColors(
    val groupedBackground: Color,
    val groupSurface: Color,
    val border: Color,
    val separator: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val headerText: Color
)

@Composable
private fun remoteSessionColors(): RemoteSessionColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        RemoteSessionColors(
            groupedBackground = Color(0xFF0B0B0F),
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            separator = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            headerText = Color(0xFFEBEBF5).copy(alpha = 0.48f)
        )
    } else {
        RemoteSessionColors(
            groupedBackground = Color(0xFFF2F2F7),
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            separator = Color(0xFF3C3C43).copy(alpha = 0.13f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            headerText = Color(0xFF3C3C43).copy(alpha = 0.52f)
        )
    }
}

// ── Section container ────────────────────────────────────────────────────────

@Composable
private fun RemoteSessionSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = remoteSessionColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.headerText,
            modifier = Modifier.padding(start = 16.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.groupSurface,
            border = BorderStroke(0.7.dp, colors.border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

// ── IDE row ──────────────────────────────────────────────────────────────────

@Composable
private fun IdeRow(
    name: String,
    description: String,
    iconSpec: RemoteIdeIcon.Spec?,
    enabled: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val colors = remoteSessionColors()
    val isDark = isSystemInDarkTheme()
    val itemShape = when {
        isFirst && isLast -> RoundedCornerShape(16.dp)
        isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(0.dp)
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = itemShape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Monochrome circular icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) colors.iconBackground
                        else colors.iconBackground.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val tint = if (enabled) colors.iconTint else colors.iconTint.copy(alpha = 0.4f)
                when {
                    iconSpec?.resId != null -> Icon(
                        painter = painterResource(id = iconSpec.resId),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = tint
                    )
                    iconSpec?.imageVector != null -> Icon(
                        imageVector = iconSpec.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = tint
                    )
                    else -> Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = tint
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = if (enabled) colors.primaryText else colors.primaryText.copy(alpha = 0.35f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = if (enabled) colors.secondaryText else colors.secondaryText.copy(alpha = 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (!enabled) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = colors.iconBackground
                ) {
                    Text(
                        "Soon",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.secondaryText.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Separator ────────────────────────────────────────────────────────────────

@Composable
private fun RemoteSessionDivider() {
    val colors = remoteSessionColors()
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = colors.separator,
        thickness = 0.7.dp
    )
}

@Composable
fun QrScannerOverlay(
    onScan: (String) -> Unit,
    onClose: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            QrScannerView(onScan = onScan)

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }

            // Instructions
            Text(
                "Scan the QR code in VS Code",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            
            // Scanner frame simulation
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .align(Alignment.Center)
                    .background(Color.Transparent, RoundedCornerShape(12.dp))
                    .then(Modifier.border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)))
            )
        }
    }
}

@Composable
fun QrScannerView(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val barcodeScanner = BarcodeScanning.getClient()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        barcodeScanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull()?.rawValue?.let { code ->
                                    if (code.startsWith("amaya://")) {
                                        onScan(code)
                                    }
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    android.util.Log.e("QrScanner", "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSetupSheet(
    ipAddress: String,
    onIpChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    var showScanner by remember { mutableStateOf(false) }
    val maxSheetHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.75f
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = com.amaya.intelligence.ui.components.shared.lockedModalBottomSheetProperties(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        shape = com.amaya.intelligence.ui.components.shared.responsiveBottomSheetShape(sheetState)
    ) {
        val gradients = LocalAmayaGradients.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .weight(1f, fill = false)
        ) {
            // Bottom Layer: Scrolling Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .ignoreNestedScrollForBottomSheet()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(90.dp)) // Reduced spacer for tighter layout
                if (showScanner) {
                    QrScannerOverlay(
                        onScan = { data ->
                            val uri = android.net.Uri.parse(data)
                            uri.getQueryParameter("ip")?.let { onIpChange(it) }
                            uri.getQueryParameter("port")?.let { onPortChange(it) }
                            showScanner = false
                        },
                        onClose = { showScanner = false }
                    )
                }

                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = onIpChange,
                    label = { Text(UiStrings.Connection.IP_ADDRESS) },
                    placeholder = { Text("Enter IP address") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        IconButton(onClick = { showScanner = true }) {
                            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                        }
                    },
                    enabled = !isConnecting
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text(UiStrings.Connection.PORT) },
                    placeholder = { Text("Enter port") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Adjust, null, modifier = Modifier.size(18.dp)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isConnecting
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                onConnect()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = ipAddress.isNotBlank() && port.toIntOrNull() != null && !isConnecting
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            UiStrings.Connection.CONNECT,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Top Layer: Blurred Header Overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(gradients.modalTopScrim)
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = 110.dp) // Ensure substantial drag surface area
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = com.amaya.intelligence.ui.components.shared.responsiveDragHandleAlpha(sheetState)))
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        UiStrings.Connection.CONNECTION_SETUP,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    .compositeOver(MaterialTheme.colorScheme.background)
                            )
                            .clickable {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (!sheetState.isVisible) onDismiss()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
