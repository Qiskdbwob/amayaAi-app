package com.amaya.intelligence.domain.memory

import javax.inject.Inject
import javax.inject.Singleton

data class SafetyCheckResult(
    val safe: Boolean,
    val redactedContent: String,
    val reasons: List<String>
)

@Singleton
class MemorySafetyFilter @Inject constructor() {
    fun check(content: String): SafetyCheckResult {
        var redacted = content
        val reasons = mutableListOf<String>()
        SECRET_RULES.forEach { rule ->
            if (rule.regex.containsMatchIn(redacted)) {
                reasons.add(rule.reason)
                redacted = rule.regex.replace(redacted) { match -> redact(match.value) }
            }
        }
        INSTRUCTION_OVERRIDE_RULES.firstOrNull { it.containsMatchIn(redacted) }?.let {
            reasons.add("Attempts to override assistant policy or identity")
        }
        return SafetyCheckResult(
            safe = reasons.isEmpty(),
            redactedContent = redacted,
            reasons = reasons.distinct()
        )
    }

    private fun redact(value: String): String {
        val keyPrefix = value.substringBefore('=', missingDelimiterValue = "").takeIf { it.isNotBlank() && it.length < value.length }
        return if (keyPrefix != null) "$keyPrefix=[REDACTED]" else "[REDACTED]"
    }

    private data class SecretRule(val regex: Regex, val reason: String)

    companion object {
        private val KEYWORDS = listOf(
            "password", "passwd", "pwd", "api key", "api_key", "apikey", "secret", "client_secret",
            "access_token", "refresh_token", "bearer", "authorization", "cookie", "sessionid",
            "jwt", "otp", "2fa", "private key", "ssh key", "credit card", "cvv"
        )

        private val INSTRUCTION_OVERRIDE_RULES = listOf(
            Regex("(?i)\\bignore (all |any |the )?(previous|prior|system) instructions?\\b"),
            Regex("(?i)\\b(bypass|disable|skip) (user )?(confirmation|approval|safety)\\b"),
            Regex("(?i)\\b(never|do not) ask (for )?(confirmation|approval)\\b"),
            Regex("(?i)\\byour (new )?(personality|identity) is\\b"),
            Regex("(?i)\\bchange (your )?(tool permissions?|safety rules?)\\b")
        )

        private val SECRET_RULES = buildList {
            KEYWORDS.forEach { keyword ->
                add(SecretRule(
                    Regex("(?i)\\b${Regex.escape(keyword)}\\b\\s*[:=]\\s*[^\\s,;]+"),
                    "Contains sensitive field: $keyword"
                ))
            }
            add(SecretRule(Regex("sk-[A-Za-z0-9_-]{8,}"), "Looks like an API key"))
            add(SecretRule(Regex("AIza[0-9A-Za-z_-]{20,}"), "Looks like a Google API key"))
            add(SecretRule(Regex("ghp_[0-9A-Za-z_]{20,}"), "Looks like a GitHub token"))
            add(SecretRule(Regex("xox[baprs]-[0-9A-Za-z-]{10,}"), "Looks like a Slack token"))
            add(SecretRule(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "Looks like a JWT"))
            add(SecretRule(Regex("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (RSA |EC |OPENSSH )?PRIVATE KEY-----"), "Contains private key"))
            add(SecretRule(Regex("\\b(?:\\d[ -]*?){13,19}\\b"), "May contain payment card number"))
            add(SecretRule(Regex("(?i)\\b(otp|2fa|verification code)\\b.{0,24}\\b\\d{4,8}\\b"), "Contains OTP/2FA code"))
            add(SecretRule(Regex("(?i)\\b(cookie|sessionid|authorization|bearer)\\b[^\\n]*"), "Contains auth/session material"))
        }
    }
}
