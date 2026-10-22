"""Software previews of the viewmodel: what the player sees (70 deg vertical FOV, 16:9, eye at the origin).

python -I tools/assets/preview.py [anim ...]   -> tools/assets/out/sheet_<anim>.png, turntable_p9.png"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402
from PIL import Image, ImageDraw                     # noqa: E402

import raster as rs                                  # noqa: E402
from glbread import GLTF, trs                        # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
GUN_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "blockland", "models", "gun")
KNIFE_DIR = os.path.join(os.path.dirname(GUN_DIR), "knife")
FOV_Y, NEAR = 54.0, 0.02                          # VIEWMODEL_SPEC section 2
OUT = os.path.join(HERE, "out")
LOADOUTS = {
    "full": {"hide": {"mag_extended", "fx_shell"}},
    "iron": {"hide": {"mag_standard", "sight_reddot", "laser", "fx_shell"}},
}


def load_materials(g):
    mats = []
    samplers = g.json.get("samplers", [])
    for m in g.json.get("materials", []):
        pbr = m.get("pbrMetallicRoughness", {})
        tex, nearest = None, False
        if "baseColorTexture" in pbr:
            ti = pbr["baseColorTexture"]["index"]
            tex = g.image(ti).copy()
            tex[:, :, :3] = rs.srgb_to_linear(tex[:, :, :3])
            s = g.json["textures"][ti].get("sampler")
            nearest = s is not None and samplers[s].get("magFilter") == 9728
        mats.append({"name": m.get("name"), "base": np.array(pbr.get("baseColorFactor", [1, 1, 1, 1]), float),
                     "tex": tex, "nearest": nearest, "metallic": pbr.get("metallicFactor", 1.0),
                     "roughness": pbr.get("roughnessFactor", 1.0),
                     "emissive": np.array(m.get("emissiveFactor", [0, 0, 0]), float),
                     "alpha_mode": m.get("alphaMode", "OPAQUE"), "cutoff": m.get("alphaCutoff", 0.5),
                     "double": m.get("doubleSided", False)})
    return mats


def _instance(g, mats, mesh_index, M, items, mat_override=None):
    R = M[:3, :3]
    if abs(np.linalg.det(R)) < 1e-12:
        return
    nm = np.linalg.inv(R).T
    for p in g.mesh_primitives(mesh_index):
        nrm = p["normals"] @ nm.T
        nrm /= np.linalg.norm(nrm, axis=1)[:, None] + 1e-30
        mat = mats[p["material"]]
        if mat_override:
            mat = mat_override(mat)
        items.append({"pos": p["positions"] @ R.T + M[:3, 3], "nrm": nrm, "uv": p["uvs"], "idx": p["indices"],
                      "mat": mat})


def hidden_set(g, names):
    hide = set()
    for i, n in enumerate(g.nodes):
        j = i
        while True:
            if g.nodes[j].get("name") in names:
                hide.add(i)
                break
            if j not in g.parent:
                break
            j = g.parent[j]
    return hide


def scene_items(gun, arms, anim, t, loadout="full", arm_variant="wide", gun_mats=None, arm_mats=None,
                extra_matrix=None):
    gun_mats = gun_mats or load_materials(gun)
    arm_mats = arm_mats or (load_materials(arms) if arms is not None else None)
    ov = gun.sample(anim, t) if anim is not None else {}
    W = gun.world_matrices(ov)
    if extra_matrix is not None:
        W = {k: extra_matrix @ v for k, v in W.items()}
    hide = hidden_set(gun, LOADOUTS[loadout]["hide"])
    items = []
    for i, n in enumerate(gun.nodes):
        if "mesh" in n and i not in hide:
            _instance(gun, gun_mats, n["mesh"], W[i], items)
    if arms is not None:
        for side in ("R", "L"):
            for seg, joint in (("upper", f"arm_{side}"), ("fore", f"arm_{side}_fore")):
                M = W[gun.names[joint]]
                for suffix in ("", "_sleeve"):
                    node = arms.nodes[arms.names[f"{side}_{seg}_{arm_variant}{suffix}"]]
                    _instance(arms, arm_mats, node["mesh"], M, items)
    return items, W


def min_eye_distance(items):
    return min(float(np.min(np.linalg.norm(it["pos"], axis=1))) for it in items)


def render_frame(gun, arms, anim, t, w=640, h=360, loadout="full", ss=2, label=None, mats=None):
    items, _ = scene_items(gun, arms, anim, t, loadout, gun_mats=mats[0] if mats else None,
                           arm_mats=mats[1] if mats else None)
    img = rs.render(items, rs.Camera(w, h, FOV_Y, near=NEAR), bg=(0.40, 0.45, 0.52), ss=ss)
    im = Image.fromarray(img)
    d = ImageDraw.Draw(im)
    cx, cy = w // 2, h // 2
    d.line([(cx - 6, cy), (cx + 6, cy)], fill=(255, 255, 255))
    d.line([(cx, cy - 6), (cx, cy + 6)], fill=(255, 255, 255))
    if label:
        d.rectangle([0, 0, 8 * len(label) + 8, 16], fill=(0, 0, 0))
        d.text((4, 2), label, fill=(255, 255, 255))
    return im


def sheet(gun, arms, anim_name, times, cols=4, w=480, h=270, loadout="full", path=None, mats=None):
    anim = gun.animation(anim_name) if anim_name != "rest" else None
    tiles = [render_frame(gun, arms, anim, t, w, h, loadout, label=f"{anim_name} t={t:.2f}", mats=mats)
             for t in times]
    rows = (len(tiles) + cols - 1) // cols
    out = Image.new("RGB", (cols * w, rows * h), (20, 20, 20))
    for k, im in enumerate(tiles):
        out.paste(im, ((k % cols) * w, (k // cols) * h))
    if path:
        out.save(path)
    return out


def turntable(gun, path, loadout="full", views=((-150, 18), (-120, 10), (-60, 12), (-30, 22), (30, 15), (60, 8),
                                                 (120, 15), (160, 25))):
    """3/4 views of the gun alone (rest pose, gun node reset to identity)."""
    mats = load_materials(gun)
    tiles = []
    gi = gun.names["gun"] if "gun" in gun.names else gun.names["knife"]
    t, r, s = gun.local_trs(gi)
    inv_gun = np.linalg.inv(trs(t, r, s))
    for yaw, pitch in views:
        a, b = np.radians(yaw), np.radians(pitch)
        Ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
        Rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
        M = np.eye(4)
        M[:3, :3] = Rx @ Ry
        M[:3, 3] = -M[:3, :3] @ np.array([0, -0.004, -0.055]) + np.array([0, 0, -0.40])
        items, _ = scene_items(gun, None, None, 0.0, loadout, gun_mats=mats, extra_matrix=M @ inv_gun)
        img = rs.render(items, rs.Camera(560, 420, 34.0, near=0.05), bg=(0.55, 0.57, 0.60), ss=2)
        tiles.append(Image.fromarray(img))
    out = Image.new("RGB", (560 * 4, 420 * 2))
    for k, im in enumerate(tiles):
        out.paste(im, ((k % 4) * 560, (k // 4) * 420))
    out.save(path)


def knife_main(argv, name="butterfly"):
    path = os.path.join(KNIFE_DIR, f"{name}.glb")
    if not os.path.exists(path):
        return
    knife = GLTF(path)
    arms = GLTF(os.path.join(GUN_DIR, "arms.glb"))
    mats = (load_materials(knife), load_materials(arms))
    names = [a["name"] for a in knife.json.get("animations", [])]
    want = [a[len(name) + 1:] for a in argv if a.startswith(name + ":")] or (names if not argv or name in argv else [])
    frames = {"light_1": [0.0, 0.05, 0.07, 0.10, 0.13, 0.16, 0.20, 0.24, 0.28, 0.33, 0.38, 0.42],
              "light_2": [0.0, 0.05, 0.07, 0.10, 0.13, 0.16, 0.20, 0.24, 0.28, 0.33, 0.38, 0.42],
              "heavy": [0.0, 0.12, 0.26, 0.32, 0.36, 0.40, 0.46, 0.55, 0.65, 0.75, 0.85, 0.95],
              "inspect": list(np.round(np.linspace(0, 3.6, 16), 3)), "idle": [0.0, 0.5, 1.0, 1.5]}
    for clip in want:
        dur = knife.duration(knife.animation(clip))
        times = frames.get(clip, list(np.round(np.linspace(0, dur, 12), 3)))
        sheet(knife, arms, clip, times, cols=4, path=os.path.join(OUT, f"sheet_{name}_{clip}.png"), mats=mats)
        print("wrote", name, clip)
    if not argv or name in argv or "turntable" in argv:
        sheet(knife, arms, "rest", [0.0], cols=1, w=1280, h=720, path=os.path.join(OUT, f"rest_{name}.png"), mats=mats)
        turntable(knife, os.path.join(OUT, f"turntable_{name}.png"))


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    gun_args = [a for a in argv if ":" not in a and a != "butterfly"]
    if not argv or gun_args:
        gun = GLTF(os.path.join(GUN_DIR, "p9.glb"))
        arms = GLTF(os.path.join(GUN_DIR, "arms.glb"))
        mats = (load_materials(gun), load_materials(arms))
        names = [a["name"] for a in gun.json.get("animations", [])]
        want = [a for a in gun_args if a in names or a == "rest"]
        if not argv:
            want = ["rest"] + names
        for name in want:
            if name == "rest":
                sheet(gun, arms, "rest", [0.0], cols=1, w=1280, h=720, path=os.path.join(OUT, "rest_full.png"),
                      mats=mats)
                sheet(gun, arms, "rest", [0.0], cols=1, w=1280, h=720, loadout="iron",
                      path=os.path.join(OUT, "rest_iron.png"), mats=mats)
                continue
            dur = gun.duration(gun.animation(name))
            n = {"idle": 4, "fire": 8, "fire_last": 8, "draw": 8}.get(name, 12)
            times = np.linspace(0, dur, n)
            if name in ("fire", "fire_last"):
                times = np.array([0.0, 0.012, 0.025, 0.035, 0.05, 0.07, 0.09, dur])
            sheet(gun, arms, name, times, path=os.path.join(OUT, f"sheet_{name}.png"), mats=mats)
            print("wrote", name)
        if "turntable" in argv or not argv:
            turntable(gun, os.path.join(OUT, "turntable_p9.png"))
    knife_main([a for a in argv if a.startswith("butterfly")])


if __name__ == "__main__":
    main(sys.argv[1:])


def look_at(eye, target, up=(0, 1, 0)):
    eye, target, up = (np.asarray(v, float) for v in (eye, target, up))
    f = target - eye
    f /= np.linalg.norm(f)
    r = np.cross(f, up)
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    M = np.eye(4)
    M[:3, :3] = np.stack([r, u, -f])
    M[:3, 3] = -M[:3, :3] @ eye
    return M


def eye_marker():
    """Small cube at the eye (origin) and a -Z arrow, for external debug views."""
    import shapes
    import meshkit
    m = shapes.box(-0.01, -0.01, -0.01, 0.01, 0.01, 0.01, 0.0, "x")
    m.merge(shapes.box(-0.002, -0.002, -0.12, 0.002, 0.002, -0.01, 0.0, "x"))
    p = meshkit.build_primitives(m)[0]
    return {"pos": p["positions"], "nrm": p["normals"], "uv": p["uvs"], "idx": p["indices"],
            "mat": {"base": np.array([1.0, 0.2, 0.1, 1.0]), "tex": None, "roughness": 0.5}}


def debug_views(gun, arms, anim_name, t, path, views=((0.9, 0.25, 0.45), (-0.9, 0.2, 0.3), (0.05, 0.9, 0.2),
                                                       (0.6, -0.5, -0.9))):
    mats = (load_materials(gun), load_materials(arms))
    anim = gun.animation(anim_name) if anim_name != "rest" else None
    items, _ = scene_items(gun, arms, anim, t, gun_mats=mats[0], arm_mats=mats[1])
    items.append(eye_marker())
    tiles = []
    for eye in views:
        M = look_at(eye, (0.03, -0.20, -0.12))
        R = M[:3, :3]
        its = [dict(it, pos=it["pos"] @ R.T + M[:3, 3], nrm=it["nrm"] @ R.T) for it in items]
        img = rs.render(its, rs.Camera(640, 480, 40.0, near=0.05), bg=(0.45, 0.48, 0.52), ss=2)
        tiles.append(Image.fromarray(img))
    out = Image.new("RGB", (1280, 960))
    for k, im in enumerate(tiles):
        out.paste(im, ((k % 2) * 640, (k // 2) * 480))
    out.save(path)


def closeup(gun, arms, anim_name, t, path, w=1280, h=720, crop=(0.45, 0.45, 1.0, 1.0), loadout="full"):
    """Full-resolution first-person frame cropped to a screen region (fractions)."""
    anim = gun.animation(anim_name) if anim_name not in (None, "rest") else None
    im = render_frame(gun, arms, anim, t, w, h, loadout)
    x0, y0, x1, y1 = crop
    im.crop((int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h))).save(path)
