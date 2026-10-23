"""Checks the PBR contract (VIEWMODEL_SPEC sections 20 / 21) over the models, texture sets and skin data.

python -I tools/assets/pbr_check.py        -> exit 1 when something is broken

* every material of every weapon / attachment file has a valid `extras.region`; materials with `extras.pbr` name a
  texture set that exists (albedo, _n, _s), whose `uvScale` matches the set's size, with a white base colour;
* every opaque, non-emissive primitive carries baked AO in COLOR_0 (normalised, 0..1);
* every texture set: power-of-two square maps of one size, LabPBR channels in range (normals reconstructable,
  F0 0..229 or metal 230..255, porosity <= 64 on dielectrics, height >= 1, emission only where a set is meant to glow);
* skins (guns.json, knives.json, craft/skins.json): legacy tints / textures name materials the weapon's model has,
  region layers name valid regions, existing sets with matching uvScale, known effects with four params;
* special skins (section 30): rarity `special` only with a `model` or `fx`; fx with known keys, #RRGGBB colours, positive
  scales, existing flash textures and a known fire sound; a skin model exists (once its weapon's model does) and has a
  PBR table (pbr_apply), so its materials carry regions."""
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import pbr_apply as pa                    # noqa: E402
import pbr_textures as pt                 # noqa: E402
import skin_looks                         # noqa: E402

REGIONS = {"body", "barrel", "magazine", "stock", "grip", "handguard", "optic", "laser", "underbarrel", "detail",
           "blade", "handle", "hardware", "none",
           # equipment (section 36)
           "lever", "pin", "label", "glass", "liquid", "rag", "plunger", "cap"}
EQUIPMENT_JSON = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "equipment",
                              "equipment.json")
EFFECTS = {"iridescent", "flow", "galaxy", "circuit"}
GLOWING = {"skin/aurora/accent", "skin/magma/rock", "skin/galaxy/blade", "skin/neon/base"}
RARITIES = {"common", "legendary", "special"}
FX_COLOURS = ("flashColor", "tracerColor")
FX_TEXTURES = ("flashTexture", "flashSideTexture")
FX_SCALES = ("flashScale", "tracerWidth")
FX_KEYS = set(FX_COLOURS + FX_TEXTURES + FX_SCALES + ("fireSound",))


class Report:
    def __init__(self):
        self.errors, self.notes = [], []

    def check(self, ok, msg):
        if not ok:
            self.errors.append(msg)
        return ok


def _png(path):
    return np.asarray(Image.open(path).convert("RGBA"), np.int32)


def check_sets(r):
    sizes = 0
    for name, d in pt.SETS.items():
        paths = [pt.set_path(name, s) for s in ("", "_n", "_s")]
        if not all(r.check(os.path.exists(p), f"set {name}: missing {os.path.basename(p)}") for p in paths):
            continue
        a, n, s = (_png(p) for p in paths)
        sizes += sum(os.path.getsize(p) for p in paths)
        for img, tag in ((a, ""), (n, "_n"), (s, "_s")):
            h, w = img.shape[:2]
            r.check(h == w and h & (h - 1) == 0 and h == d.size, f"set {name}{tag}: {w}x{h}, expected {d.size} square")
        x, y = n[..., 0] / 255 * 2 - 1, n[..., 1] / 255 * 2 - 1
        r.check(float((x * x + y * y).max()) <= 1.02, f"set {name}_n: normal xy outside the unit disc")
        r.check(int(n[..., 3].min()) >= 1, f"set {name}_n: height below 1")
        metal = s[..., 1] >= 230
        r.check(not np.any((s[..., 1] > 229) & (s[..., 1] < 230)), f"set {name}_s: F0 between 229 and 230")
        r.check(int(np.where(metal, 0, s[..., 2]).max()) <= 64, f"set {name}_s: porosity above 64 on a dielectric")
        glow = s[..., 3] < 255
        if name in GLOWING:
            r.check(glow.any(), f"set {name}_s: meant to glow but has no emission")
        else:
            r.check(not glow.any(), f"set {name}_s: emission where none is meant ({glow.mean():.1%} of the texels)")
    r.notes.append(f"{len(pt.SETS)} texture sets, {sizes / 1024 / 1024:.1f} MiB")


