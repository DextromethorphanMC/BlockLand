package com.blockland.client.viewmodel;

import com.blockland.BlockLand;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.gltf.ClipSampler;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.MaterialLayer;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.TextureRef;
import com.blockland.client.render.Wetness;
import com.blockland.client.spray.SprayDecals;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What every first-person weapon shares (asset spec §3 / §12): one GLB viewmodel with its clips, the player-skin
 * arms (hands and fingers) from {@code arms.glb}, the procedural {@link ViewmodelMotion} layer and a {@link DrawList}.
 * Blends the locomotion loops locked to the footsteps (walk, running carry, tactical sprint) over the idle by the
 * motion layer's weights, plays one one-shot clip at a time on top, reads the animated {@code camera} node and
 * resolves node matrices under a root matrix that the weapon builds. Universal attachments (spec §19: separate models
 * drawn at the weapon's {@code mount_*} nodes) ride along, charms swing on a pendulum. Weapon-specific layers (aiming,
 * recoil, slide lock, effects) stay in the weapon's own viewmodel.
 * <p>
 * Transitions are inertial (spec §31.4, "dead blending"): when a clip starts, ends or is cut off, the pose on screen
 * carries on with the velocity it had (decaying) and eases into the new target, so chained and interrupted clips keep
 * their momentum instead of popping or averaging two poses.
 *
 * @param <A> what the current one-shot clip means to the weapon (fire, reload, slash …)
 */
public final class ViewmodelRig<A> {
    private static final ResourceLocation ARMS = BlockLand.id("models/gun/arms.glb");
    /** Default blend into a new clip, and back to the idle when a clip ends or is stopped. */
    public static final float BLEND = 0.16f;
    private static final float BLEND_OUT = 0.22f;
    /** A finished clip holds its last frame this long before blending out (a chained clip starts from it exactly). */
    private static final float END_HOLD = 0.06f;
    /** Time constant of the source's extrapolated velocity during a blend. */
    private static final float CARRY = 0.05f;
    /** Velocities above these are treated as a cut, not motion (m/s, rad/s). */
    private static final float MAX_SPEED = 7f, MAX_SPIN = 60f;

    /** The weapon is part of the world's frame: lit by the world, emission in alpha (rendering spec §8). */
    private final DrawList draws = new DrawList().scene(true);
    /** The camera's dynamic feedback (spec §32): kicks, shake, FOV punch and head movement from every layer. */
    private final CameraRig cameraRig = new CameraRig();
    private final ViewmodelMotion motion = new ViewmodelMotion(cameraRig);
    @Nullable
    private GpuModel model;
    private Pose pose;
    private Pose layer;
    /** The aimed variant's pose (spec §31.5), blended into the one-shot's layer. */
    private Pose variantLayer;
    private final Map<String, ClipSampler> clips = new HashMap<>();
    /** Nodes outside the {@code viewmodel} subtree (helper roots such as {@code fx_shell}) are never drawn. */
    private boolean[] outside = new boolean[0];
    private int viewmodelRoot = -1, camera = -1;
    /** The support arm's joint: the free arm while swimming (spec §31.4). */
    private int armL = -1;
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
    private float actionTime, actionSpeed = 1;
    /** An aimed variant of the playing clip (spec §31.5: {@code fire_ads}) and how much of it shows, 0..1. */
    @Nullable
    private ClipSampler variant;
    private float variantWeight;
    /** The clip has finished and holds its last frame for {@link #END_HOLD} (it is no longer the weapon's action). */
    private boolean ended;
    private float endHeld;

    // dead blending: the pose last shown (locals) and its velocity; a blend's source, its velocity and progress
    private float[] shownT = new float[0], shownR = new float[0], velT = new float[0], velR = new float[0];
    private float[] fromT = new float[0], fromR = new float[0], fromVT = new float[0], fromVR = new float[0];
    private boolean shown, blending;
    private float sincePose, blendTime, blendLength;
    /** Blend length of a transition asked for since the last pose (it starts from the pose then on screen), -1 none. */
    private float pendingBlend = -1;
    private final Quaternionf qa = new Quaternionf(), qb = new Quaternionf();
    private final Vector3f rv = new Vector3f();
    /** Optional authored locomotion loops (spec §17); without sprint loops the motion layer poses the carries. */
    @Nullable
    private ClipSampler walkClip, runClip, tacticalClip;
    @Nullable
    private Consumer<String> eventSink;
    /** Weapon changes to the base pose, applied after the idle is sampled (the gun's slide lock). */
    @Nullable
    private Consumer<Pose> baseModifier;

