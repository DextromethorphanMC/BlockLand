package com.blockland.equipment.effect;

import com.blockland.BlockLand;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/** Damage types of equipment effects (data/blockland/damage_type) and the item ids kills are credited to. */
public final class EffectDamage {
    public static final ResourceKey<DamageType> FRAG = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("frag"));
    public static final ResourceKey<DamageType> MOLOTOV = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("molotov"));
    public static final String FRAG_ITEM = "blockland:frag_grenade";
    public static final String MOLOTOV_ITEM = "blockland:molotov";

    private EffectDamage() {
    }

    /** @param direct the thrown item, @param owner who threw it (the kill is theirs) */
    public static DamageSource source(ServerLevel level, ResourceKey<DamageType> type, @Nullable Entity direct, @Nullable Entity owner) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type), direct, owner);
    }
}
