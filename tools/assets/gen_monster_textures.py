"""Infection monster textures: the slime licker skin, the slimed HUD overlay + drip layer and the slimed effect icon.

textures/entity/slime_licker.png        64x64.  Top half = vanilla zombie layout (HumanoidModel.createMesh; the left
                                        limbs mirror the right ones), bottom half = the extra boxes in EXTRA_BOXES.
textures/misc/slime_overlay.png         512x256 straight-alpha goo frame, stretched over the window.
textures/misc/slime_overlay_drips.png   256x256 sparse drips, tiles in both directions (the runtime scrolls it down).
textures/mob_effect/slimed.png          18x18 effect icon.

The skin is pixel art: every box face is a char map (palette letters; slime is shaded from its own outline) over
seeded noise, so the output is deterministic.  Faces follow Minecraft's cube unwrap (box_faces): a row
[top | bottom] above a strip [right | front | left | back], right/left being the entity's own sides; top and bottom
read back -> front downwards.  preview_monster.py folds the skin onto the model (out/monster_textures.png)."""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import textures as tx                                # noqa: E402


def _hex(*cs):
    return np.array([[int(c[i:i + 2], 16) / 255 for i in (0, 2, 4)] for c in cs])


# ------------------------------------------------------------------------------------------------ palettes (sRGB)
SKIN = _hex("222c1b", "303d23", "40512e", "516439", "637743", "778750")       # mottled dark olive
BRUISE = _hex("3a2a33", "4e3944", "624956")                                   # lesions
SHIRT = _hex("161d1f", "202a2d", "2b383b", "38474a", "47575a")                # ruined teal-grey shirt
PANTS = _hex("1e1a17", "2a2420", "372f29", "453b32", "54483d")                # dark brown trousers
STAIN = _hex("1e2c18", "2a3d1f", "374e27")                                    # dried slime on cloth
FLESH = _hex("2c0910", "561520", "862a3a", "b04c5a", "d47e84")                # wounds: deep .. wet highlight
SLIME = _hex("174f10", "2c7e17", "56b020", "92dd3c", "dcffa0")                # skin slime: dark, mid, body, lit, spec
EYE = _hex("587616", "b4e41e", "ecff68")                                      # glow spill, iris, core
MOUTH = _hex("12080a", "4a1420", "7a2a36")                                    # cavity, throat, gums
TOOTH = _hex("9a9064", "c8bc8a", "e6dcb4")
THREAD = _hex("c4b892")[0]
VEIN = _hex("4c9a22")[0]                                                      # infection veins under the sac
TONGUE = _hex("441236", "6c2456", "9a3c78", "c45e98", "eaa0c6")
SAC = _hex("185c14", "2a8a1c", "4cba28", "86e040", "c8ff8c", "f0ffdc")
SAC_VEIN = _hex("13461a")[0]
SAC_GLOW = _hex("c4f546")[0]
SAC_ALPHA = 0.86                                                              # membrane; veins/glints are opaque

# --------------------------------------------------------------------------------------------------- skin layout
# part -> ((texOffs u, v), (w, h, d)).  BASE_BOXES = HumanoidModel.createMesh on a 64x64 texture.
BASE_BOXES = {"head": ((0, 0), (8, 8, 8)), "hat": ((32, 0), (8, 8, 8)), "body": ((16, 16), (8, 12, 4)),
              "right_arm": ((40, 16), (4, 12, 4)), "right_leg": ((0, 16), (4, 12, 4))}
EXTRA_BOXES = {"tongue_base": ((0, 32), (2, 1, 5)), "tongue_tip": ((0, 40), (2, 1, 4)),
               "sac": ((16, 32), (6, 6, 3)), "jaw": ((36, 32), (6, 2, 4))}
TRANSLUCENT_OK = {"hat"}                       # faces of these boxes may contain alpha-0 pixels by design
ROUNDED = {"sac"}                              # these boxes have alpha-0 corner pixels (rounded silhouette)


