"""Thompson SMG geometry (VIEWMODEL_SPEC section 24). Design frame (metres): +X right, +Y up, +Z rearward; bore axis on
x = y = 0. Real-world scale (overall ~0.85 m).

The receiver, frame, levers, grips, stock and cocking knob come from *Gangster Tommy Gun* by Pieter Ferreira (CC-BY-4.0,
see CREDITS.md), read with tommy_source.py, scaled, split into our parts and given our materials (the source's
textures are not used). Our own rework: the barrel with fewer, thicker cooling fins, the compensator, both sights,
a slimmer pistol grip, both magazines (a 30-round stick and a 50-round drum hung from a feed tower), the case; the
source's drum (rebuilt as ours, the same way round) and the parts hidden inside the receiver are dropped.

Every builder returns a meshkit.Mesh in the design frame; gen_tommy moves meshes into node-local space."""
import functools
import os

import numpy as np

import mesh_decimate as md
import shapes as sh
import tommy_source as ts
from meshkit import Mesh
from p9_model import chamfer_bevel

# ---- the source in our frame ---------------------------------------------------------------------
SRC_LENGTH = 0.85                                 # overall length the source is scaled to (M1928: ~0.857 m)
SRC_OFFSET = np.array([-0.17715, 0.08375, -0.1000])   # bore x / y and the design z origin, in scaled source units

# ---- key dimensions ------------------------------------------------------------------------------
REC_W = 0.0167                   # receiver half width
REC_TOP = 0.0127                 # receiver top (the cocking slot is recessed into it)
REC_Y0 = -0.0260                 # receiver underside ahead of the frame
REC_Z0, REC_Z1 = -0.0200, 0.3080  # receiver front face / rear end (stock tang)
FRAME_Y0 = -0.0790               # trigger guard bottom
BARREL_R = 0.0080
CROWN_Z = -0.2760                # barrel crown: the compensator / a suppressor threads on here
COMP_R, COMP_Z1 = 0.0120, -0.3260  # compensator radius and front face
MUZZLE_Z = COMP_Z1
FIN_R, FIN_T = 0.0118, 0.0017    # cooling fins (radius, thickness)
FIN_ZS = tuple(round(-0.1640 + 0.0110 * k, 4) for k in range(13) if abs(-0.1640 + 0.0110 * k + 0.1320) > 0.0090)
BAND_Z0, BAND_Z1 = CROWN_Z, -0.2630  # front sight band
SIGHT_Y = 0.0300                 # sight line: front post top / rear notch shoulders
FRONT_POST_Z = -0.2700
REAR_NOTCH_Z = 0.2620            # the rear sight's notch plate
RS_Z0, RS_Z1 = 0.2360, 0.2880    # rear sight base
KNOB_Z = 0.1440                  # cocking knob centre at rest (bolt forward)
BOLT_TRAVEL = 0.0840             # cocking knob stroke along the slot
OPTIC_Z = 0.0850                 # optic base centre on the riser rail, ahead of the cocking slot
RAIL_Z0, RAIL_Z1 = 0.0380, 0.1300  # optic riser rail on the receiver top (our addition: optics clear the knob)
RAIL_TOP, RAIL_W = REC_TOP + 0.0175, 0.0105
MAG_ORIGIN = np.array([0.0, -0.0040, 0.0450])   # seated magazine pivot (top centre, inside the well)
MAG_HALF = np.array([0.0118, 0.0172])           # stick magazine half width (x) / half depth (z)
STICK_LEN = 0.1860
# the drum's axis runs along the bore (round faces to the front and the back, as on the real L drum): its front face
# is flush with the stick's front face, so one front-face hold (tommy_rig "mag_side") suits both magazines
DRUM_R, DRUM_HW = 0.0620, 0.0220
DRUM_C = np.array([0.0, -0.0860, -MAG_HALF[1] + DRUM_HW])      # mag-local centre of the drum
TRIGGER_PIVOT = np.array([0.0, -0.0410, 0.1070])
SELECTOR_PIVOT = np.array([-0.0140, -0.0335, 0.1765])
EJECTION = np.array([REC_W + 0.0005, 0.0020, 0.0900])
GRIP_TOP = np.array([0.0, -0.0470, 0.1525])
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(18.2)), np.sin(np.radians(18.2))])     # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(18.2)), -np.cos(np.radians(18.2))])
GRIP_DEPTH_SCALE = 0.82          # our slimmer grip (front to back)
FORE_TOP = np.array([0.0, -0.0500, -0.1000])    # vertical foregrip: top centre and axis (down, a little back)
FORE_AXIS = np.array([0.0, -np.cos(np.radians(9.5)), np.sin(np.radians(9.5))])
FORE_FRONT = np.array([0.0, -np.sin(np.radians(9.5)), -np.cos(np.radians(9.5))])
FORE_DEPTH_SCALE = 0.86          # the hand-held part of the foregrip, slimmed front to back


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def fore_center(d):
    return FORE_TOP + d * FORE_AXIS


