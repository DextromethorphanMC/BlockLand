package com.blockland.client.supply;

import com.blockland.client.camera.CameraRig;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gun.ClientGuns;
import com.blockland.client.render.GpuModel;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.client.viewmodel.WeaponOverlays;
import com.blockland.gun.GunStats;
import com.blockland.supply.SupplyContent;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The first-person resupply from an ammo crate (asset spec §46), laid over the gun's viewmodel through
 * {@link WeaponOverlays}: the head turns to look into the crate's open top and leans over it (the lower the crate stands
 * under the eye, the more it bends down), the gun is lowered and canted and held still against the head's turn, the left
 * hand reaches into the crate where it really is, rummages (the view shakes with it), grabs the gun's magazine (its
 * fitted magazine's mesh from the gun's own file) and brings it up under the magazine well as the head comes back, and
 * the gun's ordinary reload takes over (or, the gun being full, the magazine goes into a pouch). Works for every gun: it
 * only turns the camera, moves the rig's root and the left arm's joints and borrows a mesh. While it plays, the hands are
 * busy.
 */
final class ResupplyOverlay implements WeaponOverlays.Overlay {
    private boolean active;
    private boolean reload;
    private int slot;
    /** The crate in use, or null (then the arm follows the file's canned pose and the head stays). */
    @Nullable
    private BlockPos crate;
    /** The head's turn towards the crate this frame (degrees; the gun holds still against a share of it). */
    private float lookPitch, lookYaw;
    private long startNanos;
    private int eventsPlayed;

    /** The left arm's joints (arm_L and every node under it) of the model they were found in. */
    @Nullable
    private GpuModel jointsOf;
    private int[] armJoints = new int[0];
    private int armRoot = -1, hand = -1;
    private boolean dual;

    private final Matrix4f m = new Matrix4f(), view = new Matrix4f();
    private final Vector3f shoulder = new Vector3f();
    /** Scratch: the gun's pose (pitch, yaw, roll, x, y, z), the arm's down / up weights and poses. */
    private final float[] k = new float[6], w = new float[2];
    private final Quaternionf qDown = new Quaternionf(), qUp = new Quaternionf(), q = new Quaternionf();
    private final Vector3f offDown = new Vector3f(), offUp = new Vector3f(), off = new Vector3f(), from = new Vector3f(),
            to = new Vector3f(), turned = new Vector3f();
    private final Quaternionf inverse = new Quaternionf();

