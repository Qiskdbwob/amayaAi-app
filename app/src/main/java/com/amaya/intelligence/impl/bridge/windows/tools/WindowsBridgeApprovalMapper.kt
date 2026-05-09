package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.domain.bridge.ApprovalRequest
import com.amaya.intelligence.tools.ConfirmationRequest
import com.amaya.intelligence.domain.security.RiskLevel as SecurityRiskLevel
import com.amaya.intelligence.domain.bridge.BridgeRiskLevel

/**
 * Maps an incoming bridge [ApprovalRequest] to the existing [ConfirmationRequest]
 * shape used by `ToolExecutor`.
 *
 * Phase 3 only produces the shape — it does not force a UI dialog. Hooking the
 * confirmation into the chat / tool-card approval flow is tagged as TODO for Phase 6
 * because wiring it end-to-end would require touching `MessageHandler` and the
 * tool-card components, which is out of scope here.
 */
internal object WindowsBridgeApprovalMapper {

    fun toConfirmationRequest(request: ApprovalRequest): ConfirmationRequest {
        val details = buildString {
            append("Windows Bridge is asking for approval.\n")
            append("Tool: ").append(request.tool).append('\n')
            if (request.reason.isNotBlank()) append("Reason: ").append(request.reason).append('\n')
            append("Risk: ").append(request.risk.wireName).append('\n')
            if (request.argsPreview.isNotEmpty()) {
                append("Args preview:\n")
                for ((k, v) in request.argsPreview) {
                    append("  - ").append(k).append(": ").append(v).append('\n')
                }
            }
        }.trimEnd()

        return ConfirmationRequest(
            toolName = request.tool,
            reason = request.reason.ifBlank { "Windows Bridge approval required" },
            details = details,
            riskLevel = mapRisk(request.risk),
            toolCallId = request.toolCallId
        )
    }

    private fun mapRisk(risk: BridgeRiskLevel): SecurityRiskLevel = when (risk) {
        BridgeRiskLevel.LOW -> SecurityRiskLevel.LOW
        BridgeRiskLevel.MEDIUM -> SecurityRiskLevel.MEDIUM
        BridgeRiskLevel.HIGH -> SecurityRiskLevel.HIGH
        BridgeRiskLevel.BLOCKED -> SecurityRiskLevel.HIGH
    }
}
