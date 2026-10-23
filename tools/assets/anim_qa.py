"""Motion QA of baked viewmodel clips (VIEWMODEL_SPEC section 31): curve plots and numbers that show what a contact
sheet cannot - where the weapon stops dead in the middle of a move (hitches), freezes in a hold, pops, how hard it
moves and how far the camera goes.

python -I tools/assets/anim_qa.py <weapon.glb | p9 | ak47 | ...> [clip ...] [--root gun] [--no-plot]
    -> prints a table per clip, writes tools/assets/out/qa_<file>_<clip>.png (curves of the root node, its parts and
       the camera, events marked)

Numbers (root = the weapon root node, in viewmodel space):
  peak v / w     peak linear (m/s) and angular (deg/s) speed of the root
  hitches        the root comes (nearly) to rest between two moves in the same direction: a mechanical stop-start
                 (turnarounds - a move reversing - are fine). Listed with their times.
  freeze         longest stretch (s) with the root practically still (< 4 mm/s and < 1.5 deg/s) inside a one-shot clip:
                 holds should drift a little (moving holds)
  end / start    distance (mm, deg) of the root from the idle's first frame at the clip's end / start (one-shots must
                 start and end on the rest pose; the draw only ends there)
  launch         speed in the first 25 ms as a share of the clip's peak: a one-shot that starts at full speed jumps
                 (only shots and a draw may launch hard)
  cam            largest camera pitch / yaw / roll (deg)
  jerk           the sharpest change of acceleration (impacts should sit on events)"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np                                     # noqa: E402
from PIL import Image, ImageDraw                       # noqa: E402

from glbread import GLTF                               # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MODELS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "blockland", "models")
OUT = os.path.join(HERE, "out")
RATE = 240.0
LOOPS = {"idle", "walk", "sprint", "tac_sprint"}
PARTS = ("slide", "bolt", "pump", "bolt_handle", "mag", "slide_L", "mag_L", "hammer", "trigger")
COLORS = [(230, 80, 70), (80, 190, 90), (80, 130, 240), (230, 180, 60), (190, 90, 220), (60, 200, 210)]


def find(name):
    if os.path.exists(name):
        return name
    for sub in ("gun", "knife"):
        p = os.path.join(MODELS, sub, name + ".glb")
        if os.path.exists(p):
            return p
    raise SystemExit(f"no weapon file {name}")


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz])


def qconj(q):
    return np.array([-q[0], -q[1], -q[2], q[3]])


def qlog(q):
    q = q if q[3] >= 0 else -q
    s = np.linalg.norm(q[:3])
    return q[:3] * 2 if s < 1e-9 else q[:3] / s * 2 * np.arctan2(s, q[3])


def euler(q):
    """yaw (Y), pitch (X), roll (Z) degrees of R = Ry Rx Rz (the apply_gun convention)."""
    x, y, z, w = q / np.linalg.norm(q)
    m = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    pitch = np.degrees(np.arcsin(np.clip(-m[1, 2], -1, 1)))
    return np.array([np.degrees(np.arctan2(m[0, 2], m[2, 2])), pitch, np.degrees(np.arctan2(m[1, 0], m[1, 1]))])


class Sampled:
    """One clip sampled at RATE: local TRS of chosen nodes (rest values where the clip leaves a node alone)."""

    def __init__(self, g, anim, nodes):
        self.dur = g.duration(anim)
        self.ts = np.unique(np.concatenate([np.arange(0, self.dur, 1 / RATE), [self.dur]]))
        self.t, self.r = {}, {}
        for name in nodes:
            i = g.names[name]
            t0, r0, _ = g.local_trs(i)
            self.t[name], self.r[name] = [], []
        for t in self.ts:
            s = g.sample(anim, float(t))
            for name in nodes:
                i = g.names[name]
                t0, r0, _ = g.local_trs(i)
                self.t[name].append(s.get(i, {}).get("translation", t0))
                self.r[name].append(s.get(i, {}).get("rotation", r0))
        for name in nodes:
            self.t[name] = np.array(self.t[name], float)
            rs = np.array(self.r[name], float)
            for k in range(1, len(rs)):
                if np.dot(rs[k], rs[k - 1]) < 0:
                    rs[k] = -rs[k]
            self.r[name] = rs
        self.events = [(e["time"], e["name"]) for e in anim.get("extras", {}).get("events", [])]


def speeds(ts, pos, rot):
    dt = np.diff(ts)
    v = np.diff(pos, axis=0) / dt[:, None]
    w = np.array([qlog(qmul(rot[k + 1], qconj(rot[k]))) for k in range(len(rot) - 1)]) / dt[:, None]
    mid = 0.5 * (ts[1:] + ts[:-1])
    # LINEAR keys at 60-120 Hz give stair-step speeds at RATE: smooth over a 60 Hz frame
    k = max(1, int(RATE / 60))
    box = np.ones(k) / k
    smooth = lambda a: np.stack([np.convolve(a[:, c], box, mode="same") for c in range(a.shape[1])], axis=1)
    return mid, smooth(v), smooth(np.degrees(w))


def hitches(mid, v, w):
    """Local minima of the combined speed between two moves in the same direction."""
    lin, ang = np.linalg.norm(v, axis=1), np.linalg.norm(w, axis=1)
    s = lin / 0.25 + ang / 120.0                        # ~0.25 m/s and ~120 deg/s count as the same "brisk" move
    six = np.concatenate([v / 0.25, w / 120.0], axis=1)
    found = []
    win = int(0.12 * RATE)
    for k in range(win, len(s) - win):
        if s[k] > s[k - 1] or s[k] > s[k + 1]:
            continue
        before, after = s[k - win:k].max(), s[k + 1:k + 1 + win].max()
        if min(before, after) < 0.6 or s[k] > 0.12 * min(before, after):
            continue
        kb = k - win + int(np.argmax(s[k - win:k]))
        ka = k + 1 + int(np.argmax(s[k + 1:k + 1 + win]))
        a, b = six[kb], six[ka]
        cos = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))
        if cos > 0.55:
            found.append((float(mid[k]), cos))
    return found


def longest_freeze(mid, v, w, dur, margin=0.06):
    lin, ang = np.linalg.norm(v, axis=1), np.linalg.norm(w, axis=1)
    still = (lin < 0.004) & (ang < 1.5) & (mid > margin) & (mid < dur - margin)
    best, run, start, at = 0.0, 0, 0.0, 0.0
    for k, st in enumerate(still):
        if st:
            if run == 0:
                start = mid[k]
            run += 1
            if mid[k] - start > best:
                best, at = mid[k] - start, start
        else:
            run = 0
    return best, at


def pose_gap(pa, ra, pb, rb):
    return float(np.linalg.norm(pa - pb) * 1000), float(np.degrees(np.linalg.norm(qlog(qmul(ra, qconj(rb))))))


def analyse(path, clips=None, root=None, plot=True):
    g = GLTF(path)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    root = root or next(n for n in ("gun", "knife") if n in g.names)
    parts = [p for p in PARTS if p in g.names]
    nodes = [root] + parts + (["camera"] if "camera" in g.names else [])
    idle = Sampled(g, anims["idle"], nodes) if "idle" in anims else None
    rows = []
    for name in clips or list(anims):
        if name not in anims:
            print(f"  (no clip {name})")
            continue
        s = Sampled(g, anims[name], nodes)
        mid, v, w = speeds(s.ts, s.t[root], s.r[root])
        hit = hitches(mid, v, w)
        loop = name in LOOPS
        freeze = (0.0, 0.0) if loop else longest_freeze(mid, v, w, s.dur)
        acc = np.diff(v, axis=0) / np.diff(mid)[:, None]
        jerk = np.linalg.norm(np.diff(acc, axis=0), axis=1) / np.diff(mid)[1:]
        kj = int(np.argmax(jerk)) if len(jerk) else 0
        cam = np.zeros(3)
        if "camera" in s.r:
            cam = np.abs(np.array([euler(q) for q in s.r["camera"]])).max(0)
        end = start = (0.0, 0.0)
        if idle is not None and not loop:
            end = pose_gap(s.t[root][-1], s.r[root][-1], idle.t[root][0], idle.r[root][0])
            start = pose_gap(s.t[root][0], s.r[root][0], idle.t[root][0], idle.r[root][0])
        sp = np.linalg.norm(v, axis=1) / 0.25 + np.linalg.norm(w, axis=1) / 120.0
        launch = 0.0 if loop or sp.max() < 1e-9 else float(sp[mid < 0.025].max(initial=0.0) / sp.max())
        row = dict(clip=name, dur=s.dur, v=float(np.linalg.norm(v, axis=1).max()), launch=launch,
                   w=float(np.linalg.norm(w, axis=1).max()), hitches=hit, freeze=freeze, end=end, start=start,
                   cam=cam, jerk_t=float(mid[1:-1][kj]) if len(jerk) else 0.0)
        rows.append(row)
        if plot:
            draw_plot(path, name, s, root, parts, mid, v, w, hit, idle)
    print_table(os.path.basename(path), rows)
    return rows


def print_table(label, rows):
    print(f"== {label}")
    print(f"  {'clip':18s} {'dur':>5s} {'peak v':>7s} {'peak w':>7s} {'freeze':>12s} {'start mm/deg':>13s} "
          f"{'end mm/deg':>12s} {'cam p/y/r':>15s} {'launch':>6s}  hitches")
    for r in rows:
        fr = f"{r['freeze'][0]:.2f}@{r['freeze'][1]:.2f}" if r["freeze"][0] > 0 else "-"
        hs = ", ".join(f"{t:.2f}" for t, _ in r["hitches"]) or "-"
        print(f"  {r['clip']:18s} {r['dur']:5.2f} {r['v']:7.2f} {r['w']:7.0f} {fr:>12s} "
              f"{r['start'][0]:6.1f}/{r['start'][1]:4.1f}  {r['end'][0]:6.1f}/{r['end'][1]:4.1f} "
              f"{r['cam'][1]:4.1f}/{r['cam'][0]:4.1f}/{r['cam'][2]:4.1f} {r['launch']:6.0%}  {hs}")


# ------------------------------------------------------------------------------------------- plot
W, PANEL, PAD = 1500, 190, 44


def draw_plot(path, name, s, root, parts, mid, v, w, hit, idle):
    os.makedirs(OUT, exist_ok=True)
    panels = 5
    img = Image.new("RGB", (W, PAD + panels * (PANEL + PAD)), (24, 26, 30))
    d = ImageDraw.Draw(img)
    rest_t = idle.t[root][0] if idle is not None else s.t[root][0]
    rest_r = idle.r[root][0] if idle is not None else s.r[root][0]
    dur = s.dur
    x0, x1 = 70, W - 20

    def X(t):
        return x0 + (x1 - x0) * t / max(dur, 1e-6)

    def panel(k, title, ts, series, unit):
        top = PAD + k * (PANEL + PAD)
        d.rectangle([x0, top, x1, top + PANEL], outline=(70, 74, 82))
        allv = np.concatenate([np.asarray(y, float) for _, y in series]) if series else np.zeros(1)
        lo, hi = float(min(allv.min(), 0)), float(max(allv.max(), 0))
        if hi - lo < 1e-9:
            hi, lo = lo + 1, lo - 1
        span = hi - lo
        lo, hi = lo - 0.06 * span, hi + 0.06 * span

        def Y(y):
            return top + PANEL - (y - lo) / (hi - lo) * PANEL

        d.line([x0, Y(0), x1, Y(0)], fill=(60, 64, 70))
        for te, ne in s.events:
            d.line([X(te), top, X(te), top + PANEL], fill=(90, 90, 60))
            if k == 0:
                d.text((X(te) + 2, top - 26 + 10 * (s.events.index((te, ne)) % 2)), ne, fill=(220, 210, 120))
        for th, _ in hit:
            if k == 2:
                d.line([X(th), top, X(th), top + PANEL], fill=(240, 60, 60), width=2)
        d.text((6, top + 2), title, fill=(220, 220, 220))
        d.text((6, top + 16), f"{hi:.3g}", fill=(150, 150, 150))
        d.text((6, top + PANEL - 12), f"{lo:.3g} {unit}", fill=(150, 150, 150))
        for c, (label, y) in enumerate(series):
            pts = [(X(float(t)), Y(float(val))) for t, val in zip(ts, y)]
            d.line(pts, fill=COLORS[c % len(COLORS)], width=2)
            d.text((x1 - 260 + 64 * c, top + 4), label, fill=COLORS[c % len(COLORS)])

    off = (s.t[root] - rest_t) * 1000
    panel(0, f"{root} offset", s.ts, [("x", off[:, 0]), ("y", off[:, 1]), ("z", off[:, 2])], "mm")
    eul = np.array([euler(qmul(qconj(rest_r), q)) for q in s.r[root]])
    panel(1, f"{root} turn", s.ts, [("yaw", eul[:, 0]), ("pitch", eul[:, 1]), ("roll", eul[:, 2])], "deg")
    panel(2, "speed", mid, [("m/s x100", np.linalg.norm(v, axis=1) * 100), ("deg/s", np.linalg.norm(w, axis=1))], "")
    ser = []
    for p in parts:
        if p in ("hammer", "trigger"):
            continue
        dp = (s.t[p] - s.t[p][0]) * 1000
        if np.abs(dp).max() > 0.05:
            ser.append((p, np.linalg.norm(dp, axis=1) * np.sign(dp[:, 2] + 1e-12) if p.startswith("mag")
                        else dp[:, 2]))
    panel(3, "parts (mm)", s.ts, ser[:6], "mm")
    if "camera" in s.r:
        ce = np.array([euler(q) for q in s.r["camera"]])
        panel(4, "camera", s.ts, [("yaw", ce[:, 0]), ("pitch", ce[:, 1]), ("roll", ce[:, 2])], "deg")
    d.text((x0, 6), f"{os.path.basename(path)}  {name}  {dur:.2f}s   (hitches red)", fill=(255, 255, 255))
    img.save(os.path.join(OUT, f"qa_{os.path.splitext(os.path.basename(path))[0]}_{name}.png"))


def main(argv):
    root = None
    if "--root" in argv:
        i = argv.index("--root")
        root = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    plot = "--no-plot" not in argv
    argv = [a for a in argv if a != "--no-plot"]
    if not argv:
        raise SystemExit(__doc__)
    analyse(find(argv[0]), argv[1:] or None, root, plot)


if __name__ == "__main__":
    main(sys.argv[1:])
