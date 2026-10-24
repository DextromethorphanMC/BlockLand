"""Item assets (VIEWMODEL_SPEC sections 1 and 8): skin icons rendered from p9.glb, icon/held item models,
held-model palettes, items/p9.json, ammo icons + models."""
import json
import os

import numpy as np

import raster as rs
import skin_looks
import textures as tx
from glbread import GLTF
from preview import GUN_DIR, hidden_set, load_materials
import third_person

GUN = "p9"
# the gun's skins as the runtime has them (guns.json + the shared craft/skins.json), default first
SKIN_DATA = {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}
SKINS = list(SKIN_DATA)
METALLIC_SKINS = {"gold": ("slide", "barrel", "steel_dark")}
AMMO = ["pistol_ammo", "smg_ammo", "shotgun_ammo", "sniper_ammo"]


def write_json(path, obj):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def skin_materials(mats, skin):
    out = skin_looks.skin_materials(mats, SKIN_DATA.get(skin, {}))
    for m in out:
        if m["name"] in METALLIC_SKINS.get(skin, ()):        # icon only: polished look
            m["metallic"], m["roughness"] = 1.0, 0.3
    return out


# ------------------------------------------------------------------------------------------- icons
def side_view_items(g, mats):
    """Gun meshes in gun space (rest pose, no viewmodel offset), rotated to a right-side view."""
    gi = g.names["gun"]
    W = g.world_matrices({gi: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    hide = hidden_set(g, {"mag_extended", "sight_reddot", "laser", "fx_shell"})
    view = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])          # look from +X, muzzle to the right
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or i in hide:
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": mats[p["material"]]})
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 1.0])
    return items, allp.max(0) - allp.min(0)


def icon_shade(frag, mats):
    n, mid, uv = frag["nrm"], frag["mid"], frag["uv"]
    L = np.array([-0.35, 0.80, 0.50])
    L /= np.linalg.norm(L)
    col = np.zeros((len(mid), 3))
    for mi in np.unique(mid):
        s = mid == mi
        m = mats[mi]
        alb = np.tile(np.asarray(m["base"][:3], float), (s.sum(), 1))
        if m.get("tex") is not None:
            alb = alb * rs.sample(m["tex"], uv[s] * m.get("uv_scale", 1.0))[:, :3]
        a = rs.linear_to_srgb(alb)
        ndl = np.clip(n[s] @ L, 0, 1)
        lum = 0.60 + 0.42 * ndl - 0.10 * np.clip(-n[s][:, 1], 0, 1)
        c = a * lum[:, None] + 0.10 * m.get("metallic", 0.0) * ndl[:, None] ** 6
        c += np.asarray(m.get("emissive", (0, 0, 0)))[None, :] * 0.6
        col[s] = 0.07 + 0.95 * c
    return np.clip(col, 0, 1)


def render_icon(g, mats, size=32, ss=8, margin=2):
    items, ext = side_view_items(g, mats)
    return render_items_icon(items, ext, size, ss, margin)


def render_items_icon(items, ext, size=32, ss=8, margin=2):
    """Orthographic, flat-shaded render of view-space items, majority-vote downsampled, 1 px outline."""
    half = max(ext[0] / (size - 2 * margin) * size, ext[1] / (size - 2 * margin) * size) / 2
    W = H = size * ss
    cam = rs.Camera(W, H, ortho_half_h=half)
    buf, data = rs.rasterize(items, cam, W, H, flat=True)
    res, mlist = data
    f = res["opaque"]
    col = np.zeros((W * H, 3))
    col[f["pix"]] = icon_shade(f, mlist)
    mid = buf["mid"].reshape(H, W)
    col = col.reshape(H, W, 3)
    out = np.zeros((size, size, 4))
    for by in range(size):
        for bx in range(size):
            bm = mid[by * ss:(by + 1) * ss, bx * ss:(bx + 1) * ss].reshape(-1)
            bc = col[by * ss:(by + 1) * ss, bx * ss:(bx + 1) * ss].reshape(-1, 3)
            cov = np.mean(bm >= 0)
            if cov < 0.42:
                continue
            vals, counts = np.unique(bm[bm >= 0], return_counts=True)
            win = vals[np.argmax(counts)]
            out[by, bx, :3] = bc[bm == win].mean(0)
            out[by, bx, 3] = 1.0
    # 1 px dark outline around the silhouette
    a = out[:, :, 3] > 0
    nb = np.zeros_like(a)
    nb[1:] |= a[:-1]
    nb[:-1] |= a[1:]
    nb[:, 1:] |= a[:, :-1]
    nb[:, :-1] |= a[:, 1:]
    edge = nb & ~a
    out[edge] = (0.055, 0.055, 0.065, 1.0)
    return out


