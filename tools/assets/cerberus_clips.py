"""The Cerberus skin's own inspect (VIEWMODEL_SPEC section 38): `inspect_cerberus`, built for the Cerberus DualRig, and
the skin's effect nodes it animates.

The beats (the left side is authored as a right-hand pose and mirrored, dual_rig):
  0.00  a dip (anticipation), then the right pistol is turned to show its inner flank and the crest, a moving hold;
  0.62  `cerberus.release`: the left hand opens and lets go of its pistol - it does not fall, it floats: rises, rolls
        slowly about its bore and bobs, held by the fire (embers orbit it: the skin's data);
  1.30  `cerberus.touch`: the free left hand has reached across and its index finger presses the crest; a sigil flares
        open round the fingertip (fx_sigil: scaled up with an overshoot, spinning), the pistol jolts, the surge of
        the skin's shader effect starts there and runs over both guns (the data's surge curve);
  1.55  the right pistol turns muzzle-up to show the burning steel; 2.02 the fire racks its slide by itself
        (`cerberus.rack` at the slam), the hammer cocks; the sigil bursts outward and is gone by 2.45;
  2.55  `cerberus.catch`: the floating pistol sinks into the waiting hand, which closes on the grip (the gun bounces);
  2.90  both pistols are brought together, muzzles crossing in front, trembling as the energy gathers at the muzzles;
  3.55  `cerberus.roar`: both thrust forward and kick apart (a shockwave ring at each muzzle, fx_ring / fx_ring_L);
  3.90  back to the hip with an overshoot and a settle, the fire dying down (the data's surge falls to 0 by the end).

Effect nodes (scale 0 at rest, so nothing shows outside the clip; LINEAR scale keys): `fx_touch` (empty, the crest on
the right slide's inner flank: the cue's node and the fingertip's target), `fx_sigil` under it (a quad facing out of the
flank, additive material `cerberus_sigil`), `fx_ring` / `fx_ring_L` under the muzzles (a quad facing the bore,
additive `cerberus_ring`)."""
import numpy as np

import hands
from anim import Curve, drift, ring, smoothstep
from armrig import blend_targets
from dual_rig import GUN_Q, GUN_T, mirror_t
from p9_clips import Clip, V
from vmath import quat_axis_angle, quat_euler, quat_mul, quat_rotate

DUR = 5.60
T_RELEASE, T_TOUCH, T_RACK, T_CATCH, T_ROAR = 0.62, 1.30, 2.12, 2.55, 3.55
EVENTS = [(0.04, "inspect_move"), (T_RELEASE, "cerberus.release"), (T_TOUCH, "cerberus.touch"), (T_RACK, "cerberus.rack"),
          (T_CATCH, "cerberus.catch"), (T_ROAR, "cerberus.roar"), (4.05, "inspect_move")]
FX_NODES = ("fx_touch", "fx_sigil", "fx_ring", "fx_ring_L")
SIGIL_SIZE = 0.040            # the sigil quad's side (m) at scale 1
RING_SIZE = 0.050             # the shockwave quad's side at scale 1
TOUCH_OUT = 0.0045            # the fingertip's pad stops this far out of the crest

hands.POSES.setdefault("cerberus_point", {"thumb": (-30, -18), "index": (-4, -2), "middle": (-80, -72), "ring": (-84, -76),
                                          "pinky": (-84, -76)})
hands.POSES.setdefault("cerberus_press", {"thumb": (-30, -18), "index": (-16, -12), "middle": (-82, -74), "ring": (-86, -78),
                                          "pinky": (-86, -78)})


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def mix(a, b, u):
    return {f: np.asarray(a[f], float) * (1 - u) + np.asarray(b[f], float) * u for f in hands.FINGERS}


class FxPose:
    """A rig pose plus the effect nodes' TRS: solve() merges them into the rig's node values."""

    def __init__(self, pose):
        self.pose = pose
        self.R, self.L = pose.R, pose.L
        self.fx = {}

    @property
    def cam_q(self):
        return self.pose.cam_q

    @cam_q.setter
    def cam_q(self, q):
        self.pose.cam_q = q

    def solve(self):
        trs, info = self.pose.solve()
        trs.update(self.fx)
        return trs, info


