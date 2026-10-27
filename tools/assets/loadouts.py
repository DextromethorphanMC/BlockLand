"""Weapon loadouts for the tools (VIEWMODEL_SPEC section 19): which attachment model sits on which mount, the
weapon nodes a fitted slot hides, and the composed first-person scene (weapon + attachments at their mounts + the
player's arms) that previews, coverage and the ADS checks render. Mirrors what the runtime draws."""
import os

import numpy as np

import raster as rs
from glbread import GLTF

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland")
GUN_DIR = os.path.join(ASSETS, "models", "gun")
KNIFE_DIR = os.path.join(ASSETS, "models", "knife")
ATTACH_DIR = os.path.join(ASSETS, "models", "attachment")

# slot -> mount node in the weapon file
SLOTS = {"optic": "mount_optic", "laser": "mount_laser", "barrel": "mount_barrel", "stock": "mount_stock",
         "charm": "mount_charm", "underbarrel": "mount_underbarrel"}
# slots only some weapons offer (section 35): their mount is not required in every weapon file
OPTIONAL_SLOTS = ("underbarrel",)
# attachment model -> (slot, helper nodes it must carry)
MODELS = {"mini_reddot": ("optic", ("aim",)), "reddot": ("optic", ("aim",)), "laser": ("laser", ("emitter",)),
          "suppressor": ("barrel", ("muzzle",)), "tactical_stock": ("stock", ()), "charm_grass": ("charm", ("pivot",)),
          "scope_2x": ("optic", ("aim",)), "scope_sniper": ("optic", ("aim",)), "holo": ("optic", ("aim",)),
          "heavy_stock": ("stock", ()), "wire_stock": ("stock", ()),
          # section 35: the rectangular red dot, the underbarrel slot
          "reddot_rect": ("optic", ("aim",)), "vertical_grip": ("underbarrel", ()), "angled_grip": ("underbarrel", ()),
          "bipod": ("underbarrel", ()), "tommy_grip": ("underbarrel", ())}
# magnified optics (section 22): eyepiece glass instead of a reticle quad
SCOPES = ("scope_2x", "scope_sniper")
# reflex sights without a reticle node: the runtime draws the reticle in the `glass` window (the holographic sight)
GLASS_RETICLE = ("holo",)
# mounts a weapon file must carry when it does not offer every slot (section 23: no laser / stock on the AWP)
MOUNTS_OF = {"awp": ("mount_optic", "mount_barrel", "mount_charm"),
             # section 26: dual guns do not aim and carry no stock; both guns have the laser and barrel mounts
             "usp_dual": ("mount_laser", "mount_barrel", "mount_charm", "mount_laser_L", "mount_barrel_L"),
             "usp_dual_cerberus": ("mount_laser", "mount_barrel", "mount_charm", "mount_laser_L", "mount_barrel_L")}
# section 26: a dual gun's left gun repeats every node of the right one with this suffix
DUAL_SUFFIX = "_L"
# weapon nodes hidden while a slot is fitted (the `hides` lists of the slots in guns.json / knives.json)
HIDES = {"ak47": {"barrel": ("muzzle_brake",), "stock": ("stock",),
                  "optic": ("sight_rear", "sight_iron", "front_sight_ears")},
         "tommy": {"barrel": ("muzzle_brake",), "stock": ("stock",), "optic": ("sight_rear", "sight_iron")},
         "ks3": {"stock": ("stock",), "optic": ("sight_rear", "sight_iron")},
         "awp": {"barrel": ("muzzle_brake",)},
         "rpd": {"barrel": ("muzzle_brake",), "stock": ("stock",), "optic": ("sight_rear", "sight_iron")}}
# weapons whose magazine nodes have names of their own: loadout magazine -> node (section 24: stick / drum,
# section 25: the KS-3's tube magazines)
MAG_NAMES = {"tommy": {"mag_standard": "mag_stick", "mag_extended": "mag_drum"},
             "ks3": {"mag_standard": "tube_standard", "mag_extended": "tube_extended"}}
# attachments that v1 files (before section 19) carried inside the weapon file
LEGACY = ("sight_reddot", "laser")

