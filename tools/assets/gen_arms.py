"""Builds models/gun/arms.glb (VIEWMODEL_SPEC sections 7 and 16): 8 skinned arm tubes (shoulder to wrist, tapered with
real-arm proportions, base + sleeve layer per side and skin model) and the hands / fingers, all with 64x64 skin UVs.

Box UV layout of a w x h x d box at texture offset (u, v) (Minecraft ModelPart.Cube):
  top (u+d, v, w, d), bottom (u+d+w, v, w, d), side strip at row v+d with height h:
  +X face (u, d wide), front -Z (u+d, w), -X face (u+d+w, d), back +Z (u+2d+w, w).
Arm space (bind pose, the straight arm): shoulder at y=0, elbow at y=-0.30, wrist at y=-0.60, front = -Z, +X = the
character's right."""
import numpy as np

import hands
import meshkit as mk
import textures as tx
from glb import GLB
from meshkit import Mesh

SKIN_OFFSETS = {("R", False): (40, 16), ("R", True): (40, 32), ("L", False): (32, 48), ("L", True): (48, 48)}
LENGTH = 0.30
# real-arm proportions (section 7, v2): (width x, depth z) of a wide-skin segment at its joint end and at its far
# end; the boxes taper towards the elbow / wrist. Slim skins (3 px arms) are SLIM as wide.
SECTIONS = {"upper": ((0.056, 0.058), (0.050, 0.050)),
            "fore": ((0.050, 0.048), (0.043, 0.038))}
SLIM = 0.75
INFLATE = 0.003                               # sleeve (overlay) layer, per side


def section(segment, slim, end):
    """(width, depth) of a segment's cross-section at its joint end ("joint") or far end ("far")."""
    w, d = SECTIONS[segment][0 if end == "joint" else 1]
    return (w * SLIM if slim else w), d


# v3: the upper arm and the forearm are ONE skinned tube anchored at the shoulder (arm_X), bound to the straight arm
# (elbow at y = -0.30, wrist at -0.60). Rings are denser around the elbow; the elbow blends upper -> fore over
# +-ELBOW_BLEND, the forearm's end blends half into the hand (the palm's start blends half into the forearm), so elbow
# and wrist bend round (dual-quaternion skinning, client/viewmodel/ArmSkin and armskin.py).
ARM_LEN = 2 * LENGTH
ELBOW, WRIST = -LENGTH, -ARM_LEN
ELBOW_BLEND = 0.045
WRIST_BLEND = 0.030
RINGS = (0.0, -0.08, -0.16, -0.21, -0.245, -0.27, -0.29, -0.30, -0.31, -0.325, -0.345, -0.365, -0.385, -0.41,
         -0.44, -0.49, -0.54, -0.575, -0.59, -0.60)
# the forearm's twist (pronation) comes in below the elbow hinge, over the forearm's upper half (as the radius turns
# about the ulna), mostly out of view: the forearm on screen stays a straight box
TWIST_START, TWIST_END = ELBOW - 0.01, ELBOW - 0.15


def tube_section(y, slim):
    """(width, depth) of the tube at bind height y: the section-7 sections, linear between shoulder, elbow, wrist."""
    (uw0, ud0), (uw1, ud1) = SECTIONS["upper"]
    (fw0, fd0), (fw1, fd1) = SECTIONS["fore"]
    keys = ((0.0, uw0, ud0), (ELBOW, (uw1 + fw0) / 2, (ud1 + fd0) / 2), (WRIST, fw1, fd1))
    y = min(max(y, WRIST), 0.0)
    for (ya, wa, da), (yb, wb, db) in zip(keys, keys[1:]):
        if yb <= y <= ya:
            u = (ya - y) / (ya - yb)
            w, d = wa + (wb - wa) * u, da + (db - da) * u
            return (w * SLIM if slim else w), d
    return keys[-1][1], keys[-1][2]


def smooth(e0, e1, x):
    u = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return u * u * (3 - 2 * u)


