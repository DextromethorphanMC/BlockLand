package com.blockland.client.viewmodel;

import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * The support hand on an underbarrel attachment (asset spec §35). The clips hold the handguard; with a grip or a bipod
 * fitted, every frame the left hand is moved onto the attachment's hold: the clip's hand, relative to the mount, keeps
 * its deviation from the idle's hold (a clench on a shot, a drift) on top of the hold, the arm reaches it by two-bone
 * IK in the plane the clip's elbow gives (the shoulder comes forward when the hold is out of reach), and the fingers
 * take the hold's curls plus the clip's deviation from its idle curls where it opens them (a clench meets the part).
 * <p>
 * How much of it applies follows the clip itself: while the clip's hand stays on its idle hold (idle, walk, shots,
 * aiming, inspect presentations) it is all there; as a clip takes the hand away (reloads, draws, bolt and pump work,
 * a sprint's pumping fist) it fades out with the hand's distance from that hold, so the clip plays as authored.
 */
public final class SupportGrip {
    /** Within this of its idle hold (relative to the mount) the clip's hand is holding; beyond {@link #FAR} it is not. */
    private static final float NEAR = 0.025f, FAR = 0.07f;
    /** The hand stops this short of the arm's full length; past it the shoulder comes forward, at most {@link #MAX_SHIFT}. */
    private static final float REACH = 0.985f, MAX_SHIFT = 0.15f;
    private static final String[] FINGERS = {"thumb", "index", "middle", "ring", "pinky"};

    /**
     * A hold in the attachment's frame (origin = the mount point): wrist position, palm orientation (hand joint frame of
     * spec §16) and per finger the curls of its two segments in degrees, NaN = the clip's own.
     */
    public record Hold(Vector3f wrist, Quaternionf palm, float[] curls) {
        public static Hold of(float[] wrist, float[] palm, Map<String, float[]> fingers) {
            float[] curls = new float[10];
            for (int f = 0; f < FINGERS.length; f++) {
                float[] c = fingers.get(FINGERS[f]);
                curls[f * 2] = c != null && c.length > 0 ? c[0] : Float.NaN;
                curls[f * 2 + 1] = c != null && c.length > 1 ? c[1] : Float.NaN;
            }
            return new Hold(new Vector3f(wrist[0], wrist[1], wrist[2]),
                    new Quaternionf(palm[0], palm[1], palm[2], palm[3]).normalize(), curls);
        }
    }

    private final int mount, upper, fore, hand, shoulderParent;
    /** Finger joints (thumb, thumb2, index, index2 …), -1 when missing. */
    private final int[] fingers = new int[10];
    /** The hold × the inverse of the idle's hand in mount space: maps the clip's hand (mount space) onto the hold. */
    private final Matrix4f transfer = new Matrix4f();
    private final Vector3f idleHand = new Vector3f();
    /** Per finger joint: the hold's local rotation (null = keep the clip's) and the idle's. */
    private final Quaternionf[] fingerHeld = new Quaternionf[10], fingerIdle = new Quaternionf[10];

    // scratch
    private final Matrix4f m = new Matrix4f(), target = new Matrix4f();
    private final Vector3f s = new Vector3f(), e = new Vector3f(), h = new Vector3f(), p = new Vector3f(),
            u = new Vector3f(), side = new Vector3f(), elbow = new Vector3f(), a = new Vector3f(), b = new Vector3f();
    private final Quaternionf qHand = new Quaternionf(), qUpper = new Quaternionf(), qFore = new Quaternionf(),
            qParent = new Quaternionf(), q = new Quaternionf(), cur = new Quaternionf();

    private SupportGrip(int mount, int upper, int fore, int hand, int shoulderParent) {
        this.mount = mount;
        this.upper = upper;
        this.fore = fore;
        this.hand = hand;
        this.shoulderParent = shoulderParent;
    }

    /**
     * @param rest the idle's first frame (world matrices resolved): the clips' own hold
     * @return null when the weapon file lacks the mount or the left arm's joints
     */
    @Nullable
    static SupportGrip create(GltfModel model, Pose rest, String mountNode, Hold hold) {
        int mount = model.node(mountNode), upper = model.node("arm_L"), fore = model.node("arm_L_fore"),
                hand = model.node("arm_L_hand");
        if (mount < 0 || upper < 0 || fore < 0 || hand < 0) return null;
        int parent = model.nodes.get(upper).parent;
        if (parent < 0) return null;
        SupportGrip grip = new SupportGrip(mount, upper, fore, hand, parent);
        Matrix4f idle = new Matrix4f(rest.world[mount]).invert().mul(rest.world[hand]);
        idle.getTranslation(grip.idleHand);
        grip.transfer.translationRotate(hold.wrist().x, hold.wrist().y, hold.wrist().z, hold.palm()).mul(idle.invert());
        Quaternionf thumbRest = thumbRest();
        for (int f = 0; f < FINGERS.length; f++) {
            for (int seg = 0; seg < 2; seg++) {
                int k = f * 2 + seg, joint = model.node("arm_L_" + FINGERS[f] + (seg == 0 ? "" : "2"));
                grip.fingers[k] = joint;
                float curl = hold.curls()[k];
                if (joint < 0 || Float.isNaN(curl)) continue;
                grip.fingerHeld[k] = (f == 0 && seg == 0 ? new Quaternionf(thumbRest) : new Quaternionf())
                        .rotateX((float) Math.toRadians(curl));
                grip.fingerIdle[k] = rest.rotation(joint, new Quaternionf());
            }
        }
        return grip;
    }

    /**
     * Moves the support hand of the sampled pose onto the hold ({@code p.world} resolved); its local rotations (and the
     * shoulder's translation) are changed in place.
     *
     * @return whether anything changed (the caller resolves the world matrices again)
     */
    boolean apply(Pose pose) {
        Matrix4f[] world = pose.world;
        // the clip's hand in mount space, and how close it is to the idle's hold
        m.set(world[mount]).invert().mul(world[hand]);
        float weight = 1 - smoothstep(m.getTranslation(a).distance(idleHand));
        if (weight <= 0.001f) return false;
        target.set(world[mount]).mul(transfer).mul(m);
        target.getTranslation(p);
        target.getNormalizedRotation(qHand);

        // two-bone IK in the plane of the clip's elbow
        world[upper].getTranslation(s);
        world[fore].getTranslation(e);
        world[hand].getTranslation(h);
        float l1 = e.distance(s), l2 = h.distance(e);
        float dist = p.distance(s);
        if (dist < 1e-5f || l1 < 1e-5f || l2 < 1e-5f) return false;
        p.sub(s, u).div(dist);
        float shift = Mth.clamp(dist - REACH * (l1 + l2), 0, MAX_SHIFT);
        s.fma(shift, u);
        e.fma(shift, u);
        dist = Mth.clamp(dist - shift, Math.abs(l1 - l2) + 1e-4f, (l1 + l2) * 0.999f);
        float along = (l1 * l1 - l2 * l2 + dist * dist) / (2 * dist);
        float out = (float) Math.sqrt(Math.max(l1 * l1 - along * along, 0));
        e.sub(s, side);
        side.fma(-side.dot(u), u);
        if (side.lengthSquared() < 1e-10f) side.set(0, -1, 0).fma(u.y, u);
        side.normalize();
        s.fma(along, u, elbow).fma(out, side);

        world[upper].getNormalizedRotation(qUpper);
        q.rotationTo(e.sub(s, a), elbow.sub(s, b));
        qUpper.premul(q);
        // the forearm takes the palm's twist, swung onto its own axis: the wrist only bends (as armrig.solve_side)
        qHand.transform(a.set(0, -1, 0));
        qFore.rotationTo(a, p.sub(elbow, b)).mul(qHand);
        world[shoulderParent].getNormalizedRotation(qParent);

        blend(pose, upper, q.set(qParent).conjugate().mul(qUpper), weight);
        blend(pose, fore, q.set(qUpper).conjugate().mul(qFore), weight);
        blend(pose, hand, q.set(qFore).conjugate().mul(qHand), weight);
        if (shift > 0) {
            m.set(world[shoulderParent]).invert().transformPosition(s);
            int t = upper * 3;
            pose.t[t] += (s.x - pose.t[t]) * weight;
            pose.t[t + 1] += (s.y - pose.t[t + 1]) * weight;
            pose.t[t + 2] += (s.z - pose.t[t + 2]) * weight;
        }
        for (int k = 0; k < fingers.length; k++) {
            if (fingers[k] < 0 || fingerHeld[k] == null) continue;
            // the clip's deviation from its idle curl (a curl about X), opening only: the attachment stops a clench
            q.set(fingerIdle[k]).conjugate().mul(pose.rotation(fingers[k], cur));
            if (q.w < 0) q.set(-q.x, -q.y, -q.z, -q.w);
            float opened = Math.max(2 * (float) Math.atan2(q.x, q.w), 0);
            blend(pose, fingers[k], q.set(fingerHeld[k]).rotateX(opened), weight);
        }
        return true;
    }

    private void blend(Pose pose, int node, Quaternionf to, float weight) {
        pose.setRotation(node, pose.rotation(node, cur).slerp(to, weight));
    }

    private static float smoothstep(float d) {
        float t = Mth.clamp((d - NEAR) / (FAR - NEAR), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** The left thumb's opposed rest frame (spec §16, hands.thumb_rest): its curls turn about this frame's X. */
    private static Quaternionf thumbRest() {
        Vector3f d = new Vector3f(0.55f, -0.80f, 0.22f).normalize();
        Vector3f close = new Vector3f(-1, 0, 0.65f);
        close.fma(-close.dot(d), d).normalize();
        Vector3f x = new Vector3f(close).cross(d).normalize();
        Vector3f y = new Vector3f(d).negate();
        Vector3f z = new Vector3f(x).cross(y);
        return new Matrix3f(x, y, z).getNormalizedRotation(new Quaternionf());
    }
}
