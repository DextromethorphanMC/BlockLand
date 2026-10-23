"""Equipment viewmodel rig (VIEWMODEL_SPEC section 36): node layout per item, the right hand's grip fitted round the
item, the ready stance, and pose -> node TRS.

The item is a rigid `equipment` node (design frame of equip_model). The right hand holds it: its palm frame sits at a
fixed place in item space (`grip`), the fingers are wrapped round the item's surface by a fit against its signed
distance (each finger's two curls chosen so its segments lie on the surface without sinking in). A pose authors the
*held frame* (`hold_t`, `hold_q`): the hand follows it, and the item is drawn there while it is in the hand; once
thrown the item is drawn on its own flight (`fly`) while the hand carries on along the held frame (the hand's motion
stays one continuous curve through the release). As for the knives, the wrist limits turn the held frame with the
hand, a free target (`free_r`) lets the hand leave the item, and the left hand has its own target.

Small parts move on their own: `parts` overrides their local TRS (the lever springing off, the injector's shroud),
and `carry` hands a part to the left hand (the pin on the index finger, the injector's cap, the lighter): a
part's palm-local frame, blended in by its weight. The molotov's flame always stands up (it is turned against the
item every frame, with a lag from the item's motion)."""
import numpy as np

import armrig
import equip_model as em
import hands
from p9_rig import POLE_L, POLE_R
from vmath import (frame_quat, norm, quat_axis_angle, quat_conj, quat_identity, quat_mul, quat_rotate, quat_slerp,
                   quat_to_mat)

V = lambda *a: np.array(a, float)
SHOULDER_R = np.array([0.200, -0.330, 0.060])
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
ALIGN_R = 0.30
LEFT_REST = (np.array([-0.10, -0.42, -0.10]), None, hands.blend({"relaxed": 1.0}))
FINGER_R = hands.FINGER_T / 2
HIDDEN_AT_REST = {"flame", "lighter", "lighter_flame"}    # unlit / not in the hand at rest (scale 0)


def item_quat(up, fwd):
    """Item orientation from where its +Y points and where its -Z points (orthogonalised against +Y)."""
    y = norm(up)
    z = -norm(np.asarray(fwd, float) - y * np.dot(fwd, y))
    return frame_quat(np.cross(y, z), y, z)


def along_quat(fwd, up):
    """Item orientation for the long items (knife, injector): -Z along `fwd`, +Y towards `up`."""
    z = -norm(fwd)
    y = norm(np.asarray(up, float) - z * np.dot(up, z))
    return frame_quat(np.cross(y, z), y, z)


# ---------------------------------------------------------------------------------------------- the grip fit
def palm_grip(contact, normal, fwd):
    """(wrist, palm quat) in item space for a palm pressed on `contact`, facing `normal` (into the item), the fingers
    running along `fwd` (wrist -> knuckles)."""
    n = norm(normal)
    f = norm(np.asarray(fwd, float) - n * np.dot(fwd, n))
    thumb = np.cross(f, n)
    q = hands.palm_frame(f, thumb, "R")
    palm_c = np.asarray(contact, float) - n * (hands.PALM_T / 2)
    return palm_c - f * (hands.PALM_LEN / 2), q


def finger_points(side, wrist, q_palm, curls, finger):
    """Points along one finger (middle of the first segment, the middle joint, middle of the second, the tip)."""
    lay = hands.joint_layout(side)
    tr = hands.finger_trs(side, curls)
    _, t1, _ = lay[f"arm_{side}_{finger}"]
    q1 = quat_mul(q_palm, tr[f"arm_{side}_{finger}"]["rotation"])
    p1 = np.asarray(wrist, float) + quat_rotate(q_palm, t1)
    d1 = quat_rotate(q1, (0.0, -1.0, 0.0))
    p2 = p1 + d1 * hands.LENGTHS[finger][0]
    q2 = quat_mul(q1, tr[f"arm_{side}_{finger}2"]["rotation"])
    d2 = quat_rotate(q2, (0.0, -1.0, 0.0))
    tip = p2 + d2 * hands.LENGTHS[finger][1]
    return [p1 + d1 * hands.LENGTHS[finger][0] * 0.5, p2, p2 + d2 * hands.LENGTHS[finger][1] * 0.5, tip]


