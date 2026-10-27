"""KS-3 source conversion (VIEWMODEL_SPEC section 25, CREDITS.md): reads *Shotgun* by DJMaesen (CC-BY-4.0) and keeps
the parts our design uses, converted to the KS-3 design frame, in source/ks3_parts.npz (committed, so the model can be
rebuilt without the download). The source is only parsed as data.

python -I tools/assets/ks3_source.py [path/to/shotgun.glb]     (default: %USERPROFILE%/Downloads/shotgun.glb)

What is kept: the receiver (split into the receiver and its pistol grip; trigger guard, ejection port and the small
hardware stay with the receiver), the pump forend, the trigger and the loading gate. Everything forward of the receiver
(the perforated heat shield, the barrel, the magazine tube and its clamp, the sights) and the round in the source are
left out: ks3_model builds its own barrel with a ventilated rib, sights, rail, tubes, stock and shotshells. The heavy
hidden geometry goes with them (16.3k -> 4.8k source triangles). The kept parts stay at the source's full resolution
(section 55: the decimation to 3.2k that the old 9k long-gun budget needed faceted the grip and the pump); DECIMATE
can still name a part to decimate (qem.py). Their textures are not used (box-projected UVs and our texture sets,
section 20).

Design frame (metres): +X right, +Y up, +Z rearward, bore axis on x = y = 0, z = 0 at the receiver's front face. The
source points its muzzle along +Z with its left side on +X (centimetre-like units): x' = -S x, y' = S (y - BORE_Y),
z' = -S (z - FRONT_Z), a proper rotation (no winding flip)."""
import json
import os
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "source", "ks3_parts.npz")
DEFAULT_SOURCE = os.path.join(os.path.expanduser("~"), "Downloads", "shotgun.glb")

S = 0.0054             # source unit -> metre: the source bore (r 1.758) becomes 19 mm, a 12-gauge bore
BORE_Y = 14.1665       # barrel axis height (source)
FRONT_Z = 38.18        # receiver front face (source)
KEEP_Z_MAX = 38.6      # base components reaching further forward than this are not kept (shield, barrel, tube)
# triangle targets of parts to decimate (qem); none: the source's 4.8k kept triangles fit the long-gun budget whole
DECIMATE = {}
COMP, NCOMP = {5121: np.uint8, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}, {"SCALAR": 1, "VEC3": 3}


def convert(p):
    p = np.asarray(p, float)
    return np.stack([-S * p[..., 0], S * (p[..., 1] - BORE_Y), -S * (p[..., 2] - FRONT_Z)], -1)


