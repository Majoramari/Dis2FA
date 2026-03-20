package cc.muhannad.discordauth.platform

import cc.muhannad.discordauth.Dis2FAPlugin
import cc.muhannad.discordauth.listeners.FoliaChatBridgeListener
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.TimeUnit

class FoliaPlatformAdapter(private val plugin: Dis2FAPlugin) : PlatformAdapter {

    override fun registerChatBridgeListener() {
        val listener = FoliaChatBridgeListener(plugin)
        plugin.server.pluginManager.registerEvents(listener, plugin)
    }

    override fun runSync(task: Runnable) {
        Bukkit.getGlobalRegionScheduler().run(plugin) { _: ScheduledTask -> task.run() }
    }

    override fun runSyncRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        val scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { _: ScheduledTask -> task.run() },
            delayTicks,
            periodTicks
        )
        return FoliaTaskHandle(scheduled)
    }

    override fun runAsyncRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        val scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin,
            { _: ScheduledTask -> task.run() },
            delayTicks * 50L,
            periodTicks * 50L,
            TimeUnit.MILLISECONDS
        )
        return FoliaTaskHandle(scheduled)
    }

    override fun getTps(): DoubleArray? = Bukkit.getServer().tps

    override fun banPlayer(playerName: String, uuid: UUID?, reason: String) {
        val console = Bukkit.getConsoleSender()
        val safeReason = reason.trim()
        val command = if (safeReason.isBlank()) {
            "ban $playerName"
        } else {
            "ban $playerName $safeReason"
        }
        Bukkit.dispatchCommand(console, command)
    }
}

private class FoliaTaskHandle(private val task: ScheduledTask) : TaskHandle {
    override fun cancel() {
        task.cancel()
    }
}
