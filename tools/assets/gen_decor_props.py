"""Road-side props of decor/DecorContent (RENDERING_SPEC §28), in the same vanilla style as gen_decor.py and with its
helpers: 16 px pixel art (own, deterministic), JSON element models, block states, loot tables, tags.

python -I tools/assets/gen_decor_props.py      (gen_decor.py also runs it)

* rear_view_mirror (后视镜): a wing mirror smaller than a block — a black housing with a glass face and an arm back to the
  wall it was placed on, or a short stalk and foot on a floor; the glass faces away from the wall / towards the placer.
* license_plate (车牌): 2 wide x 1 high, 1 px thick; two parts (left / right seen from the front) cut from one 32 px
  picture (an original plate: a blue band, a made-up registration); flat on the wall it was placed on, or standing on two
  posts on a floor.
* <colour>_oil_drum (油桶, red / blue / yellow): a steel drum 1 block across and 1.5 tall (a full lower block, the upper
  one 8 px), its cross-section three crossed boxes (a voxel circle), rolling hoops and chimes as 1 px rings, two bungs on
  the lid, a hazard label towards the placer.
* tire (轮胎): 1-3 tyres in one block, an octagonal ring of rubber 14 px across with an 8 px hole; lying flat (stacked
  up, 5 px each) or upright on the tread (side by side along the axle, centred).
Keep the ids and boxes in step with DecorContent and the block classes (tools/assets/check_decor.py checks)."""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gen_decor as d  # noqa: E402
from gen_decor import N, FACINGS, box, canvas, face, loot, model, rgba, save, shade, simple_pool, speckle, state_is, \
    tex, uv_for, write_json, all_faces, icon, item  # noqa: E402

DRUM_COLOURS = {"red": (176, 40, 34), "blue": (44, 86, 164), "yellow": (222, 172, 42)}
RUBBER = (36, 36, 39)
RUBBER_LIGHT = (56, 56, 60)
RUBBER_DARK = (20, 20, 22)
STEEL = (150, 154, 160)
STEEL_LIGHT = (196, 200, 206)
STEEL_DARK = (96, 100, 106)
PLASTIC = (30, 30, 33)
PLASTIC_LIGHT = (52, 52, 57)


def blockstate(block, variants):
    write_json(os.path.join(d.ASSETS, "blockstates", f"{block}.json"), {"variants": variants})


def turned(ref, y):
    v = {"model": ref}
    if y:
        v["y"] = y
    return v


# ---- rear-view mirror -------------------------------------------------------------------------------

# glass towards north, the wall (if any) south; keep in step with RearViewMirrorBlock.WALL_NORTH / FLOOR_NORTH
MIRROR_HOUSING = [((2, 4, 9), (14, 12, 12)), ((3, 5, 12), (13, 11, 13))]
MIRROR_GLASS = ((3, 5, 8.75), (13, 11, 9))
MIRROR_WALL = [((7, 7, 13), (9, 9, 16))]
MIRROR_FLOOR = [((7, 0, 10), (9, 4, 12)), ((5, 0, 8), (11, 1, 14))]


