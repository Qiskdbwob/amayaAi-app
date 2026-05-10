using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper.Services;

internal static class WindowService
{
    public static List<WindowInfo> List()
    {
        var foreground = NativeMethods.GetForegroundWindow();
        var windows = new List<WindowInfo>();

        NativeMethods.EnumWindows((hWnd, _) =>
        {
            try
            {
                if (!NativeMethods.IsWindowVisible(hWnd)) return true;

                int titleLen = NativeMethods.GetWindowTextLengthW(hWnd);
                if (titleLen <= 0) return true;
                var sb = new StringBuilder(titleLen + 1);
                NativeMethods.GetWindowTextW(hWnd, sb, sb.Capacity);
                var title = sb.ToString();
                if (string.IsNullOrWhiteSpace(title)) return true;

                NativeMethods.GetWindowThreadProcessId(hWnd, out uint pid);

                var bounds = ReadBounds(hWnd);
                if (bounds is null) return true;

                windows.Add(new WindowInfo
                {
                    Id = hWnd.ToInt64().ToString(),
                    Title = title,
                    ProcessId = (int)pid,
                    ProcessName = SafeProcessName((int)pid),
                    Bounds = bounds,
                    ClientBounds = ReadClientBounds(hWnd),
                    State = WindowState(hWnd),
                    Visible = true,
                    Focused = hWnd == foreground,
                    Focusable = IsFocusable(hWnd),
                    ScaleFactor = DpiService.GetScaleFactorForWindow(hWnd),
                    DpiAwareness = DpiService.GetAwarenessContext(hWnd)
                });
            }
            catch
            {
                // Never propagate exceptions out of EnumWindows — skip offending window.
            }
            return true;
        }, IntPtr.Zero);

        // Windows are enumerated top-to-bottom already. Assign the z-index
        // explicitly so the client can rely on it even if we change collection
        // order later.
        for (int i = 0; i < windows.Count; i++)
        {
            windows[i] = new WindowInfo
            {
                Id = windows[i].Id,
                Title = windows[i].Title,
                ProcessId = windows[i].ProcessId,
                ProcessName = windows[i].ProcessName,
                Bounds = windows[i].Bounds,
                ClientBounds = windows[i].ClientBounds,
                State = windows[i].State,
                Visible = windows[i].Visible,
                Focused = windows[i].Focused,
                Focusable = windows[i].Focusable,
                ScaleFactor = windows[i].ScaleFactor,
                DpiAwareness = windows[i].DpiAwareness,
                ZIndex = i
            };
        }
        return windows;
    }

    public static WindowInfo? Active()
    {
        var hWnd = NativeMethods.GetForegroundWindow();
        if (hWnd == IntPtr.Zero) return null;
        return Info(hWnd, focused: true);
    }

    public static WindowInfo? Info(IntPtr hWnd, bool focused)
    {
        if (hWnd == IntPtr.Zero || !NativeMethods.IsWindow(hWnd)) return null;
        try
        {
            var titleLen = NativeMethods.GetWindowTextLengthW(hWnd);
            var sb = new StringBuilder(Math.Max(1, titleLen + 1));
            NativeMethods.GetWindowTextW(hWnd, sb, sb.Capacity);
            var title = sb.ToString();

            NativeMethods.GetWindowThreadProcessId(hWnd, out uint pid);
            var bounds = ReadBounds(hWnd) ?? new WindowBounds();
            return new WindowInfo
            {
                Id = hWnd.ToInt64().ToString(),
                Title = title,
                ProcessId = (int)pid,
                ProcessName = SafeProcessName((int)pid),
                Bounds = bounds,
                ClientBounds = ReadClientBounds(hWnd),
                State = WindowState(hWnd),
                Visible = NativeMethods.IsWindowVisible(hWnd),
                Focused = focused,
                Focusable = IsFocusable(hWnd),
                ScaleFactor = DpiService.GetScaleFactorForWindow(hWnd),
                DpiAwareness = DpiService.GetAwarenessContext(hWnd)
            };
        }
        catch
        {
            return null;
        }
    }

    public static (bool Focused, string? Reason) Focus(string? windowId)
    {
        if (string.IsNullOrWhiteSpace(windowId))
        {
            return (false, "windowId is required");
        }
        if (!long.TryParse(windowId, out var handleValue))
        {
            return (false, "windowId must be a numeric handle");
        }

        IntPtr hWnd = NormalizeWindowHandle(new IntPtr(handleValue));
        if (hWnd == IntPtr.Zero)
        {
            return (false, "window not found");
        }

        if (IsForegroundRoot(hWnd)) return (true, null);

        // Be conservative for normal/windowed apps: do not call SW_SHOWNORMAL and
        // do not attach input queues. Some apps react badly to aggressive focus
        // manipulation. Only restore minimized windows, then request foreground.
        if (NativeMethods.IsIconic(hWnd))
        {
            NativeMethods.ShowWindow(hWnd, NativeMethods.SW_RESTORE);
            Thread.Sleep(120);
        }

        NativeMethods.BringWindowToTop(hWnd);
        _ = NativeMethods.SetForegroundWindow(hWnd);
        Thread.Sleep(80);

        return IsForegroundRoot(hWnd)
            ? (true, null)
            : (false, "Windows blocked foreground activation (recoverable)");
    }

