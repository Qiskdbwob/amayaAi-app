export const BridgeRiskLevel = {
  LOW: 'low',
  MEDIUM: 'medium',
  HIGH: 'high',
  BLOCKED: 'blocked'
} as const;
export type BridgeRiskLevel = (typeof BridgeRiskLevel)[keyof typeof BridgeRiskLevel];

export const BridgePermissionDecision = {
  ALLOW: 'allow',
  REQUIRE_APPROVAL: 'require_approval',
  DENY: 'deny',
  BLOCK: 'block'
} as const;
export type BridgePermissionDecision =
  (typeof BridgePermissionDecision)[keyof typeof BridgePermissionDecision];

export function riskFromWire(value: string | undefined | null): BridgeRiskLevel {
  switch ((value ?? '').toLowerCase()) {
    case 'low':
      return BridgeRiskLevel.LOW;
    case 'medium':
      return BridgeRiskLevel.MEDIUM;
    case 'high':
      return BridgeRiskLevel.HIGH;
    case 'blocked':
      return BridgeRiskLevel.BLOCKED;
    default:
      return BridgeRiskLevel.MEDIUM;
  }
}
