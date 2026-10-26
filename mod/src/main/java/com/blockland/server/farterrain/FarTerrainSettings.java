package com.blockland.server.farterrain;

/**
 * The server's far terrain settings (config section {@code farTerrain}, rendering spec §22), read once per tick.
 *
 * @param enabled       far terrain is offered at all
 * @param maxDistance   farthest a requested tile may lie from its player (blocks; one tile more is allowed)
 * @param threads       worker threads computing tiles (estimates, imports, disk)
 * @param bytesPerSecond answers sent to one player per second (bytes)
 * @param estimate      the world generator may estimate terrain where no chunk exists
 * @param importSaved   chunks saved in the region files are read where none was captured live
 * @param generateRadius real chunks are generated in the background this far round players (blocks, 0 = never)
 * @param generateConcurrency chunks generated at once
 * @param columnDistance farthest real far chunk a player may ask for (chunks from the player's chunk to the nearest chunk
 *                       of the unit asked for, spec §23); the background generation follows players this far
 * @param columnBytesPerSecond real far chunk answers sent to one player per second (bytes)
 */
public record FarTerrainSettings(boolean enabled, int maxDistance, int threads, int bytesPerSecond, boolean estimate,
                                 boolean importSaved, int generateRadius, int generateConcurrency, int columnDistance,
                                 int columnBytesPerSecond) {
    public static final FarTerrainSettings DEFAULTS = new FarTerrainSettings(true, 32768, 0, 393216, true, true, 1536, 8, 1024,
            8 << 20);
}
