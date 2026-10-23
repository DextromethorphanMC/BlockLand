"""On-screen framing of a viewmodel GLB at the runtime projection (VIEWMODEL_SPEC section 2: 54 deg vertical FOV).

python -I tools/assets/framing.py [p9.glb]   -> screen-fraction bounding boxes of the whole gun with the largest
build (every attachment of section 19 fitted at its mount)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                      # noqa: E402

import loadouts as lo                   # noqa: E402

FOV_Y = 54.0
ASPECTS = {"16:9": 16 / 9, "4:3": 4 / 3}


def weapon_points(g, anim=None, t=0.0, root="gun", exclude=(), loadout="largest"):
    """View-space vertices of the weapon (meshes under `root` that the build shows) and its fitted attachments;
    `exclude` names mesh nodes or attachment models to leave out."""
    W = g.world_matrices(g.sample(anim, t) if anim is not None else {})
    ri = g.names[root]
    hidden = lo.hidden_nodes(g, loadout)
    pts = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or n.get("name") in exclude or i in hidden:
            continue
        j = i
        while j in g.parent and j != ri:
            j = g.parent[j]
        if j != ri:
            continue
        M = W[i]
        if abs(np.linalg.det(M[:3, :3])) < 1e-12:
            continue
        for p in g.mesh_primitives(n["mesh"]):
            pts.append(p["positions"] @ M[:3, :3].T + M[:3, 3])
    for name, _slot, AW in lo.attachment_matrices(g, W, loadout):
        if name in exclude:
            continue
        a = lo.model(name)
        for i, n in enumerate(a.nodes):
            if "mesh" in n:
                M = AW[i]
                for p in a.mesh_primitives(n["mesh"]):
                    pts.append(p["positions"] @ M[:3, :3].T + M[:3, 3])
    return np.concatenate(pts)


def bbox(points, aspect):
    """-> (left, top, right, bottom) in screen fractions (0..1, y down), and the nearest point distance."""
    t = np.tan(np.radians(FOV_Y) / 2)
    near = float(np.linalg.norm(points, axis=1).min())
    points = points[-points[:, 2] > 0.02]                 # behind the near plane: never drawn
    if len(points) == 0:
        return (2.0, 2.0, 2.0, 2.0), near
    d = -points[:, 2]
    xn = points[:, 0] / d / (t * aspect)
    yn = points[:, 1] / d / t
    return (0.5 * (xn.min() + 1), 0.5 * (1 - yn.max()), 0.5 * (xn.max() + 1), 0.5 * (1 - yn.min())), near


def report(path, clips=("rest", "idle", "fire", "fire_last", "walk", "sprint", "tac_sprint"), root="gun", exclude=(),
           loadout="largest"):
    """exclude: mesh node / attachment names left out of the box (stocks, grips and magazines may leave the frame)."""
    g = lo.load(path)
    out = {}
    for name in clips:
        a = None if name == "rest" else g.animation(name)
        if name != "rest" and a is None:
            continue
        times = [0.0] if a is None else np.arange(0, g.duration(a) + 1e-9, 1 / 120)
        for asp_name, asp in ASPECTS.items():
            boxes = [bbox(weapon_points(g, a, t, root, exclude, loadout), asp) for t in times]
            l = min(b[0][0] for b in boxes)
            tp = min(b[0][1] for b in boxes)
            r = max(b[0][2] for b in boxes)
            bt = max(b[0][3] for b in boxes)
            near = min(b[1] for b in boxes)
            out[(name, asp_name)] = ((l, tp, r, bt), near)
    return out


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(lo.GUN_DIR, "p9.glb")
    for (name, asp), ((l, t, r, b), near) in report(path).items():
        print(f"{name:10s} {asp:5s} left {l:.3f} top {t:.3f} right {r:.3f} bottom {b:.3f}  "
              f"right margin {(1 - r) * ASPECTS[asp]:+.3f}H  bottom margin {1 - b:+.3f}H  nearest {near:.3f} m")
