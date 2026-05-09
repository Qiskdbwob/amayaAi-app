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
                    Visible = true,
                    Focused = hWnd == foreground
                });
            }
            catch
            {
                // Never propagate exceptions out of EnumWindows — skip offending window.
            }
            return true;
        }, IntPtr.Zero);

        return windows;
    }

    public static WindowInfo? Active()
    {
        var hWnd = NativeMethods.GetForegroundWindow();
        if (hWnd == IntPtr.Zero) return null;
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
                Visible = NativeMethods.IsWindowVisible(hWnd),
                Focused = true
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

        IntPtr hWnd = new IntPtr(handleValue);
        if (!NativeMethods.IsWindowVisible(hWnd) && !NativeMethods.IsIconic(hWnd))
        {
            // Not visible and not minimized — treat as missing.
            return (false, "window not found");
        }

        if (NativeMethods.IsIconic(hWnd))
        {
            NativeMethods.ShowWindow(hWnd, NativeMethods.SW_RESTORE);
        }

        bool ok = NativeMethods.SetForegroundWindow(hWnd);
        if (!ok)
        {
            return (false, "Windows blocked foreground activation (recoverable)");
        }
        return (true, null);
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
