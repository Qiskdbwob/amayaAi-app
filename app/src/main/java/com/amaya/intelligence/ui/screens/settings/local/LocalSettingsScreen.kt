package com.amaya.intelligence.ui.screens.settings.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.res.UiStrings
import com.amaya.intelligence.ui.screens.amaya.AmayaViewModel
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSettingsScreen(
    onNavigateBack: () -> Unit,
    currentWorkspace: String?,
    onNavigateToWorkspace: () -> Unit,
    aiSettingsManager: AiSettingsManager,
    onNavigateToModels: () -> Unit,
    onNavigateToReminders: () -> Unit,
    onNavigateToMcp: () -> Unit,
    onNavigateToPersona: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToContextRecall: () -> Unit,
    onNavigateToReview: () -> Unit,
    onNavigateToPrivacy: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val settings by aiSettingsManager.settingsFlow.collectAsState(
        initial = com.amaya.intelligence.data.remote.api.AiSettings()
    )
    val gradients = LocalAmayaGradients.current
    val settingsColors = iosSettingsColors()
    val amayaViewModel: AmayaViewModel = hiltViewModel()
    val amayaState by amayaViewModel.uiState.collectAsState()
    LaunchedEffect(currentWorkspace) { amayaViewModel.setWorkspace(currentWorkspace) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize().background(settingsColors.groupedBackground)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Spacer(Modifier.statusBarsPadding().height(52.dp))

                IosSettingsSection("Workspace") {
                    IosSettingsRow(
                        icon = Icons.Default.Folder,
                        title = UiStrings.Settings.CURRENT_WORKSPACE,
                        subtitle = currentWorkspace ?: UiStrings.Settings.NOT_SELECTED,
                        isFirst = true,
                        isLast = true,
                        onClick = onNavigateToWorkspace
                    )
                }

                IosSettingsSection("AI") {
                    IosSettingsRow(
                        icon = Icons.Default.SmartToy,
                        title = UiStrings.Settings.MANAGE_MODELS,
                        subtitle = if (settings.connections.isEmpty()) {
                            "No providers configured"
                        } else {
                            val modelCount = settings.connections.sumOf { it.visibleModels.size }
                            "${settings.connections.size} ${if (settings.connections.size == 1) "provider" else "providers"} · $modelCount ${if (modelCount == 1) "model" else "models"}"
                        },
                        isFirst = true,
                        isLast = false,
                        onClick = onNavigateToModels
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        icon = Icons.Default.Person,
                        title = "Persona",
                        subtitle = "Voice & behavior",
                        isFirst = false,
                        isLast = false,
                        onClick = onNavigateToPersona
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        icon = Icons.Default.Memory,
                        title = "Memory",
                        subtitle = "${amayaState.totalMemoryCount} saved · tool only",
                        isFirst = false,
                        isLast = false,
                        onClick = onNavigateToMemory
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        icon = Icons.Default.Psychology,
                        title = "Skills",
                        subtitle = "${amayaState.enabledSkills} enabled · ${amayaState.activeSkills} active",
                        isFirst = false,
                        isLast = false,
                        onClick = onNavigateToSkills
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        icon = Icons.Default.TravelExplore,
                        title = "Context & Recall",
                        subtitle = "${amayaState.settings.context.enabledCount()} sources",
                        isFirst = false,
                        isLast = false,
                        onClick = onNavigateToContextRecall
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        icon = Icons.Default.AutoAwesome,
                        title = "Review",
                        subtitle = "${amayaState.pendingProposals.size} pending",
                        isFirst = false,
                        isLast = false,
                        onClick = onNavigateToReview
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        icon = Icons.Default.Security,
                        title = "Privacy & Safety",
                        subtitle = "Memory rules",
                        isFirst = false,
                        isLast = true,
                        onClick = onNavigateToPrivacy
                    )
                }

                IosSettingsSection("Automation") {
                    IosSettingsRow(
                        icon = Icons.Default.Alarm,
                        title = UiStrings.Settings.REMINDERS_JOBS,
                        subtitle = "Schedules",
                        isFirst = true,
                        isLast = false,
                        onClick = onNavigateToReminders
                    )
                    IosSettingsDivider()
                    val mcpConfig = remember(settings.mcpConfigJson) {
                        com.amaya.intelligence.data.remote.api.McpConfig.fromJson(settings.mcpConfigJson)
                    }
                    val activeCount = mcpConfig.servers.count { it.enabled }
                    val totalCount = mcpConfig.servers.size
                    val mcpSubtitle = when {
                        totalCount == 0 -> UiStrings.Settings.NO_SERVERS_CONFIGURED
                        activeCount == 0 -> "$totalCount server${if (totalCount > 1) "s" else ""}, none active"
                        else -> "$activeCount of $totalCount active"
                    }
                    IosSettingsRow(
                        icon = Icons.Default.Extension,
                        title = UiStrings.Settings.MCP_SERVERS,
                        subtitle = mcpSubtitle,
                        isFirst = false,
                        isLast = true,
                        onClick = onNavigateToMcp
                    )
                }

                IosSettingsSection("Appearance") {
                    IosThemeRow(
                        selectedTheme = settings.theme,
                        onSelectTheme = { theme -> scope.launch { aiSettingsManager.setTheme(theme) } }
                    )
                }

                IosSettingsSection("About") {
                    IosSettingsRow(
                        icon = Icons.Default.Info,
                        title = UiStrings.Settings.VERSION,
                        subtitle = UiStrings.Settings.VERSION_NUMBER,
                        isFirst = true,
                        isLast = false,
                        onClick = {
                            scope.launch { snackbarHostState.showSnackbar("Amaya Intelligence v${UiStrings.Settings.VERSION_NUMBER}") }
                        }
                    )
                    IosSettingsDivider()
                    val context = androidx.compose.ui.platform.LocalContext.current
                    IosSettingsRow(
                        icon = Icons.AutoMirrored.Filled.Help,
                        title = UiStrings.Settings.HELP_FEEDBACK,
                        subtitle = UiStrings.Settings.HELP_FEEDBACK_SUBTITLE,
                        isFirst = false,
                        isLast = false,
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/nazrielnr/amaya/pulls")
                            )
                            context.startActivity(intent)
                        }
                    )
                    IosSettingsDivider()
                    val updateViewModel: com.amaya.intelligence.ui.screens.settings.shared.UpdateViewModel = hiltViewModel()
                    val updateState by updateViewModel.uiState.collectAsState()

                    IosSettingsRow(
                        icon = Icons.Default.SystemUpdate,
                        title = UiStrings.Settings.CHECK_FOR_UPDATE,
                        subtitle = when (updateState) {
                            is com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.Checking -> UiStrings.Settings.CHECKING_UPDATE
                            is com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.UpToDate -> UiStrings.Settings.UP_TO_DATE
                            is com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.UpdateAvailable -> "New version available"
                            else -> "Tap to check"
                        },
                        isFirst = false,
                        isLast = true,
                        onClick = { updateViewModel.checkForUpdate() }
                    )

                    if (updateState is com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.UpdateAvailable) {
                        val info = (updateState as com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.UpdateAvailable).info
                        com.amaya.intelligence.ui.components.shared.UpdateInfoSheet(
                            info = info,
                            onDismiss = { updateViewModel.dismiss() }
                        )
                    }

                    LaunchedEffect(updateState) {
                        if (updateState is com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.UpToDate) {
                            snackbarHostState.showSnackbar(UiStrings.Settings.UP_TO_DATE)
                            updateViewModel.dismiss()
                        } else if (updateState is com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.Error) {
                            snackbarHostState.showSnackbar((updateState as com.amaya.intelligence.ui.screens.settings.shared.UpdateUiState.Error).message)
                            updateViewModel.dismiss()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(64.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.TopCenter)
                    .background(gradients.topScrim)
            )

            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                },
                navigationIcon = {
                    SettingsBackButton(onClick = onNavigateBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                modifier = Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp),
                windowInsets = WindowInsets(0.dp)
            )
        }
    }
}

