"""Offline geometry quality settings; no dependency on weapon implementations."""
import json
import math
from pathlib import Path

SETTINGS = json.loads(Path(__file__).with_suffix(".json").read_text(encoding="utf-8"))
BUDGETS = SETTINGS["budgets"]


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


def refine_samples(values):
    """Preserve authored breakpoints, evaluate the analytic surface between them (not mesh subdivision)."""
    import numpy as np
    values = np.asarray(values, dtype=float)
    n = SETTINGS["blade_subdivisions"]
    return np.concatenate([np.linspace(a, b, n, endpoint=False) for a, b in zip(values[:-1], values[1:])]
                          + [values[-1:]])
