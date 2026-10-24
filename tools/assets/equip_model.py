"""Equipment geometry (VIEWMODEL_SPEC section 36): frag grenade (M67 pattern), flashbang (M84 pattern), smoke grenade
(M18 pattern), molotov, throwing knife and adrenaline auto-injector. Original designs at real scale.

Design frame of every item (metres): the origin is the middle of the right hand's grip (equip_rig places the palm
round it); grenades and the bottle stand along +Y (fuze / mouth up), their lever runs down the +X side (the palm's
side: the palm squeezes it) and the pin's ring sticks out towards -Z next to the fuze (the left hand hooks it from the
front). The throwing knife and the injector lie along Z: the knife's point towards -Z, flats facing +-X; the
injector's needle end towards -Z, the cap (thumb end) towards +Z.

Each function returns {node: (Mesh in design coordinates, node origin in design coordinates)}; gen_equipment moves
the meshes into their nodes' spaces."""
import numpy as np

import shapes as sh
from meshkit import Mesh

V = lambda *a: np.array(a, float)
Y_UP = dict(x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))      # revolve's Z axis -> item +Y


def revolve_y(profile, seg, material, origin=(0, 0, 0)):
    """Lathe an (r, y) profile about the item's Y axis (closed when it starts and ends on the axis)."""
    m = sh.revolve(profile, seg, material, precision=material != "flame")
    return sh.place(m, origin=origin, **Y_UP)


def revolve_z(profile, seg, material, origin=(0, 0, 0)):
    """Lathe an (r, z) profile about the item's Z axis."""
    m = sh.revolve(profile, seg, material, precision=material != "flame")
    return m.translated(np.asarray(origin, float)) if np.any(origin) else m


def torus(centre, normal_axis, radius, tube, material, seg=20, tseg=8, squash=1.0):
    """A ring: centre line `radius`, tube `tube`; the hole along `normal_axis` (0 = X, 1 = Y, 2 = Z)."""
    seg = sh.arc_segments(radius + tube, seg)
    tseg = sh.arc_segments(tube * max(1.0, squash), tseg)
    prof = [(radius + tube * np.cos(a), tube * squash * np.sin(a)) for a in np.linspace(0, 2 * np.pi, tseg, endpoint=False)]
    rings = [[(r * np.cos(th), r * np.sin(th), z) for r, z in prof] for th in np.linspace(0, 2 * np.pi, seg, endpoint=False)]
    m = sh.loft(rings + [rings[0]], material, cap_start=False, cap_end=False)
    sh.orient_outward(m)
    axes = {0: dict(x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0)),
            1: dict(x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0)),
            2: dict(x_axis=(1, 0, 0), y_axis=(0, 1, 0), z_axis=(0, 0, 1))}[normal_axis]
    return sh.place(m, origin=centre, **axes)


def tube_along(path, radius, material, seg=8, flat=1.0, twist=0.0, up=(0, 1, 0), radii=None):
    """A closed tube along a 3D polyline (cord, wire, rag); `flat` squashes the section along the second axis,
    `twist` turns the section (radians per metre), `radii` per-point radius factors."""
    seg = sh.arc_segments(radius * max(1.0, flat), seg)
    path = np.asarray(path, float)
    n = len(path)
    secs = []
    length = 0.0
    prev_side = None
    for i in range(n):
        t = path[min(i + 1, n - 1)] - path[max(i - 1, 0)]
        t /= np.linalg.norm(t)
        if prev_side is None:
            side = np.cross(t, up)
            if np.linalg.norm(side) < 1e-6:
                side = np.cross(t, (1, 0, 0))
        else:
            side = prev_side - t * np.dot(prev_side, t)
        side /= np.linalg.norm(side)
        prev_side = side
        other = np.cross(t, side)
        if i:
            length += np.linalg.norm(path[i] - path[i - 1])
        a0 = twist * length
        k = 1.0 if radii is None else radii[i]
        ring = []
        for a in np.linspace(0, 2 * np.pi, seg, endpoint=False):
            c, s = np.cos(a + a0), np.sin(a + a0)
            ring.append(path[i] + radius * k * (c * side + flat * s * other))
        secs.append(ring)
    return sh.loft(secs, material)


