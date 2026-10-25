"""pbr_apply table of the hand cannon (VIEWMODEL_SPEC sections 20 / 21 / 43), plugged into pbr_apply.MODELS (see
MODEL_MODULES there). The steel receiver and frame (`polymer` by the P9 contract's name) and the top rail (`slide`) are
the `body` region, the olive aluminium tube `barrel`, the rubber grip `grip`, the round `magazine`. Its finishes are
shared sets: a parkerized receiver, an anodized rail, the equipment's olive paint on the tube and the projectile, the
P9's stippled grip, a blasted aluminium case, brass for the band and fuze."""


def models():
    return {
        "gun/handcannon": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.022,
                   "separate": [["barrel"], ["mag_standard"], ["mag_extended"]]},
            "materials": {
                "polymer": ("common/parkerized", "body"),
                "grip": ("p9/grip", "grip"),
                "slide": ("common/anodized_black", "body"),
                "barrel": ("equipment/olive", "barrel"),
                "steel_dark": ("common/steel_dark", "detail"),
                "sight_dots": (None, "none"),
                "mag": ("p9/barrel", "magazine"),
                "mag_base": ("equipment/olive", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
