import {
  AgentEventKind,
  type AgentEventPayload
} from '../../protocol/bridge-agent';
import type { OpencodeSseEvent } from './opencode-event-stream';

const OPENCODE_RUNTIME_ID = 'opencode';

/**
 * Map an opencode SSE event to the neutral [AgentEventPayload] forwarded to
 * Android. Returns `null` for events the bridge intentionally drops.
 *
 * We keep this mapping permissive: unknown event shapes pass through as a
 * generic "session.status" wrapper so the UI can still render something if a
 * new opencode event type appears before we model it.
 */
export function mapOpencodeEvent(
  event: OpencodeSseEvent
): AgentEventPayload | null {
  const props = (event.properties ?? {}) as Record<string, unknown>;
  const now = Date.now();

  const sessionId = extractSessionId(event.type, props);

  switch (event.type) {
    case 'server.connected':
      // Noisy, drop — connection state is already tracked elsewhere.
      return null;

    case 'message.part.updated':
      return toMessagePart(props, sessionId, now);

    case 'message.part.removed':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.MESSAGE_PART_TEXT,
        data: {
          removed: true,
          messageId: props.messageID ?? null,
          partId: props.partID ?? null
        },
        timestamp: now
      };

    case 'message.updated':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.SESSION_STATUS,
        data: {
          messageId: (props.info as Record<string, unknown> | undefined)?.id ?? null,
          role:
            (props.info as Record<string, unknown> | undefined)?.role ?? null,
          info: props.info ?? null
        },
        timestamp: now
      };

    case 'session.status':
    case 'session.updated':
    case 'session.created':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.SESSION_STATUS,
        data: { ...props },
        timestamp: now
      };

    case 'session.idle':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.SESSION_IDLE,
        data: { ...props },
        timestamp: now
      };

    case 'session.error':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.SESSION_ERROR,
        data: { ...props },
        timestamp: now
      };

    case 'session.diff':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.SESSION_DIFF,
        data: { ...props },
        timestamp: now
      };

    case 'permission.asked':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.PERMISSION_ASKED,
        data: { ...props },
        timestamp: now
      };
    case 'permission.replied':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.PERMISSION_REPLIED,
        data: { ...props },
        timestamp: now
      };

    case 'question.asked':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.QUESTION_ASKED,
        data: { ...props },
        timestamp: now
      };
    case 'question.replied':
    case 'question.rejected':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.QUESTION_REPLIED,
        data: { ...props, rejected: event.type === 'question.rejected' },
        timestamp: now
      };

    case 'todo.updated':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.TODO_UPDATE,
        data: { ...props },
        timestamp: now
      };

    case 'mcp.tools.changed':
    case 'mcp.changed':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.MCP_CHANGED,
        data: { ...props },
        timestamp: now
      };

    case 'installation.updated':
    case 'installation.update_available':
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.INSTALLATION_UPDATE,
        data: { ...props, kind: event.type },
        timestamp: now
      };

    default:
      return {
        runtimeId: OPENCODE_RUNTIME_ID,
        sessionId,
        kind: AgentEventKind.SESSION_STATUS,
        data: { opencodeType: event.type, ...props },
        timestamp: now
      };
  }
}

function toMessagePart(
  props: Record<string, unknown>,
  sessionId: string | null,
  now: number
): AgentEventPayload | null {
  const part = (props.part ?? {}) as Record<string, unknown>;
  const partType = typeof part.type === 'string' ? part.type : '';
  if (partType === 'text') {
    return {
      runtimeId: OPENCODE_RUNTIME_ID,
      sessionId,
      kind: AgentEventKind.MESSAGE_PART_TEXT,
      data: {
        messageId: props.messageID ?? null,
        partId: part.id ?? null,
        text: part.text ?? '',
        timeEnd: (part.time as Record<string, unknown> | undefined)?.end ?? null
      },
      timestamp: now
    };
  }
  if (partType === 'thought') {
    return {
      runtimeId: OPENCODE_RUNTIME_ID,
      sessionId,
      kind: AgentEventKind.MESSAGE_PART_THOUGHT,
      data: {
        messageId: props.messageID ?? null,
        partId: part.id ?? null,
        text: part.text ?? ''
      },
      timestamp: now
    };
  }
  if (partType === 'tool' || partType === 'tool_use' || partType === 'tool_call') {
    return {
      runtimeId: OPENCODE_RUNTIME_ID,
      sessionId,
      kind: AgentEventKind.MESSAGE_PART_TOOL,
      data: {
        messageId: props.messageID ?? null,
        partId: part.id ?? null,
        tool: part.tool ?? null,
        state: part.state ?? null
      },
      timestamp: now
    };
  }
  return {
    runtimeId: OPENCODE_RUNTIME_ID,
    sessionId,
    kind: AgentEventKind.SESSION_STATUS,
    data: { ...props },
    timestamp: now
  };
}

function extractSessionId(
  type: string,
  props: Record<string, unknown>
): string | null {
  const direct = props.sessionID ?? props.sessionId ?? null;
  if (typeof direct === 'string' && direct.length > 0) return direct;
  const info = (props.info ?? null) as Record<string, unknown> | null;
  if (info && typeof info.sessionID === 'string') return info.sessionID;
  const id = (props.id ?? null) as string | null;
  if (type.startsWith('session.') && typeof id === 'string' && id.length > 0) {
    return id;
  }
  return null;
}
