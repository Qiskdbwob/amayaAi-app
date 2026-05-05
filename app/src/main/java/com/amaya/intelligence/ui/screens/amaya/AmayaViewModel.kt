package com.amaya.intelligence.ui.screens.amaya

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amaya.intelligence.data.repository.BrainSettings
import com.amaya.intelligence.data.repository.BrainSettingsRepository
import com.amaya.intelligence.data.repository.ContextRecallSettings
import com.amaya.intelligence.data.repository.MemoryBehaviorSettings
import com.amaya.intelligence.data.repository.MemoryRecord
import com.amaya.intelligence.data.repository.MemoryRepository
import com.amaya.intelligence.data.repository.PendingProposalRepository
import com.amaya.intelligence.data.repository.ProposalApplyResult
import com.amaya.intelligence.data.repository.SkillBehaviorSettings
import com.amaya.intelligence.data.repository.SkillRepository
import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryProposal
import com.amaya.intelligence.domain.memory.MemoryScope
import com.amaya.intelligence.domain.memory.MemoryType
import com.amaya.intelligence.domain.memory.PendingProposal
import com.amaya.intelligence.domain.memory.PendingProposalType
import com.amaya.intelligence.domain.skills.SkillMetadata
import com.amaya.intelligence.domain.skills.SkillStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AmayaViewModel @Inject constructor(
    private val brainSettingsRepository: BrainSettingsRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryClassifier: com.amaya.intelligence.domain.memory.MemoryClassifier,
    private val skillRepository: SkillRepository,
    private val pendingProposalRepository: PendingProposalRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AmayaUiState())
    val uiState: StateFlow<AmayaUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val settings = brainSettingsRepository.getBrainSettings()
            val proposals = pendingProposalRepository.listPending(100)
            val skills = skillRepository.listSkills()
            val userRecords = memoryRepository.listMemoryRecords(type = com.amaya.intelligence.domain.memory.MemoryType.USER_PROFILE, limit = 100)
            val importantRecords = memoryRepository.listMemoryRecords(type = com.amaya.intelligence.domain.memory.MemoryType.LONG_TERM_MEMORY, limit = 100)
            val projectRecords = memoryRepository.listMemoryRecords(type = com.amaya.intelligence.domain.memory.MemoryType.WORKSPACE_FACT, limit = 100)
            val dailyRecords = memoryRepository.listMemoryRecords(type = com.amaya.intelligence.domain.memory.MemoryType.DAILY_LOG, limit = 100)
            val rawUserMemory = memoryRepository.readUserProfile()
            val rawImportantMemory = memoryRepository.readHotMemory()
            val rawProjectMemory = memoryRepository.readWorkspaceFacts()
            val rawDailyNotes = memoryRepository.readRecentDailyNotes()
            _uiState.value = _uiState.value.copy(
                settings = settings,
                pendingProposals = proposals,
                skills = skills,
                userMemoryPreview = rawUserMemory.toFriendlyPreview(),
                importantMemoryPreview = rawImportantMemory.toFriendlyPreview(),
                projectMemoryPreview = rawProjectMemory.toFriendlyPreview(),
                dailyNotesPreview = rawDailyNotes.toFriendlyPreview(),
                userMemoryRaw = rawUserMemory,
                importantMemoryRaw = rawImportantMemory,
                projectMemoryRaw = rawProjectMemory,
                dailyNotesRaw = rawDailyNotes,
                userMemoryRecords = userRecords,
                importantMemoryRecords = importantRecords,
                projectMemoryRecords = projectRecords,
                dailyMemoryRecords = dailyRecords,
                userMemoryCount = userRecords.size,
                importantMemoryCount = importantRecords.size,
                projectMemoryCount = projectRecords.size,
                isLoading = false
            )
        }
    }

    fun updateMemorySettings(transform: (MemoryBehaviorSettings) -> MemoryBehaviorSettings) {
        viewModelScope.launch {
            val current = brainSettingsRepository.getBrainSettings()
            brainSettingsRepository.setMemorySettings(transform(current.memory))
            refresh()
        }
    }

    fun updateSkillSettings(transform: (SkillBehaviorSettings) -> SkillBehaviorSettings) {
        viewModelScope.launch {
            val current = brainSettingsRepository.getBrainSettings()
            brainSettingsRepository.setSkillSettings(transform(current.skills))
            refresh()
        }
    }

    fun setSkillEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            val skill = skillRepository.getSkill(name)
            if (skill == null) {
                _uiState.value = _uiState.value.copy(message = "Skill not found")
                return@launch
            }
            val result = skillRepository.updateSkillMetadata(
                skill.metadata.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
            )
            _uiState.value = _uiState.value.copy(message = result.fold({ if (enabled) "Skill enabled" else "Skill disabled" }, { "Update failed: ${it.message}" }))
            refresh()
        }
    }

    fun updateContextSettings(transform: (ContextRecallSettings) -> ContextRecallSettings) {
        viewModelScope.launch {
            val current = brainSettingsRepository.getBrainSettings()
            brainSettingsRepository.setContextRecallSettings(transform(current.context))
            refresh()
        }
    }

    fun saveSuggestion(id: String) {
        viewModelScope.launch {
            val approve = pendingProposalRepository.approve(id)
            val result = if (approve.isSuccess) pendingProposalRepository.applyApprovedWithResult(id) else Result.failure(approve.exceptionOrNull() ?: IllegalStateException("Approve failed"))
            _uiState.value = _uiState.value.copy(
                lastApplyResults = result.fold({ listOf(it) }, { listOf(ProposalApplyResult(id, false, "Suggestion", it.message ?: "Save failed")) }),
                message = result.fold({ "Suggestion saved" }, { "Save failed: ${it.message}" })
            )
            refresh()
        }
    }

    fun addMemory(area: MemoryArea, content: String) {
        viewModelScope.launch {
            val clean = content.trim()
            if (clean.isBlank()) {
                _uiState.value = _uiState.value.copy(message = "Memory content is empty")
                return@launch
            }
            val proposal = memoryClassifier.classify(
                content = clean,
                requestedType = area.memoryType(),
                requestedAction = MemoryAction.ADD,
                requestedScope = area.memoryScope(),
                reason = "The user manually added this memory in Settings.",
                confidence = 0.95,
                importance = if (area == MemoryArea.USER) 0.75 else 0.65
            )
            val result = memoryRepository.applyProposal(proposal)
            _uiState.value = _uiState.value.copy(message = result.fold({ "Saved" }, { "Save failed: ${it.message}" }))
            refresh()
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            val result = memoryRepository.removeMemoryById(id)
            _uiState.value = _uiState.value.copy(message = result.fold({ "Deleted" }, { "Delete failed: ${it.message}" }))
            refresh()
        }
    }

    fun dismissSuggestion(id: String) {
        viewModelScope.launch {
            val result = pendingProposalRepository.reject(id)
            _uiState.value = _uiState.value.copy(message = result.fold({ "Suggestion dismissed" }, { "Dismiss failed: ${it.message}" }))
            refresh()
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

data class AmayaUiState(
    val settings: BrainSettings = BrainSettings(),
    val pendingProposals: List<PendingProposal> = emptyList(),
    val skills: List<SkillMetadata> = emptyList(),
    val userMemoryPreview: String = "",
    val importantMemoryPreview: String = "",
    val projectMemoryPreview: String = "",
    val dailyNotesPreview: String = "",
    val userMemoryRaw: String = "",
    val importantMemoryRaw: String = "",
    val projectMemoryRaw: String = "",
    val dailyNotesRaw: String = "",
    val userMemoryRecords: List<MemoryRecord> = emptyList(),
    val importantMemoryRecords: List<MemoryRecord> = emptyList(),
    val projectMemoryRecords: List<MemoryRecord> = emptyList(),
    val dailyMemoryRecords: List<MemoryRecord> = emptyList(),
    val userMemoryCount: Int = 0,
    val importantMemoryCount: Int = 0,
    val projectMemoryCount: Int = 0,
    val lastApplyResults: List<ProposalApplyResult> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null
) {
    val activeSkills: Int get() = skills.count { it.status == SkillStatus.ACTIVE }
    val enabledSkills: Int get() = skills.count { it.status == SkillStatus.ACTIVE && it.enabled }
    val reviewSkills: Int get() = skills.count { it.needsReview }
    val memorySuggestions: Int get() = pendingProposals.count { it.type.isMemoryType() }
    val skillSuggestions: Int get() = pendingProposals.count { it.type.isSkillType() }
    val totalMemoryCount: Int get() = userMemoryCount + importantMemoryCount + projectMemoryCount
}

fun PendingProposalType.isMemoryType(): Boolean = this == PendingProposalType.USER_PROFILE ||
    this == PendingProposalType.LONG_TERM_MEMORY || this == PendingProposalType.WORKSPACE_FACT || this == PendingProposalType.DAILY_LOG

fun PendingProposalType.isSkillType(): Boolean = this == PendingProposalType.SKILL_CREATE ||
    this == PendingProposalType.SKILL_PATCH || this == PendingProposalType.SKILL_UPDATE

private fun MemoryArea.memoryType(): MemoryType = when (this) {
    MemoryArea.USER -> MemoryType.USER_PROFILE
    MemoryArea.IMPORTANT -> MemoryType.LONG_TERM_MEMORY
    MemoryArea.PROJECT -> MemoryType.WORKSPACE_FACT
    MemoryArea.DAILY -> MemoryType.DAILY_LOG
}

private fun MemoryArea.memoryScope(): MemoryScope = when (this) {
    MemoryArea.USER -> MemoryScope.USER
    MemoryArea.PROJECT -> MemoryScope.WORKSPACE
    MemoryArea.DAILY -> MemoryScope.SESSION
    MemoryArea.IMPORTANT -> MemoryScope.GLOBAL
}

private fun String.toFriendlyPreview(): String = lines()
    .map { it.trim() }
    .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(">") && !it.startsWith("*(") }
    .take(6)
    .joinToString("\n")
    .ifBlank { "No saved items" }
