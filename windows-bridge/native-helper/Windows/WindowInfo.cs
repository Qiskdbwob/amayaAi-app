using System.Text.Json.Serialization;

namespace AmayaBridgeHelper.Windows;

internal sealed class WindowBounds
{
    [JsonPropertyName("x")] public int X { get; init; }
    [JsonPropertyName("y")] public int Y { get; init; }
    [JsonPropertyName("width")] public int Width { get; init; }
    [JsonPropertyName("height")] public int Height { get; init; }
}

internal sealed class WindowInfo
{
    [JsonPropertyName("id")] public string Id { get; init; } = string.Empty;
    [JsonPropertyName("title")] public string Title { get; init; } = string.Empty;
    [JsonPropertyName("processId")] public int ProcessId { get; init; }
    [JsonPropertyName("processName")] public string ProcessName { get; init; } = string.Empty;
    /// <summary>Window rect in physical pixels, rooted at the virtual screen.</summary>
    [JsonPropertyName("bounds")] public WindowBounds Bounds { get; init; } = new();
    /// <summary>Client rect projected into screen space, physical pixels. May be null when unavailable.</summary>
    [JsonPropertyName("clientBounds")] public WindowBounds? ClientBounds { get; init; }
    [JsonPropertyName("state")] public string State { get; init; } = "unknown";
    [JsonPropertyName("visible")] public bool Visible { get; init; }
    [JsonPropertyName("focused")] public bool Focused { get; init; }
    /// <summary>
    /// Convenience flag: false for tool windows / no-activate windows that should not receive focus.
    /// </summary>
    [JsonPropertyName("focusable")] public bool Focusable { get; init; } = true;
    /// <summary>Z-order index. 0 = frontmost. Computed from GetWindow(GW_HWNDPREV) chain.</summary>
    [JsonPropertyName("zIndex")] public int ZIndex { get; init; }
    /// <summary>Physical scale factor for the monitor owning this window (1.0 = 96 DPI).</summary>
    [JsonPropertyName("scaleFactor")] public double ScaleFactor { get; init; } = 1.0;
    /// <summary>DPI awareness reported by the window. One of PerMonitorV2/PerMonitor/System/Unaware/unknown.</summary>
    [JsonPropertyName("dpiAwareness")] public string DpiAwareness { get; init; } = "unknown";
}
