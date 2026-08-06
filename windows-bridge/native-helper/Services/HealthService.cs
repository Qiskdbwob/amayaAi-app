namespace AmayaBridgeHelper.Services;

internal static class HealthService
{
    public static object Ping() => new
    {
        status = "ok",
        helper = "AmayaBridgeHelper",
        platform = "windows",
        pid = Environment.ProcessId,
        version = "0.1.0"
    };
}
