package cc.muhannad.discordauth

import cc.muhannad.discordauth.platform.BukkitPlatformAdapter
import cc.muhannad.discordauth.platform.PlatformAdapter

class BukkitDis2FAPlugin : Dis2FAPlugin() {
    override fun createPlatformAdapter(): PlatformAdapter = BukkitPlatformAdapter(this)
}
