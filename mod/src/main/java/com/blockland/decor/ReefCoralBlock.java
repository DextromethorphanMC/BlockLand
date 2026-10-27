package com.blockland.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reef coral (礁珊瑚): a knobbly coral core with an arm out to every neighbouring reef coral (alive or dead) — a heap
 * of them grows into one branching reef, the model joining up like a chorus plant's (multipart: the core and one arm per
 * joined side) — and down to whatever solid face it grows on. Alive while water touches it (waterlogged or next to
 * water), dead and bleached a moment after it has none, keeping its shape, as vanilla's corals die.
 */
public class ReefCoralBlock extends Block implements SimpleWaterloggedBlock {
    public static final MapCodec<ReefCoralBlock> CODEC = simpleCodec(p -> new ReefCoralBlock(null, p));
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final Map<Direction, BooleanProperty> SIDES = PipeBlock.PROPERTY_BY_DIRECTION;
    /** The core (8 px) and an arm (6 px square) to each joined side. */
    private static final VoxelShape CORE = Block.box(4, 4, 4, 12, 12, 12);
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);

    static {
        ARMS.put(Direction.NORTH, Block.box(5, 5, 0, 11, 11, 4));
        ARMS.put(Direction.SOUTH, Block.box(5, 5, 12, 11, 11, 16));
        ARMS.put(Direction.WEST, Block.box(0, 5, 5, 4, 11, 11));
        ARMS.put(Direction.EAST, Block.box(12, 5, 5, 16, 11, 11));
        ARMS.put(Direction.DOWN, Block.box(5, 0, 5, 11, 4, 11));
        ARMS.put(Direction.UP, Block.box(5, 12, 5, 11, 16, 11));
    }

    /** What it turns into when it dies; null for a dead one. */
    @Nullable
    private final Supplier<Block> dead;
    private final Function<BlockState, VoxelShape> shapes;

    public ReefCoralBlock(@Nullable Supplier<Block> dead, Properties properties) {
        super(properties);
        this.dead = dead;
        BlockState state = stateDefinition.any().setValue(WATERLOGGED, false);
        for (BooleanProperty side : SIDES.values()) state = state.setValue(side, false);
        registerDefaultState(state);
        shapes = getShapeForEachState(ReefCoralBlock::shapeOf);
    }

    private static VoxelShape shapeOf(BlockState state) {
        VoxelShape shape = CORE;
        for (Map.Entry<Direction, BooleanProperty> side : SIDES.entrySet()) {
            if (state.getValue(side.getValue())) shape = Shapes.or(shape, ARMS.get(side.getKey()));
        }
        return shape.optimize();
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED);
        SIDES.values().forEach(builder::add);
    }

    /** Joins another reef coral on any side, and a solid face below it. */
    private static boolean joins(LevelReader level, BlockPos pos, Direction side) {
        BlockPos at = pos.relative(side);
        BlockState there = level.getBlockState(at);
        if (there.getBlock() instanceof ReefCoralBlock) return true;
        return side == Direction.DOWN && there.isFaceSturdy(level, at, Direction.UP);
    }

    private BlockState joined(BlockState state, LevelReader level, BlockPos pos) {
        for (Map.Entry<Direction, BooleanProperty> side : SIDES.entrySet()) {
            state = state.setValue(side.getValue(), joins(level, pos, side.getKey()));
        }
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean water = context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER;
        return joined(defaultBlockState().setValue(WATERLOGGED, water), context.getLevel(), context.getClickedPos());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        if (dead != null && !wet(level, pos, state)) level.scheduleTick(pos, this, 60 + level.random.nextInt(40));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        if (state.getValue(WATERLOGGED)) ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        if (dead != null && !wet(level, pos, state)) ticks.scheduleTick(pos, this, 60 + random.nextInt(40));
        return state.setValue(SIDES.get(direction), joins(level, pos, direction));
    }

    /** Alive one dries out and dies (into its dead twin, the same shape). */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (dead == null || wet(level, pos, state)) return;
        BlockState into = dead.get().defaultBlockState().setValue(WATERLOGGED, false);
        for (BooleanProperty side : SIDES.values()) into = into.setValue(side, state.getValue(side));
        level.setBlock(pos, into, Block.UPDATE_CLIENTS);
    }

    /** Water touches it: it is waterlogged, or water lies next to it (vanilla coral's rule). */
    private static boolean wet(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.getValue(WATERLOGGED)) return true;
        for (Direction side : Direction.values()) {
            if (level.getFluidState(pos.relative(side)).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.apply(state);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return !state.getValue(WATERLOGGED);
    }
}
