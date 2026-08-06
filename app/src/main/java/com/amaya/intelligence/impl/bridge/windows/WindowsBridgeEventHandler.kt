package com.amaya.intelligence.impl.bridge.windows

/**
 * Optional callback-style surface for consumers that do not want to subscribe to the
 * [WindowsBridgeSessionClient.events] flow directly.
 *
 * Phase 2 only ships the interface. The default [WindowsBridgeEventHandler.NoOp]
 * implementation exists so higher layers can wire progressively without stubbing
 * every callback.
 */
interface WindowsBridgeEventHandler {

    fun onEvent(event: WindowsBridgeClientEvent) {}

    fun onConnectionStateChanged(state: WindowsBridgeConnectionState) {}

    object NoOp : WindowsBridgeEventHandler
}
