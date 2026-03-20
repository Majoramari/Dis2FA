package cc.muhannad.discordauth.listeners

import cc.muhannad.discordauth.Dis2FAPlugin
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.entity.PlayerDeathEvent

class PaperChatBridgeListener(private val plugin: Dis2FAPlugin) : Listener {

    private val handler = ChatBridgeHandler(plugin)
    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val message = plain.serialize(event.message()).trim()
        if (message.isBlank()) return
        handler.handleChat(event.player.name, message)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val base = event.joinMessage()?.let { plain.serialize(it).trim() } ?: ""
        handler.handleJoin(event.player.name, base)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val base = event.quitMessage()?.let { plain.serialize(it).trim() } ?: ""
        handler.handleQuit(event.player.name, base)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val raw = event.deathMessage()?.let { plain.serialize(it).trim() } ?: ""
        val displayName = plain.serialize(player.displayName()).trim()
        handler.handleDeath(player, displayName, raw, event)
    }

    @EventHandler
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val display = event.advancement.display ?: return
        if (!display.doesAnnounceToChat()) return

        val title = plain.serialize(display.title()).trim()
        if (title.isBlank()) return

        handler.handleAdvancement(event.player.name, title)
    }
}
