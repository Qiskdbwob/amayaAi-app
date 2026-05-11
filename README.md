# Amaya

[![Android](https://img.shields.io/badge/Android-Kotlin-3DDC84)](app/)
[![VS Code Extension](https://img.shields.io/badge/VS%20Code-Extension-007ACC)](amaya-remote-extension/)
[![Windows Bridge](https://img.shields.io/badge/Windows-Bridge-0078D6)](windows-bridge/)

Amaya is a mobile-first agent stack for Android, IDE workspace control, and Windows-side execution.

## Architecture

- **Android app** - planner, chat UI, memory, skills, browser operator, local tools.
- **VS Code extension** - IDE adapter, session bridge, workspace surface.
- **Windows bridge** - execution plane for window, input, capture, clipboard, and file actions.
- **Shared bridge contract** - message schema, tool names, risk policy, approvals, and session state.

## What it does

- Agent chat with multiple provider setups
- Local-first memory and reusable skills
- Android browser automation
- Remote workspace orchestration from mobile into an IDE
- Session sync, tool routing, and capability gating
- Windows-native tool execution through a helper process


## Quickstart

### 1. Android

```bash
./gradlew installDebug
```

### 2. VS Code extension

```bash
cd amaya-remote-extension
npm install
npx @vscode/vsce package
```

### 3. Windows bridge

```bash
cd windows-bridge
npm install
npm run verify
npm run package
```

## Screenshots

Add product screenshots here when ready.

- `docs/media/` for README images
- `docs/media/demo-android.png`
- `docs/media/demo-bridge.png`

## Demo

- Android app: chat, memory, skills, and browser control.
- IDE bridge: workspace actions from mobile into your editor session.
- Windows bridge: native window, input, and capture execution.

## Repo layout

- `app/` - Android application.
- `amaya-remote-extension/` - VS Code extension.
- `windows-bridge/` - Electron bridge and native helper.
- `docs/` - protocol notes and implementation docs.

## Roadmap

- Broaden IDE support beyond Google Antigravity.
- Expand bridge approvals and safety controls.
- Polish pairing, session recovery, and tool visibility.
- Add more demo assets and release notes.

## Current focus

The VS Code Extension is currently wired to Google Antigravity on the extension side.

## Contributing

- Read the nearest `AGENTS.md` before changing code.
- Keep changes scoped to the active module.
- Update docs when a feature or folder changes.

## License

See `LICENSE`.

## Docs

- `AGENTS.md`
- `app/AGENTS.md`
- `amaya-remote-extension/AGENTS.md`
- `windows-bridge/AGENTS.md`
