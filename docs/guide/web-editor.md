# Web Config Editor

The web editor lets you view and update `config.yml` from a browser.

## Setup

1. Set `web-editor.enabled: true`.
2. Keep `web-editor.bind-address` as `127.0.0.1` for local-only access.
3. Set `web-editor.port` if you want a different port.
4. Note the `web-editor.token` value in `config.yml`.
5. Reload or restart the server.
6. Open `http://127.0.0.1:8166` and enter the token.

## Notes

- The editor requires the token for all API calls.
- Sensitive values stay hidden unless you update them.
- If you bind to `0.0.0.0` or a public IP, use a firewall or reverse proxy and treat the token as a password.
- Changing `web-editor.*` settings may restart the editor.
