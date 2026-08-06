package com.amaya.intelligence.ui.screens.mcp.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpEditSheet(
    initialJson: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {


    var rawJson by remember {
        mutableStateOf(
            initialJson.ifBlank {
                """
                {
                  "mcpServers": {}
                }
                """.trimIndent()
            }
        )
    }

    val rawJsonError = when {
        rawJson.isBlank() -> "Raw JSON is required"
        runCatching { org.json.JSONObject(rawJson) }.isFailure -> "Invalid MCP JSON"
        else -> null
    }
    val isValid = rawJsonError == null

    com.amaya.intelligence.ui.components.shared.StandardModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "MCP Configuration"
    ) {
        com.amaya.intelligence.ui.screens.amaya.AmayaSection("JSON Configuration") {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = rawJson,
                    onValueChange = { rawJson = it },
                    placeholder = {
                        Text(
                            "{\n  \"mcpServers\": {\n    \"my-server\": {\n      \"serverUrl\": \"https://mcp.example.com/mcp\",\n      \"headers\": {},\n      \"enabled\": true\n    }\n  }\n}",
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 10,
                    maxLines = 24,
                    shape = RoundedCornerShape(12.dp),
                    isError = rawJsonError != null,
                    supportingText = rawJsonError?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        Button(
            onClick = { dismiss { onSave(rawJson.trim()) } },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = isValid
        ) {
            Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Save Config",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