    /** Attachment models at mount nodes, rebuilt when the weapon's build changes. */
    private List<Mounted> mounted = List.of();
    /** The support hand's hold on the fitted underbarrel attachment (spec §35); null = the clips' hand. */
    @Nullable
    private SupportGrip supportGrip;
    /** The build's sprays (spec §29) and a dual gun's twin suffix; null = none. */
    @Nullable
    private Supplier<SprayDecals> sprays;
    @Nullable
    private String sprayTwins;
    /** Materials of one attachment (by key) that another pass draws itself this frame; empty = all drawn. */
    private String hiddenKey = "";
    private Set<String> hiddenMaterials = Set.of();
    /** Seconds since the charms were last simulated. */
    private float charmTime;

    // scratch (no per-frame allocation)
    private final Matrix4f modelView = new Matrix4f();
    private final Quaternionf q = new Quaternionf();
    private final Vector3f v = new Vector3f();

    // ---- loading ----------------------------------------------------------------------------------

    /**
     * @param walk     optional walk loop
     * @param sprint   optional running-carry loop
     * @param tactical optional tactical-sprint loop (the run is used for it when missing, and the other way round)
     * @return false when the model is missing or has no {@code viewmodel} root (vanilla rendering is kept then)
     */
    public boolean load(ResourceLocation location, String idleClip, String walk, String sprint, String tactical) {
        model = ModelCache.get(location);
        clips.clear();
        mounted = List.of();
        supportGrip = null;
        action = null;
        actionKind = null;
        ended = false;
        shown = blending = false;
        pendingBlend = -1;
        if (model == null) return false;
        GltfModel source = model.source;
        pose = new Pose(source);
        layer = new Pose(source);
        variantLayer = new Pose(source);
        int n = source.nodes.size();
        shownT = new float[n * 3];
        shownR = new float[n * 4];
        velT = new float[n * 3];
        velR = new float[n * 3];
        fromT = new float[n * 3];
        fromR = new float[n * 4];
        fromVT = new float[n * 3];
        fromVR = new float[n * 3];
        for (GltfModel.Animation animation : source.animations) clips.put(animation.name, new ClipSampler(animation));
        idle = clips.get(idleClip);
        walkClip = clips.get(walk);
        runClip = clips.get(sprint);
        tacticalClip = clips.get(tactical);
        if (runClip == null) runClip = tacticalClip;
        if (tacticalClip == null) tacticalClip = runClip;
        motion.setWalkClip(walkClip != null);
        viewmodelRoot = source.node("viewmodel");
        camera = source.node("camera");
        // a dual gun's left hand holds its own gun (spec §26): never a free arm
        armL = source.node("gun_L") >= 0 ? -1 : source.node("arm_L");
        outside = new boolean[source.nodes.size()];
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            outside[index] = node.parent < 0 ? index != viewmodelRoot : outside[node.parent];
        }
        if (isReady()) motion.setReach(reach());
        return isReady();
    }

    /** Eye to the muzzle (or a knife's tip) in the rest pose: the weapon's lever arm for its rotational inertia. */
    private float reach() {
        int end = node("muzzle") >= 0 ? node("muzzle") : node("tip");
        if (end < 0) return 0.45f;
        return restPose().world[end].getTranslation(new Vector3f()).length();
    }

    /**
     * Loaded and drawable. A resource reload frees the model ({@link ModelCache#clear}) until the weapon module loads
     * it again, so a rig still holding it (the weapon on screen, one dropping out of view) draws nothing meanwhile.
     */
    public boolean isReady() {
        return model != null && !model.isClosed() && pose != null && viewmodelRoot >= 0;
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
     * @param snapIn a short clip that must feel instant (a shot): the shortest blend in
     * @return false when the model has no such clip
     */
    public boolean play(A kind, String clipName, float seconds, boolean snapIn) {
        return play(kind, clipName, seconds, snapIn ? 0.045f : BLEND);
    }

    /**
     * Plays a one-shot clip (stretched to {@code seconds} when > 0), blending into it inertially from whatever is on
     * screen — the previous clip mid-way, the same clip repeating (a shell reload's round), the idle.
     *
     * @param blend seconds of the blend (0 = cut straight to the clip)
     * @return false when the model has no such clip
     */
    public boolean play(A kind, String clipName, float seconds, float blend) {
        ClipSampler clip = clips.get(clipName);
        if (clip == null) {
            actionKind = null;
            return false;
        }
        action = clip;
        actionKind = kind;
        actionTime = 0;
        variant = null;
        ended = false;
        actionSpeed = seconds > 0 && clip.duration() > 0 ? clip.duration() / seconds : 1;
        transition(blend);
        return true;
    }

    public boolean hasClip(String name) {
        return clips.containsKey(name);
    }

    /**
     * Blends the playing one-shot with its aimed variant (spec §31.5: a shot with the sights up keeps the sight picture)
     * by {@code weight} (the aim amount, 0..1) until the clip ends. Call right after {@link #play}; a model without that
     * clip plays the hip clip alone. The variant runs on the clip's clock (same length by contract) and its events are
     * the clip's.
     */
    public void playVariant(String clipName, float weight) {
        ClipSampler clip = action == null ? null : clips.get(clipName);
        variant = clip != null && weight > 0.001f ? clip : null;
        variantWeight = Math.min(1, Math.max(0, weight));
    }

    /** Seconds of a clip as authored (0 when missing). */
    public float clipLength(String name) {
        ClipSampler clip = clips.get(name);
        return clip == null ? 0 : clip.duration();
    }

    public void stopAction() {
        stopAction(BLEND);
    }

    /** Cuts the one-shot off; the pose blends back to the idle over {@code blend} seconds with its momentum. */
    public void stopAction(float blend) {
        if (action != null) transition(blend);
        action = null;
        actionKind = null;
        ended = false;
    }

    /**
     * Starts an inertial blend from the pose on screen at the next {@link #pose}. Several requests before it (a shot
     * that cuts an inspect off: stop, then fire) take the longest, so a big pose is never whipped home in a shot's blend.
     */
    private void transition(float blend) {
        pendingBlend = Math.max(pendingBlend, Math.max(0, blend));
    }

    /** What the playing one-shot clip is, or null when only the idle plays. */
    @Nullable
    public A action() {
        return actionKind;
    }

    /** Seconds left in the current one-shot (0 when idle). */
    public float actionRemaining() {
        if (action == null || ended) return 0;
        return Math.max(0, (action.duration() - actionTime) / Math.max(0.01f, actionSpeed));
    }

    /** Seconds played of the current one-shot, in gameplay time (0 when idle). */
    public float actionElapsed() {
        return action == null || ended ? 0 : actionTime / Math.max(0.01f, actionSpeed);
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
        // a weapon brought out of the water stays wet a while (rendering spec §15, soaking)
        draws.wetness(Wetness.of(player));
        charmTime += dt;
        sincePose += dt;
        idleTime += dt;
        if (idle != null && idle.duration() > 0) idleTime %= idle.duration();
        if (action != null && !ended) {
            float before = actionTime;
            actionTime += dt * actionSpeed;
            fireEvents(action, before, actionTime);
            if (actionTime >= action.duration()) {
                // the weapon is free again; the last frame holds a moment, so a chained clip (reload start → rounds →
                // end) starts from exactly that pose instead of one already sliding back towards the idle
                actionTime = action.duration();
                actionKind = null;
                ended = true;
                endHeld = 0;
            }
        } else if (ended && (endHeld += dt) >= END_HOLD) {
            action = null;
            ended = false;
            transition(BLEND_OUT);
        }
        // swimming: any one-shot (a shot, a reload, a cut) brings the weapon up out of the swim carry
        motion.swim().hands(action != null && !ended);
        motion.prone().hands(action != null && !ended);
        motion.update(dt, player, sprinting, calm);
        cameraRig.update(dt, calm);
    }

    /** A clip event plays its sound (the weapon's sink) and shoves the rig as the hands work the weapon (spec §31.4). */
    private void fireEvents(ClipSampler clip, float from, float to) {
        for (GltfModel.Event event : clip.clip().events) {
            if (event.time() <= from || event.time() > to) continue;
            if (eventSink != null) eventSink.accept(event.name());
            ViewmodelImpulses.Impulse impulse = ViewmodelImpulses.get(event.name());
            if (impulse != null) motion.react(impulse);
        }
    }

    public ViewmodelMotion motion() {
        return motion;
    }

    /** 0..1 how far into the sprint carry the weapon is. */
    public float sprintAmount() {
        return motion.sprintAmount();
    }

    public boolean hasSprintClips() {
        return runClip != null;
    }

    /**
     * Samples the clips for this frame and resolves every node's matrix under {@code root} (the weapon's
     * procedural transform of the {@code viewmodel} root).
     */
    public void pose(Matrix4f root) {
        Pose p = pose;
        base(p);
        // locomotion loops, locked to the footsteps; one-shot clips (fire, reload, inspect…) play over them
        locomotion(p, walkClip, motion.walkWeight());
        locomotion(p, runClip, motion.runWeight());
        locomotion(p, tacticalClip, motion.tacticalWeight());
        if (action != null) {
            base(layer);
            action.sample(actionTime, layer);
            if (variant != null) {
                base(variantLayer);
                variant.sample(actionTime * variant.duration() / Math.max(1e-4f, action.duration()), variantLayer);
                layer.blend(variantLayer, variantWeight);
            }
            p.blend(layer, 1);
        }
        deadBlend(p);
        motion.swim().poseFreeArm(p, armL);
        motion.prone().poseFreeArm(p, armL);
        cameraFromPose(p);
        p.computeWorld(root);
        // the support hand on the underbarrel attachment (spec §35), wherever the clip's hand holds the weapon
        if (supportGrip != null && supportGrip.apply(p)) p.computeWorld(root);
        poseAttachments();
    }

    private void locomotion(Pose p, @Nullable ClipSampler clip, float weight) {
        if (clip == null || weight <= 0.01f) return;
        base(layer);
        clip.sample(motion.cycleTime(clip.duration()), layer);
        p.blend(layer, Math.min(1, weight));
    }

    /**
     * Inertial transitions: a requested transition starts from the pose last shown and its velocity; while it runs,
     * that source keeps moving with its (decaying) velocity and the target {@code p} takes over along a smoothstep, so
     * the motion is continuous in position and velocity. Scales (a magazine shown or hidden) are never blended.
     */
    private void deadBlend(Pose p) {
        float dt = sincePose;
        sincePose = 0;
        if (pendingBlend >= 0) {
            blending = shown && pendingBlend > 0.001f && dt < 0.25f;
            if (blending) {
                System.arraycopy(shownT, 0, fromT, 0, shownT.length);
                System.arraycopy(shownR, 0, fromR, 0, shownR.length);
                System.arraycopy(velT, 0, fromVT, 0, velT.length);
                System.arraycopy(velR, 0, fromVR, 0, velR.length);
                blendTime = 0;
                blendLength = pendingBlend;
            }
            pendingBlend = -1;
        }
        if (blending) {
            blendTime += dt;
            if (blendTime >= blendLength) {
                blending = false;
            } else {
                float u = blendTime / blendLength, w = u * u * (3 - 2 * u);
                float carry = CARRY * (1 - (float) Math.exp(-blendTime / CARRY));
                for (int i = 0, n = p.t.length / 3; i < n; i++) {
                    for (int j = i * 3; j < i * 3 + 3; j++) {
                        float source = fromT[j] + fromVT[j] * carry;
                        p.t[j] = source + (p.t[j] - source) * w;
                    }
                    int r = i * 4, v = i * 3;
                    rv.set(fromVR[v], fromVR[v + 1], fromVR[v + 2]).mul(carry);
                    float angle = rv.length();
                    if (angle > 1e-6f) qa.setAngleAxis(angle, rv.x / angle, rv.y / angle, rv.z / angle);
                    else qa.identity();
                    qa.mul(qb.set(fromR[r], fromR[r + 1], fromR[r + 2], fromR[r + 3]));
                    qa.slerp(qb.set(p.r[r], p.r[r + 1], p.r[r + 2], p.r[r + 3]), w);
                    p.r[r] = qa.x;
                    p.r[r + 1] = qa.y;
                    p.r[r + 2] = qa.z;
                    p.r[r + 3] = qa.w;
                }
            }
        }
        // the velocity of what is shown (a jump faster than any hand moves is a cut: no velocity to carry)
        boolean measure = shown && dt > 1e-4f && dt < 0.1f;
        for (int i = 0, n = p.t.length / 3; i < n; i++) {
            int t = i * 3, r = i * 4;
            if (measure) {
                float vx = (p.t[t] - shownT[t]) / dt, vy = (p.t[t + 1] - shownT[t + 1]) / dt, vz = (p.t[t + 2] - shownT[t + 2]) / dt;
                boolean cut = vx * vx + vy * vy + vz * vz > MAX_SPEED * MAX_SPEED;
                velT[t] = cut ? 0 : vx;
                velT[t + 1] = cut ? 0 : vy;
                velT[t + 2] = cut ? 0 : vz;
                // angular velocity: q_now = delta × q_before, delta as a rotation vector per second
                qa.set(p.r[r], p.r[r + 1], p.r[r + 2], p.r[r + 3]).mul(qb.set(shownR[r], shownR[r + 1], shownR[r + 2], shownR[r + 3]).conjugate());
                if (qa.w < 0) qa.set(-qa.x, -qa.y, -qa.z, -qa.w);
                float s = (float) Math.sqrt(qa.x * qa.x + qa.y * qa.y + qa.z * qa.z);
                float angle = 2 * (float) Math.atan2(s, qa.w);
                float k = s < 1e-7f || angle / dt > MAX_SPIN ? 0 : angle / (s * dt);
                velR[t] = qa.x * k;
                velR[t + 1] = qa.y * k;
                velR[t + 2] = qa.z * k;
            } else {
                velT[t] = velT[t + 1] = velT[t + 2] = 0;
                velR[t] = velR[t + 1] = velR[t + 2] = 0;
            }
        }
        System.arraycopy(p.t, 0, shownT, 0, p.t.length);
        System.arraycopy(p.r, 0, shownR, 0, p.r.length);
        shown = true;
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

    /**
     * View rotation for this frame: the clip's camera track, the locomotion sway (scaled by the player's comfort
     * setting) and the camera rig's kicks and shake.
     */
    public float cameraPitch() {
        return cameraPitch + motion.cameraPitch() * CameraRig.motionIntensity() + cameraRig.pitch();
    }

    public float cameraYaw() {
        return cameraYaw + motion.cameraYaw() * CameraRig.motionIntensity() + cameraRig.yaw();
    }

    public float cameraRoll() {
        return cameraRoll + motion.cameraRoll() * CameraRig.motionIntensity() + cameraRig.roll();
    }

    /** The camera's dynamic feedback (the weapon feeds its shots and hits into it; FOV punch and head movement). */
    public CameraRig camera() {
        return cameraRig;
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /** Matrix of a node from the last {@link #pose}, in the root's space. */
    public Matrix4f world(int node) {
        return pose.world[node];
    }

    /** A node scaled to zero by the current animation, or under one (hidden this frame). */
    /** Whether {@link #drawMeshes} draws node {@code index}'s mesh this frame (inside the viewmodel, shown, not collapsed). */
    boolean drawsNode(int index, @Nullable boolean[] hidden) {
        return index >= 0 && index < outside.length && !outside[index] && !(hidden != null && index < hidden.length && hidden[index])
                && !isCollapsed(index);
    }

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
            model.addNode(draws, index, modelView, style, "", blockLight, skyLight, 1);
        }
        drawSprays(base, hidden, blockLight, skyLight);
        for (Mounted m : mounted) {
            // an attachment goes away with its mount (hidden by the build, scaled to zero by a clip, outside the viewmodel)
            if (!mountVisible(m, hidden)) continue;
            GltfModel att = m.model.source;
            for (int index : att.order) {
                GltfModel.Node node = att.nodes.get(index);
                if (node.mesh < 0 || !m.drawn[index] || m.pose.isCollapsed(index)) continue;
                modelView.set(base).mul(m.pose.world[index]);
                // in the weapon's effect space (spec §30): skin effects run on across the mount without a seam
                m.model.addNode(draws, index, modelView, m.key.equals(hiddenKey) ? hiding(style) : style, m.region,
                        model.restOrigin(m.mount), blockLight, skyLight, 1);
            }
        }
    }

    // ---- sprays (spec §29) ----------------------------------------------------------------------------

    /**
     * The build's sprays: fetched every frame (the decal cache may have rebuilt them), drawn on the nodes they were
     * projected onto so they ride with every animation.
     *
     * @param twinSuffix a dual gun's left-hand suffix: decals of a part also go on its twin; null otherwise
     */
    public void setSprays(@Nullable Supplier<SprayDecals> decals, @Nullable String twinSuffix) {
        this.sprays = decals;
        this.sprayTwins = twinSuffix;
    }

    private void drawSprays(Matrix4f base, @Nullable boolean[] hidden, int blockLight, int skyLight) {
        SprayDecals decals = sprays == null ? null : sprays.get();
        if (decals == null || decals.isEmpty() || !decals.madeFor(model)) return;
        for (int node : decals.weaponNodes()) {
            drawSprayNode(decals, node, node, base, hidden, blockLight, skyLight);
            int twin = sprayTwins == null ? -1 : decals.twin(node, sprayTwins);
            if (twin >= 0) drawSprayNode(decals, node, twin, base, hidden, blockLight, skyLight);
        }
        for (Mounted m : mounted) {
            if (!mountVisible(m, hidden)) continue;
            for (int node : decals.attachmentNodes(m.key)) {
                if (node >= m.drawn.length || !m.drawn[node] || m.pose.isCollapsed(node)) continue;
                modelView.set(base).mul(m.pose.world[node]);
                decals.drawAttachmentNode(draws, m.key, node, modelView, blockLight, skyLight);
            }
        }
    }

    /** The decals made for weapon node {@code node}, drawn at node {@code at} (itself, or its dual twin). */
    private void drawSprayNode(SprayDecals decals, int node, int at, Matrix4f base, @Nullable boolean[] hidden,
                               int blockLight, int skyLight) {
        if (at >= outside.length || outside[at] || hidden != null && at < hidden.length && hidden[at] || isCollapsed(at)) return;
        modelView.set(base).mul(pose.world[at]);
        decals.drawWeaponNode(draws, node, modelView, blockLight, skyLight);
    }

    // ---- attachments (spec §19) -----------------------------------------------------------------------

    /**
     * An attachment model to draw at a mount node of the weapon.
     *
     * @param key    what the weapon calls it (its slot id), for helper lookups such as the optic's {@code aim}
     * @param mount  mount node in the weapon model ({@code mount_optic} …)
     * @param region skin region of its materials that do not name one (asset spec §21), "" = none
     */
    public record Attachment(String key, String mount, ResourceLocation model, String region) {
        public Attachment(String key, String mount, ResourceLocation model) {
            this(key, mount, model, "");
        }
    }

    /** One resolved attachment: its model, its own pose and, for charms, the pendulum. */
    private static final class Mounted {
        final String key;
        final int mount;
        final GpuModel model;
        String region = "";
        final Pose pose;
        /** Nodes of the {@code attachment} subtree (helper roots elsewhere are not drawn). */
        final boolean[] drawn;
        final int pivot;
        @Nullable
        final CharmPhysics charm;
        final Quaternionf pivotRest = new Quaternionf();

        Mounted(String key, int mount, GpuModel model) {
            this.key = key;
            this.mount = mount;
            this.model = model;
            GltfModel source = model.source;
            this.pose = new Pose(source);
            int root = source.node("attachment");
            this.drawn = new boolean[source.nodes.size()];
            for (int index : source.order) {
                GltfModel.Node node = source.nodes.get(index);
                drawn[index] = node.parent < 0 ? root < 0 || index == root : drawn[node.parent];
            }
            this.pivot = source.node("pivot");
            if (pivot >= 0) {
                float[] r = source.nodes.get(pivot).rotation;
                pivotRest.set(r[0], r[1], r[2], r[3]);
                this.charm = new CharmPhysics(massCenter(source, pivot));
            } else {
                this.charm = null;
            }
        }
    }

    /** Fits these attachments (replacing the previous ones); missing models or mounts are skipped. */
    /**
     * Leaves out some materials of one attachment (a reflex sight's glass and reticle, drawn by their own pass);
     * {@code materials} empty = draw everything again.
     */
    public void setHiddenMaterials(String key, Set<String> materials) {
        hiddenKey = materials.isEmpty() ? "" : key;
        hiddenMaterials = materials;
    }

    private GpuModel.Style hiding(GpuModel.Style style) {
        Set<String> hidden = hiddenMaterials;
        return new GpuModel.Style() {
            @Override
            public float[] tint(String material) {
                return style.tint(material);
            }

            @Override
            public TextureRef textureRef(String material) {
                return style.textureRef(material);
            }

            @Override
            public GpuTextureView texture(String material) {
                return style.texture(material);
            }

            @Override
            public MaterialLayer layer(String material, String region) {
                return style.layer(material, region);
            }

            @Override
            public boolean hidden(String material) {
                return hidden.contains(material) || style.hidden(material);
            }

            @Override
            public float surge() {
                return style.surge();
            }
        };
    }

    public void setAttachments(List<Attachment> attachments) {
        List<Mounted> list = new ArrayList<>();
        if (model != null) {
            for (Attachment attachment : attachments) {
                int mount = model.source.node(attachment.mount());
                GpuModel gpu = mount < 0 ? null : ModelCache.get(attachment.model());
                if (gpu == null) continue;
                Mounted m = new Mounted(attachment.key(), mount, gpu);
                m.region = attachment.region() == null ? "" : attachment.region();
                list.add(m);
            }
        }
        mounted = List.copyOf(list);
    }

    /**
     * The support hand holds the attachment at {@code mount} as {@code hold} says (spec §35), wherever the clips' hand
     * holds the weapon; null = as the clips put it. Call after {@link #load}.
     */
    public void setSupportHold(String mount, @Nullable SupportGrip.Hold hold) {
        supportGrip = hold == null || !isReady() ? null : SupportGrip.create(model.source, restPose(), mount, hold);
    }

    /** Whether an attachment with this key is fitted and has a model. */
    public boolean hasAttachment(String key) {
        return find(key) != null;
    }

    /** Matrix of a node of a fitted attachment from the last {@link #pose}, in the root's space; null when absent. */
    @Nullable
    public Matrix4f attachmentNode(String key, String node) {
        Mounted m = find(key);
        if (m == null) return null;
        int index = m.model.source.node(node);
        return index < 0 ? null : m.pose.world[index];
    }

    /** A fitted attachment's node in the rest pose under an identity root (e.g. the optic's eye point for ADS). */
    @Nullable
    public Matrix4f restAttachmentNode(String key, String node) {
        Mounted m = find(key);
        if (m == null) return null;
        int index = m.model.source.node(node);
        if (index < 0) return null;
        Pose rest = new Pose(m.model.source);
        rest.computeWorld(restPose().world[m.mount]);
        return new Matrix4f(rest.world[index]);
    }

    /** Whether a fitted attachment is on screen this frame (its mount neither hidden nor collapsed by a clip). */
    public boolean attachmentVisible(String key, @Nullable boolean[] hidden) {
        Mounted m = find(key);
        return m != null && mountVisible(m, hidden);
    }

    private boolean mountVisible(Mounted m, @Nullable boolean[] hidden) {
        return !outside[m.mount] && !(hidden != null && hidden[m.mount]) && !isCollapsed(m.mount);
    }

    @Nullable
    private Mounted find(String key) {
        for (Mounted m : mounted) if (m.key.equals(key)) return m;
        return null;
    }

    /** Puts every attachment at its mount and lets charms swing (the root's space is view space for weapons). */
    private void poseAttachments() {
        float dt = charmTime;
        charmTime = 0;
        if (mounted.isEmpty()) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        for (Mounted m : mounted) {
            m.pose.computeWorld(pose.world[m.mount]);
            if (m.charm == null || !camera.isInitialized()) continue;
            Quaternionf view = camera.rotation();
            Vector3f ring = view.transform(m.pose.world[m.pivot].getTranslation(new Vector3f()));
            Vector3d ringWorld = new Vector3d(camera.getPosition().x + ring.x, camera.getPosition().y + ring.y,
                    camera.getPosition().z + ring.z);
            int parent = m.model.source.nodes.get(m.pivot).parent;
            Matrix4f parentMatrix = parent >= 0 ? m.pose.world[parent] : pose.world[m.mount];
            Quaternionf parentToWorld = new Quaternionf(view).mul(parentMatrix.getNormalizedRotation(new Quaternionf()));
            m.pose.setRotation(m.pivot, m.charm.step(dt, ringWorld, parentToWorld, m.pivotRest, new Quaternionf()));
            m.pose.computeWorld(pose.world[m.mount]);
        }
    }

    /** Centre of the meshes under {@code pivot} in the pivot's own space (rest pose): the pendulum's mass. */
    private static Vector3f massCenter(GltfModel source, int pivot) {
        Pose rest = new Pose(source);
        rest.computeWorld(new Matrix4f());
        Matrix4f toPivot = new Matrix4f(rest.world[pivot]).invert();
        Vector3f sum = new Vector3f(), p = new Vector3f();
        int count = 0;
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            if (node.mesh < 0 || index != pivot && !source.isUnder(index, pivot)) continue;
            Matrix4f toLocal = new Matrix4f(toPivot).mul(rest.world[index]);
            for (GltfModel.Primitive primitive : source.meshes.get(node.mesh).primitives) {
                float[] pos = primitive.positions;
                for (int i = 0; i < pos.length; i += 3) {
                    sum.add(toLocal.transformPosition(pos[i], pos[i + 1], pos[i + 2], p));
                    count++;
                }
            }
        }
        return count == 0 ? new Vector3f(0, -0.02f, 0) : sum.div(count);
    }

    /** Rig joint → arm mesh prefix in a v1 arms.glb without node extras (spec §7). */
    private static final String[][] ARM_JOINTS = {{"arm_R", "R_upper"}, {"arm_R_fore", "R_fore"}, {"arm_L", "L_upper"}, {"arm_L_fore", "L_fore"}};
    private static final String[] ARM_LAYERS = {"", "_sleeve"};
    private static final String[] VARIANTS = {"wide", "slim"};

    /** One mesh of arms.glb and the viewmodel joint it is drawn at (spec §16: node extras); skinned when {@code node.skin} is set (§7). */
    private record ArmPart(int mesh, String joint, String variant, @Nullable GltfModel.Node node) {
    }

    @Nullable
    private GpuModel armsSource;
    private List<ArmPart> armParts = List.of();
    /** CPU skinning of the skinned arms meshes (elbow and wrist bend round, spec §7); this rig's own buffers. */
    private final ArmSkin armSkin = new ArmSkin();
    private final ViewmodelBlood blood = new ViewmodelBlood();
    @Nullable
    private TextureRef armsTexture;
    private final GpuModel.Style armsStyle = new GpuModel.Style() {
        @Override
        public TextureRef textureRef(String material) {
            return armsTexture;
        }
    };

    /**
     * The player's own arms, hands and fingers (skin texture, wide or slim): every arms.glb mesh at the viewmodel
     * joint its {@code extras.joint} names, the skinned arm tubes and palms bent over their skin joints (spec §7). A mesh
     * whose joint the weapon file lacks (or hides) is not drawn.
     */
    public void drawArms(Matrix4f base, AbstractClientPlayer player, @Nullable boolean[] hidden, int blockLight, int skyLight) {
        GpuModel arms = ModelCache.get(ARMS);
        if (arms == null) return;
        PlayerSkin skin = player.getSkin();
        String variant = skin.model() == PlayerSkin.Model.SLIM ? "slim" : "wide";
        armsTexture = TextureRef.managed(skin.texture());
        GltfModel source = model.source;
        blood.beginArms(arms);
        for (ArmPart part : armParts(arms)) {
            if (!part.variant.equals(variant)) continue;
            int node = source.node(part.joint);
            if (node < 0 || hidden != null && hidden[node] || isCollapsed(node)) continue;
            if (part.node != null && part.node.skin != null) {
                armSkin.draw(draws, arms, part.node, pose, node, base, armsStyle, blockLight, skyLight);
                blood.arm(part.mesh, node, armSkin.skinned(part.mesh));
                continue;
            }
            modelView.set(base).mul(pose.world[node]);
            arms.addMesh(draws, part.mesh, modelView, armsStyle, blockLight, skyLight, 1);
            blood.arm(part.mesh, node, null);
        }
        // blood splashed onto the weapon and the hands, laid where it landed (rendering spec §19)
        blood.draw(this, player, base, hidden, blockLight, skyLight);
    }

    /** arms.glb meshes with the joint they belong to; from node extras, or the v1 naming table. */
    private List<ArmPart> armParts(GpuModel arms) {
        if (arms == armsSource) return armParts;
        armsSource = arms;
        List<ArmPart> parts = new ArrayList<>();
        for (GltfModel.Node node : arms.source.nodes) {
            String joint = node.extras.get("joint");
            if (node.mesh >= 0 && joint != null) parts.add(new ArmPart(node.mesh, joint, node.extras.getOrDefault("variant", "wide"), node));
        }
        if (parts.isEmpty()) {
            for (String[] joint : ARM_JOINTS) {
                for (String variant : VARIANTS) {
                    for (String layer : ARM_LAYERS) {
                        int index = arms.source.node(joint[1] + "_" + variant + layer);
                        if (index >= 0 && arms.source.nodes.get(index).mesh >= 0) {
                            parts.add(new ArmPart(arms.source.nodes.get(index).mesh, joint[0], variant, null));
                        }
                    }
                }
            }
        }
        armParts = List.copyOf(parts);
        return armParts;
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
        armSkin.flushed();
    }

    /** View-space matrix of a node from the last drawn frame (for spawning effects in the world). */
    @Nullable
    public Matrix4f nodeMatrix(int index, Matrix4f base, Matrix4f into) {
        if (!isReady() || index < 0) return null;
        return into.set(base).mul(pose.world[index]);
    }
}
