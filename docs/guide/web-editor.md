# Web Config Editor

The web editor lets you view and update `config.yml` from a browser. It also includes a Linked Players page for
managing Discord links.

## Setup

1. Ensure `web-editor.enabled: true` (default).
2. Keep `web-editor.bind-address` as `127.0.0.1` for local access (default). Use `0.0.0.0` for external access.
3. Set `web-editor.port` if you want a different port.
4. Reload or restart the server.
5. Open `http://YOUR_SERVER_IP:8166`.

## Access Methods

### Magic Link (Recommended)

Run this from console or an OP account:

```
/da web
```

This returns a one-time link that logs you in without a token. The link expires after 5 minutes and the session lasts
about 12 hours. If you need a different host (reverse proxy or domain), use:

```
/da web your.domain.com
```

You can also pass a full URL (including `https://`) if needed.

### Token

If you prefer token access, use the `web-editor.token` value from `config.yml`. The token is stored in your browser
local storage for convenience.

## Linked Players Page

The **Linked Players** tab lets you:

- Unlink a Discord account from a Minecraft UUID.
- Randomize a device ID to force a new device approval on next join.

## Language

Use the **Language** dropdown to update the `locale` setting directly from the page.

## Notes

- Sensitive values stay hidden unless you update them.
- If you bind to `0.0.0.0` or a public IP, use a firewall or reverse proxy and treat access like a password.
- Changing `web-editor.*` settings may restart the editor.
