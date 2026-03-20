package cc.muhannad.discordauth

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class CommandHandler(private val plugin: Dis2FAPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val canUse = sender.hasPermission("discordauth.use") || sender.hasPermission("discordauth.admin")
        if (!canUse) {
            sender.sendMessage(msg("cmd.no-permission"))
            return true
        }
        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }

        return when (args[0].lowercase()) {
            "help" -> {
                showHelp(sender)
                true
            }
            "reload" -> {
                if (!sender.hasPermission("discordauth.admin")) {
                    sender.sendMessage(msg("cmd.no-permission"))
                    return true
                }
                plugin.configManager.reload()
                plugin.refreshPresence()
                sender.sendMessage(msg("cmd.reload-success"))
                true
            }
            "randomizeid" -> {
                if (!sender.hasPermission("discordauth.admin")) {
                    sender.sendMessage(msg("cmd.no-permission"))
                    return true
                }
                handleRandomizeId(sender, args)
                true
            }
            "web" -> {
                if (!sender.hasPermission("discordauth.admin")) {
                    sender.sendMessage(msg("cmd.no-permission"))
                    return true
                }
                val hostOverride = args.getOrNull(1)
                val link = plugin.createWebLoginLink(hostOverride)
                if (link == null) {
                    sender.sendMessage(msg("cmd.web-disabled"))
                } else {
                    sender.sendMessage(msg("cmd.web-link", mapOf("URL" to link)))
                }
                true
            }
            "status" -> {
                showStatus(sender)
                true
            }
            else -> {
                showHelp(sender)
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) {
            val canUse = sender.hasPermission("discordauth.use") || sender.hasPermission("discordauth.admin")
            if (!canUse) return emptyList()

            val options = mutableListOf("help", "status")
            if (sender.hasPermission("discordauth.admin")) {
                options.add("reload")
                options.add("randomizeid")
                options.add("web")
            }

            return options.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }

    private fun showHelp(sender: CommandSender) {
        sender.sendMessage(msg("cmd.help-header"))
        sender.sendMessage(msg("cmd.help-help"))
        sender.sendMessage(msg("cmd.help-status"))
        if (sender.hasPermission("discordauth.admin")) {
            sender.sendMessage(msg("cmd.help-reload"))
            sender.sendMessage(msg("cmd.help-randomize"))
            sender.sendMessage(msg("cmd.help-web"))
        }
        sender.sendMessage(msg("cmd.help-unlink"))
        sender.sendMessage(msg("cmd.help-footer"))
    }

    private fun showStatus(sender: CommandSender) {
        val tokenConfigured = plugin.configManager.getBotToken().isNotBlank()
        val channelId = plugin.configManager.getAlertsChannelId()
        val guildId = plugin.configManager.getGuildId()
        val roleCount = plugin.configManager.getAllowedRoleIds().size
        val banSync = plugin.configManager.isBanSyncEnabled()
        val configured = raw("cmd.value-configured")
        val missing = raw("cmd.value-missing")
        val enabled = raw("cmd.value-enabled")
        val disabled = raw("cmd.value-disabled")
        val none = raw("cmd.value-none")

        sender.sendMessage(msg("cmd.status-title"))
        sender.sendMessage(
            msg(
                "cmd.status-token",
                mapOf("VALUE" to if (tokenConfigured) configured else missing)
            )
        )
        sender.sendMessage(
            msg(
                "cmd.status-alerts",
                mapOf("VALUE" to if (channelId.isNotBlank()) channelId else missing)
            )
        )
        sender.sendMessage(
            msg(
                "cmd.status-guild",
                mapOf("VALUE" to if (guildId.isNotBlank()) guildId else missing)
            )
        )
        sender.sendMessage(
            msg(
                "cmd.status-roles",
                mapOf("VALUE" to if (roleCount > 0) roleCount.toString() else none)
            )
        )
        sender.sendMessage(
            msg(
                "cmd.status-bansync",
                mapOf("VALUE" to if (banSync) enabled else disabled)
            )
        )
    }

    private fun handleRandomizeId(sender: CommandSender, args: Array<out String>) {
        val targetUuid = when {
            args.size >= 2 -> {
                val targetName = args[1]
                val online = Bukkit.getPlayerExact(targetName)
                online?.uniqueId ?: offlineUuid(targetName)
            }
            sender is Player -> sender.uniqueId
            else -> {
                sender.sendMessage(msg("cmd.usage-randomize"))
                return
            }
        }

        val link = plugin.database.getLink(targetUuid)
        if (link == null) {
            sender.sendMessage(msg("cmd.no-link"))
            return
        }

        val newDeviceId = UUID.randomUUID().toString().replace("-", "")
        plugin.database.updateDeviceIdOnly(targetUuid, newDeviceId, System.currentTimeMillis())
        plugin.kickIfOnline(targetUuid, plugin.configManager.formatKickMessage("kick-device-change"))
        sender.sendMessage(msg("cmd.randomized"))
    }

    private fun msg(path: String, placeholders: Map<String, String> = emptyMap()): String {
        return plugin.configManager.formatKickMessage(path, placeholders)
    }

    private fun raw(path: String, placeholders: Map<String, String> = emptyMap()): String {
        return plugin.configManager.formatDiscordMessage(path, placeholders)
    }

    private fun offlineUuid(playerName: String): UUID {
        val name = "OfflinePlayer:$playerName"
        return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))
    }
}

class UnlinkCommandExecutor(private val plugin: Dis2FAPlugin) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val canUse = sender.hasPermission("discordauth.use") || sender.hasPermission("discordauth.admin")
        if (!canUse) {
            sender.sendMessage(plugin.configManager.formatKickMessage("cmd.no-permission"))
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(plugin.configManager.formatKickMessage("cmd.only-players"))
            return true
        }

        val uuid = sender.uniqueId
        val link = plugin.database.getLink(uuid)
        if (link == null) {
            sender.sendMessage(plugin.configManager.formatKickMessage("cmd.not-linked"))
            return true
        }

        plugin.database.unlink(uuid)
        sender.kickPlayer(plugin.configManager.formatKickMessage("unlink-kick"))
        return true
    }
}
