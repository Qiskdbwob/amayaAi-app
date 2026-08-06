#!/usr/bin/env node
/**
 * CLI harness that connects to a running Amaya Windows Bridge over WebSocket,
 * drives the opencode agent envelopes, and prints the responses. Useful for
 * reproducing mobile-side issues straight from a terminal.
 *
 * Usage:
 *   node scripts/opencode-smoke-cli.mjs \
 *     --host 192.168.0.71 --port 17878 \
 *     --token <optional pairing token> \
 *     --device smoke-cli \
 *     --prompt "Sebutkan tiga hal tentang project ini"
 *
 * Available flags:
 *   --host          Bridge host (default 127.0.0.1)
 *   --port          Bridge port (default 17878)
 *   --token         Pairing token if the bridge enforces it
 *   --device        Device id advertised to the bridge (default smoke-cli)
 *   --prompt        Prompt text to send after a session is created
 *   --session       Existing opencode session id (skip session create)
 *   --mode          opencode agent mode: build | plan (default build)
 *   --provider      provider id for the prompt (optional)
 *   --model         model id for the prompt (optional)
 *   --skip-prompt   Don't send any prompt — just list providers/models/sessions
 *   --wait-ms       How long to wait for streaming before exiting (default 60_000)
 */

import WebSocket from 'ws';
import { randomUUID } from 'node:crypto';
import { parseArgs } from 'node:util';
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

const { values } = parseArgs({
  options: {
    host: { type: 'string', default: '127.0.0.1' },
    port: { type: 'string', default: '17878' },
    token: { type: 'string' },
    device: { type: 'string', default: 'smoke-cli' },
    prompt: { type: 'string' },
    session: { type: 'string' },
    mode: { type: 'string', default: 'build' },
    provider: { type: 'string' },
    model: { type: 'string' },
    'skip-prompt': { type: 'boolean', default: false },
    'wait-ms': { type: 'string', default: '60000' },
    verbose: { type: 'boolean', default: false },
    'raw-log': { type: 'string' }
  },
  strict: true
});

const VERBOSE = values.verbose;
const RAW_LOG = values['raw-log'];
if (RAW_LOG) {
  mkdirSync(dirname(RAW_LOG), { recursive: true });
  writeFileSync(RAW_LOG, '');
}

const HOST = values.host;
const PORT = Number(values.port);
const WAIT_MS = Number(values['wait-ms']);
const DEVICE = values.device;
const TOKEN = values.token;
const SKIP_PROMPT = values['skip-prompt'];

const AGENT_TYPES = {
  TOOL_CALL: 'tool.call',
  TOOL_RESULT: 'tool.result',
  TOOL_ERROR: 'tool.error',
  AGENT_RUNTIME_STATUS_REQUEST: 'agent.runtime.status.request',
  AGENT_RUNTIME_STATUS: 'agent.runtime.status',
  AGENT_CONFIG_REQUEST: 'agent.config.request',
  AGENT_CONFIG: 'agent.config',
  AGENT_PROVIDER_LIST_REQUEST: 'agent.provider.list.request',
  AGENT_PROVIDER_LIST: 'agent.provider.list',
  AGENT_MODEL_LIST_REQUEST: 'agent.model.list.request',
  AGENT_MODEL_LIST: 'agent.model.list',
  AGENT_MCP_LIST_REQUEST: 'agent.mcp.list.request',
  AGENT_MCP_LIST: 'agent.mcp.list',
  AGENT_SESSION_LIST_REQUEST: 'agent.session.list.request',
  AGENT_SESSION_LIST: 'agent.session.list',
  AGENT_SESSION_CREATE: 'agent.session.create',
  AGENT_SESSION_CREATED: 'agent.session.created',
  AGENT_SESSION_PROMPT: 'agent.session.prompt',
  AGENT_SESSION_ABORT: 'agent.session.abort',
  AGENT_SESSION_MESSAGES_REQUEST: 'agent.session.messages.request',
  AGENT_SESSION_MESSAGES: 'agent.session.messages',
  AGENT_EVENT: 'agent.event',
  DEVICE_PAIRED: 'device.paired',
  SESSION_CREATED: 'session.created',
  ERROR: 'error'
};

const wsUrl = buildWsUrl();
console.log(`[cli] connecting ${wsUrl}`);

