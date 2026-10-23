"""How much of the screen a first-person weapon covers, per clip, at the runtime projection (VIEWMODEL_SPEC
section 2: 54 deg vertical FOV, near 0.02). Counts rasterized pixels of the weapon + arms (wide skin).

python -I tools/assets/coverage.py [p9|ak47|butterfly|path.glb ...] [--loadout default|largest] [--aspect 16:9|4:3]

Per clip: mean / max share of the screen covered, max share of the centre zone (the crosshair area, a box of
CENTRE x CENTRE of the screen height around the centre) and max share of the lower-left quadrant."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                       # noqa: E402

import loadouts as lo                    # noqa: E402
import raster as rs                      # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland")
GUN_DIR = os.path.join(ASSETS, "models", "gun")
KNIFE_DIR = os.path.join(ASSETS, "models", "knife")
FOV_Y, NEAR = 54.0, 0.02
ASPECTS = {"16:9": 16 / 9, "4:3": 4 / 3}
CENTRE = 0.30                        # side of the centre zone, in screen heights
CLIPS = ("rest", "idle", "walk", "sprint", "tac_sprint", "draw", "fire", "reload", "reload_empty", "inspect",
         "light_1", "light_2", "heavy")


def weapon_path(name):
    if name.endswith(".glb"):
        return name
    return os.path.join(KNIFE_DIR if name == "butterfly" else GUN_DIR, f"{name}.glb")


def masks(h, aspect):
    w = int(round(h * aspect))
    ys, xs = np.mgrid[0:h, 0:w]
    c = CENTRE * h / 2
    centre = (np.abs(xs + 0.5 - w / 2) <= c) & (np.abs(ys + 0.5 - h / 2) <= c)
    lower_left = (xs < w / 2) & (ys >= h / 2)
    return w, centre.reshape(-1), lower_left.reshape(-1)


def frame_coverage(items, w, h, centre, lower_left):
    buf, _ = rs.rasterize(items, rs.Camera(w, h, FOV_Y, near=NEAR), w, h)
    hit = buf["mid"] >= 0
    return hit.mean(), hit[centre].mean(), hit[lower_left].mean()


def clip_report(weapon, arms, clip, loadout, aspect, h=180, rate=12.0):
    anim = None if clip == "rest" else weapon.animation(clip)
    if clip != "rest" and anim is None:
        return None
    dur = 0.0 if anim is None else weapon.duration(anim)
    times = [0.0] if anim is None else list(np.linspace(0.0, dur, max(2, int(dur * rate) + 1)))
    w, centre, lower_left = masks(h, ASPECTS[aspect])
    rows = []
    for t in times:
        items = lo.scene(weapon, arms, anim, t, loadout)
        rows.append(frame_coverage(items, w, h, centre, lower_left))
    rows = np.array(rows)
    return {"mean": rows[:, 0].mean(), "max": rows[:, 0].max(), "centre": rows[:, 1].max(),
            "lower_left": rows[:, 2].max(), "lower_left_mean": rows[:, 2].mean()}


def report(name, loadouts=("default", "largest"), aspects=("16:9",), clips=CLIPS, arms_path=None):
    weapon = lo.load(weapon_path(name))
    arms = lo.load(arms_path or os.path.join(GUN_DIR, "arms.glb"))
    out = {}
    for loadout in loadouts:
        for aspect in aspects:
            for clip in clips:
                r = clip_report(weapon, arms, clip, loadout, aspect)
                if r is not None:
                    out[(loadout, aspect, clip)] = r
    return out


def main(argv):
    loadouts = ("default", "largest")
    aspects = ("16:9",)
    names = []
    i = 0
    while i < len(argv):
        if argv[i] == "--loadout":
            loadouts = (argv[i + 1],)
            i += 2
        elif argv[i] == "--aspect":
            aspects = (argv[i + 1],)
            i += 2
        else:
            names.append(argv[i])
            i += 1
    for name in names or ["p9", "ak47", "butterfly"]:
        print(f"== {os.path.basename(weapon_path(name))}")
        for (loadout, aspect, clip), r in report(name, loadouts, aspects).items():
            print(f"  {loadout:8s} {aspect:5s} {clip:13s} cover mean {r['mean']:6.1%} max {r['max']:6.1%}  "
                  f"centre max {r['centre']:6.1%}  lower-left max {r['lower_left']:6.1%} "
                  f"(mean {r['lower_left_mean']:5.1%})")


if __name__ == "__main__":
    main(sys.argv[1:])
