"""Synthesised equipment sounds (VIEWMODEL_SPEC section 36) -> sounds/equipment/*.ogg + entries merged into sounds.json
(locked). Same DSP toolkit as gen_sounds.py; every transient sits at the very start so it lines up with its event, and
the loops (smoke hiss, fire) cross-fade their ends into their starts so they repeat without a seam."""
import tempfile

import numpy as np

from gen_knife_sounds import whoosh
from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def _loop(x, fade=0.25):
    """Make x loop: its last `fade` seconds cross-faded into its first."""
    n = int(fade * SR)
    a, b = x[:n].copy(), x[-n:].copy()
    w = np.linspace(0, 1, n)
    x = x[:-n].copy()
    x[:n] = a * w + b * (1 - w)
    return x


def equip(seed=80):
    """Fabric and a light metal tap: the item taken from a pouch."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    x = rustle(t, rng, 0.0, 0.22, 300, 3000, 0.6)
    x += ring(t, [2300, 3700], [0.02, 0.012], [0.25, 0.15], 0.16, rng) + click(t, 0.16, rng, 1500, 7000, 0.003, 0.4)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -6.0)


def pin(seed=81):
    """The pin dragged out of the fuze: a scrape of wire on steel, the ring's jingle, the pin popping free."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    scrape = band(rng.standard_normal(len(t)), 2500, 9000) * np.clip((t - 0.0) / 0.01, 0, 1) * env_exp(t, 0.035)
    pop = click(t, 0.045, rng, 1800, 9000, 0.002, 1.2)
    jingle = ring(t, [4100, 5600, 7300], [0.06, 0.05, 0.03], [0.30, 0.22, 0.12], 0.05, rng)
    jingle += ring(t, [4300, 6100], [0.04, 0.03], [0.18, 0.10], 0.12, rng)
    return finish(reverb(0.5 * scrape + pop + jingle, 0.3, 0.2, seed=seed), -3.5)


def spoon(seed=82):
    """The spoon kicked off by the striker spring: a sprung 'ping', the striker snapping onto the primer."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.55)
    ping = ring(t, [1850, 2950, 4400], [0.10, 0.07, 0.04], [0.5, 0.35, 0.2], 0.0, rng)
    snap = click(t, 0.012, rng, 900, 6000, 0.004, 1.0)
    fizz = band(rng.standard_normal(len(t)), 3000, 9000) * env_exp(t, 0.12, 0.01, 0.02) * 0.08   # the fuze delay
    return finish(reverb(ping + snap + fizz, 0.3, 0.2, seed=seed), -4.0)


def throw(seed, heavy=True):
    """Arm swing: a big body whoosh (overhand) or a soft one (underhand)."""
    x = whoosh(seed, 0.42 if heavy else 0.34, 0.07 if heavy else 0.09, 220 if heavy else 260,
               1500 if heavy else 1100, 1.0, 0.0)
    rng = np.random.default_rng(seed + 9)
    t = t_axis(len(x) / SR)
    x = x + rustle(t, rng, 0.0, 0.15, 200, 2000, 0.25 * np.max(np.abs(x)))
    return finish(x, -5.0 if heavy else -8.0)


def bounce(seed):
    """A steel body knocking on stone and rolling a little."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    k = click(t, 0.0, rng, 300, 4500, 0.012, 1.2)
    body = ring(t, np.array([520, 910, 1730]) * rng.uniform(0.92, 1.08), [0.05, 0.035, 0.02], [0.55, 0.35, 0.2], 0.0, rng)
    roll = band(rng.standard_normal(len(t)), 200, 1600) * env_exp(t, 0.12, 0.02, 0.04) * 0.12
    return finish(reverb(k + body + roll, 0.35, 0.2, seed=seed), -3.0)


