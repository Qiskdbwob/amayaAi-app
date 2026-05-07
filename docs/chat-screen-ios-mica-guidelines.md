# Chat Screen iOS/Mica Design Guidelines

Panduan ini mendokumentasikan arah redesign Chat/Home Amaya yang sedang dikerjakan: iOS-inspired floating UI dengan material mica/kaca yang tetap readable di Android Compose.

## Tujuan Visual

- Chat screen terasa seperti modern iOS workspace chat.
- Top controls dan message composer mengambang di atas konten.
- Material terlihat seperti mica/kaca: lembut, readable, tidak terlalu transparan.
- Konten chat tetap bersih dan tidak berlebihan memakai glass effect.

## Prinsip Utama

1. **Glass/mica hanya untuk functional layer**
   - Top floating bar.
   - Input composer.
   - Floating action kecil seperti menu/more.
   - Jangan gunakan glass untuk semua message, tool card, atau body content.

2. **Readability lebih penting dari blur**
   - Surface tidak boleh terlalu transparan.
   - Light mode perlu outline tipis.
   - Dark mode perlu border halus agar layer tetap terbaca.

3. **Judul selalu centered**
   - Title island berada di tengah layar.
   - Menu di kiri dan More/Refresh di kanan tidak boleh menggeser posisi title.
   - Title island memakai fixed width agar tidak melebar saat title + subtitle tampil.

4. **Chat content punya breathing room**
   - Pesan tidak boleh terlalu mepet ke top floating bar.
   - Gunakan top content padding lebih besar dari tinggi visual topbar.
   - Bottom padding mengikuti tinggi input composer.

## Top Floating Bar

### Struktur

```text
┌─────────────────────────────────────────────┐
│                                             │
│   ○        ╭────────────────────╮       ○   │
│  ☰         │ Conversation Title │       …   │
│            │   Active AI Model  │           │
│            ╰────────────────────╯           │
│                                             │
└─────────────────────────────────────────────┘
```

### Behavior Title

```kotlin
if active conversation exists and messages are not empty:
    title = conversation title from sidebar
    subtitle = active AI model name
else:
    title = active AI model name
    subtitle = ""
```

### Rules

- Do not show `Amaya Chat` in the top island.
- Do not show New Chat button in topbar.
- Center island width should stay fixed/compact.
- Keep text max 1 line with ellipsis.
- Remove decorative circles/highlights inside island if they reduce readability.

### Actions

- Left orb: drawer/menu.
- Center island: opens model selector.
- Right orb:
  - Remote mode: refresh state.
  - Local mode: open todo sheet when todos exist, otherwise session info/more.

## Mica Material Tokens

Use opaque-ish mica, not pure transparent glass.

### Light Mode

```kotlin
micaColor = Color(0xFFF7F7FA).copy(alpha = 0.94f)
orbColor = Color(0xFFFAFAFC).copy(alpha = 0.96f)
borderColor = Color.Black.copy(alpha = 0.10f)
```

### Dark Mode

```kotlin
micaColor = Color(0xFF1D1F24).copy(alpha = 0.92f)
orbColor = Color(0xFF202228).copy(alpha = 0.92f)
borderColor = Color.White.copy(alpha = 0.14f)
```

### Elevation

- Avoid heavy shadow on topbar and input composer.
- Use border + opacity for separation.
- Input composer should have `shadowElevation = 0.dp`.

## Input Composer

### Structure

```text
╭─────────────────────────────────────────╮
│  +     Message / Ask anything...    ⇧   │
╰─────────────────────────────────────────╯
```

### Rules

- Single floating capsule.
- Attach button inside left side.
- Send/stop button inside right side.
- Keep bottom scrim/blur area.
- No shadow.
- Use thin outline in light mode and subtle border in dark mode.
- Placeholder may reference workspace when active.

### Send Button

- Active send: iOS blue `#0A84FF`.
- Disabled send: low contrast surface tint.
- Streaming: stop icon with error tint.

## Message Styling

### User Bubble

- Right aligned.
- iMessage-inspired blue.

```kotlin
color = Color(0xFF0A84FF)
text = Color.White
shape = RoundedCornerShape(21.dp, 21.dp, 6.dp, 21.dp)
```

### Assistant Message

- Prefer plain readable text, not heavy bubble.
- Maintain comfortable line height.
- Tool cards remain content-layer surfaces, not glass.

## Empty/Home State

### Current Direction

- Keep dynamic greeting.
- Keep simple workspace selector.
- Remove static description such as `Start with a prompt...`.
- Remove suggestion pills from the empty state and bottom area.

### Layout

```text
┌─────────────────────────────────────────────┐
│   ○        ╭────────────────────╮       ○   │
│  ☰         │   Active AI Model  │       …   │
│            ╰────────────────────╯           │
│                                             │
│                                             │
│            What's on your mind?             │
│                                             │
│          ╭────────────────────╮             │
│          │  Folder Workspace  │             │
│          ╰────────────────────╯             │
│                                             │
│ ╭─────────────────────────────────────────╮ │
│ │  +       Message...                ⇧    │ │
│ ╰─────────────────────────────────────────╯ │
└─────────────────────────────────────────────┘
```

## Spacing

- Top chat content padding should account for the new floating bar.
- Current target: `headerDp = statusBarHeight + 84.dp`.
- If messages still feel close to the island, increase by 8–12dp.
- Bottom content padding should be derived from measured input composer height.

## Accessibility

- All orb buttons need content descriptions.
- Touch targets should stay at least 44–48dp.
- Text contrast must remain readable on mica surfaces.
- Avoid decorative effects inside the island that reduce legibility.
- Long model names and conversation titles must ellipsize.

## Main Files

- `app/src/main/java/com/amaya/intelligence/ui/screens/chat/shared/ChatScreen.kt`
  - Floating topbar, title/subtitle logic, content padding.
- `app/src/main/java/com/amaya/intelligence/ui/components/shared/ChatInput.kt`
  - Mica input composer.
- `app/src/main/java/com/amaya/intelligence/ui/components/shared/MessageBubble.kt`
  - User bubble and assistant message layout.
- `app/src/main/java/com/amaya/intelligence/ui/components/shared/WelcomeScreen.kt`
  - Dynamic empty/home state.
- `app/src/main/java/com/amaya/intelligence/ui/screens/chat/shared/ChatBottomSection.kt`
  - Bottom scrim and composer placement.

## Do / Don’t

### Do

- Keep topbar centered.
- Keep surfaces readable.
- Use border instead of heavy shadow.
- Preserve top and bottom scrims.
- Keep message content clean and focused.

### Don’t

- Don’t add `Amaya Chat` subtitle.
- Don’t add New Chat button back to topbar.
- Don’t make title island stretch between side buttons.
- Don’t add decorative circles inside the island.
- Don’t put suggestion pills back into empty state unless explicitly requested.
