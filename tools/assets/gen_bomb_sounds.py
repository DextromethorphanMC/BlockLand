"""Synthesised bomb sounds (VIEWMODEL_SPEC section 39) -> sounds/bomb/*.ogg + entries merged into sounds.json (locked).
Same DSP toolkit as gen_sounds.py / gen_equipment_sounds.py; every transient sits at the very start so it lines up
with its event or the server's cue, and the defuse loop cross-fades its end into its start."""
import tempfile

import numpy as np

from gen_equipment_sounds import _loop
from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def tone(t, freq, t0, dur, square=0.35, attack=0.002, release=0.008):
    """A piezo buzzer's note: a sine with odd harmonics (a softened square), gated from t0 for dur seconds."""
    tt = t - t0
    gate = np.clip(tt / attack, 0, 1) * np.clip((dur - tt) / release, 0, 1) * (tt >= 0)
    ph = 2 * np.pi * freq * tt
    return gate * (np.sin(ph) + square * np.sin(3 * ph) / 3 + 0.5 * square * np.sin(5 * ph) / 5)


def draw(seed):
    """The bundle taken out: webbing and film rustling, the tape crinkling, the case knocking on the hand."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.55)
    x = rustle(t, rng, 0.0, 0.30, 250, 3500, 0.7)
    x += band(rng.standard_normal(len(t)), 2500, 9000) * env_exp(t, 0.05, 0.002, 0.10) * 0.25      # film crinkle
    x += click(t, 0.24, rng, 300, 2500, 0.012, 0.6) + ring(t, [680, 1220], [0.03, 0.02], [0.18, 0.1], 0.24, rng)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -6.0)


def key(seed, freq):
    """A keypad press: the rubber key's click and the unit's short confirmation beep."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.16)
    x = click(t, 0.0, rng, 1500, 8000, 0.003, 0.6) + 0.55 * tone(t, freq, 0.004, 0.075)
    return finish(reverb(x, 0.18, 0.12, seed=seed), -8.0)


def planted(seed=301):
    """Armed: three quick rising chirps and a held confirmation tone."""
    t = t_axis(0.95)
    x = tone(t, 1900, 0.0, 0.07) + tone(t, 2300, 0.11, 0.07) + tone(t, 2750, 0.22, 0.07) + tone(t, 3100, 0.36, 0.38)
    return finish(reverb(x, 0.35, 0.22, seed=seed), -4.0)


def beep(seed=302):
    """The ticking: one short high beep, as from a piezo in the plastic case (a little ring of the case after it)."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.30)
    x = tone(t, 2650, 0.0, 0.11, 0.45) + ring(t, [5300], [0.02], [0.08], 0.0, rng)
    return finish(reverb(x, 0.30, 0.25, seed=seed), -3.0)


def beep_final(seed=303):
    """The last moments: beeps rushing together into a solid tone that rises a little until the charge fires."""
    t = t_axis(1.25)
    x = np.zeros_like(t)
    at, gap = 0.0, 0.11
    while at < 0.55:
        x += tone(t, 2650, at, 0.05, 0.45)
        at += gap
        gap = max(0.045, gap * 0.82)
    f = 2650 + 220 * np.clip((t - 0.55) / 0.7, 0, 1)
    ph = 2 * np.pi * np.cumsum(f) / SR
    gate = np.clip((t - 0.55) / 0.01, 0, 1) * (t >= 0.55)
    x += gate * (np.sin(ph) + 0.45 * np.sin(3 * ph) / 3)
    return finish(reverb(x, 0.30, 0.2, seed=seed), -3.0, fade=0.004)


def defuse_start(seed=304):
    """The defuser kneels over it: a kit opened, pliers taken up, a wire pulled clear of the tape."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.70)
    x = rustle(t, rng, 0.0, 0.35, 200, 3000, 0.6)
    x += click(t, 0.05, rng, 800, 6000, 0.006, 0.7) + ring(t, [2100, 3400], [0.05, 0.03], [0.25, 0.12], 0.05, rng)
    x += band(rng.standard_normal(len(t)), 1500, 7000) * env_exp(t, 0.08, 0.01, 0.30) * 0.35       # tape peeled
    x += click(t, 0.46, rng, 1200, 8000, 0.004, 0.5)
    return finish(reverb(x, 0.3, 0.2, seed=seed), -5.0)


def defuse(seed=305):
    """The defusing itself (repeats while it lasts): fingers and tools working the wires, small clicks, a scrape."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.25)
    x = rustle(t, rng, 0.0, 1.25, 300, 2800, 0.25)
    for _ in range(9):
        t0 = rng.uniform(0.0, 1.15)
        x += click(t, t0, rng, 1200, 8000, rng.uniform(0.002, 0.006), rng.uniform(0.2, 0.6))
    for _ in range(2):
        t0 = rng.uniform(0.1, 0.9)
        x += band(rng.standard_normal(len(t)), 2000, 7000) * env_exp(t, 0.05, 0.01, t0) * 0.25
    return finish(_loop(reverb(x, 0.25, 0.15, seed=seed), 0.25), -8.0)


def defused(seed=306):
    """Disarmed: a snip, then three falling tones and the unit powering down."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.2)
    snip = click(t, 0.0, rng, 1500, 9000, 0.004, 1.0) + ring(t, [3800, 5200], [0.03, 0.02], [0.3, 0.15], 0.0, rng)
    x = snip + tone(t, 2600, 0.12, 0.09) + tone(t, 2000, 0.25, 0.09) + tone(t, 1400, 0.38, 0.09)
    f = 1400 * np.exp(-np.clip(t - 0.52, 0, None) / 0.25)
    ph = 2 * np.pi * np.cumsum(f) / SR
    x += 0.5 * np.sin(ph) * env_exp(t, 0.25, 0.005, 0.52) * (t >= 0.52)
    return finish(reverb(x, 0.35, 0.25, seed=seed), -4.0)


