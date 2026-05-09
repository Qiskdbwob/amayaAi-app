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
            "the Windows Bridge. Returns image data plus accessibility metadata: coordinate guide, " +
            "display bounds, cursor position, active window, and labeled windows with windowId, state, zIndex, bounds, screenshotBounds, and safe points.",
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
            ),
            ToolParameter(
                "maxWidth", "integer",
                "Optional resize width for the returned screenshot. Coordinate metadata includes scale conversion back to screen coordinates.",
                required = false
            ),
            ToolParameter(
                "includeWindows", "boolean",
                "Include labeled window metadata in the capture result. Default true.",
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

    private val windowClose = BridgeToolSpec(
        name = BridgeToolNames.WINDOW_CLOSE,
        description = "Request close for a top-level window on the paired Windows computer " +
            "by its handle id from window.list. Prefer this over Alt+F4 when a window id is known. " +
            "Requires an active Agent Control session.",
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

    private val appOpen = BridgeToolSpec(
        name = BridgeToolNames.APP_OPEN,
        description = "Open a Windows application by name or safe executable alias. " +
            "Use this when the requested app is not present in window.list; then wait, " +
            "call window.list, focus the new window, and verify with screen.capture.",
        parameters = listOf(
            ToolParameter(
                "app", "string",
                "Application name or safe executable alias, e.g. chrome, msedge, notepad, explorer, taskmgr, calculator, settings, terminal.",
                required = true
            ),
            ToolParameter(
                "args", "string",
                "Optional launch arguments for the app. Keep empty unless explicitly needed.",
                required = false
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
            ),
            ToolParameter(
                "clicks", "integer",
                "Number of clicks: 1 (default) or 2 for double-click.",
                required = false
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val mouseMove = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_MOVE,
        description = "Move the cursor to (x, y) on the paired Windows computer without " +
            "clicking. Use to reveal hover menus or tooltips. Requires Agent Control.",
        parameters = listOf(
            ToolParameter("x", "integer", "X coordinate in pixels.", required = true),
            ToolParameter("y", "integer", "Y coordinate in pixels.", required = true),
            ToolParameter(
                "durationMs", "integer",
                "Movement duration in ms (0 = instant, max 2000). Default 0.",
                required = false
            )
        ),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val mouseScroll = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_SCROLL,
        description = "Scroll at coordinate (x, y) on the paired Windows computer. " +
            "Requires Agent Control.",
        parameters = listOf(
            ToolParameter("x", "integer", "X coordinate to scroll at.", required = true),
            ToolParameter("y", "integer", "Y coordinate to scroll at.", required = true),
            ToolParameter(
                "direction", "string",
                "Scroll direction: 'up', 'down' (default), 'left', or 'right'.",
                required = false,
                enum = listOf("up", "down", "left", "right")
            ),
            ToolParameter(
                "amount", "integer",
                "Number of scroll ticks (1–50, default 3).",
                required = false
            )
        ),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val mouseDrag = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_DRAG,
        description = "Press-hold at (startX, startY), move to (endX, endY), then release " +
            "on the paired Windows computer. Supports optional waypoints for curved paths. " +
            "Requires Agent Control.",
        parameters = listOf(
            ToolParameter("startX", "integer", "Start X coordinate.", required = true),
            ToolParameter("startY", "integer", "Start Y coordinate.", required = true),
            ToolParameter("endX", "integer", "End X coordinate.", required = true),
            ToolParameter("endY", "integer", "End Y coordinate.", required = true),
            ToolParameter(
                "button", "string",
                "Mouse button: 'left' (default), 'right', or 'middle'.",
                required = false,
                enum = listOf("left", "right", "middle")
            ),
            ToolParameter(
                "durationMs", "integer",
                "Total drag duration in ms (50–5000, default 400).",
                required = false
            ),
            ToolParameter(
                "path", "array",
                "Optional intermediate waypoints as [{x, y}] objects for curved drag paths.",
                required = false,
                items = "object"
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val inputWait = BridgeToolSpec(
        name = "input.wait",
        description = "Pause execution for durationMs milliseconds on the Windows Bridge. " +
            "Use to wait for animations, loading spinners, or rate-limited UIs.",
        parameters = listOf(
            ToolParameter(
                "durationMs", "integer",
                "Wait duration in ms (100–10000, default 1000).",
                required = false
            )
        ),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val keyboardType = BridgeToolSpec(
        name = BridgeToolNames.KEYBOARD_TYPE,
        description = "Type the given text on the paired Windows computer. For long or multiline text, the bridge uses clipboard paste internally for reliability and returns only length/mode, never the raw text. Requires an active Agent Control session.",
        parameters = listOf(
            ToolParameter("text", "string", "Text to type or paste. Raw text is not echoed in results.", required = true),
            ToolParameter(
                "mode", "string",
                "Typing mode: auto (default), paste for long/multiline text, or keys for short physical key input.",
                required = false,
                enum = listOf("auto", "paste", "keys")
            )
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
        description = "Run an approved shell command in an allowed working directory on the paired Windows computer. Requires Agent Control and explicit Windows Bridge approval; subject to commandPolicy allowedCommands/blockedCommands.",
        parameters = listOf(
            ToolParameter("command", "string", "Command line to run.", required = true),
            ToolParameter("cwd", "string", "Working directory (must be in allowed list).", required = true),
            ToolParameter("timeoutMs", "integer", "Timeout in ms (default 120000).", required = false)
        ),
        risk = BridgeRiskLevel.HIGH,
        requiresApproval = true,
        category = Category.SHELL,
        enabledByDefault = true
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
        description = "Snapshot a lightweight Windows UI element tree for the active or specified window. Returns elementId, role, name, className, bounds, center, enabled/visible. Use this before coordinate clicking when semantic elements are available.",
        parameters = listOf(
            ToolParameter("windowId", "string", "Optional windowId from screen.capture/window.list. Defaults to active window.", required = false),
            ToolParameter("limit", "integer", "Maximum elements to return (default 250, max 1000).", required = false)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.UI_AUTOMATION,
        enabledByDefault = true
    )

    private val uiFindText = BridgeToolSpec(
        name = BridgeToolNames.UI_FIND_TEXT,
        description = "Find UI elements by visible text or class name on the paired Windows computer. Returns elementIds and bounds for deterministic clicking.",
        parameters = listOf(
            ToolParameter("text", "string", "Text or class fragment to search for.", required = true),
            ToolParameter("windowId", "string", "Optional windowId from screen.capture/window.list. Defaults to active window.", required = false),
            ToolParameter("limit", "integer", "Maximum matches to return (default 50, max 1000).", required = false)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.UI_AUTOMATION,
        enabledByDefault = true
    )

    private val uiClickElement = BridgeToolSpec(
        name = BridgeToolNames.UI_CLICK_ELEMENT,
        description = "Click a UI element by elementId returned from ui.tree or ui.find_text. Prefer this over raw mouse.click when an elementId is known.",
        parameters = listOf(
            ToolParameter("elementId", "string", "Element/window handle id returned by ui.tree or ui.find_text.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.UI_AUTOMATION,
        enabledByDefault = true
    )

    /** All specs declared so far, regardless of `enabledByDefault`. */
    val all: List<BridgeToolSpec> = listOf(
        screenCapture, windowList, windowFocus, windowClose, appOpen,
        mouseClick, mouseMove, mouseScroll, mouseDrag, inputWait,
        keyboardType, keyboardHotkey, clipboardWrite,
        clipboardRead,
        fileList, fileRead, fileWrite, fileEdit, fileDelete,
        shellRun, shellCancel,
        browserOpen, browserGoto, browserDom, browserClick, browserType,
        browserScreenshot,
        uiTree, uiFindText, uiClickElement
    )
}
