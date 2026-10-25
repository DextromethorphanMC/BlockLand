package com.blockland.client.render;

import com.blockland.BlockLand;
import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The posed models of the living entities drawn this frame: every cube of the model as it was drawn — animated limbs,
 * turned head, crouch, swim, a slide's lean (everything that moved the pose stack or the render state before the draw)
 * — as a matrix from the cube's own space (model pixels) to the world, and the cube's box. Features that put
 * something on a body (wound decals) anchor it to a cube and follow the model, instead of the bounding box.
 *
 * <p>Captured after each living entity's draw ({@code RenderLivingEvent.Post}: the model still holds that entity's
 * animation, the pose stack is at the entity's origin), within {@link #RANGE} blocks, never during the shadow map's
 * entity capture or for GUI previews. Render thread only.
 */
public final class EntityPoses {
    private static final double RANGE = 96;
    /** Cube boxes in pixels, inflation included (cubes are immutable; weak: models are rebuilt on reload). */
    private static final Map<ModelPart.Cube, float[]> cubeBounds = new WeakHashMap<>();
    /**
     * Model part chains by path below the root, to tell whether a cube is drawn (a hidden part hides its children).
     * Weak by root: no part refers to its parent, so the chains never keep their root alive.
     */
    private static final Map<ModelPart, Map<String, ModelPart[]>> chains = new WeakHashMap<>();
    /**
     * Each model's part tree as {@code ModelPart.visit} walks it, every part's path and chain worked out once
     * ({@link Optimization#FRAME_GARBAGE}: no path strings, lambdas or map lookups per entity and frame). A model's parts
     * and cubes never change after it is baked (both private, final, filled from maps and lists only the bake held).
     * Weak by root, and no node refers to the root.
     */
    private static final Map<ModelPart, Node> trees = new WeakHashMap<>();
    @Nullable
    private static Field cubesField, childrenField;
    private static boolean treesBroken;
    // scratch, the render thread only
    private static final Quaternionf turn = new Quaternionf();
    private static final Vector3f originScratch = new Vector3f();
    private static final Matrix4f baseScratch = new Matrix4f();

    @Nullable
    private static Method setupRotations, scale;
    private static boolean initialised, broken;
    private static int frame;
    /** This frame's captures and the last frame's, by entity type. */
    private static Map<EntityType<?>, List<Capture>> current = new HashMap<>(), previous = new HashMap<>();
    private static final List<Capture> pool = new ArrayList<>();

    /** One drawn cube. */
    public static final class Box {
        /** Path of its model part ("/body/right_arm") and its index there: the anchor key. */
        public String path = "";
        public int cube;
        /** Cube pixels → world, relative to the entity's drawn position. */
        public final Matrix4f matrix = new Matrix4f();
        /** min xyz, max xyz in pixels. */
        public float[] bounds = new float[6];
    }

    /** An entity's model as drawn in one frame. */
    public static final class Capture {
        EntityType<?> type;
        /** Where the entity was drawn (its interpolated position). */
        public double x, y, z;
        int frame;
        final List<Box> boxes = new ArrayList<>();
        int count;

        public int size() {
            return count;
        }

        public Box box(int i) {
            return boxes.get(i);
        }

        @Nullable
        public Box find(String path, int cube) {
            for (int i = 0; i < count; i++) {
                Box b = boxes.get(i);
                if (b.cube == cube && b.path.equals(path)) return b;
            }
            return null;
        }

        /**
         * A box's space → world relative to {@code origin}, with the model placed at {@code at} (null = where it was
         * drawn; a hit test passes the entity's present position, a frame or a tick later).
         */
        public Matrix4f toWorld(Box box, Vec3 origin, @Nullable Vec3 at, Matrix4f into) {
            double px = at == null ? x : at.x, py = at == null ? y : at.y, pz = at == null ? z : at.z;
            return into.translation((float) (px - origin.x), (float) (py - origin.y), (float) (pz - origin.z)).mul(box.matrix);
        }
    }

    private EntityPoses() {
    }