WEIGHTS = (0.4, 1.0, 1.0, 1.4)


def wrap_cost(points, sdf, curl=None):
    c = 0.0
    if curl is not None:                              # the middle joint follows the knuckle (a natural curl)
        c += 1.5e-8 * (curl[1] - 0.85 * curl[0]) ** 2
    for w, p in zip(WEIGHTS, points):
        d = sdf(p) - FINGER_R
        c += w * (40.0 * d * d if d < 0 else d * d)
    return c


def fit_wrap(wrist, q_palm, sdf, fingers=("index", "middle", "ring", "pinky", "thumb"), base=None):
    """Curls that lay each finger on the item's surface (brute force over both curls, then a finer pass)."""
    curls = {f: np.array(v, float) for f, v in (base or hands.POSES["grip"]).items()}
    for f in fingers:
        ranges = ((-120, 50), (-100, 30)) if f == "thumb" else ((-100, 10), (-100, -6))
        best = (np.inf, curls[f])
        for step, rng in ((5.0, ranges), (1.0, None)):
            if rng is None:
                c0 = best[1]
                rng = ((c0[0] - 5, c0[0] + 5), (c0[1] - 5, min(c0[1] + 5, ranges[1][1])))
            for c1 in np.arange(rng[0][0], rng[0][1] + 1e-6, step):
                for c2 in np.arange(rng[1][0], rng[1][1] + 1e-6, step):
                    trial = dict(curls)
                    trial[f] = np.array([c1, c2])
                    cost = wrap_cost(finger_points("R", wrist, q_palm, trial, f), sdf,
                                     None if f == "thumb" else (c1, c2))
                    if cost < best[0]:
                        best = (cost, np.array([c1, c2]))
        curls[f] = best[1]
    return curls


# --------------------------------------------------------------------------------------------- the items
class ItemDef:
    """One item: meshes per node, the grip, the ready stance, its moving parts and their rest TRS."""

    def __init__(self, key, parts, points, contact, normal, fwd, sdf, rest_t, rest_q, moving=(), viewmodel_parts=(),
                 thumb=None):
        self.key = key
        self.parts = parts                                   # node -> (design mesh, design origin)
        self.points = points                                 # named design points (charm, top / tip)
        self.sdf = sdf
        self.grip = palm_grip(contact, normal, fwd)
        self.curls = fit_wrap(*self.grip, sdf)
        if thumb is not None:                                # a thumb placed on a point (the injector's cap end)
            d, cur = hands.fit_finger("R", self.grip[0], self.grip[1], self.curls, "thumb", thumb,
                                      ranges=((-120, 60), (-100, 40)), step=3)
            self.curls["thumb"] = np.array(cur, float)
        self.viewmodel_parts = set(viewmodel_parts)          # parts hanging on the viewmodel (the lighter)
        self.moving = list(moving)                           # parts with their own TRS channels
        self.part_rest = {}
        for name in self.moving:
            mesh_origin = parts[name][1] if name in parts else V(0, 0, 0)
            self.part_rest[name] = {"translation": mesh_origin.copy(), "rotation": quat_identity(),
                                    "scale": np.zeros(3) if name in HIDDEN_AT_REST else np.ones(3)}
        self.rest_t, self.rest_q = np.asarray(rest_t, float), np.asarray(rest_q, float)
        self.rest_t, self.rest_q = reachable(self, self.rest_t, self.rest_q)

    def parent(self, node):
        if node in self.viewmodel_parts:
            return "viewmodel"
        if node == "lighter_lid" or node == "lighter_flame":
            return "lighter"
        return "equipment"


