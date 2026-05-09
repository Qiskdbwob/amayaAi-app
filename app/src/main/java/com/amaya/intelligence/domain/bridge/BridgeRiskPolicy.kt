package com.amaya.intelligence.domain.bridge

/**
 * Risk classification attached to each [BridgeToolCall]. Used by the Windows Bridge and
 * Android-side planner to decide whether a tool may run automatically, requires explicit
 * user approval, or must be blocked outright.
 */
enum class BridgeRiskLevel(val wireName: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    BLOCKED("blocked");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgeRiskLevel? =
            if (value == null) null else byWireName[value.lowercase()]
    }
}

/**
 * Permission decision for a tool call under a given session mode. Produced by a future
 * policy engine; Phase 1 only defines the vocabulary.
 */
enum class BridgePermissionDecision(val wireName: String) {
    ALLOW("allow"),
    REQUIRE_APPROVAL("require_approval"),
    DENY("deny"),
    BLOCK("block");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgePermissionDecision? =
            if (value == null) null else byWireName[value.lowercase()]
    }
}

/**
 * Thin helper surface so early callers can make consistent decisions without duplicating
 * the logic. The real policy engine will live in a dedicated file in a later phase.
 */
object BridgeRiskPolicy {

    /**
     * Default mapping from a [BridgeRiskLevel] to a [BridgePermissionDecision] under the
     * stated rules:
     *
     *  - LOW: allowed when the session is active
     *  - MEDIUM: allowed when Agent Control mode is active
     *  - HIGH: require approval
     *  - BLOCKED: never execute
     */
    fun defaultDecision(
        risk: BridgeRiskLevel,
        sessionActive: Boolean,
        agentControlActive: Boolean
    ): BridgePermissionDecision = when (risk) {
        BridgeRiskLevel.BLOCKED -> BridgePermissionDecision.BLOCK
        BridgeRiskLevel.HIGH -> BridgePermissionDecision.REQUIRE_APPROVAL
        BridgeRiskLevel.MEDIUM -> when {
            !sessionActive -> BridgePermissionDecision.DENY
            agentControlActive -> BridgePermissionDecision.ALLOW
            else -> BridgePermissionDecision.REQUIRE_APPROVAL
        }
        BridgeRiskLevel.LOW -> if (sessionActive) {
            BridgePermissionDecision.ALLOW
        } else {
            BridgePermissionDecision.DENY
        }
    }

    /** Convenience: true when the decision requires user approval before execution. */
    fun BridgePermissionDecision.shouldRequireApproval(): Boolean =
        this == BridgePermissionDecision.REQUIRE_APPROVAL

    /** Convenience: true when the decision must never execute. */
    fun BridgePermissionDecision.isBlocked(): Boolean =
        this == BridgePermissionDecision.BLOCK || this == BridgePermissionDecision.DENY
}