def fx_rest(touch_t):
    """Rest values of the effect nodes (for bake_clip): where they sit, collapsed."""
    zero = np.zeros(3)
    return {"fx_touch": {"translation": np.asarray(touch_t, float)},
            "fx_sigil": {"rotation": np.array([0.0, 0.0, 0.0, 1.0]), "scale": zero},
            "fx_ring": {"rotation": np.array([0.0, 0.0, 0.0, 1.0]), "scale": zero},
            "fx_ring_L": {"rotation": np.array([0.0, 0.0, 0.0, 1.0]), "scale": zero}}


def touch_point(lay):
    """The crest on the right slide's inner (-X) flank, slide space (the slide node sits at the gun's origin)."""
    import cerberus_model as cm
    cy = 0.5 * (cm.SLIDE_Y[0] + cm.SLIDE_Y[1])
    return lay.G(np.array([-(cm.SLIDE_W + 0.0011), cy, cm.CREST_Z]))


def fx_set(lay):
    """The effect part of the Cerberus gunset (gen_usp_dual.build's `fx`)."""
    import textures as tx
    touch = touch_point(lay)
    return {"materials": MATERIALS,
            "textures": {"sigil": tx.png_bytes(sigil_rgba()), "ring": tx.png_bytes(ring_rgba())},
            "meshes": {"fx_sigil": sigil_mesh(), "fx_ring": ring_mesh()},
            "nodes": [("fx_touch", "slide", touch, None, None, None),
                      ("fx_sigil", "fx_touch", None, None, np.zeros(3), "fx_sigil"),
                      ("fx_ring", "muzzle", None, None, np.zeros(3), "fx_ring"),
                      ("fx_ring_L", "muzzle_L", None, None, np.zeros(3), "fx_ring")],
            "rest": fx_rest(touch)}


