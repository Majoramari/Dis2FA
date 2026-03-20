package cc.muhannad.discordauth.web

import cc.muhannad.discordauth.Dis2FAPlugin
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bukkit.Bukkit
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebConfigServer(private val plugin: Dis2FAPlugin) {

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var host: String = ""
    private var port: Int = 0
    private var token: String = ""

    private val sensitiveKeys = setOf("bot-token", "chat-bridge.webhook-url", "web-editor.token")
    private val restartKeys = setOf("bot-token", "discord.guild-id", "discord.clear-global-commands")

    @Synchronized
    fun startFromConfig() {
        val cfg = plugin.config
        val enabled = cfg.getBoolean("web-editor.enabled", false)
        val newHost = cfg.getString("web-editor.bind-address")?.trim().orEmpty().ifBlank { "127.0.0.1" }
        val newPort = cfg.getInt("web-editor.port", 8166).coerceIn(1, 65535)
        val newToken = cfg.getString("web-editor.token")?.trim().orEmpty()

        if (!enabled) {
            stop()
            return
        }

        if (newToken.isBlank()) {
            logWarn("Web config editor token is blank. Server not started.")
            stop()
            return
        }

        if (server != null && newHost == host && newPort == port) {
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
        if (!authorize(exchange)) {
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
        if (!authorize(exchange)) {
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
        if (!authorize(exchange)) {
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

    private data class ConfigItem(
        val key: String,
        val type: String,
        val value: String,
        val sensitive: Boolean,
        val restart: Boolean
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
                restart = restartKeys.contains(key)
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
            sb.append(""""restart":${item.restart}""")
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

    private fun authorize(exchange: HttpExchange): Boolean {
        val expected = token
        if (expected.isBlank()) return false

        val auth = exchange.requestHeaders.getFirst("Authorization")
        if (auth != null && auth.startsWith("Bearer ")) {
            if (auth.removePrefix("Bearer ").trim() == expected) return true
        }

        val headerToken = exchange.requestHeaders.getFirst("X-Dis2FA-Token")
        if (headerToken == expected) return true

        val queryToken = parseQuery(exchange.requestURI.rawQuery)["token"]
        if (queryToken == expected) return true

        return false
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
      color-scheme: light;
      --bg: #f6f2ea;
      --panel: #ffffff;
      --text: #1d1d1d;
      --muted: #6b6b6b;
      --accent: #2a7f62;
      --danger: #c0392b;
      --border: #e3dbcf;
      --code: #f2efe8;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: "IBM Plex Sans", "Segoe UI", sans-serif;
      background: radial-gradient(circle at top, #fff7e9 0, #f6f2ea 50%, #efe9df 100%);
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
    .controls input, .controls button, .controls select {
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 10px 12px;
      font-size: 14px;
    }
    .controls button {
      background: var(--accent);
      color: white;
      cursor: pointer;
      border: none;
    }
    .controls button.secondary {
      background: #e6dfd2;
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
      background: #faf7f0;
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
      background: #ffe7d6;
      color: #8b3c2f;
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
    <div class="controls">
      <input id="token" type="password" placeholder="Web editor token">
      <button id="connect">Connect</button>
      <input id="search" type="search" placeholder="Filter keys">
      <button id="reload" class="secondary">Reload</button>
    </div>
    <div id="status" class="status">Not connected.</div>
    <table>
      <thead>
        <tr>
          <th>Key</th>
          <th>Type</th>
          <th>Value</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody id="rows"></tbody>
    </table>
  </main>
  <script>
    const tokenInput = document.getElementById('token');
    const connectBtn = document.getElementById('connect');
    const reloadBtn = document.getElementById('reload');
    const searchInput = document.getElementById('search');
    const statusEl = document.getElementById('status');
    const rowsEl = document.getElementById('rows');
    let cachedItems = [];

    const storedToken = localStorage.getItem('dis2fa_token') || '';
    tokenInput.value = storedToken;

    function setStatus(text, isError) {
      statusEl.textContent = text;
      statusEl.style.color = isError ? '#c0392b' : '#6b6b6b';
    }

    function authHeaders() {
      const token = tokenInput.value.trim();
      return { 'X-Dis2FA-Token': token };
    }

    function saveToken() {
      const token = tokenInput.value.trim();
      if (token.length === 0) {
        setStatus('Token required.', true);
        return false;
      }
      localStorage.setItem('dis2fa_token', token);
      return true;
    }

    function loadConfig() {
      if (!saveToken()) return;
      setStatus('Loading...');
      fetch('/api/config', { headers: authHeaders() })
        .then(r => r.json())
        .then(data => {
          if (!data.ok) {
            throw new Error(data.error || 'Failed to load');
          }
          cachedItems = data.items || [];
          renderRows();
          setStatus('Loaded ' + cachedItems.length + ' keys.');
        })
        .catch(err => {
          setStatus(err.message || 'Failed to load', true);
        });
    }

    function renderRows() {
      const filter = searchInput.value.trim().toLowerCase();
      rowsEl.innerHTML = '';
      let rowIndex = 0;
      cachedItems.forEach(item => {
        if (filter && item.key.toLowerCase().indexOf(filter) === -1) {
          return;
        }
        const tr = document.createElement('tr');
        tr.className = 'fade-in';
        tr.style.animationDelay = (rowIndex * 12) + 'ms';
        rowIndex += 1;

        const keyTd = document.createElement('td');
        const keySpan = document.createElement('span');
        keySpan.className = 'key';
        keySpan.textContent = item.key;
        keyTd.appendChild(keySpan);
        if (item.restart) {
          const badge = document.createElement('span');
          badge.className = 'badge';
          badge.textContent = 'restart';
          keyTd.appendChild(badge);
        }

        const typeTd = document.createElement('td');
        typeTd.textContent = item.type;

        const valueTd = document.createElement('td');
        let input;
        if (item.type === 'boolean') {
          input = document.createElement('select');
          const optTrue = document.createElement('option');
          optTrue.value = 'true';
          optTrue.textContent = 'true';
          const optFalse = document.createElement('option');
          optFalse.value = 'false';
          optFalse.textContent = 'false';
          input.appendChild(optTrue);
          input.appendChild(optFalse);
          input.value = (item.value || 'false').toString().toLowerCase() === 'true' ? 'true' : 'false';
        } else {
          input = document.createElement('input');
          input.type = item.type === 'number' ? 'number' : 'text';
          input.value = item.value || '';
          if (item.type === 'list') {
            input.placeholder = 'comma, separated';
          }
        }
        input.className = 'value-input';
        if (item.sensitive) {
          input.value = '';
          input.placeholder = 'hidden';
          input.dataset.sensitive = 'true';
        }
        valueTd.appendChild(input);
        if (item.sensitive) {
          const note = document.createElement('div');
          note.className = 'muted';
          note.textContent = 'Enter a new value to update.';
          valueTd.appendChild(note);
        }

        const actionTd = document.createElement('td');
        const saveBtn = document.createElement('button');
        saveBtn.className = 'action-btn action-save';
        saveBtn.textContent = 'Save';
        saveBtn.addEventListener('click', () => saveValue(item.key, input));
        const resetBtn = document.createElement('button');
        resetBtn.className = 'action-btn action-reset';
        resetBtn.textContent = 'Reset';
        resetBtn.addEventListener('click', () => resetValue(item.key));
        actionTd.appendChild(saveBtn);
        actionTd.appendChild(resetBtn);

        tr.appendChild(keyTd);
        tr.appendChild(typeTd);
        tr.appendChild(valueTd);
        tr.appendChild(actionTd);
        rowsEl.appendChild(tr);
      });
    }

    function saveValue(key, input) {
      if (!saveToken()) return;
      if (input.dataset.sensitive === 'true' && input.value.trim() === '') {
        setStatus('Enter a value for ' + key + ' or use Reset.', true);
        return;
      }
      const body = new URLSearchParams();
      body.set('key', key);
      body.set('value', input.value);
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

    function resetValue(key) {
      if (!saveToken()) return;
      const body = new URLSearchParams();
      body.set('key', key);
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
          setStatus('Reset ' + key + (data.restart ? ' (restart required).' : '.'));
          loadConfig();
        })
        .catch(err => {
          setStatus(err.message || 'Reset failed', true);
        });
    }

    connectBtn.addEventListener('click', loadConfig);
    reloadBtn.addEventListener('click', loadConfig);
    searchInput.addEventListener('input', renderRows);
  </script>
</body>
</html>
""".trimIndent()
    }
}
