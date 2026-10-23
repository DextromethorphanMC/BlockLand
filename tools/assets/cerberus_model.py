"""Cerberus pistol, the dual USP's special skin 地狱犬 (VIEWMODEL_SPEC section 26).

Converted from *GunGrave Dual Pistols* by sandyyusuf0 (CC-BY-4.0, see CREDITS.md) and reworked: one of the two
pistols is taken apart into the contract's parts, scaled so its grip fits a hand (0.74), its long slab shortened in
front of the trigger guard (the slide ends ≈ 0.26 m from the back of the grip instead of 0.46 m), and changed into a
design of our own — the screws and the cross emblems are gone, an engraved crest (a shield with three fangs) sits on
each side of the slide, a ribbed top, red inlay strips, a slide stop, magazines and a bore; the source's textures and
flat colours are not used (our materials and texture sets, section 20).

The source GLB is untrusted input: it is only parsed (glbread), never executed. Its path comes from the command line
or BLOCKLAND_CERBERUS_SRC (default: ~/Downloads/gungrave_dual_pistols.glb). The converted parts are cached in
data/cerberus_parts.npz, so the pistol rebuilds (rig or clip changes) without the source.

python -I tools/assets/cerberus_model.py [source.glb]   -> refreshes the cache from the source"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import numpy as np                              # noqa: E402

import mesh_orient                              # noqa: E402
import shapes as sh                             # noqa: E402
from meshkit import Mesh                        # noqa: E402
from p9_model import chamfer_bevel              # noqa: E402

CACHE = os.path.join(HERE, "data", "cerberus_parts.npz")
SRC_DEFAULT = os.path.join(os.path.expanduser("~"), "Downloads", "gungrave_dual_pistols.glb")

# source node (parent of the mesh) -> part, for the first of the two pistols; the screws are left out
SOURCE_PARTS = {"butt_6": "slab", "cross_4": "strip", "keker_5": "sights", "Plane_7": "frame",
                "Plane.001_8": "grip_frame", "Plane.002_9": "grip", "Plane.003_10": "trigger",
                "Plane.004_11": "hammer", "Cylinder.002_22": "crown"}
SRC_ANGLE = 33.128              # the source pistols lie rotated this much in their side plane
SRC_BORE_Y = 0.1975             # bore height of the source pistol (centre of its muzzle crown)
SCALE = 0.74                    # grip length 0.099 -> 0.073 m
SHIFT_Z = 0.0956                # puts the top of the grip where the USP's is (z 0.0545)
SQUEEZE_Z, SQUEEZE = -0.025, 0.62   # everything in front of the trigger guard is shortened by SQUEEZE
CROSS_Z = (-0.214, -0.157)      # the source's cross emblem (removed), source z
STRIP_Y = (0.2115, 0.2245)      # the inlay strip between the slide and the lower bar, source y


# ----------------------------------------------------------------------------------------- conversion
def _to_design(p):
    """Source scene coordinates -> our design frame (+X right, +Y up, +Z rearward, bore on x = y = 0)."""
    a = np.radians(SRC_ANGLE)
    rot = np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1.0]])
    q = p @ rot.T                                            # the pistol level, muzzle towards +x
    d = np.stack([q[:, 2], q[:, 1], -q[:, 0]], 1)            # X = z, Y = y, Z = -x
    return d


def _fit(d):
    """Design-frame source points -> scaled, shifted and shortened in front of the trigger guard."""
    out = (d - np.array([0.0, SRC_BORE_Y, 0.0])) * SCALE + np.array([0.0, 0.0, SHIFT_Z])
    z = out[:, 2]
    out[:, 2] = np.where(z < SQUEEZE_Z, SQUEEZE_Z + (z - SQUEEZE_Z) * SQUEEZE, z)
    return out


def _world_matrices(g):
    from glbread import trs
    mats = {}

    def local(i):
        n = g.nodes[i]
        if "matrix" in n:
            return np.array(n["matrix"], float).reshape(4, 4).T
        return trs(n.get("translation", [0, 0, 0]), n.get("rotation", [0, 0, 0, 1]), n.get("scale", [1, 1, 1]))

    def world(i):
        if i not in mats:
            m = local(i)
            mats[i] = world(g.parent[i]) @ m if i in g.parent else m
        return mats[i]

    for i in range(len(g.nodes)):
        world(i)
    return mats


def convert(src):
    """-> {part: (vertices (N, 3) design frame, triangles (T, 3))} from the source GLB."""
    from glbread import GLTF
    g = GLTF(src)
    W = _world_matrices(g)
    parts = {}
    for name, part in SOURCE_PARTS.items():
        i = g.names[name]
        for c in g.nodes[i].get("children", []):
            node = g.nodes[c]
            if "mesh" not in node:
                continue
            M = W[c]
            for p in g.mesh_primitives(node["mesh"]):
                src_d = _to_design(p["positions"] @ M[:3, :3].T + M[:3, 3])
                tri = p["indices"]
                key = part
                if part == "slab":                       # one mesh: the slide block above, the lower bar below
                    if p["material"] == 0:
                        key = "port"
                    else:
                        cy = src_d[tri].mean(1)[:, 1]
                        _add(parts, "slide_block", src_d, tri[cy > 0.218])
                        _add(parts, "lower_bar", src_d, tri[cy <= 0.218])
                        continue
                if part == "strip":                      # keep the inlay strip, drop the cross
                    c3 = src_d[tri].mean(1)
                    keep = (c3[:, 1] > STRIP_Y[0]) & (c3[:, 1] < STRIP_Y[1]) & \
                           ~((c3[:, 2] > CROSS_Z[0]) & (c3[:, 2] < CROSS_Z[1]))
                    tri = tri[keep]
                _add(parts, key, src_d, tri)
    # the source's materials are double-sided, so its winding is mixed and whole pieces are inside out (the frame, the
    # slide block); ours are culled: every part is re-wound to face outwards (mesh_orient)
    fitted = {k: (_fit(v), t) for k, (v, t) in parts.items()}
    out = {}
    for k, (v, t) in fitted.items():
        others = [fitted[o] for o in fitted if o != k]
        ov = np.concatenate([o[0] for o in others])
        of = np.concatenate([o[1] + sum(len(p[0]) for p in others[:i]) for i, o in enumerate(others)])
        out[k] = (v, mesh_orient.orient(v, t, occluders=(ov, of)))
    return out


def _add(parts, key, verts, tri):
    """Appends triangles (with only the vertices they use) to a part."""
    if len(tri) == 0:
        return
    used, inv = np.unique(tri.reshape(-1), return_inverse=True)
    v, t = verts[used], inv.reshape(-1, 3)
    if key in parts:
        v0, t0 = parts[key]
        parts[key] = (np.concatenate([v0, v]), np.concatenate([t0, t + len(v0)]))
    else:
        parts[key] = (v, t)


def save_cache(parts):
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    arrays = {}
    for k, (v, t) in parts.items():
        arrays[f"{k}__v"] = v.astype(np.float32)
        arrays[f"{k}__t"] = t.astype(np.int32)
    np.savez_compressed(CACHE, **arrays)


def load_parts(src=None):
    """The converted parts: from the source when it is there (refreshing the cache), else from the cache."""
    src = src or os.environ.get("BLOCKLAND_CERBERUS_SRC") or SRC_DEFAULT
    if src and os.path.exists(src):
        parts = convert(src)
        save_cache(parts)
        return parts
    if not os.path.exists(CACHE):
        raise FileNotFoundError(f"Cerberus source {src} not found and no cache {CACHE}")
    data = np.load(CACHE)
    keys = sorted({k.rsplit("__", 1)[0] for k in data.files})
    return {k: (data[f"{k}__v"].astype(np.float64), data[f"{k}__t"].astype(np.int64)) for k in keys}


def _mesh(part, material):
    v, t = part
    m = Mesh()
    base = m.add_vertices(v)
    for tri in t:
        m.add_face([base + int(i) for i in tri], material)
    return m


# ------------------------------------------------------------------------------------------- our parts
SLIDE_W = 0.0111                # half width of the slide block (converted)
SLIDE_Y = (0.0189, 0.0426)      # slide block bottom / top above the bore
FRAME_W = 0.0082                # half width of the frame under the slide
CREST_Z = -0.035                # where the source's cross was: our crest on both flanks of the slide
HAMMER_PIVOT = np.array([0.0, 0.0107, 0.0460])
TRIGGER_PIVOT = np.array([0.0, -0.0026, 0.0012])
STOP_PIVOT = np.array([-(FRAME_W + 0.0009), -0.0050, 0.0300])


def crest_mesh(side):
    """Our engraved crest: a pointed shield (red inlay) with three fangs standing proud of it (body steel)."""
    shield = [(-0.0085, 0.0100), (0.0085, 0.0100), (0.0085, -0.0010), (0.0040, -0.0080), (0.0, -0.0102),
              (-0.0040, -0.0080), (-0.0085, -0.0010)]
    fangs = []
    for k, (cz, h) in enumerate(((-0.0045, 0.0118), (0.0, 0.0150), (0.0045, 0.0118))):
        fangs.append([(cz - 0.0020, 0.0070), (cz + 0.0020, 0.0070), (cz, 0.0070 - h)])
    x0 = side * SLIDE_W
    cy = 0.5 * (SLIDE_Y[0] + SLIDE_Y[1])

    def plate(profile, depth, out, material):
        # profile in (z, y) relative to the crest centre; extruded from the flank outwards by `out`
        pts = [(CREST_Z + z, cy + y) for z, y in profile]
        return sh.extrude_x(pts, (), depth / 2, chamfer_bevel(min(0.0003, depth / 3)), material,
                            x_center=x0 + side * (out - depth / 2))

    m = plate(shield, 0.0009, 0.0006, "cerberus_trim")
    for f in fangs:
        m.merge(plate(f, 0.0008, 0.0011, "cerberus_body"))
    return m


def ribs_mesh():
    """Transverse ribs along the top of the slide between the sights."""
    m = Mesh()
    top = SLIDE_Y[1]
    for k in range(13):
        z = -0.130 + k * 0.0085
        m.merge(sh.box(-0.0060, top - 0.0004, z, 0.0060, top + 0.0011, z + 0.0028, 0.0004, "cerberus_body"))
    return m


def inlay_patch_mesh():
    """Red inlay across the gap the source's cross left in the strip (under our crest)."""
    y0, y1 = (STRIP_Y[0] - SRC_BORE_Y) * SCALE, (STRIP_Y[1] - SRC_BORE_Y) * SCALE
    z0 = SQUEEZE_Z + ((CROSS_Z[0] - 0.003) * SCALE + SHIFT_Z - SQUEEZE_Z) * SQUEEZE
    z1 = (CROSS_Z[1] + 0.003) * SCALE + SHIFT_Z
    return sh.box(-0.0117, y0, z0, 0.0117, y1, z1, 0.0003, "cerberus_trim")


