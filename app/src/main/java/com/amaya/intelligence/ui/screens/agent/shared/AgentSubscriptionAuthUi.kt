package com.amaya.intelligence.ui.screens.agent.shared

/**
 * Provider-neutral auth state for subscription-backed agents.
 * Provider-specific systems map into this model outside the shared agent editor
 * so this UI does not depend on concrete provider auth classes.
 */
data class AgentSubscriptionAuthUi(
    val providerId: String,
    val providerName: String,
    val authenticated: Boolean,
    val accountLabel: String? = null,
    val step: SubscriptionAuthStep = SubscriptionAuthStep.Methods,
    val onBrowserSignIn: (() -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
    val onSignOut: (() -> Unit)? = null
)

sealed interface SubscriptionAuthStep {
    data object Methods : SubscriptionAuthStep
    data class Error(val message: String) : SubscriptionAuthStep
    data class Waiting(val label: String) : SubscriptionAuthStep
}

internal fun subscriptionAuthStepKey(authUi: AgentSubscriptionAuthUi?): String = when (authUi?.step) {
    is SubscriptionAuthStep.Waiting -> "auth_wait"
    else -> "auth_methods"
}

internal fun subscriptionAuthTitle(authUi: AgentSubscriptionAuthUi?): String = when (authUi?.step) {
    is SubscriptionAuthStep.Waiting -> "Waiting for ${authUi.providerName}"
    else -> "${authUi?.providerName ?: "Subscription"} Sign In"
}