def arm_weights(y):
    """(upper, untwisted fore, fore, hand) weights of a tube vertex at bind height y: the elbow blends the upper arm into
    the forearm without its twist, the twist comes in down the forearm, the wrist end blends half into the hand."""
    hand = 0.5 * smooth(WRIST + WRIST_BLEND, WRIST, y)
    fore = smooth(ELBOW + ELBOW_BLEND, ELBOW - ELBOW_BLEND, y) - hand
    twist = smooth(TWIST_START, TWIST_END, y)
    return 1.0 - fore - hand, fore * (1 - twist), fore * twist, hand


def arm_tube(side, slim, sleeve):
    """Shoulder-to-wrist tube in arm_X space (bind pose), Minecraft box UVs: the 12-px side strip runs down the whole
    arm (upper = top 6 rows, forearm = bottom 6), the shoulder cap is the top face, the wrist cap the bottom face."""
    u0, v0 = SKIN_OFFSETS[(side, sleeve)]
    wp, dp = (3 if slim else 4), 4
    g = INFLATE if sleeve else 0.0
    ys = [r for r in RINGS]
    ys[0], ys[-1] = g, WRIST - g
    rows = [v0 + dp + 12 * min(max(-y / ARM_LEN, 0.0), 1.0) for y in ys]
    half = [(tube_section(y, slim)[0] / 2 + g, tube_section(y, slim)[1] / 2 + g) for y in ys]
    mat = "skin_overlay" if sleeve else "skin"
    s = 1.0 / 64.0
    m = Mesh()

    def c(sx, k, sz):                      # corner of ring k: sx = -1/+1 (-X/+X side), sz = -1 front / +1 back
        return (sx * half[k][0], ys[k], sz * half[k][1])

    def quad(p, uv):                       # corners listed clockwise from outside -> reverse to CCW
        m.add_poly(p[::-1], mat, uvs=[(a * s, b * s) for a, b in uv][::-1])

    for J in range(len(ys) - 1):
        F = J + 1
        r0, r1 = rows[J], rows[F]
        quad([c(1, J, -1), c(1, F, -1), c(1, F, 1), c(1, J, 1)],
             [(u0 + dp, r0), (u0 + dp, r1), (u0, r1), (u0, r0)])
        quad([c(1, J, -1), c(-1, J, -1), c(-1, F, -1), c(1, F, -1)],
             [(u0 + dp, r0), (u0 + dp + wp, r0), (u0 + dp + wp, r1), (u0 + dp, r1)])
        quad([c(-1, J, -1), c(-1, J, 1), c(-1, F, 1), c(-1, F, -1)],
             [(u0 + dp + wp, r0), (u0 + 2 * dp + wp, r0), (u0 + 2 * dp + wp, r1), (u0 + dp + wp, r1)])
        quad([c(-1, J, 1), c(1, J, 1), c(1, F, 1), c(-1, F, 1)],
             [(u0 + 2 * dp + wp, r0), (u0 + 2 * dp + 2 * wp, r0), (u0 + 2 * dp + 2 * wp, r1), (u0 + 2 * dp + wp, r1)])
    J, F = 0, len(ys) - 1
    quad([c(1, J, 1), c(-1, J, 1), c(-1, J, -1), c(1, J, -1)],
         [(u0 + dp, v0), (u0 + dp + wp, v0), (u0 + dp + wp, v0 + dp), (u0 + dp, v0 + dp)])
    quad([c(1, F, -1), c(-1, F, -1), c(-1, F, 1), c(1, F, 1)],
         [(u0 + dp + wp, v0 + dp), (u0 + dp + 2 * wp, v0 + dp), (u0 + dp + 2 * wp, v0), (u0 + dp + wp, v0)])
    return m


def skin_arm(prims):
    """JOINTS_0 / WEIGHTS_0 of the tube from each vertex's bind height (skin = upper, untwisted fore, fore, hand)."""
    for p in prims:
        p["weights"] = np.array([arm_weights(y) for y in p["positions"][:, 1]])
        p["joints"] = np.tile(np.array([0, 1, 2, 3], np.uint8), (len(p["weights"]), 1))
    return prims


