package com.blockland.equipment.effect;

import com.blockland.effect.AdrenalineEffect;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * What equipment does when it goes off (server side): the frag's blast, the flashbang's blinding, the smoke cloud,
 * the molotov's fire, the adrenaline shot. The equipment module (items, throwing, the thrown entity) calls in here and
 * knows nothing about the effects; the effects only see vanilla types and the item's {@code effect} parameters
 * (equipment.json). Contract: shared notes, round-i-contracts.md.
 */
public final class EquipmentEffects {
    private EquipmentEffects() {
    }

    /**
     * The thrown item went off at its position.
     *
     * @param kind   equipment kind ({@code frag}, {@code flash}, {@code smoke}, {@code molotov})
     * @param params the item's {@code effect} object
     * @return ticks the thrown entity stays as an active area (smoke, fire), 0 = remove it now
     */
    public static int detonate(ServerLevel level, Entity thrown, String kind, JsonObject params, @Nullable LivingEntity owner) {
        return switch (kind) {
            case "frag" -> {
                FragBlast.explode(level, thrown, params, owner);
                yield 0;
            }
            case "flash" -> {
                Flashbang.burst(level, thrown, params, owner);
                yield 0;
            }
            case "smoke" -> Smoke.start(level, thrown, params);
            case "molotov" -> Molotov.ignite(level, thrown, params, owner);
            default -> 0;
        };
    }

    /** Every server tick while the thrown entity is an active area; {@code ticksLeft} counts down to 0. */
    public static void tickActive(ServerLevel level, Entity thrown, String kind, JsonObject params, @Nullable LivingEntity owner,
                                  int ticksLeft) {
        if ("molotov".equals(kind)) Molotov.tick(level, thrown, params, owner, ticksLeft);
    }

    /** A used (not thrown) item took effect, e.g. the adrenaline injection reaching its {@code inject} point. */
    public static void use(ServerPlayer player, String kind, JsonObject params) {
        if ("adrenaline".equals(kind)) {
            AdrenalineEffect.apply(player, Params.f(params, "duration", 8), Params.f(params, "speed", 0.35f), Params.f(params, "regen", 4),
                    Params.f(params, "fatigue", 2), Params.f(params, "fatigueSlow", 0.15f));
        }
    }
}
