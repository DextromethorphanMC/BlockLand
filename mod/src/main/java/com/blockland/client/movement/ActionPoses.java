package com.blockland.client.movement;

import com.blockland.movement.action.DiveRules;
import com.blockland.movement.action.MovementAction;
import com.blockland.movement.action.SlideRules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Third-person poses of the movement actions (spec §47; other players, and your own view in F5) — the same bodies the
 * hit boxes describe (each action's {@code hitBody} / {@code hitHead}):
 * <ul>
 *   <li>slide: legs stretched forward along the slide, hips down near the ground and the torso leaning back, the head
 *       still looking where the player looks — vanilla's riding legs plus a lean of the whole body about the hips,
 *       blended in and out with the slide;</li>
 *   <li>prone: vanilla's crawl (the action forces the crawl pose, synced to everyone) lies the body flat; holding
 *       something, the arms stay forward on it instead of swimming and the crawl rocks the body from side to side;</li>
 *   <li>dive: the crawl's flat body flying, arms thrown forward, the nose dipping as it falls.</li>
 * </ul>
 */
public final class ActionPoses {
    /** Height of the hips (leg pivot) of the player model at scale 1: 12 of 32 model pixels, model scale 15/16. */
    private static final float HIP = 12 / 16f * 0.9375f;
    /** A walk animation position whose crawl stroke holds both arms straight forward (vanilla's 26-unit stroke). */
    private static final float ARMS_FORWARD = 26 * 64 + 1.5f;
    /** Renders we transformed, so the matching Post undoes exactly those. */
    private static final Deque<PlayerRenderState> posed = new ArrayDeque<>();

    private ActionPoses() {
    }

    public static void init() {
        // last, so a listener that replaces the player's rendering (morphs) cancels before we push anything
        RenderPlayerEvent.Pre.BUS.addListener(Priority.LOWEST, ActionPoses::pre);
        RenderPlayerEvent.Post.BUS.addListener(ActionPoses::post);
    }

    private static boolean pre(RenderPlayerEvent.Pre event) {
        PlayerRenderState state = event.getState();
        MovementAction action = ClientActions.poseAction(state.id);
        if (action == null) return false;
        float a = ClientActions.poseAmount(state.id);
        if (a <= 0.001f) return false;
        // lying down is vanilla's crawl pose: once the body is up again, nothing is left to adjust
        if (action.lying() && !state.isVisuallySwimming) return false;
        return switch (action) {
            case SLIDE -> slide(event, state, a);
            case PRONE -> prone(event, state);
            case DIVE -> dive(event, state, a);
        };
    }

    private static boolean slide(RenderPlayerEvent.Pre event, PlayerRenderState state, float a) {
        SlideRules.Pose p = SlideRules.get().pose;
        float scale = state.scale;
        // the body faces the slide; the head keeps looking where the player looks
        float headYaw = state.bodyRot + state.yRot;
        state.bodyRot = Mth.rotLerp(a, state.bodyRot, ClientActions.poseYaw(state.id));
        state.yRot = Mth.wrapDegrees(headYaw - state.bodyRot);
        // leaning back tips the head (and arms aiming along it) up: look down by as much
        state.xRot += p.lean * a;
        // the slide forces the crouching pose for its low collision box; it should not look like a crouch
        boolean crouched = state.isCrouching;
        state.isCrouching = false;
        state.isPassenger = a > 0.35f;
        state.walkAnimationSpeed *= 1 - a;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        posed.push(state);
        double rad = Math.toRadians(state.bodyRot);
        float forwardX = (float) -Math.sin(rad), forwardZ = (float) Math.cos(rad);
        float hip = HIP * scale;
        // vanilla lowered a crouching player by 2 pixels before this event: put that back
        float lift = crouched ? 2 / 16f * scale : 0;
        pose.translate(forwardX * p.forward * scale * a, lift + Mth.lerp(a, hip, p.hipHeight * scale), forwardZ * p.forward * scale * a);
        // lean back about the body's sideways axis through the hips
        pose.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(-p.lean * a), (float) Math.cos(rad), 0, (float) Math.sin(rad)));
        pose.translate(0, -hip, 0);
        return false;
    }

    private static boolean prone(RenderPlayerEvent.Pre event, PlayerRenderState state) {
        if (state.getMainHandItem().isEmpty() && state.leftHandItem.isEmpty() && state.rightHandItem.isEmpty()) {
            return false; // empty hands crawl as vanilla's
        }
        // the weapon held forward: the stroke held at arms-forward, the crawl a rock of the body about its long axis
        float speed = state.walkAnimationSpeed;
        float rock = Mth.sin(state.walkAnimationPos * 0.33333334f) * 7 * Mth.clamp(speed * 2, 0, 1);
        state.walkAnimationPos = ARMS_FORWARD;
        state.walkAnimationSpeed = 0;
        if (Math.abs(rock) < 0.05f) return false;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        posed.push(state);
        double rad = Math.toRadians(state.bodyRot);
        float h = 0.15f * state.scale;
        pose.translate(0, h, 0);
        pose.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(rock), (float) -Math.sin(rad), 0, (float) Math.cos(rad)));
        pose.translate(0, -h, 0);
        return false;
    }

    private static boolean dive(RenderPlayerEvent.Pre event, PlayerRenderState state, float a) {
        // superman: both arms thrown forward, the legs trailing; the body faces the dive
        state.walkAnimationPos = ARMS_FORWARD;
        state.walkAnimationSpeed = 0;
        float headYaw = state.bodyRot + state.yRot;
        state.bodyRot = Mth.rotLerp(a, state.bodyRot, ClientActions.poseYaw(state.id));
        state.yRot = Mth.wrapDegrees(headYaw - state.bodyRot);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        posed.push(state);
        double rad = Math.toRadians(state.bodyRot);
        float pitch = DiveRules.get().pose.bodyPitch * a;
        float h = 0.3f * state.scale;
        // the nose dips about the body's sideways axis through its middle
        pose.translate(0, h, 0);
        pose.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(pitch), (float) Math.cos(rad), 0, (float) Math.sin(rad)));
        pose.translate(0, -h, 0);
        return false;
    }

    private static void post(RenderPlayerEvent.Post event) {
        if (!posed.isEmpty() && posed.peek() == event.getState()) {
            posed.pop();
            event.getPoseStack().popPose();
        }
    }
}
