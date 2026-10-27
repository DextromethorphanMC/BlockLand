"""The blind one's skin (瞎子, infection monster): textures/entity/blind.png, 64x64 in the vanilla zombie layout
(HumanoidModel.createMesh; the left limbs mirror the right ones; the bottom half stays empty). Drawn with
gen_monster_textures' painter: per box face a char map over seeded noise, so it is deterministic.

A gaunt, hairless, grey-white body that has not seen light in a long time, blue veins under the skin: a face without
eyes — two sunken, sewn-shut hollows — and a slack open mouth, a strip of dirty linen tied round the head over them
(the hat layer: only its band is painted, the rest stays transparent), a blood stain soaked through where the eyes
were, the knot at the back. Rags of a dark tunic over the ribs, torn trousers, long bare arms with dark nails."""
import numpy as np

import gen_monster_textures as gm
from gen_monster_textures import BASE_BOXES, Skin, _cat, _hex

PALE = _hex("4e4b49", "67635f", "817c76", "9b958d", "b6afa5", "d0c9be")       # bloodless skin, dark .. lit
VEIN = _hex("4c5a6c")[0]
SOCKET = _hex("1c1716", "2e2522", "433632")                                   # sunken hollows, the stitching
STITCH = _hex("6d5f4c")[0]
RAG = _hex("3d342a", "554839", "6d5d49", "86745c", "9e8a70")                  # dirty linen
BLOOD = _hex("3e0d0e", "5c1716")
TUNIC = _hex("19191b", "232426", "2e2f32", "3b3c40")
NAIL = _hex("2e2a28")[0]

LEGEND = {
    "a": ("n", PALE, 3.0, 1.0, "skin"), "A": ("n", PALE, 1.7, 0.7, "skin"), "h": ("n", PALE, 4.3, 0.5, "skin"),
    "v": ("c", VEIN), "k": ("c", SOCKET[0]), "K": ("c", SOCKET[1]), "z": ("c", STITCH),   # not "s": the painter shades "s" as slime
    "x": ("c", gm.MOUTH[0]), "m": ("c", gm.MOUTH[1]), "t": ("c", gm.TOOTH[0]),
    "r": ("n", RAG, 2.2, 0.8, "cloth"), "R": ("n", RAG, 0.9, 0.5, "cloth"), "b": ("c", BLOOD[1]), "B": ("c", BLOOD[0]),
    "c": ("n", TUNIC, 2.0, 0.8, "cloth"), "C": ("n", TUNIC, 0.7, 0.5, "cloth"),
    "p": ("n", gm.PANTS, 2.3, 0.8, "cloth"), "P": ("n", gm.PANTS, 0.8, 0.45, "cloth"),
    "f": ("n", gm.FLESH, 1.6, 0.6, "flat"), "n": ("c", NAIL),
}

# Orientation per face (gen_monster_textures.box_faces): top/bottom x = entity right -> left, y = back -> front;
# right x = back -> front; front x = entity right -> left; left x = front -> back; back x = entity left -> right.
HEAD = {
    "top": ["aaAaaaaa",
            "aaaaahaa",
            "ahaaaaaa",
            "aaaaAaaa",
            "aaaaaaaa",
            "aaahaaaa",
            "aaaaaaha",
            "aaaaaaaa"],
    "bottom": ["AAAAAAAA"] * 8,
    "right": ["aaaaaaaa",
              "aaaavaaa",
              "aaaaaaaa",
              "aaaaaaah",
              "aaaaaaaa",
              "aavaaaaa",
              "aaaaaaaA",
              "AaaaaaAA"],
    "front": ["aaahaaaa",            # no eyes: two sunken, sewn-shut hollows (under the rag), the nose, a slack mouth
              "aaaaaaaa",
              "aKzkKzka",
              "akKKkKka",
              "aaaAAaaa",
              "aaAhhAaa",
              "aamxxmaa",
              "aaxtxxaa"],
    "left": ["aaaaaaaa",
             "aaavaaaa",
             "aaaaaaaa",
             "haaaaaaa",
             "aaaaaaaa",
             "aaaaavaa",
             "Aaaaaaaa",
             "AAaaaaaA"],
    "back": ["aaaaaaaa",
             "aaaaaaaa",
             "aahaaaaa",
             "aaaaaaaa",
             "aaaaavaa",
             "aaaaaaaa",
             "aaaaaaaa",
             "AaaaaaaA"],
}
# the rag over the eyes: the hat layer's band, two texels high at eye level; the knot at the back
HAT = {
    "top": ["........"] * 8,
    "bottom": ["........"] * 8,
    "right": ["........",
              "........",
              "rrrrrrrr",
              "RrrRrrrR",
              "........",
              "........",
              "........",
              "........"],
    "front": ["........",
              "........",
              "rrbrrbrr",
              "RbBrrBbR",
              "........",
              "........",
              "........",
              "........"],
    "left": ["........",
             "........",
             "rrrrrrrr",
             "RrrrRrrR",
             "........",
             "........",
             "........",
             "........"],
    "back": ["........",
             "........",
             "rrrRRrrr",
             "RrrRRrrR",
             "...rR...",
             "...Rr...",
             "....r...",
             "........"],
}
BODY_FACES = {"top": ["acccccca",
                      "cccccccc",
                      "cccccccc",
                      "acccccca"],
              "bottom": ["CCCCCCCC", "CCCCCCCC", "CCCCCCCC", "CCCCCCCC"]}
