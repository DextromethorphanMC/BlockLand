package com.blockland.revive;

import com.blockland.common.block.MultiBlockFootprint;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The revive antenna (复活天线, asset spec §51): a field relay two blocks tall, for now only decoration. A lower and an
 * upper half like a door — placed only where the block above is free (the upper half is set right after), broken as
 * one (the lower half drops the item; in creative nothing drops). Both halves are drawn by the lower half's block
 * entity renderer (the GLB), so the block itself has no model but its break particles. It reports its two blocks to
 * the placement preview ({@link MultiBlockFootprint}).
 */
public class ReviveAntennaBlock extends BaseEntityBlock implements MultiBlockFootprint {
    public static final MapCodec<ReviveAntennaBlock> CODEC = simpleCodec(ReviveAntennaBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /**
     * Pixels with the front north (revive_antenna_model.py: the case, the mast's first stage, the head with its dish
     * and the beacon; the whip is left out). Lower half: the case (handles included) and the mast; upper half: the mast
     * and the head.
     */
    private static final double[][] LOWER_NORTH = {{2.9, 0, 4.6, 13.1, 4.4, 11.4}, {7.4, 4.4, 8.3, 8.6, 16, 9.5}};
    private static final double[][] UPPER_NORTH = {{7.4, 0, 8.3, 8.6, 4.3, 9.5}, {6.0, 4.3, 5.3, 10.0, 8.4, 10.2}};
    private static final Map<Direction, VoxelShape> LOWER = shapes(LOWER_NORTH), UPPER = shapes(UPPER_NORTH);

    public ReviveAntennaBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
    }

    /** The boxes turned from north to every horizontal facing about the block's middle. */
    private static Map<Direction, VoxelShape> shapes(double[][] north) {
        Map<Direction, VoxelShape> out = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : north) {
                double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
                double[] r = switch (facing) {
                    case SOUTH -> new double[]{16 - x1, 16 - z1, 16 - x0, 16 - z0};
                    case EAST -> new double[]{16 - z1, x0, 16 - z0, x1};
                    case WEST -> new double[]{z0, 16 - x1, z1, 16 - x0};
                    default -> new double[]{x0, z0, x1, z1};
                };
                shape = Shapes.or(shape, Block.box(r[0], b[1], r[1], r[2], b[4], r[3]));
            }
            out.put(facing, shape.optimize());
        }
        return out;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    /** The lower half, its front towards whoever placed it; null where the block above is not free. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxY() || !level.getBlockState(pos.above()).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    @Override
    public List<BlockPos> footprint(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? List.of(pos, pos.above()) : List.of(pos, pos.below());
    }

    /** A half whose other half is gone (or replaced) goes too. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            return neighbour.is(this) && neighbour.getValue(HALF) != half ? state : Blocks.AIR.defaultBlockState();
        }
        return half == DoubleBlockHalf.LOWER && direction == Direction.DOWN && !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbour, random);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
        BlockState below = level.getBlockState(pos.below());
        // placement asks before the lower half is there (Forge's note on DoublePlantBlock): assume it will be
        return state.getBlock() != this || below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    /** In creative the upper half takes the lower one with it without letting it drop (vanilla's doors and tall plants). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.preventsBlockDrops() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState lower = level.getBlockState(below);
            if (lower.is(this) && lower.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, lower.getFluidState().is(Fluids.WATER) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, below, Block.getId(lower));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected long getSeed(BlockState state, BlockPos pos) {
        return net.minecraft.util.Mth.getSeed(pos.getX(), pos.below(state.getValue(HALF) == DoubleBlockHalf.LOWER ? 0 : 1).getY(), pos.getZ());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER).get(state.getValue(FACING));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** Only the lower half has a block entity: it draws the whole antenna and blinks its beacon. */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new ReviveAntennaBlockEntity(pos, state) : null;
    }
}
