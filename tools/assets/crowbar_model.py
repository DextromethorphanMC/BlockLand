"""Crowbar geometry (VIEWMODEL_SPEC section 41). Design frame (metres): the bar runs along Z, the straight shaft from
the chisel end (+Z) towards the head (-Z); the goose-neck bends the bar round towards +Y (the claw side) and back, so
the claw points back along +Z above the shaft. The bar is swept along its centre line: a hex section (19 mm across the
flats, two flats facing +-Y) flattened into the chisel at one end and the split nail claw at the other. Original
design after a common 600 mm gooseneck wrecking bar: the chisel end bent 12 degrees towards the claw side, the
goose-neck round a 40 mm centre-line radius through 172 degrees, a 25 mm wide claw with a V nail slot, the paint
ground off the claw and chisel, a friction-tape wrap where the right hand holds it."""
import numpy as np

import shapes as sh
from meshkit import Mesh

HEX_APOTHEM = 0.0095           # half the width across the flats
SHAFT_Z0 = 0.035               # where the straight shaft meets the chisel bend
SHAFT_LEN = 0.505              # straight shaft towards the head
NECK_R = 0.040                 # goose-neck centre-line radius
NECK_DEG = 165.0               # goose-neck bend
CLAW_STRAIGHT = 0.012
CLAW_LEN = 0.040               # flattened claw (along the centre line)
CLAW_CURL = (0.15, 7.0)        # the claw keeps curling a little inwards: radius, degrees
CLAW_W = 0.0125                # half width of the claw
SLOT_LEN = 0.030               # V nail slot from the claw's tip
SLOT_W = 0.0036                # half width of the slot at the tip
CHISEL_BEND = (0.060, 12.0)    # radius, degrees (towards +Y)
CHISEL_STRAIGHT = 0.030
CHISEL_W = 0.0115              # half width of the chisel blade
TAPE_Z = (0.022, -0.103)       # tape wrap from just above the chisel bend up the shaft
TAPE_T = 0.0009                # tape thickness over the bar (two overlapping layers at the seam)
TAPE_PITCH = 0.019             # the helical seam's pitch
GRIP = np.array([0.0, 0.0, -0.040])     # right-hand grip point = knife root (middle of the tape wrap)
SAMPLES = 30                   # vertices per bar section (5 per hex corner, see _thetas)


def _smooth(a, b, x):
    u = np.clip((x - a) / (b - a), 0, 1)
    return u * u * (3 - 2 * u)


# --------------------------------------------------------------------------------------------- centre line
def _turtle(start, heading_deg, plan):
    """Centre line in the Y-Z plane. heading 0 = -Z, 90 = +Y. plan: [(length, radius or None, turn sign)], radius None
    = straight. -> (points (n, 3), headings in degrees (n,)) sampled every ~3 mm on straights and ~5.5 deg on arcs."""
    p = np.asarray(start, float).copy()
    a = float(heading_deg)
    pts, hd = [p.copy()], [a]
    for length, radius, sign in plan:
        if radius is None:
            n = max(1, int(np.ceil(length / 0.030)))
            for _ in range(n):
                d = np.array([0.0, np.sin(np.radians(a)), -np.cos(np.radians(a))])
                p = p + d * length / n
                pts.append(p.copy())
                hd.append(a)
        else:
            sweep = np.degrees(length / radius)
            n = max(2, int(np.ceil(sweep / 5.5)))
            for _ in range(n):
                a0 = a
                a = a + sign * sweep / n
                # exact chord of the arc between the two headings
                c = 2 * radius * np.sin(np.radians(sweep / n) / 2)
                mid = np.radians(0.5 * (a0 + a))
                p = p + c * np.array([0.0, np.sin(mid), -np.cos(mid)])
                pts.append(p.copy())
                hd.append(a)
    return np.array(pts), np.array(hd)


def centre_line():
    """(points, headings, arc length s from the chisel tip) along the whole bar, chisel tip -> claw tip."""
    q0 = np.array([0.0, 0.0, SHAFT_Z0])
    # chisel side: from the shaft start heading +Z (180), turning towards +Y (heading decreasing towards 168)
    r, deg = CHISEL_BEND
    cp, ch = _turtle(q0, 180.0, [(np.radians(deg) * r, r, -1), (CHISEL_STRAIGHT, None, 1)])
    # head side: heading -Z (0), the goose-neck turning towards +Y and on round
    neck = np.radians(NECK_DEG) * NECK_R
    rc, dc = CLAW_CURL
    hp, hh = _turtle(q0, 0.0, [(SHAFT_LEN, None, 1), (neck, NECK_R, 1), (CLAW_STRAIGHT, None, 1),
                               (np.radians(dc) * rc, rc, 1)])
    hp, hh = _densify(hp, hh, CLAW_LEN + CLAW_STRAIGHT + 0.006)
    # chisel points reversed (tip first); its headings turned to point towards the head
    pts = np.concatenate([cp[::-1], hp[1:]])
    hd = np.concatenate([ch[::-1] - 180.0, hh[1:]])
    s = np.concatenate([[0.0], np.cumsum(np.linalg.norm(np.diff(pts, axis=0), axis=1))])
    return pts, hd, s