def strip(path_xy, z0, z1, thick, material, out_sign=1.0):
    """A thin strip (lever) following a path in the XY plane, from z0 to z1 across, `thick` thick; closed solid."""
    p = np.asarray(path_xy, float)
    secs = []
    for i in range(len(p)):
        t = p[min(i + 1, len(p) - 1)] - p[max(i - 1, 0)]
        t /= np.linalg.norm(t)
        nrm = out_sign * np.array([t[1], -t[0]])
        a, b = p[i] + nrm * thick / 2, p[i] - nrm * thick / 2
        # a shallow channel: the middle of the strip bulges outwards a little
        mid = 0.0006
        ring = [(a[0], a[1], z0), (a[0] + nrm[0] * mid, a[1] + nrm[1] * mid, 0.5 * (z0 + z1)), (a[0], a[1], z1),
                (b[0], b[1], z1), (b[0] + nrm[0] * mid, b[1] + nrm[1] * mid, 0.5 * (z0 + z1)), (b[0], b[1], z0)]
        secs.append(ring)
    return sh.loft(secs, material)


def teardrop(base, height, radius, material, seg=10, lean=(0.0, 0.0)):
    """A flame tongue: a closed lathe body pointing +Y from `base`, its tip pushed by `lean` (x, z)."""
    ys = np.linspace(0, 1, 9)
    prof = [(0.0, -0.18 * radius)] + [(radius * np.sin(np.pi * min(1.0, 0.25 + u)) * (1 - u) ** 0.7, height * u)
                                      for u in ys[:-1]] + [(0.0, height)]
    m = revolve_y(prof, seg, material)
    v = np.array(m.v)
    u = np.clip(v[:, 1] / height, 0, 1) ** 1.6
    v[:, 0] += lean[0] * u
    v[:, 2] += lean[1] * u
    m.v = [tuple(x) for x in v]
    return m.translated(np.asarray(base, float))


# ======================================================================================== the fuze (M213 pattern)
FUZE_R = 0.0115
LEVER_W = 0.0125              # across (Z)
LEVER_T = 0.0011
LEVER_GAP = 0.0026            # the lever stands off the body by this much (it springs out once released)


def fuze(y0):
    """Fuze assembly standing on y0: threaded neck, body, the striker housing on the +X side where the lever hooks,
    and the pin's guide ears. -> (mesh, hinge point, pin head point, pin axis)."""
    m = revolve_y([(0.0, y0 - 0.003), (0.0095, y0 - 0.003), (0.0105, y0), (0.0108, y0 + 0.003), (FUZE_R, y0 + 0.004),
                   (FUZE_R, y0 + 0.014), (0.0098, y0 + 0.017), (0.0075, y0 + 0.0185), (0.0, y0 + 0.019)], 18, "fuze")
    m.merge(sh.box(0.0040, y0 + 0.013, -0.0055, 0.0130, y0 + 0.0205, 0.0055, 0.0008, "fuze"))      # striker housing
    m.merge(sh.box(0.0020, y0 + 0.004, -0.0135, 0.0100, y0 + 0.0125, -0.0095, 0.0006, "fuze"))      # pin ears (front)
    m.merge(sh.box(0.0020, y0 + 0.004, 0.0095, 0.0100, y0 + 0.0125, 0.0135, 0.0006, "fuze"))        # (back)
    hinge = V(0.0115, y0 + 0.0205, 0.0)
    pin_head = V(0.0060, y0 + 0.0085, -0.0136)
    return m, hinge, pin_head


