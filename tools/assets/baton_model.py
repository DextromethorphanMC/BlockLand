"""Expandable baton geometry (VIEWMODEL_SPEC section 45). Design frame (metres): the baton runs along Z, the end cap at
+Z, the tip towards -Z; z = 0 is the middle of the grip. Original design after a common 21-inch friction-lock baton:
a ribbed rubber grip on the handle tube, an end cap with a lanyard loop, and two nested steel shafts that slide out of
the handle and lock by friction on their tapered ends - 0.55 m from the cap to the tip extended, 0.22 m collapsed. The
handle and the two shafts are separate parts: shaft_1 slides in the handle, shaft_2 in shaft_1."""
import numpy as np

import shapes as sh
from meshkit import Mesh

CAP_Z = (0.078, 0.094)             # end cap (behind the grip)
GRIP_Z = (0.078, -0.075)           # rubber grip over the handle tube
HANDLE_Z = (0.078, -0.105)         # handle tube, its front collar the last 6 mm
R_HANDLE, R_GRIP, R_COLLAR = 0.0118, 0.0134, 0.0124
SHAFT1_Z = (-0.090, -0.262)        # extended: overlapping the handle by 15 mm (the friction taper)
R_SHAFT1 = (0.0090, 0.0084)        # back, front (it tapers a little), its bushing lip at the front
SHAFT2_Z = (-0.250, -0.430)
R_SHAFT2 = (0.0066, 0.0060)
KNOB_Z = (-0.428, -0.452)          # the tip knob
R_KNOB = 0.0098
COLLAPSE = (0.158, 0.168)          # how far shaft_1 slides back into the handle, shaft_2 into shaft_1
GRIP = np.array([0.0, 0.0, 0.0])   # right-hand grip point = knife root (middle of the grip)
TIP = np.array([0.0, 0.0, KNOB_Z[1]])
LOOP_C = np.array([0.0, 0.0, CAP_Z[1] + 0.0060])   # lanyard loop behind the cap (charm mount)


def _lathe(profile, material, segments=24):
    """Closed solid of revolution about Z from an (r, z) profile running from the axis round to the axis."""
    m = sh.revolve(profile, segments, material, precision=True)
    return sh.orient_outward(m)


def handle_mesh():
    """Handle tube with its front collar, the ribbed rubber grip, the end cap and the lanyard loop."""
    m = Mesh()
    z0, z1 = HANDLE_Z
    # the tube shows only in front of the grip: from inside the grip to the collar, open at the front (the shaft's bore)
    m.merge(_lathe([(0.0, -0.070), (R_HANDLE, -0.070), (R_HANDLE, z1 + 0.0065), (R_COLLAR, z1 + 0.0055),
                    (R_COLLAR, z1 + 0.0012), (R_COLLAR - 0.0008, z1), (0.0094, z1), (0.0094, z1 + 0.004),
                    (0.0, z1 + 0.004)], "hardware"))
    # grip: rubber rings with grooves every 6 mm and a swell under the palm (six profile points per groove: a flat
    # land, a crisp groove)
    zs, rs = [], []
    a, b = GRIP_Z
    n = 25 * 6
    for k in range(n + 1):
        z = a + (b - a) * k / n
        u = k / n
        swell = 1.0 + 0.06 * np.sin(np.pi * u) ** 1.5
        groove = 0.0 if k in (0, n) else 0.0009 * (0.5 + 0.5 * np.cos(2 * np.pi * (z - a) / 0.006)) ** 3
        zs.append(z)
        rs.append(R_GRIP * swell - groove)
    prof = [(0.0, a)] + [(r, z) for r, z in zip(rs, zs)]
    prof[1] = (R_GRIP * 0.94, a)                       # rounded lips at both ends
    prof[-1] = (R_GRIP * 0.94, b)
    prof.append((0.0, b))
    m.merge(_lathe(prof, "grip", 28))
    # end cap: a short cylinder domed at the back
    c0, c1 = CAP_Z
    m.merge(_lathe([(0.0, c0), (R_GRIP * 1.02, c0), (R_GRIP * 1.03, c0 + 0.006), (R_GRIP * 0.95, c1 - 0.004),
                    (R_GRIP * 0.75, c1 - 0.0012), (R_GRIP * 0.45, c1), (0.0, c1)], "hardware", 28))
    # lanyard loop: a flat ring standing in the y-z plane behind the cap, its hole along X
    ring = sh.revolve([(0.0028, -0.0013), (0.0050, -0.0013), (0.0050, 0.0013), (0.0028, 0.0013), (0.0028, -0.0013)],
                      16, "hardware", precision=True)
    m.merge(sh.place(ring, origin=LOOP_C, x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(1, 0, 0)))
    return m


def shaft1_mesh():
    """Middle shaft: a thin-walled tube tapering forward, the friction flare at its back, a bushing lip at its front."""
    z0, z1 = SHAFT1_Z
    r0, r1 = R_SHAFT1
    return _lathe([(0.0, z0), (r0 + 0.0006, z0), (r0 + 0.0006, z0 - 0.010), (r0, z0 - 0.016), (r1, z1 + 0.008),
                   (r1 + 0.0010, z1 + 0.0065), (r1 + 0.0010, z1 + 0.0012), (r1 + 0.0002, z1), (0.0072, z1),
                   (0.0072, z1 + 0.004), (0.0, z1 + 0.004)], "shaft")


def shaft2_mesh():
    """Tip shaft: a solid rod tapering forward, its friction flare at the back, the tip knob at the front."""
    z0, z1 = SHAFT2_Z
    r0, r1 = R_SHAFT2
    k0, k1 = KNOB_Z
    prof = [(0.0, z0), (r0 + 0.0006, z0), (r0 + 0.0006, z0 - 0.010), (r0, z0 - 0.016), (r1, k0)]
    # knob: an ogive from the shaft swelling to R_KNOB and closing at the tip
    for u in np.linspace(0.0, 1.0, 9)[1:]:
        z = k0 + (k1 - k0) * u
        r = r1 + (R_KNOB - r1) * np.sin(np.pi * min(u / 0.45, 1.0) / 2) if u <= 0.45 else \
            R_KNOB * np.sqrt(max(0.0, 1 - ((u - 0.45) / 0.55) ** 2))
        prof.append((max(r, 0.0), z))
    if prof[-1][0] > 0:
        prof.append((0.0, k1))
    return _lathe(prof, "shaft", 24)


def parts():
    """Design-frame meshes per node (extended)."""
    return {"handle": handle_mesh(), "shaft_1": shaft1_mesh(), "shaft_2": shaft2_mesh()}
