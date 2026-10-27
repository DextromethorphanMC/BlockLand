"""Drum magazines (VIEWMODEL_SPEC section 56): the `mag_drum` option of the guns whose magazine slot takes one, built in
each gun's magazine space (+Y up into the gun, -Z front) on top of its standard magazine, which stays the drum's neck
(the feed tower that goes into the well, so the drum seats exactly as the box magazine does). Two kinds:

* `side_drum` - one drum hung under the neck, its axis across the gun (X): the pistol drums (a Glock-style 50-round
  drum) and the Uzi's. A fairing joins the neck to the drum's top; rounded rims, slightly dished covers with a raised hub,
  rivets round both covers, the winding knob with its fold-out key on the right, round-count windows (brass showing)
  on the left.
* `twin_drum` - two drums side by side under a central bridge, their axes along the bore (Z): the MP5's C-mag style
  100-round double drum.

And `drum_clips`: the reload clips re-baked for a drum (`reload_drum`, `reload_empty_drum`): the support hand's holds
moved off the box magazine's base onto the drum (the box magazine's palm-slap point lies inside the drum). The runtime
plays them for a magazine with `clipSuffix: "_drum"` (guns.json). Not part of the shared modelling core."""
import copy
import contextlib

import numpy as np

import attach_details as ad
import detail_parts as dp
import shapes as sh
from meshkit import Mesh


# ------------------------------------------------------------------------------------------- geometry
def _bounds(mesh):
    v = np.asarray(mesh.v, float)
    return v.min(0), v.max(0)


def _rrect_ring(hw, hd, y, zc, r, n=8):
    """A rounded rectangle (half width hw along X, half depth hd along Z) at height y, as a ring (x, y, z)."""
    loop = sh.rounded_rect(-hw, zc - hd, hw, zc + hd, min(r, 0.95 * min(hw, hd)), n)
    loop = sh.ccw(loop)
    return np.stack([loop[:, 0], np.full(len(loop), y), loop[:, 1]], 1)


def _drum_body(R, W, body):
    """A drum along local Z centred at the origin: rounded rims, covers dished a little inside a raised edge, a hub."""
    h = W / 2
    prof = [(0.0, -h - 0.0026), (0.16 * R, -h - 0.0026), (0.19 * R, -h - 0.0006), (0.70 * R, -h + 0.0006),
            (0.90 * R, -h - 0.0004), (R, -h + 0.0040), (R, h - 0.0040), (0.90 * R, h + 0.0004),
            (0.70 * R, h - 0.0006), (0.19 * R, h + 0.0006), (0.16 * R, h + 0.0026), (0.0, h + 0.0026)]
    return sh.revolve(prof, 48, body, precision=True)


def _rim_band(R, z, width, height, material):
    """A raised band round the drum's rim at local z."""
    prof = [(R - 0.0006, z - width / 2), (R + height, z - width / 2 + 0.0004), (R + height, z + width / 2 - 0.0004),
            (R - 0.0006, z + width / 2)]
    m = sh.revolve(prof, 48, material, precision=True, rounding=False)
    m.soft = [True] * len(m.f)               # one round band: smooth shaded
    return m


def _cover_details(R, W, cover, brass, key_side, windows=6):
    """Rivets round both covers, the winding knob and key on `key_side` (+1 = the local +Z cover), the round-count
    windows on the other."""
    h = W / 2
    m = Mesh()
    for side in (-1, 1):
        for k in range(6):
            a = 2 * np.pi * (k + 0.5) / 6
            p = (0.80 * R * np.cos(a), 0.80 * R * np.sin(a), side * (h - 0.0002))
            m.merge(dp.on_surface(dp.rivet(0.0013, cover, seg=10), p, (0, 0, side)))
    # the winding knob on the hub with its folding key lying along a radius
    knob = ad.knurled_cap(0.0105 * R / 0.045, 0.0050, 18, 0.0005, cover)
    m.merge(dp.on_surface(knob, (0, 0, key_side * (h + 0.0018)), (0, 0, key_side)))
    zk = key_side * (h + 0.0068)
    m.merge(sh.box(-0.0030, -0.0030, zk - 0.0011, 0.0030, 0.62 * R, zk + 0.0011, 0.0008, cover))
    m.merge(dp.on_surface(dp.rivet(0.0024, cover), (0, 0.60 * R, zk + key_side * 0.0010), (0, 0, key_side)))
    # round-count windows on the other cover: brass showing through, low on the drum's front
    zw = -key_side * h
    for k in range(windows):
        a = np.radians(200 + k * 24)
        c = np.array([0.52 * R * np.cos(a), 0.52 * R * np.sin(a), zw - key_side * 0.0001])
        m.merge(sh.disc(c, (0, 0, -key_side), 0.0024, 16, cover))
        m.merge(sh.disc(c + np.array([0, 0, key_side * 0.0002]), (0, 0, -key_side), 0.0017, 16, brass))
    return m


