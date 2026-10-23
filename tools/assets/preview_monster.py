"""Contact sheet for gen_monster_textures:  python -I tools/assets/preview_monster.py  -> out/monster_textures.png

The skin x8 (plain and with every box face outlined), the skin folded onto the zombie mesh + extra boxes (front,
three-quarter, side, back; the extras' placement is this preview's guess, the real model lives in the mod), small
"distance" views next to the vanilla zombie when the cached vanilla client jar is found, the effect icon, and the HUD
overlay + drip layer over a test scene.  Prints the size and UV-coverage checks."""
import io
import math
import os
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gen_monster_textures as gm                    # noqa: E402
import raster as rs                                  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "blockland")
OUT = os.path.join(HERE, "out")
VANILLA_JAR = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "minecraftforge", "forgegradle", "mavenizer",
                           "caches", "minecraft_tasks", "1.21.8", "client.jar")
FILES = {"skin": (("textures", "entity", "slime_licker.png"), (64, 64)),
         "overlay": (("textures", "misc", "slime_overlay.png"), (512, 256)),
         "drips": (("textures", "misc", "slime_overlay_drips.png"), (256, 256)),
         "icon": (("textures", "mob_effect", "slimed.png"), (18, 18))}


def load(parts):
    return np.asarray(Image.open(os.path.join(ASSETS, *parts)).convert("RGBA")).astype(float) / 255


def vanilla_zombie():
    try:
        with zipfile.ZipFile(VANILLA_JAR) as z:
            data = z.read("assets/minecraft/textures/entity/zombie/zombie.png")
        return np.asarray(Image.open(io.BytesIO(data)).convert("RGBA")).astype(float) / 255
    except (OSError, KeyError):
        return None


# ------------------------------------------------------------------------------------------------ model folding
def mc_cube(uv, origin, size, grow=0.0, mirror=False, tex=(64, 64)):
    """ModelPart.Cube's quads with the game's vertex order and UVs -> [(4x3 positions, 4x2 uvs)]."""
    (u, v), (ox, oy, oz), (w, h, d) = uv, origin, size
    x0, y0, z0 = ox - grow, oy - grow, oz - grow
    x1, y1, z1 = ox + w + grow, oy + h + grow, oz + d + grow
    if mirror:
        x0, x1 = x1, x0
    p7, p0, p1, p2 = (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)
    p3, p4, p5, p6 = (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)
    f4, f5, f6, f7, f8, f9 = u, u + d, u + d + w, u + d + 2 * w, u + 2 * d + w, u + 2 * d + 2 * w
    f10, f11, f12 = v, v + d, v + d + h
    polys = [((p4, p3, p7, p0), f5, f10, f6, f11),     # DOWN  (model -Y = the top of the box in the world)
             ((p1, p2, p6, p5), f6, f11, f7, f10),     # UP
             ((p7, p3, p6, p2), f4, f11, f5, f12),     # WEST  (the entity's right side)
             ((p0, p7, p2, p1), f5, f11, f6, f12),     # NORTH (front)
             ((p4, p0, p1, p5), f6, f11, f8, f12),     # EAST
             ((p3, p4, p5, p6), f8, f11, f9, f12)]     # SOUTH (back)
    quads = []
    for verts, u1, v1, u2, v2 in polys:
        uvs = np.array([(u2, v1), (u1, v1), (u1, v2), (u2, v2)], float)
        uvs += np.sign(uvs.mean(0) - uvs) * 0.01          # stay inside the face's texels
        verts = np.array(verts, float)
        if mirror:
            verts, uvs = verts[::-1], uvs[::-1]
        quads.append((verts, uvs / np.array(tex, float)))
    return quads


def _rot(xr, yr, zr):
    """Quaternionf.rotationZYX(z, y, x) as a matrix (ModelPart.translateAndRotate)."""
    cx, sx, cy, sy, cz, sz = math.cos(xr), math.sin(xr), math.cos(yr), math.sin(yr), math.cos(zr), math.sin(zr)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


ARM_X = -math.pi / 2.25                                  # AnimationUtils.animateZombieArms, not aggressive


