package com.blockland.client.thirdperson;

import com.blockland.craft.Build;
import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentItem;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunState;
import com.blockland.melee.KnifeItem;
import com.blockland.melee.MeleeContent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code blockland:weapon} special item model (asset spec §37): draws the weapon of the stack — its build's model,
 * attachments, skin and sprays — fitted into the box of the held model ({@code fit}), so the held model's display
 * transforms put it in the hand. Optional tuning in the item definition, in held-model units: {@code offset} (pixels),
 * {@code rotation} (degrees, applied to the weapon first) and {@code scale} (a multiplier on the fit).
 */
final class WeaponItemModel implements SpecialModelRenderer<WeaponItemModel.Key> {
    /** What to draw for a stack: the weapon and its build (as stored on the item). */
    record Key(String weaponId, Build build) {
    }

    /** The item definition's {@code model} object. */
    record Unbaked(Optional<ResourceLocation> fit, Vector3f offset, Vector3f rotation, float scale)
            implements SpecialModelRenderer.Unbaked {
        static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("fit").forGetter(Unbaked::fit),
                ExtraCodecs.VECTOR3F.optionalFieldOf("offset", new Vector3f()).forGetter(Unbaked::offset),
                ExtraCodecs.VECTOR3F.optionalFieldOf("rotation", new Vector3f()).forGetter(Unbaked::rotation),
                Codec.FLOAT.optionalFieldOf("scale", 1f).forGetter(Unbaked::scale)
        ).apply(instance, Unbaked::new));

        @Override
        public SpecialModelRenderer<?> bake(EntityModelSet models) {
            return new WeaponItemModel(this);
        }

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }
    }

    private final Unbaked data;

    private WeaponItemModel(Unbaked data) {
        this.data = data;
    }

    @Nullable
    @Override
    public Key extractArgument(ItemStack stack) {
        if (stack.getItem() instanceof GunItem gun) {
            GunState state = GunContent.stateOf(stack);
            return state == null ? null : new Key(gun.gunId(), state.build());
        }
        if (stack.getItem() instanceof KnifeItem knife) {
            Build build = MeleeContent.buildOf(stack);
            return build == null ? null : new Key(knife.knifeId(), build);
        }
        if (stack.getItem() instanceof EquipmentItem item) {
            Build build = EquipmentContent.buildOf(stack);
            return build == null ? null : new Key(item.equipmentId(), build);
        }
        return null;
    }

    @Override
    public void render(@Nullable Key key, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light,
                       int overlay, boolean foil) {
        if (key != null) ThirdPersonDraw.draw(key, data, context, pose, buffers, light);
    }

    /** The held model's box (what the weapon is fitted into): the item's size on the ground, in frames, in the GUI. */
    @Override
    public void getExtents(Set<Vector3f> out) {
        HeldBox box = HeldBox.of(data.fit().orElse(null));
        for (int i = 0; i < 8; i++) {
            out.add(new Vector3f((i & 1) == 0 ? box.min().x() : box.max().x(), (i & 2) == 0 ? box.min().y() : box.max().y(),
                    (i & 4) == 0 ? box.min().z() : box.max().z()).div(16));
        }
    }
}
