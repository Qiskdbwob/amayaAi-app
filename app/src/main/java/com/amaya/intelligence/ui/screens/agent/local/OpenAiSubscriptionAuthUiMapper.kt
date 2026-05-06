package com.amaya.intelligence.ui.screens.agent.local

import com.amaya.intelligence.data.remote.api.CodexAuthState
import com.amaya.intelligence.ui.screens.agent.shared.AgentSubscriptionAuthUi
import com.amaya.intelligence.ui.screens.agent.shared.SubscriptionAuthStep

internal const val OpenAiSubscriptionProviderId = "openai_codex_bridge"

internal fun openAiSubscriptionAuthUi(
    authState: CodexAuthState?,
    authenticated: Boolean,
    accountLabel: String?,
    onBrowserSignIn: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onSignOut: (() -> Unit)?
): AgentSubscriptionAuthUi = AgentSubscriptionAuthUi(
    providerId = OpenAiSubscriptionProviderId,
    providerName = "OpenAI",
    authenticated = authenticated,
    accountLabel = accountLabel,
    step = authState.toSubscriptionAuthStep(),
    onBrowserSignIn = onBrowserSignIn,
    onCancel = onCancel,
    onSignOut = onSignOut
)

private fun CodexAuthState?.toSubscriptionAuthStep(): SubscriptionAuthStep = when (this) {
    is CodexAuthState.Starting -> SubscriptionAuthStep.Waiting("Opening browser…")
    is CodexAuthState.WaitingForBrowser -> SubscriptionAuthStep.Waiting("Waiting for browser…")
    is CodexAuthState.ExchangingToken -> SubscriptionAuthStep.Waiting("Finishing sign in…")
    is CodexAuthState.Error -> SubscriptionAuthStep.Error(message)
    else -> SubscriptionAuthStep.Methods
}
