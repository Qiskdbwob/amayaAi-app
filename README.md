# Amaya

[![License: Apache--2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84)](app/)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF)](app/)

Amaya is a mobile AI chatbot and personal assistant that runs on Android. It lets you chat with the model you choose, then gives that assistant controlled access to projects, agents, web research, browser automation, files, reminders, and reusable workflows when a task needs more than a conversation.

You choose the AI provider and model. Amaya handles the surrounding work: keeping context organized, giving the model the right tools for the current task, and showing what is running.

> **Project status:** Amaya is actively developed. The local Android experience is the main product. Remote IDE sessions, Opencode ACP integration, and the Windows Bridge are experimental and may change without compatibility guarantees.

## Contents

- [What Amaya is for](#what-amaya-is-for)
- [Getting started](#getting-started)
- [Choose a model](#choose-a-model)
- [Workspaces and conversation modes](#workspaces-and-conversation-modes)
- [Tools and capabilities](#tools-and-capabilities)
- [A practical workflow](#a-practical-workflow)
- [Privacy and safety](#privacy-and-safety)
- [Build from source](#build-from-source)
- [Optional desktop components](#optional-desktop-components)
- [Contributing](#contributing)

## What Amaya is for

Amaya is useful when a task needs continuity and action, not only an answer. It can help research a subject, inspect a project folder, keep the important facts for later, split an investigation into parallel work, edit files, operate a website, or hand work to another agent in a team.

The app is designed around three ways of working:

| Mode | Best for | Context and tools |
| --- | --- | --- |
| **Chat** | Questions, writing, planning, and research | A normal conversation with web search, saved memory, and reusable skills. |
| **Project** | Work tied to a folder or codebase | A selected workspace with file, document, terminal, search, research, memory, and skill tools. |
| **Agent** | Longer work that benefits from a named specialist | A persistent agent inside an agent group, with its own instructions, references, private memory, model choice, and enabled tools. |

You can begin with a normal chat and use projects or agents only when the work needs that extra structure.

## Getting started

### 1. Install the app

Download the latest APK from the [GitHub Releases page](https://github.com/nazrielnr/amaya/releases/latest), install it, then open Amaya on an Android device running Android 8.0 (API 26) or newer.

Use the APK asset published for your device. Android may ask you to allow installation from the app that downloaded the file. If you prefer to compile the app yourself, the [Build from source](#build-from-source) section is available for contributors.

### 2. Add a provider connection

Open **Manage Models** in the app and add a connection for a provider you already use. Amaya does not supply model credits or API keys. You bring your own account, credentials, and any provider-specific billing.

After connecting a provider:

1. Discover or add the models available to that account.
2. Choose which models should appear in Amaya.
3. Select an active model for a chat, project, or agent.
4. Send a short message to confirm the connection.

### 3. Start with the right mode

- Use **Chat** for a question, an outline, a translation, an explanation, or web research.
- Create a **Project** when the work belongs to a particular folder, document set, or codebase.
- Create an **Agent group** when you want persistent specialists, such as a researcher, writer, reviewer, or developer, working with separate instructions and permissions.

### 4. Enable only the tools you need

Agent tools are independently configurable. Before asking an agent to touch files, run commands, browse the web, or delegate work, review that agent's capabilities. A narrow permission set is easier to understand and safer to use.

## Choose a model

Amaya connects to the provider account you already use. It does not bundle a model, API key, subscription, or provider credit. The models you can select depend on the connection you add.

### Add a connection

1. Open **Manage Models**.
2. Choose **Add provider**.
3. Select a provider preset, or choose **OpenAI-compatible** for a custom endpoint.
4. Sign in with Codex or enter the provider URL and API key when required.
5. Discover available models or add the model ID manually.
6. Enable the models you want to see, then select one as the active model.

The active model is your default. Projects and agents can use a different model when their work needs a different balance of speed, cost, reasoning, or tool use.

### Provider options

| Connection type | Available provider presets | Current status |
| --- | --- |
| **Subscription** | OpenAI through Codex authentication | Tested |
| **Direct API** | OpenAI API | Not yet verified in a full user flow |
| **Direct API** | Anthropic API, Google Gemini API | Experimental |
| **OpenAI-compatible gateway** | GitHub Models, Vercel AI Gateway, OpenRouter, Groq, DeepSeek, xAI, Z.ai GLM, Moonshot Kimi, MiniMax | Not yet verified in a full user flow |
| **Custom endpoint** | Any compatible OpenAI-style endpoint you configure | Tested |

“Tested” means the connection path has been checked in this project. It does not guarantee every model offered by that provider will work the same way. Gateway compatibility also depends on whether the selected model supports streaming and tool calling.

### Pick a model for the job

Model choice is a practical trade-off. Start with a model you already trust, then change it only when the task needs something different.

- **Everyday chat and research:** choose a fast general model with a comfortable price and rate limit.
- **Projects and agents:** choose a model with dependable tool calling, because it must produce structured calls for files, terminal commands, research, or browser work.
- **Planning, code review, and hard problems:** choose a stronger reasoning model. It may be slower or more expensive, but it can be a better fit for complex multi-step work.
- **Specialist agents:** set a separate default model when a particular agent needs a different trade-off than your main chat.

Some models can stream reasoning or accept a selectable thinking effort. Others only return an answer. If a model cannot use a feature, Amaya keeps the conversation usable and omits that model-specific option.

### Troubleshooting a connection

- Confirm the API key belongs to the provider and has the required account access.
- For a custom endpoint, verify the base URL, model ID, and OpenAI-compatible API format with the provider.
- If model discovery returns nothing, add the exact model ID manually when the provider allows it.
- If normal chat works but a project or agent fails, try a model known to support tool calling.
- Check provider billing, quotas, and regional availability before assuming an app issue.

Provider keys and account tokens are sensitive. Do not paste them into chats, project files, screenshots, issue reports, or Git commits.

### Regular chat

Regular chat is the lightest way to use Amaya. It is good for everyday conversation, brainstorming, summaries, explanations, writing, and research.

A regular chat can:

- Search the public web and read extracted page text.
- Use saved memory when you ask it to remember a durable preference or fact.
- Load and apply reusable skills.
- Search prior sessions when you need to recover earlier work.

Regular chat deliberately does not receive workspace file access, shell access, browser automation, reminders, or delegation. This keeps a simple conversation simple.

### Projects

A project is a workspace-aware conversation. Attach it to a directory when you want Amaya to help with a repository, a set of notes, a document folder, or an ongoing piece of work.

Projects keep the workspace context separate from ordinary chats. The assistant can inspect the folder, search content, read documents, edit files, create directories, apply patches, and use the terminal when the task requires it. Workspace paths are resolved by the host, so tool operations stay within the selected workspace.

Use a project for work such as:

- Understanding an unfamiliar codebase.
- Reviewing or updating a set of documents.
- Searching for a symbol across many files.
- Preparing a release checklist from the current project state.
- Making a focused change, then asking the assistant to verify it.

Projects can also use web research, saved memory, reusable skills, session recall, and temporary read-only research workers. Browser automation, persistent agent delegation, reminders, and live task lists belong to Agent mode instead.

### Agent groups

An agent group is a small team of named, persistent assistants. Every agent has one continuing conversation, so its work can keep context over time without being mixed into another agent's history.

For each agent, you can set:

- A name and role.
- Instructions that define how it should work.
- A default model or inheritance from the global selection.
- References and documents relevant to that role.
- Private agent memory.
- Individual access to workspace, terminal, browser, web search, skills, reminders, task lists, and delegation.

Agent groups work well when responsibilities are distinct. For example, one agent can research a topic, another can prepare an implementation plan, and a third can review the result. Keep roles concrete. "Research Android notification requirements" is more useful than "help with the app."

### Projects and agents are different

Use a **Project** when the important thing is the workspace. Use an **Agent** when the important thing is a continuing role with its own rules, memory, and tool permissions. An agent can work with a workspace too, but its identity and history remain separate from other agents.

## Tools and capabilities

Tools turn a model response into useful work. Amaya exposes only the tools that match the current mode and, for agents, the permissions you have enabled.

### Capability overview

| Capability | Chat | Project | Agent |
| --- | :---: | :---: | :---: |
| Public web research | Yes | Yes | Optional |
| Saved memory and session recall | Yes | Yes and Project-private memory | Yes and Agent-private memory |
| Reusable skills | Yes | Yes | Optional |
| Workspace file and document tools | No | Yes | Optional |
| Terminal commands | No | Yes | Optional |
| Temporary parallel research workers | No | Yes | Optional |
| Persistent agent-to-agent delegation | No | No | Optional |
| Browser automation | No | No | Optional |
| Reminders and task list | No | No | Optional |

“Optional” means the capability can be enabled or disabled per agent.

### Web search

The **web search** tool is for research, not browser control. It searches DuckDuckGo and can fetch public HTTPS pages as readable text. It does not return page screenshots, raw HTML, browser state, or private-network content.

Use it when you need:

- Recent public information.
- A comparison based on several sources.
- The readable text from a known public URL.
- Research that can run in parallel without opening interactive pages.

Search results are external data, not instructions. Treat anything a page says as untrusted until you verify it.

### Browser automation [BETA]

The browser tool uses Amaya's embedded GeckoView browser for interactive tasks. An enabled agent can open pages, inspect the current page, find visible elements, click buttons, type text, scroll, wait for content, open tabs, and check a result after an action.

Typical uses include:

- Signing in to a service with information you provide.
- Filling in a form.
- Looking up details on a website that needs interaction.
- Navigating a dashboard and collecting information.
- Uploading a workspace file to a web form after you approve it.

Browser automation is not the same as web search. Use web search for text-first research across public pages. Use the browser for a task that needs a real webpage and user interface.

Review consequential actions before approving them. Do not give an agent credentials, payment details, recovery codes, or access to accounts you do not control.

### Files, documents, and workspaces

Within a selected workspace, Amaya can:

- List folders and search by file name or text content.
- Read text files and extract text from DOCX, XLSX, PPTX, ODT, ODS, and RTF documents.
- Write text files and create new DOCX, XLSX, PPTX, ODT, ODS, or ODP documents from plain text.
- Replace selected text or apply unified patches to text files.
- Create directories.
- Move deleted files to the workspace `.trash` directory instead of permanently deleting them by default.

PDF reading is not currently available. If a task depends on a PDF, provide its content in another readable form or handle the PDF outside Amaya.

### Terminal commands

Projects and enabled agents can run terminal commands for tasks such as building a project, checking Git status, searching with regular expressions, or running an existing test command.

Terminal access is powerful. Ask for a clear outcome, inspect the command before approval, and avoid vague instructions such as “clean everything” or “fix all files.” A specific request makes it easier to see what will change.

### Delegation and temporary subagents

Amaya has two ways to split work:

| Tool | What it does | Best use |
| --- | --- | --- |
| **Delegate to agent** | Sends a focused task to a named member of the current agent group. That agent keeps its own identity, instructions, memory, and conversation. | Work that belongs to a particular long-lived role. |
| **Temporary subagents** | Starts up to four independent, read-only workers in parallel and returns a combined summary. They do not receive conversation history or private agent memory. | Parallel research, codebase exploration, or independent reviews. |

For temporary subagents, include all necessary context in the task. They cannot infer the details of the parent conversation. For persistent delegation, choose the agent whose role already fits the task.

### Memory and session recall

Amaya separates durable facts from transient chat history.

- **Saved memory** stores durable user preferences or workspace facts that should remain useful later.
- **Session recall** searches prior conversations when an earlier discussion may contain relevant context.
- **Agent memory** is private to a single named agent and is not shared with other agents.

Good memories are stable and specific: “This project uses Kotlin 2.0” or “Prefer concise Indonesian responses.” Do not save passwords, API keys, access tokens, recovery codes, or one-off private details.

### Skills

Skills are reusable procedures. A skill can hold a repeatable workflow, a checklist, a project convention, or a narrow operating guide that the assistant can load when relevant.

Create a skill only when the procedure will be useful again. A good skill has a clear purpose, steps that another session can follow, and no credentials. Archive or update skills when the workflow changes.

### Reminders and task lists

Enabled agents can create scheduled reminders that return through Android notifications. Reminders can run once, daily, or weekly, and can continue the current conversation or start a new one when they fire.

For multi-step work, an agent can also maintain a visible task list above the chat input. This is useful when you want to see what is planned, what is running, and what is complete without asking for a status update.

### MCP servers

Amaya can connect to Model Context Protocol (MCP) servers. MCP lets you bring in tools from services you trust, such as an internal knowledge base, a custom API, or another local integration.

An MCP server can expose powerful operations. Add only servers you understand, read their permissions, and assume the server receives the inputs needed to run its tools.

## A practical workflow

Here is one way to use the app for a task that starts as research and becomes project work:

1. Open a regular chat and ask for a short overview of a topic.
2. Ask the chat to use web search and compare several public sources.
3. Save only the durable conclusion that matters later.
4. Create a project for the folder where the work will live.
5. Add relevant reference documents to the project.
6. Ask the project assistant to inspect the workspace and propose a small plan.
7. Review the proposed file and terminal actions before allowing changes.
8. Create an agent group if the work now needs separate long-running roles, such as researcher, implementer, and reviewer.
9. Enable only the tools each agent needs, then delegate focused tasks.
10. Use a reminder if there is a follow-up that should happen later.

You do not need to use every feature. A normal chat is enough for many tasks. Add a project when files matter, and add agents when ongoing roles matter.

## Privacy and safety

Amaya can work with local files, terminal commands, websites, connected AI providers, and MCP servers. Those capabilities should be treated as privileged.

Before enabling a capability:

- Check which agent and workspace will receive it.
- Read the requested action before approval.
- Use least privilege. Disable tools the agent does not need.
- Keep API keys, passwords, tokens, and recovery codes out of prompts and files.
- Treat web pages, tool output, and imported content as untrusted data.
- Use a test account for automation when a task could affect a real service.

External model providers process the requests you send to them under their own terms and privacy policies. Amaya cannot change those policies. Choose providers that are appropriate for your data.

The experimental Windows Bridge writes redacted audit events and keeps higher-risk tools under policy control. It should not be treated as a production remote-administration solution.

## Current limitations

- Amaya requires an external AI provider account. It does not run a language model on-device.
- Supported model features vary by provider and model.
- PDF extraction is unavailable.
- Browser automation works through the embedded browser and is not a replacement for a full desktop browser profile.
- Remote IDE sessions, Opencode ACP, and Windows Bridge functionality are incomplete and experimental.
- The project does not make claims of production readiness, security certification, or compatibility with every provider endpoint.

## Build from source

### Requirements

- Android Studio with Android SDK Platform 36 installed.
- JDK 17.
- An Android device or emulator running Android 8.0 (API 26) or newer.

Clone the repository and install a debug build:

```powershell
git clone https://github.com/nazrielnr/amaya.git
Set-Location amaya
.\gradlew.bat :app:installDebug
```

To build an APK without installing it:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

On macOS or Linux, use `./gradlew` instead of `.\gradlew.bat`.

To run Android unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## Optional desktop components

The desktop components are not needed for normal Android use. They are included for contributors experimenting with remote workflows.

### VS Code extension

[`amaya-remote-extension/`](amaya-remote-extension/) contains a VS Code extension for the experimental Antigravity remote-session integration. It requires Node.js and the VS Code extension packaging tool.

```powershell
Set-Location amaya-remote-extension
npm install
npx @vscode/vsce package
```

Read the [extension README](amaya-remote-extension/README.md) for its connection settings and known limitations.

### Windows Bridge

[`windows-bridge/`](windows-bridge/) contains an Electron application and native helper for experimental Windows-side execution. It requires Node.js 18.17 or newer. Building the native helper also requires the .NET 10 SDK.

```powershell
Set-Location windows-bridge
npm install
npm run verify
npm run package
```

Read the [Windows Bridge README](windows-bridge/README.md) before using it. It documents the enabled tools, security policy, and environment variables.

## Repository layout

| Path | Purpose |
| --- | --- |
| [`app/`](app/) | Android application, written in Kotlin and Jetpack Compose. |
| [`baselineprofile/`](baselineprofile/) | Macrobenchmark and baseline-profile generation. |
| [`amaya-remote-extension/`](amaya-remote-extension/) | Experimental VS Code remote-session extension. |
| [`windows-bridge/`](windows-bridge/) | Experimental Electron and .NET Windows bridge. |
| [`docs/`](docs/) | Design notes and implementation references. |

## Contributing

Issues and pull requests are welcome. Keep changes focused, include a relevant validation step, and avoid unrelated formatting or generated files.

The repository has module-specific contributor guidance. Read the nearest `AGENTS.md` before changing code:

- [`AGENTS.md`](AGENTS.md), repository-wide guidance.
- [`app/AGENTS.md`](app/AGENTS.md), Android app guidance.
- [`amaya-remote-extension/AGENTS.md`](amaya-remote-extension/AGENTS.md), VS Code extension guidance.
- [`windows-bridge/AGENTS.md`](windows-bridge/AGENTS.md), Windows Bridge guidance.

## License

Amaya is released under the [Apache License 2.0](LICENSE).