BODY_STRIP = _cat(
    ["cccc",          # right side (back -> front)
     "cccc",
     "cCcc",
     "cccc",
     "ccCc",
     "cccc",
     "Cccc",
     "ccac",
     "caaa",
     "aaaA",
     "aaaa",
     "AaAA"],
    ["acccccca",      # front: the tunic torn open over the ribs, a ragged hem above the waist
     "aaccccaa",
     "cahaahac",
     "caAaaAac",
     "cahaahac",
     "caAvaAac",
     "ccaaaacc",
     "cCcffcCc",
     "aCcaacCa",
     "aaCaaCaa",
     "aaaaaaaa",
     "AAaaaaAA"],
    ["cccc",          # left side (front -> back)
     "cccc",
     "ccCc",
     "cccc",
     "cccc",
     "cCcc",
     "cccc",
     "cacc",
     "aaac",
     "Aaaa",
     "aaaa",
     "AAaA"],
    ["cccccccc",      # back: the spine showing through a tear
     "cccCcccc",
     "ccccaccc",
     "cccahccc",
     "cccaAccc",
     "Ccccaccc",
     "ccccaccC",
     "cCccccca",
     "aacccCaa",
     "aaaaaaaa",
     "aaaaaaaa",
     "AAaaaaAA"])
ARM_FACES = {"top": ["aaaa", "ahaa", "aaaa", "aaAa"],
             "bottom": ["nAnA", "AAAA", "nAnA", "AAnA"]}                 # fingertips, dark nails
ARM_STRIP = _cat(
    ["aaaa", "aaaa", "ahaa", "aaaa", "aava", "aaaa", "aaaa", "haaa", "aaaa", "aaaa", "AAaA", "AnAA"],
    ["aaaa", "ahaa", "aaaa", "avaa", "aaaa", "aaaa", "aaha", "aava", "aaaa", "aaaa", "AaAA", "nAnA"],
    ["aaaa", "aaaa", "aaah", "aaaa", "aaaa", "avaa", "aaaa", "aaaa", "aaha", "aaaa", "AAaA", "AAnA"],
    ["aaaa", "aaaa", "aaaa", "aaha", "aaaa", "aaaa", "vaaa", "aaaa", "aaaa", "ahaa", "AaAA", "AnAA"])
LEG_FACES = {"top": ["pppp", "pPpp", "ppPp", "pppp"],
             "bottom": ["AAAA", "AAAA", "AAAA", "AAAA"]}
LEG_STRIP = _cat(                                                  # torn trousers to the shin, bare pale feet
    ["pppp", "pPpp", "pppp", "ppPp", "pppp", "pppp", "ppPp", "pppa", "paaa", "aaaa", "aaaa", "AAAA"],
    ["pppp", "ppPp", "pPpp", "pppp", "pppp", "pPpp", "pppp", "apPa", "aapa", "aaaa", "ahaa", "AAAA"],
    ["pppp", "pppp", "Pppp", "pppp", "ppPp", "pppp", "pppp", "Pppa", "apaa", "aaaa", "aaaa", "AAAA"],
    ["pPpp", "pppp", "pppp", "pppP", "pppp", "pppp", "ppPp", "ppaa", "paaa", "aaaa", "aaha", "AAAA"])


def blind_skin():
    s = Skin(seed=79)
    s.paint_box(*BASE_BOXES["head"], HEAD, legend=LEGEND, light={"front": (0.25, -0.2)})
    s.paint_box(*BASE_BOXES["hat"], HAT, legend=LEGEND)
    s.paint_box(*BASE_BOXES["body"], BODY_FACES, BODY_STRIP, legend=LEGEND)
    s.paint_box(*BASE_BOXES["right_arm"], ARM_FACES, ARM_STRIP, legend=LEGEND)
    s.paint_box(*BASE_BOXES["right_leg"], LEG_FACES, LEG_STRIP, legend=LEGEND)
    return s.image()


def coverage(img):
    """Every face of the zombie model's boxes fully opaque (the hat layer: only its band)."""
    out = {}
    for name, (uv, size) in BASE_BOXES.items():
        for face, (x, y, w, h) in gm.box_faces(uv, size).items():
            out[name, face] = float(np.mean(img[y:y + h, x:x + w, 3] >= 0.5))
    return out


def build(path):
    import textures as tx
    img = blind_skin()
    cov = coverage(img)
    holes = [k for k, v in cov.items() if k[0] != "hat" and v < 1.0]
    assert not holes, f"blind skin: transparent texels on {holes}"
    band = [k for k, v in cov.items() if k[0] == "hat" and k[1] in ("front", "left", "right", "back") and v <= 0]
    assert not band, f"blind skin: the rag's band is missing on {band}"
    tx.save_png(img, path("textures", "entity", "blind.png"))
    print("blind: textures/entity/blind.png")
