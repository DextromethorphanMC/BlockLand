package com.blockland.client.font;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FT_Vector;
import org.lwjgl.util.freetype.FreeType;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * One TrueType / OpenType face opened with FreeType: character lookup, unscaled metrics, pair kerning and glyphs
 * rasterised as signed distance fields. Not thread-safe: every thread that uses a font opens its own face, on its own
 * FreeType library (the SDF rasteriser keeps per-library state). Only depends on LWJGL, so it also runs outside the game.
 */
public final class FontFace implements AutoCloseable {
    /** Unhinted outlines: glyphs scale exactly with the size, as a distance field needs. */
    private static final int LOAD_FLAGS = FreeType.FT_LOAD_NO_HINTING | FreeType.FT_LOAD_NO_BITMAP;

    private final long library;
    private final ByteBuffer data;
    private final FT_Face face;
    public final int unitsPerEm;
    private final boolean kerning;
    private int pixelSize = -1, spread = -1;

    /** A glyph as a distance field: 128 on the outline, higher inside, {@code 128 / spread} per pixel. */
    public record Sdf(int width, int height, int left, int top, byte[] pixels) {
    }

    private FontFace(long library, ByteBuffer data, FT_Face face) {
        this.library = library;
        this.data = data;
        this.face = face;
        this.unitsPerEm = Math.max(1, face.units_per_EM());
        this.kerning = (face.face_flags() & FreeType.FT_FACE_FLAG_KERNING) != 0;
    }

    /** Opens a face from font file bytes on a FreeType library of its own (closed with the face). */
    public static FontFace open(byte[] bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(FreeType.FT_Init_FreeType(pointer), "initialising FreeType");
            long library = pointer.get(0);
            ByteBuffer data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            int error = FreeType.FT_New_Memory_Face(library, data, 0, pointer);
            if (error != 0) {
                MemoryUtil.memFree(data);
                FreeType.FT_Done_FreeType(library);
                check(error, "opening the font");
            }
            return new FontFace(library, data, FT_Face.create(pointer.get(0)));
        }
    }

    /** Glyph index of a character, 0 when the face does not have it. */
    public int glyph(int codepoint) {
        return (int) FreeType.FT_Get_Char_Index(face, codepoint);
    }

    /** Horizontal advance in font units. */
    public int advance(int glyph) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var advance = stack.mallocCLong(1);
            if (FreeType.FT_Get_Advance(face, glyph, FreeType.FT_LOAD_NO_SCALE | FreeType.FT_LOAD_NO_HINTING, advance) != 0) return 0;
            return (int) advance.get(0);
        }
    }

    /** Pair kerning in font units (0 without a {@code kern} table). */
    public int kerning(int left, int right) {
        if (!kerning || left == 0 || right == 0) return 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FT_Vector vector = FT_Vector.malloc(stack);
            if (FreeType.FT_Get_Kerning(face, left, right, FreeType.FT_KERNING_UNSCALED, vector) != 0) return 0;
            return (int) vector.x();
        }
    }

    /** Ascender and descender of the face in font units (OS/2 typographic values when present). */
    public int ascender() {
        return face.ascender();
    }

    public int descender() {
        return face.descender();
    }

    /**
     * Rasterises a glyph as a signed distance field.
     *
     * @param pixelSize em size in pixels
     * @param spread    distance in pixels the field reaches out from the outline (and in)
     * @return null for glyphs without ink (spaces)
     */
    @Nullable
    public Sdf sdf(int glyph, int pixelSize, int spread) {
        if (this.pixelSize != pixelSize) {
            check(FreeType.FT_Set_Pixel_Sizes(face, 0, pixelSize), "sizing the font");
            this.pixelSize = pixelSize;
        }
        if (this.spread != spread) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(FreeType.FT_Property_Set(library, "sdf", "spread", stack.malloc(4).putInt(0, spread)), "setting the field spread");
            }
            this.spread = spread;
        }
        check(FreeType.FT_Load_Glyph(face, glyph, LOAD_FLAGS), "loading a glyph");
        FT_GlyphSlot slot = face.glyph();
        if (slot == null || slot.outline().n_points() == 0) return null;
        check(FreeType.FT_Render_Glyph(slot, FreeType.FT_RENDER_MODE_SDF), "rendering a glyph");
        FT_Bitmap bitmap = slot.bitmap();
        int width = bitmap.width(), height = bitmap.rows(), pitch = bitmap.pitch();
        if (width <= 0 || height <= 0) return null;
        ByteBuffer source = bitmap.buffer(Math.abs(pitch) * height);
        byte[] pixels = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int row = pitch >= 0 ? y * pitch : (height - 1 - y) * -pitch;
            source.get(row, pixels, y * width, width);
        }
        return new Sdf(width, height, slot.bitmap_left(), slot.bitmap_top(), pixels);
    }

    @Override
    public void close() {
        FreeType.FT_Done_Face(face);
        FreeType.FT_Done_FreeType(library);
        MemoryUtil.memFree(data);
    }

    private static void check(int error, String what) {
        if (error != 0) {
            String message = FreeType.FT_Error_String(error);
            throw new IllegalStateException("FreeType error while " + what + ": " + (message != null ? message : "0x" + Integer.toHexString(error)));
        }
    }
}
