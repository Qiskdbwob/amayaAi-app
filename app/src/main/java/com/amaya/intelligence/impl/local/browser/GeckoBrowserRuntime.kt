package com.amaya.intelligence.impl.local.browser

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import com.amaya.intelligence.util.debugLog
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.UUID

object GeckoBrowserRuntime {
    private const val EXTENSION_ID = "browser-bridge@amaya.local"
    private const val NATIVE_APP = "browser_bridge"
    private const val PROCESS_GONE_MESSAGE = "Browser content process was killed"
    private const val READY_TIMEOUT_MS = 8_000L
    private const val RECOVERY_READY_TIMEOUT_MS = 6_000L
    private const val LIVENESS_TRUST_MS = 3_000L

    private var runtime: GeckoRuntime? = null
    private var extension: WebExtension? = null
    private var installing: CompletableDeferred<WebExtension>? = null
    private val attaching = mutableMapOf<GeckoSession, CompletableDeferred<Unit>>()
    private val delegated = mutableSetOf<GeckoSession>()
    private val processGone = mutableSetOf<GeckoSession>()
    private val ports = mutableMapOf<GeckoSession, WebExtension.Port>()
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val pendingSessions = mutableMapOf<String, GeckoSession>()
    private val readySessions = mutableSetOf<GeckoSession>()
    private val lastReplyAt = mutableMapOf<GeckoSession, Long>()

    /** Raised when a session's content process is gone; only close()+open() can recover it. */
    class BridgeUnrecoverable(message: String) : IllegalStateException(message)

    @Synchronized
    private fun portFor(session: GeckoSession): WebExtension.Port? = ports[session]

    @Synchronized
    private fun addPort(session: GeckoSession, port: WebExtension.Port) {
        ports[session] = port
        readySessions.remove(session)
    }

    @Synchronized
    private fun removePort(session: GeckoSession, port: WebExtension.Port) {
        if (ports[session] === port) {
            ports.remove(session)
            readySessions.remove(session)
            failPending(session, "Browser bridge disconnected")
        }
    }

    @Synchronized
    private fun addPending(session: GeckoSession, id: String, result: CompletableDeferred<JSONObject>) {
        pending[id] = result
        pendingSessions[id] = session
    }

    @Synchronized
    private fun completePending(id: String, result: JSONObject) {
        pendingSessions.remove(id)?.let { lastReplyAt[it] = SystemClock.uptimeMillis() }
        pending.remove(id)?.complete(result)
    }

    @Synchronized
    private fun removePending(id: String) {
        pendingSessions.remove(id)
        pending.remove(id)
    }

    @Synchronized
    private fun markReady(session: GeckoSession, port: WebExtension.Port) {
        if (ports[session] === port) {
            readySessions.add(session)
            lastReplyAt[session] = SystemClock.uptimeMillis()
        }
    }

    @Synchronized
    private fun isReady(session: GeckoSession): Boolean = ports[session] != null && session in readySessions

