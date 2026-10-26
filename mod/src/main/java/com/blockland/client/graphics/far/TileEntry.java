package com.blockland.client.graphics.far;

import com.blockland.farterrain.FarTile;

import javax.annotation.Nullable;

/**
 * What the client knows of one far tile (rendering spec §22): its data (from the disk cache or the server), whether the
 * server answered it this session, the request in flight and the mesh. Render thread only.
 */
final class TileEntry {
    final long key;
    final int level, tx, tz;

    /** The tile's data, null until known (or when it has no terrain). */
    @Nullable
    FarTile tile;
    /** Version of {@link #tile} (0 = none held). */
    long version;
    /** The server said there is nothing here (no terrain) or that we may not have it. */
    boolean empty, denied;
    /** The server answered this session (our copy is current). */
    boolean answered;
    /** The disk cache was looked at / is being read. */
    boolean diskChecked, diskLoading;

    /** Waiting to be requested; time the request went out (0 = none in flight). */
    boolean queued;
    long sentAt;

    @Nullable
    FarMesh mesh;
    /** The data or a neighbour changed since the mesh was built. */
    boolean meshDirty;
    /** Stamp of the mesh job in flight (0 = none); a result with another stamp is outdated. */
    int meshJob;
    /** Which same-level neighbours the mesh was built with (bits: north, south, west, east). */
    int neighbours;

    /** Last frame it was wanted, and how urgent (smaller first). */
    long lastWanted;
    double priority;

    TileEntry(long key) {
        this.key = key;
        this.level = FarTile.level(key);
        this.tx = FarTile.tx(key);
        this.tz = FarTile.tz(key);
    }

    /**
     * Nothing more will change what this tile shows for now: its mesh is up (possibly empty), or there is nothing to draw.
     * A parent tile hands over to its children only when all four are complete.
     */
    boolean complete() {
        return empty || denied || mesh != null;
    }

    /** Has something to draw. */
    boolean drawable() {
        return mesh != null && mesh.quads > 0 && !empty && !denied;
    }

    void dropMesh() {
        if (mesh != null) mesh.close();
        mesh = null;
        meshJob = 0;
        meshDirty = tile != null;
    }
}
