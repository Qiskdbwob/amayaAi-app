package com.amaya.intelligence.impl.bridge.windows.pairing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists [WindowsBridgeProfile] entries in SharedPreferences. Token is never
 * stored — only host/port/name/deviceId/bridgeId/computerName.
 *
 * Thread-safe: reads are from memory snapshot, writes are atomic via `apply()`.
 */
@Singleton
class WindowsBridgeProfileStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getAll(): List<WindowsBridgeProfile> = synchronized(lock) {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.optJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun findById(id: String): WindowsBridgeProfile? = getAll().firstOrNull { it.id == id }

    fun findByHost(host: String, port: Int): WindowsBridgeProfile? =
        getAll().firstOrNull { it.host == host && it.port == port }

    fun save(profile: WindowsBridgeProfile): WindowsBridgeProfile {
        synchronized(lock) {
            val existing = getAll().toMutableList()
            val idx = existing.indexOfFirst { it.id == profile.id }
            if (idx >= 0) {
                existing[idx] = profile
            } else {
                existing.add(profile)
            }
            persist(existing)
        }
        return profile
    }

    fun saveOrUpdate(
        host: String,
        port: Int,
        deviceId: String,
        bridgeId: String? = null,
        computerName: String? = null
    ): WindowsBridgeProfile {
        val existing = findByHost(host, port)
        val profile = existing?.copy(
            bridgeId = bridgeId ?: existing.bridgeId,
            computerName = computerName ?: existing.computerName,
            lastConnectedAt = System.currentTimeMillis(),
            trusted = true
        ) ?: WindowsBridgeProfile(
            id = UUID.randomUUID().toString(),
            bridgeId = bridgeId,
            name = computerName ?: "$host:$port",
            host = host,
            port = port,
            deviceId = deviceId,
            computerName = computerName,
            lastConnectedAt = System.currentTimeMillis(),
            trusted = true
        )
        return save(profile)
    }

    fun delete(id: String) {
        synchronized(lock) {
            val remaining = getAll().filter { it.id != id }
            persist(remaining)
        }
    }

    fun clear() {
        synchronized(lock) { persist(emptyList()) }
    }

    private fun persist(profiles: List<WindowsBridgeProfile>) {
        val arr = JSONArray()
        for (p in profiles) arr.put(toJson(p))
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    private fun toJson(p: WindowsBridgeProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("bridgeId", p.bridgeId ?: JSONObject.NULL)
        put("name", p.name)
        put("host", p.host)
        put("port", p.port)
        put("deviceId", p.deviceId)
        put("computerName", p.computerName ?: JSONObject.NULL)
        put("lastConnectedAt", p.lastConnectedAt ?: JSONObject.NULL)
        put("trusted", p.trusted)
    }

    private fun fromJson(json: JSONObject?): WindowsBridgeProfile? {
        json ?: return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val host = json.optString("host").takeIf { it.isNotBlank() } ?: return null
        val port = json.optInt("port", 17878)
        val deviceId = json.optString("deviceId").takeIf { it.isNotBlank() } ?: return null
        return WindowsBridgeProfile(
            id = id,
            bridgeId = json.optString("bridgeId").takeIf { it.isNotBlank() },
            name = json.optString("name", "$host:$port"),
            host = host,
            port = port,
            deviceId = deviceId,
            computerName = json.optString("computerName").takeIf { it.isNotBlank() },
            lastConnectedAt = json.optLong("lastConnectedAt", 0L).takeIf { it > 0 },
            trusted = json.optBoolean("trusted", false)
        )
    }

    companion object {
        private const val PREFS_NAME = "amaya_bridge_profiles"
        private const val KEY_PROFILES = "profiles_json"
    }
}
