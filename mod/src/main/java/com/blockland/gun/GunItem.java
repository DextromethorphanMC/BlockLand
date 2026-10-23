package com.blockland.gun;

import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import net.minecraft.ChatFormatting;
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
 * A gun. Shooting and reloading are handled by the gun services (server) and the viewmodel (client), its build by
 * the 工匠 (craft) system; the item itself only disables vanilla behaviour (mining, melee, swinging) and describes itself.
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
        return GunCatalog.get().gun(gunId).map(GunData.Gun::displayName).orElseGet(() -> super.getName(stack));
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
            Component ammoName = CraftCatalog.text("item.blockland." + stats.ammo().item, stats.ammo().name);
            tooltip.accept(Component.translatable("blockland.craft.tooltip.ammo", state.ammo(), stats.capacity(), ammoName)
                    .withStyle(ChatFormatting.GRAY));
            // the fitted parts, slot by slot (empty slots are left out)
            for (Craftable.Slot slot : stats.gun().slots()) {
                String option = stats.build().option(slot.id(), slot.defaultOption());
                if (option.equals(CraftData.NONE)) continue;
                tooltip.accept(Component.translatable("blockland.craft.slot." + slot.id()).append(": ")
                        .append(stats.gun().describe(slot.id(), option).name()).withStyle(ChatFormatting.GRAY));
            }
            tooltip.accept(Component.translatable("blockland.craft.skin").append(": ")
                    .append(CraftCatalog.skinName(stats.gun(), stats.skin())).withStyle(ChatFormatting.GRAY));
            tooltip.accept(Component.translatable("blockland.craft.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
        });
    }

    /**
     * Third person: both arms raised, aiming like a handgun; a dual pair holds a gun out in each hand. Only called on the
     * client.
     */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return com.blockland.client.gun.DualHold.pose(stack);
            }
        });
    }
}