def mirror():
    rng = np.random.default_rng(21)
    housing = canvas(fill=PLASTIC)
    speckle(housing, rng, [PLASTIC_LIGHT, shade(PLASTIC, 0.8)], 0.15)
    housing[4, :, :3] = PLASTIC_LIGHT                                   # a moulding line catching the light
    save(housing, "rear_view_mirror/housing")
    # the glass (where the north face's automatic uv lands: columns 3..13, rows 5..11): the sky above, the road below,
    # two bright streaks across, a dark edge where it meets the housing
    glass = canvas(fill=(150, 176, 196))
    for y in range(N):
        t = np.clip((y - 5) / 6.0, 0, 1)
        glass[y, :, :3] = d.mix((196, 222, 242), (104, 120, 136), t)
    for x in range(N):
        for y in range(N):
            k = x + y
            if k in (12, 13) or k == 18:
                glass[y, x, :3] = d.mix(tuple(glass[y, x, :3]), (246, 250, 255), 0.7 if k != 18 else 0.45)
    glass[5, 3:13, :3] = (70, 80, 92)
    glass[10, 3:13, :3] = (70, 80, 92)
    glass[5:11, 3, :3] = (70, 80, 92)
    glass[5:11, 12, :3] = (70, 80, 92)
    save(glass, "rear_view_mirror/glass")
    h, g = tex("rear_view_mirror/housing"), tex("rear_view_mirror/glass")
    refs = {}
    for mount, extra in (("wall", MIRROR_WALL), ("floor", MIRROR_FLOOR)):
        elements = [box(f, t, all_faces(f, t, h)) for f, t in MIRROR_HOUSING + extra]
        f, t = MIRROR_GLASS
        elements.append(box(f, t, {"north": face(g, uv_for(f, t, "north"))}))
        refs[mount] = model(f"rear_view_mirror_{mount}", {"parent": "minecraft:block/block", "textures": {"particle": h},
                                                           "elements": elements})
    blockstate("rear_view_mirror", {f"facing={facing},wall={str(wall).lower()}": turned(refs["wall" if wall else "floor"], y)
                                    for facing, y in FACINGS.items() for wall in (False, True)})
    item("rear_view_mirror", refs["floor"])
    loot("rear_view_mirror")


# ---- license plate ------------------------------------------------------------------------------------

# a 3 x 5 font for the registration
GLYPHS = {
    "B": ["##.", "#.#", "##.", "#.#", "##."], "L": ["#..", "#..", "#..", "#..", "###"],
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"], "2": ["###", "..#", "###", "#..", "###"],
    "6": ["###", "#..", "###", "#.#", "###"], "-": ["...", "...", "###", "...", "..."],
}
PLATE_TEXT = "BL-426"
PLATE_WHITE = (236, 236, 226)
PLATE_INK = (28, 34, 52)
PLATE_BLUE = (36, 78, 170)


def plate_front():
    """The 32 x 16 front: the plate on columns 1..30, rows 2..13 (the boxes' automatic uv), the rest never shows."""
    img = canvas(32, 16, PLATE_WHITE)
    rng = np.random.default_rng(22)
    speckle(img, rng, [shade(PLATE_WHITE, 0.95)], 0.12)
    x0, x1, y0, y1 = 1, 31, 2, 14
    img[y0, x0:x1, :3] = PLATE_INK
    img[y1 - 1, x0:x1, :3] = PLATE_INK
    img[y0:y1, x0, :3] = PLATE_INK
    img[y0:y1, x1 - 1, :3] = PLATE_INK
    img[y0 + 1, x0 + 1:x1 - 1, :3] = (255, 255, 250)                   # the pressed rim catching the light
    img[3:5, 2:30, :3] = PLATE_BLUE                                      # the band
    for x in range(4, 28, 3):
        img[3, x, :3] = (120, 156, 226)
    for bx in (5, 26):                                                   # the screws in the band
        img[4, bx, :3] = STEEL_LIGHT
    width = len(PLATE_TEXT) * 4 - 1
    cx = x0 + (x1 - x0 - width) // 2
    for i, ch in enumerate(PLATE_TEXT):
        for r, row in enumerate(GLYPHS[ch]):
            for c, on in enumerate(row):
                if on == "#":
                    x, y = cx + i * 4 + c, 6 + r
                    img[y + 1, x + 1, :3] = (178, 178, 170)              # the embossing's shadow
                    img[y, x, :3] = PLATE_INK
    return img


# front north, the wall (if any) south; left = the viewer's left (east); keep in step with LicensePlateBlock
PLATE_WALL = {"left": [((0, 2, 15), (15, 14, 16))], "right": [((1, 2, 15), (16, 14, 16))]}
PLATE_FLOOR = {"left": [((0, 2, 7), (15, 14, 8)), ((7, 0, 7), (9, 2, 8)), ((5, 0, 6), (11, 1, 9))],
               "right": [((1, 2, 7), (16, 14, 8)), ((7, 0, 7), (9, 2, 8)), ((5, 0, 6), (11, 1, 9))]}