def rivets_mesh():
    """Rivet heads along the red inlay strips (where the source had screws)."""
    m = Mesh()
    y = (0.2180 - SRC_BORE_Y) * SCALE
    for z in (-0.150, -0.110, -0.070, 0.005, 0.040):
        for s in (-1, 1):
            m.merge(sh.disc((s * (SLIDE_W - 0.0002), y, z), (s, 0, 0), 0.0016, 8, "steel_dark"))
    return m


def bore_mesh(crown_z):
    m = sh.revolve([(0.0, crown_z + 0.0300), (0.0044, crown_z + 0.0300), (0.0044, crown_z + 0.0006),
                    (0.0049, crown_z)], 12, "steel_dark", outward=False)
    return m


def slide_stop_mesh():
    prof = [(0.0360, -0.0068), (0.0360, -0.0030), (0.0180, -0.0018), (0.0110, -0.0016), (0.0098, -0.0034),
            (0.0108, -0.0062), (0.0200, -0.0066)]
    m = sh.extrude_x(prof, (), 0.0007, chamfer_bevel(0.0003), "steel_dark", x_center=STOP_PIVOT[0])
    m.merge(sh.box(STOP_PIVOT[0] - 0.0016, -0.0058, 0.0100, STOP_PIVOT[0] + 0.0004, -0.0022, 0.0180, 0.0004,
                   "steel_dark"))
    return m


