package com.blockland.server.farterrain;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarTile;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A dimension's tiles on disk (rendering spec §22): {@code <folder>/L<level>/<tx>.<tz>.bin}, each the encoded tile with
 * the server's own notes (cell sources, the stamp it was built at). Written to a temporary file and moved into place, so a
 * crash never leaves half a tile.
 */
final class TileFiles {
    private static final int MAGIC = 0x424C4654; // "BLFT"
    private static final int FORMAT = 1;

    private final Path folder;

    TileFiles(Path folder) {
        this.folder = folder;
    }

    Path folder() {
        return folder;
    }

    private Path file(long key) {
        return folder.resolve("L" + FarTile.level(key)).resolve(FarTile.tx(key) + "." + FarTile.tz(key) + ".bin");
    }

    @Nullable
    TileEntry read(long key) {
        Path file = file(key);
        if (!Files.isRegularFile(file)) return null;
        try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(raw)) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) return null;
            long builtAt = in.readLong();
            int n = in.readInt();
            if (n <= 0 || n > 4 * 1024 * 1024) return null;
            byte[] bytes = in.readNBytes(n);
            int s = in.readInt();
            if (s != FarTile.CELLS) return null;
            byte[] source = in.readNBytes(s);
            if (bytes.length != n || source.length != s) return null;
            FarTile tile = FarTile.decode(bytes);
            if (tile.key() != key) return null;
            TileEntry entry = new TileEntry(tile, source, builtAt);
            entry.encoded = bytes;
            entry.written = entry.changes;
            return entry;
        } catch (IOException | RuntimeException e) {
            BlockLand.LOGGER.debug("[BlockLand] Far terrain tile {} unreadable, made again", file, e);
            return null;
        }
    }

    void write(long key, byte[] encoded, byte[] source, long builtAt) throws IOException {
        Path file = file(key);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(builtAt);
            out.writeInt(encoded.length);
            out.write(encoded);
            out.writeInt(source.length);
            out.write(source);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
