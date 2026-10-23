"""Reads the Thompson source model (VIEWMODEL_SPEC section 24: *Gangster Tommy Gun* by Pieter Ferreira, CC-BY-4.0,
see CREDITS.md) as plain geometry: every mesh's triangles in world space (node matrices / TRS applied), then turned
into our design frame (metres, +X right, +Y up, +Z rearward, bore on x = y = 0). Only geometry is read: the file's
textures are never used. The source is untrusted input: it is parsed as data, nothing in it is executed.

python -I tools/assets/tommy_source.py [path/to/gangster_tommy_gun.glb]     (prints a summary)"""
import json
import os
import struct
import sys

import numpy as np

DEFAULT = os.path.join(os.path.expanduser("~"), "Downloads", "gangster_tommy_gun.glb")
COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def _quat_mat(q):
    x, y, z, w = np.asarray(q, float) / np.linalg.norm(q)
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def _local(n):
    if "matrix" in n:
        return np.array(n["matrix"], float).reshape(4, 4).T          # glTF: column-major
    m = np.eye(4)
    m[:3, :3] = _quat_mat(n.get("rotation", [0, 0, 0, 1])) * np.asarray(n.get("scale", [1, 1, 1]), float)[None, :]
    m[:3, 3] = n.get("translation", [0, 0, 0])
    return m


def read_glb(path):
    """-> (positions (N, 3) world space, triangles (M, 3) int) of every mesh in the file."""
    data = open(path, "rb").read()
    magic, version, length = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67 or version != 2:
        raise ValueError(f"{path}: not a GLB 2.0 file")
    off, js, binary = 12, None, b""
    while off + 8 <= min(length, len(data)):
        clen, ctype = struct.unpack_from("<II", data, off)
        body = data[off + 8: off + 8 + clen]
        if ctype == 0x4E4F534A:
            js = json.loads(body.decode("utf-8"))
        elif ctype == 0x004E4942:
            binary = body
        off += 8 + clen

    def accessor(i):
        a = js["accessors"][i]
        bv = js["bufferViews"][a["bufferView"]]
        dt = np.dtype(COMP[a["componentType"]])
        n = NCOMP[a["type"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0) or dt.itemsize * n
        if stride == dt.itemsize * n:
            return np.frombuffer(binary, dtype=dt, count=a["count"] * n, offset=start).reshape(a["count"], n)
        return np.stack([np.frombuffer(binary, dtype=dt, count=n, offset=start + k * stride) for k in range(a["count"])])

    nodes = js.get("nodes", [])
    parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}
    world = {}

    def wm(i):
        if i not in world:
            m = _local(nodes[i])
            world[i] = wm(parent[i]) @ m if i in parent else m
        return world[i]

    pos, tris, base = [], [], 0
    for i, n in enumerate(nodes):
        if "mesh" not in n:
            continue
        M = wm(i)
        for p in js["meshes"][n["mesh"]]["primitives"]:
            v = accessor(p["attributes"]["POSITION"]).astype(np.float64)
            idx = accessor(p["indices"]).reshape(-1).astype(np.int64) if "indices" in p else np.arange(len(v))
            pos.append(v @ M[:3, :3].T + M[:3, 3])
            tris.append(idx.reshape(-1, 3) + base)
            base += len(v)
    return np.concatenate(pos), np.concatenate(tris)


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    P, T = read_glb(src)
    print(f"{len(P)} vertices, {len(T)} triangles")
    print("min", P.min(0).round(2), "max", P.max(0).round(2), "size", (P.max(0) - P.min(0)).round(2))
