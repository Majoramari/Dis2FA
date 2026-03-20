package cc.muhannad.discordauth.listeners

import cc.muhannad.discordauth.Dis2FAPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class BukkitChatBridgeListener(private val plugin: Dis2FAPlugin) : Listener {

    private val handler = ChatBridgeHandler(plugin)

    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val message = event.message.trim()
        if (message.isBlank()) return
        handler.handleChat(event.player.name, message)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val base = event.joinMessage ?: ""
        handler.handleJoin(event.player.name, base)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val base = event.quitMessage ?: ""
        handler.handleQuit(event.player.name, base)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val raw = event.deathMessage ?: ""
        handler.handleDeath(player, player.name, raw, event)
    }

    @EventHandler
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val display = invokeNoArg(event.advancement, "getDisplay") ?: invokeNoArg(event.advancement, "display")
        if (display == null) return
        if (!doesAnnounce(display)) return

        val title = extractTitle(display)
        if (title.isBlank()) return
        handler.handleAdvancement(event.player.name, title)
    }

    private fun doesAnnounce(display: Any): Boolean {
        val result = invokeNoArg(display, "doesAnnounceToChat") ?: invokeNoArg(display, "shouldAnnounceChat")
        return result as? Boolean ?: true
    }

    private fun extractTitle(display: Any): String {
        val titleValue = invokeNoArg(display, "title") ?: invokeNoArg(display, "getTitle")
        if (titleValue == null) return ""
        if (titleValue is String) return titleValue.trim()
        return trySerializeAdventure(titleValue)?.trim().orEmpty()
    }

    private fun invokeNoArg(target: Any, method: String): Any? {
        return try {
            target.javaClass.getMethod(method).invoke(target)
        } catch (_: Exception) {
            null
        }
    }

    private fun trySerializeAdventure(component: Any): String? {
        return try {
            val componentClass = Class.forName("net.kyori.adventure.text.Component")
            if (!componentClass.isInstance(component)) return component.toString()
            val serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer")
            val plainText = serializerClass.getMethod("plainText").invoke(null)
            val serialize = serializerClass.getMethod("serialize", componentClass)
            serialize.invoke(plainText, component) as? String
        } catch (_: Exception) {
            component.toString()
        }
    }
}
