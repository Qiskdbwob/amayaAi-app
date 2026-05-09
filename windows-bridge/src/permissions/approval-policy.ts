// Phase 6 approval policy.
//
// The approval infrastructure now lives in `approval-manager.ts`. This file keeps
// the legacy `APPROVAL_ENABLED` flag as a safe fallback when no security policy
// is loaded. Real runtime toggles flow through `SecurityPolicy.approval`.

export const APPROVAL_ENABLED: boolean = true;
export const APPROVAL_TIMEOUT_MS = 30_000;
