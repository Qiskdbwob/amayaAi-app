using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper.Services;

/// <summary>
/// Handles app.launch and app.list operations.
/// app.launch: start an application by executable name or full path.
/// app.list:   enumerate running processes with window titles.
/// </summary>
internal static class AppService
{
    // ── app.list ─────────────────────────────────────────────────────────────

    /// <summary>
    /// Returns a snapshot of running processes that have a visible main window.
    /// Each entry includes processId, processName, mainWindowTitle, and whether
    /// the process is the current foreground window owner.
    /// </summary>
    public static List<RunningAppInfo> List()
    {
        var foreground = NativeMethods.GetForegroundWindow();
        var result = new List<RunningAppInfo>();

        foreach (var proc in Process.GetProcesses())
        {
            try
            {
                // Skip processes without a visible main window.
                if (proc.MainWindowHandle == IntPtr.Zero) continue;
                if (!NativeMethods.IsWindowVisible(proc.MainWindowHandle)) continue;

                var title = proc.MainWindowTitle;
                if (string.IsNullOrWhiteSpace(title)) continue;

                result.Add(new RunningAppInfo
                {
                    ProcessId = proc.Id,
                    ProcessName = proc.ProcessName,
                    MainWindowTitle = title,
                    IsForeground = proc.MainWindowHandle == foreground
                });
            }
            catch
            {
                // Access denied or process exited — skip silently.
            }
            finally
            {
                proc.Dispose();
            }
        }

        return result;
    }

    // ── app.launch ───────────────────────────────────────────────────────────

    /// <summary>
    /// Launch an application.
    /// <para>
    /// <paramref name="target"/> can be:
    ///   - A bare executable name like "notepad" or "notepad.exe" (resolved via PATH / known locations).
    ///   - A full absolute path like "C:\Windows\System32\notepad.exe".
    ///   - A registered app alias like "ms-settings:" (shell: URI).
    /// </para>
    /// <paramref name="arguments"/> are passed verbatim to the process.
    /// <paramref name="waitForWindowMs"/> — if > 0, poll for a visible window up to this many ms.
    /// </summary>
    public static (bool Ok, string? Reason, LaunchResult? Info) Launch(
        string? target,
        string? arguments,
        int waitForWindowMs)
    {
        if (string.IsNullOrWhiteSpace(target))
            return (false, "target is required", null);

        // Resolve the executable path.
        string resolved = ResolveTarget(target.Trim());

        ProcessStartInfo psi;
        if (resolved.StartsWith("shell:", StringComparison.OrdinalIgnoreCase) ||
            resolved.Contains("://") ||
            resolved.StartsWith("ms-", StringComparison.OrdinalIgnoreCase))
        {
            // Shell URI — use ShellExecute.
            psi = new ProcessStartInfo
            {
                FileName = resolved,
                Arguments = arguments ?? string.Empty,
                UseShellExecute = true
            };
        }
        else
        {
            psi = new ProcessStartInfo
            {
                FileName = resolved,
                Arguments = arguments ?? string.Empty,
                UseShellExecute = false,
                CreateNoWindow = false
            };
        }

        Process? proc;
        try
        {
            proc = Process.Start(psi);
        }
        catch (Exception ex)
        {
            // Fallback: try with UseShellExecute = true (handles .lnk, registered apps, etc.)
            try
            {
                psi.UseShellExecute = true;
                proc = Process.Start(psi);
            }
            catch
            {
                return (false, $"Failed to launch '{target}': {ex.Message}", null);
            }
        }

        if (proc is null)
            return (false, $"Process.Start returned null for '{target}'", null);

        int pid = proc.Id;
        string procName = proc.ProcessName;

        // Optionally wait for a visible window to appear.
        string? windowTitle = null;
        if (waitForWindowMs > 0)
        {
            windowTitle = WaitForWindow(proc, waitForWindowMs);
        }

        proc.Dispose();

        return (true, null, new LaunchResult
        {
            ProcessId = pid,
            ProcessName = procName,
            MainWindowTitle = windowTitle
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Poll until the process has a visible main window or the timeout expires.
    /// Returns the window title if found, null otherwise.
    /// </summary>
    private static string? WaitForWindow(Process proc, int timeoutMs)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < deadline)
        {
            try
            {
                proc.Refresh();
                if (proc.HasExited) break;
                if (proc.MainWindowHandle != IntPtr.Zero &&
                    NativeMethods.IsWindowVisible(proc.MainWindowHandle))
                {
                    var title = proc.MainWindowTitle;
                    if (!string.IsNullOrWhiteSpace(title)) return title;
                }
            }
            catch
            {
                break;
            }
            Thread.Sleep(150);
        }
        return null;
    }

    /// <summary>
    /// Resolve a bare name or full path to a launchable target string.
    /// Order: full path → PATH lookup → known Windows locations → return as-is.
    /// </summary>
    private static string ResolveTarget(string target)
    {
        // Already an absolute path.
        if (Path.IsPathRooted(target) && File.Exists(target))
            return target;

        // Try adding .exe if missing.
        var withExe = target.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
            ? target
            : target + ".exe";

        // Search PATH.
        var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
        foreach (var dir in pathEnv.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            var candidate = Path.Combine(dir.Trim(), withExe);
            if (File.Exists(candidate)) return candidate;
        }

        // Known Windows locations.
        var knownDirs = new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.System),
            Environment.GetFolderPath(Environment.SpecialFolder.SystemX86),
            Environment.GetFolderPath(Environment.SpecialFolder.Windows),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Windows NT", "Accessories"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Windows NT", "Accessories")
        };
        foreach (var dir in knownDirs)
        {
            if (string.IsNullOrEmpty(dir)) continue;
            var candidate = Path.Combine(dir, withExe);
            if (File.Exists(candidate)) return candidate;
        }

        // Return original — let the OS / ShellExecute handle it.
        return target;
    }
}

internal sealed class RunningAppInfo
{
    public int ProcessId { get; init; }
    public string ProcessName { get; init; } = string.Empty;
    public string MainWindowTitle { get; init; } = string.Empty;
    public bool IsForeground { get; init; }
}

internal sealed class LaunchResult
{
    public int ProcessId { get; init; }
    public string ProcessName { get; init; } = string.Empty;
    public string? MainWindowTitle { get; init; }
}
