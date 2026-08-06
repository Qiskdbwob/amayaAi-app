using System.Text.Json;
using System.Text.Json.Serialization;

namespace AmayaBridgeHelper.Protocol;

/// <summary>
/// Single-line JSON-RPC request read from stdin.
/// <para>Shape mirrors <c>windows-bridge/src/native/native-helper-protocol.ts</c>.</para>
/// </summary>
internal sealed class JsonRpcRequest
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("method")]
    public string? Method { get; set; }

    /// <summary>
    /// Raw params node. Typed parsing is done per-method to avoid a giant
    /// discriminated union here.
    /// </summary>
    [JsonPropertyName("params")]
    public JsonElement? Params { get; set; }
}
