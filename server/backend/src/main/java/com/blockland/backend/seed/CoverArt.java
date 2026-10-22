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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
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
