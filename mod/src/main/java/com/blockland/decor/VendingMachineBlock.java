package com.blockland.decor;

import com.blockland.common.block.MultiBlockFootprint;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The vending machine (自动贩卖机): two blocks wide and two tall, its front towards whoever placed it, decoration only.
 * Four parts — the block placed is the lower part on the viewer's left, the others go to its right and above — broken as
 * one (any part gone takes the others; only the lower left drops the item, so a survival break drops one, and a
 * creative break of another part takes the lower left without its drop). Its four blocks are its footprint for the
 * placement preview ({@link MultiBlockFootprint}). Vanilla-style model per part (tools/assets/gen_decor.py).
 */
public class VendingMachineBlock extends Block implements MultiBlockFootprint {
    public static final MapCodec<VendingMachineBlock> CODEC = simpleCodec(VendingMachineBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    /** Pixels with the front north: the cabinet stands 1 px back from the front of its blocks. */
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            SHAPES.put(facing, switch (facing) {
                case SOUTH -> Block.box(0, 0, 0, 16, 16, 15);
                case EAST -> Block.box(0, 0, 0, 15, 16, 16);
                case WEST -> Block.box(1, 0, 0, 16, 16, 16);
                default -> Block.box(0, 0, 1, 16, 16, 16);
            });
        }
    }

    /** Which quarter of the machine a block is, seen from the front. */
    public enum Part implements StringRepresentable {
        LOWER_LEFT(false, false), LOWER_RIGHT(false, true), UPPER_LEFT(true, false), UPPER_RIGHT(true, true);

        final boolean upper, right;

        Part(boolean upper, boolean right) {
            this.upper = upper;
            this.right = right;
        }

        static Part of(boolean upper, boolean right) {
            return upper ? (right ? UPPER_RIGHT : UPPER_LEFT) : (right ? LOWER_RIGHT : LOWER_LEFT);
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public VendingMachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.LOWER_LEFT));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    /** The way the machine's right side points (seen from its front): the viewer's right. */
    private static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** The lower left part's position for a part at {@code pos}. */
    private static BlockPos origin(BlockState state, BlockPos pos) {
        Part part = state.getValue(PART);
        BlockPos o = part.upper ? pos.below() : pos;
        return part.right ? o.relative(right(state.getValue(FACING)).getOpposite()) : o;
    }

    private static BlockPos at(BlockPos origin, Direction facing, Part part) {
        BlockPos p = part.upper ? origin.above() : origin;
        return part.right ? p.relative(right(facing)) : p;
    }

    @Override
    public List<BlockPos> footprint(BlockState state, BlockPos pos) {
        BlockPos origin = origin(state, pos);
        Direction facing = state.getValue(FACING);
        List<BlockPos> out = new ArrayList<>(4);
        out.add(pos);
        for (Part part : Part.values()) {
            BlockPos p = at(origin, facing, part);
            if (!p.equals(pos)) out.add(p);
        }
        return out;
    }

    /** The lower left part, front towards the placer; null unless the other three blocks are free. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxY()) return null;
        for (Part part : Part.values()) {
            if (part == Part.LOWER_LEFT) continue;
            BlockPos p = at(pos, facing, part);
            if (!level.getWorldBorder().isWithinBounds(p) || !level.getBlockState(p).canBeReplaced(context)) return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.LOWER_LEFT);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        Direction facing = state.getValue(FACING);
        for (Part part : Part.values()) {
            if (part != Part.LOWER_LEFT) level.setBlock(at(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
        }
    }

    /** A part whose neighbour part (beside or above / below it) is gone goes too. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        Part part = state.getValue(PART);
        Direction facing = state.getValue(FACING);
        Direction side = part.right ? right(facing).getOpposite() : right(facing);
        Direction vertical = part.upper ? Direction.DOWN : Direction.UP;
        if (direction == side || direction == vertical) {
            Part expected = direction == side ? Part.of(part.upper, !part.right) : Part.of(!part.upper, part.right);
            if (!neighbour.is(this) || neighbour.getValue(FACING) != facing || neighbour.getValue(PART) != expected) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbour, random);
    }

    /** In creative, breaking another part takes the lower left with it without letting it drop. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.preventsBlockDrops() && state.getValue(PART) != Part.LOWER_LEFT) {
            BlockPos origin = origin(state, pos);
            BlockState lower = level.getBlockState(origin);
            if (lower.is(this) && lower.getValue(PART) == Part.LOWER_LEFT) {
                level.setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, origin, Block.getId(lower));
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
        return SHAPES.getOrDefault(state.getValue(FACING), Shapes.block());
    }
}
