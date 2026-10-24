"""Section 19 checks, used by check_glb.py: the attachment files themselves, and every attachment fitted on every
weapon -- mounts present, nothing comes closer than EYE_MIN to the eye in any clip with the biggest builds, and
aiming down every optic works (clearance, reticle centred, the window clear around the reticle). A reflex sight
without a `reticle` node (the holographic sight: the runtime draws its reticle in the glass) aims through the centre
of its `glass` window instead. Optics carry a matte `interior` material on the walls the eye looks along; a scope's
eyecup frames the eyepiece and hides the rest of the scope from the eye point (section 22)."""
import os

import numpy as np

import loadouts as lo
import raster as rs

FOV_Y, NEAR = 54.0, 0.02
EYE_MIN = 0.06              # section 2: nothing closer to the eye in any frame of any clip
ADS_CLEAR = 0.03            # at ADS no part in front of the eye closer than this (near plane 0.02)
ADS_PX = 2.0                # reticle within this many pixels of the screen centre at 1920x1080
CLEAR_ZONE = 0.25           # this fraction of the window radius around the reticle must show nothing but the scene
BUILDS = ("largest", "mini_full", "holo_full", "sniper_full", "scope2x_full", "heavy_full", "wire_full", "rect_full",
          "angled_full", "bipod_full")
RELIEF = (0.07, 0.09)       # section 22: eye point behind a scope's eyepiece glass
OBJECTIVE_CLEAR = 0.40      # nothing of the weapon in front of a scope's objective within this distance
CUP_RATIO = (1.35, 1.45)    # section 22: eyecup (scope_rubber) outer radius / eyepiece glass radius
CUP_DEPTH = 0.0125          # section 22: the eyecup's rear rim at most this far behind the eyepiece glass
INTERIOR = (0.04, 0.9)      # `interior`: base colour at most, roughness at least (and not metallic)
OWN_CLEAR = 0.5             # a reflex sight's own body leaves its window clear within this share of its inner radius
GUN_MOUNTS = tuple(m for s, m in lo.SLOTS.items() if s not in lo.OPTIONAL_SLOTS)
KNIFE_MOUNTS = ("mount_charm",)


def _under(g, i, ancestor):
    while True:
        if i == ancestor:
            return True
        if i not in g.parent:
            return False
        i = g.parent[i]


def _points(g, W, nodes=None):
    out = []
    for i, n in enumerate(g.nodes):
        if "mesh" in n and (nodes is None or i in nodes):
            M = W[i]
            if abs(np.linalg.det(M[:3, :3])) < 1e-12:
                continue
            out.extend(p["positions"] @ M[:3, :3].T + M[:3, 3] for p in g.mesh_primitives(n["mesh"]))
    return np.concatenate(out) if out else np.zeros((0, 3))


