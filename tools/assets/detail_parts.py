"""Small detail parts the knife, attachment and equipment models share (VIEWMODEL_SPEC section 53: detail that reads
at viewmodel distance): screw and rivet heads, placed onto a surface by its normal. Not part of the shared modelling
core (shapes / meshkit): only builders of finished parts that the model scripts merge into their own meshes."""
import numpy as np

import shapes as sh


def frame(normal, up_hint=(0.0, 1.0, 0.0)):
    """A right-handed (x, y, z) frame whose z is `normal` (unit), x as close to perpendicular to `up_hint` as it gets."""
    z = np.asarray(normal, float)
    z = z / np.linalg.norm(z)
    up = np.asarray(up_hint, float)
    if abs(np.dot(up, z)) > 0.95:
        up = np.array([1.0, 0.0, 0.0]) if abs(z[0]) < 0.9 else np.array([0.0, 0.0, 1.0])
    x = np.cross(up, z)
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    return x, y, z


def on_surface(mesh, origin, normal, up_hint=(0.0, 1.0, 0.0)):
    """Places a part built along +Z (its face up the axis) at `origin`, +Z along `normal`."""
    x, y, z = frame(normal, up_hint)
    return sh.place(mesh, origin=origin, x_axis=x, y_axis=y, z_axis=z)


def socket_screw(r, material, height=None, seg=20):
    """A low socket-head screw: a domed head of radius r with a hexalobular socket, a shank sunk below z = 0."""
    h = 0.30 * r if height is None else height
    s = 0.38 * r
    return sh.revolve([(0.0, h * 0.35), (s, h * 0.35), (s * 1.25, h), (r * 0.82, h * 0.95), (r, h * 0.45), (r, -0.6 * r),
                       (0.0, -0.6 * r)], seg, material)


def rivet(r, material, height=None, seg=16):
    """A round rivet head (a low dome) of radius r, sunk below z = 0."""
    h = 0.45 * r if height is None else height
    return sh.revolve([(0.0, h), (r * 0.55, h * 0.88), (r * 0.88, h * 0.5), (r, h * 0.1), (r, -0.5 * r), (0.0, -0.5 * r)],
                      seg, material)
