"""Item assets of guns whose GLB the artist makes (VIEWMODEL_SPEC sections 1, 8, 33 and 40): for every gun in GUNS, the
skin icons rendered from models/gun/<id>.glb (right-side view, the standard magazine fitted), the icon item models, a
low-poly held model built from the GLB's own parts (the bounding box of every visible mesh node in gun space, the
largest first) with a palette of its materials per skin, and items/<id>.json.

Until a gun's GLB is there, the gun gets placeholders so its item never shows the missing texture: the icon and held
model of an existing gun of its category (pistol p9, smg tommy, rifle ak47, sniper awp, shotgun ks3). Run again once the
GLB is in place:  python -I tools/assets/build_all.py gun_items  (or gun_items:<id> for one gun)."""
import json
import os
import shutil

import numpy as np

import skin_looks
import textures as tx
import third_person
from gen_items import patch_colours, render_items_icon, write_json
from glbread import GLTF
from preview import GUN_DIR, hidden_set, load_materials

GUNS = ("uzi", "p90", "mp5", "sten", "ssg08", "calico", "scar", "m4a1", "jackhammer", "vector")
TEMPLATE = {"pistol": "p9", "smg": "tommy", "rifle": "ak47", "lmg": "rpd", "sniper": "awp", "shotgun": "ks3"}
UNIT = 0.044                      # metres per item-model unit (the AK's scale)
ORIGIN = (8.0, 8.95, 7.3)         # gun-space origin in item-model units (as the AK / RPD held models)
MAX_BOXES = 14


def _guns_json():
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "guns", "guns.json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)["guns"]


def skins_of(gun):
    return {k: v for k, v in skin_looks.weapon_skins(gun, "gun").items() if k != "default"}