LOADOUTS = {
    "default": {"slots": {}, "mag": "mag_standard"},
    "largest": {"slots": {"optic": "reddot", "laser": "laser", "barrel": "suppressor", "stock": "tactical_stock",
                          "charm": "charm_grass"}, "mag": "mag_extended"},
    "mini": {"slots": {"optic": "mini_reddot"}, "mag": "mag_standard"},
    "reddot": {"slots": {"optic": "reddot"}, "mag": "mag_standard"},
    "suppressed": {"slots": {"barrel": "suppressor"}, "mag": "mag_standard"},
    "stock": {"slots": {"stock": "tactical_stock"}, "mag": "mag_standard"},
    "charm": {"slots": {"charm": "charm_grass"}, "mag": "mag_standard"},
    "laser": {"slots": {"laser": "laser"}, "mag": "mag_standard"},
    "mini_full": {"slots": {"optic": "mini_reddot", "laser": "laser", "barrel": "suppressor",
                            "stock": "tactical_stock", "charm": "charm_grass"}, "mag": "mag_extended"},
    # section 22: the scopes with everything else fitted, and alone
    "sniper_full": {"slots": {"optic": "scope_sniper", "laser": "laser", "barrel": "suppressor",
                              "stock": "tactical_stock", "charm": "charm_grass"}, "mag": "mag_extended"},
    "scope2x_full": {"slots": {"optic": "scope_2x", "laser": "laser", "barrel": "suppressor",
                               "stock": "tactical_stock", "charm": "charm_grass"}, "mag": "mag_extended"},
    "sniper": {"slots": {"optic": "scope_sniper"}, "mag": "mag_standard"},
    "scope2x": {"slots": {"optic": "scope_2x"}, "mag": "mag_standard"},
    # the holographic sight with everything else fitted, and alone
    "holo_full": {"slots": {"optic": "holo", "laser": "laser", "barrel": "suppressor", "stock": "tactical_stock",
                            "charm": "charm_grass"}, "mag": "mag_extended"},
    "holo": {"slots": {"optic": "holo"}, "mag": "mag_standard"},
    # section 28: the other stocks with everything else fitted
    "heavy_full": {"slots": {"optic": "reddot", "laser": "laser", "barrel": "suppressor", "stock": "heavy_stock",
                             "charm": "charm_grass"}, "mag": "mag_extended"},
    "wire_full": {"slots": {"optic": "reddot", "laser": "laser", "barrel": "suppressor", "stock": "wire_stock",
                            "charm": "charm_grass"}, "mag": "mag_extended"},
    # section 35: the rectangular red dot and each underbarrel attachment with everything else fitted
    "rect_full": {"slots": {"optic": "reddot_rect", "laser": "laser", "barrel": "suppressor", "stock": "tactical_stock",
                            "charm": "charm_grass", "underbarrel": "vertical_grip"}, "mag": "mag_extended"},
    "angled_full": {"slots": {"optic": "holo", "laser": "laser", "barrel": "suppressor", "stock": "heavy_stock",
                              "charm": "charm_grass", "underbarrel": "angled_grip"}, "mag": "mag_extended"},
    "bipod_full": {"slots": {"optic": "scope_sniper", "laser": "laser", "barrel": "suppressor",
                             "stock": "tactical_stock", "charm": "charm_grass", "underbarrel": "bipod"},
                   "mag": "mag_extended"},
    "rect": {"slots": {"optic": "reddot_rect"}, "mag": "mag_standard"},
}
OPTICS = ("mini_reddot", "reddot", "holo", "scope_2x", "scope_sniper", "reddot_rect")

_models = {}
_materials = {}


def weapon_name(g):
    return os.path.basename(getattr(g, "path", "") or "")[:-4]


def load(path):
    g = GLTF(path)
    g.path = path
    return g


def model(name):
    """Attachment GLB by model name (cached)."""
    if name not in _models:
        _models[name] = load(os.path.join(ATTACH_DIR, f"{name}.glb"))
    return _models[name]


def is_v2(g):
    """Section 19 file: attachments are separate models at mount nodes."""
    return any(m in g.names for m in SLOTS.values())


