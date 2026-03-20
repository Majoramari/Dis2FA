package cc.muhannad.discordauth

import cc.muhannad.discordauth.platform.FoliaPlatformAdapter
import cc.muhannad.discordauth.platform.PlatformAdapter

class FoliaDis2FAPlugin : Dis2FAPlugin() {
    override fun createPlatformAdapter(): PlatformAdapter = FoliaPlatformAdapter(this)
}
