"""pbr_apply.py table of the SCAR-H (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES: the FDE
anodised upper (`upper` body, `handguard` handguard), the FDE polymer lower (body), grip and stock, black phosphated
barrel and steel parts, blued small parts, the black steel magazines and the brass (pbr_sets_scar.py and the common
sets)."""


def models():
    return {
        "gun/scar": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.026,
                   "separate": [["bolt"], ["bolt_catch"], ["trigger"], ["mag_standard"], ["mag_extended"]]},
            "materials": {
                "upper": ("scar/anodized", "body"),
                "handguard": ("scar/anodized", "handguard"),
                "lower": ("scar/polymer", "body"),
                "grip": ("scar/grip", "grip"),
                "stock": ("scar/polymer", "stock"),
                "stock_pad": ("common/rubber", "none"),
                "barrel": ("scar/barrel", "barrel"),
                "steel": ("scar/barrel", "detail"),
                "steel_dark": ("common/steel_dark", "detail"),
                "mag": ("scar/mag", "magazine"),
                "mag_base": ("scar/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
