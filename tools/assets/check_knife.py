"""Knife GLB checks (VIEWMODEL_SPEC sections 11-12), used by check_glb.py."""
import numpy as np

from check_glb import check_container, check_eye_distance, check_meshes, euler_deg
from glbread import GLTF

KNIFE_CLIPS = {"idle": (2.0, 2.0), "draw": (0.80, 0.80), "light_1": (0.42, 0.42), "light_2": (0.42, 0.42),
               "heavy": (0.95, 0.95), "inspect": (3.0, 4.2)}
OPTIONAL_CLIPS = {"sprint": (0.66, 0.66)}
LOOPING = {"idle", "sprint"}
EVENTS = {"swing", "swing_heavy", "flip", "open", "catch", "equip"}
REQUIRED_EVENTS = {"draw": {"equip", "open"}, "light_1": {"swing"}, "light_2": {"swing"}, "heavy": {"swing_heavy"},
                   "inspect": {"flip", "catch"}}
CONTACT = {"light_1": 0.13 / 0.42, "light_2": 0.13 / 0.42, "heavy": 0.40 / 0.95}   # fraction of the clip
MATERIALS = {"blade", "blade_edge", "handle", "handle_inlay", "pins", "latch"}
PARENTS = {"viewmodel": None, "knife": "viewmodel", "arm_R": "viewmodel", "arm_R_fore": "arm_R",
           "arm_L": "viewmodel", "arm_L_fore": "arm_L"}
BALISONG = ("handle_safe", "blade", "handle_bite", "latch", "tip")


def _descends(g, i, ancestor):
    while i in g.parent:
        i = g.parent[i]
        if i == ancestor:
            return True
    return False


def check_nodes(g, r, label):
    for nm, parent in PARENTS.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        par = g.parent.get(g.names[nm])
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"{label}: node {nm} parent is {pname}, expected {parent}")
    if "camera" in g.names:
        par = g.parent.get(g.names["camera"])
        r.check(par is not None and g.nodes[par]["name"] == "viewmodel", f"{label}: camera must be under viewmodel")
        r.check("mesh" not in g.nodes[g.names["camera"]], f"{label}: camera must not carry a mesh")
    knife = g.names.get("knife")
    for nm in BALISONG:
        if r.check(nm in g.names, f"{label}: balisong node {nm} missing"):
            r.check(_descends(g, g.names[nm], knife), f"{label}: {nm} is not under knife")
    for i, n in enumerate(g.nodes):
        if "mesh" in n:
            r.check(_descends(g, i, knife), f"{label}: mesh node {n.get('name')} outside the knife hierarchy")
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), f"{label}: viewmodel rest not identity")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    tip, blade = g.names.get("tip"), g.names.get("blade")
    if tip is not None and blade is not None:
        r.check(g.parent.get(tip) == blade, f"{label}: tip should hang under blade")


