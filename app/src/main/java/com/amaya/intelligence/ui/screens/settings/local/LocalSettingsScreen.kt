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
import com.amaya.intelligence.data.local.entity.AgentEntity
import com.amaya.intelligence.data.local.entity.AgentGroupEntity
import com.amaya.intelligence.data.local.entity.ProjectEntity
import com.amaya.intelligence.data.remote.api.AiSettings
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.data.remote.api.McpConfig
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet
import com.amaya.intelligence.ui.res.UiStrings
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

enum class SettingsScope(val label: String) {
    GLOBAL("Global"), PROJECT("Project"), AGENT("Agent")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSettingsScreen(
    onNavigateBack: () -> Unit,
    initialScope: SettingsScope,
    projects: List<ProjectEntity>,
    agentGroups: List<AgentGroupEntity>,
    agents: List<AgentEntity>,
    aiSettingsManager: AiSettingsManager,
    onNavigateToModels: () -> Unit,
    onNavigateToMcp: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToAboutYou: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onOpenProject: (ProjectEntity) -> Unit,
    onCreateProject: (String, String, String) -> Unit,
    onSelectProjectWorkspace: () -> Unit,
    selectedProjectWorkspace: String?,
    onOpenAgentGroup: (AgentGroupEntity) -> Unit,
    onCreateAgentGroup: (String, String, String) -> Unit,
    onSelectAgentWorkspace: () -> Unit,
    selectedAgentWorkspace: String?
) {
    var selectedScope by remember { mutableStateOf(initialScope) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by aiSettingsManager.settingsFlow.collectAsState(initial = AiSettings())
    val colors = iosSettingsColors()
    val gradients = LocalAmayaGradients.current
    var addSheet by remember { mutableStateOf<SettingsScope?>(null) }

    Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Spacer(Modifier.statusBarsPadding().height(52.dp))
                PrimaryTabRow(
                    selectedTabIndex = selectedScope.ordinal,
                    containerColor = Color.Transparent,
                    divider = {}
                ) {
                    SettingsScope.entries.forEach { tab ->
                        Tab(
                            selected = selectedScope == tab,
                            onClick = { selectedScope = tab },
                            text = { Text(tab.label, maxLines = 1) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }

                when (selectedScope) {
                    SettingsScope.GLOBAL -> GlobalSettings(
                        settings = settings,
                        onModels = onNavigateToModels,
                        onAboutYou = onNavigateToAboutYou,
                        onMcp = onNavigateToMcp,
                        onSkills = onNavigateToSkills,
                        onTerminal = onNavigateToTerminal,
                        onSelectTheme = { theme -> scope.launch { aiSettingsManager.setTheme(theme) } },
                        onSnackbar = { text -> scope.launch { snackbar.showSnackbar(text) } }
                    )
                    SettingsScope.PROJECT -> ProjectListSettings(projects, onOpenProject)
                    SettingsScope.AGENT -> AgentListSettings(agentGroups, agents, onOpenAgentGroup)
                }
                Spacer(Modifier.height(64.dp))
            }

            Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter).background(gradients.topScrim))
            if (selectedScope != SettingsScope.GLOBAL) {
                ExtendedFloatingActionButton(
                    onClick = { addSheet = selectedScope },
                    icon = { Icon(Icons.Default.Add, if (selectedScope == SettingsScope.PROJECT) "Add project" else "Add agent group") },
                    text = { Text(if (selectedScope == SettingsScope.PROJECT) "Project" else "Agent") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                )
            }
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp)) },
                navigationIcon = { SettingsBackButton(onClick = onNavigateBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp),
                windowInsets = WindowInsets(0.dp)
            )
        }
    }

    when (addSheet) {
        SettingsScope.PROJECT -> AddOwnerSheet(
            title = "New project",
            nameLabel = "Project name",
            instructionsLabel = "Project instructions",
            workspace = selectedProjectWorkspace,
            onSelectWorkspace = onSelectProjectWorkspace,
            onCreate = { name, instructions, workspace -> onCreateProject(name, instructions, workspace); addSheet = null },
            onDismiss = { addSheet = null }
        )
        SettingsScope.AGENT -> AddOwnerSheet(
            title = "New agent group",
            nameLabel = "Agent group name",
            instructionsLabel = "Shared instructions",
            workspace = selectedAgentWorkspace,
            onSelectWorkspace = onSelectAgentWorkspace,
            onCreate = { name, instructions, workspace -> onCreateAgentGroup(name, instructions, workspace); addSheet = null },
            onDismiss = { addSheet = null }
        )
        else -> Unit
    }
}

