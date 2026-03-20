package cc.muhannad.discordauth.managers

import cc.muhannad.discordauth.Dis2FAPlugin
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.security.SecureRandom
import java.util.Base64

class ConfigManager(private val plugin: Dis2FAPlugin) {

    private val random = SecureRandom()

    private val config: FileConfiguration
        get() = plugin.config

    private var langConfig: YamlConfiguration = YamlConfiguration()
    private var fallbackLangConfig: YamlConfiguration = YamlConfiguration()
    private val displayNameOverrides = mapOf(
        "locale" to "Language",
        "fallback-locale" to "Fallback Language",
        "bot-token" to "Bot Token",
        "discord-invite" to "Discord Invite",
        "alerts-channel-id" to "Alerts Channel ID",
        "discord.guild-id" to "Discord Guild ID",
        "discord.clear-global-commands" to "Clear Global Commands",
        "discord.bot-name" to "Bot Display Name",
        "discord.link-channel-name" to "Link Channel Name",
        "discord.allowed-role-ids" to "Allowed Role IDs",
        "discord.require-member" to "Require Discord Member",
        "discord.member-check-timeout-seconds" to "Member Check Timeout (Seconds)",
        "discord.allow-guild-link" to "Allow Guild Linking",
        "discord.link-channel-id" to "Link Channel ID",
        "discord.presence.status" to "Presence Status",
        "discord.presence.activity.type" to "Presence Activity Type",
        "discord.presence.activity.text" to "Presence Activity Text",
        "discord.presence.update-seconds" to "Presence Update Interval (Seconds)",
        "ban-sync.enabled" to "Ban Sync Enabled",
        "ban-sync.apply-minecraft-ban" to "Apply Minecraft Ban",
        "ban-sync.ban-reason" to "Ban Reason",
        "code-length" to "Verification Code Length",
        "code-expiration-seconds" to "Code Expiration (Seconds)",
        "device-approval-seconds" to "Device Approval (Seconds)",
        "device-id.salt" to "Device ID Salt",
        "device-id.ip-prefix-v4" to "Device ID IPv4 Prefix",
        "device-id.ip-prefix-v6" to "Device ID IPv6 Prefix",
        "chat-bridge.enabled" to "Chat Bridge Enabled",
        "chat-bridge.discord-channel-id" to "Chat Bridge Channel ID",
        "chat-bridge.webhook-url" to "Chat Bridge Webhook URL",
        "chat-bridge.webhook-username-format" to "Webhook Username Format",
        "chat-bridge.avatar-url" to "Chat Bridge Avatar URL",
        "chat-bridge.minecraft-format" to "Discord to Minecraft Format",
        "chat-bridge.discord-format" to "Minecraft to Discord Format",
        "chat-bridge.bridge-joins" to "Bridge Join Messages",
        "chat-bridge.bridge-quits" to "Bridge Quit Messages",
        "chat-bridge.bridge-deaths" to "Bridge Death Messages",
        "chat-bridge.bridge-advancements" to "Bridge Advancement Messages",
        "chat-bridge.join-format" to "Join Message Format",
        "chat-bridge.quit-format" to "Quit Message Format",
        "chat-bridge.death-format" to "Death Message Format",
        "chat-bridge.advancement-format" to "Advancement Message Format",
        "web-editor.enabled" to "Web Editor Enabled",
        "web-editor.bind-address" to "Web Editor Bind Address",
        "web-editor.port" to "Web Editor Port",
        "web-editor.token" to "Web Editor Token"
    )

    fun initialize() {
        plugin.saveDefaultConfig()
        addDefaults()
        plugin.saveConfig()
        loadLangFiles()
    }

    fun reload() {
        plugin.reloadConfig()
        addDefaults()
        plugin.saveConfig()
        loadLangFiles()
        plugin.onConfigReloaded()
    }

