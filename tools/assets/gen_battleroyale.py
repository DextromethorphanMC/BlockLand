"""大逃杀 assets: the drop plane's texture (box-UV layout of `client/battleroyale/BrPlaneModel`) and the mode's sounds
(the plane's engine drone, the jump, the free-fall wind, the zone siren). Deterministic.

python -I gen_battleroyale.py            (or build_all.py battleroyale)"""
import os
import tempfile

import numpy as np

from gen_equipment_sounds import _loop
from gen_sounds import SR, band, encode, env_exp, finish, merge_sounds_json, reverb, t_axis
from textures import save_png

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources", "assets", "blockland")

# ---- texture ---------------------------------------------------------------------------------------------------------
TEX = 256
# box UV regions: name -> (u, v, w, h, d) of the cube in pixels (must match BrPlaneModel's texOffs and sizes)
BOXES = {
    "fuselage": (0, 0, 14, 14, 80),
    "nose": (0, 94, 10, 10, 8),
    "wing": (0, 112, 80, 3, 24),
    "stabilizer": (0, 140, 30, 2, 14),
    "fin": (90, 140, 2, 24, 16),
    "engine": (130, 140, 8, 8, 14),
    "blade": (180, 140, 2, 22, 1),
    "hub": (190, 140, 3, 3, 3),
}
BODY = np.array([0.80, 0.82, 0.84])
BELLY = np.array([0.55, 0.58, 0.60])
STRIPE = np.array([0.80, 0.18, 0.14])
WINDOW = np.array([0.10, 0.16, 0.24])
METAL = np.array([0.40, 0.42, 0.45])
DARK = np.array([0.12, 0.12, 0.13])


def _fill(img, x, y, w, h, color, rng, grain=0.03):
    if w <= 0 or h <= 0:
        return
    noise = 1 + (rng.random((h, w, 1)) - 0.5) * 2 * grain
    img[y:y + h, x:x + w, :3] = np.clip(color[None, None, :] * noise, 0, 1)
    img[y:y + h, x:x + w, 3] = 1


def _box(img, name, rng, side, top=None, bottom=None, front=None, back=None):
    """Paints one box-UV unwrap: top / bottom faces in the first row, west, north, east, south in the second."""
    u, v, w, h, d = BOXES[name]
    _fill(img, u + d, v, w, d, top if top is not None else side, rng)
    _fill(img, u + d + w, v, w, d, bottom if bottom is not None else side, rng)
    _fill(img, u, v + d, d, h, side, rng)
    _fill(img, u + d, v + d, w, h, front if front is not None else side, rng)
    _fill(img, u + d + w, v + d, d, h, side, rng)
    _fill(img, u + 2 * d + w, v + d, w, h, back if back is not None else side, rng)
    return u, v, w, h, d


