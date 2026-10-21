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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/** Procedural 16:9 cover art for demo modes and groups (real covers are uploaded in the admin UI). */
public final class CoverArt {
    public static final int W = 512;
    public static final int H = 288;

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
