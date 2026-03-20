# Chat Bridge

## Requirements

For two-way sync, set both:

- `chat-bridge.discord-channel-id`
- `chat-bridge.webhook-url`

## Formats and Placeholders

- `chat-bridge.minecraft-format` uses `{USER}` and `{MESSAGE}`
- `chat-bridge.discord-format` uses `{USER}` and `{MESSAGE}`
- `chat-bridge.join-format` uses `{PLAYER}` and `{MESSAGE}`
- `chat-bridge.quit-format` uses `{PLAYER}` and `{MESSAGE}`
- `chat-bridge.death-format` uses `{PLAYER}` and `{MESSAGE}`
- `chat-bridge.advancement-format` uses `{PLAYER}` and `{ADVANCEMENT}`

## Notes

- Discord -> Minecraft uses `chat-bridge.minecraft-format`.
- Minecraft -> Discord uses the webhook and `chat-bridge.discord-format`.
