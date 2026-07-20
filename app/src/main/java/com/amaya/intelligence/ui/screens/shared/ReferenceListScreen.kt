package com.amaya.intelligence.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.ui.screens.amaya.AmayaDivider
import com.amaya.intelligence.ui.screens.amaya.AmayaNavigationRow
import com.amaya.intelligence.ui.screens.amaya.AmayaScaffold
import com.amaya.intelligence.ui.screens.amaya.AmayaSection
import java.io.File

@Composable
fun ReferenceListScreen(
    title: String,
    paths: List<String>,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onAdd: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AmayaScaffold(title, snackbarHostState, onNavigateBack) {
            AmayaSection("References") {
                if (paths.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No references", style = MaterialTheme.typography.titleSmall)
                        Text("Add a text document for this context.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else paths.forEachIndexed { index, path ->
                    if (index > 0) AmayaDivider()
                    AmayaNavigationRow(Icons.Default.Description, File(path).name.substringAfter('_'), path, onClick = {})
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, "Add reference") },
            text = { Text("Reference") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
}
