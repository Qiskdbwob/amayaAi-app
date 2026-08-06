package com.amaya.intelligence.domain.models

enum class AssistantMode {
    CHAT,
    PROJECT,
    AGENT;

    companion object {
        fun forWorkspace(workspacePath: String?): AssistantMode =
            if (workspacePath.isNullOrBlank()) CHAT else PROJECT
    }
}
