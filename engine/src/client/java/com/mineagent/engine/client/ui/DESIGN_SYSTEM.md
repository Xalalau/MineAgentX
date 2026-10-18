# MineAgent Client UI Design System

The NeoForge-only client uses `engine/src/client`. Keep Minecraft's
native pixel style without stacking multiple equally prominent borders over the world view.

## Hierarchy

1. Each full-screen view has exactly one main panel.
2. Titles sit in a 24px-high graphite header; teal is reserved for the top accent line and selected states.
3. Use text and 1px separators for sections, rather than nested decorative cards inside the main panel.
4. Every EditBox must have a separate, visible label. Hints explain what an empty value means; they never replace labels.
5. Keep the HUD compact by default and away from the screen center. Put long content only in dismissible screens.

## Colors

| Purpose | ARGB | Notes |
|---|---:|---|
| Main panel | `0xF0181D20` | High-opacity graphite |
| Header | `0xF0242B2F` | Subtle contrast with the content area |
| Border | `0xFF4B565B` | Neutral gray; avoid highlighting the entire border |
| Accent | `0xFF55C2AA` | Muted teal, only for status and the top line |
| Primary text | `0xFFF1F5F4` | Titles and key status information |
| Secondary text | `0xFFAAB4B6` | Labels and supporting status information |

## Layout

- Use `centeredPanel` to constrain every panel's size before calculating its coordinates.
- Standard outer margins are 10px, panel padding is 12px, and buttons/input fields are 20px high.
- The unified creation and connection screen uses two columns of fields, with 2px between each label and its input.
- Local provider presets (Bonsai/MTP and native Ollama) use one compact cycling selector, shown only when enough height is available. Short GUI layouts must still retain protocol, model, optional local server key, URL, identity, and actions.
- The help screen uses scissor clipping and a shared scroll offset. Never clamp each text line separately.
- The chat footer always has three rows: reflex toggles, companion actions, and input. Never use left/right floating button groups at any width.

## Screen Responsibilities

| Screen | Responsibility |
|---|---|
| Control Panel | Status and top-level navigation |
| Configure & Create | Name, local protocol adapter (Bonsai/MTP or native Ollama), editable model ID, optional local server API key, local/private-LAN Base URL, and server-default reasoning policy (`auto` or blank) for this creation |
| Runtime Settings | Temperature, default name, and server-default reasoning policy (`auto`) for online companions |
| Appearance | Built-in skins and player skins |
| Companion Chat | Conversation, reflex toggles, and companion removal |
| Help & Commands | Scrollable command and keybinding reference |

All visible text uses `en_us.json`. Do not mix languages or embed `§` color codes
in Java layout code.
