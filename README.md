# Dis2FA

Dis2FA is a Discord-based authentication plugin designed for offline-mode Minecraft servers. Players must link their
Discord account to their Minecraft account, and device changes require Discord approval.

Download from [Modrinth](https://modrinth.com/project/gFVau8h5)

## Features

- Kick-on-join linking with one-time codes
- Device approval when IP-based device ID changes
- Optional Discord role gating
- Optional Discord-to-Minecraft ban sync
- Discord chat bridge (Discord <-> Minecraft)
- Built-in Discord bot (JDA), no DiscordSRV dependency
- Web config editor with magic link login (`/da web`)
- SQLite storage for links and device requests

## Compatibility

- Bukkit/Spigot/Purpur: 1.18.2+ (use `Dis2FA-bukkit-<version>.jar`)
- Paper: 1.20.2+ (use `Dis2FA-paper-<version>.jar`)
- Folia: 1.20.2+ (use `Dis2FA-folia-<version>.jar`)

## Requirements

- Java 17+
- Discord bot token

## Quick Start

1. Pick the correct jar for your server type.
2. Drop it into `plugins/` and start the server once to generate `config.yml`.
3. Edit `config.yml` with your bot token and channel IDs.
4. Use [Minimal config](https://majoramari.github.io/Dis2FA/guide/setup.html#minimal-config-example) as a starter
5. Restart the server.
6. Verify with `/da status`.

## Video Tutorial :
> If you can record better tutorial submit it as an Issue/PR 🤍
[![Dis2FA Setup Guide](https://img.youtube.com/vi/kVws_iXo7lc/maxresdefault.jpg)](https://www.youtube.com/watch?v=kVws_iXo7lc)



## Build From Source

```bash
./gradlew build
```

Artifacts:

- `bukkit/build/libs/Dis2FA-bukkit-<version>.jar`
- `paper/build/libs/Dis2FA-paper-<version>.jar`
- `folia/build/libs/Dis2FA-folia-<version>.jar`
