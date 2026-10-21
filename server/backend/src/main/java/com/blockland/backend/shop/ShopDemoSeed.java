package com.blockland.backend.shop;

import com.blockland.backend.seed.DemoSeed;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** The first product: 优先匹配排队 (grants {@code PRIORITY_QUEUE} for 30 days), with a generated icon. */
@Component
@Order(30)
public class ShopDemoSeed implements DemoSeed {
    public static final String PRIORITY_QUEUE = "PRIORITY_QUEUE";
    private static final String PRODUCT_ID = "priority_queue";

    private final ShopService shop;

    public ShopDemoSeed(ShopService shop) {
        this.shop = shop;
    }

    @Override
    public String id() {
        return "shop-priority-queue";
    }

    @Override
    public void apply() {
        if (shop.exists(PRODUCT_ID)) return;
        shop.create(new Product(PRODUCT_ID, "优先匹配排队", "匹配时自动排在队列最前面，更快进入对局。购买后立即生效，有效期 30 天，可叠加续期。",
                "特权", 990, "CNY", PRIORITY_QUEUE, 30, true, 0, null, null));
        shop.setImage(PRODUCT_ID, icon());
    }

    /** A gold lightning bolt on a dark rounded tile. */
    private static byte[] icon() {
        int size = 128;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x2A3A5E), size, size, new Color(0x0E1424)));
        g.fill(new RoundRectangle2D.Float(4, 4, size - 8, size - 8, 28, 28));
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0xF5A623));
        g.draw(new RoundRectangle2D.Float(5.5f, 5.5f, size - 11, size - 11, 26, 26));
        Polygon bolt = new Polygon(new int[]{72, 36, 60, 50, 92, 66}, new int[]{18, 72, 72, 112, 54, 54}, 6);
        g.setPaint(new GradientPaint(40, 18, new Color(0xFFE08A), 90, 112, new Color(0xF5A623)));
        g.fill(bolt);
        g.setColor(new Color(0x8A5A00));
        g.setStroke(new BasicStroke(2f));
        g.draw(bolt);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
