"""Builds models/gun/arms.glb (VIEWMODEL_SPEC section 7): 16 arm segment meshes with 64x64 skin UVs.

Box UV layout of a w x h x d box at texture offset (u, v) (Minecraft ModelPart.Cube):
  top (u+d, v, w, d), bottom (u+d+w, v, w, d), side strip at row v+d with height h:
  +X face (u, d wide), front -Z (u+d, w), -X face (u+d+w, d), back +Z (u+2d+w, w).
Joint space: segment from y=0 down to y=-0.30, front = -Z, +X = the character's right."""
import numpy as np

import meshkit as mk
import textures as tx
from glb import GLB
from meshkit import Mesh

SKIN_OFFSETS = {("R", False): (40, 16), ("R", True): (40, 32), ("L", False): (32, 48), ("L", True): (48, 48)}
LENGTH = 0.30
PX = 0.0175                                   # one skin pixel across the arm (4 px = 0.07)
INFLATE = 0.004


def segment_mesh(side, segment, slim, sleeve):
    u0, v0 = SKIN_OFFSETS[(side, sleeve)]
    wp, dp = (3 if slim else 4), 4
    w, d = wp * PX, dp * PX
    g = INFLATE if sleeve else 0.0
    x0, x1 = -w / 2 - g, w / 2 + g
    z0, z1 = -d / 2 - g, d / 2 + g                 # z0 = front
    y0, y1 = -LENGTH - g, g                        # y1 = joint end, y0 = far end
    r0 = v0 + dp + (0 if segment == "upper" else 6)  # first texel row of the side strip used
    r1 = r0 + 6
    mat = "skin_overlay" if sleeve else "skin"
    s = 1.0 / 64.0
    m = Mesh()

    def quad(p, uv):                       # corners listed clockwise from outside -> reverse to CCW
        m.add_poly(p[::-1], mat, uvs=[(a * s, b * s) for a, b in uv][::-1])

    # +X face: front edge at u0+d, back edge at u0
    quad([(x1, y1, z0), (x1, y0, z0), (x1, y0, z1), (x1, y1, z1)],
         [(u0 + dp, r0), (u0 + dp, r1), (u0, r1), (u0, r0)])
    # front (-Z): +X at u0+d, -X at u0+d+w
    quad([(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
         [(u0 + dp, r0), (u0 + dp + wp, r0), (u0 + dp + wp, r1), (u0 + dp, r1)])
    # -X face: front at u0+d+w, back at u0+2d+w
    quad([(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
         [(u0 + dp + wp, r0), (u0 + 2 * dp + wp, r0), (u0 + 2 * dp + wp, r1), (u0 + dp + wp, r1)])
    # back (+Z): -X at u0+2d+w, +X at u0+2d+2w
    quad([(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
         [(u0 + 2 * dp + wp, r0), (u0 + 2 * dp + 2 * wp, r0), (u0 + 2 * dp + 2 * wp, r1), (u0 + 2 * dp + wp, r1)])
    # top (+Y): +X left, -X right, back at v0, front at v0+d
    quad([(x1, y1, z1), (x0, y1, z1), (x0, y1, z0), (x1, y1, z0)],
         [(u0 + dp, v0), (u0 + dp + wp, v0), (u0 + dp + wp, v0 + dp), (u0 + dp, v0 + dp)])
    # bottom (-Y, the hand end on the forearm): +X left, -X right, back at v0, front at v0+d
    quad([(x1, y0, z0), (x0, y0, z0), (x0, y0, z1), (x1, y0, z1)],
         [(u0 + dp + wp, v0 + dp), (u0 + dp + 2 * wp, v0 + dp), (u0 + dp + 2 * wp, v0), (u0 + dp + wp, v0)])
    return m


def preview_skin():
    """Our own 64x64 test skin (rolled sleeves, bare hands); only for previews / the default texture."""
    rng = np.random.default_rng(5)
    img = np.zeros((64, 64, 4))
    shirt = np.array([0.20, 0.42, 0.55])
    skin = np.array([0.86, 0.66, 0.50])
    for (side, sleeve), (u0, v0) in SKIN_OFFSETS.items():
        region = img[v0:v0 + 16, u0:u0 + 16]
        n = 0.92 + 0.16 * rng.random((16, 16, 1))
        if not sleeve:
            base = np.tile(skin, (16, 16, 1))
            base[4:10] = shirt                              # sleeve on the upper segment
            base[0:4, 4:8] = shirt                          # shoulder top
            base[14:16] *= 0.86                             # knuckles / hand end darker
            base[4:16, 0:4] *= 0.92                         # outer side slightly darker
            region[:, :, :3] = np.clip(base * n, 0, 1)
            region[:, :, 3] = 1.0
        else:
            over = np.tile(shirt * 0.8, (16, 16, 1)) * n
            a = np.zeros((16, 16))
            a[4:12] = 1.0                                   # rolled sleeve ends 2 px into the forearm
            a[0:4, 4:8] = 1.0
            over[11:12] *= 0.75                             # cuff
            region[:, :, :3] = np.clip(over, 0, 1)
            region[:, :, 3] = a
    return img


def build(path, embed_skin=True):
    g = GLB()
    png = tx.png_bytes(preview_skin()) if embed_skin else None
    g.add_material("skin", (1, 1, 1, 1), 0.0, 0.9, texture_png=png)
    g.add_material("skin_overlay", (1, 1, 1, 1), 0.0, 0.9, alpha_mode="MASK", alpha_cutoff=0.1, texture_png=png)
    # glTF samplers: Minecraft skins are pixel art -> nearest filtering
    for s in g.json.get("samplers", []):
        s["magFilter"], s["minFilter"] = 9728, 9728
    tris = 0
    for side in ("R", "L"):
        for segment in ("upper", "fore"):
            for variant in ("wide", "slim"):
                for sleeve in (False, True):
                    name = f"{side}_{segment}_{variant}" + ("_sleeve" if sleeve else "")
                    prims = mk.build_primitives(segment_mesh(side, segment, variant == "slim", sleeve))
                    tris += mk.triangle_count(prims)
                    g.add_node(name, None, mesh=g.add_mesh(name, prims))
    g.save(path)
    return tris