    /** Idempotent: every feature that reads poses calls it from its own init. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        try {
            setupRotations = LivingEntityRenderer.class.getDeclaredMethod("setupRotations",
                    LivingEntityRenderState.class, PoseStack.class, float.class, float.class);
            setupRotations.setAccessible(true);
            scale = LivingEntityRenderer.class.getDeclaredMethod("scale", LivingEntityRenderState.class, PoseStack.class);
            scale.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            BlockLand.LOGGER.warn("[BlockLand] Entity poses unavailable, body decals use the rest pose ({})", e.toString());
            return;
        }
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> beginFrame());
        RenderLivingEvent.Post.BUS.addListener(EntityPoses::capture);
    }

    /** The entity's model as drawn this frame; null when it was not drawn (culled, out of range, first person). */
    @Nullable
    public static Capture drawn(Entity entity, float partialTick) {
        // a little slack: entities frozen by the tick rate are drawn at their tick position
        return nearest(current.get(entity.getType()), entity.getPosition(partialTick), 0.35, frame);
    }

    /** The entity's model as last drawn (this frame or the one before), for a hit test; null when it was not drawn. */
    @Nullable
    public static Capture latest(Entity entity) {
        Vec3 at = entity.position();
        double reach = entity.getBbWidth() + 1;
        Capture c = nearest(current.get(entity.getType()), at, reach, frame);
        return c != null ? c : nearest(previous.get(entity.getType()), at, reach, frame - 1);
    }

