import { BridgeRiskLevel } from './bridge-risk';

export type ApprovalStatus =
  | 'pending'
  | 'approved'
  | 'rejected'
  | 'expired'
  | 'cancelled';

export interface ApprovalRequest {
  id: string;
  sessionId: string;
  toolCallId: string;
  tool: string;
  risk: BridgeRiskLevel;
  reason: string;
  argsPreview: Record<string, unknown>;
  requestedAt: number;
  expiresAt?: number | null;
  status: ApprovalStatus;
}

export interface ApprovalDecision {
  requestId: string;
  sessionId: string;
  toolCallId: string;
  approved: boolean;
  decidedAt: number;
  reason?: string | null;
}