def side_drum(neck, R, W, body="mag", cover="mag_base", brass="brass", z_shift=0.0, drop=0.004):
    """The standard magazine `neck` (mag space) with one drum hung under it, its axis across the gun.

    R, W: the drum's radius and width; z_shift moves it forward (-) or back (+) of the neck's centre; drop: how far its
    top rises above the neck's bottom (it swallows the base plate). Returns (mesh, info) with the drum's centre and
    bottom in mag space, for the reload holds."""
    lo, hi = _bounds(neck)
    zc = 0.5 * (lo[2] + hi[2]) + z_shift
    hw, hd = 0.5 * (hi[0] - lo[0]), 0.5 * (hi[2] - lo[2])
    y_bottom = lo[1]
    yc = y_bottom + drop - R
    m = Mesh()
    m.merge(neck)
    drum = _drum_body(R, W, body)
    for z in (-W / 2 + 0.0080, W / 2 - 0.0080):
        drum.merge(_rim_band(R, z, 0.0032, 0.0008, cover))
    drum.merge(_cover_details(R, W, cover, brass, key_side=-1))
    # local Z (the drum's axis) along the gun's X: local +Z cover = the gun's left side, the key on the right
    m.merge(sh.place(drum, origin=(0.0, yc, zc), x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(-1, 0, 0)))
    # the fairing from the neck into the drum's top
    rings = [_rrect_ring(hw * 0.98, hd * 0.98, y_bottom + 0.012, zc - z_shift, 0.0025),
             _rrect_ring(hw + 0.0010, hd + 0.0012, y_bottom + 0.004, zc - 0.5 * z_shift, 0.0030),
             _rrect_ring(hw + 0.0045, hd + 0.0060, y_bottom - 0.004, zc, 0.0045),
             _rrect_ring(min(0.46 * W, hw + 0.010), hd + 0.012, yc + 0.80 * R, zc, 0.0060)]
    m.merge(sh.loft(rings, body, rounding=False, soft_bands=(0, 1, 2)))
    return m, {"centre": np.array([0.0, yc, zc]), "bottom": yc - R, "half_width": W / 2, "radius": R}


def twin_drum(neck, R, L, gap=0.004, body="mag", cover="mag_base", brass="brass", drop=0.010, z_shift=0.0):
    """The standard magazine `neck` with two drums side by side under a bridge (C-mag style), their axes along Z.

    R, L: each drum's radius and length; gap: between the drums. Returns (mesh, info) as side_drum."""
    lo, hi = _bounds(neck)
    zc = 0.5 * (lo[2] + hi[2]) + z_shift
    hw, hd = 0.5 * (hi[0] - lo[0]), 0.5 * (hi[2] - lo[2])
    y_bottom = lo[1]
    yc = y_bottom + drop - R
    xc = R + gap / 2
    m = Mesh()
    m.merge(neck)
    for side in (-1, 1):
        drum = _drum_body(R, L, body)
        for z in (-L / 2 + 0.0090, L / 2 - 0.0090):
            drum.merge(_rim_band(R, z, 0.0034, 0.0008, cover))
        drum.merge(_cover_details(R, L, cover, brass, key_side=1, windows=4))
        # local Z along the gun's -Z (front): the knob on each drum's front cover
        m.merge(sh.place(drum, origin=(side * xc, yc, zc), x_axis=(-1, 0, 0), y_axis=(0, 1, 0), z_axis=(0, 0, -1)))
    # the bridge joining the drums under the neck, and the fairing up into the neck
    m.merge(sh.box(-xc, yc + 0.20 * R, zc - 0.40 * L, xc, yc + 0.95 * R, zc + 0.40 * L, 0.0040, body))
    rings = [_rrect_ring(hw * 0.98, hd * 0.98, y_bottom + 0.012, zc - z_shift, 0.0025),
             _rrect_ring(hw + 0.0012, hd + 0.0014, y_bottom + 0.003, zc, 0.0030),
             _rrect_ring(hw + 0.0060, hd + 0.0070, yc + 0.95 * R, zc, 0.0050)]
    m.merge(sh.loft(rings, body, rounding=False, soft_bands=(0, 1)))
    return m, {"centre": np.array([0.0, yc, zc]), "bottom": yc - R, "half_width": xc + R, "radius": R}


