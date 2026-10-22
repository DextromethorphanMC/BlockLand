package com.blockland.client.viewmodel;

import com.blockland.BlockLand;
import com.blockland.client.gltf.ClipSampler;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What every first-person weapon shares (asset spec §3 / §12): one GLB viewmodel with its clips, the player-skin
 * arms from {@code arms.glb}, the procedural {@link ViewmodelMotion} layer and a {@link DrawList}. Plays one
 * one-shot clip at a time over the looping idle with crossfades, blends the optional sprint loop locked to the
 * footsteps, reads the animated {@code camera} node and resolves node matrices under a root matrix that the weapon
 * builds. Weapon-specific layers (aiming, recoil, slide lock, effects) stay in the weapon's own viewmodel.
 *
 * @param <A> what the current one-shot clip means to the weapon (fire, reload, slash …)
 */
public final class ViewmodelRig<A> {
    private static final ResourceLocation ARMS = BlockLand.id("models/gun/arms.glb");
    private static final float FADE_IN = 0.08f;
    private static final float FADE_OUT = 0.14f;

    private final DrawList draws = new DrawList();
    private final ViewmodelMotion motion = new ViewmodelMotion();
    @Nullable
    private GpuModel model;
    private Pose pose;
    private Pose layer;
    private final Map<String, ClipSampler> clips = new HashMap<>();
    /** Nodes outside the {@code viewmodel} subtree (helper roots such as {@code fx_shell}) are never drawn. */
    private boolean[] outside = new boolean[0];
    private int viewmodelRoot = -1, camera = -1;
    /** View rotation from the animated {@code camera} node (degrees, Minecraft convention). */
    private float cameraPitch, cameraYaw, cameraRoll;

    // animation state
    @Nullable
    private ClipSampler idle;
    private float idleTime;
    @Nullable
    private ClipSampler action;
    @Nullable
    private A actionKind;
    private float actionTime, actionSpeed = 1, actionWeight;
    @Nullable
    private ClipSampler fading;
    private float fadingTime, fadingWeight;
    /** Optional authored sprint loop; without it the motion layer poses a raised carry itself. */
    @Nullable
    private ClipSampler sprintClip;
    @Nullable
    private Consumer<String> eventSink;
    /** Weapon changes to the base pose, applied after the idle is sampled (the gun's slide lock). */
    @Nullable
    private Consumer<Pose> baseModifier;

    // scratch (no per-frame allocation)
    private final Matrix4f modelView = new Matrix4f();
    private final Quaternionf q = new Quaternionf();
    private final Vector3f v = new Vector3f();

    // ---- loading ----------------------------------------------------------------------------------