def model_parts(extras=True):
    """(name, parent, pivot, rotation, [(texOffs, origin, size, grow, mirror)]) in model space (y down, -z front)."""
    b, e = gm.BASE_BOXES, gm.EXTRA_BOXES
    parts = [
        ("head", None, (0, 0, 0), (0, 0, 0), [(b["head"][0], (-4, -8, -4), (8, 8, 8), 0, False)]),
        ("hat", "head", (0, 0, 0), (0, 0, 0), [(b["hat"][0], (-4, -8, -4), (8, 8, 8), 0.5, False)]),
        ("body", None, (0, 0, 0), (0, 0, 0), [(b["body"][0], (-4, 0, -2), (8, 12, 4), 0, False)]),
        ("right_arm", None, (-5, 2, 0), (ARM_X, -0.1, 0), [(b["right_arm"][0], (-3, -2, -2), (4, 12, 4), 0, False)]),
        ("left_arm", None, (5, 2, 0), (ARM_X, 0.1, 0), [(b["right_arm"][0], (-1, -2, -2), (4, 12, 4), 0, True)]),
        ("right_leg", None, (-1.9, 12, 0), (0, 0, 0), [(b["right_leg"][0], (-2, 0, -2), (4, 12, 4), 0, False)]),
        ("left_leg", None, (1.9, 12, 0), (0, 0, 0), [(b["right_leg"][0], (-2, 0, -2), (4, 12, 4), 0, True)]),
    ]
    if extras:
        parts += [
            ("jaw", "head", (0, 0, -0.5), (0.45, 0, 0), [(e["jaw"][0], (-3, 0, -3.5), (6, 2, 4), 0, False)]),
            ("tongue_base", "jaw", (0, -0.6, -1.0), (0.55, 0, 0),
             [(e["tongue_base"][0], (-1, 0, -5), (2, 1, 5), 0, False)]),
            ("tongue_tip", "tongue_base", (0, 0, -5), (0.6, 0, 0),
             [(e["tongue_tip"][0], (-1, 0, -4), (2, 1, 4), 0, False)]),
            ("sac", "body", (0, 2, 2), (0, 0, 0), [(e["sac"][0], (-3, 0, 0), (6, 6, 3), 0, False)]),
        ]
    return parts


def model_quads(extras=True):
    mats, quads = {}, []
    for name, parent, pivot, rot, cubes in model_parts(extras):
        m = np.eye(4)
        m[:3, :3], m[:3, 3] = _rot(*rot), pivot
        mats[name] = (mats[parent] @ m) if parent else m
        for c in cubes:
            for verts, uvs in mc_cube(*c):
                quads.append((verts @ mats[name][:3, :3].T + mats[name][:3, 3], uvs))
    return quads


def _ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def _rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def render_model(tex, yaw, pitch=0.2, size=(300, 420), half=19.0, ss=2, bg=None, extras=True):
    """Orthographic render, model space -> world as the entity renderer does (yaw 0 = facing the camera)."""
    quads = model_quads(extras)
    to_view = _rx(pitch) @ _ry(yaw) @ np.diag([1.0, -1.0, -1.0])
    pos = np.concatenate([q[0] for q in quads]) @ to_view.T
    pos += np.array([0, 7.5, -200.0])
    uv = np.concatenate([q[1] for q in quads])
    k = np.arange(len(quads))[:, None] * 4
    idx = np.concatenate([k + [0, 1, 2], k + [0, 2, 3]])
    mat = {"base": np.ones(4), "tex": tex, "nearest": True, "alpha_mode": "MASK", "cutoff": 0.1, "double": True}
    W, H = size[0] * ss, size[1] * ss
    cam = rs.Camera(W, H, ortho_half_h=half)
    _, data = rs.rasterize([{"pos": pos, "nrm": np.zeros_like(pos), "uv": uv, "idx": idx, "mat": mat}], cam, W, H,
                           flat=True)
    img = np.empty((H * W, 3))
    if bg is None:
        img[:] = (0.20, 0.21, 0.23)
    else:
        img[:] = np.asarray(Image.fromarray((bg * 255).astype(np.uint8)).resize((W, H))).reshape(-1, 3) / 255
    f = data[0]["opaque"]
    col = rs.sample(tex, f["uv"], nearest=True)[:, :3]
    n = f["nrm"]
    l0 = np.array([-0.35, 0.80, 0.55]) / np.linalg.norm([-0.35, 0.80, 0.55])
    l1 = np.array([0.65, 0.20, 0.35]) / np.linalg.norm([0.65, 0.20, 0.35])
    shade = np.clip(0.42 + 0.58 * np.clip(n @ l0, 0, 1) + 0.22 * np.clip(n @ l1, 0, 1), 0, 1)
    img[f["pix"]] = col * shade[:, None]
    return img.reshape(size[1], ss, size[0], ss, 3).mean(axis=(1, 3))


