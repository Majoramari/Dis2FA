package cc.muhannad.discordauth.utils;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;

public final class AvatarLinkImage {
    private AvatarLinkImage() {
    }

    public static byte[] render(String minecraftAvatarUrl, String discordAvatarUrl) {
        try {
            int width = 520;
            int height = 200;
            int avatarSize = 140;
            int margin = 36;

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            enableQuality(g);

            BufferedImage mc = loadAndPrepare(minecraftAvatarUrl, avatarSize);
            BufferedImage dc = loadAndPrepare(discordAvatarUrl, avatarSize);

            int y = (height - avatarSize) / 2;
            int leftX = margin;
            int rightX = width - avatarSize - margin;

            drawAvatar(g, mc, leftX, y, avatarSize);
            drawAvatar(g, dc, rightX, y, avatarSize);
            drawLinkIcon(g, width / 2, height / 2);

            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private static void enableQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static BufferedImage loadAndPrepare(String url, int size) throws IOException {
        URI uri = URI.create(url);
        BufferedImage src = ImageIO.read(uri.toURL());
        BufferedImage square = cropSquare(src);
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        enableQuality(g);
        g.drawImage(square, 0, 0, size, size, null);
        g.dispose();
        return scaled;
    }

    private static BufferedImage cropSquare(BufferedImage src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        return src.getSubimage(x, y, size, size);
    }

    private static void drawAvatar(Graphics2D g, BufferedImage avatar, int x, int y, int size) {
        BufferedImage circle = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D cg = circle.createGraphics();
        enableQuality(cg);
        cg.setClip(new Ellipse2D.Double(0, 0, size, size));
        cg.drawImage(avatar, 0, 0, size, size, null);
        cg.dispose();

        g.setColor(new Color(0, 0, 0, 80));
        g.fillOval(x + 4, y + 6, size, size);
        g.drawImage(circle, x, y, null);
        g.setColor(new Color(255, 255, 255, 200));
        g.setStroke(new BasicStroke(4f));
        g.drawOval(x, y, size, size);
    }

    private static void drawLinkIcon(Graphics2D g, int cx, int cy) {
        Graphics2D gg = (Graphics2D) g.create();
        enableQuality(gg);

        gg.translate(cx, cy);
        gg.rotate(Math.toRadians(-28));

        gg.setColor(new Color(74, 85, 104, 230));
        gg.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int linkW = 90;
        int linkH = 42;
        int arc = 42;
        int gap = 30;

        gg.drawRoundRect(-linkW / 2 - gap / 2, -linkH / 2, linkW, linkH, arc, arc);
        gg.drawRoundRect(-linkW / 2 + gap / 2, -linkH / 2, linkW, linkH, arc, arc);

        gg.dispose();
    }
}