def make(rig):
    """-> [inspect_cerberus] for this rig (the Cerberus layout)."""
    lay = rig.lay
    LOCK = lay.slide_lock
    COCKED = lay.hammer_cocked
    touch_local = touch_point(lay)
    slide_rest = rig.nodes["slide"][1]

    # ------------------------------------------------------------------ the right pistol (held all through)
    r_off = Curve((0.00, V(0, 0, 0)), (0.10, V(0.002, -0.004, 0.002)),
                  (0.52, V(-0.052, 0.032, 0.008), "flow"), (0.66, V(-0.049, 0.030, 0.010)),
                  (1.22, V(-0.050, 0.031, 0.010)), (1.50, V(-0.046, 0.030, 0.010)),
                  (1.90, V(-0.030, 0.046, 0.000), "flow"), (2.30, V(-0.028, 0.048, -0.002)),
                  (2.80, V(-0.030, 0.046, -0.004)), (3.10, V(-0.064, 0.044, -0.026), "flow"),
                  (3.48, V(-0.066, 0.046, -0.022)), (T_ROAR, V(-0.068, 0.044, -0.030), "hit"),
                  (3.66, V(-0.056, 0.050, 0.012)), (3.85, V(-0.052, 0.046, 0.000)),
                  (4.35, V(-0.004, 0.006, -0.004), "flow"), (4.85, V(0.0012, -0.0016, 0.0010)),
                  (5.25, V(-0.0003, 0.0004, 0.0)), (DUR, V(0, 0, 0)))
    r_rot = Curve((0.00, V(0, 0, 0)), (0.10, V(-1.5, -1.0, -3.0)),
                  (0.52, V(43.0, 7.0, -14.0), "flow"), (0.66, V(39.0, 6.0, -12.0)),
                  (1.22, V(40.0, 6.5, -12.5)), (1.50, V(37.0, 8.0, -10.0)),
                  (1.95, V(12.0, 38.0, 6.0), "flow"), (2.30, V(10.0, 35.0, 5.0)),
                  (2.80, V(11.0, 36.0, 5.5)), (3.12, V(22.0, 9.0, -14.0), "flow"),
                  (3.48, V(21.0, 10.0, -13.0)), (T_ROAR, V(21.0, 8.0, -13.0), "hit"),
                  (3.68, V(17.0, 24.0, -8.0)), (3.88, V(19.0, 14.0, -11.0)),
                  (4.38, V(-3.0, -2.5, 2.0), "flow"), (4.88, V(0.6, 0.5, -0.4)), (5.27, V(-0.15, -0.1, 0.1)),
                  (DUR, V(0, 0, 0)))
    # the fire racks the right slide: drawn back slowly against the spring, released into battery
    r_slide = Curve((0.0, 0.0), (1.88, 0.0), (2.04, LOCK, "stop"), (2.08, LOCK), (T_RACK, 0.0, "hit"),
                    (T_RACK + 0.010, 0.0012, "stop"), (T_RACK + 0.024, 0.0, "stop"))
    r_hammer = Curve((0.0, COCKED), (1.94, COCKED), (2.04, 46.0, "stop"), (T_RACK + 0.004, COCKED, "hit"))

    # ------------------------------------------------------------------ the left pistol (released, floating, caught)
    l_off = Curve((0.00, V(0, 0, 0)), (0.10, V(0.002, -0.004, 0.002)), (0.50, V(-0.016, 0.020, -0.004), "flow"),
                  (T_RELEASE, V(-0.018, 0.024, -0.006)), (1.05, V(0.020, 0.012, 0.006), "flow"),
                  (1.70, V(0.026, 0.020, 0.002)), (2.25, V(0.000, 0.026, -0.004), "flow"),
                  (T_CATCH, V(-0.028, 0.030, -0.006), "hit"), (2.70, V(-0.030, 0.036, -0.004)),
                  (3.10, V(-0.064, 0.018, -0.034), "flow"), (3.48, V(-0.066, 0.020, -0.030)),
                  (T_ROAR + 0.03, V(-0.068, 0.018, -0.038), "hit"), (3.69, V(-0.056, 0.024, 0.004)),
                  (3.88, V(-0.052, 0.020, -0.006)), (4.40, V(-0.004, 0.006, -0.004), "flow"),
                  (4.90, V(0.0012, -0.0016, 0.0010)), (5.30, V(-0.0003, 0.0004, 0.0)), (DUR, V(0, 0, 0)))
    l_rot = Curve((0.00, V(0, 0, 0)), (0.10, V(-1.5, -1.0, -3.0)), (0.50, V(8.0, 10.0, 8.0), "flow"),
                  (T_RELEASE, V(9.0, 11.0, 9.0)), (1.05, V(-14.0, 12.0, 6.0), "flow"), (1.70, V(-18.0, 16.0, 4.0)),
                  (2.25, V(-2.0, 9.0, 6.0), "flow"), (T_CATCH, V(6.0, 4.0, 4.0), "hit"), (2.70, V(8.0, 6.0, 5.0)),
                  (3.12, V(22.0, 13.0, -14.0), "flow"), (3.48, V(21.0, 14.0, -13.0)),
                  (T_ROAR + 0.03, V(21.0, 12.0, -13.0), "hit"), (3.71, V(17.0, 27.0, -8.0)),
                  (3.90, V(19.0, 18.0, -11.0)), (4.42, V(-3.0, -2.5, 2.0), "flow"), (4.92, V(0.6, 0.5, -0.4)),
                  (5.32, V(-0.15, -0.1, 0.1)), (DUR, V(0, 0, 0)))
    # the float: a slow barrel roll about the bore (one turn and a little, eased at both ends)
    l_roll = Curve((0.0, 0.0), (T_RELEASE + 0.05, 0.0), (1.10, 60.0), (2.15, 360.0 + 20.0), (T_CATCH, 360.0, "stop"))
    # the hand: on its gun -> opens and lets go -> reaches for the crest -> presses -> pulls back open -> catches
    l_open = Curve((0.0, 0.0), (0.38, 0.0), (T_RELEASE - 0.04, 1.0), (2.30, 1.0), (T_CATCH, 0.0, "hit"))
    l_free = Curve((0.0, 0.0), (T_RELEASE, 0.0), (T_RELEASE + 0.16, 1.0), (2.30, 1.0), (T_CATCH - 0.02, 0.0, "hit"))
    l_reach = Curve((0.0, 0.0), (0.74, 0.0), (T_TOUCH - 0.04, 1.0, "out2"), (T_TOUCH + 0.16, 1.0), (1.62, 0.0))
    l_press = Curve((0.0, 0.0), (T_TOUCH - 0.02, 0.0), (T_TOUCH + 0.03, 1.0, "hit"), (T_TOUCH + 0.14, 0.6),
                    (T_TOUCH + 0.20, 0.0))
    l_point = Curve((0.0, 0.0), (0.78, 0.0), (1.10, 1.0), (T_TOUCH + 0.18, 1.0), (1.72, 0.0))

    def hand_target(s, gun_t, gun_q):
        """The authored left side's hand at the crest of the actual right gun (mirrored into the authored space): the
        index fingertip on the crest, palm down, fingers pointing across towards the gun."""
        p_slide = quat_rotate(gun_q, slide_rest + touch_local) + gun_t            # actual right gun, viewmodel space
        normal = quat_rotate(gun_q, np.array([-1.0, 0.0, 0.0]))                    # out of the inner flank
        target = mirror_t(p_slide + normal * TOUCH_OUT)
        n = mirror_t(normal)
        fwd = -n + np.array([0.0, -0.25, -0.30])
        fwd = fwd / np.linalg.norm(fwd)
        up = np.array([0.0, 1.0, 0.0])
        thumb = np.cross(up, fwd) + 0.45 * up         # palm down, turned a little towards the flank
        q = hands.palm_frame(fwd, thumb, "R")
        return target, q

    # where the hand lets go: the open hand stays there while the gun floats up out of it, and waits there to catch it
    probe = rig.pose().L
    probe.offset(l_off(T_RELEASE), l_rot(T_RELEASE))
    hover = probe.anchor("open")

    def right_frame(t):
        o = r_off(t) + jolt_t(t, False)
        r = r_rot(t) + jolt_r(t, False)
        return GUN_T + o, quat_mul(GUN_Q, quat_euler(*r))

    def jolt_t(t, left):
        if left:
            return (ring(t, T_CATCH, V(0.0, -0.006, 0.002), 7.0, 0.4) + ring(t, T_ROAR + 0.03, V(0.0, 0.004, 0.010), 6.0, 0.35))
        return (ring(t, T_TOUCH + 0.02, V(0.002, -0.002, 0.0), 8.0, 0.4) + ring(t, T_RACK, V(0.0, -0.0015, -0.002), 10.0, 0.35)
                + ring(t, T_ROAR, V(0.0, 0.004, 0.010), 6.0, 0.35))

    def jolt_r(t, left):
        if left:
            return ring(t, T_CATCH, V(0.0, -3.0, 1.5), 7.0, 0.4) + ring(t, T_ROAR + 0.03, V(0.0, 6.0, 2.0), 6.0, 0.35)
        return (ring(t, T_TOUCH + 0.02, V(-2.5, 0.0, 2.0), 8.0, 0.4) + ring(t, T_RACK, V(0.0, -2.0, -0.6), 10.0, 0.35)
                + ring(t, T_ROAR, V(0.0, 6.0, 2.0), 6.0, 0.35))

    def tremble(t):
        """The energy gathering: a fast tremble growing until the roar."""
        k = smoothstep(2.95, 3.45, t) * (1.0 - smoothstep(T_ROAR - 0.02, T_ROAR + 0.02, t))
        w = 2 * np.pi * 17.0 * t
        return V(0.0006 * np.sin(w), 0.0005 * np.sin(1.3 * w + 1.0), 0.0) * k, V(0.4 * np.sin(1.1 * w), 0.5 * np.sin(w + 2.0), 0.6 * np.sin(0.9 * w)) * k

    def holds(t, seed):
        return (drift(t, V(0.0008, 0.0008, 0.0006), seed=seed, freq=0.6, start=0.6, end=1.25, fade=0.15)
                + drift(t, V(0.0008, 0.0008, 0.0006), seed=seed + 1, freq=0.6, start=2.2, end=2.85, fade=0.15),
                drift(t, V(0.6, 0.7, 0.9), seed=seed + 2, freq=0.6, start=0.6, end=1.25, fade=0.15)
                + drift(t, V(0.6, 0.7, 0.9), seed=seed + 3, freq=0.6, start=2.2, end=2.85, fade=0.15))

    def pose_fn(t):
        p = FxPose(rig.pose())
        # right: held, presented, racked by the fire
        R = p.R
        h = holds(t, 21)
        tr = tremble(t)
        R.offset(r_off(t) + jolt_t(t, False) + h[0] + tr[0], r_rot(t) + jolt_r(t, False) + h[1] + tr[1])
        R.slide_z = float(r_slide(t))
        R.hammer = float(r_hammer(t))
        grip = R.anchor("grip")
        R.hand = (grip[0], grip[1], hands.blend({"indexed": 1.0}))
        # left: released and floating, then caught
        L = p.L
        hl = holds(t, 31)
        float_bob = V(0.0, 0.004 * np.sin(2 * np.pi * 0.9 * (t - T_RELEASE)), 0.0) * smoothstep(T_RELEASE, 1.0, t) \
            * (1.0 - smoothstep(2.2, T_CATCH, t))
        L.offset(l_off(t) + jolt_t(t, True) + hl[0] + tr[0] + float_bob, l_rot(t) + jolt_r(t, True) + hl[1] + tr[1])
        roll = float(l_roll(t))
        if roll:
            pivot = lay.G(np.array([0.0, 0.0, -0.06]))                  # on the bore, mid-slide
            pw = L.gun_point(pivot)
            L.gun_q = quat_mul(L.gun_q, quat_axis_angle((0, 0, 1), roll))
            L.gun_t = pw - quat_rotate(L.gun_q, pivot)
        grip_l = L.anchor("grip")
        u_open = float(np.clip(l_open(t), 0, 1))
        held = blend_targets([(1 - u_open, grip_l), (u_open, L.anchor("open"))]) if u_open > 1e-4 else grip_l
        held = (held[0], held[1], mix(hands.POSES["indexed"], hands.POSES["relaxed"], u_open))
        u_free = float(np.clip(l_free(t), 0, 1))
        if u_free > 1e-4:
            gt, gq = right_frame(t)
            target, q_touch = hand_target(L, gt, gq)
            u_reach = float(np.clip(l_reach(t), 0, 1))
            u_point = float(np.clip(l_point(t), 0, 1))
            curls = mix(hands.POSES["relaxed"], hands.POSES["cerberus_point"], u_point)
            press = float(np.clip(l_press(t), 0, 1))
            if press > 0:
                curls = mix(curls, hands.POSES["cerberus_press"], press)
            # the wrist that puts the index fingertip on the crest with this palm
            tip = hands.fingertips("R", np.zeros(3), q_touch, curls)["index"]
            touch = (target - tip, q_touch, curls)
            # the free hand: waiting where it let go (a slow sink), or at the crest; it arcs up on the way
            wait = (hover[0] + np.array([0.0, -0.006, 0.0]) * smoothstep(T_RELEASE, 1.0, t), hover[1], curls)
            free = blend_targets([(1 - u_reach, wait), (u_reach, touch)]) if u_reach < 1 - 1e-4 else touch
            free = (free[0] + np.array([0.0, 0.025, 0.0]) * 4 * u_reach * (1 - u_reach), free[1], curls)
            L.hand = blend_targets([(1 - u_free, held), (u_free, free)])
        else:
            L.hand = held
        clench = float(ring(t, T_CATCH + 0.01, -8.0, 7.0, 0.45)) + float(ring(t, T_ROAR, -6.0, 8.0, 0.45))
        for s in (R, L):
            s.hand = (s.hand[0], s.hand[1], {f: np.asarray(v, float) + (np.array([clench, 0.6 * clench])
                                                                    if f in ("middle", "ring", "pinky") else 0.0)
                                             for f, v in s.hand[2].items()})
        # camera: leans into the presentation, a jolt at the touch, the rack and the catch, a big one at the roar
        c = (Curve((0.0, V(0, 0, 0)), (0.55, V(0.30, 0.50, -0.80)), (1.25, V(0.25, 0.45, -0.70)),
                   (1.95, V(0.55, 0.20, 0.30)), (2.80, V(0.45, 0.10, 0.20)), (3.45, V(0.10, 0.0, 0.0)),
                   (3.90, V(0.35, 0.0, 0.0)), (4.60, V(-0.10, 0.0, 0.0)), (DUR, V(0, 0, 0)))(t)
             + ring(t, T_TOUCH + 0.02, V(-0.45, 0.15, 0.6), 8.0, 0.4) + ring(t, T_RACK, V(-0.35, 0.0, 0.3), 10.0, 0.4)
             + ring(t, T_CATCH, V(-0.4, -0.1, -0.4), 7.0, 0.4) + ring(t, T_ROAR, V(-1.3, 0.0, 1.6), 5.5, 0.35))
        cam(p, *(c * (1 - smoothstep(DUR - 0.35, DUR, t))))
        # effect nodes: the sigil at the touch, the shockwave rings at the roar
        p.fx = fx_values(t, touch_local)
        return p

    clip = Clip("inspect_cerberus", DUR, pose_fn, events=EVENTS,
                extra_times=(T_TOUCH, T_TOUCH + 0.03, T_RACK, T_CATCH, T_ROAR, T_ROAR + 0.03, 2.45, 3.74),
                force_nodes=("slide", "hammer", "fx_sigil", "fx_ring", "fx_ring_L"))
    clip.linear_scale = {"fx_sigil", "fx_ring", "fx_ring_L"}
    return [clip]


