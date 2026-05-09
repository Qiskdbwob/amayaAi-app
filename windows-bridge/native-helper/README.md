# Amaya Bridge — Native Helper (Phase 5 MVP)

C# .NET 8 console app spawned by the Electron Windows Bridge to execute Win32
actions that Node cannot do natively (real window enumeration, focus, mouse,
keyboard).

## Scope (Phase 5)

- `health.ping`
- `window.list` (real, via `EnumWindows`)
- `window.focus`
- `mouse.click`
- `keyboard.type`
- `keyboard.hotkey`

Explicitly out of scope: `shell.run`, `file.*`, `clipboard.read`, browser
automation, UI Automation tree, credential access, privilege escalation.

## Transport

One JSON object per line over stdin/stdout.

Stdout **only** carries JSON-RPC responses. Diagnostics go to stderr.

Request:

```json
{ "id": "req_001", "method": "mouse.click", "params": { "x": 720, "y": 420, "button": "left" } }
```

Success:

```json
{ "id": "req_001", "ok": true, "result": { "clicked": true } }
```

Error:

```json
{
  "id": "req_001",
  "ok": false,
  "error": { "code": "EXECUTION_FAILED", "message": "…", "recoverable": true }
}
```

## Build

Requires the **.NET 10 SDK** (`dotnet --list-sdks` must include `10.x`).

```powershell
cd windows-bridge/native-helper
dotnet publish -c Release -r win-x64 --self-contained false
```

Publish output:

```
windows-bridge/native-helper/bin/Release/net10.0-windows/win-x64/publish/AmayaBridgeHelper.exe
```

Electron looks for the helper at:

1. `$env:AMAYA_BRIDGE_HELPER_PATH` (override), or
2. `dist/native/AmayaBridgeHelper.exe` (copied from the publish folder), or
3. the publish folder above as a fallback during dev.

## Safety defaults

- Process never elevates. Foreground focus may be blocked by Windows — that's
  reported as a recoverable error, not swallowed.
- `keyboard.type` rejects text > 5000 chars and `intervalMs` outside 0–100 ms.
- `keyboard.hotkey` rejects empty lists, lists longer than 4, or unknown keys.
- `mouse.click` validates coordinates against the virtual screen and caps
  `clicks` at 2.
- The helper never echoes typed text back in the result — only the length.
