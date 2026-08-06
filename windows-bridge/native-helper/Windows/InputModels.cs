namespace AmayaBridgeHelper.Windows;

internal enum MouseButton
{
    Left,
    Right,
    Middle
}

internal static class MouseButtonParser
{
    public static bool TryParse(string? value, out MouseButton result)
    {
        switch ((value ?? string.Empty).ToLowerInvariant())
        {
            case "":
            case "left":
                result = MouseButton.Left;
                return true;
            case "right":
                result = MouseButton.Right;
                return true;
            case "middle":
                result = MouseButton.Middle;
                return true;
            default:
                result = MouseButton.Left;
                return false;
        }
    }
}
