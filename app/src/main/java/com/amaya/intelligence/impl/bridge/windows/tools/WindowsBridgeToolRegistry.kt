package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.tools.ToolDefinition

/**
 * In-memory registry of Windows Bridge tools. Intentionally tiny: lookup by wire name,
 * filter by the `enabledByDefault` flag declared in [WindowsBridgeToolDefinitions], and
 * emit [ToolDefinition] objects shaped exactly like the ones built by the existing
 * `ToolExecutor.getToolDefinitions()`.
 *
 * Phase 3 keeps the registry static. Agent Control / user-toggled overrides are out of
 * scope and will layer on top of this in a later phase.
 */
class WindowsBridgeToolRegistry(
    private val specs: List<WindowsBridgeToolDefinitions.BridgeToolSpec> =
        WindowsBridgeToolDefinitions.all
) {

    private val byName: Map<String, WindowsBridgeToolDefinitions.BridgeToolSpec> =
        specs.associateBy { it.name }

    /** Every spec, including disabled ones. Useful for debug UIs / audit listings. */
    fun allSpecs(): List<WindowsBridgeToolDefinitions.BridgeToolSpec> = specs

    /** Specs that are eligible to run under the Phase 3 safety defaults. */
    fun enabledSpecs(): List<WindowsBridgeToolDefinitions.BridgeToolSpec> =
        specs.filter { it.enabledByDefault }

    /** Names of tools enabled by default. */
    fun enabledNames(): Set<String> = enabledSpecs().map { it.name }.toSet()

    fun find(toolName: String): WindowsBridgeToolDefinitions.BridgeToolSpec? = byName[toolName]

    fun isKnown(toolName: String): Boolean = toolName in byName

    fun isEnabled(toolName: String): Boolean =
        byName[toolName]?.enabledByDefault == true

    /** [ToolDefinition]s for tools enabled under Phase 3 defaults. */
    fun enabledDefinitions(): List<ToolDefinition> =
        enabledSpecs().map { it.toToolDefinition() }

    /** [ToolDefinition]s for every declared tool. */
    fun allDefinitions(): List<ToolDefinition> =
        specs.map { it.toToolDefinition() }
}
