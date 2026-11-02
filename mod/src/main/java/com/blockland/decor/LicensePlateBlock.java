package com.blockland.decor;

import com.blockland.common.block.MultiBlockFootprint;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The license plate (车牌): two blocks wide, one high, 1 px thick, its front away from the wall it was placed on (flat on
 * it) or towards the placer on a floor (standing on two posts, {@link #WALL}). Two parts — the block placed is the
 * viewer's left, the right one goes beside it — broken as one, only the left drops; its two blocks are its footprint for
 * the placement preview ({@link MultiBlockFootprint}). Decoration only (tools/assets/gen_decor_props.py).
 */
public class LicensePlateBlock extends Block implements MultiBlockFootprint {
    public static final MapCodec<LicensePlateBlock> CODEC = simpleCodec(LicensePlateBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    /** Flat on a wall behind it, or standing on a floor. */
    public static final BooleanProperty WALL = BooleanProperty.create("wall");
    /** Pixels with the front north, the wall south, left = the viewer's left (east); keep in step with gen_decor_props.py. */
    static final double[][] LEFT_WALL_NORTH = {{0, 2, 15, 15, 14, 16}};
    static final double[][] RIGHT_WALL_NORTH = {{1, 2, 15, 16, 14, 16}};
    static final double[][] LEFT_FLOOR_NORTH = {{0, 2, 7, 15, 14, 8}, {7, 0, 7, 9, 2, 8}, {5, 0, 6, 11, 1, 9}};
    static final double[][] RIGHT_FLOOR_NORTH = {{1, 2, 7, 16, 14, 8}, {7, 0, 7, 9, 2, 8}, {5, 0, 6, 11, 1, 9}};
    private static final Map<Direction, VoxelShape> LEFT_WALL = TargetStandBlock.shapes(LEFT_WALL_NORTH),
            RIGHT_WALL = TargetStandBlock.shapes(RIGHT_WALL_NORTH), LEFT_FLOOR = TargetStandBlock.shapes(LEFT_FLOOR_NORTH),
            RIGHT_FLOOR = TargetStandBlock.shapes(RIGHT_FLOOR_NORTH);

    /** Which half of the plate a block is, seen from the front. */
    public enum Part implements StringRepresentable {
        LEFT, RIGHT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public LicensePlateBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.LEFT).setValue(WALL, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, WALL);
    }

    /** The way from the left part to the right one (seen from the front): the viewer's right. */
    private static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Where the other part of the part at {@code pos} is. */
    private static BlockPos partner(BlockState state, BlockPos pos) {
        Direction right = right(state.getValue(FACING));
        return pos.relative(state.getValue(PART) == Part.LEFT ? right : right.getOpposite());
    }

    @Override
    public List<BlockPos> footprint(BlockState state, BlockPos pos) {
        return List.of(pos, partner(state, pos));
    }

    /** The left part: on the side of a block flat on it, otherwise standing towards the placer; null unless the right one fits. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        boolean wall = clicked.getAxis().isHorizontal();
        Direction facing = wall ? clicked : context.getHorizontalDirection().getOpposite();
        BlockPos other = context.getClickedPos().relative(right(facing));
        Level level = context.getLevel();
        if (!level.getWorldBorder().isWithinBounds(other) || !level.getBlockState(other).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.LEFT).setValue(WALL, wall);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(partner(state, pos), state.setValue(PART, Part.RIGHT), Block.UPDATE_ALL);
    }

    /** A part whose other part is gone goes too. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        if (neighbourPos.equals(partner(state, pos))) {
            boolean matches = neighbour.is(this) && neighbour.getValue(FACING) == state.getValue(FACING)
                    && neighbour.getValue(WALL) == state.getValue(WALL) && neighbour.getValue(PART) != state.getValue(PART);
            if (!matches) return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbour, random);
    }

    /** In creative, breaking the right part takes the left with it without letting it drop. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.preventsBlockDrops() && state.getValue(PART) == Part.RIGHT) {
            BlockPos left = partner(state, pos);
            BlockState there = level.getBlockState(left);
            if (there.is(this) && there.getValue(PART) == Part.LEFT) {
                level.setBlock(left, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, left, Block.getId(there));
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
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean left = state.getValue(PART) == Part.LEFT;
        Map<Direction, VoxelShape> shapes = state.getValue(WALL) ? (left ? LEFT_WALL : RIGHT_WALL) : (left ? LEFT_FLOOR : RIGHT_FLOOR);
        return shapes.get(state.getValue(FACING));
    }
}
