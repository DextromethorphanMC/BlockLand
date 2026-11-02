package com.blockland.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * The rear-view mirror (后视镜): a wing mirror smaller than a block — a black housing with a glass face. Placed on a
 * wall it hangs from it on an arm, its glass facing away from the wall; placed on a floor it stands on a short stalk,
 * its glass towards the placer ({@link #WALL}). Decoration only (tools/assets/gen_decor_props.py).
 */
public class RearViewMirrorBlock extends Block {
    public static final MapCodec<RearViewMirrorBlock> CODEC = simpleCodec(RearViewMirrorBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    /** On a wall (an arm back to it) or standing on a floor (a stalk and a foot). */
    public static final BooleanProperty WALL = BooleanProperty.create("wall");
    /** Pixels with the glass north, the wall south (keep in step with gen_decor_props.py): housing, glass, then the mount. */
    static final double[][] WALL_NORTH = {{2, 4, 9, 14, 12, 12}, {3, 5, 12, 13, 11, 13}, {3, 5, 8.75, 13, 11, 9}, {7, 7, 13, 9, 9, 16}};
    static final double[][] FLOOR_NORTH = {{2, 4, 9, 14, 12, 12}, {3, 5, 12, 13, 11, 13}, {3, 5, 8.75, 13, 11, 9},
            {7, 0, 10, 9, 4, 12}, {5, 0, 8, 11, 1, 14}};
    private static final Map<Direction, VoxelShape> ON_WALL = TargetStandBlock.shapes(WALL_NORTH), ON_FLOOR = TargetStandBlock.shapes(FLOOR_NORTH);

    public RearViewMirrorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(WALL, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WALL);
    }

    /** On the side of a block: hung from it; otherwise standing, the glass towards the placer. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        boolean wall = clicked.getAxis().isHorizontal();
        return defaultBlockState().setValue(FACING, wall ? clicked : context.getHorizontalDirection().getOpposite()).setValue(WALL, wall);
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
        return (state.getValue(WALL) ? ON_WALL : ON_FLOOR).get(state.getValue(FACING));
    }
}
