package com.blockland.craft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a player has customised one weapon in the 工匠 (craft) system: its skin, the option fitted in each slot
 * (slot id → option id, e.g. {@code optic → reddot}, {@code charm → charm_grass}) and the sprays placed on it. Shared by
 * guns and knives; what the slots and options are comes from the weapon's data ({@link Craftable}), the sprays from
 * {@link SprayCatalog}. JSON: {@code {"skin": ..., "slots": {...}, "sprays": [...]}}.
 */
public record Build(String skin, Map<String, String> slots, List<Spray> sprays) {
    /** Longest id accepted on the wire (weapon, skin, slot and option ids). */
    public static final int MAX_ID = 48;
    public static final int MAX_SLOTS = 16;
    /** Most sprays a build can carry on the wire (the data's per-weapon limit is at most this). */
    public static final int MAX_SPRAYS = 8;

    public static final Codec<Build> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("skin", "default").forGetter(Build::skin),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("slots", Map.of()).forGetter(Build::slots),
            Spray.CODEC.listOf().optionalFieldOf("sprays", List.of()).forGetter(Build::sprays)
    ).apply(instance, Build::new));

    private static final StreamCodec<ByteBuf, Map<String, String>> SLOTS_CODEC =
            ByteBufCodecs.map(LinkedHashMap::new, ByteBufCodecs.stringUtf8(MAX_ID), ByteBufCodecs.stringUtf8(MAX_ID), MAX_SLOTS);
    private static final StreamCodec<ByteBuf, List<Spray>> SPRAYS_CODEC =
            Spray.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SPRAYS));

    public static final StreamCodec<ByteBuf, Build> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_ID), Build::skin,
            SLOTS_CODEC, Build::slots,
            SPRAYS_CODEC, Build::sprays,
            Build::new);

    public Build {
        skin = skin == null || skin.isEmpty() ? "default" : skin;
        slots = slots == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        sprays = sprays == null ? List.of() : List.copyOf(sprays);
    }

    public Build(String skin, Map<String, String> slots) {
        this(skin, slots, List.of());
    }

    /** The option in {@code slot}, or {@code fallback} when the build says nothing about it. */
    public String option(String slot, String fallback) {
        return slots.getOrDefault(slot, fallback);
    }

    public Build with(String slot, String option) {
        Map<String, String> next = new LinkedHashMap<>(slots);
        next.put(slot, option);
        return new Build(skin, next, sprays);
    }

    public Build withSkin(String skinId) {
        return new Build(skinId, slots, sprays);
    }

    public Build withSprays(List<Spray> next) {
        return new Build(skin, slots, next);
    }

    /** The sprays with the one at {@code index} replaced (appended when {@code index} is past the end). */
    public Build withSpray(int index, Spray spray) {
        List<Spray> next = new ArrayList<>(sprays);
        if (index >= 0 && index < next.size()) next.set(index, spray);
        else next.add(spray);
        return withSprays(next);
    }

    public Build withoutSpray(int index) {
        if (index < 0 || index >= sprays.size()) return this;
        List<Spray> next = new ArrayList<>(sprays);
        next.remove(index);
        return withSprays(next);
    }

    public void write(FriendlyByteBuf buf) {
        STREAM_CODEC.encode(buf, this);
    }

    public static Build read(FriendlyByteBuf buf) {
        return STREAM_CODEC.decode(buf);
    }
}