def check_models(r):
    """Every attachment file: one root `attachment` (identity), its helper nodes, the optics' reticle on the aim line,
    helpers looking down -Z, a charm hanging under `pivot`."""
    for name, (slot, helpers) in lo.MODELS.items():
        path = os.path.join(lo.ATTACH_DIR, f"{name}.glb")
        if not r.check(os.path.exists(path), f"attachment {name}: models/attachment/{name}.glb missing"):
            continue
        a = lo.model(name)
        roots = [i for i in range(len(a.nodes)) if i not in a.parent]
        r.check(len(roots) == 1 and a.nodes[roots[0]].get("name") == "attachment",
                f"attachment {name}: needs exactly one root node `attachment`")
        root = a.nodes[a.names["attachment"]] if "attachment" in a.names else {}
        r.check(not any(k in root for k in ("translation", "rotation", "scale")),
                f"attachment {name}: root transform must be identity (origin = the mount point)")
        W = a.world_matrices({})
        for h in helpers:
            r.check(h in a.names and "mesh" not in a.nodes[a.names[h]], f"attachment {name}: empty helper node {h}")
        for h in ("aim", "emitter", "muzzle"):
            if h in a.names:
                d = -W[a.names[h]][:3, 2]
                r.check(np.dot(d, (0.0, 0.0, -1.0)) / np.linalg.norm(d) > 0.9999,
                        f"attachment {name}: {h} must look down the attachment's -Z")
        mats = {m["name"]: m for m in a.json.get("materials", [])}
        if slot == "optic":
            _check_interior(a, mats, r, name)
        if slot == "optic" and name in lo.SCOPES:
            _check_scope(a, W, mats, r, name)
        elif slot == "optic":
            aim = W[a.names["aim"]]
            fwd = -aim[:3, 2] / np.linalg.norm(aim[:3, 2])
            if name in lo.GLASS_RETICLE:          # holographic: the reticle is drawn in the glass, no reticle node
                r.check("reticle" not in a.names, f"attachment {name}: no reticle node (the runtime draws it in the glass)")
                pos, nrm = _glass(a, W)
                ret = pos.mean(0)
                r.check(bool(len(pos)) and float(np.abs((pos - ret) @ fwd).max()) < 1e-6
                        and bool(np.all(np.abs(nrm @ fwd) > 0.9999)),
                        f"attachment {name}: the glass window must be flat, square to the aim line")
                r.check(mats.get("glass", {}).get("doubleSided", False), f"attachment {name}: glass must be double sided")
                ext = (pos - ret) @ np.stack([aim[:3, 0], aim[:3, 1]], 1)
                r.note(f"{name}: window {np.ptp(ext[:, 0]) * 1000:.1f} x {np.ptp(ext[:, 1]) * 1000:.1f} mm, centre "
                       f"{np.round(ret, 4).tolist()} (attachment frame)")
            else:
                ret = W[a.names["reticle"]][:3, 3]
                ret_m = mats.get("reticle", {})
                r.check(ret_m.get("alphaMode") == "MASK" and any(x > 0 for x in ret_m.get("emissiveFactor", [0])),
                        f"attachment {name}: reticle material must be emissive MASK")
            v = ret - aim[:3, 3]
            off = v - fwd * np.dot(v, fwd)
            r.check(np.linalg.norm(off) < 1e-5 and np.dot(v, fwd) > 0,
                    f"attachment {name}: {'window centre' if name in lo.GLASS_RETICLE else 'reticle'} not on the aim line")
            r.check(mats.get("glass", {}).get("alphaMode") == "BLEND", f"attachment {name}: glass must be BLEND")
            r.note(f"{name}: eye relief {np.dot(v, fwd):.3f} m, sight line {aim[1, 3]:.4f} m over the mount")
            _check_own_window(a, W, r, name)
        if slot == "laser":
            r.check(any(x > 0 for x in mats.get("laser_lens", {}).get("emissiveFactor", [0])),
                    f"attachment {name}: laser_lens must be emissive")
        if slot == "charm":
            piv = a.names.get("pivot")
            meshes = [i for i, n in enumerate(a.nodes) if "mesh" in n]
            if r.check(piv is not None and all(_under(a, i, piv) for i in meshes),
                       f"attachment {name}: everything that swings must hang under `pivot`"):
                pts = _points(a, a.world_matrices({}), set(meshes)) - W[piv][:3, 3]
                r.check(pts[:, 1].max() <= 1e-4, f"attachment {name}: must hang along -Y from the pivot")
                r.note(f"{name}: hangs {-pts[:, 1].min():.3f} m below the pivot")
        tris = sum(len(p["indices"]) for n in a.nodes if "mesh" in n for p in a.mesh_primitives(n["mesh"]))
        r.note(f"{name}: {tris} triangles, materials {sorted(mats)}")


def _glass(a, W):
    """World positions and normals of an optic's `glass` primitives."""
    pos, nrm = [], []
    for i, n in enumerate(a.nodes):
        if "mesh" in n:
            M = W[i]
            for p in a.mesh_primitives(n["mesh"]):
                if a.json["materials"][p["material"]]["name"] == "glass":
                    pos.append(p["positions"] @ M[:3, :3].T + M[:3, 3])
                    nrm.append(p["normals"] @ M[:3, :3].T)
    return (np.concatenate(pos), np.concatenate(nrm)) if pos else (np.zeros((0, 3)), np.zeros((0, 3)))


