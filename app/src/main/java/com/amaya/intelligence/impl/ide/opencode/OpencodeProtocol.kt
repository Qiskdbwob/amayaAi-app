package com.amaya.intelligence.impl.ide.opencode

/**
 * Constants mirroring the bridge-side `AgentEventKind` map. Used to switch on
 * payload["kind"] values carried inside [BridgeMessageType.AGENT_EVENT] envelopes.
 *
 * Kept co-located with the opencode implementation because the wire strings are
 * defined in `domain/bridge/AgentRuntime.kt` and should not be re-declared
 * elsewhere. We only mirror values that opencode actually emits today.
 */
object OpencodeEventKind {
    const val MESSAGE_PART_TEXT = "message.part.text"
    const val MESSAGE_PART_THOUGHT = "message.part.thought"
    const val MESSAGE_PART_TOOL = "message.part.tool"
    const val TOOL_CALL_UPDATE = "tool.call.update"
    const val PLAN_UPDATE = "plan.update"
    const val TODO_UPDATE = "todo.update"
    const val PERMISSION_ASKED = "permission.asked"
    const val PERMISSION_REPLIED = "permission.replied"
    const val QUESTION_ASKED = "question.asked"
    const val QUESTION_REPLIED = "question.replied"
    const val SESSION_STATUS = "session.status"
    const val SESSION_IDLE = "session.idle"
    const val SESSION_ERROR = "session.error"
    const val SESSION_DIFF = "session.diff"
    const val MCP_CHANGED = "mcp.changed"
    const val INSTALLATION_UPDATE = "installation.update"
}

/** Opencode runtime ID as advertised by the bridge. */
const val OPENCODE_RUNTIME_ID = "opencode"