# ------------------------------------------------------------------------------------------------- sheet helpers
def checker(h, w, cell=8, a=0.30, b=0.38):
    y, x = np.mgrid[0:h, 0:w]
    c = np.where((x // cell + y // cell) % 2 == 0, a, b)
    return np.dstack([c, c, c])


def over(rgba, bg):
    a = rgba[..., 3:4]
    return rgba[..., :3] * a + bg * (1 - a)


def upscale(img, k):
    return np.kron(img, np.ones((k, k, 1)))


def outlined_skin(skin, k=8):
    img = over(upscale(skin, k), checker(64 * k, 64 * k, k * 2))
    pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(pil)
    colors = {"head": (255, 220, 80), "hat": (120, 220, 255), "body": (255, 140, 60), "right_arm": (255, 90, 160),
              "right_leg": (170, 120, 255), "tongue_base": (255, 120, 220), "tongue_tip": (255, 170, 240),
              "sac": (140, 255, 120), "jaw": (255, 255, 255)}
    for name, (uv, size) in {**gm.BASE_BOXES, **gm.EXTRA_BOXES}.items():
        for face, (x, y, w, h) in gm.box_faces(uv, size).items():
            d.rectangle([x * k, y * k, (x + w) * k - 1, (y + h) * k - 1], outline=colors[name])
        x, y = uv
        d.text((x * k + 2, y * k + 1), name, fill=colors[name])
    return np.asarray(pil).astype(float) / 255


def scene(w, h):
    """A plain daylight test scene (sky, hills, blocks, crosshair, hotbar) to judge the HUD overlay."""
    y, x = np.mgrid[0:h, 0:w] + 0.5
    img = np.zeros((h, w, 3))
    sky = np.array([0.47, 0.65, 1.0]) * (1 - 0.3 * (y / h))[..., None] + 0.12
    img[:] = np.clip(sky, 0, 1)
    hill = h * 0.58 + 10 * np.sin(x / w * 7) + 6 * np.sin(x / w * 19)
    ground = y > hill
    img[ground] = (0.33, 0.55, 0.22)
    img[ground & (y > hill + h * 0.08)] = (0.45, 0.33, 0.22)
    for bx, by, bw, bh, c in ((0.18, 0.44, 0.10, 0.16, (0.50, 0.50, 0.52)), (0.62, 0.40, 0.08, 0.20,
                                                                              (0.55, 0.40, 0.24)),
                              (0.70, 0.30, 0.16, 0.12, (0.18, 0.40, 0.14)), (0.42, 0.50, 0.06, 0.09, (0.8, 0.8, 0.8))):
        m = (x > bx * w) & (x < (bx + bw) * w) & (y > by * h) & (y < (by + bh) * h)
        img[m] = c
    img[(np.abs(x - w / 2) < 1) & (np.abs(y - h / 2) < 7)] = 1
    img[(np.abs(y - h / 2) < 1) & (np.abs(x - w / 2) < 7)] = 1
    hb = (np.abs(x - w / 2) < w * 0.22) & (y > h * 0.9) & (y < h * 0.97)
    img[hb] = img[hb] * 0.4 + 0.25
    return img


def resize(img, w, h, linear):
    pil = Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8))
    return np.asarray(pil.resize((w, h), Image.BILINEAR if linear else Image.NEAREST)).astype(float) / 255


