"""Checks the vehicles' data against itself and against their models (tools/vehicles/VEHICLE_SPEC.md).

python -I tools/vehicles/check_vehicles.py

The game runs on data/blockland/vehicles/vehicles.json (seats, weapon pivots and muzzles, effect anchors); the GLBs in
models/vehicle/ are drawn with their nodes at the same points. For every vehicle: seat 0 is the driver, every weapon
has a seat, at most 6 seats and 2 weapons (what the entity syncs), sound keys are listed for every weapon; and, where
the vehicle's GLB exists, each named node sits within TOLERANCE of the point the data gives it (rest pose). Exit code 0
when everything matches; models not built yet are reported and skipped."""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "assets"))

from glbread import GLTF  # noqa: E402

RES = os.path.join(REPO, "mod", "src", "main", "resources")
DATA = os.path.join(RES, "data", "blockland", "vehicles", "vehicles.json")
TOLERANCE = 0.03                    # metres
MAX_SEATS, MAX_WEAPONS = 6, 2
KINDS = ("bike", "wheeled", "tracked", "boat", "jet", "heli")


def model_path(model):
    ns, path = model.split(":", 1)
    return os.path.join(RES, "assets", ns, *path.split("/"))


def check_data(vid, v, problems):
    seats, weapons = v.get("seats", []), v.get("weapons", [])
    if not seats or seats[0].get("role") != "driver":
        problems.append(f"{vid}: seat 0 must be the driver")
    if len(seats) > MAX_SEATS:
        problems.append(f"{vid}: {len(seats)} seats (at most {MAX_SEATS} are synced)")
    if len(weapons) > MAX_WEAPONS:
        problems.append(f"{vid}: {len(weapons)} weapons (at most {MAX_WEAPONS} are synced)")
    sounds = set(v.get("sounds", []))
    for w in weapons:
        if not 0 <= w.get("seat", -1) < len(seats):
            problems.append(f"{vid}: weapon {w.get('id')} has no seat {w.get('seat')}")
        for key in (f"fire_{w.get('id')}", f"reload_{w.get('id')}"):
            if key not in sounds:
                problems.append(f"{vid}: sound key {key} missing")
    if any(s.get("mount") == "turret" for s in seats) and not weapons:
        problems.append(f"{vid}: a turret seat needs weapon 0 (the turret)")
    # a seat fires at most one weapon with each key (attack, use)
    for seat in range(len(seats)):
        for trigger in ("attack", "use"):
            count = sum(1 for w in weapons if w.get("seat") == seat and w.get("trigger", "attack") == trigger)
            if count > 1:
                problems.append(f"{vid}: seat {seat} has {count} weapons on {trigger}")
    for w in weapons:
        if w.get("trigger", "attack") not in ("attack", "use"):
            problems.append(f"{vid}: weapon {w.get('id')} trigger {w.get('trigger')!r}")
    kind = v.get("movement", {}).get("kind")
    if kind not in KINDS:
        problems.append(f"{vid}: movement kind {kind!r} (one of {', '.join(KINDS)})")
    if kind in ("jet", "heli") and not seats[0].get("yawLimit", 0) >= 180:
        problems.append(f"{vid}: an aircraft's pilot looks all round (yawLimit 180)")
    if "engine" not in sounds:
        problems.append(f"{vid}: no engine sound key")


def expected_points(v):
    """Node name -> rest position in model space, from the data."""
    pts = {}
    weapons = v.get("weapons", [])
    turret = np.array(weapons[0]["pivot"], float) if weapons else np.zeros(3)
    for i, s in enumerate(v.get("seats", [])):
        p = np.array(s["pos"], float)
        pts[s.get("node", f"seat_{i}")] = turret + p if s.get("mount") == "turret" else p
    for w in weapons:
        pivot = np.array(w["pivot"], float) + (turret if w.get("mount") == "turret" else 0)
        pitch = pivot + np.array(w["pitchPivot"], float)
        muzzle = pitch + np.array(w["muzzleOffset"], float)
        if w.get("pivotYaw"):
            pts[w["pivotYaw"]] = pivot
        if w.get("pivotPitch") and w.get("pivotPitch") != w.get("pivotYaw"):
            pts[w["pivotPitch"]] = pitch
        if w.get("muzzle"):
            pts[w["muzzle"]] = muzzle
    fx = v.get("effects", {})
    for i, p in enumerate(fx.get("exhaust", [])):
        pts[f"exhaust_{i}"] = np.array(p, float)
    for i, p in enumerate(fx.get("headlights", [])):
        pts[f"headlight_{i}"] = np.array(p, float)
    if fx.get("wake"):
        pts["wake"] = np.array(fx["wake"], float)
    return pts


def check_model(vid, v, problems, notes):
    path = model_path(v["model"])
    if not os.path.exists(path):
        notes.append(f"{vid}: no model yet ({os.path.relpath(path, REPO)})")
        return
    g = GLTF(path)
    world = g.world_matrices()
    for name, want in expected_points(v).items():
        i = g.names.get(name)
        if i is None:
            problems.append(f"{vid}: node {name} missing from the model")
            continue
        have = world[i][:3, 3]
        d = float(np.linalg.norm(have - want))
        if d > TOLERANCE:
            problems.append(f"{vid}: node {name} at {np.round(have, 3).tolist()}, data says {np.round(want, 3).tolist()} ({d * 100:.1f} cm)")
    notes.append(f"{vid}: model nodes checked")


def main():
    data = json.load(open(DATA, encoding="utf-8"))["vehicles"]
    problems, notes = [], []
    for vid, v in data.items():
        check_data(vid, v, problems)
        check_model(vid, v, problems, notes)
    for n in notes:
        print("  " + n)
    if problems:
        print(f"FAILED ({len(problems)} problems):")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"OK: {len(data)} vehicles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
