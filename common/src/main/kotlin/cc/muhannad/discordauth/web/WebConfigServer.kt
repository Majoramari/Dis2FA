package cc.muhannad.discordauth.web

import cc.muhannad.discordauth.Dis2FAPlugin
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bukkit.Bukkit
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebConfigServer(private val plugin: Dis2FAPlugin) {

    private val random = SecureRandom()
    private val magicLinkTtlMs = 5 * 60 * 1000L

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var host: String = ""
    private var port: Int = 0
    private var token: String = ""

    private val sensitiveKeys = setOf(
        "bot-token",
        "chat-bridge.webhook-url",
        "web-editor.token"
    )
    private val restartKeys = setOf("bot-token", "discord.guild-id", "discord.clear-global-commands")
    private val descriptions = mapOf(
        "locale" to "Language file to use for messages (en or ar).",
        "fallback-locale" to "Fallback language if a key is missing.",
        "bot-token" to "Discord bot token used to connect to the API.",
        "discord-invite" to "Invite link shown in kick messages.",
        "alerts-channel-id" to "Channel ID for device approval requests.",
        "discord.guild-id" to "Discord server (guild) ID for faster command registration.",
        "discord.clear-global-commands" to "Clear global Discord commands on startup.",
        "discord.bot-name" to "Override bot name shown in Minecraft messages.",
        "discord.link-channel-name" to "Override link channel name shown in messages.",
        "discord.allowed-role-ids" to "Allow only members with these role IDs (empty allows all).",
        "discord.require-member" to "Require the player to be in the Discord server.",
        "discord.member-check-timeout-seconds" to "Timeout (seconds) for Discord member lookups.",
        "discord.allow-guild-link" to "Allow linking by sending codes in a guild channel.",
        "discord.link-channel-id" to "Channel where link buttons and codes are accepted.",
        "discord.presence.status" to "Bot status (online, idle, dnd, invisible).",
        "discord.presence.activity.type" to "Bot activity type (playing, listening, watching, competing, custom).",
        "discord.presence.activity.text" to "Bot activity text, or 'off' to disable.",
        "discord.presence.update-seconds" to "How often to refresh presence placeholders.",
        "ban-sync.enabled" to "Check Discord bans on join.",
        "ban-sync.apply-minecraft-ban" to "Also ban the player in Minecraft if banned on Discord.",
        "ban-sync.ban-reason" to "Reason used for the Minecraft ban.",
        "code-length" to "Verification code length (4-8 digits).",
        "code-expiration-seconds" to "How long link codes remain valid.",
        "device-approval-seconds" to "How long device approvals remain valid.",
        "device-id.salt" to "Salt used to hash UUID+IP for device IDs.",
        "device-id.ip-prefix-v4" to "IPv4 prefix length used for device ID.",
        "device-id.ip-prefix-v6" to "IPv6 prefix length used for device ID.",
        "chat-bridge.enabled" to "Enable Discord <-> Minecraft chat bridge.",
        "chat-bridge.discord-channel-id" to "Discord channel ID to read messages from.",
        "chat-bridge.webhook-url" to "Webhook URL for sending Minecraft chat to Discord.",
        "chat-bridge.webhook-username-format" to "Webhook username template. Supports {PLAYER}.",
        "chat-bridge.avatar-url" to "Webhook avatar URL template. Supports {PLAYER}.",
        "chat-bridge.minecraft-format" to "Format for Discord -> Minecraft messages.",
        "chat-bridge.discord-format" to "Format for Minecraft -> Discord messages.",
        "chat-bridge.bridge-joins" to "Bridge join messages.",
        "chat-bridge.bridge-quits" to "Bridge quit messages.",
        "chat-bridge.bridge-deaths" to "Bridge death messages.",
        "chat-bridge.bridge-advancements" to "Bridge advancement messages.",
        "chat-bridge.join-format" to "Join message template.",
        "chat-bridge.quit-format" to "Quit message template.",
        "chat-bridge.death-format" to "Death message template.",
        "chat-bridge.advancement-format" to "Advancement message template.",
        "web-editor.enabled" to "Enable the web config editor.",
        "web-editor.bind-address" to "Address the web editor binds to.",
        "web-editor.port" to "Port for the web editor.",
        "web-editor.token" to "Token for web editor API access."
    )
    private val sessions = ConcurrentHashMap<String, Session>()
    private val magicLinks = ConcurrentHashMap<String, Long>()

    private data class Session(val label: String, val expiresAt: Long)

    private sealed class AuthResult {
        object Token : AuthResult()
        object Session : AuthResult()
    }

    @Synchronized
    fun startFromConfig() {
        val cfg = plugin.config
        val enabled = cfg.getBoolean("web-editor.enabled", false)
        val newHost = cfg.getString("web-editor.bind-address")?.trim().orEmpty().ifBlank { "0.0.0.0" }
        val newPort = cfg.getInt("web-editor.port", 8166).coerceIn(1, 65535)
        val newToken = cfg.getString("web-editor.token")?.trim().orEmpty()

        if (!enabled) {
            stop()
            return
        }

        if (newToken.isBlank()) {
            logWarn("Web config editor token is blank. Use /da web to generate a magic link.")
        }

        if (
            server != null &&
            newHost == host &&
            newPort == port &&
            newToken == token
        ) {
            token = newToken
            return
        }

        stop()

        try {
            val http = HttpServer.create(InetSocketAddress(newHost, newPort), 0)
            http.createContext("/") { exchange -> handleRoot(exchange) }
            http.createContext("/api/config") { exchange -> handleConfig(exchange) }
            http.createContext("/api/config/set") { exchange -> handleConfigSet(exchange) }
            http.createContext("/api/config/reset") { exchange -> handleConfigReset(exchange) }
            http.createContext("/api/me") { exchange -> handleMe(exchange) }
            http.createContext("/api/links") { exchange -> handleLinks(exchange) }
            http.createContext("/api/links/unlink") { exchange -> handleLinksUnlink(exchange) }
            http.createContext("/api/links/randomize") { exchange -> handleLinksRandomize(exchange) }
            http.createContext("/login") { exchange -> handleMagicLogin(exchange) }
            http.createContext("/logout") { exchange -> handleLogout(exchange) }
            val exec = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "Dis2FA-WebConfig")
            }
            http.executor = exec
            http.start()

            server = http
            executor = exec
            host = newHost
            port = newPort
            token = newToken
            logInfo("Web config editor listening on http://$host:$port")
        } catch (e: Exception) {
            logError("Failed to start web config editor: ${e.message}")
        }
    }

    @Synchronized
    fun reloadFromConfig() {
        startFromConfig()
    }

    @Synchronized
    fun createMagicLink(hostOverride: String?): String? {
        if (server == null) return null

        val baseUrl = resolveBaseUrl(hostOverride) ?: return null
        val code = generateToken(18)
        magicLinks[code] = System.currentTimeMillis() + magicLinkTtlMs
        return "${baseUrl}/login?code=$code"
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        try {
            current.stop(1)
        } catch (_: Exception) {
        }
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handleRoot(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/", "" -> {
                if (exchange.requestMethod != "GET") {
                    send(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed")
                    return
                }
                send(exchange, 200, "text/html; charset=utf-8", INDEX_HTML)
            }
            "/favicon.ico" -> send(exchange, 204, "text/plain; charset=utf-8", "")
            else -> send(exchange, 404, "text/plain; charset=utf-8", "Not Found")
        }
    }

    private fun handleConfig(exchange: HttpExchange) {
        if (authenticate(exchange) == null) {
            sendJson(exchange, 401, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"ok":false,"error":"method_not_allowed"}""")
            return
        }

        val snapshot = try {
            runSyncWait(2500) { buildConfigSnapshot() }
        } catch (_: Exception) {
            sendJson(exchange, 500, """{"ok":false,"error":"internal_error"}""")
            return
        }
        if (snapshot == null) {
            sendJson(exchange, 503, """{"ok":false,"error":"timeout"}""")
            return
        }

        sendJson(exchange, 200, buildConfigJson(snapshot))
    }

    private fun handleConfigSet(exchange: HttpExchange) {
        if (authenticate(exchange) == null) {
            sendJson(exchange, 401, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"ok":false,"error":"method_not_allowed"}""")
            return
        }

        val form = parseForm(readBody(exchange))
        val key = form["key"].orEmpty().trim()
        val value = form["value"].orEmpty()
        if (key.isBlank()) {
            sendJson(exchange, 400, """{"ok":false,"error":"missing_key"}""")
            return
        }

        val result = try {
            runSyncWait(2500) { applyConfigSet(key, value) }
        } catch (_: Exception) {
            sendJson(exchange, 500, """{"ok":false,"error":"internal_error"}""")
            return
        }
        if (result == null) {
            sendJson(exchange, 503, """{"ok":false,"error":"timeout"}""")
            return
        }

        when (result) {
            is ConfigChangeResult.Error -> {
                sendJson(exchange, 400, """{"ok":false,"error":"${jsonEscape(result.message)}"}""")
            }
            is ConfigChangeResult.Success -> {
                val payload = StringBuilder()
                payload.append("""{"ok":true,"value":"${jsonEscape(result.displayValue)}","restart":""")
                payload.append(result.requiresRestart)
                payload.append("}")
                sendJson(exchange, 200, payload.toString())
            }
        }
    }

    private fun handleConfigReset(exchange: HttpExchange) {
        if (authenticate(exchange) == null) {
            sendJson(exchange, 401, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"ok":false,"error":"method_not_allowed"}""")
            return
        }

        val form = parseForm(readBody(exchange))
        val key = form["key"].orEmpty().trim()
        if (key.isBlank()) {
            sendJson(exchange, 400, """{"ok":false,"error":"missing_key"}""")
            return
        }

        val result = try {
            runSyncWait(2500) { applyConfigReset(key) }
        } catch (_: Exception) {
            sendJson(exchange, 500, """{"ok":false,"error":"internal_error"}""")
            return
        }
        if (result == null) {
            sendJson(exchange, 503, """{"ok":false,"error":"timeout"}""")
            return
        }

        when (result) {
            is ConfigChangeResult.Error -> {
                sendJson(exchange, 400, """{"ok":false,"error":"${jsonEscape(result.message)}"}""")
            }
            is ConfigChangeResult.Success -> {
                val payload = StringBuilder()
                payload.append("""{"ok":true,"value":"${jsonEscape(result.displayValue)}","restart":""")
                payload.append(result.requiresRestart)
                payload.append("}")
                sendJson(exchange, 200, payload.toString())
            }
        }
    }

    private fun handleMe(exchange: HttpExchange) {
        val auth = authenticate(exchange)
        if (auth == null) {
            sendJson(exchange, 401, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"ok":false,"error":"method_not_allowed"}""")
            return
        }
        val payload = when (auth) {
            is AuthResult.Token -> """{"ok":true,"auth":"token"}"""
            is AuthResult.Session -> """{"ok":true,"auth":"session"}"""
        }
        sendJson(exchange, 200, payload)
    }

    private fun handleMagicLogin(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            send(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed")
            return
        }

        val query = parseQuery(exchange.requestURI.rawQuery)
        val code = query["code"].orEmpty()
        if (code.isBlank()) {
            send(exchange, 400, "text/plain; charset=utf-8", "Missing login code.")
            return
        }

        val expiry = magicLinks.remove(code)
        if (expiry == null || expiry < System.currentTimeMillis()) {
            send(exchange, 400, "text/plain; charset=utf-8", "Login code expired.")
            return
        }

        val sessionId = generateToken(24)
        sessions[sessionId] = Session("magic", System.currentTimeMillis() + 12 * 60 * 60 * 1000)
        exchange.responseHeaders.add(
            "Set-Cookie",
            "dis2fa_session=$sessionId; Path=/; HttpOnly; SameSite=Lax"
        )
        redirect(exchange, "/")
    }

    private fun handleLinks(exchange: HttpExchange) {
        if (authenticate(exchange) == null) {
            sendJson(exchange, 401, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"ok":false,"error":"method_not_allowed"}""")
            return
        }

        val query = parseQuery(exchange.requestURI.rawQuery)
        val term = query["q"].orEmpty()
        val limit = query["limit"]?.toIntOrNull() ?: 50
        val links = plugin.database.searchLinks(term, limit.coerceIn(1, 100))

        val items = links.map { link ->
            LinkItem(
                uuid = link.uuid.toString(),
                playerName = link.playerName,
                discordId = link.discordId,
                deviceId = link.deviceId,
                lastIp = link.lastIp,
                linkedAt = link.linkedAt,
                lastSeen = link.lastSeen
            )
        }

        sendJson(exchange, 200, buildLinksJson(items))
    }

    private fun handleLinksUnlink(exchange: HttpExchange) {
        if (authenticate(exchange) == null) {
            sendJson(exchange, 401, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"ok":false,"error":"method_not_allowed"}""")
            return
        }

        val form = parseForm(readBody(exchange))
        val uuidRaw = form["uuid"].orEmpty().trim()
        val discordId = form["discordId"].orEmpty().trim()
        if (uuidRaw.isBlank() && discordId.isBlank()) {
            sendJson(exchange, 400, """{"ok":false,"error":"missing_identifier"}""")
            return
        }

        val link = when {
            uuidRaw.isNotBlank() -> runCatching { java.util.UUID.fromString(uuidRaw) }
                .getOrNull()
                ?.let { plugin.database.getLink(it) }
            discordId.isNotBlank() -> plugin.database.getLinkByDiscordId(discordId)
            else -> null
        }

        if (link == null) {
            sendJson(exchange, 404, """{"ok":false,"error":"not_found"}""")
            return
        }

        plugin.database.unlink(link.uuid)
        plugin.kickIfOnline(link.uuid, plugin.configManager.formatKickMessage("unlink-kick"))
        sendJson(exchange, 200, """{"ok":true}""")
    }

    private fun handleLinksRandomize(exchange: HttpExchange) {
        if (authenticate(exchange) == null) {
            sendJson(exchange, 401, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"ok":false,"error":"method_not_allowed"}""")
            return
        }

        val form = parseForm(readBody(exchange))
        val uuidRaw = form["uuid"].orEmpty().trim()
        if (uuidRaw.isBlank()) {
            sendJson(exchange, 400, """{"ok":false,"error":"missing_uuid"}""")
            return
        }
        val uuid = runCatching { java.util.UUID.fromString(uuidRaw) }.getOrNull()
        if (uuid == null) {
            sendJson(exchange, 400, """{"ok":false,"error":"invalid_uuid"}""")
            return
        }
        val link = plugin.database.getLink(uuid)
        if (link == null) {
            sendJson(exchange, 404, """{"ok":false,"error":"not_found"}""")
            return
        }

        val newDeviceId = java.util.UUID.randomUUID().toString().replace("-", "")
        plugin.database.updateDeviceIdOnly(uuid, newDeviceId, System.currentTimeMillis())
        plugin.kickIfOnline(uuid, plugin.configManager.formatKickMessage("kick-device-change"))
        sendJson(exchange, 200, """{"ok":true,"deviceId":"${jsonEscape(newDeviceId)}"}""")
    }

    private fun handleLogout(exchange: HttpExchange) {
        val sessionId = parseCookie(exchange, "dis2fa_session")
        if (sessionId != null) {
            sessions.remove(sessionId)
        }
        exchange.responseHeaders.add(
            "Set-Cookie",
            "dis2fa_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
        )
        redirect(exchange, "/")
    }

    private data class ConfigItem(
        val key: String,
        val type: String,
        val value: String,
        val sensitive: Boolean,
        val restart: Boolean,
        val displayName: String,
        val description: String
    )

    private data class LinkItem(
        val uuid: String,
        val playerName: String,
        val discordId: String,
        val deviceId: String?,
        val lastIp: String?,
        val linkedAt: Long,
        val lastSeen: Long
    )

    private sealed class ConfigChangeResult {
        data class Success(val displayValue: String, val requiresRestart: Boolean) : ConfigChangeResult()
        data class Error(val message: String) : ConfigChangeResult()
    }

    private fun buildConfigSnapshot(): List<ConfigItem> {
        val config = plugin.config
        val keys = config.getKeys(true)
            .filter { !config.isConfigurationSection(it) }
            .sorted()

        return keys.map { key ->
            val value = if (config.isSet(key)) config.get(key) else config.defaults?.get(key)
            val type = when (value) {
                is Boolean -> "boolean"
                is Int, is Long, is Float, is Double, is Number -> "number"
                is List<*> -> "list"
                else -> "string"
            }
            val displayValue = if (sensitiveKeys.contains(key)) "" else formatConfigValue(value)
            ConfigItem(
                key = key,
                type = type,
                value = displayValue,
                sensitive = sensitiveKeys.contains(key),
                restart = restartKeys.contains(key),
                displayName = plugin.configManager.displayNameForKey(key),
                description = descriptions[key] ?: "No description available."
            )
        }
    }

    private fun buildConfigJson(items: List<ConfigItem>): String {
        val sb = StringBuilder()
        sb.append("""{"ok":true,"items":[""")
        items.forEachIndexed { index, item ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append(""""key":"${jsonEscape(item.key)}",""")
            sb.append(""""type":"${jsonEscape(item.type)}",""")
            sb.append(""""value":"${jsonEscape(item.value)}",""")
            sb.append(""""sensitive":${item.sensitive},""")
            sb.append(""""restart":${item.restart},""")
            sb.append(""""displayName":"${jsonEscape(item.displayName)}",""")
            sb.append(""""description":"${jsonEscape(item.description)}"""")
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun buildLinksJson(items: List<LinkItem>): String {
        val sb = StringBuilder()
        sb.append("""{"ok":true,"items":[""")
        items.forEachIndexed { index, item ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append(""""uuid":"${jsonEscape(item.uuid)}",""")
            sb.append(""""playerName":"${jsonEscape(item.playerName)}",""")
            sb.append(""""discordId":"${jsonEscape(item.discordId)}",""")
            sb.append(""""deviceId":"${jsonEscape(item.deviceId ?: "")}",""")
            sb.append(""""lastIp":"${jsonEscape(item.lastIp ?: "")}",""")
            sb.append(""""linkedAt":${item.linkedAt},""")
            sb.append(""""lastSeen":${item.lastSeen}""")
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun applyConfigSet(key: String, rawValue: String): ConfigChangeResult {
        if (!isConfigKeySupported(key)) {
            return ConfigChangeResult.Error("Unknown config key.")
        }

        return when (val parsed = parseConfigValue(key, rawValue)) {
            is ConfigParseResult.Error -> {
                ConfigChangeResult.Error("Invalid value (expected ${parsed.expectedType}).")
            }
            is ConfigParseResult.Success -> {
                plugin.config.set(key, parsed.value)
                plugin.saveConfig()
                plugin.configManager.reload()
                plugin.refreshPresence()

                val display = formatConfigValue(parsed.value)
                ConfigChangeResult.Success(display, restartKeys.contains(key))
            }
        }
    }

    private fun applyConfigReset(key: String): ConfigChangeResult {
        if (!isConfigKeySupported(key)) {
            return ConfigChangeResult.Error("Unknown config key.")
        }

        plugin.config.set(key, null)
        plugin.saveConfig()
        plugin.configManager.reload()
        plugin.refreshPresence()

        val defaultValue = plugin.config.defaults?.get(key)
        val display = formatConfigValue(defaultValue)
        return ConfigChangeResult.Success(display, restartKeys.contains(key))
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

    private fun formatConfigValue(value: Any?): String {
        return when (value) {
            null -> ""
            is List<*> -> value.joinToString(", ") { item -> item?.toString() ?: "" }.trim()
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

    private fun authenticate(exchange: HttpExchange): AuthResult? {
        cleanupSessions()

        val sessionId = parseCookie(exchange, "dis2fa_session")
        if (!sessionId.isNullOrBlank()) {
            val session = sessions[sessionId]
            if (session != null && session.expiresAt > System.currentTimeMillis()) {
                return AuthResult.Session
            }
        }

        val expected = token
        if (expected.isBlank()) return null

        val auth = exchange.requestHeaders.getFirst("Authorization")
        if (auth != null && auth.startsWith("Bearer ")) {
            if (auth.removePrefix("Bearer ").trim() == expected) return AuthResult.Token
        }

        val headerToken = exchange.requestHeaders.getFirst("X-Dis2FA-Token")
        if (headerToken == expected) return AuthResult.Token

        val queryToken = parseQuery(exchange.requestURI.rawQuery)["token"]
        if (queryToken == expected) return AuthResult.Token

        return null
    }

    private fun cleanupSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt <= now }
        magicLinks.entries.removeIf { it.value <= now }
    }

    private fun parseCookie(exchange: HttpExchange, name: String): String? {
        val cookieHeader = exchange.requestHeaders.getFirst("Cookie") ?: return null
        val parts = cookieHeader.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$name=")) {
                return trimmed.substring(name.length + 1)
            }
        }
        return null
    }

    private fun generateToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun resolveBaseUrl(hostOverride: String?): String? {
        val override = hostOverride?.trim().orEmpty()
        if (override.isNotBlank()) {
            if (override.startsWith("http://") || override.startsWith("https://")) {
                return override.trimEnd('/')
            }
            return if (override.contains(":")) {
                "http://$override"
            } else {
                "http://$override:$port"
            }
        }

        val bind = host.trim().ifBlank { "127.0.0.1" }
        val resolved = if (bind == "0.0.0.0" || bind == "::") {
            val serverIp = plugin.server.ip?.trim().orEmpty()
            if (serverIp.isNotBlank()) serverIp else "localhost"
        } else {
            bind
        }
        return "http://$resolved:$port"
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.set("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.responseBody.close()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx < 0) return@mapNotNull null
                val key = urlDecode(part.substring(0, idx))
                val value = urlDecode(part.substring(idx + 1))
                key to value
            }
            .toMap()
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx < 0) return@mapNotNull null
                val key = urlDecode(part.substring(0, idx))
                val value = urlDecode(part.substring(idx + 1))
                key to value
            }
            .toMap()
    }

    private fun readBody(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.use { it.readAllBytes() }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun urlDecode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private fun send(exchange: HttpExchange, status: Int, contentType: String, body: String) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        send(exchange, status, "application/json; charset=utf-8", body)
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder()
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun <T> runSyncWait(timeoutMs: Long, task: () -> T): T? {
        if (Bukkit.isPrimaryThread()) {
            return task()
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        plugin.platform.runSync(Runnable {
            try {
                result.set(task())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        })

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) return null
        error.get()?.let { throw it }
        return result.get()
    }

    private fun logInfo(message: String) {
        plugin.logger.info("[Dis2FA] $message")
    }

    private fun logWarn(message: String) {
        plugin.logger.warning("[Dis2FA] $message")
    }

    private fun logError(message: String) {
        plugin.logger.severe("[Dis2FA] $message")
    }

    private companion object {
        private val INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Dis2FA Config Editor</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #111315;
      --panel: #191c20;
      --text: #f2f2f2;
      --muted: #a0a6ad;
      --accent: #49c2a3;
      --danger: #e05a5a;
      --border: #2a2f36;
      --code: #20252b;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: "IBM Plex Sans", "Segoe UI", sans-serif;
      background: radial-gradient(circle at top, #1c222a 0, #111315 55%, #0b0d10 100%);
      color: var(--text);
    }
    header {
      padding: 24px 28px;
      border-bottom: 1px solid var(--border);
      background: var(--panel);
      position: sticky;
      top: 0;
      z-index: 2;
    }
    header h1 {
      margin: 0 0 8px 0;
      font-size: 20px;
      letter-spacing: 0.5px;
    }
    header p {
      margin: 0;
      color: var(--muted);
      font-size: 14px;
    }
    main {
      padding: 24px 28px 40px;
      max-width: 1200px;
      margin: 0 auto;
    }
    .controls {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
      margin-bottom: 16px;
    }
    .tabs {
      display: flex;
      gap: 8px;
      margin-bottom: 16px;
    }
    .tab-btn {
      border: 1px solid var(--border);
      border-radius: 999px;
      padding: 8px 14px;
      font-size: 13px;
      background: #22262c;
      cursor: pointer;
    }
    .tab-btn.active {
      background: var(--accent);
      color: #0d1a16;
      border-color: transparent;
    }
    .panel.hidden {
      display: none;
    }
    .controls input, .controls button, .controls select {
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 10px 12px;
      font-size: 14px;
      background: #1b2026;
      color: var(--text);
    }
    .controls button {
      background: var(--accent);
      color: white;
      cursor: pointer;
      border: none;
    }
    .controls button.secondary {
      background: #2a2f36;
      color: var(--text);
    }
    .status {
      font-size: 13px;
      color: var(--muted);
      margin-bottom: 16px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      background: var(--panel);
      border-radius: 12px;
      overflow: hidden;
      box-shadow: 0 10px 30px rgba(0,0,0,0.05);
    }
    @keyframes fadeInUp {
      from { opacity: 0; transform: translateY(6px); }
      to { opacity: 1; transform: translateY(0); }
    }
    .fade-in {
      animation: fadeInUp 0.35s ease both;
    }
    th, td {
      text-align: left;
      padding: 12px 14px;
      border-bottom: 1px solid var(--border);
      vertical-align: middle;
    }
    th {
      background: #1f2328;
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
    }
    tr:last-child td {
      border-bottom: none;
    }
    .key {
      font-family: "IBM Plex Mono", "Consolas", monospace;
      font-size: 13px;
      background: var(--code);
      padding: 4px 8px;
      border-radius: 6px;
      display: inline-block;
    }
    .badge {
      display: inline-block;
      padding: 4px 8px;
      border-radius: 999px;
      font-size: 11px;
      background: #2a2f36;
      color: #f0b088;
      margin-left: 8px;
    }
    .action-btn {
      padding: 6px 10px;
      border-radius: 6px;
      border: none;
      cursor: pointer;
      font-size: 12px;
    }
    .action-save { background: var(--accent); color: #fff; }
    .action-reset { background: var(--danger); color: #fff; margin-left: 6px; }
    .value-input {
      width: 100%;
      min-width: 180px;
      padding: 8px 10px;
      border: 1px solid var(--border);
      border-radius: 6px;
      font-size: 13px;
      background: #1b2026;
      color: var(--text);
    }
    .switch {
      position: relative;
      display: inline-block;
      width: 42px;
      height: 22px;
    }
    .switch input {
      opacity: 0;
      width: 0;
      height: 0;
    }
    .slider {
      position: absolute;
      cursor: pointer;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background-color: #2a2f36;
      transition: .2s;
      border-radius: 999px;
    }
    .slider:before {
      position: absolute;
      content: "";
      height: 16px;
      width: 16px;
      left: 3px;
      top: 3px;
      background-color: #f4f4f4;
      transition: .2s;
      border-radius: 50%;
      box-shadow: 0 1px 3px rgba(0,0,0,0.2);
    }
    .switch input:checked + .slider {
      background-color: var(--accent);
    }
    .switch input:checked + .slider:before {
      transform: translateX(20px);
    }
    .player-cell {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .avatar {
      width: 28px;
      height: 28px;
      border-radius: 6px;
      flex-shrink: 0;
      border: 1px solid var(--border);
    }
    .muted {
      color: var(--muted);
      font-size: 12px;
    }
    @media (max-width: 900px) {
      th:nth-child(2), td:nth-child(2) { display: none; }
      header, main { padding: 18px; }
    }
  </style>
</head>
<body>
  <header>
    <h1>Dis2FA Config Editor</h1>
    <p>Edit config.yml from the browser. Sensitive values stay hidden unless updated.</p>
  </header>
  <main>
    <div class="tabs">
      <button class="tab-btn active" data-tab="settings">Settings</button>
      <button class="tab-btn" data-tab="links">Linked Players</button>
    </div>

    <section id="settingsPanel" class="panel">
      <div class="controls">
        <input id="token" type="password" placeholder="Web editor token">
        <button id="connect">Connect</button>
        <select id="langSelect" title="Language">
          <option value="en">English</option>
          <option value="ar">Arabic</option>
        </select>
        <input id="search" type="search" placeholder="Filter keys">
        <button id="reload" class="secondary">Reload</button>
        <button id="logout" class="secondary">Logout</button>
      </div>
      <div id="status" class="status">Not connected.</div>
      <table>
        <thead>
          <tr>
            <th>Setting</th>
            <th>Value</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody id="rows"></tbody>
      </table>
    </section>

    <section id="linksPanel" class="panel hidden">
      <div class="controls">
        <input id="linkSearch" type="search" placeholder="Search players or Discord IDs">
      </div>
      <div id="linkStatus" class="status">No data loaded.</div>
      <table>
        <thead>
          <tr>
            <th>Player</th>
            <th>Discord ID</th>
            <th>UUID</th>
            <th>Last Seen</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody id="linkRows"></tbody>
      </table>
    </section>
  </main>
  <script>
    const tokenInput = document.getElementById('token');
    const connectBtn = document.getElementById('connect');
    const reloadBtn = document.getElementById('reload');
    const logoutBtn = document.getElementById('logout');
    const searchInput = document.getElementById('search');
    const langSelect = document.getElementById('langSelect');
    const statusEl = document.getElementById('status');
    const rowsEl = document.getElementById('rows');
    const linkSearch = document.getElementById('linkSearch');
    const linkStatusEl = document.getElementById('linkStatus');
    const linkRowsEl = document.getElementById('linkRows');
    const tabs = document.querySelectorAll('.tab-btn');
    const settingsPanel = document.getElementById('settingsPanel');
    const linksPanel = document.getElementById('linksPanel');
    let cachedItems = [];
    let cachedLinks = [];
    let authMode = 'none';
    let linksLoaded = false;
    let linkSearchTimer = null;

    const storedToken = localStorage.getItem('dis2fa_token') || '';
    tokenInput.value = storedToken;

    function setStatus(text, isError) {
      statusEl.textContent = text;
      statusEl.style.color = isError ? '#c0392b' : '#6b6b6b';
    }

    function setLinkStatus(text, isError) {
      linkStatusEl.textContent = text;
      linkStatusEl.style.color = isError ? '#c0392b' : '#6b6b6b';
    }

    function authHeaders() {
      const token = tokenInput.value.trim();
      return token ? { 'X-Dis2FA-Token': token } : {};
    }

    function saveToken() {
      const token = tokenInput.value.trim();
      if (token.length > 0) {
        localStorage.setItem('dis2fa_token', token);
      }
      return true;
    }

    function refreshAuth() {
      fetch('/api/me', { headers: authHeaders() })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error('unauthorized');
          }
          authMode = data.auth || 'token';
          setStatus(authMode === 'session' ? 'Magic link session active.' : 'Token auth ready.', false);
        })
        .catch(() => {
          authMode = 'none';
          setStatus('Not authenticated. Use token or /da web link.', true);
        });
    }

    function loadConfig() {
      saveToken();
      setStatus('Loading...');
      fetch('/api/config', { headers: authHeaders() })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Failed to load');
          }
          cachedItems = data.items || [];
          renderRows();
          syncLanguageSelect();
          setStatus('Loaded ' + cachedItems.length + ' keys.');
        })
        .catch(err => {
          setStatus(err.message || 'Failed to load', true);
        });
    }

    function loadLinks() {
      saveToken();
      const query = linkSearch.value.trim();
      setLinkStatus('Loading...');
      const qs = query.length ? '?q=' + encodeURIComponent(query) : '';
      fetch('/api/links' + qs, { headers: authHeaders() })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Failed to load links');
          }
          cachedLinks = data.items || [];
          renderLinks();
          setLinkStatus('Loaded ' + cachedLinks.length + ' links.');
        })
        .catch(err => {
          setLinkStatus(err.message || 'Failed to load links', true);
        });
    }

    function formatRelative(ms) {
      if (!ms) return 'n/a';
      const diff = Date.now() - ms;
      const seconds = Math.floor(diff / 1000);
      if (seconds < 60) return seconds + 's ago';
      const minutes = Math.floor(seconds / 60);
      if (minutes < 60) return minutes + 'm ago';
      const hours = Math.floor(minutes / 60);
      if (hours < 24) return hours + 'h ago';
      const days = Math.floor(hours / 24);
      return days + 'd ago';
    }

    function renderLinks() {
      linkRowsEl.innerHTML = '';
      cachedLinks.forEach(item => {
        const tr = document.createElement('tr');

        const playerTd = document.createElement('td');
        playerTd.className = 'player-cell';
        const avatar = document.createElement('img');
        const playerName = item.playerName || '';
        const avatarKey = playerName.length ? playerName : (item.uuid || '');
        avatar.src = 'https://minotar.net/avatar/' + encodeURIComponent(avatarKey) + '/32';
        avatar.alt = playerName || 'player';
        avatar.className = 'avatar';
        const nameSpan = document.createElement('span');
        nameSpan.textContent = playerName || 'unknown';
        playerTd.appendChild(avatar);
        playerTd.appendChild(nameSpan);

        const discordTd = document.createElement('td');
        discordTd.textContent = item.discordId || '';

        const uuidTd = document.createElement('td');
        uuidTd.textContent = item.uuid || '';

        const seenTd = document.createElement('td');
        seenTd.textContent = formatRelative(item.lastSeen);

        const actionTd = document.createElement('td');
        const unlinkBtn = document.createElement('button');
        unlinkBtn.className = 'action-btn action-reset';
        unlinkBtn.textContent = 'Unlink';
        unlinkBtn.addEventListener('click', () => unlinkLink(item.uuid));

        const randomizeBtn = document.createElement('button');
        randomizeBtn.className = 'action-btn action-save';
        randomizeBtn.textContent = 'Randomize';
        randomizeBtn.style.marginLeft = '6px';
        randomizeBtn.addEventListener('click', () => randomizeLink(item.uuid));

        actionTd.appendChild(unlinkBtn);
        actionTd.appendChild(randomizeBtn);

        tr.appendChild(playerTd);
        tr.appendChild(discordTd);
        tr.appendChild(uuidTd);
        tr.appendChild(seenTd);
        tr.appendChild(actionTd);
        linkRowsEl.appendChild(tr);
      });
    }

    function unlinkLink(uuid) {
      if (!uuid) return;
      if (!confirm('Unlink this player?')) return;
      const body = new URLSearchParams();
      body.set('uuid', uuid);
      fetch('/api/links/unlink', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, authHeaders()),
        body: body.toString()
      })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Unlink failed');
          }
          setLinkStatus('Unlinked ' + uuid + '.', false);
          loadLinks();
        })
        .catch(err => {
          setLinkStatus(err.message || 'Unlink failed', true);
        });
    }

    function randomizeLink(uuid) {
      if (!uuid) return;
      const body = new URLSearchParams();
      body.set('uuid', uuid);
      fetch('/api/links/randomize', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, authHeaders()),
        body: body.toString()
      })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Randomize failed');
          }
          setLinkStatus('Randomized device ID for ' + uuid + '.', false);
          loadLinks();
        })
        .catch(err => {
          setLinkStatus(err.message || 'Randomize failed', true);
        });
    }

    function renderRows() {
      const filter = searchInput.value.trim().toLowerCase();
      rowsEl.innerHTML = '';
      let rowIndex = 0;
      cachedItems.forEach(item => {
        const haystack = (item.displayName + ' ' + (item.description || '')).toLowerCase();
        if (filter && haystack.indexOf(filter) === -1) {
          return;
        }
        const tr = document.createElement('tr');
        tr.className = 'fade-in';
        tr.style.animationDelay = (rowIndex * 12) + 'ms';
        rowIndex += 1;

        const keyTd = document.createElement('td');
        const nameSpan = document.createElement('span');
        nameSpan.textContent = item.displayName || item.key;
        keyTd.appendChild(nameSpan);
        if (item.restart) {
          const badge = document.createElement('span');
          badge.className = 'badge';
          badge.textContent = 'restart';
          keyTd.appendChild(badge);
        }

        const valueTd = document.createElement('td');
        let input;
        if (item.type === 'boolean') {
          const wrapper = document.createElement('label');
          wrapper.className = 'switch';
          const checkbox = document.createElement('input');
          checkbox.type = 'checkbox';
          checkbox.checked = (item.value || 'false').toString().toLowerCase() === 'true';
          checkbox.dataset.kind = 'boolean';
          const slider = document.createElement('span');
          slider.className = 'slider';
          wrapper.appendChild(checkbox);
          wrapper.appendChild(slider);
          valueTd.appendChild(wrapper);
          input = checkbox;
        } else {
          input = document.createElement('input');
          input.type = item.type === 'number' ? 'number' : 'text';
          input.value = item.value || '';
          if (item.type === 'list') {
            input.placeholder = 'comma, separated';
          }
          valueTd.appendChild(input);
        }
        if (input) {
          input.className = input.type === 'checkbox' ? '' : 'value-input';
        }
        if (item.sensitive) {
          input.value = '';
          input.placeholder = 'hidden';
          input.dataset.sensitive = 'true';
        }
        if (item.sensitive) {
          const note = document.createElement('div');
          note.className = 'muted';
          note.textContent = 'Enter a new value to update.';
          valueTd.appendChild(note);
        }
        if (item.description) {
          const desc = document.createElement('div');
          desc.className = 'muted';
          desc.textContent = item.description;
          valueTd.appendChild(desc);
        }

        const actionTd = document.createElement('td');
        const saveBtn = document.createElement('button');
        saveBtn.className = 'action-btn action-save';
        saveBtn.textContent = 'Save';
        saveBtn.addEventListener('click', () => saveValue(item, input));
        const resetBtn = document.createElement('button');
        resetBtn.className = 'action-btn action-reset';
        resetBtn.textContent = 'Reset';
        resetBtn.addEventListener('click', () => resetValue(item));
        actionTd.appendChild(saveBtn);
        actionTd.appendChild(resetBtn);

        tr.appendChild(keyTd);
        tr.appendChild(valueTd);
        tr.appendChild(actionTd);
        rowsEl.appendChild(tr);
      });
    }

    function syncLanguageSelect() {
      if (!langSelect) return;
      const localeItem = cachedItems.find(item => item.key === 'locale');
      if (!localeItem) return;
      const value = (localeItem.value || 'en').toLowerCase();
      if (langSelect.value !== value) {
        langSelect.value = value;
      }
    }

    function setConfigKey(key, value) {
      if (!saveToken()) return;
      const body = new URLSearchParams();
      body.set('key', key);
      body.set('value', value);
      fetch('/api/config/set', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, authHeaders()),
        body: body.toString()
      })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Update failed');
          }
          setStatus('Updated ' + key + (data.restart ? ' (restart required).' : '.'));
          loadConfig();
        })
        .catch(err => {
          setStatus(err.message || 'Update failed', true);
        });
    }

    function saveValue(item, input) {
      if (!saveToken()) return;
      const name = item.displayName || item.key;
      if (input.dataset.sensitive === 'true' && input.value.trim() === '') {
        setStatus('Enter a value for ' + name + ' or use Reset.', true);
        return;
      }
      let value = input.value;
      if (input.type === 'checkbox') {
        value = input.checked ? 'true' : 'false';
      }
      const body = new URLSearchParams();
      body.set('key', item.key);
      body.set('value', value);
      fetch('/api/config/set', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, authHeaders()),
        body: body.toString()
      })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Update failed');
          }
          setStatus('Updated ' + name + (data.restart ? ' (restart required).' : '.'));
          loadConfig();
        })
        .catch(err => {
          setStatus(err.message || 'Update failed', true);
        });
    }

    function resetValue(item) {
      if (!saveToken()) return;
      const name = item.displayName || item.key;
      const body = new URLSearchParams();
      body.set('key', item.key);
      fetch('/api/config/reset', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, authHeaders()),
        body: body.toString()
      })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Reset failed');
          }
          setStatus('Reset ' + name + (data.restart ? ' (restart required).' : '.'));
          loadConfig();
        })
        .catch(err => {
          setStatus(err.message || 'Reset failed', true);
        });
    }

    connectBtn.addEventListener('click', loadConfig);
    reloadBtn.addEventListener('click', loadConfig);
    logoutBtn.addEventListener('click', () => { window.location = '/logout'; });
    if (langSelect) {
      langSelect.addEventListener('change', () => setConfigKey('locale', langSelect.value));
    }
    searchInput.addEventListener('input', renderRows);
    linkSearch.addEventListener('input', () => {
      if (linkSearchTimer) {
        clearTimeout(linkSearchTimer);
      }
      linkSearchTimer = setTimeout(loadLinks, 300);
    });
    tabs.forEach(btn => {
      btn.addEventListener('click', () => {
        tabs.forEach(other => other.classList.remove('active'));
        btn.classList.add('active');
        const tab = btn.getAttribute('data-tab');
        if (tab === 'links') {
          settingsPanel.classList.add('hidden');
          linksPanel.classList.remove('hidden');
          if (!linksLoaded) {
            linksLoaded = true;
            loadLinks();
          }
        } else {
          linksPanel.classList.add('hidden');
          settingsPanel.classList.remove('hidden');
        }
      });
    });
    refreshAuth();
  </script>
</body>
</html>
""".trimIndent()
    }
}
