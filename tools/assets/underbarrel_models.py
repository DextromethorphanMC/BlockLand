"""Round H attachments (VIEWMODEL_SPEC section 35): the rectangular red dot `reddot_rect` (optic, no magnification) and
the underbarrel slot's `vertical_grip`, `angled_grip` and `bipod`.

Same frame as every attachment (attach_models.py): origin = the mount point, +X right, +Y up, -Z towards the muzzle.
An optic stands on the rail (+Y); an underbarrel attachment clamps to the underside of the handguard / rail and hangs
along -Y. attach_models.MODELS picks the builders up from MODELS below."""
import numpy as np

import p9_parts as pp
import shapes as sh
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z
from stock_models import rod


def _attach():
    import attach_models as am          # imported late: attach_models registers these builders when it loads
    return am


# ------------------------------------------------------------------------------------- rectangular red dot
RECT_W, RECT_H = 0.0320, 0.0220    # window (glass) width / height
RECT_R = 0.0020                    # window corner radius
RECT_C = 0.0242                    # window centre over the mount point (the sight line)
RECT_Z = -0.0120                   # glass plane (the frame's rear face)
RECT_D = 0.0035                    # frame depth in front of the glass
RECT_DRAFT = (0.13, 0.11)          # the frame's inner walls open forward more steeply than the sight lines from the eye
                                   # to the window's edges (0.114, 0.079): none of them shows through the glass
RECT_BEVEL = 0.0008                # the window's rear edge, chamfered towards the eye
BODY_TOP = 0.0115                  # the base housing stays under the sight lines to the window's bottom edge


def _flare(loop, t, cy, dx, dy):
    p = np.array(loop, float)
    p[:, 0] += np.sign(p[:, 0]) * dx * t
    p[:, 1] += np.where(p[:, 1] > cy, dy * t, -dy * t)
    return p


def _at_z(loop, z):
    return np.column_stack([loop, np.full(len(loop), z)])


def reddot_rect():
    """Open reflex sight with a rectangular window: a low picatinny clamp, a flat base housing (brightness buttons on
    the left, battery tray on the right, the LED emitter at the rear), and a thin rectangular frame round the glass.
    The glass is one flat face at the frame's rear (UV 0..1 across it, u along +X, v down); the frame's inner walls
    open forward and are matte `interior`. A `reticle` quad (the dot under a shader pack) on the sight line, `aim`
    0.14 behind it."""
    am = _attach()
    p = am.Part()
    cy, zg, D, b = RECT_C, RECT_Z, RECT_D, RECT_BEVEL
    dx, dy = RECT_DRAFT
    # rail clamp with cross-slot lugs and the clamp screw (right)
    m = sh.box(-0.0105, 0.0, -0.0230, 0.0105, 0.0055, 0.0230, 0.0008, "attachment")
    for z in (-0.0150, -0.0050, 0.0050, 0.0150):
        m.merge(sh.box(-0.0108, 0.0004, z - 0.0018, 0.0108, 0.0018, z + 0.0018, 0.0003, "attachment"))
    m.merge(am._cyl_axis(0.0034, 0.0040, (0.0125, 0.0028, 0.0060), "x", "steel_dark", 12))
    # base housing: flat, its rear end ramps down under the sight lines; the emitter hood at the rear
    zr, zf = 0.0200, zg - D - 0.0030
    body = [(zf + 0.0015, 0.0045), (zr - 0.0015, 0.0045), (zr, 0.0060), (zr, 0.0095), (zr - 0.0030, BODY_TOP - 0.0010),
            (zg + 0.0010, BODY_TOP), (zf + 0.0020, BODY_TOP), (zf, BODY_TOP - 0.0020), (zf, 0.0060)]
    m.merge(sh.extrude_x(body, (), 0.0170, chamfer_bevel(0.0009), "attachment"))
    m.merge(sh.box(-0.0035, BODY_TOP - 0.0006, 0.0080, 0.0035, BODY_TOP + 0.0018, 0.0140, 0.0006, "steel_dark"))  # emitter
    for z0, z1 in ((0.0020, 0.0070), (0.0090, 0.0140)):                                   # buttons (left)
        m.merge(sh.box(-0.0182, 0.0065, z0, -0.0168, 0.0095, z1, 0.0004, "steel_dark"))
    m.merge(sh.box(0.0166, 0.0060, -0.0030, 0.0184, 0.0102, 0.0130, 0.0005, "steel_dark"))  # battery tray (right)
    # the frame: outer skin, the window's inner walls (flared forward), the end faces
    inner = sh.rounded_rect(-RECT_W / 2, cy - RECT_H / 2, RECT_W / 2, cy + RECT_H / 2, RECT_R, 3)
    in_rear, in_front = _flare(inner, b, cy, 1.0, 1.0), _flare(inner, D, cy, dx, dy)
    out0 = sh.rounded_rect(-RECT_W / 2 - 0.0020, BODY_TOP - 0.0008, RECT_W / 2 + 0.0020,
                           cy + RECT_H / 2 + 0.0024, (0.0010, 0.0010, 0.0040, 0.0040), 3)
    outD = _flare(out0, D, cy, dx * 0.5, dy * 0.5)
    c = 0.0005
    frame = sh.loft([_at_z(sh.offset_loop(outD, c), zg - D), _at_z(outD, zg - D + c),
                     _at_z(out0, zg + b - c), _at_z(sh.offset_loop(out0, c), zg + b)], "attachment",
                    cap_start=False, cap_end=False)
    frame.merge(sh.loft([_at_z(in_rear, zg + b), _at_z(inner, zg), _at_z(in_front, zg - D)], "interior",
                        cap_start=False, cap_end=False))
    sh.cap_polygon(frame, _at_z(sh.offset_loop(out0, c), zg + b), "attachment", holes3d=[_at_z(in_rear, zg + b)])
    sh.cap_polygon(frame, _at_z(sh.offset_loop(outD, c), zg - D), "attachment", holes3d=[_at_z(in_front, zg - D)],
                   flip=True)
    m.merge(frame)
    uv = [((x + RECT_W / 2) / RECT_W, (cy + RECT_H / 2 - y) / RECT_H) for x, y in inner]
    m.add_poly(_at_z(inner, zg), "glass", uvs=uv)
    p.meshes["attachment"] = m
    reticle = np.array([0.0, cy, zg + 0.0003])
    p.node("reticle", "attachment", reticle, pp.reticle_mesh(0.0016))
    p.node("aim", "attachment", reticle + np.array([0.0, 0.0, am.EYE_RELIEF]))
    return p