SIGIL_SCALE = Curve((0.0, 0.0), (T_TOUCH, 0.0), (T_TOUCH + 0.10, 1.28, "stop"), (T_TOUCH + 0.24, 0.95),
                    (T_TOUCH + 0.34, 1.0), (2.18, 1.0), (2.32, 1.55, "stop"), (2.45, 0.0, "hit"))
RING_SCALE = Curve((0.0, 0.0), (T_ROAR, 0.0), (T_ROAR + 0.02, 0.35), (T_ROAR + 0.17, 2.6, "out2"), (3.74, 3.0))


def fx_values(t, touch_local):
    """TRS of the effect nodes at time t."""
    s_sigil = float(SIGIL_SCALE(t)) if T_TOUCH <= t < 2.45 else 0.0
    s_sigil *= 1.0 + 0.05 * np.sin(2 * np.pi * 4.2 * t) * smoothstep(1.6, 1.8, t)        # the heartbeat
    spin = 120.0 * max(0.0, t - T_TOUCH) + 220.0 * max(0.0, t - 2.18) ** 2
    s_ring = float(RING_SCALE(t)) if T_ROAR <= t < 3.74 else 0.0
    ring_spin = 90.0 * max(0.0, t - T_ROAR)
    return {
        "fx_touch": {"translation": np.asarray(touch_local, float)},
        "fx_sigil": {"rotation": quat_axis_angle((1, 0, 0), spin), "scale": np.full(3, s_sigil)},
        "fx_ring": {"rotation": quat_axis_angle((0, 0, 1), ring_spin), "scale": np.full(3, s_ring)},
        "fx_ring_L": {"rotation": quat_axis_angle((0, 0, 1), -ring_spin), "scale": np.full(3, s_ring)},
    }


