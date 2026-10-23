"""Binds the LabPBR texture sets and skin regions to the weapon GLBs and bakes per-vertex AO (VIEWMODEL_SPEC 20 / 21).

python -I tools/assets/pbr_apply.py [model ...]     (model = "gun/p9", "attachment/suppressor" ...; default: all)

Per model a table maps every material to (texture set, region); a material used by parts of different regions is
split first (a copy with a suffixed name per region, e.g. the AK's `wood` -> `wood_handguard`, `wood_grip`). For each
material with a set: `extras` {pbr, region, uvScale}, `baseColorFactor` white (the albedo PNG carries the colour), no
embedded `baseColorTexture` (unused images are dropped), metallic / roughness factors from the set (fallback only).
COLOR_0 (VEC4 normalised bytes, R = G = B = AO, 1 = open) is baked by ray casting in the rest pose: guns and
attachments with every part as occluder (one magazine at a time), knives part by part (the handles swing).
Idempotent: running it again on a processed file gives the same file. Baked AO is cached in out/ao_cache."""
import copy
import hashlib
import json
import os
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import pbr_textures as pt                 # noqa: E402

MODELS_DIR = os.path.join(pt.ASSETS, "models")
CACHE = os.path.join(HERE, "out", "ao_cache")
AO_VERSION = 4

# ------------------------------------------------------------------------------------------------ the tables
# material -> (texture set or None, region); "split": material -> [(new material, [meshes that get it])]
# "ao": together = the meshes under `root` occlude each other in the rest pose, except the subtrees named in
# `separate` (parts that move away from the rest during clips: magazines, bolts, a pistol barrel inside its slide),
# which only occlude themselves; per_mesh = every mesh only occludes itself (the knife's handles swing).

