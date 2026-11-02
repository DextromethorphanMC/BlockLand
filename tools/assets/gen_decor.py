"""Vanilla-style building blocks of decor/DecorContent: 16 px textures (own pixel art, deterministic), block and item
models (JSON elements, vanilla shading), block states, loot tables and the mineable tags.

python -I tools/assets/gen_decor.py

* vending_machine (自动贩卖机): 2 wide x 2 tall, four parts (lower_left / lower_right / upper_left / upper_right seen from
  the front); the front is one 32 px picture cut into quarters: a lit display window of drinks, the payment panel
  (display, keypad, coin and note slots), the dispensing flap. Emissive texels through graphics/block_materials.json.
* target_stand (靶子): the easel stand (lower half) and a straw board printed with the archery rings (upper half).
* swim_ring (游泳圈): a lifebuoy ring of eight segments, white and red alternating.
* grass_layer / stone_layer (草台 / 石台): eight heights like snow, the grass block's top (biome tinted) / stone.
* corals: staghorn, sea_whip (cross plants), table, pillar, brain_dome, tube_cluster (element shapes); alive and dead.
* reef_coral (礁珊瑚): multipart, a core and an arm per joined side (chorus plant style); alive and dead.
* the road-side props (rear-view mirror, license plate, oil drums, tires): gen_decor_props.py, run at the end.
Keep the ids in step with DecorContent (tools/assets/check_decor.py checks both)."""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)

import textures as tx  # noqa: E402
import filelock  # noqa: E402

RES = os.path.join(REPO, "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "blockland")
DATA = os.path.join(RES, "data")
TEX = os.path.join(ASSETS, "textures", "block", "decor")
N = 16
FACINGS = {"north": 0, "east": 90, "south": 180, "west": 270}

CORAL_PLANTS = ["staghorn", "sea_whip"]                     # cross models
CORAL_SHAPES = ["table", "pillar", "brain_dome", "tube_cluster"]   # element models
# alive colours (base, light, dark), vanilla-coral-like saturation
CORAL_COLOURS = {
    "staghorn": ((222, 128, 52), (247, 176, 92), (160, 78, 30)),
    "sea_whip": ((200, 44, 64), (236, 92, 104), (130, 22, 40)),
    "table": ((54, 170, 176), (110, 214, 214), (28, 110, 120)),
    "pillar": ((218, 194, 118), (244, 228, 162), (156, 128, 66)),
    "brain_dome": ((122, 190, 70), (170, 226, 110), (72, 128, 40)),
    "tube_cluster": ((198, 74, 178), (232, 128, 214), (128, 36, 116)),
    "reef": ((236, 122, 136), (252, 172, 178), (170, 70, 90)),
}


# ---- helpers ---------------------------------------------------------------------------------------

def rgba(c, a=255):
    return np.array([c[0], c[1], c[2], a], dtype=np.uint8)


def canvas(w=N, h=N, fill=(0, 0, 0), a=255):
    img = np.zeros((h, w, 4), dtype=np.uint8)
    img[:, :] = rgba(fill, a)
    return img


def shade(c, f):
    return tuple(int(np.clip(v * f, 0, 255)) for v in c)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def speckle(img, rng, colours, amount, mask=None):
    """Vanilla-like noise: a share of the texels turned to one of a few nearby colours."""
    h, w = img.shape[:2]
    pick = rng.random((h, w)) < amount
    if mask is not None:
        pick &= mask
    which = rng.integers(0, len(colours), (h, w))
    for i, c in enumerate(colours):
        img[pick & (which == i), :3] = c
    return img


def grey(img):
    """A dead coral's bleached grey, keeping the light and dark of the picture."""
    out = img.copy()
    lum = img[..., :3].astype(np.float32) @ np.array([0.30, 0.55, 0.15])
    g = np.clip(70 + lum * 0.55, 0, 255)
    out[..., 0] = np.clip(g * 1.0, 0, 255)
    out[..., 1] = np.clip(g * 0.98, 0, 255)
    out[..., 2] = np.clip(g * 0.94, 0, 255)
    return out


def save(img, name):
    path = os.path.join(TEX, *name.split("/")) + ".png"
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tx.save_png(img, path)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def merge_tag(path, values):
    """Adds values to a block tag file (locked: other generators add to the same tags)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with filelock.locked(path):
        data = {"replace": False, "values": []}
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        for v in values:
            if v not in data["values"]:
                data["values"].append(v)
        write_json(path, data)


def tex(name):
    return f"blockland:block/decor/{name}"


def face(texture, uv=(0, 0, 16, 16), cull=None, tint=None, rotation=None):
    f = {"uv": list(uv), "texture": texture}
    if cull:
        f["cullface"] = cull
    if tint is not None:
        f["tintindex"] = tint
    if rotation:
        f["rotation"] = rotation
    return f


def box(frm, to, faces, rotation=None, shade_on=True):
    e = {"from": list(frm), "to": list(to), "faces": faces}
    if rotation:
        e["rotation"] = rotation
    if not shade_on:
        e["shade"] = False
    return e


def uv_for(frm, to, side):
    """Vanilla's automatic uv of a box face (in texture pixels)."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    return {
        "north": (16 - x1, 16 - y1, 16 - x0, 16 - y0), "south": (x0, 16 - y1, x1, 16 - y0),
        "west": (z0, 16 - y1, z1, 16 - y0), "east": (16 - z1, 16 - y1, 16 - z0, 16 - y0),
        "up": (x0, z0, x1, z1), "down": (x0, 16 - z1, x1, 16 - z0),
    }[side]


