"""Validates p9.glb / arms.glb against VIEWMODEL_SPEC.md, independently of the writer.

python -I tools/assets/check_glb.py [path/to/p9.glb] [path/to/arms.glb]     (exit code 1 on failure)"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                       # noqa: E402

from glbread import GLTF, quat_mat       # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
GUN_DIR = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "gun")
KNIFE_DIR = os.path.join(os.path.dirname(GUN_DIR), "knife")
FOV_Y = 54.0                       # section 2: viewmodel projection (vertical), used for the on-screen estimate
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", "frame": "gun", "slide": "gun", "sight_iron": "slide",
    "sight_reddot": "slide", "reddot_reticle": "sight_reddot", "aim_iron": "slide", "aim_reddot": "slide",
    "barrel": "gun", "hammer": "gun", "trigger": "gun", "slide_stop": "gun", "mag": "gun",
    "mag_standard": "mag", "mag_extended": "mag", "laser": "gun", "laser_emitter": "laser", "muzzle": "gun",
    "ejection": "gun", "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel",
    "arm_L_fore": "arm_L", "camera": "viewmodel", "fx_shell": None,
}
MESH_NODES = {"frame", "slide", "sight_iron", "sight_reddot", "reddot_reticle", "barrel", "hammer", "trigger",
              "slide_stop", "mag_standard", "mag_extended", "laser", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "aim_reddot", "mag", "laser_emitter", "muzzle", "ejection", "arm_R",
               "arm_R_fore", "arm_L", "arm_L_fore", "camera"}
DURATIONS = {"idle": (2.0, 2.0), "sprint": (0.66, 0.66), "draw": (0.45, 0.8), "fire": (0.15, 0.3),
             "fire_last": (0.15, 0.3),
             "reload": (1.6, 2.2), "reload_empty": (2.0, 2.6), "inspect": (3.0, 4.2)}
LOOPING = {"idle", "sprint"}
NO_CAMERA = {"idle", "sprint"}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "slide_back", "slide_forward", "slide_release",
               "inspect_move", "equip", "hammer"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "slide_forward"},
                   "inspect": {"slide_back", "slide_forward", "inspect_move"}}
MATERIALS = {"polymer", "grip", "slide", "barrel", "steel_dark", "sight_dots", "mag", "mag_base", "attachment",
             "glass", "reticle", "laser_lens", "brass"}
ARM_MESHES = [f"{s}_{g}_{v}{x}" for s in "RL" for g in ("upper", "fore") for v in ("wide", "slim")
              for x in ("", "_sleeve")]


class Report:
    def __init__(self):
        self.errors, self.notes = [], []

    def check(self, ok, msg):
        if not ok:
            self.errors.append(msg)
        return ok

    def note(self, msg):
        self.notes.append(msg)


def check_container(g, r, name):
    data = g.raw
    r.check(len(data) % 4 == 0, f"{name}: file length not 4-byte aligned")
    for i, (off, clen, ctype) in enumerate(g.chunks):
        r.check(off % 4 == 0 and clen % 4 == 0, f"{name}: chunk {i} not 4-byte aligned")
    r.check(g.chunks[0][2] == 0x4E4F534A, f"{name}: first chunk is not JSON")
    blen = g.json["buffers"][0]["byteLength"]
    r.check(blen <= len(g.bin), f"{name}: buffer byteLength exceeds BIN chunk")
    for i, bv in enumerate(g.json.get("bufferViews", [])):
        off = bv.get("byteOffset", 0)
        r.check(off % 4 == 0, f"{name}: bufferView {i} offset {off} not 4-aligned")
        r.check(off + bv["byteLength"] <= blen, f"{name}: bufferView {i} out of range")
    sizes = {5121: 1, 5123: 2, 5125: 4, 5126: 4, 5120: 1, 5122: 2}
    ncomp = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
    for i, a in enumerate(g.json.get("accessors", [])):
        bv = g.json["bufferViews"][a["bufferView"]]
        cs = sizes[a["componentType"]]
        r.check(a.get("byteOffset", 0) % cs == 0, f"{name}: accessor {i} misaligned")
        need = a.get("byteOffset", 0) + a["count"] * cs * ncomp[a["type"]]
        r.check(need <= bv["byteLength"], f"{name}: accessor {i} overflows its bufferView")
        arr = g.accessor(i)
        r.check(np.all(np.isfinite(arr.astype(np.float64))), f"{name}: accessor {i} has non-finite values")
        if "min" in a:
            r.check(np.allclose(arr.min(0), a["min"], atol=1e-6) and np.allclose(arr.max(0), a["max"], atol=1e-6),
                    f"{name}: accessor {i} min/max mismatch")


def check_meshes(g, r, name):
    for mi, m in enumerate(g.json.get("meshes", [])):
        for p in m["primitives"]:
            at = p["attributes"]
            r.check("min" in g.json["accessors"][at["POSITION"]], f"{name}: mesh {m.get('name')} POSITION lacks min/max")
            pos = g.accessor(at["POSITION"])
            idx = g.accessor(p["indices"]).reshape(-1)
            r.check(len(idx) % 3 == 0, f"{name}: mesh {m.get('name')} index count not a multiple of 3")
            r.check(idx.max() < len(pos), f"{name}: mesh {m.get('name')} index out of range")
            if "NORMAL" in at:
                nv = g.accessor(at["NORMAL"]).astype(np.float64)
                n = np.linalg.norm(nv, axis=1)
                r.check(np.all(np.abs(n - 1) < 1e-3), f"{name}: mesh {m.get('name')} normals not unit length")
                tri = idx.reshape(-1, 3)
                pv = pos.astype(np.float64)[tri]
                fn = np.cross(pv[:, 1] - pv[:, 0], pv[:, 2] - pv[:, 0])
                agree = np.einsum("ij,ij->i", fn, nv[tri].sum(1)) > 0
                r.check(agree.mean() > 0.98, f"{name}: mesh {m.get('name')} winding disagrees with normals "
                                             f"({1 - agree.mean():.0%} of triangles)")
            r.check("TEXCOORD_0" in at, f"{name}: mesh {m.get('name')} has no UVs")


def check_nodes(g, r):
    for nm, parent in PARENTS.items():
        if not r.check(nm in g.names, f"p9: missing node {nm}"):
            continue
        i = g.names[nm]
        par = g.parent.get(i)
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"p9: node {nm} parent is {pname}, expected {parent}")
        has_mesh = "mesh" in g.nodes[i]
        if nm in MESH_NODES:
            r.check(has_mesh, f"p9: node {nm} should carry a mesh")
        if nm in EMPTY_NODES:
            r.check(not has_mesh, f"p9: node {nm} should be empty")
    for n in g.nodes:
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"p9: node {n.get('name')} rotation not normalized")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"p9: arm_{side}_fore translation {t}")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), "p9: viewmodel rest pose not identity")
    cam = g.nodes[g.names["camera"]]
    r.check(not any(k in cam for k in ("translation", "rotation", "scale")), "p9: camera rest pose not identity")


def euler_deg(q):
    """yaw (Y), pitch (X), roll (Z) of R = Ry Rx Rz."""
    m = quat_mat(np.asarray(q, float))
    pitch = np.degrees(np.arcsin(np.clip(-m[1, 2], -1, 1)))
    yaw = np.degrees(np.arctan2(m[0, 2], m[2, 2]))
    roll = np.degrees(np.arctan2(m[1, 0], m[1, 1]))
    return yaw, pitch, roll


def check_materials(g, r):
    names = [m.get("name") for m in g.json.get("materials", [])]
    for nm in names:
        r.check(nm in MATERIALS, f"p9: unexpected material name {nm}")
    for nm in MATERIALS:
        r.check(nm in names, f"p9: material {nm} missing")
    mats = {m["name"]: m for m in g.json["materials"]}
    for nm in ("grip", "slide"):
        r.check("baseColorTexture" in mats[nm]["pbrMetallicRoughness"], f"p9: material {nm} lacks its texture")
    r.check(mats["glass"].get("alphaMode") == "BLEND", "p9: glass must be BLEND")
    r.check(mats["reticle"].get("alphaMode") == "MASK", "p9: reticle must be MASK")
    for nm in ("reticle", "sight_dots", "laser_lens"):
        r.check(any(v > 0 for v in mats[nm].get("emissiveFactor", [0, 0, 0])), f"p9: {nm} should be emissive")
    for s in g.json.get("samplers", []):
        r.check(s.get("wrapS", 10497) == 10497 and s.get("wrapT", 10497) == 10497, "p9: sampler not REPEAT")
    for im in g.json.get("images", []):
        r.check(im.get("mimeType") == "image/png", "p9: image not PNG")


def check_animations(g, r):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm = g.names["viewmodel"]
    cam = g.names["camera"]
    for nm, (lo, hi) in DURATIONS.items():
        if not r.check(nm in anims, f"p9: animation {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-4 <= dur <= hi + 1e-4, f"p9: {nm} duration {dur:.3f} outside [{lo}, {hi}]")
        r.note(f"{nm}: {dur:.3f}s, {len(a['channels'])} channels")
        for ch in a["channels"]:
            node = ch["target"].get("node")
            path = ch["target"]["path"]
            r.check(node is not None and node < len(g.nodes), f"p9: {nm} channel targets a missing node")
            r.check(node != vm, f"p9: {nm} animates viewmodel")
            r.check(path in ("translation", "rotation", "scale"), f"p9: {nm} uses path {path}")
            s = a["samplers"][ch["sampler"]]
            r.check(s.get("interpolation", "LINEAR") in ("LINEAR", "STEP"), f"p9: {nm} interpolation")
            ts = g.accessor(s["input"])[:, 0]
            vs = g.accessor(s["output"]).astype(np.float64)
            r.check(abs(ts[0]) < 1e-6, f"p9: {nm} channel does not start at 0")
            r.check(np.all(np.diff(ts) > 0), f"p9: {nm} key times not increasing")
            r.check(len(ts) == len(vs), f"p9: {nm} input/output count mismatch")
            if path == "rotation":
                r.check(np.all(np.abs(np.linalg.norm(vs, axis=1) - 1) < 1e-4), f"p9: {nm} quaternions not normalized")
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"p9: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if nm in NO_CAMERA:
                r.check(node != cam, f"p9: {nm} must not animate the camera node")
            if node == cam:
                r.check(path == "rotation", f"p9: {nm} animates camera {path}")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= 1.5 + 1e-3) and np.all(np.abs(eul[:, 2]) <= 3.0 + 1e-3),
                        f"p9: {nm} camera rotation exceeds limits (max {np.abs(eul).max(0).round(2)})")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"p9: {nm} camera not identity at end")
            if g.nodes[node]["name"] == "mag" and path == "scale":
                r.check(s.get("interpolation") == "STEP", f"p9: {nm} mag scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"p9: {nm} mag scale not 0/1")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"p9: {nm} has no extras.events list"):
            for e in ev:
                r.check(e.get("name") in EVENT_NAMES, f"p9: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"p9: {nm} event {e} outside the clip")
            have = {e["name"] for e in ev}
            r.check(REQUIRED_EVENTS.get(nm, set()) <= have, f"p9: {nm} missing events {REQUIRED_EVENTS.get(nm, set()) - have}")
            r.note(f"   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    return anims


def mesh_points(g, mesh_index):
    return [p["positions"] for p in g.mesh_primitives(mesh_index)]


def check_eye_distance(g, arms, r, anims, root="gun", label="p9"):
    """Every vertex of every drawn mesh stays >= 0.06 from the eye in sampled frames of every clip."""
    gun_meshes = []
    for i, n in enumerate(g.nodes):
        if "mesh" in n and n["name"] != "fx_shell":
            gun_meshes.append((i, np.concatenate(mesh_points(g, n["mesh"]))))
    arm_meshes = []
    if arms is not None:
        for side in "RL":
            for seg, joint in (("upper", f"arm_{side}"), ("fore", f"arm_{side}_fore")):
                pts = [np.concatenate(mesh_points(arms, arms.nodes[arms.names[f"{side}_{seg}_{v}{x}"]]["mesh"]))
                       for v in ("wide", "slim") for x in ("", "_sleeve")]
                arm_meshes.append((g.names[joint], np.concatenate(pts)))
    worst = {}
    onscreen = {}
    t70 = np.tan(np.radians(FOV_Y / 2))
    gun_root = g.names[root]
    for nm, a in [("rest", None)] + list(anims.items()):
        dur = g.duration(a) if a is not None else 0.0
        times = np.unique(np.concatenate([np.arange(0, dur + 1e-9, 1 / 60), [dur]]))
        best = (np.inf, 0, "")
        fr = []
        for t in times:
            W = g.world_matrices(g.sample(a, t) if a is not None else {})
            gun_pts = []
            for node, pts in gun_meshes + arm_meshes:
                M = W[node]
                if abs(np.linalg.det(M[:3, :3])) < 1e-12:
                    continue
                w = pts @ M[:3, :3].T + M[:3, 3]
                d = np.linalg.norm(w, axis=1)
                k = int(np.argmin(d))
                if d[k] < best[0]:
                    best = (float(d[k]), float(t), g.nodes[node]["name"])
                if node in (gun_root,) or g.parent.get(node) == gun_root:
                    gun_pts.append(w)
            if gun_pts:
                w = np.concatenate(gun_pts)
                zf = -w[:, 2]
                vis = (zf > 0.05) & (np.abs(w[:, 1]) <= zf * t70) & (np.abs(w[:, 0]) <= zf * t70 * 16 / 9)
                fr.append(vis.mean())
        worst[nm] = best
        onscreen[nm] = (min(fr), float(np.mean(fr))) if fr else (0, 0)
        r.check(best[0] >= 0.06, f"{label}: {nm} comes within {best[0]:.3f} of the eye at t={best[1]:.3f} ({best[2]})")
        r.note(f"{nm}: min eye distance {best[0]:.3f} (t={best[1]:.2f}, {best[2]}); {root} vertices on screen "
               f"min {onscreen[nm][0]:.0%} mean {onscreen[nm][1]:.0%}")
    return worst


def check_sights(g, r):
    W = g.world_matrices({})
    ret = W[g.names["reddot_reticle"]]
    aim = W[g.names["aim_reddot"]]
    v = ret[:3, 3] - aim[:3, 3]
    fwd = -aim[:3, 2] / np.linalg.norm(aim[:3, 2])
    off = v - fwd * np.dot(v, fwd)
    r.check(np.linalg.norm(off) < 1e-5 and np.dot(v, fwd) > 0, "p9: reticle not on the aim_reddot sight line")
    r.note(f"aim_reddot -> reticle distance {np.dot(v, fwd):.4f}")
    bore = -W[g.names["muzzle"]][:3, 2]
    for nm in ("aim_iron", "aim_reddot", "laser_emitter"):
        d = -W[g.names[nm]][:3, 2]
        r.check(np.dot(d, bore) > 0.9999, f"p9: {nm} -Z not parallel to the bore")
    ej = W[g.names["ejection"]][:3, 0]
    gx = W[g.names["gun"]][:3, 0]
    r.check(np.dot(ej, gx) > 0.5, "p9: ejection +X does not point to the right of the gun")


def check_arms(a, r):
    names = [n.get("name") for n in a.nodes]
    for nm in ARM_MESHES:
        if not r.check(nm in names, f"arms: missing {nm}"):
            continue
        n = a.nodes[a.names[nm]]
        r.check(a.names[nm] not in a.parent, f"arms: {nm} is not a root node")
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"arms: {nm} transform not identity")
        for p in a.mesh_primitives(n["mesh"]):
            mat = a.json["materials"][p["material"]]
            want = "skin_overlay" if nm.endswith("_sleeve") else "skin"
            r.check(mat["name"] == want, f"arms: {nm} uses material {mat['name']}")
            uv = p["uvs"]
            r.check(uv.min() >= 0 and uv.max() <= 1, f"arms: {nm} UVs outside [0,1]")
            pos = p["positions"]
            g = 0.004 if nm.endswith("_sleeve") else 0.0
            w = (0.0525 if "_slim" in nm else 0.07) + 2 * g
            r.check(np.isclose(np.ptp(pos[:, 0]), w, atol=1e-5) and np.isclose(np.ptp(pos[:, 2]), 0.07 + 2 * g, atol=1e-5),
                    f"arms: {nm} cross-section {np.ptp(pos[:, 0]):.4f} x {np.ptp(pos[:, 2]):.4f}")
            r.check(np.isclose(pos[:, 1].max(), g, atol=1e-6) and np.isclose(pos[:, 1].min(), -0.30 - g, atol=1e-6),
                    f"arms: {nm} length range {pos[:, 1].min():.3f}..{pos[:, 1].max():.3f}")
    mats = {m["name"]: m for m in a.json["materials"]}
    r.check(mats.get("skin_overlay", {}).get("alphaMode") == "MASK" and
            abs(mats["skin_overlay"].get("alphaCutoff", 0) - 0.1) < 1e-6, "arms: skin_overlay must be MASK 0.1")


def check_gun(path, arms, r):
    g = GLTF(path)
    check_container(g, r, "p9")
    check_meshes(g, r, "p9")
    check_nodes(g, r)
    check_materials(g, r)
    anims = check_animations(g, r)
    check_sights(g, r)
    check_eye_distance(g, arms, r, anims)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.note(f"gun triangles (all attachments, excluding fx_shell): {tris}")


def main(argv):
    arms_path = next((a for a in argv if a.endswith("arms.glb")), os.path.join(GUN_DIR, "arms.glb"))
    paths = [a for a in argv if a.endswith(".glb") and not a.endswith("arms.glb")]
    if not paths:
        paths = [os.path.join(GUN_DIR, "p9.glb")] + sorted(
            os.path.join(KNIFE_DIR, f) for f in (os.listdir(KNIFE_DIR) if os.path.isdir(KNIFE_DIR) else [])
            if f.endswith(".glb"))
    arms = GLTF(arms_path) if os.path.exists(arms_path) else None
    failed = 0
    if arms is not None:
        r = Report()
        check_container(arms, r, "arms")
        check_meshes(arms, r, "arms")
        check_arms(arms, r)
        failed += report("arms.glb", r)
    else:
        print("FAILED: arms.glb missing")
        failed += 1
    for path in paths:
        r = Report()
        names = GLTF(path).names
        if "knife" in names:
            import check_knife
            check_knife.check(path, arms, r)
        else:
            check_gun(path, arms, r)
        failed += report(os.path.basename(path), r)
    return 1 if failed else 0


def report(name, r):
    print(f"== {name}")
    for n in r.notes:
        print("  " + n)
    if r.errors:
        print(f"FAILED ({len(r.errors)} problems):")
        for e in r.errors:
            print("  - " + e)
        return 1
    print(f"OK: {name} follows the contract")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
