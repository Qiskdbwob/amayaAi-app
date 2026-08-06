using System.Diagnostics;
using System.Text.RegularExpressions;

namespace AmayaBridgeHelper.Services;

internal static partial class AppLaunchService
{
    private const int MaxArgsLength = 512;

    private static readonly Dictionary<string, string> Aliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["chrome"] = "chrome",
        ["google chrome"] = "chrome",
        ["msedge"] = "msedge",
        ["edge"] = "msedge",
        ["microsoft edge"] = "msedge",
        ["notepad"] = "notepad",
        ["explorer"] = "explorer",
        ["file explorer"] = "explorer",
        ["taskmgr"] = "taskmgr",
        ["task manager"] = "taskmgr",
        ["calculator"] = "calc",
        ["calc"] = "calc",
        ["settings"] = "ms-settings:",
        ["terminal"] = "wt",
        ["windows terminal"] = "wt"
    };

    public static (bool Ok, string? Reason, string Target, int? ProcessId) Open(string? app, string? args)
    {
        if (string.IsNullOrWhiteSpace(app)) return (false, "app is required", string.Empty, null);
        var target = ResolveTarget(app);
        if (target is null) return (false, "app name is not allowed", app.Trim(), null);

        var cleanArgs = args?.Trim();
        if (cleanArgs?.Length > MaxArgsLength) return (false, $"args exceeds {MaxArgsLength} chars", target, null);

        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = target,
                UseShellExecute = true
            };
            if (!string.IsNullOrWhiteSpace(cleanArgs)) startInfo.Arguments = cleanArgs;
            using var process = Process.Start(startInfo);
            return (true, null, target, process?.Id);
        }
        catch (Exception ex)
        {
            return (false, ex.Message, target, null);
        }
    }

    private static string? ResolveTarget(string raw)
    {
        var key = raw.Trim();
        if (Aliases.TryGetValue(key, out var alias)) return alias;

        // Permit simple executable commands only; do not accept paths, URLs, or shell metacharacters here.
        if (!SimpleExecutableNameRegex().IsMatch(key)) return null;
        return key.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? key : key;
    }

    [GeneratedRegex("^[A-Za-z0-9_.-]{1,64}$")]
    private static partial Regex SimpleExecutableNameRegex();
}