# ---- source parts ----------------------------------------------------------------------------------
def _components(P, T):
    """Triangles grouped into connected pieces (vertices welded by position)."""
    _, canon = np.unique(np.round(P / 1e-5).astype(np.int64), axis=0, return_inverse=True)
    canon = canon.reshape(-1)
    TT = canon[T]
    n = int(canon.max()) + 1
    V = np.zeros((n, 3))
    V[canon] = P
    parent = np.arange(n)

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for a, b, c in TT:
        ra = find(a)
        parent[find(b)] = ra
        parent[find(c)] = ra
    roots = np.array([find(i) for i in range(n)])
    keys, inv = np.unique(roots[TT[:, 0]], return_inverse=True)
    return V, [TT[inv == k] for k in range(len(keys))]


def _classify(lo, hi, count):
    """Our part for one source piece (bounding box in the design frame), or None to drop it."""
    c, s = (lo + hi) / 2, hi - lo
    if count > 1000 and s[2] > 0.2:
        return "receiver"                                   # upper receiver, lower frame
    if s[2] < 0.003 and abs(c[0]) < 0.003 and abs(c[1]) < 0.003 and -0.18 < c[2] < -0.02:
        return None                                         # cooling fins: rebuilt (fewer, thicker)
    if c[2] < -0.1 and s[2] > 0.25:
        return None                                         # barrel, compensator, front sight: rebuilt
    if 0.012 < c[2] < 0.075 and c[1] < -0.008:
        return None                                         # the source's drum: rebuilt
    if c[0] > 0.002 and hi[0] < 0.012 and hi[1] < 0.0115 and lo[1] > -0.005 and s[2] > 0.012:
        return None                                         # cylinders hidden inside the receiver
    if c[2] < -0.05 and lo[1] < -0.10:
        return "foregrip"
    if 0.11 < c[2] < 0.22 and lo[1] < -0.12:
        return "grip"
    if lo[2] > 0.20 and lo[1] < -0.10:
        return "stock"
    if lo[2] > 0.31:
        return "stock_pin"
    if lo[1] > 0.004 and hi[1] > 0.02 and c[2] < 0.16 and s[0] < 0.012:
        return "bolt"                                       # the cocking knob
    if lo[1] >= 0.011 and 0.22 < c[2] < 0.30:
        return None                                         # rear sight: rebuilt with a proper notch
    if s[0] < 0.005 and lo[1] < -0.07 and 0.08 < c[2] < 0.13:
        return "trigger"
    if c[0] < -0.008 and 0.15 < c[2] < 0.19 and s[2] > 0.015:
        return "selector"                                   # the safety lever on the left
    return "detail"                                         # levers, pins, the cocking slot's floor


EXPECT = {"receiver": 2, "foregrip": 1, "grip": 1, "stock": 1, "stock_pin": 1, "bolt": 1, "trigger": 1,
          "selector": 1}
