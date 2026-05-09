package com.amaya.intelligence.impl.bridge.windows.pairing

import org.json.JSONObject

/**
 * Parsed pairing payload copied from the Windows Bridge status window.
 *
 * Expected JSON shape:
 * ```json
 * {
 *   "type": "amaya.windows_bridge.pairing",
 *   "version": 1,
 *   "host": "192.168.1.5",
 *   "port": 17878,
 *   "token": "short-lived-token",
 *   "bridgeId": "windows_bridge_abc",
 *   "computerName": "BIUBIU-PC",
 *   "expiresAt": 1778320000
 * }
 * ```
 */
data class WindowsBridgePairingPayload(
    val host: String,
    val port: Int,
    val token: String?,
    val bridgeId: String?,
    val computerName: String?,
    val expiresAt: Long?
) {
    val expiresAtMillis: Long?
        get() = expiresAt?.let { value ->
            // Pairing payloads may be produced as epoch seconds by the Windows app or
            // epoch millis by tests/tools. Normalize both forms before comparing.
            if (value in 1 until 10_000_000_000L) value * 1000L else value
        }

    val isExpired: Boolean
        get() = expiresAtMillis != null && System.currentTimeMillis() > expiresAtMillis!!

    companion object {
        private const val EXPECTED_TYPE = "amaya.windows_bridge.pairing"
        private const val CURRENT_VERSION = 1

        /**
         * Parse a pairing payload JSON string. Returns null if the input is not a
         * valid pairing payload (wrong type, missing host, etc.). Never throws.
         */
        fun parse(raw: String?): WindowsBridgePairingPayload? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw.trim())
                val type = json.optString("type", "")
                if (type != EXPECTED_TYPE) return null
                val version = json.optInt("version", 0)
                if (version < 1 || version > CURRENT_VERSION) return null
                val host = json.optString("host", "").takeIf { it.isNotBlank() } ?: return null
                val port = json.optInt("port", 17878).takeIf { it in 1..65535 } ?: return null
                val token = json.optString("token", "").takeIf { it.isNotBlank() }
                val bridgeId = json.optString("bridgeId", "").takeIf { it.isNotBlank() }
                val computerName = json.optString("computerName", "").takeIf { it.isNotBlank() }
                val expiresAt = json.optLong("expiresAt", 0L).takeIf { it > 0 }
                WindowsBridgePairingPayload(
                    host = host,
                    port = port,
                    token = token,
                    bridgeId = bridgeId,
                    computerName = computerName,
                    expiresAt = expiresAt
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
