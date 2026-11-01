"""Vehicle GLB checks (VEHICLE_SPEC section 3, VIEWMODEL_SPEC section 57), used by check_glb.py (CHECK_MODULES; claims
models/vehicle/*.glb). The runtime side (the data's seats, muzzles, effect anchors against these nodes) is
tools/vehicles/check_vehicles.py.

* nodes of the contract with their parents (wheels under their steering, the cannon under the turret, muzzles under
  their guns, turret seats under the turret) and one seat per seat of the vehicle;
* the front is -Z (the bow / the hood / the gun's muzzle ahead of the middle), the origin on the ground (land wheels
  touch y = 0 within 2 cm, the tank's track bottom too, the boat's keel at 0);
* every spinning part is concentric with its pivot (its mesh's centre on the axis within 3 mm), wheels of a pair alike;
* materials: a region on every one, a texture set on every textured one, glass blended, the track's scroll extras,
  baked AO on every opaque non-glowing primitive;
* the triangle budget (model_quality.json `vehicle_<id>`)."""
import os

import numpy as np

from check_glb import check_container, check_meshes
from glbread import GLTF
from model_quality import BUDGETS

SEATS = {"motorcycle": 1, "offroad": 4, "speedboat": 5, "tank": 3, "fighter_jet": 1, "helicopter": 6}
PARENTS = {
    "motorcycle": {"body": None, "fork": "body", "handlebar": "fork", "wheel_f": "fork", "wheel_r": "body"},
    "offroad": {"body": None, "steer_fl": "body", "steer_fr": "body", "wheel_fl": "steer_fl", "wheel_fr": "steer_fr",
                "wheel_rl": "body", "wheel_rr": "body"},
    "speedboat": {"body": None, "outboard": "body", "propeller": "outboard", "wake": "body"},
    "tank": dict({"body": None, "turret": "body", "cannon": "turret", "muzzle_cannon": "cannon", "mg": "turret",
                  "muzzle_mg": "mg", "track_l": "body", "track_r": "body", "sprocket_l": "body", "sprocket_r": "body",
                  "idler_l": "body", "idler_r": "body", "seat_1": "turret", "seat_2": "turret"},
                 **{f"roadwheel_{s}{i}": "body" for s in "lr" for i in range(6)}),
    "fighter_jet": {"body": None, "gear_0": "body", "gear_1": "body", "gear_2": "body", "wheel_n": "gear_0",
                    "wheel_l": "gear_1", "wheel_r": "gear_2", "afterburner": "body", "muzzle_cannon": "body",
                    "muzzle_rockets": "body", "exhaust_0": "body", "headlight_0": "gear_0", "seat_0": "body"},
    "helicopter": dict({"body": None, "rotor_main": "body", "rotor_tail": "body", "chin_turret": "body",
                        "chin_gun": "chin_turret", "muzzle_chingun": "chin_gun", "muzzle_rockets": "body",
                        "exhaust_0": "body", "exhaust_1": "body", "headlight_0": "body"},
                       **{f"seat_{i}": "body" for i in range(6)}),
}
SPIN_X = {"motorcycle": ["wheel_f", "wheel_r"], "offroad": ["wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"],
          "tank": [f"roadwheel_{s}{i}" for s in "lr" for i in range(6)] + ["sprocket_l", "sprocket_r", "idler_l",
                                                                           "idler_r"],
          "fighter_jet": ["wheel_n", "wheel_l", "wheel_r"], "helicopter": ["rotor_tail"]}
SPIN_Y = {"helicopter": ["rotor_main"]}
SPIN_Z = {"speedboat": ["propeller"]}
GROUNDED = {"motorcycle": ["wheel_f", "wheel_r"], "offroad": ["wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"],
            "fighter_jet": ["wheel_n", "wheel_l", "wheel_r"]}
SEE_THROUGH = {"glass"}
GLOWING = {"headlight", "taillight", "gauge", "afterburner", "nav_green"}
NO_SET = {"headlight", "taillight", "indicator", "gauge", "reflector", "lens", "glass", "afterburner", "nav_green"}


def claims(path, names):
    return os.path.basename(os.path.dirname(path)) == "vehicle"


def _points(g, W, i):
    out = []
    for p in g.mesh_primitives(g.nodes[i]["mesh"]):
        out.append(p["positions"] @ W[i][:3, :3].T + W[i][:3, 3])
    return np.concatenate(out) if out else np.zeros((0, 3))


def _local_points(g, i):
    return np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[i]["mesh"])])


