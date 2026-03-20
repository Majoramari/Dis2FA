# Setup

## Requirements

- Java 17+
- Discord bot token

## Installation

1. Download the correct jar for your server platform.
2. Drop the jar into `plugins/`.
3. Start the server once to generate `plugins/Dis2FA/config.yml`.
4. Edit `config.yml` with your Discord bot token and channel IDs.
5. Restart the server.
6. Run `/da status` to verify configuration.

## Discord Bot Setup

1. Create a Discord application and add a bot.
2. Copy the bot token into `bot-token` in `config.yml`.
3. Enable privileged intents in the Discord Developer Portal:
   Server Members Intent and Message Content Intent.
4. Invite the bot to your server with permissions:
   View Channels, Send Messages, Embed Links, Read Message History, Use Application Commands.
5. If you use the chat bridge webhook, create the webhook in the target channel and copy the URL into
   `chat-bridge.webhook-url`.

## Getting Discord IDs

1. Enable Developer Mode in Discord (User Settings -> Advanced).
2. Right-click a server, channel, or role and choose Copy ID.
3. Use those IDs for `discord.guild-id`, `alerts-channel-id`, `discord.link-channel-id`, and
   `discord.allowed-role-ids`.

## Minimal Config Example

Minimum config to get linking and approvals working:

```yaml
bot-token: "YOUR_BOT_TOKEN"
discord-invite: "discord.gg/yourinvite"
alerts-channel-id: "123456789012345678"
discord:
  guild-id: "123456789012345678"
  allow-guild-link: true
  link-channel-id: "123456789012345678"
```

Notes:

- `discord.guild-id` is strongly recommended so slash commands register quickly.
- `alerts-channel-id` is required for device approval requests.
- `discord.link-channel-id` is where link buttons and link codes are handled. If empty, it falls back to
  `chat-bridge.discord-channel-id`, then `alerts-channel-id`.
- `discord.allowed-role-ids` limits access to specific Discord roles.
- `discord.require-member` forces users to be in the Discord server.
