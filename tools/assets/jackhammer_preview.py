"""Offline looks at the Jackhammer (VIEWMODEL_SPEC section 40) into a scratch folder (never the repo): the rest pose
at the runtime projection, turntables, clip contact sheets, ADS views and framing, through preview.py's renderers.

python -I tools/assets/jackhammer_preview.py <out dir> [glb] [rest] [turntable] [ads] [framing] [<clip> ...]
       [frames:<clip>:t1,t2,..]"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                     # noqa: E402

import loadouts as lo                  # noqa: E402
import preview as pv                   # noqa: E402


def main(argv):
    out = os.path.abspath(argv[0])
    os.makedirs(out, exist_ok=True)
    pv.OUT = out
    args = argv[1:]
    path = next((a for a in args if a.endswith(".glb")), os.path.join(pv.GUN_DIR, "jackhammer.glb"))
    args = [a for a in args if not a.endswith(".glb")]
    gun = lo.load(path)
    arms = lo.load(os.path.join(pv.GUN_DIR, "arms.glb"))
    mats = (pv.load_materials(gun), pv.load_materials(arms))
    names = [a["name"] for a in gun.json.get("animations", [])]
    for a in args:
        if a == "rest":
            for build in ("default", "holo", "largest", "rect_full"):
                pv.sheet(gun, arms, "rest", [0.0], cols=1, w=1280, h=720, loadout=build,
                         path=os.path.join(out, f"rest_{build}.png"), mats=mats)
        elif a == "turntable":
            pv.turntable(gun, os.path.join(out, "turntable.png"), loadout="default", dist=1.10,
                         centre=(0.0, 0.03, 0.05))
            pv.turntable(gun, os.path.join(out, "turntable_close.png"), loadout="default", dist=0.55,
                         centre=(0.0, 0.03, 0.12), views=((-100, 10), (-60, 15), (-30, 25), (-80, -30),
                                                         (100, 10), (60, 15), (150, 20), (-140, 30)))
        elif a == "ads":
            print("wrote", pv.ads_views(os.path.splitext(os.path.basename(path))[0]))
        elif a == "framing":
            pv.framing_sheets(path, "jackhammer")
        elif a.startswith("frames:"):
            _, clip, ts = a.split(":")
            times = [float(x) for x in ts.split(",")]
            pv.sheet(gun, arms, clip, times, cols=4, path=os.path.join(out, f"frames_{clip}.png"), mats=mats)
        elif a in names:
            dur = gun.duration(gun.animation(a))
            n = 8 if dur < 0.7 else 12
            times = list(np.round(np.linspace(0, dur, n), 3))
            if a in ("fire", "fire_last"):
                times = [0.0, 0.006, 0.014, 0.024, 0.034, 0.042, 0.06, dur - 0.001]
            pv.sheet(gun, arms, a, times, cols=4, path=os.path.join(out, f"sheet_{a}.png"), mats=mats)
        print("done", a)


if __name__ == "__main__":
    main(sys.argv[1:])
