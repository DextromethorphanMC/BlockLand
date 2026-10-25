"""Ammo crate GLB checks (VIEWMODEL_SPEC section 46), used by check_glb.py (CHECK_MODULES; claims ammo_crate.glb).

Nodes and pivots, materials with their regions and texture sets, the open / close clips (lengths, animated nodes,
events, the lid shut at rest and both ends, open at the open clip's end), the crate inside its block open and shut, the
lid clear of the contents when shut and resting on the walls, the collision box of AmmoCrateBlock against the model,
the budget, and the first-person resupply data (assets/blockland/supply/resupply.json) and its sounds."""
import json
import os
import re

import numpy as np

import ammo_crate_model as cm
from check_glb import check_container, check_curve_rate, check_meshes
from glbread import GLTF
from model_quality import BUDGETS

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")
JAVA = os.path.join(HERE, "..", "..", "mod", "src", "main", "java", "com", "blockland")
SOUNDS_JSON = os.path.join(RES, "assets", "blockland", "sounds.json")
RESUPPLY = os.path.join(RES, "assets", "blockland", "supply", "resupply.json")
BLOCK_JAVA = os.path.join(JAVA, "supply", "AmmoCrateBlock.java")
PARENTS = {"crate": None, "body": "crate", "contents": "crate", "lid": "crate", "latch_L": "crate", "latch_R": "crate"}
MESH_NODES = {"body", "contents", "lid", "latch_L", "latch_R"}
MOVING = {"lid", "latch_L", "latch_R"}
MATERIALS = {"wood": "body", "marking": "body", "metal": "hardware", "rope": "body", "can": "contents",
             "mag": "contents", "brass": "contents"}
CLIPS = {"open": (0.5, 0.7), "close": (0.35, 0.55)}
EVENTS = {"open": {"latch", "lid_open"}, "close": {"lid_shut", "latch"}}
MAX_TRIS = BUDGETS["equipment"]


def claims(path, names):
    return os.path.splitext(os.path.basename(path))[0] == "ammo_crate"


def _angle_x(q):
    """Rotation about X (degrees) of a quaternion that turns about X only."""
    return float(np.degrees(2 * np.arctan2(q[0], q[3])))


def _points(g, W, names):
    out = []
    for nm in names:
        i = g.names[nm]
        pts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[i]["mesh"])])
        out.append(pts @ W[i][:3, :3].T + W[i][:3, 3])
    return np.concatenate(out)


def check_nodes(g, r, label):
    for nm, parent in PARENTS.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        par = g.parent.get(g.names[nm])
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"{label}: node {nm} parent is {pname}, expected {parent}")
        r.check(("mesh" in g.nodes[g.names[nm]]) == (nm in MESH_NODES), f"{label}: {nm} mesh presence")
    for nm, pivot in cm.PIVOTS.items():
        t = np.asarray(g.nodes[g.names[nm]].get("translation", [0, 0, 0]))
        r.check(np.allclose(t, pivot, atol=1e-6), f"{label}: {nm} pivot {np.round(t, 4)}, expected {np.round(pivot, 4)}")
    root = g.nodes[g.names["crate"]]
    r.check(not any(k in root for k in ("translation", "rotation", "scale")), f"{label}: crate root not identity")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(MATERIALS), f"{label}: materials {sorted(mats)}, expected {sorted(MATERIALS)}")
    for nm, region in MATERIALS.items():
        if nm in mats:
            ex = mats[nm].get("extras") or {}
            r.check(ex.get("region") == region, f"{label}: material {nm} region {ex.get('region')}, expected {region}")
            r.check(bool(ex.get("pbr")), f"{label}: material {nm} has no texture set")


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    for nm, (lo, hi) in CLIPS.items():
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo <= dur <= hi, f"{label}: {nm} length {dur:.3f} outside [{lo}, {hi}]")
        for ch in a["channels"]:
            node = g.nodes[ch["target"]["node"]]["name"]
            r.check(node in MOVING and ch["target"]["path"] == "rotation", f"{label}: {nm} animates {node}.{ch['target']['path']}")
        ev = {e["name"] for e in (a.get("extras") or {}).get("events", [])}
        r.check(EVENTS[nm] <= ev, f"{label}: {nm} missing events {EVENTS[nm] - ev}")
        s0, s1 = g.sample(a, 0.0), g.sample(a, dur)
        lid = g.names["lid"]
        a0, a1 = _angle_x(s0[lid]["rotation"]), _angle_x(s1[lid]["rotation"])
        if nm == "open":
            r.check(abs(a0) < 0.5 and abs(a1 - cm.OPEN_DEG) < 1.0, f"{label}: open: lid {a0:.1f} -> {a1:.1f} deg")
            for latch in ("latch_L", "latch_R"):
                end = _angle_x(s1[g.names[latch]]["rotation"])
                r.check(abs(end - cm.LATCH_OPEN_DEG) < 1.0, f"{label}: open: {latch} ends at {end:.1f} deg")
        else:
            r.check(abs(a0 - cm.OPEN_DEG) < 1.0 and abs(a1) < 0.5, f"{label}: close: lid {a0:.1f} -> {a1:.1f} deg")
            for latch in ("latch_L", "latch_R"):
                end = _angle_x(s1[g.names[latch]]["rotation"])
                r.check(abs(end) < 0.5, f"{label}: close: {latch} ends at {end:.1f} deg")
        r.note(f"{nm}: {dur:.2f}s, lid {a0:.0f} -> {a1:.0f} deg, events {sorted(ev)}")
    return anims


