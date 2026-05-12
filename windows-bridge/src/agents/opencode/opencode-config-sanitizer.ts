/**
 * Redact secrets from an opencode config JSON before sending it to Android.
 *
 * Rules:
 *  - Every `options.apiKey`, `options.password`, `headers.*` value is replaced
 *    with '***' if it looks like a secret.
 *  - Provider-level `env` arrays stay, so the UI can show "has API key" without
 *    leaking it.
 *  - Plugins and MCP stanzas pass through unchanged (they don't usually carry
 *    secrets, but individual headers are redacted above).
 */
export function sanitizeOpencodeConfig(raw: string): string {
  const parsed = tryParse(raw);
  if (!parsed || typeof parsed !== 'object') return raw;
  const cleaned = deepSanitize(parsed);
  try {
    return JSON.stringify(cleaned, null, 2);
  } catch {
    return raw;
  }
}

const SECRET_KEY_PATTERN = /api[_-]?key|password|secret|token|auth|credential/i;

function deepSanitize(value: unknown): unknown {
  if (value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map(deepSanitize);
  const obj = value as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const [key, v] of Object.entries(obj)) {
    if (SECRET_KEY_PATTERN.test(key) && (typeof v === 'string' || typeof v === 'number')) {
      out[key] = redact(v);
    } else if (key === 'headers' && v && typeof v === 'object') {
      out[key] = redactHeaders(v as Record<string, unknown>);
    } else {
      out[key] = deepSanitize(v);
    }
  }
  return out;
}

function redactHeaders(headers: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(headers)) {
    if (typeof v === 'string' || typeof v === 'number') {
      out[k] = redact(v);
    } else {
      out[k] = deepSanitize(v);
    }
  }
  return out;
}

function redact(value: string | number): string {
  const str = String(value);
  if (!str) return '';
  if (str.length <= 4) return '***';
  return `***${str.slice(-4)}`;
}

function tryParse(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}