def _check_interior(a, mats, r, name):
    """Every optic has an `interior` material (the walls the eye looks along when aiming): flat matte near-black,
    not metallic, never skinned (region none), no texture set."""
    m = mats.get("interior")
    if not r.check(m is not None, f"attachment {name}: optic without an `interior` material"):
        return
    pbr = m.get("pbrMetallicRoughness", {})
    ex = m.get("extras") or {}
    r.check(max(pbr.get("baseColorFactor", [1, 1, 1])[:3]) <= INTERIOR[0] and pbr.get("roughnessFactor", 1.0) >= INTERIOR[1]
            and pbr.get("metallicFactor", 1.0) == 0 and "pbr" not in ex and "baseColorTexture" not in pbr,
            f"attachment {name}: `interior` must be flat matte near-black (base <= {INTERIOR[0]}, roughness >= "
            f"{INTERIOR[1]}, metallic 0, no texture)")
    r.check(ex.get("region") == "none", f"attachment {name}: `interior` region must be none")


def _check_own_window(a, W, r, name, w=640, h=360):
    """From its own `aim` point a reflex sight's body (hood, tube, emitter) leaves the window clear within OWN_CLEAR
    of the window's inner radius (the hood's inner walls must not show through the glass)."""
    inv = np.linalg.inv(W[a.names["aim"]])
    mats = lo.load_materials(a)
    glass, body = [], []
    for i, n in enumerate(a.nodes):
        if "mesh" in n:
            items = []
            lo.instance(a, mats, n["mesh"], inv @ W[i], items,
                        mat_override=lambda m: dict(m, alpha_mode="OPAQUE", double=True))
            for it in items:
                if it["mat"]["name"] != "reticle":            # the dot itself belongs in the window
                    (glass if it["mat"]["name"] == "glass" else body).append(it)
    cam = rs.Camera(w, h, FOV_Y, near=0.005)
    win = (rs.rasterize(glass, cam, w, h)[0]["mid"] >= 0).reshape(h, w)
    hit = (rs.rasterize(body, cam, w, h)[0]["mid"] >= 0).reshape(h, w)
    ys, xs = np.mgrid[0:h, 0:w]
    rr = np.hypot(xs + 0.5 - w / 2, ys + 0.5 - h / 2)
    if not r.check(bool(win.any()), f"attachment {name}: no glass window seen from the aim point"):
        return
    inner = float(rr[~win].min())
    zone = rr <= OWN_CLEAR * inner
    cover = float(hit[zone].mean())
    r.check(cover <= 0.01, f"attachment {name}: its own body covers {cover:.0%} of the window within {OWN_CLEAR} of its "
                           f"radius, seen from the aim point")
    r.note(f"{name}: seen from the aim point its own body covers {float(hit[win].mean()):.0%} of the window "
           f"({cover:.0%} within {OWN_CLEAR} of the inner radius)")


