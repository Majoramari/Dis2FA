package cc.muhannad.discordauth

import cc.muhannad.discordauth.listeners.ChatBridgeListener
import cc.muhannad.discordauth.listeners.DiscordListener
import cc.muhannad.discordauth.listeners.PlayerListener
import cc.muhannad.discordauth.managers.ConfigManager
import cc.muhannad.discordauth.storage.Database
import cc.muhannad.discordauth.utils.HashUtil
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.requests.GatewayIntent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.TimeUnit

class Dis2FAPlugin : JavaPlugin() {

    companion object {
        lateinit var instance: Dis2FAPlugin
            private set
    }

    lateinit var configManager: ConfigManager
        private set
    lateinit var database: Database
        private set

    private lateinit var playerListener: PlayerListener
    private lateinit var discordListener: DiscordListener
    private lateinit var chatBridgeListener: ChatBridgeListener

    private val prefix = Component.text("[Dis2FA] ", NamedTextColor.GOLD)
    private var presenceTaskId: Int? = null

    var jda: JDA? = null
        private set

    override fun onEnable() {
        instance = this

        configManager = ConfigManager(this)
        configManager.initialize()

        database = Database(this)
        database.open()

        initializeDiscordBot()
        registerListeners()
        registerCommands()
        scheduleCleanupTasks()
        refreshPresence()
        logStartupInfo()
    }

    override fun onDisable() {
        presenceTaskId?.let { Bukkit.getScheduler().cancelTask(it) }
        presenceTaskId = null
        jda?.shutdown()
        database.close()
        logInfo("Dis2FA disabled")
    }

    private fun initializeDiscordBot() {
        val token = configManager.getBotToken()
        if (token.isBlank()) {
            logWarn("Discord bot token not configured. Linking will not work until you set it in config.yml.")
            return
        }

        try {
            val builder = JDABuilder.createDefault(token)
                .enableIntents(
                    GatewayIntent.DIRECT_MESSAGES,
                    GatewayIntent.MESSAGE_CONTENT,
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.GUILD_MEMBERS
                )
                .setStatus(configManager.getPresenceStatus())

            val activity = configManager.getPresenceActivity()
            if (activity != null) {
                builder.setActivity(activity)
            }

            jda = builder.build()

            discordListener = DiscordListener(this)
            jda?.addEventListener(discordListener)
            logInfo("Discord bot starting...")
        } catch (e: Exception) {
            logError("Failed to initialize Discord bot: ${e.message}")
        }
    }

    private fun registerListeners() {
        playerListener = PlayerListener(this)
        server.pluginManager.registerEvents(playerListener, this)
        chatBridgeListener = ChatBridgeListener(this)
        server.pluginManager.registerEvents(chatBridgeListener, this)
        logInfo("Registered listeners")
    }

    private fun registerCommands() {
        val main = getCommand("discordauth")
        main?.setExecutor(CommandHandler(this))
        main?.tabCompleter = CommandHandler(this)

        val unlink = getCommand("unlink")
        unlink?.setExecutor(UnlinkCommandExecutor(this))

        logInfo("Registered commands")
    }

