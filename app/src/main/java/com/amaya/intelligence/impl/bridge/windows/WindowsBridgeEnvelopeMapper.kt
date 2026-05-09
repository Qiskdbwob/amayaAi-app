package com.amaya.intelligence.impl.bridge.windows

import com.amaya.intelligence.domain.bridge.ApprovalRequest
import com.amaya.intelligence.domain.bridge.ApprovalStatus
import com.amaya.intelligence.domain.bridge.BridgeAuditActor
import com.amaya.intelligence.domain.bridge.BridgeAuditEvent
import com.amaya.intelligence.domain.bridge.BridgeAuditEventType
import com.amaya.intelligence.domain.bridge.BridgeEnvelope
import com.amaya.intelligence.domain.bridge.BridgeError
import com.amaya.intelligence.domain.bridge.BridgeMessageType
import com.amaya.intelligence.domain.bridge.BridgePermissionDecision
import com.amaya.intelligence.domain.bridge.BridgeRiskLevel
import com.amaya.intelligence.domain.bridge.BridgeToolError
import com.amaya.intelligence.domain.bridge.BridgeToolErrorCode
import com.amaya.intelligence.domain.bridge.BridgeToolResult
import com.amaya.intelligence.domain.bridge.BridgeToolResultStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON ↔ [BridgeEnvelope] mapper used by [WindowsBridgeSessionClient].
 *
 * The mapper is intentionally tolerant: malformed or unknown fields never throw — they
 * produce a [DecodeResult.Failure] or fall through to a generic [BridgeEnvelope] with
 * the raw payload preserved as a `Map<String, Any?>`.
 *
 * Stays reference-only to the Antigravity client. No Antigravity types or JSON keys
 * leak into this code.
 */
internal object WindowsBridgeEnvelopeMapper {

    // ── Encoding ─────────────────────────────────────────────────────────────

    fun encode(envelope: BridgeEnvelope): String = toJson(envelope).toString()

    fun toJson(envelope: BridgeEnvelope): JSONObject = JSONObject().apply {
        put(KEY_ID, envelope.id)
        put(KEY_TYPE, envelope.type.wireName)
        put(KEY_SESSION_ID, envelope.sessionId ?: JSONObject.NULL)
        put(KEY_DEVICE_ID, envelope.deviceId)
        put(KEY_SEQ, envelope.seq)
        put(KEY_TIMESTAMP, envelope.timestamp)
        put(KEY_PAYLOAD, mapToJson(envelope.payload))
        if (envelope.metadata.isNotEmpty()) {
            put(KEY_METADATA, mapToJson(envelope.metadata))
        }
    }

    // ── Decoding ─────────────────────────────────────────────────────────────

    sealed class DecodeResult {
        data class Success(val envelope: BridgeEnvelope) : DecodeResult()
        data class Failure(val reason: String, val cause: Throwable? = null) : DecodeResult()
    }

    fun decode(raw: String): DecodeResult {
        val json = try {
            JSONObject(raw)
        } catch (ex: Exception) {
            return DecodeResult.Failure("malformed JSON: ${ex.message}", ex)
        }
        return decode(json)
    }