def plate():
    front = plate_front()
    save(front[:, 0:16].copy(), "license_plate/front_left")
    save(front[:, 16:32].copy(), "license_plate/front_right")
    rng = np.random.default_rng(23)
    back = canvas(fill=STEEL)
    speckle(back, rng, [STEEL_LIGHT, STEEL_DARK], 0.18)
    for y in (4, 11):
        for x in (5, 10):
            back[y, x, :3] = (40, 42, 46)                                # the screw holes
    save(back, "license_plate/back")
    s = tex("license_plate/back")
    for part in ("left", "right"):
        fr = tex(f"license_plate/front_{part}")
        for mount, boxes in (("wall", PLATE_WALL), ("floor", PLATE_FLOOR)):
            elements = []
            for i, (f, t) in enumerate(boxes[part]):
                faces = all_faces(f, t, s, per={"north": fr} if i == 0 else None)
                if i == 0:
                    # the plate's edges outside its own block's outer side are the seam: no face there
                    faces.pop("west" if part == "left" else "east", None)
                elements.append(box(f, t, faces))
            model(f"license_plate_{part}_{mount}", {"parent": "minecraft:block/block", "textures": {"particle": s},
                                                    "elements": elements})
    variants = {}
    for facing, y in FACINGS.items():
        for part in ("left", "right"):
            for wall in (False, True):
                variants[f"facing={facing},part={part},wall={str(wall).lower()}"] = turned(
                    f"blockland:block/decor/license_plate_{part}_{'wall' if wall else 'floor'}", y)
    blockstate("license_plate", variants)
    ic = canvas(a=0)
    ic[4:12, 0:16] = rgba(PLATE_WHITE)
    ic[4, :, :3] = PLATE_INK
    ic[11, :, :3] = PLATE_INK
    ic[4:12, 0, :3] = PLATE_INK
    ic[4:12, 15, :3] = PLATE_INK
    ic[5, 1:15, :3] = PLATE_BLUE
    for x in (2, 3, 5, 6, 8, 10, 11, 13):
        ic[7:10, x, :3] = PLATE_INK
    icon(ic, "license_plate")
    loot("license_plate", [simple_pool("license_plate", [state_is("license_plate", part="left")])])


# ---- oil drums ----------------------------------------------------------------------------------------

# the cross-section (a voxel circle of radius 7) and the 1 px rings round it; keep in step with OilDrumBlock.BODY
DRUM_BODY = [((1, 4), (15, 12)), ((2, 2), (14, 14)), ((4, 1), (12, 15))]
DRUM_RING = [((0, 3), (16, 13)), ((1, 1), (15, 15)), ((3, 0), (13, 16))]
# the drum is 24 px tall: the upper block holds its top 8 px; keep in step with OilDrumBlock.UPPER_HEIGHT
DRUM_TOP = {"lower": 16, "upper": 8}
# a rolling hoop a third and two thirds up, a chime 1 px in from either end (never on the end faces: no coplanar faces)
DRUM_RINGS = {"lower": [(1, 2), (7, 8), (14, 15)], "upper": [(6, 7)]}


def drum_textures(colour, rng):
    base = DRUM_COLOURS[colour]
    rust = (118, 66, 36)
    for half in ("lower", "upper"):
        side = canvas(fill=base)
        for x in range(N):
            # rounder towards the middle of the drum (the faces' automatic uv runs across the whole drum)
            side[:, x, :3] = shade(base, 1.12 - 0.3 * abs(x - 7.5) / 7.5)
        speckle(side, rng, [shade(base, 0.86), shade(base, 1.08)], 0.10)
        speckle(side, rng, [rust, shade(rust, 1.2)], 0.025)
        if half == "lower":
            side[13:16, :, :3] = np.minimum(side[13:16, :, :3], np.array(shade(base, 0.8), dtype=np.uint8))   # grime
        save(side, f"oil_drum/{colour}_side_{half}")
        if half == "upper":
            # the upper block shows rows 8-15 (its 8 px, vanilla's automatic uv): the label sits under the chime
            label = side.copy()
            label[10:16, 4:12, :3] = (238, 236, 226)
            label[10, 4:12, :3] = (60, 60, 64)
            label[15, 4:12, :3] = (60, 60, 64)
            # a diamond with a flame in it
            for y in range(11, 15):
                r = 2.5 - abs(y - 12.5)
                for x in range(5, 11):
                    if abs(x - 7.5) <= r:
                        label[y, x, :3] = (232, 120, 30)
                    if abs(abs(x - 7.5) - r) < 0.6:
                        label[y, x, :3] = (40, 36, 34)
            label[12:14, 7:9, :3] = (40, 36, 34)
            save(label, f"oil_drum/{colour}_label")
        top = canvas(fill=shade(base, 1.04))
        speckle(top, rng, [shade(base, 0.9), rust], 0.08)
        yy, xx = np.mgrid[0:N, 0:N] + 0.5
        dist = np.hypot(xx - 8, yy - 8)
        top[np.abs(dist - 5.8) < 0.5, :3] = shade(base, 0.72)            # the lid's pressed ring
        save(top, f"oil_drum/{colour}_top")
    ring = canvas(fill=shade(base, 0.82))
    ring[7:9, :, :3] = shade(base, 1.15)
    speckle(ring, rng, [rust, shade(base, 0.7)], 0.08)
    save(ring, f"oil_drum/{colour}_ring")


