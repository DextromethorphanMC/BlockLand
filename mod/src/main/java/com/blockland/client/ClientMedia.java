package com.blockland.client;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.MediaPackets;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Textures for images relayed by the server (shop products, mode and group covers).
 * {@link #get} returns null until the image arrived and requests it on first use. Client thread only.
 */
public final class ClientMedia {
    /** A loaded image and its pixel size. */
    public record Image(ResourceLocation texture, int width, int height) {
    }

    private static final Map<String, Image> images = new HashMap<>();
    private static final Set<String> requested = new HashSet<>();

    private ClientMedia() {
    }

    @Nullable
    public static Image get(String namespace, String id, String version) {
        if (version == null || version.isEmpty()) return null;
        String key = key(namespace, id, version);
        Image image = images.get(key);
        if (image == null && requested.add(key)) {
            BLNetwork.sendToServer(new MediaPackets.C2SRequest(namespace, id, version));
        }
        return image;
    }

    static void receive(MediaPackets.S2CImage packet) {
        String key = key(packet.namespace(), packet.id(), packet.version());
        try {
            NativeImage image = NativeImage.read(packet.png());
            ResourceLocation location = BlockLand.id("media/" + packet.namespace() + "/" + packet.id().toLowerCase(Locale.ROOT)
                    + "_" + packet.version().toLowerCase(Locale.ROOT));
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(location::toString, image));
            images.put(key, new Image(location, image.getWidth(), image.getHeight()));
            releaseOlderVersions(packet.namespace(), packet.id(), key);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not decode image {}", key, e);
        }
    }

    /** Forgets failed requests so they are retried after reconnecting. */
    static void resetRequests() {
        requested.removeIf(key -> !images.containsKey(key));
    }

    private static void releaseOlderVersions(String namespace, String id, String keep) {
        String prefix = namespace + "/" + id + "@";
        images.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix) || entry.getKey().equals(keep)) return false;
            Minecraft.getInstance().getTextureManager().release(entry.getValue().texture());
            return true;
        });
    }

    private static String key(String namespace, String id, String version) {
        return namespace + "/" + id + "@" + version;
    }
}
