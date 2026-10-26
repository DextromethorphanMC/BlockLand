"""Offline check of the connected-texture layout (rendering spec section 25).

python -I tools/ctm/check_ctm.py

* the 47-tile ctm layout: exactly 47 reduced masks, tile 0 = alone, tile 46 = surrounded, every raw mask maps to a tile
  whose frame matches it (edges drawn exactly where the edge neighbour is missing, inner-corner notches exactly where
  both edges join but the diagonal does not);
* the Java twin (client/ctm/CtmTiles.java) uses the same direction order, numbering and permutation;
* whatever way a quad's uvs run (the 8 rotations / mirrorings), the frame drawn by the chosen tile lies on exactly the
  world sides that lack a neighbour;
* every ctm rule file in the mod's resources (assets and the built-in packs) names tiles that exist under
  textures/block (the block atlas)."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)

import ctm_layout as L  # noqa: E402

JAVA = os.path.join(REPO, "mod", "src", "main", "java", "com", "blockland", "client", "ctm", "CtmTiles.java")
RESOURCES = os.path.join(REPO, "mod", "src", "main", "resources")
failures = []


def check(cond, what):
    if not cond:
        failures.append(what)


def frame(mask):
    """What a tile draws: (edges with a frame, corners with an inner notch)."""
    r = L.reduce(mask)
    edges = {e for e in (L.UP, L.RIGHT, L.DOWN, L.LEFT) if not L.has(r, e)}
    notches = {c for c, (a, b) in L.CORNERS.items() if L.has(r, a) and L.has(r, b) and not L.has(r, c)}
    return edges, notches


def layout():
    check(len(L.CTM_MASKS) == 47, f"ctm layout has {len(L.CTM_MASKS)} tiles, expected 47")
    check(L.ctm(0) == 0, "tile 0 is not the block alone")
    check(L.ctm(0xFF) == 46, "tile 46 is not the surrounded block")
    seen = set()
    for raw in range(256):
        t = L.ctm(raw)
        seen.add(t)
        edges, notches = frame(L.CTM_MASKS[t])
        want_edges = {e for e in (L.UP, L.RIGHT, L.DOWN, L.LEFT) if not L.has(raw, e)}
        want_notches = {c for c, (a, b) in L.CORNERS.items() if L.has(raw, a) and L.has(raw, b) and not L.has(raw, c)}
        check(edges == want_edges and notches == want_notches, f"mask {raw:08b}: tile {t} draws {edges}/{notches}")
    check(seen == set(range(47)), "not every tile is reachable")
    check([L.horizontal(m) for m in (0, 1 << L.LEFT, 1 << L.RIGHT, 1 << L.LEFT | 1 << L.RIGHT)] == [0, 1, 2, 3], "horizontal tiles")
    check([L.vertical(m) for m in (0, 1 << L.UP, 1 << L.DOWN, 1 << L.UP | 1 << L.DOWN)] == [0, 1, 2, 3], "vertical tiles")


def java_twin():
    src = open(JAVA, encoding="utf-8").read()
    m = re.search(r"DIRS = \{(.*?)\};", src)
    check(m is not None, "CtmTiles.DIRS not found")
    if m:
        dirs = [tuple(int(x) for x in p.split(",")) for p in re.findall(r"\{\s*(-?\d+\s*,\s*-?\d+)\s*\}", m.group(1))]
        check(dirs == L.DIRS, f"CtmTiles.DIRS {dirs} differs from {L.DIRS}")
    for name, value in (("UP", 0), ("RIGHT", 1), ("DOWN", 2), ("LEFT", 3), ("UP_RIGHT", 4), ("DOWN_RIGHT", 5), ("DOWN_LEFT", 6),
                        ("UP_LEFT", 7)):
        check(re.search(rf"\b{name} = {value}\b", src) is not None, f"CtmTiles.{name} is not {value}")
    check("CTM_TILES = 47" in src, "CtmTiles.CTM_TILES is not 47")
    check("int fx = tx * uRight - ty * vRight;" in src and "int fy = tx * uUp - ty * vUp;" in src,
          "CtmTiles.permutation differs from the checked formula")


def permutation(u_right, u_up, v_right, v_up):
    """CtmTiles.permutation: texture direction t -> face direction (texture right = +u, texture up = -v)."""
    out = []
    for tx, ty in L.DIRS:
        fx = tx * u_right - ty * v_right
        fy = tx * u_up - ty * v_up
        out.append(L.DIRS.index((fx, fy)))
    return out


def frames():
    orientations = []
    for u in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        for v in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (u[0] != 0) != (v[0] != 0):
                orientations.append((u, v))
    check(len(orientations) == 8, "expected 8 uv orientations")
    for u, v in orientations:
        perm = permutation(u[0], u[1], v[0], v[1])
        check(sorted(perm) == list(range(8)), f"permutation {perm} is not one")
        for face_mask in range(256):
            tex = sum(((face_mask >> perm[t]) & 1) << t for t in range(8))
            edges, notches = frame(L.CTM_MASKS[L.ctm(tex)])
            world_edges = {perm[e] for e in edges}
            want = {d for d in (0, 1, 2, 3) if not L.has(face_mask, d)}
            check(world_edges == want, f"orientation {u},{v} mask {face_mask:08b}: frame on {world_edges}, wanted {want}")
            world_notches = {perm[c] for c in notches}
            want_notches = {c for c, (a, b) in L.CORNERS.items()
                            if L.has(face_mask, a) and L.has(face_mask, b) and not L.has(face_mask, c)}
            check(world_notches == want_notches, f"orientation {u},{v} mask {face_mask:08b}: notches wrong")


def rule_files():
    roots = [os.path.join(RESOURCES, "assets")]
    packs = os.path.join(RESOURCES, "resourcepacks")
    if os.path.isdir(packs):
        roots += [os.path.join(packs, p, "assets") for p in sorted(os.listdir(packs))]
    count = 0
    for assets in roots:
        if not os.path.isdir(assets):
            continue
        for ns in sorted(os.listdir(assets)):
            folder = os.path.join(assets, ns, "ctm")
            if not os.path.isdir(folder):
                continue
            for name in sorted(os.listdir(folder)):
                if not name.endswith(".json"):
                    continue
                data = json.load(open(os.path.join(folder, name), encoding="utf-8"))
                for rule in data.get("rules", [data]):
                    count += 1
                    method = rule.get("method", "ctm")
                    need = {"ctm": 47, "horizontal": 4, "vertical": 4}.get(method, rule.get("count"))
                    tiles = rule["tiles"]
                    if isinstance(tiles, str):
                        tiles = [f"{tiles}/{i}" for i in range(need)]
                    check(len(tiles) == need, f"{ns}/ctm/{name}: {len(tiles)} tiles, {method} needs {need}")
                    for t in tiles:
                        tns, path = t.split(":", 1) if ":" in t else ("minecraft", t)
                        png = os.path.join(assets, tns, "textures", *path.split("/")) + ".png"
                        check(os.path.isfile(png), f"{ns}/ctm/{name}: tile {t} missing")
                        check(path.startswith("block/"), f"{ns}/ctm/{name}: tile {t} is not under textures/block")
    return count


def main():
    layout()
    java_twin()
    frames()
    rules = rule_files()
    if failures:
        for f in failures[:40]:
            print("FAIL", f)
        print(f"{len(failures)} failure(s)")
        return 1
    print(f"ctm layout OK: 47 tiles, 256 masks x 8 uv orientations; {rules} rule(s) with all tiles present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
