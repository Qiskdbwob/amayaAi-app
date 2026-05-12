import { EventEmitter } from 'node:events';
import type { IncomingMessage } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import {
  decodeEnvelope,
  encodeEnvelope,
  type BridgeEnvelope
} from '../protocol/bridge-envelope';
import { BridgeMessageType } from '../protocol/bridge-message-type';
import { riskFromWire } from '../protocol/bridge-risk';
import { BridgePermissionDecision } from '../protocol/bridge-risk';
import type { BridgeToolCall } from '../protocol/bridge-tool';
import { decide } from '../permissions/risk-engine';
import { findTool, enabledTools } from '../tools/tool-registry';
import { ToolInvocationError } from '../tools/tool-result';
import type { SecurityPolicy } from '../permissions/security-policy';
import { evaluateAppPolicy } from '../permissions/app-allowlist';
import { ApprovalManager, type ApprovalOutcome } from '../permissions/approval-manager';
import { AuditLog } from '../audit/audit-log';
import { readPairingFromRequest } from './device-pairing';
import { SessionManager, type SessionSnapshot } from './session-manager';
import { logger } from '../shared/logger';
import { newId } from '../shared/ids';
import { nowMs } from '../shared/time';
import type { NativeHelperClient } from '../native/native-helper-client';
import type { TrustedDeviceStore } from '../permissions/trusted-device-store';
import type { PairingTokenStore } from '../permissions/pairing-token-store';
import type { AgentRouter } from '../agents/agent-router';
import type {
  AgentEventPayload,
  AgentMcpEntry,
  AgentModelEntry,
  AgentModelRef,
  AgentPermissionReplyPayload,
  AgentProviderEntry,
  AgentQuestionReplyPayload,
  AgentRuntimeInfo,
  AgentRuntimeStartPayload,
  AgentSessionCreatePayload,
  AgentSessionDeletePayload,
  AgentSessionPromptPayload,
  AgentSessionSummary
} from '../protocol/bridge-agent';

const SCOPE = 'ws';
const BRIDGE_DEVICE_ID = 'windows_bridge';

// Tools that require an active foreground window check via the app allowlist.
// Any tool that physically moves the cursor or sends input to the OS must be
// listed here so the policy guard runs before execution.
const INPUT_TOOLS = new Set<string>([
  'mouse.click',
  'mouse.move',
  'mouse.scroll',
  'mouse.drag',
  'keyboard.type',
  'keyboard.hotkey',
  'window.focus',
  'window.close',
  'app.open',
  'ui.click_element'
]);

export interface ServerOptions {
  host: string;
  port: number;
  authToken?: string | null;
}

export interface ServerEvents {
  snapshot: (snap: SessionSnapshot) => void;
}

export class WindowsBridgeWebSocketServer extends EventEmitter {
  private server: WebSocketServer | null = null;
  private activeSocket: WebSocket | null = null;
  private readonly approvals: ApprovalManager;

  constructor(
    private readonly options: ServerOptions,
    private readonly sessions: SessionManager,
    private readonly audit: AuditLog = new AuditLog(),
    private readonly policy: SecurityPolicy,
    private readonly helper: NativeHelperClient | null = null,
    private readonly trustedDevices: TrustedDeviceStore | null = null,
    private readonly pairingTokens: PairingTokenStore | null = null,
    private readonly agentRouter: AgentRouter | null = null
  ) {
    super();
    this.approvals = new ApprovalManager(policy.approval.timeoutMs);
    this.sessions.on('snapshot', (snap) => this.emit('snapshot', snap));
    this.approvals.on('requested', (request) =>
      this.sendApprovalRequest(request)
    );
    this.agentRouter?.on('agent_event', (event: AgentEventPayload) =>
      this.sendAgentEvent(event)
    );
    this.agentRouter?.on('status_changed', (info: AgentRuntimeInfo) =>
      this.sendAgentRuntimeStatus(info)
    );
    this.agentRouter?.on('pty_event', (event: Record<string, unknown>) =>
      this.sendPtyEvent(event)
    );
  }

  start(): void {
    if (this.server) return;
    this.server = new WebSocketServer({
      host: this.options.host,
      port: this.options.port
    });
    this.server.on('listening', () => {
      logger.info(
        SCOPE,
        `listening host=${this.options.host} port=${this.options.port}`
      );
    });
    this.server.on('connection', (socket, req) =>
      this.onConnection(socket, req).catch((err) =>
        logger.error(SCOPE, 'connection handler threw', (err as Error).message)
      )
    );
    this.server.on('error', (err) => {
      logger.error(SCOPE, 'server error', err.message);
    });
  }