def _check_scope(a, W, mats, r, name):
    """Section 22: eyepiece and objective glass discs on the optical axis (UV centre 0.5 / rim radius 0.5, the
    eyepiece facing the eye, upright: u along +X, v down), eye relief, and no reticle quad."""
    r.check("reticle" not in a.names, f"attachment {name}: a scope draws its reticle in the eyepiece (no reticle node)")
    aim = W[a.names["aim"]]
    fwd = -aim[:3, 2] / np.linalg.norm(aim[:3, 2])
    for node, mat_name, facing in (("lens_ocular", "lens", 1.0), ("lens_objective", "lens_front", -1.0)):
        if not r.check(node in a.names and "mesh" in a.nodes[a.names[node]], f"attachment {name}: {node} disc missing"):
            continue
        r.check(mats.get(mat_name, {}).get("alphaMode") == "BLEND", f"attachment {name}: {mat_name} must be BLEND")
        M = W[a.names[node]]
        c = M[:3, 3]
        v = c - aim[:3, 3]
        off = v - fwd * np.dot(v, fwd)
        r.check(np.linalg.norm(off) < 1e-5 and np.dot(v, fwd) > 0,
                f"attachment {name}: {node} not on the aim line in front of the eye")
        for p in a.mesh_primitives(a.nodes[a.names[node]]["mesh"]):
            r.check(a.json["materials"][p["material"]]["name"] == mat_name, f"attachment {name}: {node} must use {mat_name}")
            pos = p["positions"] @ M[:3, :3].T + M[:3, 3]
            nrm = p["normals"] @ M[:3, :3].T
            r.check(bool(np.all(nrm @ (-fwd * facing) > 0.999)),
                    f"attachment {name}: {node} must face {'the eye (+Z)' if facing > 0 else 'forward (-Z)'}")
            d = pos - c
            rad = np.linalg.norm(d, axis=1)
            uv = p["uvs"]
            centre, rim = rad < 1e-7, rad > rad.max() * 0.999
            r.check(bool(centre.any()) and np.allclose(uv[centre], 0.5, atol=1e-4),
                    f"attachment {name}: {node} UV (0.5, 0.5) not at the disc centre")
            r.check(np.allclose(np.linalg.norm(uv[rim] - 0.5, axis=1), 0.5, atol=2e-3),
                    f"attachment {name}: {node} rim not at UV radius 0.5")
            if node == "lens_ocular":                       # upright through the eyepiece
                x_ax, y_ax = M[:3, 0] / np.linalg.norm(M[:3, 0]), M[:3, 1] / np.linalg.norm(M[:3, 1])
                r.check(np.corrcoef(uv[:, 0], d @ x_ax)[0, 1] > 0.99 and np.corrcoef(uv[:, 1], -(d @ y_ax))[0, 1] > 0.99,
                        f"attachment {name}: lens_ocular UV must run u along +X and v down")
                r.note(f"{name}: eyepiece glass radius {rad.max() * 1000:.1f} mm")
    if "lens_ocular" in a.names:
        relief = float(np.dot(W[a.names["lens_ocular"]][:3, 3] - aim[:3, 3], fwd))
        r.check(RELIEF[0] - 1e-6 <= relief <= RELIEF[1] + 1e-6,
                f"attachment {name}: eye relief {relief:.3f} m outside {RELIEF[0]}..{RELIEF[1]}")
        r.note(f"{name}: scope, eye relief {relief:.3f} m, optical axis {aim[1, 3]:.4f} m over the mount")
        _check_eyecup(a, W, r, name)


