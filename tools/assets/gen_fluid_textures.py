"""Realistic fluid textures for BlockLand's built-in resource pack "BlockLand 材质" (rendering spec §15):
mod/src/main/resources/resourcepacks/blockland_textures/assets/minecraft/textures/block/{water,lava}_{still,flow}.png.

    python -I tools/assets/gen_fluid_textures.py

Every field is spectral noise (numpy FFT): it tiles in both axes by construction, and animates by turning each Fourier
component's phase a whole number of times per loop (so the animation loops without a seam). The flow textures also
translate along +v, the way vanilla's flow frames do (LiquidBlockRenderer's UVs point the flow down the sprite), by a
whole period of the content per loop: their spectrum only keeps v frequencies that fit that period.

Water stays greyscale with vanilla's alpha (180) and about vanilla's mean (the biome colour tints it through the vertex
colour); its look is low-contrast on purpose — the block atlas has only ~4 mip levels, so fine high-contrast detail
would shimmer in the distance: a slow swell, a soft caustic-like network, faint capillary ripples; the flow adds streaks
and foam along the current. Lava: cooled basalt crust, near black and grey-brown, broken into plates of very different
sizes (warped jittered cells at three scales: big plates, about half split again, fine cooling cracks), dull red where
thin; light only in the cracks (white-yellow core, orange edge, varying width) and in molten pools welling up between the
plates (a quarter to a third of the area is hot); the flow draws the plates out along the current. The block material
threshold (block_materials.json) keeps the crust from glowing. Frames are not interpolated (vanilla interpolates animated sprites per pixel on
the CPU every tick, far too slow at 1024²); there are enough frames that the steps are small. Deterministic.
"""
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
PACK = os.path.join(ROOT, "mod", "src", "main", "resources", "resourcepacks", "blockland_textures")
OUT = os.path.join(PACK, "assets", "minecraft", "textures", "block")
N = 1024


class Field:
    """Band-limited spectral noise on an N×N torus, animated by whole phase turns per loop.

    peak: centre of the band (cycles per texture), width: its spread, stretch: (u, v) scale of the frequencies
    (a small v factor makes streaks along v), ky_step: only v frequencies that are multiples of it (content periodic
    in v with period N / ky_step), speed: phase turns per loop at the band's centre (higher frequencies turn faster;
    0 = still)."""

    def __init__(self, rng, peak, width, stretch=(1.0, 1.0), ky_step=1, speed=1.0):
        kx = np.fft.fftfreq(N) * N
        ky = np.fft.fftfreq(N) * N
        KX, KY = np.meshgrid(kx, ky)
        k = np.hypot(KX * stretch[0], KY * stretch[1])
        amp = np.exp(-0.5 * ((k - peak) / width) ** 2)
        amp[0, 0] = 0
        if ky_step > 1:
            amp[(np.round(KY).astype(int) % ky_step) != 0] = 0
        self.ky = KY
        self.spectrum = amp * np.exp(2j * np.pi * rng.random((N, N)))
        turns = np.round(speed * np.sqrt(np.maximum(k, 1e-6) / max(peak, 1e-6)) * rng.choice([-1, 1], (N, N)))
        turns[np.abs(turns) < 0.5] = 0
        self.turns = turns
        base = np.real(np.fft.ifft2(self.spectrum))
        self.mean, self.std = base.mean(), max(base.std(), 1e-12)

    def at(self, t, shift=0.0):
        """The field at loop time t (0..1), moved `shift` pixels along +v; mean 0, deviation 1."""
        phase = np.exp(2j * np.pi * (self.turns * t - self.ky * shift / N))
        return (np.real(np.fft.ifft2(self.spectrum * phase)) - self.mean) / self.std


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def warp(f, dx, dy):
    """f sampled at (x + dx, y + dy), bilinear, wrapping."""
    y, x = np.mgrid[0:N, 0:N].astype(np.float64)
    sx, sy = (x + dx) % N, (y + dy) % N
    x0, y0 = np.floor(sx).astype(int), np.floor(sy).astype(int)
    fx, fy = sx - x0, sy - y0
    x1, y1 = (x0 + 1) % N, (y0 + 1) % N
    return ((f[y0, x0] * (1 - fx) + f[y0, x1] * fx) * (1 - fy)
            + (f[y1, x0] * (1 - fx) + f[y1, x1] * fx) * fy)