  async stop(): Promise<void> {
    const server = this.server;
    this.server = null;
    this.approvals.cancelAll('bridge shutting down');
    if (!server) return;
    for (const client of server.clients) {
      try {
        client.close(1001, 'bridge shutting down');
      } catch {
        /* ignore */
      }
    }
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  /** Send an agent-control change to the active client, if any. */
  broadcastAgentControl(enabled: boolean, source: 'windows' | 'android' = 'windows'): void {
    const current = this.sessions.snapshot.agentControlEnabled;
    if (current === enabled) return;
    this.sessions.setAgentControl(enabled);
    this.send(
      BridgeMessageType.AGENT_STATUS,
      {
        status: 'agent_control_changed',
        agentControlEnabled: enabled,
        source: source === 'windows' ? 'windows_bridge' : 'android_agent'
      }
    );
  }

  /** Flip the emergency-stop flag and notify the client. */
  emergencyStop(): void {
    this.sessions.emergencyStop();
    this.approvals.cancelAll('emergency stop');
    this.send(BridgeMessageType.AGENT_PAUSED, {
      reason: 'emergency_stop'
    });
  }

  resume(): void {
    this.sessions.resume();
    this.send(BridgeMessageType.AGENT_RESUMED, { reason: 'resume' });
  }

  // ── Connection lifecycle ─────────────────────────────────────────────────

  private async onConnection(
    socket: WebSocket,
    req: IncomingMessage
  ): Promise<void> {
    const pairing = readPairingFromRequest(req, {
      allowQueryTokenFallback: this.policy.auth.allowQueryTokenFallback
    });
    if (this.options.authToken) {
      if (pairing.token !== this.options.authToken) {
        // Also check pairing token store as fallback
        const pairingValid = this.pairingTokens?.validate(pairing.token ?? '') ?? false;
        // Also allow trusted devices to reconnect without token
        const isTrusted = pairing.deviceId
          ? (this.trustedDevices?.isTrusted(pairing.deviceId) ?? false)
          : false;
        if (!pairingValid && !isTrusted) {
          logger.warn(SCOPE, `rejecting connection: token mismatch source=${pairing.tokenSource}`);
          socket.close(1008, 'invalid token');
          return;
        }
      }
    } else if (this.policy.auth.requireToken) {
      // No configured token — check pairing token store or trusted device
      const pairingValid = this.pairingTokens?.validate(pairing.token ?? '') ?? false;
      const isTrusted = pairing.deviceId
        ? (this.trustedDevices?.isTrusted(pairing.deviceId) ?? false)
        : false;
      if (!pairingValid && !isTrusted) {
        logger.warn(SCOPE, 'rejecting connection: token required but not valid and device not trusted');
        socket.close(1008, 'token required');
        return;
      }
    }

    let attach: { sessionId: string; reused: boolean };
    try {
      attach = this.sessions.attach(pairing.deviceId);
    } catch (err) {
      logger.warn(SCOPE, 'rejecting connection', (err as Error).message);
      socket.close(1013, (err as Error).message);
      return;
    }

    if (this.activeSocket && this.activeSocket !== socket) {
      try {
        this.activeSocket.close(1000, 'replaced by new connection');
      } catch {
        /* ignore */
      }
    }
    this.activeSocket = socket;

    const deviceId = pairing.deviceId ?? 'unknown_device';
    const sessionId = attach.sessionId;

    socket.on('message', (data) => {
      const text = data.toString('utf-8');
      this.handleIncoming(socket, sessionId, deviceId, text).catch((err) =>
        logger.error(SCOPE, 'handleIncoming threw', (err as Error).message)
      );
    });
    socket.on('close', (code, reasonBuf) => {
      const reason = reasonBuf?.toString('utf-8') || '';
      logger.info(
        SCOPE,
        `socket closed code=${code} reason=${reason || '-'} session=${sessionId}`
      );
      if (this.activeSocket === socket) this.activeSocket = null;
      this.sessions.disconnect(reason);
      this.approvals.cancelAll('session closed');
      this.audit.append({
        id: newId(),
        sessionId,
        eventType: 'session_closed',
        timestamp: nowMs(),
        actor: 'windows_bridge',
        argsPreview: { code, reason }
      });
    });
    socket.on('error', (err) => {
      logger.warn(SCOPE, 'socket error', err.message);
    });

    // Initial handshake envelopes.
    // Trust the device on successful connection.
    this.trustedDevices?.trust(deviceId);

    this.sendDirect(
      socket,
      BridgeMessageType.DEVICE_PAIRED,
      sessionId,
      deviceId,
      {
        deviceId,
        reused: attach.reused,
        tokenSource: pairing.tokenSource
      }
    );
    this.sendDirect(
      socket,
      BridgeMessageType.SESSION_CREATED,
      sessionId,
      deviceId,
      {
        sessionId,
        capabilities: [
          'screenCapture',
          'windowCapture',
          'windowControl',
          'mouseControl',
          'mouseHover',
          'mousePressRelease',
          'keyboardControl',
          'keyboardHold',
          'uiHitTest',
          'clipboardWrite',
          'diagnostics'
        ],
        tools: enabledTools().map((spec) => ({
          name: spec.name,
          description: spec.description,
          risk: spec.risk,
          requiresApproval: spec.requiresApproval
        }))
      }
    );
  }

  private async handleIncoming(
    socket: WebSocket,
    sessionId: string,
    deviceId: string,
    raw: string
  ): Promise<void> {
    const decoded = decodeEnvelope(raw);
    if (!decoded.ok) {
      logger.warn(SCOPE, `decode failure: ${decoded.reason}`);
      this.sendError(socket, sessionId, deviceId, 'INVALID_ENVELOPE', decoded.reason);
      return;
    }
    const env = decoded.envelope;
    if (!this.sessions.noteIncomingSeq(env.seq)) {
      logger.debug(SCOPE, `duplicate seq=${env.seq}, dropping`);
      return;
    }
    logger.debug(SCOPE, `inbound type=${env.type} id=${env.id} seq=${env.seq}`);

    switch (env.type) {
      case BridgeMessageType.TOOL_CALL:
        await this.handleToolCall(env, sessionId, deviceId);
        break;
      case BridgeMessageType.APPROVAL_ACCEPTED:
        this.handleApprovalDecision(env, { status: 'approved' });
        break;
      case BridgeMessageType.APPROVAL_REJECTED:
        this.handleApprovalDecision(env, {
          status: 'rejected',
          reason:
            (env.payload['reason'] as string | undefined) ?? 'rejected by android'
        });
        break;
      case BridgeMessageType.AGENT_STATUS:
        this.handleAgentStatus(env);
        break;
      case BridgeMessageType.AGENT_PAUSED:
        this.sessions.emergencyStop();
        break;
      case BridgeMessageType.AGENT_RESUMED:
        this.sessions.resume();
        break;
      case BridgeMessageType.AGENT_CANCELLED:
        this.sessions.emergencyStop();
        this.approvals.cancelAll('agent cancelled');
        break;
      case BridgeMessageType.SESSION_CLOSED:
        this.sessions.close('client requested');
        this.approvals.cancelAll('session closed by client');
        try {
          socket.close(1000, 'client closed session');
        } catch {
          /* ignore */
        }
        break;
      case BridgeMessageType.AGENT_RUNTIME_STATUS_REQUEST:
      case BridgeMessageType.AGENT_RUNTIME_START:
      case BridgeMessageType.AGENT_RUNTIME_STOP:
      case BridgeMessageType.AGENT_RUNTIME_RESTART:
      case BridgeMessageType.AGENT_CONFIG_REQUEST:
      case BridgeMessageType.AGENT_PROVIDER_LIST_REQUEST:
      case BridgeMessageType.AGENT_MODEL_LIST_REQUEST:
      case BridgeMessageType.AGENT_MCP_LIST_REQUEST:
      case BridgeMessageType.AGENT_SESSION_LIST_REQUEST:
      case BridgeMessageType.AGENT_SESSION_CREATE:
      case BridgeMessageType.AGENT_SESSION_DELETE:
      case BridgeMessageType.AGENT_SESSION_PROMPT:
      case BridgeMessageType.AGENT_SESSION_ABORT:
      case BridgeMessageType.AGENT_PERMISSION_REPLY:
      case BridgeMessageType.AGENT_QUESTION_REPLY:
      case BridgeMessageType.AGENT_PTY_OPEN:
      case BridgeMessageType.AGENT_PTY_RESIZE:
      case BridgeMessageType.AGENT_PTY_INPUT:
      case BridgeMessageType.AGENT_PTY_CLOSE:
        await this.handleAgentEnvelope(env, sessionId, deviceId);
        break;
      default:
        logger.debug(SCOPE, `unhandled inbound type=${env.type}`);
    }
  }

  // ── Agent status (Android → Windows sync) ───────────────────────────────

  private handleAgentStatus(env: BridgeEnvelope): void {
    const status = env.payload['status'];
    if (status === 'agent_control_changed') {
      const enabled = env.payload['agentControlEnabled'] === true;
      this.broadcastAgentControl(enabled, 'android');
      logger.info(
        SCOPE,
        `agent control (android → windows) = ${enabled ? 'on' : 'off'}`
      );
    }
  }

  // ── Approval decisions from Android ─────────────────────────────────────

  private handleApprovalDecision(
    env: BridgeEnvelope,
    outcome: ApprovalOutcome
  ): void {
    const requestId = env.payload['requestId'];
    const toolCallId = env.payload['toolCallId'];
    if (typeof requestId === 'string' && requestId.length) {
      const ok = this.approvals.resolve(requestId, outcome);
      if (ok) return;
    }
    if (typeof toolCallId === 'string' && toolCallId.length) {
      this.approvals.resolveByToolCallId(toolCallId, outcome);
    }
  }

  // ── Tool dispatch ────────────────────────────────────────────────────────

  private async handleToolCall(
    env: BridgeEnvelope,
    sessionId: string,
    deviceId: string
  ): Promise<void> {
    const payload = env.payload as Partial<BridgeToolCall>;
    const toolCallId = payload.id ?? env.id;
    const toolName = typeof payload.tool === 'string' ? payload.tool : '';
    const args =
      payload.args && typeof payload.args === 'object'
        ? (payload.args as Record<string, unknown>)
        : {};
    const declaredRisk = riskFromWire(payload.risk as string | undefined);

    this.audit.append({
      id: newId(),
      sessionId,
      toolCallId,
      eventType: 'tool_requested',
      tool: toolName,
      risk: declaredRisk,
      argsPreview: previewArgs(toolName, args),
      timestamp: nowMs(),
      actor: 'android_agent'
    });

    const spec = findTool(toolName);
    if (!spec) {
      this.sendToolError(sessionId, deviceId, toolCallId, toolName, 'UNKNOWN', 'Unknown tool.');
      return;
    }
    if (!spec.enabled) {
      this.sendToolError(
        sessionId,
        deviceId,
        toolCallId,
        toolName,
        'PERMISSION_DENIED',
        'Tool is disabled by bridge policy.'
      );
      return;
    }

    const snap = this.sessions.snapshot;
    const decision = decide(spec.risk, {
      sessionConnected: snap.status === 'connected' || snap.status === 'agent_control',
      agentControlEnabled: snap.agentControlEnabled,
      emergencyStopped: snap.emergencyStopped
    });

    if (decision === BridgePermissionDecision.BLOCK) {
      this.sendToolError(
        sessionId,
        deviceId,
        toolCallId,
        toolName,
        'COMMAND_BLOCKED',
        'Tool is blocked by bridge policy.'
      );
      return;
    }
    if (decision === BridgePermissionDecision.DENY) {
      this.sendToolError(
        sessionId,
        deviceId,
        toolCallId,
        toolName,
        'PERMISSION_DENIED',
        snap.emergencyStopped
          ? 'Bridge is in emergency stop.'
          : 'Session not ready for this tool.'
      );
      return;
    }

    // App allowlist check for input tools — independent of risk engine decision.
    if (INPUT_TOOLS.has(toolName)) {
      const denial = await this.checkAppPolicy();
      if (denial) {
        this.sendToolError(
          sessionId,
          deviceId,
          toolCallId,
          toolName,
          'APP_NOT_ALLOWED',
          denial
        );
        this.audit.append({
          id: newId(),
          sessionId,
          toolCallId,
          eventType: 'tool_failed',
          tool: toolName,
          risk: spec.risk,
          decision,
          resultPreview: { code: 'APP_NOT_ALLOWED', reason: denial },
          timestamp: nowMs(),
          actor: 'windows_bridge'
        });
        return;
      }
    }

    if (decision === BridgePermissionDecision.REQUIRE_APPROVAL) {
      if (!this.policy.approval.enabled) {
        this.sendToolError(
          sessionId,
          deviceId,
          toolCallId,
          toolName,
          'APPROVAL_REQUIRED',
          'Tool requires user approval but approvals are disabled.'
        );
        return;
      }

      const outcome = await this.requestApproval({
        sessionId,
        toolCallId,
        tool: toolName,
        risk: spec.risk,
        args
      });

      if (outcome.status === 'approved') {
        this.audit.append({
          id: newId(),
          sessionId,
          toolCallId,
          eventType: 'approval_accepted',
          tool: toolName,
          risk: spec.risk,
          decision,
          timestamp: nowMs(),
          actor: 'android_user'
        });
      } else {
        const code = outcome.status === 'expired' ? 'TIMEOUT' : 'APPROVAL_REJECTED';
        const msg =
          outcome.status === 'expired'
            ? 'Approval request expired.'
            : outcome.reason ?? 'Approval rejected by user.';
        this.sendToolError(sessionId, deviceId, toolCallId, toolName, code, msg);
        this.audit.append({
          id: newId(),
          sessionId,
          toolCallId,
          eventType: 'approval_rejected',
          tool: toolName,
          risk: spec.risk,
          decision,
          resultPreview: { status: outcome.status, reason: 'reason' in outcome ? outcome.reason ?? '' : '' },
          timestamp: nowMs(),
          actor: 'android_user'
        });
        return;
      }
    }

    const startedAt = nowMs();
    this.audit.append({
      id: newId(),
      sessionId,
      toolCallId,
      eventType: 'tool_started',
      tool: toolName,
      risk: spec.risk,
      decision,
      timestamp: startedAt,
      actor: 'windows_bridge'
    });

    try {
      const result = await spec.execute(args);
      const finishedAt = nowMs();
      this.sendToolResult(sessionId, deviceId, toolCallId, toolName, result.status, {
        ...result.result
      }, startedAt, finishedAt);
      this.audit.append({
        id: newId(),
        sessionId,
        toolCallId,
        eventType: 'tool_succeeded',
        tool: toolName,
        risk: spec.risk,
        decision,
        resultPreview: previewResult(toolName, result.result),
        timestamp: finishedAt,
        actor: 'windows_bridge'
      });
    } catch (err) {
      const finishedAt = nowMs();
      const code =
        err instanceof ToolInvocationError ? err.code : 'EXECUTION_FAILED';
      const message =
        err instanceof Error ? err.message : 'Tool execution failed.';
      this.sendToolError(sessionId, deviceId, toolCallId, toolName, code, message, {
        recoverable: err instanceof ToolInvocationError ? err.recoverable : false
      });
      this.audit.append({
        id: newId(),
        sessionId,
        toolCallId,
        eventType: 'tool_failed',
        tool: toolName,
        risk: spec.risk,
        decision,
        resultPreview: { code, message },
        timestamp: finishedAt,
        actor: 'windows_bridge'
      });
    }
  }

  private async checkAppPolicy(): Promise<string | null> {
    if (!this.helper) return null;
    const active = await this.helper.activeWindow();
    if (!active) return null;
    const verdict = evaluateAppPolicy(this.policy.appPolicy, {
      processName: active.processName ?? '',
      title: active.title ?? ''
    });
    return verdict.decision === 'deny' ? verdict.reason : null;
  }

  // ── Approval request plumbing ───────────────────────────────────────────

  private async requestApproval(params: {
    sessionId: string;
    toolCallId: string;
    tool: string;
    risk: 'low' | 'medium' | 'high' | 'blocked';
    args: Record<string, unknown>;
  }): Promise<ApprovalOutcome> {
    this.audit.append({
      id: newId(),
      sessionId: params.sessionId,
      toolCallId: params.toolCallId,
      eventType: 'approval_requested',
      tool: params.tool,
      risk: params.risk,
      argsPreview: previewArgs(params.tool, params.args),
      timestamp: nowMs(),
      actor: 'windows_bridge'
    });
    return this.approvals.open({
      sessionId: params.sessionId,
      toolCallId: params.toolCallId,
      tool: params.tool,
      risk: params.risk,
      reason: `Tool '${params.tool}' requires user approval.`,
      argsPreview: previewArgs(params.tool, params.args),
      timeoutMs: this.policy.approval.timeoutMs
    });
  }

  private sendApprovalRequest(request: {
    id: string;
    sessionId: string;
    toolCallId: string;
    tool: string;
    risk: string;
    reason: string;
    argsPreview: Record<string, unknown>;
    requestedAt: number;
    expiresAt?: number | null;
    status: string;
  }): void {
    const socket = this.activeSocket;
    const snap = this.sessions.snapshot;
    if (!socket || !snap.sessionId) return;
    this.sendDirect(
      socket,
      BridgeMessageType.APPROVAL_REQUEST,
      snap.sessionId,
      snap.deviceId ?? 'unknown_device',
      {
        id: request.id,
        sessionId: request.sessionId,
        toolCallId: request.toolCallId,
        tool: request.tool,
        risk: request.risk,
        reason: request.reason,
        argsPreview: request.argsPreview,
        requestedAt: request.requestedAt,
        expiresAt: request.expiresAt ?? null,
        status: request.status
      }
    );
  }

  // ── Agent runtime (opencode, claude-code, codex) ────────────────────────

  private async handleAgentEnvelope(
    env: BridgeEnvelope,
    sessionId: string,
    deviceId: string
  ): Promise<void> {
    const router = this.agentRouter;
    const runtimeId =
      typeof env.payload['runtimeId'] === 'string'
        ? (env.payload['runtimeId'] as string)
        : '';
    const provider = runtimeId ? router?.get(runtimeId) ?? null : null;
    const replyType = this.agentReplyType(env.type);
    if (!router) {
      this.sendAgentError(
        sessionId,
        deviceId,
        env,
        'agent router not configured on this bridge'
      );
      return;
    }
    if (!provider) {
      this.sendAgentError(
        sessionId,
        deviceId,
        env,
        `unknown agent runtime: ${runtimeId || '<missing>'}`
      );
      return;
    }

    try {
      switch (env.type) {
        case BridgeMessageType.AGENT_RUNTIME_STATUS_REQUEST: {
          const info = provider.info();
          this.sendEnvelope(replyType, sessionId, deviceId, {
            ...info,
            runtimes: router.list().map((p) => p.info())
          });
          break;
        }
        case BridgeMessageType.AGENT_RUNTIME_START: {
          const payload = env.payload as unknown as AgentRuntimeStartPayload;
          const info = await provider.start(payload);
          this.sendEnvelope(
            BridgeMessageType.AGENT_RUNTIME_STATUS,
            sessionId,
            deviceId,
            {
              ...info,
              runtimes: router.list().map((p) => p.info())
            }
          );
          break;
        }
        case BridgeMessageType.AGENT_RUNTIME_STOP: {
          await provider.stop();
          const info = provider.info();
          this.sendEnvelope(
            BridgeMessageType.AGENT_RUNTIME_STATUS,
            sessionId,
            deviceId,
            {
              ...info,
              runtimes: router.list().map((p) => p.info())
            }
          );
          break;
        }
        case BridgeMessageType.AGENT_RUNTIME_RESTART: {
          const payload = env.payload as unknown as AgentRuntimeStartPayload;
          const info = await provider.restart(payload);
          this.sendEnvelope(
            BridgeMessageType.AGENT_RUNTIME_STATUS,
            sessionId,
            deviceId,
            {
              ...info,
              runtimes: router.list().map((p) => p.info())
            }
          );
          break;
        }
        case BridgeMessageType.AGENT_CONFIG_REQUEST: {
          const config = await provider.getConfig();
          this.sendEnvelope(BridgeMessageType.AGENT_CONFIG, sessionId, deviceId, {
            runtimeId,
            configJson: config.configJson,
            configPath: config.configPath ?? null
          });
          break;
        }
        case BridgeMessageType.AGENT_PROVIDER_LIST_REQUEST: {
          const providers: AgentProviderEntry[] = await provider.listProviders();
          this.sendEnvelope(
            BridgeMessageType.AGENT_PROVIDER_LIST,
            sessionId,
            deviceId,
            { runtimeId, providers }
          );
          break;
        }
        case BridgeMessageType.AGENT_MODEL_LIST_REQUEST: {
          const { models, defaultModel } = await provider.listModels();
          this.sendEnvelope(
            BridgeMessageType.AGENT_MODEL_LIST,
            sessionId,
            deviceId,
            {
              runtimeId,
              models: models as AgentModelEntry[],
              defaultModel: (defaultModel ?? null) as AgentModelRef | null
            }
          );
          break;
        }
        case BridgeMessageType.AGENT_MCP_LIST_REQUEST: {
          const servers: AgentMcpEntry[] = await provider.listMcp();
          this.sendEnvelope(
            BridgeMessageType.AGENT_MCP_LIST,
            sessionId,
            deviceId,
            { runtimeId, servers }
          );
          break;
        }
        case BridgeMessageType.AGENT_SESSION_LIST_REQUEST: {
          const sessions: AgentSessionSummary[] = await provider.listSessions();
          this.sendEnvelope(
            BridgeMessageType.AGENT_SESSION_LIST,
            sessionId,
            deviceId,
            { runtimeId, sessions }
          );
          break;
        }
        case BridgeMessageType.AGENT_SESSION_CREATE: {
          const payload = env.payload as unknown as AgentSessionCreatePayload;
          const created = await provider.createSession(payload);
          this.sendEnvelope(
            BridgeMessageType.AGENT_SESSION_CREATED,
            sessionId,
            deviceId,
            { runtimeId, session: created }
          );
          break;
        }
        case BridgeMessageType.AGENT_SESSION_DELETE: {
          const payload = env.payload as unknown as AgentSessionDeletePayload;
          await provider.deleteSession(payload.sessionId);
          this.sendEnvelope(
            BridgeMessageType.AGENT_SESSION_DELETED,
            sessionId,
            deviceId,
            { runtimeId, sessionId: payload.sessionId }
          );
          break;
        }
        case BridgeMessageType.AGENT_SESSION_PROMPT: {
          const payload = env.payload as unknown as AgentSessionPromptPayload;
          await provider.prompt(payload);
          break;
        }
        case BridgeMessageType.AGENT_SESSION_ABORT: {
          const sid = env.payload['sessionId'];
          if (typeof sid === 'string') await provider.abort(sid);
          break;
        }
        case BridgeMessageType.AGENT_PERMISSION_REPLY: {
          const payload = env.payload as unknown as AgentPermissionReplyPayload;
          await provider.replyPermission(payload);
          break;
        }
        case BridgeMessageType.AGENT_QUESTION_REPLY: {
          const payload = env.payload as unknown as AgentQuestionReplyPayload;
          await provider.replyQuestion(payload);
          break;
        }
        case BridgeMessageType.AGENT_PTY_OPEN: {
          const result = await provider.openPty(env.payload as Record<string, unknown>);
          this.sendEnvelope(
            BridgeMessageType.AGENT_PTY_OPENED,
            sessionId,
            deviceId,
            {
              runtimeId,
              ptyId: result.ptyId,
              requestId: env.id
            }
          );
          break;
        }
        case BridgeMessageType.AGENT_PTY_RESIZE: {
          const ptyId = env.payload['ptyId'] as string | undefined;
          const cols = Number(env.payload['cols'] ?? 0);
          const rows = Number(env.payload['rows'] ?? 0);
          if (!ptyId || !cols || !rows) break;
          await provider.resizePty(ptyId, cols, rows);
          break;
        }
        case BridgeMessageType.AGENT_PTY_INPUT: {
          const ptyId = env.payload['ptyId'] as string | undefined;
          const data = env.payload['dataBase64'] as string | undefined;
          if (!ptyId || !data) break;
          await provider.writePty(ptyId, data);
          break;
        }
        case BridgeMessageType.AGENT_PTY_CLOSE: {
          const ptyId = env.payload['ptyId'] as string | undefined;
          if (!ptyId) break;
          await provider.closePty(ptyId);
          this.sendEnvelope(
            BridgeMessageType.AGENT_PTY_CLOSED,
            sessionId,
            deviceId,
            { runtimeId, ptyId, reason: 'closed_by_client' }
          );
          break;
        }
        default:
          break;
      }
    } catch (err) {
      logger.warn(SCOPE, `agent op ${env.type} failed: ${(err as Error).message}`);
      this.sendAgentError(sessionId, deviceId, env, (err as Error).message);
    }
  }

  private agentReplyType(type: string): string {
    switch (type) {
      case BridgeMessageType.AGENT_RUNTIME_STATUS_REQUEST:
      case BridgeMessageType.AGENT_RUNTIME_START:
      case BridgeMessageType.AGENT_RUNTIME_STOP:
      case BridgeMessageType.AGENT_RUNTIME_RESTART:
        return BridgeMessageType.AGENT_RUNTIME_STATUS;
      case BridgeMessageType.AGENT_CONFIG_REQUEST:
        return BridgeMessageType.AGENT_CONFIG;
      case BridgeMessageType.AGENT_PROVIDER_LIST_REQUEST:
        return BridgeMessageType.AGENT_PROVIDER_LIST;
      case BridgeMessageType.AGENT_MODEL_LIST_REQUEST:
        return BridgeMessageType.AGENT_MODEL_LIST;
      case BridgeMessageType.AGENT_MCP_LIST_REQUEST:
        return BridgeMessageType.AGENT_MCP_LIST;
      case BridgeMessageType.AGENT_SESSION_LIST_REQUEST:
        return BridgeMessageType.AGENT_SESSION_LIST;
      case BridgeMessageType.AGENT_SESSION_CREATE:
        return BridgeMessageType.AGENT_SESSION_CREATED;
      case BridgeMessageType.AGENT_SESSION_DELETE:
        return BridgeMessageType.AGENT_SESSION_DELETED;
      default:
        return BridgeMessageType.AGENT_EVENT;
    }
  }

  private sendAgentEvent(event: AgentEventPayload): void {
    const snap = this.sessions.snapshot;
    const socket = this.activeSocket;
    if (!socket || !snap.sessionId) return;
    this.sendDirect(
      socket,
      BridgeMessageType.AGENT_EVENT,
      snap.sessionId,
      snap.deviceId ?? 'unknown_device',
      event as unknown as Record<string, unknown>
    );
  }

  private sendPtyEvent(event: Record<string, unknown>): void {
    const snap = this.sessions.snapshot;
    const socket = this.activeSocket;
    if (!socket || !snap.sessionId) return;
    const kind = (event.kind as string | undefined) ?? 'output';
    const type = kind === 'output'
      ? BridgeMessageType.AGENT_PTY_OUTPUT
      : kind === 'opened'
        ? BridgeMessageType.AGENT_PTY_OPENED
        : BridgeMessageType.AGENT_PTY_CLOSED;
    this.sendDirect(
      socket,
      type,
      snap.sessionId,
      snap.deviceId ?? 'unknown_device',
      event
    );
  }

  private sendAgentRuntimeStatus(info: AgentRuntimeInfo): void {
    const router = this.agentRouter;
    this.send(BridgeMessageType.AGENT_RUNTIME_STATUS, {
      ...info,
      runtimes: router ? router.list().map((p) => p.info()) : undefined
    });
  }

  private sendEnvelope(
    type: string,
    sessionId: string,
    deviceId: string,
    payload: Record<string, unknown>
  ): void {
    const socket = this.activeSocket;
    if (!socket) return;
    this.sendDirect(socket, type, sessionId, deviceId, payload);
  }

  private sendAgentError(
    sessionId: string,
    deviceId: string,
    env: BridgeEnvelope,
    message: string
  ): void {
    this.sendEnvelope(BridgeMessageType.ERROR, sessionId, deviceId, {
      code: 'AGENT_OP_FAILED',
      message,
      requestId: env.id,
      requestType: env.type
    });
  }

  // ── Outgoing helpers ─────────────────────────────────────────────────────

  private send(type: string, payload: Record<string, unknown>): void {
    const socket = this.activeSocket;
    const snap = this.sessions.snapshot;
    if (!socket || !snap.sessionId) return;
    this.sendDirect(
      socket,
      type,
      snap.sessionId,
      snap.deviceId ?? 'unknown_device',
      payload
    );
  }

  private sendDirect(
    socket: WebSocket,
    type: string,
    sessionId: string,
    deviceId: string,
    payload: Record<string, unknown>
  ): void {
    const envelope: BridgeEnvelope = {
      id: newId(),
      type: type as BridgeEnvelope['type'],
      sessionId,
      deviceId: BRIDGE_DEVICE_ID,
      seq: this.sessions.nextOutgoingSeq(),
      timestamp: nowMs(),
      payload,
      metadata: { target: deviceId }
    };
    try {
      socket.send(encodeEnvelope(envelope));
      logger.debug(
        SCOPE,
        `outbound type=${envelope.type} id=${envelope.id} seq=${envelope.seq}`
      );
    } catch (err) {
      logger.warn(SCOPE, 'send failed', (err as Error).message);
    }
  }

  private sendToolResult(
    sessionId: string,
    deviceId: string,
    toolCallId: string,
    tool: string,
    status: 'success' | 'cancelled' | 'timeout',
    result: Record<string, unknown>,
    startedAt: number,
    finishedAt: number
  ): void {
    const socket = this.activeSocket;
    if (!socket) return;
    this.sendDirect(socket, BridgeMessageType.TOOL_RESULT, sessionId, deviceId, {
      id: newId(),
      toolCallId,
      sessionId,
      tool,
      status,
      result,
      startedAt,
      finishedAt,
      durationMs: Math.max(0, finishedAt - startedAt)
    });
  }

  private sendToolError(
    sessionId: string,
    deviceId: string,
    toolCallId: string,
    tool: string,
    code: string,
    message: string,
    details: Record<string, unknown> = {}
  ): void {
    const socket = this.activeSocket;
    if (!socket) return;
    this.sendDirect(socket, BridgeMessageType.TOOL_ERROR, sessionId, deviceId, {
      id: newId(),
      toolCallId,
      sessionId,
      tool,
      code,
      message,
      details,
      recoverable: details['recoverable'] === true,
      timestamp: nowMs()
    });
  }

  private sendError(
    socket: WebSocket,
    sessionId: string | null,
    deviceId: string,
    code: string,
    message: string
  ): void {
    this.sendDirect(
      socket,
      BridgeMessageType.ERROR,
      sessionId ?? 'unknown_session',
      deviceId,
      { code, message }
    );
  }
}

function previewArgs(
  toolName: string,
  args: Record<string, unknown>
): Record<string, unknown> {
  // Per-tool redaction: never log typed text, only its length.
  if (toolName === 'keyboard.type') {
    const text = args['text'];
    return {
      length: typeof text === 'string' ? text.length : 0,
      intervalMs: typeof args['intervalMs'] === 'number' ? args['intervalMs'] : undefined
    };
  }
  if (toolName === 'clipboard.write') {
    const text = args['text'];
    return { length: typeof text === 'string' ? text.length : 0 };
  }
  if (toolName === 'keyboard.hotkey') {
    return { keys: previewHotkeyKeys(args) };
  }
  if (toolName === 'window.focus') {
    return { windowId: args['windowId'] };
  }

  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(args)) {
    if (typeof v === 'string') {
      out[k] = v.length > 80 ? `${v.slice(0, 77)}...` : v;
    } else if (typeof v === 'number' || typeof v === 'boolean') {
      out[k] = v;
    } else {
      out[k] = '[object]';
    }
  }
  return out;
}

