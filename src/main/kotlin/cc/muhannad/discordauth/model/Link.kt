package cc.muhannad.discordauth.model

import java.util.UUID

data class Link(
    val uuid: UUID,
    val playerName: String,
    val discordId: String,
    val deviceId: String?,
    val lastIp: String?,
    val linkedAt: Long,
    val lastSeen: Long
)