def _check_eyecup(a, W, r, name):
    """Section 22: the rubber eyecup (`scope_rubber`) frames the eyepiece -- outer radius CUP_RATIO x the glass radius,
    the rear rim at most CUP_DEPTH behind the glass -- and seen from `aim` along -Z nothing of the scope shows outside
    the eyecup's silhouette, except straight below it where the mount comes up."""
    aim = W[a.names["aim"]]
    e = aim[:3, 3]
    fwd = -aim[:3, 2] / np.linalg.norm(aim[:3, 2])
    X, Y = aim[:3, 0] / np.linalg.norm(aim[:3, 0]), aim[:3, 1] / np.linalg.norm(aim[:3, 1])
    c = W[a.names["lens_ocular"]][:3, 3]
    lens = {a.names[k] for k in ("lens_ocular", "lens_objective") if k in a.names}
    r_glass = float(np.linalg.norm(_points(a, W, lens & {a.names["lens_ocular"]}) - c, axis=1).max())
    rub, rest = [], []
    for i, n in enumerate(a.nodes):
        if "mesh" in n and i not in lens:
            M = W[i]
            for p in a.mesh_primitives(n["mesh"]):
                pos = p["positions"] @ M[:3, :3].T + M[:3, 3]
                (rub if a.json["materials"][p["material"]]["name"] == "scope_rubber" else rest).append(pos)
    if not r.check(bool(rub), f"attachment {name}: no `scope_rubber` eyecup"):
        return
    rub, rest = np.concatenate(rub), np.concatenate(rest)
    rel = rub - c
    r_out = float(np.linalg.norm(rel - np.outer(rel @ fwd, fwd), axis=1).max())
    depth = float(-((np.concatenate([rub, rest]) - c) @ fwd).min())         # the rearmost point, behind the glass
    ratio = r_out / r_glass
    r.check(CUP_RATIO[0] - 1e-3 <= ratio <= CUP_RATIO[1] + 1e-3,
            f"attachment {name}: eyecup outer radius {ratio:.2f} x the glass radius (needs {CUP_RATIO[0]}..{CUP_RATIO[1]})")
    r.check(depth <= CUP_DEPTH + 1e-6, f"attachment {name}: eyecup rim {depth * 1000:.1f} mm behind the glass "
                                       f"(at most {CUP_DEPTH * 1000:.1f})")

    def view(P):
        v = P - e
        d = v @ fwd
        return np.stack([v @ X, v @ Y], 1) / d[:, None], d

    a_cup, _ = view(rub)
    R = float(np.linalg.norm(a_cup, axis=1).max())
    a_rest, d_rest = view(rest)
    below = (a_rest[:, 1] < 0) & (np.abs(a_rest[:, 0]) <= R)
    out = (d_rest <= 0) | ((np.linalg.norm(a_rest, axis=1) > R + 1e-4) & ~below)
    worst = float(np.linalg.norm(a_rest, axis=1)[out].max()) if out.any() else 0.0
    r.check(not out.any(), f"attachment {name}: {int(out.sum())} points of the scope stick out of the eyecup's "
                           f"silhouette seen from the eye point (up to {worst:.3f} rad, silhouette {R:.3f})")
    r.note(f"{name}: eyecup outer radius {r_out * 1000:.1f} mm = {ratio:.2f} x the glass ({r_glass * 1000:.1f} mm), "
           f"rim {depth * 1000:.1f} mm behind it; seen from the eye point its silhouette ({R:.3f} rad) hides the scope")


def check_weapon(path, arms, r, label):
    """Mounts present and parallel to the bore (checked by the caller), every build's attachments outside EYE_MIN
    in every clip, and ADS through every optic."""
    g = lo.load(path)
    knife = "knife" in g.names
    for m in KNIFE_MOUNTS if knife else lo.MOUNTS_OF.get(lo.weapon_name(g), GUN_MOUNTS):
        r.check(m in g.names and "mesh" not in g.nodes[g.names[m]], f"{label}: empty mount node {m} missing")
    for build in BUILDS:
        _eye_distance(g, r, label, build)
    if not knife and "mount_underbarrel" in g.names and arms is not None:
        _hand_clearance(g, arms, r, label)
    if not knife:
        for optic in lo.OPTICS:
            _ads(g, r, label, optic)


def _eye_distance(g, r, label, build):
    anims = [None] + list(g.json.get("animations", []))
    worst = (np.inf, "", 0.0, "")
    for a in anims:
        dur = 0.0 if a is None else g.duration(a)
        for t in np.unique(np.concatenate([np.arange(0, dur + 1e-9, 1 / 30), [dur]])):
            W = g.world_matrices(g.sample(a, t) if a is not None else {})
            for name, _slot, AW in lo.attachment_matrices(g, W, build):
                pts = _points(lo.model(name), AW)
                if len(pts):
                    d = float(np.linalg.norm(pts, axis=1).min())
                    if d < worst[0]:
                        worst = (d, "rest" if a is None else a["name"], float(t), name)
    if worst[0] < np.inf:
        r.check(worst[0] >= EYE_MIN, f"{label}: {build} build: {worst[3]} comes within {worst[0]:.3f} m of the eye "
                                     f"({worst[1]} t={worst[2]:.2f})")
        r.note(f"{build} build: attachments stay >= {worst[0]:.3f} m from the eye in every clip "
               f"(closest: {worst[3]} in {worst[1]} t={worst[2]:.2f})")


