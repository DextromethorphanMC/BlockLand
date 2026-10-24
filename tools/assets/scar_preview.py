"""Offline looks at the SCAR-H (VIEWMODEL_SPEC section 40) with the repo's software renderer (preview.py), written to an
output folder of your choice (scratch renders never go into the repo).

python -I tools/assets/scar_preview.py <out_dir> rest | turntable | ads | sheet <clip> | frames <clip> t1,t2,...
                                                  | attachments"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402
from PIL import Image                                # noqa: E402

import loadouts as lo                                # noqa: E402
import preview as pv                                 # noqa: E402

GLB = os.environ.get("SCAR_GLB") or os.path.join(pv.GUN_DIR, "scar.glb")   # another build to look at


def main(argv):
    out, cmd, rest = argv[0], argv[1], argv[2:]
    os.makedirs(out, exist_ok=True)
    gun = lo.load(GLB)
    arms = lo.load(os.path.join(pv.GUN_DIR, "arms.glb"))
    mats = (pv.load_materials(gun), pv.load_materials(arms))
    if cmd == "rest":
        for loadout in ("full", "largest"):
            im = pv.render_frame(gun, arms, None, 0.0, 1280, 720, loadout, label=f"scar rest {loadout}", mats=mats)
            im.save(os.path.join(out, f"scar_rest_{loadout}.png"))
    elif cmd == "turntable":
        pv.turntable(gun, os.path.join(out, "scar_turntable.png"), dist=1.35, centre=(0.0, -0.01, 0.02))
        pv.turntable(gun, os.path.join(out, "scar_turntable_largest.png"), loadout="largest", dist=1.45,
                     centre=(0.0, -0.01, 0.02))
    elif cmd == "ads":
        pv.OUT = out
        print(pv.ads_views("scar"))
    elif cmd == "sheet":
        clip = rest[0]
        dur = gun.duration(gun.animation(clip))
        n = int(rest[1]) if len(rest) > 1 else 12
        times = np.linspace(0, dur, n)
        pv.sheet(gun, arms, clip, times, path=os.path.join(out, f"scar_sheet_{clip}.png"), mats=mats)
    elif cmd == "frames":
        clip, times = rest[0], [float(x) for x in rest[1].split(",")]
        tiles = [pv.render_frame(gun, arms, gun.animation(clip) if clip != "rest" else None, t, 960, 540, "full",
                                 label=f"{clip} {t:.2f}", mats=mats) for t in times]
        cols = 3
        im = Image.new("RGB", (960 * cols, 540 * ((len(tiles) + cols - 1) // cols)))
        for k, tile in enumerate(tiles):
            im.paste(tile, ((k % cols) * 960, (k // cols) * 540))
        im.save(os.path.join(out, f"scar_frames_{clip}.png"))
    elif cmd == "attachments":
        pv.turntable(gun, os.path.join(out, "scar_attachments.png"), loadout="largest", dist=1.5,
                     centre=(0.0, -0.01, 0.0))
    print("wrote", cmd, "to", out)


if __name__ == "__main__":
    main(sys.argv[1:])