# ------------------------------------------------------------------------------------ held model
def palette(mats_by_name):
    """16x16 palette: 4x4 px patches with a light top row and a dark bottom row."""
    order = ["slide", "slide_top", "polymer", "grip", "barrel", "steel_dark", "sight_dots", "mag_base", "bore"]
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(31)
    for k, name in enumerate(order):
        src = {"slide_top": "slide", "bore": "steel_dark"}.get(name, name)
        c = patch_colours(mats_by_name[src], k) * 0.95 + 0.07
        if name == "slide_top":
            c = c * 1.12 + 0.03
        if name == "bore":
            c = np.full((4, 4, 3), 0.02)
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = c * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.10
        patch[3] *= 0.85
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def patch_colours(m, k=0):
    """4x4 sRGB colours of a material for a held-model palette patch: point samples of its texture (a quarter of a
    repeat, so patterns such as camo stay visible) times its colour, or the plain colour."""
    base = np.asarray(m["base"][:3], float)
    tex = m.get("tex")
    if tex is None:
        return np.tile(rs.linear_to_srgb(base), (4, 4, 1))
    h, w = tex.shape[:2]
    off = (k * 0.137) % 1.0
    ys = ((np.arange(4) + 0.5) / 16 + off) % 1.0 * h
    xs = ((np.arange(4) + 0.5) / 16 + off * 0.61) % 1.0 * w
    s = tex[ys.astype(int)[:, None], xs.astype(int)[None, :], :3]
    return rs.linear_to_srgb(s * base)


PATCH = {"slide": 0, "slide_top": 1, "polymer": 2, "grip": 3, "barrel": 4, "steel_dark": 5, "sight_dots": 6,
         "mag_base": 7, "bore": 8}


def _uv(name):
    k = PATCH[name]
    x0, y0 = (k % 4) * 4, (k // 4) * 4
    return [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]


def _element(frm, to, mat, top=None, rot=None, faces=("north", "south", "east", "west", "up", "down")):
    el = {"from": frm, "to": to, "faces": {}}
    for f in faces:
        m = top if (f == "up" and top) else mat
        el["faces"][f] = {"uv": _uv(m), "texture": "#0"}
    if rot:
        el["rotation"] = rot
    return el


def held_model(texture):
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 8.2, 11.2]}
    e = [
        _element([7.2, 8.96, 2.0], [8.8, 10.8, 13.2], "slide", top="slide_top"),
        _element([7.28, 8.1, 2.3], [8.72, 9.0, 13.7], "polymer"),
        _element([7.35, 7.7, 2.6], [8.65, 8.1, 5.5], "polymer"),
        _element([7.62, 6.45, 6.0], [8.38, 6.85, 10.2], "polymer"),
        _element([7.62, 6.45, 5.95], [8.38, 8.1, 6.4], "polymer"),
        _element([7.05, 3.3, 9.4], [8.95, 8.4, 12.9], "grip", rot=grip_rot),
        _element([6.98, 2.7, 9.3], [9.02, 3.3, 13.1], "mag_base", rot=grip_rot),
        _element([7.8, 7.1, 7.9], [8.2, 8.1, 8.6], "steel_dark"),
        _element([7.6, 9.55, 1.7], [8.4, 10.35, 2.0], "barrel"),
        _element([7.9, 10.8, 2.3], [8.1, 11.2, 2.8], "steel_dark"),
        _element([7.3, 10.8, 12.4], [8.7, 11.2, 13.1], "steel_dark"),
        _element([7.75, 9.2, 13.2], [8.25, 10.4, 14.0], "steel_dark"),
    ]
    e[8]["faces"]["north"] = {"uv": _uv("bore"), "texture": "#0"}
    display = {
        "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 1.5], "scale": [0.6, 0.6, 0.6]},
        "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 1.5], "scale": [0.6, 0.6, 0.6]},
        "firstperson_righthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, -1], "scale": [0.75, 0.75, 0.75]},
        "firstperson_lefthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, -1], "scale": [0.75, 0.75, 0.75]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
        "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "gui": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1.0, 1.0, 1.0]},
    }
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": display}


def skin_select(x):
    return {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": s, "model": {"type": "minecraft:model", "model": f"blockland:item/{x}_{s}"}}
                      for s in SKINS],
            "fallback": {"type": "minecraft:model", "model": f"blockland:item/{x}"}}


def item_definition():
    return third_person.weapon_definition(skin_select(GUN), skin_select(f"{GUN}_held"))