def label(pil, xy, text):
    ImageDraw.Draw(pil).text(xy, text, fill=(235, 235, 235))


# ---------------------------------------------------------------------------------------------------------- main
def check():
    ok = True
    imgs = {}
    for key, (parts, size) in FILES.items():
        img = load(parts)
        imgs[key] = img
        good = img.shape[1::-1] == size
        ok &= good
        print(f"{'/'.join(parts)}: {img.shape[1]}x{img.shape[0]} {'ok' if good else 'WRONG SIZE, want %s' % (size,)}")
    cov = gm.skin_coverage(imgs["skin"])
    for (box, face), c in cov.items():
        want = c >= 0.999 or box in gm.TRANSLUCENT_OK or (box in gm.ROUNDED and c >= 0.5)
        ok &= want
        if not want or c < 0.999:
            print(f"  {box}.{face}: {c:.0%} opaque{'' if want else '  <-- NOT COVERED'}")
    a = imgs["skin"][..., 3]
    part_alpha = a[(a > 0) & (a < 0.5)]
    print(f"skin texels with 0 < alpha < 0.5: {len(part_alpha)}")
    ok &= len(part_alpha) == 0
    ov = imgs["overlay"][..., 3]
    print(f"overlay alpha: centre {ov[96:160, 192:320].mean():.2f} (min {ov[96:160, 192:320].min():.2f}, "
          f"max {ov[96:160, 192:320].max():.2f}), top band {ov[:24].mean():.2f}, edges {ov[:, :8].mean():.2f}")
    dr = imgs["drips"]
    pm = dr[..., 3:] * dr[..., :3]                   # premultiplied, so invisible colour does not count
    seam_y, step_y = np.abs(pm[0] - pm[-1]).max(), np.abs(np.diff(pm, axis=0)).max()
    seam_x, step_x = np.abs(pm[:, 0] - pm[:, -1]).max(), np.abs(np.diff(pm, axis=1)).max()
    tiles = seam_y <= step_y and seam_x <= step_x
    ok &= tiles
    print(f"drips: coverage {np.mean(dr[..., 3] > 0.05):.1%}; wrap-around step y {seam_y:.2f} / x {seam_x:.2f} vs "
          f"largest inner step {step_y:.2f} / {step_x:.2f} -> {'tiles' if tiles else 'SEAM'}")
    print("checks:", "ok" if ok else "FAILED")
    return imgs


