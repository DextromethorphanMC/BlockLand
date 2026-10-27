"""Software previews of the vehicle models (VIEWMODEL_SPEC section 57): a turntable of 3/4 views and a close-up sheet.

python -I tools/assets/vehicle_preview.py <id|path.glb> [out.png]"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                   # noqa: E402
from PIL import Image                                # noqa: E402

import loadouts as lo                                # noqa: E402
import raster as rs                                  # noqa: E402
from glbread import GLTF                             # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
VEHICLE_DIR = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "vehicle")
VIEWS = ((-145, 16), (-90, 6), (-35, 20), (0, 30), (35, 12), (90, 4), (145, 22), (180, 45))


def items_of(g, M, pose=None):
    W = g.world_matrices(pose or {})
    mats = lo.load_materials(g)
    out = []
    for i, n in enumerate(g.nodes):
        if "mesh" in n:
            lo.instance(g, mats, n["mesh"], M @ W[i], out)
    return out


def bounds(g):
    W = g.world_matrices({})
    lo_, hi = np.full(3, 1e9), np.full(3, -1e9)
    for i, n in enumerate(g.nodes):
        if "mesh" in n:
            for p in g.mesh_primitives(n["mesh"]):
                q = p["positions"] @ W[i][:3, :3].T + W[i][:3, 3]
                lo_, hi = np.minimum(lo_, q.min(0)), np.maximum(hi, q.max(0))
    return lo_, hi


def turntable(path, out, w=640, h=420, views=VIEWS, fov=30.0):
    g = GLTF(path)
    lo_, hi = bounds(g)
    centre = (lo_ + hi) / 2
    radius = np.linalg.norm(hi - lo_) / 2
    dist = radius / np.tan(np.radians(fov) / 2) * 1.05
    tiles = []
    for yaw, pitch in views:
        a, b = np.radians(yaw), np.radians(pitch)
        Ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
        Rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
        M = np.eye(4)
        M[:3, :3] = Rx @ Ry
        M[:3, 3] = -M[:3, :3] @ centre + np.array([0, 0, -dist])
        img = rs.render(items_of(g, M), rs.Camera(w, h, fov, near=0.05), bg=(0.55, 0.57, 0.60), ss=2)
        tiles.append(Image.fromarray(img))
    cols = 4
    sheet = Image.new("RGB", (w * cols, h * ((len(tiles) + cols - 1) // cols)))
    for k, im in enumerate(tiles):
        sheet.paste(im, ((k % cols) * w, (k // cols) * h))
    sheet.save(out)
    return out


def closeup(path, out, target, yaw, pitch, dist, w=900, h=600, fov=40.0):
    g = GLTF(path)
    a, b = np.radians(yaw), np.radians(pitch)
    Ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
    Rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
    M = np.eye(4)
    M[:3, :3] = Rx @ Ry
    M[:3, 3] = -M[:3, :3] @ np.asarray(target, float) + np.array([0, 0, -dist])
    Image.fromarray(rs.render(items_of(g, M), rs.Camera(w, h, fov, near=0.02), bg=(0.55, 0.57, 0.60), ss=2)).save(out)
    return out


if __name__ == "__main__":
    arg = sys.argv[1]
    path = arg if arg.endswith(".glb") else os.path.join(VEHICLE_DIR, f"{arg}.glb")
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "out", f"vehicle_{os.path.basename(path)[:-4]}.png")
    print(turntable(path, out))
