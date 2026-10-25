"""Section 43 checks of models/gun/handcannon.glb, used by check_glb.py (CHECK_MODULES): the 40 mm hand cannon follows
the P9 contract (nodes, materials, clips and events, mounts and every optic's ADS, framing; check_glb.check_gun), plus
the pistol budget, its data (an optic-only slot, one single-round magazine of a launched round, the spent case thrown at
`mag_out`), the break-open (the barrel at its stop while open in both reloads, shut at their end and at rest, the round
hidden from mag_out until the new one comes up) and the iron sight line (front blade level with the rear notch)."""
import json
import os

import numpy as np

from glbread import GLTF
from model_quality import BUDGETS

BUDGET = BUDGETS["pistol"]
GUNS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources",
                         "data", "blockland", "guns", "guns.json")


def claims(path, names):
    return os.path.basename(path) == "handcannon.glb"


def _angle_x(q):
    """Rotation about X (degrees) of a quaternion that turns about X only."""
    return float(np.degrees(2 * np.arctan2(q[0], q[3])))


def check(path, arms, r):
    import check_glb
    import handcannon_model as hm
    check_glb.check_gun(path, arms, r)
    g = GLTF(path)
    label = "handcannon"
    tris = {n["name"]: sum(len(p["indices"]) for p in g.mesh_primitives(n["mesh"])) for n in g.nodes if "mesh" in n}
    drawn = sum(v for k, v in tris.items() if k not in ("fx_shell", "mag_extended"))
    r.check(drawn <= BUDGET, f"{label}: {drawn} triangles drawn (budget {BUDGET})")
    with open(GUNS_JSON, encoding="utf-8") as f:
        data = json.load(f)
    gun = data["guns"].get("handcannon")
    if r.check(gun is not None, f"{label}: no guns.json entry"):
        r.check(set(gun["slots"]) == {"optic"}, f"{label}: slots {sorted(gun['slots'])}, expected only the optic")
        mags = gun["magazines"]
        r.check(len(mags) == 1 and data["magazines"][mags[0]]["capacity"] == 1,
                f"{label}: magazines {mags}: one, holding one round")
        ammo = data["ammo"].get(gun["ammo"], {})
        r.check(isinstance(ammo.get("projectile"), dict) and "blast" in ammo["projectile"],
                f"{label}: ammo {gun['ammo']} is not a launched round with a blast")
        r.check(gun.get("ejectEvent") == "mag_out", f"{label}: ejectEvent {gun.get('ejectEvent')}, the case leaves at mag_out")
    anims = {a["name"]: a for a in g.json["animations"]}
    barrel, mag = g.names["barrel"], g.names["mag"]
    rest = _angle_x(np.asarray(g.nodes[barrel].get("rotation", [0, 0, 0, 1]), float))
    r.check(abs(rest) < 0.01, f"{label}: barrel open {rest:.2f} deg at rest")
    for nm in ("reload", "reload_empty"):
        a = anims[nm]
        dur = g.duration(a)
        ev = {e["name"]: e["time"] for e in a["extras"]["events"]}
        angles = [_angle_x(np.asarray(g.sample(a, t).get(barrel, {}).get("rotation", [0, 0, 0, 1]), float))
                  for t in np.arange(0, dur + 1e-9, 1 / 60)]
        widest = min(angles)
        r.check(abs(widest - hm.OPEN) < 3.0, f"{label}: {nm} opens the barrel to {widest:.1f} deg (stop {hm.OPEN})")
        r.check(abs(angles[-1]) < 0.05, f"{label}: {nm} ends with the barrel open {angles[-1]:.2f} deg")
        at_out = _angle_x(np.asarray(g.sample(a, ev["mag_out"]).get(barrel, {}).get("rotation", [0, 0, 0, 1]), float))
        r.check(at_out < hm.OPEN + 5, f"{label}: {nm} throws the case with the barrel only {at_out:.1f} deg open")
        scale = lambda t: float(np.asarray(g.sample(a, t).get(mag, {}).get("scale", [1, 1, 1]))[0])
        r.check(scale(ev["mag_out"] + 0.02) == 0.0 and scale(ev["mag_in"]) == 1.0,
                f"{label}: {nm}: the round must hide after mag_out and show at mag_in")
        r.note(f"{label}: {nm}: barrel opens to {widest:.1f} deg, case out at {ev['mag_out']:.2f}s, "
               f"round in at {ev['mag_in']:.2f}s, shut at {ev['slide_forward']:.2f}s")
    # iron sights: the front blade's top (barrel mesh, hinge-relative) level with the rear notch's
    bpts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[barrel]["mesh"])])
    top = float(bpts[:, 1].max()) + hm.HINGE[1]
    r.check(abs(top - hm.SIGHT_Y) < 0.0003, f"{label}: front blade top {top:.4f}, the sight line is {hm.SIGHT_Y:.4f}")
    r.note(f"{label}: {drawn} triangles drawn")