def all_faces(frm, to, texture, sides=("north", "south", "west", "east", "up", "down"), per=None):
    """Faces of a box with the vanilla automatic uvs; `per` maps a side to another texture."""
    per = per or {}
    return {s: face(per.get(s, texture), uv_for(frm, to, s)) for s in sides}


def model(name, data):
    # a face names its texture by a "#variable" of the model's textures map; a bare path there is an undefined
    # variable to the model loader (the missing-texture checkerboard), so every path a face uses becomes a variable
    textures = data.setdefault("textures", {})
    by_path = {path: key for key, path in textures.items() if not path.startswith("#")}
    for element in data.get("elements", ()):
        for f in element.get("faces", {}).values():
            path = f["texture"]
            if path.startswith("#"):
                continue
            if path not in by_path:
                key = base = path.rsplit("/", 1)[-1]
                n = 2
                while key in textures:
                    key = f"{base}{n}"
                    n += 1
                textures[key] = path
                by_path[path] = key
            f["texture"] = "#" + by_path[path]
    write_json(os.path.join(ASSETS, "models", "block", "decor", name + ".json"), data)
    return f"blockland:block/decor/{name}"


def item(block, model_ref, tints=None):
    m = {"type": "minecraft:model", "model": model_ref}
    if tints:
        m["tints"] = tints
    write_json(os.path.join(ASSETS, "items", block + ".json"), {"model": m})


def loot(block, pools=None):
    write_json(os.path.join(DATA, "blockland", "loot_table", "blocks", block + ".json"), {
        "type": "minecraft:block",
        "pools": pools or [simple_pool(block)],
        "random_sequence": f"blockland:blocks/{block}",
    })


def simple_pool(block, conditions=None):
    entry = {"type": "minecraft:item", "name": f"blockland:{block}"}
    pool = {"rolls": 1, "bonus_rolls": 0, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}
    if conditions:
        pool["conditions"] = conditions + pool["conditions"]
    return pool


def state_is(block, **props):
    return {"condition": "minecraft:block_state_property", "block": f"blockland:{block}", "properties": props}


def downscale(img, w, h):
    """A picture shrunk to w x h (box filter), centred on a transparent 16 px canvas."""
    from PIL import Image
    small = np.asarray(Image.fromarray(img).resize((w, h), Image.BOX))
    out = canvas(a=0)
    y0, x0 = (N - h) // 2, (N - w) // 2
    out[y0:y0 + h, x0:x0 + w] = small
    return out


def icon(img, block):
    """A flat item icon (textures/item/decor/<block>.png) and the item using it."""
    path = os.path.join(ASSETS, "textures", "item", "decor", block + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tx.save_png(img, path)
    write_json(os.path.join(ASSETS, "models", "item", "decor", block + ".json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/decor/{block}"}})
    item(block, f"blockland:item/decor/{block}")


# ---- vending machine ---------------------------------------------------------------------------------

BODY = (150, 30, 32)
BODY_LIGHT = (176, 44, 44)
BODY_DARK = (104, 18, 22)
TRIM = (220, 222, 226)


