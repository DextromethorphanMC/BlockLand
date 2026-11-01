package com.blockland.client.perf;

import java.util.Locale;

/**
 * The switchable performance work (rendering spec §20). Every entry keeps the picture and the game exactly as they
 * are — same pixels, same logic — and only does less work for them; the benchmark proves both (frame times with the
 * entry off and on, and frames compared pixel for pixel). Code that implements one asks {@link #on()} where it chooses
 * between the old path and the new one, so switching it back gives exactly the old behaviour, at any time.
 * <p>
 * The player's choice is {@code PerfSettings.optimizations} (the 性能 tab, all on by default); the benchmark overrides
 * it for its passes. Labels and notes are {@code blockland.settings.perf.opt.<id>} and {@code ….<id>.note}.
 */
public enum Optimization {
    // ---- vanilla Minecraft (mixins under com.blockland.mixin, one config per area)
    /** GL state Blaze3D sets again on every draw although it already holds (texture levels, samplers, buffers, viewport). */
    GL_STATE(Group.VANILLA, false),
    /** Immediate-mode vertices (entities, particles, block entities) go into a ring buffer instead of one buffer rewritten. */
    IMMEDIATE_UPLOADS(Group.VANILLA, false),
    /** The terrain's per-section draw loop without garbage or repeated binds. */
    TERRAIN_DRAWS(Group.VANILLA, false),
    /** Chunk section meshing: the same vertices built with less work (quads, light and occlusion caches). */
    SECTION_MESHING(Group.VANILLA, true),
    /** The block snapshot a section rebuild takes copies only what the section can read. */
    SECTION_COPIES(Group.VANILLA, false),
    /** Biome colour blending reuses each sampled biome's colour. */
    BIOME_TINTS(Group.VANILLA, true),
    /** Blocks whose every face is culled by full neighbours are not tesselated at all (same mesh, less work). */
    ENCLOSED_BLOCKS(Group.VANILLA, true),
    /** Enclosed blocks whose model then draws nothing are left out before their model is read (variant, layers, data). */
    ENCLOSED_MODELS(Group.VANILLA, true),
    /** Fluid blocks none of whose faces would be drawn (inside lakes and seas) are not tesselated (same mesh, less work). */
    HIDDEN_FLUIDS(Group.VANILLA, true),
    /** Solid and cutout quads grouped by facing; groups facing away from the camera are not drawn (the GPU culls them). */
    FACE_GROUPS(Group.VANILLA, true),
    /** The section compile queue drops cancelled tasks in one sweep. */
    COMPILE_QUEUE(Group.VANILLA, false),
    /** Particles: dead ones removed in one sweep, no garbage per particle, light looked up once per block and frame. */
    PARTICLES(Group.VANILLA, false),

    // ---- BlockLand's render pipeline
    /** The sun's shadow map: terrain casters gathered without garbage and submitted as batches per cascade. */
    SHADOW_BATCHING(Group.PIPELINE, false),
    /** A due shadow cascade's tile is cleared by the GPU's own clear, not by a full-tile depth quad. */
    SHADOW_TILE_CLEAR(Group.PIPELINE, false),
    /** Entity shadow capture: geometry the shadow map throws away is never transformed, the rest only where read. */
    SHADOW_ENTITY_CAPTURE(Group.PIPELINE, false),
    /**
     * Entity shadow capture: a BlockLand model's triangles drawn from the GPU buffers they already sit in (placed by the
     * same float operations as on the CPU) instead of being rebuilt as vertices and uploaded every frame.
     */
    SHADOW_GPU_CASTERS(Group.PIPELINE, false),
    /** Shadow casters culled by their box in the sun's view instead of a bounding sphere (still conservative). */
    SHADOW_CULLING(Group.PIPELINE, false),
    /** Shadow casters drawn nearest the sun first, so the GPU's early depth test rejects what lies beneath. */
    SHADOW_SORTING(Group.PIPELINE, false),
    /** A shadow cascade's terrain is kept while nothing it is drawn from changed; only the entities are drawn again. */
    SHADOW_REUSE(Group.PIPELINE, false),
    /** Motion blur's tile maximum in two separable passes instead of one serial loop per tile. */
    MOTION_BLUR_TILES(Group.PIPELINE, false),
    /** Post-processing constants written once per change, each pass with its own uniform block. */
    POST_UNIFORMS(Group.PIPELINE, false),
    /** Post passes skip texture reads and clears whose result is never used. */
    POST_DEAD_WORK(Group.PIPELINE, false),
    /** First-person draws that share their state are submitted as one batch. */
    VIEWMODEL_BATCHING(Group.PIPELINE, false),
    /** Bullet debris, sparks and stains: only live entries are visited, stain clipping is kept while unchanged. */
    IMPACT_POOLS(Group.PIPELINE, false),
    /** Per-frame loops over nearby entities without garbage (motion, lights, poses). */
    FRAME_GARBAGE(Group.PIPELINE, false),
    /** Real far chunks: blocks no line of sight from outside can reach (sealed caves, buried blocks) are not meshed. */
    FAR_HIDDEN_GEOMETRY(Group.PIPELINE, false);

    /** Where an entry works: vanilla Minecraft (through hooks) or BlockLand's own pipeline. */
    public enum Group { VANILLA, PIPELINE }

    public final Group group;
    /** Switching it changes how chunk sections are meshed: the sections are rebuilt so every one uses the new choice. */
    public final boolean rebuildsSections;
    public final String id;
    private volatile boolean on = true;

    Optimization(Group group, boolean rebuildsSections) {
        this.group = group;
        this.rebuildsSections = rebuildsSections;
        this.id = name().toLowerCase(Locale.ROOT);
    }

    /** Whether the optimized path runs now (render thread, worker threads alike: one volatile read). */
    public boolean on() {
        return on;
    }

    /** Perf module only (settings, benchmark passes). */
    void set(boolean value) {
        on = value;
    }

    public String labelKey() {
        return "blockland.settings.perf.opt." + id;
    }

    public String noteKey() {
        return labelKey() + ".note";
    }

    public static Optimization byId(String id) {
        for (Optimization o : values()) if (o.id.equals(id)) return o;
        return null;
    }
}
