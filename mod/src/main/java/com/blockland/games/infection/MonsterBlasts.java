package com.blockland.games.infection;

import com.blockland.BlockLand;
import com.blockland.combat.HitRecords;
import com.blockland.common.Sounds;
import com.blockland.entity.BLEntities;
import com.blockland.entity.SkillFireball;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Monster explosions of one match, data-driven by the skills in monsters.json: the {@code self_destruct} skill (a
 * fuse — the monster slowed, swelling and flashing on everyone's screen — then a blast that takes the monster with it),
 * the {@code death_blast} passive (a monster that dies goes off after a short delay, also when it is killed during
 * its fuse) and the {@code fireball} skill (a vanilla ghast fireball that blows up where it lands). A blast hurts only the monster's enemies, with the damage falling off from {@code innerRadius} to
 * {@code radius} and scaled by how much of each body it can see (cover protects, nothing goes through walls); the
 * kill is the monster player's. It never touches a block. The game supplies the rules (who is an enemy, what dying
 * means, who watches) through {@link Rules}.
 */
final class MonsterBlasts {
    /** Damage type of monster blasts (data/blockland/damage_type/monster_blast.json). */
    static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("monster_blast"));
    /** What kills by a monster blast are credited to (kill feed / hit records). */
    static final String WEAPON = "blockland:monster_blast";
    /** What fireball kills are credited to (an item, so the kill feed names it). */
    static final String FIREBALL_WEAPON = "minecraft:fire_charge";
    private static final String EXPLODE_SOUND = "blockland:equipment.frag_explode";
    private static final String FIREBALL_SOUND = "minecraft:entity.generic.explode";

    /** What the game decides. */
    interface Rules {
        /** Whether a blast set off by {@code monster} hurts {@code victim}. */
        boolean isEnemy(UUID monster, LivingEntity victim);

        /** The monster blew itself up: it dies (no death blast of its own follows). */
        void selfDestructed(ServerPlayer monster);

        /** Tell everyone watching: {@code player} is burning a fuse of {@code seconds} (0 = put out). */
        void fuse(UUID player, float seconds);

        /** Tell everyone watching: a blast of {@code radius} went off at {@code at} (the client's fire and smoke). */
        void blasted(Vec3 at, float radius);
    }

    private record Fuse(MonsterCatalog.Skill skill, int at) {
    }

    private record Delayed(ServerLevel level, Vec3 centre, UUID owner, MonsterCatalog.Skill skill, int at) {
    }

    private final Rules rules;
    private final Map<UUID, Fuse> fuses = new HashMap<>();
    private final List<Delayed> delayed = new ArrayList<>();
    /** Monsters dying by their own blast right now: their death sets nothing more off. */
    private final Set<UUID> selfDestructing = new HashSet<>();
    /** Fireballs in flight. */
    private final List<SkillFireball> fireballs = new ArrayList<>();

    MonsterBlasts(Rules rules) {
        this.rules = rules;
    }

    // ---- the skill and the passive --------------------------------------------------------------------

    /** The {@code self_destruct} skill: light the fuse (slowed by the skill's effect while it burns). */
    void lightFuse(ServerPlayer player, MonsterCatalog.Skill skill) {
        if (fuses.containsKey(player.getUUID())) return;
        int ticks = Math.max(1, Math.round(skill.fuse * 20));
        fuses.put(player.getUUID(), new Fuse(skill, player.level().getServer().getTickCount() + ticks));
        ResourceLocation effect = ResourceLocation.tryParse(skill.effect);
        if (effect != null && !skill.effect.isEmpty()) {
            BuiltInRegistries.MOB_EFFECT.get(effect).ifPresent(e -> player.addEffect(
                    new MobEffectInstance(e, ticks, Math.max(0, skill.amplifier), false, false, true)));
        }
        rules.fuse(player.getUUID(), skill.fuse);
    }

    /**
     * The {@code fireball} skill: a vanilla ghast fireball from the monster's eyes along its view, launched at the
     * skill's {@code speed} and speeding up as it flies. It passes through everyone who is not its owner's enemy and
     * blows up on the first enemy or block it meets (a punch can send it back: then it is the puncher's, and their
     * enemies get the blast), or fizzles out after its {@code lifetime}.
     */
    void fireball(ServerPlayer player, MonsterCatalog.Skill skill) {
        ServerLevel level = player.level();
        SkillFireball ball = BLEntities.SKILL_FIREBALL.get().create(level, EntitySpawnReason.TRIGGERED);
        if (ball == null) return;
        Vec3 look = player.getLookAngle();
        // the 1-block ball centred a little in front of the eyes
        ball.setPos(player.getX() + look.x * 0.6, player.getEyeY() - 0.5 + look.y * 0.6, player.getZ() + look.z * 0.6);
        ball.setOwner(player);
        ball.setDeltaMovement(look.scale(Math.max(0.05f, skill.speed)));
        ball.setLifetime(Math.round(skill.lifetime * 20));
        ball.setImpact(new SkillFireball.Impact() {
            @Override
            public boolean canHit(SkillFireball b, Entity target) {
                Entity owner = b.getOwner();
                return owner == null || !(target instanceof LivingEntity living) || rules.isEnemy(owner.getUUID(), living);
            }

            @Override
            public void land(SkillFireball b, Vec3 at, @Nullable Entity hit) {
                fireballs.remove(b);
                Entity owner = b.getOwner();
                if (owner == null) return;
                explode(level, at, owner.getUUID(), owner instanceof ServerPlayer p ? p : null, skill, FIREBALL_WEAPON, FIREBALL_SOUND);
            }
        });
        fireballs.add(ball);
        level.addFreshEntity(ball);
    }

    /**
     * A monster died (not by falling out of the world): a burning fuse is put out, and a {@code death_blast} passive
     * goes off at the spot after its delay.
     */
    void onDeath(ServerPlayer player, @Nullable MonsterCatalog.Monster monster, boolean fell) {
        UUID id = player.getUUID();
        if (fuses.remove(id) != null) rules.fuse(id, 0);
        if (selfDestructing.contains(id) || fell || monster == null || monster.passive == null) return;
        MonsterCatalog.Skill passive = monster.passive;
        if (!"death_blast".equals(passive.type)) return;
        ServerLevel level = player.level();
        playSound(level, player.position(), passive.sound, 1.0f, 1.5f);
        int at = level.getServer().getTickCount() + Math.max(0, Math.round(passive.fuse * 20));
        delayed.add(new Delayed(level, player.position().add(0, 0.9, 0), id, passive, at));
    }

    /** Every server tick of the fight: fuses that burned down go off (the monster with them), then delayed blasts. */
    void tick(int now, Function<UUID, ServerPlayer> online) {
        fireballs.removeIf(Entity::isRemoved);
        for (Iterator<Map.Entry<UUID, Fuse>> it = fuses.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Fuse> e = it.next();
            if (now < e.getValue().at) continue;
            it.remove();
            rules.fuse(e.getKey(), 0);
            ServerPlayer player = online.apply(e.getKey());
            if (player == null || !player.isAlive()) continue;
            ServerLevel level = player.level();
            Vec3 centre = player.position().add(0, 0.9, 0);
            // the monster goes first: it is down whatever its blast does (infecting the last human ends the round, and
            // a round that ended no longer kills anyone), and its own death sets nothing more off
            selfDestructing.add(player.getUUID());
            try {
                rules.selfDestructed(player);
            } finally {
                selfDestructing.remove(player.getUUID());
            }
            explode(level, centre, e.getKey(), player, e.getValue().skill, WEAPON, EXPLODE_SOUND);
        }
        for (Iterator<Delayed> it = delayed.iterator(); it.hasNext(); ) {
            Delayed d = it.next();
            if (now < d.at) continue;
            it.remove();
            explode(d.level, d.centre, d.owner, online.apply(d.owner), d.skill, WEAPON, EXPLODE_SOUND);
        }
    }

    /** A player left or turned back into a human: their fuse is gone (a delayed blast still goes off). */
    void cancel(UUID player) {
        if (fuses.remove(player) != null) rules.fuse(player, 0);
    }

    /** The round is over: every fuse, pending blast and fireball in flight is dropped. */
    void clear() {
        for (UUID id : fuses.keySet()) rules.fuse(id, 0);
        fuses.clear();
        delayed.clear();
        for (SkillFireball ball : fireballs) ball.discard();
        fireballs.clear();
    }

    // ---- the blast ----------------------------------------------------------------------------------

    /** @param weapon what kills are credited to; @param sound the bang */
    private void explode(ServerLevel level, Vec3 centre, UUID ownerId, @Nullable ServerPlayer owner, MonsterCatalog.Skill skill,
                         String weapon, String sound) {
        float radius = Math.max(0.5f, skill.radius), inner = Math.min(radius, skill.innerRadius);
        playSound(level, centre, sound, 5f, 0.85f + level.random.nextFloat() * 0.12f);
        rules.blasted(centre, radius);
        DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DAMAGE), null, owner);
        AABB box = new AABB(centre, centre).inflate(radius);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && !e.isSpectator() && rules.isEnemy(ownerId, e))) {
            AABB body = victim.getBoundingBox();
            Vec3 nearest = new Vec3(Mth.clamp(centre.x, body.minX, body.maxX), Mth.clamp(centre.y, body.minY, body.maxY),
                    Mth.clamp(centre.z, body.minZ, body.maxZ));
            double distance = nearest.distanceTo(centre);
            if (distance > radius) continue;
            float exposure = exposure(level, centre, victim);
            if (exposure <= 0) continue;
            float t = distance <= inner ? 0 : (float) ((distance - inner) / Math.max(0.01, radius - inner));
            float amount = (skill.minDamage + (skill.damage - skill.minDamage) * (1 - t) * (1 - t)) * exposure;
            if (owner != null) HitRecords.record(victim, owner, weapon, false);
            victim.invulnerableTime = 0;
            victim.hurtServer(level, source, amount);
            Vec3 away = body.getCenter().subtract(centre);
            if (away.lengthSqr() < 1e-4) away = new Vec3(0, 1, 0);
            away = away.normalize();
            double push = skill.knockback * (1 - t) * exposure;
            victim.push(away.x * push, Math.max(away.y, 0.3) * push * 0.6 + 0.1 * push, away.z * push);
            if (victim instanceof ServerPlayer player) player.hurtMarked = true;
        }
    }

    /** Share (0, 1/3, 2/3, 1) of the eyes, middle and feet the blast has a clear line to. */
    private static float exposure(ServerLevel level, Vec3 centre, LivingEntity victim) {
        AABB body = victim.getBoundingBox();
        Vec3[] points = {victim.getEyePosition(), body.getCenter(), new Vec3(body.getCenter().x, body.minY + 0.15, body.getCenter().z)};
        int visible = 0;
        for (Vec3 point : points) {
            HitResult hit = level.clip(new ClipContext(centre, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));
            if (hit.getType() == HitResult.Type.MISS) visible++;
        }
        return visible / 3f;
    }

    private static void playSound(ServerLevel level, Vec3 at, String id, float volume, float pitch) {
        ResourceLocation sound = ResourceLocation.tryParse(id);
        if (sound == null || id.isEmpty()) return;
        level.playSound(null, at.x, at.y, at.z, Sounds.byId(sound), SoundSource.PLAYERS, volume, pitch);
    }
}
