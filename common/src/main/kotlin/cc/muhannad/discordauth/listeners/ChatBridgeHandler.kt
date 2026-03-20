package cc.muhannad.discordauth.listeners

import cc.muhannad.discordauth.Dis2FAPlugin
import cc.muhannad.discordauth.utils.WebhookSender
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack

class ChatBridgeHandler(private val plugin: Dis2FAPlugin) {

    fun handleChat(playerName: String, message: String) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val content = plugin.configManager.formatDiscordChat(playerName, message.trim())
        if (content.isBlank()) return
        sendForPlayer(playerName, content)
    }

    fun handleJoin(playerName: String, baseMessage: String) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeJoins()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val content = plugin.configManager.formatJoinBridge(playerName, baseMessage)
        if (content.isBlank()) return
        sendSystemEmbed(playerName, content, 0x2ecc71, true)
    }

    fun handleQuit(playerName: String, baseMessage: String) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeQuits()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return

        val content = plugin.configManager.formatQuitBridge(playerName, baseMessage)
        if (content.isBlank()) return
        sendSystemEmbed(playerName, content, 0xe74c3c, true)
    }

    fun handleDeath(player: Player, displayName: String, rawMessage: String, event: PlayerDeathEvent) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeDeaths()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return
        if (rawMessage.isBlank()) return

        val message = sanitizeDeathMessage(rawMessage, displayName, player.name, event)
        val content = plugin.configManager.formatDeathBridge(player.name, message)
        if (content.isBlank()) return
        sendSystemEmbed(player.name, content, 0xe67e22, true)
    }

    fun handleAdvancement(playerName: String, advancementTitle: String) {
        if (!plugin.configManager.isChatBridgeEnabled()) return
        if (!plugin.configManager.bridgeAdvancements()) return
        if (plugin.configManager.getWebhookUrl().isBlank()) return
        if (advancementTitle.isBlank()) return

        val content = plugin.configManager.formatAdvancementBridge(playerName, advancementTitle)
        if (content.isBlank()) return
        sendSystemEmbed(playerName, content, 0x3498db, false)
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

    private fun sanitizeDeathMessage(raw: String, displayName: String, playerName: String, event: PlayerDeathEvent): String {
        var message = raw

        val mobName = resolveMobName(event)
        if (!mobName.isNullOrBlank()) {
            message = message.replace("[mobName]", mobName)
            message = message.replace("{MOB}", mobName)
        }

        if (!mobName.isNullOrBlank()) {
            message = message.replace("[prevMobName]", mobName)
            message = message.replace("{PREV_MOB}", mobName)
        } else if (message.contains("[prevMobName]") || message.contains("{PREV_MOB}")) {
            message = message.replace("[prevMobName]", "someone")
            message = message.replace("{PREV_MOB}", "someone")
        }

        val itemName = resolveItemName(event)
        if (!itemName.isNullOrBlank()) {
            message = message.replace("[item]", itemName)
            message = message.replace("{ITEM}", itemName)
        } else if (message.contains("[item]") || message.contains("{ITEM}")) {
            message = message.replace("[item]", "their weapon")
            message = message.replace("{ITEM}", "their weapon")
        }

        val displayValue = if (displayName.isNotBlank()) displayName else playerName
        if (displayValue.isNotBlank()) {
            message = message.replace("[playerDisplayName]", displayValue)
            message = message.replace("{PLAYER_DISPLAY}", displayValue)
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
        val entity = resolveDamager(event) ?: return null
        return formatEntityName(entity)
    }

    private fun resolveItemName(event: PlayerDeathEvent): String? {
        val damager = resolveDamager(event) ?: return null
        val item = when (damager) {
            is Player -> firstNonAir(damager.inventory.itemInMainHand, damager.inventory.itemInOffHand)
            is LivingEntity -> damager.equipment?.itemInMainHand
            else -> null
        } ?: return null
        if (item.type == Material.AIR) return null
        return formatItemName(item)
    }

    private fun resolveDamager(event: PlayerDeathEvent): Entity? {
        val cause = event.entity.lastDamageCause as? EntityDamageByEntityEvent ?: return null
        val damager = cause.damager
        return when (damager) {
            is Projectile -> damager.shooter as? Entity
            else -> damager
        }
    }

    private fun firstNonAir(primary: ItemStack?, secondary: ItemStack?): ItemStack? {
        if (primary != null && primary.type != Material.AIR) return primary
        if (secondary != null && secondary.type != Material.AIR) return secondary
        return null
    }

    private fun formatEntityName(entity: Entity): String {
        val custom = entity.customName?.trim()
        if (!custom.isNullOrBlank()) return custom
        val key = entity.type.key.key.replace('_', ' ')
        return key.split(' ').joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { it.titlecase() }
        }
    }

    private fun formatItemName(item: ItemStack): String {
        val meta = item.itemMeta
        if (meta != null && meta.hasDisplayName()) {
            val display = meta.displayName?.trim()
            if (!display.isNullOrBlank()) return display
        }
        val key = item.type.key.key.replace('_', ' ')
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
                val skull = match.groupValues[1].trim()
                if (skull.isNotEmpty()) "💀 " else ""
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