def box_faces(uv, size):
    """Minecraft cube unwrap -> face -> (x, y, w, h) in texels."""
    (u, v), (w, h, d) = uv, size
    return {"top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d), "right": (u, v + d, d, h),
            "front": (u + d, v + d, w, h), "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h)}


def box_strip(uv, size):
    (u, v), (w, h, d) = uv, size
    return u, v + d, 2 * (w + d), h


# --------------------------------------------------------------------------------------------------- skin painter
# legend: char -> None (transparent) | ("n", palette, base index, noise amplitude, noise field) | ("c", rgb[, alpha])
#         | ("s",) slime, shaded from its outline | ("*",) slime glint
LEGEND = {
    ".": None,
    "k": ("n", SKIN, 2.6, 0.8, "skin"), "K": ("n", SKIN, 0.9, 0.45, "skin"), "j": ("n", SKIN, 4.0, 0.5, "skin"),
    "q": ("n", BRUISE, 1.0, 0.7, "skin"),
    "c": ("n", SHIRT, 2.3, 0.8, "cloth"), "C": ("n", SHIRT, 0.7, 0.45, "cloth"),
    "p": ("n", PANTS, 2.3, 0.8, "cloth"), "P": ("n", PANTS, 0.7, 0.45, "cloth"),
    "e": ("n", STAIN, 1.0, 0.8, "flat"),
    "f": ("n", FLESH, 2.0, 0.6, "flat"), "F": ("n", FLESH, 0.8, 0.4, "flat"), "r": ("c", FLESH[4]),
    "n": ("c", THREAD), "v": ("c", VEIN),
    "x": ("c", MOUTH[0]), "m": ("c", MOUTH[1]), "M": ("c", MOUTH[2]), "t": ("c", TOOTH[2]), "T": ("c", TOOTH[1]),
    "Y": ("c", EYE[2]), "y": ("c", EYE[1]), "g": ("c", EYE[0]),
    "s": ("s",), "*": ("*",),
}
TONGUE_LEGEND = {"z": ("c", TONGUE[0]), "u": ("c", TONGUE[1]), "U": ("c", TONGUE[2]), "w": ("c", TONGUE[3]),
                 "W": ("c", TONGUE[4])}
SAC_LEGEND = {str(i + 1): ("c", SAC[i], SAC_ALPHA if i < 4 else 1.0) for i in range(6)}
SAC_LEGEND.update({"v": ("c", SAC_VEIN), "g": ("c", SAC_GLOW, SAC_ALPHA)})


def _znorm(a):
    return (a - a.mean()) / (a.std() + 1e-9)


def _shift(m, dy, dx, wrap=False):
    """out[y, x] = m[y + dy, x + dx]; False outside (x wraps around when wrap)."""
    h, w = m.shape
    if wrap:
        m, dx = np.roll(m, -dx, axis=1), 0
    out = np.zeros_like(m)
    out[max(0, -dy):min(h, h - dy), max(0, -dx):min(w, w - dx)] = \
        m[max(0, dy):min(h, h + dy), max(0, dx):min(w, w + dx)]
    return out


class Skin:
    def __init__(self, size=64, seed=61):
        rng = np.random.default_rng(seed)
        self.rgb = np.zeros((size, size, 3))
        self.alpha = np.zeros((size, size))
        blur = tx._blur_wrap
        fine = _znorm(blur(rng.standard_normal((size, size)), 0.7))
        coarse = _znorm(blur(rng.standard_normal((size, size)), 1.8))
        self.noise = {"skin": _znorm(0.5 * fine + 0.85 * coarse),                          # patches, not grain
                      "cloth": _znorm(blur(rng.standard_normal((size, size)), 0.5, 1.9)),   # hanging folds
                      "flat": _znorm(blur(rng.standard_normal((size, size)), 0.6))}

    def paint(self, rect, rows, light=(0.0, 0.0), wrap=False, legend=None):
        """Fill rect (x, y, w, h) from a char map; light = noise bias at the top and bottom row."""
        x0, y0, w, h = rect
        assert len(rows) == h and all(len(r) == w for r in rows), (rect, rows)
        leg = dict(LEGEND, **(legend or {}))
        grid = np.array([list(r) for r in rows])
        rgb, alpha = self.rgb[y0:y0 + h, x0:x0 + w], self.alpha[y0:y0 + h, x0:x0 + w]
        for j in range(h):
            bias = light[0] + (light[1] - light[0]) * j / max(h - 1, 1)
            for i in range(w):
                e = leg[grid[j, i]]
                if e is None or e[0] in "s*":
                    rgb[j, i], alpha[j, i] = 0, 0
                elif e[0] == "c":
                    rgb[j, i], alpha[j, i] = e[1], (e[2] if len(e) > 2 else 1.0)
                else:
                    _, pal, base, amp, field = e
                    k = int(np.clip(np.round(base + amp * self.noise[field][y0 + j, x0 + i] + bias), 0, len(pal) - 1))
                    rgb[j, i], alpha[j, i] = pal[k], 1.0
        slime = np.isin(grid, ["s", "*"])
        if slime.any():
            lit = (~_shift(slime, 0, -1, wrap)).astype(int) + ~_shift(slime, -1, 0)      # open to the left / top
            dark = (~_shift(slime, 0, 1, wrap)).astype(int) + ~_shift(slime, 1, 0)       # open to the right / bottom
            lvl = np.clip(2 + np.minimum(lit, 1) - dark, 0, 3)
            lvl[grid == "*"] = 4
            # wet contact shadow on whatever lies right below / right of the slime
            shadow = ~slime & (alpha > 0) & (_shift(slime, -1, 0) | _shift(slime, 0, -1, wrap))
            rgb[shadow] *= 0.72
            rgb[slime], alpha[slime] = SLIME[lvl[slime]], 1.0

    def paint_box(self, uv, size, faces, strip=None, legend=None, light=None):
        """faces: dict face -> char map (any of top/bottom/right/front/left/back); strip: char map of the whole
        [right|front|left|back] band (wraps around the box for the slime outline)."""
        light = light or {}
        rects = box_faces(uv, size)
        for name, rows in faces.items():
            self.paint(rects[name], rows, light.get(name, FACE_LIGHT[name]), legend=legend)
        if strip is not None:
            self.paint(box_strip(uv, size), strip, light.get("strip", FACE_LIGHT["strip"]), wrap=True, legend=legend)

    def image(self):
        return np.dstack([self.rgb, self.alpha])


FACE_LIGHT = {"top": (0.6, 0.6), "bottom": (-0.9, -0.9), "strip": (0.35, -0.45),
              "right": (0.2, -0.3), "front": (0.2, -0.3), "left": (0.2, -0.3), "back": (0.2, -0.3)}


def _cat(*cols):
    """Join per-face char maps side by side into one strip."""
    return ["".join(parts) for parts in zip(*cols)]


# ------------------------------------------------------------------------------------------------ skin char maps
# Orientation per face (see box_faces): top/bottom x = entity right -> left, y = back -> front;
# right x = back -> front; front x = entity right -> left; left x = front -> back; back x = entity left -> right.
HEAD = {
    "top": ["kkkkkkkk",
            "kkqkFkkk",
            "kkknFnkk",
            "kKkkFkkk",
            "kkknFnkk",
            "kkkkFkqk",
            "kkknFnkk",
            "kkkkkkkk"],
    "bottom": ["KKKKKKKK",           # neck underside; the front rows are the open mouth's palate
               "KkkkkkkK",
               "KkkkkkkK",
               "KkkkkkkK",
               "KKkkkkKK",
               "KxxxxxxK",
               "KmmMMmmK",
               "KtxtTxtK"],
    "right": ["kkkkkkkk",            # rotten open wound
              "kkkqqkkk",
              "kkqffqkk",
              "kqfFrfqk",
              "kkqfFfkk",
              "kkkqqkkk",
              "kkkkkkKk",
              "KkkkkkkK"],
    "front": ["kkkkkkkk",            # sunken glowing slits, gaping mouth (the jaw box hangs below it)
              "kKKkkKKk",
              "KyYKKYyK",
              "kKgkkgKk",
              "jkkKKkkj",
              "KxxxxxxK",
              "KtxtTxtK",
              "Kxmxxmxk"],
    "left": ["kkkkkkkk",             # stitched gash from the cheek back
             "kkkkkkkk",
             "knknknkk",
             "FFFFFFfk",
             "knknknkk",
             "kkkkkkqk",
             "Kkkkkqqk",
             "kkkkkkkk"],
    "back": ["kkkkkkkk",
             "kkkkkkkk",
             "kqkkkkkk",
             "kkkkkkkk",
             "kkkkkfkk",
             "kkkknFnk",
             "kkkkkfkk",
             "KKkkkkKK"],
}
HAT = {  # goo on the scalp running over the edges (drips meet the top face's slime), drool at the mouth corners
    "top": ["..sss...",
            ".sss*s..",
            "ssssss..",
            ".ssss.s.",
            "..ss....",
            "..s.....",
            "......s.",
            ".....ss."],
    "bottom": ["........"] * 8,
    "right": ["..s.....",
              "..s.....",
              ".ss.....",
              "........",
              "........",
              "........",
              "........",
              "........"],
    "front": [".....ss.",            # a drip creeping down towards the left eye, a drool strand from one corner
              "......s.",
              "........",
              "........",
              "........",
              ".s...ss.",
              "......s.",
              "......*."],
    "left": ["........",
             "........",
             "........",
             "........",
             "........",
             ".....*s.",
             ".....ss.",
             ".....s.."],
    "back": ["...sss..",
             "...s.s..",
             "...s.s..",
             "...s....",
             "..ss....",
             "........",
             "........",
             "........"],
}
BODY_FACES = {"top": ["ccckkccc",
                      "cckkkkcc",
                      "cckkkkcc",
                      "ccckkccc"],
              "bottom": ["CCcCCcCC",
                         "CcCCCCcC",
                         "CCCcCCCC",
                         "CcCCCcCC"]}
BODY_STRIP = _cat(
    ["cccc",          # right side (back -> front)
     "cCcc",
     "cccs",
     "ccCs",
     "cecs",
     "kcee",
     "kkcc",
     "kkCc",
     "cccc",
     "cCec",
     "ccee",
     "kcck"],
    ["cCkkkkCc",      # front: torn collar, drool pooled on the chest running down, stitched belly wound
     "cCssskCc",
     "cs*ssssc",
     "csssssCc",
     "cssCssec",
     "cssCscee",
     "cCsCsece",
     "ccCcsknk",
     "cCcc*FFF",
     "cecCsknk",
     "cCecsckc",
     "ckCcckCk"],
    ["cccc",          # left side (front -> back)
     "ccCc",
     "sccc",
     "scCc",
     "secc",
     "eeck",
     "cckk",
     "cCkk",
     "cccc",
     "ceCc",
     "eecc",
     "kcck"],
    ["cccCcccc",      # back: shirt torn open around the sac (cols 1-6, rows 2-7), infection veins
     "cCkkkkcc",
     "ckvkkvkc",
     "cvkkkkvk",
     "ckkkkkkc",
     "cvkkkkvc",
     "ckvkkvkc",
     "cckkkkCc",
     "cCcssCcc",
     "ceCs*ecc",
     "cCesecCc",
     "ckcecCkc"])
ARM_FACES = {"top": ["cCcc",
                     "cccc",
                     "ccCc",
                     "cccc"],
             "bottom": ["kKkK",          # the hand end (points forward when the arms are raised)
                        "kkkk",
                        "KkKk",
                        "kkks"]}
# raised zombie arms: the front face points up (slime runs along it), the back face hangs down (drips)
ARM_STRIP = _cat(
    ["cccc", "cCcc", "kcCk", "kkkk", "kkqk", "kkkk", "knkn", "kFFF", "knkn", "kkkk", "Kkkk", "KkKk"],   # outer
    ["cccc", "cccC", "cCsc", "k*sk", "kssk", "kssk", "kksk", "kksk", "kssk", "kkkk", "kkkK", "KkKk"],   # front
    ["cccc", "cCcc", "cckc", "kckk", "kkkk", "kqkk", "kqqk", "kkkk", "kkkk", "kkkk", "Kkkk", "KkKk"],   # inner
    ["cccc", "ccCc", "Cckc", "kskk", "kskk", "kskk", "kssk", "kkkk", "kkkk", "kksk", "kksk", "KkKk"])   # back
LEG_FACES = {"top": ["pppp"] * 4,
             "bottom": ["KKKK",
                        "KkKK",
                        "KKkK",
                        "KKKK"]}
LEG_STRIP = _cat(   # torn trousers: slime from the shirt hem, dried stains, a skinned knee, ragged hems
    ["pppp", "pPpp", "pppp", "ppPp", "pepp", "peep", "pppp", "ppPp", "pPpp", "kpPk", "kkkk", "KKKK"],   # outer
    ["pspp", "pspP", "pspp", "ppPp", "Pjkp", "kfkP", "PkkP", "pppp", "pPpp", "pkpP", "kkkk", "KKKK"],   # front
    ["pppp", "pPpp", "pppe", "ppee", "pppp", "pppP", "pppp", "pPpp", "pppp", "kPpk", "kkkk", "KKKK"],   # inner
    ["pppp", "ppPp", "pppp", "pPpp", "pppp", "ppPp", "pppp", "pPpp", "pppp", "Ppkp", "kkkk", "KKKK"])   # back

TONGUE_BASE = {"top": ["uU", "wU", "Wu", "sU", "wW"],            # upper surface, root (back) -> tip (front)
               "bottom": ["zu", "uz", "Uu", "uU", "Us"],
               "right": ["zuUwU"], "front": ["wU"], "left": ["UwUuz"], "back": ["zz"]}
TONGUE_TIP = {"top": ["wU", "WU", "ws", "WU"],                  # swollen tip with a slime drop hanging off
              "bottom": ["uU", "zu", "uU", "Uu"],
              "right": ["uUwU"], "front": ["Ws"], "left": ["UwUu"], "back": ["zz"]}
SAC_FACES = {  # back = the outward face when the sac sits on the monster's back; corners are cut (rounded)
    "top": [".4554.",
            "445643",
            ".3333."],
    "bottom": [".2112.",
               "211122",
               ".1111."],
    "right": [".4.", "432", "3v2", "332", "222", ".1."],
    "front": [".2222.",
              "222v22",
              "22v222",
              "2v2g21",
              "222221",
              ".1111."],
    "left": [".4.", "234", "2v3", "233", "222", ".1."],
    "back": [".4443.",                # glint top left, one branching vein, glowing core, darker lower right
             "456332",
             "463v22",
             "33vg21",
             "3v2221",
             ".2211."],
}
JAW_FACES = {"top": ["xxxxxx",          # inside of the lower jaw, teeth along the front edge
                     "xmmmmx",
                     "MmxxmM",
                     "tTxtxT"],
             "bottom": ["KKKKKK",
                        "KkkkkK",
                        "kkskkk",
                        "kssksk"],
             "right": ["KkkT",
                       "Kkks"],
             "front": ["tTxtxT",
                       "kskksk"],
             "left": ["TkkK",
                      "skkK"],
             "back": ["KKKKKK",
                      "KKKKKK"]}


def slime_licker_skin():
    s = Skin()
    s.paint_box(*BASE_BOXES["head"], HEAD, light={"front": (0.25, -0.2)})
    s.paint_box(*BASE_BOXES["hat"], HAT)
    s.paint_box(*BASE_BOXES["body"], BODY_FACES, BODY_STRIP)
    s.paint_box(*BASE_BOXES["right_arm"], ARM_FACES, ARM_STRIP)
    s.paint_box(*BASE_BOXES["right_leg"], LEG_FACES, LEG_STRIP)
    s.paint_box(*EXTRA_BOXES["tongue_base"], TONGUE_BASE, legend=TONGUE_LEGEND)
    s.paint_box(*EXTRA_BOXES["tongue_tip"], TONGUE_TIP, legend=TONGUE_LEGEND)
    s.paint_box(*EXTRA_BOXES["sac"], SAC_FACES, legend=SAC_LEGEND)
    s.paint_box(*EXTRA_BOXES["jaw"], JAW_FACES)
    return s.image()


def skin_coverage(img):
    """-> {(box, face): fraction of texels with alpha >= 0.5} for every box face of the skin layout."""
    out = {}
    for name, (uv, size) in {**BASE_BOXES, **EXTRA_BOXES}.items():
        for face, (x, y, w, h) in box_faces(uv, size).items():
            a = img[y:y + h, x:x + w, 3]
            out[name, face] = float(np.mean(a >= 0.5))
    return out


# ---------------------------------------------------------------------------------------------- goo (HUD layers)
def _smax(a, b, k):
    """Smooth maximum: rounded union of two 'inside' distance fields (bigger = deeper inside)."""
    h = np.clip(0.5 + 0.5 * (a - b) / k, 0, 1)
    return b + (a - b) * h + k * h * (1 - h)


def _noise1(n, scale, rng):
    """Smooth periodic 1D noise in [-1, 1] with feature size ~scale samples."""
    f = np.fft.fftfreq(n)
    v = np.real(np.fft.ifft(np.fft.fft(rng.standard_normal(n)) * np.exp(-2 * (np.pi * f * scale) ** 2)))
    return v / (np.abs(v).max() + 1e-9)


GOO_RAMP = [(0.00, (0.62, 0.90, 0.26)), (0.18, (0.38, 0.76, 0.14)), (0.45, (0.18, 0.54, 0.08)),
            (0.75, (0.08, 0.34, 0.05)), (1.00, (0.04, 0.20, 0.03))]


def _ramp(t, stops):
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,))
    for c in range(3):
        out[..., c] = np.interp(t, [s[0] for s in stops], [s[1][c] for s in stops])
    return out


