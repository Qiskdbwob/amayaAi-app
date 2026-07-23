package com.amaya.intelligence.impl.local.browser

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.UUID

object GeckoBrowserRuntime {
    private const val EXTENSION_ID = "browser-bridge@amaya.local"
    private const val NATIVE_APP = "browser_bridge"

    private var runtime: GeckoRuntime? = null
    private var extension: WebExtension? = null
    private var installing: CompletableDeferred<WebExtension>? = null
    private val attaching = mutableMapOf<GeckoSession, CompletableDeferred<Unit>>()
    private val delegated = mutableSetOf<GeckoSession>()
    private val ports = mutableMapOf<GeckoSession, WebExtension.Port>()
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val pendingSessions = mutableMapOf<String, GeckoSession>()
    private val readySessions = mutableSetOf<GeckoSession>()

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
        pendingSessions.remove(id)
        pending.remove(id)?.complete(result)
    }

    @Synchronized
    private fun removePending(id: String) {
        pendingSessions.remove(id)
        pending.remove(id)
    }

    @Synchronized
    private fun markReady(session: GeckoSession, port: WebExtension.Port) {
        if (ports[session] === port) readySessions.add(session)
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
        failPending(session, "Browser session closed")
        attaching.remove(session)?.cancel()
    }

    fun get(context: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(context.applicationContext).also { runtime = it }

    suspend fun attach(context: Context, session: GeckoSession, reloadIfNeeded: Boolean = true) {
        if (synchronized(this) { session in delegated && isReady(session) }) return

        val (deferred, shouldAttach) = synchronized(this) {
            attaching[session]?.let { it to false }
                ?: if (session in delegated) CompletableDeferred<Unit>().also { it.complete(Unit) }.let { it to false }
                else CompletableDeferred<Unit>().also { attaching[session] = it }.let { it to true }
        }
        if (!shouldAttach) {
            if (!reloadIfNeeded && session in delegated) return
            if (portFor(session) == null) {
                withContext(Dispatchers.Main.immediate) { session.reload() }
            }
            if (awaitReady(session, 15_000)) return
            // Screen-off resource suspension can leave Gecko's document resident while
            // its extension delegate no longer reconnects. Re-register once; the next
            // DOM action waits for the fresh port instead of inheriting a dead delegate.
            synchronized(this) {
                delegated.remove(session)
                ports.remove(session)
                readySessions.remove(session)
            }
            attach(context, session, reloadIfNeeded = true)
            return
        }
        try {
            val webExtension = ensureExtension(context)
            withContext(Dispatchers.Main.immediate) {
                session.webExtensionController.setMessageDelegate(webExtension, object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        Log.d("AmayaBrowser", "bridge connected session=$session")
                        addPort(session, port)
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, source: WebExtension.Port) {
                                val json = message as? JSONObject ?: return
                                if (json.optString("type") == "ready") {
                                    Log.d("AmayaBrowser", "bridge ready session=$session")
                                    markReady(session, source)
                                } else completePending(json.optString("id"), json)
                            }

                            override fun onDisconnect(source: WebExtension.Port) {
                                Log.w("AmayaBrowser", "bridge disconnected session=$session")
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
            synchronized(this) { attaching.remove(session) }
            deferred.complete(Unit)
        } catch (error: Throwable) {
            synchronized(this) { attaching.remove(session) }
            deferred.completeExceptionally(error)
            throw error
        }
        deferred.await()
    }

    suspend fun awaitReady(session: GeckoSession, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!isReady(session)) kotlinx.coroutines.delay(25)
        true
    } == true

    suspend fun evaluate(session: GeckoSession, script: String, timeoutMs: Long = 10_000): String {
        Log.d("AmayaBrowser", "evaluate start session=$session timeoutMs=$timeoutMs")
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        addPending(session, id, deferred)
        try {
            val port = withTimeout(timeoutMs) {
                while (true) {
                    if (isReady(session)) portFor(session)?.let { return@withTimeout it }
                    kotlinx.coroutines.delay(25)
                }
                error("unreachable")
            }
            withContext(Dispatchers.Main.immediate) {
                port.postMessage(JSONObject().put("id", id).put("script", script))
            }
            val reply = withTimeout(timeoutMs) { deferred.await() }
            Log.d("AmayaBrowser", "evaluate reply session=$session id=$id")
            if (!reply.optBoolean("ok")) error(reply.optString("error", "JavaScript evaluation failed"))
            val value = reply.opt("value")
            return if (value == null || value == JSONObject.NULL) "" else if (value is String) value else value.toString()
        } catch (error: Throwable) {
            Log.e("AmayaBrowser", "evaluate failed session=$session id=$id", error)
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
