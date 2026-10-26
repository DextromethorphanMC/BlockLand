package com.blockland.client.placement;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Shows where a block that occupies more than one block would go (rendering spec §26): while one is held and the
 * crosshair is on a spot where vanilla would place it, the blocks it would take are outlined — a faint fill and a light
 * line with a dark halo, so it reads on snow and in caves alike. Asks only {@link Footprints} (the block's
 * {@code MultiBlockFootprint}, vanilla's doors, beds and tall plants); nothing when the block cannot go there.
 *
 * <p>Drawn from vanilla's block outline (Forge's {@link RenderHighlightEvent.Block}): the same point of the level's
 * frame as the outline it sits beside, so it follows whatever the outline does — jittered with the level, never in the
 * shadow map, hidden with the HUD and wherever vanilla shows no outline.
 */
public final class PlacementPreview {
    private static final int FILL = 0x2E9AE0FF;
    private static final int LINE = 0xF0A8ECFF;
    private static final int HALO = 0x8C000000;
    /** The fill sits this far inside the blocks (it must not fight with the faces it rests on). */
    private static final double INSET = 0.004;

    /** Block classes whose empty-hand use does something (a chest, a door, a lever): a click there uses the block. */
    private static final ClassValue<Boolean> USED_BY_CLICK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Block.class && c != BlockBehaviour.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) if (m.getName().equals("useWithoutItem")) return true;
            }
            return false;
        }
    };

    private PlacementPreview() {
    }

    public static void init(BusGroup modBus) {
        RenderHighlightEvent.Block.BUS.addListener(event -> {
            List<BlockPos> cells = placement(event.getTarget());
            if (cells != null) draw(event.getPoseStack(), event.getMultiBufferSource(), event.getCamera().getPosition(), cells);
            return false;
        });
    }

    /**
     * The blocks the held block would take if the use button were pressed now, or null when it takes only one or would
     * not be placed — vanilla's own decision ({@code MultiPlayerGameMode.useItemOn}, {@code BlockItem.place}): the main
     * hand's block (the off hand's while the main hand is empty), not when the click would use the block clicked (unless
     * sneaking), the adventure rules, the placement context, the block's placement state, survival and room.
     */
    @Nullable
    static List<BlockPos> placement(BlockHitResult hit) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || player.isSpectator() || hit.getType() != HitResult.Type.BLOCK) return null;
        InteractionHand hand;
        if (player.getMainHandItem().getItem() instanceof BlockItem) hand = InteractionHand.MAIN_HAND;
        else if (player.getMainHandItem().isEmpty() && player.getOffhandItem().getItem() instanceof BlockItem) hand = InteractionHand.OFF_HAND;
        else return null;
        ItemStack stack = player.getItemInHand(hand);
        BlockItem item = (BlockItem) stack.getItem();
        BlockPos clicked = hit.getBlockPos();
        if (!level.getWorldBorder().isWithinBounds(clicked)) return null;
        if (!player.isSecondaryUseActive() && USED_BY_CLICK.get(level.getBlockState(clicked).getBlock().getClass())) return null;
        if (!player.getAbilities().mayBuild && !stack.canPlaceOnBlockInAdventureMode(new BlockInWorld(level, clicked, false))) return null;
        if (!item.getBlock().isEnabled(level.enabledFeatures())) return null;
        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
        if (!context.canPlace()) return null;
        context = item.updatePlacementContext(context);
        if (context == null) return null;
        BlockState state = item.getBlock().getStateForPlacement(context);
        if (state == null) return null;
        BlockPos pos = context.getClickedPos();
        if (!state.canSurvive(level, pos) || !level.isUnobstructed(state, pos, CollisionContext.placementContext(player))) return null;
        List<BlockPos> cells = Footprints.of(state, pos);
        if (cells == null || cells.size() < 2) return null;
        for (BlockPos cell : cells) {
            if (!level.isInsideBuildHeight(cell.getY()) || !level.getWorldBorder().isWithinBounds(cell)) return null;
        }
        return cells;
    }

    private static void draw(PoseStack stack, MultiBufferSource buffers, Vec3 camera, List<BlockPos> cells) {
        BlockPos origin = cells.get(0);
        VoxelShape shape = Shapes.empty();
        for (BlockPos cell : cells) {
            shape = Shapes.or(shape, Shapes.block().move(cell.getX() - origin.getX(), cell.getY() - origin.getY(), cell.getZ() - origin.getZ()));
        }
        shape = shape.optimize();
        double x = origin.getX() - camera.x, y = origin.getY() - camera.y, z = origin.getZ() - camera.z;
        PoseStack.Pose pose = stack.last();
        VertexConsumer fill = buffers.getBuffer(RenderType.debugStructureQuads());
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> box(fill, pose, (float) (x + x0 + INSET), (float) (y + y0 + INSET),
                (float) (z + z0 + INSET), (float) (x + x1 - INSET), (float) (y + y1 - INSET), (float) (z + z1 - INSET)));
        ShapeRenderer.renderShape(stack, buffers.getBuffer(RenderType.secondaryBlockOutline()), shape, x, y, z, HALO);
        ShapeRenderer.renderShape(stack, buffers.getBuffer(RenderType.lines()), shape, x, y, z, LINE);
    }

    /** The six faces of a box as quads (the fill's render type draws both sides). */
    private static void box(VertexConsumer out, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1) {
        quad(out, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(out, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(out, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        out.addVertex(pose, ax, ay, az).setColor(FILL);
        out.addVertex(pose, bx, by, bz).setColor(FILL);
        out.addVertex(pose, cx, cy, cz).setColor(FILL);
        out.addVertex(pose, dx, dy, dz).setColor(FILL);
    }
}
