package com.blockland.client.font;

import com.blockland.BlockLand;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Rasterises glyph distance fields on a background thread with faces of its own, so a screen full of new characters
 * does not stall a frame; the render thread uploads the results. Requests carry the font generation: after a resource
 * reload the worker opens the new font files and drops what was queued for the old ones.
 */
final class GlyphRasterizer {
    /** One glyph to rasterise; {@code key} identifies it to the caller. */
    record Request(long key, int face, int glyph, int pixelSize, int spread, int generation) {
    }

    /** A rasterised glyph; {@code sdf} is null for glyphs without ink or when rasterising failed. */
    record Result(long key, int generation, @Nullable FontFace.Sdf sdf) {
    }

    /** The font files of one generation; {@code done}: keys rasterised for them (a glyph queued twice is done once). */
    private record Fonts(int generation, byte[][] files, Set<Long> done) {
    }

    private final LinkedBlockingDeque<Request> queue = new LinkedBlockingDeque<>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    @Nullable
    private volatile Fonts fonts;
    @Nullable
    private Thread thread;
    private boolean warned;

    /** The font files (one per face index) requests of {@code generation} refer to. */
    void setFonts(int generation, byte[][] files) {
        fonts = new Fonts(generation, files, ConcurrentHashMap.newKeySet());
        queue.removeIf(request -> request.generation != generation);
    }

    /** Queues a glyph; {@code urgent} ones (on screen now) go before the warm-up. */
    void submit(Request request, boolean urgent) {
        if (thread == null) {
            thread = new Thread(this::run, "BlockLand font rasteriser");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }
        if (urgent) queue.offerFirst(request);
        else queue.offerLast(request);
    }

    @Nullable
    Result poll() {
        return results.poll();
    }

    private void run() {
        FontFace[] faces = new FontFace[0];
        int open = -1;
        while (true) {
            Request request;
            try {
                request = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            Fonts current = fonts;
            if (current == null || request.generation != current.generation || !current.done.add(request.key)) continue;
            FontFace.Sdf sdf = null;
            try {
                if (open != current.generation) {
                    for (FontFace face : faces) if (face != null) face.close();
                    faces = new FontFace[current.files.length];
                    for (int i = 0; i < faces.length; i++) faces[i] = FontFace.open(current.files[i]);
                    open = current.generation;
                }
                sdf = faces[request.face].sdf(request.glyph, request.pixelSize, request.spread);
            } catch (RuntimeException e) {
                if (!warned) {
                    warned = true;
                    BlockLand.LOGGER.warn("[BlockLand] Cannot rasterise UI font glyphs", e);
                }
            }
            results.add(new Result(request.key, request.generation, sdf));
        }
    }
}