def frag_explode(seed=90, far=False):
    """High explosive: an instant crack, a deep chest-hitting boom with a pitch drop, debris and fragments raining,
    a long reverberant tail."""
    rng = np.random.default_rng(seed)
    t = t_axis(3.2)
    crack = band(rng.standard_normal(len(t)), 600, 12000) * env_exp(t, 0.006, 0.0002)
    f = 55 * np.exp(-t / 0.25) + 26
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.42, 0.002) * 2.0
    body = band(rng.standard_normal(len(t)), 40, 900) * env_exp(t, 0.30, 0.003) * 1.4
    debris = np.zeros_like(t)
    for _ in range(26):
        t0 = rng.uniform(0.15, 1.6)
        debris += click(t, t0, rng, 900, 6000, rng.uniform(0.004, 0.02), rng.uniform(0.05, 0.25))
    x = 0.7 * crack + boom + body + debris
    if far:
        x = band(x, 30, 900)
        x = np.concatenate([np.zeros(int(0.06 * SR)), x])
    x = reverb(x, 1.8 if far else 1.2, 0.85 if far else 0.55, 2400, seed=seed + 3, predelay=0.02)
    return finish(x, -0.5 if not far else -6.0, drive=2.6, fade=0.6)


def flash_bang(seed=91):
    """The flashbang: a brutally sharp, bright report (no boom of fragments), a white-noise blast and a ringing
    room."""
    rng = np.random.default_rng(seed)
    t = t_axis(2.4)
    crack = band(rng.standard_normal(len(t)), 900, 16000) * env_exp(t, 0.012, 0.0001) * 1.5
    f = 90 * np.exp(-t / 0.12) + 40
    thump = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.18, 0.001)
    blast = band(rng.standard_normal(len(t)), 300, 8000) * env_exp(t, 0.10, 0.001)
    x = reverb(crack + thump + blast, 1.4, 0.6, 5000, seed=seed + 1, predelay=0.01)
    return finish(x, -0.5, drive=3.0, fade=0.5)


def flash_ring(seed=92):
    """Tinnitus (played to the blinded player): a pure high tone with a faint beat, slowly fading out."""
    t = t_axis(3.4)
    tone = np.sin(2 * np.pi * 5200 * t) + 0.35 * np.sin(2 * np.pi * 5213 * t) + 0.12 * np.sin(2 * np.pi * 10400 * t)
    env = np.clip(t / 0.05, 0, 1) * np.exp(-t / 1.6)
    hum = band(np.random.default_rng(seed).standard_normal(len(t)), 80, 400) * 0.05 * env
    return finish(tone * env + hum, -9.0, fade=0.4)


def smoke_pop(seed=93):
    """The fuze pops the canister: a hard metallic pop and the first violent rush of smoke."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.2)
    pop = click(t, 0.0, rng, 400, 7000, 0.008, 1.3) + ring(t, [780, 1350], [0.04, 0.03], [0.4, 0.25], 0.0, rng)
    rush = band(rng.standard_normal(len(t)), 600, 9000) * np.clip(t / 0.04, 0, 1) * env_exp(t, 0.6, 0.0, 0.0) * 0.6
    return finish(reverb(pop + rush, 0.5, 0.25, seed=seed), -2.5, fade=0.3)


def smoke_hiss(seed=94):
    """The canister spewing smoke (loops): a broad hiss with sputtering, slowly wandering in colour."""
    rng = np.random.default_rng(seed)
    t = t_axis(4.25)
    n = rng.standard_normal(len(t))
    wob = 0.7 + 0.3 * np.sin(2 * np.pi * 0.7 * t + 1.0) * np.sin(2 * np.pi * 0.31 * t)
    x = band(n, 700, 7000) * wob + 0.4 * band(n, 150, 900)
    sput = band(rng.standard_normal(len(t)), 300, 2500) * (np.sin(2 * np.pi * 9.0 * t) > 0.6) * 0.15
    return finish(_loop(x + sput), -7.0, fade=0.0)


def molotov_light(seed=95):
    """A petrol lighter: the lid's metallic clink open (0), the wheel's rasp and the spark (0.16), the wick catching
    (0.18), then the rag taking fire with a soft whoomph (0.26)."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.0)
    lid = click(t, 0.0, rng, 2500, 10000, 0.003, 0.9) + ring(t, [3800, 6100], [0.03, 0.02], [0.35, 0.2], 0.0, rng)
    rasp = band(rng.standard_normal(len(t)), 2000, 9000) * env_exp(t, 0.03, 0.002, 0.16) * 0.7
    catch = band(rng.standard_normal(len(t)), 150, 1800) * env_exp(t, 0.12, 0.01, 0.18) * 0.4
    whoomph = band(rng.standard_normal(len(t)), 80, 1200) * env_exp(t, 0.30, 0.05, 0.26) * 1.1
    return finish(reverb(lid + rasp + catch + whoomph, 0.3, 0.15, seed=seed), -3.0, fade=0.2)


