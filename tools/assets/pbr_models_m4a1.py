"""pbr_apply.py table of the M4A1 (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES. Receivers
and rail in the hard-anodised set (`steel`, region body), the barrel group parkerised (`barrel`), small steel parts
`steel_dark` (detail), the polymer split per region (handguard / stock / grip), magazines, brass, the butt pad
rubber and the dark machined insides (`bore`, no set)."""


def models():
    return {
        "gun/m4a1": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.026,
                   "separate": [["bolt"], ["charging_handle"], ["mag_standard"], ["mag_extended"]]},
            "split": {
                "polymer": [("polymer_stock", ["stock"]), ("polymer_grip", ["pistol_grip"])],
            },
            "materials": {
                "steel": ("m4a1/receiver", "body"),
                "steel_dark": ("m4a1/small_parts", "detail"),
                "barrel": ("m4a1/phosphate", "barrel"),
                "polymer": ("m4a1/polymer", "handguard"),
                "polymer_stock": ("m4a1/polymer", "stock"),
                "polymer_grip": ("m4a1/grip", "grip"),
                "pad": ("common/rubber", "none"),
                "mag": ("m4a1/mag", "magazine"),
                "mag_base": ("m4a1/small_parts", "magazine"),
                "brass": ("common/brass", "none"),
                "bore": (None, "none"),
            },
        },
    }