class EquipPose:
    def __init__(self, item):
        self.item = item
        self.hold_t = item.rest_t.copy()
        self.hold_q = item.rest_q.copy()
        self.fly = None              # (t, q): the item's own frame once it left the hand
        self.spin = 0.0              # degrees the item turns in the loosened hand (about spin_axis through its origin)
        self.spin_axis = (1.0, 0.0, 0.0)
        self.visible = True          # the `equipment` node's scale (STEP): hidden once thrown away
        self.grip = item.grip
        self.fingers_r = None
        self.free_r = None
        self.free_w = 0.0
        self.hand_l = None
        self.left_scale = 0.0
        self.shoulder_r = np.zeros(3)
        self.pole_r = None
        self.pole_l = None
        self.parts = {}              # node -> {translation / rotation / scale} local overrides
        self.carry = {}              # node -> (weight, palm-local position, palm-local quat): in the left hand
        self.flame_lag = np.zeros(3)  # extra lean of the flame (rad about x, z), e.g. from the item's motion
        self.cam_q = quat_identity()

    # -------------------------------------------------------------------------------- helpers
    def item_frame(self):
        if self.fly is not None:
            return self.fly
        if self.spin:
            return self.hold_t, quat_mul(self.hold_q, quat_axis_angle(self.spin_axis, self.spin))
        return self.hold_t, self.hold_q

    def point(self, local):
        t, q = self.item_frame()
        return t + quat_rotate(q, local)

    def grip_target(self):
        w, q = self.grip
        curls = self.fingers_r if self.fingers_r is not None else self.item.curls
        return self.hold_t + quat_rotate(self.hold_q, w), quat_mul(self.hold_q, q), curls

    def _hand_target(self):
        target = self.grip_target()
        if self.free_r is not None and self.free_w > 0:
            target = armrig.blend_targets([(1 - self.free_w, target), (self.free_w, self.free_r)])
        return target

    # ---------------------------------------------------------------------------------- solve
    def solve(self):
        shoulder_r = SHOULDER_R + self.shoulder_r
        pole_r = POLE_R if self.pole_r is None else self.pole_r
        wrist0, q_palm0, _ = self.grip_target()
        target = self._hand_target()
        tr, ar = armrig.solve_side("R", shoulder_r, pole_r, target, align=ALIGN_R, limit=True)
        hold = 1.0 - float(np.clip(self.free_w, 0.0, 1.0))
        if hold > 0:
            q_lim = armrig.limit_palm(q_palm0, norm(ar["hand"] - ar["elbow"]), "R")
            corr = quat_slerp(quat_identity(), quat_mul(q_lim, quat_conj(q_palm0)), hold)
            self.hold_t = wrist0 + quat_rotate(corr, self.hold_t - wrist0)
            self.hold_q = quat_mul(corr, self.hold_q)
        if self.free_w <= 0:
            self.hold_t = self.hold_t + (ar["hand"] - target[0])
        hl = self.hand_l if self.hand_l is not None else LEFT_REST
        tl, al = armrig.solve_side("L", SHOULDER_L, POLE_L if self.pole_l is None else self.pole_l, hl)
        it, iq = self.item_frame()
        trs = {"equipment": {"translation": it.copy(), "rotation": iq.copy(),
                             "scale": np.ones(3) if self.visible else np.zeros(3)},
               "camera": {"rotation": self.cam_q}}
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = shoulder_r
        trs["arm_L"]["scale"] = np.full(3, self.left_scale)
        # the left palm frame, actually reached
        lw, lq = al["hand"], al["q_palm"]
        world = {"equipment": (it, iq)}
        for name in self.item.moving:
            v = dict(self.item.part_rest[name])
            v.update(self.parts.get(name, {}))
            v = {k: np.asarray(x, float).copy() for k, x in v.items()}
            parent = self.item.parent(name)
            if name in self.carry and self.carry[name][0] > 0:
                w, pl, ql = self.carry[name]
                cw_t, cw_q = lw + quat_rotate(lq, pl), quat_mul(lq, ql)
                pt, pq = world.get(parent, (np.zeros(3), quat_identity()))
                inv = quat_conj(pq)
                lt, lqq = quat_rotate(inv, cw_t - pt), quat_mul(inv, cw_q)
                v["translation"] = (1 - w) * v["translation"] + w * lt
                v["rotation"] = quat_slerp(v["rotation"], lqq, w)
            if name == "flame":                       # flames stand up whatever the bottle does
                up = quat_mul(quat_axis_angle((1, 0, 0), np.degrees(self.flame_lag[0])),
                              quat_axis_angle((0, 0, 1), np.degrees(self.flame_lag[2])))
                v["rotation"] = quat_mul(quat_conj(iq), up)
            pt, pq = world.get(parent, (np.zeros(3), quat_identity()))
            world[name] = (pt + quat_rotate(pq, v["translation"]), quat_mul(pq, v["rotation"]))
            trs[name] = v
        return trs, {"R": ar, "L": al, "target_R": target, "target_L": hl}


