package com.amaya.intelligence.ui.screens.project.local

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.models.ProjectFileEntry
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.project.shared.BreadcrumbBar
import com.amaya.intelligence.ui.screens.project.shared.FileListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.name

private data class IosProjectBrowserColors(
    val groupedBackground: Color,
    val groupSurface: Color,
    val border: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val headerText: Color
)

@Composable
private fun iosProjectBrowserColors(): IosProjectBrowserColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosProjectBrowserColors(
            groupedBackground = Color(0xFF0B0B0F),
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            headerText = Color(0xFFEBEBF5).copy(alpha = 0.48f)
        )
    } else {
        IosProjectBrowserColors(
            groupedBackground = Color(0xFFF2F2F7),
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            headerText = Color(0xFF3C3C43).copy(alpha = 0.52f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalProjectBrowserScreen(
    onWorkspaceSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = iosProjectBrowserColors()
    var currentPath by remember {
        mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
    }
    var files by remember { mutableStateOf<List<ProjectFileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(currentPath, showHidden) {
        isLoading = true
        files = loadLocalFiles(currentPath, showHidden)
        isLoading = false
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onWorkspaceSelected(currentPath) },
                icon = { Icon(Icons.Default.Check, "Select") },
                text = { Text("Select This Folder", style = MaterialTheme.typography.labelLarge) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(colors.groupedBackground)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.statusBarsPadding().height(52.dp))
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    placeholder = {
                        Text(
                            "Search files...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.secondaryText
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.iconTint
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(20.dp), tint = colors.iconTint)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = colors.groupSurface,
                        focusedContainerColor = colors.groupSurface
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.primaryText)
                )

                BreadcrumbBar(
                    path = currentPath,
                    onNavigate = { currentPath = it }
                )

                Spacer(Modifier.height(16.dp))

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        isLoading -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary
                        )
                        filteredFiles.isEmpty() -> Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(colors.iconBackground),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    null,
                                    modifier = Modifier.size(40.dp),
                                    tint = colors.iconTint.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            Text(
                                if (searchQuery.isNotEmpty()) "No files match your search" else "This folder is empty",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.secondaryText
                            )
                        }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (currentPath != "/" && currentPath != Environment.getExternalStorageDirectory().absolutePath) {
                                item {
                                    FileListItem(
                                        item = ProjectFileEntry(
                                            name = "..",
                                            path = Paths.get(currentPath).parent?.toString() ?: "/",
                                            type = "directory",
                                            size = 0
                                        ),
                                        onClick = { selected ->
                                            if (selected.name == "..") {
                                                currentPath = Paths.get(currentPath).parent?.toString() ?: "/"
                                            } else {
                                                currentPath = selected.path
                                            }
                                            searchQuery = ""
                                        },
                                        isFirst = true,
                                        isLast = filteredFiles.isEmpty()
                                    )
                                }
                            }

                            items(filteredFiles.size, key = { filteredFiles[it].path }) { index ->
                                val item = filteredFiles[index]
                                val isFirst = index == 0 && (currentPath == "/" || currentPath == Environment.getExternalStorageDirectory().absolutePath)
                                val isLast = index == filteredFiles.size - 1

                                FileListItem(
                                    item = item,
                                    onClick = { selected ->
                                        if (selected.type == "directory") {
                                            currentPath = selected.path
                                            searchQuery = ""
                                        }
                                    },
                                    isFirst = isFirst,
                                    isLast = isLast
                                )
                            }
                        }
                    }
                }
            }

            TopAppBar(
                title = {
                    Text(
                        "Workspace",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 12.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    SettingsBackButton(onClick = onDismiss)
                },
                actions = {
                    com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                        icon = if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        onClick = { showHidden = !showHidden },
                        contentDescription = "Toggle hidden files",
                        modifier = Modifier.padding(end = 12.dp)
                    )
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

private suspend fun loadLocalFiles(path: String, showHidden: Boolean): List<ProjectFileEntry> =
    withContext(Dispatchers.IO) {
        try {
            val dir = Paths.get(path)
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return@withContext emptyList()
            }

            Files.list(dir).use { stream ->
                stream
                    .filter { showHidden || !it.isHidden() }
                    .map { file ->
                        ProjectFileEntry(
                            name = file.name,
                            path = file.toString(),
                            type = if (file.isDirectory()) "directory" else "file",
                            size = if (file.isDirectory()) 0 else Files.size(file)
                        )
                    }
                    .collect(Collectors.toList())
                    .sortedWith(compareBy({ it.type != "directory" }, { it.name.lowercase() }))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
