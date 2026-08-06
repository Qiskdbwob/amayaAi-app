package com.amaya.intelligence.domain.bridge

import java.util.UUID

/**
 * Lifecycle status of a single [ApprovalRequest].
 */
enum class ApprovalStatus(val wireName: String) {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    CANCELLED("cancelled");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): ApprovalStatus? =
            if (value == null) null else byWireName[value.lowercase()]
    }
}

/**
 * Request emitted by either peer asking the user to approve a specific [BridgeToolCall].
 * The receiving side renders approval UI and responds with [ApprovalDecision].
 */
data class ApprovalRequest(
    /** Unique id for this approval request. */
    val id: String = UUID.randomUUID().toString(),
    /** Session this request belongs to. */
    val sessionId: String,
    /** Correlates with [BridgeToolCall.id] that triggered the request. */
    val toolCallId: String,
    /** Tool name being approved, duplicated from the originating call. */
    val tool: String,
    /** Risk classification of the underlying tool call. */
    val risk: BridgeRiskLevel,
    /** Short human-readable rationale. Safe to show to the user as-is. */
    val reason: String,
    /**
     * Redacted, user-facing preview of the underlying [BridgeToolCall.args]. Bridge
     * implementations must strip secrets, truncate binary payloads, and paraphrase
     * any path/URL values that could leak private data.
     */
    val argsPreview: Map<String, Any?> = emptyMap(),
    /** Epoch millis when the request was emitted. */
    val requestedAt: Long = System.currentTimeMillis(),
    /** Epoch millis when the request auto-expires. `null` means no expiry. */
    val expiresAt: Long? = null,
    /** Current lifecycle status. Starts at [ApprovalStatus.PENDING]. */
    val status: ApprovalStatus = ApprovalStatus.PENDING
)

/**
 * User decision on an [ApprovalRequest]. Emitted by Android (for bridge-initiated
 * approvals) or by the Windows Bridge (for Android-initiated approvals).
 */
data class ApprovalDecision(
    /** Correlates with [ApprovalRequest.id]. */
    val requestId: String,
    /** Session this decision belongs to. */
    val sessionId: String,
    /** Correlates with [BridgeToolCall.id] from the originating request. */
    val toolCallId: String,
    /** `true` when approved, `false` when rejected. */
    val approved: Boolean,
    /** Epoch millis when the decision was recorded. */
    val decidedAt: Long = System.currentTimeMillis(),
    /** Optional human-readable explanation provided by the approver. */
    val reason: String? = null
)
