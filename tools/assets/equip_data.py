"""What the equipment assets write into equipment.json (VIEWMODEL_SPEC sections 21 and 36), by `python -I
equip_data.py` and by the `equipment` build step (textual edits under the file lock: the rest of the file - the
gameplay numbers of the runtime - keeps its formatting):
* the skins: camo (迷彩) re-texture the paintable regions with the camo enamels; the shader skin (特效皮肤) of each item
  runs one of the shared effects;
* the timing the clips are built with (equip_clips.timing: drawTime, pullTime, throw.releaseAt / recover, useTime,
  injectAt)."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import filelock                        # noqa: E402
import pbr_textures as pt              # noqa: E402

EQUIPMENT_JSON = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "equipment",
                              "equipment.json")


def layer(name, **kw):
    out = {"pbr": f"textures/pbr/{name}", "uvScale": pt.uv_scale(name)}
    out.update(kw)
    return out


def grenade_skins():
    return {
        "default": {"name": "标准"},
        "woodland": {"name": "林地迷彩", "regions": {"body": layer("skin/camo/woodland")}},
        "desert": {"name": "沙漠迷彩", "regions": {"body": layer("skin/camo/desert"),
                                               "lever": layer("equipment/zinc", tint=[1.0, 0.92, 0.78])}},
        "arctic": {"name": "雪地迷彩", "regions": {"body": layer("skin/camo/arctic"),
                                               "label": layer("skin/camo/arctic", tint=[0.85, 0.88, 0.92])}},
        "aurora": {"name": "极光", "rarity": "legendary", "regions": {
            "body": layer("skin/aurora/body", effect="iridescent", params=[0.85, 1.0, 0.04, 6.0]),
            "label": layer("skin/aurora/accent", effect="flow", effectColor="#5CF2FF", params=[0.35, 0.06, 0.25, 1.0]),
            "*": layer("skin/aurora/metal", tint=[0.92, 0.96, 1.0])}},
    }


SKINS = {
    "frag_grenade": grenade_skins(),
    "flashbang": grenade_skins(),
    "smoke_grenade": grenade_skins(),
    "molotov": {
        "default": {"name": "标准"},
        "camo_rag": {"name": "林地布条", "regions": {"rag": layer("skin/camo/woodland"),
                                                 "glass": {"tint": [0.55, 1.45, 0.75]}}},
        "desert_rag": {"name": "沙漠布条", "regions": {"rag": layer("skin/camo/desert"),
                                                   "glass": {"tint": [1.6, 1.5, 1.3]}}},
        "hellfire": {"name": "地狱火", "rarity": "legendary", "regions": {
            "rag": layer("skin/magma/rock", effect="flow", effectColor="#FF6A1A", params=[0.12, 0.12, 0.6, 0.7]),
            "glass": {"tint": [1.5, 0.35, 0.30]}, "liquid": {"tint": [1.3, 0.55, 0.30]}}},
    },
    "throwing_knife": {
        "default": {"name": "标准"},
        "woodland": {"name": "林地迷彩", "regions": {"blade": layer("skin/camo/woodland"),
                                                 "grip": layer("equipment/paracord", tint=[1.6, 2.2, 1.2])}},
        "tiger": {"name": "虎纹迷彩", "regions": {"blade": layer("skin/camo/tiger")}},
        "galaxy": {"name": "星河", "rarity": "legendary", "regions": {
            "blade": layer("skin/galaxy/blade", effect="galaxy", effectColor="#DDE6FF", params=[0.012, 1.0, 0.6, 0.8]),
            "grip": layer("skin/galaxy/handle")}},
    },
    "adrenaline": {
        "default": {"name": "标准"},
        "woodland": {"name": "林地迷彩", "regions": {"barrel": layer("skin/camo/woodland")}},
        "arctic": {"name": "雪地迷彩", "regions": {"barrel": layer("skin/camo/arctic"),
                                               "cap": layer("equipment/pen_blue", tint=[0.6, 0.6, 0.6])}},
        "neon": {"name": "霓虹回路", "rarity": "legendary", "regions": {
            "*": layer("skin/neon/base", effect="circuit", effectColor="#38F0FF", params=[0.25, 40.0, 0.8, 0.05])}},
    },
    "flashlight": {
        "default": {"name": "标准"},
        "desert": {"name": "沙漠迷彩", "regions": {"body": layer("skin/camo/desert")}},
        "arctic": {"name": "雪地迷彩", "regions": {"body": layer("skin/camo/arctic")}},
        "aurora": {"name": "极光", "rarity": "legendary", "regions": {
            "body": layer("skin/aurora/body", effect="iridescent", params=[0.85, 1.0, 0.04, 6.0]),
            "*": layer("skin/aurora/metal", tint=[0.92, 0.96, 1.0])}},
    },
}


def _block(skins, indent):
    text = json.dumps(skins, ensure_ascii=False, indent=2)
    return text.replace("\n", "\n" + " " * indent)


def write_skins(path=EQUIPMENT_JSON):
    with filelock.locked(path):
        s = open(path, encoding="utf-8").read()
        for key, skins in SKINS.items():
            start = re.search(r'\n(\s*)"' + key + r'": \{', s)
            assert start, key
            m = re.compile(r'\n(\s*)"skins": ').search(s, start.end())
            # the value: a balanced {...} after "skins":
            i = s.index("{", m.end() - 1)
            depth, j = 0, i
            while True:
                c = s[j]
                depth += c == "{"
                depth -= c == "}"
                j += 1
                if depth == 0:
                    break
            s = s[:i] + _block(skins, len(m.group(1))) + s[j:]
        json.loads(s)
        open(path, "w", encoding="utf-8", newline="\n").write(s)


def _obj_end(s, i):
    """Index just past the {...} object that opens at s[i]."""
    depth, j = 0, i
    while True:
        depth += s[j] == "{"
        depth -= s[j] == "}"
        j += 1
        if depth == 0:
            return j


def write_timing(path=EQUIPMENT_JSON):
    import equip_clips
    with filelock.locked(path):
        s = open(path, encoding="utf-8").read()
        for key in SKINS:
            for field, value in equip_clips.timing(key).items():
                m = re.search(r'\n\s*"' + key + r'": \{', s)
                a = m.end() - 1
                b = _obj_end(s, a)
                if field.startswith("throw."):            # inside the item's "throw" object
                    t = re.search(r'"throw": \{', s[a:b])
                    a = a + t.end() - 1
                    b = _obj_end(s, a)
                name = field.split(".")[-1]
                new, n = re.subn(r'("' + name + r'": )[-0-9.eE]+', lambda mm: mm.group(1) + repr(float(value)),
                                 s[a:b], count=1)
                assert n == 1, (key, field)
                s = s[:a] + new + s[b:]
        json.loads(s)
        open(path, "w", encoding="utf-8", newline="\n").write(s)


if __name__ == "__main__":
    write_skins()
    write_timing()
    print("equipment skins and timing written:", {k: list(v) for k, v in SKINS.items()})
