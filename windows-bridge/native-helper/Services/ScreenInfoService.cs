using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper.Services;

internal static class ScreenInfoService
{
    public static (int X, int Y, int Width, int Height) VirtualScreenBounds()
    {
        int x = NativeMethods.GetSystemMetrics(NativeMethods.SM_XVIRTUALSCREEN);
        int y = NativeMethods.GetSystemMetrics(NativeMethods.SM_YVIRTUALSCREEN);
        int w = NativeMethods.GetSystemMetrics(NativeMethods.SM_CXVIRTUALSCREEN);
        int h = NativeMethods.GetSystemMetrics(NativeMethods.SM_CYVIRTUALSCREEN);
        // Guard against bogus zero size — fall back to a sane 1920x1080 area so the
        // coordinate validator still works on unusual display setups.
        if (w <= 0 || h <= 0)
        {
            w = 1920;
            h = 1080;
        }
        return (x, y, w, h);
    }
}