# ------------------------------------------------------------------------------------------- underbarrel
def _clamp(length=0.0360, depth=0.0085):
    """Rail clamp under the mount point: a block hanging below y = 0 with cross-slot lugs and a thumb screw."""
    am = _attach()
    m = sh.box(-0.0110, -depth, -length / 2, 0.0110, 0.0, length / 2, 0.0008, "attachment")
    for z in np.linspace(-length / 2 + 0.0060, length / 2 - 0.0060, 3):
        m.merge(sh.box(-0.0113, -0.0035, z - 0.0016, 0.0113, -0.0010, z + 0.0016, 0.0003, "attachment"))
    m.merge(am._cyl_axis(0.0038, 0.0045, (0.0132, -depth / 2, 0.0), "x", "steel_dark", 12))
    return m


def vertical_grip():
    """Vertical foregrip: a clamp and a tapered, finger-grooved grip hanging straight down ~0.10 m, end cap."""
    p = _attach().Part()
    m = _clamp()
    prof = [(0.0, 0.0)]
    for k in range(9):                       # radius down the grip (local z = down): taper with three finger swells
        y = 0.008 + k * 0.0115
        swell = 0.0008 * np.sin(np.pi * k / 2.6) ** 2 if k < 8 else 0.0
        prof.append((0.0158 - 0.0020 * k / 8 + swell, y))
    prof += [(0.0150, 0.1000), (0.0142, 0.1040), (0.0, 0.1040)]
    grip = sh.revolve(prof, 20, "attachment")
    grip = sh.place(grip, x_axis=(1, 0, 0), y_axis=(0, 0, 1), z_axis=(0, -1, 0))          # local z -> world -Y
    m.merge(grip.translated((0.0, -0.0020, 0.0)))
    cap = sh.revolve([(0.0, 0.0), (0.0138, 0.0), (0.0145, 0.0030), (0.0130, 0.0060), (0.0, 0.0060)], 20, "steel_dark")
    m.merge(sh.place(cap, x_axis=(1, 0, 0), y_axis=(0, 0, 1), z_axis=(0, -1, 0)).translated((0.0, -0.1040, 0.0)))
    p.meshes["attachment"] = m
    return p


def angled_grip():
    """Angled foregrip: a low wedge whose rear face slopes down and forward, a palm stop the hand pushes against."""
    p = _attach().Part()
    m = _clamp(0.0600, 0.0060)
    wedge = np.array([(-0.0360, -0.0050), (0.0400, -0.0050), (0.0400, -0.0110), (0.0330, -0.0170),
                      (-0.0040, -0.0400), (-0.0230, -0.0410), (-0.0360, -0.0300)])          # (z, y)
    m.merge(sh.extrude_x(wedge, (), 0.0115, chamfer_bevel(0.0018), "attachment"))
    for k in range(4):                                                                    # grip ribs on the ramp
        u = 0.18 + k * 0.18
        z = 0.0330 + (-0.0040 - 0.0330) * u
        y = -0.0170 + (-0.0400 + 0.0170) * u
        rib = np.array([(z - 0.0016, y + 0.0012), (z + 0.0016, y + 0.0010), (z + 0.0012, y - 0.0012),
                        (z - 0.0020, y - 0.0010)])
        m.merge(sh.extrude_x(rib, (), 0.0118, chamfer_bevel(0.0003), "steel_dark"))
    p.meshes["attachment"] = m
    return p


def bipod():
    """Folding bipod: clamp, a hinge block and two legs folded forward along -Z under the barrel, rubber feet."""
    p = _attach().Part()
    m = _clamp(0.0400, 0.0090)
    m.merge(sh.box(-0.0170, -0.0230, -0.0180, 0.0170, -0.0085, 0.0150, 0.0015, "attachment"))   # hinge yoke
    for s in (-1.0, 1.0):
        x = 0.0125 * s
        m.merge(_attach()._cyl_axis(0.0045, 0.0050, (x + 0.0040 * s, -0.0170, -0.0120), "x", "steel_dark", 12))
        upper = rod([(x, -0.0170, -0.0120), (x, -0.0175, -0.0700), (x * 1.04, -0.0180, -0.1150)], 0.0050, "attachment", 10)
        lower = rod([(x * 1.04, -0.0180, -0.1150), (x * 1.08, -0.0185, -0.1700)], 0.0040, "steel_dark", 10, smooth=False)
        m.merge(upper)
        m.merge(lower)
        m.merge(sh.box(x * 1.08 - 0.0050, -0.0235, -0.1840, x * 1.08 + 0.0050, -0.0135, -0.1690, 0.0020, "stock_pad"))
    p.meshes["attachment"] = m
    return p


MODELS = {"reddot_rect": reddot_rect, "vertical_grip": vertical_grip, "angled_grip": angled_grip, "bipod": bipod}
