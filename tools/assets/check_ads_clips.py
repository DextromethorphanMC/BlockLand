"""Aimed clip checks (VIEWMODEL_SPEC section 4 / 31.5), run by check_glb.py on every weapon file that has `<clip>_ads`
clips: the hip clip exists with the same length and events, the camera stays inside the section-4 limits and ends at
rest, and at EVERY frame - with the viewmodel moved as the runtime aims (the rest pose's eye point on the camera) - the
sight picture holds: the iron sights' front post tip and, through every optic that fits the weapon, its reticle (a
holographic window's centre, a scope's eyepiece) stay within ADS_PX pixels of the screen centre at 1920x1080, and no
part in front of the eye comes closer than ADS_CLEAR."""
import numpy as np

import loadouts as lo
from check_attach import ADS_CLEAR, ADS_PX, FOV_Y, _glass, _points
from check_glb import CAM_ROLL, CAM_TURN, euler_deg

SUFFIX = "_ads"
RATE = 120.0


def _rest(g):
    """Node matrices of the pose the runtime aims from: the idle's first frame (the file's rest pose without one)."""
    idle = g.animation("idle")
    return g.world_matrices(g.sample(idle, 0.0) if idle is not None else {})


def _iron_point(g, M, pick):
    """Front post tip of the iron sights in the frame M maps to: the mean of the vertices `pick` chose at rest (the
    forward-most top ones; choosing them again per frame would jump between a post's corners as it rolls a hair)."""
    i = g.names.get("sight_iron")
    if i is None or "mesh" not in g.nodes[i]:
        return None
    v = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[i]["mesh"])])
    v = v @ M[i][:3, :3].T + M[i][:3, 3]
    if pick is None:
        post = np.flatnonzero(v[:, 2] <= v[:, 2].min() + 0.01)
        return post[v[post, 1] >= v[post, 1].max() - 1e-6]
    return v[pick].mean(0)


def _optic_point(optic, AW):
    a = lo.model(optic)
    if optic in lo.SCOPES:
        return AW[a.names["lens_ocular"]][:3, 3]
    if "reticle" in a.names:
        return AW[a.names["reticle"]][:3, 3]
    return _glass(a, AW)[0].mean(0)


def _nearest(g, M, hidden, optic=None, AW=None):
    best = np.inf
    for i, n in enumerate(g.nodes):
        if "mesh" in n and i not in hidden:
            pts = _points(g, M, {i})
            front = pts[pts[:, 2] < 0]
            if len(front):
                best = min(best, float(np.linalg.norm(front, axis=1).min()))
    if optic is not None:
        pts = _points(lo.model(optic), AW)
        front = pts[pts[:, 2] < 0]
        if len(front):
            best = min(best, float(np.linalg.norm(front, axis=1).min()))
    return best


def _sights(g):
    """(label, aim matrix at rest, build, optic) for the irons and every optic the weapon can take."""
    out = []
    W0 = _rest(g)
    if "aim_iron" in g.names:
        out.append(("irons", W0[g.names["aim_iron"]], {"slots": {}, "mag": "mag_standard"}, None))
    if lo.SLOTS["optic"] in g.names:
        for optic in lo.OPTICS:
            a = lo.model(optic)
            aim = W0[g.names[lo.SLOTS["optic"]]] @ a.world_matrices({})[a.names["aim"]]
            out.append((optic, aim, {"slots": {"optic": optic}, "mag": "mag_standard"}, optic))
    return out


def check(path, r, label):
    g = lo.load(path)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    aimed = sorted(n for n in anims if n.endswith(SUFFIX))
    if not aimed:
        return
    focal = 540.0 / np.tan(np.radians(FOV_Y / 2))
    sights = _sights(g)
    cam = g.names.get("camera")
    for name in aimed:
        a, hip = anims[name], anims.get(name[:-len(SUFFIX)])
        if not r.check(hip is not None, f"{label}: {name} has no hip clip {name[:-len(SUFFIX)]}"):
            continue
        dur = g.duration(a)
        r.check(abs(dur - g.duration(hip)) < 1e-3, f"{label}: {name} length {dur:.3f} differs from the hip clip's "
                                                   f"{g.duration(hip):.3f}")
        ev = [(round(e["time"], 3), e["name"]) for e in a.get("extras", {}).get("events", [])]
        ev_hip = [(round(e["time"], 3), e["name"]) for e in hip.get("extras", {}).get("events", [])]
        r.check(ev == ev_hip, f"{label}: {name} events {ev} differ from the hip clip's {ev_hip}")
        for ch in a["channels"]:
            if ch["target"]["node"] != cam:
                continue
            vs = g.accessor(a["samplers"][ch["sampler"]]["output"]).astype(np.float64)
            eul = np.array([euler_deg(q) for q in vs])
            r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                    f"{label}: {name} camera beyond the section-4 limits")
            r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {name} camera not at rest at end")
        times = np.unique(np.concatenate([np.arange(0.0, dur + 1e-9, 1.0 / RATE), [dur]]))
        for sight, aim0, build, optic in sights:
            inv = np.linalg.inv(aim0)
            hidden = lo.hidden_nodes(g, build)
            worst_px, worst_t, near = 0.0, 0.0, np.inf
            pick = _iron_point(g, {k: inv @ v for k, v in _rest(g).items()}, None) if optic is None else None
            for t in times:
                W = g.world_matrices(g.sample(a, float(t)))
                M = {k: inv @ v for k, v in W.items()}
                if optic is None:
                    point, AW = (None if pick is None else _iron_point(g, M, pick)), None
                else:
                    am = lo.model(optic)
                    AW = {k: M[g.names[lo.SLOTS["optic"]]] @ v for k, v in am.world_matrices({}).items()}
                    point = _optic_point(optic, AW)
                if point is None or point[2] >= 0:
                    worst_px = np.inf
                    break
                px = float(np.abs(focal * point[:2] / -point[2]).max())
                if px > worst_px:
                    worst_px, worst_t = px, float(t)
                near = min(near, _nearest(g, M, hidden, optic, AW))
            r.check(worst_px <= ADS_PX, f"{label}: {name} through {sight}: the aiming point is {worst_px:.2f} px off "
                                        f"centre at t={worst_t:.3f} (needs <= {ADS_PX})")
            r.check(near >= ADS_CLEAR, f"{label}: {name} through {sight}: a part comes {near:.3f} m from the eye "
                                       f"(needs >= {ADS_CLEAR})")
            r.note(f"{name} through {sight}: aiming point within {worst_px:.2f} px (t={worst_t:.3f}), nearest part "
                   f"{near:.3f} m")
