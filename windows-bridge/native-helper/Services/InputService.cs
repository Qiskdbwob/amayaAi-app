using System.Runtime.InteropServices;
using AmayaBridgeHelper.Windows;
using static AmayaBridgeHelper.Windows.NativeMethods;

namespace AmayaBridgeHelper.Services;

internal static class InputService
{
    private const int MaxTypeLength = 5000;
    private const int MaxHotkeyKeys = 4;

    // ── mouse ────────────────────────────────────────────────────────────────

    public static (bool Ok, string? Reason) Click(int x, int y, MouseButton button, int clicks)
    {
        if (clicks < 1 || clicks > 2) return (false, "clicks must be 1 or 2");

        var (sx, sy, sw, sh) = ScreenInfoService.VirtualScreenBounds();
        if (x < sx || y < sy || x >= sx + sw || y >= sy + sh)
        {
            return (false, "coordinate outside virtual screen bounds");
        }

        if (!SetCursorPos(x, y)) return (false, "SetCursorPos failed");

        uint down, up;
        switch (button)
        {
            case MouseButton.Right:
                down = MOUSEEVENTF_RIGHTDOWN; up = MOUSEEVENTF_RIGHTUP; break;
            case MouseButton.Middle:
                down = MOUSEEVENTF_MIDDLEDOWN; up = MOUSEEVENTF_MIDDLEUP; break;
            default:
                down = MOUSEEVENTF_LEFTDOWN; up = MOUSEEVENTF_LEFTUP; break;
        }

        var inputs = new INPUT[clicks * 2];
        for (int i = 0; i < clicks; i++)
        {
            inputs[i * 2] = new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = down } } };
            inputs[i * 2 + 1] = new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = up } } };
        }
        uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        if (sent == 0) return (false, "SendInput returned 0");
        return (true, null);
    }

    // ── keyboard.type ────────────────────────────────────────────────────────

    public static (bool Ok, string? Reason, int Length) Type(string? text, int intervalMs)
    {
        if (text is null) return (false, "text is required", 0);
        if (text.Length == 0) return (true, null, 0);
        if (text.Length > MaxTypeLength) return (false, $"text exceeds {MaxTypeLength} chars", 0);
        if (intervalMs < 0 || intervalMs > 100)
            return (false, "intervalMs must be between 0 and 100", 0);

        foreach (var ch in text)
        {
            SendUnicodeChar(ch);
            if (intervalMs > 0) Thread.Sleep(intervalMs);
        }
        return (true, null, text.Length);
    }

    private static void SendUnicodeChar(char ch)
    {
        var inputs = new INPUT[2];
        inputs[0] = new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT { wVk = 0, wScan = ch, dwFlags = KEYEVENTF_UNICODE }
            }
        };
        inputs[1] = new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT { wVk = 0, wScan = ch, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP }
            }
        };
        _ = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
    }

    // ── keyboard.hotkey ──────────────────────────────────────────────────────

    public static (bool Ok, string? Reason, IReadOnlyList<string> Keys) Hotkey(IReadOnlyList<string>? keys)
    {
        if (keys is null || keys.Count == 0) return (false, "keys is required", Array.Empty<string>());
        if (keys.Count > MaxHotkeyKeys) return (false, $"keys exceeds {MaxHotkeyKeys}", keys);

        var codes = new List<ushort>(keys.Count);
        foreach (var raw in keys)
        {
            if (!HotkeyMap.TryResolve(raw, out var vk))
            {
                return (false, $"unknown key: {raw}", keys);
            }
            codes.Add(vk);
        }

        // Press in order, release in reverse order.
        var down = new INPUT[codes.Count];
        var up = new INPUT[codes.Count];
        for (int i = 0; i < codes.Count; i++)
        {
            down[i] = new INPUT
            {
                type = INPUT_KEYBOARD,
                U = new InputUnion { ki = new KEYBDINPUT { wVk = codes[i], dwFlags = 0 } }
            };
        }
        for (int i = 0; i < codes.Count; i++)
        {
            int src = codes.Count - 1 - i;
            up[i] = new INPUT
            {
                type = INPUT_KEYBOARD,
                U = new InputUnion { ki = new KEYBDINPUT { wVk = codes[src], dwFlags = KEYEVENTF_KEYUP } }
            };
        }

        uint sentDown = SendInput((uint)down.Length, down, Marshal.SizeOf<INPUT>());
        uint sentUp = SendInput((uint)up.Length, up, Marshal.SizeOf<INPUT>());
        if (sentDown == 0 || sentUp == 0) return (false, "SendInput returned 0", keys);
        return (true, null, keys);
    }
}

internal static class HotkeyMap
{
    private static readonly Dictionary<string, ushort> Map = BuildMap();

    public static bool TryResolve(string raw, out ushort vk)
    {
        var key = (raw ?? string.Empty).Trim().ToUpperInvariant();
        return Map.TryGetValue(key, out vk);
    }