def lever_path(y0, body_r, y_end, top_y=None):
    """Lever centre line in the XY plane (z = 0): hooked over the striker housing, down past the fuze, then hugging
    the body (body_r(y) its radius) to y_end."""
    pts = [(0.0050, y0 + 0.0212), (0.0110, y0 + 0.0218), (0.0142, y0 + 0.0200), (0.0150, y0 + 0.0160)]
    ys = np.linspace(y0 + 0.012, y_end, 22)
    for y in ys:
        x = max(FUZE_R + LEVER_GAP + 0.001, body_r(y) + LEVER_GAP) if y > y0 - 0.006 else body_r(y) + LEVER_GAP
        pts.append((x, y))
    p = np.array(pts)
    # smooth the knee where the fuze meets the body (three passes of a [1 2 1] filter, ends fixed)
    for _ in range(3):
        q = p.copy()
        q[1:-1] = 0.25 * p[:-2] + 0.5 * p[1:-1] + 0.25 * p[2:]
        p = q
    # flare the lower end outwards a little (the thumb tab)
    p[-2:, 0] += np.array([0.0004, 0.0012])
    return p


def pin_parts(head):
    """The safety pin (node origin at its head where it leaves the fuze): a wire through the fuze along +Z and the
    pull ring hanging in front, its hole along X (the left index finger hooks through it)."""
    wire = tube_along([(0, 0, 0.0005), (0, 0, 0.012), (0, 0, 0.0262)], 0.0010, "pin", seg=6)
    ring = torus(V(0.0, 0.0040, -0.0112), 0, 0.0108, 0.0013, "pin", seg=22, tseg=7)
    wire.merge(ring)
    wire.merge(tube_along([(0, 0, 0.001), (0, 0.0006, -0.0010), (0, 0.0010, -0.0004)], 0.0010, "pin", seg=6))
    return wire


RING_HOLE = (V(0.0, 0.0040, -0.0112), 0.0108 - 0.0013)      # pin-local centre, hole radius (checks)


# ================================================================================================ grenades
FRAG_R = 0.0318


def frag_body_r(y):
    return np.sqrt(max(FRAG_R ** 2 - (y / 0.985) ** 2, 0.0))


def frag():
    """M67 pattern: a smooth steel sphere (64 mm), the fuze screwed into its top, the lever down the +X side."""
    prof = [(0.0, -FRAG_R * 0.985)]
    for a in np.linspace(-80, 66, sh.arc_segments(FRAG_R, 20, np.radians(146)) + 1):
        r = FRAG_R * np.cos(np.radians(a))
        prof.append((r, FRAG_R * 0.985 * np.sin(np.radians(a))))
    prof += [(0.0112, 0.0296), (0.0, 0.0296)]
    body = revolve_y(prof, 28, "body")
    body.merge(torus(V(0.0, -0.0322, 0.0), 2, 0.0032, 0.0009, "fuze", seg=12, tseg=6))           # charm eye
    y0 = 0.0292
    fz, hinge, pin_head = fuze(y0)
    body.merge(fz)
    lever = strip(lever_path(y0, frag_body_r, -0.012), -LEVER_W / 2, LEVER_W / 2, LEVER_T, "lever")
    return ({"body": (body, V(0, 0, 0)), "lever": (lever, hinge), "pin": (pin_parts(pin_head).translated(pin_head), pin_head)},
            {"charm": V(0.0, -0.0362, 0.0), "top": V(0, y0 + 0.019, 0)})


FLASH_R = 0.0215
FLASH_Y = (-0.062, 0.028)


def flash_body_r(y):
    return FLASH_R


