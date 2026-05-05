package com.amaya.intelligence.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.personaStore by preferencesDataStore(name = "persona_settings")

/**
 * Simple-only persona settings. This is intentionally limited to how Amaya speaks
 * and behaves. User facts, memory, skills, daily logs, and workspace context are
 * owned by their own repositories and are composed into the prompt elsewhere.
 */
data class SimplePersona(
    val tone: String = "",
    val characteristic: String = "",
    val customInstruction: String = "",
    val nickname: String = "",
    val aboutYou: String = ""
)

data class PersonaProfile(
    val assistantName: String = "Amaya",
    val tone: String = "",
    val traits: String = "",
    val customInstruction: String = ""
)

@Singleton
class PersonaRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_TONE = stringPreferencesKey("persona_tone")
        private val KEY_CHARACTERISTIC = stringPreferencesKey("persona_characteristic")
        private val KEY_INSTRUCTION = stringPreferencesKey("persona_instruction")
        private val KEY_NICKNAME = stringPreferencesKey("persona_nickname")
        private val KEY_ABOUT = stringPreferencesKey("persona_about")

        val DEFAULT_PERSONA_PROMPT = """
            You are Amaya, a personal AI companion and productivity assistant running on the user's Android device.
            Be warm, direct, practical, honest, and concise by default.
            Speak naturally like a capable friend, not a corporate assistant.
            Match the user's language and tone when appropriate.
            Ask clarifying questions when needed and do not pretend to know things you do not know.
        """.trimIndent()
    }

    suspend fun getSimplePersona(): SimplePersona {
        return context.personaStore.data.map { prefs ->
            SimplePersona(
                tone = prefs[KEY_TONE] ?: "",
                characteristic = prefs[KEY_CHARACTERISTIC] ?: "",
                customInstruction = prefs[KEY_INSTRUCTION] ?: "",
                nickname = prefs[KEY_NICKNAME] ?: "",
                aboutYou = prefs[KEY_ABOUT] ?: ""
            )
        }.first()
    }

    suspend fun saveSimplePersona(persona: SimplePersona) {
        context.personaStore.edit { prefs ->
            prefs[KEY_TONE] = persona.tone
            prefs[KEY_CHARACTERISTIC] = persona.characteristic
            prefs[KEY_INSTRUCTION] = persona.customInstruction
            // Kept for migration/UI compatibility. These user-facing facts should be
            // moved to Memory > About You by the new Memory screen.
            prefs[KEY_NICKNAME] = persona.nickname
            prefs[KEY_ABOUT] = persona.aboutYou
        }
    }

    suspend fun loadPersonaProfile(): PersonaProfile {
        val persona = getSimplePersona()
        return PersonaProfile(
            tone = persona.tone,
            traits = persona.characteristic,
            customInstruction = persona.customInstruction
        )
    }

    suspend fun extractLegacyMemoryFacts(): List<String> {
        val persona = getSimplePersona()
        return buildList {
            if (persona.nickname.isNotBlank()) add("User prefers to be called ${persona.nickname.trim()}.")
            if (persona.aboutYou.isNotBlank()) add("User profile: ${persona.aboutYou.trim()}")
        }
    }

    suspend fun clearLegacyMemoryFacts() {
        val persona = getSimplePersona()
        if (persona.nickname.isBlank() && persona.aboutYou.isBlank()) return
        saveSimplePersona(persona.copy(nickname = "", aboutYou = ""))
    }

    suspend fun buildPersonaPrompt(): String {
        val profile = loadPersonaProfile()
        return buildString {
            appendLine(DEFAULT_PERSONA_PROMPT)
            if (profile.tone.isNotBlank()) appendLine("Tone: ${profile.tone}")
            if (profile.traits.isNotBlank()) appendLine("Traits: ${profile.traits}")
            if (profile.customInstruction.isNotBlank()) {
                appendLine("Custom instruction:")
                appendLine(profile.customInstruction)
            }
        }.trim()
    }
}
