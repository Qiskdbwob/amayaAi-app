import {
  BridgePermissionDecision,
  BridgeRiskLevel
} from '../protocol/bridge-risk';

export interface RiskContext {
  sessionConnected: boolean;
  agentControlEnabled: boolean;
  emergencyStopped: boolean;
}

/**
 * Phase 4 MVP risk engine.
 *
 *  LOW     : allowed when session connected.
 *  MEDIUM  : allowed when agent control enabled.
 *  HIGH    : require approval (Phase 4 rejects by default — see approval-policy).
 *  BLOCKED : always reject.
 */
export function decide(
  risk: BridgeRiskLevel,
  ctx: RiskContext
): BridgePermissionDecision {
  if (ctx.emergencyStopped) return BridgePermissionDecision.DENY;
  switch (risk) {
    case BridgeRiskLevel.BLOCKED:
      return BridgePermissionDecision.BLOCK;
    case BridgeRiskLevel.HIGH:
      return BridgePermissionDecision.REQUIRE_APPROVAL;
    case BridgeRiskLevel.MEDIUM:
      if (!ctx.sessionConnected) return BridgePermissionDecision.DENY;
      if (!ctx.agentControlEnabled) return BridgePermissionDecision.DENY;
      return BridgePermissionDecision.ALLOW;
    case BridgeRiskLevel.LOW:
    default:
      return ctx.sessionConnected
        ? BridgePermissionDecision.ALLOW
        : BridgePermissionDecision.DENY;
  }
}
