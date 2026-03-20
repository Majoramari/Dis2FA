package cc.muhannad.discordauth

import cc.muhannad.discordauth.platform.PaperPlatformAdapter
import cc.muhannad.discordauth.platform.PlatformAdapter

class PaperDis2FAPlugin : Dis2FAPlugin() {
    override fun createPlatformAdapter(): PlatformAdapter = PaperPlatformAdapter(this)
}
