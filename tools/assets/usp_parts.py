"""USP small parts, sights, magazines and the .45 case (design frame, see usp_model)."""
import numpy as np

import shapes as sh
from p9_model import chamfer_bevel, round_bevel
from usp_model import MAG_HD, MAG_LEN, REAR_SIGHT_Z, SIGHT_Y, SY1, SZ0, mag_outline


# ---------------------------------------------------------------------------- moving parts
def trigger_mesh():
    """Curved trigger blade, smooth face (no safety blade), hinged at TRIGGER_PIVOT."""
    blade = [(0.0024, -0.0255), (0.0030, -0.0310), (0.0046, -0.0360), (0.0072, -0.0408), (0.0104, -0.0446),
             (0.0132, -0.0468), (0.0150, -0.0466), (0.0154, -0.0450), (0.0134, -0.0420), (0.0108, -0.0382),
             (0.0092, -0.0340), (0.0086, -0.0295), (0.0090, -0.0255)]
    m = sh.extrude_x(blade, (), 0.0032, round_bevel(0.0012, 1), "steel_dark")
    m.merge(sh.box(-0.0020, -0.0262, -0.0010, 0.0020, -0.0236, 0.0110, 0.0004, "steel_dark"))   # trigger bar boss
    return m


def hammer_mesh():
    """Exposed ring hammer (spur with a round hole), upright at rotation 0, pivot at HAMMER_PIVOT."""
    c = np.array([0.0952, -0.0012])
    ring = [(c[0] + 0.0060 * np.cos(a), c[1] + 0.0060 * np.sin(a)) for a in np.radians(np.linspace(-35, 215, 10))]
    prof = [(0.0894, -0.0228), (0.0958, -0.0228), (0.0976, -0.0120)] + ring + [(0.0893, -0.0110)]
    hole = sh.circle(c[0], c[1], 0.0027, 8)[::-1]
    m = sh.extrude_x(prof, [hole], 0.0036, round_bevel(0.0012, 1), "steel_dark")
    for k in range(3):                                   # grip grooves across the top of the ring
        a = np.radians(70 + 20 * k)
        z, y = c[0] + 0.0060 * np.cos(a), c[1] + 0.0060 * np.sin(a)
        m.merge(sh.box(-0.0030, y - 0.0003, z - 0.0004, 0.0030, y + 0.0005, z + 0.0004, 0.0002, "steel_dark"))
    return m


def slide_stop_mesh():
    """Long slide-stop lever on the left of the frame with a serrated thumb tab (pivot at STOP_PIVOT)."""
    prof = [(0.0420, -0.0244), (0.0420, -0.0196), (0.0210, -0.0180), (0.0115, -0.0178), (0.0098, -0.0196),
            (0.0108, -0.0228), (0.0210, -0.0232), (0.0360, -0.0248)]
    m = sh.extrude_x(prof, (), 0.00078, chamfer_bevel(0.0003), "steel_dark", x_center=-0.01275)
    m.merge(sh.box(-0.0149, -0.0214, 0.0104, -0.0131, -0.0180, 0.0200, 0.0004, "steel_dark"))
    return m


# ------------------------------------------------------------------------------------ sights
def _loft_z(pairs, material):
    return sh.loft([np.stack([*s.T, np.full(len(s), z)], 1) for s, z in pairs], material)


