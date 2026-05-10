package com.amaya.intelligence.domain.bridge

/**
 * Stable tool-name constants used as the [BridgeToolCall.tool] value.
 *
 * Phase 1 only declares these names so that Android-side planners and future Windows
 * Bridge executors agree on a vocabulary. No executor logic lives here — see the
 * respective Android/Windows runtime modules for actual implementations.
 */
object BridgeToolNames {
    // Screen
    const val SCREEN_CAPTURE = "screen.capture"
    const val SCREEN_STREAM_START = "screen.stream.start"
    const val SCREEN_STREAM_STOP = "screen.stream.stop"

    // Windows
    const val WINDOW_LIST = "window.list"
    const val WINDOW_FOCUS = "window.focus"
    const val WINDOW_CLOSE = "window.close"
    const val APP_OPEN = "app.open"

    // Mouse
    const val MOUSE_MOVE = "mouse.move"
    const val MOUSE_CLICK = "mouse.click"
    const val MOUSE_DOUBLE_CLICK = "mouse.double_click"
    const val MOUSE_DRAG = "mouse.drag"
    const val MOUSE_SCROLL = "mouse.scroll"
    const val MOUSE_PRESS = "mouse.press"
    const val MOUSE_RELEASE = "mouse.release"
    const val MOUSE_HOVER = "mouse.hover"

    // Keyboard
    const val KEYBOARD_TYPE = "keyboard.type"
    const val KEYBOARD_PRESS = "keyboard.press"
    const val KEYBOARD_HOTKEY = "keyboard.hotkey"
    const val KEYBOARD_HOLD = "keyboard.hold"

    // Clipboard
    const val CLIPBOARD_READ = "clipboard.read"
    const val CLIPBOARD_WRITE = "clipboard.write"

    // UI automation
    const val UI_TREE = "ui.tree"
    const val UI_FIND_TEXT = "ui.find_text"
    const val UI_CLICK_ELEMENT = "ui.click_element"
    const val UI_HIT_TEST = "ui.hit_test"

    // Diagnostics
    const val DIAGNOSTICS = "diagnostics"

    // Files
    const val FILE_LIST = "file.list"
    const val FILE_READ = "file.read"
    const val FILE_WRITE = "file.write"
    const val FILE_EDIT = "file.edit"
    const val FILE_MOVE = "file.move"
    const val FILE_DELETE = "file.delete"

    // Shell
    const val SHELL_RUN = "shell.run"
    const val SHELL_CANCEL = "shell.cancel"

    // Browser
    const val BROWSER_OPEN = "browser.open"
    const val BROWSER_GOTO = "browser.goto"
    const val BROWSER_DOM = "browser.dom"
    const val BROWSER_CLICK = "browser.click"
    const val BROWSER_TYPE = "browser.type"
    const val BROWSER_SCREENSHOT = "browser.screenshot"

    /** All declared tool names, useful for validation / routing tables. */
    val all: Set<String> = setOf(
        SCREEN_CAPTURE, SCREEN_STREAM_START, SCREEN_STREAM_STOP,
        WINDOW_LIST, WINDOW_FOCUS, WINDOW_CLOSE, APP_OPEN,
        MOUSE_MOVE, MOUSE_CLICK, MOUSE_DOUBLE_CLICK, MOUSE_DRAG, MOUSE_SCROLL,
        MOUSE_PRESS, MOUSE_RELEASE, MOUSE_HOVER,
        KEYBOARD_TYPE, KEYBOARD_PRESS, KEYBOARD_HOTKEY, KEYBOARD_HOLD,
        CLIPBOARD_READ, CLIPBOARD_WRITE,
        UI_TREE, UI_FIND_TEXT, UI_CLICK_ELEMENT, UI_HIT_TEST,
        DIAGNOSTICS,
        FILE_LIST, FILE_READ, FILE_WRITE, FILE_EDIT, FILE_MOVE, FILE_DELETE,
        SHELL_RUN, SHELL_CANCEL,
        BROWSER_OPEN, BROWSER_GOTO, BROWSER_DOM, BROWSER_CLICK, BROWSER_TYPE,
        BROWSER_SCREENSHOT
    )
}
