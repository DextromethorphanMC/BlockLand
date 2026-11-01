"""Section 20 tables of the vehicles (pbr_apply.MODEL_MODULES, VIEWMODEL_SPEC section 57): one material name means
the same part on every vehicle (`paint` the main body colour, `paint_accent` its trim, `frame` powder-coated steel ...),
the set bound to it is the vehicle's own (red on the motorcycle, sand on the 4x4, olive on the tank, gelcoat and
antifouling on the boat). Lights, gauges, lenses and glass keep their glTF colours. AO: the hull and everything fixed to
it occlude each other; parts that move (wheels, the fork, the turret, the outboard, the tracks) only shade themselves."""

GLOW = {"headlight": (None, "none"), "taillight": (None, "none"), "indicator": (None, "none"),
        "gauge": (None, "none"), "reflector": (None, "none"), "lens": (None, "none"), "glass": (None, "none"),
        "afterburner": (None, "none"), "nav_green": (None, "none")}
COMMON = {"metal": ("common/steel_dark", "hardware"), "chrome": ("common/polished_steel", "hardware"),
          "rubber": ("common/rubber", "detail"), "tire": ("vehicle/tyre", "detail"),
          "plastic": ("vehicle/plastic", "detail"), "exhaust": ("vehicle/exhaust", "hardware"),
          "frame": ("vehicle/paint_black", "hardware"), "alu": ("vehicle/cast_aluminium", "detail"),
          "gunmetal": ("common/parkerized", "hardware"), "propeller": ("common/polished_steel", "hardware"),
          "fabric": ("vehicle/canvas", "detail"), "deck": ("vehicle/nonskid", "body"),
          "track": ("vehicle/track", "detail"), "armor": ("vehicle/paint_olive", "body")}
AO_DISTANCE = 0.08


def _table(**own):
    t = dict(COMMON)
    t.update(GLOW)
    t.update(own)
    return t


def models():
    tank_wheels = [[f"roadwheel_{s}{i}" for i in range(6)] + [f"sprocket_{s}", f"idler_{s}"] for s in "lr"]
    return {
        "vehicle/motorcycle": {
            "ao": {"mode": "together", "root": "body", "distance": AO_DISTANCE, "separate": [["fork"], ["wheel_r"]]},
            "materials": _table(paint=("vehicle/paint_red", "body"), paint_accent=("vehicle/paint_black", "body"),
                                seat=("vehicle/vinyl_black", "detail"), deck=("vehicle/gelcoat", "detail")),
        },
        "vehicle/offroad": {
            "ao": {"mode": "together", "root": "body", "distance": AO_DISTANCE,
                   "separate": [["steer_fl"], ["steer_fr"], ["wheel_rl"], ["wheel_rr"]]},
            "materials": _table(paint=("vehicle/paint_sand", "body"), paint_accent=("vehicle/paint_black", "body"),
                                seat=("vehicle/vinyl_black", "detail")),
        },
        "vehicle/speedboat": {
            "ao": {"mode": "together", "root": "body", "distance": AO_DISTANCE, "separate": [["outboard"]]},
            "materials": _table(gelcoat=("vehicle/gelcoat", "body"), paint=("vehicle/antifouling", "body"),
                                paint_accent=("vehicle/paint_navy", "body"), seat=("vehicle/vinyl_white", "detail")),
        },
        "vehicle/tank": {
            "ao": {"mode": "together", "root": "body", "distance": AO_DISTANCE,
                   "separate": [["turret"], ["track_l"], ["track_r"]] + tank_wheels},
            "materials": _table(),
        },
        "vehicle/fighter_jet": {
            "ao": {"mode": "together", "root": "body", "distance": AO_DISTANCE,
                   "separate": [["gear_0"], ["gear_1"], ["gear_2"], ["afterburner"]]},
            "materials": _table(paint=("vehicle/paint_jet_grey", "body"), paint_accent=("vehicle/paint_jet_dark", "body"),
                                seat=("vehicle/vinyl_black", "detail")),
        },
        "vehicle/helicopter": {
            "ao": {"mode": "together", "root": "body", "distance": AO_DISTANCE,
                   "separate": [["rotor_main"], ["rotor_tail"], ["chin_turret"]]},
            "materials": _table(paint=("vehicle/paint_heli", "body"), paint_accent=("vehicle/paint_black", "body"),
                                seat=("vehicle/vinyl_black", "detail")),
        },
    }
