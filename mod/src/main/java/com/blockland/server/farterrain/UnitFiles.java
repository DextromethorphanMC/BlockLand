package com.blockland.server.farterrain;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarColumn;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A dimension's real far chunk units of scale 1 and coarser on disk (rendering spec §23; chunks themselves are read from
 * the region files): {@code <folder>/<scale>/<x >> 5>.<z >> 5>/<x>.<z>.bin}, each the encoded unit (or none: nothing of it
 * exists) with the stamps of the four children it was built from ({@link UnitMath#stamp}). Written to a temporary file
 * and moved into place, so a crash never leaves half a unit. Worker threads.
 */
final class UnitFiles {
    private static final int MAGIC = 0x424C4655; // "BLFU"
    private static final int FORMAT = 1;

    /** A unit read back: what it was built from and what it is. */
    record Stored(long[] stamps, ColumnService.Encoded encoded) {
    }

    private final Path folder;

    UnitFiles(Path folder) {
        this.folder = folder;
    }

    private Path file(long key) {
        int x = FarColumn.x(key), z = FarColumn.z(key);
        return folder.resolve(Integer.toString(FarColumn.scale(key))).resolve((x >> 5) + "." + (z >> 5)).resolve(x + "." + z + ".bin");
    }

    @Nullable
    Stored read(long key) {
        Path file = file(key);
        if (!Files.isRegularFile(file)) return null;
        try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readLong() != key) return null;
            long[] stamps = new long[4];
            for (int i = 0; i < 4; i++) stamps[i] = in.readLong();
            long version = in.readLong();
            int absent = in.readInt();
            int n = in.readInt();
            byte[] bytes = null;
            if (n >= 0) {
                if (n > 8 << 20) return null;
                bytes = in.readNBytes(n);
                if (bytes.length != n) return null;
            }
            return new Stored(stamps, new ColumnService.Encoded(version, bytes, absent));
        } catch (IOException | RuntimeException e) {
            BlockLand.LOGGER.debug("[BlockLand] Far chunk unit {} unreadable, built again", file, e);
            return null;
        }
    }

    void write(long key, long[] stamps, ColumnService.Encoded encoded) throws IOException {
        Path file = file(key);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(key);
            for (int i = 0; i < 4; i++) out.writeLong(stamps[i]);
            out.writeLong(encoded.version());
            out.writeInt(encoded.absent());
            if (encoded.bytes() == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(encoded.bytes().length);
                out.write(encoded.bytes());
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
