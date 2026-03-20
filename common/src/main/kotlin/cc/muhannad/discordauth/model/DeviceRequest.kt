package cc.muhannad.discordauth.model

import java.util.UUID

data class DeviceRequest(
    val requestId: String,
    val uuid: UUID,
    val discordId: String,
    val oldDeviceId: String?,
    val newDeviceId: String,
    val newIp: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String,
    val channelId: String?,
    val messageId: String?
)
