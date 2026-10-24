package com.blockland.client.battleroyale;

import com.blockland.BlockLand;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * The drop plane: a high-wing four-engine transport out of boxes (model space, pixels, nose towards −Z, Y down). The
 * texture's box-UV layout is written by {@code tools/assets/gen_battleroyale.py} (same offsets and sizes). The
 * propellers turn with the plane's age.
 */
final class BrPlaneModel extends EntityModel<BrPlaneRenderer.State> {
    static final ModelLayerLocation LAYER = new ModelLayerLocation(BlockLand.id("br_plane"), "main");
    /** Engine positions along the wings (x, pixels). */
    private static final float[] ENGINES = {-50, -25, 25, 50};

    private final ModelPart[] propellers = new ModelPart[ENGINES.length];

    BrPlaneModel(ModelPart root) {
        super(root);
        for (int i = 0; i < ENGINES.length; i++) propellers[i] = root.getChild("propeller_" + i);
    }

    static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("fuselage", CubeListBuilder.create().texOffs(0, 0).addBox(-7, -7, -40, 14, 14, 80), PartPose.ZERO);
        root.addOrReplaceChild("nose", CubeListBuilder.create().texOffs(0, 94).addBox(-5, -5, -48, 10, 10, 8), PartPose.ZERO);
        root.addOrReplaceChild("wings", CubeListBuilder.create().texOffs(0, 112)
                .addBox(-87, -9, -12, 80, 3, 24)
                .addBox(7, -9, -12, 80, 3, 24), PartPose.ZERO);
        root.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(0, 140)
                .addBox(-37, -6, 26, 30, 2, 14)
                .addBox(7, -6, 26, 30, 2, 14), PartPose.ZERO);
        root.addOrReplaceChild("fin", CubeListBuilder.create().texOffs(90, 140).addBox(-1, -31, 24, 2, 24, 16), PartPose.ZERO);
        CubeListBuilder engines = CubeListBuilder.create().texOffs(130, 140);
        for (float x : ENGINES) engines.addBox(x - 4, -6, -16, 8, 8, 14);
        root.addOrReplaceChild("engines", engines, PartPose.ZERO);
        for (int i = 0; i < ENGINES.length; i++) {
            root.addOrReplaceChild("propeller_" + i, CubeListBuilder.create()
                    .texOffs(180, 140).addBox(-1, -11, -0.5f, 2, 22, 1)
                    .texOffs(180, 140).addBox(-11, -1, -0.6f, 22, 2, 1)
                    .texOffs(190, 140).addBox(-1.5f, -1.5f, -1.5f, 3, 3, 3), PartPose.offset(ENGINES[i], -2, -17));
        }
        return LayerDefinition.create(mesh, 256, 256);
    }

    @Override
    public void setupAnim(BrPlaneRenderer.State state) {
        super.setupAnim(state);
        for (int i = 0; i < propellers.length; i++) propellers[i].zRot = state.ageInTicks * 1.9f * (i % 2 == 0 ? 1 : -1);
    }
}
