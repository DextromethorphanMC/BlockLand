"""pbr_apply.py table of the Thompson (VIEWMODEL_SPEC sections 20, 21 and 24), plugged in through MODEL_MODULES."""


def models():
    return {
        "gun/tommy": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.028,
                   "separate": [["bolt"], ["mag_stick"], ["mag_drum"]]},
            "split": {
                "wood": [("wood_grip", ["grip"])],
                "steel": [("steel_barrel", ["barrel", "muzzle_brake"])],
            },
            "materials": {
                "wood": ("tommy/walnut", "stock"),
                "wood_grip": ("tommy/walnut", "grip"),
                "steel": ("tommy/blued", "body"),
                "steel_barrel": ("common/parkerized", "barrel"),
                "steel_dark": ("common/steel_dark", "detail"),
                "mag": ("tommy/blued", "magazine"),
                "mag_base": ("common/steel_dark", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
