package cc.muhannad.discordauth

import cc.muhannad.discordauth.listeners.DiscordListener
import cc.muhannad.discordauth.listeners.PlayerListener
import cc.muhannad.discordauth.managers.ConfigManager
import cc.muhannad.discordauth.platform.PlatformAdapter
import cc.muhannad.discordauth.platform.TaskHandle
import cc.muhannad.discordauth.storage.Database
import cc.muhannad.discordauth.utils.HashUtil
import cc.muhannad.discordauth.web.WebConfigServer
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.requests.GatewayIntent
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.TimeUnit

abstract class Dis2FAPlugin : JavaPlugin() {

    companion object {
        lateinit var instance: Dis2FAPlugin
            private set
    }

    lateinit var configManager: ConfigManager
        private set
    lateinit var database: Database
        private set

    lateinit var platform: PlatformAdapter
        private set

    private lateinit var playerListener: PlayerListener
    private lateinit var discordListener: DiscordListener

    private val prefix = "[Dis2FA] "
    private var presenceTask: TaskHandle? = null
    private var webConfigServer: WebConfigServer? = null

    var jda: JDA? = null
        private set

    protected abstract fun createPlatformAdapter(): PlatformAdapter

    override fun onEnable() {
        instance = this

        configManager = ConfigManager(this)
        configManager.initialize()

        webConfigServer = WebConfigServer(this)
        webConfigServer?.startFromConfig()

        database = Database(this)
        database.open()

        platform = createPlatformAdapter()

        initializeDiscordBot()
        registerListeners()
        registerCommands()
        scheduleCleanupTasks()
        refreshPresence()
        logStartupInfo()
    }

    override fun onDisable() {
        webConfigServer?.stop()
        presenceTask?.cancel()
        presenceTask = null
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
        platform.registerChatBridgeListener()
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
        platform.runAsyncRepeating(20L * 60L, 20L * 60L, Runnable {
            val now = System.currentTimeMillis()
            val codes = database.cleanupExpiredCodes(now)
            val requests = database.cleanupExpiredDeviceRequests(now)
            if (codes > 0 || requests > 0) {
                logInfo("Cleanup: removed $codes expired codes and $requests expired device requests")
            }
        }) // every minute
    }

    fun refreshPresence() {
        if (jda == null) {
            presenceTask?.cancel()
            presenceTask = null
            return
        }

        updatePresence()
        schedulePresenceUpdates()
    }

    private fun schedulePresenceUpdates() {
        presenceTask?.cancel()
        presenceTask = null

        val interval = configManager.getPresenceUpdateSeconds()
        if (interval <= 0) return

        presenceTask = platform.runSyncRepeating(interval * 20L, interval * 20L, Runnable {
            updatePresence()
        })
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
        val tpsRaw = platform.getTps()?.firstOrNull()
        val tps = if (tpsRaw == null) {
            "N/A"
        } else {
            String.format("%.2f", if (tpsRaw > 20.0) 20.0 else tpsRaw)
        }

        return mapOf(
            "PLAYERS" to online.toString(),
            "MAX_PLAYERS" to max.toString(),
            "TPS" to tps
        )
    }

    fun runSync(task: () -> Unit) {
        platform.runSync(Runnable { task() })
    }

    fun onConfigReloaded() {
        webConfigServer?.reloadFromConfig()
    }

    fun createWebLoginLink(hostOverride: String?): String? {
        return webConfigServer?.createMagicLink(hostOverride)
    }

    fun kickIfOnline(uuid: UUID, message: String) {
        val player = Bukkit.getPlayer(uuid) ?: return
        platform.runSync(Runnable { player.kickPlayer(message) })
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

    fun kickMessageUnlinked(code: String) = configManager.formatKickMessage(
            "kick-unlinked",
            mapOf(
                "CODE" to code,
                "DISCORD_INVITE" to configManager.getDiscordInvite(),
                "BOT_NAME" to resolveBotName(),
                "LINK_CHANNEL" to resolveLinkChannelName()
            )
        )

    fun kickMessageDeviceChange() = configManager.formatKickMessage("kick-device-change")

    fun resolveGuild(): Guild? {
        val guildId = configManager.getGuildId()
        if (guildId.isBlank()) return null
        return jda?.getGuildById(guildId)
    }

    fun fetchMember(guild: Guild, discordId: String): Member? {
        val timeout = configManager.getMemberCheckTimeoutSeconds().coerceAtLeast(1)
        return try {
            guild.retrieveMemberById(discordId).submit().get(timeout, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    fun isDiscordBanned(guild: Guild, discordId: String): Boolean {
        val timeout = configManager.getMemberCheckTimeoutSeconds().coerceAtLeast(1)
        return try {
            guild.retrieveBan(UserSnowflake.fromId(discordId)).submit().get(timeout, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
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
        avatarUrl: String?
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
        logger.info(prefix + message)
    }

    private fun logWarn(message: String) {
        logger.warning(prefix + message)
    }

    private fun logError(message: String) {
        logger.severe(prefix + message)
    }
}