# ------------------------------------------------------------------------------------------- materials
def load_materials(g):
    key = id(g)
    if key in _materials and _materials[key][0] is g:      # ids of freed models get reused: check the owner
        return _materials[key][1]
    mats = []
    samplers = g.json.get("samplers", [])
    for m in g.json.get("materials", []):
        pbr = m.get("pbrMetallicRoughness", {})
        extras = m.get("extras") or {}
        tex, nearest = None, False
        if "baseColorTexture" in pbr:
            ti = pbr["baseColorTexture"]["index"]
            tex = g.image(ti).copy()
            tex[:, :, :3] = rs.srgb_to_linear(tex[:, :, :3])
            s = g.json["textures"][ti].get("sampler")
            nearest = s is not None and samplers[s].get("magFilter") == 9728
        if extras.get("pbr"):                 # section 20: the texture set's albedo (sRGB PNG) replaces the texture
            tex = pbr_albedo(extras["pbr"])
        mats.append({"name": m.get("name"), "base": np.array(pbr.get("baseColorFactor", [1, 1, 1, 1]), float),
                     "tex": tex, "nearest": nearest, "metallic": pbr.get("metallicFactor", 1.0),
                     "roughness": pbr.get("roughnessFactor", 1.0),
                     "emissive": np.array(m.get("emissiveFactor", [0, 0, 0]), float),
                     "alpha_mode": m.get("alphaMode", "OPAQUE"), "cutoff": m.get("alphaCutoff", 0.5),
                     "double": m.get("doubleSided", False), "pbr": extras.get("pbr"),
                     "uv_scale": float(extras.get("uvScale", 1.0)), "region": extras.get("region")})
    _materials[key] = (g, mats)
    return mats


_albedo = {}


def pbr_albedo(path):
    """Linear RGBA albedo of a texture set (`textures/pbr/<set>` under assets/blockland), cached; None if missing."""
    if path not in _albedo:
        from PIL import Image
        f = os.path.join(ASSETS, *path.split("/")) + ".png"
        if os.path.exists(f):
            a = np.asarray(Image.open(f).convert("RGBA"), np.float64) / 255.0
            a[:, :, :3] = rs.srgb_to_linear(a[:, :, :3])
            _albedo[path] = a
        else:
            _albedo[path] = None
    return _albedo[path]


def instance(g, mats, mesh_index, M, items, mat_override=None):
    R = M[:3, :3]
    if abs(np.linalg.det(R)) < 1e-12:
        return
    nm = np.linalg.inv(R).T
    for p in g.mesh_primitives(mesh_index):
        nrm = p["normals"] @ nm.T
        nrm /= np.linalg.norm(nrm, axis=1)[:, None] + 1e-30
        mat = mats[p["material"]]
        if mat_override:
            mat = mat_override(mat)
        items.append({"pos": p["positions"] @ R.T + M[:3, 3], "nrm": nrm, "uv": p["uvs"], "idx": p["indices"],
                      "mat": mat, "ao": p["colors"][:, 0] if p.get("colors") is not None else None})


def subtree(g, names):
    """Node indices at or under any node called one of `names`."""
    out = set()
    for i in range(len(g.nodes)):
        j = i
        while True:
            if g.nodes[j].get("name") in names:
                out.add(i)
                break
            if j not in g.parent:
                break
            j = g.parent[j]
    return out


# ---------------------------------------------------------------------------------------------- scene
def spec(loadout):
    return LOADOUTS[loadout] if isinstance(loadout, str) else loadout


def hidden_nodes(g, loadout):
    """Weapon nodes not drawn with this loadout: helper roots (fx_shell), unfitted magazines, slot hides and (v1
    files) embedded attachments that are not fitted."""
    sp = spec(loadout)
    names = set()
    mags = [n.get("name") for n in g.nodes if str(n.get("name", "")).startswith(("mag_", "tube_"))   # box / tube
            and not str(n.get("name")).endswith(DUAL_SUFFIX)]
    want_mag = MAG_NAMES.get(weapon_name(g), {}).get(sp.get("mag", "mag_standard"), sp.get("mag", "mag_standard"))
    if want_mag not in mags and mags:
        want_mag = "mag_standard" if "mag_standard" in mags else mags[0]
    names.update(m for m in mags if m != want_mag)
    names.update(m + DUAL_SUFFIX for m in mags if m != want_mag and m + DUAL_SUFFIX in g.names)   # section 26
    slots = sp.get("slots", {})
    hides = HIDES.get(weapon_name(g), {})
    for slot in slots:
        names.update(hides.get(slot, ()))
    if not is_v2(g):
        if "optic" not in slots:
            names.add("sight_reddot")
        if "laser" not in slots:
            names.add("laser")
    hide = subtree(g, names)
    root = g.names.get("viewmodel")
    for i in range(len(g.nodes)):
        j = i
        while j in g.parent:
            j = g.parent[j]
        if root is not None and j != root:
            hide.add(i)
    return hide


