"""Equipment GLB checks (VIEWMODEL_SPEC sections 11-12, 15-17, 19-21, 31 and 36), used by check_glb.py (CHECK_MODULES).

Nodes and parents, materials and their regions, the clip set (lengths, loops, camera rules, STEP scales, events), the
gameplay timing of equipment.json (the clips' release / inject / pull / use / draw times), the item in the hand until
its release and gone after it, the pin on the left index finger once pulled, the pin and the cooked spoon gone in the
hold, the molotov's lighter flame at the rag when it catches, eye distance, curve rate, the charm mount, the triangle
budget, and the hip pose leaving the crosshair area clear."""
import json
import os

import numpy as np

from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (FOV_Y, check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       euler_deg)
from glbread import GLTF

HERE = os.path.dirname(os.path.abspath(__file__))
EQUIPMENT_JSON = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "equipment",
                              "equipment.json")
GRENADES = ("frag_grenade", "flashbang", "smoke_grenade")
COOKS = ("frag_grenade", "flashbang")
LOCO = {"idle": (2.0, 2.0), "walk": (0.6, 1.6), "sprint": (0.66, 0.66)}
CLIPS = {
    "grenade": dict(LOCO, draw=(0.3, 0.7), inspect=(2.0, 4.0), pull=(0.3, 0.9), hold=(0.8, 2.0), throw=(0.4, 0.8),
                    lob=(0.4, 0.8)),
    "molotov": dict(LOCO, draw=(0.3, 0.7), inspect=(2.0, 4.0), pull=(0.4, 1.0), hold=(0.8, 2.0), throw=(0.4, 0.8),
                    lob=(0.4, 0.8)),
    "knife": dict(LOCO, draw=(0.2, 0.6), inspect=(2.0, 4.0), throw=(0.3, 0.7)),
    "adrenaline": dict(LOCO, draw=(0.3, 0.7), inspect=(2.0, 4.0), use=(1.0, 2.0)),
}
LOOPING = {"idle", "walk", "sprint", "hold"}
NO_CAMERA = {"idle", "walk", "sprint", "hold"}
EVENTS = {"equip", "pin", "spoon", "light", "release", "cap", "stab", "inject", "discard", "flip", "catch"}
PARTS = {
    "frag_grenade": {"body": "equipment", "lever": "equipment", "pin": "equipment"},
    "molotov": {"glass": "equipment", "liquid": "equipment", "rag": "equipment", "flame": "equipment",
                "lighter": "viewmodel", "lighter_lid": "lighter", "lighter_flame": "lighter"},
    "throwing_knife": {"blade": "equipment", "grip": "equipment", "tip": "equipment"},
    "adrenaline": {"barrel": "equipment", "plunger": "equipment", "cap": "equipment", "tip": "equipment"},
}
PARTS["flashbang"] = PARTS["smoke_grenade"] = PARTS["frag_grenade"]
_G = {"body": "body", "fuze": "body", "lever": "lever", "pin": "pin"}
MATERIALS = {
    "frag_grenade": _G,
    "flashbang": dict(_G, label="label", vent="body"),
    "smoke_grenade": dict(_G, label="label", vent="body"),
    "molotov": {"glass": "glass", "liquid": "liquid", "rag": "rag", "flame": "none", "lighter": "none"},
    "throwing_knife": {"blade": "blade", "blade_edge": "blade", "grip": "grip"},
    "adrenaline": {"barrel": "barrel", "label": "label", "plunger": "plunger", "cap": "cap"},
}
NO_SET = {"vent", "glass", "liquid", "flame"}            # keep their glTF look (dark recesses, see-through, self-lit)
MAX_TRIS = 4000
CROSSHAIR = 0.15


def claims(path, names):
    return "equipment" in names


def family(key):
    return "grenade" if key in GRENADES else "molotov" if key == "molotov" else \
        "knife" if key == "throwing_knife" else "adrenaline"


def data(key):
    with open(EQUIPMENT_JSON, encoding="utf-8") as f:
        return json.load(f)["equipment"][key]


def required_events(key):
    fam = family(key)
    need = {"draw": {"equip"}}
    if fam in ("grenade", "molotov"):
        need.update(throw={"release"}, lob={"release"})
    if fam == "grenade":
        need["pull"] = {"pin", "spoon"} if key in COOKS else {"pin"}
        if key not in COOKS:
            need["throw"] = {"release", "spoon"}
            need["lob"] = {"release", "spoon"}
    if fam == "molotov":
        need["pull"] = {"light"}
    if fam == "knife":
        need.update(throw={"release"}, inspect={"flip", "catch"})
    if fam == "adrenaline":
        need["use"] = {"cap", "stab", "inject", "discard"}
    return need