def _densify(pts, hd, tail):
    """Extra samples (every ~2.5 mm) over the last `tail` metres (the claw: its section changes fast)."""
    s = np.concatenate([[0.0], np.cumsum(np.linalg.norm(np.diff(pts, axis=0), axis=1))])
    keep = s < s[-1] - tail
    s_new = np.unique(np.concatenate([s[keep], np.linspace(s[-1] - tail, s[-1], int(tail / 0.0025) + 1)]))
    out_p = np.stack([np.interp(s_new, s, pts[:, k]) for k in range(3)], 1)
    return out_p, np.interp(s_new, s, hd)


def frames(hd):
    """Tangent (towards the head), section axes u (= +X) and v (in the bend plane, towards the claw side)."""
    a = np.radians(hd)
    T = np.stack([np.zeros_like(a), np.sin(a), -np.cos(a)], 1)
    N = np.stack([np.zeros_like(a), np.cos(a), np.sin(a)], 1)
    B = np.tile([1.0, 0.0, 0.0], (len(a), 1))
    return T, B, N


# -------------------------------------------------------------------------------------------- sections
def _thetas():
    """Polar sample angles: five round each hex corner (corners at 0, 60, ... deg; the flats in between are straight,
    so they need no samples of their own)."""
    offs = np.array([-13.0, -5.0, 0.0, 5.0, 13.0])
    return np.radians(np.concatenate([c + offs for c in range(0, 360, 60)]))


TH = _thetas()


def r_hex(th, apothem=HEX_APOTHEM, p=22.0):
    """Hex with slightly rounded (forged) corners: flats facing +-v at 90 / 270 deg, corners at 0, 60, ...."""
    acc = np.zeros_like(th)
    for k in range(6):
        acc += np.maximum(np.cos(th - np.radians(30 + 60 * k)), 0.0) ** p
    return apothem / acc ** (1.0 / p)


def r_flat(th, w, t, p=5.0):
    """Flattened section: a rounded slab, half width w (along u), half thickness t (along v)."""
    return 1.0 / ((np.abs(np.cos(th)) / w) ** p + (np.abs(np.sin(th)) / t) ** p) ** (1.0 / p)


def claw_slab(s, s_end):
    """The claw's flattened slab at s: (half width, v of the outer face, v of the inner face). The outer face (-v,
    away from the bend's centre) stays straight; the inner face is ground off towards the tip into the nail-pulling
    bevel, leaving a 0.9 mm edge."""
    c0 = s_end - CLAW_LEN
    u = np.clip((s - c0) / CLAW_LEN, 0, 1)
    outer = -0.0032
    inner = 0.0032 - (0.0032 + 0.0032 - 0.0009) * _smooth(0.35, 1.0, u) ** 1.2
    w = CLAW_W * (1 - 0.12 * _smooth(0.82, 1.0, u))
    return w, outer, inner


def section(s, s_end):
    """(u, v) points of the section at arc length s (polar samples at TH)."""
    hexr = r_hex(TH)
    hx, hy = hexr * np.cos(TH), hexr * np.sin(TH)
    # chisel: ground edge (1.4 mm) -> 5.5 mm slab -> hex
    if s < 0.055:
        t = 0.0007 + (0.0028 - 0.0007) * _smooth(0.0, 0.014, s)
        w = CHISEL_W * (1.0 - 0.06 * _smooth(0.02, 0.05, s))
        k = _smooth(0.020, 0.055, s)
        r = (1 - k) * r_flat(TH, w, t) + k * hexr
        return r * np.cos(TH), r * np.sin(TH)
    # claw: hex -> a 6.4 mm slab over the end of the neck -> the inner bevel towards the tip
    c0 = s_end - CLAW_LEN
    if s > c0 - 0.026:
        k = _smooth(c0 - 0.026, c0 + 0.004, s)
        w, lo, hi = claw_slab(s, s_end)
        r = r_flat(TH, w, 0.5 * (hi - lo))
        fx, fy = r * np.cos(TH), 0.5 * (hi + lo) + r * np.sin(TH)
        return (1 - k) * hx + k * fx, (1 - k) * hy + k * fy
    return hx, hy


def ring_points(p, B, N, uv):
    return p + np.outer(uv[0], B) + np.outer(uv[1], N)