# optimisation: share of the triangles kept per part (quadric decimation; the receiver's flat sides lose nothing)
KEEP = {"receiver": 0.60, "detail": 0.50, "grip": 0.85}
KEEP_MIN = 100                   # pieces smaller than this are left alone


#: The converted source parts (design frame, decimated), committed so the model rebuilds without the downloaded file.
PARTS_CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "source", "tommy_parts.npz")


@functools.lru_cache(maxsize=2)
def source_parts(path):
    """part -> list of (V, F) triangle sets in the design frame: converted from the source file when it exists (and
    the cache refreshed), else read from the committed cache."""
    if not os.path.exists(path) and os.path.exists(PARTS_CACHE):
        return _load_parts()
    parts = _convert(path)
    _save_parts(parts)
    return parts


def _save_parts(parts):
    arrays = {}
    for part, pieces in parts.items():
        for k, (V, F) in enumerate(pieces):
            used = np.unique(F)
            remap = -np.ones(len(V), np.int64)
            remap[used] = np.arange(len(used))
            arrays[f"{part}|{k}|v"] = V[used].astype(np.float64)
            arrays[f"{part}|{k}|f"] = remap[F].astype(np.int32)
    os.makedirs(os.path.dirname(PARTS_CACHE), exist_ok=True)
    np.savez_compressed(PARTS_CACHE, **arrays)


def _load_parts():
    z = np.load(PARTS_CACHE)
    parts = {}
    for key in sorted(z.files, key=lambda k: (k.split("|")[0], int(k.split("|")[1]))):
        part, k, kind = key.split("|")
        if kind == "v":
            parts.setdefault(part, []).append((z[key], z[f"{part}|{k}|f"].astype(np.int64)))
    return parts


def _convert(path):
    P, T = ts.read_glb(path)
    scale = SRC_LENGTH / (P[:, 0].max() - P[:, 0].min())
    # source axes: X along the gun (muzzle towards +X), Y up, Z to the right -> ours (+Z rearward), same handedness
    D = np.stack([P[:, 2], P[:, 1], -P[:, 0]], 1) * scale - SRC_OFFSET
    V, comps = _components(D, T)
    parts = {}
    for F in comps:
        v = V[np.unique(F)]
        part = _classify(v.min(0), v.max(0), len(F))
        if part is None:
            continue
        piece = (V, F)
        if part in KEEP and len(F) > KEEP_MIN:
            piece = md.decimate(V, F, int(len(F) * KEEP[part]))
        parts.setdefault(part, []).append(piece)
    for part, n in EXPECT.items():
        got = len(parts.get(part, []))
        if got != n:
            raise ValueError(f"tommy source: expected {n} piece(s) of {part}, found {got} (unexpected source file?)")
    return parts


def _soup_mesh(pieces, material, material_fn=None, warp=None):
    """Triangle sets -> Mesh (one face per triangle); material_fn(centroid) may override the material."""
    m = Mesh()
    for V, F in pieces:
        used = np.unique(F)
        remap = -np.ones(len(V), np.int64)
        remap[used] = np.arange(len(used))
        pts = V[used] if warp is None else warp(V[used])
        base = m.add_vertices(pts)
        for f in F:
            idx = [base + int(remap[i]) for i in f]
            mat = material
            if material_fn is not None:
                mat = material_fn(pts[remap[f]].mean(0)) or material
            m.add_face(idx, mat)
    return m


def receiver_mesh(path):
    """Upper receiver with the cocking slot and the magazine well, the lower frame with its trigger guard, levers and
    pins; the rod and clamp under the barrel that carry the foregrip are dark steel."""
    parts = source_parts(path)
    m = _soup_mesh(parts["receiver"], "steel", lambda c: "steel_dark" if c[2] < REC_Z0 - 0.002 else None)
    m.merge(_soup_mesh(parts["detail"], "steel_dark"))
    m.merge(rail_mesh())
    return m


