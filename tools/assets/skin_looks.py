"""Skins as the tools draw them (VIEWMODEL_SPEC section 21), from the same data the runtime reads: a weapon's own
skins in guns.json / knives.json plus the shared library craft/skins.json (joined by `for`: kind or weapon id).

skin_materials(mats, skin) applies one skin to raster materials (loadouts.load_materials):
  * legacy per-material `tints` are display colours: on a texture-set material they recolour the albedo and keep its
    detail (albedo / its mean colour x tint), like the runtime;
  * legacy per-material `textures` replace the albedo;
  * `regions` layers replace the texture set of every material in the region (or `*`), with an optional `tint`."""
import json
import os

import numpy as np

import loadouts as lo
import raster as rs

DATA = os.path.join(lo.HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland")
GUNS_JSON = os.path.join(DATA, "guns", "guns.json")
KNIVES_JSON = os.path.join(DATA, "melee", "knives.json")
SKINS_JSON = os.path.join(DATA, "craft", "skins.json")


def _read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def weapon_skins(weapon, kind):
    """{skin id: skin data} of a weapon: its own skins, then the shared ones `for` its kind ("gun" / "knife") or id
    that it does not define itself. Ordered like the craft screen (own first)."""
    own = _read(GUNS_JSON if kind == "gun" else KNIVES_JSON).get("guns" if kind == "gun" else "knives", {})
    skins = dict(own.get(weapon, {}).get("skins", {}))
    for sid, s in _read(SKINS_JSON).get("skins", {}).items():
        targets = s.get("for", [])
        if (kind in targets or weapon in targets) and sid not in skins:
            skins[sid] = s
    return skins


def _set_stats(path):
    import pbr_apply
    name = path.split("textures/pbr/", 1)[1]
    return pbr_apply.set_stats(name) or (0.0, 0.5)


def _detail(tex):
    """The albedo with its mean colour divided out (detail around 1)."""
    mean = tex[:, :, :3].reshape(-1, 3).mean(0)
    out = tex.copy()
    out[:, :, :3] = tex[:, :, :3] / np.maximum(mean, 1e-4)
    return out


def skin_materials(mats, skin):
    out = []
    tints = skin.get("tints", {})
    textures = skin.get("textures", {})
    regions = skin.get("regions", {})
    for m in mats:
        m = dict(m)
        name, region = m["name"], m.get("region")
        layer = None
        if region and region != "none":
            layer = regions.get(region) or regions.get("*")
        if layer and layer.get("pbr"):
            tex = lo.pbr_albedo(layer["pbr"])
            if tex is not None:
                m["tex"], m["pbr"] = tex, layer["pbr"]
                m["uv_scale"] = float(layer.get("uvScale", 1.0))
                m["base"] = np.array([1.0, 1.0, 1.0, m["base"][3]])
                m["metallic"], m["roughness"] = _set_stats(layer["pbr"])
        if layer and layer.get("tint"):
            m["base"] = np.concatenate([m["base"][:3] * rs.srgb_to_linear(np.asarray(layer["tint"], float)),
                                        m["base"][3:]])
        if name in tints:                      # a display colour: replaces the colour, keeps the texture's detail
            if m.get("tex") is not None and m.get("pbr"):
                m["tex"] = _detail(m["tex"])
            m["base"] = np.array(list(rs.srgb_to_linear(np.asarray(tints[name], float))) + [m["base"][3]])
        if name in textures:
            path = os.path.join(lo.ASSETS, *textures[name].split("/"))
            if os.path.exists(path):
                from PIL import Image
                t = np.asarray(Image.open(path).convert("RGBA"), np.float64) / 255.0
                t[:, :, :3] = rs.srgb_to_linear(t[:, :, :3])
                m["tex"], m["pbr"], m["uv_scale"] = t, None, 1.0
                m["base"] = np.array([1.0, 1.0, 1.0, m["base"][3]])
        out.append(m)
    return out
