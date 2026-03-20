package cc.muhannad.discordauth.utils

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO

object AvatarLinkImage {
    fun render(minecraftAvatarUrl: String, discordAvatarUrl: String): ByteArray? {
        return try {
            val width = 520
            val height = 200
            val avatarSize = 140
            val margin = 36

            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = canvas.createGraphics()
            enableQuality(g)

            val mc = loadAndPrepare(minecraftAvatarUrl, avatarSize)
            val dc = loadAndPrepare(discordAvatarUrl, avatarSize)

            val y = (height - avatarSize) / 2
            val leftX = margin
            val rightX = width - avatarSize - margin

            drawAvatar(g, mc, leftX, y, avatarSize)
            drawAvatar(g, dc, rightX, y, avatarSize)
            drawLinkIcon(g, width / 2, height / 2)

            g.dispose()

            val out = ByteArrayOutputStream()
            ImageIO.write(canvas, "png", out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun enableQuality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun loadAndPrepare(url: String, size: Int): BufferedImage {
        val uri = URI.create(url)
        val src = ImageIO.read(uri.toURL())
        val square = cropSquare(src)
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        enableQuality(g)
        g.drawImage(square, 0, 0, size, size, null)
        g.dispose()
        return scaled
    }

    private fun cropSquare(src: BufferedImage): BufferedImage {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        return src.getSubimage(x, y, size, size)
    }

    private fun drawAvatar(g: Graphics2D, avatar: BufferedImage, x: Int, y: Int, size: Int) {
        val circle = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val cg = circle.createGraphics()
        enableQuality(cg)
        cg.clip = Ellipse2D.Double(0.0, 0.0, size.toDouble(), size.toDouble())
        cg.drawImage(avatar, 0, 0, size, size, null)
        cg.dispose()

        g.color = Color(0, 0, 0, 80)
        g.fillOval(x + 4, y + 6, size, size)
        g.drawImage(circle, x, y, null)
        g.color = Color(255, 255, 255, 200)
        g.stroke = BasicStroke(4f)
        g.drawOval(x, y, size, size)
    }

    private fun drawLinkIcon(g: Graphics2D, cx: Int, cy: Int) {
        val gg = g.create() as Graphics2D
        enableQuality(gg)

        gg.translate(cx.toDouble(), cy.toDouble())
        gg.rotate(Math.toRadians(-28.0))

        gg.color = Color(74, 85, 104, 230)
        gg.stroke = BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val linkW = 90
        val linkH = 42
        val arc = 42
        val gap = 30

        gg.drawRoundRect(-linkW / 2 - gap / 2, -linkH / 2, linkW, linkH, arc, arc)
        gg.drawRoundRect(-linkW / 2 + gap / 2, -linkH / 2, linkW, linkH, arc, arc)

        gg.dispose()
    }
}
