using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper.Services;

/// <summary>
/// Per-window screenshot via PrintWindow(PW_RENDERFULLCONTENT). Works even when
/// the target window is partially covered by other windows, which is the main
/// gap in the default display-only capture. Returns a PNG byte buffer.
///
/// Windows.Graphics.Capture would be a more modern path (supports DirectX and
/// protected content better), but requires WinRT interop that meaningfully
/// complicates packaging. PrintWindow covers ~85% of desktop apps with zero
/// extra dependencies, so we start here and can layer WGC in later.
/// </summary>
[SupportedOSPlatform("windows")]
internal static class WindowCaptureService
{
    public static (bool Ok, string? Reason, byte[]? PngBytes, WindowBounds? Bounds, bool Partial)
        CaptureWindow(string? windowId)
    {
        if (string.IsNullOrWhiteSpace(windowId)) return (false, "windowId is required", null, null, false);
        if (!long.TryParse(windowId, out var handleValue)) return (false, "windowId must be a numeric handle", null, null, false);
        var hWnd = new IntPtr(handleValue);
        if (!NativeMethods.IsWindow(hWnd)) return (false, "window not found", null, null, false);

        if (!NativeMethods.GetWindowRect(hWnd, out var rect))
        {
            return (false, "GetWindowRect failed", null, null, false);
        }
        int width = Math.Max(1, rect.Right - rect.Left);
        int height = Math.Max(1, rect.Bottom - rect.Top);
        var bounds = new WindowBounds { X = rect.Left, Y = rect.Top, Width = width, Height = height };

        using var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using var graphics = Graphics.FromImage(bitmap);
        var hdc = graphics.GetHdc();
        bool partial = false;
        try
        {
            // PW_RENDERFULLCONTENT is required for Chromium/DXGI content; older
            // apps still work with it. If the call fails we return partial=true
            // and let the caller fall back to the display capture.
            if (!NativeMethods.PrintWindow(hWnd, hdc, NativeMethods.PW_RENDERFULLCONTENT))
            {
                partial = true;
                _ = NativeMethods.PrintWindow(hWnd, hdc, 0);
            }
        }
        finally
        {
            graphics.ReleaseHdc(hdc);
        }

        using var ms = new MemoryStream();
        bitmap.Save(ms, ImageFormat.Png);
        return (true, null, ms.ToArray(), bounds, partial);
    }
}