@Composable
private fun AddOwnerSheet(
    title: String,
    nameLabel: String,
    instructionsLabel: String,
    workspace: String?,
    onSelectWorkspace: () -> Unit,
    onCreate: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(title) { mutableStateOf("") }
    var instructions by remember(title) { mutableStateOf("") }
    StandardModalBottomSheet(onDismissRequest = onDismiss, title = title) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(nameLabel) }, singleLine = true)
        OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text(instructionsLabel) }, minLines = 4)
        OutlinedButton(onClick = onSelectWorkspace, modifier = Modifier.fillMaxWidth()) { Text(workspace ?: "Select workspace") }
        Button(
            onClick = { onCreate(name.trim(), instructions.trim(), workspace.orEmpty()) },
            enabled = name.isNotBlank() && !workspace.isNullOrBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create") }
    }
}

@Composable
private fun GlobalSettings(
    settings: AiSettings,
    onModels: () -> Unit,
    onAboutYou: () -> Unit,
    onMcp: () -> Unit,
    onSkills: () -> Unit,
    onTerminal: () -> Unit,
    onSelectTheme: (String) -> Unit,
    onSnackbar: (String) -> Unit
) {
    IosSettingsSection("AI") {
        val modelCount = settings.connections.sumOf { it.visibleModels.size }
        IosSettingsRow(
            Icons.Default.SmartToy,
            UiStrings.Settings.MANAGE_MODELS,
            if (settings.connections.isEmpty()) "No providers configured" else "${settings.connections.size} providers · $modelCount models",
            true,
            true,
            onModels
        )
    }
    IosSettingsSection("Knowledge") {
        IosSettingsRow(Icons.Default.Person, "About You", "Global saved profile and preferences", true, false, onAboutYou)
        IosSettingsDivider()
        IosSettingsRow(Icons.Default.Psychology, "Skills", "Universal reusable workflows", false, true, onSkills)
    }
    IosSettingsSection("Execution") {
        IosSettingsRow(Icons.Default.Terminal, "Terminal Policy", "Global trusted and declined commands", true, true, onTerminal)
    }
    IosSettingsSection("Integrations") {
        val mcp = remember(settings.mcpConfigJson) { McpConfig.fromJson(settings.mcpConfigJson) }
        val active = mcp.servers.count { it.enabled }
        IosSettingsRow(
            Icons.Default.Extension,
            UiStrings.Settings.MCP_SERVERS,
            if (mcp.servers.isEmpty()) UiStrings.Settings.NO_SERVERS_CONFIGURED else "$active of ${mcp.servers.size} active",
            true,
            true,
            onMcp
        )
    }
    IosSettingsSection("Appearance") { IosThemeRow(settings.theme, onSelectTheme) }
    IosSettingsSection("About") {
        IosSettingsRow(Icons.Default.Info, UiStrings.Settings.VERSION, UiStrings.Settings.VERSION_NUMBER, true, false) {
            onSnackbar("Amaya Intelligence v${UiStrings.Settings.VERSION_NUMBER}")
        }
        IosSettingsDivider()
        val context = androidx.compose.ui.platform.LocalContext.current
        IosSettingsRow(Icons.AutoMirrored.Filled.Help, UiStrings.Settings.HELP_FEEDBACK, UiStrings.Settings.HELP_FEEDBACK_SUBTITLE, false, true) {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/nazrielnr/amaya/pulls")))
        }
    }
}