def rail_mesh():
    """Our riser rail ahead of the cocking slot: a clamped base with a short picatinny top (optics stand on it, high
    enough to look over the cocking knob)."""
    m = sh.box(-RAIL_W + 0.0015, REC_TOP - 0.0008, RAIL_Z0, RAIL_W - 0.0015, RAIL_TOP - 0.0030, RAIL_Z1, 0.0010,
               "steel_dark")
    for z in (RAIL_Z0 + 0.010, RAIL_Z1 - 0.010):                         # clamp screw heads on both sides
        for x in (-RAIL_W + 0.0012, RAIL_W - 0.0030):
            m.merge(sh.box(x, REC_TOP + 0.0016, z - 0.0020, x + 0.0018, REC_TOP + 0.0056, z + 0.0020, 0.0,
                           "steel_dark"))
    m.merge(sh.box(-RAIL_W, RAIL_TOP - 0.0032, RAIL_Z0 + 0.002, RAIL_W, RAIL_TOP - 0.0012, RAIL_Z1 - 0.002, 0.0006,
                   "steel_dark"))
    k = 0
    while RAIL_Z0 + 0.008 + k * 0.010 + 0.0045 < RAIL_Z1 - 0.004:          # picatinny cross slots
        z = RAIL_Z0 + 0.008 + k * 0.010
        m.merge(sh.box(-0.0078, RAIL_TOP - 0.0013, z, 0.0078, RAIL_TOP, z + 0.0055, 0.0, "steel_dark"))
        k += 1
    return m


def foregrip_mesh(path):
    """The source's foregrip: its long top along the barrel rod kept, the grip under it slimmed front to back."""
    def slim(p):
        w = np.clip((-0.055 - p[:, 1]) / 0.020, 0.0, 1.0)
        w = w * w * (3 - 2 * w)
        d = p - FORE_TOP
        front = d @ FORE_FRONT
        return p + np.outer(front * (FORE_DEPTH_SCALE - 1.0) * w, FORE_FRONT)
    return _soup_mesh(source_parts(path)["foregrip"], "wood", warp=slim)


def grip_mesh(path):
    """The source's chunky pistol grip, slimmed front to back about its axis."""
    def slim(p):
        d = p - GRIP_TOP
        along = d @ GRIP_AXIS
        front = d @ GRIP_FRONT
        return GRIP_TOP + np.outer(along, GRIP_AXIS) + np.outer(front * GRIP_DEPTH_SCALE, GRIP_FRONT) \
            + np.outer(d[:, 0], (1.0, 0.0, 0.0))
    return _soup_mesh(source_parts(path)["grip"], "wood", warp=slim)


def stock_mesh(path):
    parts = source_parts(path)
    return _soup_mesh(parts["stock"], "wood").merge(_soup_mesh(parts["stock_pin"], "steel_dark"))


def bolt_mesh(path):
    """The cocking knob riding in the top slot (bolt forward)."""
    return _soup_mesh(source_parts(path)["bolt"], "steel_dark")


def trigger_mesh(path):
    return _soup_mesh(source_parts(path)["trigger"], "steel_dark")


def selector_mesh(path):
    return _soup_mesh(source_parts(path)["selector"], "steel_dark")


# ---- our parts -------------------------------------------------------------------------------------
def _ring(r0, r1, z0, z1, material, seg=12, chamfer=0.0):
    """Annulus (tube section) along Z from z0 to z1 (z0 > z1 allowed), outer edges chamfered."""
    za, zb = min(z0, z1), max(z0, z1)
    c = min(chamfer, 0.3 * (zb - za))
    prof = [(r0, za), (r1 - c, za), (r1, za + c), (r1, zb - c), (r1 - c, zb), (r0, zb)]
    if c <= 0:
        prof = [(r0, za), (r1, za), (r1, zb), (r0, zb)]
    return sh.revolve(prof, seg, material, precision=True)