# ---- water ------------------------------------------------------------------------------------------------------------

def water_frame(fields, t, shift, flow):
    swell, caustic, warpx, warpy, capillary, streak, foam = fields
    s = swell.at(t, shift)
    # caustic-like network: bright thin ridges of a warped mid-frequency field
    c = warp(caustic.at(t, shift), warpx.at(t, shift) * 18, warpy.at(t, shift) * 18)
    net = (1 - np.clip(np.abs(c) / 0.9, 0, 1)) ** 3
    grey = 177 + 5.5 * s + 22 * (net - net.mean()) + 2.2 * capillary.at(t, shift)
    if flow:
        grey = grey - 10 + 6 * streak.at(t, shift)
        # foam in long soft streaks along the current, where the streaks and the foam field agree (low contrast: the
        # atlas has few mip levels, sharp bright dashes would shimmer in the distance)
        f = smoothstep(0.8, 3.4, foam.at(t, shift) + 0.9 * streak.at(t, shift))
        grey = grey + 20 * f
    return np.clip(grey, 140, 240)


def water(flow):
    rng = np.random.default_rng(1771 if flow else 1717)
    if flow:
        # content repeats every 512 px along v (one block of the 2-block sprite): 16 frames × 32 px = one period
        step, kys = 32, 2
        # v factors above 1: slower change along v, the current's direction (streaks); every band keeps several of the
        # allowed v frequencies, or translating it would do nothing
        stretch = (1.0, 1.5)
        fields = [Field(rng, 6, 3, stretch, kys, 1), Field(rng, 16, 5, (1.0, 1.8), kys, 1),
                  Field(rng, 6, 3, stretch, kys, 1), Field(rng, 6, 3, stretch, kys, 1),
                  Field(rng, 90, 30, (1.0, 1.4), kys, 2), Field(rng, 30, 12, (1.0, 6.0), kys, 1),
                  Field(rng, 14, 6, (1.0, 6.0), kys, 1)]
        frames = 16
    else:
        step, kys = 0, 1
        fields = [Field(rng, 3, 1.5, speed=1), Field(rng, 12, 4, speed=1), Field(rng, 4, 2, speed=1),
                  Field(rng, 4, 2, speed=1), Field(rng, 110, 35, speed=2), None, None]
        frames = 16
    out = [water_frame(fields, i / frames, i * step, flow) for i in range(frames)]
    return np.concatenate(out, axis=0)


def save_water(grey, path):
    g = np.clip(np.round(grey), 0, 255).astype(np.uint8)
    im = Image.fromarray(g, "P")
    im.putpalette([v for i in range(256) for v in (i, i, i)])
    # one alpha for every entry: vanilla's 180
    im.save(path, transparency=bytes([180] * 256), optimize=True)


# ---- lava -------------------------------------------------------------------------------------------------------------

# sRGB colours: cooled basalt crust (near black, grey-brown), its dull red where thin, the hot parts by temperature
CRUST_DARK = np.array([38, 33, 31])
CRUST_GREY = np.array([84, 72, 64])
CRUST_RED = np.array([118, 34, 18])
HOT = np.array([
    (0.00, (170, 40, 10)), (0.30, (232, 92, 14)), (0.55, (250, 150, 30)), (0.78, (255, 206, 90)), (1.00, (255, 246, 200))],
    dtype=object)


def hot_ramp(t):
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,))
    for (a, ca), (b, cb) in zip(HOT[:-1], HOT[1:]):
        m = (t >= a) & (t <= b)
        k = ((t - a) / (b - a))[m][:, None]
        out[m] = np.array(ca) * (1 - k) + np.array(cb) * k
    return out