def release_mesh():
    """Magazine release buttons either side behind the trigger guard."""
    m = Mesh()
    for s in (-1, 1):
        x0, x1 = sorted((s * (FRAME_W - 0.0004), s * (FRAME_W + 0.0024)))
        m.merge(sh.box(x0, -0.0190, 0.0290, x1, -0.0130, 0.0360, 0.0008, "steel_dark"))
    return m


def hammer_upright(part):
    """The source draws the hammer cocked; the contract wants it upright at rotation 0 (the rig cocks it)."""
    v, t = part
    a = np.radians(-32.0)
    rx = np.array([[1, 0, 0], [0, np.cos(a), -np.sin(a)], [0, np.sin(a), np.cos(a)]])
    return (v - HAMMER_PIVOT) @ rx.T, t


def measure(parts):
    """Key dimensions of the converted pistol (design frame)."""
    slab = parts["slide_block"][0]
    bar = parts["lower_bar"][0]
    crown = parts["crown"][0]
    return {"slide_front": float(slab[:, 2].min()), "crown_z": float(crown[:, 2].min()),
            "bar_bottom": float(bar[:, 1].min()), "rear": float(max(p[0][:, 2].max() for p in parts.values()))}


# name: (base rgba (linear), metallic, roughness, extra) -- the Cerberus skin's own materials (section 26)
MATERIALS = {
    "cerberus_body": ((0.060, 0.060, 0.066, 1), 0.85, 0.36, {"texture": "brushed"}),
    "cerberus_trim": ((0.55, 0.018, 0.020, 1), 0.20, 0.22, {}),
    "cerberus_grip": ((0.030, 0.030, 0.032, 1), 0.0, 0.85, {"texture": "stipple"}),
    "barrel": ((0.085, 0.085, 0.090, 1), 0.90, 0.32, {}),
    "steel_dark": ((0.035, 0.035, 0.038, 1), 0.70, 0.50, {}),
    "mag": ((0.040, 0.040, 0.043, 1), 0.15, 0.68, {}),
    "mag_base": ((0.30, 0.012, 0.014, 1), 0.20, 0.40, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),
}
MAG_SCALE = np.diag([0.78, 0.45, 0.50, 1.0])      # the USP's magazine, slimmed and shortened for this grip
MAG_D, MAG_FRONT = 0.066, 0.001                   # magazine pivot: this far down the grip axis, and forward


