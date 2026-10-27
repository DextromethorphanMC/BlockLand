"""Synthesised vehicle sounds (VEHICLE_SPEC section 2, VIEWMODEL_SPEC section 57) -> sounds/vehicle/<id>_<key>.ogg and
the sound events `blockland:vehicle.<id>.<key>` merged into sounds.json (locked). Same DSP toolkit as gen_sounds.py.

Engines are seamless loops (the runtime pitches them by speed and turns their volume by the throttle): the
motorcycle's air-cooled single (a 25 Hz four-stroke thump with exhaust pops and intake hiss), the 4x4's V8 burble,
the outboard's buzzing two-stroke with its gearcase whine and the water slapping the hull, the tank's diesel rumble
under a turbocharger whine with the tracks' clatter. Each also has a starter (`start`), a horn (not the tank), a hit on
the hull (`hit`: a metal clang or the boat's fibreglass knock) and its destruction (`destroyed`); the tank its cannon
(`fire_cannon`, `reload_cannon`) and roof machine gun (`fire_mg`, `reload_mg`)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def _loop(x, fade):
    """Makes x loop seamlessly: its tail of `fade` seconds cross-faded into its head (the result is that much shorter)."""
    n = int(fade * SR)
    head, body, tail = x[:n], x[n:-n] if n else x, x[-n:]
    w = np.linspace(0, 1, n)
    start = head * w + tail * (1 - w)
    return np.concatenate([start, body])


def engine(seed, f_fire, dur, lo, hi, decay, jitter=0.03, pops=0.0, harmonics=(1.0, 0.5, 0.3, 0.15), whine=None,
           clatter=None, hiss=0.05, slap=0.0):
    """A running engine: firing pulses at f_fire Hz (each a decaying band of noise, timing jittered), the tone of the
    firing frequency's harmonics, optional exhaust pops, a whine (freq, level), track clatter (rate, level), intake
    hiss and water slap."""
    rng = np.random.default_rng(seed)
    total = dur + 0.5
    t = t_axis(total)
    x = np.zeros_like(t)
    k = 0.0
    while k < total:
        i = int(k * SR)
        n = min(len(t) - i, int(decay * 6 * SR))
        if n > 0:
            tt = np.arange(n) / SR
            burst = rng.standard_normal(n) * np.exp(-tt / decay) * (0.8 + 0.4 * rng.random())
            x[i:i + n] += burst
            if pops and rng.random() < pops:
                m = min(len(t) - i, int(0.02 * SR))
                x[i:i + m] += 2.5 * rng.standard_normal(m) * np.exp(-np.arange(m) / SR / 0.004)
        k += (1 + jitter * rng.standard_normal()) / f_fire
    x = band(x, lo, hi)
    tone = sum(a * np.sin(2 * np.pi * f_fire * (h + 1) * t + rng.random() * 6.28) for h, a in enumerate(harmonics))
    x = x / (np.max(np.abs(x)) + 1e-9) + 0.5 * tone / max(1e-9, sum(harmonics))
    x += hiss * band(rng.standard_normal(len(t)), 1500, 7000)
    if whine:
        f, level = whine
        wob = 1 + 0.004 * np.sin(2 * np.pi * 0.7 * t)
        x += level * np.sin(2 * np.pi * np.cumsum(f * wob) / SR) + 0.3 * level * np.sin(2 * np.pi * np.cumsum(2 * f * wob) / SR)
    if clatter:
        rate, level = clatter
        k = 0.0
        while k < total:
            x += level * click(t, k, rng, 600, 4500, 0.012, 0.6 + 0.4 * rng.random())
            k += (1 + 0.1 * rng.standard_normal()) / rate
    if slap:
        s = band(rng.standard_normal(len(t)), 120, 1800)
        x += slap * s * (0.5 + 0.5 * np.sin(2 * np.pi * 1.3 * t) ** 2)
    x = _loop(x[:int((dur + 0.25) * SR)], 0.25)
    return finish(x, -4.0, drive=1.3, fade=0.0)


def starter(seed, run, crank_hz=9.0, crank_dur=0.9, motor=(700, 1400)):
    """The starter grinding the engine over (pulses at the cranking speed under the motor's whine), then it catches:
    the running engine `run` fading in."""
    rng = np.random.default_rng(seed)
    t = t_axis(crank_dur + 1.4)
    x = np.zeros_like(t)
    k = 0.05
    while k < crank_dur:
        x += click(t, k, rng, 150, 2500, 0.03, 0.8)
        k += 1 / crank_hz
    f = np.interp(t, [0, crank_dur], motor)
    x += 0.25 * np.sin(2 * np.pi * np.cumsum(f) / SR) * (t < crank_dur)
    caught = np.zeros_like(t)
    n = min(len(run), len(t) - int(crank_dur * SR))
    caught[int(crank_dur * SR):int(crank_dur * SR) + n] = run[:n] * np.minimum(1, np.arange(n) / (0.15 * SR))
    x = x + 1.2 * caught
    x *= np.clip((t[-1] - t) / 0.4, 0, 1)
    return finish(x, -3.0, drive=1.2, fade=0.05)


def horn(freqs, dur, buzz=0.6):
    t = t_axis(dur + 0.2)
    x = sum(np.sign(np.sin(2 * np.pi * f * t)) * buzz + np.sin(2 * np.pi * f * t) * (1 - buzz) for f in freqs)
    x = band(x, 200, 3500) * np.clip(t / 0.03, 0, 1) * np.clip((dur - t) / 0.05, 0, 1)
    return finish(reverb(x, rt=0.5, mix=0.25, hi=3000), -3.0, drive=1.5)


def hit(seed, metal=True):
    rng = np.random.default_rng(seed)
    t = t_axis(1.2)
    thud = band(rng.standard_normal(len(t)), 60, 900) * env_exp(t, 0.05, 0.0005)
    x = 1.2 * thud
    if metal:
        x += ring(t, [420, 1130, 2380, 3550], [0.35, 0.22, 0.12, 0.08], [0.5, 0.35, 0.2, 0.12], 0.0, rng)
        x += click(t, 0.0, rng, 2000, 9000, 0.004, 0.8)
    else:
        x += band(rng.standard_normal(len(t)), 300, 2500) * env_exp(t, 0.03, 0.0005) * 0.7
    return finish(reverb(x, rt=0.6, mix=0.3, seed=seed), -2.0, drive=1.4)


def explosion(seed, size=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(3.5)
    f = 60 * np.exp(-t / 0.25) + 25
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.6 * size, 0.002)
    blast = band(rng.standard_normal(len(t)), 40, 5000) * env_exp(t, 0.35 * size, 0.001)
    debris = np.zeros_like(t)
    for _ in range(40):
        t0 = 0.2 + rng.random() * 2.2
        debris += click(t, t0, rng, 800, 7000, 0.01 + 0.03 * rng.random(), 0.2 + 0.4 * rng.random())
    x = 1.4 * boom + 1.0 * blast + 0.5 * debris
    return finish(reverb(x, rt=2.0, mix=0.5, hi=2500, seed=seed + 1), -1.0, drive=2.2, fade=0.4)


def cannon(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(4.0)
    crack = band(rng.standard_normal(len(t)), 600, 9000) * env_exp(t, 0.006, 0.0002)
    f = 55 * np.exp(-t / 0.3) + 22
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.5, 0.0008)
    body = band(rng.standard_normal(len(t)), 40, 1500) * env_exp(t, 0.25, 0.0005)
    mech = click(t, 0.12, rng, 400, 3500, 0.03, 0.5) + ring(t, [180, 410], [0.4, 0.25], [0.3, 0.15], 0.12, rng)
    x = 1.1 * crack + 1.5 * boom + 1.2 * body + 0.5 * mech
    return finish(reverb(x, rt=2.6, mix=0.55, hi=2200, seed=seed + 3, predelay=0.02), -0.5, drive=2.8, fade=0.6)


def heavy_mg(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.7)
    crack = band(rng.standard_normal(len(t)), 1500 * pitch, 11000) * env_exp(t, 0.0012, 0.00005)
    bark = band(rng.standard_normal(len(t)), 160 * pitch, 4000) * env_exp(t, 0.02, 0.0003)
    f = 70 * pitch * np.exp(-t / 0.05) + 35
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.07, 0.001)
    mech = click(t, 0.03, rng, 700, 6000, 0.005, 0.35) + click(t, 0.075, rng, 600, 5000, 0.006, 0.4)
    x = crack + 1.2 * bark + boom + mech
    return finish(reverb(x, rt=0.8, mix=0.4, hi=3000, seed=seed + 9, predelay=0.01), -1.0, drive=2.4, fade=0.15)


def reload(seed, events, dur):
    """A reload as a list of (time, kind): `clank` heavy metal, `clunk` a round seating, `rattle` a belt, `latch`."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur)
    x = np.zeros_like(t)
    for t0, kind in events:
        if kind == "clank":
            x += click(t, t0, rng, 300, 4000, 0.03, 1.0) + ring(t, [260, 690, 1500], [0.25, 0.15, 0.08],
                                                                  [0.4, 0.25, 0.12], t0, rng)
        elif kind == "clunk":
            x += click(t, t0, rng, 120, 2000, 0.05, 1.0)
        elif kind == "rattle":
            for k in range(14):
                x += click(t, t0 + k * 0.025 + 0.01 * rng.random(), rng, 1500, 8000, 0.006, 0.35)
        else:
            x += click(t, t0, rng, 1500, 9000, 0.008, 0.7)
    return finish(reverb(x, rt=0.4, mix=0.25, seed=seed), -3.0, drive=1.3, fade=0.05)


def files():
    moto = engine(1201, 25.0, 2.0, 60, 3200, 0.010, jitter=0.05, pops=0.06, hiss=0.08)
    car = engine(1211, 42.0, 2.0, 40, 2400, 0.016, jitter=0.02, harmonics=(1.0, 0.6, 0.25, 0.2, 0.1), hiss=0.04)
    boat = engine(1221, 70.0, 2.0, 120, 5000, 0.006, jitter=0.02, harmonics=(1.0, 0.7, 0.5, 0.3), whine=(1650, 0.08),
                  hiss=0.06, slap=0.35)
    tank = engine(1231, 30.0, 2.0, 30, 1800, 0.022, jitter=0.03, harmonics=(1.0, 0.7, 0.4, 0.2), whine=(1250, 0.10),
                  clatter=(7.0, 0.45), hiss=0.05)
    return {
        "motorcycle_engine": moto, "motorcycle_start": starter(1202, moto, 11.0, 0.7, (900, 1700)),
        "motorcycle_horn": horn([420, 505], 0.6), "motorcycle_hit": hit(1203), "motorcycle_destroyed": explosion(1204, 0.8),
        "offroad_engine": car, "offroad_start": starter(1212, car, 8.0, 1.0), "offroad_horn": horn([350, 440], 0.7, 0.4),
        "offroad_hit": hit(1213), "offroad_destroyed": explosion(1214, 1.0),
        "speedboat_engine": boat, "speedboat_start": starter(1222, boat, 10.0, 0.8, (800, 1600)),
        "speedboat_horn": horn([220, 277], 1.0, 0.5), "speedboat_hit": hit(1223, metal=False),
        "speedboat_destroyed": explosion(1224, 0.9),
        "tank_engine": tank, "tank_start": starter(1232, tank, 6.0, 1.4, (400, 900)), "tank_hit": hit(1233),
        "tank_destroyed": explosion(1234, 1.5), "tank_fire_cannon": cannon(1235),
        "tank_reload_cannon": reload(1236, [(0.05, "clank"), (0.55, "clunk"), (0.95, "clunk"), (1.35, "clank"),
                                            (1.55, "latch")], 2.0),
        "tank_fire_mg": heavy_mg(1237), "tank_reload_mg": reload(1238, [(0.05, "latch"), (0.30, "clank"),
                                                                        (0.65, "rattle"), (1.20, "clank"), (1.45, "latch")], 1.8),
    }


EVENTS = {
    "motorcycle": ("engine", "start", "horn", "hit", "destroyed"),
    "offroad": ("engine", "start", "horn", "hit", "destroyed"),
    "speedboat": ("engine", "start", "horn", "hit", "destroyed"),
    "tank": ("engine", "start", "hit", "destroyed", "fire_cannon", "reload_cannon", "fire_mg", "reload_mg"),
}


def entries():
    out = {}
    for vid, keys in EVENTS.items():
        for key in keys:
            out[f"vehicle.{vid}.{key}"] = {"sounds": [f"blockland:vehicle/{vid}_{key}"],
                                          "subtitle": f"subtitles.blockland.vehicle.{vid}.{key}"}
    return out


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_vehicle_snd_")
    made = files()
    for name, x in made.items():
        encode(x, path("sounds", "vehicle", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"vehicle sounds: {len(made)} files, {len(entries())} events")


if __name__ == "__main__":
    import build_all
    build(build_all.path)
