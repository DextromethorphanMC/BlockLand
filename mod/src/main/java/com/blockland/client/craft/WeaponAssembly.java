package com.blockland.client.craft;

import com.blockland.BlockLand;
import com.blockland.client.gltf.ClipSampler;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.spray.SprayDecals;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A crafted weapon put together for drawing outside the viewmodel (the 工匠 preview, third person): the build's model (a
 * special skin's own, spec §30) at its idle clip's first frame, the nodes the build hides, the attachment models at their
 * mounts and the skin. The pose is computed once; drawing only multiplies matrices and queues meshes.
 */
public final class WeaponAssembly {
    /**
     * One model's meshes to draw: the weapon's own, or an attachment's at its mount (with its slot's skin region, and the
     * mount's rest position as its effect origin so skin effects run on across the joint, spec §30).
     */
    public record Part(GpuModel model, Pose pose, boolean[] hidden, int root, String region, Vector3fc effectOrigin) {
        /** A mesh node of the part's root subtree that the build does not hide. */
        public boolean drawn(int index) {
            GltfModel src = model.source;
            GltfModel.Node node = src.nodes.get(index);
            if (node.mesh < 0 || hidden[index]) return false;
            return root < 0 || index == root || src.isUnder(index, root);
        }
    }

    public final Craftable weapon;
    public final Build build;
    /** The weapon's model (the first part's). */
    public final GpuModel model;
    /** The weapon first, then its attachments. */
    public final List<Part> parts;
    public final GpuModel.Style style;
    @Nullable
    public final CraftData.Skin skin;
    /** The model's own space → the space the parts are posed in (identity, or into the drawn root's space). */
    private final Matrix4f toPoseSpace;
    private final Matrix4f scratch = new Matrix4f();

    private WeaponAssembly(Craftable weapon, Build build, GpuModel model, List<Part> parts, Matrix4f toPoseSpace) {
        this.weapon = weapon;
        this.build = build;
        this.model = model;
        this.parts = parts;
        this.skin = WeaponLook.skinOf(weapon, build);
        this.style = WeaponLook.style(skin);
        this.toPoseSpace = toPoseSpace;
    }

    /**
     * Puts a build together; null when its model is not available.
     *
     * @param build     a completed build ({@code CraftCatalog.complete})
     * @param model     the model to draw (normally {@link WeaponLook#modelOf}; the weapon's own for a fit)
     * @param rootName  the root subtree to draw: null = the weapon's own ({@code gun}, {@code knife} …), or a dual gun's
     *                  left copy ({@code gun_L}, spec §26) whose attachments sit at the mounts' twins with that suffix
     * @param rootSpace pose everything in the drawn root's own space (the weapon square to its axes, pivot at the origin)
     *                  instead of the model's space (where the root holds its viewmodel place)
     */
    @Nullable
    public static WeaponAssembly of(Craftable weapon, Build build, @Nullable GpuModel model, @Nullable String rootName, boolean rootSpace) {
        if (model == null) return null;
        GltfModel source = model.source;
        String rootId = rootName == null ? weapon.modelRoot() : rootName;
        int weaponRoot = source.node(rootId);
        if (weaponRoot < 0) return null;
        String suffix = rootId.startsWith(weapon.modelRoot()) ? rootId.substring(weapon.modelRoot().length()) : "";
        Pose pose = new Pose(source);
        GltfModel.Animation idle = source.animation("idle");
        if (idle != null) new ClipSampler(idle).sample(0, pose);
        Matrix4f toPoseSpace = new Matrix4f();
        pose.computeWorld(toPoseSpace);
        if (rootSpace) {
            pose.world[weaponRoot].invert(toPoseSpace);
            pose.computeWorld(toPoseSpace);
        }
        boolean[] hidden = WeaponLook.hiddenNodes(source, weapon, build, -1);

        List<Part> parts = new ArrayList<>();
        parts.add(new Part(model, pose, hidden, weaponRoot, "", new Vector3f()));
        for (ViewmodelRig.Attachment attachment : WeaponLook.attachments(weapon, build)) {
            int mount = source.node(attachment.mount() + suffix);
            GpuModel att = mount < 0 || hidden[mount] ? null : ModelCache.get(attachment.model());
            if (att == null) continue;
            Pose attPose = new Pose(att.source);
            attPose.computeWorld(pose.world[mount]);
            parts.add(new Part(att, attPose, new boolean[att.source.nodes.size()], att.source.node("attachment"), attachment.region(),
                    model.restOrigin(mount)));
        }
        return new WeaponAssembly(weapon, build, model, parts, toPoseSpace);
    }

    /** {@link #of} with the build's own model (a special skin's, else the weapon's). */
    @Nullable
    public static WeaponAssembly of(Craftable weapon, Build build, @Nullable String rootName, boolean rootSpace) {
        GpuModel model = ModelCache.get(WeaponLook.modelOf(weapon, build));
        if (model == null) model = ModelCache.get(BlockLand.id(weapon.model()));
        return of(weapon, build, model, rootName, rootSpace);
    }

    /** The skin has shader effects (they move, so a cached picture of it would freeze them). */
    public boolean animated() {
        return skin != null && skin.hasEffects();
    }

    /** Extends {@code min} / {@code max} by every drawn vertex, in the pose space ({@code transform} applied first). */
    public void bounds(Matrix4fc transform, boolean attachments, Vector3f min, Vector3f max) {
        Vector3f p = new Vector3f();
        Matrix4f m = new Matrix4f();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0 && !attachments) break;
            Part part = parts.get(i);
            GltfModel src = part.model.source;
            for (int index : src.order) {
                if (!part.drawn(index)) continue;
                m.set(transform).mul(part.pose.world[index]);
                for (GltfModel.Primitive primitive : src.meshes.get(src.nodes.get(index).mesh).primitives) {
                    float[] pos = primitive.positions;
                    for (int v = 0; v < pos.length; v += 3) {
                        m.transformPosition(pos[v], pos[v + 1], pos[v + 2], p);
                        min.min(p);
                        max.max(p);
                    }
                }
            }
        }
    }

    /**
     * Queues the weapon (and its attachments when {@code attachments}) with {@code view} mapping the pose space to view
     * space (or to whatever space the list is drawn in).
     */
    public void draw(DrawList list, Matrix4fc view, int blockLight, int skyLight, boolean attachments) {
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0 && !attachments) break;
            Part part = parts.get(i);
            GltfModel src = part.model.source;
            for (int index : src.order) {
                if (!part.drawn(index)) continue;
                scratch.set(view).mul(part.pose.world[index]);
                part.model.addNode(list, index, scratch, style, part.region, part.effectOrigin, blockLight, skyLight, 1);
            }
        }
    }

    /** Queues the build's sprays at the rest pose (spec §29; the weapon's own root only). */
    public void drawSprays(DrawList list, Matrix4fc view, int blockLight, int skyLight) {
        SprayDecals sprays = SprayDecals.of(weapon, build);
        if (sprays.madeFor(model)) sprays.drawRest(list, scratch.set(view).mul(toPoseSpace), blockLight, skyLight);
    }
}