def barrel_mesh():
    """Barrel from the receiver to the crown, its cooling fins and the front sight band (with the blade's base)."""
    m = sh.revolve([(0.0, CROWN_Z), (BARREL_R - 0.0010, CROWN_Z), (BARREL_R, CROWN_Z + 0.0010),
                    (BARREL_R, REC_Z0 + 0.0040), (0.0, REC_Z0 + 0.0040)], 16, "steel", precision=True)
    for z in FIN_ZS:
        m.merge(_ring(BARREL_R - 0.0002, FIN_R, z - FIN_T / 2, z + FIN_T / 2, "steel", 12))
    # a thicker collar where the fins end at the receiver, and one at the front of the finned section
    m.merge(_ring(BARREL_R - 0.0002, 0.0106, REC_Z0 - 0.0090, REC_Z0 + 0.0010, "steel", 16, 0.0008))
    m.merge(_ring(BARREL_R - 0.0002, 0.0100, -0.1760, -0.1700, "steel", 16, 0.0006))
    # front sight band with the flat seat the blade stands on
    m.merge(_ring(BARREL_R - 0.0002, 0.0104, BAND_Z0, BAND_Z1, "steel", 16, 0.0008))
    m.merge(sh.box(-0.0035, 0.0080, BAND_Z0 + 0.0010, 0.0035, 0.0128, BAND_Z1 - 0.0010, 0.0008, "steel"))
    return m


def muzzle_brake_mesh():
    """Cutts-pattern compensator: a barrel-sized collar, a fat body with a flat top cut by five slots (gas vents
    upwards to hold the muzzle down) and a dished front face around the bore."""
    flat = COMP_R * 0.62                                     # height of the flat top
    sec = []
    for a in np.linspace(0, 2 * np.pi, 20, endpoint=False):
        x, y = COMP_R * np.cos(a), COMP_R * np.sin(a)
        sec.append((x, min(y, flat)))
    sec = np.array(sec)
    z0, z1 = CROWN_Z - 0.0060, COMP_Z1
    rings = [np.stack([sec[:, 0] * k, sec[:, 1] * k, np.full(len(sec), z)], 1)
             for z, k in ((z0, 0.93), (z0 - 0.0012, 1.0), (z1 + 0.0012, 1.0), (z1, 0.93))]
    m = sh.loft(rings, "steel")
    m.merge(_ring(0.0, BARREL_R + 0.0012, CROWN_Z, CROWN_Z - 0.0062, "steel", 16, 0.0006))     # threaded collar
    m.merge(sh.disc((0.0, 0.0, z1 - 0.0002), (0.0, 0.0, -1.0), 0.0052, 12, "steel_dark"))       # bore
    for k in range(5):                                       # vent slots: dark recesses across the flat
        zc = z0 - 0.0085 - k * 0.0075
        slot = Mesh()
        slot.add_poly([(-0.0072, flat + 0.00015, zc - 0.0016), (0.0072, flat + 0.00015, zc - 0.0016),
                       (0.0072, flat + 0.00015, zc + 0.0016), (-0.0072, flat + 0.00015, zc + 0.0016)][::-1],
                      "steel_dark")
        m.merge(slot)
    return m


def front_post_mesh():
    """Front sight blade on the band (its top is the sight line)."""
    prof = [(FRONT_POST_Z - 0.0030, 0.0122), (FRONT_POST_Z + 0.0045, 0.0122), (FRONT_POST_Z + 0.0045, SIGHT_Y),
            (FRONT_POST_Z - 0.0005, SIGHT_Y), (FRONT_POST_Z - 0.0030, SIGHT_Y - 0.0060)]
    return sh.extrude_x(prof, (), 0.0011, chamfer_bevel(0.0002), "steel_dark")