def skin_palm(prims):
    """The palm's start (wrist end) blends half into the forearm (skin = fore, hand)."""
    for p in prims:
        fore = np.array([hands.palm_fore_weight(y) for y in p["positions"][:, 1]])
        p["joints"] = np.tile(np.array([0, 1, 0, 0], np.uint8), (len(fore), 1))
        p["weights"] = np.column_stack([fore, 1.0 - fore, np.zeros(len(fore)), np.zeros(len(fore))])
    return prims


def preview_skin():
    """Our own 64x64 test skin (rolled sleeves, bare hands); only for previews / the default texture."""
    rng = np.random.default_rng(5)
    img = np.zeros((64, 64, 4))
    shirt = np.array([0.20, 0.42, 0.55])
    skin = np.array([0.86, 0.66, 0.50])
    for (side, sleeve), (u0, v0) in SKIN_OFFSETS.items():
        region = img[v0:v0 + 16, u0:u0 + 16]
        n = 0.92 + 0.16 * rng.random((16, 16, 1))
        if not sleeve:
            base = np.tile(skin, (16, 16, 1))
            base[4:10] = shirt                              # sleeve on the upper segment
            base[0:4, 4:8] = shirt                          # shoulder top
            base[14:16] *= 0.86                             # knuckles / hand end darker
            base[4:16, 0:4] *= 0.92                         # outer side slightly darker
            region[:, :, :3] = np.clip(base * n, 0, 1)
            region[:, :, 3] = 1.0
        else:
            over = np.tile(shirt * 0.8, (16, 16, 1)) * n
            a = np.zeros((16, 16))
            a[4:12] = 1.0                                   # rolled sleeve ends 2 px into the forearm
            a[0:4, 4:8] = 1.0
            over[11:12] *= 0.75                             # cuff
            region[:, :, :3] = np.clip(over, 0, 1)
            region[:, :, 3] = a
    return img


def build(path, embed_skin=True):
    g = GLB()
    png = tx.png_bytes(preview_skin()) if embed_skin else None
    g.add_material("skin", (1, 1, 1, 1), 0.0, 0.9, texture_png=png)
    g.add_material("skin_overlay", (1, 1, 1, 1), 0.0, 0.9, alpha_mode="MASK", alpha_cutoff=0.1, texture_png=png)
    # glTF samplers: Minecraft skins are pixel art -> nearest filtering
    for s in g.json.get("samplers", []):
        s["magFilter"], s["minFilter"] = 9728, 9728
    tris = 0
    for side in ("R", "L"):
        for variant in ("wide", "slim"):
            for sleeve in (False, True):
                name = f"{side}_arm_{variant}" + ("_sleeve" if sleeve else "")
                prims = skin_arm(mk.build_primitives(arm_tube(side, variant == "slim", sleeve)))
                tris += mk.triangle_count(prims)
                g.add_node(name, None, mesh=g.add_mesh(name, prims),
                           extras={"joint": f"arm_{side}", "variant": variant, "layer": "sleeve" if sleeve else "base",
                                   "skin": [f"arm_{side}", f"arm_{side}_fore:untwisted", f"arm_{side}_fore",
                                            f"arm_{side}_hand"],
                                   "bind": [[0.0, 0.0, 0.0], [0.0, ELBOW, 0.0], [0.0, ELBOW, 0.0], [0.0, WRIST, 0.0]]})
        for suffix, joint_name, fn in hands.mesh_joints(side):      # palm + 10 finger segments, base layer
            for variant in ("wide", "slim"):
                name = f"{side}_{suffix}_{variant}"
                prims = mk.build_primitives(fn(variant))
                extras = {"joint": joint_name, "variant": variant, "layer": "base"}
                if suffix == "hand":
                    prims = skin_palm(prims)
                    extras.update(skin=[f"arm_{side}_fore", joint_name], bind=[[0.0, LENGTH, 0.0], [0.0, 0.0, 0.0]])
                tris += mk.triangle_count(prims)
                g.add_node(name, None, mesh=g.add_mesh(name, prims), extras=extras)
    g.save(path)
    return tris