def vending_front(rng):
    """The 32 px front: the display window (left, top), the payment panel (right), the flap (bottom)."""
    img = canvas(32, 32, BODY)
    speckle(img, rng, [BODY_LIGHT, BODY_DARK], 0.12)
    img[0, :, :3] = BODY_LIGHT
    img[:, 0, :3] = BODY_LIGHT
    img[31, :, :3] = BODY_DARK
    img[:, 31, :3] = BODY_DARK
    # the display window: a lit back wall, four shelves of cans and bottles, a chrome frame
    x0, x1, y0, y1 = 3, 21, 3, 23
    img[y0 - 1:y1 + 1, x0 - 1:x1 + 1, :3] = TRIM
    img[y0:y1, x0:x1, :3] = (236, 240, 230)
    drinks = [(232, 40, 40), (40, 110, 236), (250, 210, 40), (60, 200, 90), (250, 132, 40), (240, 240, 250), (160, 70, 220)]
    for shelf in range(4):
        top = y0 + shelf * 5
        for slot in range(4):
            c = drinks[rng.integers(0, len(drinks))]
            cx = x0 + 1 + slot * 4 + 1
            img[top + 1:top + 4, cx - 1:cx + 2, :3] = c
            img[top + 1, cx - 1:cx + 2, :3] = shade(c, 1.15)          # the lid catching the light
            img[top + 3, cx + 1, :3] = shade(c, 0.75)                   # the side away from the light
        img[top + 4, x0:x1, :3] = (180, 186, 196)                       # the shelf
    # the payment panel: a sign strip, the green display, the keypad, the coin and note slots
    px0, px1 = 23, 30
    img[2:5, px0 - 1:px1 + 1, :3] = (250, 250, 246)
    img[3, px0:px1, :3] = (210, 40, 40)
    img[7:9, px0:px1, :3] = (30, 40, 34)
    img[7, px0 + 1:px1 - 1, :3] = (90, 255, 140)
    for r in range(4):
        for c in range(3):
            y, x = 11 + r * 2, px0 + c * 2 + 1
            img[y, x, :3] = (205, 208, 214)
            img[y, x + 1, :3] = BODY_DARK
    img[20, px0 + 2:px1 - 2, :3] = (20, 20, 22)                        # coin slot
    img[23:25, px0 + 1:px1 - 1, :3] = (36, 36, 40)                     # note acceptor
    img[23, px0 + 2:px1 - 2, :3] = (90, 255, 140)
    # the dispensing flap and its pocket
    img[25:30, 4:20, :3] = (34, 32, 36)
    img[25, 4:20, :3] = (120, 120, 128)
    img[26:28, 5:19, :3] = (60, 58, 64)
    return img


def vending():
    rng = np.random.default_rng(11)
    front = vending_front(rng)
    # quarters as seen from the front: left = the viewer's left
    quarters = {"upper_left": front[0:16, 0:16], "upper_right": front[0:16, 16:32],
                "lower_left": front[16:32, 0:16], "lower_right": front[16:32, 16:32]}
    for part, img in quarters.items():
        save(img.copy(), f"vending_machine/front_{part}")
    for half in ("upper", "lower"):
        side = canvas(fill=BODY)
        speckle(side, rng, [BODY_LIGHT, BODY_DARK], 0.12)
        side[:, 0, :3] = BODY_LIGHT
        side[:, 15, :3] = BODY_DARK
        if half == "upper":
            side[0, :, :3] = BODY_LIGHT
            side[5:7, 1:15, :3] = TRIM                                  # the white stripe round the cabinet
        else:
            side[14:16, :, :3] = (40, 36, 40)                           # the plinth
        save(side, f"vending_machine/side_{half}")
        back = canvas(fill=BODY_DARK)
        speckle(back, rng, [BODY, shade(BODY_DARK, 0.8)], 0.15)
        back[4, 2:14, :3] = shade(BODY_DARK, 0.7)
        back[11, 2:14, :3] = shade(BODY_DARK, 0.7)
        save(back, f"vending_machine/back_{half}")
    top = canvas(fill=BODY)
    speckle(top, rng, [BODY_LIGHT, BODY_DARK], 0.12)
    for z in range(3, 13, 3):
        top[z, 2:14, :3] = BODY_DARK                                   # vents
    save(top, "vending_machine/top")
    bottom = canvas(fill=(40, 36, 40))
    save(bottom, "vending_machine/bottom")

    for part in quarters:
        upper, right = part.startswith("upper"), part.endswith("right")
        frm, to = (0, 0, 1), (16, 16, 16)
        faces = {"north": face(tex(f"vending_machine/front_{part}")),
                 "south": face(tex(f"vending_machine/back_{'upper' if upper else 'lower'}"), cull="south")}
        # with the front north, the viewer's left is east
        outer = "west" if right else "east"
        faces[outer] = face(tex(f"vending_machine/side_{'upper' if upper else 'lower'}"), cull=outer)
        if upper:
            faces["up"] = face(tex("vending_machine/top"), uv_for(frm, to, "up"), cull="up")
        else:
            faces["down"] = face(tex("vending_machine/bottom"), uv_for(frm, to, "down"), cull="down")
        model(f"vending_machine_{part}", {"parent": "minecraft:block/block",
                                          "textures": {"particle": tex(f"vending_machine/side_{'upper' if upper else 'lower'}")},
                                          "elements": [box(frm, to, faces)]})
    variants = {}
    for facing, y in FACINGS.items():
        for part in quarters:
            v = {"model": f"blockland:block/decor/vending_machine_{part}"}
            if y:
                v["y"] = y
            variants[f"facing={facing},part={part}"] = v
    write_json(os.path.join(ASSETS, "blockstates", "vending_machine.json"), {"variants": variants})
    # a flat icon, as vanilla draws its doors: the whole front, shrunk
    icon(downscale(front, 13, 15), "vending_machine")
    loot("vending_machine", [simple_pool("vending_machine", [state_is("vending_machine", part="lower_left")])])


