"""build_all.py steps of section 57 (STEP_MODULES): `vehicle_<id>` builds models/vehicle/<id>.glb (gen_vehicles from the
vehicle's model script) and applies its texture sets and baked AO; `vehicle_sounds` the vehicles' sound files and
sounds.json entries (gen_vehicle_sounds); `vehicle_items` the spawn items' icons (gen_vehicle_items)."""

VEHICLES = ("motorcycle", "offroad", "speedboat", "tank", "fighter_jet", "helicopter")


def steps(path, apply_pbr):
    def one(vid):
        def build():
            import os
            import gen_vehicles
            out = path("models", "vehicle", f"{vid}.glb")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            tris = gen_vehicles.build(vid, out)
            print(f"{vid}.glb: {sum(tris.values())} triangles; per node {tris}")
            apply_pbr(f"vehicle/{vid}")
        return build

    def sounds():
        import gen_vehicle_sounds
        gen_vehicle_sounds.build(path)

    def items():
        import gen_vehicle_items
        gen_vehicle_items.build(path)

    out = {f"vehicle_{vid}": one(vid) for vid in VEHICLES}
    out["vehicle_sounds"] = sounds
    out["vehicle_items"] = items
    return out