def steel_texture():
    rng = np.random.default_rng(24)
    img = canvas(fill=STEEL)
    speckle(img, rng, [STEEL_LIGHT, STEEL_DARK], 0.25)
    save(img, "oil_drum/steel")
    bottom = canvas(fill=(46, 44, 44))
    speckle(bottom, rng, [(60, 58, 56), (32, 30, 30)], 0.25)
    save(bottom, "oil_drum/bottom")


def drums():
    steel_texture()
    rng = np.random.default_rng(25)
    for colour in DRUM_COLOURS:
        block = f"{colour}_oil_drum"
        drum_textures(colour, rng)
        side = {h: tex(f"oil_drum/{colour}_side_{h}") for h in ("lower", "upper")}
        label, top, ring = tex(f"oil_drum/{colour}_label"), tex(f"oil_drum/{colour}_top"), tex(f"oil_drum/{colour}_ring")
        steel, bottom = tex("oil_drum/steel"), tex("oil_drum/bottom")
        for half in ("lower", "upper"):
            elements = []
            for i, ((x0, z0), (x1, z1)) in enumerate(DRUM_BODY):
                f, t = (x0, 0, z0), (x1, DRUM_TOP[half], z1)
                per = {}
                if half == "upper":
                    per["up"] = top
                    if i == 2:
                        per["north"] = label
                else:
                    per["down"] = bottom
                sides = ("north", "south", "west", "east", "up") if half == "upper" else ("north", "south", "west", "east", "down")
                elements.append(box(f, t, all_faces(f, t, side[half], sides=sides, per=per)))
            for y0, y1 in DRUM_RINGS[half]:
                for (x0, z0), (x1, z1) in DRUM_RING:
                    f, t = (x0, y0, z0), (x1, y1, z1)
                    elements.append(box(f, t, all_faces(f, t, ring)))
            if half == "upper":
                for f, t in (((9, 8, 3), (12, 9, 6)), ((4, 8, 10), (6, 8.5, 12))):   # the bungs
                    elements.append(box(f, t, all_faces(f, t, steel)))
            model(f"{block}_{half}", {"parent": "minecraft:block/block", "textures": {"particle": side["lower"]},
                                      "elements": elements})
        blockstate(block, {f"facing={facing},half={half}": turned(f"blockland:block/decor/{block}_{half}", y)
                           for facing, y in FACINGS.items() for half in ("lower", "upper")})
        base = DRUM_COLOURS[colour]
        ic = canvas(a=0)
        for x in range(2, 14):
            ic[3:15, x] = rgba(shade(base, 1.1 - 0.35 * abs(x - 7.5) / 6))
        for y in (3, 7, 11, 14):
            ic[y, 1:15] = rgba(shade(base, 0.75))
        ic[4:7, 6:10] = rgba((238, 236, 226))
        ic[5, 7:9] = rgba((232, 120, 30))
        icon(ic, block)
        loot(block, [simple_pool(block, [state_is(block, half="lower")])])


# ---- tires --------------------------------------------------------------------------------------------

