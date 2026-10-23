package com.blockland.gun;

import com.blockland.craft.Build;
import com.blockland.craft.CraftData;
import com.blockland.craft.Spray;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-item gun state, stored as the {@code blockland:gun} data component: the fitted 工匠 build (skin, magazine and
 * attachments per slot) and the rounds in the magazine. The server is the only one that changes it; clients predict
 * ammo locally.
 */
public record GunState(Build build, int ammo) {
    /**
     * {@code {"skin": ..., "slots": {...}, "sprays": [...], "ammo": n}}. Items saved before the craft system kept the magazine, sight and
     * laser in fields of their own; those still load (laser ids gained a {@code laser_} prefix).
     */
    public static final Codec<GunState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("skin", "default").forGetter(s -> s.build.skin()),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("slots", Map.of()).forGetter(s -> s.build.slots()),
            Spray.CODEC.listOf().optionalFieldOf("sprays", List.of()).forGetter(s -> s.build.sprays()),
            Codec.STRING.optionalFieldOf("magazine", "").forGetter(s -> ""),
            Codec.STRING.optionalFieldOf("sight", "").forGetter(s -> ""),
            Codec.STRING.optionalFieldOf("laser", "").forGetter(s -> ""),
            Codec.INT.optionalFieldOf("ammo", 0).forGetter(GunState::ammo)
    ).apply(instance, GunState::decode));

    public static final StreamCodec<ByteBuf, GunState> STREAM_CODEC = StreamCodec.composite(
            Build.STREAM_CODEC, GunState::build,
            ByteBufCodecs.VAR_INT, GunState::ammo,
            GunState::new);

    private static GunState decode(String skin, Map<String, String> slots, List<Spray> sprays, String magazine, String sight,
                                   String laser, int ammo) {
        Map<String, String> merged = new LinkedHashMap<>(slots);
        if (!magazine.isEmpty()) merged.putIfAbsent(GunData.Gun.MAGAZINE_SLOT, magazine);
        if (!sight.isEmpty()) merged.putIfAbsent("optic", sight);
        if (!laser.isEmpty()) merged.putIfAbsent("laser", laser.equals(CraftData.NONE) || laser.startsWith("laser_") ? laser : "laser_" + laser);
        return new GunState(new Build(skin, merged, sprays), ammo);
    }

    /** A freshly made gun: default build and a full magazine. */
    public static GunState fresh(GunData.Gun gun) {
        GunData.Magazine magazine = GunCatalog.get().magazine(gun.defaultMagazine());
        return new GunState(gun.defaultBuild(), magazine == null ? 0 : magazine.capacity);
    }

    public String skin() {
        return build.skin();
    }

    /** The fitted magazine id ("" when the build names none; {@link GunStats} falls back to the default). */
    public String magazine() {
        return build.option(GunData.Gun.MAGAZINE_SLOT, "");
    }

    public GunState withAmmo(int rounds) {
        return new GunState(build, Math.max(0, rounds));
    }

    public GunState withBuild(Build next) {
        return new GunState(next, ammo);
    }
}