const socket = new WebSocket(wsUrl, {
  headers: {
    'X-Amaya-Device-Id': DEVICE
  }
});

let seq = 0;
let sessionId = null;
let runtimeReady = false;
let streamingActive = false;
let assistantText = '';
let opencodeSessionId = values.session ?? null;
const deadline = setTimeout(() => {
  console.log('\n[cli] wait budget expired, closing');
  socket.close();
}, WAIT_MS);

socket.on('open', () => {
  console.log('[cli] ws open');
});

socket.on('message', (raw) => {
  let env;
  try {
    env = JSON.parse(raw.toString('utf8'));
  } catch (err) {
    console.warn('[cli] failed to parse envelope', err);
    return;
  }
  if (RAW_LOG) {
    try {
      appendFileSync(RAW_LOG, raw.toString('utf8') + '\n');
    } catch (err) {
      console.warn('[cli] raw log write failed', err);
    }
  }
  handleEnvelope(env);
});

socket.on('close', (code, reason) => {
  clearTimeout(deadline);
  console.log(`[cli] ws closed code=${code} reason=${reason?.toString() || '-'}`);
});

socket.on('error', (err) => {
  console.error('[cli] ws error', err.message);
});

function handleEnvelope(env) {
  const type = env.type;
  const payload = env.payload ?? {};
  switch (type) {
    case AGENT_TYPES.DEVICE_PAIRED:
      console.log('[cli] paired as', payload.deviceId ?? DEVICE);
      break;
    case AGENT_TYPES.SESSION_CREATED:
      sessionId = payload.sessionId;
      console.log('[cli] bridge session', sessionId);
      // First thing: check runtime + providers + models
      send(AGENT_TYPES.AGENT_RUNTIME_STATUS_REQUEST, { runtimeId: 'opencode' });
      send(AGENT_TYPES.AGENT_PROVIDER_LIST_REQUEST, { runtimeId: 'opencode' });
      send(AGENT_TYPES.AGENT_MODEL_LIST_REQUEST, { runtimeId: 'opencode' });
      send(AGENT_TYPES.AGENT_MCP_LIST_REQUEST, { runtimeId: 'opencode' });
      send(AGENT_TYPES.AGENT_SESSION_LIST_REQUEST, { runtimeId: 'opencode' });
      break;
    case AGENT_TYPES.AGENT_RUNTIME_STATUS: {
      console.log('[cli] runtime', {
        status: payload.status,
        baseUrl: payload.baseUrl,
        version: payload.version,
        lastError: payload.lastError
      });
      if (payload.status === 'ready' && !runtimeReady) {
        runtimeReady = true;
        maybeStartPromptFlow();
      }
      break;
    }
    case AGENT_TYPES.AGENT_PROVIDER_LIST:
      console.log('[cli] providers:');
      for (const p of payload.providers ?? []) {
        console.log(`  - ${p.providerId} (${p.displayName}) models=${p.modelCount ?? p.models?.length ?? 0}`);
      }
      break;
    case AGENT_TYPES.AGENT_MODEL_LIST:
      console.log('[cli] models:');
      for (const m of payload.models ?? []) {
        console.log(`  - ${m.providerId}/${m.modelId} ctx=${m.contextWindowTokens ?? '-'}`);
      }
      if (payload.defaultModel) {
        console.log('  default =', payload.defaultModel);
      }
      break;
    case AGENT_TYPES.AGENT_MCP_LIST:
      console.log('[cli] mcp:');
      for (const s of payload.servers ?? []) {
        console.log(`  - ${s.name} type=${s.type} connected=${s.connected}`);
      }
      break;
    case AGENT_TYPES.AGENT_SESSION_LIST:
      console.log('[cli] opencode sessions:');
      for (const s of payload.sessions ?? []) {
        console.log(`  - ${s.sessionId} "${s.title ?? ''}"`);
      }
      if (opencodeSessionId && payload.sessions?.some((s) => s.sessionId === opencodeSessionId)) {
        console.log('[cli] loading history for', opencodeSessionId);
        send(AGENT_TYPES.AGENT_SESSION_MESSAGES_REQUEST, {
          runtimeId: 'opencode',
          sessionId: opencodeSessionId
        });
      }
      break;
    case AGENT_TYPES.AGENT_SESSION_MESSAGES: {
      console.log(`[cli] history for ${payload.sessionId}: ${payload.messages?.length ?? 0} entries`);
      for (const m of payload.messages ?? []) {
        const info = m.info ?? m;
        const role = info.role ?? 'unknown';
        const text = (m.parts ?? [])
          .filter((p) => p.type === 'text')
          .map((p) => p.text)
          .join('');
        if (text) {
          console.log(`  [${role}] ${text.slice(0, 200)}${text.length > 200 ? '…' : ''}`);
        }
      }
      break;
    }
    case AGENT_TYPES.AGENT_SESSION_CREATED: {
      const created = payload.session;
      console.log('[cli] opencode session created', created?.sessionId);
      opencodeSessionId = created?.sessionId ?? opencodeSessionId;
      sendPrompt();
      break;
    }
    case AGENT_TYPES.AGENT_EVENT: {
      handleAgentEvent(payload);
      break;
    }
    case AGENT_TYPES.ERROR:
      console.error('[cli] error envelope', payload);
      break;
    default:
      // Ignore noise like screen.frame etc.
      break;
  }
}

