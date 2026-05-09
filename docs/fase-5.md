# Phase 5 — Windows Native Helper MVP for Amaya Bridge

## Context

Phase 1 selesai:
- Android punya shared bridge protocol di `app/src/main/java/com/amaya/intelligence/domain/bridge/`.

Phase 2 selesai:
- Android punya `WindowsBridgeSessionClient`.
- Android bisa send/receive `BridgeEnvelope`.

Phase 3A selesai:
- Android punya bridge tool adapter:
  - `WindowsBridgeToolExecutor`
  - `WindowsBridgeToolRegistry`
  - `WindowsBridgeToolProvider`
  - mapper result/error/approval.

Phase 3B selesai:
- Bridge tools sudah masuk ke agent path secara controlled.
- Bridge tools hanya muncul ketika Windows Bridge connected.
- Mouse/keyboard/clipboard.write gated behind Agent Control.
- HIGH-risk tools masih disabled.

Phase 4 selesai:
- Folder `windows-bridge/` sudah ada sebagai Electron/TypeScript standalone project.
- WebSocket server jalan di `0.0.0.0:17878`.
- Protocol TypeScript mirror sudah ada.
- `screen.capture` sudah real via Electron.
- `window.list` masih stub/limited.
- `mouse.click`, `keyboard.type`, `keyboard.hotkey` masih stub.
- Audit JSONL sudah ada.
- Tray/status window sudah ada.
- Emergency stop sudah ada.
- Build Windows Bridge dan Android clean.

Sekarang lanjut ke **Phase 5**.

---

## Product Direction

Amaya Android tetap menjadi:

