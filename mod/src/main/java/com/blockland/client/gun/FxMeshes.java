package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.Textures;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;

/**
 * Effect geometry drawn with the retained pipeline: a front-on muzzle star, two crossed side flames and a laser
 * beam (unit length along −Z). Built in code (no asset needed), uploaded once.
 */
final class FxMeshes {
    static final String FLASH_FRONT = "flash_front";
    static final String FLASH_SIDE = "flash_side";
    static final String BEAM = "beam";

    private static GpuModel model;
    private static int flashFront, flashSide, beam;

    private FxMeshes() {
    }

    static GpuModel model() {
        if (model == null) {
            GltfModel m = new GltfModel();
            flashFront = add(m, FLASH_FRONT, quadXY(0.5f));
            flashSide = add(m, FLASH_SIDE, crossedAlongZ(0.22f, 1.0f));
            GltfModel.Primitive beamQuads = crossedAlongZ(1f, 1f);
            // the glow texture's centre sits at the emitter and fades out along the beam and across it
            beamQuads.uvs = new float[]{0.5f, 0, 0.5f, 1, 1, 1, 1, 0, 0.5f, 0, 0.5f, 1, 1, 1, 1, 0};
            beam = add(m, BEAM, beamQuads);
            model = GpuModel.upload("blockland fx", m);
        }
        return model;
    }

    static int flashFront() {
        model();
        return flashFront;
    }

    static int flashSide() {
        model();
        return flashSide;
    }

    static int beam() {
        model();
        return beam;
    }

    /** Texture for an effect mesh (resource pack textures, see the asset spec §9). */
    @Nullable
    static GpuTextureView texture(String material) {
        return switch (material) {
            case FLASH_FRONT -> Textures.resource(BlockLand.id("textures/effect/muzzle_flash.png"));
            case FLASH_SIDE -> Textures.resource(BlockLand.id("textures/effect/muzzle_flash_side.png"));
            case BEAM -> Textures.resource(BlockLand.id("textures/effect/glow.png"));
            default -> null;
        };
    }

    static void clear() {
        if (model != null) model.close();
        model = null;
    }

    private static int add(GltfModel m, String name, GltfModel.Primitive primitive) {
        GltfModel.Material material = new GltfModel.Material();
        material.name = name;
        material.emissive[0] = material.emissive[1] = material.emissive[2] = 1;
        material.alphaMode = "ADD";
        material.doubleSided = true;
        primitive.material = m.materials.size();
        m.materials.add(material);
        GltfModel.Mesh mesh = new GltfModel.Mesh();
        mesh.name = name;
        mesh.primitives.add(primitive);
        m.meshes.add(mesh);
        return m.meshes.size() - 1;
    }

    /** A square facing ±Z, centred on the origin. */
    private static GltfModel.Primitive quadXY(float half) {
        GltfModel.Primitive p = new GltfModel.Primitive();
        p.positions = new float[]{-half, -half, 0, half, -half, 0, half, half, 0, -half, half, 0};
        p.normals = new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        p.uvs = new float[]{0, 1, 1, 1, 1, 0, 0, 0};
        p.indices = new int[]{0, 1, 2, 0, 2, 3};
        return p;
    }

    /** Two perpendicular quads from z = 0 to z = −length (u runs along the length), half-width {@code half}. */
    private static GltfModel.Primitive crossedAlongZ(float half, float length) {
        GltfModel.Primitive p = new GltfModel.Primitive();
        p.positions = new float[]{
                -half, 0, 0, half, 0, 0, half, 0, -length, -half, 0, -length,
                0, -half, 0, 0, half, 0, 0, half, -length, 0, -half, -length};
        p.normals = new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0};
        p.uvs = new float[]{0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0};
        p.indices = new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7};
        return p;
    }
}
