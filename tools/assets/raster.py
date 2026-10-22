"""Numpy z-buffer rasterizer used for previews and icon rendering.

Draw items: dict(pos=(V,3) view-space, nrm=(V,3), uv=(V,2), idx=(T,3), mat=dict) where mat has
base (rgba linear), tex (H,W,4 linear float or None), nearest (bool), metallic, roughness, emissive,
alpha_mode ('OPAQUE'|'MASK'|'BLEND'), cutoff, double (bool), flat (bool).
View space = glTF camera space (+X right, +Y up, looking down -Z)."""
import numpy as np


class Camera:
    def __init__(self, width, height, fov_y_deg=70.0, near=0.02, ortho_half_h=None):
        self.w, self.h = width, height
        self.aspect = width / height
        self.t = np.tan(np.radians(fov_y_deg) / 2)
        self.near = near
        self.ortho = ortho_half_h

    def project(self, p):
        """-> screen x, y (pixels, y down) and depth key helpers."""
        if self.ortho:
            xn = p[:, 0] / (self.ortho * self.aspect)
            yn = p[:, 1] / self.ortho
            iw = np.ones(len(p))
            key = -p[:, 2]
        else:
            d = np.maximum(-p[:, 2], 1e-6)
            xn = p[:, 0] / d / (self.t * self.aspect)
            yn = p[:, 1] / d / self.t
            iw = 1.0 / d
            key = None
        sx = (xn + 1) * 0.5 * self.w
        sy = (1 - yn) * 0.5 * self.h
        return sx, sy, iw, key


def srgb_to_linear(c):
    c = np.asarray(c, dtype=np.float64)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


def sample(tex, uv, nearest=False):
    h, w = tex.shape[:2]
    u = uv[:, 0] * w - 0.5
    v = uv[:, 1] * h - 0.5
    if nearest:
        x = np.floor(u + 0.5).astype(np.int64) % w
        y = np.floor(v + 0.5).astype(np.int64) % h
        return tex[y, x]
    x0 = np.floor(u).astype(np.int64)
    y0 = np.floor(v).astype(np.int64)
    fx = (u - x0)[:, None]
    fy = (v - y0)[:, None]
    x0 %= w
    y0 %= h
    x1 = (x0 + 1) % w
    y1 = (y0 + 1) % h
    return (tex[y0, x0] * (1 - fx) * (1 - fy) + tex[y0, x1] * fx * (1 - fy)
            + tex[y1, x0] * (1 - fx) * fy + tex[y1, x1] * fx * fy)


def _gather(items, cam):
    P, N, UV, MID, TRI = [], [], [], [], []
    mats = []
    base = 0
    for it in items:
        idx = np.asarray(it["idx"], dtype=np.int64).reshape(-1, 3)
        if len(idx) == 0:
            continue
        mats.append(it["mat"])
        P.append(np.asarray(it["pos"], float))
        N.append(np.asarray(it["nrm"], float))
        UV.append(np.asarray(it["uv"], float) if it.get("uv") is not None else np.zeros((len(it["pos"]), 2)))
        TRI.append(idx + base)
        MID.append(np.full(len(idx), len(mats) - 1))
        base += len(it["pos"])
    if not P:
        return None
    return (np.concatenate(P), np.concatenate(N), np.concatenate(UV), np.concatenate(TRI),
            np.concatenate(MID), mats)


