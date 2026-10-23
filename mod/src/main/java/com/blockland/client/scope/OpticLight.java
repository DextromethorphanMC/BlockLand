package com.blockland.client.scope;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import org.joml.Vector3f;

/**
 * What an optic's glass reflects: the sky the player is under (its real colour, by biome and time of day) over their
 * ground, as lit as the player is — so the glass looks like glass in a cave, at night and at noon alike, instead of
 * showing a fixed studio sky (which read as a blue cast on every lens).
 */
final class OpticLight {
    private OpticLight() {
    }

    /**
     * @param into display RGB of the reflected sky (the ground is a dim share of it in the shaders)
     * @return the light around the player, 0..1
     */
    static float environment(Camera camera, int blockLight, int skyLight, Vector3f into) {
        ClientLevel level = Minecraft.getInstance().level;
        float partial = camera.getPartialTickTime();
        float daylight = level == null ? 1 : level.getSkyDarken(partial);
        float sky = skyLight / 15f * daylight, block = blockLight / 15f;
        int skyColor = level == null ? 0x78A7FF : level.getSkyColor(camera.getPosition(), partial);
        // block light is warm lamp light
        into.set(ARGB.redFloat(skyColor) * sky + 0.24f * block, ARGB.greenFloat(skyColor) * sky + 0.2f * block,
                ARGB.blueFloat(skyColor) * sky + 0.14f * block).mul(0.85f);
        return Math.max(blockLight, skyLight * daylight) / 15f;
    }
}
