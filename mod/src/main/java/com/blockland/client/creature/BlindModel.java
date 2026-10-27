package com.blockland.client.creature;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.util.Mth;

/**
 * 瞎子: Minecraft's zombie model in a blind man's posture — hunched, the head pushed forward and low, cocked from side to
 * side as it listens (a sudden twitch now and then), the arms hanging forward and groping the air instead of the
 * zombie's raised reach. The rag over its eyes is the hat layer's band, which turns with the head (texture:
 * tools/assets/blind_skin.py).
 */
public final class BlindModel extends ZombieModel<ZombieRenderState> {
    public BlindModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(ZombieRenderState state) {
        super.setupAnim(state);
        float t = state.ageInTicks;
        // hunched: the torso leans, the head comes forward and down
        body.xRot += 0.32F;
        head.z -= 2.0F;
        head.y += 1.4F;
        // listening: the head cocks slowly from side to side, with a quick twitch every few seconds
        float twitch = Mth.clamp(Mth.sin(t * 0.045F) * 6.0F - 5.0F, 0.0F, 1.0F);
        head.zRot = Mth.sin(t * 0.06F) * 0.28F + twitch * Mth.sin(t * 1.7F) * 0.12F;
        head.xRot = head.xRot * 0.5F + 0.22F;
        // arms hang forward and grope the air; an attack swings them in
        float grope = Mth.sin(t * 0.09F) * 0.12F;
        float swing = Mth.sin(Mth.clamp(state.attackTime, 0, 1) * Mth.PI);
        rightArm.xRot = -0.55F + grope - swing * 0.9F + Mth.cos(state.walkAnimationPos * 0.6662F) * 0.25F * state.walkAnimationSpeed;
        leftArm.xRot = -0.55F - grope - swing * 0.6F - Mth.cos(state.walkAnimationPos * 0.6662F) * 0.25F * state.walkAnimationSpeed;
        rightArm.zRot = 0.08F + Mth.sin(t * 0.07F) * 0.05F;
        leftArm.zRot = -0.08F - Mth.sin(t * 0.07F + 1.3F) * 0.05F;
        rightArm.z = leftArm.z = -1.5F;
        rightArm.y = leftArm.y = 3.0F;
    }
}