    private fun scheduleCleanupTasks() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            val now = System.currentTimeMillis()
            val codes = database.cleanupExpiredCodes(now)
            val requests = database.cleanupExpiredDeviceRequests(now)
            if (codes > 0 || requests > 0) {
                logInfo("Cleanup: removed $codes expired codes and $requests expired device requests")
            }
        }, 20L * 60L, 20L * 60L) // every minute
    }

    fun refreshPresence() {
        if (jda == null) {
            presenceTaskId?.let { Bukkit.getScheduler().cancelTask(it) }
            presenceTaskId = null
            return
        }

        updatePresence()
        schedulePresenceUpdates()
    }

    private fun schedulePresenceUpdates() {
        presenceTaskId?.let { Bukkit.getScheduler().cancelTask(it) }
        presenceTaskId = null

        val interval = configManager.getPresenceUpdateSeconds()
        if (interval <= 0) return

        val task = Bukkit.getScheduler().runTaskTimer(this, Runnable {
            updatePresence()
        }, interval * 20L, interval * 20L)
        presenceTaskId = task.taskId
    }

    private fun updatePresence() {
        val jda = jda ?: return
        val placeholders = buildPresencePlaceholders()
        val status = configManager.getPresenceStatus()
        val activity = configManager.getPresenceActivity(placeholders)
        jda.presence.setStatus(status)
        jda.presence.setActivity(activity)
    }

    private fun buildPresencePlaceholders(): Map<String, String> {
        val online = Bukkit.getOnlinePlayers().size
        val max = Bukkit.getMaxPlayers()
        val tpsRaw = Bukkit.getServer().tps.firstOrNull() ?: 0.0
        val tps = String.format("%.2f", if (tpsRaw > 20.0) 20.0 else tpsRaw)

        return mapOf(
            "PLAYERS" to online.toString(),
            "MAX_PLAYERS" to max.toString(),
            "TPS" to tps
        )
    }

    fun computeDeviceId(uuid: UUID, ip: String): String {
        return HashUtil.deviceId(
            uuid = uuid,
            ip = ip,
            salt = configManager.getDeviceSalt(),
            v4Prefix = configManager.getIpPrefixV4(),
            v6Prefix = configManager.getIpPrefixV6()
        )
    }

    fun kickMessageUnlinked(code: String) = configManager.formatKickComponent(
            "kick-unlinked",
            mapOf(
                "CODE" to code,
                "DISCORD_INVITE" to configManager.getDiscordInvite(),
                "BOT_NAME" to resolveBotName(),
                "LINK_CHANNEL" to resolveLinkChannelName()
            )
        )

    fun kickMessageDeviceChange() = configManager.formatKickComponent("kick-device-change")

    fun resolveGuild(): Guild? {
        val guildId = configManager.getGuildId()
        if (guildId.isBlank()) return null
        return jda?.getGuildById(guildId)
    }

    fun fetchMember(guild: Guild, discordId: String): Member? {
        val timeout = configManager.getMemberCheckTimeoutSeconds().coerceAtLeast(1)
        return try {
            guild.retrieveMemberById(discordId).submit().get(timeout, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun isDiscordBanned(guild: Guild, discordId: String): Boolean {
        val timeout = configManager.getMemberCheckTimeoutSeconds().coerceAtLeast(1)
        return try {
            guild.retrieveBan(UserSnowflake.fromId(discordId)).submit().get(timeout, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasAllowedRole(member: Member): Boolean {
        val allowed = configManager.getAllowedRoleIds()
        if (allowed.isEmpty()) return true
        return member.roles.any { role -> allowed.contains(role.id) }
    }

    fun sendDeviceApprovalRequest(
        requestId: String,
        linkDiscordId: String,
        avatarUrl: String?,
        timestampSeconds: Long
    ) {
        val channelId = configManager.getAlertsChannelId()
        if (channelId.isBlank()) {
            logWarn("alerts-channel-id is not configured; cannot send device approval request.")
            return
        }

        if (!this::discordListener.isInitialized) {
            logWarn("Discord bot not initialized; cannot send device approval request.")
            return
        }

        val mention = "<@${linkDiscordId}>"
        val action = discordListener.sendDeviceApprovalMessage(
            channelId = channelId,
            mention = mention,
            avatarUrl = avatarUrl,
            timestampSeconds = timestampSeconds,
            requestId = requestId
        )

        if (action == null) {
            logWarn("Failed to resolve alerts channel $channelId")
            return
        }

        action.queue { message ->
            database.updateDeviceRequestMessage(requestId, message.channel.id, message.id)
        }
    }

    fun sendLinkRequest(playerName: String) {
        if (!configManager.allowGuildLink()) return

        val channelId = configManager.getEffectiveLinkChannelId()
        if (channelId.isBlank()) return

        if (!this::discordListener.isInitialized) return

        discordListener.sendLinkRequestMessage(channelId, playerName)
    }

    private fun resolveBotName(): String {
        val configured = configManager.getBotName().trim()
        if (configured.isNotBlank()) return configured
        return jda?.selfUser?.name ?: "Discord bot"
    }

    private fun resolveLinkChannelName(): String {
        val configured = configManager.getLinkChannelName().trim()
        if (configured.isNotBlank()) return configured

        val channelId = configManager.getEffectiveLinkChannelId()
        if (channelId.isBlank()) return "#link"

        val channel = jda?.getTextChannelById(channelId)
        return if (channel != null) "#${channel.name}" else "#link"
    }

    private fun logStartupInfo() {
        logInfo("Dis2FA enabled")
        logInfo("Locale: ${configManager.getLocale()} (fallback: ${configManager.getFallbackLocale()})")
        logInfo("Guild ID: ${configManager.getGuildId().ifBlank { "not set" }}")
        logInfo("Alerts channel: ${configManager.getAlertsChannelId().ifBlank { "not set" }}")
        val bridgeStatus = if (configManager.isChatBridgeEnabled()) "enabled" else "disabled"
        logInfo("Chat bridge: $bridgeStatus (channel: ${configManager.getChatChannelId().ifBlank { "not set" }})")
    }

    private fun logInfo(message: String) {
        componentLogger.info(prefix.append(Component.text(message, NamedTextColor.GRAY)))
    }

    private fun logWarn(message: String) {
        componentLogger.warn(prefix.append(Component.text(message, NamedTextColor.YELLOW)))
    }

    private fun logError(message: String) {
        componentLogger.error(prefix.append(Component.text(message, NamedTextColor.RED)))
    }
}
