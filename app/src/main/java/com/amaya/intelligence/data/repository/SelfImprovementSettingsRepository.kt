package com.amaya.intelligence.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.selfImprovementStore by preferencesDataStore(name = "self_improvement_settings")

/** Legacy compatibility only. New logic uses domain-owned BrainSettings below. */
enum class SelfImprovementMode {
    OFF,
    DAILY_LOG_ONLY,
    SAFE_AUTO,
    ASK_APPROVAL
}

data class MemoryBehaviorSettings(
    val useSavedMemory: Boolean = true,
    val suggestNewMemories: Boolean = true,
    val autoSaveSafeMemory: Boolean = true,
    val dailyNotesEnabled: Boolean = true
)

data class SkillBehaviorSettings(
    val useSavedSkills: Boolean = true
)

data class ContextRecallSettings(
    val pastChatRecallEnabled: Boolean = true,
    val workspaceContextEnabled: Boolean = true,
    val relevantMemoryEnabled: Boolean = true,
    val maxRecallItems: Int = 5
)

data class BrainSettings(
    val memory: MemoryBehaviorSettings = MemoryBehaviorSettings(),
    val skills: SkillBehaviorSettings = SkillBehaviorSettings(),
    val context: ContextRecallSettings = ContextRecallSettings()
)

interface SelfImprovementSettingsRepository {
    suspend fun getMode(): SelfImprovementMode
    suspend fun setMode(mode: SelfImprovementMode)
}

interface BrainSettingsRepository {
    suspend fun getBrainSettings(): BrainSettings
    suspend fun setMemorySettings(settings: MemoryBehaviorSettings)
    suspend fun setSkillSettings(settings: SkillBehaviorSettings)
    suspend fun setContextRecallSettings(settings: ContextRecallSettings)
}

@Singleton
class DataStoreSelfImprovementSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SelfImprovementSettingsRepository, BrainSettingsRepository {
    override suspend fun getMode(): SelfImprovementMode {
        val settings = getBrainSettings()
        return when {
            !settings.memory.suggestNewMemories && !settings.memory.dailyNotesEnabled -> SelfImprovementMode.OFF
            settings.memory.dailyNotesEnabled && !settings.memory.suggestNewMemories -> SelfImprovementMode.DAILY_LOG_ONLY
            settings.memory.suggestNewMemories && !settings.memory.autoSaveSafeMemory -> SelfImprovementMode.ASK_APPROVAL
            else -> SelfImprovementMode.SAFE_AUTO
        }
    }

    override suspend fun setMode(mode: SelfImprovementMode) {
        val current = getBrainSettings()
        when (mode) {
            SelfImprovementMode.OFF -> {
                setMemorySettings(current.memory.copy(suggestNewMemories = false, autoSaveSafeMemory = false, dailyNotesEnabled = false))
            }
            SelfImprovementMode.DAILY_LOG_ONLY -> {
                setMemorySettings(current.memory.copy(suggestNewMemories = false, autoSaveSafeMemory = false, dailyNotesEnabled = true))
            }
            SelfImprovementMode.ASK_APPROVAL -> {
                setMemorySettings(current.memory.copy(suggestNewMemories = true, autoSaveSafeMemory = false, dailyNotesEnabled = true))
            }
            SelfImprovementMode.SAFE_AUTO -> {
                setMemorySettings(current.memory.copy(suggestNewMemories = true, autoSaveSafeMemory = true, dailyNotesEnabled = true))
            }
        }
        context.selfImprovementStore.edit { prefs -> prefs[KEY_LEGACY_MODE] = mode.name }
    }

    override suspend fun getBrainSettings(): BrainSettings {
        return context.selfImprovementStore.data.map { prefs ->
            BrainSettings(
                memory = MemoryBehaviorSettings(
                    useSavedMemory = prefs[KEY_MEMORY_USE] ?: true,
                    suggestNewMemories = prefs[KEY_MEMORY_SUGGEST] ?: true,
                    autoSaveSafeMemory = prefs[KEY_MEMORY_AUTO_SAFE] ?: true,
                    dailyNotesEnabled = prefs[KEY_DAILY_NOTES] ?: true
                ),
                skills = SkillBehaviorSettings(
                    useSavedSkills = prefs[KEY_SKILLS_USE] ?: true
                ),
                context = ContextRecallSettings(
                    pastChatRecallEnabled = prefs[KEY_CONTEXT_SESSIONS] ?: true,
                    workspaceContextEnabled = prefs[KEY_CONTEXT_WORKSPACE] ?: true,
                    relevantMemoryEnabled = prefs[KEY_CONTEXT_MEMORY] ?: true,
                    maxRecallItems = (prefs[KEY_CONTEXT_MAX_RECALL] ?: 5).coerceIn(1, 20)
                )
            )
        }.first()
    }

    override suspend fun setMemorySettings(settings: MemoryBehaviorSettings) {
        context.selfImprovementStore.edit { prefs ->
            prefs[KEY_MEMORY_USE] = settings.useSavedMemory
            prefs[KEY_MEMORY_SUGGEST] = settings.suggestNewMemories
            prefs[KEY_MEMORY_AUTO_SAFE] = settings.autoSaveSafeMemory
            prefs[KEY_DAILY_NOTES] = settings.dailyNotesEnabled
        }
    }

    override suspend fun setSkillSettings(settings: SkillBehaviorSettings) {
        context.selfImprovementStore.edit { prefs ->
            prefs[KEY_SKILLS_USE] = settings.useSavedSkills
        }
    }

    override suspend fun setContextRecallSettings(settings: ContextRecallSettings) {
        context.selfImprovementStore.edit { prefs ->
            prefs[KEY_CONTEXT_SESSIONS] = settings.pastChatRecallEnabled
            prefs[KEY_CONTEXT_WORKSPACE] = settings.workspaceContextEnabled
            prefs[KEY_CONTEXT_MEMORY] = settings.relevantMemoryEnabled
            prefs[KEY_CONTEXT_MAX_RECALL] = settings.maxRecallItems.coerceIn(1, 20)
        }
    }

    companion object {
        private val KEY_LEGACY_MODE = stringPreferencesKey("self_improvement_mode")
        private val KEY_MEMORY_USE = booleanPreferencesKey("memory_use_saved")
        private val KEY_MEMORY_SUGGEST = booleanPreferencesKey("memory_suggest_new")
        private val KEY_MEMORY_AUTO_SAFE = booleanPreferencesKey("memory_auto_save_safe")
        private val KEY_DAILY_NOTES = booleanPreferencesKey("memory_daily_notes")
        private val KEY_SKILLS_USE = booleanPreferencesKey("skills_use_saved")
        private val KEY_CONTEXT_SESSIONS = booleanPreferencesKey("context_past_chats")
        private val KEY_CONTEXT_WORKSPACE = booleanPreferencesKey("context_workspace")
        private val KEY_CONTEXT_MEMORY = booleanPreferencesKey("context_memory")
        private val KEY_CONTEXT_MAX_RECALL = intPreferencesKey("context_max_recall")
    }
}