def rear_sight_mesh():
    """Fixed rear sight: a tapered base on the receiver top with a notch plate (shoulders on the sight line) and a
    windage screw on the right."""
    lo, hi = REC_TOP - 0.0006, 0.0185
    ring = lambda z, hw_lo, hw_hi: np.array([(-hw_lo, lo, z), (hw_lo, lo, z), (hw_hi, hi, z), (-hw_hi, hi, z)])
    m = sh.loft([ring(RS_Z0, 0.0090, 0.0060), ring(RS_Z0 + 0.0060, 0.0095, 0.0075), ring(RS_Z1 - 0.0060, 0.0095, 0.0075),
                 ring(RS_Z1, 0.0090, 0.0060)], "steel_dark")
    shoulder = SIGHT_Y + 0.0004
    gap = 0.0014
    z0, z1 = REAR_NOTCH_Z - 0.0013, REAR_NOTCH_Z + 0.0013
    m.merge(sh.box(-0.0078, hi - 0.0005, z0, -gap, shoulder, z1, 0.0003, "steel_dark"))
    m.merge(sh.box(gap, hi - 0.0005, z0, 0.0078, shoulder, z1, 0.0003, "steel_dark"))
    m.merge(sh.box(-gap - 0.0002, hi - 0.0005, z0, gap + 0.0002, SIGHT_Y - 0.0018, z1, 0.0002, "steel_dark"))
    screw = sh.revolve([(0.0, 0.0), (0.0024, 0.0), (0.0026, 0.0006), (0.0026, 0.0030), (0.0, 0.0030)], 10, "steel_dark", precision=True)
    m.merge(sh.place(screw, origin=(0.0086, 0.0155, REAR_NOTCH_Z + 0.0120), x_axis=(0, 1, 0), y_axis=(0, 0, 1),
                     z_axis=(1, 0, 0)))
    return m


def _rounded_rect_ring(hw, hd, y, r=0.0030, seg=2):
    loop = sh.rounded_rect(-hw, -hd, hw, hd, r, seg)          # (x, z)
    return np.stack([loop[:, 0], np.full(len(loop), y), loop[:, 1]], 1)


def _top_round(y=0.0005, material="brass"):
    """.45 ACP round on the feed lips (case rear, bullet forward)."""
    return sh.revolve([(0.0, 0.0), (0.0058, 0.0), (0.0058, -0.0160), (0.0057, -0.0228), (0.0050, -0.0260),
                       (0.0036, -0.0300), (0.0, -0.0315)], 8, material).translated((0.0, y, 0.0150))


def _mag_tube(y_top, y_bottom, taper=0.0):
    rings = [_rounded_rect_ring(MAG_HALF[0], MAG_HALF[1], y_top - 0.0010, 0.0034),
             _rounded_rect_ring(MAG_HALF[0] + 0.0003, MAG_HALF[1] + 0.0003, y_top - 0.0040, 0.0036),
             _rounded_rect_ring(MAG_HALF[0] + 0.0003 - taper, MAG_HALF[1] + 0.0003 - taper, y_bottom, 0.0036)]
    return sh.loft(rings, "mag")


def stick_mag_mesh():
    """30-round stick magazine (mag-local: +Y up into the well, -Z front): pressed side ribs, a witness-hole
    strip, feed lips with the top round, a floor plate."""
    y_bot = -STICK_LEN
    m = _mag_tube(0.0040, y_bot, taper=0.0004)
    side = MAG_HALF[0] + 0.0003
    for s in (-1, 1):                                        # pressed stiffening ribs along both sides
        x0, x1 = sorted((s * (side - 0.0004), s * (side + 0.0007)))
        for z in (-0.0085, 0.0085):
            m.merge(sh.box(x0, -0.1650, z - 0.0018, x1, -0.0200, z + 0.0018, 0.0, "mag"))
    m.merge(sh.box(-0.0090, 0.0000, -0.0150, 0.0090, 0.0050, 0.0160, 0.0008, "mag"))       # feed lips
    m.merge(_top_round())
    plate = [_rounded_rect_ring(MAG_HALF[0] + 0.0012, MAG_HALF[1] + 0.0014, y, r)
             for y, r in ((y_bot + 0.0004, 0.0040), (y_bot - 0.0050, 0.0040), (y_bot - 0.0066, 0.0030))]
    m.merge(sh.loft(plate, "mag_base"))
    return m


