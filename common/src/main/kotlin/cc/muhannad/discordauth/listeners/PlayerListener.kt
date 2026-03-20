package cc.muhannad.discordauth.listeners

import cc.muhannad.discordauth.Dis2FAPlugin
import cc.muhannad.discordauth.utils.HashUtil
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.sql.SQLException
import java.util.UUID

class PlayerListener(private val plugin: Dis2FAPlugin) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        val uuid = event.uniqueId
        val playerName = event.name
        val ip = event.address.hostAddress
        val now = System.currentTimeMillis()

        val link = plugin.database.getLink(uuid)
        if (link == null) {
            val code = createPendingCode(uuid, playerName, ip, now)
            val message = plugin.kickMessageUnlinked(code)
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message)
            plugin.sendLinkRequest(playerName)
            return
        }

        val guild = plugin.resolveGuild()
        var member: net.dv8tion.jda.api.entities.Member? = null
        if (plugin.configManager.getGuildId().isNotBlank()) {
            if (guild == null) {
                val message = plugin.configManager.formatKickMessage("discord-unavailable")
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message)
                return
            }

            if (plugin.configManager.isBanSyncEnabled()) {
                val banned = plugin.isDiscordBanned(guild, link.discordId)
                if (banned) {
                    if (plugin.configManager.isApplyMinecraftBan()) {
                        plugin.runSync {
                            plugin.platform.banPlayer(
                                playerName = playerName,
                                uuid = uuid,
                                reason = plugin.configManager.getBanReason()
                            )
                        }
                    }
                    val message = plugin.configManager.formatKickMessage("discord-banned")
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message)
                    return
                }
            }

            member = plugin.fetchMember(guild, link.discordId)
            val allowedRoles = plugin.configManager.getAllowedRoleIds()
            if ((plugin.configManager.requireMember() || allowedRoles.isNotEmpty()) && member == null) {
                val message = plugin.configManager.formatKickMessage(
                    "not-in-guild",
                    mapOf("DISCORD_INVITE" to plugin.configManager.getDiscordInvite())
                )
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message)
                return
            }

            if (member != null && !plugin.hasAllowedRole(member)) {
                val message = plugin.configManager.formatKickMessage("role-required")
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message)
                return
            }
        }

        val deviceId = plugin.computeDeviceId(uuid, ip)

        if (link.deviceId == null) {
            plugin.database.updateLinkDevice(uuid, deviceId, ip, now)
            return
        }

        if (link.deviceId == deviceId) {
            plugin.database.touchLink(uuid, playerName, ip, now)
            return
        }

        val existing = plugin.database.getPendingDeviceRequest(uuid, deviceId, now)
        if (existing == null) {
            val requestId = UUID.randomUUID().toString().replace("-", "")
            val expiresAt = now + plugin.configManager.getDeviceApprovalSeconds() * 1000
            plugin.database.insertDeviceRequest(
                requestId = requestId,
                uuid = uuid,
                discordId = link.discordId,
                oldDeviceId = link.deviceId,
                newDeviceId = deviceId,
                newIp = ip,
                createdAt = now,
                expiresAt = expiresAt
            )

            plugin.sendDeviceApprovalRequest(
                requestId = requestId,
                linkDiscordId = link.discordId,
                avatarUrl = member?.user?.effectiveAvatarUrl
            )
        }

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.kickMessageDeviceChange())
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val link = plugin.database.getLink(player.uniqueId) ?: return
        player.sendMessage(plugin.configManager.formatKickMessage("joined-linked"))
    }

    private fun createPendingCode(uuid: UUID, playerName: String, ip: String, now: Long): String {
        val expiresAt = now + plugin.configManager.getCodeExpirationSeconds() * 1000
        plugin.database.deletePendingCodesForUuid(uuid)

        repeat(5) {
            val code = HashUtil.generateCode(plugin.configManager.getCodeLength())
            try {
                plugin.database.insertPendingCode(code, uuid, playerName, ip, now, expiresAt)
                return code
            } catch (_: SQLException) {
                // Collision, try again
            }
        }

        // Fallback: digits from UUID (match code length)
        val length = plugin.configManager.getCodeLength()
        val digits = uuid.toString().filter { it.isDigit() }
        val fallback = if (digits.length >= length) {
            digits.takeLast(length)
        } else {
            digits.padStart(length, '0')
        }
        plugin.database.insertPendingCode(fallback, uuid, playerName, ip, now, expiresAt)
        return fallback
    }
}
