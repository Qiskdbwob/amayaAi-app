import { BridgeMessageType, isKnownMessageType } from './bridge-message-type';

export interface BridgeEnvelope {
  id: string;
  type: BridgeMessageType;
  sessionId: string | null;
  deviceId: string;
  seq: number;
  timestamp: number;
  payload: Record<string, unknown>;
  metadata?: Record<string, string>;
}

export type DecodeResult =
  | { ok: true; envelope: BridgeEnvelope }
  | { ok: false; reason: string };

/** Tolerant JSON → BridgeEnvelope decoder. Never throws. */
export function decodeEnvelope(raw: string): DecodeResult {
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch (err) {
    return { ok: false, reason: `malformed JSON: ${(err as Error).message}` };
  }
  if (typeof json !== 'object' || json === null) {
    return { ok: false, reason: 'invalid envelope: expected object' };
  }
  const obj = json as Record<string, unknown>;

  const type = obj['type'];
  if (typeof type !== 'string' || !type.length) {
    return { ok: false, reason: 'missing required field: type' };
  }
  if (!isKnownMessageType(type)) {
    return { ok: false, reason: `unknown message type: ${type}` };
  }

  const id = obj['id'];
  if (typeof id !== 'string' || !id.length) {
    return { ok: false, reason: 'missing required field: id' };
  }

  const deviceId = obj['deviceId'];
  if (typeof deviceId !== 'string' || !deviceId.length) {
    return { ok: false, reason: 'missing required field: deviceId' };
  }

  const seq = toFiniteNumber(obj['seq']);
  if (seq === undefined) {
    return { ok: false, reason: 'missing or invalid field: seq' };
  }

  const timestamp = toFiniteNumber(obj['timestamp']);
  if (timestamp === undefined) {
    return { ok: false, reason: 'missing or invalid field: timestamp' };
  }

  const payloadRaw = obj['payload'];
  let payload: Record<string, unknown> = {};
  if (payloadRaw !== undefined && payloadRaw !== null) {
    if (typeof payloadRaw !== 'object' || Array.isArray(payloadRaw)) {
      return { ok: false, reason: 'invalid payload: expected object' };
    }
    payload = payloadRaw as Record<string, unknown>;
  }

  const sessionIdRaw = obj['sessionId'];
  const sessionId =
    typeof sessionIdRaw === 'string' && sessionIdRaw.length > 0
      ? sessionIdRaw
      : null;

  const metadataRaw = obj['metadata'];
  let metadata: Record<string, string> | undefined;
  if (metadataRaw && typeof metadataRaw === 'object' && !Array.isArray(metadataRaw)) {
    metadata = {};
    for (const [k, v] of Object.entries(metadataRaw as Record<string, unknown>)) {
      metadata[k] = v == null ? '' : String(v);
    }
  }

  return {
    ok: true,
    envelope: { id, type, sessionId, deviceId, seq, timestamp, payload, metadata }
  };
}

export function encodeEnvelope(env: BridgeEnvelope): string {
  return JSON.stringify(env);
}

function toFiniteNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string') {
    const n = Number(value);
    return Number.isFinite(n) ? n : undefined;
  }
  return undefined;
}
