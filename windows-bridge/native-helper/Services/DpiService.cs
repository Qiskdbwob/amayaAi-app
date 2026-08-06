using System.Runtime.InteropServices;
using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper.Services;

/// <summary>
/// DPI-awareness bootstrap and query helpers.
///
/// The app.manifest declares PerMonitorV2 which is applied before any managed
/// code runs, but some hosting scenarios (for example .NET Host elevating the
/// helper through a different path) can still miss the manifest. Calling
/// <see cref="EnsurePerMonitorV2"/> at process start closes that gap by
/// invoking <c>SetProcessDpiAwarenessContext</c> at runtime; the call is a
/// no-op when the manifest has already taken effect.
/// </summary>
internal static class DpiService
{
    private static readonly IntPtr PerMonitorV2 = new(-4);
    private static readonly IntPtr PerMonitor = new(-3);
    private static readonly IntPtr System = new(-2);
    private static readonly IntPtr Unaware = new(-1);

    public static string ActiveContext { get; private set; } = "unknown";
    public static uint SystemDpi { get; private set; } = 96;
    public static double SystemScaleFactor => SystemDpi / 96.0;

    public static void EnsurePerMonitorV2()
    {
        // Try PerMonitorV2 first (Win10 1703+), then fall back to PerMonitor
        // (Win10 1607+) if the platform rejects it. SetProcessDpiAwarenessContext
        // returns IntPtr.Zero on failure and sets GetLastError; the common
        // benign failure is ERROR_ACCESS_DENIED when the process is already
        // DPI-aware via the manifest. Either outcome leaves us in a known
        // per-monitor mode so input coordinates are physical pixels.
        if (TrySetContext(PerMonitorV2, "PerMonitorV2")) return;
        if (TrySetContext(PerMonitor, "PerMonitor")) return;
        if (TrySetContext(System, "System")) return;
        ActiveContext = "manifest-or-default";
        RefreshSystemDpi();
    }

    public static void RefreshSystemDpi()
    {
        try
        {
            SystemDpi = NativeMethods.GetDpiForSystem();
            if (SystemDpi == 0) SystemDpi = 96;
        }
        catch
        {
            SystemDpi = 96;
        }
    }

    public static double GetScaleFactorForWindow(IntPtr hWnd)
    {
        if (hWnd == IntPtr.Zero) return SystemScaleFactor;
        try
        {
            uint dpi = NativeMethods.GetDpiForWindow(hWnd);
            return dpi > 0 ? dpi / 96.0 : SystemScaleFactor;
        }
        catch
        {
            return SystemScaleFactor;
        }
    }

    public static string GetAwarenessContext(IntPtr hWnd)
    {
        if (hWnd == IntPtr.Zero) return ActiveContext;
        try
        {
            var ctx = NativeMethods.GetWindowDpiAwarenessContext(hWnd);
            if (NativeMethods.AreDpiAwarenessContextsEqual(ctx, PerMonitorV2)) return "PerMonitorV2";
            if (NativeMethods.AreDpiAwarenessContextsEqual(ctx, PerMonitor)) return "PerMonitor";
            if (NativeMethods.AreDpiAwarenessContextsEqual(ctx, System)) return "System";
            if (NativeMethods.AreDpiAwarenessContextsEqual(ctx, Unaware)) return "Unaware";
            return "unknown";
        }
        catch
        {
            return "unknown";
        }
    }

    private static bool TrySetContext(IntPtr context, string label)
    {
        try
        {
            var result = NativeMethods.SetProcessDpiAwarenessContext(context);
            if (result != IntPtr.Zero)
            {
                ActiveContext = label;
                RefreshSystemDpi();
                return true;
            }
            // The API fails with ERROR_ACCESS_DENIED when a manifest already
            // applied the awareness. That's a success for us.
            int err = Marshal.GetLastWin32Error();
            if (err == 5 /* ERROR_ACCESS_DENIED */)
            {
                ActiveContext = label + "-manifest";
                RefreshSystemDpi();
                return true;
            }
        }
        catch
        {
            // Some older Windows editions don't expose SetProcessDpiAwarenessContext.
            // Callers will fall back to the next context automatically.
        }
        return false;
    }
}
