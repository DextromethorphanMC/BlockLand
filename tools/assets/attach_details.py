"""The small parts of the attachments (VIEWMODEL_SPEC section 53: detail that reads at viewmodel distance), one
builder per attachment, merged into its mesh by attach_models / stock_models / underbarrel_models. Everything here
stays outside the optics' sight lines and lenses (side and corner screws, knurled bands round the outside of tubes and
turrets) and keeps every mount point where it was."""
import numpy as np

import detail_parts as dp
import shapes as sh
from meshkit import Mesh


def _am():
    import attach_models as am
    return am


def knurled_cap(r, length, flutes, depth, material):
    """A solid knurled cap (turret, knob) along +Z from 0 to `length`: fluted sides, its ends eased in."""
    n = flutes * 4
    a = np.linspace(0, 2 * np.pi, n, endpoint=False)
    groove = (0.5 + 0.5 * np.cos(flutes * a)) ** 2
    b = min(0.0005, length * 0.2)
    rings = []
    for z, w, k in ((0.0, 0.0, 1.0), (b, 1.0, 1.0), (length - b, 1.0, 1.0), (length, 0.0, 0.97)):
        rr = ((r - depth) + w * depth * (1 - groove)) * k
        rings.append(np.stack([rr * np.cos(a), rr * np.sin(a), np.full(n, z)], 1))
    return sh.loft(rings, material)


