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
        description = "Capture the paired Windows computer screen in PHYSICAL pixels. " +
            "mode=display (default) captures a monitor; mode=window captures a specific windowId " +
            "even when it is partially covered by other windows; mode=region crops a rectangle " +
            "(use to verify an action locally). Output includes accessibility metadata: " +
            "displayBounds, virtualBounds, workArea, dpi, imageToScreenScale, screenToImageScale, " +
            "cursor {x,y,imageX,imageY,onCaptured}, windows[] (windowId, title, processName, " +
            "state, focused, focusable, zIndex, bounds, clientBounds, imageBounds, overlapRatio, " +
            "overlappedBy, center, titleBarPoint, closeButtonPoint), activeWindow, " +
            "recommendedWindowId (pass to focusWindowId on mouse.* tools), coordinateGuide " +
            "with imageToScreenFormula / screenToImageFormula, and hints{focus,verify,coordinates}.",
        parameters = listOf(
            ToolParameter(
                "mode", "string",
                "Capture mode: 'display' (default), 'window', or 'region'.",
                required = false,
                enum = listOf("display", "window", "region")
            ),
            ToolParameter(
                "windowId", "string",
                "Required when mode=window. Window handle id returned by window.list / screen.capture accessibility.windows[].",
                required = false
            ),
            ToolParameter(
                "region", "object",
                "Required when mode=region. {x,y,width,height} in physical pixels on the virtual screen.",
                required = false
            ),
            ToolParameter(
                "format", "string",
                "Image format: 'png' (default) or 'jpeg'.",
                required = false,
                enum = listOf("png", "jpeg")
            ),
            ToolParameter(
                "quality", "integer",
                "JPEG quality (1-100). Ignored for PNG.",
                required = false
            ),
            ToolParameter(
                "displayIndex", "integer",
                "Zero-based display index when mode=display and multiple monitors are present.",
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
            ),
            ToolParameter(
                "includeCursor", "boolean",
                "Include cursor position metadata. Default true.",
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
        description = "Click at screen coordinates (x, y) in physical pixels on the paired " +
            "Windows computer. Supports modifiers (e.g. 'ctrl+shift'), focusWindowId to force " +
            "the target window foreground first, and relativeToWindowId + clientX + clientY for " +
            "window-relative clicks that survive window moves between capture and click. " +
            "Returns cursor, foregroundWindow, and hitWindow for post-action verification. " +
            "Requires an active Agent Control session.",
        parameters = listOf(
            ToolParameter("x", "integer", "Screen X in physical pixels. Omit when using relativeToWindowId + clientX.", required = false),
            ToolParameter("y", "integer", "Screen Y in physical pixels. Omit when using relativeToWindowId + clientY.", required = false),
            ToolParameter(
                "button", "string",
                "Mouse button: 'left' (default), 'right', or 'middle'.",
                required = false,
                enum = listOf("left", "right", "middle")
            ),
            ToolParameter(
                "clicks", "integer",
                "Number of clicks: 1 (default), 2 for double, 3 for triple (selects paragraph).",
                required = false
            ),
            ToolParameter(
                "modifiers", "string",
                "Modifier keys held during the click, e.g. 'ctrl+shift', 'alt', 'ctrl'. Comma or + separated.",
                required = false
            ),
            ToolParameter(
                "focusWindowId", "string",
                "If provided, the helper activates this window to foreground before sending the click. Pass accessibility.recommendedWindowId from the latest screen.capture.",
                required = false
            ),
            ToolParameter(
                "relativeToWindowId", "string",
                "If provided with clientX + clientY, the helper converts client-area coordinates to screen coordinates via ClientToScreen. Use this when the target window may be moved between screen.capture and mouse.click.",
                required = false
            ),
            ToolParameter("clientX", "integer", "Client-relative X in physical pixels (pair with relativeToWindowId + clientY).", required = false),
            ToolParameter("clientY", "integer", "Client-relative Y in physical pixels (pair with relativeToWindowId + clientX).", required = false)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val mousePress = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_PRESS,
        description = "Press a mouse button at (x, y) without releasing. Pair with mouse.release " +
            "for spreadsheet-style drag select, tight modifier-locked drags, or games that need a held button.",
        parameters = listOf(
            ToolParameter("x", "integer", "Screen X in physical pixels.", required = true),
            ToolParameter("y", "integer", "Screen Y in physical pixels.", required = true),
            ToolParameter(
                "button", "string",
                "Mouse button: 'left' (default), 'right', or 'middle'.",
                required = false,
                enum = listOf("left", "right", "middle")
            ),
            ToolParameter(
                "focusWindowId", "string",
                "Force this window to foreground before pressing.",
                required = false
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val mouseRelease = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_RELEASE,
        description = "Release the mouse button previously pressed via mouse.press. Always pair " +
            "press/release to avoid leaving the host in a pressed-button state.",
        parameters = listOf(
            ToolParameter(
                "button", "string",
                "Which button to release: 'left' (default), 'right', or 'middle'. Must match the button passed to mouse.press.",
                required = false,
                enum = listOf("left", "right", "middle")
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val mouseHover = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_HOVER,
        description = "Move the cursor to (x, y) and hold it there for holdMs so tooltips and " +
            "hover menus become visible before the next action. No click.",
        parameters = listOf(
            ToolParameter("x", "integer", "Screen X in physical pixels.", required = true),
            ToolParameter("y", "integer", "Screen Y in physical pixels.", required = true),
            ToolParameter(
                "holdMs", "integer",
                "Hover duration in ms (0-5000, default 400).",
                required = false
            ),
            ToolParameter(
                "focusWindowId", "string",
                "Force this window to foreground before hovering.",
                required = false
            )
        ),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val mouseMove = BridgeToolSpec(
        name = BridgeToolNames.MOUSE_MOVE,
        description = "Move the cursor to (x, y) on the paired Windows computer without " +
            "clicking. Use to reveal hover menus or tooltips. Requires Agent Control.",
        parameters = listOf(
            ToolParameter("x", "integer", "X coordinate in physical pixels.", required = true),
            ToolParameter("y", "integer", "Y coordinate in physical pixels.", required = true),
            ToolParameter(
                "durationMs", "integer",
                "Movement duration in ms (0 = instant, max 2000). Default 0.",
                required = false
            ),
            ToolParameter(
                "focusWindowId", "string",
                "Optional: force this window to foreground before the move.",
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
        description = "Scroll at coordinate (x, y) on the paired Windows computer. Uses " +
            "SendInput plus a PostMessage(WM_MOUSEWHEEL) fallback so DirectManipulation / " +
            "WebView2 surfaces also receive the event. Requires Agent Control.",
        parameters = listOf(
            ToolParameter("x", "integer", "X coordinate to scroll at (physical pixels).", required = true),
            ToolParameter("y", "integer", "Y coordinate to scroll at (physical pixels).", required = true),
            ToolParameter(
                "direction", "string",
                "Scroll direction: 'up', 'down' (default), 'left', or 'right'.",
                required = false,
                enum = listOf("up", "down", "left", "right")
            ),
            ToolParameter(
                "amount", "integer",
                "Number of scroll ticks (1-50, default 3).",
                required = false
            ),
            ToolParameter(
                "focusWindowId", "string",
                "Optional: force this window to foreground before scrolling. Strongly recommended for Chromium / Electron.",
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
            ToolParameter("startX", "integer", "Start X coordinate (physical pixels).", required = true),
            ToolParameter("startY", "integer", "Start Y coordinate (physical pixels).", required = true),
            ToolParameter("endX", "integer", "End X coordinate (physical pixels).", required = true),
            ToolParameter("endY", "integer", "End Y coordinate (physical pixels).", required = true),
            ToolParameter(
                "button", "string",
                "Mouse button: 'left' (default), 'right', or 'middle'.",
                required = false,
                enum = listOf("left", "right", "middle")
            ),
            ToolParameter(
                "durationMs", "integer",
                "Total drag duration in ms (50-5000, default 400).",
                required = false
            ),
            ToolParameter(
                "path", "array",
                "Optional intermediate waypoints as [{x, y}] objects for curved drag paths.",
                required = false,
                items = "object"
            ),
            ToolParameter(
                "focusWindowId", "string",
                "Optional: force this window to foreground before dragging.",
                required = false
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

    private val keyboardHold = BridgeToolSpec(
        name = BridgeToolNames.KEYBOARD_HOLD,
        description = "Press a single key down, wait durationMs, then release. Equivalent to " +
            "Claude computer_use hold_key. Useful for games and selection-with-modifier.",
        parameters = listOf(
            ToolParameter(
                "key", "string",
                "Key name (e.g. 'shift', 'a', 'f5', 'pagedown', 'arrow_left').",
                required = true
            ),
            ToolParameter(
                "durationMs", "integer",
                "Hold duration in ms (10-10000, default 200).",
                required = false
            )
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.INPUT,
        enabledByDefault = true
    )

    private val diagnostics = BridgeToolSpec(
        name = BridgeToolNames.DIAGNOSTICS,
        description = "One-shot snapshot of helper capabilities: DPI context, system DPI, " +
            "elevation status, OS version, virtual screen bounds, and Windows.Graphics.Capture / " +
            "PrintWindow availability. Call once per session so the agent can refuse tasks that " +
            "require elevation or a secure desktop.",
        parameters = emptyList(),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.SCREEN,
        enabledByDefault = true
    )

    private val uiHitTest = BridgeToolSpec(
        name = BridgeToolNames.UI_HIT_TEST,
        description = "Return the top-level window at a screen coordinate (physical pixels). " +
            "Use after computing a click target from screen.capture to verify the right window " +
            "is under (x, y) before committing a click. Also returns clientX/clientY inside the " +
            "top-level window, which you can feed back into mouse.click as relativeToWindowId + " +
            "clientX + clientY for robust click that survives window moves.",
        parameters = listOf(
            ToolParameter("x", "integer", "Screen X in physical pixels.", required = true),
            ToolParameter("y", "integer", "Screen Y in physical pixels.", required = true)
        ),
        risk = BridgeRiskLevel.LOW,
        requiresApproval = false,
        category = Category.UI_AUTOMATION,
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
        description = "LEGACY: Dump the child HWND tree of a Win32 window. Works reliably only " +
            "for classic Win32 apps (Notepad, File Explorer, installers, some WinForms/WPF). " +
            "Modern apps (Chromium, Electron, UWP, WinUI, DirectX) expose almost nothing here — " +
            "prefer screen.capture + mouse.click + ui.hit_test instead. Disabled by default; the " +
            "Windows Bridge only surfaces this tool when features.legacyUiToolsEnabled is true.",
        parameters = listOf(
            ToolParameter("windowId", "string", "Optional windowId from screen.capture/window.list. Defaults to active window.", required = false),
            ToolParameter("limit", "integer", "Maximum elements to return (default 250, max 1000).", required = false)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.UI_AUTOMATION,
        enabledByDefault = false
    )

    private val uiFindText = BridgeToolSpec(
        name = BridgeToolNames.UI_FIND_TEXT,
        description = "LEGACY: Find child HWNDs whose title/class contains the query. Same " +
            "Win32-only caveats as ui.tree. Prefer screen.capture + visual matching + mouse.click. " +
            "Disabled by default.",
        parameters = listOf(
            ToolParameter("text", "string", "Text or class fragment to search for.", required = true),
            ToolParameter("windowId", "string", "Optional windowId from screen.capture/window.list. Defaults to active window.", required = false),
            ToolParameter("limit", "integer", "Maximum matches to return (default 50, max 1000).", required = false)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.UI_AUTOMATION,
        enabledByDefault = false
    )

    private val uiClickElement = BridgeToolSpec(
        name = BridgeToolNames.UI_CLICK_ELEMENT,
        description = "LEGACY: Click a UI element by elementId returned from ui.tree or " +
            "ui.find_text. Disabled by default.",
        parameters = listOf(
            ToolParameter("elementId", "string", "Element/window handle id returned by ui.tree or ui.find_text.", required = true)
        ),
        risk = BridgeRiskLevel.MEDIUM,
        requiresApproval = false,
        category = Category.UI_AUTOMATION,
        enabledByDefault = false
    )

    /** All specs declared so far, regardless of `enabledByDefault`. */
    val all: List<BridgeToolSpec> = listOf(
        screenCapture, windowList, windowFocus, windowClose, appOpen,
        mouseClick, mouseMove, mouseScroll, mouseDrag,
        mousePress, mouseRelease, mouseHover,
        inputWait,
        keyboardType, keyboardHotkey, keyboardHold,
        clipboardWrite, clipboardRead,
        fileList, fileRead, fileWrite, fileEdit, fileDelete,
        shellRun, shellCancel,
        browserOpen, browserGoto, browserDom, browserClick, browserType,
        browserScreenshot,
        uiHitTest, diagnostics,
        uiTree, uiFindText, uiClickElement
    )
}
