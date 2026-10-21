package com.blockland.backend.seed;

/**
 * Demo content contributed by a game type. Each seed runs once ever (tracked in {@code data/seeds.json}),
 * so content an admin deleted is not recreated.
 */
public interface DemoSeed {

    /** Stable id, recorded once applied. */
    String id();

    void apply();
}