# ------------------------------------------------------------------------------------------------ meshes and textures
def sigil_mesh():
    """A square facing out of the inner flank (-X), centred on its node: the sigil's quad (double sided: additive)."""
    from meshkit import Mesh
    h = SIGIL_SIZE / 2
    m = Mesh()
    m.add_poly([(0.0, h, h), (0.0, h, -h), (0.0, -h, -h), (0.0, -h, h)], "cerberus_sigil",
               [(0, 0), (1, 0), (1, 1), (0, 1)])
    return m


def ring_mesh():
    """A square facing along the bore (+Z, double sided) for the shockwave ring."""
    from meshkit import Mesh
    h = RING_SIZE / 2
    m = Mesh()
    m.add_poly([(-h, -h, 0.0), (h, -h, 0.0), (h, h, 0.0), (-h, h, 0.0)], "cerberus_ring",
               [(0, 1), (1, 1), (1, 0), (0, 0)])
    return m


def _polar(n):
    y, x = (np.mgrid[0:n, 0:n] + 0.5) / n * 2 - 1
    return np.hypot(x, y), np.arctan2(y, x), x, y


def _segment(px, py, a, b):
    ax, ay = a
    bx, by = b
    vx, vy = bx - ax, by - ay
    h = np.clip(((px - ax) * vx + (py - ay) * vy) / max(vx * vx + vy * vy, 1e-9), 0, 1)
    return np.hypot(px - ax - vx * h, py - ay - vy * h)


