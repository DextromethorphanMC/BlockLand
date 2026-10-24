"""The bomb's geometry (VIEWMODEL_SPEC section 39): a demolition charge in the pattern players know from the classic
defusal games — three sticks of plastic explosive side by side, bound by two bands of duct tape, a keypad timer unit
strapped on top (a small LCD at the far end, a 3 x 4 keypad, a red status LED) and three wires from the unit down
into the charge. Original design at real scale.

Design frame (metres): the origin is the middle of the bundle; the sticks run along Z (the far end, with the LCD,
towards -Z), the keypad faces +Y, the bundle's width is X. Returns {node: (Mesh in design coordinates, node origin in
design coordinates)} plus named design points (`key_<n>` = the top centre of key n, row by row from the LCD;
`charm` unused; `top` = the middle of the keypad face)."""
import numpy as np

import shapes as sh
from equip_model import tube_along

V = lambda *a: np.array(a, float)

STICK_W, STICK_H, STICK_L = 0.031, 0.044, 0.180
STICK_X = (-0.032, 0.0, 0.032)
HALF_W = STICK_X[-1] + STICK_W / 2                     # 0.0475: half the bundle's width
TAPE_Z = (-0.052, 0.054)
TAPE_W, TAPE_T = 0.026, 0.0016
UNIT_X, UNIT_Y0, UNIT_Y1, UNIT_Z0, UNIT_Z1 = 0.034, STICK_H / 2 + TAPE_T, STICK_H / 2 + TAPE_T + 0.016, -0.052, 0.052
KEY_COLS = (-0.0155, 0.0, 0.0155)
KEY_ROWS = (-0.010, 0.006, 0.022, 0.038)
KEY_W, KEY_D, KEY_H = 0.0115, 0.0105, 0.0026
SCREEN = (-0.025, 0.025, -0.046, -0.022)               # x0, x1, z0, z1 on the unit's face
LED = V(0.027, UNIT_Y1 + 0.0016, -0.017)
BOX_LO = V(-HALF_W - TAPE_T, -STICK_H / 2 - TAPE_T, -STICK_L / 2)
BOX_HI = V(HALF_W + TAPE_T, UNIT_Y1 + KEY_H, STICK_L / 2)


def _sticks():
    m = None
    for i, x in enumerate(STICK_X):
        # the sticks are not machined: a little shorter / taller each, so the bundle reads as hand-packed
        dz = (0.004, -0.002, 0.003)[i]
        dy = (0.0005, 0.0, -0.0006)[i]
        s = sh.box(x - STICK_W / 2, -STICK_H / 2, -STICK_L / 2 + dz, x + STICK_W / 2, STICK_H / 2 + dy,
                   STICK_L / 2 - dz * 0.5, 0.0045, "explosive")
        m = s if m is None else m.merge(s)
    return m


def _tape():
    m = None
    for z in TAPE_Z:
        b = sh.box(-HALF_W - TAPE_T, -STICK_H / 2 - TAPE_T, z - TAPE_W / 2, HALF_W + TAPE_T, STICK_H / 2 + TAPE_T,
                   z + TAPE_W / 2, 0.0040, "tape")
        m = b if m is None else m.merge(b)
    # a loose end of the far band hanging down the side
    m.merge(sh.box(HALF_W + TAPE_T, -0.020, TAPE_Z[0] - 0.010, HALF_W + 2.2 * TAPE_T, 0.004, TAPE_Z[0] + 0.009, 0.0,
                   "tape"))
    return m


def _unit():
    m = sh.box(-UNIT_X, UNIT_Y0, UNIT_Z0, UNIT_X, UNIT_Y1, UNIT_Z1, 0.0030, "housing")
    # the LCD sits in a raised bezel; the keys stand on the face
    x0, x1, z0, z1 = SCREEN
    m.merge(sh.box(x0 - 0.003, UNIT_Y1, z0 - 0.003, x1 + 0.003, UNIT_Y1 + 0.0014, z1 + 0.003, 0.0, "housing"))
    m.merge(sh.box(x0, UNIT_Y1 + 0.0014, z0, x1, UNIT_Y1 + 0.0018, z1, 0.0, "screen"))
    for row in KEY_ROWS:
        for col in KEY_COLS:
            m.merge(sh.box(col - KEY_W / 2, UNIT_Y1, row - KEY_D / 2, col + KEY_W / 2, UNIT_Y1 + KEY_H, row + KEY_D / 2,
                           0.0009, "keys"))
    # the strap screws at the near corners and a battery pack on the near end
    m.merge(sh.box(-0.024, UNIT_Y0 + 0.002, UNIT_Z1, 0.024, UNIT_Y1 - 0.003, UNIT_Z1 + 0.009, 0.002, "housing"))
    return m


def _wires():
    out = None
    paths = (("wire_red", 0.034, -0.030, 1.0), ("wire_yellow", 0.034, -0.004, 1.0), ("wire_blue", -0.034, 0.020, -1.0))
    for mat, x, z, side in paths:
        y = UNIT_Y0 + 0.008
        p = [V(x, y, z), V(x + side * 0.008, y + 0.004, z + 0.004), V(x + side * 0.016, y - 0.004, z + 0.010),
             V(side * (HALF_W + 0.0035), y - 0.016, z + 0.015), V(side * (HALF_W + 0.0028), y - 0.030, z + 0.019),
             V(side * (HALF_W - 0.0015), y - 0.038, z + 0.022)]
        w = tube_along(p, 0.0017, mat, seg=6)
        out = w if out is None else out.merge(w)
    return out


def _led():
    return sh.box(LED[0] - 0.0022, LED[1] - 0.0016, LED[2] - 0.0022, LED[0] + 0.0022, LED[1] + 0.0016, LED[2] + 0.0022,
                  0.0008, "led")


def key_points():
    return {f"key_{r * 3 + c}": V(x, UNIT_Y1 + KEY_H, z) for r, z in enumerate(KEY_ROWS) for c, x in enumerate(KEY_COLS)}


def c4():
    body = _sticks().merge(_tape())
    parts = {"body": (body, V(0, 0, 0)), "keypad": (_unit(), V(0, 0, 0)), "wires": (_wires(), V(0, 0, 0)),
             "led": (_led(), LED.copy())}
    points = {"top": V(0.0, UNIT_Y1 + KEY_H, 0.0), "charm": V(HALF_W, -0.01, 0.06)}
    points.update(key_points())
    return parts, points


def sdf(p):
    """Signed distance to the bundle's box (with the unit on top), for wrapping the fingers."""
    c = 0.5 * (BOX_LO + BOX_HI)
    h = 0.5 * (BOX_HI - BOX_LO)
    q = np.abs(np.asarray(p, float) - c) - h
    return float(np.linalg.norm(np.maximum(q, 0.0)) + min(max(q[0], q[1], q[2]), 0.0))
