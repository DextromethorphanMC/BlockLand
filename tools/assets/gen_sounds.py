"""Synthesised gun sounds (VIEWMODEL_SPEC section 10) -> sounds/gun/*.ogg (mono 44.1 kHz Vorbis) + sounds.json."""
import json
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100


# ----------------------------------------------------------------------------------------- dsp
def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def band(x, lo=None, hi=None, order=2.0):
    """Zero-phase FFT band filter with smooth (Butterworth-like) skirts."""
    n = len(x)
    f = np.fft.rfftfreq(n * 2, 1 / SR)
    X = np.fft.rfft(np.concatenate([x, np.zeros(n)]))
    g = np.ones_like(f)
    if lo:
        g *= 1 / np.sqrt(1 + (lo / np.maximum(f, 1e-3)) ** (2 * order))
    if hi:
        g *= 1 / np.sqrt(1 + (f / hi) ** (2 * order))
    return np.fft.irfft(X * g)[:n]


def env_exp(t, tau, attack=0.0005, t0=0.0):
    tt = t - t0
    e = np.where(tt < 0, 0.0, np.exp(-np.maximum(tt, 0) / tau))
    if attack > 0:
        e *= np.clip(tt / attack, 0, 1)
    return e


def ring(t, freqs, taus, amps, t0=0.0, rng=None):
    out = np.zeros_like(t)
    tt = t - t0
    for f, tau, a in zip(freqs, taus, amps):
        ph = rng.uniform(0, 2 * np.pi) if rng is not None else 0.0
        out += a * np.sin(2 * np.pi * f * tt + ph) * env_exp(t, tau, 0.0002, t0)
    return out


def click(t, t0, rng, lo=2000, hi=9000, tau=0.004, amp=1.0):
    n = rng.standard_normal(len(t))
    return amp * band(n, lo, hi) * env_exp(t, tau, 0.0002, t0)


def reverb(x, rt=0.4, mix=0.25, hi=4000, seed=0, predelay=0.006):
    rng = np.random.default_rng(seed)
    tl = t_axis(rt * 1.5)
    ir = rng.standard_normal(len(tl)) * np.exp(-6.9 * tl / rt)
    ir = band(ir, 120, hi)
    ir[: int(predelay * SR)] = 0
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-12
    n = len(x) + len(ir)
    wet = np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n), n)[: len(x)]
    return x + mix * wet / (np.max(np.abs(wet)) + 1e-12) * np.max(np.abs(x))


def finish(x, peak_db=-1.0, drive=1.0, fade=0.03):
    if drive != 1.0:
        x = np.tanh(x * drive) / np.tanh(drive)
    nf = int(fade * SR)
    if nf and len(x) > nf:
        x[-nf:] *= np.linspace(1, 0, nf) ** 2
    x = x - np.mean(x)
    return x / (np.max(np.abs(x)) + 1e-12) * 10 ** (peak_db / 20)


def pad(x, dur):
    out = np.zeros(int(dur * SR))
    out[: min(len(x), len(out))] = x[: len(out)]
    return out


# ------------------------------------------------------------------------------------- sounds
def gunshot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.65)
    crack = band(rng.standard_normal(len(t)), 1800 * pitch, 14000) * env_exp(t, 0.0016, 0.0001)
    crack[:3] += np.array([0.9, -0.6, 0.3])
    body = band(rng.standard_normal(len(t)), 160 * pitch, 4200) * env_exp(t, 0.022, 0.0006)
    body2 = band(rng.standard_normal(len(t)), 300, 1600) * env_exp(t, 0.060, 0.002, 0.004) * 0.35
    f = 105 * pitch * np.exp(-t / 0.05) + 42 * pitch
    thump = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.055, 0.0015) * 1.1
    mech = ring(t, [2150, 3400, 5300], [0.008, 0.006, 0.004], [0.10, 0.07, 0.05], 0.042, rng)
    mech += ring(t, [1700, 2900, 4600], [0.012, 0.008, 0.005], [0.12, 0.08, 0.05], 0.088, rng)
    dry = 0.9 * crack + 0.8 * body + body2 + thump + mech
    slap = np.zeros_like(dry)
    d = int(0.072 * SR)
    slap[d:] = band(dry[:-d], 200, 2500) * 0.18
    wet = reverb(dry + slap, rt=0.55, mix=0.45, hi=3200, seed=seed + 100, predelay=0.012)
    return finish(wet, -1.0, drive=2.2, fade=0.12)


