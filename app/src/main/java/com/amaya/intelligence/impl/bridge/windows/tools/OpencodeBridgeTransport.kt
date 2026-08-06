package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.domain.bridge.BridgeEnvelope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Narrow surface used by agent clients (opencode, claude-code, codex) to ride
 * on top of the Windows Bridge WebSocket transport.
 *
 * Extracting an interface keeps agent clients unit-testable (`sendEnvelope` +
 * `envelopes` are the only two hooks they need) without knowing about the full
 * [WindowsBridgeController] surface.
 */
interface OpencodeBridgeTransport {
    /** Hot flow of envelopes received from the bridge. */
    val envelopes: SharedFlow<BridgeEnvelope>

    /**
     * Forward a pre-built envelope to the bridge. Returns `false` when the
     * underlying transport is not ready — callers should queue or ignore.
     */
    fun sendEnvelope(envelope: BridgeEnvelope): Boolean
}