    private fun addDefaults() {
        val cfg = config

        cfg.addDefault("locale", "en")
        cfg.addDefault("fallback-locale", "en")

        cfg.addDefault("bot-token", "")
        cfg.addDefault("discord-invite", "discord.gg/yourinvite")
        cfg.addDefault("alerts-channel-id", "")
        cfg.addDefault("discord.guild-id", "")
        cfg.addDefault("discord.clear-global-commands", true)
        cfg.addDefault("discord.bot-name", "")
        cfg.addDefault("discord.link-channel-name", "")
        cfg.addDefault("discord.allowed-role-ids", listOf<String>())
        cfg.addDefault("discord.require-member", true)
        cfg.addDefault("discord.member-check-timeout-seconds", 5)
        cfg.addDefault("discord.allow-guild-link", true)
        cfg.addDefault("discord.link-channel-id", "")
        cfg.addDefault("discord.presence.status", "online")
        cfg.addDefault("discord.presence.activity.type", "playing")
        cfg.addDefault("discord.presence.activity.text", "Minecraft")
        cfg.addDefault("discord.presence.update-seconds", 30)

        cfg.addDefault("ban-sync.enabled", false)
        cfg.addDefault("ban-sync.apply-minecraft-ban", true)
        cfg.addDefault("ban-sync.ban-reason", "Banned on Discord")

        cfg.addDefault("code-length", 4)
        cfg.addDefault("code-expiration-seconds", 600)
        cfg.addDefault("device-approval-seconds", 600)

        cfg.addDefault("device-id.salt", "")
        cfg.addDefault("device-id.ip-prefix-v4", 32)
        cfg.addDefault("device-id.ip-prefix-v6", 128)

        cfg.addDefault("chat-bridge.enabled", false)
        cfg.addDefault("chat-bridge.discord-channel-id", "")
        cfg.addDefault("chat-bridge.webhook-url", "")
        cfg.addDefault("chat-bridge.webhook-username-format", "{PLAYER}")
        cfg.addDefault("chat-bridge.avatar-url", "https://minotar.net/avatar/{PLAYER}/128")
        cfg.addDefault("chat-bridge.minecraft-format", "&9[Discord] &b{USER}&7: &f{MESSAGE}")
        cfg.addDefault("chat-bridge.discord-format", "{MESSAGE}")
        cfg.addDefault("chat-bridge.bridge-joins", true)
        cfg.addDefault("chat-bridge.bridge-quits", true)
        cfg.addDefault("chat-bridge.bridge-deaths", true)
        cfg.addDefault("chat-bridge.bridge-advancements", true)
        cfg.addDefault("chat-bridge.join-format", "joined the game")
        cfg.addDefault("chat-bridge.quit-format", "left the game")
        cfg.addDefault("chat-bridge.death-format", "{MESSAGE}")
        cfg.addDefault("chat-bridge.advancement-format", "earned the advancement **{ADVANCEMENT}**")

        cfg.addDefault("web-editor.enabled", true)
        cfg.addDefault("web-editor.bind-address", "0.0.0.0")
        cfg.addDefault("web-editor.port", 8166)
        cfg.addDefault("web-editor.token", "")

        cfg.options().copyDefaults(true)

        if (cfg.getString("device-id.salt").isNullOrBlank()) {
            cfg.set("device-id.salt", generateSalt())
        }
        if (cfg.getString("web-editor.token").isNullOrBlank()) {
            cfg.set("web-editor.token", generateSalt())
        }
    }

    private fun loadLangFiles() {
        val langDir = File(plugin.dataFolder, "lang")
        if (!langDir.exists()) {
            langDir.mkdirs()
        }

        copyLangIfMissing(langDir, "en")
        copyLangIfMissing(langDir, "ar")

        val locale = getLocale()
        val fallback = getFallbackLocale()

        langConfig = loadLang(langDir, locale)
        fallbackLangConfig = loadLang(langDir, fallback)
    }

    private fun copyLangIfMissing(langDir: File, code: String) {
        val file = File(langDir, "$code.yml")
        if (!file.exists()) {
            plugin.saveResource("lang/$code.yml", false)
        }
    }