def texture():
    rng = np.random.default_rng(510)
    img = np.zeros((TEX, TEX, 4))
    u, v, w, h, d = _box(img, "fuselage", rng, BODY, top=BODY, bottom=BELLY)
    # the long sides (west at u, east at u + d + w): a red cheat line and a row of windows
    for x0 in (u, u + d + w):
        _fill(img, x0, v + d + 8, d, 2, STRIPE, rng)
        _fill(img, x0, v + d + 10, d, h - 10, BELLY, rng)
        for i in range(6, d - 6, 6):
            _fill(img, x0 + i, v + d + 4, 3, 3, WINDOW, rng, 0.0)
    nu, nv, nw, nh, nd = _box(img, "nose", rng, BODY, bottom=BELLY)
    # the cockpit glazing on the nose's front and upper sides
    _fill(img, nu + nd + 1, nv + nd + 1, nw - 2, 4, WINDOW, rng, 0.0)
    _box(img, "wing", rng, METAL, top=BODY, bottom=BELLY)
    _box(img, "stabilizer", rng, METAL, top=BODY, bottom=BELLY)
    fu, fv, fw, fh, fd = _box(img, "fin", rng, BODY)
    for x0, ww in ((fu, fd), (fu + fd + fw, fd)):
        _fill(img, x0, fv + fd, ww, 6, STRIPE, rng)
    _box(img, "engine", rng, METAL, front=DARK)
    _box(img, "blade", rng, DARK)
    _box(img, "hub", rng, METAL)
    out = os.path.join(ROOT, "textures", "entity", "br_plane.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    save_png(img, out)
    return out


# ---- sounds ------------------------------------------------------------------------------------------------------------
def engine(seed=520, dur=4.0):
    """Four propeller engines heard from the cabin: a low drone with beating harmonics and airflow. Loops seamlessly."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur + 0.25)
    x = np.zeros_like(t)
    for f, a in ((38.0, 1.0), (38.6, 0.8), (76.2, 0.45), (114.0, 0.25), (152.5, 0.12)):
        x += a * np.sin(2 * np.pi * f * t + rng.random() * 6.28)
    x *= 1 + 0.25 * np.sin(2 * np.pi * 0.6 * t)
    x += band(rng.standard_normal(len(t)), 120, 1800) * 0.35
    return finish(_loop(x, 0.25), -4.0, drive=1.4, fade=0)


def jump(seed=521):
    """Out of the door: a rush of air that swells and passes."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.4)
    swell = np.clip(t / 0.25, 0, 1) * np.exp(-np.maximum(t - 0.25, 0) / 0.45)
    x = band(rng.standard_normal(len(t)), 200, 5000) * swell
    return finish(reverb(x, 0.3, 0.15, seed=seed), -3.0)


def wind(seed=522, dur=3.0):
    """Free fall: a steady roar of wind with slow gusts. Loops seamlessly."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur + 0.25)
    gust = 0.75 + 0.25 * np.sin(2 * np.pi * 0.7 * t) * np.sin(2 * np.pi * 0.23 * t + 1)
    x = (band(rng.standard_normal(len(t)), 80, 900) * 0.9 + band(rng.standard_normal(len(t)), 900, 4000) * 0.35) * gust
    return finish(_loop(x, 0.25), -4.0, fade=0)


def zone_shrink(seed=523):
    """The zone starts moving in: two rising siren sweeps."""
    t = t_axis(2.4)
    x = np.zeros_like(t)
    for start in (0.0, 1.1):
        local = np.clip((t - start) / 1.0, 0, 1)
        f = 420 + 380 * local
        phase = 2 * np.pi * np.cumsum(f) / SR
        x += np.sin(phase) * (0.6 + 0.4 * np.sign(np.sin(phase * 0.5))) * env_exp(t, 0.6, 0.02, start) * ((t >= start) & (t < start + 1.05))
    return finish(reverb(x, 0.6, 0.3, seed=seed), -3.0)


FILES = {"plane_engine": engine, "jump": jump, "wind": wind, "zone_shrink": zone_shrink}
EVENTS = {f"battleroyale.{name}": [name] for name in FILES}
SUBTITLES = {
    "battleroyale.plane_engine": ("飞机引擎轰鸣", "Plane engines drone"),
    "battleroyale.jump": ("跳出机舱", "Jumps out"),
    "battleroyale.wind": ("呼啸的风声", "Wind rushes"),
    "battleroyale.zone_shrink": ("安全区开始缩小", "Zone closing in"),
}


def sounds():
    tmp = tempfile.mkdtemp(prefix="blockland_br_snd_")
    for name, fn in FILES.items():
        out = os.path.join(ROOT, "sounds", "battleroyale", f"{name}.ogg")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        encode(fn(), out, tmp)
    merge_sounds_json(os.path.join(ROOT, "sounds.json"), {
        ev: {"sounds": [f"blockland:battleroyale/{f}" for f in files], "subtitle": f"subtitles.blockland.{ev}"}
        for ev, files in EVENTS.items()})
    return len(FILES)


def build(_path=None):
    print("br_plane texture:", texture())
    print("battle royale sounds:", sounds())


if __name__ == "__main__":
    build()
