"""Independent GLB reader: parses the container, accessors, node graph and samples animations."""
import io
import json
import struct

import numpy as np
from PIL import Image

COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


class GLTF:
    def __init__(self, path):
        data = open(path, "rb").read()
        self.raw = data
        magic, version, length = struct.unpack_from("<III", data, 0)
        assert magic == 0x46546C67, "not a GLB"
        assert version == 2, "GLB version"
        assert length == len(data), "length mismatch"
        off = 12
        self.chunks = []
        self.json, self.bin = None, b""
        while off < len(data):
            clen, ctype = struct.unpack_from("<II", data, off)
            self.chunks.append((off, clen, ctype))
            body = data[off + 8: off + 8 + clen]
            if ctype == 0x4E4F534A:
                self.json = json.loads(body.decode("utf-8"))
            elif ctype == 0x004E4942:
                self.bin = body
            off += 8 + clen
        j = self.json
        self.nodes = j.get("nodes", [])
        self.names = {n.get("name"): i for i, n in enumerate(self.nodes)}
        self.parent = {}
        for i, n in enumerate(self.nodes):
            for c in n.get("children", []):
                self.parent[c] = i
        self._acc_cache = {}
        self._tex_cache = {}

    def accessor(self, idx):
        if idx in self._acc_cache:
            return self._acc_cache[idx]
        a = self.json["accessors"][idx]
        bv = self.json["bufferViews"][a["bufferView"]]
        dt = np.dtype(COMP[a["componentType"]])
        n = NCOMP[a["type"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0) or dt.itemsize * n
        if stride == dt.itemsize * n:
            arr = np.frombuffer(self.bin, dtype=dt, count=a["count"] * n, offset=start).reshape(a["count"], n)
        else:
            arr = np.stack([np.frombuffer(self.bin, dtype=dt, count=n, offset=start + i * stride)
                            for i in range(a["count"])])
        self._acc_cache[idx] = arr
        return arr

    def image(self, tex_index):
        if tex_index in self._tex_cache:
            return self._tex_cache[tex_index]
        src = self.json["textures"][tex_index]["source"]
        im = self.json["images"][src]
        bv = self.json["bufferViews"][im["bufferView"]]
        png = self.bin[bv.get("byteOffset", 0): bv.get("byteOffset", 0) + bv["byteLength"]]
        arr = np.asarray(Image.open(io.BytesIO(png)).convert("RGBA"), dtype=np.float64) / 255.0
        self._tex_cache[tex_index] = arr
        return arr

    def mesh_primitives(self, mesh_index):
        out = []
        for p in self.json["meshes"][mesh_index]["primitives"]:
            at = p["attributes"]
            out.append({"positions": self.accessor(at["POSITION"]).astype(np.float64),
                        "normals": self.accessor(at["NORMAL"]).astype(np.float64) if "NORMAL" in at else None,
                        "uvs": self.accessor(at["TEXCOORD_0"]).astype(np.float64) if "TEXCOORD_0" in at else None,
                        "indices": self.accessor(p["indices"]).reshape(-1, 3).astype(np.int64),
                        "material": p.get("material")})
        return out

    # ------------------------------------------------------------------ transforms & animation
    def local_trs(self, i):
        n = self.nodes[i]
        return (np.array(n.get("translation", [0, 0, 0]), float), np.array(n.get("rotation", [0, 0, 0, 1]), float),
                np.array(n.get("scale", [1, 1, 1]), float))

    def animation(self, name):
        for a in self.json.get("animations", []):
            if a.get("name") == name:
                return a
        return None

    def duration(self, anim):
        return max(float(self.accessor(s["input"]).max()) for s in anim["samplers"])

    def sample(self, anim, t):
        """-> {node_index: {path: value}} for the animation at time t."""
        out = {}
        for ch in anim["channels"]:
            s = anim["samplers"][ch["sampler"]]
            ts = self.accessor(s["input"])[:, 0].astype(np.float64)
            vs = self.accessor(s["output"]).astype(np.float64)
            path = ch["target"]["path"]
            if t <= ts[0]:
                v = vs[0]
            elif t >= ts[-1]:
                v = vs[-1]
            else:
                k = int(np.searchsorted(ts, t, side="right") - 1)
                if s.get("interpolation", "LINEAR") == "STEP":
                    v = vs[k]
                else:
                    u = (t - ts[k]) / (ts[k + 1] - ts[k])
                    if path == "rotation":
                        v = _slerp(vs[k], vs[k + 1], u)
                    else:
                        v = vs[k] * (1 - u) + vs[k + 1] * u
            out.setdefault(ch["target"]["node"], {})[path] = v
        return out

    def world_matrices(self, overrides=None):
        overrides = overrides or {}
        mats = {}

        def local(i):
            t, r, s = self.local_trs(i)
            ov = overrides.get(i, {})
            t = ov.get("translation", t)
            r = ov.get("rotation", r)
            s = ov.get("scale", s)
            return trs(t, r, s)

        def world(i):
            if i in mats:
                return mats[i]
            m = local(i)
            if i in self.parent:
                m = world(self.parent[i]) @ m
            mats[i] = m
            return m

        for i in range(len(self.nodes)):
            world(i)
        return mats


def _slerp(a, b, u):
    a = a / np.linalg.norm(a)
    b = b / np.linalg.norm(b)
    d = float(np.dot(a, b))
    if d < 0:
        b, d = -b, -d
    if d > 0.9995:
        q = a + (b - a) * u
        return q / np.linalg.norm(q)
    th = np.arccos(d)
    return (np.sin((1 - u) * th) * a + np.sin(u * th) * b) / np.sin(th)


def quat_mat(q):
    x, y, z, w = q / np.linalg.norm(q)
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def trs(t, r, s):
    m = np.eye(4)
    m[:3, :3] = quat_mat(np.asarray(r, float)) * np.asarray(s, float)[None, :]
    m[:3, 3] = t
    return m