# ---------------------------------------------------------------------------------------------- clips
class _PatchedPose:
    """A clip pose solved with the drum's holds in place (the rig reads its anchors when it solves)."""

    def __init__(self, pose, patch):
        self._pose, self._patch = pose, patch

    def solve(self, *args, **kwargs):
        with self._patch():
            return self._pose.solve(*args, **kwargs)

    def __getattr__(self, name):
        return getattr(self._pose, name)


def drum_clips(clips, anchors, holds, suffix="_drum", names=("reload", "reload_empty")):
    """The reload clips of `clips` re-baked with the rig's `anchors` dict entries replaced by `holds` (name -> the
    rig's anchor tuple) while they pose and solve, renamed with `suffix`."""

    @contextlib.contextmanager
    def patch():
        saved = {k: anchors[k] for k in holds}
        anchors.update(holds)
        try:
            yield
        finally:
            anchors.update(saved)

    out = []
    for clip in clips:
        if clip.name not in names:
            continue
        variant = copy.copy(clip)
        variant.name = clip.name + suffix
        original = clip._pose

        def pose(t, original=original):
            with patch():
                return _PatchedPose(original(t), patch)

        variant._pose = pose
        out.append(variant)
    return out


def p9_holds(rig, info):
    """The P9 rig family's (P9, USP, Desert Eagle) support-hand holds for a drum: the fresh drum carried by its left
    cover (palm flat on it, fingers round its front rim), the seating slap under the drum's bottom."""
    c, bottom = info["centre"], info["bottom"]
    return {
        "mag": ("mag", rig._wrap((0.0, c[1] + 0.25 * info["radius"], c[2]), (0.0, -0.15, -1.0), (0.0, 1.0, 0.0), "L",
                                 "mag", info["half_width"] + 0.004, 0.004)),
        "slap": ("mag", rig._anchor((0.0, bottom - 0.0187, c[2] + 0.010), (0.0, 0.0, -1.0), (-1.0, 0.0, 0.0), "L",
                                    "spread")),
    }


def trim(mesh, y_min):
    """The part of a magazine above y_min (mag space): faces with every corner at or above it. A drum seats right under
    the grip or the well, so a long box magazine's lower body gives way to the drum's fairing (it hides the cut)."""
    out = Mesh()
    v = np.asarray(mesh.v, float)
    keep = [i for i, f in enumerate(mesh.f) if np.all(v[list(f), 1] >= y_min)]
    used = sorted({j for i in keep for j in mesh.f[i]})
    remap = {j: k for k, j in enumerate(used)}
    out.add_vertices(v[used])
    soft = list(getattr(mesh, "soft", []) or []) + [False] * len(mesh.f)
    for i in keep:
        out.add_face([remap[j] for j in mesh.f[i]], mesh.m[i], mesh.uv[i], soft[i])
    return out


def uzi_holds(rig, info):
    """The Uzi rig's hold on a drum: the fresh drum carried by its left cover, fingers round its front rim."""
    c = info["centre"]
    return {"mag": ("mag", rig._wrap((0.0, c[1] + 0.20 * info["radius"], c[2]), (0.10, -0.25, -1.0), (0.0, 1.0, 0.35),
                                     "L", "uzi_mag", info["half_width"] + 0.004, 0.006))}


def mp5_holds(rig, info, length):
    """The MP5 rig's holds on a twin drum: the left drum by its outer side, the fresh one low by the drums' front
    covers, the seating push under the bridge."""
    c, bottom = info["centre"], info["bottom"]
    return {
        "mag": ("mag", rig._wrap((0.0, c[1] + 0.25 * info["radius"], c[2]), (0.0, -0.20, -1.0), (0.0, 1.0, 0.25), "L",
                                 "mp5_mag", info["half_width"] + 0.004, 0.004)),
        "mag_low": ("mag", rig._wrap((0.0, c[1], c[2]), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "mp5_mag",
                                     0.5 * length + 0.004, -0.018)),
        "slap": ("mag", rig._anchor((0.0, bottom - 0.0187, c[2] - 0.010), (0.0, -0.35, -1.0), (-1.0, 0.0, 0.0), "L",
                                    "spread")),
    }
