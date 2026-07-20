package com.amaya.intelligence.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.selfImprovementStore by preferencesDataStore(name = "self_improvement_settings")

data class MemoryBehaviorSettings(val useSavedMemory: Boolean = true)
data class SkillBehaviorSettings(val useSavedSkills: Boolean = true)
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

interface BrainSettingsRepository {
    suspend fun getBrainSettings(): BrainSettings
    suspend fun setMemorySettings(settings: MemoryBehaviorSettings)
    suspend fun setSkillSettings(settings: SkillBehaviorSettings)
    suspend fun setContextRecallSettings(settings: ContextRecallSettings)
}

@Singleton
class DataStoreBrainSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : BrainSettingsRepository {
    override suspend fun getBrainSettings(): BrainSettings = context.selfImprovementStore.data.map { prefs ->
        BrainSettings(
            memory = MemoryBehaviorSettings(useSavedMemory = true),
            skills = SkillBehaviorSettings(useSavedSkills = prefs[KEY_SKILLS_USE] ?: true),
            context = ContextRecallSettings(
                pastChatRecallEnabled = true,
                workspaceContextEnabled = true,
                relevantMemoryEnabled = true,
                maxRecallItems = 5
            )
        )
    }.first()

    override suspend fun setMemorySettings(settings: MemoryBehaviorSettings) = Unit

    override suspend fun setSkillSettings(settings: SkillBehaviorSettings) {
        context.selfImprovementStore.edit { it[KEY_SKILLS_USE] = settings.useSavedSkills }
    }

    override suspend fun setContextRecallSettings(settings: ContextRecallSettings) = Unit

    private companion object {
        val KEY_SKILLS_USE = booleanPreferencesKey("skills_use_saved")
    }
}
