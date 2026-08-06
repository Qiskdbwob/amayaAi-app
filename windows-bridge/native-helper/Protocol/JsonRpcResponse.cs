using System.Text.Json.Serialization;

namespace AmayaBridgeHelper.Protocol;

internal sealed class JsonRpcResponse
{
    [JsonPropertyName("id")]
    public string Id { get; init; } = string.Empty;

    [JsonPropertyName("ok")]
    public bool Ok { get; init; }

    [JsonPropertyName("result")]
    public object? Result { get; init; }

    [JsonPropertyName("error")]
    public HelperError? Error { get; init; }

    public static JsonRpcResponse Success(string id, object? result) =>
        new() { Id = id, Ok = true, Result = result };

    public static JsonRpcResponse Failure(string id, HelperError error) =>
        new() { Id = id, Ok = false, Error = error };
}
