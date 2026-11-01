"""Synthesised vehicle sounds (VEHICLE_SPEC section 2, VIEWMODEL_SPEC section 57) -> sounds/vehicle/<id>_<key>.ogg and
the sound events `blockland:vehicle.<id>.<key>` merged into sounds.json (locked). Same DSP toolkit as gen_sounds.py.

Engines are seamless loops (the runtime pitches them by speed and turns their volume by the throttle): the
motorcycle's air-cooled single (a 25 Hz four-stroke thump with exhaust pops and intake hiss), the 4x4's V8 burble,
the outboard's buzzing two-stroke with its gearcase whine and the water slapping the hull, the tank's diesel rumble
under a turbocharger whine with the tracks' clatter. Each also has a starter (`start`), a horn (not the tank), a hit on
the hull (`hit`: a metal clang or the boat's fibreglass knock) and its destruction (`destroyed`); the tank its cannon
(`fire_cannon`, `reload_cannon`) and roof machine gun (`fire_mg`, `reload_mg`).

The aircraft's engines are exactly periodic instead of cross-faded: every tone a whole number of cycles in the loop and
the noise filtered circularly, so the loop has no seam at all — the fighter's turbofan (broadband roar, fan and
compressor whine, the shaft's buzz-saw orders, a low rumble) and the helicopter's four-blade rotor (a slap on every
blade pass at 20 Hz, uneven blade to blade, over the turboshaft's whine and the tail rotor's buzz). Their starters spool
up (the jet's ignition thump, the rotor's chop speeding up); their guns are single grains the runtime repeats at the
fire interval (the jet's rotary cannon, the helicopter's chin minigun); rockets leave with an ignition crack and a
falling motor roar."""
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


def _cband(x, lo=None, hi=None, order=2.0):
    """Band filter applied circularly (no padding): a periodic input stays periodic, so a loop built of it has no seam."""
    f = np.fft.rfftfreq(len(x), 1 / SR)
    g = np.ones_like(f)
    if lo:
        g *= 1 / np.sqrt(1 + (lo / np.maximum(f, 1e-3)) ** (2 * order))
    if hi:
        g *= 1 / np.sqrt(1 + (f / hi) ** (2 * order))
    return np.fft.irfft(np.fft.rfft(x) * g, len(x))


def _tone(t, f, dur):
    """A sine at the nearest frequency with a whole number of cycles in `dur` seconds (so it loops)."""
    f = max(1, round(f * dur)) / dur
    return np.sin(2 * np.pi * f * t)


def _norm(x):
    return x / (np.max(np.abs(x)) + 1e-9)


