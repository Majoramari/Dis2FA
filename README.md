# Dis2FA

Discord-based authentication for offline-mode Paper servers. Players must link their Discord account to their Minecraft
account, and device changes require Discord approval.

## Features

- **Kick-on-join linking** - Players get kicked with a code, DM it to the bot to link
- **Offline-mode support** - Built for offline servers
- **Device approval** - IP-based device ID; new device logins require Discord approval
- **Standalone** - Bundled JDA, no DiscordSRV dependency
- **SQLite storage** - Persistent linking and approval data

## Requirements

- **Server:** Paper 1.21.11
- **Java:** 21+
- **Discord bot token**

## How It Works

1. Player joins → server kicks with a code
2. Player sends the code to the bot (DM or link channel)
3. Bot links Discord ID ↔ Minecraft UUID
4. Player rejoins → allowed if device ID matches
5. If device ID changes → bot posts an approval request in the configured channel