    @Nullable
    private static Capture nearest(@Nullable List<Capture> list, Vec3 at, double reach, int wantFrame) {
        if (list == null) return null;
        Capture best = null;
        double bestD = reach * reach;
        for (Capture c : list) {
            if (c.frame != wantFrame) continue;
            double dx = c.x - at.x, dy = c.y - at.y, dz = c.z - at.z, d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private static void beginFrame() {
        for (List<Capture> list : previous.values()) pool.addAll(list);
        Map<EntityType<?>, List<Capture>> swap = previous;
        swap.clear();
        previous = current;
        current = swap;
        frame++;
    }

    // ---- capture ------------------------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void capture(RenderLivingEvent.Post event) {
        if (broken || ShadowPass.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        LivingEntityRenderState state = (LivingEntityRenderState) event.getState();
        if (state.entityType == null || state.distanceToCameraSq > RANGE * RANGE) return;
        PoseStack stack = event.getPoseStack();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        boolean garbageFree = Optimization.FRAME_GARBAGE.on();
        // a level draw puts the entity at its position less the camera's (a slide's lean moves it a little more);
        // anything else is a GUI preview of the entity, not where it stands
        Vector3f origin = stack.last().pose().getTranslation(garbageFree ? originScratch : new Vector3f());
        if (Math.abs(origin.x - (state.x - cam.x)) > 3 || Math.abs(origin.y - (state.y - cam.y)) > 3
                || Math.abs(origin.z - (state.z - cam.z)) > 3) return;
        LivingEntityRenderer renderer = event.getRenderer();
        EntityModel<?> model = renderer.getModel();
        if (model == null) return;

        // the renderer's own placement of its model (LivingEntityRenderer.render), the model already posed for it
        stack.pushPose();
        try {
            if (state.hasPose(Pose.SLEEPING) && state.bedOrientation != null) {
                Direction bed = state.bedOrientation;
                float f = state.eyeHeight - 0.1f;
                stack.translate(-bed.getStepX() * f, 0, -bed.getStepZ() * f);
            }
            float s = state.scale;
            stack.scale(s, s, s);
            setupRotations.invoke(renderer, state, stack, state.bodyRot, s);
            stack.scale(-1, -1, 1);
            scale.invoke(renderer, state, stack);
            stack.translate(0, -1.501f, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            stack.popPose();
            BlockLand.LOGGER.warn("[BlockLand] Entity poses stopped, body decals use the rest pose ({})", e.toString());
            return;
        }
        Capture c = slot(state);
        // relative to the entity's drawn position: camera-relative at capture, so the camera drops out (translation()
        // sets the whole matrix: a reused one ends up as a new one would)
        Matrix4f base = (garbageFree ? baseScratch : new Matrix4f())
                .translation((float) (cam.x - state.x), (float) (cam.y - state.y), (float) (cam.z - state.z));
        ModelPart root = model.root();
        Node tree = garbageFree ? tree(root) : null;
        if (tree != null) {
            if (tree.walked) walk(tree, root, stack, root, c, base);
        } else {
            Map<String, ModelPart[]> paths = chains.computeIfAbsent(root, r -> new HashMap<>());
            model.root().visit(stack, (pose, path, index, cube) -> {
                if (!drawn(root, paths, path)) return;
                Box box = c.count < c.boxes.size() ? c.boxes.get(c.count) : add(c);
                c.count++;
                box.path = path;
                box.cube = index;
                box.matrix.set(base).mul(pose.pose()).scale(1 / 16f);
                box.bounds = cubeBounds.computeIfAbsent(cube, EntityPoses::bounds);
            });
        }
        stack.popPose();
    }

    /**
     * {@code ModelPart.visit} over the kept tree, with the visitor above: the same pose stack operations in the same
     * order (push, the part's own {@code translateAndRotate}, its cubes, its children in the order of its map, pop), so
     * every cube gets the very matrix and the boxes come out in the same order with equal paths. A part with no cubes and
     * no children is skipped, as {@code visit} skips it.
     */
    private static void walk(Node node, ModelPart part, PoseStack stack, ModelPart root, Capture c, Matrix4f base) {
        stack.pushPose();
        // ModelPart.translateAndRotate, its rotation in a reused quaternion (rotationZYX sets all of it)
        stack.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
        if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
            stack.mulPose(turn.rotationZYX(part.zRot, part.yRot, part.xRot));
        }
        if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F) {
            stack.scale(part.xScale, part.yScale, part.zScale);
        }
        PoseStack.Pose pose = stack.last();
        int cubes = node.cubes.size();
        // the visitor's test per cube, once for the part (the walk changes no part's visibility)
        if (cubes > 0 && drawn(root, node.chain)) {
            for (int i = 0; i < cubes; i++) {
                Box box = c.count < c.boxes.size() ? c.boxes.get(c.count) : add(c);
                c.count++;
                box.path = node.path;
                box.cube = i;
                box.matrix.set(base).mul(pose.pose()).scale(1 / 16f);
                // the map's own array (its entry stays while the tree holds the cube)
                float[] bounds = node.bounds[i];
                if (bounds == null) bounds = node.bounds[i] = cubeBounds.computeIfAbsent(node.cubes.get(i), EntityPoses::bounds);
                box.bounds = bounds;
            }
        }
        for (Node child : node.children) walk(child, child.part, stack, root, c, base);
        stack.popPose();
    }

    /** The model's kept tree, made on its first capture; null when the parts cannot be read (the visitor is used). */
    @Nullable
    private static Node tree(ModelPart root) {
        if (treesBroken) return null;
        Node tree = trees.get(root);
        if (tree != null) return tree;
        try {
            if (cubesField == null || childrenField == null) {
                Field cubes = ModelPart.class.getDeclaredField("cubes"), children = ModelPart.class.getDeclaredField("children");
                cubes.setAccessible(true);
                children.setAccessible(true);
                cubesField = cubes;
                childrenField = children;
            }
            tree = node(root, root, null, "");
        } catch (ReflectiveOperationException | RuntimeException e) {
            treesBroken = true;
            BlockLand.LOGGER.warn("[BlockLand] Entity poses: model parts unreadable, captured through ModelPart.visit ({})", e.toString());
            return null;
        }
        trees.put(root, tree);
        return tree;
    }

    /** {@code of}'s node ({@code part}: {@code of}, null for the root), its children's as {@code visit} would reach them. */
    @SuppressWarnings("unchecked")
    private static Node node(ModelPart root, ModelPart of, @Nullable ModelPart part, String path) throws IllegalAccessException {
        List<ModelPart.Cube> cubes = (List<ModelPart.Cube>) cubesField.get(of);
        Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(of);
        // visit's order: the map's own forEach
        List<String> names = new ArrayList<>();
        List<ModelPart> parts = new ArrayList<>();
        children.forEach((name, child) -> {
            names.add(name);
            parts.add(child);
        });
        String prefix = path + "/";
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Node child = node(root, parts.get(i), parts.get(i), prefix + names.get(i));
            if (child.walked) nodes.add(child);
        }
        return new Node(part, path, cubes, nodes.toArray(new Node[0]), chain(root, path), !cubes.isEmpty() || !children.isEmpty());
    }