def reachable(item, t, q):
    """Held frame after the wrist limit for an authored pose (authoring pass for key poses)."""
    p = EquipPose(item)
    p.hold_t, p.hold_q = np.asarray(t, float).copy(), np.asarray(q, float).copy()
    p.solve()
    return p.hold_t.copy(), p.hold_q.copy()


# ----------------------------------------------------------------------------------------- item table
GRENADE_UP, GRENADE_FWD = (-0.06, 0.99, 0.08), (-0.60, 0.0, -0.80)
GRENADE_REST_T = V(0.112, -0.098, -0.305)


def _grenade(key, build, contact, sdf, rest_t=GRENADE_REST_T):
    parts, points = build()
    return ItemDef(key, parts, points, contact, (-1, 0, 0), (0, -0.22, -1), sdf, rest_t,
                   item_quat(GRENADE_UP, GRENADE_FWD), moving=("pin", "lever"))


def _frag_sdf(p):
    return em.sdf_sphere(p)


def _flash_sdf(p):
    return em.sdf_cylinder_y(p, em.FLASH_R, em.FLASH_Y[0] - 0.004, em.FLASH_Y[1] + 0.003)


def _smoke_sdf(p):
    return em.sdf_cylinder_y(p, em.SMOKE_R, em.SMOKE_Y[0], em.SMOKE_Y[1])


def _knife_sdf(p):
    return em.sdf_box_z(p, 0.0062, 0.0130, -0.013, 0.051)


def _pen_sdf(p):
    return em.sdf_cyl_z(p, em.PEN_R, -0.056, 0.060)


def _molotov_items():
    parts, points = em.molotov()
    parts.update(em.lighter())
    return ItemDef("molotov", parts, points, V(em.BOTTLE_R, 0.0, 0.0), (-1, 0, 0), (0, -0.10, -1), em.sdf_bottle,
                   V(0.128, -0.150, -0.300), item_quat((-0.08, 0.97, 0.22), GRENADE_FWD),
                   moving=("flame", "lighter", "lighter_lid", "lighter_flame"), viewmodel_parts=("lighter",))


def _items():
    out = {
        "frag_grenade": _grenade("frag_grenade", em.frag, V(em.FRAG_R + em.LEVER_GAP + em.LEVER_T, -0.006, 0.0), _frag_sdf),
        "flashbang": _grenade("flashbang", em.flashbang, V(em.FLASH_R + em.LEVER_GAP + em.LEVER_T, -0.012, 0.0),
                              _flash_sdf, V(0.108, -0.080, -0.310)),
        "smoke_grenade": _grenade("smoke_grenade", em.smoke_grenade,
                                  V(em.SMOKE_R + em.LEVER_GAP + em.LEVER_T, 0.008, 0.0), _smoke_sdf,
                                  V(0.112, -0.078, -0.320)),
        "molotov": _molotov_items(),
    }
    kparts, kpoints = em.throwing_knife()
    out["throwing_knife"] = ItemDef("throwing_knife", kparts, kpoints, V(0.0062, 0.0, 0.020), (-1, 0, 0),
                                    (0, -1, 0.45), _knife_sdf, V(0.118, -0.098, -0.300),
                                    along_quat((-0.40, 0.25, -0.88), (0.15, 1.0, 0.1)))
    pparts, ppoints = em.adrenaline()
    out["adrenaline"] = ItemDef("adrenaline", pparts, ppoints, V(em.PEN_R, 0.0, 0.004), (-1, 0, 0), (0, 1, 0),
                                _pen_sdf, V(0.120, -0.110, -0.300),
                                along_quat((-0.30, -0.55, -0.78), (0.2, 0.75, -0.6)), moving=("plunger", "cap"),
                                thumb=V(0.0, 0.0, 0.0815))
    return out


ITEMS = {}


def item(key):
    if not ITEMS:
        ITEMS.update(_items())
    return ITEMS[key]


def rest_values(it):
    trs, _ = EquipPose(it).solve()
    return trs
