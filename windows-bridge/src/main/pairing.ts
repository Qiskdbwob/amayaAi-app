import { randomBytes } from 'node:crypto';
import { networkInterfaces } from 'node:os';

export interface PairingConfig {
  host: string;
  port: number;
  token: string | null;
}

/**
 * Read pairing config from environment variables. Keeps Phase 4 dependency-free;
 * a richer config (QR, persisted device allowlist) lands in later phases.
 */
export function loadPairingConfig(): PairingConfig {
  const host = process.env.AMAYA_BRIDGE_HOST || '0.0.0.0';
  const port = Number(process.env.AMAYA_BRIDGE_PORT) || 17878;
  const token = process.env.AMAYA_BRIDGE_TOKEN?.trim() || null;
  return { host, port, token };
}

/** Generate a one-shot pairing token. Not persisted; intended for dev usage. */
export function generatePairingToken(): string {
  return randomBytes(24).toString('hex');
}

/**
 * Detect likely LAN IPv4 addresses that an Android device on the same network
 * could use to reach this machine. Filters out loopback and link-local.
 */
export function detectLanIps(): string[] {
  const ips: string[] = [];
  const ifaces = networkInterfaces();
  for (const [, addrs] of Object.entries(ifaces)) {
    if (!addrs) continue;
    for (const addr of addrs) {
      if (addr.family !== 'IPv4') continue;
      if (addr.internal) continue;
      if (addr.address.startsWith('169.254.')) continue;
      ips.push(addr.address);
    }
  }
  return ips;
}
