"""pbr_apply.py table of the P90 (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES. The
polymer is split by region (butt -> stock, grip module -> grip, forend -> handguard, receiver / housing -> body); the
translucent magazine shell keeps its own factors (BLEND: no set, no AO, it does not occlude the rounds inside)."""


def models():
    return {
        "gun/p90": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.024,
                   "separate": [["bolt"], ["trigger"], ["selector"], ["mag_standard"]]},
            "split": {
                "polymer": [("polymer_stock", ["butt"]), ("polymer_grip", ["frame"]), ("polymer_fore", ["forend"])],
            },
            "materials": {
                "polymer": ("p90/polymer", "body"),
                "polymer_stock": ("p90/polymer", "stock"),
                "polymer_grip": ("p90/grip", "grip"),
                "polymer_fore": ("p90/grip", "handguard"),
                "steel": ("common/anodized_black", "detail"),
                "steel_dark": ("common/steel_dark", "detail"),
                "barrel": ("p90/barrel", "barrel"),
                "mag": ("p90/mag", "magazine"),
                "mag_window": (None, "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
