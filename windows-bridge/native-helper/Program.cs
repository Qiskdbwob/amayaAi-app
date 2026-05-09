using System.Text.Json;
using AmayaBridgeHelper.Protocol;
using AmayaBridgeHelper.Services;
using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper;

internal static class Program
{
    private static readonly JsonSerializerOptions ResponseJsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    public static async Task<int> Main(string[] args)
    {
        // Force UTF-8 on stdin/stdout so Unicode text round-trips cleanly.
        Console.InputEncoding = new System.Text.UTF8Encoding(false);
        Console.OutputEncoding = new System.Text.UTF8Encoding(false);

        await using var stdout = Console.OpenStandardOutput();
        using var writer = new StreamWriter(stdout, new System.Text.UTF8Encoding(false))
        {
            AutoFlush = true,
            NewLine = "\n"
        };
        using var reader = new StreamReader(Console.OpenStandardInput(), new System.Text.UTF8Encoding(false));

        LogStderr($"AmayaBridgeHelper online pid={Environment.ProcessId}");

        string? line;
        while ((line = await reader.ReadLineAsync()) is not null)
        {
            if (line.Length == 0) continue;
            JsonRpcResponse response = Dispatch(line);
            var json = JsonSerializer.Serialize(response, ResponseJsonOptions);
            await writer.WriteLineAsync(json);
        }
        return 0;
    }

    private static JsonRpcResponse Dispatch(string line)
    {
        JsonRpcRequest? request;
        try
        {
            request = JsonSerializer.Deserialize<JsonRpcRequest>(line);
        }
        catch (Exception ex)
        {
            LogStderr($"bad request: {ex.Message}");
            return JsonRpcResponse.Failure("unknown", new HelperError
            {
                Code = HelperErrorCode.InvalidRequest,
                Message = "malformed JSON"
            });
        }

        if (request is null || string.IsNullOrEmpty(request.Id) || string.IsNullOrEmpty(request.Method))
        {
            return JsonRpcResponse.Failure(request?.Id ?? "unknown", new HelperError
            {
                Code = HelperErrorCode.InvalidRequest,
                Message = "id and method are required"
            });
        }

        try
        {
            return request.Method switch
            {
                "health.ping" => JsonRpcResponse.Success(request.Id, HealthService.Ping()),
                "window.list" => HandleWindowList(request),
                "window.focus" => HandleWindowFocus(request),
                "window.close" => HandleWindowClose(request),
                "window.active" => HandleWindowActive(request),
                "app.open" => HandleAppOpen(request),
                "ui.tree" => HandleUiTree(request),
                "ui.find_text" => HandleUiFindText(request),
                "ui.click_element" => HandleUiClickElement(request),
                "mouse.click" => HandleMouseClick(request),
                "mouse.move" => HandleMouseMove(request),
                "mouse.scroll" => HandleMouseScroll(request),
                "mouse.drag" => HandleMouseDrag(request),
                "keyboard.type" => HandleKeyboardType(request),
                "keyboard.hotkey" => HandleKeyboardHotkey(request),
                _ => JsonRpcResponse.Failure(request.Id, new HelperError
                {
                    Code = HelperErrorCode.UnknownMethod,
                    Message = $"Unknown method: {request.Method}"
                })
            };
        }
        catch (Exception ex)
        {
            LogStderr($"dispatch error for {request.Method}: {ex.Message}");
            return JsonRpcResponse.Failure(request.Id, HelperError.From(ex));
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private static JsonRpcResponse HandleWindowList(JsonRpcRequest request)
    {
        var windows = WindowService.List();
        return JsonRpcResponse.Success(request.Id!, new { windows });
    }

    private static JsonRpcResponse HandleWindowActive(JsonRpcRequest request)
    {
        var window = WindowService.Active();
        return JsonRpcResponse.Success(request.Id!, new { window });
    }

    private static JsonRpcResponse HandleWindowFocus(JsonRpcRequest request)
    {
        var windowId = TryGetString(request.Params, "windowId");
        var (focused, reason) = WindowService.Focus(windowId);
        if (!focused)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "window not found" ? HelperErrorCode.NotFound : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "focus failed",
                Recoverable = reason != "window not found"
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { focused = true, windowId });
    }

    private static JsonRpcResponse HandleWindowClose(JsonRpcRequest request)
    {
        var windowId = TryGetString(request.Params, "windowId");
        var (closed, reason) = WindowService.Close(windowId);
        if (!closed)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "window not found" ? HelperErrorCode.NotFound : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "close failed",
                Recoverable = reason != "window not found"
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { closed = true, windowId });
    }