function previewHotkeyKeys(args: Record<string, unknown>): string[] {
  const raw = args['keys'] ?? args['combo'] ?? args['hotkey'] ?? args['shortcut'];
  if (typeof raw === 'string') {
    const separator = raw.includes('+') ? /\s*\+\s*/ : /\s*,\s*|\s+/;
    return raw.split(separator).map((part) => part.trim()).filter(Boolean);
  }
  if (Array.isArray(raw)) {
    return raw.flatMap((item) =>
      typeof item === 'string' ? previewHotkeyKeys({ keys: item }) : []
    );
  }
  if (raw && typeof raw === 'object') {
    const obj = raw as Record<string, unknown>;
    if ('keys' in obj || 'combo' in obj || 'hotkey' in obj || 'shortcut' in obj) {
      return previewHotkeyKeys(obj);
    }
    return Object.keys(obj)
      .sort(compareMaybeNumericKeys)
      .flatMap((key) => previewHotkeyKeys({ keys: obj[key] }));
  }
  return [];
}

function compareMaybeNumericKeys(a: string, b: string): number {
  const an = Number(a);
  const bn = Number(b);
  const ai = Number.isFinite(an);
  const bi = Number.isFinite(bn);
  if (ai && bi) return an - bn;
  if (ai) return -1;
  if (bi) return 1;
  return a.localeCompare(b);
}

function previewResult(
  toolName: string,
  result: Record<string, unknown>
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(result)) {
    if (k === 'imageBase64' && typeof v === 'string') {
      out[k] = `[base64 length=${v.length}]`;
      continue;
    }
    if (typeof v === 'string') {
      out[k] = v.length > 80 ? `${v.slice(0, 77)}...` : v;
    } else if (typeof v === 'number' || typeof v === 'boolean') {
      out[k] = v;
    } else {
      out[k] = '[object]';
    }
  }
  // Suppress `text` on any tool we explicitly never want to log.
  void toolName;
  return out;
}
