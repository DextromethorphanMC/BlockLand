package com.blockland.server.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;

/** A large world edit split into small steps so it never freezes the server tick. */
public interface WorldJob {

    /** The level the job edits, or null when it does not say (completion listeners are then not told). */
    @Nullable
    default ServerLevel level() {
        return null;
    }

    /** The box the job's edits lie in, or null when it does not say. */
    @Nullable
    default BoundingBox area() {
        return null;
    }

    /**
     * Performs up to {@code budget} block operations.
     *
     * @return number of operations actually used
     */
    int step(int budget);

    boolean isDone();
}