    private static JsonRpcResponse HandleAppOpen(JsonRpcRequest request)
    {
        var p = request.Params;
        var app = TryGetString(p, "app") ?? TryGetString(p, "name") ?? TryGetString(p, "target");
        var args = TryGetString(p, "args");
        var (ok, reason, target, processId) = AppLaunchService.Open(app, args);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "app name is not allowed" || reason == "app is required"
                    ? HelperErrorCode.InvalidArgs
                    : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "app.open failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { launched = true, target, processId });
    }

    private static JsonRpcResponse HandleUiTree(JsonRpcRequest request)
    {
        var p = request.Params;
        var windowId = TryGetString(p, "windowId");
        var limit = TryGetInt(p, "limit") ?? 250;
        var (ok, reason, snapshot) = UiAutomationService.Tree(windowId, limit);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "window not found" ? HelperErrorCode.NotFound : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "ui.tree failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, snapshot!);
    }

    private static JsonRpcResponse HandleUiFindText(JsonRpcRequest request)
    {
        var p = request.Params;
        var text = TryGetString(p, "text");
        var windowId = TryGetString(p, "windowId");
        var limit = TryGetInt(p, "limit") ?? 50;
        var (ok, reason, matches) = UiAutomationService.FindText(text, windowId, limit);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "text is required" ? HelperErrorCode.InvalidArgs : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "ui.find_text failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { matches, count = matches.Count });
    }

    private static JsonRpcResponse HandleUiClickElement(JsonRpcRequest request)
    {
        var p = request.Params;
        var elementId = TryGetString(p, "elementId") ?? TryGetString(p, "id") ?? TryGetString(p, "handle");
        var (ok, reason, element) = UiAutomationService.ClickElement(elementId);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "element not found" ? HelperErrorCode.NotFound : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "ui.click_element failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { clicked = true, element });
    }