    public static (bool Closed, string? Reason) Close(string? windowId)
    {
        if (string.IsNullOrWhiteSpace(windowId))
        {
            return (false, "windowId is required");
        }
        if (!long.TryParse(windowId, out var handleValue))
        {
            return (false, "windowId must be a numeric handle");
        }

        IntPtr hWnd = NormalizeWindowHandle(new IntPtr(handleValue));
        if (hWnd == IntPtr.Zero)
        {
            return (false, "window not found");
        }

        // Preferred path: ask the target window to close. Never report success
        // only because the message was accepted; verify that the HWND disappears.
        if (NativeMethods.PostMessageW(hWnd, NativeMethods.WM_CLOSE, IntPtr.Zero, IntPtr.Zero) &&
            WaitUntilClosed(hWnd, 900))
        {
            return (true, null);
        }

        _ = NativeMethods.SendMessageTimeoutW(
            hWnd,
            NativeMethods.WM_CLOSE,
            IntPtr.Zero,
            IntPtr.Zero,
            NativeMethods.SMTO_ABORTIFHUNG,
            500,
            out _);
        if (WaitUntilClosed(hWnd, 900))
        {
            return (true, null);
        }

        // Fallback for windows that reject cross-thread WM_CLOSE (for example
        // integrity-level/UIPI cases): focus the target and send Alt+F4.
        var (focused, _) = Focus(windowId);
        if (focused)
        {
            var (ok, reason, _) = InputService.Hotkey(["alt", "f4"]);
            if (!ok) return (false, reason ?? "Alt+F4 close fallback failed");
            if (WaitUntilClosed(hWnd, 1200)) return (true, null);
            return (false, "close command was sent but the window is still open");
        }

        return (false, "window close request was blocked");
    }

    private static bool WaitUntilClosed(IntPtr hWnd, int timeoutMs)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        while (Environment.TickCount64 < deadline)
        {
            if (!NativeMethods.IsWindow(hWnd)) return true;
            Thread.Sleep(50);
        }
        return !NativeMethods.IsWindow(hWnd);
    }

    private static bool IsForegroundRoot(IntPtr hWnd)
    {
        var foreground = NativeMethods.GetForegroundWindow();
        if (foreground == IntPtr.Zero) return false;
        return NormalizeWindowHandle(foreground) == NormalizeWindowHandle(hWnd);
    }

    private static IntPtr NormalizeWindowHandle(IntPtr hWnd)
    {
        if (hWnd == IntPtr.Zero || !NativeMethods.IsWindow(hWnd)) return IntPtr.Zero;
        var root = NativeMethods.GetAncestor(hWnd, NativeMethods.GA_ROOT);
        if (root != IntPtr.Zero && NativeMethods.IsWindow(root)) hWnd = root;
        if (!NativeMethods.IsWindowVisible(hWnd) && !NativeMethods.IsIconic(hWnd)) return IntPtr.Zero;
        return hWnd;
    }

    private static string WindowState(IntPtr hWnd)
    {
        if (NativeMethods.IsIconic(hWnd)) return "minimized";
        if (NativeMethods.IsZoomed(hWnd)) return "maximized";
        return "normal";
    }

    private static WindowBounds? ReadBounds(IntPtr hWnd)
    {
        if (!NativeMethods.GetWindowRect(hWnd, out var rect)) return null;
        return new WindowBounds
        {
            X = rect.Left,
            Y = rect.Top,
            Width = Math.Max(0, rect.Right - rect.Left),
            Height = Math.Max(0, rect.Bottom - rect.Top)
        };
    }

    private static WindowBounds? ReadClientBounds(IntPtr hWnd)
    {
        if (!NativeMethods.GetClientRect(hWnd, out var rect)) return null;
        int width = Math.Max(0, rect.Right - rect.Left);
        int height = Math.Max(0, rect.Bottom - rect.Top);
        if (width == 0 && height == 0) return null;
        var origin = new NativeMethods.POINT { X = rect.Left, Y = rect.Top };
        if (!NativeMethods.ClientToScreen(hWnd, ref origin)) return null;
        return new WindowBounds
        {
            X = origin.X,
            Y = origin.Y,
            Width = width,
            Height = height
        };
    }

    private static bool IsFocusable(IntPtr hWnd)
    {
        try
        {
            long exStyle = NativeMethods.GetWindowLongPtrW(hWnd, NativeMethods.GWL_EXSTYLE).ToInt64();
            if ((exStyle & NativeMethods.WS_EX_NOACTIVATE) != 0) return false;
            // Tool windows are technically focusable, but the caller usually means
            // "a real target window" — keep them false to avoid noisy suggestions.
            if ((exStyle & NativeMethods.WS_EX_TOOLWINDOW) != 0) return false;
            return true;
        }
        catch
        {
            return true;
        }
    }

    private static string SafeProcessName(int pid)
    {
        if (pid <= 0) return string.Empty;
        // Try the managed API first — fast path and doesn't need any P/Invoke.
        try
        {
            using var proc = Process.GetProcessById(pid);
            return proc.ProcessName;
        }
        catch
        {
            // Fall through to the Win32 fallback for protected processes.
        }

        IntPtr handle = NativeMethods.OpenProcess(
            NativeMethods.PROCESS_QUERY_LIMITED_INFORMATION, false, (uint)pid);
        if (handle == IntPtr.Zero) return string.Empty;
        try
        {
            var sb = new StringBuilder(1024);
            uint capacity = (uint)sb.Capacity;
            if (NativeMethods.QueryFullProcessImageNameW(handle, 0, sb, ref capacity) == 0)
            {
                return string.Empty;
            }
            return Path.GetFileNameWithoutExtension(sb.ToString());
        }
        catch
        {
            return string.Empty;
        }
        finally
        {
            NativeMethods.CloseHandle(handle);
        }
    }
}
