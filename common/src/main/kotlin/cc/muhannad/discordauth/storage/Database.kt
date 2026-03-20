package cc.muhannad.discordauth.storage

import cc.muhannad.discordauth.model.DeviceRequest
import cc.muhannad.discordauth.model.Link
import cc.muhannad.discordauth.model.PendingCode
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Database(private val plugin: JavaPlugin) {

    private val lock = ReentrantLock()
    private var connection: Connection? = null
    private val dbFile: File = File(plugin.dataFolder, "discordauth.db")

    fun open() {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA foreign_keys=ON")
        }
        connection = conn
        createTables()
    }

    fun close() {
        lock.withLock {
            connection?.close()
            connection = null
        }
    }

    private fun createTables() {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS links (
                        uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        discord_id TEXT NOT NULL UNIQUE,
                        device_id TEXT,
                        last_ip TEXT,
                        linked_at INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pending_codes (
                        code TEXT PRIMARY KEY,
                        uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        ip TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS device_requests (
                        request_id TEXT PRIMARY KEY,
                        uuid TEXT NOT NULL,
                        discord_id TEXT NOT NULL,
                        old_device_id TEXT,
                        new_device_id TEXT NOT NULL,
                        new_ip TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        channel_id TEXT,
                        message_id TEXT
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private inline fun <T> withConnection(block: (Connection) -> T): T {
        return lock.withLock {
            val conn = connection ?: throw IllegalStateException("Database not open")
            block(conn)
        }
    }

    fun getLink(uuid: UUID): Link? {
        return withConnection { conn ->
            conn.prepareStatement(
                "SELECT uuid, player_name, discord_id, device_id, last_ip, linked_at, last_seen FROM links WHERE uuid = ?"
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    return@use Link(
                        uuid = UUID.fromString(rs.getString("uuid")),
                        playerName = rs.getString("player_name"),
                        discordId = rs.getString("discord_id"),
                        deviceId = rs.getString("device_id"),
                        lastIp = rs.getString("last_ip"),
                        linkedAt = rs.getLong("linked_at"),
                        lastSeen = rs.getLong("last_seen")
                    )
                }
            }
        }
    }

    fun getLinkByDiscordId(discordId: String): Link? {
        return withConnection { conn ->
            conn.prepareStatement(
                "SELECT uuid, player_name, discord_id, device_id, last_ip, linked_at, last_seen FROM links WHERE discord_id = ?"
            ).use { ps ->
                ps.setString(1, discordId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    return@use Link(
                        uuid = UUID.fromString(rs.getString("uuid")),
                        playerName = rs.getString("player_name"),
                        discordId = rs.getString("discord_id"),
                        deviceId = rs.getString("device_id"),
                        lastIp = rs.getString("last_ip"),
                        linkedAt = rs.getLong("linked_at"),
                        lastSeen = rs.getLong("last_seen")
                    )
                }
            }
        }
    }

    fun upsertLink(uuid: UUID, playerName: String, discordId: String, deviceId: String?, ip: String?, now: Long) {
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO links (uuid, player_name, discord_id, device_id, last_ip, linked_at, last_seen)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    player_name = excluded.player_name,
                    discord_id = excluded.discord_id,
                    device_id = excluded.device_id,
                    last_ip = excluded.last_ip,
                    last_seen = excluded.last_seen
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, playerName)
                ps.setString(3, discordId)
                ps.setString(4, deviceId)
                ps.setString(5, ip)
                ps.setLong(6, now)
                ps.setLong(7, now)
                ps.executeUpdate()
            }
        }
    }

    fun updateLinkDevice(uuid: UUID, deviceId: String, ip: String, now: Long) {
        withConnection { conn ->
            conn.prepareStatement(
                "UPDATE links SET device_id = ?, last_ip = ?, last_seen = ? WHERE uuid = ?"
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.setString(2, ip)
                ps.setLong(3, now)
                ps.setString(4, uuid.toString())
                ps.executeUpdate()
            }
        }
    }

    fun updateDeviceIdOnly(uuid: UUID, deviceId: String, now: Long) {
        withConnection { conn ->
            conn.prepareStatement(
                "UPDATE links SET device_id = ?, last_seen = ? WHERE uuid = ?"
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.setLong(2, now)
                ps.setString(3, uuid.toString())
                ps.executeUpdate()
            }
        }
    }

    fun touchLink(uuid: UUID, playerName: String, ip: String, now: Long) {
        withConnection { conn ->
            conn.prepareStatement(
                "UPDATE links SET player_name = ?, last_ip = ?, last_seen = ? WHERE uuid = ?"
            ).use { ps ->
                ps.setString(1, playerName)
                ps.setString(2, ip)
                ps.setLong(3, now)
                ps.setString(4, uuid.toString())
                ps.executeUpdate()
            }
        }
    }

    fun unlink(uuid: UUID) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM links WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeUpdate()
            }
        }
    }

    fun deletePendingCodesForUuid(uuid: UUID) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM pending_codes WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeUpdate()
            }
        }
    }

    fun insertPendingCode(code: String, uuid: UUID, playerName: String, ip: String, createdAt: Long, expiresAt: Long) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO pending_codes (code, uuid, player_name, ip, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, code)
                ps.setString(2, uuid.toString())
                ps.setString(3, playerName)
                ps.setString(4, ip)
                ps.setLong(5, createdAt)
                ps.setLong(6, expiresAt)
                ps.executeUpdate()
            }
        }
    }

    fun getPendingCode(code: String): PendingCode? {
        return withConnection { conn ->
            conn.prepareStatement(
                "SELECT code, uuid, player_name, ip, created_at, expires_at FROM pending_codes WHERE code = ?"
            ).use { ps ->
                ps.setString(1, code)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    return@use PendingCode(
                        code = rs.getString("code"),
                        uuid = UUID.fromString(rs.getString("uuid")),
                        playerName = rs.getString("player_name"),
                        ip = rs.getString("ip"),
                        createdAt = rs.getLong("created_at"),
                        expiresAt = rs.getLong("expires_at")
                    )
                }
            }
        }
    }

    fun deletePendingCode(code: String) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM pending_codes WHERE code = ?").use { ps ->
                ps.setString(1, code)
                ps.executeUpdate()
            }
        }
    }

    fun cleanupExpiredCodes(now: Long): Int {
        return withConnection { conn ->
            conn.prepareStatement("DELETE FROM pending_codes WHERE expires_at <= ?").use { ps ->
                ps.setLong(1, now)
                ps.executeUpdate()
            }
        }
    }

    fun getPendingDeviceRequest(uuid: UUID, newDeviceId: String, now: Long): DeviceRequest? {
        return withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT request_id, uuid, discord_id, old_device_id, new_device_id, new_ip, created_at, expires_at, status, channel_id, message_id
                FROM device_requests
                WHERE uuid = ? AND new_device_id = ? AND status = 'PENDING' AND expires_at > ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, newDeviceId)
                ps.setLong(3, now)
                ps.executeQuery().use { rs ->
                    return@use if (rs.next()) mapDeviceRequest(rs) else null
                }
            }
        }
    }

    fun insertDeviceRequest(
        requestId: String,
        uuid: UUID,
        discordId: String,
        oldDeviceId: String?,
        newDeviceId: String,
        newIp: String,
        createdAt: Long,
        expiresAt: Long
    ) {
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO device_requests (request_id, uuid, discord_id, old_device_id, new_device_id, new_ip, created_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, requestId)
                ps.setString(2, uuid.toString())
                ps.setString(3, discordId)
                ps.setString(4, oldDeviceId)
                ps.setString(5, newDeviceId)
                ps.setString(6, newIp)
                ps.setLong(7, createdAt)
                ps.setLong(8, expiresAt)
                ps.executeUpdate()
            }
        }
    }

    fun updateDeviceRequestMessage(requestId: String, channelId: String, messageId: String) {
        withConnection { conn ->
            conn.prepareStatement(
                "UPDATE device_requests SET channel_id = ?, message_id = ? WHERE request_id = ?"
            ).use { ps ->
                ps.setString(1, channelId)
                ps.setString(2, messageId)
                ps.setString(3, requestId)
                ps.executeUpdate()
            }
        }
    }

    fun getDeviceRequest(requestId: String): DeviceRequest? {
        return withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT request_id, uuid, discord_id, old_device_id, new_device_id, new_ip, created_at, expires_at, status, channel_id, message_id
                FROM device_requests
                WHERE request_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, requestId)
                ps.executeQuery().use { rs ->
                    return@use if (rs.next()) mapDeviceRequest(rs) else null
                }
            }
        }
    }

    fun updateDeviceRequestStatus(requestId: String, status: String) {
        withConnection { conn ->
            conn.prepareStatement(
                "UPDATE device_requests SET status = ? WHERE request_id = ?"
            ).use { ps ->
                ps.setString(1, status)
                ps.setString(2, requestId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanupExpiredDeviceRequests(now: Long): Int {
        return withConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM device_requests WHERE expires_at <= ? AND status = 'PENDING'"
            ).use { ps ->
                ps.setLong(1, now)
                ps.executeUpdate()
            }
        }
    }

    fun searchLinks(query: String, limit: Int): List<Link> {
        val lowered = query.trim().lowercase()
        val pattern = "%$lowered%"
        return withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT uuid, player_name, discord_id, device_id, last_ip, linked_at, last_seen
                FROM links
                WHERE lower(player_name) LIKE ? OR lower(discord_id) LIKE ?
                ORDER BY last_seen DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, pattern)
                ps.setString(2, pattern)
                ps.setInt(3, limit.coerceIn(1, 100))
                ps.executeQuery().use { rs ->
                    val results = mutableListOf<Link>()
                    while (rs.next()) {
                        results.add(
                            Link(
                                uuid = UUID.fromString(rs.getString("uuid")),
                                playerName = rs.getString("player_name"),
                                discordId = rs.getString("discord_id"),
                                deviceId = rs.getString("device_id"),
                                lastIp = rs.getString("last_ip"),
                                linkedAt = rs.getLong("linked_at"),
                                lastSeen = rs.getLong("last_seen")
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    private fun mapDeviceRequest(rs: ResultSet): DeviceRequest {
        return DeviceRequest(
            requestId = rs.getString("request_id"),
            uuid = UUID.fromString(rs.getString("uuid")),
            discordId = rs.getString("discord_id"),
            oldDeviceId = rs.getString("old_device_id"),
            newDeviceId = rs.getString("new_device_id"),
            newIp = rs.getString("new_ip"),
            createdAt = rs.getLong("created_at"),
            expiresAt = rs.getLong("expires_at"),
            status = rs.getString("status"),
            channelId = rs.getString("channel_id"),
            messageId = rs.getString("message_id")
        )
    }
}
