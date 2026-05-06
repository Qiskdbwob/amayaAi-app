package com.amaya.intelligence.ui.screens.agent.local

import com.amaya.intelligence.data.remote.api.GitHubCopilotAuthState
import com.amaya.intelligence.ui.screens.agent.shared.AgentSubscriptionAuthUi
import com.amaya.intelligence.ui.screens.agent.shared.SubscriptionAuthStep

internal const val GitHubCopilotSubscriptionProviderId = "github_copilot"

internal fun githubCopilotSubscriptionAuthUi(
    authState: GitHubCopilotAuthState?,
    authenticated: Boolean,
    accountLabel: String?,
    onBrowserSignIn: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onSignOut: (() -> Unit)?
): AgentSubscriptionAuthUi = AgentSubscriptionAuthUi(
    providerId = GitHubCopilotSubscriptionProviderId,
    providerName = "GitHub Copilot",
    authenticated = authenticated,
    accountLabel = accountLabel,
    step = authState.toSubscriptionAuthStep(),
    onBrowserSignIn = onBrowserSignIn,
    onCancel = onCancel,
    onSignOut = onSignOut
)

private fun GitHubCopilotAuthState?.toSubscriptionAuthStep(): SubscriptionAuthStep = when (this) {
    is GitHubCopilotAuthState.Starting -> SubscriptionAuthStep.Waiting("Opening browser…")
    is GitHubCopilotAuthState.WaitingForBrowser -> SubscriptionAuthStep.Waiting("Waiting for browser…")
    is GitHubCopilotAuthState.ExchangingToken -> SubscriptionAuthStep.Waiting("Finishing sign in…")
    is GitHubCopilotAuthState.Error -> SubscriptionAuthStep.Error(message)
    else -> SubscriptionAuthStep.Methods
}