def molotov_shatter(seed=96):
    """The bottle bursting: glass breaking into many shards, the fuel splashing and lighting with a roar."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.8)
    hit = click(t, 0.0, rng, 800, 12000, 0.006, 1.4)
    shards = np.zeros_like(t)
    for _ in range(40):
        t0 = rng.uniform(0.0, 0.5) ** 1.5
        f = rng.uniform(2500, 9000)
        shards += ring(t, [f, f * 1.47], [rng.uniform(0.01, 0.05), 0.01], [rng.uniform(0.05, 0.25), 0.05], t0, rng)
    splash = band(rng.standard_normal(len(t)), 300, 4000) * env_exp(t, 0.15, 0.005, 0.01) * 0.6
    roar = band(rng.standard_normal(len(t)), 60, 1500) * np.clip((t - 0.06) / 0.10, 0, 1) * env_exp(t, 0.7, 0.0) * 1.3
    x = reverb(hit + shards + splash + roar, 0.8, 0.35, 5000, seed=seed + 2)
    return finish(x, -1.0, drive=1.6, fade=0.4)


def fire_loop(seed=97):
    """Burning fuel (loops): a low roar with crackles and pops."""
    rng = np.random.default_rng(seed)
    t = t_axis(4.25)
    roar = band(rng.standard_normal(len(t)), 50, 900) * (0.8 + 0.2 * np.sin(2 * np.pi * 0.5 * t))
    crackle = np.zeros_like(t)
    for _ in range(90):
        crackle += click(t, rng.uniform(0, 4.0), rng, 1500, 9000, rng.uniform(0.001, 0.006), rng.uniform(0.1, 0.6))
    return finish(_loop(roar + 0.5 * crackle), -6.0, fade=0.0)


def knife_throw(seed):
    """A small blade cutting the air: a short, high whoosh with a whistle."""
    return finish(whoosh(seed, 0.24, 0.045, 900, 4200, 1.0, 0.35), -7.0)


def knife_hit_flesh(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.30)
    thud = band(rng.standard_normal(len(t)), 90, 1400) * env_exp(t, 0.04, 0.001)
    wet = band(rng.standard_normal(len(t)), 600, 3500) * env_exp(t, 0.06, 0.003, 0.004) * 0.4
    return finish(thud + wet, -3.0)


def knife_hit_block(seed):
    """The point biting into wood or stone: a hard thunk and the blade's twang."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.70)
    thunk = click(t, 0.0, rng, 300, 5000, 0.010, 1.3)
    twang = ring(t, np.array([980, 2650, 4400]) * rng.uniform(0.95, 1.05), [0.20, 0.08, 0.04], [0.55, 0.25, 0.1], 0.003,
                 rng) * (1 + 0.4 * np.sin(2 * np.pi * 18 * t))
    return finish(reverb(thunk + twang, 0.3, 0.15, seed=seed), -3.0)