def check_space(g, r, label, anims):
    """Inside the block in every frame; the shut lid rests on the walls and clears the contents."""
    worst = 0.0
    for nm, a in [("rest", None)] + list(anims.items()):
        dur = g.duration(a) if a is not None else 0.0
        for t in np.arange(0, dur + 1e-9, 1 / 30):
            W = g.world_matrices(g.sample(a, t) if a is not None else {})
            p = _points(g, W, MESH_NODES)
            out = max(np.abs(p[:, 0]).max() - 0.5, np.abs(p[:, 2]).max() - 0.5, -p[:, 1].min(), p[:, 1].max() - 1.0)
            worst = max(worst, out)
    r.check(worst <= 0.0, f"{label}: the crate leaves its block by {worst * 1000:.1f} mm")
    W = g.world_matrices({})
    lid = _points(g, W, ["lid"])
    contents = _points(g, W, ["contents"])
    body = _points(g, W, ["body"])
    r.check(abs(lid[:, 1].min() - cm.BODY_TOP) < 0.001, f"{label}: shut lid bottom at {lid[:, 1].min():.4f}, walls at {cm.BODY_TOP}")
    r.check(contents[:, 1].max() < cm.BODY_TOP - 0.003, f"{label}: contents reach {contents[:, 1].max():.3f} (lid at {cm.BODY_TOP})")
    r.note(f"inside its block open and shut; contents top {contents[:, 1].max():.3f} m under the lid at {cm.BODY_TOP} m")
    return np.concatenate([lid, body])


def check_shape(r, label, pts):
    """AmmoCrateBlock's collision box (front north) against the shut model's extent, within a pixel."""
    with open(BLOCK_JAVA, encoding="utf-8") as f:
        java = f.read()
    m = re.search(r"ALONG_X = Shapes\.or\(\s*Block\.box\(([^)]*)\)", java)
    if not r.check(m is not None, f"{label}: AmmoCrateBlock.ALONG_X not found"):
        return
    x0, y0, z0, x1, y1, z1 = (float(v) / 16 for v in m.group(1).split(","))
    lo, hi = pts.min(0) + 0.5, pts.max(0) + 0.5           # block space (0..1), skids on the floor
    hi_y, lo_y = pts[:, 1].max(), pts[:, 1].min()
    box = np.array([x0, z0, x1, z1])
    model = np.array([lo[0], lo[2], hi[0], hi[2]])
    r.check(np.all(np.abs(box - model) <= 1 / 16 + 1e-6) and abs(y1 - hi_y) <= 1 / 16 and abs(y0 - lo_y) <= 1e-6,
            f"{label}: collision box {np.round(box * 16, 2)} y {y0 * 16:.1f}..{y1 * 16:.1f} px vs model "
            f"{np.round(model * 16, 2)} y {lo_y * 16:.1f}..{hi_y * 16:.1f} px")
    r.note(f"collision box within a pixel of the shut crate ({np.round(box * 16, 1)} px, {y1 * 16:.1f} px high)")


def check_resupply(r, label):
    with open(RESUPPLY, encoding="utf-8") as f:
        mo = json.load(f)
    with open(SOUNDS_JSON, encoding="utf-8") as f:
        sounds = json.load(f)
    for timeline in ("reach", "stow"):
        dur = mo[timeline]
        for part, width in (("gun", 7), ("arm", 3)):
            keys = mo[part][timeline]
            ts = [k[0] for k in keys]
            r.check(all(len(k) == width for k in keys), f"{label}: resupply {part}.{timeline} keys need {width} values")
            r.check(ts[0] == 0 and all(b > a for a, b in zip(ts, ts[1:])) and abs(ts[-1] - dur) < 1e-6,
                    f"{label}: resupply {part}.{timeline} key times {ts} (0 .. {dur})")
            r.check(all(v == 0 for v in keys[0][1:]) and all(v == 0 for v in keys[-1][1:]),
                    f"{label}: resupply {part}.{timeline} must start and end at rest")
            if part == "arm":
                r.check(all(0 <= v <= 1 for k in keys for v in k[1:]), f"{label}: resupply arm weights outside 0..1")
        a, b = mo["magazine"][timeline]
        r.check(0 <= a < b <= dur + 0.05, f"{label}: resupply magazine {timeline} span {a}..{b}")
    for t, name in mo.get("events", []):
        r.check(0 <= t <= mo["reach"], f"{label}: resupply event {name} at {t}")
        r.check(name in sounds, f"{label}: resupply event {name} has no sound event in sounds.json")
    for ev in ("supply.crate_open", "supply.crate_close", "supply.rummage", "supply.magazine"):
        r.check(ev in sounds, f"{label}: sound event {ev} missing in sounds.json")
    r.note(f"resupply: reach {mo['reach']} s, stow {mo['stow']} s, keys and sounds consistent")


def check(path, arms, r):
    g = GLTF(path)
    label = "ammo_crate"
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    anims = check_clips(g, r, label)
    check_curve_rate(g, r, label)
    pts = check_space(g, r, label, anims)
    check_shape(r, label, pts)
    check_resupply(r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"ammo crate triangles: {tris}")