def layout(parts):
    import dual_rig as dr
    m = measure(parts)
    grip_c0 = np.array([0.0, -0.0169, 0.0545])
    grip_angle = 25.4
    a = np.radians(grip_angle)
    axis = np.array([0.0, -np.cos(a), np.sin(a)])
    grip_len = 0.0735
    front = np.array([0.0, -np.sin(a), -np.cos(a)])
    return dr.PistolLayout(
        name="cerberus", p0=grip_c0 + np.array([0.0, -0.018, 0.0055]), grip_angle=grip_angle, grip_c0=grip_c0,
        grip_len=grip_len, mag_origin=grip_c0 + MAG_D * axis + MAG_FRONT * front, hammer_pivot=HAMMER_PIVOT,
        trigger_pivot=TRIGGER_PIVOT, stop_pivot=STOP_PIVOT, crown_z=m["crown_z"], slide_front=m["slide_front"],
        laser_origin=np.array([0.0, m["bar_bottom"], -0.1200]), eject_pos=np.array([SLIDE_W, 0.0310, -0.0180]),
        release=np.array([-(FRAME_W + 0.0024), -0.0160, 0.0325]),
        stop_tab=np.array([STOP_PIVOT[0] - 0.0016, -0.0040, 0.0140]),
        charm_pos=np.array([-(SLIDE_W + 0.0002), -0.0030, -0.0900]), grip_r=0.032, slide_lock=0.032,
        spin_pivot=np.array([0.0, -0.0200, -0.0080]))


def gunset(src=None):
    """The Cerberus pistol's meshes in their nodes' local spaces (right gun), materials and layout (gen_usp_dual)."""
    import usp_parts as up
    parts = load_parts(src)
    lay = layout(parts)
    body = lambda key, material="cerberus_body": _mesh(parts[key], material)
    frame = body("lower_bar").merge(body("frame")).merge(body("grip_frame")).merge(body("grip", "cerberus_grip"))
    frame.merge(body("strip", "cerberus_trim")).merge(inlay_patch_mesh()).merge(rivets_mesh()).merge(release_mesh())
    slide = body("slide_block").merge(body("port", "steel_dark")).merge(ribs_mesh())
    for side in (-1, 1):
        slide.merge(crest_mesh(side))
    meshes = {
        "frame": frame,
        "slide": slide,
        "sight_iron": body("sights", "steel_dark"),
        "barrel": body("crown", "barrel").merge(bore_mesh(lay.crown_z)),
        "hammer": _mesh(hammer_upright(parts["hammer"]), "steel_dark"),
        "trigger": _mesh((parts["trigger"][0] - TRIGGER_PIVOT, parts["trigger"][1]), "steel_dark"),
        "slide_stop": slide_stop_mesh().translated(-STOP_PIVOT),
        "mag_standard": up.mag_mesh(False).transformed(MAG_SCALE),
        "mag_extended": up.mag_mesh(True).transformed(MAG_SCALE),
    }
    return {"meshes": meshes, "shell": up.shell_mesh(1.25), "materials": MATERIALS, "layout": lay}


if __name__ == "__main__":
    parts = load_parts(sys.argv[1] if len(sys.argv) > 1 else None)
    for k, (v, t) in sorted(parts.items()):
        print(f"{k:12s} {len(t):5d} tris  min {np.round(v.min(0), 4)}  max {np.round(v.max(0), 4)}")
    print(measure(parts))