def gunshot_far(seed):
    x = gunshot(seed, 0.95)
    x = band(x, 60, 900)
    x = np.concatenate([np.zeros(int(0.02 * SR)), x, np.zeros(int(0.9 * SR))])
    x = reverb(x, rt=1.3, mix=0.9, hi=1200, seed=seed + 7, predelay=0.03)
    return finish(x, -9.0, drive=1.3, fade=0.4)


def dry_fire(seed=40):
    rng = np.random.default_rng(seed)
    t = t_axis(0.16)
    x = ring(t, [3100, 6200, 8300], [0.004, 0.003, 0.002], [0.6, 0.35, 0.2], 0.002, rng)
    x += click(t, 0.002, rng, 1000, 3500, 0.010, 0.5)
    x += ring(t, [900, 1500], [0.012, 0.008], [0.25, 0.15], 0.004, rng)
    return finish(reverb(x, 0.2, 0.2, seed=seed), -3.0)


def mag_release(seed=41):
    rng = np.random.default_rng(seed)
    t = t_axis(0.13)
    x = click(t, 0.003, rng, 2000, 7000, 0.005, 1.0) + ring(t, [1850, 3300], [0.010, 0.006], [0.5, 0.3], 0.003, rng)
    return finish(reverb(x, 0.2, 0.2, seed=seed), -4.0)


def mag_out(seed=42):
    rng = np.random.default_rng(seed)
    t = t_axis(0.36)
    scrape_env = np.clip(t / 0.03, 0, 1) * np.exp(-np.maximum(t - 0.03, 0) / 0.05) * (t < 0.16)
    flutter = 1 + 0.5 * np.sin(2 * np.pi * 60 * t) * rng.uniform(0.6, 1.0)
    x = band(rng.standard_normal(len(t)), 800, 4500) * scrape_env * flutter * 0.6
    x += ring(t, [1250, 2300, 3900], [0.014, 0.010, 0.006], [0.5, 0.35, 0.2], 0.135, rng)
    x += click(t, 0.135, rng, 600, 3000, 0.012, 0.5)
    return finish(reverb(x, 0.25, 0.25, seed=seed), -3.0)


def mag_in(seed=43):
    rng = np.random.default_rng(seed)
    t = t_axis(0.32)
    scrape = band(rng.standard_normal(len(t)), 900, 5000) * np.clip(t / 0.02, 0, 1) * (t < 0.07) * 0.35
    latch = ring(t, [1200, 2600, 4400, 6100], [0.014, 0.010, 0.007, 0.004], [0.7, 0.5, 0.3, 0.15], 0.075, rng)
    latch += click(t, 0.075, rng, 1500, 8000, 0.004, 0.8)
    thud = np.sin(2 * np.pi * 180 * t) * env_exp(t, 0.022, 0.001, 0.075) * 0.6
    return finish(reverb(scrape + latch + thud, 0.25, 0.25, seed=seed), -1.5, drive=1.4)


def slide_back(seed=44):
    rng = np.random.default_rng(seed)
    t = t_axis(0.26)
    e = np.clip(t / 0.015, 0, 1) * (t < 0.11) * (0.6 + 0.4 * t / 0.11)
    x = band(rng.standard_normal(len(t)), 1500, 7500) * e * 0.5
    x += ring(t, [2700, 4100, 5900], [0.030, 0.020, 0.012], [0.45, 0.30, 0.2], 0.105, rng)
    x += click(t, 0.105, rng, 2000, 9000, 0.004, 0.6)
    return finish(reverb(x, 0.25, 0.22, seed=seed), -2.5)


def slide_forward(seed=45):
    rng = np.random.default_rng(seed)
    t = t_axis(0.38)
    x = click(t, 0.002, rng, 1500, 12000, 0.003, 1.2)
    x += ring(t, [1600, 2900, 4300, 6800], [0.040, 0.028, 0.018, 0.010], [0.8, 0.6, 0.4, 0.25], 0.002, rng)
    x += np.sin(2 * np.pi * 140 * t) * env_exp(t, 0.030, 0.001, 0.002) * 0.7
    x += band(rng.standard_normal(len(t)), 2000, 6000) * np.clip(t / 0.01, 0, 1) * (t < 0.02) * 0.2
    return finish(reverb(x, 0.3, 0.3, seed=seed), -1.0, drive=1.6)


