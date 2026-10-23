package com.blockland.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Smoke grenade: a cloud that stands for {@code duration} seconds where the grenade popped. Blocking the view is the
 * client's job (dense puffs, the screen greys out inside); the server keeps the volume so it can put fire out.
 */
final class Smoke {
    private Smoke() {
    }

    static int start(ServerLevel level, Entity thrown, JsonObject params) {
        int ticks = Math.max(20, Math.round(Params.f(params, "duration", 18) * 20));
        Vec3 at = thrown.position();
        Areas.Smoke smoke = new Areas.Smoke(thrown, at, Params.f(params, "radius", 4.8f), Params.f(params, "height", 3.4f),
                level.getGameTime() + ticks);
        Areas.addSmoke(smoke);
        level.playSound(null, at.x, at.y, at.z, Sounds.byId(BlockLand.id("equipment.smoke_pop")), SoundSource.PLAYERS, 1.6f, 1f);
        if (Params.b(params, "extinguish", true)) {
            for (Areas.Fire fire : Areas.firesUnder(smoke)) Molotov.extinguish(level, fire.entity());
        }
        return ticks;
    }
}
