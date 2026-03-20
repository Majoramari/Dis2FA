package cc.muhannad.discordauth.platform

import cc.muhannad.discordauth.Dis2FAPlugin
import cc.muhannad.discordauth.listeners.PaperChatBridgeListener
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class PaperPlatformAdapter(private val plugin: Dis2FAPlugin) : PlatformAdapter {

    override fun registerChatBridgeListener() {
        val listener = PaperChatBridgeListener(plugin)
        plugin.server.pluginManager.registerEvents(listener, plugin)
    }

    override fun runSync(task: Runnable) {
        Bukkit.getScheduler().runTask(plugin, task)
    }

    override fun runSyncRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        val scheduled = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks)
        return BukkitTaskHandle(scheduled)
    }

    override fun runAsyncRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        val scheduled = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks)
        return BukkitTaskHandle(scheduled)
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

private class BukkitTaskHandle(private val task: BukkitTask) : TaskHandle {
    override fun cancel() {
        task.cancel()
    }
}
