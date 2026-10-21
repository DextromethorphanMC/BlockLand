package com.blockland.backend.media;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.config.BlockLandProperties;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Images owned by other modules (shop products, mode covers, group covers), stored under
 * {@code data/media/<namespace>/<id>.png}. Uploads are converted to PNG and scaled down so game
 * clients can display them; the returned version (content hash) lets clients cache aggressively.
 */
@Service
public class MediaService {
    public static final String SHOP = "shop";
    public static final String MODE = "mode";
    public static final String GROUP = "group";
    private static final Set<String> NAMESPACES = Set.of(SHOP, MODE, GROUP);
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_\\-]{0,63}$");

    private final Path root;

    public MediaService(BlockLandProperties properties) {
        this.root = properties.dataPath().resolve("media");
    }

    /**
     * @param maxWidth  images wider than this are scaled down (aspect ratio kept)
     * @param maxHeight images taller than this are scaled down
     * @return version of the stored image
     */
    public String store(String namespace, String id, byte[] data, int maxWidth, int maxHeight) {
        Path path = path(namespace, id);
        byte[] png = toPng(data, maxWidth, maxHeight);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sha256(png).substring(0, 12);
    }

    public Path file(String namespace, String id) {
        Path path = path(namespace, id);
        if (!Files.isRegularFile(path)) throw ApiException.notFound("图片", namespace + "/" + id);
        return path;
    }

    public void delete(String namespace, String id) {
        try {
            Files.deleteIfExists(path(namespace, id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String namespace, String id) {
        if (!NAMESPACES.contains(namespace) || id == null || !ID.matcher(id).matches()) {
            throw ApiException.notFound("图片", namespace + "/" + id);
        }
        return root.resolve(namespace).resolve(id + ".png");
    }

    private static byte[] toPng(byte[] data, int maxWidth, int maxHeight) {
        try {
            BufferedImage source = data == null ? null : ImageIO.read(new ByteArrayInputStream(data));
            if (source == null) throw ApiException.badRequest("无法识别的图片格式（支持 PNG / JPG / GIF / BMP）");
            double scale = Math.min(1.0, Math.min((double) maxWidth / source.getWidth(), (double) maxHeight / source.getHeight()));
            int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = target.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(target, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw ApiException.badRequest("图片处理失败: " + e.getMessage());
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
