"""First-person resupply previews (VIEWMODEL_SPEC section 46): a gun's viewmodel at rest with the resupply motion of
assets/blockland/supply/resupply.json applied exactly as client/supply/ResupplyOverlay applies it (the root lowered and
canted about the pivot, the left arm's joints swung rigidly about the shoulder, the gun's own magazine mesh in the left
hand), at a row of times.

python -I tools/assets/ammo_crate_preview.py [gun ...] [--stow] [--out DIR]   -> DIR/resupply_<gun>[_stow].png
(default guns: ak47 p9 mp5; DIR: tools/assets/out)"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402
from PIL import Image, ImageDraw                     # noqa: E402

import loadouts as lo                                # noqa: E402
import raster as rs                                  # noqa: E402
from preview import FOV_Y, GUN_DIR, NEAR, OUT, load_materials   # noqa: E402
from vmath import norm, quat_axis_angle, quat_identity, quat_mul, quat_rotate, quat_slerp, quat_to_mat   # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")
MOTION = os.path.join(RES, "assets", "blockland", "supply", "resupply.json")
GUNS_JSON = os.path.join(RES, "data", "blockland", "guns", "guns.json")


def motion():
    with open(MOTION, encoding="utf-8") as f:
        return json.load(f)


def eased(keys, t, c):
    """ResupplyMotion.eased: component c of the keys at t, each segment smoothstepped."""
    if not keys:
        return 0.0
    if t <= keys[0][0]:
        return keys[0][c]
    for a, b in zip(keys[:-1], keys[1:]):
        if t < b[0]:
            u = (t - a[0]) / max(1e-4, b[0] - a[0])
            u = u * u * (3 - 2 * u)
            return a[c] + (b[c] - a[c]) * u
    return keys[-1][c]


def pose(part, reload, t):
    """(pitch, yaw, roll, x, y, z) of a part at t."""
    keys = part["reach" if reload else "stow"]
    return [eased(keys, t, c) for c in range(1, 7)]


def T(v):
    m = np.eye(4)
    m[:3, 3] = v
    return m


def R(axis, deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    m = np.eye(4)
    i, j = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}[axis]
    m[i, i], m[i, j], m[j, i], m[j, j] = c, -s, s, c
    return m


def root_adjust(mo, reload, t):
    """ResupplyOverlay.adjustRoot as a matrix (applied after everything else on the root)."""
    pi, ya, ro, x, y, z = pose(mo["gun"], reload, t)
    p = np.array(mo["pivot"], float)
    return T((x, y, z)) @ T(p) @ R("x", pi) @ R("y", ya) @ R("z", ro) @ T(-p)


def Rq(q):
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(q)
    return m


def rotation_to(a, b):
    """JOML Quaternionf.rotationTo: the shortest turn taking direction a onto direction b."""
    a, b = norm(a), norm(b)
    axis = np.cross(a, b)
    s = np.linalg.norm(axis)
    if s < 1e-9:
        return quat_identity()
    return quat_axis_angle(axis / s, np.degrees(np.arctan2(s, float(np.dot(a, b)))))


def arm_joints(g):
    root = g.names.get("arm_L")
    out = []
    for i in range(len(g.nodes)):
        n = i
        while n is not None:
            if n == root:
                out.append(i)
                break
            n = g.parent.get(n)
    return out


def posed(g, gun, mo, reload, t):
    """World matrices (dict) of the rest pose with the resupply applied at t."""
    W = g.world_matrices({})
    A = root_adjust(mo, reload, t)
    W = {k: A @ v for k, v in W.items()}
    arm = mo["arm"]
    keys = arm["reach" if reload else "stow"]
    wd, wu = eased(keys, t, 1), eased(keys, t, 2)
    if (wd > 0 or wu > 0) and "arm_L" in g.names and "gun_L" not in g.names:
        sh = W[g.names["arm_L"]][:3, 3].copy()
        pi, ya, ro, x, y, z = arm["down"]
        q_down = quat_mul(quat_mul(quat_axis_angle((0, 1, 0), ya), quat_axis_angle((1, 0, 0), pi)),
                          quat_axis_angle((0, 0, 1), ro))
        off_down = np.array((x, y, z), float)
        mag = magazine_node(g, gun)
        if mag is not None:
            frm = W[g.names["arm_L_hand"]][:3, 3] - sh
            to = W[mag][:3, 3] + np.array(arm["up"], float) - sh
            q_up = rotation_to(frm, to)
            off_up = to - quat_rotate(q_up, frm)
        else:
            q_up, off_up = q_down, off_down
        q = quat_slerp(quat_slerp(quat_identity(), q_down, wd), q_up, wu)
        off = off_down * wd * (1 - wu) + off_up * wu
        M = T(sh + off) @ Rq(q) @ T(-sh)
        for j in arm_joints(g):
            W[j] = M @ W[j]
    return W


def magazine_node(g, gun):
    with open(GUNS_JSON, encoding="utf-8") as f:
        data = json.load(f)
    mag = data["magazines"][data["guns"][gun]["magazines"][0]]
    return g.names.get(mag.get("node", ""))


def magazine_matrix(g, mo, W):
    mag = mo["magazine"]
    rx, ry, rz = mag["rotation"]
    return W[g.names["arm_L_hand"]] @ T(np.array(mag["offset"], float)) @ R("x", rx) @ R("y", ry) @ R("z", rz)


def frame(g, arms, gun, mo, reload, t, w=480, h=270):
    W = posed(g, gun, mo, reload, t)
    mats = load_materials(g)
    items = []
    hide = lo.hidden_nodes(g, "default")
    for i, n in enumerate(g.nodes):
        if "mesh" in n and i not in hide:
            lo.instance(g, mats, n["mesh"], W[i], items)
    import armskin
    amats = load_materials(arms)
    for node in arms.nodes:
        ex = node.get("extras", {})
        if "mesh" in node and ex.get("variant") == "wide" and ex.get("joint") in g.names:
            for p, pos, nrm in armskin.mesh_world(arms, node, g, W):
                items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": amats[p["material"]]})
    span = mo["magazine"]["reach" if reload else "stow"]
    node = magazine_node(g, gun)
    if node is not None and span[0] <= t < span[1] and "mesh" in g.nodes[node]:
        lo.instance(g, mats, g.nodes[node]["mesh"], magazine_matrix(g, mo, W), items)
    img = Image.fromarray(rs.render(items, rs.Camera(w, h, FOV_Y, near=NEAR), bg=(0.40, 0.45, 0.52), ss=2))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 120, 14], fill=(0, 0, 0))
    d.text((3, 1), f"{gun} t={t:.2f}{'' if reload else ' stow'}", fill=(255, 255, 255))
    return img


def sheet(gun, reload=True, out=OUT, cols=4):
    mo = motion()
    g = lo.load(os.path.join(GUN_DIR, f"{gun}.glb"))
    arms = lo.load(os.path.join(GUN_DIR, "arms.glb"))
    dur = mo["reach"] if reload else mo["stow"]
    times = list(np.round(np.linspace(0, dur, 8), 3))
    tiles = [frame(g, arms, gun, mo, reload, t) for t in times]
    rows = (len(tiles) + cols - 1) // cols
    im = Image.new("RGB", (cols * 480, rows * 270))
    for k, tile in enumerate(tiles):
        im.paste(tile, ((k % cols) * 480, (k // cols) * 270))
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, f"resupply_{gun}{'' if reload else '_stow'}.png")
    im.save(path)
    return path


def main(argv):
    out = OUT
    if "--out" in argv:
        out = argv[argv.index("--out") + 1]
        argv = [a for a in argv if a not in ("--out", out)]
    stow = "--stow" in argv
    guns = [a for a in argv if not a.startswith("--")] or ["ak47", "p9", "mp5"]
    for gun in guns:
        print("wrote", sheet(gun, not stow, out))


if __name__ == "__main__":
    main(sys.argv[1:])