def slide_release(seed=46):
    rng = np.random.default_rng(seed)
    t = t_axis(0.14)
    x = ring(t, [3500, 5800, 7600], [0.006, 0.004, 0.003], [0.7, 0.4, 0.2], 0.002, rng)
    x += click(t, 0.002, rng, 2500, 9000, 0.003, 0.7)
    return finish(reverb(x, 0.2, 0.2, seed=seed), -4.0)


def rustle(t, rng, t0, dur, lo=200, hi=2600, amp=1.0):
    e = np.clip((t - t0) / (dur * 0.3), 0, 1) * np.exp(-np.maximum(t - t0 - dur * 0.3, 0) / (dur * 0.35))
    e *= (t >= t0)
    flutter = band(rng.standard_normal(len(t)), None, 30)
    flutter = 0.6 + 0.8 * np.abs(flutter) / (np.max(np.abs(flutter)) + 1e-9)
    return amp * band(rng.standard_normal(len(t)), lo, hi) * e * flutter


def inspect_move(seed=47):
    rng = np.random.default_rng(seed)
    t = t_axis(0.48)
    x = rustle(t, rng, 0.0, 0.40)
    x += ring(t, [2300, 3700], [0.010, 0.006], [0.10, 0.06], 0.20, rng)
    return finish(x, -8.0, fade=0.08)


def equip(seed=48):
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = rustle(t, rng, 0.0, 0.28, 250, 3000)
    x += ring(t, [2100, 3300, 5200], [0.012, 0.008, 0.005], [0.4, 0.25, 0.15], 0.20, rng)
    x += click(t, 0.20, rng, 1500, 7000, 0.004, 0.4)
    return finish(reverb(x, 0.25, 0.2, seed=seed), -5.0, fade=0.06)


def hammer(seed=49):
    rng = np.random.default_rng(seed)
    t = t_axis(0.16)
    x = ring(t, [2200, 3900, 6100], [0.007, 0.005, 0.003], [0.6, 0.4, 0.25], 0.002, rng)
    x += ring(t, [2500, 4300, 6600], [0.008, 0.005, 0.003], [0.8, 0.5, 0.3], 0.028, rng)
    x += click(t, 0.028, rng, 2000, 9000, 0.003, 0.6)
    return finish(reverb(x, 0.2, 0.2, seed=seed), -3.0)


def shell(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.65)
    f0 = rng.uniform(3300, 4300)
    ratios = np.array([1.0, 1.47, 2.09, 2.71, 3.4])
    x = np.zeros_like(t)
    tt, amp = 0.004, 1.0
    for k in range(5):
        x += ring(t, f0 * ratios * rng.uniform(0.98, 1.02, 5), [0.09, 0.07, 0.05, 0.03, 0.02],
                  amp * np.array([1.0, 0.7, 0.5, 0.35, 0.2]), tt, rng)
        x += click(t, tt, rng, 3000, 12000, 0.002, 0.4 * amp)
        tt += rng.uniform(0.07, 0.12) * (0.75 ** k)
        amp *= rng.uniform(0.45, 0.65)
    return finish(reverb(x, 0.3, 0.2, hi=8000, seed=seed), -6.0, fade=0.1)


def hitmarker(seed=60):
    rng = np.random.default_rng(seed)
    t = t_axis(0.09)
    x = ring(t, [2800, 5600], [0.007, 0.004], [1.0, 0.5], 0.001, rng) + click(t, 0.001, rng, 3000, 12000, 0.002, 0.5)
    return finish(x, -3.0, fade=0.02)


def headshot(seed=61):
    rng = np.random.default_rng(seed)
    t = t_axis(0.36)
    x = ring(t, [1900, 3800, 5700, 7300], [0.07, 0.05, 0.03, 0.02], [1.0, 0.6, 0.4, 0.2], 0.001, rng)
    x += click(t, 0.001, rng, 2500, 14000, 0.003, 0.8)
    return finish(reverb(x, 0.2, 0.15, hi=9000, seed=seed), -2.0, fade=0.08)