def flashbang():
    """M84 pattern: a perforated steel tube (43 mm) with heavier end caps, rows of large bevelled vent holes (1.2 mm shell over a recessed core),
    a printed band near the bottom, the fuze on top."""
    y_lo, y_hi = FLASH_Y
    # Separate end caps leave the vented centre genuinely open, not covered by dark decals.
    body = revolve_y([(0.0, y_lo - 0.004), (0.0205, y_lo - 0.004), (0.0228, y_lo - 0.0025),
                      (0.0228, y_lo + 0.006), (FLASH_R, y_lo + 0.0072), (FLASH_R, -0.035),
                      (FLASH_R - 0.0012, -0.035), (FLASH_R - 0.0012, y_lo), (0.0, y_lo)], 72, "body")
    body.merge(revolve_y([(FLASH_R, 0.019), (FLASH_R, y_hi - 0.0072), (0.0228, y_hi - 0.006),
                           (0.0228, y_hi + 0.0015), (0.0130, y_hi + 0.0028), (0.0, y_hi + 0.0028),
                           (0.0, y_hi), (FLASH_R - 0.0012, y_hi - 0.0072),
                           (FLASH_R - 0.0012, 0.019), (FLASH_R, 0.019)], 72, "body"))
    label = revolve_y([(FLASH_R + 0.0001, y_lo + 0.0085), (FLASH_R + 0.0004, y_lo + 0.0090),
                       (FLASH_R + 0.0004, y_lo + 0.0235), (FLASH_R + 0.0001, y_lo + 0.0240)], 28, "label")
    vents = Mesh()
    for row, y in enumerate((-0.026, -0.008, 0.010)):
        vents.merge(sh.perforated_band(FLASH_R, y - 0.009, y + 0.009, 0.0062, 0.0012, "body",
                                       phase=np.radians(15 + (30 if row % 2 else 0))))
    # Recessed inner canister seen THROUGH the holes; never a coplanar disc on the outer shell.
    vents.merge(revolve_y([(FLASH_R - 0.0035, -0.036), (FLASH_R - 0.0035, 0.020)], 48, "vent"))
    body.merge(label)
    body.merge(vents)
    body.merge(torus(V(0.0, y_lo - 0.0062, 0.0), 2, 0.0030, 0.0009, "fuze", seg=12, tseg=6))
    y0 = y_hi + 0.0025
    fz, hinge, pin_head = fuze(y0)
    body.merge(fz)
    lever = strip(lever_path(y0, flash_body_r, y_lo + 0.030), -LEVER_W / 2, LEVER_W / 2, LEVER_T, "lever")
    return ({"body": (body, V(0, 0, 0)), "lever": (lever, hinge), "pin": (pin_parts(pin_head).translated(pin_head), pin_head)},
            {"charm": V(0.0, y_lo - 0.0100, 0.0), "top": V(0, y0 + 0.019, 0)})


SMOKE_R = 0.0300
SMOKE_Y = (-0.074, 0.050)


def smoke_body_r(y):
    return SMOKE_R


def smoke_grenade():
    """M18 pattern: a sheet-steel canister (60 mm) with rolled seams, emission holes round the fuze on the top lid, a
    coloured band and stencilling at the top (`label`), the fuze on top."""
    y_lo, y_hi = SMOKE_Y
    body = revolve_y([(0.0, y_lo), (0.0285, y_lo), (0.0307, y_lo + 0.0018), (0.0307, y_lo + 0.0042),
                      (SMOKE_R, y_lo + 0.0050), (SMOKE_R, y_hi - 0.0215), (SMOKE_R, y_hi - 0.0050),
                      (0.0307, y_hi - 0.0042), (0.0307, y_hi - 0.0018), (0.0282, y_hi), (0.0130, y_hi + 0.0010),
                      (0.0, y_hi + 0.0010)], 30, "body")
    label = revolve_y([(SMOKE_R + 0.0001, y_hi - 0.0215), (SMOKE_R + 0.0004, y_hi - 0.0210),
                       (SMOKE_R + 0.0004, y_hi - 0.0070), (SMOKE_R + 0.0001, y_hi - 0.0065)], 30, "label")
    label.merge(revolve_y([(SMOKE_R + 0.0001, y_lo + 0.030), (SMOKE_R + 0.0004, y_lo + 0.0305),
                           (SMOKE_R + 0.0004, y_lo + 0.058), (SMOKE_R + 0.0001, y_lo + 0.0585)], 30, "label"))
    vents = Mesh()
    for k in range(4):
        a = np.radians(45 + 90 * k)
        c = V(0.0205 * np.cos(a), y_hi + 0.0003 + 0.00055, 0.0205 * np.sin(a))
        vents.merge(sh.disc(c, (0, 1, 0), 0.0034, 10, "vent"))
    body.merge(label)
    body.merge(vents)
    body.merge(torus(V(0.0, y_lo - 0.0022, 0.0), 2, 0.0030, 0.0009, "fuze", seg=12, tseg=6))
    y0 = y_hi + 0.0008
    fz, hinge, pin_head = fuze(y0)
    body.merge(fz)
    lever = strip(lever_path(y0, smoke_body_r, y_hi - 0.058), -LEVER_W / 2, LEVER_W / 2, LEVER_T, "lever")
    return ({"body": (body, V(0, 0, 0)), "lever": (lever, hinge), "pin": (pin_parts(pin_head).translated(pin_head), pin_head)},
            {"charm": V(0.0, y_lo - 0.0060, 0.0), "top": V(0, y0 + 0.019, 0)})


