package com.blockland.client.decal;

import com.blockland.client.render.EntityPoses;
import com.blockland.client.render.WorldFx;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Damage decals: bullet holes, knife slashes and stabs on blocks, cuts on bodies. Each decal has its own lifetime
 * (its type's lifetime ± jitter) and fades out at the end of it; a type holds at most {@code max} at once, the
 * oldest making room. Block decals lie flat on the struck face, clipped to it, and vanish when the block changes;
 * body decals lie on the struck cube of the entity's drawn model ({@link EntityPoses}), clipped to its face, and follow
 * it as the model animates (not drawn in a frame the model was not drawn in); bodies whose model was not drawn lately
 * fall back to rest-pose parts ({@link BodySurface}). Everything is drawn in one batch per decal texture.
 *
 * <p>Client only and weapon-agnostic: weapons call {@link #onBlock} / {@link #onEntity} with a decal type id
 * from their data.
 */
public final class Decals {
    private static final double RENDER_DISTANCE_SQR = 96 * 96;
    /** Body decals sit this far outside the cube or part box they lie on. */
    private static final float BODY_LIFT = 0.012f;
    private static final int FORCED_FADE_TICKS = 6;

    /** Live decals per type, oldest first. */
    private static final Map<DecalTypes.Type, List<Decal>> decals = new LinkedHashMap<>();
    @Nullable
    private static ClientLevel level;
    private static final Random random = new Random();
    private static int tickCount;

    private static final class Decal {
        final DecalTypes.Type type;
        final DecalGeometry.Polygon polygon;
        /** Outward normal in the anchor's space. */
        final Vector3f normal;
        final long dieAt;
        long fadeTicks;
        long forcedDieAt = Long.MAX_VALUE;
        // block decals: positions relative to the block
        @Nullable
        BlockPos pos;
        @Nullable
        BlockState state;
        int light;
        float shade = 1;
        // body decals: positions in the model cube's space (modelPath set) or in a rest-pose part's
        int entityId = -1;
        @Nullable
        String modelPath;
        int modelCube;
        @Nullable
        BodySurface.Part part;

        Decal(DecalTypes.Type type, DecalGeometry.Polygon polygon, Vector3f normal, long now) {
            this.type = type;
            this.polygon = polygon;
            this.normal = normal;
            float life = Math.max(0.5f, type.lifetime + (random.nextFloat() * 2 - 1) * type.lifetimeJitter);
            this.dieAt = now + Math.round(life * 20);
            this.fadeTicks = Math.max(1, Math.min(Math.round(type.fade * 20), Math.round(life * 20)));
        }

        float alpha(double ticks) {
            double end = Math.min(dieAt, forcedDieAt);
            long fade = forcedDieAt < dieAt ? FORCED_FADE_TICKS : fadeTicks;
            return (float) Mth.clamp((end - ticks) / fade, 0, 1) * type.opacity;
        }

        boolean expired(long now) {
            return now >= Math.min(dieAt, forcedDieAt);
        }
    }

    private Decals() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    DecalTypes.clear();
                    clear();
                }));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        // body decals lie on the drawn models
        EntityPoses.init();
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        // on the faces: under the dust, smoke and debris in front of them
        WorldFx.register(WorldFx.Layer.SURFACE, Decals::render);
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && !decals.isEmpty()) {
                event.getText().add("[BlockLand] decals: " + count());
            }
            return false;
        });
    }

    // ---- spawning -----------------------------------------------------------------------------------

    /**
     * A decal on the face of a block.
     *
     * @param point where the face was struck
     * @param along direction the decal's length follows (projected onto the face), null = random rotation
     */
    public static void onBlock(String typeId, Vec3 point, Direction face, BlockPos pos, @Nullable Vec3 along) {
        DecalTypes.Type type = DecalTypes.get(typeId);
        ClientLevel world = Minecraft.getInstance().level;
        if (type == null || world == null) return;
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return;
        Vector3f normal = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());
        Vector3f centre = new Vector3f((float) (point.x - pos.getX()), (float) (point.y - pos.getY()), (float) (point.z - pos.getZ()));
        float[] rect = faceRect(world, pos, state, face, centre);
        DecalGeometry.Polygon polygon = layOut(type, centre, normal, along, face.getAxis().ordinal(), rect);
        if (polygon == null) return;
        Decal decal = new Decal(type, polygon, normal, world.getGameTime());
        decal.pos = pos.immutable();
        decal.state = state;
        decal.light = LevelRenderer.getLightColor(world, pos.relative(face));
        decal.shade = world.getShade(face, true);
        add(world, decal);
    }

    /**
     * A decal on a body, at the part the attack's line meets.
     *
     * @param from  where the attack came from (the attacker's eye)
     * @param point where it struck the entity
     * @param along direction the decal's length follows, null = random rotation
     */
    public static void onEntity(String typeId, Entity target, Vec3 from, Vec3 point, @Nullable Vec3 along) {
        DecalTypes.Type type = DecalTypes.get(typeId);
        ClientLevel world = Minecraft.getInstance().level;
        if (type == null || world == null || !target.isAlive()) return;
        Vec3 dir = point.subtract(from);
        if (dir.lengthSqr() < 1e-6) return;
        BodySurface.ModelAnchor onModel = BodySurface.onModel(target, from, dir.normalize());
        if (onModel != null) {
            onModel(type, world, target, onModel, along);
            return;
        }
        BodySurface.Anchor anchor = BodySurface.anchor(target, from, dir.normalize());
        // the cut direction in part space, sized in part units (humanoid parts are scaled with the entity)
        Matrix4f toLocal = BodySurface.partToWorld(target, anchor.part(), 1, from, new Matrix4f()).invert();
        Vec3 localAlong = null;
        if (along != null) {
            Vector3f a = toLocal.transformDirection(new Vector3f((float) along.x, (float) along.y, (float) along.z));
            localAlong = new Vec3(a.x, a.y, a.z);
        }
        float scale = anchor.part() == BodySurface.Part.BOX ? 1 : Math.max(0.3f, target.getBbWidth() / 0.6f);
        int axis = Math.abs(anchor.normal().x) > 0.5f ? 0 : Math.abs(anchor.normal().y) > 0.5f ? 1 : 2;
        float[] b = anchor.bounds();
        int sAxis = axis == 0 ? 1 : 0, tAxis = axis == 2 ? 1 : 2;
        float[] rect = {b[sAxis], b[tAxis], b[sAxis + 3], b[tAxis + 3]};
        Vector3f centre = new Vector3f(anchor.point()).fma(BODY_LIFT / scale, anchor.normal());
        DecalGeometry.Polygon polygon = layOut(type, centre, anchor.normal(), localAlong, axis, rect, 1 / scale);
        if (polygon == null) return;
        Decal decal = new Decal(type, polygon, new Vector3f(anchor.normal()), world.getGameTime());
        decal.entityId = target.getId();
        decal.part = anchor.part();
        add(world, decal);
    }

    /** A body decal on a cube of the drawn model, laid out in the cube's space (model pixels) and clipped to its face. */
    private static void onModel(DecalTypes.Type type, ClientLevel world, Entity target, BodySurface.ModelAnchor anchor, @Nullable Vec3 along) {
        Matrix4f toWorld = anchor.toWorld();
        // blocks per model pixel here (the entity's scale, a baby's, the 1/16)
        Vector3f scale = toWorld.getScale(new Vector3f());
        float size = (scale.x + scale.y + scale.z) / 3;
        if (size < 1e-6f) return;
        Vec3 localAlong = null;
        if (along != null) {
            Vector3f a = new Matrix4f(toWorld).invert().transformDirection(new Vector3f((float) along.x, (float) along.y, (float) along.z));
            localAlong = new Vec3(a.x, a.y, a.z);
        }
        Vector3f normal = anchor.normal();
        int axis = Math.abs(normal.x) > 0.5f ? 0 : Math.abs(normal.y) > 0.5f ? 1 : 2;
        float[] b = anchor.bounds();
        int sAxis = axis == 0 ? 1 : 0, tAxis = axis == 2 ? 1 : 2;
        float[] rect = {b[sAxis], b[tAxis], b[sAxis + 3], b[tAxis + 3]};
        Vector3f centre = new Vector3f(anchor.point()).fma(BODY_LIFT / size, normal);
        DecalGeometry.Polygon polygon = layOut(type, centre, normal, localAlong, axis, rect, 1 / size);
        if (polygon == null) return;
        Decal decal = new Decal(type, polygon, new Vector3f(normal), world.getGameTime());
        decal.entityId = target.getId();
        decal.modelPath = anchor.path();
        decal.modelCube = anchor.cube();
        add(world, decal);
    }

    @Nullable
    private static DecalGeometry.Polygon layOut(DecalTypes.Type type, Vector3f centre, Vector3f normal, @Nullable Vec3 along,
                                                int axis, float[] rect) {
        return layOut(type, centre, normal, along, axis, rect, 1);
    }

    /** Orients and sizes the decal quad on the face plane, picks a variant and clips it to {@code rect}. */
    @Nullable
    private static DecalGeometry.Polygon layOut(DecalTypes.Type type, Vector3f centre, Vector3f normal, @Nullable Vec3 along,
                                                int axis, float[] rect, float unit) {
        Vector3f u = new Vector3f();
        if (along != null) {
            u.set((float) along.x, (float) along.y, (float) along.z);
            u.fma(-u.dot(normal), normal);
        }
        if (along == null || u.lengthSquared() < 1e-4f) {
            // random rotation in the face plane
            Vector3f tangent = Math.abs(normal.y) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            u.set(tangent).fma(-tangent.dot(normal), normal).normalize();
            u.rotateAxis(random.nextFloat() * Mth.TWO_PI, normal.x, normal.y, normal.z);
        }
        u.normalize();
        Vector3f v = new Vector3f(normal).cross(u).normalize();
        float jitter = 1 + (random.nextFloat() * 2 - 1) * type.sizeJitter;
        float halfW = type.size[0] * 0.5f * jitter * unit;
        float halfH = (type.size.length > 1 ? type.size[1] : type.size[0]) * 0.5f * jitter * unit;
        int variant = random.nextInt(type.variants);
        return DecalGeometry.clip(centre, u.mul(halfW), v.mul(halfH), axis, rect,
                variant / (float) type.variants, (variant + 1) / (float) type.variants);
    }

    /**
     * The struck face's rectangle in block-local coordinates: the box of the block's shape that the point lies on,
     * extended over neighbouring blocks that continue the same flat surface (so a hole near a block edge on a wall
     * is not cut in half).
     */
    private static float[] faceRect(ClientLevel world, BlockPos pos, BlockState state, Direction face, Vector3f point) {
        int axis = face.getAxis().ordinal();
        int sAxis = axis == 0 ? 1 : 0, tAxis = axis == 2 ? 1 : 2;
        float[] rect = {0, 0, 1, 1};
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) shape = state.getShape(world, pos);
        float plane = point.get(axis);
        for (AABB box : shape.toAabbs()) {
            float boxPlane = (float) (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(face.getAxis()) : box.min(face.getAxis()));
            if (Math.abs(boxPlane - plane) > 2e-3f) continue;
            float s = point.get(sAxis), t = point.get(tAxis);
            float s0 = (float) min(box, sAxis), s1 = (float) max(box, sAxis), t0 = (float) min(box, tAxis), t1 = (float) max(box, tAxis);
            if (s < s0 - 2e-3f || s > s1 + 2e-3f || t < t0 - 2e-3f || t > t1 + 2e-3f) continue;
            rect = new float[]{s0, t0, s1, t1};
            break;
        }
        boolean fullFace = Math.abs(plane - (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0)) < 2e-3f;
        if (!fullFace) return rect;
        Direction[] sides = {along(sAxis, false), along(tAxis, false), along(sAxis, true), along(tAxis, true)};
        for (int i = 0; i < 4; i++) {
            boolean atEdge = i < 2 ? rect[i] < 2e-3f : rect[i] > 1 - 2e-3f;
            if (atEdge && continuesSurface(world, pos.relative(sides[i]), face)) rect[i] += i < 2 ? -1 : 1;
        }
        return rect;
    }

    private static boolean continuesSurface(ClientLevel world, BlockPos neighbour, Direction face) {
        return world.getBlockState(neighbour).isCollisionShapeFullBlock(world, neighbour)
                && !world.getBlockState(neighbour.relative(face)).isCollisionShapeFullBlock(world, neighbour.relative(face));
    }

    private static Direction along(int axis, boolean positive) {
        Direction.Axis a = Direction.Axis.values()[axis];
        return Direction.fromAxisAndDirection(a, positive ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
    }

    private static double min(AABB box, int axis) {
        return box.min(Direction.Axis.values()[axis]);
    }

    private static double max(AABB box, int axis) {
        return box.max(Direction.Axis.values()[axis]);
    }

    private static void add(ClientLevel world, Decal decal) {
        if (level != world) clear();
        level = world;
        List<Decal> list = decals.computeIfAbsent(decal.type, t -> new ArrayList<>());
        list.add(decal);
        // over the limit: the oldest fade out quickly (and are dropped outright far over it)
        int alive = 0;
        for (Decal d : list) if (d.forcedDieAt == Long.MAX_VALUE) alive++;
        long now = world.getGameTime();
        for (int i = 0; i < list.size() && alive > decal.type.max; i++) {
            Decal old = list.get(i);
            if (old.forcedDieAt != Long.MAX_VALUE) continue;
            old.forcedDieAt = now + FORCED_FADE_TICKS;
            alive--;
        }
        while (list.size() > decal.type.max + 32) list.remove(0);
        WorldFx.wake(world);
    }

    // ---- ticking ------------------------------------------------------------------------------------

    private static void tick() {
        ClientLevel world = Minecraft.getInstance().level;
        if (world != level) {
            clear();
            level = world;
        }
        if (world == null || decals.isEmpty()) return;
        long now = world.getGameTime();
        boolean checkBlocks = ++tickCount % 10 == 0;
        boolean relight = tickCount % 20 == 0;
        for (Iterator<List<Decal>> lists = decals.values().iterator(); lists.hasNext(); ) {
            List<Decal> list = lists.next();
            list.removeIf(d -> {
                if (d.expired(now)) return true;
                if (d.pos != null) {
                    // broken or replaced block: its marks go with it
                    if (checkBlocks && world.getBlockState(d.pos) != d.state) return true;
                    if (relight) d.light = LevelRenderer.getLightColor(world, d.pos.offset(Math.round(d.normal.x), Math.round(d.normal.y), Math.round(d.normal.z)));
                    return false;
                }
                Entity entity = world.getEntity(d.entityId);
                return entity == null || !entity.isAlive() || entity.isSpectator();
            });
            if (list.isEmpty()) lists.remove();
        }
        if (!decals.isEmpty()) WorldFx.wake(world);
    }

    public static void clear() {
        decals.clear();
        level = null;
    }

    /** Live decals (F3 / tests). */
    public static int count() {
        int n = 0;
        for (List<Decal> list : decals.values()) n += list.size();
        return n;
    }

    // ---- rendering ----------------------------------------------------------------------------------

    private static final Matrix4f partMatrix = new Matrix4f();
    private static final Vector3f corner = new Vector3f();

    /**
     * Draws all decals from inside the particle pass (world matrices, depth of the solid world), right away: left in the
     * buffer source they would only be drawn at the end of the pass, over the dust and smoke in front of them.
     */
    private static void render(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
        ClientLevel world = level;
        if (world == null || decals.isEmpty()) return;
        Vec3 cam = camera.getPosition();
        double ticks = world.getGameTime() + partialTick;
        Matrix4f m = pose.last().pose();
        Minecraft mc = Minecraft.getInstance();
        for (Map.Entry<DecalTypes.Type, List<Decal>> entry : decals.entrySet()) {
            RenderType type = DecalRenderTypes.of(entry.getKey().textureLocation);
            VertexConsumer out = null;
            for (Decal d : entry.getValue()) {
                float alpha = d.alpha(ticks);
                if (alpha <= 0.01f) continue;
                int color = (Mth.clamp(Math.round(alpha * 255), 0, 255) << 24);
                int light;
                if (d.pos != null) {
                    double dx = d.pos.getX() + 0.5 - cam.x, dy = d.pos.getY() + 0.5 - cam.y, dz = d.pos.getZ() + 0.5 - cam.z;
                    if (dx * dx + dy * dy + dz * dz > RENDER_DISTANCE_SQR) continue;
                    // faces turned away from the camera are hidden by their own block anyway
                    if (dx * d.normal.x + dy * d.normal.y + dz * d.normal.z > 0.6) continue;
                    partMatrix.identity().translate((float) (d.pos.getX() - cam.x), (float) (d.pos.getY() - cam.y), (float) (d.pos.getZ() - cam.z));
                    int shade = Mth.clamp(Math.round(d.shade * 255), 0, 255);
                    color |= shade << 16 | shade << 8 | shade;
                    light = d.light;
                } else {
                    Entity entity = world.getEntity(d.entityId);
                    if (entity == null || entity.isInvisible() || entity.distanceToSqr(cam) > RENDER_DISTANCE_SQR) continue;
                    // the camera's own body is not drawn in first person
                    if (entity == camera.getEntity() && !camera.isDetached()) continue;
                    if (d.modelPath != null) {
                        // where its cube was drawn this frame; not drawn (culled, out of range) = no decal either
                        EntityPoses.Capture drawn = EntityPoses.drawn(entity, partialTick);
                        EntityPoses.Box box = drawn == null ? null : drawn.find(d.modelPath, d.modelCube);
                        if (box == null) continue;
                        drawn.toWorld(box, cam, null, partMatrix);
                    } else {
                        BodySurface.partToWorld(entity, d.part, partialTick, cam, partMatrix);
                    }
                    color |= 0xE6E6E6;
                    light = mc.getEntityRenderDispatcher().getPackedLightCoords(entity, partialTick);
                }
                if (out == null) out = buffers.getBuffer(type);
                emit(out, m, d.polygon, color, light);
            }
            if (out != null && buffers instanceof MultiBufferSource.BufferSource source) source.endBatch(type);
        }
    }

    /** The polygon as quads (a fan; odd leftovers repeat a corner). */
    private static void emit(VertexConsumer out, Matrix4f m, DecalGeometry.Polygon p, int color, int light) {
        int n = p.count();
        for (int i = 1; i < n - 1; i += 2) {
            vertex(out, m, p, 0, color, light);
            vertex(out, m, p, i, color, light);
            vertex(out, m, p, i + 1, color, light);
            vertex(out, m, p, Math.min(i + 2, n - 1), color, light);
        }
    }

    private static void vertex(VertexConsumer out, Matrix4f m, DecalGeometry.Polygon p, int i, int color, int light) {
        float[] xyz = p.xyz(), uv = p.uv();
        partMatrix.transformPosition(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], corner);
        out.addVertex(m, corner.x, corner.y, corner.z).setUv(uv[i * 2], uv[i * 2 + 1]).setColor(color).setLight(light);
    }
}
