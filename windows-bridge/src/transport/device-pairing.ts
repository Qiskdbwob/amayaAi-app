import type { IncomingMessage } from 'node:http';

export interface PairingRequest {
  deviceId: string | null;
  token: string | null;
  tokenSource: 'header' | 'query' | 'none';
}

export interface PairingOptions {
  allowQueryTokenFallback: boolean;
}

/**
 * Extract pairing hints from the handshake.
 *
 *  - `X-Amaya-Device-Id` header (preferred).
 *  - `Authorization: Bearer <token>` header (preferred).
 *  - `?deviceId=&token=` query string (fallback).
 *
 * Query-string token reading is gated by [[PairingOptions.allowQueryTokenFallback]]
 * so production deployments can require the header-only path.
 */
export function readPairingFromRequest(
  req: IncomingMessage,
  options: PairingOptions = { allowQueryTokenFallback: true }
): PairingRequest {
  const deviceHeader = req.headers['x-amaya-device-id'];
  const authHeader = req.headers['authorization'];

  const deviceFromHeader = Array.isArray(deviceHeader)
    ? deviceHeader[0]
    : deviceHeader;

  let token: string | null = null;
  let tokenSource: PairingRequest['tokenSource'] = 'none';
  if (typeof authHeader === 'string' && authHeader.toLowerCase().startsWith('bearer ')) {
    const t = authHeader.slice(7).trim();
    if (t.length) {
      token = t;
      tokenSource = 'header';
    }
  }

  let deviceFromUrl: string | null = null;
  let tokenFromUrl: string | null = null;
  try {
    const url = new URL(req.url ?? '/', 'http://localhost');
    deviceFromUrl = url.searchParams.get('deviceId');
    tokenFromUrl = url.searchParams.get('token');
  } catch {
    // ignore — url parsing is best-effort
  }

  if (token === null && options.allowQueryTokenFallback && tokenFromUrl) {
    token = tokenFromUrl;
    tokenSource = 'query';
  }

  return {
    deviceId: (deviceFromHeader ?? deviceFromUrl) || null,
    token,
    tokenSource
  };
}
