package com.amaya.intelligence.impl.ide.opencode

import com.amaya.intelligence.domain.ai.IdeProvider
import com.amaya.intelligence.domain.models.IdeCapabilities
import com.amaya.intelligence.domain.models.IdeInfo

/**
 * Opencode CLI provider metadata.
 *
 * Opencode doesn't have its own transport — it rides inside the Windows Bridge
 * WebSocket session using envelope types under `agent.*`. The connection UI is
 * still gated behind pairing with a Windows computer; [requiresConnection]
 * stays true so the Remote Session screen surfaces it.
 */
object OpencodeProvider : IdeProvider {

    override val ideId: String = "opencode"

    override val info: IdeInfo = IdeInfo(
        id = ideId,
        displayName = "Opencode",
        description = "Run the opencode CLI agent through your Windows bridge.",
        capabilities = IdeCapabilities(
            supportsStreaming = true,
            supportsThinking = true,
            supportsWorkspaces = true,
            supportsProjectFiles = false,
            supportsModels = true,
            supportsConversations = true,
            supportsToolExecution = true,
            requiresConnection = true,
            supportsMcp = true
        )
    )

    override val isEnabled: Boolean = true
}