HOLD_CLIPS = ("idle", "fire", "fire_last", "walk")     # the clips' support hand holds the weapon throughout
HOLD_REACH = 0.002          # the arm reaches the hold within this (m), the shoulder coming forward if it must
HOLD_DEPTH = 0.0025         # no point of the hand's skin deeper than this inside the attachment while it holds (idle,
                            # walk); a shot's hand reaction (a wrist roll on a pump) is a transient: reported only
AS_AUTHORED = ("tommy_grip",)   # holds that are the weapon clips' own (the Thompson's foregrip): reported only
DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources", "data",
                    "blockland")


def _data(*path):
    import json
    with open(os.path.join(DATA, *path), encoding="utf-8") as f:
        return json.load(f)


def _hand_clearance(g, arms, r, label):
    """Section 35: with an underbarrel attachment fitted the runtime moves the support hand onto its hold
    (support_grip.py mirrors client/viewmodel/SupportGrip). For every attachment the weapon's slot offers: its
    `grip` in attachments.json is the one underbarrel_models.grip_holds() authors, and in the holding clips the hand
    is on the hold (the clip's hand on its idle hold), the arm reaches it, and the hand does not sink into the
    attachment."""
    import support_grip as sg
    import underbarrel_models as um
    from glbread import trs
    slot = _data("guns", "guns.json")["guns"].get(lo.weapon_name(g), {}).get("slots", {}).get("underbarrel")
    if not slot:
        return
    offered = [o for o in [slot.get("default", "none")] + list(slot.get("options") or []) if o != "none"]
    data = _data("craft", "attachments.json")["attachments"]
    authored = um.grip_json()
    idle0 = g.sample(g.animation("idle"), 0.0) if g.animation("idle") else {}
    mount, hand = g.names["mount_underbarrel"], g.names["arm_L_hand"]
    I = g.world_matrices(idle0)
    H0 = np.linalg.inv(I[mount]) @ I[hand]
    for name in dict.fromkeys(offered):
        grip = data.get(name, {}).get("grip")
        if grip is None:
            r.note(f"{label}: {name} has no grip: the clips keep the support hand [report only]")
            continue
        want = authored.get(name)
        same = want is not None and all(np.allclose(grip[k], want[k], atol=1e-4) for k in ("wrist", "palm")) and all(
            np.allclose(grip["fingers"][f], want["fingers"][f], atol=0.05) for f in want["fingers"])
        r.check(same, f"attachments.json {name}: grip differs from underbarrel_models.grip_holds() "
                      f"(python -I tools/assets/underbarrel_models.py grips)")
        hold = (np.asarray(grip["wrist"], float), np.asarray(grip["palm"], float),
                {f: tuple(c) for f, c in grip["fingers"].items()})
        a = lo.model(name)
        G = trs(hold[0], hold[1], (1, 1, 1))
        worst = {"reach": (0.0, "", 0.0), "depth": (0.0, "", 0.0), "shot": (0.0, "", 0.0), "gap": (np.inf, "", 0.0),
                 "shift": 0.0}
        for anim in [g.animation(c) for c in HOLD_CLIPS if g.animation(c)]:
            dur = g.duration(anim)
            for t in np.unique(np.concatenate([np.arange(0, dur + 1e-9, 1 / 15), [dur]])):
                ov, w, d, shift = sg.apply(g, g.sample(anim, t), idle0, hold)
                W = g.world_matrices(ov)
                M = W[mount]
                if anim["name"] == "idle" and t == 0:
                    r.check(w > 0.999, f"{label}: {name}: the idle's hand is not on its hold (weight {w:.2f})")
                if w < 0.999:
                    continue
                # where the hand should be: the hold, carrying the clip's deviation from the idle's hand
                Wc = g.world_matrices(g.sample(anim, t))
                want = M @ G @ np.linalg.inv(H0) @ np.linalg.inv(Wc[mount]) @ Wc[hand]
                reach = float(np.linalg.norm(W[hand][:3, 3] - want[:3, 3]))
                dist, depth = sg.gap(sg.hand_points(g, arms, W), sg.surface(a, M))
                if reach > worst["reach"][0]:
                    worst["reach"] = (reach, anim["name"], float(t))
                kind = "shot" if anim["name"].startswith("fire") else "depth"
                if depth > worst[kind][0]:
                    worst[kind] = (depth, anim["name"], float(t))
                if dist < worst["gap"][0]:
                    worst["gap"] = (dist, anim["name"], float(t))
                worst["shift"] = max(worst["shift"], shift)
        rv, rc, rt = worst["reach"]
        dv, dc, dt = worst["depth"]
        r.check(rv <= HOLD_REACH, f"{label}: {name}: the support hand misses its hold by {rv * 1000:.1f} mm "
                                  f"({rc} t={rt:.2f})")
        r.check(dv <= HOLD_DEPTH or name in AS_AUTHORED, f"{label}: {name}: the support hand sinks {dv * 1000:.1f} mm "
                                                         f"into it ({dc} t={dt:.2f})")
        sv, sc, st = worst["shot"]
        if sv > HOLD_DEPTH:
            r.note(f"{label}: {name}: a shot's hand reaction sinks {sv * 1000:.1f} mm into it ({sc} t={st:.2f}) "
                   f"[report only]")
        r.note(f"{label}: {name} held: closest skin {worst['gap'][0] * 1000:.1f} mm, deepest {dv * 1000:.1f} mm, "
               f"shoulder forward up to {worst['shift'] * 1000:.0f} mm")