def check(path, arms, r):
    vid = os.path.splitext(os.path.basename(path))[0]
    g = GLTF(path)
    check_container(g, r, vid)
    check_meshes(g, r, vid)
    if not r.check(vid in SEATS, f"{vid}: not a vehicle of the contract ({', '.join(SEATS)})"):
        return
    names = g.names
    parent = {}
    for i, n in enumerate(g.nodes):
        for c in n.get("children", []):
            parent[c] = i
    W = g.world_matrices({})
    # nodes and parents
    for name, want in PARENTS[vid].items():
        if not r.check(name in names, f"{vid}: node {name} missing"):
            continue
        got = parent.get(names[name])
        got = g.nodes[got]["name"] if got is not None else None
        r.check(got == want, f"{vid}: {name} is under {got}, the contract wants {want}")
    seats = sorted(n for n in names if n.startswith("seat_"))
    r.check(seats == [f"seat_{i}" for i in range(SEATS[vid])], f"{vid}: seats {seats}, want {SEATS[vid]}")
    # the whole model: front towards -Z, on the ground
    pts = np.concatenate([_points(g, W, i) for i, n in enumerate(g.nodes) if "mesh" in n])
    lo, hi = pts.min(0), pts.max(0)
    r.note(f"{vid}: bounds x {lo[0]:.2f}..{hi[0]:.2f}  y {lo[1]:.2f}..{hi[1]:.2f}  z {lo[2]:.2f}..{hi[2]:.2f}")
    # the ground: the lowest point of the hull (a boat's outboard reaches below its keel on purpose)
    ground = _points(g, W, names["body"])[:, 1].min() if vid == "speedboat" else lo[1]
    r.check(abs(ground) < 0.03, f"{vid}: lowest point at y {ground:.3f}, the origin is on the ground")
    r.check(abs(lo[0] + hi[0]) < 0.08, f"{vid}: not centred on x ({lo[0]:.2f}..{hi[0]:.2f})")
    if vid == "tank":
        mz = W[names["muzzle_cannon"]][2, 3]
        r.check(mz < lo[2] + 0.05, f"tank: the muzzle (z {mz:.2f}) is not the front-most point")
    for name in GROUNDED.get(vid, []):
        if name in names:
            p = W[names[name]][:3, 3]
            radius = np.max(np.linalg.norm(_points(g, W, names[name])[:, 1:] - p[1:], axis=1))
            r.check(abs(p[1] - radius) < 0.02, f"{vid}: {name} (radius {radius:.3f}) floats {p[1] - radius:+.3f} m")
    # concentric spinning parts
    for axis, table in ((0, SPIN_X), (1, SPIN_Y), (2, SPIN_Z)):
        for name in table.get(vid, []):
            if name not in names or "mesh" not in g.nodes[names[name]]:
                r.check(False, f"{vid}: {name} has no mesh")
                continue
            q = _local_points(g, names[name])
            others = [k for k in range(3) if k != axis]
            # the centroid of a part symmetric about its axis lies on it (a box would not: three blades, odd teeth)
            centre = q[:, others].mean(0)
            r.check(np.all(np.abs(centre) < 0.003), f"{vid}: {name} is off its spin axis by {centre}")
    # materials
    mats = g.json.get("materials", [])
    for m in mats:
        nm, ex = m.get("name"), m.get("extras") or {}
        r.check("region" in ex, f"{vid}: material {nm} has no region")
        if nm not in NO_SET:
            r.check("pbr" in ex and "uvScale" in ex, f"{vid}: material {nm} has no texture set")
        if nm in SEE_THROUGH:
            r.check(m.get("alphaMode") == "BLEND", f"{vid}: {nm} is not blended")
        else:
            r.check(m.get("alphaMode", "OPAQUE") == "OPAQUE", f"{vid}: {nm} must be opaque")
        if nm in GLOWING:
            r.check(any(v > 0 for v in m.get("emissiveFactor", [0, 0, 0])), f"{vid}: {nm} does not glow")
        if nm == "track":
            r.check("scroll" in ex and ex["scroll"].get("vPerMetre", 0) > 0, f"{vid}: the track has no scroll extras")
    tris = 0
    no_ao = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n:
            continue
        for p, raw in zip(g.mesh_primitives(n["mesh"]), g.json["meshes"][n["mesh"]]["primitives"]):
            tris += len(p["indices"])
            mat = mats[raw["material"]]
            glow = any(v > 0 for v in mat.get("emissiveFactor", [0, 0, 0]))
            if mat.get("alphaMode", "OPAQUE") == "OPAQUE" and not glow and "COLOR_0" not in raw["attributes"]:
                no_ao.append(f"{n['name']}/{mat.get('name')}")
    r.check(not no_ao, f"{vid}: primitives without baked AO: {no_ao[:6]}")
    budget = BUDGETS.get(f"vehicle_{vid}")
    r.check(budget is not None and tris <= budget, f"{vid}: {tris} triangles over the budget {budget}")
    r.note(f"{vid}: {tris} triangles / {budget}; {len(mats)} materials; {len(g.nodes)} nodes")
