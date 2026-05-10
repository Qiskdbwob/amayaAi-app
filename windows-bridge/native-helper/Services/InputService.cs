using System.Runtime.InteropServices;
using AmayaBridgeHelper.Windows;
using static AmayaBridgeHelper.Windows.NativeMethods;

namespace AmayaBridgeHelper.Services;

internal static class InputService
{
    private const int MaxTypeLength = 5000;
    private const int MaxHotkeyKeys = 4;

    /// <summary>
    /// Returned when the helper refuses to send input because the target window is at
    /// a higher integrity level than the helper process (UIPI would silently drop it).
    /// The caller should map this to <c>HelperErrorCode.PermissionDenied</c>.
    /// </summary>
    public const string UipiBlockedReason = "uipi_blocked";

    /// <summary>
    /// Decide whether a planned input op targets a higher-integrity window.
    /// Priority:
    ///   1. If caller passed focusWindowId, check that window.
    ///   2. Else check the window currently at (x, y).
    ///   3. Else check the foreground window.
    /// Returns null when nothing to block; otherwise a human-readable reason.
    /// </summary>
    private static string? CheckUipi(string? focusWindowId, int? x, int? y)
    {
        IntPtr target = IntPtr.Zero;
        if (!string.IsNullOrWhiteSpace(focusWindowId) && long.TryParse(focusWindowId, out var handleValue))
        {
            target = new IntPtr(handleValue);
        }
        else if (x is not null && y is not null)
        {
            target = NativeMethods.WindowFromPoint(new NativeMethods.POINT { X = x.Value, Y = y.Value });
            if (target != IntPtr.Zero)
            {
                var root = NativeMethods.GetAncestor(target, NativeMethods.GA_ROOT);
                if (root != IntPtr.Zero) target = root;
            }
        }
        else
        {
            target = NativeMethods.GetForegroundWindow();
        }
        if (target == IntPtr.Zero) return null;
        if (!IntegrityService.WouldBeBlockedByUipi(target)) return null;
        var label = IntegrityService.LabelForWindow(target);
        return UipiBlockedReason + ": target window runs at " + label +
            " integrity but the helper is " + IntegrityService.SelfIntegrity +
            ". Injected input is silently dropped by Windows UIPI. Relaunch Amaya Windows Bridge as Administrator to control this app.";
    }

    // ── mouse ────────────────────────────────────────────────────────────────