def _ads(g, r, label, optic):
    """Aim down `optic` (rest pose, eye point = mount_optic x aim on the camera)."""
    inv = np.linalg.inv(lo.aim_matrix(g, optic))
    build = {"slots": {"optic": optic}, "mag": "mag_standard"}
    W = {k: inv @ v for k, v in g.world_matrices({}).items()}
    hidden = lo.hidden_nodes(g, build)
    near = (np.inf, "")
    for i, n in enumerate(g.nodes):
        if "mesh" in n and i not in hidden:
            pts = _points(g, W, {i})
            front = pts[pts[:, 2] < 0]
            if len(front):
                d = float(np.linalg.norm(front, axis=1).min())
                if d < near[0]:
                    near = (d, n["name"])
    a = lo.model(optic)
    AW = {k: W[g.names[lo.SLOTS["optic"]]] @ v for k, v in a.world_matrices({}).items()}
    pts = _points(a, AW)
    front = pts[pts[:, 2] < 0]
    if len(front) and float(np.linalg.norm(front, axis=1).min()) < near[0]:
        near = (float(np.linalg.norm(front, axis=1).min()), optic)
    r.check(near[0] >= ADS_CLEAR, f"{label}: ADS {optic}: {near[1]} {near[0]:.3f} m from the eye (needs >= {ADS_CLEAR})")
    if optic in lo.SCOPES:
        _ads_scope(g, r, label, optic, a, AW, W, hidden, near)
        return
    # the aiming point: the reticle quad, or (reticle drawn in the glass) the window's centre
    reticle = AW[a.names["reticle"]][:3, 3] if "reticle" in a.names else _glass(a, AW)[0].mean(0)
    what = "reticle" if "reticle" in a.names else "window centre"
    focal = 540.0 / np.tan(np.radians(FOV_Y / 2))
    px = focal * reticle[:2] / -reticle[2]
    r.check(reticle[2] < 0 and bool(np.all(np.abs(px) <= ADS_PX)), f"{label}: ADS {optic}: {what} {np.round(px, 2)} px "
                                                                       f"off centre")
    # window: the optic's glass outline on screen; the weapon (without the optic itself) must leave its middle clear
    glass = [p for n in a.nodes if "mesh" in n for p in a.mesh_primitives(n["mesh"])
             if a.json["materials"][p["material"]]["name"] == "glass"]
    M = AW[a.names["attachment"]]
    gp = np.concatenate([p["positions"] for p in glass]) @ M[:3, :3].T + M[:3, 3]
    h = 360
    w = 640
    f = (h / 2) / np.tan(np.radians(FOV_Y / 2))
    gpx = f * gp[:, :2] / -gp[:, 2:3]
    # the window's inscribed radius: a wide, low window (the rectangular red dot) is only as clear as it is high
    radius = float(min(np.abs(gpx[:, 0]).max(), np.abs(gpx[:, 1]).max(), np.median(np.linalg.norm(gpx, axis=1))))
    items = lo.scene(g, None, None, 0.0, build, extra_matrix=inv, with_arms=False, draw_slots=())
    buf, _ = rs.rasterize(items, rs.Camera(w, h, FOV_Y, near=NEAR), w, h)
    hit = (buf["mid"] >= 0).reshape(h, w)
    ys, xs = np.mgrid[0:h, 0:w]
    rr = np.hypot(xs + 0.5 - w / 2, ys + 0.5 - h / 2)
    zone, window = rr <= CLEAR_ZONE * radius, rr <= radius
    zone_cover = float(hit[zone].mean()) if zone.any() else 0.0
    window_cover = float(hit[window].mean()) if window.any() else 0.0
    r.check(zone_cover <= 0.01, f"{label}: ADS {optic}: the weapon covers {zone_cover:.0%} of the window around the "
                                f"{what}")
    r.note(f"ADS {optic}: nearest part {near[1]} {near[0]:.3f} m; {what} ({px[0]:+.2f}, {px[1]:+.2f}) px; window "
           f"radius {radius / h * 1080:.0f} px @1080p, weapon in the window {window_cover:.0%} (centre zone "
           f"{zone_cover:.0%})")