def bar_mesh():
    """Swept bar (chisel -> shaft -> goose-neck -> claw up to the slot's root); the two prongs of the claw are lofts of
    their own past the slot's root. Bare steel (`ends`) over the ground chisel and claw, paint (`bar`) elsewhere."""
    pts, hd, s = centre_line()
    _, B, N = frames(hd)
    s_end = s[-1]
    s_slot = s_end - SLOT_LEN
    keep = s < s_slot - 1e-6
    ss = np.concatenate([s[keep], [s_slot]])
    pp = np.stack([np.interp(ss, s, pts[:, k]) for k in range(3)], 1)
    hh = np.interp(ss, s, hd)
    _, Bs, Ns = frames(hh)
    rings = [ring_points(p, b, n, section(si, s_end)) for p, b, n, si in zip(pp, Bs, Ns, ss)]
    m = sh.loft(rings, "bar")
    ends = lambda si: si < 0.016 or si > s_end - CLAW_LEN + 0.006
    # material per side face: rings k, k+1 -> faces k * SAMPLES ... (loft adds side faces first, then two caps)
    mats = []
    for k in range(len(rings) - 1):
        mats += ["ends" if ends(0.5 * (ss[k] + ss[k + 1])) else "bar"] * SAMPLES
    mats += ["ends", "bar"]                      # chisel tip cap (start), the slot root cap (hidden)
    m.m = mats
    m.merge(_prongs(pts, hd, s, s_slot, s_end))
    return m


def _prong_section(s, s_end, side):
    """A claw prong's section (u, v) loop at s: half the claw from the slot's edge out (side +-1 = +-u)."""
    w, lo, hi = claw_slab(s, s_end)
    g = (s - (s_end - SLOT_LEN)) / SLOT_LEN               # 0 at the slot's root .. 1 at the tip
    slot = SLOT_W * np.clip(g, 0, 1) ** 0.8
    t = 0.5 * (hi - lo)
    rc = min(0.9 * t, 0.0012)
    ri = min(rc, 0.4 * (w - slot))
    loop = sh.rounded_rect(slot, lo, w, hi, (ri, rc, rc, ri), 2)
    if side < 0:
        loop = loop[::-1] * np.array([-1.0, 1.0])
    return loop


def _prongs(pts, hd, s, s_slot, s_end):
    m = Mesh()
    ss = np.linspace(s_slot, s_end, 13)
    pp = np.stack([np.interp(ss, s, pts[:, k]) for k in range(3)], 1)
    _, B, N = frames(np.interp(ss, s, hd))
    for side in (-1, 1):
        rings = []
        for p, b, n, si in zip(pp, B, N, ss):
            loop = _prong_section(si, s_end, side)
            rings.append(p + np.outer(loop[:, 0], b) + np.outer(loop[:, 1], n))
        m.merge(sh.loft(rings, "ends"))
    return m


def tape_mesh():
    """Friction tape wound on the shaft: the hex rounded off under the layers, a raised helical seam where each turn
    overlaps the last, a step at both ends."""
    # sampled finely enough for the seam (1.5 mm wide) to resolve: ~0.8 mm along, 64 round
    th = np.linspace(0, 2 * np.pi, 64, endpoint=False)
    zs = np.linspace(TAPE_Z[0], TAPE_Z[1], 150)
    base = 0.55 * (r_hex(th) + TAPE_T) + 0.45 * (HEX_APOTHEM * 1.06 + TAPE_T)
    rings = []
    for z in zs:
        x = ((SHAFT_Z0 - z) / TAPE_PITCH + th / (2 * np.pi)) % 1.0
        seam = 0.00042 * (_smooth(0.0, 0.08, x) * (1 - _smooth(0.08, 0.55, x)))
        r = base + seam
        rings.append(np.stack([r * np.cos(th), r * np.sin(th), np.full_like(th, z)], 1))
    return sh.loft(rings, "grip")


def parts():
    """Design-frame meshes per node: bar (bar + ends) and grip (tape)."""
    return {"bar": bar_mesh(), "grip": tape_mesh()}


def nose():
    """The goose-neck's outermost point towards -Z (the striking point, node `tip`)."""
    pts, _, _ = centre_line()
    i = int(np.argmin(pts[:, 2]))
    return pts[i] + np.array([0.0, 0.0, -HEX_APOTHEM])


def claw_tip():
    pts, _, _ = centre_line()
    return pts[-1].copy()


def chisel_tip():
    pts, _, _ = centre_line()
    return pts[0].copy()


TIP = nose()
CHARM = np.array([0.0, -HEX_APOTHEM - 0.004, TAPE_Z[0] - 0.006])    # lanyard knotted below the tape's lower end