def attachment_matrices(g, W, loadout):
    """[(model name, slot, {node index: world matrix})] for every attachment fitted at a mount of `g`."""
    out = []
    if not is_v2(g):
        return out
    for slot, name in spec(loadout).get("slots", {}).items():
        # section 26: a dual gun's left twin of a mount carries the same attachment
        for mount in (SLOTS[slot], SLOTS[slot] + DUAL_SUFFIX):
            if mount not in g.names:
                continue
            a = model(name)
            A = a.world_matrices({})
            Mm = W[g.names[mount]]
            out.append((name, slot, {i: Mm @ m for i, m in A.items()}))
    return out


def scene(g, arms, anim, t, loadout="default", arm_variant="wide", extra_matrix=None, with_arms=True,
          draw_slots=None):
    """Raster items of the first-person scene (view space) at time t of `anim` (None = rest pose).
    draw_slots: only draw the attachments of these slots (the others still hide what they hide); None = all."""
    W = g.world_matrices(g.sample(anim, t) if anim is not None else {})
    if extra_matrix is not None:
        W = {k: extra_matrix @ v for k, v in W.items()}
    items = []
    mats = load_materials(g)
    hide = hidden_nodes(g, loadout)
    for i, n in enumerate(g.nodes):
        if "mesh" in n and i not in hide:
            instance(g, mats, n["mesh"], W[i], items)
    for name, slot, AW in attachment_matrices(g, W, loadout):
        if draw_slots is not None and slot not in draw_slots:
            continue
        a = model(name)
        amats = load_materials(a)
        for i, n in enumerate(a.nodes):
            if "mesh" in n:
                instance(a, amats, n["mesh"], AW[i], items)
    if arms is not None and with_arms:      # section 16: every arms.glb mesh is drawn at its extras.joint (skinned)
        import armskin
        amats = load_materials(arms)
        for node in arms.nodes:
            ex = node.get("extras", {})
            if "mesh" in node and ex.get("variant") == arm_variant and ex.get("joint") in g.names:
                if g.names[ex["joint"]] in hide:
                    continue
                for p, pos, nrm in armskin.mesh_world(arms, node, g, W):
                    items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"],
                                  "mat": amats[p["material"]],
                                  "ao": p["colors"][:, 0] if p.get("colors") is not None else None})
    return items


def aim_matrix(g, optic=None):
    """Rest-pose world matrix of the eye point: aim_iron, or the fitted optic's `aim` (mount_optic x aim)."""
    W = g.world_matrices({})
    if optic is None:
        return W[g.names["aim_iron"]]
    a = model(optic)
    return W[g.names[SLOTS["optic"]]] @ a.world_matrices({})[a.names["aim"]]


# Section 40: the mounts and hidden parts of these guns follow their slots in guns.json (absent slots included: never
# assume every new weapon is an AK with all six mounts).
SECTION_40 = ("uzi", "p90", "mp5", "sten", "ssg08", "calico", "scar", "m4a1", "jackhammer", "vector", "revolver",
              "bizon")


def _section_40():
    import json
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources", "data",
                        "blockland", "guns", "guns.json")
    with open(path, encoding="utf-8") as f:
        guns = json.load(f)["guns"]
    for name in SECTION_40:
        slots = guns.get(name, {}).get("slots", {})
        # a slot without a model (the rounds: `ammo`) names the part its callout points at, not a mount
        MOUNTS_OF[name] = tuple(s["node"] for s in slots.values() if s.get("node", "").startswith("mount_"))
        HIDES[name] = {slot: tuple(s.get("hides", [])) for slot, s in slots.items() if s.get("hides")}


_section_40()
