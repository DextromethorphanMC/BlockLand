package com.blockland.gun;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * A gun. Shooting, reloading and the gunsmith are handled by the gun services (server) and the viewmodel
 * (client); the item itself only disables vanilla behaviour (mining, melee, swinging) and describes itself.
 */
public class GunItem extends Item {
    private final String gunId;

    public GunItem(String gunId, Properties properties) {
        super(properties);
        this.gunId = gunId;
    }

    public String gunId() {
        return gunId;
    }

    @Override
    public Component getName(ItemStack stack) {
        return GunCatalog.get().gun(gunId).<Component>map(gun -> Component.literal(gun.name)).orElseGet(() -> super.getName(stack));
    }

    @Override
    public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
        return false;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean onEntitySwing(ItemStack stack, LivingEntity entity) {
        return true;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        return true;
    }

    /** Ammo and attachment changes must not replay the vanilla equip motion. */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        GunState state = stack.get(GunContent.GUN_STATE.get());
        if (state == null) return;
        GunStats.of(gunId, state).ifPresent(stats -> {
            tooltip.accept(Component.literal("§7弹匣 §f" + state.ammo() + "/" + stats.capacity() + " §8· §7" + stats.ammo().name));
            tooltip.accept(Component.literal("§7" + stats.sight().name + " §8· §7" + stats.magazine().name
                    + (stats.laser().hasBeam() ? " §8· §7" + stats.laser().name : "")));
            tooltip.accept(Component.literal("§7皮肤 §f" + stats.skin().name));
            tooltip.accept(Component.literal("§8手持时按 G 打开枪匠"));
        });
    }

    /** Third person: both arms raised, aiming like a handgun. Only called on the client. */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return HumanoidModel.ArmPose.CROSSBOW_HOLD;
            }
        });
    }
}