MODELS = {
    "gun/p9": {
        "ao": {"mode": "together", "root": "gun", "distance": 0.022,
               "separate": [["barrel"], ["mag_standard"], ["mag_extended"]]},
        "materials": {
            "polymer": ("p9/polymer", "grip"),          # the polymer frame is the grip module
            "grip": ("p9/grip", "grip"),
            "slide": ("p9/slide", "body"),
            "barrel": ("p9/barrel", "barrel"),
            "steel_dark": ("common/steel_dark", "detail"),
            "sight_dots": (None, "none"),
            "mag": ("p9/mag", "magazine"),
            "mag_base": ("p9/polymer", "magazine"),
            "brass": ("common/brass", "none"),
        },
    },
    "gun/ak47": {
        "ao": {"mode": "together", "root": "gun", "distance": 0.028,
               "separate": [["bolt"], ["mag_standard"], ["mag_extended"]]},
        "split": {
            "wood": [("wood_handguard", ["handguard"]), ("wood_grip", ["pistol_grip"])],
            "steel": [("steel_barrel", ["barrel", "gas_tube", "muzzle_brake"]), ("steel_stock", ["stock"])],
            "steel_dark": [("steel_dark_barrel", ["front_sight", "front_sight_ears", "muzzle_brake"])],
        },
        "materials": {
            "wood": ("ak47/wood", "stock"),
            "wood_handguard": ("ak47/wood", "handguard"),
            "wood_grip": ("ak47/wood", "grip"),
            "steel": ("ak47/steel", "body"),
            "steel_barrel": ("common/parkerized", "barrel"),
            "steel_stock": ("ak47/steel", "stock"),
            "steel_dark": ("common/steel_dark", "detail"),
            "steel_dark_barrel": ("common/steel_dark", "barrel"),
            "mag": ("ak47/mag", "magazine"),
            "mag_base": ("ak47/mag", "magazine"),
            "brass": ("common/brass", "none"),
        },
    },
    "knife/butterfly": {
        "ao": {"mode": "per_mesh", "distance": 0.008},
        "materials": {
            "blade": ("butterfly/blade", "blade"),
            "blade_edge": ("butterfly/blade_edge", "blade"),
            "handle": ("butterfly/handle", "handle"),
            "handle_inlay": ("butterfly/handle_inlay", "handle"),
            "pins": ("common/polished_steel", "hardware"),
            "latch": ("common/polished_steel", "hardware"),
        },
    },
    "attachment/mini_reddot": {
        "ao": {"mode": "together", "distance": 0.010},
        "materials": {"attachment": ("common/anodized_black", "optic"), "glass": (None, "none"),
                      "reticle": (None, "none"), "interior": (None, "none")},
    },
    "attachment/reddot": {
        "ao": {"mode": "together", "distance": 0.012},
        "materials": {"attachment": ("common/anodized_black", "optic"), "steel_dark": ("common/steel_dark", "optic"),
                      "glass": (None, "none"), "reticle": (None, "none"), "interior": (None, "none")},
    },
    # `interior` (optics' inner walls seen when aiming): flat matte black factors, never skinned, no set
    "attachment/holo": {
        "ao": {"mode": "together", "distance": 0.012},
        "materials": {"attachment": ("common/anodized_black", "optic"), "steel_dark": ("common/steel_dark", "optic"),
                      "glass": (None, "none"), "interior": (None, "none")},
    },
    "attachment/laser": {
        "ao": {"mode": "together", "distance": 0.010},
        "materials": {"attachment": ("common/anodized_black", "laser"), "laser_lens": (None, "none")},
    },
    "attachment/suppressor": {
        "ao": {"mode": "together", "distance": 0.012},
        "materials": {"suppressor": ("attachment/suppressor", "barrel"), "steel_dark": ("common/steel_dark", "barrel")},
    },
    "attachment/tactical_stock": {
        "ao": {"mode": "together", "distance": 0.015},
        "materials": {"attachment": ("common/anodized_black", "stock"), "steel_dark": ("common/steel_dark", "stock"),
                      "stock_pad": ("common/rubber", "none")},
    },
    "attachment/charm_grass": {
        "ao": {"mode": "together", "distance": 0.004},
        "materials": {"charm_metal": ("common/polished_steel", "none"), "charm_block": (None, "none")},
    },
    # ---- the AWP and the scopes (awp agent's files; entries stay inert until the files exist) -----------------
    "gun/awp": {
        "ao": {"mode": "together", "root": "gun", "distance": 0.03,
               "separate": [["bolt"], ["mag_standard"], ["mag_extended"]]},
        "materials": {
            "receiver": ("awp/receiver", "body"),
            "barrel": ("common/parkerized", "barrel"),
            "bolt": ("common/polished_steel", "detail"),
            "steel_dark": ("common/steel_dark", "detail"),
            "stock": ("awp/stock", "stock"),
            "grip": ("awp/grip", "grip"),
            "pad": ("common/rubber", "none"),
            "mag": ("p9/mag", "magazine"),
            "mag_base": ("p9/mag", "magazine"),
            "brass": ("common/brass", "none"),
        },
    },
    "attachment/scope_2x": {
        "ao": {"mode": "together", "distance": 0.012},
        "materials": {"scope": ("common/anodized_black", "optic"), "steel_dark": ("common/steel_dark", "optic"),
                      "scope_rubber": ("common/rubber", "none"), "lens": (None, "none"), "lens_front": (None, "none"),
                      "interior": (None, "none")},
    },
    "attachment/scope_sniper": {
        "ao": {"mode": "together", "distance": 0.015},
        "materials": {"scope": ("common/anodized_black", "optic"), "steel_dark": ("common/steel_dark", "optic"),
                      "scope_rubber": ("common/rubber", "none"), "lens": (None, "none"), "lens_front": (None, "none"),
                      "interior": (None, "none")},
    },
}
# Tables of later weapons live in modules of their own (parallel work on different weapons): each module has
# `models()` -> {model: table} in the format above. Missing modules are skipped.
MODEL_MODULES = ("pbr_models_tommy", "pbr_models_ks3", "pbr_models_usp", "pbr_models_dagger", "pbr_models_stocks",
                 "pbr_models_underbarrel", "pbr_models_rpd", "pbr_models_karambit", "pbr_models_equipment")


