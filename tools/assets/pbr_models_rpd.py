"""pbr_apply.py table of the RPD (VIEWMODEL_SPEC sections 20, 21 and 33), plugged in through MODEL_MODULES. The RPD
wears the AK's texture sets (walnut, blued steel, the magazine steel) and the common ones."""


def models():
    return {
        "gun/rpd": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.028,
                   "separate": [["bolt"], ["feed_cover"], ["belt"], ["mag_standard"], ["mag_extended"]]},
            "split": {
                "wood": [("wood_handguard", ["handguard"]), ("wood_grip", ["pistol_grip"])],
                "steel": [("steel_barrel", ["barrel", "muzzle_brake"]), ("steel_stock", ["stock"])],
                "steel_dark": [("steel_dark_barrel", ["front_sight", "muzzle_brake"])],
            },
            "materials": {
                "wood": ("ak47/wood", "stock"),
                "wood_handguard": ("ak47/wood", "handguard"),
                "wood_grip": ("ak47/wood", "grip"),
                "steel": ("ak47/steel", "body"),
                "steel_barrel": ("common/parkerized", "barrel"),
                "steel_stock": ("ak47/steel", "stock"),
                "steel_dark": ("common/steel_dark", "detail"),
                "steel_dark_barrel": ("common/steel_dark", "barrel"),
                "mag": ("ak47/mag", "magazine"),
                "mag_base": ("ak47/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
