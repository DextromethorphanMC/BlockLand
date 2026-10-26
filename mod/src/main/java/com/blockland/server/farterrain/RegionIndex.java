package com.blockland.server.farterrain;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;

/**
 * Which chunks a dimension's region files hold (rendering spec §22: saved chunks are imported only where they exist): the
 * offset table at the head of each {@code r.<x>.<z>.mca}, read on a worker and read again when the file's modification time
 * changed (new chunks were written), at most every half minute. Only the table is read; the chunks themselves come through
 * the server's own chunk storage. With {@code stamps} also the table of each chunk's last write (seconds), which says
 * whether a real far chunk unit kept on disk is still what the region holds (spec §23); then at most {@code maxHeaders}
 * files' tables are kept, the least recently used going first. Thread-safe.
 */
final class RegionIndex {
    private static final long RECHECK_MS = 30_000;
    /** {@link #existsNow}: a table that says a chunk is missing is looked at again after this long. */
    private static final long RECHECK_MISSING_MS = 1_000;

    private final Path folder;
    private final boolean stamps;
    private final int maxHeaders;
    private final Long2ObjectLinkedOpenHashMap<Header> headers = new Long2ObjectLinkedOpenHashMap<>();

    /** {@code times}: each chunk's last write (null unless stamps are kept). */
    private record Header(long mtime, long checked, BitSet present, @Nullable int[] times) {
    }

    RegionIndex(Path folder) {
        this(folder, false, Integer.MAX_VALUE);
    }

    RegionIndex(Path folder, boolean stamps, int maxHeaders) {
        this.folder = folder;
        this.stamps = stamps;
        this.maxHeaders = maxHeaders;
    }

    /** Whether the chunk is in its region file (worker threads: may read the file's table). */
    boolean exists(int cx, int cz) {
        return header(cx, cz).present.get((cx & 31) + (cz & 31) * 32);
    }

    /**
     * Whether the chunk is in its region file now: a table that says it is not is checked against the file once it is
     * a second old (a chunk saved a moment ago is never taken for one not there). Worker threads.
     */
    boolean existsNow(int cx, int cz) {
        int i = (cx & 31) + (cz & 31) * 32;
        Header header = header(cx, cz);
        if (header.present.get(i) || System.currentTimeMillis() - header.checked <= RECHECK_MISSING_MS) return header.present.get(i);
        return header(cx, cz, true).present.get(i);
    }

    /** The chunk's stamp (its last write, with a bit saying it is there; 0 when it is not), worker threads. */
    long stamp(int cx, int cz) {
        Header header = header(cx, cz);
        int i = (cx & 31) + (cz & 31) * 32;
        if (!header.present.get(i)) return 0;
        return (header.times == null ? 0 : header.times[i] & 0xFFFFFFFFL) | 1L << 32;
    }

    private Header header(int cx, int cz) {
        return header(cx, cz, false);
    }

    private Header header(int cx, int cz, boolean recheck) {
        int rx = cx >> 5, rz = cz >> 5;
        long key = Chunks.key(rx, rz);
        long now = System.currentTimeMillis();
        Header header;
        synchronized (this) {
            header = headers.getAndMoveToLast(key);
        }
        if (header == null || recheck || now - header.checked > RECHECK_MS) {
            Path file = folder.resolve("r." + rx + "." + rz + ".mca");
            long mtime = mtime(file);
            if (header == null || header.mtime != mtime) {
                header = read(file, mtime, now, stamps);
            } else {
                header = new Header(mtime, now, header.present, header.times);
            }
            synchronized (this) {
                headers.putAndMoveToLast(key, header);
                while (headers.size() > maxHeaders) headers.removeFirst();
            }
        }
        return header;
    }

    private static long mtime(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static Header read(Path file, long mtime, long now, boolean stamps) {
        BitSet present = new BitSet(1024);
        int[] times = stamps ? new int[1024] : null;
        if (!Files.isRegularFile(file)) return new Header(mtime, now, present, times);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            // the offsets, then (for stamps) the timestamps
            ByteBuffer table = ByteBuffer.allocate(stamps ? 8192 : 4096);
            while (table.hasRemaining() && channel.read(table) >= 0) {
                // keep reading until the table is in
            }
            table.flip();
            for (int i = 0; i < 1024 && table.remaining() >= 4; i++) {
                if (table.getInt() != 0) present.set(i);
            }
            for (int i = 0; times != null && i < 1024 && table.remaining() >= 4; i++) times[i] = table.getInt();
        } catch (IOException e) {
            // unreadable now: nothing imported from it until it is read again
        }
        return new Header(mtime, now, present, times);
    }
}