def adrenaline_cap(seed=98):
    """The safety cap pulled off: a plastic pop."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.25)
    x = click(t, 0.0, rng, 700, 6000, 0.006, 1.0) + ring(t, [1500, 2600], [0.015, 0.01], [0.3, 0.15], 0.0, rng)
    return finish(x, -5.0)


def adrenaline_inject(seed=99):
    """The auto-injector firing: a loud spring click, the drive spring's buzz, the hiss of the dose."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    clack = click(t, 0.0, rng, 900, 8000, 0.004, 1.3) + ring(t, [2100, 3300], [0.03, 0.02], [0.4, 0.2], 0.0, rng)
    buzz = np.sign(np.sin(2 * np.pi * 140 * t)) * env_exp(t, 0.05, 0.002, 0.005) * 0.12
    hiss = band(rng.standard_normal(len(t)), 2500, 9000) * np.clip((t - 0.02) / 0.05, 0, 1) * env_exp(t, 0.35) * 0.25
    return finish(reverb(clack + band(buzz, 100, 3000) + hiss, 0.2, 0.1, seed=seed), -3.0, fade=0.2)


def flashlight_switch(seed, on=True):
    """The tail switch: a crisp two-stage click (the boot squashing, the contact snapping), a hair higher going on."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.18)
    pitch = 1.12 if on else 0.92
    x = click(t, 0.0, rng, 1200 * pitch, 9000, 0.003, 0.9) + click(t, 0.018, rng, 2200 * pitch, 11000, 0.002, 0.7)
    x += ring(t, [3100 * pitch, 4700 * pitch], [0.012, 0.008], [0.22, 0.12], 0.018, rng)
    x += band(rng.standard_normal(len(t)), 300, 1200) * env_exp(t, 0.02) * 0.08      # the thumb on the rubber
    return finish(x, -9.0)


def heartbeat(seed=100):
    """One heartbeat (lub-dub), deep and close: the runtime repeats it faster while the drug runs."""
    t = t_axis(0.75)

    def beat(t0, f, amp):
        tt = np.maximum(t - t0, 0)
        return amp * np.sin(2 * np.pi * f * tt * (1 - 0.3 * tt / 0.12)) * env_exp(t, 0.045, 0.004, t0)
    x = beat(0.0, 52, 1.0) + beat(0.17, 44, 0.75)
    x = band(x, 25, 300)
    return finish(x, -2.0, drive=1.4, fade=0.1)


FILES = {
    "equip_1": lambda: equip(80), "equip_2": lambda: equip(180),
    "pin_1": lambda: pin(81), "pin_2": lambda: pin(181),
    "spoon": lambda: spoon(82),
    "throw_1": lambda: throw(83), "throw_2": lambda: throw(183), "lob": lambda: throw(84, heavy=False),
    "bounce_1": lambda: bounce(85), "bounce_2": lambda: bounce(185), "bounce_3": lambda: bounce(285),
    "frag_explode_1": lambda: frag_explode(90), "frag_explode_2": lambda: frag_explode(190),
    "frag_explode_far": lambda: frag_explode(290, far=True),
    "flash_bang": lambda: flash_bang(91), "flash_ring": lambda: flash_ring(92),
    "smoke_pop": lambda: smoke_pop(93), "smoke_hiss": lambda: smoke_hiss(94),
    "molotov_light": lambda: molotov_light(95), "molotov_shatter": lambda: molotov_shatter(96),
    "fire_loop": lambda: fire_loop(97),
    "knife_throw_1": lambda: knife_throw(101), "knife_throw_2": lambda: knife_throw(102),
    "knife_hit_flesh_1": lambda: knife_hit_flesh(103), "knife_hit_flesh_2": lambda: knife_hit_flesh(104),
    "knife_hit_block_1": lambda: knife_hit_block(105), "knife_hit_block_2": lambda: knife_hit_block(106),
    "adrenaline_cap": lambda: adrenaline_cap(98), "adrenaline_inject": lambda: adrenaline_inject(99),
    "heartbeat": lambda: heartbeat(100),
    "flashlight_on": lambda: flashlight_switch(107, True), "flashlight_off": lambda: flashlight_switch(108, False),
}
EVENTS = {   # sound event -> files
    "equipment.equip": ["equip_1", "equip_2"],
    "equipment.pin": ["pin_1", "pin_2"],
    "equipment.spoon": ["spoon"],
    "equipment.throw": ["throw_1", "throw_2"],
    "equipment.lob": ["lob"],
    "equipment.bounce": ["bounce_1", "bounce_2", "bounce_3"],
    "equipment.frag_explode": ["frag_explode_1", "frag_explode_2"],
    "equipment.frag_explode_far": ["frag_explode_far"],
    "equipment.flash_bang": ["flash_bang"],
    "equipment.flash_ring": ["flash_ring"],
    "equipment.smoke_pop": ["smoke_pop"],
    "equipment.smoke_hiss": ["smoke_hiss"],
    "equipment.molotov_light": ["molotov_light"],
    "equipment.molotov_shatter": ["molotov_shatter"],
    "equipment.fire_loop": ["fire_loop"],
    "equipment.knife_throw": ["knife_throw_1", "knife_throw_2"],
    "equipment.knife_hit_flesh": ["knife_hit_flesh_1", "knife_hit_flesh_2"],
    "equipment.knife_hit_block": ["knife_hit_block_1", "knife_hit_block_2"],
    "equipment.adrenaline_cap": ["adrenaline_cap"],
    "equipment.adrenaline_inject": ["adrenaline_inject"],
    "equipment.heartbeat": ["heartbeat"],
    "equipment.flashlight_on": ["flashlight_on"],
    "equipment.flashlight_off": ["flashlight_off"],
}
#: subtitles (lang keys subtitles.blockland.<event>): Chinese, English
SUBTITLES = {
    "equipment.equip": ("装备取出", "Equipment readied"), "equipment.pin": ("拔出保险销", "Pin pulled"),
    "equipment.spoon": ("握片弹开", "Spoon flies off"), "equipment.throw": ("投掷", "Throw"),
    "equipment.lob": ("低抛", "Lob"), "equipment.bounce": ("投掷物弹跳", "Grenade bounces"),
    "equipment.frag_explode": ("手雷爆炸", "Grenade explodes"),
    "equipment.frag_explode_far": ("远处爆炸", "Distant explosion"),
    "equipment.flash_bang": ("闪光弹爆响", "Flashbang bangs"), "equipment.flash_ring": ("耳鸣", "Ears ringing"),
    "equipment.smoke_pop": ("烟雾弹喷发", "Smoke grenade pops"), "equipment.smoke_hiss": ("烟雾嘶嘶作响", "Smoke hisses"),
    "equipment.molotov_light": ("点燃燃烧瓶", "Molotov lit"), "equipment.molotov_shatter": ("燃烧瓶碎裂", "Molotov shatters"),
    "equipment.fire_loop": ("火焰燃烧", "Fire burns"), "equipment.knife_throw": ("飞刀掷出", "Knife thrown"),
    "equipment.knife_hit_flesh": ("飞刀命中", "Knife hits"), "equipment.knife_hit_block": ("飞刀扎入", "Knife sticks"),
    "equipment.adrenaline_cap": ("拔下针帽", "Cap off"), "equipment.adrenaline_inject": ("注射", "Injection"),
    "equipment.heartbeat": ("心跳", "Heartbeat"),
    "equipment.flashlight_on": ("手电筒打开", "Flashlight clicks on"),
    "equipment.flashlight_off": ("手电筒关闭", "Flashlight clicks off"),
}

#: the files and events of each item (build(keys=...): only those are written)
ITEM_FILES = {"flashlight": ("flashlight_on", "flashlight_off")}


def build(path, keys=None):
    """Every equipment sound, or only those of the items `keys` (ITEM_FILES)."""
    tmp = tempfile.mkdtemp(prefix="blockland_equipment_snd_")
    names = None if keys is None else {f for k in keys for f in ITEM_FILES.get(k, ())}
    files = {n: fn for n, fn in FILES.items() if names is None or n in names}
    events = {ev: fs for ev, fs in EVENTS.items() if names is None or set(fs) <= names}
    for name, fn in files.items():
        encode(fn(), path("sounds", "equipment", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {
        ev: {"sounds": [f"blockland:equipment/{f}" for f in fs], "subtitle": f"subtitles.blockland.{ev}"}
        for ev, fs in events.items()})
    print(f"equipment sounds: {len(files)} files, events {', '.join(events)}")