    /** @return false when the model is missing or has no {@code viewmodel} root (vanilla rendering is kept then) */
    public boolean load(ResourceLocation location, String idleClip, String sprintClipName) {
        model = ModelCache.get(location);
        clips.clear();
        action = null;
        fading = null;
        actionKind = null;
        actionWeight = 0;
        if (model == null) return false;
        GltfModel source = model.source;
        pose = new Pose(source);
        layer = new Pose(source);
        for (GltfModel.Animation animation : source.animations) clips.put(animation.name, new ClipSampler(animation));
        idle = clips.get(idleClip);
        sprintClip = clips.get(sprintClipName);
        viewmodelRoot = source.node("viewmodel");
        camera = source.node("camera");
        outside = new boolean[source.nodes.size()];
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            outside[index] = node.parent < 0 ? index != viewmodelRoot : outside[node.parent];
        }
        return isReady();
    }

    public boolean isReady() {
        return model != null && pose != null && viewmodelRoot >= 0;
    }

    @Nullable
    public GpuModel model() {
        return model;
    }

    /** Node index by name, -1 when missing. */
    public int node(String name) {
        return model == null ? -1 : model.source.node(name);
    }

    public void setEventSink(@Nullable Consumer<String> sink) {
        this.eventSink = sink;
    }

    public void setBaseModifier(@Nullable Consumer<Pose> modifier) {
        this.baseModifier = modifier;
    }

    // ---- one-shot clips ---------------------------------------------------------------------------

    /**
     * Plays a one-shot clip, stretched to {@code seconds} when given (> 0) so animations always match the gameplay
     * timing in the weapon data.
     *
     * @param snapIn start at full weight (short clips that must feel instant) instead of fading in
     * @return false when the model has no such clip
     */
    public boolean play(A kind, String clipName, float seconds, boolean snapIn) {
        ClipSampler clip = clips.get(clipName);
        if (clip == null) {
            actionKind = null;
            return false;
        }
        if (action != null && action != clip && actionWeight > 0.05f) {
            fading = action;
            fadingTime = actionTime;
            fadingWeight = actionWeight;
        }
        action = clip;
        actionKind = kind;
        actionTime = 0;
        actionSpeed = seconds > 0 && clip.duration() > 0 ? clip.duration() / seconds : 1;
        actionWeight = snapIn ? 1 : 0;
        return true;
    }

    public boolean hasClip(String name) {
        return clips.containsKey(name);
    }

    public void stopAction() {
        if (action != null) {
            fading = action;
            fadingTime = actionTime;
            fadingWeight = actionWeight;
        }
        action = null;
        actionKind = null;
    }

    /** What the playing one-shot clip is, or null when only the idle plays. */
    @Nullable
    public A action() {
        return actionKind;
    }

    /** Seconds left in the current one-shot (0 when idle). */
    public float actionRemaining() {
        if (action == null) return 0;
        return Math.max(0, (action.duration() - actionTime) / Math.max(0.01f, actionSpeed));
    }

    /** Seconds played of the current one-shot, in gameplay time (0 when idle). */
    public float actionElapsed() {
        return action == null ? 0 : actionTime / Math.max(0.01f, actionSpeed);
    }

    // ---- per frame --------------------------------------------------------------------------------

    /**
     * Advances clips, events and the motion layer.
     *
     * @param sprinting the player is sprinting (weapon in the sprint carry)
     * @param calm      0 = full motion, 1 = aimed down sights (motion calmed to a few percent)
     */
    public void update(float dt, Player player, boolean sprinting, float calm) {
        if (!isReady()) return;
        idleTime += dt;
        if (idle != null && idle.duration() > 0) idleTime %= idle.duration();
        if (action != null) {
            float before = actionTime;
            actionTime += dt * actionSpeed;
            fireEvents(action, before, actionTime);
            actionWeight = Math.min(1, actionWeight + dt / FADE_IN);
            if (actionTime >= action.duration()) {
                fading = action;
                fadingTime = action.duration();
                fadingWeight = actionWeight;
                action = null;
                actionKind = null;
                actionWeight = 0;
            }
        }
        if (fading != null) {
            fadingWeight -= dt / FADE_OUT;
            if (fadingWeight <= 0) fading = null;
        }
        motion.update(dt, player, sprinting, calm);
    }

    private void fireEvents(ClipSampler clip, float from, float to) {
        if (eventSink == null) return;
        for (GltfModel.Event event : clip.clip().events) {
            if (event.time() > from && event.time() <= to) eventSink.accept(event.name());
        }
    }

    public ViewmodelMotion motion() {
        return motion;
    }

    /** 0..1 how far into the sprint carry the weapon is. */
    public float sprintAmount() {
        return motion.sprintAmount();
    }

    public boolean hasSprintClip() {
        return sprintClip != null;
    }

    /**
     * Samples the clips for this frame and resolves every node's matrix under {@code root} (the weapon's
     * procedural transform of the {@code viewmodel} root).
     */
    public void pose(Matrix4f root) {
        Pose p = pose;
        base(p);
        if (fading != null && fadingWeight > 0) {
            base(layer);
            fading.sample(Math.min(fadingTime, fading.duration()), layer);
            p.blend(layer, Mth.clamp(fadingWeight, 0, 1));
        }
        if (action != null) {
            base(layer);
            action.sample(actionTime, layer);
            p.blend(layer, Mth.clamp(actionWeight, 0, 1));
        }
        // sprint loop, locked to the footsteps; one-shot clips (reload, inspect…) play over it
        float sprintWeight = sprintClip == null ? 0 : motion.sprintAmount() * (1 - Mth.clamp(actionWeight, 0, 1));
        if (sprintWeight > 0.01f) {
            base(layer);
            sprintClip.sample(motion.cycleTime(sprintClip.duration()), layer);
            p.blend(layer, sprintWeight);
        }
        cameraFromPose(p);
        p.computeWorld(root);
    }

    private void base(Pose into) {
        into.reset();
        if (idle != null) idle.sample(idleTime, into);
        if (baseModifier != null) baseModifier.accept(into);
    }

    /** The idle's first frame under an identity root (for rest-pose measurements such as the ADS eye point). */
    public Pose restPose() {
        Pose rest = new Pose(model.source);
        if (idle != null) idle.sample(0, rest);
        rest.computeWorld(new Matrix4f());
        return rest;
    }

    /** Reads the animated camera node (spec §4): rotation relative to its rest pose, as view angles. */
    private void cameraFromPose(Pose p) {
        if (camera < 0) {
            cameraPitch = cameraYaw = cameraRoll = 0;
            return;
        }
        float[] rest = model.source.nodes.get(camera).rotation;
        Quaternionf delta = q.set(rest[0], rest[1], rest[2], rest[3]).conjugate().mul(p.rotation(camera, new Quaternionf()));
        Vector3f euler = delta.getEulerAnglesYXZ(v);
        // OpenGL camera space: +X rotation looks up, +Y turns left; Minecraft pitch is positive downwards, yaw rightwards
        cameraPitch = (float) -Math.toDegrees(euler.x);
        cameraYaw = (float) -Math.toDegrees(euler.y);
        cameraRoll = (float) Math.toDegrees(euler.z);
    }

    /** View rotation for this frame: the clip's camera track plus the procedural camera bob. */
    public float cameraPitch() {
        return cameraPitch + motion.cameraPitch();
    }

    public float cameraYaw() {
        return cameraYaw + motion.cameraYaw();
    }

    public float cameraRoll() {
        return cameraRoll + motion.cameraRoll();
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /** Matrix of a node from the last {@link #pose}, in the root's space. */
    public Matrix4f world(int node) {
        return pose.world[node];
    }

    /** A node scaled to zero by the current animation, or under one (hidden this frame). */
    public boolean isCollapsed(int index) {
        for (int n = index; n >= 0; n = pose.model().nodes.get(n).parent) {
            if (pose.isCollapsed(n)) return true;
        }
        return false;
    }

    /**
     * Queues every visible mesh of the viewmodel subtree. {@code base} maps viewmodel space to view space.
     *
     * @param hidden per node, hidden by the weapon (unfitted attachments); null = nothing extra hidden
     */
    public void drawMeshes(Matrix4f base, @Nullable boolean[] hidden, GpuModel.Style style, int blockLight, int skyLight) {
        GltfModel source = model.source;
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            if (node.mesh < 0 || outside[index] || hidden != null && hidden[index] || isCollapsed(index)) continue;
            modelView.set(base).mul(pose.world[index]);
            model.addMesh(draws, node.mesh, modelView, style, blockLight, skyLight, 1);
        }
    }

    /** Rig joint → arm mesh prefix in arms.glb (spec §7). */
    private static final String[][] ARM_JOINTS = {{"arm_R", "R_upper"}, {"arm_R_fore", "R_fore"}, {"arm_L", "L_upper"}, {"arm_L_fore", "L_fore"}};
    private static final String[] ARM_LAYERS = {"", "_sleeve"};
    private GpuTextureView armsTexture;
    private final GpuModel.Style armsStyle = new GpuModel.Style() {
        @Override
        public GpuTextureView texture(String material) {
            return armsTexture;
        }
    };

    /** The player's own arms (skin texture, wide or slim) on the rig joints. */
    public void drawArms(Matrix4f base, AbstractClientPlayer player, @Nullable boolean[] hidden, int blockLight, int skyLight) {
        GpuModel arms = ModelCache.get(ARMS);
        if (arms == null) return;
        PlayerSkin skin = player.getSkin();
        String variant = skin.model() == PlayerSkin.Model.SLIM ? "_slim" : "_wide";
        armsTexture = Minecraft.getInstance().getTextureManager().getTexture(skin.texture()).getTextureView();
        GltfModel source = model.source;
        for (String[] joint : ARM_JOINTS) {
            int node = source.node(joint[0]);
            if (node < 0 || hidden != null && hidden[node] || pose.isCollapsed(node)) continue;
            modelView.set(base).mul(pose.world[node]);
            for (String layer : ARM_LAYERS) {
                int meshNode = arms.source.node(joint[1] + variant + layer);
                if (meshNode < 0 || arms.source.nodes.get(meshNode).mesh < 0) continue;
                arms.addMesh(draws, arms.source.nodes.get(meshNode).mesh, modelView, armsStyle, blockLight, skyLight, 1);
            }
        }
    }

    /** The frame's draw list, for weapon effects queued after the model. */
    public DrawList draws() {
        return draws;
    }

    /** "draws, triangles" of the last submitted frame. */
    public String stats() {
        return draws.lastDraws() + " draws, " + draws.lastTriangles() + " tris";
    }

    /** Submits everything queued this frame. */
    public void flush(GpuTextureView color, GpuTextureView depth, GpuTextureView lightmap, String label) {
        draws.flush(color, depth, lightmap, label);
    }

    /** View-space matrix of a node from the last drawn frame (for spawning effects in the world). */
    @Nullable
    public Matrix4f nodeMatrix(int index, Matrix4f base, Matrix4f into) {
        if (!isReady() || index < 0) return null;
        return into.set(base).mul(pose.world[index]);
    }
}
