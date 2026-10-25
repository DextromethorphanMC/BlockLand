"""The creeper zombie's skin (苦力怕僵尸, infection monster): textures/entity/creeper_zombie.png, 64x64 in the vanilla
zombie layout (HumanoidModel.createMesh; the left limbs mirror the right ones; the hat layer and the bottom half stay
empty). Drawn with gen_monster_textures' painter: per box face a char map over seeded noise, so it is deterministic.

A creeper's speckled green hide on a zombie's body: the creeper face (black eyes and frown, embers deep in the eyes),
the hide charred and split over the chest where the gunpowder inside glows through the cracks, a leather bandolier of
red powder charges across the chest, the rags of trousers, burnt hands and feet."""
import numpy as np

import gen_monster_textures as gm
from gen_monster_textures import BASE_BOXES, Skin, _cat, _hex

CREEPER = _hex("0c3a0e", "16571a", "247a24", "3a9a33", "62bd52", "a4e290")     # speckled hide, dark .. lit
CHAR = _hex("15130f", "231f19", "332c22", "463b2c")                          # burnt hide
EMBER = _hex("5a1c06", "b54a10", "f08a1e", "ffd27a")                          # glow in the cracks
STRAP = _hex("2a1a10", "3d2717", "553620")
CHARGE = _hex("6a160f", "9a251a", "c43a26")

LEGEND = {
    "a": ("n", CREEPER, 3.0, 1.35, "flat"), "A": ("n", CREEPER, 1.6, 0.8, "flat"), "h": ("n", CREEPER, 4.2, 0.6, "flat"),
    "b": ("n", CHAR, 1.6, 0.9, "skin"), "B": ("n", CHAR, 0.6, 0.5, "skin"),
    "x": ("c", _hex("070806")[0]), "X": ("c", _hex("1b1d19")[0]),
    "e": ("c", EMBER[0]), "o": ("c", EMBER[2]), "O": ("c", EMBER[3]), "E": ("c", EMBER[1]),
    "l": ("n", STRAP, 1.2, 0.6, "cloth"), "S": ("c", _hex("8a7a5a")[0]),                  # strap, its buckle
    "r": ("n", CHARGE, 1.3, 0.5, "flat"), "R": ("c", CHARGE[2]), "w": ("c", _hex("c8bc9a")[0]),   # charges, fuse wick
    "p": ("n", gm.PANTS, 2.3, 0.8, "cloth"), "P": ("n", gm.PANTS, 0.8, 0.45, "cloth"),
    "f": ("n", gm.FLESH, 2.0, 0.6, "flat"), "F": ("n", gm.FLESH, 0.8, 0.4, "flat"),
}

# Orientation per face (gen_monster_textures.box_faces): top/bottom x = entity right -> left, y = back -> front;
# right x = back -> front; front x = entity right -> left; left x = front -> back; back x = entity left -> right.
HEAD = {
    "top": ["aaaaaaaa",
            "aabbaaaa",
            "abBbaaha",
            "aabeoaaa",
            "aaabaaaa",
            "aaaaaaaa",
            "ahaaaaba",
            "aaaaaaaa"],
    "bottom": ["AAAAAAAA"] * 8,
    "right": ["aaaaaaaa",
              "aaahaaaa",
              "aaaaaaaa",
              "aabbaaaa",
              "abBeaaaa",
              "aabbaaaa",
              "aaaaaaha",
              "AaaaaaaA"],
    "front": ["aaaaahaa",            # the creeper's face: black eyes with embers deep in them, the frown
              "aaaaaaaa",
              "axxaaxxa",
              "axeaaexa",
              "aaaxxaaa",
              "aaxxxxaa",
              "aaxXXxaa",
              "aaxaaxaa"],
    "left": ["aaaaaaaa",
             "aaaaahaa",
             "aaaaaaaa",
             "aaaaaaaa",
             "aaaaaaaa",
             "aaahaaaa",
             "aaaaaaaa",
             "AaaaaaaA"],
    "back": ["aaaaaaaa",
             "aaaaaaaa",
             "aaahaaaa",
             "aaaaaaaa",
             "aaaaaabb",
             "aaaaaaba",
             "ahaaaaaa",
             "AaaaaaaA"],
}
HAT = {face: ["........"] * 8 for face in ("top", "bottom", "right", "front", "left", "back")}
BODY_FACES = {"top": ["aalaaaaa",
                      "aalaaaaa",
                      "aaalaaaa",
                      "aaalaaaa"],
              "bottom": ["AAAAAAAA"] * 4}
