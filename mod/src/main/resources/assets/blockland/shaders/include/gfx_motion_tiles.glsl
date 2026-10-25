// BlockLand motion blur's tile maximum in two separable passes (client/graphics/post/MotionBlur, MOTION_BLUR_TILES):
// gfx_mb_tile_rows finds the longest blur of each tile row, gfx_mb_tile_columns the longest of those rows. Both rank a
// blur by this one function and keep the first strictly longer one in their scan, so the winner is the texel the one-pass
// gfx_mb_tile picks: the first of the longest in row-major order (the first row holding one, its first texel there).
// The rank is exact either way: velocities are half floats (11-bit significands), so each square is exact in a float and
// the sum is rounded once, with or without a fused multiply-add.
float gfx_mb_rank(vec2 v) {
    return dot(v, v);
}