# ---- target -------------------------------------------------------------------------------------------

WOOD = (156, 112, 64)
WOOD_LIGHT = (184, 138, 84)
WOOD_DARK = (110, 76, 40)
STRAW = (214, 184, 92)


def target():
    rng = np.random.default_rng(12)
    wood = canvas(fill=WOOD)
    for y in range(N):
        if y % 4 == 0:
            wood[y, :, :3] = WOOD_DARK
    speckle(wood, rng, [WOOD_LIGHT, WOOD_DARK], 0.15)
    save(wood, "target_stand/wood")
    straw = canvas(fill=STRAW)
    speckle(straw, rng, [(236, 206, 112), (178, 146, 62), (198, 168, 78)], 0.45)
    for x in range(0, N, 3):
        straw[:, x, :3] = np.minimum(straw[:, x, :3], np.array([190, 160, 72], dtype=np.uint8))
    save(straw, "target_stand/straw")
    # the face: the archery rings printed on the straw board (gold, red, blue, black, white from the middle)
    face_img = straw.copy()
    rings = [(1.6, (250, 214, 40)), (3.2, (222, 40, 36)), (4.6, (44, 96, 210)), (5.8, (30, 30, 34)), (7.0, (240, 238, 232))]
    yy, xx = np.mgrid[0:N, 0:N] + 0.5
    d = np.hypot(xx - 8, yy - 8)
    for radius, colour in reversed(rings):
        face_img[d <= radius, :3] = colour
    face_img[(np.abs(d - 7.0) < 0.5), :3] = (40, 36, 34)
    save(face_img, "target_stand/face")

    w, s, f = tex("target_stand/wood"), tex("target_stand/straw"), tex("target_stand/face")
    # keep the boxes in step with TargetStandBlock.LOWER_NORTH / UPPER_NORTH (face north)
    lower = [box((1, 0, 6), (15, 2, 12), all_faces((1, 0, 6), (15, 2, 12), w)),
             box((7, 0, 8), (9, 16, 10), all_faces((7, 0, 8), (9, 16, 10), w)),
             box((3, 2, 8), (5, 11, 10), all_faces((3, 2, 8), (5, 11, 10), w), rotation={"origin": [4, 2, 9], "axis": "z", "angle": -22.5}),
             box((11, 2, 8), (13, 11, 10), all_faces((11, 2, 8), (13, 11, 10), w), rotation={"origin": [12, 2, 9], "axis": "z", "angle": 22.5})]
    upper = [box((1, 1, 6), (15, 15, 8), all_faces((1, 1, 6), (15, 15, 8), s, per={"north": f})),
             box((7, 0, 8), (9, 9, 10), all_faces((7, 0, 8), (9, 9, 10), w))]
    model("target_stand_lower", {"parent": "minecraft:block/block", "textures": {"particle": w}, "elements": lower})
    model("target_stand_upper", {"parent": "minecraft:block/block", "textures": {"particle": s}, "elements": upper})
    variants = {}
    for facing, y in FACINGS.items():
        for half in ("lower", "upper"):
            v = {"model": f"blockland:block/decor/target_stand_{half}"}
            if y:
                v["y"] = y
            variants[f"facing={facing},half={half}"] = v
    write_json(os.path.join(ASSETS, "blockstates", "target_stand.json"), {"variants": variants})
    stand = canvas(a=0)
    stand[9:16, 7:9] = rgba(WOOD_DARK)
    stand[15, 3:13] = rgba(WOOD)
    stand[11:15, 4] = rgba(WOOD)
    stand[11:15, 11] = rgba(WOOD)
    board = downscale(face_img, 10, 10)[3:13, 3:13]
    stand[0:10, 3:13] = board
    icon(stand, "target_stand")
    loot("target_stand", [simple_pool("target_stand", [state_is("target_stand", half="lower")])])


