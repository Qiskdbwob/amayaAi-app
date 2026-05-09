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
    [JsonPropertyName("bounds")] public WindowBounds Bounds { get; init; } = new();
    [JsonPropertyName("state")] public string State { get; init; } = "unknown";
    [JsonPropertyName("visible")] public bool Visible { get; init; }
    [JsonPropertyName("focused")] public bool Focused { get; init; }
}