def _load_model_modules():
    import importlib
    for module in MODEL_MODULES:
        try:
            mod = importlib.import_module(module)
        except ModuleNotFoundError as e:
            if e.name == module:
                continue
            raise
        MODELS.update(mod.models())


_load_model_modules()
#: Materials never given a set or baked AO, whatever their table says (see-through or self-lit).
NO_AO_ALPHA = ("BLEND", "MASK")


def glb_path(model):
    return os.path.join(MODELS_DIR, *model.split("/")) + ".glb"


# ------------------------------------------------------------------------------------------------ GLB editing
COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


class Glb:
    def __init__(self, path):
        data = open(path, "rb").read()
        magic, version, length = struct.unpack_from("<III", data, 0)
        assert magic == 0x46546C67 and version == 2, f"{path}: not a GLB 2.0"
        off, self.json, self.bin = 12, None, bytearray()
        while off < len(data):
            clen, ctype = struct.unpack_from("<II", data, off)
            body = data[off + 8: off + 8 + clen]
            if ctype == 0x4E4F534A:
                self.json = json.loads(body.decode("utf-8"))
            elif ctype == 0x004E4942:
                self.bin = bytearray(body)
            off += 8 + clen

    def accessor(self, idx):
        a = self.json["accessors"][idx]
        bv = self.json["bufferViews"][a["bufferView"]]
        dt = np.dtype(COMP[a["componentType"]])
        n = NCOMP[a["type"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0) or dt.itemsize * n
        if stride == dt.itemsize * n:
            arr = np.frombuffer(bytes(self.bin), dtype=dt, count=a["count"] * n, offset=start).reshape(a["count"], n)
        else:
            arr = np.stack([np.frombuffer(bytes(self.bin), dtype=dt, count=n, offset=start + i * stride)
                            for i in range(a["count"])])
        if a.get("normalized"):
            arr = arr.astype(np.float64) / float(np.iinfo(dt).max)
        return arr

    def add_accessor(self, array, kind, component, normalized=False, target=34962):
        array = np.ascontiguousarray(array)
        while len(self.bin) % 4:
            self.bin.append(0)
        view = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": array.nbytes, "target": target}
        self.bin.extend(array.tobytes())
        self.json["bufferViews"].append(view)
        acc = {"bufferView": len(self.json["bufferViews"]) - 1, "componentType": component,
               "count": int(array.shape[0]), "type": kind}
        if normalized:
            acc["normalized"] = True
        self.json["accessors"].append(acc)
        return len(self.json["accessors"]) - 1

    # -- compaction: drop accessors, buffer views, images and textures nothing references any more --------------
    def compact(self):
        j = self.json
        # textures / images
        used_tex = set()

        def scan_tex(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k.endswith("Texture") and isinstance(v, dict) and "index" in v:
                        used_tex.add(v["index"])
                    else:
                        scan_tex(v)
            elif isinstance(o, list):
                for v in o:
                    scan_tex(v)
        scan_tex(j.get("materials", []))
        textures = j.get("textures", [])
        tex_map = {old: new for new, old in enumerate(sorted(used_tex))}

        def remap_tex(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k.endswith("Texture") and isinstance(v, dict) and "index" in v:
                        v["index"] = tex_map[v["index"]]
                    else:
                        remap_tex(v)
            elif isinstance(o, list):
                for v in o:
                    remap_tex(v)
        remap_tex(j.get("materials", []))
        new_textures = [textures[i] for i in sorted(used_tex)]
        used_img = sorted({t["source"] for t in new_textures if "source" in t})
        img_map = {old: new for new, old in enumerate(used_img)}
        for t in new_textures:
            if "source" in t:
                t["source"] = img_map[t["source"]]
        images = [j["images"][i] for i in used_img] if used_img else []
        for key, val in (("textures", new_textures), ("images", images)):
            if val:
                j[key] = val
            else:
                j.pop(key, None)
        if not j.get("textures"):
            j.pop("samplers", None)
        # accessors
        used_acc = set()
        for m in j.get("meshes", []):
            for p in m["primitives"]:
                used_acc.update(p["attributes"].values())
                if "indices" in p:
                    used_acc.add(p["indices"])
        for a in j.get("animations", []):
            for s in a["samplers"]:
                used_acc.update((s["input"], s["output"]))
        for s in j.get("skins", []):
            if "inverseBindMatrices" in s:
                used_acc.add(s["inverseBindMatrices"])
        acc_order = sorted(used_acc)
        acc_map = {old: new for new, old in enumerate(acc_order)}
        for m in j.get("meshes", []):
            for p in m["primitives"]:
                p["attributes"] = {k: acc_map[v] for k, v in p["attributes"].items()}
                if "indices" in p:
                    p["indices"] = acc_map[p["indices"]]
        for a in j.get("animations", []):
            for s in a["samplers"]:
                s["input"], s["output"] = acc_map[s["input"]], acc_map[s["output"]]
        for s in j.get("skins", []):
            if "inverseBindMatrices" in s:
                s["inverseBindMatrices"] = acc_map[s["inverseBindMatrices"]]
        accessors = [j["accessors"][i] for i in acc_order]
        # buffer views, rebuilt into a fresh binary chunk
        used_bv = sorted({a["bufferView"] for a in accessors if "bufferView" in a}
                         | {im["bufferView"] for im in images if "bufferView" in im})
        bv_map, new_bin, views = {}, bytearray(), []
        for old in used_bv:
            v = dict(j["bufferViews"][old])
            while len(new_bin) % 4:
                new_bin.append(0)
            start = v.get("byteOffset", 0)
            chunk = self.bin[start:start + v["byteLength"]]
            v["byteOffset"] = len(new_bin)
            new_bin.extend(chunk)
            bv_map[old] = len(views)
            views.append(v)
        for a in accessors:
            if "bufferView" in a:
                a["bufferView"] = bv_map[a["bufferView"]]
        for im in images:
            if "bufferView" in im:
                im["bufferView"] = bv_map[im["bufferView"]]
        j["accessors"], j["bufferViews"], self.bin = accessors, views, new_bin

    def save(self, path):
        while len(self.bin) % 4:
            self.bin.append(0)
        self.json["buffers"] = [{"byteLength": len(self.bin)}]
        js = json.dumps(self.json, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        js += b" " * ((4 - len(js) % 4) % 4)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        out = bytearray(struct.pack("<III", 0x46546C67, 2, total))
        out += struct.pack("<II", len(js), 0x4E4F534A) + js
        out += struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin)
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(out)
        os.replace(tmp, path)


# ------------------------------------------------------------------------------------------------ transforms
def _quat(q):
    x, y, z, w = np.asarray(q, float) / np.linalg.norm(q)
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def world_matrices(j):
    nodes = j.get("nodes", [])
    parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}
    mats = {}

    def world(i):
        if i in mats:
            return mats[i]
        n = nodes[i]
        m = np.eye(4)
        if "matrix" in n:
            m = np.array(n["matrix"], float).reshape(4, 4).T
        else:
            m[:3, :3] = _quat(n.get("rotation", [0, 0, 0, 1])) * np.array(n.get("scale", [1, 1, 1]), float)[None, :]
            m[:3, 3] = n.get("translation", [0, 0, 0])
        if i in parent:
            m = world(parent[i]) @ m
        mats[i] = m
        return m

    for i in range(len(nodes)):
        world(i)
    return mats, parent


def under(j, parent, node, root_name):
    names = [n.get("name") for n in j["nodes"]]
    i = node
    while i is not None:
        if names[i] == root_name:
            return True
        i = parent.get(i)
    return False


# ------------------------------------------------------------------------------------------------ AO
def _hemisphere(k):
    """k cosine-weighted directions about +Z (Fibonacci spiral, Malley's method)."""
    i = np.arange(k) + 0.5
    r = np.sqrt(i / k)
    phi = i * np.pi * (3 - np.sqrt(5))
    return np.stack([r * np.cos(phi), r * np.sin(phi), np.sqrt(np.clip(1 - r * r, 0, 1))], -1)


def bake_ao(points, normals, tris, distance, rays=48, eps=1e-4):
    """Ambient occlusion (1 = open) at each point within `distance` (world units) against triangles (m, 3, 3)."""
    n = len(points)
    if n == 0 or len(tris) == 0:
        return np.ones(n)
    pts = points.astype(np.float64)
    nrm = normals / (np.linalg.norm(normals, axis=1, keepdims=True) + 1e-12)
    # per-point frame, rotated by a hashed angle so neighbouring vertices sample different directions
    helper = np.where(np.abs(nrm[:, 2:3]) < 0.9, np.array([[0, 0, 1.0]]), np.array([[1.0, 0, 0]]))
    t = np.cross(helper, nrm)
    t /= np.linalg.norm(t, axis=1, keepdims=True)
    b = np.cross(nrm, t)
    ang = (np.arange(n) * 2.399963) % (2 * np.pi)
    c, s = np.cos(ang)[:, None], np.sin(ang)[:, None]
    t, b = t * c + b * s, b * c - t * s
    hemi = _hemisphere(rays)
    dirs = hemi[None, :, 0:1] * t[:, None, :] + hemi[None, :, 1:2] * b[:, None, :] + hemi[None, :, 2:3] * nrm[:, None, :]
    origins = pts + nrm * eps
    v0 = tris[:, 0].astype(np.float64)
    e1 = (tris[:, 1] - tris[:, 0]).astype(np.float64)
    e2 = (tris[:, 2] - tris[:, 0]).astype(np.float64)
    lo = tris.min(axis=1) - distance
    hi = tris.max(axis=1) + distance
    cell = max(distance, 1e-3)
    keys = np.floor(pts / cell).astype(np.int64)
    order = np.lexsort(keys.T[::-1])
    keys_sorted = keys[order]
    split = np.nonzero(np.any(np.diff(keys_sorted, axis=0) != 0, axis=1))[0] + 1
    occl = np.zeros((n, rays))
    for group in np.split(order, split):
        cmin = pts[group].min(0)
        cmax = pts[group].max(0)
        cand = np.nonzero(np.all(hi >= cmin, axis=1) & np.all(lo <= cmax, axis=1))[0]
        if len(cand) == 0:
            continue
        O = np.repeat(origins[group], rays, axis=0)
        D = dirs[group].reshape(-1, 3)
        nearest = np.full(len(O), np.inf)
        step = max(1, int(1_500_000 // max(1, len(cand))))
        V0, E1, E2 = v0[cand], e1[cand], e2[cand]
        for s0 in range(0, len(O), step):
            o = O[s0:s0 + step][:, None, :]
            d = D[s0:s0 + step][:, None, :]
            p = np.cross(d, E2[None])
            det = np.sum(E1[None] * p, -1)
            ok = np.abs(det) > 1e-14
            inv = np.where(ok, 1.0 / np.where(ok, det, 1), 0)
            sv = o - V0[None]
            u = np.sum(sv * p, -1) * inv
            q = np.cross(sv, E1[None])
            v = np.sum(d * q, -1) * inv
            tt = np.sum(E2[None] * q, -1) * inv
            hit = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (tt > eps) & (tt < distance)
            tt = np.where(hit, tt, np.inf)
            nearest[s0:s0 + step] = tt.min(axis=1)
        w = np.where(np.isfinite(nearest), 1 - (np.minimum(nearest, distance) / distance) ** 2, 0)
        occl[group] = w.reshape(len(group), rays)
    ao = 1 - occl.mean(axis=1)
    return np.clip(ao, 0.25, 1.0)


def _ao_key(points, normals, tris, distance):
    h = hashlib.sha1()
    for a in (points, normals, tris):
        h.update(np.round(np.asarray(a, np.float64), 7).tobytes())
    h.update(struct.pack("<dI", distance, AO_VERSION))
    return h.hexdigest()


def cached_ao(points, normals, tris, distance):
    key = _ao_key(points, normals, tris, distance)
    path = os.path.join(CACHE, key + ".npy")
    if os.path.exists(path):
        return np.load(path)
    ao = bake_ao(points, normals, tris, distance)
    os.makedirs(CACHE, exist_ok=True)
    np.save(path, ao)
    return ao


# ------------------------------------------------------------------------------------------------ applying
def set_stats(name):
    """(metallic factor, roughness factor) a material with this set falls back to without textures."""
    from PIL import Image
    path = pt.set_path(name, "_s")
    if not os.path.exists(path):
        return None
    s = np.asarray(Image.open(path).convert("RGBA"), np.float64)
    metal = float((s[..., 1] >= 230).mean())
    rough = float((((255 - s[..., 0]) / 255) ** 2).mean())
    return (1.0 if metal > 0.5 else 0.0), round(rough, 3)


def apply(model, verbose=True, path=None):
    """Applies the model's table to its GLB (or to `path`, e.g. a copy for a dry run)."""
    spec = MODELS[model]
    path = path or glb_path(model)
    if not os.path.exists(path):
        if verbose:
            print(f"pbr: {model}: no file yet, skipped")
        return False
    g = Glb(path)
    j = g.json
    mats = j.get("materials", [])
    names = [m.get("name") for m in mats]
    meshes = j.get("meshes", [])
    # 1. split shared materials by region (by mesh); an existing split material is reused
    for src, parts in spec.get("split", {}).items():
        if src not in names:
            continue
        for new_name, mesh_names in parts:
            if new_name in names:
                idx = names.index(new_name)
            else:
                m = copy.deepcopy(mats[names.index(src)])
                m["name"] = new_name
                mats.append(m)
                names.append(new_name)
                idx = len(mats) - 1
            for mesh in meshes:
                if mesh.get("name") in mesh_names:
                    for p in mesh["primitives"]:
                        if p.get("material") == names.index(src):
                            p["material"] = idx
    # 2. texture sets, regions, factors
    table = spec["materials"]
    missing = [nm for nm in names if nm not in table]
    for m in mats:
        entry = table.get(m.get("name"))
        if entry is None:
            continue
        set_name, region = entry
        extras = dict(m.get("extras") or {})
        extras["region"] = region
        if set_name and m.get("alphaMode", "OPAQUE") not in NO_AO_ALPHA:
            extras["pbr"] = f"textures/pbr/{set_name}"
            extras["uvScale"] = pt.uv_scale(set_name)
            pbr = m.setdefault("pbrMetallicRoughness", {})
            alpha = pbr.get("baseColorFactor", [1, 1, 1, 1])[3]
            pbr["baseColorFactor"] = [1.0, 1.0, 1.0, float(alpha)]
            pbr.pop("baseColorTexture", None)
            stats = set_stats(set_name)
            if stats is not None:
                pbr["metallicFactor"], pbr["roughnessFactor"] = stats
        else:
            extras.pop("pbr", None)
            extras.pop("uvScale", None)
        m["extras"] = extras
    # 3. baked AO into COLOR_0
    W, parent = world_matrices(j)
    nodes = j.get("nodes", [])
    ao_spec = spec["ao"]
    root = ao_spec.get("root")
    separate = ao_spec.get("separate", [])
    entries = []                                    # (node, mesh, primitive index, world positions, normals)
    for i, n in enumerate(nodes):
        if "mesh" not in n:
            continue
        inside = root is None or under(j, parent, i, root)    # helpers outside it (fx_shell) only shade themselves
        M = W[i]
        if abs(np.linalg.det(M[:3, :3])) < 1e-15:     # collapsed at rest (scale 0, e.g. the KS-3's shell_load):
            M, inside = np.eye(4), False                # baked in its own space, shading only itself
        nm = np.linalg.inv(M[:3, :3]).T
        for pi, p in enumerate(meshes[n["mesh"]]["primitives"]):
            pos = g.accessor(p["attributes"]["POSITION"]).astype(np.float64)
            nor = g.accessor(p["attributes"]["NORMAL"]).astype(np.float64) if "NORMAL" in p["attributes"] \
                else np.tile([0, 1.0, 0], (len(pos), 1))
            idx = g.accessor(p["indices"]).reshape(-1, 3).astype(np.int64) if "indices" in p \
                else np.arange(len(pos)).reshape(-1, 3)
            wp = pos @ M[:3, :3].T + M[:3, 3]
            wn = nor @ nm.T
            entries.append((i, n["mesh"], pi, wp, wn, idx, mats[p["material"]] if "material" in p else {}, inside))

    def blocks(mat):
        return mat.get("alphaMode", "OPAQUE") not in NO_AO_ALPHA

    def group(i):
        """Index of the `separate` group holding node i (it or an ancestor is named there), -1 = the main group."""
        while i is not None:
            for k, names_ in enumerate(separate):
                if nodes[i].get("name") in names_:
                    return k
            i = parent.get(i)
        return -1

    baked = {}
    for e in entries:
        node, mesh, pi, wp, wn, idx, mat, inside = e
        if (mesh, pi) in baked:                 # a mesh instanced by several nodes keeps its first bake
            continue
        if not blocks(mat) or any(v > 0 for v in mat.get("emissiveFactor", [0, 0, 0])):
            baked[(mesh, pi)] = None
            continue
        if ao_spec["mode"] == "per_mesh" or not inside:
            occ = [o for o in entries if o[1] == mesh and blocks(o[6])]
        else:
            mine = group(node)
            occ = [o for o in entries if o[7] and blocks(o[6]) and group(o[0]) == mine]
        tris = np.concatenate([o[3][o[5]] for o in occ], 0) if occ else np.zeros((0, 3, 3))
        baked[(mesh, pi)] = cached_ao(wp, wn, tris, ao_spec["distance"])
    for (mesh, pi), ao in baked.items():
        p = meshes[mesh]["primitives"][pi]
        if ao is None:
            p["attributes"].pop("COLOR_0", None)
            continue
        a8 = np.clip(np.round(ao * 255), 0, 255).astype(np.uint8)
        rgba = np.stack([a8, a8, a8, np.full_like(a8, 255)], -1)
        p["attributes"]["COLOR_0"] = g.add_accessor(rgba, "VEC4", 5121, normalized=True)
    g.compact()
    g.save(path)
    if verbose:
        sets = sorted({m["extras"]["pbr"].split("pbr/")[1] for m in mats if "pbr" in m.get("extras", {})})
        aos = [v for v in baked.values() if v is not None]
        mean_ao = float(np.mean(np.concatenate(aos))) if aos else 1.0
        print(f"pbr: {model}: {len(mats)} materials ({len(sets)} sets), AO on "
              f"{sum(1 for v in baked.values() if v is not None)} primitives, mean {mean_ao:.2f}"
              + (f"; NOT MAPPED: {missing}" if missing else ""))
    return True


def main(argv):
    want = [a for a in argv if a in MODELS] or list(MODELS)
    for model in want:
        apply(model)


if __name__ == "__main__":
    main(sys.argv[1:])