    private static JsonRpcResponse HandleMouseClick(JsonRpcRequest request)
    {
        var p = request.Params;
        int? x = TryGetInt(p, "x");
        int? y = TryGetInt(p, "y");
        if (x is null || y is null)
        {
            return ArgsError(request.Id!, "x and y are required integers");
        }
        MouseButtonParser.TryParse(TryGetString(p, "button"), out var button);
        int clicks = TryGetInt(p, "clicks") ?? 1;

        var (ok, reason) = InputService.Click(x.Value, y.Value, button, clicks);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "coordinate outside virtual screen bounds"
                    ? HelperErrorCode.InvalidArgs
                    : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "click failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, new
        {
            clicked = true,
            x = x.Value,
            y = y.Value,
            button = button.ToString().ToLowerInvariant(),
            clicks
        });
    }

    private static JsonRpcResponse HandleMouseMove(JsonRpcRequest request)
    {
        var p = request.Params;
        int? x = TryGetInt(p, "x");
        int? y = TryGetInt(p, "y");
        if (x is null || y is null)
            return ArgsError(request.Id!, "x and y are required integers");

        int durationMs = TryGetInt(p, "durationMs") ?? 0;
        durationMs = Math.Clamp(durationMs, 0, 2000);

        var (ok, reason) = InputService.Move(x.Value, y.Value, durationMs);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason == "coordinate outside virtual screen bounds"
                    ? HelperErrorCode.InvalidArgs
                    : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "mouse.move failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { moved = true, x = x.Value, y = y.Value, durationMs });
    }

    private static JsonRpcResponse HandleMouseScroll(JsonRpcRequest request)
    {
        var p = request.Params;
        int? x = TryGetInt(p, "x");
        int? y = TryGetInt(p, "y");
        if (x is null || y is null)
            return ArgsError(request.Id!, "x and y are required integers");

        var direction = (TryGetString(p, "direction") ?? "down").ToLowerInvariant();
        if (direction is not ("up" or "down" or "left" or "right"))
            return ArgsError(request.Id!, "direction must be up, down, left, or right");

        int amount = Math.Clamp(TryGetInt(p, "amount") ?? 3, 1, 50);

        var (ok, reason) = InputService.Scroll(x.Value, y.Value, direction, amount);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason?.Contains("outside") == true
                    ? HelperErrorCode.InvalidArgs
                    : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "mouse.scroll failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { scrolled = true, x = x.Value, y = y.Value, direction, amount });
    }

    private static JsonRpcResponse HandleMouseDrag(JsonRpcRequest request)
    {
        var p = request.Params;
        int? startX = TryGetInt(p, "startX");
        int? startY = TryGetInt(p, "startY");
        int? endX = TryGetInt(p, "endX");
        int? endY = TryGetInt(p, "endY");

        if (startX is null || startY is null)
            return ArgsError(request.Id!, "startX and startY are required integers");
        if (endX is null || endY is null)
            return ArgsError(request.Id!, "endX and endY are required integers");

        MouseButtonParser.TryParse(TryGetString(p, "button"), out var button);
        int durationMs = Math.Clamp(TryGetInt(p, "durationMs") ?? 400, 50, 5000);

        // Parse optional waypoints: [{x, y}, ...]
        var waypoints = TryGetWaypoints(p, "waypoints");

        var (ok, reason) = InputService.Drag(
            startX.Value, startY.Value,
            endX.Value, endY.Value,
            button, durationMs, waypoints);

        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = reason?.Contains("outside") == true
                    ? HelperErrorCode.InvalidArgs
                    : HelperErrorCode.ExecutionFailed,
                Message = reason ?? "mouse.drag failed",
                Recoverable = true
            });
        }
        return JsonRpcResponse.Success(request.Id!, new
        {
            dragged = true,
            startX = startX.Value,
            startY = startY.Value,
            endX = endX.Value,
            endY = endY.Value,
            button = button.ToString().ToLowerInvariant(),
            durationMs,
            waypointCount = waypoints?.Count ?? 0
        });
    }

    private static JsonRpcResponse HandleKeyboardType(JsonRpcRequest request)
    {
        var p = request.Params;
        var text = TryGetString(p, "text");
        int interval = TryGetInt(p, "intervalMs") ?? 5;

        var (ok, reason, length) = InputService.Type(text, interval);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = HelperErrorCode.InvalidArgs,
                Message = reason ?? "keyboard.type failed"
            });
        }
        // Deliberately do NOT echo text back. Length only.
        return JsonRpcResponse.Success(request.Id!, new { typed = true, length });
    }

    private static JsonRpcResponse HandleKeyboardHotkey(JsonRpcRequest request)
    {
        var keys = TryGetStringArray(request.Params, "keys");
        var (ok, reason, normalized) = InputService.Hotkey(keys);
        if (!ok)
        {
            return JsonRpcResponse.Failure(request.Id!, new HelperError
            {
                Code = HelperErrorCode.InvalidArgs,
                Message = reason ?? "hotkey failed"
            });
        }
        return JsonRpcResponse.Success(request.Id!, new { pressed = true, keys = normalized });
    }

    private static JsonRpcResponse ArgsError(string id, string message) =>
        JsonRpcResponse.Failure(id, new HelperError
        {
            Code = HelperErrorCode.InvalidArgs,
            Message = message
        });

    // ── JSON helpers (static; nullable-friendly) ────────────────────────────

    private static string? TryGetString(JsonElement? element, string name)
    {
        if (element is null) return null;
        var e = element.Value;
        if (e.ValueKind != JsonValueKind.Object) return null;
        if (!e.TryGetProperty(name, out var value)) return null;
        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static int? TryGetInt(JsonElement? element, string name)
    {
        if (element is null) return null;
        var e = element.Value;
        if (e.ValueKind != JsonValueKind.Object) return null;
        if (!e.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out int n)) return n;
        if (value.ValueKind == JsonValueKind.String && int.TryParse(value.GetString(), out int s)) return s;
        return null;
    }

    private static List<string>? TryGetStringArray(JsonElement? element, string name)
    {
        if (element is null) return null;
        var e = element.Value;
        if (e.ValueKind != JsonValueKind.Object) return null;
        if (!e.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind != JsonValueKind.Array) return null;
        var list = new List<string>();
        foreach (var item in value.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.String) return null;
            var s = item.GetString();
            if (s is null) continue;
            list.Add(s);
        }
        return list;
    }

    /// <summary>
    /// Parse an optional waypoints array: [{x: int, y: int}, ...].
    /// Invalid or missing entries are silently skipped.
    /// </summary>
    private static IReadOnlyList<(int X, int Y)>? TryGetWaypoints(JsonElement? element, string name)
    {
        if (element is null) return null;
        var e = element.Value;
        if (e.ValueKind != JsonValueKind.Object) return null;
        if (!e.TryGetProperty(name, out var arr)) return null;
        if (arr.ValueKind != JsonValueKind.Array) return null;

        var list = new List<(int X, int Y)>();
        foreach (var item in arr.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            if (!item.TryGetProperty("x", out var xEl) || !item.TryGetProperty("y", out var yEl)) continue;
            if (!xEl.TryGetInt32(out int wx) || !yEl.TryGetInt32(out int wy)) continue;
            list.Add((wx, wy));
        }
        return list.Count > 0 ? list : null;
    }

    private static void LogStderr(string line)
    {
        try
        {
            Console.Error.WriteLine($"[helper] {line}");
        }
        catch
        {
            // Stderr is best-effort; never let logging kill the process.
        }
    }
}