def sigil_rgba(n=256):
    """The sigil: two runic rings, a ring of glyphs between them, a three-fanged star inside (the crest's fangs) -
    crimson glow with hot white-orange cores on black (an additive texture: black is nothing)."""
    r, a, x, y = _polar(n)
    px = 2.0 / n
    line = lambda d, w: np.clip(1.0 - (d - w) / (1.5 * px), 0, 1)
    ink = np.zeros((n, n))
    ink = np.maximum(ink, line(np.abs(r - 0.92), 0.012))
    ink = np.maximum(ink, line(np.abs(r - 0.70), 0.010))
    ink = np.maximum(ink, line(np.abs(r - 0.32), 0.008))
    # glyphs between the rings: 16 cells, each a stem and two strokes from its hash
    rng = np.random.default_rng(666)
    cells = 16
    for k in range(cells):
        a0 = (k + 0.5) / cells * 2 * np.pi
        c, s = np.cos(a0), np.sin(a0)
        def g(u, v):                                   # glyph frame: u along the ring, v outward
            rr = 0.81 + 0.075 * v
            ang = a0 + u * (2 * np.pi / cells) * 0.32
            return rr * np.cos(ang), rr * np.sin(ang)
        pts = [g(-1, -1), g(1, -1), g(-1, 0), g(1, 0), g(-1, 1), g(1, 1)]
        d = _segment(x, y, g(0, -1.1), g(0, 1.1))
        for _ in range(2):
            i, j = rng.choice(6, 2, replace=False)
            d = np.minimum(d, _segment(x, y, pts[i], pts[j]))
        ink = np.maximum(ink, line(d, 0.009))
    # the three fangs: long triangles from the centre ring inwards-up, and a star of three lines
    for k in range(3):
        ang = np.pi / 2 + k * 2 * np.pi / 3
        tip = (0.62 * np.cos(ang), 0.62 * np.sin(ang))
        base_l = (0.30 * np.cos(ang - 0.35), 0.30 * np.sin(ang - 0.35))
        base_r = (0.30 * np.cos(ang + 0.35), 0.30 * np.sin(ang + 0.35))
        d = np.minimum(_segment(x, y, base_l, tip), _segment(x, y, base_r, tip))
        ink = np.maximum(ink, line(d, 0.010))
        d = _segment(x, y, (0.0, 0.0), (0.30 * np.cos(ang + np.pi), 0.30 * np.sin(ang + np.pi)))
        ink = np.maximum(ink, line(d, 0.007))
    # a soft glow round the ink and a faint disc
    from textures import _blur_wrap                 # the border is black: wrapping changes nothing
    glow = _blur_wrap(ink, n * 0.012) * 1.6 + _blur_wrap(ink, n * 0.04) * 0.9
    disc = np.clip(1.0 - r, 0, 1) ** 2 * 0.10 * (r < 1)
    hot = np.clip(ink, 0, 1)
    rgb = (np.stack([1.00, 0.16, 0.05]) * np.clip(glow + disc, 0, 1.5)[..., None]
           + np.stack([1.00, 0.70, 0.35]) * hot[..., None] * 0.95)
    rgb *= (r < 1.0)[..., None]
    rgb = np.clip(rgb, 0, 1)
    alpha = np.clip(np.max(rgb, -1), 0, 1)
    return np.concatenate([rgb, alpha[..., None]], -1)


