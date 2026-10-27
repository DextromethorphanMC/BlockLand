package com.blockland.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A stackable platform of 1–8 layers of 1/8 block (草台 grass layers, 石台 stone layers), stacked by using the same item
 * on it like vanilla's snow. What another block does to it (the user's rule): a layer lower than half a block (1–3
 * layers) gives way — another block placed into it replaces it, and a block put on top of it breaks it; one higher than
 * half but under a whole block (5–7 layers) is pressed down to half a block (4 layers) when a block is put on top of it.
 * Half a block and a whole block stay as they are. Solid to walk on at its full height (unlike snow).
 */
public class LayerBlock extends Block {
    public static final MapCodec<LayerBlock> CODEC = simpleCodec(LayerBlock::new);
    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;
    public static final int HALF = 4, FULL = 8;
    private static final VoxelShape[] SHAPES = new VoxelShape[FULL + 1];

    static {
        SHAPES[0] = Shapes.empty();
        for (int i = 1; i <= FULL; i++) SHAPES[i] = Block.box(0, 0, 0, 16, i * 2, 16);
    }

    public LayerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LAYERS, 1));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(LAYERS) == FULL ? 0.2F : 1.0F;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return type == PathComputationType.LAND && state.getValue(LAYERS) < 5;
    }

    /** On a full top face, or on a whole block of the same layers. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return Block.isFaceFull(below.getCollisionShape(level, pos.below()), Direction.UP)
                || below.is(this) && below.getValue(LAYERS) == FULL;
    }

    /**
     * Its own item stacks it (from above, or into its own block from the side); any other block replaces it while it is
     * lower than half a block.
     */
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        int layers = state.getValue(LAYERS);
        if (context.getItemInHand().is(asItem()) && layers < FULL) {
            return !context.replacingClickedOnBlock() || context.getClickedFace() == Direction.UP;
        }
        return layers < HALF;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState there = context.getLevel().getBlockState(context.getClickedPos());
        if (there.is(this)) return there.setValue(LAYERS, Math.min(FULL, there.getValue(LAYERS) + 1));
        return super.getStateForPlacement(context);
    }

    /**
     * Unsupported it goes; a block put on top of it breaks it while it is under half a block and presses it down to
     * half a block while it is between half and whole.
     */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        if (!state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        if (direction == Direction.UP && pressedBy(neighbour)) {
            int layers = state.getValue(LAYERS);
            if (layers < HALF) return Blocks.AIR.defaultBlockState();
            if (layers > HALF && layers < FULL) return state.setValue(LAYERS, HALF);
        }
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbour, random);
    }

    /** What counts as a block put on top: anything with a body, not air, fluids, plants or more of these layers. */
    private boolean pressedBy(BlockState above) {
        return !above.isAir() && !above.is(this) && !above.canBeReplaced() && above.blocksMotion();
    }
}