def check_nodes(g, r, label, key):
    want = {"viewmodel": None, "equipment": "viewmodel", "mount_charm": "equipment", "camera": "viewmodel",
            "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L"}
    want.update(PARTS[key])
    for nm, parent in want.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        par = g.parent.get(g.names[nm])
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"{label}: node {nm} parent is {pname}, expected {parent}")
    for nm in ("equipment", "mount_charm", "camera", "viewmodel", "tip"):
        if nm in g.names:
            r.check("mesh" not in g.nodes[g.names[nm]], f"{label}: {nm} must be empty")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), f"{label}: viewmodel rest not identity")
    W = g.world_matrices({})
    down = W[g.names["mount_charm"]][:3, :3] @ np.array([0.0, -1.0, 0.0])
    r.check(down[1] < -0.999, f"{label}: mount_charm -Y does not hang straight down ({np.round(down, 3)})")
    for nm in ("flame", "lighter"):
        if nm in g.names:
            r.check(abs(np.linalg.det(W[g.names[nm]][:3, :3])) < 1e-12, f"{label}: {nm} must be hidden (scale 0) at rest")


def check_materials(g, r, label, key):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(MATERIALS[key]), f"{label}: materials {sorted(mats)}, expected {sorted(MATERIALS[key])}")
    for nm, region in MATERIALS[key].items():
        if nm not in mats:
            continue
        ex = mats[nm].get("extras") or {}
        r.check(ex.get("region") == region, f"{label}: material {nm} region {ex.get('region')}, expected {region}")
        if nm not in NO_SET:
            r.check(bool(ex.get("pbr")), f"{label}: material {nm} has no texture set")


def check_clips(g, r, label, key):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    names = {n: i for i, n in enumerate(n.get("name") for n in g.nodes)}
    vm, cam, arm_l = g.names["viewmodel"], g.names.get("camera"), g.names["arm_L"]
    need = required_events(key)
    for nm, (lo, hi) in CLIPS[family(key)].items():
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-3 <= dur <= hi + 1e-3, f"{label}: {nm} length {dur:.3f} outside [{lo}, {hi}]")
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            s = a["samplers"][ch["sampler"]]
            ts = g.accessor(s["input"])[:, 0]
            vs = g.accessor(s["output"]).astype(np.float64)
            r.check(node != vm, f"{label}: {nm} animates viewmodel")
            r.check(abs(ts[0]) < 1e-6 and np.all(np.diff(ts) > 0), f"{label}: {nm} bad key times")
            if path == "scale":
                r.check(s.get("interpolation") == "STEP", f"{label}: {nm} {g.nodes[node]['name']} scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"{label}: {nm} scale not 0/1")
            if path == "rotation":
                r.check(np.all(np.abs(np.linalg.norm(vs, axis=1) - 1) < 1e-4), f"{label}: {nm} quaternions")
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"{label}: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if node == cam:
                r.check(nm not in NO_CAMERA, f"{label}: {nm} must not animate the camera (section 17)")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera beyond the section 4 limits")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENTS, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            r.check(need.get(nm, set()) <= have, f"{label}: {nm} missing events {need.get(nm, set()) - have}")
            r.note(f"{nm}: {dur:.3f}s" + ("; events " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev)
                                          if ev else ""))
    return anims


def _event(anims, clip, name):
    for e in anims.get(clip, {}).get("extras", {}).get("events", []):
        if e["name"] == name:
            return float(e["time"])
    return None


def check_timing(g, r, label, key, anims):
    """The gameplay numbers of equipment.json are the clips' as-built times."""
    d = data(key)
    dur = {n: g.duration(a) for n, a in anims.items()}
    fam = family(key)
    r.check(abs(d.get("drawTime", 0) - dur.get("draw", 0)) < 0.011,
            f"{label}: drawTime {d.get('drawTime')} != draw clip {dur.get('draw', 0):.3f}")
    if fam in ("grenade", "molotov", "knife"):
        rel = d.get("throw", {}).get("releaseAt")
        for clip in ("throw", "lob") if fam != "knife" else ("throw",):
            t = _event(anims, clip, "release")
            r.check(t is not None and rel is not None and abs(t - rel) < 0.006,
                    f"{label}: {clip} release at {t}, equipment.json releaseAt {rel}")
    if fam in ("grenade", "molotov"):
        r.check(abs(d.get("pullTime", 0) - dur.get("pull", 0)) < 0.011,
                f"{label}: pullTime {d.get('pullTime')} != pull clip {dur.get('pull', 0):.3f}")
    if fam == "adrenaline":
        r.check(abs(d.get("useTime", 0) - dur.get("use", 0)) < 0.011,
                f"{label}: useTime {d.get('useTime')} != use clip {dur.get('use', 0):.3f}")
        t = _event(anims, "use", "inject")
        r.check(t is not None and abs(t - d.get("injectAt", -1)) < 0.006,
                f"{label}: inject at {t}, equipment.json injectAt {d.get('injectAt')}")


