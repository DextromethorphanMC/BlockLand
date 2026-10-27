"""Offline geometry quality settings; no dependency on weapon implementations."""
import json
import math
from pathlib import Path

SETTINGS = json.loads(Path(__file__).with_suffix(".json").read_text(encoding="utf-8"))
BUDGETS = SETTINGS["budgets"]
#: Edge rounding of the shared primitives (VIEWMODEL_SPEC section 53): radii, which turns count as corners, facets.
EDGES = SETTINGS["edges"]


def arc_segments(radius, minimum, sweep=2 * math.pi):
    """Bound circular chord error in metres; keep authored minimum and cap extra geometry.
    Full circles use multiples of four so opposing/cardinal samples remain symmetric.
    Call explicitly for curves, never for intentional polygonal nuts or keyed profiles."""
    radius = abs(float(radius))
    if radius == 0:
        return int(minimum)
    error = SETTINGS["curve_error_m"]
    angle = 2 * math.acos(max(-1.0, 1 - min(error / radius, 2.0)))
    count = max(int(minimum), math.ceil(abs(sweep) / angle))
    if math.isclose(abs(sweep), 2 * math.pi):
        count = 4 * math.ceil(count / 4)
    return max(int(minimum), min(SETTINGS["max_radial_segments"], count))


def fillet_segments(radius, sweep):
    """Facets of a rounded edge of `radius` turning `sweep` radians: the fillet chord error (`fillet_error_m`, a round
    a millimetre across is a pixel or two on screen) and no two facets more than `max_facet_deg` apart. Its faces are
    marked soft (meshkit.Mesh.soft), so the exporter shades them smooth up to `soft_deg`: the round reads as one
    curved surface with few facets."""
    radius = abs(float(radius))
    if radius == 0:
        return 1
    angle = 2 * math.acos(max(-1.0, 1 - min(EDGES["fillet_error_m"] / radius, 2.0)))
    return max(1, math.ceil(abs(sweep) / angle), math.ceil(math.degrees(abs(sweep)) / EDGES["max_facet_deg"]))


def edge_radius(feature_size):
    """The default rounding of a part's edges by its smallest dimension (metres): a fraction of it, kept in the range
    of a machined or moulded edge (a few tenths of a millimetre up to about a millimetre)."""
    return min(EDGES["max_radius_m"], max(EDGES["min_radius_m"], EDGES["radius_fraction"] * abs(feature_size)))


def is_sharp(material):
    """Materials whose edges stay as authored (cutting edges, points)."""
    return any(material == m or material.endswith("_" + m) for m in EDGES["sharp_materials"])


def refine_samples(values):
    """Preserve authored breakpoints, evaluate the analytic surface between them (not mesh subdivision)."""
    import numpy as np
    values = np.asarray(values, dtype=float)
    n = SETTINGS["blade_subdivisions"]
    return np.concatenate([np.linspace(a, b, n, endpoint=False) for a, b in zip(values[:-1], values[1:])]
                          + [values[-1:]])
