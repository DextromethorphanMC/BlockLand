"""pbr_apply.py table of the Uzi (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES.
Splits: the stock's steel (region stock), the grip panels' polymer (region grip; the handguard keeps `polymer`), the
knurled barrel nut and cocking knob (the knurl set). Baked AO: the parts that leave their place in clips (the
cocking knob, the magazines, the trigger) only occlude themselves."""


def models():
    return {
        "gun/uzi": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.024,
                   "separate": [["bolt"], ["trigger"], ["mag_standard"], ["mag_extended"]]},
            "split": {
                "steel": [("steel_stock", ["stock"])],
                "polymer": [("polymer_grip", ["grip"])],
                "barrel": [("barrel_knurl", ["barrel_nut"])],
                "steel_dark": [("steel_dark_knurl", ["bolt"])],
            },
            "materials": {
                "steel": ("uzi/parkerized", "body"),
                "steel_stock": ("uzi/parkerized", "stock"),
                "steel_dark": ("common/steel_dark", "detail"),
                "steel_dark_knurl": ("uzi/knurl", "detail"),
                "barrel": ("uzi/barrel", "barrel"),
                "barrel_knurl": ("uzi/knurl", "barrel"),
                "polymer": ("uzi/polymer", "handguard"),
                "polymer_grip": ("uzi/polymer", "grip"),
                "mag": ("uzi/mag", "magazine"),
                "mag_base": ("uzi/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
