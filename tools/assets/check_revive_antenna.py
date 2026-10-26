"""Revive antenna GLB checks (VIEWMODEL_SPEC section 51), used by check_glb.py (CHECK_MODULES; claims
revive_antenna.glb).

Nodes and pivots (the lit and dark beacon at the same place), materials with their regions, texture sets and glow, the
antenna inside its two blocks, ReviveAntennaBlock's boxes against the model (the case, the mast, the head with its dish
and beacon, each within a pixel; the whip is left out), the beacon's centre in client/revive/Beacon.java, the budget,
and the block's assets (blockstate of both halves, item definition, loot table dropping from the lower half only, the
mineable tag, both languages)."""
import json
import os
import re

import numpy as np

import revive_antenna_model as am
from check_glb import check_container, check_meshes
from glbread import GLTF
from model_quality import BUDGETS
from pbr_models_revive import models

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")
JAVA = os.path.join(HERE, "..", "..", "mod", "src", "main", "java", "com", "blockland")
BLOCK_JAVA = os.path.join(JAVA, "revive", "ReviveAntennaBlock.java")
BEACON_JAVA = os.path.join(JAVA, "client", "revive", "Beacon.java")
ID = "revive_antenna"
PARENTS = {"antenna": None, "base": "antenna", "mast": "antenna", "beacon": "antenna", "beacon_off": "antenna"}
MESH_NODES = {"base", "mast", "beacon", "beacon_off"}
TABLE = models()[f"block/{ID}"]["materials"]
GLOWING = {"screen", "beacon"}
MAX_TRIS = BUDGETS["equipment"]


def claims(path, names):
    return os.path.splitext(os.path.basename(path))[0] == ID


def _points(g, W, names, skip_materials=()):
    mats = g.json.get("materials", [])
    out = []
    for nm in names:
        i = g.names[nm]
        for p in g.mesh_primitives(g.nodes[i]["mesh"]):
            if mats[p["material"]].get("name") in skip_materials:
                continue
            out.append(p["positions"] @ W[i][:3, :3].T + W[i][:3, 3])
    return np.concatenate(out)


def check_nodes(g, r, label):
    for nm, parent in PARENTS.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        par = g.parent.get(g.names[nm])
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"{label}: node {nm} parent is {pname}, expected {parent}")
        r.check(("mesh" in g.nodes[g.names[nm]]) == (nm in MESH_NODES), f"{label}: {nm} mesh presence")
    for nm, pivot in am.PIVOTS.items():
        t = np.asarray(g.nodes[g.names[nm]].get("translation", [0, 0, 0]))
        r.check(np.allclose(t, pivot, atol=1e-6), f"{label}: {nm} pivot {np.round(t, 4)}, expected {np.round(pivot, 4)}")
    root = g.nodes[g.names["antenna"]]
    r.check(not any(k in root for k in ("translation", "rotation", "scale")), f"{label}: antenna root not identity")
    r.check(not g.json.get("animations"), f"{label}: the antenna has no clips")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(TABLE), f"{label}: materials {sorted(mats)}, expected {sorted(TABLE)}")
    for nm, (set_name, region) in TABLE.items():
        if nm not in mats:
            continue
        ex = mats[nm].get("extras") or {}
        r.check(ex.get("region") == region, f"{label}: material {nm} region {ex.get('region')}, expected {region}")
        r.check(bool(ex.get("pbr")) == bool(set_name), f"{label}: material {nm} texture set {ex.get('pbr')}, expected {set_name}")
        glows = any(v > 0 for v in mats[nm].get("emissiveFactor", [0, 0, 0]))
        r.check(glows == (nm in GLOWING), f"{label}: material {nm} {'glows' if glows else 'does not glow'}")


def check_space(g, r, label):
    W = g.world_matrices({})
    p = _points(g, W, MESH_NODES)
    out = max(np.abs(p[:, 0]).max() - 0.5, np.abs(p[:, 2]).max() - 0.5, -p[:, 1].min(), p[:, 1].max() - 2.0)
    r.check(out <= 0.0, f"{label}: the antenna leaves its two blocks by {out * 1000:.1f} mm")
    lit, dark = (_points(g, W, [n]) for n in ("beacon", "beacon_off"))
    r.check(lit.shape == dark.shape and np.allclose(lit, dark, atol=1e-6), f"{label}: the lit and dark beacons differ in shape")
    r.note(f"inside its two blocks: x {p[:, 0].min():.3f}..{p[:, 0].max():.3f}, z {p[:, 2].min():.3f}..{p[:, 2].max():.3f}, "
           f"top {p[:, 1].max():.3f} m")
    return W


def _boxes(java, name):
    m = re.search(name + r"\s*=\s*\{(.*?)\};", java, re.S)
    if m is None:
        return None
    return [np.array([float(v) for v in b.split(",")]) / 16 for b in re.findall(r"\{([^{}]*)\}", m.group(1))]


