"""Looks at the LabPBR texture sets offline (VIEWMODEL_SPEC section 20), shaded roughly like the runtime does.

python -I tools/assets/pbr_preview.py sets [prefix ...]   -> tools/assets/out/pbr_sets_<group>.png
    per set: albedo | normal | roughness / metal / emission | a lit tube | a raking-light swatch
python -I tools/assets/pbr_preview.py models               -> tools/assets/out/pbr_<model>.png (turntables, every
                                                              weapon and attachment with its texture sets and AO)
python -I tools/assets/pbr_preview.py skins                -> tools/assets/out/pbr_skins_<weapon>.png"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import pbr_textures as pt                 # noqa: E402

OUT = os.path.join(HERE, "out")


def srgb_to_linear(c):
    c = np.asarray(c, dtype=np.float64)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


# ------------------------------------------------------------------------------------------- loading a set
_cache = {}


def load_set(name):
    """-> dict albedo (H,W,3 linear), normal (H,W,3 tangent space, DirectX y = +v), ao, height, rough, metal (bool),
    f0 (H,W), emission (H,W) — the decoded LabPBR maps of textures/pbr/<name>."""
    if name in _cache:
        return _cache[name]
    rd = lambda s: np.asarray(Image.open(pt.set_path(name, s)).convert("RGBA"), np.float64) / 255.0
    a, n, s = rd(""), rd("_n"), rd("_s")
    nx, ny = n[..., 0] * 2 - 1, n[..., 1] * 2 - 1
    nz = np.sqrt(np.clip(1 - nx * nx - ny * ny, 0, 1))
    g = s[..., 1] * 255
    out = {"albedo": srgb_to_linear(a[..., :3]), "normal": np.stack([nx, ny, nz], -1), "ao": n[..., 2],
           "height": n[..., 3], "rough": (1 - s[..., 0]) ** 2, "metal": g >= 229.5,
           "f0": np.where(g >= 229.5, 0, g / 255), "emission": np.where(s[..., 3] > 254.5 / 255, 0, s[..., 3] * 255 / 254)}
    _cache[name] = out
    return out


def sample(img, u, v):
    """Bilinear, wrapping; u, v in repeats (texel centres at (i + 0.5) / size)."""
    h, w = img.shape[:2]
    x = u * w - 0.5
    y = v * h - 0.5
    x0 = np.floor(x).astype(np.int64)
    y0 = np.floor(y).astype(np.int64)
    fx, fy = x - x0, y - y0
    if img.ndim == 3:
        fx, fy = fx[..., None], fy[..., None]
    x0 %= w
    y0 %= h
    x1, y1 = (x0 + 1) % w, (y0 + 1) % h
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x1] * fx * (1 - fy) + img[y1, x0] * (1 - fx) * fy
            + img[y1, x1] * fx * fy)


# ------------------------------------------------------------------------------------------- shading
KEY = np.array([0.38, 0.82, 0.43])
FILL = np.array([-0.80, 0.18, 0.32])


def _ggx(n, v, l, rough, f0):
    h = l + v
    h = h / (np.linalg.norm(h, axis=-1, keepdims=True) + 1e-9)
    ndl = np.clip(np.sum(n * l, -1), 0, 1)
    ndv = np.clip(np.sum(n * v, -1), 1e-4, 1)
    ndh = np.clip(np.sum(n * h, -1), 0, 1)
    vdh = np.clip(np.sum(v * h, -1), 0, 1)
    a = np.maximum(rough, 0.02) ** 2
    d = a * a / (np.pi * ((ndh * ndh) * (a * a - 1) + 1) ** 2)
    k = (rough + 1) ** 2 / 8
    g = ndl / (ndl * (1 - k) + k) * ndv / (ndv * (1 - k) + k)
    f = f0 + (1 - f0) * ((1 - vdh) ** 5)[..., None]
    spec = (d * g / (4 * ndl * ndv + 1e-4))[..., None] * f
    return spec, ndl


def shade(m, n_world, t_world, b_world, v, uv_u, uv_v, vertex_ao=None, light_scale=1.0):
    """Studio-lit GGX shading of a texture set on a surface (all vectors (..., 3), view space)."""
    alb = sample(m["albedo"], uv_u, uv_v)
    nt = sample(m["normal"], uv_u, uv_v)
    ao = sample(m["ao"], uv_u, uv_v)
    rough = sample(m["rough"], uv_u, uv_v)
    metal = sample(m["metal"].astype(float), uv_u, uv_v)[..., None]
    f0d = sample(m["f0"], uv_u, uv_v)
    em = sample(m["emission"], uv_u, uv_v)
    n = nt[..., 0:1] * t_world + nt[..., 1:2] * b_world + nt[..., 2:3] * n_world
    n = n / (np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9)
    f0 = np.where(metal > 0.5, alb, f0d[..., None] * np.ones(3))
    diff = alb * (1 - metal)
    occ = ao if vertex_ao is None else ao * vertex_ao
    col = np.zeros(alb.shape)
    for L, k in ((KEY / np.linalg.norm(KEY), 2.6), (FILL / np.linalg.norm(FILL), 0.7)):
        L = np.broadcast_to(L, n.shape)
        spec, ndl = _ggx(n, v, L, rough, f0)
        col += k * light_scale * (diff / np.pi * ndl[..., None] + spec * ndl[..., None])
    r = 2 * np.sum(n * v, -1, keepdims=True) * n - v
    sky = np.clip((r[..., 1:2] + 0.15) / 0.9, 0, 1)
    env = (1 - sky) * np.array([0.06, 0.06, 0.07]) + sky * np.array([0.80, 0.86, 0.95])
    env = env * (1 - 0.55 * rough[..., None]) + 0.25 * rough[..., None] * np.array([0.35, 0.37, 0.42])
    ndv = np.clip(np.sum(n * v, -1, keepdims=True), 0, 1)
    fres = f0 + (1 - f0) * (1 - ndv) ** 5 * (1 - rough[..., None]) ** 2
    amb = 0.22 * np.array([0.42, 0.44, 0.50])
    col = col * (0.4 + 0.6 * occ[..., None]) + (diff * amb + fres * env * 0.9) * occ[..., None]
    col += alb * em[..., None] * 2.5
    return 1 - np.exp(-col * 1.25)


# ------------------------------------------------------------------------------------------- set sheets
def tube(m, scale, w=256, h=160):
    """A horizontal cylinder (radius 0.02 m) lit from the top right, the set wrapped like a barrel."""
    ys, xs = np.mgrid[0:h, 0:w]
    yn = (ys + 0.5) / h * 2 - 1
    xn = (xs + 0.5) / w * 2 - 1
    inside = np.abs(yn) < 0.92
    s = np.clip(yn / 0.92, -1, 1)
    ang = np.arcsin(s)
    n = np.stack([np.zeros_like(s), -s, np.cos(ang)], -1)
    t = np.broadcast_to(np.array([1.0, 0, 0]), n.shape)
    b = np.cross(n, t)
    v = np.broadcast_to(np.array([0, 0, 1.0]), n.shape)
    length = 0.12
    radius = 0.02
    u = (xn * 0.5 + 0.5) * length / pt.UV_UNIT * scale
    vv = (ang * radius) / pt.UV_UNIT * scale
    col = shade(m, n, t, -b, v, u, vv)
    img = np.where(inside[..., None], col, 0.12)
    return img


def swatch(m, scale, w=256, h=160):
    """A flat 6 x 4 cm swatch seen straight on, lit from a low angle (shows relief)."""
    ys, xs = np.mgrid[0:h, 0:w]
    u = (xs + 0.5) / w * 0.06 / pt.UV_UNIT * scale
    vv = (ys + 0.5) / h * 0.04 / pt.UV_UNIT * scale
    n = np.broadcast_to(np.array([0, 0, 1.0]), (h, w, 3))
    t = np.broadcast_to(np.array([1.0, 0, 0]), (h, w, 3))
    b = np.broadcast_to(np.array([0, -1.0, 0]), (h, w, 3))   # image rows go down: +v = -Y in view space
    v = np.broadcast_to(np.array([0, 0.3, 0.95]) / np.linalg.norm([0, 0.3, 0.95]), (h, w, 3))
    return shade(m, n, t, b, v, u, vv)


def maps(m, w=160, h=160):
    a = linear_to_srgb(m["albedo"])
    n = m["normal"] * 0.5 + 0.5
    r = np.stack([m["rough"], m["metal"].astype(float), np.clip(m["emission"], 0, 1)], -1)
    pick = lambda img: np.asarray(Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR)) / 255.0
    return [pick(a), pick(n), pick(r)]


def sets_sheet(prefixes):
    names = [n for n in pt.SETS if not prefixes or any(n.startswith(p) for p in prefixes)]
    rows = []
    for name in names:
        m = load_set(name)
        sc = pt.uv_scale(name)
        tiles = [linear_to_srgb(x) if i >= 3 else x for i, x in enumerate(maps(m) + [tube(m, sc), swatch(m, sc)])]
        row_h = 160
        row = np.full((row_h + 16, 160 * 3 + 256 * 2 + 20, 3), 0.1)
        x = 0
        for tile in tiles:
            th, tw = tile.shape[:2]
            row[16:16 + th, x:x + tw] = tile
            x += tw + 4
        im = Image.fromarray((np.clip(row, 0, 1) * 255).astype(np.uint8))
        ImageDraw.Draw(im).text((4, 2), f"{name}  ({pt.SETS[name].size}px, {pt.SETS[name].tile} m)", fill=(255, 255, 255))
        rows.append(np.asarray(im))
    sheet = np.concatenate(rows, 0)
    group = (prefixes[0].strip("/").replace("/", "_") if prefixes else "all") or "all"
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"pbr_sets_{group}.png")
    Image.fromarray(sheet).save(path)
    print(path)


# ------------------------------------------------------------------------------------------- models
def _set_name(path):
    return path.split("textures/pbr/", 1)[1] if path and "textures/pbr/" in path else None


def _flat_set(m):
    """A 1x1 'texture set' from a material without one (glTF factors and its texture's mean colour)."""
    base = np.asarray(m["base"][:3], float)
    if m.get("tex") is not None:
        base = base * m["tex"][:, :, :3].reshape(-1, 3).mean(0)
    em = np.asarray(m.get("emissive", (0, 0, 0)), float)
    glow = float(em.max() > 0)
    alb = np.maximum(base, em) if glow else base
    metal = m.get("metallic", 0) > 0.5
    return {"albedo": alb.reshape(1, 1, 3), "normal": np.array([0, 0, 1.0]).reshape(1, 1, 3),
            "ao": np.ones((1, 1)), "height": np.ones((1, 1)), "rough": np.full((1, 1), m.get("roughness", 0.5)),
            "metal": np.full((1, 1), metal), "f0": np.full((1, 1), 0.04), "emission": np.full((1, 1), glow)}


def render_pbr(items, cam, w, h, ss=2, bg=(0.55, 0.57, 0.60), layers=None):
    """Software render of raster items with their texture sets (normal maps, AO, GGX) -> (h, w, 3) uint8.
    layers: optional function(material dict) -> (set name or None, tint rgb or None) replacing a material's set
    (skin previews)."""
    import raster as rs
    W, H = w * ss, h * ss
    cam_ss = rs.Camera(W, H, ortho_half_h=cam.ortho)
    cam_ss.t, cam_ss.near, cam_ss.aspect = cam.t, cam.near, cam.aspect
    g = rs._gather(items, cam_ss)
    buf, data = rs.rasterize(items, cam_ss, W, H)
    img = np.tile(srgb_to_linear(np.asarray(bg, float)), (W * H, 1))
    if data is not None:
        P, N, UV, TRI, MID, mats = g
        aos = [np.asarray(it["ao"], float) if it.get("ao") is not None else np.ones(len(it["pos"]))
               for it in items if len(np.asarray(it["idx"]).reshape(-1, 3))]
        AO = np.concatenate(aos)
        res, _ = data
        for phase in ("opaque", "blend"):
            if phase not in res:
                continue
            f = res[phase]
            tv, bary, mid = f["tv"], f["bary"], f["mid"]
            n = f["nrm"]
            p0, p1, p2 = P[tv[:, 0]], P[tv[:, 1]], P[tv[:, 2]]
            u0, u1, u2 = UV[tv[:, 0]], UV[tv[:, 1]], UV[tv[:, 2]]
            e1, e2 = p1 - p0, p2 - p0
            d1, d2 = u1 - u0, u2 - u0
            det = d1[:, 0] * d2[:, 1] - d2[:, 0] * d1[:, 1]
            det = np.where(np.abs(det) < 1e-12, 1e-12, det)
            T = (e1 * d2[:, 1:2] - e2 * d1[:, 1:2]) / det[:, None]
            B = (e2 * d1[:, 0:1] - e1 * d2[:, 0:1]) / det[:, None]
            T = T - n * np.sum(T * n, 1, keepdims=True)
            T /= np.linalg.norm(T, axis=1, keepdims=True) + 1e-12
            B = B - n * np.sum(B * n, 1, keepdims=True) - T * np.sum(B * T, 1, keepdims=True)
            B /= np.linalg.norm(B, axis=1, keepdims=True) + 1e-12
            v = -f["pos"] / (np.linalg.norm(f["pos"], axis=1, keepdims=True) + 1e-12)
            flip = np.sum(n * v, 1) < 0
            n = np.where(flip[:, None], -n, n)
            ao = np.sum(AO[tv] * bary, 1)
            col = np.zeros((len(mid), 3))
            alpha = np.ones(len(mid))
            for mi in np.unique(mid):
                s = mid == mi
                m = mats[mi]
                name, tint_rgb = (layers(m) if layers else (None, None))
                name = name or _set_name(m.get("pbr"))
                if name:
                    ms = load_set(name)
                    from pbr_textures import uv_scale
                    sc = uv_scale(name)
                else:
                    ms, sc = _flat_set(m), 1.0
                if tint_rgb is not None:
                    ms = dict(ms, albedo=ms["albedo"] * srgb_to_linear(np.asarray(tint_rgb, float)))
                uv = f["uv"][s] * sc
                c = shade(ms, n[s], T[s], B[s], v[s], uv[:, 0], uv[:, 1], vertex_ao=ao[s])
                col[s] = c
                alpha[s] = m["base"][3] if m.get("alpha_mode") == "BLEND" else 1.0
            lin = col
            if phase == "opaque":
                img[f["pix"]] = lin
            else:
                for i in range(len(f["pix"])):
                    img[f["pix"][i]] = img[f["pix"][i]] * (1 - alpha[i]) + lin[i] * alpha[i]
    out = linear_to_srgb(img).reshape(H, W, 3).reshape(h, ss, w, ss, 3).mean(axis=(1, 3))
    return (out * 255 + 0.5).astype(np.uint8)


def _model_items(g, M, loadout=None):
    import loadouts as lo
    if loadout is not None and "viewmodel" in g.names:
        return lo.scene(g, None, None, 0.0, loadout, extra_matrix=M, with_arms=False)
    W = g.world_matrices({})
    items = []
    mats = lo.load_materials(g)
    for i, n in enumerate(g.nodes):
        if "mesh" in n and not n.get("name", "").startswith("fx_"):
            lo.instance(g, mats, n["mesh"], M @ W[i], items)
    return items


def turntable_pbr(path, out_path, loadout=None, views=((-150, 18), (-115, 8), (-65, 12), (-25, 24)),
                  size=(480, 360), fov=30.0, layers=None):
    """Turntable of a GLB (gun subtree in its own space, every mesh of other files) with the PBR shading."""
    import loadouts as lo
    import raster as rs
    from glbread import trs
    g = lo.load(path)
    root = next((r for r in ("gun", "knife", "attachment") if r in g.names), None)
    inv = np.eye(4)
    if root:
        W = g.world_matrices({})
        inv = np.linalg.inv(W[g.names[root]])
    # frame: bounds of the drawn meshes at identity
    items0 = _model_items(g, inv, loadout)
    allp = np.concatenate([it["pos"] for it in items0])
    lo_, hi_ = allp.min(0), allp.max(0)
    centre = (lo_ + hi_) / 2
    radius = np.linalg.norm(hi_ - lo_) / 2
    dist = radius / np.tan(np.radians(fov) / 2) * 0.62
    tiles = []
    for yaw, pitch in views:
        a, b = np.radians(yaw), np.radians(pitch)
        Ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
        Rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
        M = np.eye(4)
        M[:3, :3] = Rx @ Ry
        M[:3, 3] = -M[:3, :3] @ centre + np.array([0, 0, -dist])
        items = _model_items(g, M @ inv, loadout)
        tiles.append(render_pbr(items, rs.Camera(size[0], size[1], fov, near=0.01), size[0], size[1], layers=layers))
    sheet = np.concatenate([np.concatenate(tiles[:2], 1), np.concatenate(tiles[2:], 1)], 0)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    Image.fromarray(sheet).save(out_path)
    print(out_path)


def models_sheet(names=None):
    import pbr_apply as pa
    for model in pa.MODELS:
        if names and model not in names and not any(model.startswith(n) for n in names):
            continue
        path = pa.glb_path(model)
        if not os.path.exists(path):
            continue
        loadout = "default" if model.startswith("gun/") else None
        turntable_pbr(path, os.path.join(OUT, f"pbr_{model.replace('/', '_')}.png"), loadout)


def skins_sheet(names=None):
    """Each weapon in each of its region skins (texture sets and tints; shader effects are runtime-only)."""
    import skin_looks
    weapons = [("gun/p9", "p9", "gun"), ("gun/ak47", "ak47", "gun"), ("gun/awp", "awp", "gun"),
               ("knife/butterfly", "butterfly", "knife")]
    for model, wid, kind in weapons:
        if names and wid not in names:
            continue
        import pbr_apply as pa
        path = pa.glb_path(model)
        if not os.path.exists(path):
            continue
        skins = {k: v for k, v in skin_looks.weapon_skins(wid, kind).items() if v.get("regions")}
        for sid, skin in skins.items():
            regions = skin["regions"]

            def layers(m, regions=regions):
                region = m.get("region")
                layer = regions.get(region) or regions.get("*") if region and region != "none" else None
                if not layer:
                    return None, None
                return _set_name(layer.get("pbr")), layer.get("tint")
            turntable_pbr(path, os.path.join(OUT, f"pbr_skin_{wid}_{sid}.png"),
                          "default" if kind == "gun" else None, layers=layers)


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args or args[0] == "sets":
        sets_sheet(args[1:])
    elif args[0] == "models":
        models_sheet(args[1:])
    elif args[0] == "skins":
        skins_sheet(args[1:])