def _ads_scope(g, r, label, optic, a, AW, W, hidden, near):
    """Section 22 at ADS (eye at the origin looking down -Z): the eyepiece centred on the screen, nothing of the
    weapon between the eye and the eyepiece, and nothing in front of the objective (its picture is the world's)."""
    focal = 540.0 / np.tan(np.radians(FOV_Y / 2))

    def disc(node):
        M = AW[a.names[node]]
        pts = np.concatenate([p["positions"] for p in a.mesh_primitives(a.nodes[a.names[node]]["mesh"])])
        pts = pts @ M[:3, :3].T + M[:3, 3]
        return float(np.linalg.norm(pts - M[:3, 3], axis=1).max()), M[:3, 3]

    r_ocu, c_ocu = disc("lens_ocular")
    r_obj, c_obj = disc("lens_objective")
    px = focal * c_ocu[:2] / -c_ocu[2]
    r.check(c_ocu[2] < 0 and bool(np.all(np.abs(px) <= ADS_PX)),
            f"{label}: ADS {optic}: eyepiece {np.round(px, 2)} px off centre")
    wp = np.concatenate([_points(g, W, {i}) for i, n in enumerate(g.nodes) if "mesh" in n and i not in hidden])
    z = -wp[:, 2]
    in_cone = (z > 0) & (z < -c_ocu[2]) & (np.hypot(wp[:, 0], wp[:, 1]) < r_ocu * z / -c_ocu[2])
    r.check(not in_cone.any(), f"{label}: ADS {optic}: {int(in_cone.sum())} weapon points between the eye and the "
                               f"eyepiece")
    axis = (c_obj - c_ocu) / np.linalg.norm(c_obj - c_ocu)
    rel = wp - c_obj
    along = rel @ axis
    radial = np.linalg.norm(rel - np.outer(along, axis), axis=1)
    block = (along > 0) & (along < OBJECTIVE_CLEAR) & (radial < 0.9 * r_obj)
    r.check(not block.any(), f"{label}: ADS {optic}: {int(block.sum())} weapon points in front of the objective")
    radius_px = focal * r_ocu / -c_ocu[2]
    r.note(f"ADS {optic}: nearest part {near[1]} {near[0]:.3f} m; eyepiece ({px[0]:+.2f}, {px[1]:+.2f}) px, "
           f"{radius_px:.0f} px radius @1080p, {-c_ocu[2]:.3f} m from the eye; objective clear for "
           f"{OBJECTIVE_CLEAR} m")
