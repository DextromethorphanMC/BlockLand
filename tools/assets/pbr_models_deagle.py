"""pbr_apply table of the Desert Eagle (VIEWMODEL_SPEC sections 20 / 21 / 48), plugged into pbr_apply.MODELS (see
MODEL_MODULES there). The steel frame (`polymer` by the P9 contract's name) and the slide are the `body` region, the
fixed barrel `barrel`, the rubber wrap `grip`, the magazine `magazine`. Its finishes are shared sets: the P9's
nitrided slide on the slide and barrel, the USP's hard coat on the frame, the common rubber, the USP's magazine steel."""


def models():
    return {
        "gun/deagle": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.022,
                   "separate": [["barrel"], ["mag_standard"], ["mag_extended"]]},
            "materials": {
                "polymer": ("usp/slide", "body"),
                "grip": ("common/rubber", "grip"),
                "slide": ("p9/slide", "body"),
                "barrel": ("p9/slide", "barrel"),
                "steel_dark": ("common/steel_dark", "detail"),
                "sight_dots": (None, "none"),
                "mag": ("usp/mag", "magazine"),
                "mag_base": ("usp/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
