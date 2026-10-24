"""Section 6 checks on every model file, used by check_glb.py: faces wound outwards and no unintended see-through
materials. The runtime culls the back faces of every material that is not `doubleSided`, so an inside-out part is
invisible from outside and the weapon looks hollow (the first Cerberus conversion: its source relied on double-sided
materials).

* winding: within a surface, the two triangles of every shared edge run it in opposite directions; closed surfaces
  enclose a positive volume (mesh_orient.analyse);
* what the player sees: the model in its rest pose, rendered without culling from 14 directions — the nearest surface
  at a pixel must (nearly) never be a back face (bores and optic interiors are front faces seen from inside, so they
  pass);
* alpha: only glass, lenses, reticles and the arms' sleeve layer may blend, cut out or be double sided."""
import glob
import os

import numpy as np

import mesh_orient as mo
import raster as rs
from glbread import GLTF

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models")
#: materials allowed to be BLEND / MASK / double sided (glass, lens glass, reticle dots, the skin's sleeve layer)
SEE_THROUGH = {"glass", "lens", "lens_front", "reticle", "skin_overlay"}
VIEWS = [np.array(v, float) / np.linalg.norm(v) for v in
         [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1),
          (1, 1, 1), (1, 1, -1), (1, -1, 1), (1, -1, -1), (-1, 1, 1), (-1, 1, -1), (-1, -1, 1), (-1, -1, -1)]]
SIZE = 192
# share of a part's visible pixels that may be back faces: above WARN it is reported (a slit between two parts shows
# the inside of one), above FAIL it fails (an inside-out part: the first Cerberus conversion showed 93 % on its slide)
BACK_PART_WARN, BACK_PART_FAIL = 0.05, 0.15
BACK_MODEL_WARN, BACK_MODEL_FAIL = 0.01, 0.03     # the same for the whole model (the first Cerberus: 58 %)
MIN_PIXELS = 300        # parts smaller than this on screen (over all views) are not judged by the share


def model_files():
    return sorted(glob.glob(os.path.join(MODELS, "*", "*.glb")))


def _parts(g):
    """(node name, material name, world-space positions, triangles, see-through) of every drawn primitive, rest pose."""
    W = g.world_matrices({})
    mats = g.json.get("materials", [])
    out = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n:
            continue
        M = W[i]
        if abs(np.linalg.det(M[:3, :3])) < 1e-12:
            continue                                   # collapsed at rest (shown only during a clip)
        flip = np.linalg.det(M[:3, :3]) < 0
        for p in g.mesh_primitives(n["mesh"]):
            m = mats[p["material"]] if p.get("material") is not None else {}
            pos = p["positions"] @ M[:3, :3].T + M[:3, 3]
            tri = p["indices"][:, [0, 2, 1]] if flip else p["indices"]
            see_through = bool(m.get("doubleSided")) or m.get("alphaMode", "OPAQUE") != "OPAQUE"
            out.append((n.get("name", f"#{i}"), m.get("name", ""), pos, tri, see_through, p["positions"], p["indices"]))
    return out


def check_alpha(g, r, label):
    for m in g.json.get("materials", []):
        name = m.get("name", "")
        # additive effect quads (VIEWMODEL_SPEC section 38: sigils, shockwaves) are light, not surfaces
        effect = m.get("alphaMode") == "ADD" and m.get("extras", {}).get("region") == "none"
        if (m.get("alphaMode", "OPAQUE") != "OPAQUE" or m.get("doubleSided")) and name not in SEE_THROUGH and not effect:
            r.check(False, f"{label}: material {name} is {m.get('alphaMode', 'OPAQUE')}"
                           f"{' double sided' if m.get('doubleSided') else ''} (only {sorted(SEE_THROUGH)} may be)")


def check_winding(parts, r, label):
    for node, material, _, _, see_through, local, tri in parts:
        if see_through:
            continue
        inconsistent, inside_out, _, _ = mo.analyse(local, tri)
        r.check(inconsistent == 0, f"{label}: {node}/{material}: {inconsistent} edges where neighbouring triangles "
                                   f"are wound opposite ways (one of them is culled)")
        r.check(inside_out == 0, f"{label}: {node}/{material}: {inside_out} closed surface(s) inside out")


