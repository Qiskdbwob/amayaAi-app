package com.amaya.intelligence.impl.local.browser

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafetyGuard @Inject constructor() {
    private val sensitiveTerms = listOf(
        "password", "passwd", "passcode", "otp", "one-time", "2fa", "mfa",
        "verification", "email", "username", "login", "card", "credit", "cc-number",
        "cvc", "cvv", "expiry", "payment", "billing", "address", "phone", "ssn",
        "nik", "ktp", "passport", "private", "secret", "token"
    )

    fun isSensitiveField(element: BrowserElementSummary?): Boolean {
        if (element == null) return false
        if (element.isSensitive) return true
        val haystack = listOf(
            element.selector,
            element.tag,
            element.type,
            element.role,
            element.label,
            element.placeholder,
            element.name,
            element.id,
            element.text
        ).joinToString(" ").lowercase()
        return sensitiveTerms.any { it in haystack }
    }

    fun buildPrompt(toolName: String, element: BrowserElementSummary?): BrowserSafetyPrompt {
        val label = element?.let { item ->
            item.label.ifBlank { item.placeholder }.ifBlank { item.name }.ifBlank { item.selector }
        }
        return BrowserSafetyPrompt(
            reason = "Sensitive input detected. Amaya will not read or fill login, OTP, payment, or private data without permission.",
            selector = element?.selector,
            fieldLabel = label,
            toolName = toolName
        )
    }
}

fun org.json.JSONObject.toElementSummary(): BrowserElementSummary = BrowserElementSummary(
    selector = optString("selector"),
    tag = optString("tag"),
    text = optString("text"),
    label = optString("label"),
    type = optString("type"),
    role = optString("role"),
    href = optString("href"),
    src = optString("src"),
    placeholder = optString("placeholder"),
    name = optString("name"),
    id = optString("id"),
    isVisible = optBoolean("visible", true),
    isSensitive = optBoolean("sensitive", false)
)
