package com.blockland.backend.seed;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/** Procedural 16:9 cover art for demo modes and groups (real covers are uploaded in the admin UI). */
public final class CoverArt {
    public static final int W = 512;
    public static final int H = 288;
    /** Muzzle of {@link #handgun} in its local coordinates. */
    private static final int MUZZLE_X = 102, MUZZLE_Y = -23;

    private CoverArt() {
    }

    /** Floating islands in a sky; {@code dusk} gives an evening palette. */
    public static byte[] islands(boolean dusk, long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        Color top = dusk ? new Color(0x2B1E4F) : new Color(0x2F7BD6);
        Color bottom = dusk ? new Color(0xF08A4B) : new Color(0xBFE6FF);
        g.setPaint(new GradientPaint(0, 0, top, 0, H, bottom));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W * 0.78f, H * 0.28f), 120,
                new float[]{0f, 1f}, new Color[]{dusk ? new Color(255, 200, 120, 200) : new Color(255, 255, 230, 190), new Color(255, 255, 255, 0)}));
        g.fillRect(0, 0, W, H);

        Random random = new Random(seed);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.setColor(Color.WHITE);
        for (int i = 0; i < 7; i++) {
            int cx = random.nextInt(W), cy = 20 + random.nextInt(H / 2);
            for (int k = 0; k < 4; k++) g.fill(new Ellipse2D.Float(cx + k * 18 - 30, cy - (k % 2) * 8, 46, 20));
        }
        g.setComposite(AlphaComposite.SrcOver);

        float[][] islands = {{0.18f, 0.62f, 1.0f}, {0.52f, 0.48f, 1.35f}, {0.86f, 0.7f, 0.85f}, {0.36f, 0.82f, 0.6f}};
        for (float[] isl : islands) island(g, W * isl[0], H * isl[1], isl[2], dusk);
        g.dispose();
        return png(image);
    }

    /** Crossed swords over a crimson arena glow. */
    public static byte[] swords(long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1A0B12), W, H, new Color(0x5A1020)));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W * 0.62f, H * 0.5f), 200,
                new float[]{0f, 1f}, new Color[]{new Color(255, 90, 60, 150), new Color(255, 60, 60, 0)}));
        g.fillRect(0, 0, W, H);

        Random random = new Random(seed);
        g.setStroke(new BasicStroke(2f));
        for (int i = 0; i < 26; i++) {
            int x = random.nextInt(W), y = random.nextInt(H), len = 20 + random.nextInt(60);
            g.setColor(new Color(255, 180 + random.nextInt(60), 120, 40 + random.nextInt(80)));
            g.drawLine(x, y, x + len, y - len / 3);
        }
        sword(g, W * 0.62f, H * 0.5f, -38, new Color(0xE8EEF5));
        sword(g, W * 0.62f, H * 0.5f, 38, new Color(0xD9C38A));
        g.dispose();
        return png(image);
    }

    /** A handgun firing at a red crosshair, muzzle flash glowing over a dark steel gradient. */
    public static byte[] pistol(long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(0x090E18), W, H, new Color(0x1E3247)));
        g.fillRect(0, 0, W, H);
        AffineTransform gun = AffineTransform.getTranslateInstance(W * 0.36, H * 0.53);
        gun.rotate(Math.toRadians(-8));
        Point2D muzzle = gun.transform(new Point2D.Float(MUZZLE_X, MUZZLE_Y), null);
        g.setPaint(new RadialGradientPaint(muzzle, 190, new float[]{0f, 1f},
                new Color[]{new Color(255, 150, 60, 150), new Color(255, 120, 40, 0)}));
        g.fillRect(0, 0, W, H);

        Random random = new Random(seed);
        for (int i = 0; i < 30; i++) {
            float size = 1.5f + random.nextFloat() * 2.5f;
            g.setColor(new Color(255, 190 + random.nextInt(50), 120, 30 + random.nextInt(90)));
            g.fill(new Ellipse2D.Float(random.nextInt(W), random.nextInt(H), size, size));
        }
        crosshair(g, gun.transform(new Point2D.Float(MUZZLE_X + 150, MUZZLE_Y), null));
        // the bullet, just before it hits
        Point2D trailStart = gun.transform(new Point2D.Float(MUZZLE_X + 70, MUZZLE_Y), null);
        Point2D trailEnd = gun.transform(new Point2D.Float(MUZZLE_X + 128, MUZZLE_Y), null);
        g.setPaint(new GradientPaint(trailStart, new Color(255, 220, 150, 0), trailEnd, new Color(255, 240, 200, 230)));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(trailStart, trailEnd));
        // sparks thrown forward
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 16; i++) {
            double angle = Math.toRadians(-8 + (random.nextDouble() - 0.5) * 120);
            double from = 40 + random.nextInt(25), to = from + 10 + random.nextInt(30);
            g.setColor(new Color(255, 200 + random.nextInt(50), 110, 80 + random.nextInt(150)));
            g.draw(new Line2D.Double(muzzle.getX() + Math.cos(angle) * from, muzzle.getY() + Math.sin(angle) * from,
                    muzzle.getX() + Math.cos(angle) * to, muzzle.getY() + Math.sin(angle) * to));
        }

        Graphics2D s = (Graphics2D) g.create();
        s.transform(gun);
        handgun(s);
        // ejected shell casings above the ejection port
        for (int i = 0; i < 2; i++) {
            Graphics2D c = (Graphics2D) s.create();
            c.translate(14 - i * 32 - random.nextInt(10), -58 - random.nextInt(24));
            c.rotate(random.nextDouble() * Math.PI);
            c.setPaint(new GradientPaint(0, -3, new Color(0xF0D27A), 0, 3, new Color(0x9A7426)));
            c.fill(new RoundRectangle2D.Float(-7, -3, 14, 6, 3, 3));
            c.dispose();
        }
        muzzleFlash(s, random);
        s.dispose();
        g.dispose();
        return png(image);
    }

    /** A glowing biohazard sign in toxic fog: dark green gradient, slime drips, spores and quarantine tape. */
    public static byte[] infection(long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(0x030A06), W, H, new Color(0x123A1E)));
        g.fillRect(0, 0, W, H);
        Point2D.Float center = new Point2D.Float(W * 0.66f, H * 0.44f);
        g.setPaint(new RadialGradientPaint(center, 210, new float[]{0f, 1f},
                new Color[]{new Color(150, 255, 60, 120), new Color(90, 200, 40, 0)}));
        g.fillRect(0, 0, W, H);

        Random random = new Random(seed);
        // fog banks low over the ground
        for (int i = 0; i < 9; i++) {
            float w = 160 + random.nextInt(160), h = 30 + random.nextInt(30);
            g.setColor(new Color(120, 220, 90, 14 + random.nextInt(18)));
            g.fill(new Ellipse2D.Float(random.nextInt(W) - w / 2, H * 0.62f + random.nextInt(H / 3), w, h));
        }
        // slime dripping from the top edge
        for (int i = 0; i < 11; i++) {
            float x = 8 + random.nextInt(W - 16), len = 14 + random.nextInt(70), w = 4 + random.nextInt(6);
            g.setColor(new Color(110, 200, 40, 120 + random.nextInt(90)));
            g.fill(new RoundRectangle2D.Float(x - w / 2, -6, w, len, w, w));
            g.fill(new Ellipse2D.Float(x - w * 0.8f, len - w * 1.1f - 6, w * 1.6f, w * 1.8f));
        }
        // spores
        for (int i = 0; i < 46; i++) {
            float size = 1.5f + random.nextFloat() * 3.5f;
            g.setColor(new Color(170 + random.nextInt(60), 255, 90, 40 + random.nextInt(140)));
            g.fill(new Ellipse2D.Float(random.nextInt(W), random.nextInt(H), size, size));
        }

        Area sign = biohazard(center.x, center.y, 4.1f);
        for (int k = 6; k >= 1; k--) {
            g.setColor(new Color(150, 255, 60, 16));
            g.setStroke(new BasicStroke(k * 5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(sign);
        }
        g.setPaint(new GradientPaint(center.x, center.y - 110, new Color(0xE6FF6A), center.x, center.y + 110, new Color(0x6CD62A)));
        g.fill(sign);
        g.setColor(new Color(20, 60, 10, 160));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(sign);

        tape(g, W * 0.5f, H * 0.89f, -6);
        tape(g, W * 0.3f, H * 0.99f, 5);
        g.dispose();
        return png(image);
    }

    /** A shooting range at night: lane lights, human silhouette targets with hit marks, a red reticle on the nearest. */
    public static byte[] range(long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(0x0B1220), 0, H, new Color(0x1C2A36)));
        g.fillRect(0, 0, W, H);
        Random random = new Random(seed);
        float horizon = H * 0.42f;
        // floor converging to the back wall, with lane lines
        g.setPaint(new GradientPaint(0, horizon, new Color(0x2A3440), 0, H, new Color(0x11171E)));
        g.fill(new Polygon(new int[]{0, W, W, 0}, new int[]{(int) horizon, (int) horizon, H, H}, 4));
        g.setStroke(new BasicStroke(2f));
        for (int i = -4; i <= 4; i++) {
            g.setColor(new Color(255, 214, 90, 60));
            g.draw(new Line2D.Double(W / 2.0 + i * 24, horizon, W / 2.0 + i * 150, H));
        }
        // ceiling lights
        for (int i = 0; i < 6; i++) {
            float x = W * (0.12f + i * 0.152f);
            g.setPaint(new RadialGradientPaint(new Point2D.Float(x, 18), 70, new float[]{0f, 1f},
                    new Color[]{new Color(255, 236, 190, 90), new Color(255, 236, 190, 0)}));
            g.fillRect((int) x - 70, 0, 140, 90);
            g.setColor(new Color(0xFFF1CF));
            g.fill(new RoundRectangle2D.Float(x - 20, 8, 40, 5, 4, 4));
        }
        // silhouette targets, the far ones smaller; the reticle sits on the last (nearest right) one
        float[][] targets = {{0.18f, 0.6f, 0.55f}, {0.38f, 0.5f, 0.38f}, {0.58f, 0.47f, 0.3f}, {0.8f, 0.56f, 0.48f}};
        Point2D aim = new Point2D.Float(W / 2f, H / 2f);
        for (float[] t : targets) {
            float cx = W * t[0], base = H * t[1] + 60 * t[2], s = t[2];
            Graphics2D d = (Graphics2D) g.create();
            d.translate(cx, base);
            d.scale(s, s);
            d.setColor(new Color(0, 0, 0, 90));
            d.fill(new Ellipse2D.Float(-60, -6, 120, 16));
            d.setPaint(new GradientPaint(0, -230, new Color(0xF2E6C9), 0, 0, new Color(0xC9B48A)));
            Path2D body = new Path2D.Float();
            body.moveTo(-58, 0);
            body.lineTo(-58, -120);
            body.quadTo(-58, -150, -28, -156);
            body.lineTo(28, -156);
            body.quadTo(58, -150, 58, -120);
            body.lineTo(58, 0);
            body.closePath();
            d.fill(body);
            d.fill(new Ellipse2D.Float(-26, -214, 52, 58));
            d.setColor(new Color(0xB33A2E));
            d.setStroke(new BasicStroke(4f));
            d.draw(new Ellipse2D.Float(-30, -120, 60, 60));
            d.draw(new Ellipse2D.Float(-12, -200, 24, 26));
            for (int k = 0; k < 5; k++) {
                d.setColor(new Color(20, 20, 24, 220));
                d.fill(new Ellipse2D.Float(-30 + random.nextInt(60), -150 + random.nextInt(110), 7, 7));
            }
            d.dispose();
            aim = new Point2D.Float(cx, base - 185 * s);
        }
        crosshair(g, aim);
        // spent casings on the floor
        for (int i = 0; i < 9; i++) {
            Graphics2D c = (Graphics2D) g.create();
            c.translate(40 + random.nextInt(W - 80), H - 30 + random.nextInt(24));
            c.rotate(random.nextDouble() * Math.PI);
            c.setPaint(new GradientPaint(0, -3, new Color(0xF0D27A), 0, 3, new Color(0x9A7426)));
            c.fill(new RoundRectangle2D.Float(-7, -3, 14, 6, 3, 3));
            c.dispose();
        }
        g.dispose();
        return png(image);
    }

    /** A half-built bridge of blocks reaching across a deep void towards a golden goal island, in a dusk sky. */
    public static byte[] bridge(long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(0x2B1B4A), 0, H, new Color(0x04040A)));
        g.fillRect(0, 0, W, H);
        Random random = new Random(seed);
        for (int i = 0; i < 70; i++) {
            float size = 1 + random.nextFloat() * 2;
            g.setColor(new Color(255, 255, 255, 40 + random.nextInt(150)));
            g.fill(new Ellipse2D.Float(random.nextInt(W), random.nextInt((int) (H * 0.6f)), size, size));
        }
        // the goal island glowing on the right
        float gx = W * 0.8f, gy = H * 0.5f;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(gx, gy), 150, new float[]{0f, 1f},
                new Color[]{new Color(255, 205, 80, 110), new Color(255, 205, 80, 0)}));
        g.fillRect(0, 0, W, H);
        block(g, gx - 44, gy, 88, 18, new Color(0xF4C542), new Color(0xB8860B));
        g.setColor(new Color(0x5B4636));
        g.fill(new Polygon(new int[]{(int) gx - 40, (int) gx + 40, (int) gx + 12, (int) gx - 14},
                new int[]{(int) gy + 18, (int) gy + 18, (int) gy + 70, (int) gy + 62}, 4));
        // the spawn island on the left and the bridge growing out of it
        float sx = W * 0.14f, sy = H * 0.62f;
        block(g, sx - 50, sy, 100, 20, new Color(0xE8E4DA), new Color(0x8C8A86));
        g.setColor(new Color(0x4A4A52));
        g.fill(new Polygon(new int[]{(int) sx - 46, (int) sx + 46, (int) sx + 16, (int) sx - 18},
                new int[]{(int) sy + 20, (int) sy + 20, (int) sy + 80, (int) sy + 72}, 4));
        float size = 17;
        int blocks = 13;
        for (int i = 0; i < blocks; i++) {
            block(g, sx + 50 + i * size, sy - i * 0.9f, size, size, new Color(0xF5F5F5), new Color(0xB9B9C0));
        }
        // the outline where the next block goes
        float px = sx + 50 + blocks * size, py = sy - blocks * 0.9f;
        g.setColor(new Color(255, 255, 255, 170));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{4, 3}, 0));
        g.draw(new Rectangle2D.Float(px, py, size, size));
        // depth fog over the void
        g.setPaint(new GradientPaint(0, H * 0.7f, new Color(10, 6, 24, 0), 0, H, new Color(10, 6, 24, 230)));
        g.fillRect(0, (int) (H * 0.7f), W, H);
        g.dispose();
        return png(image);
    }

    /**
     * Tall block pillars rising out of a void at sunset, a player on top of each of the near ones and golden lucky items
     * floating between them; {@code many} draws a whole ring of them, otherwise two facing each other.
     */
    public static byte[] pillars(boolean many, long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(0x241A4A), 0, H, new Color(0xF2994A)));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W * 0.5f, H * 0.78f), 210, new float[]{0f, 1f},
                new Color[]{new Color(255, 214, 120, 170), new Color(255, 214, 120, 0)}));
        g.fillRect(0, 0, W, H);
        Random random = new Random(seed);
        for (int i = 0; i < 40; i++) {
            float s = 1 + random.nextFloat() * 1.6f;
            g.setColor(new Color(255, 255, 255, 30 + random.nextInt(120)));
            g.fill(new Ellipse2D.Float(random.nextInt(W), random.nextInt((int) (H * 0.4f)), s, s));
        }
        // far pillars first (thin, hazy), near ones last; x as a fraction of the width, top as a fraction of the height
        float[][] layout = many
                ? new float[][]{{0.07f, 0.42f, 9}, {0.24f, 0.36f, 10}, {0.35f, 0.46f, 8}, {0.63f, 0.44f, 8}, {0.76f, 0.34f, 10},
                {0.93f, 0.40f, 9}, {0.5f, 0.5f, 7}, {0.14f, 0.3f, 18}, {0.86f, 0.27f, 20}, {0.43f, 0.36f, 14}}
                : new float[][]{{0.32f, 0.48f, 9}, {0.68f, 0.46f, 9}, {0.18f, 0.3f, 22}, {0.82f, 0.32f, 22}};
        for (float[] p : layout) {
            float x = W * p[0], top = H * p[1], w = p[2];
            boolean near = w >= 14;
            Color stone = near ? new Color(0x3A3640) : new Color(0x6B5A78);
            for (float y = top; y < H; y += w) {
                Color face = ((int) ((y - top) / w) % 3 == 0) ? stone.brighter() : stone;
                block(g, x - w / 2, y, w, w, face, stone.darker());
            }
            g.setColor(new Color(0x5DA33A));
            g.fill(new Rectangle2D.Float(x - w * 1.8f, H * 0.86f, w * 3.6f, w * 0.5f));
            if (!near) continue;
            // a player on top: legs, body, head
            g.setColor(new Color(0x2B2B3A));
            g.fill(new Rectangle2D.Float(x - w * 0.22f, top - w * 0.9f, w * 0.44f, w * 0.9f));
            g.setColor(new Color(0x3F6FD8));
            g.fill(new Rectangle2D.Float(x - w * 0.3f, top - w * 1.8f, w * 0.6f, w * 0.95f));
            g.setColor(new Color(0xE0B48C));
            g.fill(new Rectangle2D.Float(x - w * 0.24f, top - w * 2.35f, w * 0.48f, w * 0.5f));
        }
        // lucky items: glowing golden blocks with a question mark
        float[][] items = many ? new float[][]{{0.29f, 0.56f, 18}, {0.6f, 0.62f, 14}, {0.69f, 0.36f, 12}} : new float[][]{{0.5f, 0.45f, 24}};
        for (float[] it : items) {
            float x = W * it[0], y = H * it[1], s = it[2];
            g.setPaint(new RadialGradientPaint(new Point2D.Float(x, y), s * 2.2f, new float[]{0f, 1f},
                    new Color[]{new Color(255, 220, 90, 200), new Color(255, 220, 90, 0)}));
            g.fill(new Ellipse2D.Float(x - s * 2.2f, y - s * 2.2f, s * 4.4f, s * 4.4f));
            block(g, x - s / 2, y - s / 2, s, s, new Color(0xF4C542), new Color(0xA8740B));
            g.setColor(new Color(0x6B3E00));
            g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, s * 0.8f));
            java.awt.FontMetrics metrics = g.getFontMetrics();
            g.drawString("?", x - metrics.stringWidth("?") / 2f, y + metrics.getAscent() * 0.36f);
        }
        // the void swallows the pillars' feet
        g.setPaint(new GradientPaint(0, H * 0.8f, new Color(12, 8, 26, 0), 0, H, new Color(12, 8, 26, 235)));
        g.fillRect(0, (int) (H * 0.8f), W, H);
        g.dispose();
        return png(image);
    }

    /**
     * 极限生存: blocky hills and trees running to the horizon between two red border walls closing in from the sides, a
     * golden apple glowing in front; {@code dusk} gives the evening palette.
     */
    public static byte[] uhc(boolean dusk, long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, dusk ? new Color(0x1F2148) : new Color(0x3C8BE0), 0, H * 0.6f,
                dusk ? new Color(0xE3774A) : new Color(0xCDEBFF)));
        g.fillRect(0, 0, W, H);
        Random random = new Random(seed);
        // hills: three bands of block columns, far (hazy) to near
        Color[][] bands = dusk
                ? new Color[][]{{new Color(0x5C5070), new Color(0x4A4060)}, {new Color(0x3E5A3A), new Color(0x2E4430)}, {new Color(0x35682C), new Color(0x5A3C28)}}
                : new Color[][]{{new Color(0x86B7A0), new Color(0x7AA592)}, {new Color(0x4F9A3E), new Color(0x3F7F30)}, {new Color(0x5DB33A), new Color(0x7A5534)}};
        float[] bandBase = {0.5f, 0.62f, 0.76f};
        int[] cell = {8, 12, 18};
        for (int b = 0; b < 3; b++) {
            double phase = random.nextDouble() * Math.PI * 2;
            for (int x = 0; x < W; x += cell[b]) {
                float top = H * bandBase[b] - (float) ((Math.sin(x / (60.0 + b * 30) + phase) * 0.5 + 0.5) * (14 + b * 6));
                top = Math.round(top / cell[b]) * cell[b];
                g.setColor(bands[b][0]);
                g.fill(new Rectangle2D.Float(x, top, cell[b], cell[b] * 0.5f));
                g.setColor(bands[b][1]);
                g.fill(new Rectangle2D.Float(x, top + cell[b] * 0.5f, cell[b], H - top));
                // a tree now and then on the nearer bands
                if (b > 0 && random.nextInt(9) == 0) {
                    float tw = cell[b] * 0.4f;
                    g.setColor(new Color(0x6B4A2B));
                    g.fill(new Rectangle2D.Float(x + cell[b] * 0.3f, top - cell[b] * 1.6f, tw, cell[b] * 1.6f));
                    g.setColor(dusk ? new Color(0x2C4A26) : new Color(0x2F7D2A));
                    g.fill(new Rectangle2D.Float(x - cell[b] * 0.4f, top - cell[b] * 2.8f, cell[b] * 1.8f, cell[b] * 1.4f));
                }
            }
        }
        // the border: translucent red walls with vanilla's diagonal stripes, nearer at the bottom
        for (int side = 0; side < 2; side++) {
            float edge = side == 0 ? W * 0.12f : W * 0.88f, far = side == 0 ? W * 0.3f : W * 0.7f;
            Path2D wall = new Path2D.Float();
            wall.moveTo(side == 0 ? 0 : W, 0);
            wall.lineTo(edge, 0);
            wall.lineTo(far, H * 0.45f);
            wall.lineTo(edge, H);
            wall.lineTo(side == 0 ? 0 : W, H);
            wall.closePath();
            g.setColor(new Color(255, 60, 50, 70));
            g.fill(wall);
            Graphics2D stripes = (Graphics2D) g.create();
            stripes.clip(wall);
            stripes.setColor(new Color(255, 120, 100, 80));
            stripes.setStroke(new BasicStroke(5));
            for (int i = -H; i < W + H; i += 18) stripes.draw(new Line2D.Float(i, 0, i + H, H));
            stripes.dispose();
            g.setColor(new Color(255, 90, 80, 200));
            g.setStroke(new BasicStroke(2));
            g.draw(new Line2D.Float(edge, 0, far, H * 0.45f));
            g.draw(new Line2D.Float(far, H * 0.45f, edge, H));
        }
        // a golden apple in front
        float ax = W * 0.5f, ay = H * 0.78f, s = 40;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(ax, ay), s * 1.8f, new float[]{0f, 1f},
                new Color[]{new Color(255, 220, 90, 190), new Color(255, 220, 90, 0)}));
        g.fill(new Ellipse2D.Float(ax - s * 1.8f, ay - s * 1.8f, s * 3.6f, s * 3.6f));
        block(g, ax - s / 2, ay - s / 2, s, s, new Color(0xF4C542), new Color(0xA8740B));
        g.setColor(new Color(0x6B4A2B));
        g.fill(new Rectangle2D.Float(ax - 2, ay - s / 2 - 10, 4, 10));
        g.setColor(new Color(0x3F9A2E));
        g.fill(new Ellipse2D.Float(ax + 1, ay - s / 2 - 12, 12, 6));
        g.dispose();
        return png(image);
    }

    /** The practice group: a shot-up bullseye and a staircase of blocks on a calm blue gradient. */
    public static byte[] practice(long seed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(0x0E2238), W, H, new Color(0x1F4A5C)));
        g.fillRect(0, 0, W, H);
        Random random = new Random(seed);
        for (int i = 0; i < 18; i++) {
            float s = 8 + random.nextInt(18);
            g.setColor(new Color(255, 255, 255, 10 + random.nextInt(20)));
            g.fill(new Rectangle2D.Float(random.nextInt(W), random.nextInt(H), s, s));
        }
        Point2D.Float center = new Point2D.Float(W * 0.32f, H * 0.5f);
        Color[] rings = {new Color(0xF5F1E6), new Color(0xD8402E), new Color(0xF5F1E6), new Color(0xD8402E), new Color(0xF5F1E6)};
        for (int i = 0; i < rings.length; i++) {
            float r = 92 - i * 18;
            g.setColor(rings[i]);
            g.fill(new Ellipse2D.Float(center.x - r, center.y - r, r * 2, r * 2));
        }
        g.setColor(new Color(0xD8402E));
        g.fill(new Ellipse2D.Float(center.x - 8, center.y - 8, 16, 16));
        for (int k = 0; k < 6; k++) {
            g.setColor(new Color(20, 20, 24, 230));
            double a = random.nextDouble() * Math.PI * 2, d = random.nextDouble() * 40;
            g.fill(new Ellipse2D.Float((float) (center.x + Math.cos(a) * d) - 4, (float) (center.y + Math.sin(a) * d) - 4, 8, 8));
        }
        float size = 34, bx = W * 0.58f, by = H * 0.72f;
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row <= col; row++) {
                block(g, bx + col * size, by - (row + 1) * size, size, size, new Color(0x8BC34A), new Color(0x4E7A24));
            }
        }
        g.dispose();
        return png(image);
    }

    /** A block seen from the front: dark rim, shaded face, a light top edge. */
    private static void block(Graphics2D g, float x, float y, float w, float h, Color face, Color edge) {
        g.setColor(edge);
        g.fill(new Rectangle2D.Float(x, y, w, h));
        g.setPaint(new GradientPaint(x, y, face, x, y + h, face.darker()));
        g.fill(new Rectangle2D.Float(x + 1.5f, y + 1.5f, w - 3, h - 3));
        g.setColor(new Color(255, 255, 255, 90));
        g.fill(new Rectangle2D.Float(x + 1.5f, y + 1.5f, w - 3, Math.max(2, h * 0.16f)));
    }

    private static void island(Graphics2D g, float cx, float cy, float scale, boolean dusk) {
        int w = (int) (90 * scale), h = (int) (60 * scale);
        Polygon rock = new Polygon(new int[]{(int) cx - w / 2, (int) cx + w / 2, (int) cx + w / 6, (int) cx - w / 8},
                new int[]{(int) cy, (int) cy, (int) cy + h, (int) cy + h / 2 + h / 3}, 4);
        g.setPaint(new GradientPaint(cx, cy, dusk ? new Color(0x5A3E3A) : new Color(0x7A5A3C), cx, cy + h, dusk ? new Color(0x2A1E24) : new Color(0x4A4A4A)));
        g.fill(rock);
        g.setColor(dusk ? new Color(0x4F7A3A) : new Color(0x5DA33A));
        g.fillRect((int) cx - w / 2, (int) cy - (int) (8 * scale), w, (int) (10 * scale));
        g.setColor(dusk ? new Color(0x3B5A2C) : new Color(0x3F7F2A));
        int trunkX = (int) (cx + w * 0.2f);
        g.fillRect(trunkX - 3, (int) (cy - 30 * scale), 6, (int) (24 * scale));
        g.fill(new Ellipse2D.Float(trunkX - 16 * scale, cy - 52 * scale, 32 * scale, 28 * scale));
    }

    private static void sword(Graphics2D g, float cx, float cy, double angleDeg, Color blade) {
        Graphics2D s = (Graphics2D) g.create();
        s.translate(cx, cy);
        s.rotate(Math.toRadians(angleDeg));
        s.setColor(blade);
        s.fill(new Polygon(new int[]{-7, 7, 0}, new int[]{40, 40, -110}, 3));
        s.fillRect(-7, 40, 14, 6);
        s.setColor(new Color(0x8A6A2A));
        s.fillRect(-26, 44, 52, 9);
        s.setColor(new Color(0x4A2E1A));
        s.fillRect(-5, 53, 10, 36);
        s.setColor(new Color(0xD9A441));
        s.fill(new Ellipse2D.Float(-8, 86, 16, 14));
        s.dispose();
    }

    /** Side view of a pistol pointing along +x, muzzle at ({@link #MUZZLE_X}, {@link #MUZZLE_Y}). */
    private static void handgun(Graphics2D s) {
        Color frame = new Color(0x2B313C);
        // grip (angled back, ribbed) and magazine baseplate
        Polygon grip = new Polygon(new int[]{-34, -86, -102, -55}, new int[]{2, 0, 72, 78}, 4);
        s.setPaint(new GradientPaint(-86, 0, new Color(0x3C4554), -55, 78, new Color(0x191D24)));
        s.fill(grip);
        Graphics2D ribs = (Graphics2D) s.create();
        ribs.clip(grip);
        ribs.setColor(new Color(255, 255, 255, 22));
        for (int y = 12; y < 78; y += 8) ribs.fillRect(-110, y, 80, 2);
        ribs.dispose();
        s.setColor(new Color(0x5C6676));
        s.fill(new Polygon(new int[]{-104, -54, -55, -105}, new int[]{71, 77, 83, 77}, 4));
        // trigger guard and trigger
        Path2D guard = new Path2D.Float();
        guard.moveTo(22, 1);
        guard.lineTo(21, 16);
        guard.quadTo(20, 30, 4, 30);
        guard.lineTo(-38, 30);
        s.setColor(frame);
        s.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        s.draw(guard);
        s.setColor(new Color(0x1C2129));
        s.fill(new Polygon(new int[]{-2, 6, 2, -4, -8, -4}, new int[]{2, 2, 12, 22, 21, 10}, 6));
        // frame with accessory rail, beavertail
        s.setColor(frame);
        s.fillRect(-86, -10, 160, 12);
        s.fill(new Polygon(new int[]{-86, -100, -97, -86}, new int[]{-12, -5, 2, 0}, 4));
        for (int x = 42; x <= 66; x += 8) s.fillRect(x, 2, 5, 4);
        // slide: steel gradient, top highlight, serrations, ejection port, sights, barrel
        s.setPaint(new GradientPaint(0, -36, new Color(0xCBD4E0), 0, -10, new Color(0x5C6676)));
        s.fill(new RoundRectangle2D.Float(-92, -36, 190, 26, 8, 8));
        s.setColor(new Color(255, 255, 255, 110));
        s.fillRect(-88, -35, 182, 2);
        s.setColor(new Color(0x3A4250));
        for (int x = -84; x <= -54; x += 6) s.fillRect(x, -32, 2, 18);
        s.setColor(new Color(0x232831));
        s.fillRect(8, -33, 30, 8);
        s.fillRect(98, MUZZLE_Y - 5, MUZZLE_X - 98, 10);
        s.setColor(new Color(0x5C6676));
        s.fillRect(86, -40, 6, 4);
        s.fillRect(-88, -41, 12, 5);
    }

    /** Spiky flash in front of the muzzle, longest along the firing direction. */
    private static void muzzleFlash(Graphics2D s, Random random) {
        float cx = MUZZLE_X + 20, cy = MUZZLE_Y;
        int spikes = 10;
        Path2D star = new Path2D.Float();
        for (int k = 0; k < spikes * 2; k++) {
            double angle = Math.PI * k / spikes;
            double r = k % 2 == 1 ? 7 : (22 + 46 * Math.max(0, Math.cos(angle))) * (0.75 + random.nextDouble() * 0.5);
            double x = cx + Math.cos(angle) * r, y = cy + Math.sin(angle) * r * 0.7;
            if (k == 0) star.moveTo(x, y);
            else star.lineTo(x, y);
        }
        star.closePath();
        s.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 70, new float[]{0f, 0.2f, 0.55f, 1f},
                new Color[]{new Color(255, 255, 235), new Color(255, 225, 120), new Color(255, 140, 40, 210), new Color(255, 90, 20, 0)}));
        s.fill(star);
        s.setColor(new Color(255, 255, 240));
        s.fill(new Ellipse2D.Float(cx - 14, cy - 7, 28, 14));
    }

    /**
     * The biohazard sign around (cx, cy) in units of {@code u} pixels (26 units to the tips): three crescents
     * (outer circle minus an offset inner circle), a centre hole and ring arcs inside each crescent's mouth.
     */
    private static Area biohazard(float cx, float cy, float u) {
        Area sign = new Area();
        Area ring = new Area(circle(cx, cy, 14.5f * u));
        ring.subtract(new Area(circle(cx, cy, 11.5f * u)));
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(-90 + 120 * i);
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            Area crescent = new Area(circle(cx + dx * 11 * u, cy + dy * 11 * u, 15 * u));
            crescent.subtract(new Area(circle(cx + dx * 15 * u, cy + dy * 15 * u, 10.5f * u)));
            sign.add(crescent);
            Area arc = new Area(ring);
            arc.intersect(new Area(circle(cx + dx * 15 * u, cy + dy * 15 * u, 9.3f * u)));
            sign.add(arc);
        }
        sign.subtract(new Area(circle(cx, cy, 3 * u)));
        return sign;
    }

    private static Ellipse2D circle(float cx, float cy, float r) {
        return new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
    }

    /** Yellow quarantine tape with black diagonal stripes, rotated around (cx, cy) and long enough to leave the frame. */
    private static void tape(Graphics2D g, float cx, float cy, double angleDeg) {
        Graphics2D t = (Graphics2D) g.create();
        t.translate(cx, cy);
        t.rotate(Math.toRadians(angleDeg));
        RoundRectangle2D band = new RoundRectangle2D.Float(-W, -12, W * 2, 24, 4, 4);
        t.setColor(new Color(0, 0, 0, 90));
        t.fill(new RoundRectangle2D.Float(-W, -9, W * 2, 24, 4, 4));
        t.setColor(new Color(0xF2C230));
        t.fill(band);
        t.clip(band);
        t.setColor(new Color(0x1A1A14));
        for (int x = -W - 24; x < W + 24; x += 22) {
            t.fill(new Polygon(new int[]{x, x + 10, x + 34, x + 24}, new int[]{12, 12, -12, -12}, 4));
        }
        t.dispose();
    }

    /** Red reticle: thin outer ring, ring, split cross and centre dot. */
    private static void crosshair(Graphics2D g, Point2D center) {
        float x = (float) center.getX(), y = (float) center.getY();
        g.setColor(new Color(255, 80, 64, 70));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Ellipse2D.Float(x - 58, y - 58, 116, 116));
        g.setColor(new Color(255, 80, 64, 230));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Float(x - 38, y - 38, 76, 76));
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i;
            g.draw(new Line2D.Double(x + Math.cos(a) * 16, y + Math.sin(a) * 16, x + Math.cos(a) * 52, y + Math.sin(a) * 52));
        }
        g.fill(new Ellipse2D.Float(x - 3, y - 3, 6, 6));
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