# ------------------------------------------------------------------------------------------- from the GLB
def side_items(g, mats):
    gi = g.names["gun"]
    W = g.world_matrices({gi: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    hide = hidden_set(g, {"mag_extended", "fx_shell", "shell_load"})
    view = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or i in hide or n.get("name", "").startswith("arm"):
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            items.append({"pos": (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T,
                          "nrm": p["normals"] @ M[:3, :3].T @ view.T, "uv": p["uvs"], "idx": p["indices"],
                          "mat": mats[p["material"]]})
    return items


def render_icon(g, mats, size=32, angle=22.0):
    """Right-side view turned a little (muzzle up-right) so a long gun fills the icon."""
    items = side_items(g, mats)
    a = np.radians(angle)
    rot = np.array([[np.cos(a), -np.sin(a), 0.0], [np.sin(a), np.cos(a), 0.0], [0.0, 0.0, 1.0]])
    for it in items:
        z = it["pos"][:, 2].copy()
        it["pos"] = it["pos"] @ rot.T
        it["pos"][:, 2] = z
        it["nrm"] = it["nrm"] @ rot.T
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"][:, :2] -= c[:2]
        it["pos"][:, 2] -= c[2] + 1.0
    return readable(render_items_icon(items, allp.max(0) - allp.min(0), size, ss=8, margin=1))


def readable(icon):
    """A black gun reads as a black blot at 32 px: lift its dark tones (keeping their order) when the icon is dark
    overall, and light the pixels along the top of the silhouette as a rim, as hand-drawn icons do."""
    body = icon[:, :, 3] > 0
    outline = body & (icon[:, :, :3].max(2) < 0.08)
    inner = body & ~outline
    if not inner.any():
        return icon
    lum = icon[:, :, :3] @ np.array([0.2126, 0.7152, 0.0722])
    mean = float(lum[inner].mean())
    if mean < 0.3:
        k = np.log(0.3) / np.log(max(mean, 1e-3))          # a gamma that brings the mean up to 0.3
        icon[inner, :3] = np.clip(icon[inner, :3], 1e-4, 1) ** k
    # rim: the topmost inner pixel of every column, a little lighter
    for x in range(icon.shape[1]):
        ys = np.nonzero(inner[:, x])[0]
        if len(ys):
            icon[ys[0], x, :3] = np.clip(icon[ys[0], x, :3] * 1.25 + 0.06, 0, 1)
    return icon


def part_boxes(g):
    """(min, max, material index) of the visible mesh nodes in gun space, the largest first (at most MAX_BOXES)."""
    gi = g.names["gun"]
    W = g.world_matrices({gi: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    hide = hidden_set(g, {"mag_extended", "fx_shell", "shell_load"})
    boxes = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or i in hide or n.get("name", "").startswith("arm"):
            continue
        pts, area = [], {}
        for p in g.mesh_primitives(n["mesh"]):
            pos = p["positions"] @ W[i][:3, :3].T + W[i][:3, 3]
            pts.append(pos)
            area[p["material"]] = area.get(p["material"], 0) + len(p["indices"])
        if not pts:
            continue
        pos = np.concatenate(pts)
        lo, hi = pos.min(0), pos.max(0)
        boxes.append((lo, hi, max(area, key=area.get), float(np.prod(np.maximum(hi - lo, 1e-4)))))
    boxes.sort(key=lambda b: -b[3])
    return [b[:3] for b in boxes[:MAX_BOXES]]


def palette(mats):
    """16x16: a 4x4 patch per material (at most 16), a light top row and a dark bottom row."""
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(57)
    for k, m in enumerate(mats[:16]):
        c = patch_colours(m, k) * 0.95 + 0.06
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = c * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.10
        patch[3] *= 0.85
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def held_model(boxes, texture, display):
    elements = []
    for lo, hi, mat in boxes:
        k = min(mat, 15)
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        uv = [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]
        frm = [round(ORIGIN[i] + lo[i] / UNIT, 3) for i in range(3)]
        to = [round(ORIGIN[i] + max(hi[i], lo[i] + 0.5 * UNIT / 16) / UNIT, 3) for i in range(3)]
        elements.append({"from": frm, "to": to,
                         "faces": {f: {"uv": uv, "texture": "#0"} for f in ("north", "south", "east", "west", "up", "down")}})
    return {"textures": {"0": texture, "particle": texture}, "elements": elements, "display": display}


# ------------------------------------------------------------------------------------------- shared
def _display(template, path):
    with open(path("models", "item", f"{template}_held.json"), encoding="utf-8") as f:
        return json.load(f)["display"]


def _skin_select(x, skins):
    return {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": s, "model": {"type": "minecraft:model", "model": f"blockland:item/{x}_{s}"}} for s in skins],
            "fallback": {"type": "minecraft:model", "model": f"blockland:item/{x}"}}


def build_gun(gun, data, path):
    skins = list(skins_of(gun))
    template = TEMPLATE.get(data["category"], "ak47")
    glb = os.path.join(GUN_DIR, f"{gun}.glb")
    if os.path.exists(glb):
        g = GLTF(glb)
        base = load_materials(g)
        boxes = part_boxes(g)
        for skin in ["default"] + skins:
            mats = skin_looks.skin_materials(base, skins_of(gun)[skin]) if skin != "default" else base
            suffix = "" if skin == "default" else f"_{skin}"
            tx.save_png(render_icon(g, mats), path("textures", "item", f"{gun}{suffix}.png"))
            tx.save_png(palette(mats), path("textures", "item", f"{gun}_held{suffix}.png"))
        kind = "from the GLB"
    else:
        # placeholders: the template gun's icon and held palette under this gun's names
        boxes = None
        for skin in ["default"] + skins:
            suffix = "" if skin == "default" else f"_{skin}"
            shutil.copyfile(path("textures", "item", f"{template}.png"), path("textures", "item", f"{gun}{suffix}.png"))
            shutil.copyfile(path("textures", "item", f"{template}_held.png"), path("textures", "item", f"{gun}_held{suffix}.png"))
        kind = f"placeholders ({template}'s; no {gun}.glb yet)"
    for skin in ["default"] + skins:
        suffix = "" if skin == "default" else f"_{skin}"
        write_json(path("models", "item", f"{gun}{suffix}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{gun}{suffix}"}})
        if skin != "default":
            tex = f"blockland:item/{gun}_held{suffix}"
            write_json(path("models", "item", f"{gun}_held{suffix}.json"),
                       {"parent": f"blockland:item/{gun}_held", "textures": {"0": tex, "particle": tex}})
    if boxes is not None:
        write_json(path("models", "item", f"{gun}_held.json"),
                   held_model(boxes, f"blockland:item/{gun}_held", _display(template, path)))
    else:
        with open(path("models", "item", f"{template}_held.json"), encoding="utf-8") as f:
            held = json.load(f)
        held["textures"] = {"0": f"blockland:item/{gun}_held", "particle": f"blockland:item/{gun}_held"}
        write_json(path("models", "item", f"{gun}_held.json"), held)
    write_json(path("items", f"{gun}.json"),
               third_person.weapon_definition(_skin_select(gun, skins), _skin_select(f"{gun}_held", skins)))
    print(f"items: {gun} icons/models for {1 + len(skins)} skins, {kind}")


def build(path, only=None):
    guns = _guns_json()
    for gun in GUNS:
        if only and gun not in only:
            continue
        if gun in guns:
            build_gun(gun, guns[gun], path)