function handleAgentEvent(payload) {
  const kind = payload.kind;
  const data = payload.data ?? {};
  if (VERBOSE) {
    console.log(`\n[evt:${kind}]`, JSON.stringify(data).slice(0, 400));
  }
  switch (kind) {
    case 'message.part.text': {
      const text = data.text ?? '';
      const timeEnd = data.timeEnd ?? null;
      assistantText = text;
      process.stdout.write('\r[assistant] ' + text.slice(-120).replace(/\n/g, ' '));
      if (timeEnd != null) {
        streamingActive = false;
        console.log('\n[cli] text part complete');
      }
      break;
    }
    case 'message.part.tool': {
      const tool = data.tool ?? '?';
      const state = (data.state && data.state.status) ?? 'running';
      console.log(`\n[tool] ${tool} → ${state}`);
      break;
    }
    case 'permission.asked': {
      console.log('\n[cli] permission asked, auto-allowing', data.id);
      send('agent.permission.reply', {
        runtimeId: 'opencode',
        sessionId: payload.sessionId,
        permissionId: data.id ?? data.permissionId,
        reply: 'once'
      });
      break;
    }
    case 'session.idle':
      streamingActive = false;
      console.log('\n[cli] session idle');
      scheduleClose();
      break;
    case 'session.error':
      console.log('\n[cli] session error', data);
      scheduleClose();
      break;
    default:
      // Uncomment for verbose:
      // console.log('[cli] event', kind, data);
      break;
  }
}

function maybeStartPromptFlow() {
  if (SKIP_PROMPT) {
    scheduleClose(2_000);
    return;
  }
  if (!values.prompt) {
    console.log('[cli] no --prompt provided, skipping prompt flow');
    scheduleClose(2_000);
    return;
  }
  if (opencodeSessionId) {
    sendPrompt();
  } else {
    send(AGENT_TYPES.AGENT_SESSION_CREATE, {
      runtimeId: 'opencode',
      title: values.prompt.slice(0, 40),
      agent: values.mode
    });
  }
}

function sendPrompt() {
  if (!values.prompt || !opencodeSessionId) return;
  streamingActive = true;
  console.log('[cli] prompt →', values.prompt);
  const payload = {
    runtimeId: 'opencode',
    sessionId: opencodeSessionId,
    parts: [{ type: 'text', text: values.prompt }],
    agent: values.mode
  };
  if (values.provider && values.model) {
    payload.model = { providerId: values.provider, modelId: values.model };
  }
  send(AGENT_TYPES.AGENT_SESSION_PROMPT, payload);
}

function scheduleClose(ms = 1500) {
  setTimeout(() => {
    if (streamingActive) return;
    socket.close();
  }, ms);
}

function send(type, payload) {
  seq += 1;
  const envelope = {
    id: randomUUID(),
    type,
    sessionId: sessionId,
    deviceId: DEVICE,
    seq,
    timestamp: Date.now(),
    payload
  };
  socket.send(JSON.stringify(envelope));
}

function buildWsUrl() {
  const u = new URL(`ws://${HOST}:${PORT}`);
  u.searchParams.set('deviceId', DEVICE);
  if (TOKEN) u.searchParams.set('token', TOKEN);
  return u.toString();
}
