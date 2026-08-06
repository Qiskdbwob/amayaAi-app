package com.amaya.intelligence.domain.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the active AI session mode (Local vs Remote).
 */
@Singleton
class IntelligenceSessionManager @Inject constructor() {
    
    enum class SessionMode(val ideId: String) {
        LOCAL("local"),
        WINDOWS_BRIDGE("windows_bridge"),
        OPENCODE("opencode"),
        ANTIGRAVITY("antigravity"),
        CURSOR("cursor"),       // Future
        WINDSURF("windsurf");   // Future
        
        fun isRemote(): Boolean = this != LOCAL
    }

    private val _currentMode = MutableStateFlow(SessionMode.LOCAL)
    val currentMode: StateFlow<SessionMode> = _currentMode.asStateFlow()

    fun setMode(mode: SessionMode) {
        _currentMode.value = mode
    }

    fun isRemote(): Boolean = _currentMode.value.isRemote()
}

/**
 * Extension function to get display name for SessionMode.
 * Defined at usage site to avoid domain layer dependencies.
 */
fun IntelligenceSessionManager.SessionMode.displayName(): String = when (this) {
    IntelligenceSessionManager.SessionMode.LOCAL -> "Amaya"
    IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE -> "Windows Bridge"
    IntelligenceSessionManager.SessionMode.OPENCODE -> "Opencode"
    else -> com.amaya.intelligence.impl.ide.IdeProviderFactory.getIdeInfo(ideId)?.displayName ?: name
}
