package cc.muhannad.discordauth.platform

import cc.muhannad.discordauth.Dis2FAPlugin
import cc.muhannad.discordauth.listeners.BukkitChatBridgeListener
import org.bukkit.BanList
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class BukkitPlatformAdapter(private val plugin: Dis2FAPlugin) : PlatformAdapter {

    override fun registerChatBridgeListener() {
        val listener = BukkitChatBridgeListener(plugin)
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

    override fun getTps(): DoubleArray? = null

    override fun banPlayer(playerName: String, uuid: UUID?, reason: String) {
        val banList = Bukkit.getBanList(BanList.Type.NAME)
        banList.addBan(playerName, reason, null, "Dis2FA")
    }
}

private class BukkitTaskHandle(private val task: BukkitTask) : TaskHandle {
    override fun cancel() {
        task.cancel()
    }
}