def _check_layer(r, where, layer):
    path = layer.get("pbr")
    if path is not None:
        name = path.split("textures/pbr/", 1)[1] if path.startswith("textures/pbr/") else None
        if r.check(name in pt.SETS, f"{where}: unknown texture set {path}"):
            r.check(abs(float(layer.get("uvScale", 1.0)) - pt.uv_scale(name)) < 1e-5,
                    f"{where}: uvScale {layer.get('uvScale')} != {pt.uv_scale(name)} for {name}")
    eff = layer.get("effect")
    if eff is not None:
        r.check(eff in EFFECTS, f"{where}: unknown effect {eff}")
        r.check(isinstance(layer.get("params"), list) and len(layer["params"]) == 4, f"{where}: params must be 4 numbers")
    col = layer.get("effectColor")
    if col is not None:
        r.check(isinstance(col, str) and len(col) == 7 and col.startswith("#"), f"{where}: effectColor {col}")
    tint = layer.get("tint")
    if tint is not None:
        r.check(isinstance(tint, list) and len(tint) == 3, f"{where}: tint must be RGB")


def check_models(r):
    model_materials = {}
    for model, spec in pa.MODELS.items():
        path = pa.glb_path(model)
        if not os.path.exists(path):
            r.notes.append(f"{model}: no file (skipped)")
            continue
        g = pa.Glb(path)
        mats = g.json.get("materials", [])
        model_materials[model] = {m.get("name") for m in mats}
        for m in mats:
            nm = m.get("name")
            ex = m.get("extras") or {}
            where = f"{model}: material {nm}"
            r.check(ex.get("region") in REGIONS, f"{where}: region {ex.get('region')!r}")
            if "pbr" in ex:
                _check_layer(r, where, ex)
                base = m.get("pbrMetallicRoughness", {}).get("baseColorFactor", [1, 1, 1, 1])
                r.check(all(abs(c - 1) < 1e-6 for c in base[:3]), f"{where}: base colour not white with a texture set")
                r.check("baseColorTexture" not in m.get("pbrMetallicRoughness", {}),
                        f"{where}: embedded texture next to a texture set")
        missing_ao = 0
        for mesh in g.json.get("meshes", []):
            for p in mesh["primitives"]:
                mat = mats[p["material"]] if "material" in p else {}
                see = mat.get("alphaMode", "OPAQUE") in pa.NO_AO_ALPHA or any(v > 0 for v in mat.get("emissiveFactor", [0, 0, 0]))
                if "COLOR_0" in p["attributes"]:
                    ao = g.accessor(p["attributes"]["COLOR_0"])
                    r.check(ao.min() >= 0 and ao.max() <= 1.0001, f"{model}: {mesh['name']} AO out of range")
                elif not see:
                    missing_ao += 1
        r.check(missing_ao == 0, f"{model}: {missing_ao} opaque primitives without baked AO")
        r.notes.append(f"{model}: {len(mats)} materials, regions "
                       f"{sorted({str((m.get('extras') or {}).get('region')) for m in mats})}")
    return model_materials


