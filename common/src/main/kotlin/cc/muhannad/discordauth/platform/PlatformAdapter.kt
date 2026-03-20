package cc.muhannad.discordauth.platform

import java.util.UUID

interface PlatformAdapter {
    fun registerChatBridgeListener()
    fun runSync(task: Runnable)
    fun runSyncRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle
    fun runAsyncRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle
    fun getTps(): DoubleArray?
    fun banPlayer(playerName: String, uuid: UUID?, reason: String)
}

interface TaskHandle {
    fun cancel()
}
