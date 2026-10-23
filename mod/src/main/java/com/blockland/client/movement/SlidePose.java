package com.blockland.client.movement;

import com.blockland.movement.SlideRules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Third-person slide pose (other players, and your own view in F5): legs stretched forward along the slide, hips
 * down near the ground and the torso leaning back, the head still looking where the player looks — the same body the
 * hit boxes describe ({@link SlideRules#hitBody} / {@link SlideRules#hitHead}). Vanilla's model has no slide, so the
 * pose is its riding legs plus a lean of the whole body about the hips, blended in and out with the slide.
 */
public final class SlidePose {
    /** Height of the hips (leg pivot) of the player model at scale 1: 12 of 32 model pixels, model scale 15/16. */
    private static final float HIP = 12 / 16f * 0.9375f;
    /** Renders we transformed, so the matching Post undoes exactly those. */
    private static final Deque<PlayerRenderState> posed = new ArrayDeque<>();

    private SlidePose() {
    }

    public static void init() {
        // last, so a listener that replaces the player's rendering (morphs) cancels before we push anything
        RenderPlayerEvent.Pre.BUS.addListener(Priority.LOWEST, SlidePose::pre);
        RenderPlayerEvent.Post.BUS.addListener(SlidePose::post);
    }

    private static boolean pre(RenderPlayerEvent.Pre event) {
        PlayerRenderState state = event.getState();
        float a = ClientSlide.poseAmount(state.id);
        if (a <= 0.001f) return false;
        SlideRules.Pose p = SlideRules.get().pose;
        float scale = state.scale;
        // the body faces the slide; the head keeps looking where the player looks
        float headYaw = state.bodyRot + state.yRot;
        state.bodyRot = Mth.rotLerp(a, state.bodyRot, ClientSlide.poseYaw(state.id));
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

    private static void post(RenderPlayerEvent.Post event) {
        if (!posed.isEmpty() && posed.peek() == event.getState()) {
            posed.pop();
            event.getPoseStack().popPose();
        }
    }
}
