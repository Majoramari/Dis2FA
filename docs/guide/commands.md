# Commands and Permissions

## Minecraft Commands

- `/discordauth help` (alias `/da`) - permission: `discordauth.use`
- `/discordauth status` - permission: `discordauth.use`
- `/discordauth reload` - permission: `discordauth.admin`
- `/discordauth randomizeid [player]` - permission: `discordauth.admin`
- `/unlink` - permission: `discordauth.use`

## Discord Slash Commands

- `/link <code>`
- `/unlink`
- `/status`
- `/help`
- `/config <get|set|reset>` (requires Manage Server or Administrator)
- `/reload` (requires Manage Server or Administrator)
- `/randomizeid [player]` (requires Manage Server or Administrator)

## Permission Nodes

- `discordauth.use` (default: true)
- `discordauth.admin` (default: op)

## Managing Config From Discord

Admins with Manage Server can change config without console access:

- `/config get <key>`: show the current value
- `/config set <key> <value>`: update and reload
- `/config reset <key>`: reset to default

Notes:

- Some keys (like `bot-token` and `discord.guild-id`) require a server restart to fully apply.
- Sensitive values like `bot-token`, `chat-bridge.webhook-url`, and `web-editor.token` are masked in `/config get`.