BODY_STRIP = _cat(
    ["aaaa",          # right side (back -> front): the strap over the shoulder
     "aaal",
     "aaal",
     "aaaa",
     "ahaa",
     "aaaa",
     "aaaa",
     "aaaa",
     "aaha",
     "aaaa",
     "Aaaa",
     "AAAA"],
    ["laaaaaaa",      # front: the bandolier from the right shoulder down to the left hip with three charges, the hide
     "lraaaaaa",      # split and charred over the chest, gunpowder glowing in the cracks
     "lrlaabaa",
     "awlrbobb",
     "aaRlrOob",
     "aabwlrba",
     "abboaSra",
     "aboOaala",
     "abbaaaal",
     "aaaaaaaa",
     "aaaafaaa",
     "AAAAAAAA"],
    ["aaaa",          # left side (front -> back)
     "aaaa",
     "aaaa",
     "aaha",
     "aaaa",
     "aaaa",
     "laaa",
     "aaaa",
     "aaaa",
     "aaha",
     "aaaa",
     "AAAA"],
    ["aaaaalaa",      # back: the strap's other end, a burnt patch
     "aaaaalaa",
     "aaaalaaa",
     "aaahlaaa",
     "aaalaaaa",
     "aablaaaa",
     "abBbaaaa",
     "aalbaaaa",
     "alaaaaaa",
     "laaaahaa",
     "aaaaaaaa",
     "AAAAAAAA"])
ARM_FACES = {"top": ["aaaa", "aaha", "aaaa", "aaaa"],
             "bottom": ["bBbb", "bbBb", "Bbbb", "bbbB"]}          # burnt fingers
ARM_STRIP = _cat(
    ["aaaa", "aaaa", "ahaa", "aaaa", "aaaa", "aaba", "aaaa", "aaaa", "aaaa", "abba", "bbBb", "bBbb"],
    ["aaaa", "aaaa", "aaaa", "aaha", "aaaa", "abea", "aaba", "aaaa", "aaaa", "abaa", "bbbB", "Bbbb"],
    ["aaaa", "ahaa", "aaaa", "aaaa", "aaaa", "aaaa", "aaha", "aaaa", "aaaa", "aaab", "bBbb", "bbBb"],
    ["aaaa", "aaaa", "aaaa", "aaaa", "ahaa", "aaaa", "aaaa", "aaaa", "aaaa", "baaa", "bbbB", "bBbb"])
LEG_FACES = {"top": ["pppp", "pPpp", "ppPp", "pppp"],
             "bottom": ["AAAA", "AAAA", "AAAA", "AAAA"]}
LEG_STRIP = _cat(                                                  # rags of trousers to the knee, torn edges
    ["pppp", "pPpp", "pppp", "ppPp", "pppp", "pppa", "ppaa", "aaaa", "ahaa", "aaaa", "aaaa", "AAAA"],
    ["pppp", "ppPp", "pPpp", "pppp", "pppp", "apPp", "aapa", "aaaa", "aaaa", "aaha", "aaaa", "AAAA"],
    ["pppp", "pppp", "Pppp", "pppp", "ppPp", "pppp", "apaa", "aaaa", "aaaa", "aaaa", "haaa", "AAAA"],
    ["pPpp", "pppp", "pppp", "pppP", "pppp", "ppaa", "paaa", "aaaa", "aaha", "aaaa", "aaaa", "AAAA"])


def creeper_zombie_skin():
    s = Skin(seed=67)
    s.paint_box(*BASE_BOXES["head"], HEAD, legend=LEGEND, light={"front": (0.25, -0.2)})
    s.paint_box(*BASE_BOXES["hat"], HAT, legend=LEGEND)
    s.paint_box(*BASE_BOXES["body"], BODY_FACES, BODY_STRIP, legend=LEGEND)
    s.paint_box(*BASE_BOXES["right_arm"], ARM_FACES, ARM_STRIP, legend=LEGEND)
    s.paint_box(*BASE_BOXES["right_leg"], LEG_FACES, LEG_STRIP, legend=LEGEND)
    return s.image()


def coverage(img):
    """Every face of the zombie model's boxes fully opaque (the hat layer stays empty)."""
    out = {}
    for name, (uv, size) in BASE_BOXES.items():
        for face, (x, y, w, h) in gm.box_faces(uv, size).items():
            out[name, face] = float(np.mean(img[y:y + h, x:x + w, 3] >= 0.5))
    return out


def build(path):
    import textures as tx
    img = creeper_zombie_skin()
    cov = coverage(img)
    holes = [k for k, v in cov.items() if k[0] != "hat" and v < 1.0]
    assert not holes, f"creeper zombie skin: transparent texels on {holes}"
    tx.save_png(img, path("textures", "entity", "creeper_zombie.png"))
    print("creeper zombie: textures/entity/creeper_zombie.png")