    private static Dictionary<string, ushort> BuildMap()
    {
        var map = new Dictionary<string, ushort>
        {
            ["CTRL"] = 0x11,   // VK_CONTROL
            ["CONTROL"] = 0x11,
            ["LCTRL"] = 0xA2,
            ["LCONTROL"] = 0xA2,
            ["RCTRL"] = 0xA3,
            ["RCONTROL"] = 0xA3,
            ["SHIFT"] = 0x10,
            ["LSHIFT"] = 0xA0,
            ["RSHIFT"] = 0xA1,
            ["ALT"] = 0x12,    // VK_MENU
            ["OPTION"] = 0x12,
            ["LALT"] = 0xA4,
            ["RALT"] = 0xA5,
            ["WIN"] = 0x5B,    // VK_LWIN
            ["WINDOWS"] = 0x5B,
            ["META"] = 0x5B,
            ["CMD"] = 0x5B,
            ["COMMAND"] = 0x5B,
            ["LWIN"] = 0x5B,
            ["RWIN"] = 0x5C,
            ["ENTER"] = 0x0D,
            ["RETURN"] = 0x0D,
            ["TAB"] = 0x09,
            ["ESC"] = 0x1B,
            ["ESCAPE"] = 0x1B,
            ["BACKSPACE"] = 0x08,
            ["BKSP"] = 0x08,
            ["DELETE"] = 0x2E,
            ["DEL"] = 0x2E,
            ["INSERT"] = 0x2D,
            ["INS"] = 0x2D,
            ["SPACE"] = 0x20,
            ["SPACEBAR"] = 0x20,
            ["HOME"] = 0x24,
            ["END"] = 0x23,
            ["PAGEUP"] = 0x21,
            ["PAGE_UP"] = 0x21,
            ["PGUP"] = 0x21,
            ["PAGEDOWN"] = 0x22,
            ["PAGE_DOWN"] = 0x22,
            ["PGDN"] = 0x22,
            ["ARROW_UP"] = 0x26,
            ["UP"] = 0x26,
            ["ARROW_DOWN"] = 0x28,
            ["DOWN"] = 0x28,
            ["ARROW_LEFT"] = 0x25,
            ["LEFT"] = 0x25,
            ["ARROW_RIGHT"] = 0x27,
            ["RIGHT"] = 0x27,
            ["PRINTSCREEN"] = 0x2C,
            ["PRINT_SCREEN"] = 0x2C,
            ["PRTSC"] = 0x2C,
            ["SCROLLLOCK"] = 0x91,
            ["SCROLL_LOCK"] = 0x91,
            ["PAUSE"] = 0x13,
            ["BREAK"] = 0x13,
            ["CAPSLOCK"] = 0x14,
            ["CAPS_LOCK"] = 0x14,
            ["NUMLOCK"] = 0x90,
            ["NUM_LOCK"] = 0x90,
            ["APPS"] = 0x5D,
            ["MENU"] = 0x5D,
            ["CONTEXTMENU"] = 0x5D,
            ["CONTEXT_MENU"] = 0x5D,
            ["SEMICOLON"] = 0xBA,
            [";"] = 0xBA,
            ["EQUAL"] = 0xBB,
            ["EQUALS"] = 0xBB,
            ["="] = 0xBB,
            ["PLUS"] = 0xBB,
            ["+"] = 0xBB,
            ["COMMA"] = 0xBC,
            [","] = 0xBC,
            ["MINUS"] = 0xBD,
            ["-"] = 0xBD,
            ["PERIOD"] = 0xBE,
            ["DOT"] = 0xBE,
            ["."] = 0xBE,
            ["SLASH"] = 0xBF,
            ["/"] = 0xBF,
            ["BACKTICK"] = 0xC0,
            ["GRAVE"] = 0xC0,
            ["`"] = 0xC0,
            ["LBRACKET"] = 0xDB,
            ["LEFTBRACKET"] = 0xDB,
            ["["] = 0xDB,
            ["BACKSLASH"] = 0xDC,
            ["\\"] = 0xDC,
            ["RBRACKET"] = 0xDD,
            ["RIGHTBRACKET"] = 0xDD,
            ["]"] = 0xDD,
            ["QUOTE"] = 0xDE,
            ["APOSTROPHE"] = 0xDE,
            ["'"] = 0xDE
        };
        for (char c = 'A'; c <= 'Z'; c++) map[c.ToString()] = (ushort)c;
        for (char c = '0'; c <= '9'; c++) map[c.ToString()] = (ushort)c;
        for (int i = 1; i <= 24; i++) map[$"F{i}"] = (ushort)(0x70 + i - 1);
        for (int i = 0; i <= 9; i++) map[$"NUMPAD{i}"] = (ushort)(0x60 + i);
        map["NUMPAD_MULTIPLY"] = 0x6A;
        map["NUMPAD*"] = 0x6A;
        map["NUMPAD_ADD"] = 0x6B;
        map["NUMPAD+"] = 0x6B;
        map["NUMPAD_SUBTRACT"] = 0x6D;
        map["NUMPAD-"] = 0x6D;
        map["NUMPAD_DECIMAL"] = 0x6E;
        map["NUMPAD."] = 0x6E;
        map["NUMPAD_DIVIDE"] = 0x6F;
        map["NUMPAD/"] = 0x6F;
        return map;
    }
}
