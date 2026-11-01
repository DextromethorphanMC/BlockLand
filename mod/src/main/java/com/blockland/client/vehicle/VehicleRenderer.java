package com.blockland.client.vehicle;

import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ObjectMotion;
import com.blockland.client.render.ShadowPass;
import com.blockland.combat.HitShapes;
import com.blockland.vehicle.VehicleData;
import com.blockland.vehicle.VehicleEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a vehicle as its GLB (vehicle spec §3, §5) with the weapon pipeline, lit by the world like the weapons and the
 * ammo crate: the hull turned and tilted, and the parts its state moves — wheels rolling with the ground covered, the
 * steering (knuckles, the bike's fork), tracks' road wheels at each track's own speed, the boat's propeller and
 * outboard, each weapon's yaw and pitch pivots (the barrel kicked back by its recoil), an aircraft's rotors, its
 * landing gear folding away and its afterburner's flame (shown only while boosting). While the sun's shadow map
 * captures entities its triangles go through vanilla render types, so it casts a shadow. Until its model exists (or if
 * it fails to load) its hull boxes are drawn as outlines, so nothing is invisible.
 */
final class VehicleRenderer extends EntityRenderer<VehicleEntity, VehicleRenderer.State> {
    private static final DrawList draws = new DrawList().scene(true);
    /** The front wheels' (and the bike's fork's) angle at full steer, degrees. */
    private static final float MAX_STEER = 30;
    /** How far a barrel kicks back at the shot (metres). */
    private static final float RECOIL = 0.28f;

    static final class State extends EntityRenderState {
        @Nullable
        VehicleEntity vehicle;
        float partialTick;
    }

    /** A track's links running round: its material's v moves with the track's speed. */
    private static final class TrackStyle implements GpuModel.Style {
        float scroll;

        @Override
        public float uvScroll(String material) {
            return "track".equals(material) ? scroll : 0;
        }
    }

    /** A model's pose and what moves which node. */
    private static final class Rig {
        final Pose pose;
        final List<int[]> moves = new ArrayList<>();
        final float[][] rest;
        final int trackLeft, trackRight, afterburner;
        /** The track material's v per metre (its extras, else the data's), 0 = no track. */
        final float trackPerMetre;

        Rig(GpuModel model, VehicleData.Vehicle data) {
            GltfModel source = model.source;
            pose = new Pose(source);
            trackLeft = source.node("track_l");
            trackRight = source.node("track_r");
            afterburner = source.node("afterburner");
            float perMetre = data.movement.trackUvPerMetre;
            for (GltfModel.Material m : source.materials) if ("track".equals(m.name) && m.scrollPerMetre > 0) perMetre = m.scrollPerMetre;
            trackPerMetre = perMetre;
            rest = new float[source.nodes.size()][];
            for (int i = 0; i < source.nodes.size(); i++) {
                rest[i] = source.nodes.get(i).rotation.clone();
                String name = source.nodes.get(i).name;
                int kind = name.startsWith("wheel_") ? WHEEL
                        : name.startsWith("steer_") || name.equals("fork") ? STEER
                        : name.startsWith("roadwheel_l") || name.equals("sprocket_l") || name.equals("idler_l") ? TRACK_LEFT
                        : name.startsWith("roadwheel_r") || name.equals("sprocket_r") || name.equals("idler_r") ? TRACK_RIGHT
                        : name.equals("propeller") ? PROPELLER
                        : name.equals("outboard") ? OUTBOARD
                        : name.equals("rotor_main") ? ROTOR_MAIN
                        : name.equals("rotor_tail") ? ROTOR_TAIL
                        : name.startsWith("gear_") ? GEAR : -1;
                if (kind >= 0) moves.add(new int[]{i, kind, 0});
            }
            for (int w = 0; w < data.weapons.size(); w++) {
                VehicleData.Weapon weapon = data.weapons.get(w);
                int yaw = source.node(weapon.pivotYaw), pitch = source.node(weapon.pivotPitch);
                if (yaw >= 0 && yaw == pitch) {
                    moves.add(new int[]{yaw, AIM_BOTH, w});
                } else {
                    if (yaw >= 0) moves.add(new int[]{yaw, AIM_YAW, w});
                    if (pitch >= 0) moves.add(new int[]{pitch, AIM_PITCH, w});
                }
            }
        }
    }

    private static final int WHEEL = 0, STEER = 1, TRACK_LEFT = 2, TRACK_RIGHT = 3, PROPELLER = 4, OUTBOARD = 5, AIM_YAW = 6,
            AIM_PITCH = 7, AIM_BOTH = 8, ROTOR_MAIN = 9, ROTOR_TAIL = 10, GEAR = 11;
    /** How much faster the tail rotor turns than the main one. */
    private static final float TAIL_ROTOR = 4.5f;

    private final Map<GpuModel, Rig> rigs = new WeakHashMap<>();
    private final TrackStyle leftTrack = new TrackStyle(), rightTrack = new TrackStyle();
    /** Per vehicle type: the model-space box of the hull and its riders (centre xyz, half xyz). */
    private final Map<VehicleData.Vehicle, float[]> boxes = new java.util.IdentityHashMap<>();
    private final Quaternionf motionTurn = new Quaternionf();
    private final Vector3f motionCenter = new Vector3f(), motionHalf = new Vector3f();
    private final Matrix4f local = new Matrix4f(), base = new Matrix4f(), scratch = new Matrix4f();
    private final Quaternionf q = new Quaternionf();

    VehicleRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(VehicleEntity vehicle, State state, float partialTick) {
        super.extractRenderState(vehicle, state, partialTick);
        state.vehicle = vehicle;
        state.partialTick = partialTick;
    }

    /** The hull boxes and the guns reach past the entity's square box: cull by what is drawn. */
    @Override
    protected AABB getBoundingBoxForCulling(VehicleEntity vehicle) {
        AABB box = vehicle.getBoundingBox();
        for (HitShapes.Box b : vehicle.hitBoxes(0.5)) box = box.minmax(b.bounds());
        float rotor = vehicle.data.movement.rotorRadius;
        if (rotor > 0) box = box.minmax(vehicle.getBoundingBox().inflate(rotor, 0, rotor));
        return box.inflate(2.5);
    }

    @Override
    public void render(State state, PoseStack stack, MultiBufferSource buffers, int light) {
        VehicleEntity vehicle = state.vehicle;
        if (vehicle == null) return;
        float pt = state.partialTick;
        GpuModel model = ModelCache.get(ResourceLocation.parse(vehicle.data.model));
        if (model == null || model.isClosed()) {
            outline(vehicle, stack, buffers);
            return;
        }
        Rig rig = rigs.computeIfAbsent(model, m -> new Rig(m, vehicle.data));
        pose(rig, vehicle, pt);
        float yaw = Mth.rotLerp(pt, vehicle.yRotO, vehicle.getYRot());
        local.identity().rotateY((float) (Math.PI - Math.toRadians(yaw)))
                .rotateX((float) Math.toRadians(vehicle.hullPitch(pt))).rotateZ((float) Math.toRadians(vehicle.hullRoll(pt)));
        boolean vanilla = ShadowPass.active();
        if (!vanilla) {
            // its own motion for TAA, DLSS and the motion blur: the hull and whoever rides on it
            Vec3 at = vehicle.getPosition(pt);
            float[] box = box(vehicle.data);
            ObjectMotion.drawn(vehicle.getId(), at.x, at.y, at.z, local.getNormalizedRotation(motionTurn),
                    motionCenter.set(box[0], box[1], box[2]), motionHalf.set(box[3], box[4], box[5]));
        }
        if (vanilla) base.set(local);
        else base.set(RenderSystem.getModelViewMatrix()).mul(stack.last().pose()).mul(local);
        if (vanilla) stack.pushPose();
        int block = LightTexture.block(light), sky = LightTexture.sky(light);
        float perMetre = rig.trackPerMetre * vehicle.data.movement.wheelRadius;
        leftTrack.scroll = Mth.lerp(pt, vehicle.trackLeftO, vehicle.trackLeft) * perMetre;
        rightTrack.scroll = Mth.lerp(pt, vehicle.trackRightO, vehicle.trackRight) * perMetre;
        GltfModel source = model.source;
        for (int index : source.order) {
            if (source.nodes.get(index).mesh < 0 || rig.pose.isCollapsed(index)) continue;
            if (index == rig.afterburner && !vehicle.boosting()) continue;
            GpuModel.Style style = index == rig.trackLeft ? leftTrack : index == rig.trackRight ? rightTrack : GpuModel.Style.NONE;
            model.addNode(draws, index, scratch.set(base).mul(rig.pose.world[index]), style, "", block, sky, 1);
        }
        if (vanilla) {
            draws.flushVanilla(stack, buffers);
            stack.popPose();
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
        draws.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), "BlockLand vehicle");
    }

    /** Moves the rig's nodes by the vehicle's state at this frame. */
    private void pose(Rig rig, VehicleEntity v, float pt) {
        rig.pose.reset();
        float wheel = Mth.lerp(pt, v.wheelTurnO, v.wheelTurn);
        float left = Mth.lerp(pt, v.trackLeftO, v.trackLeft), right = Mth.lerp(pt, v.trackRightO, v.trackRight);
        float steer = Mth.lerp(pt, v.steerO, v.steer);
        float rotor = Mth.lerp(pt, v.rotorO, v.rotor), gear = Mth.lerp(pt, v.gearO, v.gear);
        for (int[] move : rig.moves) {
            int node = move[0], weapon = move[2];
            float[] r = rig.rest[node];
            q.set(r[0], r[1], r[2], r[3]);
            switch (move[1]) {
                case WHEEL -> q.rotateX(-wheel);
                case STEER -> q.rotateY((float) Math.toRadians(-steer * MAX_STEER));
                case TRACK_LEFT -> q.rotateX(-left);
                case TRACK_RIGHT -> q.rotateX(-right);
                case PROPELLER -> q.rotateZ(wheel * 3);
                case OUTBOARD -> q.rotateY((float) Math.toRadians(steer * 25));
                case ROTOR_MAIN -> q.rotateY(rotor);
                case ROTOR_TAIL -> q.rotateX(rotor * TAIL_ROTOR);
                case GEAR -> q.rotateX((float) Math.toRadians(v.data.movement.gearFold * gear));
                case AIM_YAW -> q.rotateY((float) Math.toRadians(-yaw(v, weapon, pt)));
                case AIM_PITCH -> q.rotateX((float) Math.toRadians(pitch(v, weapon, pt)));
                case AIM_BOTH -> q.rotateY((float) Math.toRadians(-yaw(v, weapon, pt))).rotateX((float) Math.toRadians(pitch(v, weapon, pt)));
                default -> { }
            }
            rig.pose.setRotation(node, q);
            if ((move[1] == AIM_PITCH || move[1] == AIM_BOTH) && v.recoil[weapon] > 0.01f) {
                // the barrel kicks back along its own axis (local +Z)
                Vector3f back = q.transform(new Vector3f(0, 0, RECOIL * v.recoil[weapon]));
                rig.pose.addTranslation(node, back.x, back.y, back.z);
            }
        }
        rig.pose.computeWorld(new Matrix4f());
    }

    /** The box (model space) holding the hull boxes and a rider's body over every seat, as centre and half extents. */
    private float[] box(VehicleData.Vehicle data) {
        return boxes.computeIfAbsent(data, d -> {
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (VehicleData.HitBox b : d.hitBoxes) {
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], b.center[a] - b.half[a]);
                    max[a] = Math.max(max[a], b.center[a] + b.half[a]);
                }
            }
            for (VehicleData.Seat seat : d.seats) {
                // a turret seat swings round the turret: its reach in every direction
                float[] p = seat.pos.clone();
                if (seat.onTurret() && !d.weapons.isEmpty()) {
                    float[] t = d.weapons.get(0).pivot;
                    float r = (float) Math.hypot(p[0], p[2]) + 0.4f;
                    min[0] = Math.min(min[0], t[0] - r);
                    max[0] = Math.max(max[0], t[0] + r);
                    min[2] = Math.min(min[2], t[2] - r);
                    max[2] = Math.max(max[2], t[2] + r);
                    p = new float[]{t[0], t[1] + p[1], t[2]};
                }
                min[0] = Math.min(min[0], p[0] - 0.45f);
                max[0] = Math.max(max[0], p[0] + 0.45f);
                min[1] = Math.min(min[1], p[1] - 0.8f);
                max[1] = Math.max(max[1], p[1] + 1.2f);
                min[2] = Math.min(min[2], p[2] - 0.6f);
                max[2] = Math.max(max[2], p[2] + 0.6f);
            }
            // a helicopter's main rotor sweeps far past its hull
            float rotor = d.movement.rotorRadius;
            if (rotor > 0) {
                min[0] = Math.min(min[0], -rotor);
                max[0] = Math.max(max[0], rotor);
                min[2] = Math.min(min[2], -rotor);
                max[2] = Math.max(max[2], rotor);
            }
            if (min[0] > max[0]) return new float[]{0, 0.5f, 0, 1, 0.5f, 1};
            return new float[]{(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2,
                    (max[0] - min[0]) / 2 + 0.1f, (max[1] - min[1]) / 2 + 0.1f, (max[2] - min[2]) / 2 + 0.1f};
        });
    }

    private static float yaw(VehicleEntity v, int weapon, float pt) {
        return v.aimYawO[weapon] + Mth.wrapDegrees(v.shownYaw[weapon] - v.aimYawO[weapon]) * pt;
    }

    private static float pitch(VehicleEntity v, int weapon, float pt) {
        return Mth.lerp(pt, v.aimPitchO[weapon], v.shownPitch[weapon]);
    }

    /** No model yet: the hull boxes as outlines (relative to where the entity is drawn). */
    private static void outline(VehicleEntity vehicle, PoseStack stack, MultiBufferSource buffers) {
        if (ShadowPass.active()) return;
        // the boxes are where the vehicle is now; the stack's origin is where it is drawn this frame, which they follow
        Vec3 now = vehicle.position();
        for (HitShapes.Box b : vehicle.hitBoxes(0)) {
            AABB box = b.bounds().move(-now.x, -now.y, -now.z);
            ShapeRenderer.renderLineBox(stack, buffers.getBuffer(RenderType.lines()), box, 1f, 0.8f, 0.2f, 1f);
        }
    }
}
