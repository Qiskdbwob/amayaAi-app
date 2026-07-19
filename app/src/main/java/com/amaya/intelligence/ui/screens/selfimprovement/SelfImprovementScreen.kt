package com.amaya.intelligence.ui.screens.selfimprovement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.ProposalApplyResult
import com.amaya.intelligence.data.repository.SelfImprovementMode
import com.amaya.intelligence.domain.memory.PendingProposal
import com.amaya.intelligence.domain.memory.PendingProposalType

private enum class LearningTab(val title: String) {
    OVERVIEW("Overview"),
    REVIEW("Review"),
    MEMORY("Memory"),
    PRIVACY("Privacy")
}

@Composable
fun SelfImprovementScreen(
    mode: SelfImprovementMode,
    pendingProposals: List<PendingProposal>,
    lastMaintenanceRun: String,
    promptPreview: PromptPreviewState,
    lastApplyResults: List<ProposalApplyResult>,
    onModeChange: (SelfImprovementMode) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onApply: (String) -> Unit,
    onApplyApproved: () -> Unit,
    modifier: Modifier = Modifier,
    initialSection: String = "overview"
) {
    var selectedTab by remember(initialSection) { mutableStateOf(initialSection.toLearningTab()) }

    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 16.dp) {
            LearningTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.title) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                LearningTab.OVERVIEW -> {
                    item { LearningIntroCard() }
                    item {
                        SelfImprovementSettingsCard(
                            mode = mode,
                            pendingCount = pendingProposals.size,
                            lastMaintenanceRun = lastMaintenanceRun,
                            onModeChange = onModeChange
                        )
                    }
                    item { FlowExplanationCard() }
                }
                LearningTab.REVIEW -> {
                    item {
                        ReviewHeaderCard(
                            pendingCount = pendingProposals.count { it.status.name == "PENDING" },
                            approvedCount = pendingProposals.count { it.status.name == "APPROVED" },
                            onApplyApproved = onApplyApproved
                        )
                    }
                    if (lastApplyResults.isNotEmpty()) {
                        item { ApplyResultsCard(results = lastApplyResults) }
                    }
                    if (pendingProposals.isEmpty()) {
                        item { EmptyStateCard("No suggestions waiting", "Safe memory is auto-saved; reusable workflow candidates wait for review.") }
                    } else {
                        items(pendingProposals, key = { it.id }) { proposal ->
                            PendingProposalCard(
                                proposal = proposal,
                                onApprove = onApprove,
                                onReject = onReject,
                                onApply = onApply
                            )
                        }
                    }
                }
                LearningTab.MEMORY -> {
                    item { MemoryOverviewCard(pendingProposals) }
                    item { PromptPreviewCard(promptPreview = promptPreview, showMemoryOnly = true) }
                }
                LearningTab.PRIVACY -> {
                    item { PrivacySafetyCard(mode) }
                    item { PromptPreviewCard(promptPreview = promptPreview, showMemoryOnly = false) }
                }
            }
        }
    }
}

private fun String.toLearningTab(): LearningTab = when (lowercase()) {
    "review", "learning" -> LearningTab.REVIEW
    "memory", "context" -> LearningTab.MEMORY
    "skills" -> LearningTab.OVERVIEW
    "privacy", "safety" -> LearningTab.PRIVACY
    else -> LearningTab.OVERVIEW
}

@Composable
private fun LearningIntroCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Amaya Brain", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Persona controls how Amaya talks. Memory and context recall control what she can remember after a chat.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FlowExplanationCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Real flow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowStep("1", "Chat", "Amaya helps normally and does not interrupt you with memory decisions.")
            FlowStep("2", "Reflect", "After the chat, she extracts possible memory and context-recall notes.")
            FlowStep("3", "Save or skip", "Safe structured memory is saved automatically; noisy or uncertain candidates are ignored.")
            FlowStep("4", "Recall", "Saved memory affects future chats only when relevant.")
        }
    }
}

@Composable
private fun FlowStep(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(number, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewHeaderCard(pendingCount: Int, approvedCount: Int, onApplyApproved: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Review queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "$pendingCount needs review · $approvedCount approved but not applied",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApplyApproved, enabled = approvedCount > 0) { Text("Apply approved") }
                OutlinedButton(onClick = onApplyApproved, enabled = approvedCount > 0) { Text("Apply all") }
            }
        }
    }
}

@Composable
private fun MemoryOverviewCard(proposals: List<PendingProposal>) {
    val memorySuggestions = proposals.count {
        it.type == PendingProposalType.USER_PROFILE || it.type == PendingProposalType.WORKSPACE_FACT
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Memory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "This is what Amaya may remember across chats. User preferences and project facts are separated from persona.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("$memorySuggestions memory suggestion(s) waiting. New safe memory is saved automatically.", style = MaterialTheme.typography.bodySmall)
        }
    }
}


@Composable
private fun PrivacySafetyCard(mode: SelfImprovementMode) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Privacy & safety", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Current mode: ${modeLabel(mode)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Amaya should never save passwords, API keys, tokens, OTPs, cookies, payment data, or temporary guesses. Reminder requests should go to Reminders, not Memory.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun EmptyStateCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ApplyResultsCard(results: List<ProposalApplyResult>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last apply result", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            results.forEach { result ->
                Text(
                    "${if (result.success) "Applied" else "Failed"} → ${result.target}: ${result.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PromptPreviewCard(promptPreview: PromptPreviewState, showMemoryOnly: Boolean, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What affects future chats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Read-only preview. This is shown in friendly groups instead of raw internal file names.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PreviewSection("User profile", promptPreview.userProfile)
            PreviewSection("Project rules", promptPreview.agents)
        }
    }
}

@Composable
private fun PreviewSection(title: String, content: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            content.ifBlank { "Nothing saved yet." }.take(900),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
