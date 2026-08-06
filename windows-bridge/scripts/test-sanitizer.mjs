// Runs the sanitizer against a fixture and verifies secrets never leak.
// Kept as an .mjs script so `npm run test` can invoke node directly — no extra
// testing framework is pulled into the bridge package.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { sanitizeOpencodeConfig } from '../dist/agents/opencode/opencode-config-sanitizer.js';

const FIXTURE = JSON.stringify({
  model: 'sumopod/gpt-5',
  provider: {
    sumopod: {
      name: 'Sumopod',
      options: {
        apiKey: 'sk-VerySecretValue123',
        baseURL: 'https://ai.sumopod.com/v1'
      }
    }
  },
  mcp: {
    remote: {
      type: 'remote',
      headers: {
        CONTEXT7_API_KEY: 'ctx7sk-topsecret',
        'X-Other-Header': 'public'
      }
    }
  }
});

test('sanitizeOpencodeConfig redacts secrets', () => {
  const sanitized = sanitizeOpencodeConfig(FIXTURE);
  assert.ok(!sanitized.includes('sk-VerySecretValue123'), 'apiKey leaked');
  assert.ok(!sanitized.includes('ctx7sk-topsecret'), 'header secret leaked');
  assert.ok(sanitized.includes('https://ai.sumopod.com/v1'), 'baseURL was stripped');
  // Headers are redacted in bulk (any of them may carry a secret). The test only
  // checks that the structural key is preserved.
  assert.ok(sanitized.includes('X-Other-Header'), 'header key dropped');
});

test('sanitizeOpencodeConfig preserves shape for invalid input', () => {
  const passthrough = sanitizeOpencodeConfig('not json');
  assert.equal(passthrough, 'not json');
});

test('sanitizeOpencodeConfig handles empty config', () => {
  const sanitized = sanitizeOpencodeConfig('{}');
  assert.equal(sanitized.trim(), '{}');
});
