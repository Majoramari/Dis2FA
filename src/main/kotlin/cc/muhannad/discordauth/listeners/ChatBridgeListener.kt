package cc.muhannad.discordauth.listeners

import cc.muhannad.discordauth.Dis2FAPlugin
import cc.muhannad.discordauth.utils.WebhookSender
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.entity.PlayerDeathEvent

class ChatBridgeListener(private val plugin: Dis2FAPlugin) : Listener {

    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val player = event.player
        val message = plain.serialize(event.message()).trim()
        if (message.isBlank()) return

        val content = plugin.configManager.formatDiscordChat(player.name, message)
        sendForPlayer(player.name, content)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeJoins()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val player = event.player
        val base = event.joinMessage()?.let { plain.serialize(it).trim() } ?: ""
        val content = plugin.configManager.formatJoinBridge(player.name, base)
        if (content.isBlank()) return
        sendSystemEmbed(player.name, content, 0x2ecc71, true)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeQuits()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val player = event.player
        val base = event.quitMessage()?.let { plain.serialize(it).trim() } ?: ""
        val content = plugin.configManager.formatQuitBridge(player.name, base)
        if (content.isBlank()) return
        sendSystemEmbed(player.name, content, 0xe74c3c, true)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeDeaths()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val player = event.entity
        val raw = event.deathMessage()?.let { plain.serialize(it).trim() } ?: ""
        if (raw.isBlank()) return

        val displayName = plain.serialize(player.displayName()).trim()
        val message = sanitizeDeathMessage(raw, displayName, player.name, event)
        val content = plugin.configManager.formatDeathBridge(player.name, message)
        if (content.isBlank()) return
        sendSystemEmbed(player.name, content, 0xe67e22, true)
    }

    @EventHandler
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeAdvancements()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val display = event.advancement.display ?: return
        if (!display.doesAnnounceToChat()) return

        val title = plain.serialize(display.title()).trim()
        if (title.isBlank()) return

        val player = event.player
        val content = plugin.configManager.formatAdvancementBridge(player.name, title)
        if (content.isBlank()) return
        sendSystemEmbed(player.name, content, 0x3498db, false)
    }

    private fun sendForPlayer(playerName: String, content: String) {
        val webhookUrl = plugin.configManager.getWebhookUrl()
        if (webhookUrl.isBlank()) return

        val username = plugin.configManager.formatWebhookUsername(playerName)
        val avatarUrl = plugin.configManager.formatAvatarUrl(playerName)
        WebhookSender.send(webhookUrl, username, avatarUrl, content)
    }

    private fun sendSystemEmbed(playerName: String, content: String, color: Int, bold: Boolean) {
        val webhookUrl = plugin.configManager.getWebhookUrl()
        if (webhookUrl.isBlank()) return

        val username = plugin.configManager.formatWebhookUsername(playerName)
        val avatarUrl = plugin.configManager.formatAvatarUrl(playerName)
        val description = if (bold) "**${content.trim()}**" else content.trim()
        if (description.isBlank()) return
        WebhookSender.sendEmbed(webhookUrl, username, avatarUrl, description, color)
    }

    private fun stripLeadingName(text: String, playerName: String): String {
        val prefix = "$playerName "
        return if (text.startsWith(prefix)) text.substring(prefix.length) else text
    }

    private fun sanitizeDeathMessage(raw: String, displayName: String, playerName: String, event: PlayerDeathEvent): String {
        var message = raw

        val mobName = resolveMobName(event)
        if (!mobName.isNullOrBlank()) {
            message = message.replace("[mobName]", mobName)
            message = message.replace("{MOB}", mobName)
        }

        if (displayName.isNotBlank()) {
            message = message.replace("[playerDisplayName]", displayName)
            message = message.replace("{PLAYER_DISPLAY}", displayName)
        }
        if (playerName.isNotBlank()) {
            message = message.replace("[playerName]", playerName)
            message = message.replace("{PLAYER}", playerName)
        }

        message = stripLegacyColors(message)
        message = removePlayerPrefix(message, displayName, playerName)

        return message.trim()
    }

    private fun resolveMobName(event: PlayerDeathEvent): String? {
        val cause = event.entity.lastDamageCause as? EntityDamageByEntityEvent ?: return null
        val damager = cause.damager
        val entity = when (damager) {
            is Projectile -> damager.shooter as? Entity
            else -> damager
        } ?: return null
        return formatEntityName(entity)
    }

    private fun formatEntityName(entity: Entity): String {
        val custom = entity.customName()?.let { plain.serialize(it).trim() }
        if (!custom.isNullOrBlank()) return custom
        val key = entity.type.key.key.replace('_', ' ')
        return key.split(' ').joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { it.titlecase() }
        }
    }

    private fun removePlayerPrefix(text: String, displayName: String, playerName: String): String {
        var result = text.trimStart()
        val names = listOf(displayName, playerName).filter { it.isNotBlank() }
        for (name in names) {
            val pattern = Regex("^\\s*(💀\\s*)?${Regex.escape(name)}\\s+", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { match ->
                match.groupValues[1].ifBlank { "" }
            }
        }
        return result.trimStart()
    }

    private fun stripLegacyColors(text: String): String {
        return text
            .replace(Regex("(?i)[&§][0-9A-FK-OR]"), "")
            .trim()
    }
}