def _fragments(sx, sy, tris, W, H, chunk_px=1 << 21):
    """Yield (tri_index_array, pix, b0, b1, b2) for covered pixel centers."""
    x0, y0, x1, y1 = sx[tris[:, 0]], sy[tris[:, 0]], sx[tris[:, 1]], sy[tris[:, 1]]
    x2, y2 = sx[tris[:, 2]], sy[tris[:, 2]]
    area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
    bx0 = np.clip(np.floor(np.minimum(np.minimum(x0, x1), x2) - 0.5), 0, W).astype(np.int64)
    bx1 = np.clip(np.ceil(np.maximum(np.maximum(x0, x1), x2) + 0.5), 0, W).astype(np.int64)
    by0 = np.clip(np.floor(np.minimum(np.minimum(y0, y1), y2) - 0.5), 0, H).astype(np.int64)
    by1 = np.clip(np.ceil(np.maximum(np.maximum(y0, y1), y2) + 0.5), 0, H).astype(np.int64)
    size = np.maximum(bx1 - bx0, by1 - by0)
    ok = (np.abs(area) > 1e-12) & (size > 0)
    B = np.ones(len(tris), dtype=np.int64)
    while True:
        more = ok & (size > B)
        if not more.any():
            break
        B[more] *= 2
    for b in np.unique(B[ok]):
        sel = np.nonzero(ok & (B == b))[0]
        gy, gx = np.divmod(np.arange(b * b), b)
        step = max(1, chunk_px // (b * b))
        for s in range(0, len(sel), step):
            t = sel[s:s + step]
            px = bx0[t][:, None] + gx[None, :]
            py = by0[t][:, None] + gy[None, :]
            inb = (px < bx1[t][:, None]) & (py < by1[t][:, None])
            cx = px + 0.5
            cy = py + 0.5
            ar = area[t][:, None]
            w0 = ((x2[t][:, None] - x1[t][:, None]) * (cy - y1[t][:, None])
                  - (y2[t][:, None] - y1[t][:, None]) * (cx - x1[t][:, None])) / ar
            w1 = ((x0[t][:, None] - x2[t][:, None]) * (cy - y2[t][:, None])
                  - (y0[t][:, None] - y2[t][:, None]) * (cx - x2[t][:, None])) / ar
            w2 = 1 - w0 - w1
            inside = inb & (w0 >= -1e-9) & (w1 >= -1e-9) & (w2 >= -1e-9)
            r, c = np.nonzero(inside)
            if len(r) == 0:
                continue
            yield t[r], py[r, c] * W + px[r, c], w0[r, c], w1[r, c], w2[r, c]


def rasterize(items, cam, W, H, flat=False):
    """-> dict with per-pixel buffers: tri id (-1 = empty), item/material id, normal, uv, depth, view pos."""
    g = _gather(items, cam)
    out = {"mid": np.full(W * H, -1), "depth": np.full(W * H, np.inf)}
    if g is None:
        return out, None
    P, N, UV, TRI, MID, mats = g
    sx, sy, iw, key_o = cam.project(P)
    # cull: behind near plane, back faces (unless double sided)
    zt = P[TRI][:, :, 2]
    keep = np.all(-zt > cam.near, axis=1) if not cam.ortho else np.ones(len(TRI), bool)
    a = ((sx[TRI[:, 1]] - sx[TRI[:, 0]]) * (sy[TRI[:, 2]] - sy[TRI[:, 0]])
         - (sy[TRI[:, 1]] - sy[TRI[:, 0]]) * (sx[TRI[:, 2]] - sx[TRI[:, 0]]))
    front = a < 0                         # CCW in y-up == negative area in y-down pixels
    double = np.array([m.get("double", False) for m in mats])[MID]
    keep &= front | double
    blend = np.array([m.get("alpha_mode") == "BLEND" for m in mats])[MID]
    mask_m = np.array([m.get("alpha_mode") == "MASK" for m in mats])
    res = {}
    for phase in ("opaque", "blend"):
        sel = np.nonzero(keep & (blend if phase == "blend" else ~blend))[0]
        if len(sel) == 0:
            continue
        tris = TRI[sel]
        zbuf = out["depth"] if phase == "opaque" else None
        best_key = np.full(W * H, np.inf)
        best = {}
        for pas in (0, 1):
            for tl, pix, b0, b1, b2 in _fragments(sx, sy, tris, W, H):
                tg = sel[tl]
                tv = TRI[tg]
                if cam.ortho:
                    k = b0 * key_o[tv[:, 0]] + b1 * key_o[tv[:, 1]] + b2 * key_o[tv[:, 2]]
                    c0, c1, c2 = b0, b1, b2
                else:
                    q0, q1, q2 = b0 * iw[tv[:, 0]], b1 * iw[tv[:, 1]], b2 * iw[tv[:, 2]]
                    s = q0 + q1 + q2
                    k = 1.0 / s
                    c0, c1, c2 = q0 / s, q1 / s, q2 / s
                mm = MID[tg]
                if mask_m[mm].any():
                    drop = np.zeros(len(tg), bool)
                    for mi in np.unique(mm[mask_m[mm]]):
                        s_ = mm == mi
                        uv = (c0[s_, None] * UV[tv[s_, 0]] + c1[s_, None] * UV[tv[s_, 1]]
                              + c2[s_, None] * UV[tv[s_, 2]])
                        m = mats[mi]
                        alpha = sample(m["tex"], uv, m.get("nearest", False))[:, 3] * m["base"][3]
                        drop[np.nonzero(s_)[0]] = alpha < m.get("cutoff", 0.5)
                    keepf = ~drop
                    tg, tv, pix, k, c0, c1, c2 = tg[keepf], tv[keepf], pix[keepf], k[keepf], c0[keepf], c1[keepf], c2[keepf]
                if phase == "blend":
                    vis = k < out["depth"][pix]
                    best.setdefault("list", []).append((tg[vis], tv[vis], pix[vis], c0[vis], c1[vis], c2[vis]))
                    continue
                if pas == 0:
                    np.minimum.at(best_key, pix, k)
                else:
                    win = k <= best_key[pix]
                    best.setdefault("list", []).append((tg[win], tv[win], pix[win], c0[win], c1[win], c2[win]))
            if phase == "blend":
                break
        lists = best.get("list", [])
        if not lists:
            continue
        tg = np.concatenate([l[0] for l in lists])
        tv = np.concatenate([l[1] for l in lists])
        pix = np.concatenate([l[2] for l in lists])
        c = [np.concatenate([l[i] for l in lists])[:, None] for i in (3, 4, 5)]
        nrm = c[0] * N[tv[:, 0]] + c[1] * N[tv[:, 1]] + c[2] * N[tv[:, 2]]
        if flat:
            pv = P[tv]
            nrm = np.cross(pv[:, 1] - pv[:, 0], pv[:, 2] - pv[:, 0])
        nrm /= np.linalg.norm(nrm, axis=1)[:, None] + 1e-30
        uv = c[0] * UV[tv[:, 0]] + c[1] * UV[tv[:, 1]] + c[2] * UV[tv[:, 2]]
        pos = c[0] * P[tv[:, 0]] + c[1] * P[tv[:, 1]] + c[2] * P[tv[:, 2]]
        frag = {"pix": pix, "mid": MID[tg], "nrm": nrm, "uv": uv, "pos": pos}
        if phase == "opaque":
            out["depth"][pix] = best_key[pix]
            out["mid"][pix] = MID[tg]
        res[phase] = frag
    return out, (res, mats)


KEY = np.array([-0.45, 0.75, 0.50])
FILL = np.array([0.70, 0.15, 0.35])


def shade(frag, mats, view_dir_fn=None, exposure=1.6, key=KEY, fill=FILL, rim=0.25):
    """Simple Lambert + Blinn-Phong + hemisphere env. Returns (n,3) linear color and alpha."""
    nrm, mid, uv, pos = frag["nrm"], frag["mid"], frag["uv"], frag["pos"]
    n = len(mid)
    albedo = np.zeros((n, 3))
    alpha = np.ones(n)
    metal = np.zeros(n)
    rough = np.zeros(n)
    emis = np.zeros((n, 3))
    for mi in np.unique(mid):
        s = mid == mi
        m = mats[mi]
        col = np.tile(np.asarray(m["base"], float), (s.sum(), 1))
        if m.get("tex") is not None:
            col = col * sample(m["tex"], uv[s], m.get("nearest", False))
        albedo[s] = col[:, :3]
        alpha[s] = col[:, 3]
        metal[s] = m.get("metallic", 0.0)
        rough[s] = m.get("roughness", 0.5)
        emis[s] = m.get("emissive", (0, 0, 0))
    v = -pos / (np.linalg.norm(pos, axis=1)[:, None] + 1e-30)
    if view_dir_fn is not None:
        v = view_dir_fn(pos)
    nrm = np.where((np.sum(nrm * v, 1) < 0)[:, None], -nrm, nrm)   # double sided / back-lit fix
    up = nrm[:, 1:2]
    hemi = (0.5 + 0.5 * up) * np.array([0.36, 0.38, 0.43]) + (0.5 - 0.5 * up) * np.array([0.13, 0.12, 0.11])
    spec_col = 0.04 * (1 - metal[:, None]) + albedo * metal[:, None]
    diff_col = albedo * (1 - metal[:, None])
    color = diff_col * hemi
    shin = np.clip(2.0 / np.maximum(rough, 0.05) ** 4 - 2.0, 4, 600)
    for L, k in ((key / np.linalg.norm(key), 1.25), (fill / np.linalg.norm(fill), 0.35)):
        ndl = np.clip(nrm @ L, 0, 1)
        h = L[None, :] + v
        h /= np.linalg.norm(h, axis=1)[:, None]
        ndh = np.clip(np.sum(nrm * h, 1), 0, 1)
        spec = (shin + 8) / (8 * np.pi) * ndh ** shin * ndl
        color += k * (diff_col * ndl[:, None] + spec_col * spec[:, None] * 0.8)
    r = 2 * np.sum(nrm * v, 1)[:, None] * nrm - v
    env = (0.5 + 0.5 * r[:, 1:2]) * np.array([0.55, 0.58, 0.65]) + (0.5 - 0.5 * r[:, 1:2]) * np.array([0.10, 0.09, 0.08])
    color += spec_col * env * (1 - 0.6 * rough[:, None])
    fres = (1 - np.clip(np.sum(nrm * v, 1), 0, 1)) ** 4
    color += rim * fres[:, None] * np.array([0.5, 0.55, 0.6]) * (0.3 + albedo)
    color += emis
    color = 1 - np.exp(-color * exposure)
    return color, alpha


def render(items, cam, bg=(0.18, 0.19, 0.21), ss=2, flat=False, shade_kw=None):
    """-> (H, W, 3) uint8 sRGB image (supersampled)."""
    W, H = cam.w * ss, cam.h * ss
    cam_ss = Camera(W, H, ortho_half_h=cam.ortho)
    cam_ss.t, cam_ss.near, cam_ss.aspect = cam.t, cam.near, cam.aspect
    buf, data = rasterize(items, cam_ss, W, H, flat=flat)
    img = np.empty((W * H, 3))
    img[:] = srgb_to_linear(np.asarray(bg))
    if data is not None:
        res, mats = data
        for phase in ("opaque", "blend"):
            if phase in res:
                f = res[phase]
                col, a = shade(f, mats, **(shade_kw or {}))
                if phase == "opaque":
                    img[f["pix"]] = col
                else:
                    for i in range(len(f["pix"])):
                        p = f["pix"][i]
                        img[p] = img[p] * (1 - a[i]) + col[i] * a[i]
    img = linear_to_srgb(img).reshape(H, W, 3)
    img = img.reshape(cam.h, ss, cam.w, ss, 3).mean(axis=(1, 3))
    return (img * 255 + 0.5).astype(np.uint8)
