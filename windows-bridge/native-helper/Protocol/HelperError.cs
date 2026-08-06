using System.Text.Json.Serialization;

namespace AmayaBridgeHelper.Protocol;

/// <summary>
/// Stable helper error codes. Electron maps these onto bridge
/// <c>BridgeToolErrorCode</c> values.
/// </summary>
internal static class HelperErrorCode
{
    public const string InvalidRequest = "INVALID_REQUEST";
    public const string InvalidArgs = "INVALID_ARGS";
    public const string UnknownMethod = "UNKNOWN_METHOD";
    public const string ExecutionFailed = "EXECUTION_FAILED";
    public const string NotFound = "NOT_FOUND";
    public const string PermissionDenied = "PERMISSION_DENIED";
    public const string Unsupported = "UNSUPPORTED";
}

internal sealed class HelperError
{
    [JsonPropertyName("code")]
    public string Code { get; init; } = HelperErrorCode.ExecutionFailed;

    [JsonPropertyName("message")]
    public string Message { get; init; } = "Helper failed.";

    [JsonPropertyName("recoverable")]
    public bool Recoverable { get; init; }

    [JsonPropertyName("details")]
    public Dictionary<string, object?>? Details { get; init; }

    public static HelperError From(Exception ex, string code = HelperErrorCode.ExecutionFailed)
        => new()
        {
            Code = code,
            Message = ex.Message,
            Recoverable = true,
            Details = new Dictionary<string, object?> { ["type"] = ex.GetType().Name }
        };
}
