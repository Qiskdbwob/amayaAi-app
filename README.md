<p align="center">
  <img src="docs/assets/amaya-logo.png" alt="Amaya" width="500">
</p>

<p align="center">
  <strong>Android AI chatbot and personal assistant.</strong><br>
  Chat, projects, persistent agents, web research, browser automation, and local tools.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-blue.svg" alt="Apache License 2.0"></a>
  <a href="app/"><img src="https://img.shields.io/badge/Platform-Android-3DDC84" alt="Android"></a>
  <a href="app/"><img src="https://img.shields.io/badge/Language-Kotlin-7F52FF" alt="Kotlin"></a>
</p>

<p align="center">
  Amaya runs on your Android phone. Choose an AI provider and model, then give the assistant the context and tools needed for the task.
</p>

## Features

| Feature | What it does |
| --- | --- |
| **Chat** | Everyday conversations with web research, saved memory, skills, and session recall. |
| **Projects** | Workspace-aware work for repositories, folders, and document collections. |
| **Agent groups** | Persistent specialists with their own instructions, memory, models, and permissions. |
| **Browser automation** | Interactive website tasks in Amaya's embedded browser. |
| **Local tools** | Read, search, edit, and organize workspace files, or run terminal commands. |
| **MCP** | Connect tools and services through Model Context Protocol servers. |
| **Reminders** | Schedule follow-up work through Android notifications. |

## Quick start

1. Download the latest APK from [GitHub Releases](https://github.com/nazrielnr/amaya/releases/latest). Android 8.0 (API 26) or newer is required.
2. Open **Manage Models**, add a provider connection, then enable the models you want to use.
3. Pick a default model for chat. Projects and agents can use their own model later.
4. Start a chat, create a project when files matter, or create an agent group for ongoing specialist work.

Amaya does not supply model subscriptions, API keys, or provider credits. Use your own account.

## Work modes

| Mode | Best for | Available capabilities |
| --- | --- | --- |
| **Chat** | Questions, writing, planning, and research | Web research, saved memory, skills, and session recall. |
| **Project** | A repository, folder, or document set | Workspace files, documents, terminal commands, research, memory, and skills. |
| **Agent group** | Long-running specialist roles | Per-agent instructions, private memory, model selection, and configurable tools. |

Use Chat for a simple conversation. Move to a Project when the assistant needs a workspace. Use an Agent group when the work benefits from distinct, persistent roles.

## Previews

| Chat | Project | Agent |
| --- | --- | --- |
| <img src="docs/assets/chat-demo.gif" alt="Chat preview" width="240"> | <img src="docs/assets/project-demo.gif" alt="Project preview" width="240"> | <img src="docs/assets/agent-preview.gif" alt="Agent preview" width="240"> |

## Provider support

| Connection | Providers | Status |
| --- | --- | --- |
| **Subscription** | OpenAI through Codex authentication | Tested |
| **Direct API** | OpenAI API | Validation in progress |
| **Direct API** | Anthropic API, Google Gemini API | Experimental |
| **Compatible gateway** | GitHub Models, OpenRouter, Groq, DeepSeek, Moonshot Kimi, MiniMax, xAI, Z.ai GLM, Vercel AI Gateway | Validation in progress |
| **Custom endpoint** | Any OpenAI-compatible endpoint | Tested |

The model list comes from the connection you configure. Compatibility depends on the selected model supporting streaming and tool calling.

## Capabilities

| Capability | Chat | Project | Agent |
| --- | :---: | :---: | :---: |
| Web research | Yes | Yes | Yes (Configurable) |
| Saved memory and session recall | Yes | Yes | Yes with Private agent memory |
| Workspace files and documents | No | Yes | Yes (Configurable) |
| Terminal commands | No | Yes | Configurable |
| Temporary parallel research | No | Yes | Yes (Configurable) |
| Agent-to-agent delegation | No | No | Yes (Configurable) |
| Browser automation | No | No | Yes (Configurable) |
| Reminders and task lists | No | No | Yes (Configurable) |

## Privacy and safety

You choose the provider, model, workspace, and tools available to an agent. Review tool permissions before enabling them, and keep API keys, passwords, recovery codes, and other secrets outside conversations.

Web pages, tool output, and MCP servers are external inputs. Treat them as untrusted until you verify them.

## Current limitations

- An external AI provider is required. Amaya does not run a language model on-device.
- DOCX, XLSX, PDF extraction is unavailable. 
- Browser automation works only inside the embedded browser.
- Windows Bridge, Remote IDE, and Opencode ACP are experimental.

## Build from source

```bash
git clone https://github.com/nazrielnr/amaya.git
cd amaya

# Windows
.\gradlew.bat :app:installDebug

# macOS or Linux
./gradlew :app:installDebug
```

To build an APK, run `.\gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` on macOS/Linux.

## Repository

| Path | Purpose |
| --- | --- |
| [`app/`](app/) | Android application |
| [`baselineprofile/`](baselineprofile/) | Android baseline-profile generator |
| [`amaya-remote-extension/`](amaya-remote-extension/) | Experimental VS Code remote-session extension |
| [`windows-bridge/`](windows-bridge/) | Experimental Windows execution bridge |
| [`docs/`](docs/) | Project notes and references |

## Contributing

Contributions are welcome. Read the nearest [`AGENTS.md`](AGENTS.md), keep pull requests focused, and include a relevant validation step.

## License

Licensed under the [Apache License 2.0](LICENSE).
