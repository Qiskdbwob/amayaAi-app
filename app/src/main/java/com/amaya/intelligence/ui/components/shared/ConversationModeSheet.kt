package com.amaya.intelligence.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.amaya.intelligence.domain.models.ConversationModeOption
import com.amaya.intelligence.ui.theme.LocalAmayaGradients

/**
 * Generic conversation-mode picker. Each provider supplies its own list of
 * [options] through its `IdeProvider.conversationModes` declaration, so the UI
 * stays provider-neutral.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationModeSheet(
    options: List<ConversationModeOption>,
    selectedId: String?,
    title: String = "Conversation Mode",
    onSelect: (ConversationModeOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberLockedModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = lockedModalBottomSheetProperties(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        shape = responsiveBottomSheetShape(sheetState)
    ) {
        val gradients = LocalAmayaGradients.current
        val scrollState = rememberScrollState()

        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .ignoreNestedScrollForBottomSheet()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.height(90.dp))

                if (options.isEmpty()) {
                    Text(
                        text = "This provider has no conversation modes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    options.forEach { option ->
                        ConversationModeItem(
                            option = option,
                            isSelected = option.id == selectedId,
                            onClick = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (!sheetState.isVisible) onSelect(option)
                                }
                            }
                        )
                    }
                }
            }

            // Header overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(gradients.modalTopScrim)
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = responsiveDragHandleAlpha(sheetState)))
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
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
                            }
                            .align(Alignment.CenterEnd),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationModeItem(
    option: ConversationModeOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (option.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
