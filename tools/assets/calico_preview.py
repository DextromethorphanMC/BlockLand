"""Offline look at the Calico (renders into a directory of your choice, never into the repo):

python -I tools/assets/calico_preview.py <out_dir> [glb] [what ...]
  what: side (left / right / top orthographic views next to the reference photo), rest (hip frames: iron, mini,
  holo, largest), turn (turntable), ads (every sight), clips (contact sheets of every clip), frames:<clip>:t1,t2..
  (default: side rest turn ads)"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402
from PIL import Image, ImageDraw                     # noqa: E402

import loadouts as lo                                # noqa: E402
import preview as pv                                 # noqa: E402
import raster as rs                                  # noqa: E402
from glbread import trs                              # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "source", "references", "calico_m950.jpg")
PX = 0.000346                                        # reference photo: metres per pixel
REF_ORIGIN = (600.0, 203.0)                          # photo pixel of the design-frame origin (bore, magazine front)


def _gun_items(g, view, loadout="iron", mag="mag_standard"):
    """Items of the gun alone (gun node at identity), transformed by `view` (4x4)."""
    gi = g.names["gun"]
    t, r, s = g.local_trs(gi)
    inv = np.linalg.inv(trs(t, r, s))
    build = dict(lo.LOADOUTS["default"] if loadout == "iron" else lo.LOADOUTS[loadout])
    build = {"slots": dict(build.get("slots", {})), "mag": mag}
    return lo.scene(g, None, None, 0.0, build, extra_matrix=view @ inv, with_arms=False)


def side_views(g, out, mag="mag_extended"):
    """Left side (as the photo), right side and top, orthographic at the photo's scale; the photo above."""
    import calico_rig as rig
    w, h = 1500, 520
    half_h = h * PX / 2
    tiles = []
    centre = np.array([0.0, (REF_ORIGIN[1] - h / 2) * PX, (w / 2 - REF_ORIGIN[0]) * PX])   # photo crop centre
    for name, R in (("left", np.array([[0, 0, 1.0], [0, 1, 0], [-1, 0, 0]])),
                    ("right", np.array([[0, 0, -1.0], [0, 1, 0], [1, 0, 0]])),
                    ("top", np.array([[0, 0, 1.0], [1, 0, 0], [0, 1, 0]]))):
        M = np.eye(4)
        M[:3, :3] = R
        M[:3, 3] = -R @ (centre - rig.P0) + np.array([0, 0, -1.0])
        items = _gun_items(g, M, "iron", mag)
        img = rs.render(items, rs.Camera(w, h, 30.0, near=0.01, ortho_half_h=half_h), bg=(0.92, 0.92, 0.92), ss=2)
        tiles.append(Image.fromarray(img))
    ref = Image.open(REF).convert("RGB").crop((0, 0, 1500, 520))
    sheet = Image.new("RGB", (w, h * 4), (255, 255, 255))
    sheet.paste(ref, (0, 0))
    for k, im in enumerate(tiles):
        sheet.paste(im, (0, h * (k + 1)))
    path = os.path.join(out, f"side_calico_{mag}.png")
    sheet.save(path)
    blend = Image.blend(ref, tiles[0], 0.5)
    blend.save(os.path.join(out, f"overlay_calico_{mag}.png"))
    return path


def main(argv):
    out = argv[0]
    os.makedirs(out, exist_ok=True)
    path = argv[1] if len(argv) > 1 and argv[1].endswith(".glb") else os.path.join(pv.GUN_DIR, "calico.glb")
    what = [a for a in argv[1:] if not a.endswith(".glb")] or ["side", "rest", "turn", "ads"]
    pv.OUT = out
    g = lo.load(path)
    arms = lo.load(os.path.join(pv.GUN_DIR, "arms.glb"))
    mats = (lo.load_materials(g), lo.load_materials(arms))
    if "side" in what:
        print("wrote", side_views(g, out, "mag_standard"), side_views(g, out, "mag_extended"))
    if "rest" in what:
        for build in ("iron", "mini", "holo", "largest", "sniper_full", "angled_full"):
            pv.sheet(g, arms, "rest", [0.0], cols=1, w=1280, h=720, loadout=build,
                     path=os.path.join(out, f"rest_calico_{build}.png"), mats=mats)
        print("wrote rest frames")
    if "turn" in what:
        pv.turntable(g, os.path.join(out, "turntable_calico.png"), loadout="iron", dist=0.95,
                     centre=(0.0, 0.03, -0.02))
        pv.turntable(g, os.path.join(out, "turntable_calico_largest.png"), loadout="largest", dist=1.15,
                     centre=(0.0, 0.03, -0.02))
        print("wrote turntables")
    if "ads" in what:
        old = pv.GUN_DIR
        pv.GUN_DIR = os.path.dirname(path)
        if not os.path.exists(os.path.join(pv.GUN_DIR, "arms.glb")):
            import shutil
            shutil.copy(os.path.join(old, "arms.glb"), pv.GUN_DIR)
        try:
            print("wrote", pv.ads_views("calico"))
        finally:
            pv.GUN_DIR = old
    if "clips" in what:
        for a in g.json.get("animations", []):
            dur = g.duration(a)
            n = 8 if dur < 0.3 else 12
            times = np.round(np.linspace(0, dur, n), 3)
            pv.sheet(g, arms, a["name"], times, cols=4, path=os.path.join(out, f"sheet_calico_{a['name']}.png"),
                     mats=mats)
            print("wrote", a["name"])
    for spec in what:
        if spec.startswith("frames:"):
            _, clip, ts = spec.split(":")
            times = [float(x) for x in ts.split(",")]
            pv.sheet(g, arms, clip, times, cols=4, path=os.path.join(out, f"frames_calico_{clip}.png"), mats=mats)
            print("wrote frames", clip)


if __name__ == "__main__":
    main(sys.argv[1:])