def check_views(parts, r, label):
    """Back faces seen from outside, per part and for the whole model. Glass and the other see-through parts hide what
    is behind them (you look at the glass) but are not judged."""
    solid = [p for p in parts if not p[4]]
    if not solid:
        return
    glass = [p for p in parts if p[4]]
    allpos = np.concatenate([p[2] for p in solid])
    centre = 0.5 * (allpos.min(0) + allpos.max(0))
    radius = max(np.linalg.norm(allpos - centre, axis=1).max(), 1e-4)
    seen = np.zeros(len(solid))
    back = np.zeros(len(solid))
    for d in VIEWS:
        z = -d                                            # camera looks along -d... from +d towards the centre
        up = np.array([0.0, 1.0, 0.0]) if abs(z[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
        x = np.cross(up, -z)
        x /= np.linalg.norm(x)
        y = np.cross(-z, x)
        R = np.stack([x, y, -z])                          # world -> view (rows), view looks down -Z
        eye = centre + d * radius * 3
        items = []
        for k, (_, _, pos, tri, _, _, _) in enumerate(solid + glass):
            items.append({"pos": (pos - eye) @ R.T, "nrm": np.zeros_like(pos), "idx": tri,
                          "mat": {"base": (1, 1, 1, 1), "tex": None, "alpha_mode": "OPAQUE", "double": True,
                                  "id": k if k < len(solid) else -1}})
        cam = rs.Camera(SIZE, SIZE, ortho_half_h=radius * 1.05)
        out, res = rs.rasterize(items, cam, SIZE, SIZE, flat=True)
        if res is None or "opaque" not in res[0]:
            continue
        frag = res[0]["opaque"]
        # the same view with back faces culled, as the game draws it: a pixel shows a back face when, without culling,
        # the nearest surface lies clearly in front of the nearest front face (a two-sided sheet's copies tie)
        culled = [dict(it, mat=dict(it["mat"], double=False)) for it in items]
        front_out, _ = rs.rasterize(culled, cam, SIZE, SIZE, flat=True)
        pix = frag["pix"]
        is_back = out["depth"][pix] < front_out["depth"][pix] - radius * 2e-3
        ids = np.array([m["id"] for m in res[1]])[frag["mid"]]
        judged = ids >= 0
        np.add.at(seen, ids[judged], 1)
        np.add.at(back, ids[judged], is_back[judged].astype(float))
    for k, (node, material, *_rest) in enumerate(solid):
        if seen[k] >= MIN_PIXELS:
            share = back[k] / seen[k]
            r.check(share <= BACK_PART_FAIL, f"{label}: {node}/{material} shows its back faces from outside "
                                             f"({share:.0%} of its pixels): inside out or wound the wrong way")
            if BACK_PART_WARN < share <= BACK_PART_FAIL:
                r.note(f"{label}: warning: {node}/{material} shows {share:.0%} back faces from outside "
                       f"(a gap where its inside shows, or a thin sheet seen from behind)")
    total = back.sum() / max(seen.sum(), 1)
    r.check(total <= BACK_MODEL_FAIL, f"{label}: {total:.1%} of the visible model is back faces (inside-out parts)")
    r.note(f"{label}: {'warning: ' if total > BACK_MODEL_WARN else ''}back faces seen from outside {total:.2%} of "
           f"{int(seen.sum())} px over {len(VIEWS)} views")


def check_file(path, r):
    label = os.path.relpath(path, MODELS).replace("\\", "/")
    g = GLTF(path)
    parts = _parts(g)
    check_alpha(g, r, label)
    check_winding(parts, r, label)
    check_views(parts, r, label)


def check_all(r, paths=None):
    """Every model file (weapons, special-skin models, knives, attachments, arms), or just `paths`."""
    for path in paths if paths else model_files():
        check_file(path, r)
