package com.blockland.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Per-item gun state, stored as the {@code blockland:gun} data component: the gunsmith configuration and the
 * rounds in the magazine. The server is the only one that changes it; clients predict ammo locally.
 */
public record GunState(String skin, String magazine, String sight, String laser, int ammo) {
    public static final Codec<GunState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("skin", "default").forGetter(GunState::skin),
            Codec.STRING.optionalFieldOf("magazine", "").forGetter(GunState::magazine),
            Codec.STRING.optionalFieldOf("sight", "iron").forGetter(GunState::sight),
            Codec.STRING.optionalFieldOf("laser", "none").forGetter(GunState::laser),
            Codec.INT.optionalFieldOf("ammo", 0).forGetter(GunState::ammo)
    ).apply(instance, GunState::new));

    public static final StreamCodec<ByteBuf, GunState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GunState::skin,
            ByteBufCodecs.STRING_UTF8, GunState::magazine,
            ByteBufCodecs.STRING_UTF8, GunState::sight,
            ByteBufCodecs.STRING_UTF8, GunState::laser,
            ByteBufCodecs.VAR_INT, GunState::ammo,
            GunState::new);

    /** A freshly made gun: default attachments and a full magazine. */
    public static GunState fresh(GunData.Gun gun) {
        GunData.Magazine magazine = GunCatalog.get().magazine(gun.defaultMagazine());
        return new GunState("default", gun.defaultMagazine(), gun.defaultSight(), gun.defaultLaser(),
                magazine == null ? 0 : magazine.capacity);
    }

    public GunState withAmmo(int rounds) {
        return new GunState(skin, magazine, sight, laser, Math.max(0, rounds));
    }

    public GunState withLoadout(String skin, String magazine, String sight, String laser) {
        return new GunState(skin, magazine, sight, laser, ammo);
    }
}
