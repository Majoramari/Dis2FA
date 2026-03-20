package cc.muhannad.discordauth.utils

import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object HashUtil {
    private val random = SecureRandom()

    fun generateCode(length: Int): String {
        val safeLength = length.coerceIn(4, 8)
        val builder = StringBuilder(safeLength)
        repeat(safeLength) {
            builder.append(random.nextInt(10))
        }
        return builder.toString()
    }

    fun deviceId(uuid: UUID, ip: String, salt: String, v4Prefix: Int, v6Prefix: Int): String {
        val normalizedIp = normalizeIp(ip, v4Prefix, v6Prefix)
        val input = "${uuid}|${normalizedIp}|${salt}"
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeIp(ip: String, v4Prefix: Int, v6Prefix: Int): String {
        if (ip.isBlank()) return ip
        return try {
            val address = InetAddress.getByName(ip)
            val bytes = address.address

            if (bytes.size == 4) {
                val masked = applyPrefix(bytes, v4Prefix.coerceIn(0, 32))
                InetAddress.getByAddress(masked).hostAddress
            } else {
                val masked = applyPrefix(bytes, v6Prefix.coerceIn(0, 128))
                InetAddress.getByAddress(masked).hostAddress
            }
        } catch (e: UnknownHostException) {
            ip
        } catch (e: Exception) {
            ip
        }
    }

    private fun applyPrefix(bytes: ByteArray, prefix: Int): ByteArray {
        val result = bytes.copyOf()
        var remaining = prefix
        var i = 0
        while (i < result.size) {
            if (remaining >= 8) {
                remaining -= 8
            } else {
                val mask = (0xFF shl (8 - remaining)).toByte()
                result[i] = result[i].toInt().and(mask.toInt()).toByte()
                remaining = 0
            }
            if (remaining == 0) {
                for (j in i + 1 until result.size) {
                    result[j] = 0
                }
                break
            }
            i++
        }
        return result
    }
}
