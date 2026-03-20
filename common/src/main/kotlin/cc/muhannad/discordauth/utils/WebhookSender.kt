package cc.muhannad.discordauth.utils

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object WebhookSender {
    private val client = HttpClient.newHttpClient()

    fun send(webhookUrl: String, username: String, avatarUrl: String?, content: String) {
        if (webhookUrl.isBlank()) return
        if (content.isBlank()) return

        val safeContent = if (content.length > 2000) content.take(1990) + "..." else content
        val payload = buildJson(username, avatarUrl, safeContent, null)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
    }

    fun sendEmbed(webhookUrl: String, username: String, avatarUrl: String?, description: String, color: Int) {
        if (webhookUrl.isBlank()) return
        if (description.isBlank()) return

        val safeDescription = if (description.length > 4096) description.take(4086) + "..." else description
        val payload = buildJson(username, avatarUrl, null, EmbedPayload(safeDescription, color))

        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun buildJson(
        username: String,
        avatarUrl: String?,
        content: String?,
        embed: EmbedPayload?
    ): String {
        val builder = StringBuilder()
        builder.append("{")
        builder.append("\"username\":\"").append(escape(username)).append("\"")
        if (!avatarUrl.isNullOrBlank()) {
            builder.append(",\"avatar_url\":\"").append(escape(avatarUrl)).append("\"")
        }
        if (!content.isNullOrBlank()) {
            builder.append(",\"content\":\"").append(escape(content)).append("\"")
        }
        if (embed != null) {
            builder.append(",\"embeds\":[{")
            builder.append("\"description\":\"").append(escape(embed.description)).append("\"")
            builder.append(",\"color\":").append(embed.color)
            builder.append("}]")
        }
        builder.append("}")
        return builder.toString()
    }

    private fun escape(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private data class EmbedPayload(val description: String, val color: Int)
}