    private fun loadLang(langDir: File, code: String): YamlConfiguration {
        val file = File(langDir, "$code.yml")
        if (!file.exists()) {
            val fallback = File(langDir, "en.yml")
            return YamlConfiguration.loadConfiguration(fallback)
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val resource = plugin.getResource("lang/$code.yml") ?: plugin.getResource("lang/en.yml")
        if (resource != null) {
            runCatching {
                InputStreamReader(resource, Charsets.UTF_8).use { reader ->
                    val defaults = YamlConfiguration.loadConfiguration(reader)
                    config.setDefaults(defaults)
                    config.options().copyDefaults(true)
                }
            }
        }
        migrateLang(config, code)
        runCatching { config.save(file) }
        return config
    }

    private fun migrateLang(config: YamlConfiguration, code: String) {
        val key = "buttons.link-modal-label"
        val current = config.getString(key) ?: return
        val updated = when (code.lowercase()) {
            "ar" -> {
                if (current == "كود التحقق") "أدخل الكود الظاهر في شاشة ماينكرافت" else null
            }
            else -> {
                if (current == "Verification Code") "Enter the code shown on your Minecraft screen" else null
            }
        }
        if (updated != null) {
            config.set(key, updated)
        }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun displayNameForKey(key: String): String {
        displayNameOverrides[key]?.let { return it }
        return key.split(".")
            .joinToString(" ") { part ->
                part.split("-")
                    .joinToString(" ") { word -> formatDisplayWord(word) }
            }
            .trim()
    }

    private fun formatDisplayWord(word: String): String {
        val lower = word.lowercase()
        return when (lower) {
            "id" -> "ID"
            "ip" -> "IP"
            "v4" -> "IPv4"
            "v6" -> "IPv6"
            "tps" -> "TPS"
            else -> lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun getLocale(): String = config.getString("locale") ?: "en"
    fun getFallbackLocale(): String = config.getString("fallback-locale") ?: "en"

    fun getBotToken(): String = config.getString("bot-token") ?: ""
    fun getDiscordInvite(): String = config.getString("discord-invite") ?: ""
    fun getAlertsChannelId(): String = config.getString("alerts-channel-id") ?: ""
    fun getGuildId(): String = config.getString("discord.guild-id") ?: ""
    fun clearGlobalCommands(): Boolean = config.getBoolean("discord.clear-global-commands", true)
    fun getBotName(): String = config.getString("discord.bot-name") ?: ""
    fun getLinkChannelName(): String = config.getString("discord.link-channel-name") ?: ""
    fun getAllowedRoleIds(): List<String> = config.getStringList("discord.allowed-role-ids")
    fun requireMember(): Boolean = config.getBoolean("discord.require-member", true)
    fun getMemberCheckTimeoutSeconds(): Long = config.getLong("discord.member-check-timeout-seconds", 5)
    fun allowGuildLink(): Boolean = config.getBoolean("discord.allow-guild-link", true)
    fun getLinkChannelId(): String = config.getString("discord.link-channel-id") ?: ""
    fun getPresenceUpdateSeconds(): Long = config.getLong("discord.presence.update-seconds", 30).coerceAtLeast(0)
    fun getPresenceStatus(): OnlineStatus {
        val raw = config.getString("discord.presence.status")?.trim()?.lowercase() ?: "online"
        return when (raw) {
            "idle" -> OnlineStatus.IDLE
            "dnd", "do-not-disturb" -> OnlineStatus.DO_NOT_DISTURB
            "invisible", "offline" -> OnlineStatus.INVISIBLE
            else -> OnlineStatus.ONLINE
        }
    }

    fun getPresenceActivity(placeholders: Map<String, String> = emptyMap()): Activity? {
        val rawText = config.getString("discord.presence.activity.text")?.trim() ?: ""
        val text = replacePlaceholders(rawText, placeholders)
        if (text.isBlank() || text.equals("off", true)) return null

        val rawType = config.getString("discord.presence.activity.type")?.trim()?.lowercase() ?: "playing"
        return when (rawType) {
            "listening" -> Activity.listening(text)
            "watching" -> Activity.watching(text)
            "competing" -> Activity.competing(text)
            "custom" -> Activity.customStatus(text)
            else -> Activity.playing(text)
        }
    }

    fun isBanSyncEnabled(): Boolean = config.getBoolean("ban-sync.enabled", false)
    fun isApplyMinecraftBan(): Boolean = config.getBoolean("ban-sync.apply-minecraft-ban", true)
    fun getBanReason(): String = config.getString("ban-sync.ban-reason") ?: "Banned on Discord"

    fun getCodeLength(): Int = config.getInt("code-length", 4).coerceIn(4, 8)
    fun getCodeExpirationSeconds(): Long = config.getLong("code-expiration-seconds", 600)
    fun getDeviceApprovalSeconds(): Long = config.getLong("device-approval-seconds", 600)

    fun getDeviceSalt(): String = config.getString("device-id.salt") ?: ""
    fun getIpPrefixV4(): Int = config.getInt("device-id.ip-prefix-v4", 32)
    fun getIpPrefixV6(): Int = config.getInt("device-id.ip-prefix-v6", 128)

    fun isChatBridgeEnabled(): Boolean = config.getBoolean("chat-bridge.enabled", false)
    fun getChatChannelId(): String = config.getString("chat-bridge.discord-channel-id") ?: ""
    fun getWebhookUrl(): String = config.getString("chat-bridge.webhook-url") ?: ""
    fun getWebhookUsernameFormat(): String = config.getString("chat-bridge.webhook-username-format") ?: "{PLAYER}"
    fun getAvatarUrlTemplate(): String = config.getString("chat-bridge.avatar-url") ?: ""
    fun getMinecraftChatFormat(): String = config.getString("chat-bridge.minecraft-format") ?: "{USER}: {MESSAGE}"
    fun getDiscordChatFormat(): String = config.getString("chat-bridge.discord-format") ?: "{MESSAGE}"
    fun bridgeJoins(): Boolean = config.getBoolean("chat-bridge.bridge-joins", true)
    fun bridgeQuits(): Boolean = config.getBoolean("chat-bridge.bridge-quits", true)
    fun bridgeDeaths(): Boolean = config.getBoolean("chat-bridge.bridge-deaths", true)
    fun bridgeAdvancements(): Boolean = config.getBoolean("chat-bridge.bridge-advancements", true)
    fun getJoinFormat(): String = config.getString("chat-bridge.join-format") ?: "joined the game"
    fun getQuitFormat(): String = config.getString("chat-bridge.quit-format") ?: "left the game"
    fun getDeathFormat(): String = config.getString("chat-bridge.death-format") ?: "{MESSAGE}"
    fun getAdvancementFormat(): String = config.getString("chat-bridge.advancement-format") ?: "earned the advancement **{ADVANCEMENT}**"


    fun formatKickMessage(path: String, placeholders: Map<String, String> = emptyMap()): String {
        val raw = langMessage(path)
        return colorize(replacePlaceholders(raw, placeholders))
    }

    fun formatDiscordMessage(path: String, placeholders: Map<String, String> = emptyMap()): String {
        val raw = langMessage(path)
        return replacePlaceholders(raw, placeholders)
    }

    fun formatMinecraftChat(user: String, message: String): String {
        val raw = getMinecraftChatFormat()
        return colorize(replacePlaceholders(raw, mapOf("USER" to user, "MESSAGE" to message)))
    }

    fun formatDiscordChat(user: String, message: String): String {
        val raw = getDiscordChatFormat()
        return replacePlaceholders(raw, mapOf("USER" to user, "MESSAGE" to message))
    }

    fun formatJoinBridge(player: String, baseMessage: String): String {
        return formatBridge(getJoinFormat(), mapOf("PLAYER" to player, "MESSAGE" to baseMessage))
    }

    fun formatQuitBridge(player: String, baseMessage: String): String {
        return formatBridge(getQuitFormat(), mapOf("PLAYER" to player, "MESSAGE" to baseMessage))
    }

    fun formatDeathBridge(player: String, baseMessage: String): String {
        return formatBridge(getDeathFormat(), mapOf("PLAYER" to player, "MESSAGE" to baseMessage))
    }

    fun formatAdvancementBridge(player: String, advancement: String): String {
        return formatBridge(getAdvancementFormat(), mapOf("PLAYER" to player, "ADVANCEMENT" to advancement))
    }

    fun formatWebhookUsername(player: String): String {
        return replacePlaceholders(getWebhookUsernameFormat(), mapOf("PLAYER" to player))
    }

    fun formatAvatarUrl(player: String): String {
        return replacePlaceholders(getAvatarUrlTemplate(), mapOf("PLAYER" to player))
    }

    private fun langMessage(path: String): String {
        return langConfig.getString(path)
            ?: fallbackLangConfig.getString(path)
            ?: ""
    }

    private fun formatBridge(template: String, placeholders: Map<String, String>): String {
        return replacePlaceholders(template, placeholders).trim()
    }

    private fun replacePlaceholders(text: String, placeholders: Map<String, String>): String {
        var result = text
        for ((key, value) in placeholders) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    private fun colorize(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }

    fun getEffectiveLinkChannelId(): String {
        val linkId = getLinkChannelId()
        if (linkId.isNotBlank()) return linkId

        val bridgeId = getChatChannelId()
        if (bridgeId.isNotBlank()) return bridgeId

        return getAlertsChannelId()
    }
}
