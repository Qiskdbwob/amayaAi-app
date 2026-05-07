# Settings Screen iOS Grouped Design Guidelines

Panduan ini mendokumentasikan arah polish `LocalSettingsScreen`: iOS-inspired grouped settings, calm, monochrome, compact, dan mudah dibaca.

## Tujuan Visual

- Settings terasa seperti iOS grouped settings, bukan dashboard menu.
- UI lebih tenang: minim warna, minim dekorasi, tanpa gradient lead icon.
- Informasi ringkas, cepat discan, dan konsisten.
- Top area tetap mengikuti implementasi existing; fokus polish ada di content/settings rows.

## Prinsip Utama

1. **Grouped list over cards**
   - Section dipisah dengan header kecil dan spacing.
   - Row berada di dalam satu grouped surface.
   - Separator dipakai antar row, bukan shadow/elevation.

2. **Monochrome icon system**
   - Jangan pakai gradient/colorful lead icon di Settings.
   - Icon harus netral, kecil, dan konsisten.
   - Warna aksen hanya untuk status penting/destructive/update jika benar-benar perlu.

3. **Compact copy**
   - Subtitle harus pendek dan scan-friendly.
   - Hindari kalimat panjang.
   - Gunakan status ringkas seperti `12 saved · review`, `2 sources`, `Tap to check`.

4. **Readability first**
   - Surface settings harus solid, bukan transparent glass.
   - Border tipis cukup untuk memisahkan surface dari grouped background.
   - Text hierarchy jelas: title > subtitle > section header.

## Layout Concept

```text
┌─────────────────────────────────────────────┐
│  ○ Back                                     │
│  Settings                                   │
│                                             │
│  WORKSPACE                                  │
│  ╭───────────────────────────────────────╮  │
│  │ ●  Current Workspace              ›   │  │
│  │    my-project                         │  │
│  ╰───────────────────────────────────────╯  │
│                                             │
│  AI                                         │
│  ╭───────────────────────────────────────╮  │
│  │ ●  Agents                         ›   │  │
│  │    Models & providers                 │  │
│  ├───────────────────────────────────────┤  │
│  │ ●  Persona                        ›   │  │
│  │    Voice & behavior                   │  │
│  ├───────────────────────────────────────┤  │
│  │ ●  Memory                         ›   │  │
│  │    12 saved · review                  │  │
│  ╰───────────────────────────────────────╯  │
└─────────────────────────────────────────────┘
```

## Color Tokens

### Light Mode

```kotlin
groupedBackground = Color(0xFFF2F2F7)
groupSurface = Color.White
border = Color.Black.copy(alpha = 0.08f)
separator = Color(0xFF3C3C43).copy(alpha = 0.13f)
iconBackground = Color(0xFFE9E9EE)
iconTint = Color(0xFF5F6368)
primaryText = Color(0xFF1C1C1E)
secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f)
headerText = Color(0xFF3C3C43).copy(alpha = 0.52f)
```

### Dark Mode

```kotlin
groupedBackground = Color(0xFF0B0B0F)
groupSurface = Color(0xFF1C1C1E)
border = Color.White.copy(alpha = 0.10f)
separator = Color.White.copy(alpha = 0.10f)
iconBackground = Color(0xFF2C2C2E)
iconTint = Color(0xFFC7C7CC)
primaryText = Color(0xFFF2F2F7)
secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f)
headerText = Color(0xFFEBEBF5).copy(alpha = 0.48f)
```

## Section Rules

```text
HEADER
╭────────────────────────────╮
│ Row                        │
├────────────────────────────┤
│ Row                        │
╰────────────────────────────╯
```

- Header uppercase.
- Header color low-contrast.
- Header starts at `16.dp`.
- Section vertical spacing: around `22.dp`.
- Group radius: `16.dp`.
- Surface shadow: none.
- Surface border: `0.7.dp`.

## Row Rules

- Horizontal padding: `16.dp`.
- Vertical padding: `10.dp`.
- Icon size container: `32.dp`.
- Icon glyph: `17.dp`.
- Icon shape: circle.
- Icon-text gap: `12.dp`.
- Chevron size: `18.dp`.
- Chevron tint: secondary text with reduced alpha.
- Title max lines: 1.
- Subtitle max lines: 2, but prefer short copy.

## Typography

### Row Title

```kotlin
MaterialTheme.typography.bodyLarge.copy(
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 19.sp
)
```

### Row Subtitle

```kotlin
MaterialTheme.typography.bodyMedium.copy(
    fontSize = 12.5.sp,
    lineHeight = 16.sp
)
```

### Section Header

```kotlin
MaterialTheme.typography.labelMedium.copy(
    fontWeight = FontWeight.Medium
)
```

## Icon System

### Do

```text
● neutral circle icon
● same size across rows
● same tint across rows
```

### Don’t

```text
■ colorful gradient icon
■ random icon colors per row
■ large launcher-style icon container
```

Implementation:

```kotlin
Box(
    modifier = Modifier
        .size(32.dp)
        .clip(CircleShape)
        .background(colors.iconBackground),
    contentAlignment = Alignment.Center
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = colors.iconTint,
        modifier = Modifier.size(17.dp)
    )
}
```

## Subtitle Copy Guidelines

Keep subtitles short.

| Row | Preferred Subtitle |
| --- | --- |
| Agents | `Models & providers` |
| Persona | `Voice & behavior` |
| Memory | `12 saved · review` |
| Skills | `3 enabled · 1 active` |
| Context & Recall | `2 sources` |
| Review | `4 pending` |
| Privacy & Safety | `Memory rules` |
| Reminders & Jobs | `Schedules` |
| MCP Servers | `2 of 3 active` |
| Help & Feedback | `GitHub` or existing short copy |
| Check for Update | `Tap to check` |

Avoid:

```text
Voice, tone, and behavior
Local memory rules and confirmations
Tap to check for new releases
```

Prefer:

```text
Voice & behavior
Memory rules
Tap to check
```

## Grouping

Current preferred grouping:

```text
Workspace
- Current Workspace

AI
- Agents
- Persona
- Memory
- Skills
- Context & Recall
- Review
- Privacy & Safety

Automation
- Reminders & Jobs
- MCP Servers

Appearance
- Theme

About
- Version
- Help & Feedback
- Check for Update
```

## Top Area

Current top area is intentionally preserved.

- Keep existing `TopAppBar` and `SettingsBackButton` unless a future task explicitly targets top area.
- Keep top scrim.
- Do not introduce ChatScreen-style floating title island here by default.

## Main File

- `app/src/main/java/com/amaya/intelligence/ui/screens/settings/local/LocalSettingsScreen.kt`

Local composables used:

- `IosSettingsSection`
- `IosSettingsRow`
- `IosSettingsIcon`
- `IosSettingsDivider`
- `IosThemeRow`
- `iosSettingsColors`

## Do / Don’t

### Do

- Use grouped list surfaces.
- Keep icons neutral and circular.
- Keep copy short.
- Use border/separators instead of shadows.
- Test light and dark mode.

### Don’t

- Don’t use colorful gradient lead icons.
- Don’t make rows look like dashboard cards.
- Don’t use long subtitles.
- Don’t add heavy glass/blur inside settings content.
- Don’t change top area unless explicitly requested.
