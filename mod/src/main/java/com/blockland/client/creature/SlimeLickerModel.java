package com.blockland.client.creature;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.util.Mth;

/**
 * The Slime Licker: Minecraft's zombie model, modified — a dislocated jaw hanging from the face, a long two-part
 * tongue that sways (and lashes out when it attacks), a pulsing slime sac on the back and a hunched posture.
 * Texture layout: the vanilla zombie's for the body, the free lower half for the new parts (asset spec, monsters).
 */
public final class SlimeLickerModel extends ZombieModel<ZombieRenderState> {
    private final ModelPart jaw;
    private final ModelPart tongue;
    private final ModelPart tongueTip;
    private final ModelPart sac;

    public SlimeLickerModel(ModelPart root) {
        super(root);
        this.jaw = head.getChild("jaw");
        this.tongue = jaw.getChild("tongue_base");
        this.tongueTip = tongue.getChild("tongue_tip");
        this.sac = body.getChild("sac");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.getChild("head");
        // jaw: hinged at the bottom front of the head, hanging open
        PartDefinition jaw = head.addOrReplaceChild("jaw", CubeListBuilder.create().texOffs(36, 32).addBox(-3.0F, 0.0F, -4.0F, 6, 2, 4),
                PartPose.offsetAndRotation(0.0F, 0.0F, -0.5F, 0.45F, 0.0F, 0.0F));
        PartDefinition tongue = jaw.addOrReplaceChild("tongue_base", CubeListBuilder.create().texOffs(0, 32).addBox(-1.0F, 0.0F, -5.0F, 2, 1, 5),
                PartPose.offsetAndRotation(0.0F, 0.6F, -3.5F, 0.35F, 0.0F, 0.0F));
        tongue.addOrReplaceChild("tongue_tip", CubeListBuilder.create().texOffs(0, 40).addBox(-1.0F, 0.0F, -4.0F, 2, 1, 4),
                PartPose.offsetAndRotation(0.0F, 0.0F, -5.0F, 0.5F, 0.0F, 0.0F));
        // the slime sac bulges from the upper back
        root.getChild("body").addOrReplaceChild("sac", CubeListBuilder.create().texOffs(16, 32).addBox(-3.0F, -3.0F, 0.0F, 6, 6, 3),
                PartPose.offset(0.0F, 5.0F, 1.8F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(ZombieRenderState state) {
        super.setupAnim(state);
        float t = state.ageInTicks;
        // hunched: the torso and head lean forward
        body.xRot += 0.18F;
        head.z -= 1.0F;
        head.y += 0.6F;
        // the tongue hangs and sways; on an attack it lashes forward
        float lash = Mth.sin(Mth.clamp(state.attackTime, 0, 1) * Mth.PI);
        tongue.xRot = 0.35F + Mth.sin(t * 0.17F) * 0.12F - lash * 0.55F;
        tongue.yRot = Mth.sin(t * 0.11F) * 0.18F;
        tongueTip.xRot = 0.5F + Mth.sin(t * 0.17F + 1.1F) * 0.22F - lash * 0.45F;
        jaw.xRot = 0.45F + Mth.sin(t * 0.08F) * 0.05F + lash * 0.25F;
        // the sac breathes
        float pulse = 1.0F + Mth.sin(t * 0.14F) * 0.07F;
        sac.xScale = pulse;
        sac.yScale = pulse;
        sac.zScale = 1.0F + (pulse - 1.0F) * 1.6F;
    }
}
