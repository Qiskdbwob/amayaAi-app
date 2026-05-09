package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.domain.bridge.BridgeRiskLevel
import com.amaya.intelligence.domain.bridge.BridgeToolNames
import com.amaya.intelligence.tools.ToolDefinition
import com.amaya.intelligence.tools.ToolParameter

/**
 * Catalog of Windows Bridge tools surfaced to the Android agent.
 *
 * Each entry keeps the wire-level tool name from [BridgeToolNames], a risk band, an
 * explicit `enabledByDefault` flag so we never accidentally expose HIGH-risk tools,
 * and a [ToolDefinition] that matches the existing Android tool definition shape used
 * by `ToolExecutor.getToolDefinitions()`.
 *
 * Phase 3 only declares the catalog — no executor logic lives in this file.
 */
object WindowsBridgeToolDefinitions {

    /** Logical grouping used purely for docs / grouping in later UI. */
    enum class Category {
        SCREEN, WINDOW, INPUT, CLIPBOARD, UI_AUTOMATION,
        FILE, SHELL, BROWSER
    }

    data class BridgeToolSpec(
        val name: String,
        val description: String,
        val parameters: List<ToolParameter>,
        val risk: BridgeRiskLevel,
        val requiresApproval: Boolean,
        val category: Category,
        val enabledByDefault: Boolean
    ) {
        fun toToolDefinition(): ToolDefinition = ToolDefinition(
            name = name,
            description = description,
            parameters = parameters
        )
    }

    // ── Enabled in Phase 3 ───────────────────────────────────────────────────

    private val screenCapture = BridgeToolSpec(
        name = BridgeToolNames.SCREEN_CAPTURE,
        description = "Capture a single screenshot of the paired Windows computer via " +
            "the Windows Bridge. Returns base64-encoded image data and dimensions.",
        parameters = listOf(
            ToolParameter(
                "format", "string",
                "Image format: 'png' (default) or 'jpeg'.",
                required = false,
                enum = listOf("png", "jpeg")
            ),
            ToolParameter(
                "displayIndex", "integer",
                "Zero-based display index when multiple monitors are present.",
                required = false
            )
        ),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.SCREEN,
        enabledByDefault = true
    )

    private val windowList = BridgeToolSpec(
        name = BridgeToolNames.WINDOW_LIST,
        description = "List the top-level windows currently open on the paired Windows " +
            "computer via the Windows Bridge.",
        parameters = emptyList(),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.WINDOW,
        enabledByDefault = true
    )