def _grad(a, wrap):
    if wrap:
        return (np.roll(a, -1, 1) - np.roll(a, 1, 1)) / 2, (np.roll(a, -1, 0) - np.roll(a, 1, 0)) / 2
    gy, gx = np.gradient(a)
    return gx, gy


def _goo_shade(F, bumps, thick, wrap=False, relief=11.0):
    """Shade a goo field: F = signed distance inside the goo (px), bumps = extra height (bubbles, lumps),
    thick = 0..1 colour depth.  -> rgb, coverage alpha, specular."""
    height = 1 - np.exp(-np.maximum(F, 0) / 4.5) + bumps
    gx, gy = _grad(height, wrap)
    n = np.dstack([-gx * relief, -gy * relief, np.ones_like(F)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    light = np.array([-0.48, -0.72, 0.62])                    # from the upper left, image y points down
    light /= np.linalg.norm(light)
    half = light + np.array([0, 0, 1.0])
    half /= np.linalg.norm(half)
    diff = np.clip(n @ light, 0, 1)
    nh = np.clip(n @ half, 0, 1)
    spec = nh ** 90 * 1.25 + nh ** 14 * 0.16
    caustic = np.clip(-(n[..., 0] * light[0] + n[..., 1] * light[1]) * 1.4, 0, 1) ** 2   # light leaving the far rim
    base = _ramp(thick, GOO_RAMP)
    rgb = base * (0.50 + 0.62 * diff[..., None]) + caustic[..., None] * (0.30 * (1 - thick))[..., None] * \
        np.array([0.55, 0.95, 0.25]) + spec[..., None] * np.array([0.92, 1.0, 0.86])
    cover = np.clip(F + 0.5, 0, 1)                               # 1 px anti-aliased edge
    return np.clip(rgb, 0, 1), cover, np.clip(spec, 0, 1)


def _drip_field(x, y, x0, y0, length, w0, rb, rng, taper=0.45, wobble=1.0):
    """Inside field of a drip hanging from (x0, y0): a tapering stem ending in a round drop."""
    ys = y[:, 0]
    t = np.clip((ys - y0) / length, 0, 1)
    phase, freq = rng.uniform(0, 2 * np.pi), rng.uniform(0.03, 0.07)
    xc = x0 + wobble * np.sin(ys * freq + phase) * t
    hw = w0 * (1 - taper * t ** 0.8)
    stem = hw[:, None] - np.abs(x - xc[:, None])
    stem[(ys < y0 - 4 * w0) | (ys > y0 + length), :] = -1e3
    xe = x0 + wobble * np.sin((y0 + length) * freq + phase)
    drop = rb - np.hypot(x - xe, (y - (y0 + length)) * 0.85)
    return _smax(stem, drop, 2.5)


def slime_overlay(w=512, h=256, seed=71):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w] + 0.5
    # edge bands: heavy at the top, thinner down the sides, a low rim at the bottom
    top = 40 + 13 * _noise1(w, 26, rng) + 5 * _noise1(w, 7, rng)
    fall = 1 - np.arange(h) / h
    side_l = (16 + 9 * _noise1(h, 9, rng) + 4 * _noise1(h, 3, rng)) * (0.5 + 0.5 * fall) + 46 * fall ** 4
    side_r = (16 + 9 * _noise1(h, 9, rng) + 4 * _noise1(h, 3, rng)) * (0.5 + 0.5 * fall) + 46 * fall ** 4
    bottom = 10 + 7 * _noise1(w, 14, rng) + 3 * _noise1(w, 4, rng)
    F = _smax(top[None, :] - y, side_l[:, None] - x, 9)
    F = _smax(F, side_r[:, None] - (w - x), 9)
    F = _smax(F, bottom[None, :] - (h - y), 9)
    blobs = [(c, top[int(c)] + rng.uniform(-4, 9), rng.uniform(7, 17)) for c in rng.uniform(0, w, 16)]
    blobs += [(side_l[int(c)] + rng.uniform(-3, 7), c, rng.uniform(6, 16)) for c in rng.uniform(40, h - 20, 7)]
    blobs += [(w - side_r[int(c)] - rng.uniform(-3, 7), c, rng.uniform(6, 16)) for c in rng.uniform(40, h - 20, 7)]
    blobs += [(c, h - bottom[int(c)] - rng.uniform(-2, 6), rng.uniform(4, 14)) for c in rng.uniform(0, w, 12)]
    blobs += [(0, 0, 70), (w, 0, 64), (0, h, 26), (w, h, 30)]
    for cx, cy, r in blobs:
        F = _smax(F, r - np.hypot(x - cx, y - cy), 7)
    # drips from the top band (the long ones stay clear of the crosshair) and down the sides
    edges = np.linspace(0, w, 16)
    for a, b in zip(edges[:-1], edges[1:]):
        x0 = rng.uniform(a + 4, b - 4)
        long = rng.random() < 0.3
        length = rng.uniform(55, 105) if long else rng.uniform(14, 48)
        if abs(x0 - w / 2) < 90:                        # keep the crosshair area clear
            length = min(length, max(14.0, 84 - top[int(x0)]))
        w0 = rng.uniform(3.0, 6.0)
        F = _smax(F, _drip_field(x, y, x0, top[int(x0)] - 3, length, w0, w0 * rng.uniform(1.15, 1.45), rng), 6)
    for side in (0, 1):
        for _ in range(3):
            y0 = rng.uniform(30, 150)
            x0 = side_l[int(y0)] - 2 if side == 0 else w - side_r[int(y0)] + 2
            w0 = rng.uniform(2.5, 4.5)
            F = _smax(F, _drip_field(x, y, x0, y0, rng.uniform(16, 60), w0, w0 * 1.3, rng, wobble=0.6), 5)
    # satellite splatter near the goo edge (kept separate from the smooth union)
    for _ in range(70):
        if rng.random() < 0.6:
            cx = rng.uniform(0, w)
            cy = top[int(cx)] + rng.uniform(4, 34)
        else:
            cy = rng.uniform(20, h - 10)
            if rng.random() < 0.5:
                cx = side_l[int(cy)] + rng.uniform(3, 22)
            else:
                cx = w - side_r[int(cy)] - rng.uniform(3, 22)
        F = np.maximum(F, rng.uniform(0.9, 3.2) - np.hypot(x - cx, y - cy))
    # surface: lumps and bubbles in the thick parts, depth colour by thickness
    lump = _znorm(tx._blur_wrap(rng.standard_normal((h, w)), 5.0)) * 0.05
    bumps = lump * np.clip(F / 10, 0, 1)
    bubbles = np.zeros_like(F)                           # trapped air: thinner (lighter) goo with a glint on top
    for _ in range(26):
        ok = np.argwhere(F > 14)
        cy, cx = ok[rng.integers(len(ok))] + 0.5
        r = rng.uniform(1.6, 4.2)
        bubbles += np.sqrt(np.clip(1 - ((x - cx) ** 2 + (y - cy) ** 2) / r ** 2, 0, 1))
    bumps += 0.12 * bubbles
    hue = _znorm(tx._blur_wrap(rng.standard_normal((h, w)), 18.0))
    thick = np.clip(np.clip(F / 38, 0, 1) ** 0.8 + 0.05 * hue - 0.5 * bubbles, 0, 1)
    rgb, cover, spec = _goo_shade(F, bumps, thick)
    a_goo = cover * np.clip(0.84 + 0.14 * thick + 0.5 * spec, 0, 1)
    # thin film over the whole screen: ~0.17-0.25 alpha in the middle, smeared streaks under the top band
    near = tx._blur_wrap((F > 0).astype(float), 22.0)
    smear = np.clip(_znorm(tx._blur_wrap(rng.standard_normal((h, w)), 2.5, 34.0)) - 0.7, 0, 1.5) * fall[:, None] ** 1.5
    film_a = 0.17 + 0.03 * _znorm(tx._blur_wrap(rng.standard_normal((h, w)), 30.0)) + 0.06 * near + 0.07 * smear
    film_rgb = np.array([0.24, 0.55, 0.13]) * (0.9 + 0.1 * hue[..., None]) + 0.25 * smear[..., None] * \
        np.array([0.5, 0.8, 0.3])
    A = a_goo + film_a * (1 - a_goo)
    C = (rgb * a_goo[..., None] + film_rgb * (film_a * (1 - a_goo))[..., None]) / np.maximum(A, 1e-6)[..., None]
    return np.dstack([np.clip(C, 0, 1), np.clip(A, 0, 1)])


DRIPS = [  # x, head y, trail length, trail half-width, head radius
    (14, 52, 150, 1.3, 3.8), (47, 186, 88, 0.9, 2.8), (79, 112, 196, 1.6, 4.6), (113, 236, 118, 1.0, 3.1),
    (141, 30, 70, 0.8, 2.5), (172, 150, 170, 1.4, 4.1), (205, 80, 112, 1.1, 3.3), (236, 214, 62, 0.8, 2.6),
]


def slime_drips(size=256, seed=73):
    """Sparse vertical drips (heads at the bottom of their trails), seamless in x and y."""
    rng = np.random.default_rng(seed)
    pad = 16
    y, x = np.mgrid[0:2 * size, 0:size + 2 * pad] + 0.5
    x = x - pad
    F = np.full(y.shape, -1e3)
    for x0, hy, length, tw, rb in DRIPS:
        hy += size                                      # head in the lower copy, the trail reaches up into the upper
        ys = y[:, 0]
        t = np.clip((ys - (hy - length)) / length, 0, 1)    # 0 at the top of the trail, 1 at the head
        phase = rng.uniform(0, 2 * np.pi)
        xc = x0 + 0.8 * np.sin(2 * np.pi * ys / (size / 2) + phase)
        hw = tw * (0.35 + 0.65 * t ** 0.6)
        for yb in rng.uniform(hy - length * 0.8, hy - 12, 2):          # viscous beads along the trail
            hw = hw + tw * 0.8 * np.exp(-((ys - yb) / 2.5) ** 2)
        stem = hw[:, None] - np.abs(x - xc[:, None])
        stem[(ys < hy - length) | (ys > hy), :] = -1e3
        xe = x0 + 0.8 * np.sin(2 * np.pi * hy / (size / 2) + phase)
        drop = rb - np.hypot(x - xe, (y - hy) * 0.78)
        F = np.maximum(F, _smax(stem, drop, 2.0))
    Fr = np.maximum(F[:size], F[size:])                 # fold the two copies: seamless in y
    out = Fr[:, pad:pad + size].copy()
    out[:, size - pad:] = np.maximum(out[:, size - pad:], Fr[:, :pad])
    out[:, :pad] = np.maximum(out[:, :pad], Fr[:, size + pad:])
    thick = np.clip(out / 9, 0, 1) * 0.55
    rgb, cover, spec = _goo_shade(out, np.zeros_like(out), thick, wrap=True, relief=6.0)
    a = cover * np.clip(0.48 + 0.44 * np.clip(out / 3.2, 0, 1) + 0.5 * spec, 0, 1)
    return np.dstack([rgb, a])


# ---------------------------------------------------------------------------------------------------- effect icon
# A goo mound with drips ending in bright drops (vanilla effect icon style: same-hue dark outline added around the
# fill, light from the top left).  d dark, m mid, l light, h highlight, w glint.
ICON_MAP = ["..................",
            "..................",
            "........lll.......",
            "......llhhllm.....",
            ".....lhwwhllmm....",
            "....lhwhlllmmmm...",
            "....lhhllmmmmmd...",
            "...llhllmmmmmmdd..",
            "...lllmmmmmmmmdd..",
            "..lllmmmmmmmmmddd.",
            ".llmmmmmmmmmmmddd.",
            ".lmmmmmmmmmmmdddd.",
            "..m.dmmmmmmmddd...",
            "....lmd.lm..md....",
            "....lm..lm..md....",
            "....hd......l.....",
            "............h.....",
            ".................."]
ICON = dict(zip("odmlhw", _hex("1d4a12", "2f7a1c", "4ea62a", "7fd040", "b6ee78", "eaffd2")))


def slimed_icon():
    grid = np.array([list(r) for r in ICON_MAP])
    fill = grid != "."
    out = np.zeros((18, 18, 4))
    for ch, rgb in ICON.items():
        out[grid == ch, :3] = rgb
    out[fill, 3] = 1
    edge = ~fill & (_shift(fill, 0, -1) | _shift(fill, 0, 1) | _shift(fill, -1, 0) | _shift(fill, 1, 0))
    out[edge, :3], out[edge, 3] = ICON["o"], 1
    return out


# ---------------------------------------------------------------------------------------------------------- build
# The HUD layers are stretched over the window: linear filtering like vanilla's vignette (smooth sub-texel scrolling
# for the drips); the overlay clamps at its edges, the drip layer keeps repeat wrapping.
META = {("textures", "misc", "slime_overlay.png.mcmeta"): {"texture": {"blur": True, "clamp": True}},
        ("textures", "misc", "slime_overlay_drips.png.mcmeta"): {"texture": {"blur": True}}}


def build(path):
    out = {("textures", "entity", "slime_licker.png"): slime_licker_skin(),
           ("textures", "misc", "slime_overlay.png"): slime_overlay(),
           ("textures", "misc", "slime_overlay_drips.png"): slime_drips(),
           ("textures", "mob_effect", "slimed.png"): slimed_icon()}
    for parts, img in out.items():
        tx.save_png(img, path(*parts))
    for parts, meta in META.items():
        with open(path(*parts), "w", encoding="utf-8", newline="\n") as f:
            json.dump(meta, f, indent=2)
            f.write("\n")
    print(f"monster textures: {', '.join('/'.join(p[1:]) for p in out)} (+{len(META)} .mcmeta)")


if __name__ == "__main__":
    _assets = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src",
                                           "main", "resources", "assets", "blockland"))

    def _path(*parts):
        p = os.path.join(_assets, *parts)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        return p

    build(_path)
