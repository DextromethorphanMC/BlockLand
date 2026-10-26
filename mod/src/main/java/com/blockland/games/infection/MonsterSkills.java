package com.blockland.games.infection;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.entity.EffectPayload;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.Map;

/**
 * What a monster skill does, by its {@code type} in monsters.json. New kinds of skills are one more entry here;
 * the game only asks to use "the monster's skill".
 */
final class MonsterSkills {
    /** One kind of skill. */
    interface Kind {
        void use(ServerPlayer player, MonsterCatalog.Skill skill, MonsterBlasts blasts);
    }

    private static final Map<String, Kind> KINDS = Map.of("effect", (p, s, b) -> effect(p, s), "projectile", (p, s, b) -> projectile(p, s),
            "self_destruct", (p, s, b) -> b.lightFuse(p, s), "fireball", (p, s, b) -> b.fireball(p, s));

    private MonsterSkills() {
    }

    /**
     * @param blasts the match's monster explosions (self_destruct lights a fuse there, fireball launches one)
     * @return false when the skill's type is unknown (nothing happens)
     */
    static boolean use(ServerPlayer player, MonsterCatalog.Skill skill, MonsterBlasts blasts) {
        Kind kind = KINDS.get(skill.type);
        if (kind == null) {
            BlockLand.LOGGER.warn("[BlockLand] Unknown monster skill type {}", skill.type);
            return false;
        }
        kind.use(player, skill, blasts);
        ServerLevel level = player.level();
        ResourceLocation sound = ResourceLocation.tryParse(skill.sound);
        // registered or only in sounds.json (a monster's own sound added through assets)
        if (sound != null && !skill.sound.isEmpty()) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), Sounds.byId(sound), SoundSource.PLAYERS, 1.0F, 1.15F);
        }
        return true;
    }

    /**
     * Fires a projectile entity along the monster's view (粘液喷吐: a slime shot); a projectile that carries an
     * effect puts the skill's effect on whatever it hits.
     */
    private static void projectile(ServerPlayer player, MonsterCatalog.Skill skill) {
        ResourceLocation id = ResourceLocation.tryParse(skill.projectile);
        if (id == null) return;
        ServerLevel level = player.level();
        BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(type -> type.create(level, EntitySpawnReason.TRIGGERED)).ifPresent(entity -> {
            if (!(entity instanceof Projectile shot)) return;
            shot.setOwner(player);
            shot.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
            shot.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, Math.max(0.2f, skill.speed), 0.6f);
            ResourceLocation effect = ResourceLocation.tryParse(skill.effect);
            if (shot instanceof EffectPayload payload && effect != null) {
                payload.setPayload(effect, Math.max(1, Math.round(skill.duration * 20)), Math.max(0, skill.amplifier));
            }
            level.addFreshEntity(shot);
        });
    }

    /** A timed mob effect on the monster itself (狂暴: Speed III). */
    private static void effect(ServerPlayer player, MonsterCatalog.Skill skill) {
        ResourceLocation id = ResourceLocation.tryParse(skill.effect);
        if (id == null) return;
        BuiltInRegistries.MOB_EFFECT.get(id).ifPresent(effect -> player.addEffect(
                new MobEffectInstance(effect, Math.max(1, Math.round(skill.duration * 20)), Math.max(0, skill.amplifier), false, true, true)));
        player.level().sendParticles(ParticleTypes.ANGRY_VILLAGER, player.getX(), player.getY() + 1.9, player.getZ(), 6, 0.35, 0.2, 0.35, 0);
    }
}
