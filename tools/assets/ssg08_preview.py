"""SSG 08 look-dev renders (software rasteriser of preview.py), written OUTSIDE the repo (default: the system temp dir
/ ssg08_preview, or --out DIR):

python -I tools/assets/ssg08_preview.py model            the rifle alone: turntable and close-ups (scratch GLB, idle only)
python -I tools/assets/ssg08_preview.py rest             first-person rest frames with the builds, the framing sheets
python -I tools/assets/ssg08_preview.py ads              ADS through every optic
python -I tools/assets/ssg08_preview.py sheet <clip ...> clip contact sheets (times below, or evenly spaced)
python -I tools/assets/ssg08_preview.py frames <clip> t1,t2,...   single frames, full size"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402
from PIL import Image, ImageDraw                     # noqa: E402

import loadouts as lo                                # noqa: E402
import preview as pv                                 # noqa: E402
import raster as rs                                  # noqa: E402
from glbread import trs                              # noqa: E402

GLB_PATH = os.path.join(pv.GUN_DIR, "ssg08.glb")
TIMES = {
    "fire": [0.0, 0.012, 0.03, 0.16, 0.27, 0.33, 0.40, 0.47, 0.55, 0.62, 0.72, 0.85],
    "fire_last": [0.0, 0.006, 0.016, 0.03, 0.06, 0.1, 0.16, 0.28],
    "reload": [0.0, 0.10, 0.32, 0.38, 0.48, 0.66, 0.90, 1.10, 1.40, 1.58, 1.80, 2.40],
    "reload_empty": [0.0, 0.32, 0.48, 0.90, 1.58, 1.95, 2.10, 2.20, 2.32, 2.45, 2.70, 3.10],
    "inspect": [0.0, 0.5, 0.9, 1.3, 1.7, 1.95, 2.15, 2.35, 2.6, 2.9, 3.3, 3.7],
    "draw": [0.0, 0.08, 0.16, 0.24, 0.32, 0.40, 0.48, 0.56, 0.62, 0.68, 0.74, 0.80],
    "idle": [0.0, 0.5, 1.0, 1.5],
}


def out_dir(argv):
    d = os.path.join(tempfile.gettempdir(), "ssg08_preview")
    if "--out" in argv:
        d = argv[argv.index("--out") + 1]
    os.makedirs(d, exist_ok=True)
    return d


def views_of(gun, path, views, dist, centre, loadout="default", w=560, h=420, fov=34.0, labels=None):
    """Views of the rifle alone (rest pose, gun node reset): (yaw, pitch) list, dist / centre in gun space."""
    mats = lo.load_materials(gun)
    gi = gun.names["gun"]
    t, r, s = gun.local_trs(gi)
    inv_gun = np.linalg.inv(trs(t, r, s))
    tiles = []
    for k, (yaw, pitch) in enumerate(views):
        a, b = np.radians(yaw), np.radians(pitch)
        Ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
        Rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
        M = np.eye(4)
        M[:3, :3] = Rx @ Ry
        c = centre[k] if isinstance(centre, list) else centre
        dd = dist[k] if isinstance(dist, list) else dist
        M[:3, 3] = -M[:3, :3] @ np.array(c, float) + np.array([0, 0, -dd])
        items, _ = pv.scene_items(gun, None, None, 0.0, loadout, gun_mats=mats, extra_matrix=M @ inv_gun)
        im = Image.fromarray(rs.render(items, rs.Camera(w, h, fov, near=0.01), bg=(0.55, 0.57, 0.60), ss=2))
        if labels:
            ImageDraw.Draw(im).text((6, 4), labels[k], fill=(0, 0, 0))
        tiles.append(im)
    cols = min(4, len(tiles))
    out = Image.new("RGB", (w * cols, h * ((len(tiles) + cols - 1) // cols)))
    for k, im in enumerate(tiles):
        out.paste(im, ((k % cols) * w, (k // cols) * h))
    out.save(path)
    return path


def model(argv):
    import gen_ssg08
    import ssg08_clips
    d = out_dir(argv)
    path = os.path.join(d, "ssg08_scratch.glb")
    tris, _ = gen_ssg08.build(path, [ssg08_clips.IDLE])
    drawn = sum(v for k, v in tris.items() if k != "fx_shell")
    print(f"triangles {drawn} (+{tris['fx_shell']} shell): {tris}")
    gun = lo.load(path)
    import ssg08_rig as rig
    P0 = rig.P0
    g = lambda p: tuple(np.asarray(p, float) - P0)
    out = [views_of(gun, os.path.join(d, "turntable.png"),
                    ((-150, 18), (-120, 10), (-60, 12), (-30, 22), (30, 15), (60, 8), (120, 15), (160, 25)),
                    1.45, g((0.0, -0.03, 0.03))),
           views_of(gun, os.path.join(d, "side_right.png"), ((-90, 0), (90, 0)), 1.25, g((0.0, -0.03, 0.03)),
                    w=1400, h=520, fov=30.0),
           views_of(gun, os.path.join(d, "close_action.png"), ((-90, 0), (-60, 20), (-120, 15), (90, 0), (60, 25),
                                                                (180, 10), (-90, 65), (0, 15)),
                    [0.42, 0.42, 0.42, 0.42, 0.42, 0.40, 0.42, 0.40],
                    [g((0, -0.03, 0.13))] * 5 + [g((0, -0.02, 0.30)), g((0, -0.02, 0.13)), g((0, -0.01, -0.40))]),
           views_of(gun, os.path.join(d, "close_front.png"), ((-90, 0), (-50, 15), (-20, 8), (90, 0), (40, 20),
                                                               (-90, -40), (-150, 10), (0, 0)),
                    0.40, [g((0, -0.01, -0.25))] * 5 + [g((0, -0.02, -0.30)), g((0, 0.0, -0.45)), g((0, 0, -0.5))]),
           views_of(gun, os.path.join(d, "close_stock.png"), ((-90, 0), (-130, 15), (90, 0), (150, 20), (180, 5),
                                                               (-90, -50), (90, 50), (-60, 10)),
                    0.45, g((0, -0.05, 0.42)))]
    for p in out:
        print("wrote", p)


def rest(argv):
    d = out_dir(argv)
    gun = lo.load(GLB_PATH)
    arms = lo.load(os.path.join(pv.GUN_DIR, "arms.glb"))
    mats = (lo.load_materials(gun), lo.load_materials(arms))
    for build in ("sniper", "scope2x", "sniper_full", "default", "reddot", "bipod_full"):
        p = os.path.join(d, f"rest_{build}.png")
        pv.sheet(gun, arms, "rest", [0.0], cols=1, w=1280, h=720, loadout=build, path=p, mats=mats)
        print("wrote", p)
    saved = pv.OUT
    pv.OUT = d
    try:
        pv.framing_sheets(GLB_PATH, "ssg08")
    finally:
        pv.OUT = saved
    print("wrote framing sheets to", d)


def ads(argv):
    d = out_dir(argv)
    saved = pv.OUT
    pv.OUT = d
    try:
        for p in pv.ads_views("ssg08"):
            print("wrote", p)
    finally:
        pv.OUT = saved


def sheet(argv):
    d = out_dir(argv)
    gun = lo.load(GLB_PATH)
    arms = lo.load(os.path.join(pv.GUN_DIR, "arms.glb"))
    mats = (lo.load_materials(gun), lo.load_materials(arms))
    clips = [a for a in argv if a in [x["name"] for x in gun.json["animations"]]]
    loadout = "sniper"
    for clip in clips:
        dur = gun.duration(gun.animation(clip))
        times = [t for t in TIMES.get(clip, list(np.round(np.linspace(0, dur, 12), 3))) if t <= dur + 1e-6]
        p = os.path.join(d, f"sheet_{clip}.png")
        pv.sheet(gun, arms, clip, times, cols=4, path=p, mats=mats, loadout=loadout)
        print("wrote", p)


def frames(argv):
    d = out_dir(argv)
    clip, times = argv[1], [float(x) for x in argv[2].split(",")]
    gun = lo.load(GLB_PATH)
    arms = lo.load(os.path.join(pv.GUN_DIR, "arms.glb"))
    mats = (lo.load_materials(gun), lo.load_materials(arms))
    anim = gun.animation(clip) if clip != "rest" else None
    for t in times:
        im = pv.render_frame(gun, arms, anim, t, 1280, 720, "sniper", label=f"ssg08 {clip} {t:.2f}", mats=mats)
        p = os.path.join(d, f"frame_{clip}_{t:.2f}.png")
        im.save(p)
        print("wrote", p)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:]]
    cmd = args[0] if args else "model"
    {"model": model, "rest": rest, "ads": ads, "sheet": sheet, "frames": frames}[cmd](args)