# ---- swim ring ----------------------------------------------------------------------------------------

def swim_ring():
    rng = np.random.default_rng(13)
    for name, base in (("red", (214, 40, 36)), ("white", (238, 236, 230))):
        img = canvas(fill=base)
        # rounded tube: lighter along the middle of every face, darker at its edges
        for y in range(N):
            f = 1.08 - 0.22 * abs(y - 7.5) / 7.5
            img[y, :, :3] = shade(base, f)
        speckle(img, rng, [shade(base, 0.94)], 0.08)
        save(img, f"swim_ring/{name}")
    red, white = tex("swim_ring/red"), tex("swim_ring/white")
    elements = []
    # white on the four sides, red at the corners (a lifebuoy's alternating segments)
    for frm, to in (((5, 0, 1), (11, 3, 4)), ((5, 0, 12), (11, 3, 15)), ((1, 0, 5), (4, 3, 11)), ((12, 0, 5), (15, 3, 11))):
        elements.append(box(frm, to, all_faces(frm, to, white)))
    for cx, cz in ((3.6, 3.6), (12.4, 3.6), (3.6, 12.4), (12.4, 12.4)):
        frm, to = (cx - 2.2, 0, cz - 2.2), (cx + 2.2, 3, cz + 2.2)
        elements.append(box(frm, to, all_faces(frm, to, red), rotation={"origin": [cx, 1.5, cz], "axis": "y", "angle": 45}))
    ref = model("swim_ring", {"parent": "minecraft:block/block", "textures": {"particle": red}, "elements": elements})
    write_json(os.path.join(ASSETS, "blockstates", "swim_ring.json"), {"variants": {"": {"model": ref}}})
    item("swim_ring", ref)
    loot("swim_ring")


# ---- layers ---------------------------------------------------------------------------------------------

def layers():
    for block, texture, tint in (("grass_layer", "minecraft:block/grass_block_top", 0), ("stone_layer", "minecraft:block/stone", None)):
        variants = {}
        for n in range(1, 9):
            h = n * 2
            frm, to = (0, 0, 0), (16, h, 16)
            faces = {}
            for side in ("north", "south", "west", "east", "up", "down"):
                cull = side if side != "up" or h == 16 else None
                faces[side] = face("#texture", uv_for(frm, to, side), cull=cull, tint=tint)
            name = f"{block}_height{h}"
            model(name, {"parent": "minecraft:block/block", "textures": {"texture": texture, "particle": texture},
                         "elements": [box(frm, to, faces)]})
            variants[f"layers={n}"] = {"model": f"blockland:block/decor/{name}"}
        write_json(os.path.join(ASSETS, "blockstates", f"{block}.json"), {"variants": variants})
        item(block, f"blockland:block/decor/{block}_height2",
             [{"type": "minecraft:grass", "temperature": 0.5, "downfall": 1.0}] if tint is not None else None)
        entries = [{"type": "minecraft:item", "name": f"blockland:{block}",
                    "conditions": [{"condition": "minecraft:block_state_property", "block": f"blockland:{block}",
                                    "properties": {"layers": str(n)}}],
                    "functions": [{"function": "minecraft:set_count", "count": n, "add": False}]} for n in range(1, 9)]
        loot(block, [{"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:alternatives", "children": entries}],
                      "conditions": [{"condition": "minecraft:survives_explosion"}]}])


# ---- corals ---------------------------------------------------------------------------------------------