def explode(seed, far=False):
    """A demolition charge: a splitting crack, a huge low boom sinking in pitch (felt more than heard), the blast's
    roar, debris and grit raining for a second and a half, and a long rolling tail off the surroundings. Far away only
    the low rumble arrives, later and longer."""
    rng = np.random.default_rng(seed)
    t = t_axis(5.5)
    crack = band(rng.standard_normal(len(t)), 500, 13000) * env_exp(t, 0.010, 0.0002) * 1.2
    f = 48 * np.exp(-t / 0.35) + 18
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.75, 0.003) * 2.6
    roar = band(rng.standard_normal(len(t)), 30, 700) * env_exp(t, 0.55, 0.004) * 1.8
    hiss = band(rng.standard_normal(len(t)), 900, 6000) * env_exp(t, 0.35, 0.01) * 0.35
    debris = np.zeros_like(t)
    for _ in range(60):
        t0 = rng.uniform(0.2, 2.2)
        debris += click(t, t0, rng, 700, 7000, rng.uniform(0.004, 0.03), rng.uniform(0.04, 0.22) * np.exp(-t0 / 1.4))
    rumble = band(rng.standard_normal(len(t)), 20, 160) * env_exp(t, 1.6, 0.25) * 0.9
    x = 0.8 * crack + boom + roar + hiss + debris + rumble
    if far:
        x = band(boom * 0.8 + roar + rumble * 1.6, 18, 520)
        x = np.concatenate([np.zeros(int(0.12 * SR)), x])[: len(t)]
        x = reverb(x, 2.6, 0.9, 1200, seed=seed + 3, predelay=0.05)
        return finish(x, -4.0, drive=1.8, fade=1.0)
    x = reverb(x, 2.0, 0.6, 2600, seed=seed + 3, predelay=0.025)
    return finish(x, -0.3, drive=3.2, fade=1.0)


def pickup(seed=307):
    """The bomb picked up off the ground: a scrape, webbing, the case in the hand."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = band(rng.standard_normal(len(t)), 300, 3000) * env_exp(t, 0.05, 0.004) * 0.5
    x += rustle(t, rng, 0.04, 0.25, 250, 3000, 0.6) + click(t, 0.20, rng, 400, 3000, 0.01, 0.5)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -6.0)


def drop(seed=308):
    """The bundle dropped: a heavy, dull plastic thud, a short rattle of the unit on its straps."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.55)
    thud = band(rng.standard_normal(len(t)), 60, 900) * env_exp(t, 0.05, 0.002) * 1.4
    body = ring(t, [180, 410, 960], [0.06, 0.04, 0.02], [0.6, 0.3, 0.12], 0.0, rng)
    rattle = sum(click(t, 0.05 + 0.03 * i, rng, 800, 5000, 0.004, 0.3 / (i + 1)) for i in range(4))
    return finish(reverb(thud + body + rattle, 0.3, 0.2, seed=seed), -3.0)


FILES = {
    "draw_1": lambda: draw(300), "draw_2": lambda: draw(310),
    "key_1": lambda: key(311, 1950), "key_2": lambda: key(312, 2080), "key_3": lambda: key(313, 2210),
    "planted": planted, "beep": beep, "beep_final": beep_final,
    "defuse_start": defuse_start, "defuse": defuse, "defused": defused,
    "explode_1": lambda: explode(320), "explode_2": lambda: explode(321), "explode_far": lambda: explode(322, far=True),
    "pickup": pickup, "drop": drop,
}
EVENTS = {   # sound event -> files
    "bomb.draw": ["draw_1", "draw_2"],
    "bomb.key": ["key_1", "key_2", "key_3"],
    "bomb.planted": ["planted"],
    "bomb.beep": ["beep"],
    "bomb.beep_final": ["beep_final"],
    "bomb.defuse_start": ["defuse_start"],
    "bomb.defuse": ["defuse"],
    "bomb.defused": ["defused"],
    "bomb.explode": ["explode_1", "explode_2"],
    "bomb.explode_far": ["explode_far"],
    "bomb.pickup": ["pickup"],
    "bomb.drop": ["drop"],
}
#: subtitles (lang keys subtitles.blockland.<event>): Chinese, English
SUBTITLES = {
    "bomb.draw": ("取出炸药包", "Bomb readied"), "bomb.key": ("输入密码", "Keypad beeps"),
    "bomb.planted": ("炸弹已安放", "Bomb armed"), "bomb.beep": ("炸弹嘀嗒", "Bomb beeps"),
    "bomb.beep_final": ("炸弹即将爆炸", "Bomb about to blow"), "bomb.defuse_start": ("开始拆弹", "Defusing starts"),
    "bomb.defuse": ("拆弹中", "Defusing"), "bomb.defused": ("炸弹已拆除", "Bomb defused"),
    "bomb.explode": ("炸弹爆炸", "Bomb explodes"), "bomb.explode_far": ("远处的爆炸", "Distant blast"),
    "bomb.pickup": ("拾起炸药包", "Bomb picked up"), "bomb.drop": ("炸药包落地", "Bomb dropped"),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_bomb_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "bomb", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {
        ev: {"sounds": [f"blockland:bomb/{f}" for f in files], "subtitle": f"subtitles.blockland.{ev}"}
        for ev, files in EVENTS.items()})
    print(f"bomb sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
