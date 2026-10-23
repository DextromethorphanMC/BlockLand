package com.blockland.common;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Sound events by id, whether or not code registered them: a sound that only exists in a resource pack's
 * sounds.json (a new gun's shot, an animation event) still plays — on the client directly, and from the server as a
 * direct sound in the packet. So weapons and animations can add sounds through data and assets alone.
 */
public final class Sounds {
    private static final Map<ResourceLocation, SoundEvent> unregistered = new HashMap<>();

    private Sounds() {
    }

    public static synchronized SoundEvent byId(ResourceLocation id) {
        return BuiltInRegistries.SOUND_EVENT.getOptional(id)
                .orElseGet(() -> unregistered.computeIfAbsent(id, SoundEvent::createVariableRangeEvent));
    }
}
