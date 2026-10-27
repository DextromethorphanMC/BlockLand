package com.blockland.games.infection;

import com.blockland.common.Sounds;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.Optional;
import java.util.UUID;

/**
 * Monster skills that work on the bodies around a monster, data-driven by monsters.json: the {@code heal_allies} skill
 * (瞎子: every infected ally within {@code radius} blocks gets {@code heal} health back, with a burst of light round the
 * monster and hearts over whoever was healed) and the {@code blind_aura} passive (瞎子: every enemy whose body comes
 * within {@code radius} blocks of the monster's goes blind for {@code duration} seconds, renewed while it stays close).
 * Server-authoritative; the game supplies who is an ally or an enemy through {@link Rules}.
 */
final class MonsterAuras {
    /** Ticks between two looks round an aura's monster (the effect lasts longer, so it never flickers off). */
    private static final int INTERVAL = 4;

    /** What the game decides. */
    interface Rules {
        /** Whether {@code other} fights on {@code monster}'s side right now (alive, in the round). */
        boolean isAlly(UUID monster, LivingEntity other);

        /** Whether {@code other} fights against {@code monster} right now. */
        boolean isEnemy(UUID monster, LivingEntity other);
    }

    private final Rules rules;

    MonsterAuras(Rules rules) {
        this.rules = rules;
    }

    /** The heal_allies skill: heals the allies within the skill's radius (and the monster itself when healSelf). */
    void healAllies(ServerPlayer monster, MonsterCatalog.Skill skill) {
        ServerLevel level = monster.level();
        float radius = Math.max(0.5f, skill.radius);
        AABB area = monster.getBoundingBox().inflate(radius);
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            boolean self = other == monster;
            if (self ? !skill.healSelf : !rules.isAlly(monster.getUUID(), other)) continue;
            if (other.distanceToSqr(monster) > radius * radius) continue;
            other.heal(Math.max(0, skill.heal));
            level.sendParticles(ParticleTypes.HEART, other.getX(), other.getY() + other.getBbHeight() + 0.3, other.getZ(),
                    4, 0.3, 0.15, 0.3, 0);
        }
        // the burst: a ring of light out to the skill's reach
        int points = Mth.ceil(radius * 10);
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points;
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, monster.getX() + Math.cos(angle) * radius, monster.getY() + 0.2,
                    monster.getZ() + Math.sin(angle) * radius, 1, 0.05, 0.05, 0.05, 0);
        }
        level.sendParticles(ParticleTypes.GLOW, monster.getX(), monster.getY() + 1.0, monster.getZ(), 14, 0.6, 0.5, 0.6, 0.02);
    }

    /**
     * Every tick for each monster in the round that has a passive: a {@code blind_aura} blinds the enemies close to it
     * (other passive types are someone else's and pass through).
     */
    void tick(ServerPlayer monster, MonsterCatalog.Skill passive, int now) {
        if (!"blind_aura".equals(passive.type) || Math.floorMod(now + monster.getId(), INTERVAL) != 0) return;
        Optional<Holder.Reference<MobEffect>> effect = Optional.ofNullable(ResourceLocation.tryParse(passive.effect))
                .flatMap(BuiltInRegistries.MOB_EFFECT::get);
        if (effect.isEmpty()) return;
        ServerLevel level = monster.level();
        AABB reach = monster.getBoundingBox().inflate(Math.max(0, passive.radius));
        int ticks = Math.max(INTERVAL * 3, Math.round(passive.duration * 20));
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, reach,
                e -> e != monster && e.isAlive() && rules.isEnemy(monster.getUUID(), e))) {
            boolean fresh = !other.hasEffect(effect.get());
            other.addEffect(new MobEffectInstance(effect.get(), ticks, Math.max(0, passive.amplifier), false, false, true), monster);
            if (fresh && !passive.sound.isEmpty()) {
                ResourceLocation sound = ResourceLocation.tryParse(passive.sound);
                if (sound != null) {
                    level.playSound(null, other.getX(), other.getEyeY(), other.getZ(), Sounds.byId(sound), SoundSource.HOSTILE, 0.8F, 1.0F);
                }
            }
        }
    }
}
