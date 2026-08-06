# Windows Bridge Native Helper Instructions

## Scope
- This file applies to `windows-bridge/native-helper/` and its children.
- It covers the C# Win32 helper that executes native window, input, screen, and diagnostics operations for the Windows bridge.

## Helper Rules
- Keep Win32 interop, JSON-RPC transport over stdin/stdout, and helper-side validation in this subtree.
- Keep helper outputs deterministic and avoid echoing sensitive input back in results.
- Keep transport errors recoverable where possible and map integrity/UIPI blocks explicitly.
- Do not add Electron, Node, or Android dependencies here.
- Do not edit generated `obj/` files unless rebuilding is intentional.

## Editing Guidance
- Prefer small changes in `Program.cs`, `Services/`, `Protocol/`, and `Windows/`.
- When helper behavior changes, verify the publish path and rebuild the shipped helper binary.
- Before finishing, crosscheck the touched-area diff and recent helper commits.
- If files, folders, or features change here, update this AGENTS file and the parent Windows bridge instructions in the same change.

## Build
- `dotnet publish -c Release -r win-x64 --self-contained false`
- `dotnet publish native-helper/AmayaBridgeHelper.csproj -c Release -r win-x64 --self-contained false`

## File Tree
```text
native-helper/
├─ AGENTS.md
├─ AmayaBridgeHelper.csproj
├─ Program.cs
├─ Protocol/
├─ Services/
└─ Windows/
```

## File Functions
- `AGENTS.md`: helper-specific rules and scope routing.
- `AmayaBridgeHelper.csproj`: helper project file and build settings.
- `Program.cs`: JSON-RPC entrypoint and request dispatch.
- `Protocol/`: request, response, and error DTOs.
- `Services/`: native window, input, diagnostics, capture, and health services.
- `Windows/`: Win32 interop types and models.

## Key Source Code
- `Program.cs`: stdin/stdout request loop and command dispatch.
- `Services/InputService.cs`: keyboard and mouse input execution.
- `Services/IntegrityService.cs`: integrity-level and UIPI refusal checks.
- `Services/WindowService.cs`: window enumeration and focus helpers.
- `Services/DiagnosticsService.cs`: diagnostics and health reporting.
- `Services/WindowCaptureService.cs`: screen/window capture helpers.
- `Windows/NativeMethods.cs`: Win32 P/Invoke declarations.
- `Windows/WindowInfo.cs`: native window metadata model.
