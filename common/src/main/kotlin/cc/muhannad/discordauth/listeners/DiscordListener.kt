package cc.muhannad.discordauth.listeners

import cc.muhannad.discordauth.Dis2FAPlugin
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.bukkit.Bukkit
import java.awt.Color
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import java.util.concurrent.CompletableFuture
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import cc.muhannad.discordauth.utils.AvatarLinkImage

class DiscordListener(private val plugin: Dis2FAPlugin) : ListenerAdapter() {
    private val linkButtonId = "da:linkbutton"
    private val linkModalPrefix = "da:linkmodal"
    private val sensitiveKeys = setOf("bot-token", "chat-bridge.webhook-url", "web-editor.token")
    private val restartKeys = setOf("bot-token", "discord.guild-id", "discord.clear-global-commands")

    override fun onReady(event: ReadyEvent) {
        registerGuildCommands(event)
        if (plugin.configManager.clearGlobalCommands()) {
            event.jda.updateCommands().queue()
        }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        if (event.isFromGuild && plugin.configManager.allowGuildLink()) {
            val linkChannelId = plugin.configManager.getEffectiveLinkChannelId()
            if (linkChannelId.isBlank() || event.channel.id == linkChannelId) {
                val code = findCode(event.message.contentRaw)
                if (code != null) {
                    handleLinkCode(
                        discordId = event.author.id,
                        code = code,
                        onLinked = { pending ->
                            sendLinkedImage(
                                channel = event.channel,
                                playerName = pending.playerName,
                                discordAvatarUrl = event.author.effectiveAvatarUrl,
                                discordId = event.author.id
                            )
                        }
                    ) { title, message ->
                        event.message.replyEmbeds(embed(title, message, Color(231, 76, 60))).queue()
                    }
                    return
                }
            }
        }

        if (event.isFromGuild && plugin.configManager.isChatBridgeEnabled()) {
            val channelId = plugin.configManager.getChatChannelId()
            if (channelId.isNotBlank() && event.channel.id == channelId) {
                if (event.isWebhookMessage) return

                var content = event.message.contentDisplay.trim()
                if (content.isBlank() && event.message.attachments.isNotEmpty()) {
                    content = event.message.attachments.joinToString(" ") { it.url }
                }
                if (content.isBlank()) return

                val user = event.member?.effectiveName ?: event.author.name
                val message = plugin.configManager.formatMinecraftChat(user, content)
                plugin.runSync {
                    Bukkit.broadcastMessage(message)
                }
                return
            }
        }

        if (event.channelType != ChannelType.PRIVATE) return

        val code = findCode(event.message.contentRaw)
        if (code == null) return

        handleLinkCode(
            discordId = event.author.id,
            code = code,
            onLinked = { pending ->
                sendLinkedImage(
                    channel = event.channel,
                    playerName = pending.playerName,
                    discordAvatarUrl = event.author.effectiveAvatarUrl,
                    discordId = event.author.id
                )
            }
        ) { title, message ->
            event.channel.sendMessageEmbeds(embed(title, message, Color(231, 76, 60))).queue()
        }
    }

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        when {
            event.name == "randomizeid" && event.focusedOption.name == "player" -> {
                val query = event.focusedOption.value
                val results = plugin.database.searchLinks(query, 25)
                val guild = event.guild

                val choices = results.map { link ->
                    val display = buildDisplayName(link.discordId, link.playerName, guild)
                    Command.Choice(display, link.discordId)
                }

                event.replyChoices(choices).queue()
            }
            event.name == "config" && event.focusedOption.name == "key" -> {
                val query = event.focusedOption.value
                val keys = suggestConfigKeys(query)
                val choices = keys.map { key -> Command.Choice(key, key) }
                event.replyChoices(choices).queue()
            }
        }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.guild == null) {
            event.replyEmbeds(embed(title("generic"), t("discord.use-in-server"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }
        val configuredGuildId = plugin.configManager.getGuildId()
        if (configuredGuildId.isNotBlank() && event.guild!!.id != configuredGuildId) {
            event.replyEmbeds(embed(title("generic"), t("discord.wrong-server"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        when (event.name) {
            "help" -> {
                event.replyEmbeds(embed(title("help"), t("discord.help-body"), Color(88, 101, 242)))
                    .setEphemeral(true)
                    .queue()
            }
            "status" -> {
                event.replyEmbeds(buildStatusEmbed())
                    .setEphemeral(true)
                    .queue()
            }
            "link" -> {
                val code = findCode(event.getOption("code")?.asString ?: "")
                if (code == null) {
                    val len = plugin.configManager.getCodeLength()
                    event.replyEmbeds(
                        embed(
                            title("link"),
                            t("discord.code-length", mapOf("LEN" to len.toString())),
                            Color(231, 76, 60)
                        )
                    )
                        .setEphemeral(true)
                        .queue()
                    return
                }
                when (val result = attemptLinkCode(event.user.id, code)) {
                    is LinkResult.Error -> {
                        event.replyEmbeds(embed(title("link"), result.message, Color(231, 76, 60)))
                            .setEphemeral(true)
                            .queue()
                    }
                    is LinkResult.Success -> {
                        event.deferReply(false).queue { hook ->
                            hook.deleteOriginal().queue()
                            sendLinkedImage(
                                channel = event.channel,
                                playerName = result.pending.playerName,
                                discordAvatarUrl = event.user.effectiveAvatarUrl,
                                discordId = event.user.id
                            )
                        }
                    }
                }
            }
            "unlink" -> {
                val link = plugin.database.getLinkByDiscordId(event.user.id)
                if (link == null) {
                    event.replyEmbeds(embed(title("unlink"), t("discord.not-linked"), Color(231, 76, 60)))
                        .setEphemeral(true)
                        .queue()
                    return
                }
                plugin.database.unlink(link.uuid)
                event.replyEmbeds(embed(title("unlink"), t("discord.unlinked"), Color(46, 204, 113)))
                    .setEphemeral(true)
                    .queue()
            }
            "reload" -> {
                if (!hasAdminPermission(event)) {
                    event.replyEmbeds(embed(title("reload"), t("discord.no-permission"), Color(231, 76, 60)))
                        .setEphemeral(true)
                        .queue()
                    return
                }
                plugin.configManager.reload()
                plugin.refreshPresence()
                event.replyEmbeds(embed(title("reload"), t("discord.reloaded"), Color(46, 204, 113)))
                    .setEphemeral(true)
                    .queue()
            }
            "randomizeid" -> {
                if (!hasAdminPermission(event)) {
                    event.replyEmbeds(embed(title("randomize"), t("discord.no-permission"), Color(231, 76, 60)))
                        .setEphemeral(true)
                        .queue()
                    return
                }
                val option = event.getOption("player")?.asString
                val link = when {
                    !option.isNullOrBlank() -> plugin.database.getLinkByDiscordId(option)
                        ?: plugin.database.getLink(offlineUuid(option))
                    else -> plugin.database.getLinkByDiscordId(event.user.id)
                }
                if (link == null) {
                    event.replyEmbeds(embed(title("randomize"), t("discord.randomize-not-found"), Color(231, 76, 60)))
                        .setEphemeral(true)
                        .queue()
                    return
                }
                val newDeviceId = UUID.randomUUID().toString().replace("-", "")
                plugin.database.updateDeviceIdOnly(link.uuid, newDeviceId, System.currentTimeMillis())
                val msg = t("discord.randomize-success", mapOf("PLAYER" to link.playerName))
                event.replyEmbeds(embed(title("randomize"), msg, Color(46, 204, 113)))
                    .setEphemeral(true)
                    .queue()
            }
            "config" -> {
                if (!hasAdminPermission(event)) {
                    event.replyEmbeds(embed(title("config"), t("discord.no-permission"), Color(231, 76, 60)))
                        .setEphemeral(true)
                        .queue()
                    return
                }
                when (event.subcommandName) {
                    "get" -> handleConfigGet(event)
                    "set" -> handleConfigSet(event)
                    "reset" -> handleConfigReset(event)
                    else -> {
                        event.replyEmbeds(embed(title("config"), t("discord.config-unknown"), Color(231, 76, 60)))
                            .setEphemeral(true)
                            .queue()
                    }
                }
            }
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.componentId

        if (id == linkButtonId) {
            val length = plugin.configManager.getCodeLength()
            val input = TextInput.create("code", t("buttons.link-modal-label"), TextInputStyle.SHORT)
                .setPlaceholder(t("buttons.link-modal-placeholder"))
                .setMinLength(1)
                .setMaxLength(length)
                .setRequired(true)
                .build()

            val modalId = "$linkModalPrefix:${event.channel.id}:${event.messageId}"
            val modal = Modal.create(modalId, t("buttons.link-modal-title"))
                .addActionRow(input)
                .build()

            event.replyModal(modal).queue()
            return
        }

        if (!id.startsWith("da:")) return

        val parts = id.split(":")
        if (parts.size != 3) return

        val action = parts[1]
        val requestId = parts[2]

        val request = plugin.database.getDeviceRequest(requestId)
        if (request == null) {
            event.replyEmbeds(embed(title("request"), t("discord.request-invalid"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        val now = System.currentTimeMillis()
        if (request.expiresAt <= now || request.status != "PENDING") {
            event.replyEmbeds(embed(title("request"), t("discord.request-expired"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        if (event.user.id != request.discordId) {
            event.replyEmbeds(embed(title("request"), t("discord.request-not-for-you"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        when (action) {
            "approve" -> handleApproval(event, requestId)
            "deny" -> handleDenial(event, requestId)
            else -> event.replyEmbeds(embed(title("request"), t("discord.request-unknown"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
        }
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        if (!event.modalId.startsWith(linkModalPrefix)) return

        val raw = event.getValue("code")?.asString ?: ""
        val len = plugin.configManager.getCodeLength()
        val code = raw.trim()
        if (!Regex("^\\d{$len}$").matches(code)) {
            event.replyEmbeds(
                embed(
                    title("link"),
                    t("discord.code-length", mapOf("LEN" to len.toString())),
                    Color(231, 76, 60)
                )
            )
                .setEphemeral(true)
                .queue()
            return
        }

        val messageRef = parseMessageRef(event.modalId)
        handleLinkCode(
            discordId = event.user.id,
            code = code,
            onLinked = { pending ->
                if (messageRef != null) {
                    editLinkRequestMessage(
                        channelId = messageRef.first,
                        messageId = messageRef.second,
                        playerName = pending.playerName,
                        discordAvatarUrl = event.user.effectiveAvatarUrl,
                        discordId = event.user.id
                    )
                }
                event.deferReply(false).queue { hook ->
                    hook.deleteOriginal().queue()
                }
            },
            reply = { tTitle, message ->
                event.replyEmbeds(embed(tTitle, message, Color(231, 76, 60)))
                    .setEphemeral(true)
                    .queue()
            }
        )
    }

    private fun registerGuildCommands(event: ReadyEvent) {
        val configuredGuildId = plugin.configManager.getGuildId()
        val commands = listOf(
            Commands.slash("help", "Show help"),
            Commands.slash("status", "Show server TPS/RAM/CPU"),
            Commands.slash("link", "Link your Minecraft account")
                .addOption(OptionType.STRING, "code", "Verification code from the server", true),
            Commands.slash("unlink", "Unlink your Discord account"),
            Commands.slash("reload", "Reload the bot configuration")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
            Commands.slash("randomizeid", "Randomize a linked player's device ID (testing)")
                .addOption(OptionType.STRING, "player", "Minecraft player name or linked user", false, true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
            Commands.slash("config", "View or change configuration")
                .addSubcommands(
                    SubcommandData("get", "Get a config value")
                        .addOption(OptionType.STRING, "key", "Config key", true, true),
                    SubcommandData("set", "Set a config value")
                        .addOption(OptionType.STRING, "key", "Config key", true, true)
                        .addOption(OptionType.STRING, "value", "New value", true),
                    SubcommandData("reset", "Reset a config value to default")
                        .addOption(OptionType.STRING, "key", "Config key", true, true)
                )
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
        )

        if (configuredGuildId.isNotBlank()) {
            val guild = event.jda.getGuildById(configuredGuildId)
            if (guild != null) {
                guild.updateCommands().addCommands(commands).queue()
            }
            return
        }

        event.jda.guilds.forEach { guild ->
            guild.updateCommands().addCommands(commands).queue()
        }
    }

    private fun handleConfigGet(event: SlashCommandInteractionEvent) {
        val key = event.getOption("key")?.asString?.trim().orEmpty()
        if (!isConfigKeySupported(key)) {
            event.replyEmbeds(embed(title("config"), t("discord.config-unknown-key"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        val value = plugin.config.get(key)
        val display = formatConfigValue(key, value)
        val message = t("discord.config-get", mapOf("KEY" to key, "VALUE" to display))

        event.replyEmbeds(embed(title("config"), message, Color(52, 152, 219)))
            .setEphemeral(true)
            .queue()
    }

    private fun handleConfigSet(event: SlashCommandInteractionEvent) {
        val key = event.getOption("key")?.asString?.trim().orEmpty()
        val rawValue = event.getOption("value")?.asString?.trim().orEmpty()
        if (!isConfigKeySupported(key)) {
            event.replyEmbeds(embed(title("config"), t("discord.config-unknown-key"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        event.deferReply(true).queue { hook ->
            plugin.runSync {
                when (val result = applyConfigSet(key, rawValue)) {
                    is ConfigChangeResult.Error -> {
                        hook.editOriginalEmbeds(embed(title("config"), result.message, Color(231, 76, 60))).queue()
                    }
                    is ConfigChangeResult.Success -> {
                        val base = t("discord.config-set", mapOf("KEY" to key, "VALUE" to result.displayValue))
                        val note = if (result.requiresRestart) "\n" + t("discord.config-note-restart") else ""
                        hook.editOriginalEmbeds(embed(title("config"), base + note, Color(46, 204, 113))).queue()
                    }
                }
            }
        }
    }

    private fun handleConfigReset(event: SlashCommandInteractionEvent) {
        val key = event.getOption("key")?.asString?.trim().orEmpty()
        if (!isConfigKeySupported(key)) {
            event.replyEmbeds(embed(title("config"), t("discord.config-unknown-key"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        event.deferReply(true).queue { hook ->
            plugin.runSync {
                plugin.config.set(key, null)
                plugin.saveConfig()
                plugin.configManager.reload()
                plugin.refreshPresence()

                val defaultValue = plugin.config.defaults?.get(key)
                val display = formatConfigValue(key, defaultValue)
                val message = t("discord.config-reset", mapOf("KEY" to key, "VALUE" to display))
                val note = if (restartKeys.contains(key)) "\n" + t("discord.config-note-restart") else ""

                hook.editOriginalEmbeds(embed(title("config"), message + note, Color(46, 204, 113))).queue()
            }
        }
    }

    private sealed class ConfigChangeResult {
        data class Success(val displayValue: String, val requiresRestart: Boolean) : ConfigChangeResult()
        data class Error(val message: String) : ConfigChangeResult()
    }

    private fun applyConfigSet(key: String, rawValue: String): ConfigChangeResult {
        return when (val parsed = parseConfigValue(key, rawValue)) {
            is ConfigParseResult.Error -> {
                val message = t(
                    "discord.config-invalid-value",
                    mapOf("KEY" to key, "TYPE" to parsed.expectedType)
                )
                ConfigChangeResult.Error(message)
            }
            is ConfigParseResult.Success -> {
                plugin.config.set(key, parsed.value)
                plugin.saveConfig()
                plugin.configManager.reload()
                plugin.refreshPresence()

                val display = formatConfigValue(key, parsed.value)
                ConfigChangeResult.Success(display, restartKeys.contains(key))
            }
        }
    }

    private sealed class ConfigParseResult {
        data class Success(val value: Any?) : ConfigParseResult()
        data class Error(val expectedType: String) : ConfigParseResult()
    }

    private fun parseConfigValue(key: String, rawValue: String): ConfigParseResult {
        val config = plugin.config
        val currentValue = if (config.isSet(key)) config.get(key) else config.defaults?.get(key)
        val trimmed = rawValue.trim()

        return when (currentValue) {
            is Boolean -> {
                val parsed = parseBoolean(trimmed) ?: return ConfigParseResult.Error("boolean")
                ConfigParseResult.Success(parsed)
            }
            is Int, is Long, is Double, is Float, is Number -> {
                if (trimmed.isBlank()) return ConfigParseResult.Error("number")
                val parsed = if (trimmed.contains(".")) {
                    trimmed.toDoubleOrNull()
                } else {
                    trimmed.toLongOrNull()
                } ?: return ConfigParseResult.Error("number")
                ConfigParseResult.Success(parsed)
            }
            is List<*> -> {
                ConfigParseResult.Success(parseStringList(trimmed))
            }
            else -> {
                ConfigParseResult.Success(rawValue)
            }
        }
    }

    private fun parseBoolean(value: String): Boolean? {
        return when (value.lowercase()) {
            "true", "yes", "y", "on", "1" -> true
            "false", "no", "n", "off", "0" -> false
            else -> null
        }
    }

    private fun parseStringList(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed == "[]") return emptyList()
        return trimmed.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun formatConfigValue(key: String, value: Any?): String {
        if (sensitiveKeys.contains(key)) return t("discord.config-hidden")
        return when (value) {
            null -> "null"
            is List<*> -> {
                if (value.isEmpty()) "[]"
                else value.joinToString(", ") { item -> item?.toString() ?: "" }.trim()
            }
            else -> value.toString()
        }
    }

    private fun isConfigKeySupported(key: String): Boolean {
        if (key.isBlank()) return false
        val config = plugin.config
        if (config.isConfigurationSection(key)) return false
        if (config.contains(key)) return true
        return config.defaults?.contains(key) == true
    }

    private fun suggestConfigKeys(query: String): List<String> {
        val config = plugin.config
        val keys = config.getKeys(true)
            .filter { !config.isConfigurationSection(it) }
            .sorted()
        if (query.isBlank()) return keys.take(25)
        val q = query.lowercase()
        return keys.filter { it.lowercase().contains(q) }.take(25)
    }

    private sealed class LinkResult {
        data class Success(val pending: cc.muhannad.discordauth.model.PendingCode) : LinkResult()
        data class Error(val message: String) : LinkResult()
    }

    private fun attemptLinkCode(discordId: String, code: String): LinkResult {
        val pending = plugin.database.getPendingCode(code)
        if (pending == null) {
            return LinkResult.Error(t("discord.link-invalid"))
        }

        val now = System.currentTimeMillis()
        if (pending.expiresAt <= now) {
            plugin.database.deletePendingCode(code)
            return LinkResult.Error(t("discord.link-invalid"))
        }

        val linkedByDiscord = plugin.database.getLinkByDiscordId(discordId)
        if (linkedByDiscord != null && linkedByDiscord.uuid != pending.uuid) {
            return LinkResult.Error(t("discord.link-already-linked"))
        }

        val linkedByUuid = plugin.database.getLink(pending.uuid)
        if (linkedByUuid != null && linkedByUuid.discordId != discordId) {
            return LinkResult.Error(t("discord.link-minecraft-already-linked"))
        }

        plugin.database.deletePendingCode(code)
        plugin.database.upsertLink(
            uuid = pending.uuid,
            playerName = pending.playerName,
            discordId = discordId,
            deviceId = null,
            ip = pending.ip,
            now = now
        )

        return LinkResult.Success(pending)
    }

    private fun handleLinkCode(
        discordId: String,
        code: String,
        onLinked: (cc.muhannad.discordauth.model.PendingCode) -> Unit = {},
        reply: (String, String) -> Unit
    ) {
        when (val result = attemptLinkCode(discordId, code)) {
            is LinkResult.Success -> {
                onLinked(result.pending)
                // Success handled by image embed; no reply here.
            }
            is LinkResult.Error -> {
                reply(title("link"), result.message)
            }
        }
    }

    private fun offlineUuid(playerName: String): UUID {
        val name = "OfflinePlayer:$playerName"
        return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))
    }

    private fun hasAdminPermission(event: SlashCommandInteractionEvent): Boolean {
        val member = event.member ?: return false
        return member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.ADMINISTRATOR)
    }

    private fun handleApproval(event: ButtonInteractionEvent, requestId: String) {
        val request = plugin.database.getDeviceRequest(requestId) ?: run {
            event.replyEmbeds(embed(title("request"), t("discord.request-invalid"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        val now = System.currentTimeMillis()
        if (request.expiresAt <= now || request.status != "PENDING") {
            event.replyEmbeds(embed(title("request"), t("discord.request-expired"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        plugin.database.updateDeviceRequestStatus(requestId, "APPROVED")
        plugin.database.updateLinkDevice(request.uuid, request.newDeviceId, request.newIp, now)

        event.replyEmbeds(embed(title("request"), t("discord.request-approved"), Color(46, 204, 113)))
            .setEphemeral(true)
            .queue()
        updateMessage(event, t("buttons.approve"))
    }

    private fun handleDenial(event: ButtonInteractionEvent, requestId: String) {
        val request = plugin.database.getDeviceRequest(requestId) ?: run {
            event.replyEmbeds(embed(title("request"), t("discord.request-invalid"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        val now = System.currentTimeMillis()
        if (request.expiresAt <= now || request.status != "PENDING") {
            event.replyEmbeds(embed(title("request"), t("discord.request-expired"), Color(231, 76, 60)))
                .setEphemeral(true)
                .queue()
            return
        }

        plugin.database.updateDeviceRequestStatus(requestId, "DENIED")

        event.replyEmbeds(embed(title("request"), t("discord.request-denied"), Color(231, 76, 60)))
            .setEphemeral(true)
            .queue()
        updateMessage(event, t("buttons.deny"))
    }

    private fun updateMessage(event: ButtonInteractionEvent, status: String) {
        val original = event.message.embeds.firstOrNull()
        val builder = if (original != null) EmbedBuilder(original) else EmbedBuilder()
        builder.addField(t("discord.request-status"), "**$status** (${Instant.now()})", false)
        event.message.editMessageEmbeds(builder.build())
            .setComponents(emptyList())
            .queue()
    }

    fun sendDeviceApprovalMessage(
        channelId: String,
        mention: String,
        avatarUrl: String?,
        requestId: String
    ): MessageCreateAction? {
        val jda = plugin.jda ?: return null
        val channel = jda.getTextChannelById(channelId) ?: return null

        val message = t("channel-device-request")
        val embed = embed(title("device-request"), message, Color(241, 196, 15), avatarUrl)

        return channel.sendMessage(mention)
            .setEmbeds(embed)
            .setActionRow(
                Button.success("da:approve:$requestId", t("buttons.approve")),
                Button.danger("da:deny:$requestId", t("buttons.deny"))
            )
    }

    fun sendLinkRequestMessage(channelId: String, playerName: String) {
        val jda = plugin.jda ?: return
        val channel = jda.getTextChannelById(channelId) ?: return

        val message = t("discord.link-request", mapOf("PLAYER" to playerName))
        val embed = embed("", message, Color(52, 152, 219))
        channel.sendMessageEmbeds(embed)
            .setActionRow(Button.primary(linkButtonId, t("buttons.link")))
            .queue()
    }

    private fun embed(title: String, description: String, color: Color, thumbnailUrl: String? = null) =
        EmbedBuilder()
            .setTitle(title.ifBlank { null })
            .setDescription(description)
            .setColor(color)
            .setThumbnail(thumbnailUrl)
            .build()

    private fun buildDisplayName(discordId: String, playerName: String, guild: Guild?): String {
        val member = guild?.getMemberById(discordId)
        val name = member?.effectiveName ?: discordId
        val display = "$playerName — $name"
        return if (display.length > 100) display.take(100) else display
    }

    private fun buildStatusEmbed(): net.dv8tion.jda.api.entities.MessageEmbed {
        val tps = plugin.platform.getTps()
        val tpsText = tps?.joinToString(" / ") { formatTps(it) } ?: t("discord.status-na")
        val tpsBar = if (tps == null) naBar() else progressBar(tps.firstOrNull() ?: 0.0, 20.0)

        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val max = runtime.maxMemory() / (1024 * 1024)
        val ramBar = if (max > 0) progressBar(used.toDouble(), max.toDouble()) else naBar()

        val cpuPercent = readCpuLoad()?.let { it * 100.0 }
        val cpuText = cpuPercent?.let { String.format("%.1f%%", it) } ?: t("discord.status-na")
        val cpuBar = cpuPercent?.let { progressBar(it, 100.0) } ?: naBar()

        val uptime = formatDuration(ManagementFactory.getRuntimeMXBean().uptime)
        val online = Bukkit.getOnlinePlayers().size
        val maxPlayers = Bukkit.getMaxPlayers()
        val playersText = "$online/$maxPlayers"
        val playersBar = if (maxPlayers > 0) progressBar(online.toDouble(), maxPlayers.toDouble()) else naBar()

        val builder = EmbedBuilder()
            .setTitle(title("status"))
            .setColor(Color(52, 152, 219))

        builder.addField(
            t("discord.status-field-tps"),
            "**$tpsText**\n$tpsBar",
            false
        )
        builder.addField(
            t("discord.status-field-cpu"),
            "**$cpuText**\n$cpuBar",
            false
        )
        builder.addField(
            t("discord.status-field-ram"),
            "**${used}MB / ${max}MB**\n$ramBar",
            false
        )
        builder.addField(
            t("discord.status-field-players"),
            "**$playersText**\n$playersBar",
            false
        )
        builder.addField(
            t("discord.status-field-uptime"),
            "**$uptime**",
            false
        )

        return builder.build()
    }

    private fun formatTps(value: Double): String {
        val capped = if (value > 20.0) 20.0 else value
        return String.format("%.2f", capped)
    }

    private fun formatDuration(ms: Long): String {
        var seconds = ms / 1000
        val days = seconds / 86400
        seconds %= 86400
        val hours = seconds / 3600
        seconds %= 3600
        val minutes = seconds / 60
        seconds %= 60

        val parts = mutableListOf<String>()
        if (days > 0) parts.add("${days}d")
        if (hours > 0) parts.add("${hours}h")
        if (minutes > 0) parts.add("${minutes}m")
        parts.add("${seconds}s")
        return parts.joinToString(" ")
    }

    private fun progressBar(value: Double, max: Double, length: Int = 12): String {
        if (max <= 0.0) return naBar()
        val ratio = (value / max).coerceIn(0.0, 1.0)
        val filled = (ratio * length).roundToInt().coerceIn(0, length)
        val bar = "#".repeat(filled) + "-".repeat(length - filled)
        val percent = (ratio * 100).roundToInt()
        return "`[$bar]` $percent%"
    }

    private fun naBar(): String {
        return "`[${t("discord.status-na")}]`"
    }

    private fun readCpuLoad(): Double? {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        val load = callDouble(bean, "getCpuLoad") ?: callDouble(bean, "getSystemCpuLoad")
        return load?.takeIf { it >= 0.0 }
    }

    private fun callDouble(target: Any, methodName: String): Double? {
        return try {
            val method = target.javaClass.getMethod(methodName)
            val value = method.invoke(target)
            value as? Double
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMessageRef(modalId: String): Pair<String, String>? {
        val parts = modalId.split(":")
        if (parts.size != 4) return null
        if (parts[0] != "da" || parts[1] != "linkmodal") return null
        val channelId = parts[2]
        val messageId = parts[3]
        if (channelId.isBlank() || messageId.isBlank()) return null
        return channelId to messageId
    }

    private fun editLinkRequestMessage(
        channelId: String,
        messageId: String,
        playerName: String,
        discordAvatarUrl: String,
        discordId: String
    ) {
        val channel = plugin.jda?.getTextChannelById(channelId) ?: return
        val minecraftAvatarUrl = plugin.configManager.formatAvatarUrl(playerName)

        CompletableFuture.supplyAsync {
            AvatarLinkImage.render(minecraftAvatarUrl, discordAvatarUrl)
        }.thenAccept { bytes ->
            if (bytes == null) return@thenAccept

            val sendNew = Runnable {
                sendLinkedImageWithBytes(channel, playerName, discordId, bytes)
            }

            channel.deleteMessageById(messageId)
                .queue(
                    { sendNew.run() },
                    { sendNew.run() }
                )
        }
    }

    private fun sendLinkedImage(
        channel: MessageChannel,
        playerName: String,
        discordAvatarUrl: String,
        discordId: String
    ) {
        val minecraftAvatarUrl = plugin.configManager.formatAvatarUrl(playerName)
        CompletableFuture.supplyAsync {
            AvatarLinkImage.render(minecraftAvatarUrl, discordAvatarUrl)
        }.thenAccept { bytes ->
            if (bytes == null) return@thenAccept
            sendLinkedImageWithBytes(channel, playerName, discordId, bytes)
        }
    }

    private fun sendLinkedImageWithBytes(
        channel: MessageChannel,
        playerName: String,
        discordId: String,
        bytes: ByteArray
    ) {
        val mention = "<@$discordId>"
        val description = t(
            "discord.linked-image",
            mapOf(
                "PLAYER" to playerName,
                "MENTION" to mention
            )
        ).ifBlank { "Successfully linked $mention to **$playerName**." }

        val embed = EmbedBuilder()
            .setDescription(description)
            .setColor(Color(46, 204, 113))
            .setImage("attachment://link.png")
            .build()

        val upload = FileUpload.fromData(bytes, "link.png")
        channel.sendMessageEmbeds(embed)
            .addFiles(upload)
            .queue()
    }

    private fun findCode(text: String): String? {
        val len = plugin.configManager.getCodeLength()
        val regex = Regex("\\d{$len}")
        return regex.find(text)?.value
    }

    private fun t(path: String, placeholders: Map<String, String> = emptyMap()): String {
        return plugin.configManager.formatDiscordMessage(path, placeholders)
    }

    private fun title(key: String): String {
        return t("titles.$key")
    }
}