def kill(seed=62):
    rng = np.random.default_rng(seed)
    t = t_axis(0.36)
    f = 150 * np.exp(-t / 0.08) + 70
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.07, 0.002) * 1.0
    x += ring(t, [2200, 4400], [0.020, 0.010], [0.5, 0.25], 0.012, rng)
    x += band(rng.standard_normal(len(t)), 400, 3000) * env_exp(t, 0.05, 0.02, 0.0) * 0.15
    return finish(x, -2.0, drive=1.5, fade=0.08)


def attach(seed=63):
    rng = np.random.default_rng(seed)
    t = t_axis(0.26)
    x = band(rng.standard_normal(len(t)), 1200, 6000) * np.clip(t / 0.01, 0, 1) * (t < 0.09) * 0.3
    x += ring(t, [1700, 3100, 5000], [0.012, 0.008, 0.005], [0.7, 0.45, 0.25], 0.10, rng)
    x += click(t, 0.10, rng, 1500, 9000, 0.004, 0.7)
    return finish(reverb(x, 0.2, 0.2, seed=seed), -3.0)


FILES = {
    "p9_fire_1": lambda: gunshot(1, 1.00), "p9_fire_2": lambda: gunshot(2, 0.96), "p9_fire_3": lambda: gunshot(3, 1.04),
    "p9_fire_far": lambda: gunshot_far(4), "dry_fire": dry_fire, "mag_release": mag_release, "mag_out": mag_out,
    "mag_in": mag_in, "slide_back": slide_back, "slide_forward": slide_forward, "slide_release": slide_release,
    "inspect_move": inspect_move, "equip": equip, "hammer": hammer, "shell_1": lambda: shell(51),
    "shell_2": lambda: shell(52), "shell_3": lambda: shell(53), "hitmarker": hitmarker, "headshot": headshot,
    "kill": kill, "attach": attach,
}
EVENTS = {   # sound event (without namespace) -> files
    "gun.p9.fire": ["p9_fire_1", "p9_fire_2", "p9_fire_3"], "gun.p9.fire_far": ["p9_fire_far"],
    "gun.dry_fire": ["dry_fire"], "gun.mag_release": ["mag_release"], "gun.mag_out": ["mag_out"],
    "gun.mag_in": ["mag_in"], "gun.slide_back": ["slide_back"], "gun.slide_forward": ["slide_forward"],
    "gun.slide_release": ["slide_release"], "gun.inspect_move": ["inspect_move"], "gun.equip": ["equip"],
    "gun.hammer": ["hammer"], "gun.shell": ["shell_1", "shell_2", "shell_3"], "gun.hitmarker": ["hitmarker"],
    "gun.headshot": ["headshot"], "gun.kill": ["kill"], "gun.attach": ["attach"],
}


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def subtitle(event):
    key = event[len("gun."):].replace("p9.", "")
    return f"subtitles.blockland.gun.{key}"


def build(path, keep_wav_dir=None):
    tmp = tempfile.mkdtemp(prefix="blockland_snd_")
    for name, fn in FILES.items():
        x = fn()
        wav = os.path.join(keep_wav_dir or tmp, f"{name}.wav")
        write_wav(wav, x)
        out = path("sounds", "gun", f"{name}.ogg")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5",
                        "-ac", "1", "-ar", "44100", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                        "-map_metadata", "-1", out], check=True)
    sounds = {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": subtitle(ev)}
              for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), sounds)
    print(f"sounds: {len(FILES)} files, {len(EVENTS)} events")


def encode(x, out, wav_dir):
    """Write a 16-bit WAV and encode it to mono 44.1 kHz Vorbis (bit-exact, so builds are reproducible)."""
    wav = os.path.join(wav_dir, os.path.basename(out)[:-4] + ".wav")
    write_wav(wav, x)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5",
                    "-ac", "1", "-ar", "44100", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                    "-map_metadata", "-1", out], check=True)


def merge_sounds_json(path, entries):
    """Update sounds.json in place: replace our keys, keep every other entry (gun / knife / others)."""
    data = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    data.update(entries)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
