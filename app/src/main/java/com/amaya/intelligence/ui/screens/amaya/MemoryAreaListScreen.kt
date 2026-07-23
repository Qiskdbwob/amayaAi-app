package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.MemoryRecord
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

private data class IosMemoryAreaColors(
    val groupedBackground: Color,
    val groupSurface: Color,
    val border: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color
)

@Composable
private fun iosMemoryAreaColors(): IosMemoryAreaColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosMemoryAreaColors(
            groupedBackground = Color(0xFF0B0B0F),
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f)
        )
    } else {
        IosMemoryAreaColors(
            groupedBackground = Color(0xFFF2F2F7),
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryAreaListScreen(
    area: MemoryArea,
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (MemoryRecord) -> Unit = {}
) {
    val colors = iosMemoryAreaColors()
    val gradients = LocalAmayaGradients.current
    var showAddSheet by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MemoryRecord?>(null) }

    val records = when (area) {
        MemoryArea.USER -> state.userMemoryRecords
        MemoryArea.PROJECT -> state.projectMemoryRecords
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.groupedBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Spacer(Modifier.statusBarsPadding().height(52.dp))

            if (records.isNotEmpty()) {
                AmayaSection("Saved") {
                    records.forEachIndexed { index, record ->
                        MemoryRecordCard(record = record, colors = colors, onDelete = { deleting = record })
                        if (index < records.lastIndex) AmayaDivider()
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No saved items",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.secondaryText
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (area == MemoryArea.PROJECT && state.workspacePath == null) "Select a workspace first" else "Tap + to add",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryText.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(100.dp))
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
                    area.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp),
                    fontWeight = FontWeight.SemiBold
                )
            },
            navigationIcon = {
                com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onNavigateBack,
                    contentDescription = "Back",
                    modifier = Modifier.padding(start = 12.dp)
                )
            },
            actions = {
                if (area != MemoryArea.PROJECT || state.workspacePath != null) {
                    com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                        icon = Icons.Default.Add,
                        onClick = { showAddSheet = true },
                        contentDescription = "Add Memory",
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            ),
            modifier = Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp),
            windowInsets = WindowInsets(0.dp)
        )
    }

    if (showAddSheet) {
        AddMemorySheet(
            area = area,
            onDismiss = { showAddSheet = false },
            onAdd = onAdd
        )
    }
    deleting?.let { record -> androidx.compose.material3.AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("Delete memory?") },
        text = { Text(record.content) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onDelete(record); deleting = null }) { Text("Delete") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel") } }
    ) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemorySheet(
    area: MemoryArea,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    val colors = iosMemoryAreaColors()
    var text by remember { mutableStateOf("") }

    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Add ${area.title}"
    ) {
        val cancel = { dismiss() }
        val save = { dismiss { onAdd(text) } }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(area.inputLabel()) },
            minLines = 2,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.primaryText)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = cancel) {
                Text("Cancel")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = save,
                enabled = text.isNotBlank()
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun MemoryRecordCard(record: MemoryRecord, colors: IosMemoryAreaColors, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                record.content,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.primaryText
            )
            if (record.reason.isNotBlank()) {
                Text(
                    record.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete memory", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun MemoryArea.inputLabel(): String = when (this) {
    MemoryArea.USER -> "Preference or profile fact"
    MemoryArea.PROJECT -> "Workspace or project fact"
}