    /** A part in a model's kept tree. */
    private static final class Node {
        /** The part (null for the root: the tree must not keep it alive). */
        @Nullable
        final ModelPart part;
        /** Its path as {@code visit} names it ("" for the root, "/body/right_arm"). */
        final String path;
        final List<ModelPart.Cube> cubes;
        /** The cubes' boxes, filled as first used. */
        final float[][] bounds;
        /** The children {@code visit} goes into, in its order. */
        final Node[] children;
        /** {@code chain(root, path)} (null: the path leads nowhere), what the visitor's test looks up by path. */
        @Nullable
        final ModelPart[] chain;
        /** {@code visit} goes into it (it has cubes or children). */
        final boolean walked;

        Node(@Nullable ModelPart part, String path, List<ModelPart.Cube> cubes, Node[] children, @Nullable ModelPart[] chain,
             boolean walked) {
            this.part = part;
            this.path = path;
            this.cubes = cubes;
            this.bounds = new float[cubes.size()][];
            this.children = children;
            this.chain = chain;
            this.walked = walked;
        }
    }

    /** This frame's capture for the entity at the state's position (a second draw of it, e.g. a scope's, reuses it). */
    private static Capture slot(LivingEntityRenderState state) {
        List<Capture> list = current.computeIfAbsent(state.entityType, t -> new ArrayList<>());
        Capture c = null;
        for (Capture old : list) {
            if (old.x == state.x && old.y == state.y && old.z == state.z) {
                c = old;
                break;
            }
        }
        if (c == null) {
            c = pool.isEmpty() ? new Capture() : pool.remove(pool.size() - 1);
            list.add(c);
        }
        c.type = state.entityType;
        c.x = state.x;
        c.y = state.y;
        c.z = state.z;
        c.frame = frame;
        c.count = 0;
        return c;
    }

    private static Box add(Capture c) {
        Box box = new Box();
        c.boxes.add(box);
        return box;
    }

    /** Whether every part on the path is visible and the last one draws its cubes. */
    private static boolean drawn(ModelPart root, Map<String, ModelPart[]> paths, String path) {
        return drawn(root, paths.computeIfAbsent(path, p -> chain(root, p)));
    }

    /** Whether every part of the chain is visible and the last one draws its cubes. */
    private static boolean drawn(ModelPart root, @Nullable ModelPart[] chain) {
        if (chain == null || !root.visible) return false;
        for (ModelPart part : chain) if (!part.visible) return false;
        return !(chain.length == 0 ? root : chain[chain.length - 1]).skipDraw;
    }

    /** The parts below the root down to the path's part; null when the path does not lead anywhere. */
    @Nullable
    private static ModelPart[] chain(ModelPart root, String path) {
        List<ModelPart> parts = new ArrayList<>();
        ModelPart part = root;
        for (String name : path.split("/")) {
            if (name.isEmpty()) continue;
            if (!part.hasChild(name)) return null;
            part = part.getChild(name);
            parts.add(part);
        }
        return parts.toArray(new ModelPart[0]);
    }

    private static float[] bounds(ModelPart.Cube cube) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (ModelPart.Polygon polygon : cube.polygons) {
            for (ModelPart.Vertex v : polygon.vertices()) {
                Vector3f p = v.pos();
                b[0] = Math.min(b[0], p.x);
                b[1] = Math.min(b[1], p.y);
                b[2] = Math.min(b[2], p.z);
                b[3] = Math.max(b[3], p.x);
                b[4] = Math.max(b[4], p.y);
                b[5] = Math.max(b[5], p.z);
            }
        }
        if (b[0] > b[3]) return new float[]{cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ};
        return b;
    }
}
