"""P9 small parts, sights, attachments, magazines and the shell casing (design frame, see p9_model)."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p9_model import (LASER_LENS, MAG_LEN, REAR_SIGHT_Z, REDDOT_GLASS_Z, SIGHT_Y, chamfer_bevel, extrude_z,
                      mag_outline, round_bevel)


# ---------------------------------------------------------------------------- moving parts
def trigger_mesh():
    blade = [(0.0030, -0.0250), (0.0038, -0.0300), (0.0055, -0.0350), (0.0080, -0.0395), (0.0112, -0.0435),
             (0.0138, -0.0456), (0.0155, -0.0455), (0.0158, -0.0440), (0.0138, -0.0410), (0.0112, -0.0370),
             (0.0096, -0.0330), (0.0090, -0.0290), (0.0092, -0.0250)]
    safety = [(0.0026, -0.0305), (0.0036, -0.0352), (0.0060, -0.0398), (0.0090, -0.0431), (0.0101, -0.0423),
              (0.0076, -0.0388), (0.0056, -0.0347), (0.0048, -0.0305)]
    m = sh.extrude_x(blade, (), 0.0030, round_bevel(0.0011, 2), "steel_dark")
    m.merge(sh.extrude_x(safety, (), 0.0009, chamfer_bevel(0.0003), "steel_dark"))
    return m


def hammer_mesh():
    prof = [(0.0882, -0.0230), (0.0935, -0.0230), (0.0950, -0.0150), (0.0956, -0.0060), (0.0950, 0.0005),
            (0.0935, 0.0030), (0.0912, 0.0036), (0.0893, 0.0025), (0.0884, 0.0000), (0.0880, -0.0080)]
    m = sh.extrude_x(prof, (), 0.0037, round_bevel(0.0014, 2), "steel_dark")
    for k in range(3):                                   # grip grooves on the spur
        y = -0.0020 - k * 0.0022
        m.merge(sh.box(-0.0030, y, 0.0948, 0.0030, y + 0.0008, 0.0960, 0.0002, "steel_dark"))
    return m


def slide_stop_mesh():
    prof = [(0.0410, -0.0235), (0.0410, -0.0190), (0.0200, -0.0172), (0.0120, -0.0170), (0.0105, -0.0185),
            (0.0115, -0.0215), (0.0200, -0.0222), (0.0350, -0.0238)]
    m = sh.extrude_x(prof, (), 0.00075, chamfer_bevel(0.0003), "steel_dark", x_center=-0.01225)
    m.merge(sh.box(-0.0143, -0.0206, 0.0112, -0.0126, -0.0173, 0.0205, 0.0004, "steel_dark"))
    return m


# ------------------------------------------------------------------------------------ sights
def _loft_z(pairs, material):
    return sh.loft([np.stack([*s.T, np.full(len(s), z)], 1) for s, z in pairs], material)


def iron_sights_mesh():
    rear = np.array([(-0.0110, 0.0118), (0.0110, 0.0118), (0.0110, 0.0168), (0.0098, SIGHT_Y),
                     (0.0018, SIGHT_Y), (0.0018, 0.0150), (-0.0018, 0.0150), (-0.0018, SIGHT_Y),
                     (-0.0098, SIGHT_Y), (-0.0110, 0.0168)])
    low = rear.copy()
    low[:, 1] = np.minimum(low[:, 1], 0.0150)
    m = _loft_z([(low, 0.0770), (rear, 0.0792), (rear, REAR_SIGHT_Z - 0.0005),
                 (sh.offset_loop(rear, 0.0005), REAR_SIGHT_Z)], "steel_dark")
    post = np.array([(-0.0017, 0.0118), (0.0017, 0.0118), (0.0017, SIGHT_Y), (-0.0017, SIGHT_Y)])
    plow = post.copy()
    plow[:, 1] = np.minimum(plow[:, 1], 0.0140)
    m.merge(_loft_z([(plow, -0.0842), (post, -0.0822), (post, -0.0782), (sh.offset_loop(post, 0.0004), -0.0778)],
                    "steel_dark"))
    for x in (-0.0058, 0.0058):
        m.merge(sh.disc((x, 0.0163, REAR_SIGHT_Z + 0.00006), (0, 0, 1), 0.00105, 12, "sight_dots"))
    m.merge(sh.disc((0, 0.0166, -0.0778 + 0.00006), (0, 0, 1), 0.00095, 12, "sight_dots"))
    return m


def reddot_mesh():
    m = sh.box(-0.0115, 0.0124, 0.0335, 0.0115, 0.0180, 0.0725, 0.0010, "attachment")
    hood_out = sh.rounded_rect(-0.0115, 0.0168, 0.0115, 0.0345, (0.0, 0.0, 0.0045, 0.0045), 3)
    hood_in = sh.rounded_rect(-0.0091, 0.0193, 0.0091, 0.0327, (0.0012, 0.0012, 0.0028, 0.0028), 3)
    m.merge(extrude_z(hood_out, [hood_in], 0.0380, 0.0470, chamfer_bevel(0.0006), "attachment"))
    m.merge(sh.box(-0.0105, 0.0170, 0.0560, 0.0105, 0.0225, 0.0725, 0.0012, "attachment"))
    m.merge(sh.box(-0.0123, 0.0188, 0.0598, -0.0100, 0.0210, 0.0642, 0.0005, "attachment"))  # button
    m.merge(sh.box(0.0100, 0.0186, 0.0605, 0.0119, 0.0214, 0.0660, 0.0004, "attachment"))    # battery cap
    g = Mesh()
    g.add_poly([(-0.0093, 0.0191, REDDOT_GLASS_Z), (0.0093, 0.0191, REDDOT_GLASS_Z),
                (0.0093, 0.0329, REDDOT_GLASS_Z), (-0.0093, 0.0329, REDDOT_GLASS_Z)], "glass")
    return m.merge(g)


def reticle_mesh(size=0.0018):
    h = size / 2
    m = Mesh()
    m.add_poly([(-h, -h, 0), (h, -h, 0), (h, h, 0), (-h, h, 0)], "reticle",
               uvs=[(0, 1), (1, 1), (1, 0), (0, 0)])
    return m


def laser_mesh():
    m = sh.box(-0.0118, -0.0545, -0.0790, 0.0118, -0.0362, -0.0390, 0.0020, "attachment")
    for s in (-1, 1):
        x0, x1 = sorted((s * 0.0097, s * 0.0119))
        m.merge(sh.box(x0, -0.0366, -0.0700, x1, -0.0322, -0.0480, 0.0005, "attachment"))       # rail clamp
        x0, x1 = sorted((s * 0.0112, s * 0.0131))
        m.merge(sh.box(x0, -0.0490, -0.0432, x1, -0.0440, -0.0378, 0.0005, "attachment"))       # paddles
    bez = sh.revolve([(0.0028, -0.0801), (0.0042, -0.0801), (0.0042, -0.0785)], 20, "attachment", precision=True)
    m.merge(bez.translated((LASER_LENS[0], LASER_LENS[1], 0)))
    m.merge(sh.disc(LASER_LENS + np.array([0, 0, 0.0001]), (0, 0, -1), 0.0029, 16, "laser_lens"))
    m.merge(sh.box(-0.0090, -0.0430, -0.0796, -0.0015, -0.0400, -0.0786, 0.0003, "attachment"))  # brand strip
    return m


# ----------------------------------------------------------------------------- magazines
def _mag_place(mesh):
    """Builder space (x, y=front, z=up the mag) -> mag-local space (+Y up the mag, -Z front)."""
    return sh.place(mesh, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))


def cartridge(length=0.0290, r=0.00493, seg=14, material="brass"):
    """9 mm round along -Z (bullet forward), case head at z = 0."""
    prof = [(0.0, 0.0), (r, 0.0), (r, -0.0150), (r * 0.97, -0.0190), (r * 0.90, -0.0225),
            (r * 0.70, -0.0262), (r * 0.35, -length + 0.0006), (0.0, -length)]
    return sh.revolve(prof, seg, material, precision=True)


def mag_mesh(extended=False):
    ext = 0.025 if extended else 0.0
    m = _mag_place(sh.prism_z(mag_outline(), -ext, MAG_LEN, 0.0006, "mag"))
    holes = (0.030, 0.045, 0.060, 0.075) + ((0.015, 0.0) if extended else ())
    # Actual punched rear sheet with chamfered ports; old gold dots floated on a solid wall.
    # Match the existing flat face exactly; do not alter feed height, envelope or animation nodes.
    keep = [i for i, f in enumerate(m.f)
            if not all(abs(m.v[j][2] - 0.01625) < 1e-9 for j in f)]
    m.f, m.m, m.uv = ([items[i] for i in keep] for items in (m.f, m.m, m.uv))
    outer = [(-0.00775, -ext + 0.0006), (0.00775, -ext + 0.0006),
             (0.00775, MAG_LEN - 0.0006), (-0.00775, MAG_LEN - 0.0006)]
    ports = [np.array([(-0.0040 + 0.0013 * np.cos(a), y + 0.0013 * np.sin(a))
                       for a in np.linspace(0, 2 * np.pi, 12, endpoint=False)]) for y in holes]
    panel = extrude_z(outer, ports, 0.01525, 0.01625, chamfer_bevel(0.00012), "mag")
    m.merge(panel)
    # Recessed brass visible behind the sheet, no new material or draw batch.
    for y in holes:
        m.merge(sh.disc((-0.0040, y, 0.0135), (0, 0, 1), 0.00165, 12, "brass"))
    if extended:
        rings = [(mag_outline(0.0018), -ext - 0.0090), (mag_outline(0.0030), -ext - 0.0075),
                 (mag_outline(0.0026), -ext - 0.0030), (mag_outline(0.0012), -ext - 0.0005)]
        pad = _loft_z(rings, "mag_base")
        for k in range(3):                               # grip ridges on the pad
            z = -ext - 0.0068 + k * 0.0018
            pad.merge(sh.box(-0.0146, -0.0155, z, 0.0146, 0.0160, z + 0.0007, 0.0002, "mag_base"))
        m.merge(_mag_place(pad))
    else:
        plate = sh.offset_loop(mag_outline(), -0.0015)
        plate[:, 1] += np.where(plate[:, 1] > 0.0, 0.0012, 0.0)      # finger lip at the front
        m.merge(_mag_place(sh.prism_z(plate, -0.0058, 0.0002, 0.0012, "mag_base")))
    m.merge(cartridge().translated((0.0028, MAG_LEN + 0.0046, 0.0150)))   # top round, bullet forward
    return m


def shell_mesh(scale=1.25):
    prof = [(0.0, 0.0040), (0.0041, 0.0040), (0.0041, -0.00955), (0.00482, -0.00955), (0.00496, 0.0064),
            (0.00432, 0.0070), (0.00432, 0.0081), (0.00498, 0.0085), (0.00498, 0.00955), (0.0, 0.00955)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 20, "brass", precision=True)