@Composable
private fun ProjectListSettings(
    projects: List<ProjectEntity>,
    onOpenProject: (ProjectEntity) -> Unit
) {
    IosSettingsSection("Projects") {
        if (projects.isEmpty()) {
            IosStatusRow("No projects yet", "Use + Project to create the first project")
        } else {
            projects.forEachIndexed { index, project ->
                IosSettingsRow(Icons.Default.FolderOpen, project.name, project.rootPath, index == 0, false) { onOpenProject(project) }
                IosSettingsDivider()
            }
        }
    }
}

@Composable
private fun AgentListSettings(
    groups: List<AgentGroupEntity>,
    agents: List<AgentEntity>,
    onOpenGroup: (AgentGroupEntity) -> Unit
) {
    IosSettingsSection("Agent Groups") {
        if (groups.isEmpty()) {
            IosStatusRow("No agent groups yet", "Use + Agent to create the first group")
        } else {
            groups.forEachIndexed { index, group ->
                val count = agents.count { it.groupId == group.id }
                IosSettingsRow(
                    Icons.Default.Groups,
                    group.name,
                    "$count agent${if (count == 1) "" else "s"} · ${group.workspacePath}",
                    index == 0,
                    false
                ) { onOpenGroup(group) }
                IosSettingsDivider()
            }
        }
    }
}

@Composable
private fun IosStatusRow(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private data class IosSettingsColors(
    val groupedBackground: Color, val groupSurface: Color, val border: Color, val separator: Color,
    val iconBackground: Color, val iconTint: Color, val primaryText: Color, val secondaryText: Color, val headerText: Color
)

@Composable
private fun iosSettingsColors(): IosSettingsColors = if (isSystemInDarkTheme()) {
    IosSettingsColors(Color(0xFF0B0B0F), Color(0xFF1C1C1E), Color.White.copy(alpha = .10f), Color.White.copy(alpha = .10f), Color(0xFF2C2C2E), Color(0xFFC7C7CC), Color(0xFFF2F2F7), Color(0xFFEBEBF5).copy(alpha = .60f), Color(0xFFEBEBF5).copy(alpha = .48f))
} else {
    IosSettingsColors(Color(0xFFF2F2F7), Color.White, Color.Black.copy(alpha = .08f), Color(0xFF3C3C43).copy(alpha = .13f), Color(0xFFE9E9EE), Color(0xFF5F6368), Color(0xFF1C1C1E), Color(0xFF3C3C43).copy(alpha = .62f), Color(0xFF3C3C43).copy(alpha = .52f))
}

@Composable
private fun IosSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = iosSettingsColors()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = colors.headerText, modifier = Modifier.padding(start = 16.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = colors.groupSurface, border = BorderStroke(.7.dp, colors.border), modifier = Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
private fun IosSettingsRow(icon: ImageVector, title: String, subtitle: String, isFirst: Boolean, isLast: Boolean, onClick: () -> Unit) {
    val colors = iosSettingsColors()
    val shape = when { isFirst && isLast -> RoundedCornerShape(16.dp); isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp); isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp); else -> RoundedCornerShape(0.dp) }
    Surface(onClick = onClick, shape = shape, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.iconBackground), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = colors.iconTint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp), color = colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = colors.secondaryText.copy(alpha = .55f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun IosSettingsDivider() {
    val colors = iosSettingsColors()
    HorizontalDivider(Modifier.padding(start = 58.dp), thickness = .7.dp, color = colors.separator)
}

@Composable
private fun IosThemeRow(selectedTheme: String, onSelectTheme: (String) -> Unit) {
    val colors = iosSettingsColors()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Palette, null, tint = colors.iconTint)
            Spacer(Modifier.width(12.dp))
            Text(UiStrings.Settings.THEME, style = MaterialTheme.typography.bodyLarge, color = colors.primaryText)
        }
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val values = listOf("system" to UiStrings.Settings.SYSTEM, "light" to UiStrings.Settings.LIGHT, "dark" to UiStrings.Settings.DARK)
            values.forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = selectedTheme == value, onClick = { onSelectTheme(value) }, shape = SegmentedButtonDefaults.itemShape(index, values.size)) { Text(label) }
            }
        }
    }
}
