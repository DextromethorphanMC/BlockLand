"""Software previews of the viewmodels: what the player sees (54 deg vertical FOV, 16:9, eye at the origin).

python -I tools/assets/preview.py [anim ...]        -> tools/assets/out/sheet_<anim>.png, turntable_p9.png (P9)
python -I tools/assets/preview.py ak47 [clip ...]   -> sheet_ak47_<clip>.png, rest_ak47_*.png, framing_ak47_*.png,
                                                       ads_ak47_<optic>.png ("ads": only those)
python -I tools/assets/preview.py ads [p9|ak47|awp] -> ads_<gun>_iron / _<optic>.png (every optic: red dots, holo, scopes)
python -I tools/assets/preview.py attachments       -> attachments_<gun>.png (turntables with every attachment fitted),
                                                       attachments_models.png (each model alone)
python -I tools/assets/preview.py butterfly         -> sheet_butterfly_<clip>.png
python -I tools/assets/preview.py frames <p9|ak47|butterfly|path.glb> <clip> t1,t2,...   -> frames_<w>_<clip>.png
(no arguments: everything)"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402
from PIL import Image, ImageDraw                     # noqa: E402

import loadouts as lo                                # noqa: E402
import raster as rs                                  # noqa: E402
from glbread import trs                              # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
GUN_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "blockland", "models", "gun")
KNIFE_DIR = os.path.join(os.path.dirname(GUN_DIR), "knife")
FOV_Y, NEAR = 54.0, 0.02                          # VIEWMODEL_SPEC section 2
OUT = os.path.join(HERE, "out")
# preview loadouts (section 19 slots, see loadouts.py); "full" = the default build, "iron" = nothing fitted
LOADOUTS = dict(lo.LOADOUTS, full=lo.LOADOUTS["default"], iron=lo.LOADOUTS["default"],
                iron_standard=lo.LOADOUTS["default"])
load_materials = lo.load_materials


def hidden_set(g, names):
    """Node indices at or under the named nodes (kept for the icon generators)."""
    return lo.subtree(g, names)


def scene_items(gun, arms, anim, t, loadout="full", arm_variant="wide", gun_mats=None, arm_mats=None,
                extra_matrix=None):
    """Raster items of the first-person scene: weapon, the loadout's attachments at their mounts, the arms."""
    W = gun.world_matrices(gun.sample(anim, t) if anim is not None else {})
    items = lo.scene(gun, arms, anim, t, LOADOUTS[loadout] if isinstance(loadout, str) else loadout, arm_variant,
                     extra_matrix=extra_matrix)
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
                                                 (120, 15), (160, 25)), dist=0.40, centre=(0, -0.004, -0.055)):
    """3/4 views of the gun alone (rest pose, gun node reset to identity); dist / centre in gun space."""
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
        M[:3, 3] = -M[:3, :3] @ np.array(centre, float) + np.array([0, 0, -dist])
        items, _ = scene_items(gun, None, None, 0.0, loadout, gun_mats=mats, extra_matrix=M @ inv_gun)
        img = rs.render(items, rs.Camera(560, 420, 34.0, near=0.05), bg=(0.55, 0.57, 0.60), ss=2)
        tiles.append(Image.fromarray(img))
    out = Image.new("RGB", (560 * 4, 420 * 2))
    for k, im in enumerate(tiles):
        out.paste(im, ((k % 4) * 560, (k // 4) * 420))
    out.save(path)


def framing_sheets(path=None, name="p9"):
    """54 deg framing check frames (largest loadout) at 16:9 and 4:3, with 3 % margin guides."""
    gun = lo.load(path or os.path.join(GUN_DIR, f"{name}.glb"))
    arms = lo.load(os.path.join(GUN_DIR, "arms.glb"))
    mats = (load_materials(gun), load_materials(arms))
    shots = [("idle", 0.0), ("fire", 0.025), ("fire", 0.09), ("draw", 0.60), ("sprint", 0.0), ("sprint", 0.33)]
    for tag, (w, h) in (("16x9", (640, 360)), ("4x3", (480, 360))):
        tiles = []
        for clip, t in shots:
            im = render_frame(gun, arms, gun.animation(clip), t, w, h, loadout="largest", label=f"{clip} t={t:.2f}",
                              mats=mats)
            d = ImageDraw.Draw(im)
            m = 0.03 * h
            d.line([(0, h - m), (w, h - m)], fill=(255, 210, 0))
            d.line([(w - m, 0), (w - m, h)], fill=(255, 210, 0))
            tiles.append(im)
        out = Image.new("RGB", (w * 3, h * 2))
        for k, im in enumerate(tiles):
            out.paste(im, ((k % 3) * w, (k // 3) * h))
        out.save(os.path.join(OUT, f"framing_{name}_54deg_{tag}.png"))


def knife_main(argv, name="butterfly"):
    path = os.path.join(KNIFE_DIR, f"{name}.glb")
    if not os.path.exists(path):
        return
    knife = lo.load(path)
    arms = lo.load(os.path.join(GUN_DIR, "arms.glb"))
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


def rifle_main(argv, name="ak47"):
    """python -I preview.py ak47 [clip ...] -> out/sheet_ak47_<clip>.png, rest_ak47_*.png, turntable_ak47.png,
    framing_ak47_54deg_*.png"""
    path = os.path.join(GUN_DIR, f"{name}.glb")
    if not os.path.exists(path):
        return
    gun = lo.load(path)
    arms = lo.load(os.path.join(GUN_DIR, "arms.glb"))
    mats = (load_materials(gun), load_materials(arms))
    names = [a["name"] for a in gun.json.get("animations", [])]
    want = [a for a in argv if a in names] if argv else names       # "rest" / "ads" alone: no clip sheets
    frames_at = {"fire": [0.0, 0.006, 0.014, 0.026, 0.035, 0.05, 0.074, 0.1],
                 "fire_last": [0.0, 0.006, 0.014, 0.026, 0.035, 0.05, 0.074, 0.1],
                 "reload": [0.0, 0.20, 0.30, 0.40, 0.50, 0.62, 0.82, 1.10, 1.40, 1.58, 1.75, 2.10],
                 "reload_empty": [0.0, 0.34, 0.50, 0.90, 1.40, 1.60, 1.85, 2.00, 2.12, 2.20, 2.45, 2.90],
                 "inspect": [0.0, 0.5, 1.0, 1.5, 1.8, 2.1, 2.25, 2.36, 2.6, 2.8, 3.2, 3.6], "idle": [0.0, 0.5, 1.0, 1.5]}
    if name == "awp":                     # section 23: the shot, then the bolt cycle (up, back, forward, down)
        frames_at = {"fire": [0.0, 0.016, 0.07, 0.24, 0.38, 0.47, 0.56, 0.65, 0.73, 0.81, 0.89, 1.05],
                     "fire_last": [0.0, 0.006, 0.016, 0.03, 0.06, 0.1, 0.16, 0.3],
                     "reload": [0.0, 0.32, 0.42, 0.56, 0.74, 0.98, 1.18, 1.40, 1.70, 1.86, 2.20, 2.80],
                     "reload_empty": [0.0, 0.42, 0.74, 1.30, 1.86, 2.30, 2.46, 2.60, 2.70, 2.86, 2.95, 3.40],
                     "inspect": [0.0, 0.5, 1.0, 1.5, 1.9, 2.06, 2.24, 2.4, 2.6, 2.7, 3.2, 3.6], "idle": [0.0, 0.5, 1.0, 1.5]}
    for clip in want:
        dur = gun.duration(gun.animation(clip))
        times = frames_at.get(clip, list(np.round(np.linspace(0, dur, 12 if dur > 0.7 else 8), 3)))
        sheet(gun, arms, clip, times, cols=4, path=os.path.join(OUT, f"sheet_{name}_{clip}.png"), mats=mats)
        print("wrote", name, clip)
    if not argv or "ads" in argv:
        print("wrote", ads_views(name))
    if not argv or "rest" in argv:
        builds = ("sniper", "scope2x", "sniper_full") if name == "awp" else ("default", "mini", "holo", "largest")
        for build in builds:
            sheet(gun, arms, "rest", [0.0], cols=1, w=1280, h=720, loadout=build,
                  path=os.path.join(OUT, f"rest_{name}_{build}.png"), mats=mats)
        if name == "awp":
            turntable(gun, os.path.join(OUT, f"turntable_{name}.png"), loadout="sniper", dist=1.75,
                      centre=(0.0, -0.02, -0.22))
        else:
            turntable(gun, os.path.join(OUT, f"turntable_{name}.png"), dist=1.35, centre=(0.0, -0.01, -0.13))
        framing_sheets(path, name)


def ads_views(name="ak47", w=1920, h=1080):
    """ADS frames at the runtime projection (sections 5 and 19): the viewmodel is moved so the eye point sits on the
    camera (54 deg vertical FOV, near 0.02, rest pose) -- aim_iron, then mount_optic x `aim` of every optic.
    Writes out/ads_<name>_<iron|optic>.png with a 4x inset of the screen centre (red ticks = exact centre)."""
    gun = lo.load(os.path.join(GUN_DIR, f"{name}.glb"))
    arms = lo.load(os.path.join(GUN_DIR, "arms.glb"))
    out = []
    for optic in (None,) + lo.OPTICS:
        if optic is not None and not lo.is_v2(gun) or optic is None and "aim_iron" not in gun.names:
            continue
        inv = np.linalg.inv(lo.aim_matrix(gun, optic))
        build = {"slots": {} if optic is None else {"optic": optic}, "mag": "mag_standard"}
        items = lo.scene(gun, arms, None, 0.0, build, extra_matrix=inv)
        im = Image.fromarray(rs.render(items, rs.Camera(w, h, FOV_Y, near=NEAR), bg=(0.40, 0.45, 0.52), ss=2))
        cw, ch = w // 12, h // 12
        box = (w // 2 - cw // 2, h // 2 - ch // 2, w // 2 + cw // 2, h // 2 + ch // 2)
        inset = im.crop(box).resize((cw * 4, ch * 4), Image.NEAREST)
        d = ImageDraw.Draw(inset)
        cx, cy = cw * 2, ch * 2
        for a, b in (((cx - 12, cy), (cx - 5, cy)), ((cx + 5, cy), (cx + 12, cy)),
                     ((cx, cy - 12), (cx, cy - 5)), ((cx, cy + 5), (cx, cy + 12))):
            d.line([a, b], fill=(255, 40, 40))
        x0 = w - cw * 4 - 12
        im.paste(inset, (x0, 12))
        d = ImageDraw.Draw(im)
        d.rectangle([x0 - 1, 11, x0 + cw * 4, 12 + ch * 4], outline=(255, 255, 255))
        d.rectangle(box, outline=(255, 255, 255))
        what = "iron sights" if optic is None else optic
        label = f"{name} ADS {what}  {w}x{h}  {FOV_Y:.0f} deg vertical FOV  near {NEAR}  (inset: centre x4)"
        d.rectangle([0, 0, 7 * len(label) + 8, 16], fill=(0, 0, 0))
        d.text((4, 2), label, fill=(255, 255, 255))
        path = os.path.join(OUT, f"ads_{name}_{'iron' if optic is None else optic}.png")
        im.save(path)
        out.append(path)
    return out


def attachment_views():
    """Turntables of each gun with every attachment fitted (largest build), the knife with its charm, and every
    attachment model alone: out/attachments_<weapon>.png, out/attachments_models.png."""
    out = []
    for name, path, dist, centre in (("p9", os.path.join(GUN_DIR, "p9.glb"), 0.62, (0.0, -0.02, -0.04)),
                                     ("ak47", os.path.join(GUN_DIR, "ak47.glb"), 1.55, (0.0, -0.01, -0.10)),
                                     ("awp", os.path.join(GUN_DIR, "awp.glb"), 2.05, (0.0, -0.02, -0.20)),
                                     ("butterfly", os.path.join(KNIFE_DIR, "butterfly.glb"), 0.55,
                                      (0.0, 0.0, 0.03))):
        if os.path.exists(path):
            target = os.path.join(OUT, f"attachments_{name}.png")
            turntable(lo.load(path), target, loadout="largest", dist=dist, centre=centre)
            out.append(target)
    tiles = []
    for model in lo.MODELS:
        a = lo.model(model)
        mats = lo.load_materials(a)
        W = a.world_matrices({})
        pts = np.concatenate([p["positions"] @ W[i][:3, :3].T + W[i][:3, 3] for i, n in enumerate(a.nodes)
                              if "mesh" in n for p in a.mesh_primitives(n["mesh"])])
        c = 0.5 * (pts.min(0) + pts.max(0))
        size = float(np.max(pts.max(0) - pts.min(0)))
        for yaw, pitch in ((-130, 18), (-40, 20)):
            ya, pa = np.radians(yaw), np.radians(pitch)
            Ry = np.array([[np.cos(ya), 0, np.sin(ya)], [0, 1, 0], [-np.sin(ya), 0, np.cos(ya)]])
            Rx = np.array([[1, 0, 0], [0, np.cos(pa), -np.sin(pa)], [0, np.sin(pa), np.cos(pa)]])
            M = np.eye(4)
            M[:3, :3] = Rx @ Ry
            M[:3, 3] = -M[:3, :3] @ c + np.array([0, 0, -2.2 * size])
            items = []
            for i, n in enumerate(a.nodes):
                if "mesh" in n:
                    lo.instance(a, mats, n["mesh"], M @ W[i], items)
            im = Image.fromarray(rs.render(items, rs.Camera(420, 315, 34.0, near=0.005), bg=(0.55, 0.57, 0.60), ss=2))
            ImageDraw.Draw(im).text((6, 4), model, fill=(0, 0, 0))
            tiles.append(im)
    sheet_im = Image.new("RGB", (420 * 4, 315 * ((len(tiles) + 3) // 4)))
    for k, im in enumerate(tiles):
        sheet_im.paste(im, ((k % 4) * 420, (k // 4) * 315))
    target = os.path.join(OUT, "attachments_models.png")
    sheet_im.save(target)
    out.append(target)
    return out


def frames(weapon, clip, times, crop=(280, 140, 1280, 720), loadout="full"):
    """python -I preview.py frames <p9|ak47|butterfly> <clip> t1,t2,... -> out/frames_<weapon>_<clip>.png"""
    path = weapon if weapon.endswith(".glb") else os.path.join(KNIFE_DIR if weapon == "butterfly" else GUN_DIR,
                                                                f"{weapon}.glb")
    weapon = os.path.basename(path)[:-4]
    gun = lo.load(path)
    arms = lo.load(os.path.join(GUN_DIR, "arms.glb"))
    mats = (load_materials(gun), load_materials(arms))
    anim = gun.animation(clip) if clip != "rest" else None
    tiles = []
    for t in times:
        im = render_frame(gun, arms, anim, t, 1280, 720, loadout, label=f"{weapon} {clip} {t:.2f}", mats=mats)
        tiles.append(im.crop(crop).resize((460, int(460 * (crop[3] - crop[1]) / (crop[2] - crop[0])))))
    h = tiles[0].size[1]
    out = Image.new("RGB", (1380, h * ((len(tiles) + 2) // 3)))
    for k, im in enumerate(tiles):
        out.paste(im, ((k % 3) * 460, (k // 3) * h))
    out.save(os.path.join(OUT, f"frames_{weapon}_{clip}.png"))


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    if argv[:1] == ["ads"]:
        for name in argv[1:] or ["p9", "ak47"]:
            print("wrote", ads_views(name))
        return
    if argv[:1] == ["attachments"]:
        print("wrote", attachment_views())
        return
    if argv[:1] == ["frames"]:
        frames(argv[1], argv[2], [float(x) for x in argv[3].split(",")])
        return
    if argv[:1] in (["ak47"], ["awp"], ["ks3"]):
        rifle_main(argv[1:], argv[0])
        return
    gun_args = [a for a in argv if ":" not in a and a not in ("butterfly", "framing")]
    if not argv or gun_args:
        gun = lo.load(os.path.join(GUN_DIR, "p9.glb"))
        arms = lo.load(os.path.join(GUN_DIR, "arms.glb"))
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
    if "framing" in argv or not argv:
        framing_sheets()
    knife_args = [a for a in argv if a.startswith("butterfly")]
    if knife_args or not argv:
        knife_main(knife_args)
    if not argv:
        rifle_main([])


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