class Plates:
    """Crust plates at one scale: a jittered cell grid on the torus (gu cells across, gv cells per v period pv), each
    point wobbling slowly (whole loops), each cell with a random value of its own."""

    def __init__(self, rng, gu, gv, pv):
        self.gu, self.gv, self.pv = gu, gv, pv
        self.jitter = rng.uniform(-0.45, 0.45, (gv, gu, 2))
        self.phase = rng.random((gv, gu))
        self.value = rng.random((gv, gu))

    def at(self, x, y, t, wobble):
        """(second minus nearest point distance: 0 on a crack; the nearest cell's value) at pixel x, y."""
        cu, cv = N / self.gu, self.pv / self.gv
        ci, cj = np.floor(x / cu).astype(int), np.floor(y / cv).astype(int)
        d1 = np.full(x.shape, 1e9)
        d2 = np.full(x.shape, 1e9)
        value = np.zeros(x.shape)
        for dj in (-2, -1, 0, 1, 2):
            for di in (-2, -1, 0, 1, 2):
                i, j = ci + di, cj + dj
                wi, wj = i % self.gu, j % self.gv
                ph = 2 * np.pi * (t + self.phase[wj, wi])
                px = (i + 0.5 + self.jitter[wj, wi, 0] + wobble * np.sin(ph)) * cu
                py = (j + 0.5 + self.jitter[wj, wi, 1] + wobble * np.cos(ph)) * cv
                d = np.hypot(x - px, y - py)
                nearer = d < d1
                d2 = np.where(nearer, d1, np.minimum(d2, d))
                value = np.where(nearer, self.value[wj, wi], value)
                d1 = np.where(nearer, d, d1)
        return d2 - d1, value


def lava_frame(scales, fields, t, shift, pv):
    """Cooled basalt crust broken into plates of very different sizes by cracks of three scales (big plates, about half
    of them split again, fine cooling cracks), light only in the cracks (white-yellow core, orange edge) and in molten
    pools welling up between the plates (orange-yellow, brighter in the middle, slow swirls)."""
    big, mid, small = scales
    molten, warpx, warpy, grain, width, split, swirl = fields
    y, x = np.mgrid[0:N, 0:N].astype(np.float64)
    # the plates are looked up through a smooth warp: cracks wander, no lattice shows
    wx, wy = warpx.at(t, shift) * 34, warpy.at(t, shift) * 34
    px, py = (x + wx) % N, (y - shift + wy) % pv
    e1, v1 = big.at(px, py, t, 0.04)
    e2, v2 = mid.at((px + 0.6 * wy) % N, (py + 0.6 * wx) % pv, t, 0.04)
    e3, _ = small.at((px - 0.4 * wy) % N, (py + 0.4 * wx) % pv, t, 0.04)
    w = np.clip(1 + 0.75 * width.at(t, shift), 0.25, 2.2)
    # big cracks everywhere; middle ones split about half the big plates; fine ones only scratch the crust
    splits = smoothstep(0.35, 0.65, v1 * 0.6 + 0.4 * (0.5 + 0.25 * split.at(t, shift)))
    # widths in texels of 1024 per block: big cracks 4-14 (a few centimetres), so they still read from a distance
    crack = np.maximum.reduce([
        1 - smoothstep(2.5 * w, 7.0 * w, e1),
        (1 - smoothstep(1.2 * w, 4.0 * w, e2)) * splits * 0.85,
        (1 - smoothstep(0.4, 1.6, e3)) * splits * 0.35])
    # thin crust near a crack shows a dull red; a few plates are still warm all over
    near = np.maximum(np.exp(-e1 / (14 * w)), 0.7 * np.exp(-e2 / (8 * w)) * splits)
    g = grain.at(t, shift)
    # pahoehoe-like ropy wrinkles: thin ridges of a mid-frequency field, low contrast
    ropes = (1 - np.clip(np.abs(swirl.at(t, shift)) / 0.45, 0, 1)) ** 3
    tone = 0.4 + 0.2 * g + 0.25 * (v2 - 0.5) + 0.22 * ropes
    crust = CRUST_DARK + (CRUST_GREY - CRUST_DARK) * np.clip(tone, 0, 1)[..., None]
    warm = np.clip(0.8 * near + 0.12 * (v1 > 0.85), 0, 1)
    color = crust + (CRUST_RED - crust) * warm[..., None]
    # molten pools welling up between the plates: along the big cracks, where the melt field is high; hottest in the
    # middle, swirled
    m = molten.at(t, shift) + 1.3 * np.exp(-e1 / 45.0)
    pool = smoothstep(1.08, 1.2, m)
    pool_heat = 0.3 + 0.55 * smoothstep(1.15, 2.4, m) + 0.1 * swirl.at(t, shift)
    crack_heat = 0.35 + 0.65 * crack ** 2
    hot = np.maximum(crack, pool)
    heat = np.where(pool >= crack, pool_heat, crack_heat)
    return color + (hot_ramp(heat) - color) * hot[..., None]