# ------------------------------------------------------------------------------------------- ammo
def _outline(img, color=(0.07, 0.06, 0.05)):
    a = img[:, :, 3] > 0
    nb = np.zeros_like(a)
    nb[1:] |= a[:-1]
    nb[:-1] |= a[1:]
    nb[:, 1:] |= a[:, :-1]
    nb[:, :-1] |= a[:, 1:]
    img[nb & ~a] = list(color) + [1.0]
    return img


def _put(img, x0, y0, rows, colors):
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch != ".":
                img[y0 + dy, x0 + dx] = list(colors[ch]) + [1.0]


BRASS = {"h": (0.98, 0.86, 0.50), "b": (0.85, 0.66, 0.30), "d": (0.60, 0.43, 0.17), "c": (0.80, 0.48, 0.30),
         "C": (0.92, 0.62, 0.42), "k": (0.55, 0.30, 0.18)}


def ammo_icon(kind):
    img = np.zeros((16, 16, 4))
    if kind == "pistol_ammo":
        rnd = [".CC.", "Cckk", "Cckk", "hbbd", "hbbd", "hbbd", "hbbd", "hbbd", "dddd"]
        for x0, y0 in ((2, 4), (6, 3), (10, 5)):
            _put(img, x0, y0, rnd, BRASS)
    elif kind == "smg_ammo":
        box = {"g": (0.36, 0.42, 0.25), "G": (0.46, 0.53, 0.32), "D": (0.24, 0.28, 0.16), "d": (0.29, 0.34, 0.20), "y": (0.85, 0.78, 0.35)}
        _put(img, 2, 7, ["GGGGGGGGGGGG", "Gggggggggggd", "GgyyyyyyygGd", "Gggggggggggd", "GgggggggggGd",
                         "GgggggggggGd", "DDDDDDDDDDDD"], box)
        for x in (3, 5, 7, 9, 11):
            _put(img, x, 3 + (x % 3 == 0), [".C", "Ck", "hb", "hb"], BRASS)
    elif kind == "shotgun_ammo":
        shell = {"r": (0.80, 0.12, 0.10), "R": (0.95, 0.30, 0.25), "q": (0.55, 0.06, 0.05), **BRASS}
        s = ["Rrrq", "Rrrq", "Rrrq", "Rrrq", "Rrrq", "Rrrq", "Rrrq", "hbbd", "hbbd", "dddd"]
        for x0, y0 in ((3, 3), (8, 5)):
            _put(img, x0, y0, s, shell)
        img[3, 3:7, :3] = (0.60, 0.08, 0.06)
        img[5, 8:12, :3] = (0.60, 0.08, 0.06)
    elif kind == "sniper_ammo":
        rnd = [".C.", ".C.", "Cck", "Cck", "Cck", ".b.", "hbd", "hbd", "hbd", "hbd", "hbd", "hbd", "ddd"]
        for x0, y0 in ((4, 1), (9, 2)):
            _put(img, x0, y0, rnd, BRASS)
    return _outline(img)


# ------------------------------------------------------------------------------------------- build
def build(path):
    g = GLTF(os.path.join(GUN_DIR, f"{GUN}.glb"))
    base_mats = load_materials(g)
    for skin in ["default"] + SKINS:
        mats = skin_materials(base_mats, skin) if skin != "default" else base_mats
        suffix = "" if skin == "default" else f"_{skin}"
        tx.save_png(render_icon(g, mats), path("textures", "item", f"{GUN}{suffix}.png"))
        write_json(path("models", "item", f"{GUN}{suffix}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{GUN}{suffix}"}})
        by_name = {m["name"]: m for m in mats}
        tx.save_png(palette(by_name), path("textures", "item", f"{GUN}_held{suffix}.png"))
        if skin == "default":
            write_json(path("models", "item", f"{GUN}_held.json"), held_model(f"blockland:item/{GUN}_held"))
        else:
            tex = f"blockland:item/{GUN}_held{suffix}"
            write_json(path("models", "item", f"{GUN}_held{suffix}.json"),
                       {"parent": f"blockland:item/{GUN}_held", "textures": {"0": tex, "particle": tex}})
    write_json(path("items", f"{GUN}.json"), item_definition())
    for a in AMMO:
        tx.save_png(ammo_icon(a), path("textures", "item", f"{a}.png"))
        write_json(path("models", "item", f"{a}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{a}"}})
        write_json(path("items", f"{a}.json"), {"model": {"type": "minecraft:model", "model": f"blockland:item/{a}"}})
    print(f"items: {GUN} icons/models for {1 + len(SKINS)} skins, {len(AMMO)} ammo items")
