"""Minimal glTF 2.0 binary (GLB) writer: nodes, meshes, materials, embedded PNG textures, animations."""
import json
import struct

import numpy as np

FLOAT = 5126
UINT8 = 5121
UINT16 = 5123
UINT32 = 5125
ARRAY_BUFFER = 34962
ELEMENT_ARRAY_BUFFER = 34963
REPEAT = 10497
LINEAR = 9729
LINEAR_MIPMAP_LINEAR = 9987


class GLB:
    def __init__(self, generator="BlockLand procedural asset pipeline"):
        self.json = {"asset": {"version": "2.0", "generator": generator}, "scene": 0,
                     "scenes": [{"nodes": []}], "nodes": [], "meshes": [], "materials": [],
                     "accessors": [], "bufferViews": [], "buffers": []}
        self.bin = bytearray()
        self.node_index = {}
        self.material_index = {}

    # ---------------------------------------------------------------- buffers
    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        view = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if target is not None:
            view["target"] = target
        self.bin.extend(data)
        self.json["bufferViews"].append(view)
        return len(self.json["bufferViews"]) - 1

    def _accessor(self, array, kind, target=None, minmax=False, normalized=False):
        array = np.ascontiguousarray(array)
        if array.dtype in (np.uint8, np.uint16, np.uint32):
            ctype = {np.dtype(np.uint8): UINT8, np.dtype(np.uint16): UINT16, np.dtype(np.uint32): UINT32}[array.dtype]
        else:
            array = array.astype(np.float32)
            ctype = FLOAT
        view = self._view(array.tobytes(), target)
        acc = {"bufferView": view, "componentType": ctype, "count": int(array.shape[0]), "type": kind}
        if normalized:
            acc["normalized"] = True
        if minmax:
            flat = array.reshape(array.shape[0], -1)
            acc["min"] = [float(v) for v in flat.min(axis=0)]
            acc["max"] = [float(v) for v in flat.max(axis=0)]
        self.json["accessors"].append(acc)
        return len(self.json["accessors"]) - 1

    # -------------------------------------------------------------- materials
    def add_texture_png(self, png_bytes, name):
        j = self.json
        j.setdefault("images", []).append(
            {"name": name, "mimeType": "image/png", "bufferView": self._view(png_bytes)})
        if "samplers" not in j:
            j["samplers"] = [{"magFilter": LINEAR, "minFilter": LINEAR_MIPMAP_LINEAR,
                              "wrapS": REPEAT, "wrapT": REPEAT}]
        j.setdefault("textures", []).append({"name": name, "sampler": 0, "source": len(j["images"]) - 1})
        return len(j["textures"]) - 1

    def add_material(self, name, base_color=(1, 1, 1, 1), metallic=0.0, roughness=0.5, emissive=None,
                     alpha_mode="OPAQUE", alpha_cutoff=None, double_sided=False, texture_png=None, extras=None):
        pbr = {"baseColorFactor": [float(c) for c in base_color], "metallicFactor": float(metallic),
               "roughnessFactor": float(roughness)}
        if texture_png is not None:
            pbr["baseColorTexture"] = {"index": self.add_texture_png(texture_png, name), "texCoord": 0}
        mat = {"name": name, "pbrMetallicRoughness": pbr}
        if emissive is not None:
            mat["emissiveFactor"] = [float(c) for c in emissive]
        if alpha_mode != "OPAQUE":
            mat["alphaMode"] = alpha_mode
            if alpha_mode == "MASK":
                mat["alphaCutoff"] = float(alpha_cutoff if alpha_cutoff is not None else 0.5)
        if double_sided:
            mat["doubleSided"] = True
        if extras:
            mat["extras"] = extras
        self.json["materials"].append(mat)
        self.material_index[name] = len(self.json["materials"]) - 1
        return self.material_index[name]

    # ----------------------------------------------------------------- meshes
    def add_mesh(self, name, primitives):
        """primitives: list of dicts {positions, normals, uvs, indices, material(name)[, joints, weights]}."""
        prims = []
        for p in primitives:
            pos = np.asarray(p["positions"], np.float32)
            attrs = {"POSITION": self._accessor(pos, "VEC3", ARRAY_BUFFER, minmax=True),
                     "NORMAL": self._accessor(np.asarray(p["normals"], np.float32), "VEC3", ARRAY_BUFFER)}
            if p.get("uvs") is not None:
                attrs["TEXCOORD_0"] = self._accessor(np.asarray(p["uvs"], np.float32), "VEC2", ARRAY_BUFFER)
            if p.get("joints") is not None:          # skinned meshes (arms.glb, VIEWMODEL_SPEC section 7)
                attrs["JOINTS_0"] = self._accessor(np.asarray(p["joints"], np.uint8), "VEC4", ARRAY_BUFFER)
                attrs["WEIGHTS_0"] = self._accessor(np.asarray(p["weights"], np.float32), "VEC4", ARRAY_BUFFER)
            idx = np.asarray(p["indices"]).reshape(-1)
            idx = idx.astype(np.uint16 if len(pos) < 65536 else np.uint32)
            prims.append({"attributes": attrs,
                          "indices": self._accessor(idx, "SCALAR", ELEMENT_ARRAY_BUFFER, minmax=True),
                          "material": self.material_index[p["material"]], "mode": 4})
        self.json["meshes"].append({"name": name, "primitives": prims})
        return len(self.json["meshes"]) - 1

    # ------------------------------------------------------------------ nodes
    def add_node(self, name, parent=None, translation=None, rotation=None, scale=None, mesh=None, extras=None):
        node = {"name": name}
        if translation is not None and np.any(np.abs(translation) > 0):
            node["translation"] = [float(v) for v in translation]
        if rotation is not None and abs(float(rotation[3])) < 1 - 1e-9:
            q = np.asarray(rotation, float)
            node["rotation"] = [float(v) for v in q / np.linalg.norm(q)]
        if scale is not None and np.any(np.abs(np.asarray(scale) - 1) > 0):
            node["scale"] = [float(v) for v in scale]
        if mesh is not None:
            node["mesh"] = mesh
        if extras:
            node["extras"] = extras
        self.json["nodes"].append(node)
        idx = len(self.json["nodes"]) - 1
        self.node_index[name] = idx
        if parent is None:
            self.json["scenes"][0]["nodes"].append(idx)
        else:
            self.json["nodes"][self.node_index[parent]].setdefault("children", []).append(idx)
        return idx

    # ------------------------------------------------------------- animation
    def add_animation(self, name, channels, extras=None):
        """channels: list of (node_name, path, times, values, interpolation)."""
        anim = {"name": name, "channels": [], "samplers": []}
        for node_name, path, times, values, interp in channels:
            times = np.asarray(times, np.float32)
            values = np.asarray(values, np.float32)
            kind = "VEC4" if path == "rotation" else "VEC3"
            s = {"input": self._accessor(times, "SCALAR", minmax=True),
                 "output": self._accessor(values, kind), "interpolation": interp}
            anim["samplers"].append(s)
            anim["channels"].append({"sampler": len(anim["samplers"]) - 1,
                                     "target": {"node": self.node_index[node_name], "path": path}})
        if extras:
            anim["extras"] = extras
        self.json.setdefault("animations", []).append(anim)

    # ------------------------------------------------------------------ write
    def to_bytes(self):
        j = dict(self.json)
        for key in ("meshes", "materials", "accessors", "bufferViews"):
            if not j[key]:
                del j[key]
        while len(self.bin) % 4:
            self.bin.append(0)
        j["buffers"] = [{"byteLength": len(self.bin)}]
        js = json.dumps(j, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        js += b" " * ((4 - len(js) % 4) % 4)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        out = bytearray(struct.pack("<III", 0x46546C67, 2, total))
        out += struct.pack("<II", len(js), 0x4E4F534A) + js
        out += struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin)
        return bytes(out)

    def save(self, path):
        with open(path, "wb") as f:
            f.write(self.to_bytes())
