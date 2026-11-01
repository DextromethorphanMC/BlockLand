package com.blockland.client.craft;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.MaterialLayer;
import com.blockland.client.render.TextureRef;
import com.blockland.client.viewmodel.SupportGrip;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a crafted weapon looks, for the viewmodels and the craft preview alike: which of the weapon's own nodes show
 * (the fitted magazine, parts a slot hides), which attachment models sit at which mount, and the skin — its layers
 * re-texture regions of the weapon and of the attachments (asset spec §21; an attachment's materials belong to its
 * slot's region unless they name one), its older per-material colours and textures apply where the materials match.
 */
public final class WeaponLook {
    private static final String UNDERBARREL = "underbarrel";
    private static final Map<CraftData.Skin, GpuModel.Style> styles = new IdentityHashMap<>();

    private WeaponLook() {
    }

    /**
     * Per node of the weapon model: hidden by the build or because an ancestor is.
     *
     * @param build    a completed build ({@link CraftCatalog#complete})
     * @param keepRoot only this root's subtree is drawn (e.g. {@code viewmodel}); -1 = all roots
     */
    /**
     * Nodes whose name starts with this glow only while their item is switched on (a flashlight's lens): hidden here;
     * the first-person viewmodel shows them while the held stack is lit (asset spec §36).
     */
    public static final String LIT_PREFIX = "lit_";

    public static boolean[] hiddenNodes(GltfModel source, Craftable weapon, Build build, int keepRoot) {
        Set<String> hide = new HashSet<>();
        for (Craftable.Slot slot : weapon.slots()) {
            String option = build.option(slot.id(), slot.defaultOption());
            slot.optionNodes().forEach((id, node) -> {
                if (!id.equals(option)) hide.add(node);
            });
            if (!option.equals(slot.defaultOption())) hide.addAll(slot.hides());
        }
        // a fitted option's own node always shows, even when two options share a part name
        for (Craftable.Slot slot : weapon.slots()) {
            String shown = slot.optionNodes().get(build.option(slot.id(), slot.defaultOption()));
            if (shown != null) hide.remove(shown);
        }
        boolean[] hidden = new boolean[source.nodes.size()];
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            boolean outside = keepRoot >= 0 && node.parent < 0 && index != keepRoot;
            hidden[index] = hide.contains(node.name) || outside || node.name.startsWith(LIT_PREFIX)
                    || (node.parent >= 0 && hidden[node.parent]);
        }
        return hidden;
    }

    /**
     * The attachment models of a build, one per slot that holds a model attachment, keyed by slot id; their materials
     * belong to the slot's region (an optic's to {@code optic} …) unless the model names one.
     */
    public static List<ViewmodelRig.Attachment> attachments(Craftable weapon, Build build) {
        List<ViewmodelRig.Attachment> list = new ArrayList<>();
        for (Craftable.Slot slot : weapon.slots()) {
            if (slot.mount().isEmpty()) continue;
            CraftData.Attachment attachment = CraftCatalog.fitted(build, slot.id());
            if (attachment != null && attachment.hasModel()) {
                // asset spec §21: charms keep their own look, every other slot is a skin region of its own name
                String region = slot.id().equals("charm") ? CraftData.Skin.NO_REGION : slot.id();
                list.add(new ViewmodelRig.Attachment(slot.id(), slot.mount(), BlockLand.id(attachment.model), region));
            }
        }
        return list;
    }

    /**
     * The support hand's hold on the build's underbarrel attachment (asset spec §35: grips, bipod), at the slot's mount;
     * without one (or without a {@code grip} in its data) the clips keep the hand on the handguard.
     */
    public static void fitSupportHold(ViewmodelRig<?> rig, Craftable weapon, Build build) {
        String mount = weapon.slot(UNDERBARREL).map(Craftable.Slot::mount).orElse("");
        CraftData.Attachment attachment = mount.isEmpty() ? null : CraftCatalog.fitted(build, UNDERBARREL);
        CraftData.Grip grip = attachment == null ? null : attachment.grip;
        rig.setSupportHold(mount, grip == null || grip.wrist == null || grip.palm == null ? null
                : SupportGrip.Hold.of(grip.wrist, grip.palm, grip.fingers == null ? Map.of() : grip.fingers));
    }

    /** The skin of a build (the default one when it names an unknown skin). */
    @Nullable
    public static CraftData.Skin skinOf(Craftable weapon, Build build) {
        return weapon.skins().getOrDefault(build.skin(), weapon.skins().get("default"));
    }

    /**
     * The model a build is drawn with: a special skin's own (asset spec §30, same node contract), else the weapon's.
     */
    public static ResourceLocation modelOf(Craftable weapon, Build build) {
        CraftData.Skin skin = skinOf(weapon, build);
        return BlockLand.id(skin != null && skin.hasModel() ? skin.model.trim() : weapon.model());
    }

    /** Skin overrides (region layers, per-material colours and replacement textures); cached per skin. */
    public static GpuModel.Style style(@Nullable CraftData.Skin skin) {
        if (skin == null || skin.isEmpty()) return GpuModel.Style.NONE;
        return styles.computeIfAbsent(skin, s -> new GpuModel.Style() {
            /** Converted layers by region ("*" resolved per region). */
            private final Map<String, MaterialLayer> layers = new HashMap<>();

            @Override
            public float[] tint(String material) {
                return s.tints == null ? null : s.tints.get(material);
            }

            @Override
            public TextureRef textureRef(String material) {
                String path = s.textures == null ? null : s.textures.get(material);
                if (path == null) return null;
                try {
                    return TextureRef.resource(BlockLand.id(path));
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            public MaterialLayer layer(String material, String region) {
                CraftData.Layer layer = s.layer(region);
                return layer == null ? null : layers.computeIfAbsent(region, r -> convert(layer));
            }
        });
    }

    /** A skin layer as the renderer takes it: effect id, parsed colour, every parameter filled in. */
    static MaterialLayer convert(CraftData.Layer data) {
        int effect = MaterialLayer.effect(data.effect);
        float[] params = MaterialLayer.defaultParams(effect);
        if (data.params != null) System.arraycopy(data.params, 0, params, 0, Math.min(params.length, data.params.length));
        float[] tint = data.tint != null && data.tint.length >= 3 ? new float[]{data.tint[0], data.tint[1], data.tint[2]} : null;
        String pbr = data.pbr == null || data.pbr.isBlank() ? null : data.pbr.trim();
        return new MaterialLayer(pbr, Math.max(0, data.uvScale), tint, effect, color(data.effectColor), params);
    }

    /** {@code #RRGGBB} as display RGB 0..1, or null. */
    @Nullable
    static float[] color(@Nullable String hex) {
        if (hex == null) return null;
        String digits = hex.trim().startsWith("#") ? hex.trim().substring(1) : hex.trim();
        if (digits.length() != 6) return null;
        try {
            int rgb = Integer.parseInt(digits, 16);
            return new float[]{(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