def ring_rgba(n=128):
    """The shockwave: a thin hot ring with a crimson halo, fading towards its inside."""
    r, a, x, y = _polar(n)
    band = np.exp(-((r - 0.86) / 0.035) ** 2)
    halo = np.exp(-((r - 0.8) / 0.12) ** 2) * 0.55 + np.clip(0.8 - r, 0, 1) * 0.12
    ripple = 0.85 + 0.15 * np.cos(a * 9)
    rgb = (np.stack([1.0, 0.82, 0.55]) * band[..., None] + np.stack([1.0, 0.18, 0.06]) * (halo * ripple)[..., None])
    rgb *= (r < 1.0)[..., None]
    rgb = np.clip(rgb, 0, 1)
    return np.concatenate([rgb, np.max(rgb, -1, keepdims=True)], -1)


MATERIALS = {   # the effect materials: (base rgba, metallic, roughness, extra) as cerberus_model.MATERIALS
    "cerberus_sigil": ((1.0, 1.0, 1.0, 1.0), 0.0, 1.0, {"alpha_mode": "ADD", "emissive": (1.0, 1.0, 1.0), "double": True,
                                                        "png": "sigil"}),
    "cerberus_ring": ((1.0, 1.0, 1.0, 1.0), 0.0, 1.0, {"alpha_mode": "ADD", "emissive": (1.0, 1.0, 1.0), "double": True,
                                                       "png": "ring"}),
}