# one lying tyre's ring in x/z (outer octagon 14 px across, an 8 px hole); keep in step with TireBlock
TIRE_RING = [((4, 1), (12, 4)), ((4, 12), (12, 15)), ((1, 4), (4, 12)), ((12, 4), (15, 12)),
             ((2, 2), (5, 5)), ((11, 2), (14, 5)), ((2, 11), (5, 14)), ((11, 11), (14, 14))]
TIRE_W = 5


def tire_textures():
    rng = np.random.default_rng(26)
    tread = canvas(fill=RUBBER)
    for y in range(N):
        for x in range(N):
            if y % 4 == 0 or (x + (2 if (y // 4) % 2 else 0)) % 4 == 0:
                tread[y, x, :3] = RUBBER_DARK                             # the grooves between the lugs
    speckle(tread, rng, [RUBBER_LIGHT], 0.06)
    save(tread, "tire/tread")
    for name, cy in (("sidewall", 8.0), ("sidewall_upright", 9.0)):
        side = canvas(fill=RUBBER)
        yy, xx = np.mgrid[0:N, 0:N] + 0.5
        dist = np.hypot(xx - 8, yy - cy)
        ang = np.degrees(np.arctan2(yy - cy, xx - 8)) % 360
        side[dist < 4.6, :3] = RUBBER_DARK                                # the bead round the hole
        side[np.abs(dist - 6.6) < 0.45, :3] = RUBBER_LIGHT                # the rim guard
        letters = (np.abs(dist - 5.4) < 0.5) & (((ang > 200) & (ang < 340)) | ((ang > 20) & (ang < 70)))
        side[letters & (np.floor(ang / 9) % 2 == 0), :3] = (150, 150, 150)  # the lettering, a dash a letter
        speckle(side, rng, [shade(RUBBER, 1.15)], 0.05)
        save(side, f"tire/{name}")


def tire_boxes(n, upright):
    """The boxes of n tyres: lying (stacked from the floor) or upright with the axle along z (centred)."""
    out = []
    start = (16 - TIRE_W * n) / 2
    for i in range(n):
        for (x0, z0), (x1, z1) in TIRE_RING:
            if upright:
                out.append(((x0, z0 - 1, start + i * TIRE_W), (x1, z1 - 1, start + (i + 1) * TIRE_W)))
            else:
                out.append(((x0, i * TIRE_W, z0), (x1, (i + 1) * TIRE_W, z1)))
    return out


def tires():
    tire_textures()
    tread = tex("tire/tread")
    refs = {}
    for upright in (False, True):
        wall = tex("tire/sidewall_upright" if upright else "tire/sidewall")
        rings = ("north", "south") if upright else ("up", "down")
        for n in (1, 2, 3):
            elements = []
            for f, t in tire_boxes(n, upright):
                elements.append(box(f, t, all_faces(f, t, tread, per={s: wall for s in rings})))
            name = f"tire_{'upright' if upright else 'flat'}_{n}"
            refs[(upright, n)] = model(name, {"parent": "minecraft:block/block", "textures": {"particle": tread},
                                              "elements": elements})
    variants = {}
    for n in (1, 2, 3):
        for upright in (False, True):
            for axis in ("x", "z"):
                variants[f"axis={axis},tires={n},upright={str(upright).lower()}"] = turned(refs[(upright, n)], 90 if axis == "x" else 0)
    blockstate("tire", variants)
    item("tire", refs[(True, 1)])
    entries = [{"type": "minecraft:item", "name": "blockland:tire",
                "conditions": [state_is("tire", tires=str(n))],
                "functions": [{"function": "minecraft:set_count", "count": n, "add": False}]} for n in (1, 2, 3)]
    loot("tire", [{"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:alternatives", "children": entries}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}])


def tags():
    tag = os.path.join(d.DATA, "minecraft", "tags", "block", "mineable")
    d.merge_tag(os.path.join(tag, "pickaxe.json"), ["blockland:license_plate", "blockland:rear_view_mirror"]
                + [f"blockland:{c}_oil_drum" for c in DRUM_COLOURS])


def main():
    mirror()
    plate()
    drums()
    tires()
    tags()
    print(f"decor props: rear-view mirror, license plate, {len(DRUM_COLOURS)} oil drums, tire x 3 x 2")


if __name__ == "__main__":
    main()