def check_shapes(g, r, label, W):
    """ReviveAntennaBlock's boxes (front north, pixels) against the model's parts, within a pixel per side."""
    with open(BLOCK_JAVA, encoding="utf-8") as f:
        java = f.read()
    lower, upper = _boxes(java, "LOWER_NORTH"), _boxes(java, "UPPER_NORTH")
    if not r.check(lower is not None and upper is not None and len(lower) == 2 and len(upper) == 2,
                   f"{label}: ReviveAntennaBlock LOWER_NORTH / UPPER_NORTH (two boxes each) not found"):
        return
    base = _points(g, W, ["base"])
    case = base[base[:, 1] < am.LID_TOP - 0.004]
    mast = _points(g, W, ["mast"], skip_materials=("whip",))
    head = np.concatenate([mast[mast[:, 1] > am.HEAD[1] - 0.06], _points(g, W, ["beacon"])])
    stage = mast[(mast[:, 1] > am.LID_TOP + 0.08) & (mast[:, 1] < am.HEAD[1] - 0.06)]
    px = 1 / 16 + 1e-6

    def fits(box, pts, what, y0=0.0):
        lo = pts.min(0) + np.array([0.5, -y0, 0.5])
        hi = pts.max(0) + np.array([0.5, -y0, 0.5])
        b0, b1 = box[:3], box[3:]
        ok = np.all(np.abs(b0 - np.clip(lo, 0, None)) <= px) and np.all(np.abs(b1 - np.minimum(hi, 1.0)) <= px)
        r.check(ok, f"{label}: {what} box {np.round(box * 16, 2)} px vs model {np.round(np.concatenate([lo, hi]) * 16, 2)} px")

    fits(lower[0], case, "lower case")
    lower_stage = stage[stage[:, 1] < 1.0]
    r.check(np.all(lower_stage[:, [0, 2]] + 0.5 >= lower[1][[0, 2]] - px) and np.all(lower_stage[:, [0, 2]] + 0.5 <= lower[1][[3, 5]] + px),
            f"{label}: the mast leaves the lower mast box {np.round(lower[1] * 16, 2)}")
    upper_stage = stage[stage[:, 1] >= 1.0]
    r.check(np.all(upper_stage[:, [0, 2]] + 0.5 >= upper[0][[0, 2]] - px) and np.all(upper_stage[:, [0, 2]] + 0.5 <= upper[0][[3, 5]] + px),
            f"{label}: the mast leaves the upper mast box {np.round(upper[0] * 16, 2)}")
    fits(upper[1], head, "upper head (dish, beacon)", y0=1.0)
    r.note(f"block boxes within a pixel of the case, the mast and the head (whip left out)")


def check_beacon(r, label):
    with open(BEACON_JAVA, encoding="utf-8") as f:
        java = f.read()
    m = re.search(r"X\s*=\s*([-0-9.]+)f,\s*Y\s*=\s*([-0-9.]+)f,\s*Z\s*=\s*([-0-9.]+)f", java)
    if not r.check(m is not None, f"{label}: Beacon.X / Y / Z not found"):
        return
    java_c = np.array([float(v) for v in m.groups()])
    r.check(np.allclose(java_c, am.BEACON_CENTRE, atol=0.002), f"{label}: Beacon centre {java_c} vs model {np.round(am.BEACON_CENTRE, 4)}")


def check_assets(r, label):
    def load(*parts):
        path = os.path.join(RES, *parts)
        if not r.check(os.path.exists(path), f"{label}: missing {os.path.join(*parts)}"):
            return None
        with open(path, encoding="utf-8") as f:
            return json.load(f)

    states = load("assets", "blockland", "blockstates", f"{ID}.json")
    if states:
        want = {f"facing={f},half={h}" for f in ("north", "east", "south", "west") for h in ("lower", "upper")}
        r.check(set(states["variants"]) == want, f"{label}: blockstate variants {sorted(states['variants'])}")
    load("assets", "blockland", "items", f"{ID}.json")
    load("assets", "blockland", "models", "item", f"{ID}.json")
    loot = load("data", "blockland", "loot_table", "blocks", f"{ID}.json")
    if loot:
        entry = loot["pools"][0]["entries"][0]
        cond = (entry.get("conditions") or [{}])[0]
        r.check(cond.get("condition") == "minecraft:block_state_property" and cond.get("properties") == {"half": "lower"},
                f"{label}: the loot table must drop from the lower half only")
    tag = load("data", "minecraft", "tags", "block", "mineable", "pickaxe.json")
    if tag:
        r.check(f"blockland:{ID}" in tag["values"], f"{label}: not in minecraft:mineable/pickaxe")
    for lang in ("zh_cn", "en_us"):
        names = load("assets", "blockland", "lang", f"{lang}.json") or {}
        for key in (f"block.blockland.{ID}", "itemGroup.blockland.revive"):
            r.check(key in names, f"{label}: {lang} lacks {key}")


def check(path, arms, r):
    g = GLTF(path)
    label = ID
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    W = check_space(g, r, label)
    check_shapes(g, r, label, W)
    check_beacon(r, label)
    check_assets(r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"revive antenna triangles: {tris}")
