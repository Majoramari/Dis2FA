# Linking and Approvals

## How Linking Works

1. Player joins and is kicked with a one-time code.
2. Player sends the code to the bot via DM or the configured link channel.
3. Bot links Discord ID to Minecraft UUID.
4. On future joins, the stored device ID is checked.
5. If the device ID changes, an approval request is sent to `alerts-channel-id`.

## Device Approvals

- Approvals are valid for `device-approval-seconds`.
- Changing `device-id.salt` will force all players to re-approve on their next login.
- If `alerts-channel-id` is blank, approvals cannot be sent.
