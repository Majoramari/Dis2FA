package cc.muhannad.discordauth.model

import java.util.UUID

data class PendingCode(
    val code: String,
    val uuid: UUID,
    val playerName: String,
    val ip: String,
    val createdAt: Long,
    val expiresAt: Long
)