def _knurl_on(r, length, origin, normal, material="steel_dark", ribs=4, depth=0.0003, seg=48):
    """A knurled cap of radius r and `length` along `normal`, its near end at `origin` (about seg / 4 flutes)."""
    return dp.on_surface(knurled_cap(r, length, max(12, seg // 2), depth, material), origin, normal)


def knurled_band(r_in, r, z0, z1, flutes, depth, material, seg_per=4):
    """A hollow knurled band round a tube (its bore stays open): a plain ring from r_in to the flute bottoms, and the
    fluted skin over it, its ends eased down onto the ring. Along +Z from z0 to z1, centred on the Z axis."""
    inner = r - depth - 0.00008                      # just under the flute bottoms: the two surfaces never coincide
    m = sh.revolve([(r_in, z0), (r_in, z1), (inner, z1), (inner, z0), (r_in, z0)], max(48, flutes * seg_per),
                   material)
    n = flutes * seg_per
    a = np.linspace(0, 2 * np.pi, n, endpoint=False)
    groove = (0.5 + 0.5 * np.cos(flutes * a)) ** 2
    b = min(0.0005, (z1 - z0) * 0.2)
    rings = []
    for z, w in ((z0, 0.0), (z0 + b, 1.0), (z1 - b, 1.0), (z1, 0.0)):
        rr = inner + w * (r - inner) * (1 - groove) + (1 - w) * 0.0
        rings.append(np.stack([rr * np.cos(a), rr * np.sin(a), np.full(n, z)], 1))
    skin = sh.loft(rings, material, cap_start=False, cap_end=False, auto_orient=False)
    # an open skin cannot be wound by its volume: face it outwards, judged over all its faces against the radius
    v = np.array(skin.v)
    score = 0.0
    for face in skin.f:
        f = v[list(face)]
        score += np.dot(np.cross(f[1] - f[0], f[2] - f[0])[:2], f.mean(0)[:2])
    if score < 0:
        skin.f = [tuple(reversed(face)) for face in skin.f]
    m.merge(skin)
    return m


def _screws(points, r, material="steel_dark"):
    """Socket screws at (origin, normal) pairs."""
    m = Mesh()
    head = dp.socket_screw(r, material)
    for origin, normal in points:
        m.merge(dp.on_surface(head, origin, normal))
    return m


def mini_reddot():
    """Windage screw on the right flank, a knurled battery cap on the left."""
    # in the body's own anodised finish (the slide optic has no separate steel parts)
    m = _screws([((0.0119, 0.0095, 0.0080), (1, 0, 0))], 0.0014, "attachment")
    m.merge(_knurl_on(0.0042, 0.0016, (-0.0121, 0.0085, 0.0080), (-1, 0, 0), "attachment", ribs=3, seg=36))
    return m


def reddot(tube_h, tube_r):
    """Knurled caps over the elevation and windage turrets, a knurled sunshade band round the tube's front, four
    riser screws."""
    m = _knurl_on(0.0053, 0.0040, (0.0, tube_h + tube_r + 0.0020, 0.0040), (0, 1, 0), ribs=4)
    m.merge(_knurl_on(0.0053, 0.0040, (tube_r + 0.0020, tube_h, 0.0040), (1, 0, 0), ribs=4))
    m.merge(knurled_band(0.0146, 0.0158, -0.0396, -0.0378, 40, 0.0003, "steel_dark").translated((0.0, tube_h, 0.0)))
    m.merge(_screws([((sx * 0.0080, 0.0095, z), (sx, 0, 0)) for sx in (-1, 1) for z in (-0.0100, 0.0100)], 0.0013))
    return m


def reddot_rect(cy, zg, depth, w, body_top):
    """Windage and elevation screws on the frame's right side, two screws on the base's front corners."""
    zm = zg - depth / 2
    m = _screws([((w / 2 + 0.0021, cy + 0.0040, zm), (1, 0, 0)), ((w / 2 + 0.0021, cy - 0.0040, zm), (1, 0, 0))], 0.0011)
    m.merge(_screws([((sx * 0.0120, body_top, zg - depth - 0.0010), (0, 1, 0)) for sx in (-1, 1)], 0.0011))
    return m


def scope_2x(h):
    """Clamp screws, two screws on each side of the prism body, a knurled grip band behind the objective's lip."""
    m = _screws([((sx * 0.0116, 0.0045, z), (sx, 0, 0)) for sx in (-1, 1) for z in (-0.0280, 0.0280)], 0.0013)
    m.merge(_screws([((sx * 0.0170, h - 0.0020, z), (sx, 0, 0)) for sx in (-1, 1) for z in (-0.0240, 0.0140)], 0.0014))
    m.merge(knurled_band(0.0179, 0.0191, -0.0795, -0.0740, 48, 0.0003, "steel_dark").translated((0.0, h, 0.0)))
    return m


def scope_sniper(h, r_tube):
    """Ring cap screws on top of both rings, four saddle screws, a knurled grip band round the objective bell."""
    m = _screws([((sx * 0.0055, h + r_tube + 0.0044, z), (0, 1, 0)) for sx in (-1, 1) for z in (-0.0500, 0.0400)], 0.0016)
    m.merge(_screws([((sx * 0.0192, h + sy * 0.0130, z), (sx, 0, 0)) for sx in (-1, 1) for sy in (-1, 1)
                     for z in (-0.0240,)], 0.0012))
    m.merge(knurled_band(0.0305, 0.0316, -0.2120, -0.2040, 72, 0.0004, "scope").translated((0.0, h, 0.0)))
    return m


def stock_pad_ribs(x_half, y0, y1, z_face, count, material="stock_pad"):
    """Grip ribs across a recoil pad's back face (at z_face, facing +Z)."""
    m = Mesh()
    for k in range(count):
        y = y0 + (y1 - y0) * (k + 0.5) / count
        m.merge(sh.box(-x_half, y - 0.0026, z_face - 0.0010, x_half, y + 0.0026, z_face + 0.0011, 0.0009, material))
    return m


def tactical_stock():
    """Ribs across the pad, the hinge's release button, vent slots along the cheek rest's sides."""
    m = stock_pad_ribs(0.0160, -0.1060, 0.0090, 0.2420, 6)
    m.merge(dp.on_surface(sh.revolve([(0.0, 0.0018), (0.0035, 0.0018), (0.0042, 0.0006), (0.0042, -0.0010),
                                      (0.0, -0.0010)], 24, "steel_dark"), (0.0130, -0.0050, 0.0150), (1, 0, 0)))
    for sx in (-1, 1):
        for k in range(4):
            z = 0.1050 + k * 0.0200
            m.merge(sh.box(sx * 0.0141 - 0.0003, 0.0010, z, sx * 0.0141 + 0.0003, 0.0060, z + 0.0120, 0.0002,
                           "steel_dark"))
    return m


def wire_stock():
    """Weld beads where the rods meet the butt plate, two screws through the plate's front holding the pad."""
    m = Mesh()
    bead = sh.revolve([(0.0, -0.0040), (0.0036, -0.0028), (0.0042, 0.0), (0.0036, 0.0028), (0.0, 0.0040)], 16,
                      "steel_dark")
    for sx in (-1, 1):
        x = sx * 0.0068 * 2.0
        for y in (-0.0080, -0.0860):
            m.merge(bead.translated((x, y, 0.2030)))
    m.merge(_screws([((0.0, y, 0.2030), (0, 0, -1)) for y in (-0.0250, -0.0650)], 0.0018))
    return m


def angled_grip():
    """Clamp screw heads either side, horizontal grip grooves along both flanks of the ramp."""
    m = _screws([((sx * 0.0112, -0.0030, z), (sx, 0, 0)) for sx in (-1, 1) for z in (-0.0200, 0.0200)], 0.0012)
    for sx in (-1, 1):
        for k in range(5):
            y = -0.0140 - k * 0.0048
            m.merge(sh.box(sx * 0.0116 - 0.0003, y - 0.0008, -0.0280 + k * 0.0035, sx * 0.0116 + 0.0003, y + 0.0008,
                           0.0300 - k * 0.0060, 0.0002, "steel_dark"))
    return m


def bipod():
    """A knurled tension knob on the yoke, extension notches round the lower legs, tread ribs under the feet."""
    m = _knurl_on(0.0060, 0.0050, (0.0170, -0.0160, 0.0000), (1, 0, 0), ribs=5)
    for sx in (-1, 1):
        x = sx * 0.0125
        for k in range(5):
            z = -0.1250 - k * 0.0080
            c = np.array([x * 1.04 + (x * 1.08 - x * 1.04) * (k * 0.0080 + 0.010) / 0.055, -0.0182, z])
            ring = sh.revolve([(0.0040, -0.0006), (0.0046, -0.0003), (0.0046, 0.0003), (0.0040, 0.0006)], 24,
                              "attachment")
            m.merge(ring.translated(c))
        for k in range(4):
            z = -0.1830 + k * 0.0040
            m.merge(sh.box(x * 1.08 - 0.0048, -0.0242, z, x * 1.08 + 0.0048, -0.0233, z + 0.0018, 0.0003, "stock_pad"))
    return m


def tommy_grip():
    """The two steel screws that hold the wooden grip to its barrel clamp, either side near the top."""
    return _screws([((x, -0.0040, z), (np.sign(x), 0, 0)) for x in (0.0111, -0.0113) for z in (-0.0250, 0.0500)], 0.0016)


def charm_grass(ring_y):
    """A split ring: the top ring's second turn, a hair behind the first."""
    return _am()._ring(0.0042, 0.0029, 0.0011, (0.0, ring_y, 0.0012), "xy", "charm_metal")
