"""Offline looks at mp5.glb through preview.py's renderer, written to a directory of your choice (scratch renders stay
out of the repo):

python -I tools/assets/mp5_preview.py <out_dir> [rest] [turntable] [ads] [framing] [<clip> ...] [frames:<clip>:t1,t2,..]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402

import loadouts as lo                                # noqa: E402
import preview as pv                                 # noqa: E402

FRAMES = {
    "fire": [0.0, 0.004, 0.010, 0.020, 0.030, 0.045, 0.055, 0.075],
    "reload": [0.0, 0.18, 0.30, 0.42, 0.50, 0.62, 0.90, 1.18, 1.30, 1.40, 1.60, 2.20],
    "reload_empty": [0.0, 0.24, 0.30, 0.44, 0.52, 0.72, 0.90, 1.40, 1.82, 2.00, 2.08, 2.30],
    "inspect": [0.0, 0.54, 1.10, 1.50, 1.88, 2.18, 2.36, 2.60, 2.96, 3.30, 3.60, 3.90],
    "draw": [0.0, 0.08, 0.16, 0.24, 0.30, 0.38, 0.48, 0.62],
    "idle": [0.0, 0.5, 1.0, 1.5],
}


def big(gun, path, yaw, pitch, build, zoom, centre):
    from PIL import Image
    from glbread import trs
    mats = pv.load_materials(gun)
    gi = gun.names["gun"]
    inv_gun = np.linalg.inv(trs(*gun.local_trs(gi)))
    a, b = np.radians(yaw), np.radians(pitch)
    Ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
    Rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
    M = np.eye(4)
    M[:3, :3] = Rx @ Ry
    M[:3, 3] = -M[:3, :3] @ np.array(centre, float) + np.array([0, 0, -1.5 / zoom])
    items, _ = pv.scene_items(gun, None, None, 0.0, build, gun_mats=mats, extra_matrix=M @ inv_gun)
    img = pv.rs.render(items, pv.rs.Camera(1800, 900, 17.0, near=0.05), bg=(0.62, 0.64, 0.67), ss=2)
    Image.fromarray(img).save(path)


def main(argv):
    out = argv[0]
    os.makedirs(out, exist_ok=True)
    pv.OUT = out
    path = os.environ.get("MP5_GLB") or os.path.join(pv.GUN_DIR, "mp5.glb")
    gun = lo.load(path)
    arms = lo.load(os.path.join(pv.GUN_DIR, "arms.glb"))
    mats = (pv.load_materials(gun), pv.load_materials(arms))
    names = [a["name"] for a in gun.json.get("animations", [])]
    want = argv[1:] or ["rest", "turntable", "ads", "framing"] + names
    for item in want:
        if item == "rest":
            for build in ("default", "mini", "holo", "largest"):
                pv.sheet(gun, arms, "rest", [0.0], cols=1, w=1280, h=720, loadout=build,
                         path=os.path.join(out, f"rest_mp5_{build}.png"), mats=mats)
        elif item == "turntable":
            pv.turntable(gun, os.path.join(out, "turntable_mp5.png"), loadout="default", dist=0.95,
                         centre=(0.0, 0.0, -0.05))
            pv.turntable(gun, os.path.join(out, "turntable_mp5_largest.png"), loadout="largest", dist=1.15,
                         centre=(0.0, 0.0, -0.07))
        elif item == "side":
            pv.turntable(gun, os.path.join(out, "side_mp5.png"), loadout="default", dist=1.25,
                         centre=(0.0, -0.02, -0.06), views=((-90, 0), (90, 0), (-90, 0), (90, 0), (0, 89), (180, 0),
                                                           (0, 0), (-135, 30)))
        elif item.startswith("big"):
            # big:<yaw>:<pitch>[:<loadout>[:<zoom>[:cx,cy,cz]]] -> a 1800x900 view of the gun alone (rest pose)
            parts = item.split(":")
            yaw, pitch = float(parts[1]), float(parts[2])
            build = parts[3] if len(parts) > 3 else "default"
            zoom = float(parts[4]) if len(parts) > 4 else 1.0
            centre = [float(v) for v in parts[5].split(",")] if len(parts) > 5 else (0.0, -0.02, -0.06)
            big(gun, os.path.join(out, f"big_{int(yaw)}_{int(pitch)}_{build}_{zoom:g}.png"), yaw, pitch, build, zoom,
                centre)
        elif item == "ads":
            print("wrote", pv.ads_views("mp5"))
        elif item == "framing":
            pv.framing_sheets(path, "mp5")
        elif item.startswith("frames:"):
            _, clip, ts = item.split(":")
            pv.frames(path, clip, [float(x) for x in ts.split(",")])
        elif item in names:
            dur = gun.duration(gun.animation(item))
            times = FRAMES.get(item, list(np.round(np.linspace(0, dur, 12 if dur > 0.7 else 8), 3)))
            pv.sheet(gun, arms, item, times, cols=4, path=os.path.join(out, f"sheet_mp5_{item}.png"), mats=mats)
        print("wrote", item)


if __name__ == "__main__":
    main(sys.argv[1:])