private fun com.amaya.intelligence.data.repository.ContextRecallSettings.enabledCount(): Int =
    listOf(pastChatRecallEnabled, workspaceContextEnabled, relevantMemoryEnabled).count { it }

private data class IosSettingsColors(
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
private fun iosSettingsColors(): IosSettingsColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosSettingsColors(
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
        IosSettingsColors(
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

@Composable
private fun IosSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = iosSettingsColors()
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

@Composable
private fun IosSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = iosSettingsColors()
    val itemShape = when {
        isFirst && isLast -> RoundedCornerShape(16.dp)
        isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(0.dp)
    }

    Surface(
        onClick = onClick,
        shape = itemShape,
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IosSettingsIcon(icon = icon)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = colors.secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = colors.secondaryText.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun IosSettingsIcon(icon: ImageVector) {
    val colors = iosSettingsColors()
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
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

@Composable
private fun IosSettingsDivider() {
    val colors = iosSettingsColors()
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = colors.separator,
        thickness = 0.7.dp
    )
}

@Composable
private fun IosThemeRow(
    selectedTheme: String,
    onSelectTheme: (String) -> Unit
) {
    val colors = iosSettingsColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IosSettingsIcon(icon = Icons.Default.Palette)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                UiStrings.Settings.THEME,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = colors.primaryText
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val themes = listOf("system", "light", "dark")
                val labels = listOf(UiStrings.Settings.SYSTEM, UiStrings.Settings.LIGHT, UiStrings.Settings.DARK)
                themes.forEachIndexed { index, theme ->
                    SegmentedButton(
                        selected = selectedTheme == theme,
                        onClick = { onSelectTheme(theme) },
                        shape = SegmentedButtonDefaults.itemShape(index, themes.size)
                    ) {
                        Text(labels[index], style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
