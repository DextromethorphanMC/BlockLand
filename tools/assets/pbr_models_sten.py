"""pbr_apply.py table of the STEN Mk II (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES.
`steel` is split by region: the stock tube and butt strap (`steel_stock`, stock) and the trigger housing with its
guard and the grip bracket (`steel_grip`, grip: the STEN's lower, where the shooting hand sits); the wooden pistol
grip (`wood`, grip)."""


def models():
    return {
        "gun/sten": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.024,
                   "separate": [["bolt"], ["mag_standard"]]},
            "split": {
                "steel": [("steel_stock", ["stock"]), ("steel_grip", ["trigger_housing"])],
            },
            "materials": {
                "steel": ("sten/body", "body"),
                "steel_stock": ("sten/body", "stock"),
                "steel_grip": ("sten/body", "grip"),
                "barrel": ("sten/blued", "barrel"),
                "steel_dark": ("common/steel_dark", "detail"),
                "mag": ("sten/mag", "magazine"),
                "mag_base": ("sten/mag", "magazine"),
                "wood": ("sten/wood", "grip"),
                "brass": ("common/brass", "none"),
            },
        },
    }
