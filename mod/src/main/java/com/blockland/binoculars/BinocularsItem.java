package com.blockland.binoculars;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * The binoculars: held use raises them to the eyes for as long as the button is held (vanilla syncs the use, so other
 * players see them raised, and it slows the walk as any use does). The use animation is {@code NONE}: the first-person
 * raise, the zoom and the view are the client's ({@code client/binoculars}), the third-person pose is the item's client
 * extension.
 */
public class BinocularsItem extends Item {
    /** Held up as long as the button is (an hour). */
    private static final int USE_DURATION = 72_000;

    public BinocularsItem(Properties properties) {
        super(properties);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // on both sides: this client hears it at once, the server plays it to everyone else
        player.playSound(BinocularsContent.sound("binoculars.raise"), 0.8f, 1.0f);
        player.awardStat(Stats.ITEM_USED.get(this));
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        lowered(entity);
        return stack;
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        lowered(entity);
        return true;
    }

    private static void lowered(LivingEntity entity) {
        entity.playSound(BinocularsContent.sound("binoculars.lower"), 0.7f, 1.0f);
    }

    /** Third person: both hands bring them up to the eyes while in use; the first-person raise. Only called on the client. */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return com.blockland.client.binoculars.ClientBinoculars.armPose(entity, hand);
            }

            @Override
            public boolean applyForgeHandTransform(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.player.LocalPlayer player,
                                                   HumanoidArm arm, ItemStack stack, float partialTick, float equipProcess, float swingProcess) {
                return com.blockland.client.binoculars.ClientBinoculars.handTransform(pose, arm, equipProcess);
            }
        });
    }
}