class Source:
    """Minimal GLB reader for the source: node matrices or TRS, positions and indices."""

    def __init__(self, path):
        data = open(path, "rb").read()
        magic, version, length = struct.unpack_from("<III", data, 0)
        if magic != 0x46546C67 or version != 2:
            raise ValueError(f"{path}: not a GLB 2.0 file")
        off, self.json, self.bin = 12, None, b""
        while off < min(length, len(data)):
            clen, ctype = struct.unpack_from("<II", data, off)
            body = data[off + 8: off + 8 + clen]
            if ctype == 0x4E4F534A:
                self.json = json.loads(body.decode("utf-8"))
            elif ctype == 0x004E4942:
                self.bin = body
            off += 8 + clen
        self.nodes = self.json["nodes"]
        self.parent = {c: i for i, n in enumerate(self.nodes) for c in n.get("children", [])}

    def accessor(self, i):
        a = self.json["accessors"][i]
        bv = self.json["bufferViews"][a["bufferView"]]
        dt = np.dtype(COMP[a["componentType"]])
        n = NCOMP[a["type"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0) or dt.itemsize * n
        if stride != dt.itemsize * n:
            raise ValueError("interleaved source buffers are not supported")
        return np.frombuffer(self.bin, dtype=dt, count=a["count"] * n, offset=start).reshape(a["count"], n)

    def local(self, i):
        n = self.nodes[i]
        if "matrix" in n:
            return np.array(n["matrix"], float).reshape(4, 4).T
        from glbread import trs
        return trs(np.array(n.get("translation", [0, 0, 0]), float), np.array(n.get("rotation", [0, 0, 0, 1]), float),
                   np.array(n.get("scale", [1, 1, 1]), float))

    def world(self, i):
        m = self.local(i)
        while i in self.parent:
            i = self.parent[i]
            m = self.local(i) @ m
        return m

    def part(self, mesh_node):
        """World-space (source units) triangles of the mesh under the node called `mesh_node`."""
        i = next(k for k, n in enumerate(self.nodes) if n.get("name") == mesh_node)
        M = self.world(i)
        pos, tris = [], []
        base = 0
        for p in self.json["meshes"][self.nodes[i]["mesh"]]["primitives"]:
            v = self.accessor(p["attributes"]["POSITION"]).astype(np.float64)
            pos.append(v @ M[:3, :3].T + M[:3, 3])
            tris.append(self.accessor(p["indices"]).reshape(-1, 3).astype(np.int64) + base)
            base += len(v)
        return np.concatenate(pos), np.concatenate(tris)

    def origin(self, node):
        i = next(k for k, n in enumerate(self.nodes) if n.get("name") == node)
        return self.world(i)[:3, 3]


def weld(pos, tris, tol=1e-4):
    """Merge vertices closer than `tol` (source units: UV seams), drop degenerate and duplicate triangles."""
    key = np.round(pos / tol).astype(np.int64)
    _, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
    inv = inv.reshape(-1)
    t = inv[tris]
    t = t[(t[:, 0] != t[:, 1]) & (t[:, 1] != t[:, 2]) & (t[:, 0] != t[:, 2])]
    _, keep = np.unique(np.sort(t, axis=1), axis=0, return_index=True)
    t = t[np.sort(keep)]
    used, remap = np.unique(t, return_inverse=True)
    return pos[first][used], remap.reshape(-1, 3)


def components(tris):
    """Connected component id per triangle (shared vertices)."""
    parent = np.arange(tris.max() + 1)

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for a, b, c in tris:
        ra = find(a)
        parent[find(b)] = ra
        parent[find(c)] = ra
    return np.array([find(t[0]) for t in tris])


def split_base(pos, tris):
    """-> {"receiver": tris, "grip": tris, "hardware": tris} of the base mesh (source units), forward parts dropped."""
    comp = components(tris)
    out = {"receiver": [], "grip": [], "hardware": []}
    ids, counts = np.unique(comp, return_counts=True)
    main = ids[np.argmax([np.ptp(pos[np.unique(tris[comp == c])][:, 1]) if pos[np.unique(tris[comp == c])][:, 2].max()
                          <= KEEP_Z_MAX else -1 for c in ids])]       # the tallest kept part: receiver + grip
    for c in ids:
        t = tris[comp == c]
        v = pos[np.unique(t)]
        if v[:, 2].max() > KEEP_Z_MAX:
            continue
        if c != main:
            out["hardware"].append(t)
            continue
        centre = pos[t].mean(axis=1)
        grip = (centre[:, 1] < 2.2) & (centre[:, 2] < 3.5)             # below the receiver, behind the guard
        out["grip"].append(t[grip])
        out["receiver"].append(t[~grip])
    return {k: np.concatenate(v) if v else np.zeros((0, 3), np.int64) for k, v in out.items()}


def compact(pos, tris):
    used, remap = np.unique(tris, return_inverse=True)
    return pos[used], remap.reshape(-1, 3)


def build(source_path=DEFAULT_SOURCE, out=OUT):
    src = Source(source_path)
    parts = {}
    pos, tris = weld(*src.part("base_shotgun_0"))
    for name, t in split_base(pos, tris).items():
        parts[name] = compact(convert(pos), t)
    for name, node in (("pump", "pump_shotgun_0"), ("trigger", "trigger_shotgun_0"), ("loading_gate", "loader_shotgun_0")):
        p, t = weld(*src.part(node))
        parts[name] = (convert(p), t)
    import qem
    for name, target in DECIMATE.items():
        parts[name] = qem.simplify(*parts[name], target)
    pivots = {"pump": convert(src.origin("pump")), "trigger": convert(src.origin("trigger")),
              "loading_gate": convert(src.origin("loader"))}
    arrays = {}
    for name, (p, t) in parts.items():
        arrays[f"{name}_pos"] = p.astype(np.float32)
        arrays[f"{name}_tri"] = t.astype(np.int32)
    for name, p in pivots.items():
        arrays[f"{name}_pivot"] = np.asarray(p, np.float32)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    np.savez_compressed(out, **arrays)
    total = sum(len(t) for _, t in parts.values())
    print(f"ks3 source: {total} triangles kept " + ", ".join(f"{k} {len(t)}" for k, (_, t) in parts.items())
          + f" -> {os.path.relpath(out, HERE)}")
    return parts, pivots


def load(path=OUT):
    """-> ({part: (positions (V, 3) metres, triangles (T, 3))}, {pivot name: (3,)}) in the design frame."""
    z = np.load(path)
    parts, pivots = {}, {}
    for key in z.files:
        if key.endswith("_pos"):
            name = key[:-4]
            parts[name] = (z[key].astype(np.float64), z[f"{name}_tri"].astype(np.int64))
        elif key.endswith("_pivot"):
            pivots[key[:-6]] = z[key].astype(np.float64)
    return parts, pivots


if __name__ == "__main__":
    sys.path.insert(0, HERE)
    build(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SOURCE)
