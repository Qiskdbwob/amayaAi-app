# Android Browser Use Toolcall

Local AI Browser Operator sekarang memakai satu parent toolcall `browser`. Semua aksi kecil berjalan sebagai nested sub-toolcall `browser.open_url`, `browser.get_dom`, `browser.click`, dan seterusnya. Agent tidak menerima raw DOM/HTML besar secara default; response selalu JSON ringkas, aman, dan observability-first.

## Modul

- `impl/local/browser/AndroidBrowserController.kt` — kontrol WebView nyata.
- `impl/local/browser/DomInspector.kt` — DOM inspector aman dengan `interactive_summary`, `element_id`, selector map, dan redaksi data sensitif.
- `impl/local/browser/BrowserSessionManager.kt` — parent BrowserTask, nested sub-toolcall state, session state, pause/resume/cancel.
- `impl/local/browser/BrowserResponseFormatter.kt` — format JSON konsisten untuk parent dan sub-tool.
- `impl/local/browser/SafetyGuard.kt` — deteksi username, password, OTP, payment, dan data pribadi.
- `tools/BrowserUseToolset.kt` — hanya `browser` yang dipublikasikan ke agent; tool lama tetap alias kompatibilitas.
- `ui/components/shared/BrowserToolCallCard.kt` — satu card Browser di chat dengan sub-tool expandable.
- `ui/screens/browser/BrowserOperatorScreen.kt` — fullscreen live browser view.
- `docs/browser-toolcall-schema.ts` — TypeScript schema response.

## Tool yang dipakai agent

```json
{
  "name": "browser",
  "arguments": {
    "task": "Buka Wikipedia Elon Musk dan baca elemen utama",
    "steps": [
      { "action": "new_tab", "params": { "url": "https://en.wikipedia.org/wiki/Elon_Musk" } },
      { "action": "get_dom", "params": { "mode": "interactive_summary" } },
      { "action": "find_element", "params": { "query": "Early life" } }
    ],
    "reset_task": true
  }
}
```

Single-step juga bisa:

```json
{
  "name": "browser",
  "arguments": {
    "task": "Klik tombol login",
    "action": "click",
    "params": { "element_id": "el_login_btn" }
  }
}
```

## Format parent response

```json
{
  "id": "browser_task_001",
  "tool": "browser",
  "type": "parent_toolcall",
  "status": "completed",
  "summary": "Membuka halaman Wikipedia Elon Musk",
  "session_id": "sess_android_001",
  "browser_id": "browser_local_001",
  "active_page_id": "page_001",
  "active_url": "https://en.wikipedia.org/wiki/Elon_Musk",
  "progress": {
    "current_step": 3,
    "total_steps": 3,
    "label": "Find Element"
  },
  "sub_toolcalls": [
    {
      "id": "call_001",
      "tool": "browser.new_tab",
      "status": "success",
      "summary": "Loaded https://en.wikipedia.org/wiki/Elon_Musk"
    },
    {
      "id": "call_002",
      "tool": "browser.get_dom",
      "status": "success",
      "summary": "Elemen utama halaman berhasil dibaca"
    }
  ],
  "ui": {
    "expandable": true,
    "show_as_single_chat_tool": true,
    "nested_subtools": true
  }
}
```

## `get_dom` default

Default `mode` adalah `interactive_summary`. Tidak ada full HTML. Tidak ada value password/OTP/token.

```json
{
  "tool": "browser.get_dom",
  "status": "success",
  "result": {
    "page": {
      "url": "https://example.com/login",
      "title": "Login"
    },
    "dom": {
      "mode": "interactive_summary",
      "nodes_count": 3,
      "truncated": false,
      "interactive_elements": [
        {
          "element_id": "el_email_1",
          "tag": "input",
          "role": "textbox",
          "type": "email",
          "name": "email",
          "placeholder": "Email address",
          "text_preview": "",
          "selector": "input[name=\"email\"]",
          "visible": true,
          "enabled": true,
          "sensitive": true,
          "sensitive_type": "email_login"
        },
        {
          "element_id": "el_password_2",
          "tag": "input",
          "type": "password",
          "text_preview": "",
          "selector": "input[type=\"password\"]",
          "visible": true,
          "enabled": true,
          "sensitive": true,
          "sensitive_type": "password"
        }
      ],
      "forms": [
        {
          "form_id": "form_login_1",
          "purpose": "login",
          "fields": ["el_email_1", "el_password_2"],
          "submit_element_id": "el_login_btn_3",
          "sensitive": true
        }
      ],
      "sensitive_fields": 2
    }
  }
}
```

## Sensitive input pause

Jika agent mencoba mengetik/klik/clear field sensitif, response sub-tool menjadi `paused`.

```json
{
  "tool": "browser.type_text",
  "status": "paused",
  "result": null,
  "safety": {
    "sensitive_detected": true,
    "sensitive_type": "password",
    "requires_user_decision": true,
    "reason": "Sensitive input detected. Amaya will not read or fill login, OTP, payment, or private data without permission.",
    "allowed_next_actions": [
      "fill_manually",
      "allow_once",
      "allow_for_this_site",
      "skip_step",
      "cancel_task",
      "resume_after_user_done"
    ]
  },
  "ui": {
    "summary": "Field password terdeteksi. Menunggu keputusan user.",
    "agent_status": "waiting_input",
    "expandable": true
  }
}
```

## Event stream ringkas

```txt
User prompt
  ↓
browser(reset_task=true, steps=[...])
  ↓
BrowserTask parent dibuat
  ↓
sub-tool browser.new_tab success
  ↓
sub-tool browser.get_dom success dengan interactive_summary
  ↓
sub-tool browser.type_text paused jika sensitif
  ↓
fullscreen browser dan chat card membaca state/result yang sama
  ↓
user fill manual / allow once / skip / cancel / resume
```