    fun decode(json: JSONObject): DecodeResult {
        val typeWire = json.optString(KEY_TYPE).takeIf { it.isNotBlank() }
            ?: return DecodeResult.Failure("missing required field: $KEY_TYPE")

        val type = BridgeMessageType.fromWireName(typeWire)
            ?: return DecodeResult.Failure("unknown message type: $typeWire")

        val id = json.optString(KEY_ID).takeIf { it.isNotBlank() }
            ?: return DecodeResult.Failure("missing required field: $KEY_ID")

        val deviceId = json.optString(KEY_DEVICE_ID).takeIf { it.isNotBlank() }
            ?: return DecodeResult.Failure("missing required field: $KEY_DEVICE_ID")

        val seq = json.optNumberOrNull(KEY_SEQ)?.toLong()
            ?: return DecodeResult.Failure("missing or invalid field: $KEY_SEQ")

        val timestamp = json.optNumberOrNull(KEY_TIMESTAMP)?.toLong()
            ?: return DecodeResult.Failure("missing or invalid field: $KEY_TIMESTAMP")

        val sessionId = json.optStringOrNull(KEY_SESSION_ID)

        val payloadJson = json.opt(KEY_PAYLOAD)
        val payload: Map<String, Any?> = when (payloadJson) {
            null, JSONObject.NULL -> emptyMap()
            is JSONObject -> jsonObjectToMap(payloadJson)
            else -> return DecodeResult.Failure("invalid payload: expected object")
        }

        val metadata: Map<String, String> =
            (json.optJSONObject(KEY_METADATA)?.let { jsonObjectToMap(it) } ?: emptyMap())
                .mapValues { (_, v) -> v?.toString() ?: "" }

        val envelope = BridgeEnvelope(
            id = id,
            type = type,
            sessionId = sessionId,
            deviceId = deviceId,
            seq = seq,
            timestamp = timestamp,
            payload = payload,
            metadata = metadata
        )
        return DecodeResult.Success(envelope)
    }

    // ── Typed payload decoders (safe, null on mismatch) ─────────────────────