```txt
AI planner
chat UI
approval UI
memory/context owner
session controller

Windows Bridge tetap menjadi:

remote executor
permission gate
audit logger
screen/input/window tool host

Phase 5 menambahkan Native Helper agar Windows Bridge bisa melakukan aksi Windows asli secara aman.

Native Helper bukan agent.

Native Helper hanya menerima command dari Electron melalui JSON-RPC, mengeksekusi aksi native, lalu mengembalikan result/error.

Phase 5 Goal

Buat native helper MVP untuk Windows.

Target utama:

window.list real
mouse.click real
keyboard.type real
keyboard.hotkey real
helper health check
JSON-RPC stdin/stdout
Electron integration
safe failure handling

Jangan implement dulu:

shell.run
file.write
file.delete
clipboard.read
browser automation
full UI Automation tree
installer packaging
privilege escalation
credential access
Recommended Stack

Gunakan:

C# .NET
Console app
JSON-RPC over stdin/stdout
Electron spawn child_process

Native helper output:

AmayaBridgeHelper.exe

Build command:

dotnet publish -c Release -r win-x64 --self-contained false

Jika repo/environment lebih cocok self-contained, boleh siapkan script untuk:

dotnet publish -c Release -r win-x64 --self-contained true

Tapi default Phase 5 cukup framework-dependent.

Required Folder

Tambahkan di:

windows-bridge/native-helper/

Struktur yang disarankan:

windows-bridge/native-helper/
├─ AmayaBridgeHelper.csproj
├─ Program.cs
├─ Protocol/
│  ├─ JsonRpcRequest.cs
│  ├─ JsonRpcResponse.cs
│  └─ HelperError.cs
├─ Services/
│  ├─ HealthService.cs
│  ├─ WindowService.cs
│  ├─ InputService.cs
│  └─ ScreenInfoService.cs
├─ Windows/
│  ├─ NativeMethods.cs
│  ├─ WindowInfo.cs
│  └─ InputModels.cs
└─ README.md

Boleh sesuaikan struktur, tapi helper harus tetap kecil dan fokus.

JSON-RPC Protocol

Electron → Helper:

{
  "id": "req_001",
  "method": "mouse.click",
  "params": {
    "x": 720,
    "y": 420,
    "button": "left"
  }
}

Helper → Electron success:

{
  "id": "req_001",
  "ok": true,
  "result": {
    "clicked": true
  }
}

Helper → Electron error:

{
  "id": "req_001",
  "ok": false,
  "error": {
    "code": "EXECUTION_FAILED",
    "message": "Failed to click at coordinate.",
    "recoverable": true
  }
}

Rules:

- One JSON object per line.
- Never write logs to stdout.
- stdout hanya untuk JSON response.
- stderr boleh untuk diagnostic logs.
- Invalid JSON must return structured error if request id is available.
- Unknown method must return UNKNOWN_METHOD.
- Missing params must return INVALID_ARGS.
- Native failure must return EXECUTION_FAILED.
Required Helper Methods
1. health.ping

Purpose:

Verify helper is alive.

Request:

{
  "id": "req_ping",
  "method": "health.ping",
  "params": {}
}

Result:

{
  "id": "req_ping",
  "ok": true,
  "result": {
    "status": "ok",
    "helper": "AmayaBridgeHelper",
    "platform": "windows"
  }
}
2. window.list

Purpose:

Return real visible top-level windows.

Use Win32 APIs:

EnumWindows
IsWindowVisible
GetWindowText
GetWindowThreadProcessId
GetWindowRect

Return:

{
  "windows": [
    {
      "id": "123456",
      "title": "Visual Studio Code",
      "processId": 1234,
      "processName": "Code",
      "bounds": {
        "x": 0,
        "y": 0,
        "width": 1280,
        "height": 720
      },
      "visible": true,
      "focused": false
    }
  ]
}

Rules:

- Exclude empty title windows by default.
- Exclude invisible windows.
- Do not crash if process name cannot be read.
- Do not require admin.
- Do not enumerate child controls yet.
3. window.focus

Purpose:

Focus top-level window by handle/id.

Use:

SetForegroundWindow
ShowWindow if minimized

Request:

{
  "id": "req_focus",
  "method": "window.focus",
  "params": {
    "windowId": "123456"
  }
}

Result:

{
  "focused": true
}

Rules:

- Return clear error if window not found.
- Do not force elevation.
- If Windows blocks foreground activation, return recoverable error.
4. mouse.click

Purpose:

Click screen coordinate.

Use Win32:

SetCursorPos
SendInput

Request:

{
  "id": "req_click",
  "method": "mouse.click",
  "params": {
    "x": 720,
    "y": 420,
    "button": "left",
    "clicks": 1
  }
}

Support:

button: left | right | middle
clicks: 1 or 2

Result:

{
  "clicked": true,
  "x": 720,
  "y": 420,
  "button": "left",
  "clicks": 1
}

Safety:

- Validate coordinate is within virtual screen bounds.
- Reject invalid button.
- Reject clicks count > 2.
5. keyboard.type

Purpose:

Type plain text.

Use:

SendInput with Unicode chars

Request:

{
  "id": "req_type",
  "method": "keyboard.type",
  "params": {
    "text": "hello world",
    "intervalMs": 5
  }
}

Result:

{
  "typed": true,
  "length": 11
}

Safety:

- Reject text larger than 5000 chars.
- intervalMs min 0 max 100.
- Do not log text.
- Do not echo full text in result.
6. keyboard.hotkey

Purpose:

Press key combination.

Request:

{
  "id": "req_hotkey",
  "method": "keyboard.hotkey",
  "params": {
    "keys": ["CTRL", "L"]
  }
}

Support common keys:

CTRL
SHIFT
ALT
WIN
ENTER
TAB
ESC
BACKSPACE
DELETE
SPACE
A-Z
0-9
F1-F12
ARROW_UP
ARROW_DOWN
ARROW_LEFT
ARROW_RIGHT

Result:

{
  "pressed": true,
  "keys": ["CTRL", "L"]
}

Safety:

- Reject empty key list.
- Reject more than 4 keys.
- Reject unknown keys.
- Do not implement dangerous hardcoded blocks here yet; Electron risk engine handles policy.
Electron Integration

Update windows-bridge/src/ to spawn helper.

Add files:

windows-bridge/src/native/
├─ native-helper-client.ts
├─ native-helper-protocol.ts
└─ native-helper-errors.ts

NativeHelperClient responsibilities:

- spawn AmayaBridgeHelper.exe
- send JSON-RPC line
- map id → pending promise
- timeout request
- parse response line
- restart helper if it exits unexpectedly
- expose health()
- expose invoke(method, params)
- dispose on app quit

Do not write token/full payload to logs.

Tool Integration in Electron

Replace stubs with helper calls:

window.list

Before:

stub / bridge-owned windows only

After:

call native helper method window.list
window.focus

Add enabled MEDIUM tool if not already listed.

call native helper method window.focus
mouse.click

Before:

structured error stub

After:

if risk engine allows, call native helper mouse.click
keyboard.type

Before:

structured error stub

After:

if risk engine allows, call native helper keyboard.type
keyboard.hotkey

Before:

structured error stub

After:

if risk engine allows, call native helper keyboard.hotkey

Keep these gated:

mouse.click
keyboard.type
keyboard.hotkey
window.focus

They require:

session connected
Agent Control enabled
emergency stop false
Tool Registry Update

Phase 5 enabled tools:

screen.capture
window.list
window.focus
mouse.click
keyboard.type
keyboard.hotkey
clipboard.write

Still disabled:

clipboard.read
file.list
file.read
file.write
file.delete
shell.run
shell.cancel
browser.open
browser.goto
browser.dom
browser.click
browser.type
browser.screenshot
ui.tree
ui.find_text
ui.click_element

Risk:

screen.capture   LOW
window.list      LOW
window.focus     MEDIUM
mouse.click      MEDIUM
keyboard.type    MEDIUM
keyboard.hotkey  MEDIUM
clipboard.write  MEDIUM

clipboard.read   HIGH disabled
shell.run        HIGH disabled
file.write       HIGH disabled
file.delete      HIGH disabled
Audit Requirements

Every native tool call must audit:

tool_requested
tool_started
tool_succeeded
tool_failed

argsPreview rules:

- For mouse.click: x/y/button/clicks okay.
- For keyboard.type: do not log text. Log length only.
- For keyboard.hotkey: log keys.
- For window.focus: log windowId only.

Never log:

password
token
secret
authorization
apiKey
typed text
imageBase64
Status UI Update

Update status window/tray to show helper status:

helper: running / stopped / error
helper pid
last helper error

Optional buttons:

restart helper

Do not overbuild UI.

Build Scripts

Update windows-bridge/package.json scripts if needed:

{
  "scripts": {
    "typecheck": "...",
    "build": "...",
    "build:helper": "dotnet publish native-helper/AmayaBridgeHelper.csproj -c Release -r win-x64 --self-contained false",
    "start": "...",
    "clean": "..."
  }
}

Ensure Electron build can find helper exe from predictable path, for example:

windows-bridge/native-helper/bin/Release/net8.0-windows/win-x64/publish/AmayaBridgeHelper.exe

or copy into:

windows-bridge/dist/native/AmayaBridgeHelper.exe

Pick one and document it.

Constraints

Wajib:

- Jangan ubah Android app behavior.
- Jangan sentuh Antigravity.
- Jangan sentuh Android local ToolExecutor.
- Jangan implement shell.run.
- Jangan implement file write/delete.
- Jangan implement browser automation.
- Jangan implement clipboard.read.
- Jangan implement credential access.
- Jangan require admin.
- Jangan fake success.
- Jangan log typed text.
- Jangan write non-JSON logs to helper stdout.

Boleh:

- Tambah C# native helper project.
- Tambah Electron native-helper client.
- Replace Phase 4 stubs for mouse/keyboard/window with real helper calls.
- Tambah helper status ke tray/status window.
- Tambah README docs.