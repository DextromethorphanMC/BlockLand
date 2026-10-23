package com.blockland.client.morph;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.S2CMorph;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws morphed players (the server decides who, {@link S2CMorph}) with a vanilla entity's model instead of their
 * own, e.g. infected players as zombies. Each morphed player has an invisible client-only proxy entity of that type;
 * its renderer draws it in the player's place with the player's pose copied over (walk cycle, body and head
 * rotation, attack swing, crouch, hurt flash, name tag), so it moves exactly like the player. In first person a
 * humanoid morph also replaces the player's bare arm with the entity's arm. Game-agnostic.
 */
public final class ClientMorphs {
    private static final Map<UUID, EntityType<?>> morphs = new HashMap<>();
    private static final Map<UUID, LivingEntity> proxies = new HashMap<>();
    @Nullable
    private static ClientLevel level;

    private ClientMorphs() {
    }

    public static void init() {
        BLNetwork.setMorphListener(ClientMorphs::onMorph);
        RenderPlayerEvent.Pre.BUS.addListener(ClientMorphs::renderPlayer);
        RenderArmEvent.BUS.addListener(ClientMorphs::renderArm);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            ClientLevel current = Minecraft.getInstance().level;
            // morphs belong to a match: a new level (back in the lobby) starts clean
            if (current != level) clear();
            level = current;
            for (LivingEntity proxy : proxies.values()) proxy.tickCount++;
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
    }

    private static void onMorph(S2CMorph morph) {
        proxies.remove(morph.player());
        if (morph.entity().isEmpty()) {
            morphs.remove(morph.player());
            return;
        }
        EntityType.byString(morph.entity()).ifPresentOrElse(type -> morphs.put(morph.player(), type), () -> morphs.remove(morph.player()));
    }

    private static void clear() {
        morphs.clear();
        proxies.clear();
    }

    /** The entity type a player is drawn as, or null. */
    @Nullable
    public static EntityType<?> morphOf(UUID player) {
        return morphs.get(player);
    }

    @Nullable
    private static LivingEntity proxy(AbstractClientPlayer player) {
        EntityType<?> type = morphs.get(player.getUUID());
        if (type == null) return null;
        LivingEntity proxy = proxies.get(player.getUUID());
        if (proxy == null || proxy.getType() != type || proxy.level() != player.level()) {
            Entity created = type.create(player.level(), EntitySpawnReason.LOAD);
            if (!(created instanceof LivingEntity living)) return null;
            proxy = living;
            proxies.put(player.getUUID(), proxy);
        }
        // the renderer reads position and rotation from the entity; the pose is copied from the player's state
        proxy.setPos(player.getX(), player.getY(), player.getZ());
        proxy.xo = player.xo;
        proxy.yo = player.yo;
        proxy.zo = player.zo;
        proxy.setYRot(player.getYRot());
        proxy.yRotO = player.yRotO;
        proxy.setXRot(player.getXRot());
        proxy.xRotO = player.xRotO;
        proxy.yBodyRot = player.yBodyRot;
        proxy.yBodyRotO = player.yBodyRotO;
        proxy.yHeadRot = player.yHeadRot;
        proxy.yHeadRotO = player.yHeadRotO;
        return proxy;
    }

    // ---- third person ---------------------------------------------------------------------------------

    private static boolean renderPlayer(RenderPlayerEvent.Pre event) {
        if (morphs.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(event.getState().id) instanceof AbstractClientPlayer player)) return false;
        LivingEntity proxy = proxy(player);
        if (proxy == null) return false;
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        renderAs(mc.getEntityRenderDispatcher().getRenderer(proxy), proxy, event.getState(), partialTick,
                event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
        return true;
    }

    private static <E extends Entity, S extends EntityRenderState> void renderAs(EntityRenderer<? super E, S> renderer, E proxy,
                                                                                 PlayerRenderState player, float partialTick,
                                                                                 PoseStack pose, MultiBufferSource buffers, int light) {
        S state = renderer.createRenderState(proxy, partialTick);
        copyPose(player, state);
        renderer.render(state, pose, buffers, light);
    }

    /** The player's animation onto the proxy's state: it walks, looks, swings and crouches like the player. */
    private static void copyPose(PlayerRenderState from, EntityRenderState to) {
        to.ageInTicks = from.ageInTicks;
        to.isInvisible = from.isInvisible;
        to.isDiscrete = from.isDiscrete;
        to.nameTag = from.nameTag;
        to.nameTagAttachment = from.nameTagAttachment;
        to.displayFireAnimation = from.displayFireAnimation;
        if (to instanceof LivingEntityRenderState living) {
            living.bodyRot = from.bodyRot;
            living.yRot = from.yRot;
            living.xRot = from.xRot;
            living.walkAnimationPos = from.walkAnimationPos;
            living.walkAnimationSpeed = from.walkAnimationSpeed;
            living.deathTime = from.deathTime;
            living.hasRedOverlay = from.hasRedOverlay;
            living.pose = from.pose;
            living.isInWater = from.isInWater;
            living.isInvisibleToPlayer = from.isInvisibleToPlayer;
            living.appearsGlowing = from.appearsGlowing;
        }
        if (to instanceof HumanoidRenderState humanoid) {
            humanoid.attackTime = from.attackTime;
            humanoid.attackArm = from.attackArm;
            humanoid.isCrouching = from.isCrouching;
            humanoid.swimAmount = from.swimAmount;
            humanoid.isVisuallySwimming = from.isVisuallySwimming;
            humanoid.speedValue = from.speedValue;
            humanoid.isPassenger = from.isPassenger;
        }
        // zombies reach out with both arms, like the real mob chasing its target
        if (to instanceof ZombieRenderState zombie) zombie.isAggressive = true;
    }

    // ---- first person ---------------------------------------------------------------------------------

    /** A humanoid morph's own arm instead of the player's bare arm. */
    private static boolean renderArm(RenderArmEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || morphs.isEmpty()) return false;
        LivingEntity proxy = proxy(mc.player);
        if (proxy == null) return false;
        return drawArm(mc.getEntityRenderDispatcher().getRenderer(proxy), proxy, event.getArm(), event.getPoseStack(),
                event.getMultiBufferSource(), event.getPackedLight());
    }

    private static <E extends Entity, S extends EntityRenderState> boolean drawArm(EntityRenderer<? super E, S> renderer, E proxy, HumanoidArm side,
                                                                                    PoseStack pose, MultiBufferSource buffers, int light) {
        if (!(renderer instanceof LivingEntityRenderer<?, ?, ?> living) || !(living.getModel() instanceof HumanoidModel<?> model)) return false;
        S state = renderer.createRenderState(proxy, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
        ResourceLocation texture = texture(living, state);
        if (texture == null) return false;
        ModelPart arm = side == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        arm.resetPose();
        arm.visible = true;
        arm.zRot = side == HumanoidArm.RIGHT ? 0.1F : -0.1F;
        arm.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(texture)), light, OverlayTexture.NO_OVERLAY);
        return true;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <S extends LivingEntityRenderState> ResourceLocation texture(LivingEntityRenderer<?, ?, ?> renderer, Object state) {
        try {
            return ((LivingEntityRenderer<?, S, ?>) renderer).getTextureLocation((S) state);
        } catch (ClassCastException e) {
            return null;
        }
    }
}
