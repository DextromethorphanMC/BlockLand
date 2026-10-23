package com.blockland.craft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One spray (喷漆) a player placed on a weapon (asset spec §29): which spray, on which side, where in that side's view of
 * the weapon and how big. Only numbers the server can check without the model: the client projects it onto the
 * weapon's surface. JSON: {@code {"id": ..., "side": "left", "u": 0.4, "v": 0.5, "size": 0.03, "rotation": 0}}.
 *
 * @param id       spray id in {@code data/blockland/craft/sprays.json}
 * @param side     {@link #LEFT} (seen from the weapon's left, −X) or {@link #RIGHT}
 * @param u        0..1 across that side's view, left to right as the viewer sees it (left side: muzzle → rear)
 * @param v        0..1 down that side's view (top → bottom)
 * @param size     edge of the square spray in metres
 * @param rotation degrees, counter-clockwise as the viewer sees it (−180..180)
 */
public record Spray(String id, String side, float u, float v, float size, float rotation) {
    public static final String LEFT = "left";
    public static final String RIGHT = "right";

    public static final Codec<Spray> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Spray::id),
            Codec.STRING.optionalFieldOf("side", LEFT).forGetter(Spray::side),
            Codec.FLOAT.optionalFieldOf("u", 0.5f).forGetter(Spray::u),
            Codec.FLOAT.optionalFieldOf("v", 0.5f).forGetter(Spray::v),
            Codec.FLOAT.optionalFieldOf("size", 0.03f).forGetter(Spray::size),
            Codec.FLOAT.optionalFieldOf("rotation", 0f).forGetter(Spray::rotation)
    ).apply(instance, Spray::new));

    public static final StreamCodec<ByteBuf, Spray> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(Build.MAX_ID), Spray::id,
            ByteBufCodecs.stringUtf8(8), Spray::side,
            ByteBufCodecs.FLOAT, Spray::u,
            ByteBufCodecs.FLOAT, Spray::v,
            ByteBufCodecs.FLOAT, Spray::size,
            ByteBufCodecs.FLOAT, Spray::rotation,
            Spray::new);

    public Spray {
        id = id == null ? "" : id;
        side = RIGHT.equals(side) ? RIGHT : LEFT;
    }

    public boolean onRight() {
        return RIGHT.equals(side);
    }

    public Spray moved(float nextU, float nextV) {
        return new Spray(id, side, nextU, nextV, size, rotation);
    }

    public Spray resized(float nextSize) {
        return new Spray(id, side, u, v, nextSize, rotation);
    }

    public Spray rotated(float degrees) {
        return new Spray(id, side, u, v, size, degrees);
    }

    public Spray onSide(String nextSide) {
        return new Spray(id, nextSide, u, v, size, rotation);
    }
}