def turbofan(seed, dur=2.0, fan=1850.0, shaft=95.0, core=6200.0):
    """A jet engine at speed, exactly periodic over `dur`: broadband roar (40 Hz - 7 kHz), the fan's blade-pass whine and
    its second harmonic, the shaft's buzz-saw orders (many weak harmonics of the shaft rate), a thin high compressor
    whine and a low rumble, all breathing slowly (whole cycles per loop)."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur)
    roar = _norm(_cband(rng.standard_normal(len(t)), 40, 1200, 1.0) + 0.35 * _cband(rng.standard_normal(len(t)), 1800, 7000))
    rumble = _norm(_cband(rng.standard_normal(len(t)), 25, 160))
    breathe = 1 + 0.06 * _tone(t, 1 / dur, dur) + 0.03 * _tone(t, 3 / dur, dur)
    whine = 0.13 * _tone(t, fan, dur) + 0.05 * _tone(t, 2 * fan, dur) + 0.035 * _tone(t, core, dur)
    saw = _norm(_cband(sum(rng.uniform(0.2, 1.0) / k * _tone(t, shaft * k, dur) for k in range(1, 18)), 150, 3000))
    x = breathe * (0.9 * roar + 0.12 * saw) + 0.45 * rumble + whine * (1 + 0.04 * _tone(t, 2 / dur, dur))
    return finish(x, -4.0, drive=1.25, fade=0.0)


def rotor(seed, dur=2.0, blade_pass=20.0, blades=4, whine=2900.0, tail=100.0):
    """A helicopter's rotor at flight speed, exactly periodic over `dur` (blade passes a whole number of times): a slap on
    every blade pass (a band of noise over a thump at the pass rate, one blade of the four a little louder — the
    rotor's lopsided beat), broadband rotor wash pulsing with it, the turboshaft's whine and the tail rotor's buzz."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur)
    rate = round(blade_pass * dur) / dur
    phase = (t * rate) % 1.0
    weight = np.array([1.0, 0.82, 0.9, 0.76])[:blades][np.floor(t * rate).astype(int) % blades]
    pulse = weight * np.exp(-phase / 0.11) * np.clip(phase / 0.004, 0, 1)
    slap = _norm(_cband(rng.standard_normal(len(t)), 120, 1800))
    thump = sum(a * _tone(t, rate * h, dur) for h, a in ((1, 0.4), (2, 0.6), (3, 0.55), (4, 0.4), (5, 0.25), (6, 0.15)))
    chop = pulse * (1.1 * slap + 0.2) + 0.12 * thump * (0.5 + 0.5 * pulse)
    wash = _norm(_cband(rng.standard_normal(len(t)), 60, 1400, 1.0)) * (0.55 + 0.45 * np.exp(-phase / 0.3))
    tail_phase = (t * round(tail * dur) / dur) % 1.0
    buzz = _norm(_cband(np.exp(-tail_phase / 0.08) * _cband(rng.standard_normal(len(t)), 300, 3000), 250, 2600))
    turbine = 0.05 * _tone(t, whine, dur) + 0.02 * _tone(t, 2 * whine, dur)
    x = 1.0 * chop + 0.3 * wash + 0.22 * buzz + turbine
    return finish(x, -4.0, drive=1.8, fade=0.0)


def turbine_start(seed, idle, dur=3.4, ignite=1.0, f0=180.0, f1=1850.0):
    """A jet engine starting: the starter's whine rising from `f0` towards the fan's `f1`, the ignition thump (a low
    whump and a puff of noise) at `ignite`, the roar fading in after it and the running engine `idle` at the end."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur)
    k = np.clip(t / (dur * 0.9), 0, 1)
    f = f0 + (f1 - f0) * (1 - (1 - k) ** 2.2)
    x = (0.25 + 0.2 * k) * np.sin(2 * np.pi * np.cumsum(f) / SR) + 0.08 * np.sin(2 * np.pi * np.cumsum(2.02 * f) / SR)
    fw = 30 * np.exp(-np.maximum(t - ignite, 0) / 0.25) + 28
    whump = np.sin(2 * np.pi * np.cumsum(fw) / SR) * env_exp(t, 0.35, 0.01, ignite)
    puff = band(rng.standard_normal(len(t)), 60, 2500) * env_exp(t, 0.25, 0.02, ignite)
    roar = band(rng.standard_normal(len(t)), 50, 4000) * np.clip((t - ignite) / (dur - ignite), 0, 1) ** 1.5
    run = np.zeros_like(t)
    m = min(len(idle), len(t))
    run[:m] = idle[:m]
    run *= np.clip((t - (dur - 1.2)) / 1.0, 0, 1)
    x = x + 1.0 * whump + 0.5 * puff + 0.35 * roar + 0.9 * run
    x *= np.clip((dur - t) / 0.3, 0, 1)
    return finish(x, -3.0, drive=1.3, fade=0.05)


def rotor_start(seed, flying, dur=4.5, whine=(400.0, 2900.0), rate=(1.5, 20.0), blades=4):
    """A helicopter starting: the turboshaft spooling up (its whine rising) and the rotor speeding up — its blade slaps
    coming faster and harder, from `rate[0]` to `rate[1]` passes a second — into the running rotor `flying`."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur)
    k = np.clip(t / (dur * 0.85), 0, 1)
    fw = whine[0] + (whine[1] - whine[0]) * (1 - (1 - np.clip(t / (dur * 0.5), 0, 1)) ** 2)
    x = 0.18 * np.sin(2 * np.pi * np.cumsum(fw) / SR) * np.clip(t / 0.4, 0, 1)
    ph = np.cumsum(rate[0] + (rate[1] - rate[0]) * k ** 1.6) / SR
    frac = ph % 1.0
    weight = np.array([1.0, 0.82, 0.9, 0.76])[:blades][np.floor(ph).astype(int) % blades]
    pulse = weight * np.exp(-frac / 0.12) * np.clip(frac / 0.01, 0, 1)
    x += (0.15 + 0.85 * k) * pulse * (0.8 * _norm(band(rng.standard_normal(len(t)), 90, 2600)) + 0.3)
    run = np.zeros_like(t)
    m = min(len(flying), len(t))
    run[:m] = flying[:m]
    x += 0.9 * run * np.clip((t - (dur - 1.0)) / 0.8, 0, 1)
    x *= np.clip((dur - t) / 0.3, 0, 1)
    return finish(x, -3.0, drive=1.25, fade=0.05)


def hit_light(seed):
    """A round on a light airframe: a thin aluminium bang (higher, shorter partials than a hull) over a small thud."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    thud = band(rng.standard_normal(len(t)), 120, 1500) * env_exp(t, 0.03, 0.0004)
    x = 0.9 * thud + ring(t, [610, 1480, 2900, 4300, 6100], [0.25, 0.16, 0.1, 0.06, 0.04], [0.4, 0.3, 0.18, 0.12, 0.06],
                         0.0, rng)
    x += click(t, 0.0, rng, 2500, 11000, 0.003, 0.9)
    return finish(reverb(x, rt=0.5, mix=0.25, seed=seed), -2.0, drive=1.5)


def rotary_grain(seed, crack_lo=1200.0, thump=90.0, length=0.12, body=0.012):
    """One round of a rotary gun, short enough to repeat at its fire rate (the grains overlap into the gun's growl): a
    sharp crack, a brief bark and a small low thump, cut off quickly."""
    rng = np.random.default_rng(seed)
    t = t_axis(length)
    crack = band(rng.standard_normal(len(t)), crack_lo, 12000) * env_exp(t, 0.0012, 0.00004)
    bark = band(rng.standard_normal(len(t)), 220, 4500) * env_exp(t, body, 0.0002)
    f = thump * np.exp(-t / 0.03) + thump * 0.5
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.03, 0.0008)
    x = reverb(1.0 * crack + 1.1 * bark + 0.8 * boom, rt=0.25, mix=0.2, hi=4000, seed=seed + 5, predelay=0.004)
    x *= np.clip((length - t) / (length * 0.4), 0, 1)
    return finish(x, -1.0, drive=2.2, fade=0.01)


def rocket(seed, dur=0.9):
    """A rocket leaving its pod: the ignition crack and pop, then the motor's roar — a hiss whose brightness falls as it
    leaves (two bands crossing over) with a little flutter."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur)
    crack = band(rng.standard_normal(len(t)), 900, 11000) * env_exp(t, 0.004, 0.0001)
    f = 80 * np.exp(-t / 0.06) + 45
    pop = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.06, 0.0008)
    hi = band(rng.standard_normal(len(t)), 2500, 10000)
    mid = band(rng.standard_normal(len(t)), 250, 3000)
    k = np.clip(t / dur, 0, 1)
    flutter = 1 + 0.18 * np.sin(2 * np.pi * 31 * t + rng.random() * 6.28)
    motor = (hi * (1 - k) ** 2 + 0.9 * mid * (0.4 + 0.6 * k)) * env_exp(t, 0.35, 0.015) * flutter
    x = 1.0 * crack + 0.9 * pop + 1.3 * motor
    return finish(reverb(x, rt=1.2, mix=0.35, hi=3500, seed=seed + 2, predelay=0.015), -1.0, drive=1.8, fade=0.15)


def files():
    moto = engine(1201, 25.0, 2.0, 60, 3200, 0.010, jitter=0.05, pops=0.06, hiss=0.08)
    car = engine(1211, 42.0, 2.0, 40, 2400, 0.016, jitter=0.02, harmonics=(1.0, 0.6, 0.25, 0.2, 0.1), hiss=0.04)
    boat = engine(1221, 70.0, 2.0, 120, 5000, 0.006, jitter=0.02, harmonics=(1.0, 0.7, 0.5, 0.3), whine=(1650, 0.08),
                  hiss=0.06, slap=0.35)
    tank = engine(1231, 30.0, 2.0, 30, 1800, 0.022, jitter=0.03, harmonics=(1.0, 0.7, 0.4, 0.2), whine=(1250, 0.10),
                  clatter=(7.0, 0.45), hiss=0.05)
    made = {
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
    made.update(aircraft_files())
    return made


def aircraft_files():
    """The fighter's and the helicopter's sounds (their engine loops are exactly periodic, see the module doc)."""
    jet = turbofan(1301)
    heli = rotor(1311)
    return {
        "fighter_jet_engine": jet, "fighter_jet_start": turbine_start(1302, jet), "fighter_jet_hit": hit_light(1303),
        "fighter_jet_destroyed": explosion(1304, 1.4), "fighter_jet_fire_cannon": rotary_grain(1305),
        "fighter_jet_reload_cannon": reload(1306, [(0.05, "latch"), (0.25, "clank"), (0.55, "rattle"), (1.05, "clunk"),
                                                   (1.30, "latch")], 1.5),
        "fighter_jet_fire_rockets": rocket(1307),
        "fighter_jet_reload_rockets": reload(1308, [(0.05, "clank"), (0.45, "clunk"), (0.85, "clunk"), (1.25, "clank"),
                                                    (1.70, "latch")], 2.0),
        "helicopter_engine": heli, "helicopter_start": rotor_start(1312, heli), "helicopter_hit": hit_light(1313),
        "helicopter_destroyed": explosion(1314, 1.3),
        "helicopter_fire_chingun": rotary_grain(1315, crack_lo=1600.0, thump=120.0, length=0.08, body=0.008),
        "helicopter_reload_chingun": reload(1316, [(0.05, "latch"), (0.20, "rattle"), (0.60, "rattle"), (1.10, "clank"),
                                                   (1.50, "latch")], 1.8),
        "helicopter_fire_rockets": rocket(1317),
        "helicopter_reload_rockets": reload(1318, [(0.05, "clank"), (0.50, "clunk"), (0.90, "clunk"), (1.30, "clank"),
                                                   (1.75, "latch")], 2.0),
    }


EVENTS = {
    "motorcycle": ("engine", "start", "horn", "hit", "destroyed"),
    "offroad": ("engine", "start", "horn", "hit", "destroyed"),
    "speedboat": ("engine", "start", "horn", "hit", "destroyed"),
    "tank": ("engine", "start", "hit", "destroyed", "fire_cannon", "reload_cannon", "fire_mg", "reload_mg"),
    "fighter_jet": ("engine", "start", "hit", "destroyed", "fire_cannon", "reload_cannon", "fire_rockets", "reload_rockets"),
    "helicopter": ("engine", "start", "hit", "destroyed", "fire_chingun", "reload_chingun", "fire_rockets", "reload_rockets"),
}


def entries():
    out = {}
    for vid, keys in EVENTS.items():
        for key in keys:
            out[f"vehicle.{vid}.{key}"] = {"sounds": [f"blockland:vehicle/{vid}_{key}"],
                                          "subtitle": f"subtitles.blockland.vehicle.{vid}.{key}"}
    return out


def build(path, merge=True, aircraft_only=False):
    """Every vehicle's sounds (or, with `aircraft_only`, just the fighter's and the helicopter's) and their events."""
    tmp = tempfile.mkdtemp(prefix="blockland_vehicle_snd_")
    made = aircraft_files() if aircraft_only else files()
    for name, x in made.items():
        encode(x, path("sounds", "vehicle", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"vehicle sounds: {len(made)} files, {len(entries())} events")


if __name__ == "__main__":
    import build_all
    build(build_all.path)
