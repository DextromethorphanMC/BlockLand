package com.blockland.decor;

import com.blockland.common.block.BulletReactive;
import com.blockland.common.block.MultiBlockFootprint;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The shooting target (靶子): a wooden stand with a round straw-and-paper face on top, two blocks tall, its face towards
 * whoever placed it. A hit on the face — an arrow or any other projectile (vanilla's {@code onProjectileHit}) or a round
 * ({@link BulletReactive}, from the gun service) — gives a redstone pulse whose strength grows towards the bullseye, as
 * vanilla's target block does, from both halves, and a thump. Broken as one like a door; it reports its two blocks to the
 * placement preview ({@link MultiBlockFootprint}).
 */
public class TargetStandBlock extends Block implements MultiBlockFootprint, BulletReactive {
    public static final MapCodec<TargetStandBlock> CODEC = simpleCodec(TargetStandBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    /** Pulse length (ticks): arrows stay longer, as on vanilla's target. */
    private static final int ARROW_TICKS = 20, OTHER_TICKS = 8;
    /** The face (upper half): a disc of radius 7 px round the middle of the block (the board at z 6..8 px with the face north). */
    private static final double FACE_RADIUS = 7 / 16.0;
    /**
     * Pixels with the face north (keep in step with gen_decor.py): lower half the crossbar foot, the post and two braces;
     * upper half the board and the post's top behind it.
     */
    static final double[][] LOWER_NORTH = {{1, 0, 6, 15, 2, 12}, {7, 0, 8, 9, 16, 10}, {3, 2, 8, 5, 11, 10}, {11, 2, 8, 13, 11, 10}};
    static final double[][] UPPER_NORTH = {{1, 1, 6, 15, 15, 8}, {7, 0, 8, 9, 9, 10}};
    private static final Map<Direction, VoxelShape> LOWER = shapes(LOWER_NORTH), UPPER = shapes(UPPER_NORTH);

    public TargetStandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER).setValue(POWER, 0));
    }

    /** The boxes turned from north to every horizontal facing about the block's middle. */
    static Map<Direction, VoxelShape> shapes(double[][] north) {
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
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, POWER);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxY() || !level.getBlockState(pos.above()).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    @Override
    public List<BlockPos> footprint(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? List.of(pos, pos.above()) : List.of(pos, pos.below());
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            return neighbour.is(this) && neighbour.getValue(HALF) != half ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbour, random);
    }

    /** In creative the upper half takes the lower one with it without letting it drop (vanilla's doors). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.preventsBlockDrops() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState lower = level.getBlockState(below);
            if (lower.is(this) && lower.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, below, Block.getId(lower));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ---- hits ---------------------------------------------------------------------------------------

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        if (level instanceof ServerLevel server) {
            struck(server, state, hit.getBlockPos(), hit.getLocation(), projectile instanceof AbstractArrow ? ARROW_TICKS : OTHER_TICKS);
        }
    }

    @Override
    public void onBulletHit(ServerLevel level, BlockState state, BlockPos pos, Vec3 at, Direction face, @Nullable Entity shooter) {
        struck(level, state, pos, at, OTHER_TICKS);
    }

    /** A hit at {@code at}: on the face, a pulse by how near the bullseye it is (not while one is still on) and a thump. */
    private void struck(ServerLevel level, BlockState state, BlockPos pos, Vec3 at, int ticks) {
        if (state.getValue(HALF) != DoubleBlockHalf.UPPER) return;
        int power = strength(state.getValue(FACING), pos, at);
        if (power <= 0) return;
        level.playSound(null, at.x, at.y, at.z, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 1.0f, 0.7f + 0.03f * power);
        if (level.getBlockTicks().hasScheduledTick(pos, this)) return;
        level.setBlock(pos, state.setValue(POWER, power), Block.UPDATE_ALL);
        level.updateNeighborsAt(pos.below(), this);
        level.scheduleTick(pos, this, ticks);
    }

    /** 1..15 by the hit's distance from the face's middle (15 = the bullseye), 0 off the board. */
    private static int strength(Direction facing, BlockPos pos, Vec3 at) {
        double dx = at.x - (pos.getX() + 0.5), dy = at.y - (pos.getY() + 0.5), dz = at.z - (pos.getZ() + 0.5);
        // across the face: the horizontal axis perpendicular to the facing, and up
        double across = facing.getAxis() == Direction.Axis.Z ? dx : dz;
        double d = Math.sqrt(across * across + dy * dy);
        if (d > FACE_RADIUS) return 0;
        return Math.max(1, Mth.ceil(15.0 * Mth.clamp(1 - d / FACE_RADIUS, 0.0, 1.0)));
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(POWER) != 0) {
            level.setBlock(pos, state.setValue(POWER, 0), Block.UPDATE_ALL);
            level.updateNeighborsAt(pos.below(), this);
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /** Both halves give the face's pulse (redstone usually runs on the ground, by the lower half). */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) return state.getValue(POWER);
        BlockState above = level.getBlockState(pos.above());
        return above.is(this) ? above.getValue(POWER) : 0;
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
        return (state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER).get(state.getValue(FACING));
    }
}
