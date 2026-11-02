package com.blockland.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Tires (轮胎): one to three in a block ({@link #TIRES}), added with their own item like vanilla's sea pickles (not while
 * sneaking). Lying flat they stack up from the floor; upright on the tread ({@link #UPRIGHT}) they stand side by side
 * along their axle ({@link #AXIS}), centred in the block. Placed on the side of a block they stand with their side to it
 * (the axle along the clicked face's normal); on a floor they lie flat, or stand with the axle along the placer's look
 * when sneaking. Decoration only (tools/assets/gen_decor_props.py).
 */
public class TireBlock extends Block {
    public static final MapCodec<TireBlock> CODEC = simpleCodec(TireBlock::new);
    public static final int MAX = 3;
    public static final IntegerProperty TIRES = IntegerProperty.create("tires", 1, MAX);
    public static final BooleanProperty UPRIGHT = BooleanProperty.create("upright");
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    /** Pixels: a tyre's width (its height lying flat) and the across of its ring; keep in step with gen_decor_props.py. */
    static final int WIDTH = 5, ACROSS = 14;
    /** [upright ? 1 : 0][axis x ? 1 : 0][tires]. */
    private static final VoxelShape[][][] SHAPES = new VoxelShape[2][2][MAX + 1];

    static {
        for (int n = 1; n <= MAX; n++) {
            VoxelShape flat = Block.box(1, 0, 1, 15, WIDTH * n, 15);
            double start = (16 - WIDTH * n) / 2.0;
            SHAPES[0][0][n] = flat;
            SHAPES[0][1][n] = flat;
            SHAPES[1][0][n] = Block.box(1, 0, start, 15, ACROSS, start + WIDTH * n);
            SHAPES[1][1][n] = Block.box(start, 0, 1, start + WIDTH * n, ACROSS, 15);
        }
    }

    public TireBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIRES, 1).setValue(UPRIGHT, false).setValue(AXIS, Direction.Axis.Z));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIRES, UPRIGHT, AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState there = context.getLevel().getBlockState(context.getClickedPos());
        if (there.is(this)) return there.setValue(TIRES, Math.min(MAX, there.getValue(TIRES) + 1));
        Direction clicked = context.getClickedFace();
        if (clicked.getAxis().isHorizontal()) return defaultBlockState().setValue(UPRIGHT, true).setValue(AXIS, clicked.getAxis());
        return defaultBlockState().setValue(UPRIGHT, context.isSecondaryUseActive()).setValue(AXIS, context.getHorizontalDirection().getAxis());
    }

    /** Its own item adds a tyre (not while sneaking: that places a new block beside it). */
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !context.isSecondaryUseActive() && context.getItemInHand().is(asItem()) && state.getValue(TIRES) < MAX
                || super.canBeReplaced(state, context);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            return state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        }
        return state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(UPRIGHT) ? 1 : 0][state.getValue(AXIS) == Direction.Axis.X ? 1 : 0][state.getValue(TIRES)];
    }
}
