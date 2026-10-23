package com.blockland.effect;

import com.blockland.BlockLand;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * BlockLand's status effects. They are ordinary mob effects, so the server applies them, vanilla syncs them to the
 * affected player and both sides can ask {@link ActionLocks} what they block.
 */
public final class BLEffects {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, BlockLand.MODID);

    /** 粘液缠身: covered in slime, the hands cannot attack or use anything (left and right click). */
    public static final RegistryObject<MobEffect> SLIMED = EFFECTS.register("slimed", () -> new Effect(MobEffectCategory.HARMFUL, 0x6BD13A));
    /** 肾上腺素: faster movement (and a slow heal) after an injection; the client shows the rush. */
    public static final RegistryObject<MobEffect> ADRENALINE = EFFECTS.register("adrenaline", () -> new AdrenalineEffect(false));
    /** 疲劳: the short slowdown when the adrenaline wears off. */
    public static final RegistryObject<MobEffect> ADRENALINE_FATIGUE = EFFECTS.register("adrenaline_fatigue", () -> new AdrenalineEffect(true));

    private BLEffects() {
    }

    public static void register(BusGroup modBus) {
        EFFECTS.register(modBus);
    }

    public static Holder<MobEffect> slimed() {
        return SLIMED.getHolder().orElseThrow();
    }

    public static Holder<MobEffect> adrenaline() {
        return ADRENALINE.getHolder().orElseThrow();
    }

    public static Holder<MobEffect> adrenalineFatigue() {
        return ADRENALINE_FATIGUE.getHolder().orElseThrow();
    }

    /** A plain effect: its meaning lives in the code that checks for it. */
    private static final class Effect extends MobEffect {
        Effect(MobEffectCategory category, int color) {
            super(category, color);
        }
    }
}
