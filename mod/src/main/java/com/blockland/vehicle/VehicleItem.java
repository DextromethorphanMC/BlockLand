package com.blockland.vehicle;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * A vehicle's spawn item (vehicle spec §2): used on the ground — a boat on water — it puts the vehicle there facing the
 * way the player looks, where it fits. The server asks whether the player may use vehicles here (mode setting
 * {@code allowVehicles}).
 */
public class VehicleItem extends Item {
    private final Supplier<? extends EntityType<? extends VehicleEntity>> type;
    private final boolean boat;

    public VehicleItem(Supplier<? extends EntityType<? extends VehicleEntity>> type, boolean boat, Properties properties) {
        super(properties);
        this.type = type;
        this.boat = boat;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        HitResult hit = getPlayerPOVHitResult(level, player, boat ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
        boolean onWater = !level.getFluidState(block.getBlockPos()).isEmpty();
        if (boat && !onWater) return InteractionResult.PASS;
        VehicleEntity vehicle = type.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (vehicle == null) return InteractionResult.FAIL;
        Vec3 at = block.getLocation();
        vehicle.snapTo(at.x, at.y + 0.02, at.z, player.getYRot(), 0);
        vehicle.yRotO = player.getYRot();
        if (!level.noCollision(vehicle, vehicle.getBoundingBox())) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            if (player instanceof ServerPlayer sp && !Vehicles.server().mayUse(sp)) {
                sp.displayClientMessage(Component.translatable("blockland.vehicle.not_allowed"), true);
                return InteractionResult.FAIL;
            }
            level.addFreshEntity(vehicle);
            level.gameEvent(player, GameEvent.ENTITY_PLACE, at);
            stack.consume(1, player);
        }
        return InteractionResult.SUCCESS;
    }
}