def coral_cross(name, rng):
    """A cross plant's picture: staghorn = forked antler branches, sea_whip = long thin whips."""
    base, light, dark = CORAL_COLOURS[name]
    img = canvas(fill=(0, 0, 0), a=0)

    def put(x, y, c):
        if 0 <= x < N and 0 <= y < N:
            img[y, x] = rgba(c)

    if name == "staghorn":
        def branch(x, y, dx, length, depth):
            for i in range(length):
                yy = y - i
                xx = int(round(x + dx * i * 0.5))
                put(xx, yy, base)
                put(xx + 1, yy, dark)
                if i == length - 1:
                    put(xx, yy, light)
                    if depth < 2:
                        branch(xx, yy, -1, max(2, length - 2), depth + 1)
                        branch(xx, yy, 1, max(2, length - 2), depth + 1)
        branch(7, 15, 0, 6, 0)
        branch(4, 15, -0.4, 5, 1)
        branch(11, 15, 0.4, 5, 1)
    else:
        for x0, h, sway in ((3, 13, 0.6), (6, 15, -0.4), (9, 12, 0.5), (12, 14, -0.6), (8, 10, 0.2)):
            for i in range(h):
                xx = int(round(x0 + sway * np.sin(i / 3.0)))
                put(xx, 15 - i, base if i < h - 2 else light)
                if i % 3 == 1:
                    put(xx + 1, 15 - i, dark)
    return img


def coral_surface(name, rng, kind):
    """A 3D coral's skin: polyps (pillar), maze (brain), ridges (table), tubes (tube cluster)."""
    base, light, dark = CORAL_COLOURS[name]
    img = canvas(fill=base)
    yy, xx = np.mgrid[0:N, 0:N]
    if kind == "maze":
        maze = ((np.sin(xx * 1.3 + np.sin(yy * 0.9) * 2) + np.sin(yy * 1.1 + np.cos(xx * 0.7) * 2)) > 0.4)
        img[maze, :3] = dark
        img[~maze & (rng.random((N, N)) < 0.25), :3] = light
    elif kind == "ridges":
        d = np.hypot(xx - 7.5, yy - 7.5)
        img[(d.astype(int) % 2 == 0), :3] = light
        img[(np.abs(((np.arctan2(yy - 7.5, xx - 7.5) * 6 / np.pi) % 2) - 1) < 0.15), :3] = dark
    elif kind == "polyps":
        speckle(img, rng, [light, dark], 0.35)
        for _ in range(14):
            x, y = rng.integers(0, N, 2)
            img[y, x, :3] = dark
            if x + 1 < N:
                img[y, x + 1, :3] = light
    elif kind == "rings":
        for y in range(0, N, 3):
            img[y, :, :3] = dark
        speckle(img, rng, [light], 0.15)
    elif kind == "hole":
        d = np.hypot(xx - 7.5, yy - 7.5)
        img[:, :, :3] = base
        img[d < 5.5, :3] = shade(dark, 0.6)
        img[(d >= 5.5) & (d < 6.6), :3] = light
    else:
        speckle(img, rng, [light, dark], 0.3)
    return img


def coral_models(name):
    """Element shapes (from, to, which skin on the sides / on top) of the 3D corals."""
    if name == "table":
        return [((7, 0, 7), (9, 6, 9), "side", "side"), ((1, 6, 1), (15, 8, 15), "side", "top"), ((4, 8, 4), (12, 9, 12), "side", "top")]
    if name == "pillar":
        return [((3, 0, 4), (7, 13, 8), "side", "top"), ((9, 0, 7), (12, 9, 10), "side", "top"), ((6, 0, 10), (9, 7, 13), "side", "top")]
    if name == "brain_dome":
        return [((1, 0, 1), (15, 4, 15), "side", "top"), ((2, 4, 2), (14, 7, 14), "side", "top"), ((4, 7, 4), (12, 9, 12), "side", "top")]
    return [((3, 0, 3), (6, 11, 6), "side", "top"), ((8, 0, 2), (11, 8, 5), "side", "top"), ((10, 0, 8), (13, 12, 11), "side", "top"),
            ((4, 0, 9), (7, 7, 12), "side", "top")]


SKINS = {"table": ("rings", "ridges"), "pillar": ("polyps", "polyps"), "brain_dome": ("maze", "maze"), "tube_cluster": ("rings", "hole")}


