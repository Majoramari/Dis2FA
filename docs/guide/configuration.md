# Configuration Reference

## Core Settings

- `bot-token`: Discord bot token. Required for linking.
- `discord-invite`: Invite shown in the kick message.
- `alerts-channel-id`: Channel for device approval requests. Required for approvals.
- `locale`: Language file to use (`en` or `ar`).
- `fallback-locale`: Fallback language if a key is missing.

## Discord Settings

- `discord.guild-id`: The Discord server (guild) ID. Recommended for faster slash command registration.
- `discord.clear-global-commands`: Clears global commands on startup to avoid stale commands.
- `discord.bot-name`: Bot name used in messages. Leave empty to auto-detect.
- `discord.link-channel-name`: Channel name shown in messages. Leave empty to auto-detect.
- `discord.allowed-role-ids`: List of allowed role IDs. Empty allows all roles.
- `discord.require-member`: Require the player to be in the Discord server.
- `discord.member-check-timeout-seconds`: REST lookup timeout when checking membership, roles, or bans.
- `discord.allow-guild-link`: Allow linking by sending codes in a guild channel.
- `discord.link-channel-id`: Channel where link buttons and link codes are handled. If empty, it falls back to
  `chat-bridge.discord-channel-id`, then `alerts-channel-id`.

## Presence

- `discord.presence.status`: `online`, `idle`, `dnd`, or `invisible`.
- `discord.presence.activity.type`: `playing`, `listening`, `watching`, `competing`, or `custom`.
- `discord.presence.activity.text`: Text or `off` to disable. Supports `{PLAYERS}`, `{MAX_PLAYERS}`, `{TPS}`.
- `discord.presence.update-seconds`: How often to refresh placeholders.

## Ban Sync

- `ban-sync.enabled`: If true, check Discord bans on join.
- `ban-sync.apply-minecraft-ban`: If true, also ban the player in Minecraft.
- `ban-sync.ban-reason`: Reason used for the Minecraft ban.

## Verification and Device Approvals

- `code-length`: Code length (4 to 8 digits).
- `code-expiration-seconds`: How long a code is valid.
- `device-approval-seconds`: How long approvals are valid.

## Device ID

- `device-id.salt`: Random salt used to hash UUID+IP. Auto-generated if empty.
- `device-id.ip-prefix-v4`: Prefix length for IPv4. Lower values reduce sensitivity to IP changes.
- `device-id.ip-prefix-v6`: Prefix length for IPv6.

## Chat Bridge

- `chat-bridge.enabled`: Enable Discord chat bridge.
- `chat-bridge.discord-channel-id`: Channel ID to read Discord messages from.
- `chat-bridge.webhook-url`: Webhook URL for sending Minecraft chat to Discord.
- `chat-bridge.webhook-username-format`: Webhook username template. Supports `{PLAYER}`.
- `chat-bridge.avatar-url`: Avatar URL template. Supports `{PLAYER}`.
- `chat-bridge.minecraft-format`: Format for Discord -> Minecraft. Supports `{USER}`, `{MESSAGE}`.
- `chat-bridge.discord-format`: Format for Minecraft -> Discord. Supports `{USER}`, `{MESSAGE}`.
- `chat-bridge.bridge-joins`: Bridge joins.
- `chat-bridge.bridge-quits`: Bridge quits.
- `chat-bridge.bridge-deaths`: Bridge deaths.
- `chat-bridge.bridge-advancements`: Bridge advancements.
- `chat-bridge.join-format`: Join format. Supports `{PLAYER}`, `{MESSAGE}`.
- `chat-bridge.quit-format`: Quit format. Supports `{PLAYER}`, `{MESSAGE}`.
- `chat-bridge.death-format`: Death format. Supports `{PLAYER}`, `{MESSAGE}`.
- `chat-bridge.advancement-format`: Advancement format. Supports `{PLAYER}`, `{ADVANCEMENT}`.

## Web Editor

- `web-editor.enabled`: Enable the web editor (default: true).
- `web-editor.bind-address`: Bind address for the HTTP server (default: `0.0.0.0`).
- `web-editor.port`: Port for the editor.
- `web-editor.token`: Access token (auto-generated if empty). Optional if you use `/da web`.
- `web-editor.public-url`: Public URL used in magic links (optional).