def main():
    imgs = check()
    skin, icon = imgs["skin"], imgs["icon"]
    os.makedirs(OUT, exist_ok=True)
    blocks = []
    # row 1: skin x8 plain + outlined
    plain = over(upscale(skin, 8), checker(512, 512, 16))
    blocks.append(("slime_licker.png x8   |   box faces", np.concatenate([plain, np.ones((512, 12, 3)) * 0.1,
                                                                         outlined_skin(skin)], 1)))
    # row 2: folded model views
    views = [render_model(skin, a) for a in (0.0, -0.65, -math.pi / 2, math.pi)]
    blocks.append(("folded onto the zombie mesh + extras: front, 3/4, side, back",
                   np.concatenate(sum([[v, np.ones((420, 6, 3)) * 0.1] for v in views], [])[:-1], 1)))
    # row 3: distance views (slime licker vs vanilla zombie) + icon
    bgh = np.concatenate([np.tile(np.array([0.55, 0.70, 1.0]), (60, 1, 1)) * np.linspace(1, 0.85, 60)[:, None, None],
                          np.tile(np.array([0.36, 0.56, 0.24]), (40, 1, 1))], 0)
    small = []
    van = vanilla_zombie()
    for tex, extras in ((skin, True),) + (((van, False),) if van is not None else ()):
        for yaw in (0.0, -0.65, math.pi):
            small.append(render_model(tex, yaw, size=(64, 96), half=19.0, ss=4, bg=bgh, extras=extras))
    tiny = [render_model(skin, 0.0, size=(32, 48), half=19.0, ss=6, bg=bgh)]
    if van is not None:
        tiny.append(render_model(van, 0.0, size=(32, 48), half=19.0, ss=6, bg=bgh, extras=False))
    srow = np.concatenate(sum([[s, np.ones((96, 4, 3)) * 0.1] for s in small], []), 1)
    tcol = np.concatenate([np.pad(t, ((0, 0), (0, 4), (0, 0)), constant_values=0.1) for t in tiny], 1)
    tcol = np.pad(tcol, ((0, 96 - 48), (0, 0), (0, 0)), constant_values=0.1)
    icon_panel = np.ones((96, 300, 3)) * 0.1
    for i, (k, bgc) in enumerate(((1, 0.78), (2, 0.78), (4, 0.78), (4, 0.16))):
        x0 = [4, 30, 72, 152][i]
        ic = upscale(icon, k)
        icon_panel[4:4 + 18 * k, x0:x0 + 18 * k] = over(ic, np.full(ic.shape[:2] + (3,), bgc))
    icon_panel[4:4 + 72, 228:228 + 72] = over(upscale(icon, 4), checker(72, 72, 8))
    blocks.append(("distance: slime licker (front, 3/4, back)%s, tiny   |   slimed.png x1 x2 x4 x4 x4"
                   % (" vs vanilla zombie" if van is not None else ""),
                   np.concatenate([srow, tcol, np.ones((96, 12, 3)) * 0.1, icon_panel], 1)))
    big_icon = over(upscale(icon, 8), checker(144, 144, 8))
    # row 4: overlay over the test scene, stretched to 16:9 with linear filtering (the .mcmeta sets blur)
    w, h = 640, 360
    sc = scene(w, h)
    ov, dr = imgs["overlay"], imgs["drips"]

    def stretch(rgba, linear, scroll=0.0):
        rgba = np.roll(rgba, int(scroll * rgba.shape[0]), axis=0)
        rgb = resize(rgba[..., :3], w, h, linear)
        a = np.asarray(Image.fromarray((rgba[..., 3] * 255 + 0.5).astype(np.uint8)).resize(
            (w, h), Image.BILINEAR if linear else Image.NEAREST)).astype(float) / 255
        return np.dstack([rgb, a])

    with_ov = over(stretch(ov, True), sc)
    with_both = over(stretch(dr, True, 0.35), with_ov)
    blocks.append(("slime_overlay.png over a test scene (linear)   |   + drips layer (scrolled)",
                   np.concatenate([with_ov, np.ones((h, 12, 3)) * 0.1, with_both], 1)))
    tiles = over(np.tile(dr, (2, 2, 1)), checker(512, 512, 16, 0.08, 0.12))
    ov_dark = over(ov, checker(256, 512, 16, 0.08, 0.12))
    ov_alpha = np.repeat(ov[..., 3:4], 3, 2)
    blocks.append(("overlay on dark   |   overlay alpha   |   drips tiled 2x2 (seams)   |   icon x8",
                   np.concatenate([np.concatenate([ov_dark, ov_alpha], 0), np.ones((512, 12, 3)) * 0.1, tiles,
                                   np.ones((512, 12, 3)) * 0.1,
                                   np.pad(big_icon, ((0, 512 - 144), (0, 0), (0, 0)), constant_values=0.1)], 1)))
    width = max(b.shape[1] for _, b in blocks) + 24
    rows = []
    for title, b in blocks:
        bar = np.ones((22, width, 3)) * 0.06
        body = np.pad(b, ((0, 12), (12, width - b.shape[1] - 12), (0, 0)), constant_values=0.06)
        rows.append((title, bar, body))
    sheet = np.concatenate(sum([[bar, body] for _, bar, body in rows], []), 0)
    pil = Image.fromarray((np.clip(sheet, 0, 1) * 255 + 0.5).astype(np.uint8))
    y = 0
    for title, bar, body in rows:
        label(pil, (12, y + 5), title)
        y += bar.shape[0] + body.shape[0]
    dst = os.path.join(OUT, "monster_textures.png")
    pil.save(dst)
    print("preview:", dst)


if __name__ == "__main__":
    main()
