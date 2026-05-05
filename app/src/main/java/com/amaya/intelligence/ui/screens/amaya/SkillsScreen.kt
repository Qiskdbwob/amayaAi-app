package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.amaya.intelligence.domain.skills.SkillStatus

@Composable
fun SkillsScreen(
    state: AmayaUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onToggleSkillEnabled: (String, Boolean) -> Unit
) {
    AmayaScaffold("Skills", snackbarHostState, onNavigateBack) {
        if (state.skillSuggestions > 0) {
            AmayaSection("Review") {
                AmayaStatusRow("Skill candidates", "${state.skillSuggestions}", "Waiting for approval")
            }
        }

        val visibleSkills = state.skills.filter { it.status != SkillStatus.ARCHIVED }
        if (visibleSkills.isNotEmpty()) {
            AmayaSection("Saved Skills") {
                visibleSkills.forEachIndexed { index, skill ->
                    val status = when {
                        skill.needsReview -> "Needs review"
                        skill.status == SkillStatus.STALE -> "Stale"
                        skill.enabled -> "Available when relevant"
                        else -> "Disabled"
                    }
                    val usage = "Used ${skill.usageCount} time${if (skill.usageCount == 1) "" else "s"}"
                    AmayaSwitchRow(
                        title = skill.name,
                        subtitle = "${skill.description.ifBlank { "Reusable workflow" }} · $usage · $status",
                        checked = skill.enabled,
                        onCheckedChange = { enabled -> onToggleSkillEnabled(skill.name, enabled) },
                        enabled = skill.status != SkillStatus.ARCHIVED
                    )
                    if (index < visibleSkills.lastIndex) AmayaDivider()
                }
            }
        } else {
            AmayaSection("Saved Skills") {
                AmayaStatusRow("No saved skills", "0", "Create a reusable workflow from chat when needed")
            }
        }
    }
}
