"""Section 20 table of the revive antenna (pbr_apply.MODEL_MODULES): the case and the radio head in olive polymer, the
control box and the rubber parts black, parkerized hardware, the mast's anodized stages, the whip black anodized; the
medic panel, the screen and the beacon keep their glTF colours (the screen and the lit beacon glow). Nothing moves:
the parts shade each other."""


def models():
    return {
        "block/revive_antenna": {
            "ao": {"mode": "together", "root": "antenna", "distance": 0.012},
            "materials": {
                "case": ("equipment/olive", "body"),
                "case_dark": ("common/anodized_black", "body"),
                "rubber": ("common/rubber", "body"),
                "hardware": ("common/parkerized", "hardware"),
                "mast": ("common/parkerized", "detail"),
                "head": ("equipment/olive", "detail"),
                "dish": ("equipment/olive", "detail"),
                "whip": ("common/anodized_black", "detail"),
                "panel_white": (None, "none"),
                "panel_red": (None, "none"),
                "screen": (None, "none"),
                "beacon": (None, "none"),
                "beacon_off": (None, "none"),
            },
        },
    }
