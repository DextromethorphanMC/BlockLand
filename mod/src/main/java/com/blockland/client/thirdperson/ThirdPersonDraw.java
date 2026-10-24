package com.blockland.client.thirdperson;

import com.blockland.BlockLand;
import com.blockland.client.craft.WeaponAssembly;
import com.blockland.client.craft.WeaponLook;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ShadowPass;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.Craftable;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws one weapon item outside the GUI and first person (asset spec §37). The weapon is posed in its root's own space
 * (square to its axes, pivot at the origin) and fitted into the held model's box, so wherever vanilla places the held
 * model — a hand, the ground, a frame, a head — the 3D weapon sits. It is drawn right away with the
 * weapon pipeline into the target the item is being drawn into (the world's frame, or a GUI entity picture), lit by
 * the world like the viewmodel; while the sun's shadow map captures entities ({@link ShadowPass}) its triangles go
 * through vanilla render types into the given buffers instead, so it casts a shadow like every other entity.
 */
final class ThirdPersonDraw {
    /** Beyond this distance (blocks) attachments are left out. */
    private static final float DETAIL_DISTANCE = 48;
    /** Sprays are drawn only this close (their decal models are cached for a few builds only). */
    private static final float SPRAY_DISTANCE = 16;
    private static final int CACHE = 48;

    /** A build's assembly in one root's space: the drawn model is kept to notice a resource reload. */
    private record Entry(WeaponAssembly assembly, ResourceLocation model) {
    }

    private record Look(String weaponId, Build build, String root) {
    }

    private record FitKey(String weaponId, String root, WeaponItemModel.Unbaked data) {
    }

    private static final DrawList draws = new DrawList().scene(true);
    private static final Map<Look, Entry> looks = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Look, Entry> eldest) {
            return size() > CACHE;
        }
    };
    private static final Map<FitKey, Matrix4f> fits = new HashMap<>();
    private static final Matrix4f into = new Matrix4f();
    private static final Vector3f scratch = new Vector3f();

    private ThirdPersonDraw() {
    }

    static void draw(WeaponItemModel.Key key, WeaponItemModel.Unbaked data, ItemDisplayContext context, PoseStack pose,
                     MultiBufferSource buffers, int light) {
        Craftable weapon = CraftCatalog.get(key.weaponId()).orElse(null);
        if (weapon == null) return;
        // a dual gun's left hand holds the left gun (its own mirrored copy, spec §26)
        String root = weapon.modelRoot();
        WeaponAssembly assembly = null;
        Matrix4f fit = null;
        GpuModel own = ModelCache.get(BlockLand.id(weapon.model()));
        if (context.leftHand() && own != null && own.source.node(root + "_L") >= 0) {
            assembly = assembly(weapon, key.build(), root + "_L");
            if (assembly != null) {
                root = root + "_L";
                fit = fit(weapon, root, data);
            }
        }
        if (assembly == null) {
            assembly = assembly(weapon, key.build(), root);
            fit = fit(weapon, root, data);
        }
        if (assembly == null || fit == null) return;

        Matrix4f item = pose.last().pose();
        // in the world the pose is camera-relative; a GUI picture (the inventory's player) always gets every detail
        float distance = RenderSystem.outputColorTextureOverride != null ? 0 : item.getTranslation(scratch).length();
        int block = LightTexture.block(light), sky = LightTexture.sky(light);
        // while the sun's shadow map captures entities: through the given buffers only
        boolean vanilla = ShadowPass.active();
        if (vanilla) into.set(fit);
        else into.set(RenderSystem.getModelViewMatrix()).mul(item).mul(fit);
        assembly.draw(draws, into, block, sky, distance < DETAIL_DISTANCE);
        if (distance < SPRAY_DISTANCE && root.equals(weapon.modelRoot())) assembly.drawSprays(draws, into, block, sky);
        if (vanilla) {
            draws.flushVanilla(pose, buffers);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null) {
            draws.clear();
            return;
        }
        draws.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), "BlockLand third-person weapon");
    }

    /** The build's assembly in the root's space (cached; rebuilt when the model was reloaded), or null. */
    @Nullable
    private static WeaponAssembly assembly(Craftable weapon, Build stored, String root) {
        Look look = new Look(weapon.id(), stored, root);
        Entry entry = looks.get(look);
        if (entry != null && ModelCache.get(entry.model) == entry.assembly.model) return entry.assembly;
        Build build = CraftCatalog.complete(weapon, stored);
        ResourceLocation id = WeaponLook.modelOf(weapon, build);
        GpuModel model = ModelCache.get(id);
        if (model == null) {
            id = BlockLand.id(weapon.model());
            model = ModelCache.get(id);
        }
        WeaponAssembly assembly = WeaponAssembly.of(weapon, build, model, root, true);
        if (assembly == null) {
            looks.remove(look);
            return null;
        }
        looks.put(look, new Entry(assembly, id));
        return assembly;
    }

    /**
     * Root space → the held model's units (1 = one block): the weapon's default build scaled uniformly so its extent
     * along the held box's longest axis matches the box, and centred in it; a special skin's model (same node contract)
     * uses its weapon's fit, so it keeps its own size against the original.
     */
    @Nullable
    private static Matrix4f fit(Craftable weapon, String root, WeaponItemModel.Unbaked data) {
        FitKey key = new FitKey(weapon.id(), root, data);
        Matrix4f fit = fits.get(key);
        if (fit != null) return fit;
        GpuModel own = ModelCache.get(BlockLand.id(weapon.model()));
        WeaponAssembly plain = WeaponAssembly.of(weapon, CraftCatalog.complete(weapon, weapon.defaultBuild()), own, root, true);
        if (plain == null) return null;
        Matrix4f turn = new Matrix4f().rotationXYZ((float) Math.toRadians(data.rotation().x()), (float) Math.toRadians(data.rotation().y()),
                (float) Math.toRadians(data.rotation().z()));
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
        plain.bounds(turn, false, min, max);
        if (min.x > max.x) return null;
        HeldBox box = HeldBox.of(data.fit().orElse(BlockLand.id("item/" + weapon.id() + "_held")));
        Vector3f held = box.size(new Vector3f());
        Vector3f size = new Vector3f(max).sub(min);
        int axis = held.maxComponent();
        float scale = held.get(axis) / 16f / Math.max(1e-4f, size.get(axis)) * data.scale();
        Vector3f centre = box.centre(new Vector3f()).add(data.offset()).div(16);
        Vector3f middle = new Vector3f(min).add(max).mul(0.5f);
        fit = new Matrix4f().translation(centre).scale(scale).translate(-middle.x, -middle.y, -middle.z).mul(turn);
        fits.put(key, fit);
        return fit;
    }

    /** Resource reload: models and held boxes may have changed. */
    static void clear() {
        looks.clear();
        fits.clear();
        HeldBox.clear();
    }
}