def _visible(W, i):
    return abs(np.linalg.det(W[i][:3, :3])) > 1e-9


def check_hands(g, r, label, key, anims):
    eq, hand_r = g.names["equipment"], g.names["arm_R_hand"]
    for clip in ("throw", "lob", "use"):
        if clip not in anims:
            continue
        a = anims[clip]
        t_rel = _event(anims, clip, "release" if clip != "use" else "discard")
        worst = 0.0
        for t in np.arange(0.0, t_rel - 1e-3, 1 / 120):
            W = g.world_matrices(g.sample(a, t))
            worst = max(worst, float(np.linalg.norm(W[eq][:3, 3] - W[hand_r][:3, 3])))
        r.check(worst < 0.11, f"{label}: {clip}: the item leaves the hand before its release ({worst:.3f} m)")
        W = g.world_matrices(g.sample(a, min(g.duration(a), t_rel + 0.15)))
        r.check(not _visible(W, eq), f"{label}: {clip}: the item is still drawn 0.15 s after its release")
    if family(key) == "grenade":
        a = anims["pull"]
        t_pin = _event(anims, "pull", "pin")
        pin, idx = g.names["pin"], g.names["arm_L_index2"]
        d = []
        for t in np.arange(t_pin, t_pin + 0.12, 1 / 120):
            W = g.world_matrices(g.sample(a, t))
            d.append(float(np.linalg.norm(W[pin][:3, 3] - W[idx][:3, 3])))
        r.check(max(d) < 0.045, f"{label}: pull: the pin is {max(d):.3f} m from the left index finger after it came out")
        r.note(f"pull: pin within {max(d) * 1000:.0f} mm of the left index finger once out")
        W = g.world_matrices(g.sample(anims["hold"], 0.3))
        r.check(not _visible(W, pin), f"{label}: hold: the pin is still on the grenade")
        if key in COOKS:
            r.check(not _visible(W, g.names["lever"]), f"{label}: hold: the cooked grenade still has its spoon")
    if key == "molotov":
        a = anims["pull"]
        t = _event(anims, "pull", "light")
        Wc = g.world_matrices(g.sample(a, 0.42))
        lf, rag = g.names["lighter_flame"], g.names["rag"]
        r.check(_visible(Wc, lf) and _visible(Wc, g.names["flame"]), f"{label}: pull: lighter flame / rag flame not lit at 0.42")
        rag_end = Wc[rag][:3, :3] @ np.array([0.0006, 0.104, 0.0220]) + Wc[rag][:3, 3]
        tip = Wc[lf][:3, :3] @ np.array([0.0, 0.026, 0.0]) + Wc[lf][:3, 3]
        r.check(np.linalg.norm(rag_end - tip) < 0.03, f"{label}: pull: the lighter flame is "
                                                       f"{np.linalg.norm(rag_end - tip):.3f} m from the rag")
        r.note(f"pull: lighter flame {np.linalg.norm(rag_end - tip) * 1000:.0f} mm from the rag's end (event light@{t})")


def check_crosshair(g, r, label, anims):
    k = 2 * CROSSHAIR * np.tan(np.radians(FOV_Y / 2))
    eq = g.names["equipment"]
    parts = []
    for i, n in enumerate(g.nodes):
        if "mesh" in n:
            j = i
            while j is not None and j != eq:
                j = g.parent.get(j)
            if j == eq:
                parts.append((i, np.concatenate([p["positions"] for p in g.mesh_primitives(n["mesh"])])))
    for nm in ("rest", "idle", "walk"):
        a = anims.get(nm)
        dur = g.duration(a) if a is not None else 0.0
        worst = 0.0
        for t in np.arange(0, dur + 1e-9, 1 / 30):
            W = g.world_matrices(g.sample(a, t) if a is not None else {})
            for i, pts in parts:
                if not _visible(W, i):
                    continue
                w = pts @ W[i][:3, :3].T + W[i][:3, 3]
                zf = np.maximum(-w[:, 2], 1e-6)
                inside = (np.abs(w[:, 0]) <= k * zf) & (np.abs(w[:, 1]) <= k * zf) & (w[:, 2] < 0)
                worst = max(worst, float(inside.mean()))
        r.check(worst == 0.0, f"{label}: {nm}: {worst:.0%} of the item in the crosshair area")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    key = label
    if key not in PARTS:
        r.check(False, f"{label}: unknown equipment file")
        return
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label, key)
    check_materials(g, r, label, key)
    anims = check_clips(g, r, label, key)
    check_timing(g, r, label, key, anims)
    check_hands(g, r, label, key, anims)
    check_crosshair(g, r, label, anims)
    check_eye_distance(g, arms, r, anims, root="equipment", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"{label} triangles: {tris}")
