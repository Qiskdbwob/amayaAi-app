using System.Text;
using System.Text.Json.Serialization;
using AmayaBridgeHelper.Windows;
using static AmayaBridgeHelper.Windows.NativeMethods;

namespace AmayaBridgeHelper.Services;

internal static class UiAutomationService
{
    private const int DefaultLimit = 250;
    private const int MaxLimit = 1000;

    public static (bool Ok, string? Reason, UiTreeSnapshot? Snapshot) Tree(string? windowId, int limit)
    {
        var root = ResolveRoot(windowId);
        if (root == IntPtr.Zero) return (false, "window not found", null);
        limit = Math.Clamp(limit <= 0 ? DefaultLimit : limit, 1, MaxLimit);

        var active = WindowService.Info(root, focused: root == GetForegroundWindow());
        var elements = Enumerate(root, limit);
        return (true, null, new UiTreeSnapshot
        {
            RootWindow = active,
            Elements = elements,
            Limit = limit,
            Truncated = elements.Count >= limit
        });
    }

    public static (bool Ok, string? Reason, IReadOnlyList<UiElementInfo> Matches) FindText(string? text, string? windowId, int limit)
    {
        if (string.IsNullOrWhiteSpace(text)) return (false, "text is required", Array.Empty<UiElementInfo>());
        var (ok, reason, snapshot) = Tree(windowId, limit <= 0 ? DefaultLimit : limit);
        if (!ok || snapshot is null) return (false, reason, Array.Empty<UiElementInfo>());
        var needle = text.Trim().ToLowerInvariant();
        var matches = snapshot.Elements
            .Where(e => e.Name.ToLowerInvariant().Contains(needle) || e.ClassName.ToLowerInvariant().Contains(needle))
            .Take(Math.Clamp(limit <= 0 ? 50 : limit, 1, MaxLimit))
            .ToList();
        return (true, null, matches);
    }

    public static (bool Ok, string? Reason, UiElementInfo? Element) ClickElement(string? elementId)
    {
        if (string.IsNullOrWhiteSpace(elementId)) return (false, "elementId is required", null);
        if (!long.TryParse(elementId, out var handleValue)) return (false, "elementId must be a numeric handle", null);
        var hWnd = new IntPtr(handleValue);
        if (!IsWindow(hWnd)) return (false, "element not found", null);
        var element = ReadElement(hWnd, depth: 0, index: 0);
        if (element is null) return (false, "element has no clickable bounds", null);
        var x = element.Center.X;
        var y = element.Center.Y;
        var (ok, reason) = InputService.Click(x, y, MouseButton.Left, clicks: 1);
        return ok ? (true, null, element) : (false, reason ?? "click failed", element);
    }

    private static IntPtr ResolveRoot(string? windowId)
    {
        if (!string.IsNullOrWhiteSpace(windowId))
        {
            if (!long.TryParse(windowId, out var handleValue)) return IntPtr.Zero;
            var hWnd = new IntPtr(handleValue);
            if (!IsWindow(hWnd)) return IntPtr.Zero;
            var root = GetAncestor(hWnd, GA_ROOT);
            return root != IntPtr.Zero && IsWindow(root) ? root : hWnd;
        }
        return GetForegroundWindow();
    }

    private static List<UiElementInfo> Enumerate(IntPtr root, int limit)
    {
        var result = new List<UiElementInfo>(Math.Min(limit, DefaultLimit));
        var queue = new Queue<(IntPtr Hwnd, int Depth)>();
        queue.Enqueue((root, 0));
        var index = 0;

        while (queue.Count > 0 && result.Count < limit)
        {
            var (current, depth) = queue.Dequeue();
            var element = ReadElement(current, depth, index++);
            if (element is not null) result.Add(element);

            EnumChildWindows(current, (child, _) =>
            {
                if (result.Count + queue.Count >= limit) return false;
                if (IsWindow(child)) queue.Enqueue((child, depth + 1));
                return true;
            }, IntPtr.Zero);
        }
        return result;
    }

    private static UiElementInfo? ReadElement(IntPtr hWnd, int depth, int index)
    {
        if (!IsWindow(hWnd)) return null;
        if (!GetWindowRect(hWnd, out var rect)) return null;
        var width = Math.Max(0, rect.Right - rect.Left);
        var height = Math.Max(0, rect.Bottom - rect.Top);
        if (width <= 0 || height <= 0) return null;
        var bounds = new WindowBounds { X = rect.Left, Y = rect.Top, Width = width, Height = height };
        var name = ReadWindowText(hWnd);
        var className = ReadClassName(hWnd);
        var role = GuessRole(className);
        return new UiElementInfo
        {
            Id = hWnd.ToInt64().ToString(),
            ElementId = hWnd.ToInt64().ToString(),
            Label = $"E{index + 1}",
            Name = name,
            ClassName = className,
            Role = role,
            Bounds = bounds,
            Center = new UiPoint { X = rect.Left + width / 2, Y = rect.Top + height / 2 },
            Enabled = IsWindowEnabled(hWnd),
            Visible = IsWindowVisible(hWnd),
            Depth = depth
        };
    }

    private static string ReadWindowText(IntPtr hWnd)
    {
        var len = Math.Min(Math.Max(GetWindowTextLengthW(hWnd), 0), 512);
        var sb = new StringBuilder(len + 1);
        GetWindowTextW(hWnd, sb, sb.Capacity);
        return sb.ToString();
    }

    private static string ReadClassName(IntPtr hWnd)
    {
        var sb = new StringBuilder(256);
        GetClassNameW(hWnd, sb, sb.Capacity);
        return sb.ToString();
    }

    private static string GuessRole(string className)
    {
        var c = className.ToLowerInvariant();
        if (c.Contains("button")) return "button";
        if (c.Contains("edit") || c.Contains("textbox")) return "edit";
        if (c.Contains("list")) return "list";
        if (c.Contains("combo")) return "combo_box";
        if (c.Contains("menu")) return "menu";
        if (c.Contains("tab")) return "tab";
        if (c.Contains("static") || c.Contains("text")) return "text";
        return "pane";
    }
}

internal sealed class UiTreeSnapshot
{
    [JsonPropertyName("rootWindow")] public WindowInfo? RootWindow { get; init; }
    [JsonPropertyName("elements")] public IReadOnlyList<UiElementInfo> Elements { get; init; } = Array.Empty<UiElementInfo>();
    [JsonPropertyName("limit")] public int Limit { get; init; }
    [JsonPropertyName("truncated")] public bool Truncated { get; init; }
}

internal sealed class UiElementInfo
{
    [JsonPropertyName("id")] public string Id { get; init; } = string.Empty;
    [JsonPropertyName("elementId")] public string ElementId { get; init; } = string.Empty;
    [JsonPropertyName("label")] public string Label { get; init; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; init; } = string.Empty;
    [JsonPropertyName("className")] public string ClassName { get; init; } = string.Empty;
    [JsonPropertyName("role")] public string Role { get; init; } = string.Empty;
    [JsonPropertyName("bounds")] public WindowBounds Bounds { get; init; } = new();
    [JsonPropertyName("center")] public UiPoint Center { get; init; } = new();
    [JsonPropertyName("enabled")] public bool Enabled { get; init; }
    [JsonPropertyName("visible")] public bool Visible { get; init; }
    [JsonPropertyName("depth")] public int Depth { get; init; }
}

internal sealed class UiPoint
{
    [JsonPropertyName("x")] public int X { get; init; }
    [JsonPropertyName("y")] public int Y { get; init; }
}
