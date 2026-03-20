# Troubleshooting

- Bot does not respond to DMs: ensure Message Content Intent is enabled and `bot-token` is correct.
- Linking in a guild channel does not work: set `discord.link-channel-id` and ensure `discord.allow-guild-link` is true.
- No approval messages appear: set `alerts-channel-id` and ensure the bot can send messages there.
- Chat bridge only works one way: ensure `chat-bridge.webhook-url` is set and `chat-bridge.discord-channel-id` is set.
- Slash commands missing or outdated: set `discord.guild-id` and restart, or clear global commands.