def lava(flow):
    rng = np.random.default_rng(2771 if flow else 2717)
    if flow:
        # content repeats every 512 px along v (one block): 16 frames x 32 px = one period; plates drawn out along v
        step, kys, pv = 32, 2, 512
        scales = [Plates(rng, 4, 1, pv), Plates(rng, 10, 3, pv), Plates(rng, 22, 7, pv)]
        a = (1.0, 1.6)
        fields = [Field(rng, 6, 3, a, kys, 0), Field(rng, 5, 2.5, a, kys, 1), Field(rng, 5, 2.5, a, kys, 1),
                  Field(rng, 60, 20, a, kys, 0), Field(rng, 14, 6, a, kys, 0), Field(rng, 4, 2, a, kys, 0),
                  Field(rng, 26, 10, (1.0, 2.5), kys, 0)]
    else:
        step, pv = 0, N
        scales = [Plates(rng, 3, 3, pv), Plates(rng, 8, 8, pv), Plates(rng, 19, 19, pv)]
        # the crust stays put; its cracks wander and the pools churn slowly (small steps between frames)
        fields = [Field(rng, 4, 2, speed=1), Field(rng, 3, 1.5, speed=1), Field(rng, 3, 1.5, speed=1),
                  Field(rng, 60, 20, speed=0), Field(rng, 12, 5, speed=0), Field(rng, 4, 2, speed=0),
                  Field(rng, 22, 8, speed=1)]
    frames = 16
    return np.concatenate([lava_frame(scales, fields, i / frames, i * step, pv) for i in range(frames)], axis=0)


def save_lava(rgb, path):
    im = Image.fromarray(np.clip(np.round(rgb), 0, 255).astype(np.uint8), "RGB")
    # one shared palette for every frame (the crust's greys and the hot ramp: 256 colours leave no visible bands), no
    # dithering (its pattern would change from frame to frame and sparkle)
    im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(path, optimize=True)


# ---- pack -------------------------------------------------------------------------------------------------------------

def mcmeta(path, frametime):
    with open(path + ".mcmeta", "w", encoding="utf-8", newline="\n") as f:
        json.dump({"animation": {"frametime": frametime}}, f, indent=2)
        f.write("\n")


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(PACK, "pack.mcmeta"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"pack": {"description": {"translate": "blockland.graphics.textures_pack.description",
                                            "fallback": "BlockLand 材质"}, "pack_format": 64}}, f, indent=2,
                  ensure_ascii=False)
        f.write("\n")
    jobs = [
        # name, maker, saver, frametime (ticks per frame)
        ("water_still", lambda: water(False), save_water, 3),
        ("water_flow", lambda: water(True), save_water, 1),
        ("lava_still", lambda: lava(False), save_lava, 8),
        ("lava_flow", lambda: lava(True), save_lava, 3),
    ]
    for name, make, save, frametime in jobs:
        path = os.path.join(OUT, name + ".png")
        save(make(), path)
        mcmeta(path, frametime)
        print("%-12s %6.1f MB" % (name, os.path.getsize(path) / 1e6))


if __name__ == "__main__":
    main()