    fun decodeToolResult(envelope: BridgeEnvelope): BridgeToolResult? {
        if (envelope.type != BridgeMessageType.TOOL_RESULT) return null
        val p = envelope.payload
        val toolCallId = p.string("toolCallId") ?: return null
        val sessionId = envelope.sessionId ?: p.string("sessionId") ?: return null
        val tool = p.string("tool") ?: return null
        val status = BridgeToolResultStatus.fromWireName(p.string("status"))
            ?: BridgeToolResultStatus.SUCCESS
        val startedAt = p.long("startedAt") ?: envelope.timestamp
        val finishedAt = p.long("finishedAt") ?: envelope.timestamp
        val result = (p["result"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        val metadata = (p["metadata"] as? Map<*, *>)?.stringKeyedString() ?: emptyMap()
        return BridgeToolResult(
            id = p.string("id") ?: envelope.id,
            toolCallId = toolCallId,
            sessionId = sessionId,
            tool = tool,
            status = status,
            result = result,
            startedAt = startedAt,
            finishedAt = finishedAt,
            metadata = metadata
        )
    }

    fun decodeToolError(envelope: BridgeEnvelope): BridgeToolError? {
        if (envelope.type != BridgeMessageType.TOOL_ERROR) return null
        val p = envelope.payload
        val toolCallId = p.string("toolCallId") ?: return null
        val sessionId = envelope.sessionId ?: p.string("sessionId") ?: return null
        val tool = p.string("tool") ?: return null
        val code = BridgeToolErrorCode.fromWireName(p.string("code"))
            ?: BridgeToolErrorCode.UNKNOWN
        val message = p.string("message") ?: "unknown error"
        val details = (p["details"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        val recoverable = (p["recoverable"] as? Boolean) ?: false
        return BridgeToolError(
            id = p.string("id") ?: envelope.id,
            toolCallId = toolCallId,
            sessionId = sessionId,
            tool = tool,
            code = code,
            message = message,
            details = details,
            recoverable = recoverable,
            timestamp = p.long("timestamp") ?: envelope.timestamp
        )
    }

    fun decodeApprovalRequest(envelope: BridgeEnvelope): ApprovalRequest? {
        if (envelope.type != BridgeMessageType.APPROVAL_REQUEST) return null
        val p = envelope.payload
        val sessionId = envelope.sessionId ?: p.string("sessionId") ?: return null
        val toolCallId = p.string("toolCallId") ?: return null
        val tool = p.string("tool") ?: return null
        val risk = BridgeRiskLevel.fromWireName(p.string("risk")) ?: BridgeRiskLevel.MEDIUM
        val reason = p.string("reason") ?: ""
        val argsPreview = (p["argsPreview"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        val status = ApprovalStatus.fromWireName(p.string("status")) ?: ApprovalStatus.PENDING
        return ApprovalRequest(
            id = p.string("id") ?: envelope.id,
            sessionId = sessionId,
            toolCallId = toolCallId,
            tool = tool,
            risk = risk,
            reason = reason,
            argsPreview = argsPreview,
            requestedAt = p.long("requestedAt") ?: envelope.timestamp,
            expiresAt = p.long("expiresAt"),
            status = status
        )
    }

    fun decodeAuditEvent(envelope: BridgeEnvelope): BridgeAuditEvent? {
        if (envelope.type != BridgeMessageType.AUDIT_EVENT) return null
        val p = envelope.payload
        val sessionId = envelope.sessionId ?: p.string("sessionId") ?: return null
        val eventType = BridgeAuditEventType.fromWireName(p.string("eventType")) ?: return null
        val actor = BridgeAuditActor.fromWireName(p.string("actor")) ?: BridgeAuditActor.SYSTEM
        val risk = BridgeRiskLevel.fromWireName(p.string("risk"))
        val decision = BridgePermissionDecision.fromWireName(p.string("decision"))
        val argsPreview = (p["argsPreview"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        val resultPreview = (p["resultPreview"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        return BridgeAuditEvent(
            id = p.string("id") ?: envelope.id,
            sessionId = sessionId,
            toolCallId = p.string("toolCallId"),
            eventType = eventType,
            tool = p.string("tool"),
            risk = risk,
            decision = decision,
            argsPreview = argsPreview,
            resultPreview = resultPreview,
            timestamp = p.long("timestamp") ?: envelope.timestamp,
            actor = actor
        )
    }

    fun decodeProtocolError(envelope: BridgeEnvelope): BridgeError? {
        if (envelope.type != BridgeMessageType.ERROR) return null
        val p = envelope.payload
        val code = p.string("code") ?: "UNKNOWN"
        val message = p.string("message") ?: "unknown error"
        val details = (p["details"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        return BridgeError(
            id = p.string("id") ?: envelope.id,
            sessionId = envelope.sessionId,
            code = code,
            message = message,
            details = details,
            timestamp = p.long("timestamp") ?: envelope.timestamp
        )
    }

    // ── JSON <-> Map helpers ─────────────────────────────────────────────────

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key, anyToJson(value))
        return obj
    }

    private fun anyToJson(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is String, is Boolean, is Int, is Long, is Double, is Float -> value
            is Number -> value
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) obj.put(k.toString(), anyToJson(v))
                obj
            }
            is List<*> -> JSONArray().apply { value.forEach { put(anyToJson(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(anyToJson(it)) } }
            else -> value.toString()
        }
    }

    internal fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = jsonValueToAny(obj.opt(key))
        }
        return out
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val out = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) out.add(jsonValueToAny(arr.opt(i)))
        return out
    }

    private fun jsonValueToAny(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }

    // ── Typed extractors ─────────────────────────────────────────────────────

    private fun Map<String, Any?>.string(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.stringKeyed(): Map<String, Any?> =
        entries.asSequence()
            .filter { it.key is String }
            .associate { (it.key as String) to it.value }

    private fun Map<*, *>.stringKeyedString(): Map<String, String> =
        entries.asSequence()
            .filter { it.key is String }
            .associate { (it.key as String) to (it.value?.toString() ?: "") }

    private fun JSONObject.optNumberOrNull(key: String): Number? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v
            is String -> v.toLongOrNull() ?: v.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return v.takeIf { it.isNotBlank() }
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    const val KEY_ID = "id"
    const val KEY_TYPE = "type"
    const val KEY_SESSION_ID = "sessionId"
    const val KEY_DEVICE_ID = "deviceId"
    const val KEY_SEQ = "seq"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_PAYLOAD = "payload"
    const val KEY_METADATA = "metadata"
}