def corals():
    rng = np.random.default_rng(14)
    for name in CORAL_PLANTS:
        img = coral_cross(name, rng)
        save(img, f"coral/{name}")
        save(grey(img), f"coral/dead_{name}")
        for prefix in ("", "dead_"):
            block = f"{prefix}{name}_coral"
            ref = model(block, {"parent": "minecraft:block/cross", "render_type": "minecraft:cutout",
                                "textures": {"cross": tex(f"coral/{prefix}{name}")}})
            write_json(os.path.join(ASSETS, "blockstates", f"{block}.json"), {"variants": {"": {"model": ref}}})
            write_json(os.path.join(ASSETS, "models", "item", "decor", f"{block}.json"),
                       {"parent": "minecraft:item/generated", "textures": {"layer0": tex(f"coral/{prefix}{name}")}})
            item(block, f"blockland:item/decor/{block}")
            loot(block)
    for name in CORAL_SHAPES:
        side_kind, top_kind = SKINS[name]
        side = coral_surface(name, rng, side_kind)
        top = coral_surface(name, rng, top_kind)
        for prefix, conv in (("", lambda i: i), ("dead_", grey)):
            save(conv(side), f"coral/{prefix}{name}_side")
            save(conv(top), f"coral/{prefix}{name}_top")
            block = f"{prefix}{name}_coral"
            st, tp = tex(f"coral/{prefix}{name}_side"), tex(f"coral/{prefix}{name}_top")
            elements = []
            for frm, to, side_skin, top_skin in coral_models(name):
                per = {"up": tp if top_skin == "top" else st, "down": st}
                elements.append(box(frm, to, all_faces(frm, to, st, per=per)))
            ref = model(block, {"parent": "minecraft:block/block", "textures": {"particle": st}, "elements": elements})
            write_json(os.path.join(ASSETS, "blockstates", f"{block}.json"), {"variants": {"": {"model": ref}}})
            item(block, ref)
            loot(block)
    # reef coral: a porous core and arms, joined like a chorus plant
    base, light, dark = CORAL_COLOURS["reef"]
    skin = canvas(fill=base)
    speckle(skin, rng, [light, dark], 0.3)
    for _ in range(18):
        x, y = rng.integers(0, N, 2)
        skin[y, x, :3] = shade(dark, 0.7)                               # pores
    for prefix, conv in (("", lambda i: i), ("dead_", grey)):
        save(conv(skin), f"coral/{prefix}reef")
        t = tex(f"coral/{prefix}reef")
        block = f"{prefix}reef_coral"
        core = model(f"{block}_core", {"parent": "minecraft:block/block", "textures": {"particle": t}, "elements": [
            box((4, 4, 4), (12, 12, 12), all_faces((4, 4, 4), (12, 12, 12), t)),
            box((6, 11, 5), (9, 13, 8), all_faces((6, 11, 5), (9, 13, 8), t)),                 # knobs
            box((9, 5, 10), (11, 8, 13), all_faces((9, 5, 10), (11, 8, 13), t))]})
        arm = model(f"{block}_arm", {"parent": "minecraft:block/block", "textures": {"particle": t}, "elements": [
            box((5, 5, 0), (11, 11, 4), all_faces((5, 5, 0), (11, 11, 4), t, sides=("north", "west", "east", "up", "down")))]})
        parts = [{"apply": {"model": core}}]
        for side, rot in (("north", {}), ("east", {"y": 90}), ("south", {"y": 180}), ("west", {"y": 270}),
                          ("up", {"x": 270}), ("down", {"x": 90})):
            parts.append({"when": {side: "true"}, "apply": dict({"model": arm, "uvlock": True}, **rot)})
        write_json(os.path.join(ASSETS, "blockstates", f"{block}.json"), {"multipart": parts})
        item(block, core)
        loot(block)


def tags():
    tag = os.path.join(DATA, "minecraft", "tags", "block", "mineable")
    merge_tag(os.path.join(tag, "pickaxe.json"), ["blockland:vending_machine", "blockland:stone_layer",
                                                  "blockland:reef_coral", "blockland:dead_reef_coral"])
    merge_tag(os.path.join(tag, "axe.json"), ["blockland:target_stand"])
    merge_tag(os.path.join(tag, "shovel.json"), ["blockland:grass_layer"])


def main():
    vending()
    target()
    swim_ring()
    layers()
    corals()
    tags()
    import gen_decor_props
    gen_decor_props.main()
    print("decor: vending machine, target stand, swim ring, 2 layers x 8, "
          f"{len(CORAL_PLANTS) + len(CORAL_SHAPES)} corals x 2, reef coral x 2")


if __name__ == "__main__":
    main()
