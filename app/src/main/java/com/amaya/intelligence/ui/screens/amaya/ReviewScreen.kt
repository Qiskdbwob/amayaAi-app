package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.memory.PendingProposal
import com.amaya.intelligence.domain.memory.PendingProposalType
import com.amaya.intelligence.ui.theme.SectionShape

@Composable
fun ReviewScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    AmayaScaffold("Review", snackbarHostState, onNavigateBack) {
        if (state.pendingProposals.isEmpty()) {
            AmayaSection("Queue") {
                AmayaStatusRow("Pending suggestions", "0")
            }
        } else {
            val grouped = state.pendingProposals.groupBy { it.type.reviewGroup() }
            grouped.forEach { (title, proposals) ->
                AmayaSection(title) {
                    proposals.forEachIndexed { index, proposal ->
                        SuggestionCard(proposal, onSave, onDismiss)
                        if (index < proposals.lastIndex) AmayaDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    proposal: PendingProposal,
    onSave: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    Surface(color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(proposal.type.friendlyTitle(), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text("“${proposal.content.take(220)}”", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Save to: ${proposal.type.destinationLabel()} · ${proposal.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(proposal.id) }) { Text(if (proposal.type.isSkillType()) "Save Skill" else "Save") }
                OutlinedButton(onClick = { onDismiss(proposal.id) }) { Text("Dismiss") }
            }
        }
    }
}

private fun PendingProposalType.reviewGroup(): String = when {
    isSkillType() -> "Skills"
    this == PendingProposalType.WORKSPACE_FACT -> "Project Memory"
    else -> "Memory"
}

private fun PendingProposalType.friendlyTitle(): String = when (this) {
    PendingProposalType.USER_PROFILE -> "User preference"
    PendingProposalType.LONG_TERM_MEMORY -> "Important memory"
    PendingProposalType.DAILY_LOG -> "Daily note"
    PendingProposalType.WORKSPACE_FACT -> "Project fact"
    PendingProposalType.SKILL_CREATE -> "New reusable workflow"
    PendingProposalType.SKILL_PATCH -> "Skill improvement"
    PendingProposalType.SKILL_UPDATE -> "Skill update"
    PendingProposalType.REMINDER -> "Reminder"
}

private fun PendingProposalType.destinationLabel(): String = when (this) {
    PendingProposalType.USER_PROFILE -> "Memory > About You"
    PendingProposalType.LONG_TERM_MEMORY -> "Memory > Important Memory"
    PendingProposalType.DAILY_LOG -> "Memory > Daily Notes"
    PendingProposalType.WORKSPACE_FACT -> "Memory > Project Memory"
    PendingProposalType.SKILL_CREATE,
    PendingProposalType.SKILL_PATCH,
    PendingProposalType.SKILL_UPDATE -> "Skills"
    PendingProposalType.REMINDER -> "Reminders"
}