# ================================================================================================= molotov
BOTTLE_R = 0.0290
BOTTLE = [(0.0, -0.098), (0.0240, -0.098), (0.0272, -0.0955), (0.0288, -0.0905), (BOTTLE_R, -0.084),
          (BOTTLE_R, 0.040), (0.0278, 0.052), (0.0235, 0.064), (0.0175, 0.077), (0.0132, 0.090), (0.0124, 0.122),
          (0.0138, 0.1245), (0.0140, 0.1290), (0.0122, 0.1305), (0.0, 0.1305)]
RAG_TOP = V(0.0012, 0.1505, 0.0030)


def bottle_r(y):
    ys = [p[1] for p in BOTTLE[1:-1]]
    rs = [p[0] for p in BOTTLE[1:-1]]
    return float(np.interp(y, ys, rs))


def molotov():
    """A glass bottle (BLEND) two-thirds full of fuel (an opaque liquid inside the glass), a twisted rag stuffed into
    the neck and hanging down its front; the `flame` on the rag (scale 0 until lit); a petrol lighter (zippo pattern,
    nodes on the viewmodel: it lives in the left hand) with its hinged lid and a little flame."""
    glass = revolve_y(BOTTLE, 28, "glass")
    liquid = revolve_y([(0.0, -0.0955), (0.0262, -0.0955), (0.0272, -0.0925), (0.0273, -0.088), (0.0273, 0.038),
                        (0.0262, 0.046), (0.0, 0.046)], 24, "liquid")
    rng = np.random.default_rng(5)
    path = [V(0.0, 0.112, 0.0), V(0.0, 0.128, 0.0), V(0.0008, 0.140, 0.0010), RAG_TOP, V(0.0, 0.1530, 0.0090),
            V(-0.0006, 0.1490, 0.0148), V(-0.0004, 0.1400, 0.0170), V(0.0004, 0.1280, 0.0172),
            V(0.0010, 0.1160, 0.0186), V(0.0006, 0.1040, 0.0220)]
    # it hangs down the neck's back (+Z: the side towards the thrower), where the lighter reaches it
    radii = [1.0, 1.05, 1.1, 1.15, 1.1, 1.0, 0.95, 1.0, 0.9, 0.75]
    rag = tube_along(path, 0.0052, "rag", seg=9, flat=0.55, twist=40.0, radii=radii)
    v = np.array(rag.v)
    v += rng.normal(0, 0.00035, v.shape)                     # crumpled cloth
    rag.v = [tuple(x) for x in v]
    flame = Mesh()
    flame.merge(teardrop((0, -0.002, 0), 0.052, 0.0115, "flame", lean=(0.003, 0.0)))
    flame.merge(teardrop((0.004, -0.004, 0.003), 0.034, 0.0075, "flame", lean=(0.006, 0.004)))
    flame.merge(teardrop((-0.004, -0.004, -0.003), 0.030, 0.0070, "flame", lean=(-0.004, -0.003)))
    parts = {"glass": (glass, V(0, 0, 0)), "liquid": (liquid, V(0, 0, 0)), "rag": (rag, V(0, 0, 0)),
             "flame": (flame.translated(RAG_TOP), RAG_TOP)}
    return parts, {"charm": V(0.0, 0.098, -0.0128), "top": RAG_TOP}