def drum_mesh():
    """50-round drum (mag-local): a feed tower into the well, the drum hung under it with its axis along the bore
    (round faces front and back), pressed rings on both faces, two rolled ribs on the rim, the winding key and its
    spring hub on the rear face (towards the shooter)."""
    m = _mag_tube(0.0040, -0.0500)
    m.merge(sh.box(-0.0090, 0.0000, -0.0150, 0.0090, 0.0050, 0.0160, 0.0008, "mag"))
    m.merge(_top_round())
    r, hw = DRUM_R, DRUM_HW
    prof = [(0.0, -hw), (0.0165, -hw), (0.0200, -hw + 0.0022), (0.0540, -hw + 0.0022), (0.0590, -hw + 0.0004),
            (r, -hw + 0.0040), (r, hw - 0.0040), (0.0590, hw - 0.0004), (0.0540, hw - 0.0022),
            (0.0200, hw - 0.0022), (0.0165, hw), (0.0, hw)]
    disc = sh.revolve(prof, 24, "mag", precision=True)                       # about Z: the bore axis
    rib = lambda x: sh.revolve([(r - 0.0004, x - 0.0020), (r + 0.0015, x), (r - 0.0004, x + 0.0020)], 24, "mag", precision=True)
    for x in (-0.0100, 0.0100):
        disc.merge(rib(x))
    hub = sh.revolve([(0.0, -hw - 0.0042), (0.0115, -hw - 0.0042), (0.0130, -hw - 0.0024), (0.0130, -hw + 0.0010),
                      (0.0, -hw + 0.0010)], 12, "mag_base", precision=True)
    disc.merge(hub)
    disc.merge(sh.revolve([(0.0, hw - 0.0010), (0.0100, hw - 0.0010), (0.0100, hw + 0.0016), (0.0085, hw + 0.0024),
                           (0.0, hw + 0.0024)], 12, "mag_base", precision=True))
    # winding key: a flat T-handle on the hub (built on the -Z face, turned to the rear below)
    disc.merge(sh.box(-0.0032, -0.0150, -hw - 0.0090, 0.0032, 0.0150, -hw - 0.0042, 0.0012, "mag_base"))
    disc.merge(sh.box(-0.0020, -0.0030, -hw - 0.0060, 0.0020, 0.0030, -hw - 0.0030, 0.0006, "mag_base"))
    for a in np.radians((40, 160, 280)):                     # rivets of the spring housing
        disc.merge(sh.revolve([(0.0, hw - 0.0024), (0.0016, hw - 0.0024), (0.0012, hw - 0.0010), (0.0, hw - 0.0006)],
                              8, "mag_base").translated((0.038 * np.cos(a), 0.038 * np.sin(a), 0.0)))
    disc = sh.place(disc, x_axis=(-1, 0, 0), y_axis=(0, 1, 0), z_axis=(0, 0, -1))     # key face -> rear (+Z)
    m.merge(disc.translated(DRUM_C))
    return m


def shell_mesh():
    """Spent .45 ACP brass case (material `brass`), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0114), (0.0057, 0.0114), (0.0060, 0.0108), (0.0060, 0.0098), (0.0050, 0.0092),
            (0.0050, 0.0080), (0.0060, 0.0074), (0.0060, -0.0114), (0.0054, -0.0114), (0.0054, -0.0100),
            (0.0, -0.0100)]
    return sh.revolve(prof, 14, "brass", precision=True)


def default_source():
    return ts.DEFAULT
