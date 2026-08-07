package com.amaya.intelligence.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.terminalSettingsStore by preferencesDataStore(name = "terminal_settings")

data class TerminalSettings(
    val trustedCommands: List<String> = DEFAULT_TRUSTED_COMMANDS,
    val declinedCommands: List<String> = emptyList(),
    /**
     * Auto-approve read-only / non-destructive shell commands so the user is not prompted
     * repeatedly for safe commands such as `ls`, `cat`, `grep`, or `git status`.
     */
    val autoApproveNonDestructive: Boolean = true
) {
    companion object {
        val DEFAULT_TRUSTED_COMMANDS = listOf(
            "pwd", "date", "uptime", "which *", "ls *", "cat *", "head *", "tail *", "grep *", "diff *"
        )
    }
}

interface TerminalSettingsRepository {
    suspend fun getSettings(): TerminalSettings
    suspend fun setSettings(settings: TerminalSettings)
}

@Singleton
class DataStoreTerminalSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : TerminalSettingsRepository {
    override suspend fun getSettings(): TerminalSettings = context.terminalSettingsStore.data.map { prefs ->
        TerminalSettings(
            trustedCommands = prefs[KEY_TRUSTED]?.sorted() ?: TerminalSettings.DEFAULT_TRUSTED_COMMANDS,
            declinedCommands = prefs[KEY_DECLINED]?.sorted().orEmpty(),
            autoApproveNonDestructive = prefs[KEY_AUTO_APPROVE_NON_DESTRUCTIVE] ?: true
        )
    }.first()

    override suspend fun setSettings(settings: TerminalSettings) {
        context.terminalSettingsStore.edit { prefs ->
            prefs[KEY_TRUSTED] = normalizePatterns(settings.trustedCommands).toSet()
            prefs[KEY_DECLINED] = normalizePatterns(settings.declinedCommands).toSet()
            prefs[KEY_AUTO_APPROVE_NON_DESTRUCTIVE] = settings.autoApproveNonDestructive
        }
    }

    private fun normalizePatterns(patterns: List<String>): List<String> = patterns
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter(String::isNotBlank)
        .distinct()

    private companion object {
        val KEY_TRUSTED = stringSetPreferencesKey("trusted_commands")
        val KEY_DECLINED = stringSetPreferencesKey("declined_commands")
        val KEY_AUTO_APPROVE_NON_DESTRUCTIVE = booleanPreferencesKey("auto_approve_non_destructive")
    }
}

internal fun commandMatchesWildcard(command: String, pattern: String): Boolean {
    val normalizedCommand = command.trim().replace(Regex("\\s+"), " ")
    val normalizedPattern = pattern.trim().replace(Regex("\\s+"), " ")
    if (normalizedPattern.isBlank()) return false
    val regex = buildString {
        append('^')
        normalizedPattern.split('*').forEachIndexed { index, part ->
            if (index > 0) append(".*")
            append(Regex.escape(part))
        }
        append('$')
    }
    return Regex(regex).matches(normalizedCommand)
}
