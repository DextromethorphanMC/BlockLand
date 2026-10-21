package com.blockland.server.world;

/** A large world edit split into small steps so it never freezes the server tick. */
public interface WorldJob {

    /**
     * Performs up to {@code budget} block operations.
     *
     * @return number of operations actually used
     */
    int step(int budget);

    boolean isDone();
}
