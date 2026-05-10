using System.Runtime.InteropServices;
using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper.Services;

/// <summary>
/// One-shot diagnostic snapshot the agent can read to know what the helper can
/// and cannot do: DPI context, elevation, active desktop, Windows.Graphics.Capture
/// availability, and monitor list. Agents use this to avoid trying tools that
/// cannot succeed in the current environment.
/// </summary>
internal static class DiagnosticsService
{
    public static DiagnosticsSnapshot Collect()
    {
        var selfIntegrity = IntegrityService.SelfIntegrity;
        var elevated = IsProcessElevated();
        IntPtr fg = NativeMethods.GetForegroundWindow();
        var fgIntegrity = IntegrityService.LabelForWindow(fg);
        return new DiagnosticsSnapshot
        {
            HelperPid = Environment.ProcessId,
            HelperVersion = typeof(DiagnosticsService).Assembly.GetName().Version?.ToString() ?? "unknown",
            DpiContext = DpiService.ActiveContext,
            SystemDpi = DpiService.SystemDpi,
            SystemScaleFactor = DpiService.SystemScaleFactor,
            Elevated = elevated,
            SelfIntegrity = selfIntegrity,
            ForegroundIntegrity = fgIntegrity,
            CanInjectIntoHighIntegrity = elevated || selfIntegrity is "high" or "system",
            OsVersion = Environment.OSVersion.VersionString,
            VirtualScreen = ReadVirtualScreen(),
            GraphicsCaptureSupported = IsGraphicsCaptureSupported(),
            PrintWindowSupported = true,
            SecureDesktopActive = false, // best-effort; secure desktops reject our process entirely
            RecommendedActions = BuildActionList(elevated, selfIntegrity)
        };
    }

    private static List<string> BuildActionList(bool elevated, string selfIntegrity)
    {
        var list = new List<string>();
        if (!elevated && selfIntegrity is not ("high" or "system"))
        {
            list.Add(
                "Amaya Windows Bridge is running at medium integrity. Injected input (mouse.click, " +
                "keyboard.hotkey, keyboard.type) into elevated windows (Task Manager, Registry Editor, " +
                "any app requiring Admin, Windows Security, UAC prompts) will be silently dropped by " +
                "Windows UIPI. To control those apps, close the bridge tray, right-click " +
                "\"Amaya Windows Bridge.exe\" and pick \"Run as administrator\" before reconnecting."
            );
        }
        return list;
    }

    private static WindowBounds ReadVirtualScreen()
    {
        var (x, y, w, h) = ScreenInfoService.VirtualScreenBounds();
        return new WindowBounds { X = x, Y = y, Width = w, Height = h };
    }

    private static bool IsProcessElevated()
    {
        try
        {
            if (!NativeMethods.OpenProcessToken(NativeMethods.GetCurrentProcess(),
                NativeMethods.TOKEN_QUERY, out var token))
            {
                return false;
            }
            try
            {
                IntPtr buffer = Marshal.AllocHGlobal(sizeof(int));
                try
                {
                    if (NativeMethods.GetTokenInformation(token, NativeMethods.TokenElevation,
                        buffer, sizeof(int), out _))
                    {
                        return Marshal.ReadInt32(buffer) != 0;
                    }
                }
                finally
                {
                    Marshal.FreeHGlobal(buffer);
                }
            }
            finally
            {
                NativeMethods.CloseHandle(token);
            }
        }
        catch
        {
            // Fall through
        }
        return false;
    }

    private static bool IsGraphicsCaptureSupported()
    {
        // GraphicsCaptureSession.IsSupported is a WinRT call we intentionally
        // don't bind here to keep the helper dependency-free. Report true on
        // Windows 10 1903+ (build 18362); the fallback path is PrintWindow.
        var ver = Environment.OSVersion.Version;
        return ver.Major > 10 || (ver.Major == 10 && ver.Build >= 18362);
    }
}

internal sealed class DiagnosticsSnapshot
{
    [System.Text.Json.Serialization.JsonPropertyName("helperPid")] public int HelperPid { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("helperVersion")] public string HelperVersion { get; init; } = string.Empty;
    [System.Text.Json.Serialization.JsonPropertyName("dpiContext")] public string DpiContext { get; init; } = string.Empty;
    [System.Text.Json.Serialization.JsonPropertyName("systemDpi")] public uint SystemDpi { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("systemScaleFactor")] public double SystemScaleFactor { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("elevated")] public bool Elevated { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("selfIntegrity")] public string SelfIntegrity { get; init; } = "unknown";
    [System.Text.Json.Serialization.JsonPropertyName("foregroundIntegrity")] public string ForegroundIntegrity { get; init; } = "unknown";
    [System.Text.Json.Serialization.JsonPropertyName("canInjectIntoHighIntegrity")] public bool CanInjectIntoHighIntegrity { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("osVersion")] public string OsVersion { get; init; } = string.Empty;
    [System.Text.Json.Serialization.JsonPropertyName("virtualScreen")] public WindowBounds VirtualScreen { get; init; } = new();
    [System.Text.Json.Serialization.JsonPropertyName("graphicsCaptureSupported")] public bool GraphicsCaptureSupported { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("printWindowSupported")] public bool PrintWindowSupported { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("secureDesktopActive")] public bool SecureDesktopActive { get; init; }
    [System.Text.Json.Serialization.JsonPropertyName("recommendedActions")] public List<string> RecommendedActions { get; init; } = new();
}