def iron_sights_mesh():
    """Square-notch rear sight with two dots, front post with one (slide space = design frame)."""
    base = SY1 - 0.0010
    rear = np.array([(-0.0118, base), (0.0118, base), (0.0118, SIGHT_Y - 0.0016), (0.0104, SIGHT_Y),
                     (0.0020, SIGHT_Y), (0.0020, SIGHT_Y - 0.0040), (-0.0020, SIGHT_Y - 0.0040), (-0.0020, SIGHT_Y),
                     (-0.0104, SIGHT_Y), (-0.0118, SIGHT_Y - 0.0016)])
    low = rear.copy()
    low[:, 1] = np.minimum(low[:, 1], SIGHT_Y - 0.0040)
    m = _loft_z([(low, REAR_SIGHT_Z - 0.0100), (rear, REAR_SIGHT_Z - 0.0076), (rear, REAR_SIGHT_Z - 0.0005),
                 (sh.offset_loop(rear, 0.0005), REAR_SIGHT_Z)], "steel_dark")
    post = np.array([(-0.0018, base), (0.0018, base), (0.0018, SIGHT_Y), (-0.0018, SIGHT_Y)])
    plow = post.copy()
    plow[:, 1] = np.minimum(plow[:, 1], SY1 + 0.0012)
    fz = SZ0 + 0.0060
    m.merge(_loft_z([(plow, fz), (post, fz + 0.0022), (post, fz + 0.0060), (sh.offset_loop(post, 0.0004), fz + 0.0064)],
                    "steel_dark"))
    for x in (-0.0062, 0.0062):
        m.merge(sh.disc((x, SIGHT_Y - 0.0021, REAR_SIGHT_Z + 0.00006), (0, 0, 1), 0.0011, 12, "sight_dots"))
    m.merge(sh.disc((0, SIGHT_Y - 0.0018, fz + 0.0064 + 0.00006), (0, 0, 1), 0.0010, 12, "sight_dots"))
    return m


# ----------------------------------------------------------------------------- magazines
def _mag_place(mesh):
    """Builder space (x, y=front, z=up the mag) -> mag-local space (+Y up the mag, -Z front)."""
    return sh.place(mesh, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))


def cartridge(length=0.0322, r=0.0060, seg=10, material="brass"):
    """.45 ACP round along -Z (bullet forward), case head at z = 0: a short fat case and a round-nose bullet."""
    prof = [(0.0, 0.0), (r, 0.0), (r, -0.0228), (r * 0.95, -0.0240), (r * 0.86, -0.0270),
            (r * 0.62, -0.0298), (r * 0.30, -length + 0.0005), (0.0, -length)]
    return sh.revolve(prof, seg, material, precision=True)


def mag_mesh(extended=False):
    ext = 0.022 if extended else 0.0
    m = _mag_place(sh.prism_z(mag_outline(), -ext, MAG_LEN, 0.0006, "mag"))
    if extended:
        rings = [(mag_outline(0.0016), -ext - 0.0094), (mag_outline(0.0030), -ext - 0.0078),
                 (mag_outline(0.0028), -ext - 0.0030), (mag_outline(0.0012), -ext - 0.0005)]
        pad = _loft_z(rings, "mag_base")
        for k in range(3):                               # grip ridges on the pad
            z = -ext - 0.0070 + k * 0.0019
            pad.merge(sh.box(-0.0150, -MAG_HD + 0.0010, z, 0.0150, MAG_HD + 0.0016, z + 0.0007, 0.0002, "mag_base"))
        m.merge(_mag_place(pad))
    else:
        plate = sh.offset_loop(mag_outline(), -0.0016)
        plate[:, 1] += np.where(plate[:, 1] > 0.0, 0.0014, 0.0)      # finger lip at the front
        m.merge(_mag_place(sh.prism_z(plate, -0.0062, 0.0002, 0.0013, "mag_base")))
    m.merge(cartridge().translated((0.0034, MAG_LEN + 0.0056, 0.0165)))   # top round, bullet forward
    holes = (0.030, 0.044, 0.058, 0.072) + ((0.016, 0.002) if extended else ())
    for y in holes:                                      # witness holes on the back of the body
        m.merge(sh.disc((-0.0045, y, MAG_HD + 0.00008), (0, 0, 1), 0.0013, 8, "brass"))
    return m


def shell_mesh(scale=1.25):
    """Spent .45 ACP case (fat, straight-walled), enlarged by `scale` like the P9's so it reads on screen."""
    prof = [(0.0, 0.0050), (0.0052, 0.0050), (0.0052, -0.0114), (0.0058, -0.0114), (0.0060, 0.0080),
            (0.0054, 0.0087), (0.0054, 0.0100), (0.0061, 0.0104), (0.0061, 0.0114), (0.0, 0.0114)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 20, "brass", precision=True)
