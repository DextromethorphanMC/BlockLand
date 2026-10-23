"""Section 20 / 21 tables of the section 35 attachments (pbr_apply.MODEL_MODULES): the rectangular red dot as the other
reflex sights, the underbarrel slot's grips and bipod in anodised black (region underbarrel), rubber feet never skinned."""


def models():
    under = lambda: {
        "ao": {"mode": "together", "distance": 0.012},
        "materials": {"attachment": ("common/anodized_black", "underbarrel"),
                      "steel_dark": ("common/steel_dark", "underbarrel"), "stock_pad": ("common/rubber", "none")},
    }
    return {
        "attachment/reddot_rect": {
            "ao": {"mode": "together", "distance": 0.012},
            "materials": {"attachment": ("common/anodized_black", "optic"), "steel_dark": ("common/steel_dark", "optic"),
                          "glass": (None, "none"), "reticle": (None, "none"), "interior": (None, "none")},
        },
        "attachment/vertical_grip": under(),
        "attachment/angled_grip": under(),
        "attachment/bipod": under(),
    }
