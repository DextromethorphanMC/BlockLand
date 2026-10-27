"""Offline check of the decor blocks (decor/DecorContent + tools/assets/gen_decor.py).

python -I tools/assets/check_decor.py

* every block id registered in DecorContent has a block state, an item definition, a loot table and both lang keys;
* every model a block state or item names exists; every element is in range with a legal rotation; every texture a
  model names exists (blockland textures as files, minecraft ones from a short allow list);
* the target stand's model boxes are the collision boxes of TargetStandBlock (LOWER_NORTH / UPPER_NORTH), the vending
  machine's cabinet is the block's shape, the layer heights are the LayerBlock shapes;
* the mineable tags hold the blocks that need a tool;
* the block states cover every state: facing x part (vending), facing x half (target), layers 1..8, the reef coral's
  multipart has a core and an arm per side."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
RES = os.path.join(REPO, "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "blockland")
DATA = os.path.join(RES, "data")
JAVA = os.path.join(REPO, "mod", "src", "main", "java", "com", "blockland", "decor")
VANILLA_TEXTURES = {"minecraft:block/grass_block_top", "minecraft:block/stone"}
VANILLA_PARENTS = {"minecraft:block/block", "minecraft:block/cross", "minecraft:item/generated"}

problems = []


def problem(msg):
    problems.append(msg)


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def java(name):
    with open(os.path.join(JAVA, name + ".java"), encoding="utf-8") as f:
        return f.read()


def block_ids():
    src = java("DecorContent")
    ids = re.findall(r'block\("([a-z_]+)"', src) + re.findall(r'BLOCKS\.register\("([a-z_]+)"', src)
    for name in re.findall(r'new Coral\("([a-z_]+)"', src):
        ids += [f"{name}_coral", f"dead_{name}_coral"]
    return sorted(set(ids))


def model_path(ref):
    ns, path = ref.split(":", 1)
    return os.path.join(RES, "assets", ns, "models", path + ".json")


def texture_ok(ref, textures):
    if ref.startswith("#"):
        key = ref[1:]
        if key not in textures:
            return False
        return texture_ok(textures[key], textures)
    if ref in VANILLA_TEXTURES:
        return True
    if not ref.startswith("blockland:"):
        return False
    return os.path.exists(os.path.join(ASSETS, "textures", ref.split(":", 1)[1] + ".png"))


def check_model(ref, seen):
    if ref in seen or ref in VANILLA_PARENTS:
        return seen.get(ref)
    path = model_path(ref)
    if not os.path.exists(path):
        problem(f"missing model {ref}")
        return None
    m = load(path)
    seen[ref] = m
    if m.get("parent") and m["parent"] not in VANILLA_PARENTS:
        check_model(m["parent"], seen)
    textures = m.get("textures", {})
    for key, value in textures.items():
        if not texture_ok(value, textures):
            problem(f"{ref}: texture {key} = {value} does not exist")
    for e in m.get("elements", []):
        for v in e["from"] + e["to"]:
            if not -16 <= v <= 32:
                problem(f"{ref}: element coordinate {v} out of range")
        rot = e.get("rotation")
        if rot and rot["angle"] not in (0, 22.5, -22.5, 45, -45):
            problem(f"{ref}: illegal rotation {rot['angle']}")
        for side, f in e["faces"].items():
            # the model loader reads a face's texture as a variable name: a bare path is an undefined variable
            if not f["texture"].startswith("#"):
                problem(f"{ref}: face {side} texture {f['texture']} is not a #variable")
            elif not texture_ok(f["texture"], textures):
                problem(f"{ref}: face {side} texture {f['texture']} not defined")
    return m


def boxes(ref):
    m = load(model_path(ref))
    return sorted(tuple(e["from"] + e["to"]) for e in m["elements"])


def java_boxes(src, name):
    found = re.search(name + r"\s*=\s*\{(.*?)\};", src, re.S)
    rows = re.findall(r"\{([^{}]+)\}", found.group(1))
    return sorted(tuple(float(v) for v in r.split(",")) for r in rows)


def main():
    ids = block_ids()
    lang = {code: load(os.path.join(ASSETS, "lang", f"{code}.json")) for code in ("zh_cn", "en_us")}
    seen = {}
    for block in ids:
        bs_path = os.path.join(ASSETS, "blockstates", block + ".json")
        if not os.path.exists(bs_path):
            problem(f"{block}: no block state")
            continue
        bs = load(bs_path)
        refs = [v["model"] for v in bs.get("variants", {}).values()] + [p["apply"]["model"] for p in bs.get("multipart", [])]
        for ref in refs:
            check_model(ref, seen)
        item_path = os.path.join(ASSETS, "items", block + ".json")
        if not os.path.exists(item_path):
            problem(f"{block}: no item definition")
        else:
            check_model(load(item_path)["model"]["model"], seen)
        if not os.path.exists(os.path.join(DATA, "blockland", "loot_table", "blocks", block + ".json")):
            problem(f"{block}: no loot table")
        for code, data in lang.items():
            if f"block.blockland.{block}" not in data:
                problem(f"{block}: no {code} name")
    for code, data in lang.items():
        if "itemGroup.blockland.decor" not in data:
            problem(f"no {code} creative tab name")

    # state coverage
    vending = load(os.path.join(ASSETS, "blockstates", "vending_machine.json"))["variants"]
    for f in ("north", "east", "south", "west"):
        for part in ("lower_left", "lower_right", "upper_left", "upper_right"):
            if f"facing={f},part={part}" not in vending:
                problem(f"vending_machine: no variant {f} {part}")
        for half in ("lower", "upper"):
            if f"facing={f},half={half}" not in load(os.path.join(ASSETS, "blockstates", "target_stand.json"))["variants"]:
                problem(f"target_stand: no variant {f} {half}")
    for layer in ("grass_layer", "stone_layer"):
        variants = load(os.path.join(ASSETS, "blockstates", layer + ".json"))["variants"]
        for n in range(1, 9):
            v = variants.get(f"layers={n}")
            if not v:
                problem(f"{layer}: no variant layers={n}")
                continue
            (frm, to), = [(e["from"], e["to"]) for e in load(model_path(v["model"]))["elements"]]
            if to[1] != n * 2 or frm != [0, 0, 0] or to[0] != 16 or to[2] != 16:
                problem(f"{layer}: layers={n} model is not 16 x {n * 2} x 16")
    for reef in ("reef_coral", "dead_reef_coral"):
        parts = load(os.path.join(ASSETS, "blockstates", reef + ".json"))["multipart"]
        sides = {next(iter(p["when"])) for p in parts if "when" in p}
        if sides != {"north", "east", "south", "west", "up", "down"} or not any("when" not in p for p in parts):
            problem(f"{reef}: multipart needs a core and an arm per side, has {sorted(sides)}")

    # shapes against models
    target = java("TargetStandBlock")
    for half, name in (("lower", "LOWER_NORTH"), ("upper", "UPPER_NORTH")):
        if boxes(f"blockland:block/decor/target_stand_{half}") != java_boxes(target, name):
            problem(f"target_stand {half}: model boxes differ from TargetStandBlock.{name}")
    vend = java("VendingMachineBlock")
    if "default -> Block.box(0, 0, 1, 16, 16, 16)" not in vend:
        problem("vending_machine: the north shape is not the cabinet (0,0,1)-(16,16,16)")
    for part in ("lower_left", "lower_right", "upper_left", "upper_right"):
        if boxes(f"blockland:block/decor/vending_machine_{part}") != [(0, 0, 1, 16, 16, 16)]:
            problem(f"vending_machine {part}: model is not the cabinet box")

    # tags
    tags = os.path.join(DATA, "minecraft", "tags", "block", "mineable")
    for tag, blocks in (("pickaxe", ["vending_machine", "stone_layer", "reef_coral", "dead_reef_coral"]),
                        ("axe", ["target_stand"]), ("shovel", ["grass_layer"])):
        values = load(os.path.join(tags, tag + ".json"))["values"]
        for b in blocks:
            if f"blockland:{b}" not in values:
                problem(f"mineable/{tag} lacks blockland:{b}")

    # emission entry
    mats = load(os.path.join(ASSETS, "graphics", "block_materials.json"))["materials"]
    lit = {t for m in mats for t in m["textures"]}
    for part in ("upper_left", "upper_right", "lower_left", "lower_right"):
        if f"blockland:block/decor/vending_machine/front_{part}" not in lit:
            problem(f"vending front {part} has no block material (emission)")

    print(f"decor: {len(ids)} blocks, {len(seen)} models")
    if problems:
        print(f"FAILED ({len(problems)} problems):")
        for p in problems:
            print("  - " + p)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