    /**
     * The server resupplied the gun in {@code slot} from the crate at {@code pos}: play the reach (and the reload after it
     * when {@code reload}).
     */
    void start(int slot, boolean reload, @Nullable BlockPos pos) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !ClientGuns.holdingGun() || player.getInventory().getSelectedSlot() != slot) return;
        this.slot = slot;
        this.reload = reload;
        crate = pos == null ? null : pos.immutable();
        startNanos = System.nanoTime();
        eventsPlayed = 0;
        active = true;
    }

    /** Client tick: ends the resupply (handing over to the reload) or drops it when the gun went away. */
    void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.isAlive() || !ClientGuns.holdingGun() || player.getInventory().getSelectedSlot() != slot) {
            active = false;
            return;
        }
        ResupplyMotion motion = ResupplyMotion.get();
        float t = elapsed();
        List<List<Object>> events = motion.events;
        while (eventsPlayed < events.size() && t >= ((Number) events.get(eventsPlayed).get(0)).floatValue()) {
            String id = String.valueOf(events.get(eventsPlayed).get(1));
            if (id.startsWith("supply.")) {
                player.level().playLocalSound(player.getX(), player.getEyeY() - 0.5, player.getZ(),
                        SupplyContent.sound(id), SoundSource.PLAYERS, 0.8f, 0.96f + player.getRandom().nextFloat() * 0.08f, false);
            }
            eventsPlayed++;
        }
        if (t >= motion.duration(reload)) {
            active = false;
            if (reload) ClientGuns.requestReload();
        }
    }

    boolean active() {
        return active;
    }

    private float elapsed() {
        return (System.nanoTime() - startNanos) / 1e9f;
    }

    // ---- WeaponOverlays ---------------------------------------------------------------------------

    @Override
    public boolean busy() {
        return active;
    }

    @Override
    public boolean claimsUse() {
        return ClientSupply.crateInReach() != null;
    }

    @Override
    public void adjustCamera(Camera camera, float[] angles) {
        lookPitch = lookYaw = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!active || crate == null || mc.level == null || mc.player == null) return;
        ResupplyMotion.Look look = ResupplyMotion.get().look;
        float t = elapsed(), w = look.weight(reload, t);
        if (w > 1e-4f) {
            // lean over it: towards it on the level and down, more the lower its top lies under the eye; never into a block
            Vec3 eye = camera.getPosition();
            Vec3 top = crateTop(mc.level);
            float below = (float) Math.max(0, eye.y - top.y);
            float forward = Math.min(look.leanMax[0], look.lean[0] + look.leanPerMetre[0] * below) * w;
            float down = Math.min(look.leanMax[1], look.lean[1] + look.leanPerMetre[1] * below) * w;
            Vec3 level = new Vec3(top.x - eye.x, 0, top.z - eye.z);
            level = level.lengthSqr() > 1e-6 ? level.normalize() : Vec3.ZERO;
            Vec3 leaned = eye.add(level.scale(forward)).add(0, -down, 0);
            HitResult hit = mc.level.clip(new ClipContext(eye, leaned, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) leaned = eye.add(hit.getLocation().subtract(eye).scale(0.5));
            CameraRig.place(camera, leaned);
            // and look into it from there: a share of the way to its inside (the eyes do the rest)
            Vec3 into = new Vec3(top.x, top.y - look.inside, top.z).subtract(leaned);
            float pitch = (float) Math.toDegrees(Math.atan2(-into.y, Math.hypot(into.x, into.z)));
            float yaw = (float) Math.toDegrees(Math.atan2(-into.x, into.z));
            lookPitch = Mth.clamp(pitch - angles[0], -look.maxPitch, look.maxPitch) * look.share * w;
            lookYaw = Mth.clamp(Mth.wrapDegrees(yaw - angles[1]), -look.maxYaw, look.maxYaw) * look.share * w;
            angles[0] += lookPitch;
            angles[1] += lookYaw;
        }
        // the hand rummaging shakes the view; the hand hitting the contents and the grab jolt it
        float[] r = look.rummage;
        if (r.length >= 4 && t >= r[0] && t < r[1] && r[1] > r[0]) {
            float amp = r[2] * Mth.sin((float) Math.PI * (t - r[0]) / (r[1] - r[0]));
            float ph = t * r[3] * Mth.TWO_PI;
            angles[0] += amp * (0.6f * Mth.sin(ph) + 0.4f * Mth.sin(ph * 1.7f + 1.3f));
            angles[1] += amp * 0.5f * Mth.sin(ph * 0.8f + 2.1f);
            angles[2] += amp * 0.4f * Mth.sin(ph * 1.3f + 0.7f);
        }
        for (float[] j : look.jolts) {
            float tau = t - j[0];
            if (tau < 0 || j.length < 4) continue;
            float ring = (float) Math.exp(-look.joltDecay * tau) * Mth.sin(Mth.TWO_PI * look.joltHz * tau);
            angles[0] += j[1] * ring;
            angles[1] += j[2] * ring;
            angles[2] += j[3] * ring;
        }
    }

    /** The middle of the crate's top (its shape's top, in the world). */
    private Vec3 crateTop(Level level) {
        VoxelShape shape = level.getBlockState(crate).getShape(level, crate);
        double y = shape.isEmpty() ? 0.4 : shape.max(Direction.Axis.Y);
        return new Vec3(crate.getX() + 0.5, crate.getY() + y, crate.getZ() + 0.5);
    }

    @Override
    public void adjustRoot(Matrix4f root) {
        if (!active) return;
        ResupplyMotion motion = ResupplyMotion.get();
        // the hands hold the gun at the chest: as the head turns down to the crate it holds still against part of that
        if (lookPitch != 0 || lookYaw != 0) {
            float stays = motion.look.gunStays;
            root.rotateLocalX((float) Math.toRadians(stays * lookPitch)).rotateLocalY((float) Math.toRadians(stays * lookYaw));
        }
        if (!motion.gun.pose(reload, elapsed(), k)) return;
        float[] p = motion.pivot;
        root.translate(k[3], k[4], k[5])
                .translate(p[0], p[1], p[2])
                .rotateX((float) Math.toRadians(k[0]))
                .rotateY((float) Math.toRadians(k[1]))
                .rotateZ((float) Math.toRadians(k[2]))
                .translate(-p[0], -p[1], -p[2]);
    }

    @Override
    public void afterPose(ViewmodelRig<?> rig, Matrix4f base, int blockLight, int skyLight) {
        if (!active || rig.model() == null) return;
        GpuModel model = rig.model();
        findJoints(model);
        if (armRoot < 0 || dual) return;
        ResupplyMotion motion = ResupplyMotion.get();
        float t = elapsed();
        int magazine = magazineNode(model.source);
        ResupplyMotion.Arm arm = motion.arm;
        if (hand >= 0 && arm.weights(reload, t, w)) {
            // the whole arm moves about its shoulder (a rigid move of every joint keeps the skinned arm whole): down into
            // the crate, then turned so the wrist comes under the gun's magazine well
            rig.world(armRoot).getTranslation(shoulder);
            if (!reachIntoCrate(rig, motion.hand)) {
                float[] d = arm.down;
                qDown.rotationY((float) Math.toRadians(d[1])).rotateX((float) Math.toRadians(d[0])).rotateZ((float) Math.toRadians(d[2]));
                offDown.set(d[3], d[4], d[5]);
            }
            if (magazine >= 0) {
                rig.world(hand).getTranslation(from).sub(shoulder);
                rig.world(magazine).getTranslation(to).add(arm.up[0], arm.up[1], arm.up[2]).sub(shoulder);
                qUp.rotationTo(from, to);
                offUp.set(to).sub(qUp.transform(from));
            } else {
                qUp.set(qDown);
                offUp.set(offDown);
            }
            q.identity().slerp(qDown, w[0]).slerp(qUp, w[1]);
            off.set(offDown).mul(w[0]).lerp(offUp, w[1]);
            m.translation(shoulder.x + off.x, shoulder.y + off.y, shoulder.z + off.z).rotate(q)
                    .translate(-shoulder.x, -shoulder.y, -shoulder.z);
            for (int joint : armJoints) rig.world(joint).mulLocal(m);
        }
        if (hand < 0 || magazine < 0 || !motion.magazine.shows(reload, t)) return;
        ResupplyMotion.Magazine mag = motion.magazine;
        view.set(base).mul(rig.world(hand))
                .translate(mag.offset[0], mag.offset[1], mag.offset[2])
                .rotateXYZ((float) Math.toRadians(mag.rotation[0]), (float) Math.toRadians(mag.rotation[1]),
                        (float) Math.toRadians(mag.rotation[2]));
        model.addNode(rig.draws(), magazine, view, GpuModel.Style.NONE, "", blockLight, skyLight, 1);
    }

    /**
     * The arm's turn about the shoulder ({@link #qDown}, {@link #offDown}) that puts the hand into the crate where it
     * really stands: a point inside it, towards the player, seen through the camera this frame (the viewmodel is drawn in
     * view space), at most {@code reachMax} from the shoulder. False when there is no crate to reach into.
     */
    private boolean reachIntoCrate(ViewmodelRig<?> rig, ResupplyMotion.Hand spec) {
        Minecraft mc = Minecraft.getInstance();
        if (crate == null || mc.level == null || mc.player == null) return false;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 top = crateTop(mc.level), eye = camera.getPosition();
        Vec3 level = new Vec3(eye.x - top.x, 0, eye.z - top.z);
        level = level.lengthSqr() > 1e-6 ? level.normalize().scale(spec.toward) : Vec3.ZERO;
        Vec3 target = top.add(level).add(0, -spec.inside, 0).subtract(eye);
        to.set((float) target.x, (float) target.y, (float) target.z);
        camera.rotation().conjugate(inverse).transform(to);
        to.sub(shoulder);
        if (to.length() > spec.reachMax) to.normalize(spec.reachMax);
        rig.world(hand).getTranslation(from).sub(shoulder);
        qDown.rotationTo(from, to);
        offDown.set(to).sub(qDown.transform(from, turned));
        return true;
    }

    /** The node of the held gun's fitted magazine in its own model, or -1 (a gun without a magazine node). */
    private static int magazineNode(GltfModel source) {
        GunStats stats = ClientGuns.stats();
        if (stats == null || stats.magazine() == null || stats.magazine().node.isEmpty()) return -1;
        int node = source.node(stats.magazine().node);
        return node >= 0 && source.nodes.get(node).mesh >= 0 ? node : -1;
    }

    private void findJoints(GpuModel model) {
        if (model == jointsOf) return;
        jointsOf = model;
        GltfModel source = model.source;
        armRoot = source.node("arm_L");
        hand = source.node("arm_L_hand");
        dual = source.node("gun_L") >= 0;
        if (armRoot < 0) {
            armJoints = new int[0];
            return;
        }
        int count = 0;
        int[] found = new int[source.nodes.size()];
        for (int i = 0; i < source.nodes.size(); i++) {
            for (int n = i; n >= 0; n = source.nodes.get(n).parent) {
                if (n == armRoot) {
                    found[count++] = i;
                    break;
                }
            }
        }
        armJoints = java.util.Arrays.copyOf(found, count);
    }
}