    public static (bool Ok, string? Reason) Click(
        int x, int y, MouseButton button, int clicks,
        string? focusWindowId = null,
        string? modifiers = null)
    {
        if (clicks < 1 || clicks > 3) return (false, "clicks must be 1, 2, or 3");

        var (sx, sy, sw, sh) = ScreenInfoService.VirtualScreenBounds();
        if (x < sx || y < sy || x >= sx + sw || y >= sy + sh)
        {
            return (false, "coordinate outside virtual screen bounds");
        }

        var uipi = CheckUipi(focusWindowId, x, y);
        if (uipi is not null) return (false, uipi);

        if (!string.IsNullOrWhiteSpace(focusWindowId))
        {
            // Best effort; do not fail the click if the focus step fails — many
            // clicks work fine even without a dedicated focus change.
            _ = WindowService.Focus(focusWindowId);
            Thread.Sleep(60);
        }

        // Modifiers (ctrl, shift, alt, win) pressed before the click and released after.
        var modifierVks = ParseModifierKeys(modifiers);
        foreach (var vk in modifierVks)
        {
            SendKey(vk, keyUp: false);
        }

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

        // Send the absolute move and click in one SendInput batch. This avoids
        // relying on SetCursorPos, which can fail on some Windows desktops even
        // though injected mouse input is still accepted.
        var inputs = new INPUT[1 + clicks * 2];
        inputs[0] = AbsoluteMoveInput(x, y);
        for (int i = 0; i < clicks; i++)
        {
            int offset = 1 + i * 2;
            inputs[offset] = new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = down } } };
            inputs[offset + 1] = new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = up } } };
        }
        uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());

        // Always release modifiers, even if the click failed, to avoid sticky keys.
        foreach (var vk in modifierVks.AsEnumerable().Reverse())
        {
            SendKey(vk, keyUp: true);
        }

        if (sent != inputs.Length) return (false, "SendInput sent fewer mouse events than requested");
        return (true, null);
    }

    private static IReadOnlyList<ushort> ParseModifierKeys(string? modifiers)
    {
        if (string.IsNullOrWhiteSpace(modifiers)) return Array.Empty<ushort>();
        var parts = modifiers.Split(new[] { '+', ',', ' ' }, StringSplitOptions.RemoveEmptyEntries);
        var result = new List<ushort>(parts.Length);
        foreach (var raw in parts)
        {
            if (HotkeyMap.TryResolve(raw, out var vk))
            {
                result.Add(vk);
            }
        }
        return result;
    }

    private static void SendKey(ushort vk, bool keyUp)
    {
        var input = new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = 0, dwFlags = keyUp ? KEYEVENTF_KEYUP : 0 } } };
        SendInput(1, [input], Marshal.SizeOf<INPUT>());
    }

    /// <summary>
    /// Move the cursor to (x, y) without clicking.
    /// durationMs=0 means instant; otherwise the cursor is interpolated over
    /// the given duration using ~60 Hz steps.
    /// </summary>
    public static (bool Ok, string? Reason) Move(int x, int y, int durationMs, string? focusWindowId = null)
    {
        var (sx, sy, sw, sh) = ScreenInfoService.VirtualScreenBounds();
        if (x < sx || y < sy || x >= sx + sw || y >= sy + sh)
            return (false, "coordinate outside virtual screen bounds");

        // Movement without a click is allowed into higher-integrity windows
        // (SetCursorPos works across integrity) so no UIPI check here.

        if (!string.IsNullOrWhiteSpace(focusWindowId))
        {
            _ = WindowService.Focus(focusWindowId);
            Thread.Sleep(40);
        }

        if (durationMs <= 0)
        {
            if (!TryMoveCursor(x, y)) return (false, "cursor move failed");
            return (true, null);
        }

        // Smooth move: interpolate from current position over durationMs.
        GetCursorPos(out var origin);
        int steps = Math.Max(1, durationMs / 16); // ~60 fps
        int sleepMs = Math.Max(1, durationMs / steps);
        for (int i = 1; i <= steps; i++)
        {
            double t = (double)i / steps;
            int cx = origin.X + (int)Math.Round((x - origin.X) * t);
            int cy = origin.Y + (int)Math.Round((y - origin.Y) * t);
            _ = TryMoveCursor(cx, cy);
            if (i < steps) Thread.Sleep(sleepMs);
        }
        if (!TryMoveCursor(x, y)) return (false, "cursor move failed"); // ensure exact final position
        return (true, null);
    }

    /// <summary>
    /// Press the mouse button at (x, y) without releasing. Paired with <see cref="Release"/>
    /// for spreadsheet-style selection, drag-with-modifier, and games needing held buttons.
    /// </summary>
    public static (bool Ok, string? Reason) Press(int x, int y, MouseButton button, string? focusWindowId = null)
    {
        var (sx, sy, sw, sh) = ScreenInfoService.VirtualScreenBounds();
        if (x < sx || y < sy || x >= sx + sw || y >= sy + sh)
            return (false, "coordinate outside virtual screen bounds");

        var uipi = CheckUipi(focusWindowId, x, y);
        if (uipi is not null) return (false, uipi);

        if (!string.IsNullOrWhiteSpace(focusWindowId))
        {
            _ = WindowService.Focus(focusWindowId);
            Thread.Sleep(40);
        }

        uint down = button switch
        {
            MouseButton.Right => MOUSEEVENTF_RIGHTDOWN,
            MouseButton.Middle => MOUSEEVENTF_MIDDLEDOWN,
            _ => MOUSEEVENTF_LEFTDOWN
        };
        var inputs = new[]
        {
            AbsoluteMoveInput(x, y),
            new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = down } } }
        };
        uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        return sent == inputs.Length ? (true, null) : (false, "SendInput press failed");
    }

    /// <summary>
    /// Release the mouse button previously pressed via <see cref="Press"/>.
    /// </summary>
    public static (bool Ok, string? Reason) Release(MouseButton button)
    {
        uint up = button switch
        {
            MouseButton.Right => MOUSEEVENTF_RIGHTUP,
            MouseButton.Middle => MOUSEEVENTF_MIDDLEUP,
            _ => MOUSEEVENTF_LEFTUP
        };
        var input = new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = up } } };
        uint sent = SendInput(1, [input], Marshal.SizeOf<INPUT>());
        return sent == 1 ? (true, null) : (false, "SendInput release failed");
    }

    /// <summary>
    /// Move to (x, y) and hold the cursor there for holdMs so tooltips / hover
    /// menus have time to appear. Does not click.
    /// </summary>
    public static (bool Ok, string? Reason) Hover(int x, int y, int holdMs, string? focusWindowId = null)
    {
        holdMs = Math.Clamp(holdMs, 0, 5000);
        var (ok, reason) = Move(x, y, 120, focusWindowId);
        if (!ok) return (ok, reason);
        if (holdMs > 0) Thread.Sleep(holdMs);
        return (true, null);
    }

    /// <summary>
    /// Scroll at coordinate (x, y).
    /// direction: "up" | "down" | "left" | "right"
    /// amount: number of wheel ticks (WHEEL_DELTA = 120 per tick).
    /// </summary>
    public static (bool Ok, string? Reason) Scroll(
        int x, int y, string direction, int amount,
        string? focusWindowId = null)
    {
        if (amount < 1 || amount > 50) return (false, "amount must be between 1 and 50");

        var (sx, sy, sw, sh) = ScreenInfoService.VirtualScreenBounds();
        if (x < sx || y < sy || x >= sx + sw || y >= sy + sh)
            return (false, "coordinate outside virtual screen bounds");

        var uipi = CheckUipi(focusWindowId, x, y);
        if (uipi is not null) return (false, uipi);

        if (!string.IsNullOrWhiteSpace(focusWindowId))
        {
            _ = WindowService.Focus(focusWindowId);
            Thread.Sleep(60);
        }

        // Move cursor to scroll target first so the OS delivers the event to
        // the correct window (Windows routes WM_MOUSEWHEEL to the window under
        // the cursor, not the focused window).
        if (!TryMoveCursor(x, y)) return (false, "cursor move failed");

        bool horizontal = direction is "left" or "right";
        bool negative = direction is "up" or "left";

        // WHEEL_DELTA = 120 per notch; negative = scroll up / left.
        int delta = (negative ? -120 : 120) * amount;

        var input = new INPUT
        {
            type = INPUT_MOUSE,
            U = new InputUnion
            {
                mi = new MOUSEINPUT
                {
                    dwFlags = horizontal ? MOUSEEVENTF_HWHEEL : MOUSEEVENTF_WHEEL,
                    mouseData = (uint)delta
                }
            }
        };
        uint sent = SendInput(1, [input], Marshal.SizeOf<INPUT>());
        if (sent == 0) return (false, "SendInput returned 0");

        // PostMessage fallback: some applications (e.g. certain WebView2 and
        // DirectManipulation surfaces) ignore injected wheel events unless
        // they arrive via WM_MOUSEWHEEL directly. We additionally post the
        // message to the window under the cursor — it is a no-op when the
        // SendInput path already worked, but rescues the case where it didn't.
        try
        {
            var pt = new NativeMethods.POINT { X = x, Y = y };
            var hWnd = NativeMethods.WindowFromPoint(pt);
            if (hWnd != IntPtr.Zero)
            {
                // wParam high word = delta, low word = virtual keys (0 = none).
                // lParam = screen coords packed (x low, y high).
                int wParamHigh = delta & 0xFFFF;
                IntPtr wParam = new IntPtr((wParamHigh << 16));
                IntPtr lParam = new IntPtr((y << 16) | (x & 0xFFFF));
                uint msg = horizontal ? 0x020Eu /* WM_MOUSEHWHEEL */ : 0x020Au /* WM_MOUSEWHEEL */;
                _ = NativeMethods.PostMessageW(hWnd, msg, wParam, lParam);
            }
        }
        catch
        {
            // Best-effort fallback; never fail the tool because of it.
        }

        return (true, null);
    }

    /// <summary>
    /// Press-hold at (startX, startY), optionally move through waypoints,
    /// then release at (endX, endY). durationMs controls total movement time.
    /// </summary>
    public static (bool Ok, string? Reason) Drag(
        int startX, int startY,
        int endX, int endY,
        MouseButton button,
        int durationMs,
        IReadOnlyList<(int X, int Y)>? waypoints,
        string? focusWindowId = null)
    {
        var (sx, sy, sw, sh) = ScreenInfoService.VirtualScreenBounds();
        bool InBounds(int px, int py) =>
            px >= sx && py >= sy && px < sx + sw && py < sy + sh;

        if (!InBounds(startX, startY)) return (false, "start coordinate outside virtual screen bounds");
        if (!InBounds(endX, endY)) return (false, "end coordinate outside virtual screen bounds");

        var uipi = CheckUipi(focusWindowId, startX, startY);
        if (uipi is not null) return (false, uipi);

        if (!string.IsNullOrWhiteSpace(focusWindowId))
        {
            _ = WindowService.Focus(focusWindowId);
            Thread.Sleep(60);
        }

        uint downFlag, upFlag;
        switch (button)
        {
            case MouseButton.Right:
                downFlag = MOUSEEVENTF_RIGHTDOWN; upFlag = MOUSEEVENTF_RIGHTUP; break;
            case MouseButton.Middle:
                downFlag = MOUSEEVENTF_MIDDLEDOWN; upFlag = MOUSEEVENTF_MIDDLEUP; break;
            default:
                downFlag = MOUSEEVENTF_LEFTDOWN; upFlag = MOUSEEVENTF_LEFTUP; break;
        }

        // 1. Move to start and press.
        if (!TryMoveCursor(startX, startY)) return (false, "cursor move failed");
        Thread.Sleep(30); // brief settle before press
        SendInput(1, [new INPUT
        {
            type = INPUT_MOUSE,
            U = new InputUnion { mi = new MOUSEINPUT { dwFlags = downFlag } }
        }], Marshal.SizeOf<INPUT>());

        // 2. Build the full path: start → waypoints → end.
        var path = new List<(int X, int Y)> { (startX, startY) };
        if (waypoints is not null)
            foreach (var wp in waypoints)
                if (InBounds(wp.X, wp.Y)) path.Add(wp);
        path.Add((endX, endY));

        // 3. Interpolate movement across all segments.
        int totalSegments = path.Count - 1;
        int msPerSegment = totalSegments > 0 ? Math.Max(50, durationMs / totalSegments) : durationMs;

        for (int seg = 0; seg < totalSegments; seg++)
        {
            var (ax, ay) = path[seg];
            var (bx, by) = path[seg + 1];
            int steps = Math.Max(1, msPerSegment / 16);
            int sleepMs = Math.Max(1, msPerSegment / steps);
            for (int i = 1; i <= steps; i++)
            {
                double t = (double)i / steps;
                int cx = ax + (int)Math.Round((bx - ax) * t);
                int cy = ay + (int)Math.Round((by - ay) * t);
                _ = TryMoveCursor(cx, cy);
                if (i < steps) Thread.Sleep(sleepMs);
            }
        }
        if (!TryMoveCursor(endX, endY)) return (false, "cursor move failed"); // ensure exact final position
        Thread.Sleep(30); // brief settle before release

        // 4. Release.
        SendInput(1, [new INPUT
        {
            type = INPUT_MOUSE,
            U = new InputUnion { mi = new MOUSEINPUT { dwFlags = upFlag } }
        }], Marshal.SizeOf<INPUT>());

        return (true, null);
    }

    /// <summary>
    /// Press a single key down, wait for <paramref name="durationMs"/>, then release it.
    /// Equivalent to Claude computer_use hold_key. Useful for games and
    /// spreadsheet-style selection with held modifiers.
    /// </summary>
    public static (bool Ok, string? Reason) HoldKey(string? key, int durationMs)
    {
        if (string.IsNullOrWhiteSpace(key)) return (false, "key is required");
        if (!HotkeyMap.TryResolve(key, out var vk)) return (false, $"unknown key: {key}");
        durationMs = Math.Clamp(durationMs, 10, 10_000);

        var uipi = CheckUipi(null, null, null);
        if (uipi is not null) return (false, uipi);

        var down = KeyInput(vk, keyUp: false);
        uint sent = SendInput(1, [down], Marshal.SizeOf<INPUT>());
        if (sent != 1) return (false, "SendInput key-down failed");
        Thread.Sleep(durationMs);
        var up = KeyInput(vk, keyUp: true);
        SendInput(1, [up], Marshal.SizeOf<INPUT>());
        return (true, null);
    }

    /// <summary>
    /// Returns the top-level and immediate window under screen coordinates (x, y).
    /// Used as a post-condition for visual click targets.
    /// </summary>
    public static (bool Ok, string? Reason, long TopLevel, long Immediate, int ClientX, int ClientY) HitTest(int x, int y)
    {
        var pt = new NativeMethods.POINT { X = x, Y = y };
        var hit = NativeMethods.WindowFromPoint(pt);
        if (hit == IntPtr.Zero) return (false, "no window at coordinate", 0, 0, 0, 0);

        var root = NativeMethods.GetAncestor(hit, NativeMethods.GA_ROOT);
        if (root == IntPtr.Zero) root = hit;

        var client = new NativeMethods.POINT { X = x, Y = y };
        NativeMethods.ScreenToClient(root, ref client);

        return (true, null, root.ToInt64(), hit.ToInt64(), client.X, client.Y);
    }

    /// <summary>
    /// Convert a client-relative (clientX, clientY) inside <paramref name="windowId"/>
    /// into virtual-screen physical pixels. Returns null if the window is gone.
    /// </summary>
    public static (int X, int Y)? ResolveClientPoint(string? windowId, int clientX, int clientY)
    {
        if (string.IsNullOrWhiteSpace(windowId)) return null;
        if (!long.TryParse(windowId, out var handleValue)) return null;
        var hWnd = new IntPtr(handleValue);
        if (!NativeMethods.IsWindow(hWnd)) return null;
        var pt = new NativeMethods.POINT { X = clientX, Y = clientY };
        if (!NativeMethods.ClientToScreen(hWnd, ref pt)) return null;
        return (pt.X, pt.Y);
    }

    private static bool TryMoveCursor(int x, int y)
    {
        if (SetCursorPos(x, y)) return true;

        var input = AbsoluteMoveInput(x, y);
        return SendInput(1, [input], Marshal.SizeOf<INPUT>()) == 1;
    }

    private static INPUT AbsoluteMoveInput(int x, int y)
    {
        var (sx, sy, sw, sh) = ScreenInfoService.VirtualScreenBounds();
        int width = Math.Max(1, sw - 1);
        int height = Math.Max(1, sh - 1);
        int dx = (int)Math.Round((x - sx) * 65535.0 / width);
        int dy = (int)Math.Round((y - sy) * 65535.0 / height);
        dx = Math.Clamp(dx, 0, 65535);
        dy = Math.Clamp(dy, 0, 65535);

        return new INPUT
        {
            type = INPUT_MOUSE,
            U = new InputUnion
            {
                mi = new MOUSEINPUT
                {
                    dx = dx,
                    dy = dy,
                    dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK
                }
            }
        };
    }

    // ── keyboard.type ────────────────────────────────────────────────────────

    public static (bool Ok, string? Reason, int Length) Type(string? text, int intervalMs)
    {
        if (text is null) return (false, "text is required", 0);
        if (text.Length == 0) return (true, null, 0);
        if (text.Length > MaxTypeLength) return (false, $"text exceeds {MaxTypeLength} chars", 0);
        if (intervalMs < 0 || intervalMs > 100)
            return (false, "intervalMs must be between 0 and 100", 0);

        var uipi = CheckUipi(null, null, null);
        if (uipi is not null) return (false, uipi, 0);

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

        var uipi = CheckUipi(null, null, null);
        if (uipi is not null) return (false, uipi, keys);

        var codes = new List<ushort>(keys.Count);
        foreach (var raw in keys)
        {
            if (!HotkeyMap.TryResolve(raw, out var vk))
            {
                return (false, $"unknown key: {raw}", keys);
            }
            codes.Add(vk);
        }

        // Press in order, release in reverse order. Use scan codes rather than
        // virtual-key-only events; this is more reliable for system chords such
        // as Alt+F4 after a foreground-window switch.
        var down = new INPUT[codes.Count];
        var up = new INPUT[codes.Count];
        for (int i = 0; i < codes.Count; i++)
        {
            down[i] = KeyInput(codes[i], keyUp: false);
        }
        for (int i = 0; i < codes.Count; i++)
        {
            int src = codes.Count - 1 - i;
            up[i] = KeyInput(codes[src], keyUp: true);
        }

        uint sentDown = SendInput((uint)down.Length, down, Marshal.SizeOf<INPUT>());
        Thread.Sleep(80);
        uint sentUp = SendInput((uint)up.Length, up, Marshal.SizeOf<INPUT>());
        if (sentDown != down.Length || sentUp != up.Length) return (false, "SendInput sent fewer key events than requested", keys);
        return (true, null, keys);
    }

    private static INPUT KeyInput(ushort vk, bool keyUp)
    {
        ushort scan = (ushort)MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
        if (scan == 0) scan = vk;
        uint flags = KEYEVENTF_SCANCODE;
        if (keyUp) flags |= KEYEVENTF_KEYUP;
        if (IsExtendedKey(vk)) flags |= KEYEVENTF_EXTENDEDKEY;

        return new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT { wVk = 0, wScan = scan, dwFlags = flags }
            }
        };
    }

    private static bool IsExtendedKey(ushort vk) => vk is
        0x21 or 0x22 or 0x23 or 0x24 or 0x25 or 0x26 or 0x27 or 0x28 or
        0x2D or 0x2E or 0x5B or 0x5C or 0x5D or 0x6F or 0xA3 or 0xA5;
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