LIGHTER = {"w": 0.0380, "h": 0.0390, "d": 0.0125, "lid": 0.0150}


def lighter():
    """Zippo-pattern petrol lighter, origin at the middle of its bottom: the case, the hinged lid (origin at the hinge
    on the back right edge), the chimney with its holes and the wheel, and the wick flame."""
    w, h, d, lid_h = LIGHTER["w"], LIGHTER["h"], LIGHTER["d"], LIGHTER["lid"]
    case = sh.box(-w / 2, 0.0, -d / 2, w / 2, h, d / 2, 0.0022, "lighter")
    case.merge(sh.box(-0.0085, h, -0.0045, 0.0085, h + 0.0130, 0.0045, 0.0006, "lighter"))           # chimney
    wheel = revolve_z([(0.0, -0.0022), (0.0034, -0.0022), (0.0036, 0.0), (0.0034, 0.0022), (0.0, 0.0022)], 12, "lighter")
    case.merge(sh.place(wheel, origin=(0.0115, h + 0.0115, 0.0), x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(-1, 0, 0)))
    hinge = V(w / 2, h, d / 2)
    lid = sh.box(-w / 2, h + 0.0004, -d / 2, w / 2, h + lid_h, d / 2, 0.0022, "lighter")
    flame = Mesh()
    flame.merge(teardrop((0.0, h + 0.0110, 0.0), 0.026, 0.0052, "flame"))
    return {"lighter": (case, V(0, 0, 0)), "lighter_lid": (lid, hinge), "lighter_flame": (flame, V(0.0, h + 0.011, 0.0))}


# ========================================================================================== throwing knife
KNIFE_HW = 0.00185            # half thickness of the steel (3.7 mm)
KNIFE_TIP = V(0.0, 0.0, -0.1375)


def throwing_knife():
    """One-piece throwing knife (balanced: the centre of mass at the grip's front end): a spear-point blade with a
    double grind, a skeletonised tang wrapped in paracord, a lanyard hole at the end."""
    blade_prof = [(-0.1375, 0.0), (-0.1180, 0.0058), (-0.0880, 0.0115), (-0.0500, 0.0132), (-0.0300, 0.0128),
                  (-0.0200, 0.0110), (-0.0200, -0.0110), (-0.0300, -0.0128), (-0.0500, -0.0132), (-0.0880, -0.0115),
                  (-0.1180, -0.0058)]
    blade = sh.extrude_x(np.array(blade_prof), half_width=KNIFE_HW, bevel=((KNIFE_HW - 0.0002, 0.0), (0.0, 0.0050)),
                         material="blade")
    # the edge bevel as a separate brighter material: everything within the outer 3 mm of the outline
    v = np.array(blade.v)
    edge_faces = []
    for k, f in enumerate(blade.f):
        c = v[list(f)].mean(0)
        if abs(c[0]) < KNIFE_HW * 0.8 and not (-0.022 < c[2] < -0.019):
            edge_faces.append(k)
    for k in edge_faces:
        blade.m[k] = "blade_edge"
    tang_prof = [(-0.0205, 0.0110), (0.0640, 0.0098), (0.0700, 0.0080), (0.0720, 0.0), (0.0700, -0.0080),
                 (0.0640, -0.0098), (-0.0205, -0.0110)]
    hole = sh.circle(0.0605, 0.0, 0.0034, sh.arc_segments(0.0034, 12))
    tang = sh.extrude_x(np.array(tang_prof), holes=[hole[:, [0, 1]]], half_width=KNIFE_HW,
                        bevel=((0.0006, 0.0), (0.0, 0.0006)), material="blade")
    # paracord wrap: a lofted sleeve with one ridge per turn of the cord (crossing wraps)
    secs = []
    zs = np.linspace(-0.0120, 0.0500, 88)
    loop = sh.superellipse(0.0060, 0.0128, 2.6, sh.arc_segments(0.0128, 16))
    for z in zs:
        bump = 1.0 + 0.10 * np.cos(2 * np.pi * (z + 0.012) / 0.0047) ** 2
        taper = 1.0 - 0.15 * np.clip((z - 0.044) / 0.006, 0, 1) - 0.12 * np.clip((-0.008 - z) / 0.004, 0, 1)
        secs.append([(x * bump * taper, y * bump * taper, z) for x, y in loop])
    grip = sh.loft(secs, "grip")
    blade.merge(tang)
    return ({"blade": (blade, V(0, 0, 0)), "grip": (grip, V(0, 0, 0))},
            {"charm": V(0.0, -0.0034, 0.0605), "tip": KNIFE_TIP})


