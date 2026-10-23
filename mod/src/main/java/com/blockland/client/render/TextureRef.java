package com.blockland.client.render;

import net.minecraft.resources.ResourceLocation;

/**
 * A texture both render paths can use: our pipeline samples its GPU view ({@link Textures#view}), the vanilla
 * render-type path (shader packs) binds it by location.
 *
 * @param location the texture's id: a PNG in the resource packs, or a texture the TextureManager owns
 * @param managed  owned by Minecraft's TextureManager (the player skin) rather than a resource-pack PNG that
 *                 {@link Textures} uploads itself (tiling, mipmapped)
 */
public record TextureRef(ResourceLocation location, boolean managed) {
    /** A PNG from the resource packs, e.g. {@code blockland:textures/gun/camo_digital.png}. */
    public static TextureRef resource(ResourceLocation location) {
        return new TextureRef(location, false);
    }

    /** A texture registered with the TextureManager by someone else (player skins). */
    public static TextureRef managed(ResourceLocation location) {
        return new TextureRef(location, true);
    }
}