    private val windowFocus = BridgeToolSpec(
        name = BridgeToolNames.WINDOW_FOCUS,
        description = "Bring a top-level window on the paired Windows computer to the " +
            "foreground by its handle id. Requires an active Agent Control session.",
        parameters = listOf(
            ToolParameter(
                "windowId", "string",
                "Window handle id as returned by window.list.",
                required = true
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.WINDOW,
        enabledByDefault = true
    )

    // ── Agent Control gated (enabled by default but guarded by availability) ──

    private val mouseClick = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_CLICK,
        description = "Send a mouse click at the given screen coordinates on the paired " +
            "Windows computer. Requires an active Agent Control session.",
        parameters = listOf(
            ToolParameter("x", "integer", "X coordinate in pixels.", required = true),
            ToolParameter("y", "integer", "Y coordinate in pixels.", required = true),
            ToolParameter(
                "button", "string",
                "Mouse button: 'left' (default), 'right', or 'middle'.",
                required = false,
                enum = listOf("left", "right", "middle")
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val keyboardType = BridgeToolSpec(
        name = BridgeToolNames.KEYBOARD_TYPE,
        description = "Type the given text on the paired Windows computer. Requires an " +
            "active Agent Control session.",
        parameters = listOf(
            ToolParameter("text", "string", "Text to type.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val keyboardHotkey = BridgeToolSpec(
        name = BridgeToolNames.KEYBOARD_HOTKEY,
        description = "Send a keyboard hotkey combination (e.g. 'ctrl+c' or 'ctrl+shift+esc') " +
            "on the paired Windows computer. Requires an active Agent Control session.",
        parameters = listOf(
            ToolParameter(
                "combo", "string",
                "Hotkey combination expressed as '+'-joined tokens, e.g. 'ctrl+shift+s'.",
                required = true
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val clipboardWrite = BridgeToolSpec(
        name = BridgeToolNames.CLIPBOARD_WRITE,
        description = "Write text to the Windows clipboard on the paired computer.",
        parameters = listOf(
            ToolParameter("text", "string", "Text to place on the clipboard.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.CLIPBOARD,
        enabledByDefault = true
    )

    // ── Future: declared but disabled in Phase 3 ─────────────────────────────

    private val clipboardRead = BridgeToolSpec(
        name = BridgeToolNames.CLIPBOARD_READ,
        description = "Read the current clipboard contents on the paired Windows " +
            "computer. Disabled until approval flow is wired in a later phase.",
        parameters = emptyList(),
        risk = BridgeRiskLevel.HIGH,
        requiresApproval = true,
        category = Category.CLIPBOARD,
        enabledByDefault = false
    )

    private val fileList = BridgeToolSpec(
        name = BridgeToolNames.FILE_LIST,
        description = "List files in an allowed Windows folder.",
        parameters = listOf(
            ToolParameter("path", "string", "Absolute directory path to list.", required = true),
            ToolParameter("maxDepth", "integer", "Max recursion depth (0-5, default 1).", required = false),
            ToolParameter("pattern", "string", "Glob pattern to filter (e.g. *.kt).", required = false),
            ToolParameter("limit", "integer", "Max entries (default 200, max 1000).", required = false)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.FILE,
        enabledByDefault = true
    )

    private val fileRead = BridgeToolSpec(
        name = BridgeToolNames.FILE_READ,
        description = "Read a text file from an allowed Windows folder.",
        parameters = listOf(
            ToolParameter("path", "string", "Absolute file path to read.", required = true),
            ToolParameter("startLine", "integer", "Start reading from this line (1-indexed).", required = false),
            ToolParameter("maxLines", "integer", "Max lines to return (default 500).", required = false),
            ToolParameter("maxBytes", "integer", "Max bytes to read.", required = false)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.FILE,
        enabledByDefault = true
    )

    private val fileWrite = BridgeToolSpec(
        name = BridgeToolNames.FILE_WRITE,
        description = "Write a file in an allowed Windows folder. Requires approval and creates backup.",
        parameters = listOf(
            ToolParameter("path", "string", "Absolute file path to write.", required = true),
            ToolParameter("content", "string", "Text content to write.", required = true),
            ToolParameter("mode", "string", "overwrite, append, or create_new.", required = false,
                enum = listOf("overwrite", "append", "create_new"))
        ),
        risk = BridgeRiskLevel.HIGH,
        requiresApproval = true,
        category = Category.FILE,
        enabledByDefault = false
    )

    private val fileDelete = BridgeToolSpec(
        name = BridgeToolNames.FILE_DELETE,
        description = "Move a file to Amaya Bridge trash. Requires approval.",
        parameters = listOf(
            ToolParameter("path", "string", "Absolute path to delete.", required = true)
        ),
        risk = BridgeRiskLevel.HIGH,
        requiresApproval = true,
        category = Category.FILE,
        enabledByDefault = false
    )

    private val fileEdit = BridgeToolSpec(
        name = BridgeToolNames.FILE_EDIT,
        description = "Safely edit a file by exact text replacement. Requires approval and creates backup.",
        parameters = listOf(
            ToolParameter("path", "string", "Absolute file path to edit.", required = true),
            ToolParameter("oldText", "string", "Exact text to find.", required = true),
            ToolParameter("newText", "string", "Replacement text.", required = true),
            ToolParameter("replaceAll", "boolean", "Replace all occurrences (default false).", required = false)
        ),
        risk = BridgeRiskLevel.HIGH,
        requiresApproval = true,
        category = Category.FILE,
        enabledByDefault = false
    )

    private val shellRun = BridgeToolSpec(
        name = BridgeToolNames.SHELL_RUN,
        description = "Run an approved shell command in an allowed working directory. Requires approval.",
        parameters = listOf(
            ToolParameter("command", "string", "Command line to run.", required = true),
            ToolParameter("cwd", "string", "Working directory (must be in allowed list).", required = true),
            ToolParameter("timeoutMs", "integer", "Timeout in ms (default 120000).", required = false)
        ),
        risk = BridgeRiskLevel.HIGH,
        requiresApproval = true,
        category = Category.SHELL,
        enabledByDefault = false
    )

    private val shellCancel = BridgeToolSpec(
        name = BridgeToolNames.SHELL_CANCEL,
        description = "Cancel a shell process started by Amaya Windows Bridge.",
        parameters = listOf(
            ToolParameter("processId", "string", "Process id returned by shell.run.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.SHELL,
        enabledByDefault = true
    )

    private val browserOpen = BridgeToolSpec(
        name = BridgeToolNames.BROWSER_OPEN,
        description = "Open the Windows Bridge controlled browser. Disabled until the " +
            "Windows browser executor ships.",
        parameters = emptyList(),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = true,
        category = Category.BROWSER,
        enabledByDefault = false
    )

    private val browserGoto = BridgeToolSpec(
        name = BridgeToolNames.BROWSER_GOTO,
        description = "Navigate the Windows-side browser to a URL. Disabled in Phase 3.",
        parameters = listOf(ToolParameter("url", "string", "Target URL.", required = true)),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = true,
        category = Category.BROWSER,
        enabledByDefault = false
    )

    private val browserDom = BridgeToolSpec(
        name = BridgeToolNames.BROWSER_DOM,
        description = "Snapshot the DOM of the Windows-side browser page. Disabled in Phase 3.",
        parameters = emptyList(),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = true,
        category = Category.BROWSER,
        enabledByDefault = false
    )

    private val browserClick = BridgeToolSpec(
        name = BridgeToolNames.BROWSER_CLICK,
        description = "Click an element on the Windows-side browser page. Disabled in Phase 3.",
        parameters = listOf(
            ToolParameter("selector", "string", "CSS selector or element id.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = true,
        category = Category.BROWSER,
        enabledByDefault = false
    )

    private val browserType = BridgeToolSpec(
        name = BridgeToolNames.BROWSER_TYPE,
        description = "Type into an element on the Windows-side browser page. Disabled in Phase 3.",
        parameters = listOf(
            ToolParameter("selector", "string", "CSS selector or element id.", required = true),
            ToolParameter("text", "string", "Text to type.", required = true)
        ),
        risk = BridgeRiskLevel.HIGH,
        requiresApproval = true,
        category = Category.BROWSER,
        enabledByDefault = false
    )

    private val browserScreenshot = BridgeToolSpec(
        name = BridgeToolNames.BROWSER_SCREENSHOT,
        description = "Take a screenshot of the Windows-side browser page. Disabled in Phase 3.",
        parameters = emptyList(),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.BROWSER,
        enabledByDefault = false
    )

    private val uiTree = BridgeToolSpec(
        name = BridgeToolNames.UI_TREE,
        description = "Snapshot the UI Automation tree of the focused window. Disabled in Phase 3.",
        parameters = emptyList(),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = true,
        category = Category.UI_AUTOMATION,
        enabledByDefault = false
    )

    private val uiFindText = BridgeToolSpec(
        name = BridgeToolNames.UI_FIND_TEXT,
        description = "Find UI elements by text on the paired Windows computer. Disabled in Phase 3.",
        parameters = listOf(
            ToolParameter("text", "string", "Text to search for.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = true,
        category = Category.UI_AUTOMATION,
        enabledByDefault = false
    )

    private val uiClickElement = BridgeToolSpec(
        name = BridgeToolNames.UI_CLICK_ELEMENT,
        description = "Click a UI Automation element by handle. Disabled in Phase 3.",
        parameters = listOf(
            ToolParameter("handle", "string", "UIA element handle.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = true,
        category = Category.UI_AUTOMATION,
        enabledByDefault = false
    )

    /** All specs declared so far, regardless of `enabledByDefault`. */
    val all: List<BridgeToolSpec> = listOf(
        screenCapture, windowList, windowFocus,
        mouseClick, keyboardType, keyboardHotkey, clipboardWrite,
        clipboardRead,
        fileList, fileRead, fileWrite, fileEdit, fileDelete,
        shellRun, shellCancel,
        browserOpen, browserGoto, browserDom, browserClick, browserType,
        browserScreenshot,
        uiTree, uiFindText, uiClickElement
    )
}
