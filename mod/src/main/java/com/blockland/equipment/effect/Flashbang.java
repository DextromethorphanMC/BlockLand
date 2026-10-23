package com.blockland.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.EffectPackets;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Flashbang: every player with a clear line from the burst to their eyes is blinded by how close it was and how
 * squarely they looked at it — fully when facing it, {@code behindFactor} when it went off behind them — and deafened
 * by the distance alone. The thrower is no exception. Each player gets their own strength; the white-out and the
 * ringing are drawn by their client.
 */
final class Flashbang {
    /** Below this a flash is not worth sending. */
    private static final float MIN_STRENGTH = 0.04f;

    private Flashbang() {
    }

    static void burst(ServerLevel level, Entity thrown, JsonObject params, @Nullable LivingEntity owner) {
        Vec3 centre = thrown.position().add(0, 0.2, 0);
        float radius = Params.f(params, "radius", 24), maxBlind = Params.f(params, "maxBlind", 4.5f), minBlind = Params.f(params, "minBlind", 0.4f);
        float behind = Params.f(params, "behindFactor", 0.25f), deafen = Params.f(params, "deafen", 3), ownerFactor = Params.f(params, "ownerFactor", 1);
        level.playSound(null, centre.x, centre.y, centre.z, Sounds.byId(BlockLand.id("equipment.flash_bang")), SoundSource.PLAYERS, 5f, 1f);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            Vec3 eye = player.getEyePosition();
            double distance = eye.distanceTo(centre);
            if (distance > radius) continue;
            HitResult hit = level.clip(new ClipContext(centre, eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrown));
            if (hit.getType() != HitResult.Type.MISS) continue;
            float near = (float) (1 - Math.pow(distance / radius, 1.6));
            double facing = distance < 1e-3 ? 1 : player.getLookAngle().dot(centre.subtract(eye).normalize());
            // full strength within ~57° of the look, down to `behind` straight behind
            float look = facing >= 0.55 ? 1 : facing <= -0.4 ? behind : behind + (1 - behind) * smooth((float) ((facing + 0.4) / 0.95));
            float strength = near * look * (player == owner ? ownerFactor : 1);
            if (strength < MIN_STRENGTH) continue;
            float seconds = minBlind + (maxBlind - minBlind) * strength;
            BLNetwork.sendTo(player, new EffectPackets.S2CFlash(Math.min(1, strength), seconds, deafen * near, centre.x, centre.y, centre.z));
        }
    }

    private static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
