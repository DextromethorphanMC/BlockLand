package com.blockland.armor;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * What a plate carrier holds (the {@code blockland:vest} item component, asset spec §50): the durability left in each
 * inserted plate, the first inserted first; the last one is the outermost and is worn down first.
 */
public record VestState(List<Float> plates) {
    public static final VestState EMPTY = new VestState(List.of());
    public static final Codec<VestState> CODEC = Codec.FLOAT.listOf().xmap(VestState::new, VestState::plates);
    public static final StreamCodec<ByteBuf, VestState> STREAM_CODEC =
            ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()).map(VestState::new, VestState::plates);

    public VestState {
        plates = List.copyOf(plates);
    }

    public int count() {
        return plates.size();
    }

    /** Durability left in all plates together. */
    public float total() {
        float sum = 0;
        for (float p : plates) sum += p;
        return sum;
    }

    /** The most worn plate with at most {@code durability} left, or -1. */
    public int mostWorn(float durability) {
        int worst = -1;
        for (int i = 0; i < plates.size(); i++) {
            if (plates.get(i) <= durability && (worst < 0 || plates.get(i) < plates.get(worst))) worst = i;
        }
        return worst;
    }

    /** With the plate at {@code index} swapped for one of {@code durability}. */
    public VestState replaced(int index, float durability) {
        List<Float> next = new ArrayList<>(plates);
        next.set(index, durability);
        return new VestState(next);
    }

    /** With one more plate of {@code durability} on the outside. */
    public VestState inserted(float durability) {
        List<Float> next = new ArrayList<>(plates);
        next.add(durability);
        return new VestState(next);
    }
}
