package com.blockland.client.perf.terrain;

import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * {@code HIDDEN_FLUIDS}: whether {@code LiquidBlockRenderer.tesselate} would draw nothing for a fluid block. It decides
 * six faces before it draws any — the top while the fluid above is another, the bottom and the four sides by
 * {@code LiquidBlockRenderer.shouldRenderFace}, the bottom also only while the block below does not cover it — and draws
 * nothing when none holds. But before that it looks up the fluid's three sprites and blends its biome tint, for every
 * fluid block, although the inside of a lake or the sea has no face to draw at all.
 * <p>
 * This asks the same six questions first (vanilla's own {@code shouldRenderFace}, the same neighbours) and answers
 * "hidden" only when vanilla's would all be no. Where vanilla needs more to decide — a bottom over a block whose top is
 * only partly full — it answers "not hidden" and vanilla's tesselation decides as before.
 */
final class HiddenFluids {
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private HiddenFluids() {
    }

    /** {@code fluid} is the fluid of the block at {@code pos}; {@code at} is scratch. */
    static boolean hidden(BlockAndTintGetter level, BlockPos pos, FluidState fluid, BlockPos.MutableBlockPos at) {
        Fluid type = fluid.getType();
        // the top is drawn unless the same fluid lies above (isNeighborSameFluid)
        if (!level.getBlockState(at.setWithOffset(pos, Direction.UP)).getFluidState().getType().isSame(type)) return false;
        BlockState state = level.getBlockState(pos);
        BlockState below = level.getBlockState(at.setWithOffset(pos, Direction.DOWN));
        // the bottom: isFaceOccludedByNeighbor(DOWN, …) holds for certain only against a full top
        if (LiquidBlockRenderer.shouldRenderFace(fluid, state, Direction.DOWN, below.getFluidState())
                && below.getFaceOcclusionShape(Direction.UP) != Shapes.block()) return false;
        for (Direction side : SIDES) {
            BlockState neighbour = level.getBlockState(at.setWithOffset(pos, side));
            if (LiquidBlockRenderer.shouldRenderFace(fluid, state, side, neighbour.getFluidState())) return false;
        }
        return true;
    }
}