def check_skins(r, model_materials):
    guns = skin_looks._read(skin_looks.GUNS_JSON).get("guns", {})
    knives = skin_looks._read(skin_looks.KNIVES_JSON).get("knives", {})
    shared = skin_looks._read(skin_looks.SKINS_JSON).get("skins", {})
    weapons = [(wid, "gun", w) for wid, w in guns.items()] + [(wid, "knife", w) for wid, w in knives.items()]
    equipment = skin_looks._read(EQUIPMENT_JSON).get("equipment", {}) if os.path.exists(EQUIPMENT_JSON) else {}
    ids = {wid for wid, _, _ in weapons} | set(equipment)
    for sid, s in shared.items():
        r.check(all(t in ("gun", "knife", "equipment") or t in ids for t in s.get("for", [])) and s.get("for"),
                f"skins.json {sid}: bad `for` {s.get('for')}")
        r.check(bool(s.get("regions")), f"skins.json {sid}: no regions")
    for wid, kind, w in weapons:
        model = os.path.splitext(w.get("model", ""))[0].replace("models/", "", 1)
        materials = model_materials.get(model)
        for sid, s in skin_looks.weapon_skins(wid, kind).items():
            where = f"{wid} skin {sid}"
            for key in ("tints", "textures"):
                for mat in s.get(key, {}):
                    if materials is not None:
                        r.check(mat in materials, f"{where}: {key} names {mat}, not a material of {model}")
            for mat, path in s.get("textures", {}).items():
                r.check(os.path.exists(os.path.join(pt.ASSETS, *path.split("/"))), f"{where}: missing {path}")
            for region, layer in s.get("regions", {}).items():
                r.check(region == "*" or region in REGIONS - {"none"}, f"{where}: unknown region {region}")
                _check_layer(r, f"{where} [{region}]", layer)
            _check_special(r, where, s, model)
    for wid, w in equipment.items():                  # section 36: the equipment's own skins
        model = os.path.splitext(w.get("model", ""))[0].replace("models/", "", 1)
        materials = model_materials.get(model)
        for sid, s in w.get("skins", {}).items():
            where = f"{wid} skin {sid}"
            for mat in s.get("tints", {}):
                if materials is not None:
                    r.check(mat in materials, f"{where}: tints names {mat}, not a material of {model}")
            for region, layer in s.get("regions", {}).items():
                r.check(region == "*" or region in REGIONS - {"none"}, f"{where}: unknown region {region}")
                _check_layer(r, f"{where} [{region}]", layer)
            r.check(s.get("rarity", "common") in RARITIES - {"special"} or s.get("model"),
                    f"{where}: rarity {s.get('rarity')}")


def _colour(value):
    return isinstance(value, str) and len(value) == 7 and value.startswith("#") and all(
        c in "0123456789abcdefABCDEF" for c in value[1:])


def _sound_events():
    path = os.path.join(pt.ASSETS, "sounds.json")
    with open(path, encoding="utf-8") as f:
        return set(json.load(f))


def _check_special(r, where, skin, weapon_model):
    """Section 30: a special skin's own model and its shot effects."""
    built = bool(weapon_model) and os.path.exists(pa.glb_path(weapon_model))
    rarity = skin.get("rarity", "common")
    r.check(rarity in RARITIES, f"{where}: unknown rarity {rarity}")
    fx, model = skin.get("fx"), skin.get("model", "")
    r.check(rarity != "special" or fx is not None or model, f"{where}: rarity special without a model or fx")
    if fx is not None:
        r.check(isinstance(fx, dict), f"{where}: fx must be an object")
        fx = fx if isinstance(fx, dict) else {}
        r.check(set(fx) <= FX_KEYS, f"{where}: unknown fx keys {sorted(set(fx) - FX_KEYS)}")
        for key in FX_COLOURS:
            if fx.get(key):
                r.check(_colour(fx[key]), f"{where}: fx {key} {fx[key]!r} is not #RRGGBB")
        for key in FX_SCALES:
            if key in fx:
                r.check(isinstance(fx[key], (int, float)) and fx[key] > 0, f"{where}: fx {key} must be > 0")
        for key in FX_TEXTURES:
            if fx.get(key):
                r.check(os.path.exists(os.path.join(pt.ASSETS, *fx[key].split("/"))), f"{where}: missing fx {key} {fx[key]}")
        if fx.get("fireSound") and fx["fireSound"] not in _sound_events():
            # like the skin model: required once the weapon itself is built
            if built:
                r.check(False, f"{where}: fx fireSound {fx['fireSound']} not in sounds.json")
            else:
                r.notes.append(f"{where}: fx fireSound {fx['fireSound']} not made yet (nor its weapon)")
    if model:
        name = os.path.splitext(model)[0].replace("models/", "", 1)
        own = built
        if os.path.exists(pa.glb_path(name)):
            r.check(name in pa.MODELS, f"{where}: skin model {model} has no PBR table (pbr_apply)")
        elif own:
            r.check(False, f"{where}: skin model {model} missing")
        else:
            r.notes.append(f"{where}: skin model {model} not built yet (nor its weapon)")


def main():
    r = Report()
    check_sets(r)
    mm = check_models(r)
    check_skins(r, mm)
    for n in r.notes:
        print("  " + n)
    if r.errors:
        print(f"FAILED ({len(r.errors)} problems):")
        for e in r.errors:
            print("  - " + e)
        return 1
    print("OK: texture sets, model materials and skins follow sections 20 / 21")
    return 0


if __name__ == "__main__":
    sys.exit(main())