# ========================================================================================= adrenaline pen
PEN_R = 0.0102


def adrenaline():
    """Auto-injector (pen pattern, 0.155 long): a moulded barrel with a viewing window band and a printed label, the
    orange needle-end shroud (`plunger`: it slides out over the needle once fired), the blue safety cap at the thumb
    end (pulled off before use)."""
    barrel = revolve_z([(0.0, -0.0560), (0.0094, -0.0560), (0.0100, -0.0548), (PEN_R, -0.0520), (PEN_R, 0.0560),
                        (0.0094, 0.0590), (0.0072, 0.0600), (0.0, 0.0600)], 24, "barrel")
    label = revolve_z([(PEN_R + 0.0001, -0.0360), (PEN_R + 0.0004, -0.0355), (PEN_R + 0.0004, 0.0300),
                       (PEN_R + 0.0001, 0.0305)], 24, "label")
    # finger grip ribs under the label's end
    for z in np.linspace(0.036, 0.052, 5):
        label.merge(revolve_z([(PEN_R, z - 0.0011), (PEN_R + 0.0007, z - 0.0004), (PEN_R + 0.0007, z + 0.0004),
                               (PEN_R, z + 0.0011)], 24, "barrel"))
    barrel.merge(label)
    shroud = revolve_z([(0.0, -0.0960), (0.0060, -0.0960), (0.0072, -0.0950), (0.0086, -0.0900), (0.0092, -0.0560),
                        (0.0086, -0.0500), (0.0, -0.0500)], 20, "plunger")
    cap = revolve_z([(0.0, 0.0590), (0.0098, 0.0590), (0.0106, 0.0610), (0.0106, 0.0760), (0.0096, 0.0790),
                     (0.0060, 0.0800), (0.0, 0.0800)], 22, "cap")
    plunger_origin = V(0.0, 0.0, -0.0560)
    cap_origin = V(0.0, 0.0, 0.0590)
    return ({"barrel": (barrel, V(0, 0, 0)), "plunger": (shroud, plunger_origin), "cap": (cap, cap_origin)},
            {"charm": V(0.0, -PEN_R - 0.0010, 0.0540), "tip": V(0.0, 0.0, -0.0960)})


# signed distance functions (item design space) for wrapping the fingers and the contact checks
def sdf_sphere(p, r=FRAG_R):
    return np.linalg.norm(p) - r


def sdf_cylinder_y(p, r, y0, y1):
    d_r = np.hypot(p[0], p[2]) - r
    d_y = max(y0 - p[1], p[1] - y1)
    return max(d_r, d_y) if d_r > 0 or d_y > 0 else max(d_r, d_y)


def sdf_bottle(p):
    return np.hypot(p[0], p[2]) - bottle_r(p[1])


def sdf_box_z(p, hx, hy, z0, z1):
    q = np.abs(np.array([p[0], p[1]])) - np.array([hx, hy])
    dz = max(z0 - p[2], p[2] - z1)
    return max(q[0], q[1], dz)


def sdf_cyl_z(p, r, z0, z1):
    return max(np.hypot(p[0], p[1]) - r, z0 - p[2], p[2] - z1)