    @Synchronized
    private fun failPending(session: GeckoSession, message: String) {
        pendingSessions.filterValues { it === session }.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(IllegalStateException(message))
            pendingSessions.remove(id)
        }
    }

    @Synchronized
    private fun removeSession(session: GeckoSession) {
        delegated.remove(session)
        ports.remove(session)
        readySessions.remove(session)
        lastReplyAt.remove(session)
        processGone.remove(session)
        failPending(session, "Browser session closed")
        attaching.remove(session)?.cancel()
    }

    /**
     * The content process that hosted this session died. Gecko keeps the session object
     * alive but it can never run script again, so drop every cached bridge fact and make
     * later calls fail immediately instead of waiting out a readiness timeout.
     */
    @Synchronized
    fun markProcessGone(session: GeckoSession) {
        processGone.add(session)
        delegated.remove(session)
        ports.remove(session)
        readySessions.remove(session)
        lastReplyAt.remove(session)
        attaching.remove(session)?.cancel()
        failPending(session, PROCESS_GONE_MESSAGE)
    }

    @Synchronized
    fun isProcessGone(session: GeckoSession): Boolean = session in processGone

    /**
     * True when this session had a working bridge that stopped answering, which only
     * close()+open() can fix. A process killed while the app was backgrounded leaves a
     * stale port behind without any disconnect callback, so readiness state alone lies.
     * Sessions that were never attached return false: the normal attach path covers them.
     */
    suspend fun isBridgeStale(session: GeckoSession, timeoutMs: Long = BrowserRuntimeLimits.DEFAULT_BRIDGE_STALE_TIMEOUT_MS): Boolean {
        if (isProcessGone(session)) return true
        if (!session.isOpen) return true
        if (synchronized(this) { session !in delegated } || !isReady(session)) return false
        val idleFor = synchronized(this) { lastReplyAt[session]?.let { SystemClock.uptimeMillis() - it } }
        if (idleFor != null && idleFor < LIVENESS_TRUST_MS) return false
        return runCatching { evaluate(session, "1", timeoutMs) }.isFailure
    }

    fun get(context: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(context.applicationContext).also { runtime = it }

    suspend fun attach(context: Context, session: GeckoSession, reloadIfNeeded: Boolean = true) {
        if (isProcessGone(session)) throw BridgeUnrecoverable(PROCESS_GONE_MESSAGE)
        if (synchronized(this) { session in delegated && isReady(session) }) return

        val inFlight = synchronized(this) { attaching[session] }
        if (inFlight != null) {
            runCatching { inFlight.await() }
        } else if (synchronized(this) { session !in delegated }) {
            // First registration for this session. The caller's own navigation (or the
            // reload it asked for) brings the bridge up; DOM calls wait for readiness
            // themselves, so returning here keeps navigation actions from stalling.
            registerDelegate(context, session, reloadIfNeeded)
            return
        }
        if (!reloadIfNeeded) return

        // Delegated but silent: the document outlived its bridge. Reload once, re-register
        // once. A killed content process answers neither, so report it as unrecoverable
        // instead of retrying forever - only close()+open() can bring that session back.
        if (portFor(session) == null) withContext(Dispatchers.Main.immediate) { session.reload() }
        if (awaitReady(session, READY_TIMEOUT_MS)) return
        if (isProcessGone(session)) throw BridgeUnrecoverable(PROCESS_GONE_MESSAGE)
        synchronized(this) {
            delegated.remove(session)
            ports.remove(session)
            readySessions.remove(session)
        }
        registerDelegate(context, session, reloadIfNeeded = true)
        if (awaitReady(session, RECOVERY_READY_TIMEOUT_MS)) return
        throw BridgeUnrecoverable("Browser bridge did not reconnect")
    }

    private suspend fun registerDelegate(context: Context, session: GeckoSession, reloadIfNeeded: Boolean) {
        val deferred = CompletableDeferred<Unit>()
        synchronized(this) { attaching[session] = deferred }
        try {
            val webExtension = ensureExtension(context)
            withContext(Dispatchers.Main.immediate) {
                session.webExtensionController.setMessageDelegate(webExtension, object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        debugLog("AmayaBrowser") { "bridge connected" }
                        addPort(session, port)
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, source: WebExtension.Port) {
                                val json = message as? JSONObject ?: return
                                if (json.optString("type") == "ready") {
                                    debugLog("AmayaBrowser") { "bridge ready" }
                                    markReady(session, source)
                                } else completePending(json.optString("id"), json)
                            }

                            override fun onDisconnect(source: WebExtension.Port) {
                                Log.w("AmayaBrowser", "bridge disconnected")
                                removePort(session, source)
                            }
                        })
                    }
                }, NATIVE_APP)
                synchronized(this@GeckoBrowserRuntime) { delegated.add(session) }
                // A page loaded before its delegate never reconnects. Reload only when
                // the caller needs DOM access on the existing document. Navigation
                // actions load their target immediately and must not race a second reload.
                if (reloadIfNeeded) session.reload()
            }
            deferred.complete(Unit)
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            // Always clear the in-flight marker; a cancelled caller used to leave a
            // dangling entry that made every later attach skip registration.
            synchronized(this) { if (attaching[session] === deferred) attaching.remove(session) }
        }
    }

    suspend fun awaitReady(session: GeckoSession, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!isReady(session)) {
            if (isProcessGone(session)) return@withTimeoutOrNull false
            kotlinx.coroutines.delay(25)
        }
        true
    } == true

    suspend fun evaluate(session: GeckoSession, script: String, timeoutMs: Long = BrowserRuntimeLimits.DEFAULT_EVALUATION_TIMEOUT_MS): String {
        debugLog("AmayaBrowser") { "evaluate start timeoutMs=$timeoutMs" }
        if (isProcessGone(session)) throw BridgeUnrecoverable(PROCESS_GONE_MESSAGE)
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        addPending(session, id, deferred)
        try {
            val port = withTimeout(timeoutMs) {
                while (true) {
                    if (isProcessGone(session)) throw BridgeUnrecoverable(PROCESS_GONE_MESSAGE)
                    if (isReady(session)) portFor(session)?.let { return@withTimeout it }
                    kotlinx.coroutines.delay(25)
                }
                error("unreachable")
            }
            withContext(Dispatchers.Main.immediate) {
                port.postMessage(JSONObject().put("id", id).put("script", script))
            }
            val reply = withTimeout(timeoutMs) { deferred.await() }
            debugLog("AmayaBrowser") { "evaluate reply" }
            if (!reply.optBoolean("ok")) error(reply.optString("error", "JavaScript evaluation failed"))
            val value = reply.opt("value")
            return if (value == null || value == JSONObject.NULL) "" else if (value is String) value else value.toString()
        } catch (error: Throwable) {
            Log.e("AmayaBrowser", "evaluate failed", error)
            throw error
        } finally {
            removePending(id)
        }
    }

    fun navigationStarted(session: GeckoSession) {
        synchronized(this) {
            ports.remove(session)
            readySessions.remove(session)
            failPending(session, "Browser document navigated")
        }
    }

    fun detach(session: GeckoSession) {
        removeSession(session)
    }

    fun clearHostData(context: Context, host: String) {
        get(context).storageController.clearDataFromHost(host, org.mozilla.geckoview.StorageController.ClearFlags.SITE_DATA)
    }

    private suspend fun ensureExtension(context: Context): WebExtension {
        extension?.let { return it }
        installing?.let { return it.await() }
        val deferred = CompletableDeferred<WebExtension>()
        installing = deferred
        withContext(Dispatchers.Main.immediate) {
            get(context).webExtensionController
                .ensureBuiltIn("resource://android/assets/browser-bridge/", EXTENSION_ID)
                .then<Void>({ value: WebExtension? ->
                    if (value == null) deferred.completeExceptionally(IllegalStateException("Browser bridge installation returned null"))
                    else {
                        extension = value
                        deferred.complete(value)
                    }
                    null
                }, { error: Throwable ->
                    deferred.completeExceptionally(error)
                    null
                })
        }
        return try {
            deferred.await()
        } finally {
            installing = null
        }
    }
}