def check_materials(g, r, label):
    names = {m.get("name") for m in g.json.get("materials", [])}
    for nm in names - MATERIALS:
        r.check(False, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - names:
        r.check(False, f"{label}: material {nm} missing")


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm, cam, arm_l = g.names["viewmodel"], g.names.get("camera"), g.names["arm_L"]
    for nm, (lo, hi) in {**KNIFE_CLIPS, **OPTIONAL_CLIPS}.items():
        if nm not in anims:
            r.check(nm in OPTIONAL_CLIPS, f"{label}: clip {nm} missing")
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-3 <= dur <= hi + 1e-3, f"{label}: {nm} length {dur:.3f} outside [{lo}, {hi}]")
        r.note(f"{nm}: {dur:.3f}s, {len(a['channels'])} channels")
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            s = a["samplers"][ch["sampler"]]
            ts = g.accessor(s["input"])[:, 0]
            vs = g.accessor(s["output"]).astype(np.float64)
            r.check(node != vm, f"{label}: {nm} animates viewmodel")
            r.check(s.get("interpolation", "LINEAR") in ("LINEAR", "STEP"), f"{label}: {nm} interpolation")
            r.check(abs(ts[0]) < 1e-6 and np.all(np.diff(ts) > 0), f"{label}: {nm} bad key times")
            if path == "rotation":
                r.check(np.all(np.abs(np.linalg.norm(vs, axis=1) - 1) < 1e-4), f"{label}: {nm} quaternions")
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"{label}: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if node == cam:
                r.check(path == "rotation", f"{label}: {nm} animates camera {path}")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= 1.5 + 1e-3) and np.all(np.abs(eul[:, 2]) <= 3.0 + 1e-3),
                        f"{label}: {nm} camera beyond +-1.5/+-3 deg")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
            if node == arm_l and path == "scale":
                r.check(s.get("interpolation") == "STEP", f"{label}: {nm} arm_L scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"{label}: {nm} arm_L scale not 0/1")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENTS, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            if nm in CONTACT:
                hit = CONTACT[nm] * dur
                pre = [e["time"] for e in ev if e["name"] in ("swing", "swing_heavy")]
                r.check(bool(pre) and all(t < hit for t in pre), f"{label}: {nm} swing event must precede contact")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    return anims


def _world(g, a, t, node):
    return g.world_matrices(g.sample(a, t))[g.names[node]][:3, 3]


def blade_crossing(g, a, dur, dt=0.0005):
    """Times at which the blade (blade pivot -> tip) passes over the screen centre."""
    out = []
    prev = None
    for t in np.arange(0, dur + 1e-9, dt):
        W = g.world_matrices(g.sample(a, t))
        A, B = W[g.names["blade"]][:3, 3], W[g.names["tip"]][:3, 3]
        if A[2] >= -0.02 or B[2] >= -0.02:
            prev = None
            continue
        pa, pb = A[:2] / -A[2], B[:2] / -B[2]
        d = pb - pa
        side = d[0] * (-pa[1]) - d[1] * (-pa[0])          # sign of the centre relative to the blade line
        s = float(np.dot(-pa, d) / max(np.dot(d, d), 1e-12))
        if prev is not None and np.sign(side) != np.sign(prev[1]) and 0.0 <= s <= 1.0:
            t0, s0 = prev[0], prev[1]
            out.append(t0 + (t - t0) * s0 / (s0 - side))
        prev = (t, side)
    return out


def check_contacts(g, r, label, anims):
    for nm, frac in CONTACT.items():
        if nm not in anims:
            continue
        a = anims[nm]
        dur = g.duration(a)
        want = frac * dur
        if nm.startswith("light"):
            hits = blade_crossing(g, a, dur)
            got = min(hits, key=lambda t: abs(t - want)) if hits else None
            ok = got is not None and abs(got - want) <= 0.005
            r.check(ok, f"{label}: {nm} blade crosses the screen centre at {got} s, expected {want:.3f}")
            r.note(f"{nm}: blade over the screen centre at {got:.4f}s = {got / dur:.4f} of the clip "
                   f"(target {frac:.4f}); crossings {[round(float(h), 4) for h in hits]}" if got is not None else
                   f"{nm}: no centre crossing")
        else:
            ts = np.arange(0, dur + 1e-9, 0.001)
            reach = [np.linalg.norm(_world(g, a, t, "tip")) for t in ts]
            got = float(ts[int(np.argmax(reach))])
            r.check(abs(got - want) <= 0.01, f"{label}: {nm} full extension at {got:.3f} s, expected {want:.3f}")
            r.note(f"{nm}: tip furthest from the eye ({max(reach):.3f} m) at {got:.3f}s = {got / dur:.4f} "
                   f"of the clip (target {frac:.4f})")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    anims = check_clips(g, r, label)
    check_contacts(g, r, label, anims)
    check_eye_distance(g, arms, r, anims, root="knife", label=label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.note(f"knife triangles: {tris}")
